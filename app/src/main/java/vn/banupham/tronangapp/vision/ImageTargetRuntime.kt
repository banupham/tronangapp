package vn.banupham.tronangapp.vision

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import android.os.SystemClock
import android.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-process image target registry + lightweight ROI matcher.
 *
 * Templates are uploaded over the persistent WebSocket as PNG/JPEG base64.
 * Only one image target is watched at a time because a workflow executes one
 * step at a time. The matcher samples a small grid of template pixels and
 * scans only the configured ROI, keeping the hot path small.
 *
 * Repeated watches use the last successful position as a temporal hint. A
 * stable UI element can therefore be verified with one tiny probe instead of
 * rescanning the entire ROI on every /find call. If the element moved a few
 * pixels, a small local refinement is attempted before falling back to the
 * normal coarse ROI scan.
 */
object ImageTargetRuntime {
    data class ImageTarget(
        val name: String,
        val width: Int,
        val height: Int,
        val samples: List<Sample>,
        val roiLeft: Int,
        val roiTop: Int,
        val roiRight: Int,
        val roiBottom: Int,
        val threshold: Double
    )

    data class Sample(
        val x: Int,
        val y: Int,
        val red: Int,
        val green: Int,
        val blue: Int
    )

    data class ImageMatch(
        val name: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val centerX: Int,
        val centerY: Int,
        val score: Double,
        val timestampMs: Long
    )

    private val targets = ConcurrentHashMap<String, ImageTarget>()
    private val lastSuccessfulMatches = ConcurrentHashMap<String, ImageMatch>()
    private val persistenceLock = Any()

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var activeWatch: String? = null

    @Volatile
    var lastMatch: ImageMatch? = null
        private set

    @Volatile
    var onMatch: ((ImageMatch) -> Unit)? = null

    @Volatile
    var onWatchStarted: (() -> Unit)? = null

    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(persistenceLock) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
            loadPersistedTargets(prefs.getString(PREFERENCES_KEY_TARGETS, null))
            preferences = prefs
        }
    }

    fun targetCount(): Int = targets.size

    fun targetNames(): List<String> = targets.values.map { it.name }.sorted()

    fun hasTarget(name: String): Boolean = targets.containsKey(normalizeName(name))

    fun registerBase64(
        name: String,
        encodedImage: String,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        threshold: Double
    ): Result<ImageTarget> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "image_name_required" }

        val payload = encodedImage.substringAfter(',', encodedImage).trim()
        require(payload.length <= MAX_ENCODED_IMAGE_CHARS) { "image_payload_too_large" }
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        require(bytes.size <= MAX_IMAGE_BYTES) { "image_payload_too_large" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image_decode_failed" }
        require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_TEMPLATE_PIXELS) {
            "image_dimensions_too_large"
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("image_decode_failed")

        try {
            require(bitmap.width > 0 && bitmap.height > 0) { "invalid_template_size" }
            val samples = buildSamples(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
            require(samples.isNotEmpty()) { "image_has_no_usable_pixels" }
            val safeThreshold = if (threshold.isFinite()) threshold.coerceIn(0.50, 0.999) else 0.90

            val target = ImageTarget(
                name = cleanName,
                width = bitmap.width,
                height = bitmap.height,
                samples = samples,
                roiLeft = roiLeft,
                roiTop = roiTop,
                roiRight = roiRight,
                roiBottom = roiBottom,
                threshold = safeThreshold
            )
            val key = normalizeName(cleanName)
            targets[key] = target
            // A newly uploaded template/ROI may represent a different visual or
            // search area, so never reuse a position learned from the old one.
            lastSuccessfulMatches.remove(key)
            persistTargets()
            target
        } finally {
            bitmap.recycle()
        }
    }

    fun registerFromFrame(
        name: String,
        image: Image,
        screenWidth: Int,
        screenHeight: Int,
        templateLeft: Int,
        templateTop: Int,
        templateRight: Int,
        templateBottom: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        threshold: Double
    ): Result<ImageTarget> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "image_name_required" }
        require(
            templateLeft >= 0 && templateTop >= 0 &&
                templateRight <= screenWidth && templateBottom <= screenHeight &&
                templateRight > templateLeft && templateBottom > templateTop
        ) { "image_template_bounds_invalid" }

        val plane = image.planes.firstOrNull() ?: error("image_plane_missing")
        require(plane.pixelStride >= 3 && plane.rowStride > 0) { "image_plane_invalid" }
        val buffer = plane.buffer
        val templateWidth = templateRight - templateLeft
        val templateHeight = templateBottom - templateTop
        val samples = buildSamples(templateWidth, templateHeight) { x, y ->
            val offset = (templateTop + y) * plane.rowStride +
                (templateLeft + x) * plane.pixelStride
            require(offset >= 0 && offset + 2 < buffer.limit()) { "image_sample_out_of_bounds" }
            Color.rgb(
                buffer.get(offset).toInt() and 0xFF,
                buffer.get(offset + 1).toInt() and 0xFF,
                buffer.get(offset + 2).toInt() and 0xFF
            )
        }
        require(samples.isNotEmpty()) { "image_has_no_usable_pixels" }
        val safeThreshold = if (threshold.isFinite()) threshold.coerceIn(0.50, 0.999) else 0.90
        val target = ImageTarget(
            name = cleanName,
            width = templateWidth,
            height = templateHeight,
            samples = samples,
            roiLeft = roiLeft.coerceIn(0, screenWidth),
            roiTop = roiTop.coerceIn(0, screenHeight),
            roiRight = roiRight.coerceIn(0, screenWidth),
            roiBottom = roiBottom.coerceIn(0, screenHeight),
            threshold = safeThreshold
        )
        val key = normalizeName(cleanName)
        targets[key] = target
        lastSuccessfulMatches.remove(key)
        persistTargets()
        target
    }

    fun remove(name: String): Boolean {
        val key = normalizeName(name)
        if (activeWatch == key) activeWatch = null
        lastSuccessfulMatches.remove(key)
        val removed = targets.remove(key) != null
        if (removed) persistTargets()
        return removed
    }

    fun startWatch(name: String): Boolean {
        val key = normalizeName(name)
        if (!targets.containsKey(key)) return false
        lastMatch = null
        activeWatch = key
        onWatchStarted?.invoke()
        return true
    }

    fun clearWatch() {
        activeWatch = null
    }

    fun activeWatchName(): String? = activeWatch?.let { targets[it]?.name ?: it }

    fun processFrame(image: Image, screenWidth: Int, screenHeight: Int) {
        val key = activeWatch ?: return
        val target = targets[key] ?: run {
            activeWatch = null
            return
        }

        val match = findMatch(
            image = image,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            target = target,
            hint = lastSuccessfulMatches[key]
        ) ?: return

        // One match completes the current image wait. A following workflow step
        // may arm another watch immediately.
        activeWatch = null
        lastSuccessfulMatches[key] = match
        lastMatch = match
        onMatch?.invoke(match)
    }

    fun probeFrame(
        name: String,
        image: Image,
        screenWidth: Int,
        screenHeight: Int
    ): Result<ImageMatch?> = runCatching {
        val key = normalizeName(name)
        val target = targets[key] ?: error("image_target_not_registered")
        val match = findMatch(
            image = image,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            target = target,
            hint = lastSuccessfulMatches[key]
        )
        if (match != null) {
            lastSuccessfulMatches[key] = match
            lastMatch = match
        }
        match
    }

    private fun findMatch(
        image: Image,
        screenWidth: Int,
        screenHeight: Int,
        target: ImageTarget,
        hint: ImageMatch?
    ): ImageMatch? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 3 || rowStride <= 0) return null

        val roiLeft = target.roiLeft.coerceIn(0, screenWidth)
        val roiTop = target.roiTop.coerceIn(0, screenHeight)
        val roiRight = (if (target.roiRight > roiLeft) target.roiRight else screenWidth)
            .coerceIn(roiLeft, screenWidth)
        val roiBottom = (if (target.roiBottom > roiTop) target.roiBottom else screenHeight)
            .coerceIn(roiTop, screenHeight)

        val maxX = roiRight - target.width
        val maxY = roiBottom - target.height
        if (maxX < roiLeft || maxY < roiTop) return null

        val buffer = plane.buffer
        val maxDiff = target.samples.size.toLong() * 3L * 255L
        val allowedDiff = ((1.0 - target.threshold) * maxDiff).toLong().coerceAtLeast(1L)

        // Fast path for repeated /find calls. UI elements normally stay in the
        // same place, so verify the previously successful coordinate first.
        // This turns a full ROI scan into at most 64 sampled pixel comparisons.
        if (hint != null) {
            val hintedMatch = findNearHint(
                buffer = buffer,
                rowStride = rowStride,
                pixelStride = pixelStride,
                target = target,
                hint = hint,
                roiLeft = roiLeft,
                roiTop = roiTop,
                maxX = maxX,
                maxY = maxY,
                maxDiff = maxDiff,
                allowedDiff = allowedDiff
            )
            if (hintedMatch != null) return hintedMatch
        }

        var bestX = -1
        var bestY = -1
        var bestDiff = Long.MAX_VALUE

        // Coarse pass: stride two pixels. For a small known ROI this keeps the
        // scan fast while the refinement pass below recovers exact coordinates.
        var y = roiTop
        while (y <= maxY) {
            var x = roiLeft
            while (x <= maxX) {
                val diff = sampleDifference(
                    buffer = buffer,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    originX = x,
                    originY = y,
                    samples = target.samples,
                    abortAbove = min(bestDiff, allowedDiff * 2L)
                )
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestX = x
                    bestY = y
                }
                x += COARSE_STRIDE
            }
            y += COARSE_STRIDE
        }

        if (bestX < 0 || bestY < 0) return null

        // Refine within a tiny neighborhood at full pixel resolution.
        val refineLeft = max(roiLeft, bestX - COARSE_STRIDE)
        val refineTop = max(roiTop, bestY - COARSE_STRIDE)
        val refineRight = min(maxX, bestX + COARSE_STRIDE)
        val refineBottom = min(maxY, bestY + COARSE_STRIDE)

        y = refineTop
        while (y <= refineBottom) {
            var x = refineLeft
            while (x <= refineRight) {
                val diff = sampleDifference(
                    buffer = buffer,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    originX = x,
                    originY = y,
                    samples = target.samples,
                    abortAbove = bestDiff
                )
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestX = x
                    bestY = y
                }
                x++
            }
            y++
        }

        if (bestDiff > allowedDiff) return null
        return buildMatch(target, bestX, bestY, bestDiff, maxDiff)
    }

    private fun findNearHint(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        target: ImageTarget,
        hint: ImageMatch,
        roiLeft: Int,
        roiTop: Int,
        maxX: Int,
        maxY: Int,
        maxDiff: Long,
        allowedDiff: Long
    ): ImageMatch? {
        val hintX = hint.left
        val hintY = hint.top

        if (hintX in roiLeft..maxX && hintY in roiTop..maxY) {
            val directDiff = sampleDifference(
                buffer = buffer,
                rowStride = rowStride,
                pixelStride = pixelStride,
                originX = hintX,
                originY = hintY,
                samples = target.samples,
                abortAbove = allowedDiff
            )
            if (directDiff <= allowedDiff) {
                return buildMatch(target, hintX, hintY, directDiff, maxDiff)
            }
        }

        // The element may have shifted slightly because of animation, insets or
        // layout jitter. Search only a tiny neighborhood before doing the full ROI.
        val left = max(roiLeft, hintX - HINT_RADIUS)
        val top = max(roiTop, hintY - HINT_RADIUS)
        val right = min(maxX, hintX + HINT_RADIUS)
        val bottom = min(maxY, hintY + HINT_RADIUS)
        if (left > right || top > bottom) return null

        var bestX = -1
        var bestY = -1
        var bestDiff = allowedDiff + 1L

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                if (x != hintX || y != hintY) {
                    val diff = sampleDifference(
                        buffer = buffer,
                        rowStride = rowStride,
                        pixelStride = pixelStride,
                        originX = x,
                        originY = y,
                        samples = target.samples,
                        abortAbove = min(bestDiff, allowedDiff)
                    )
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestX = x
                        bestY = y
                    }
                }
                x++
            }
            y++
        }

        if (bestX < 0 || bestDiff > allowedDiff) return null
        return buildMatch(target, bestX, bestY, bestDiff, maxDiff)
    }

    private fun buildMatch(
        target: ImageTarget,
        x: Int,
        y: Int,
        diff: Long,
        maxDiff: Long
    ): ImageMatch {
        val score = 1.0 - (diff.toDouble() / maxDiff.toDouble())
        return ImageMatch(
            name = target.name,
            left = x,
            top = y,
            right = x + target.width,
            bottom = y + target.height,
            centerX = x + target.width / 2,
            centerY = y + target.height / 2,
            score = score,
            timestampMs = SystemClock.elapsedRealtime()
        )
    }

    private fun sampleDifference(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        originX: Int,
        originY: Int,
        samples: List<Sample>,
        abortAbove: Long
    ): Long {
        var total = 0L
        for (sample in samples) {
            val offset = (originY + sample.y) * rowStride + (originX + sample.x) * pixelStride
            if (offset < 0 || offset + 2 >= buffer.limit()) return Long.MAX_VALUE

            val red = buffer.get(offset).toInt() and 0xFF
            val green = buffer.get(offset + 1).toInt() and 0xFF
            val blue = buffer.get(offset + 2).toInt() and 0xFF

            total += kotlin.math.abs(red - sample.red)
            total += kotlin.math.abs(green - sample.green)
            total += kotlin.math.abs(blue - sample.blue)
            if (total > abortAbove) {
                return if (abortAbove == Long.MAX_VALUE) total else abortAbove + 1L
            }
        }
        return total
    }

    private fun buildSamples(
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int
    ): List<Sample> {
        val result = ArrayList<Sample>()
        val gridX = min(SAMPLE_GRID, width)
        val gridY = min(SAMPLE_GRID, height)

        for (gy in 0 until gridY) {
            val y = if (gridY == 1) 0 else gy * (height - 1) / (gridY - 1)
            for (gx in 0 until gridX) {
                val x = if (gridX == 1) 0 else gx * (width - 1) / (gridX - 1)
                val color = pixelAt(x, y)
                if (Color.alpha(color) < 32) continue
                result += Sample(
                    x = x,
                    y = y,
                    red = Color.red(color),
                    green = Color.green(color),
                    blue = Color.blue(color)
                )
            }
        }
        return result
    }

    private fun persistTargets() {
        val prefs = preferences ?: return
        val serialized = JSONArray().apply {
            targets.values.sortedBy { normalizeName(it.name) }.forEach { target ->
                put(JSONObject().apply {
                    put("name", target.name)
                    put("width", target.width)
                    put("height", target.height)
                    put("roi_left", target.roiLeft)
                    put("roi_top", target.roiTop)
                    put("roi_right", target.roiRight)
                    put("roi_bottom", target.roiBottom)
                    put("threshold", target.threshold)
                    put("samples", JSONArray().apply {
                        target.samples.forEach { sample ->
                            put(JSONArray().apply {
                                put(sample.x)
                                put(sample.y)
                                put(sample.red)
                                put(sample.green)
                                put(sample.blue)
                            })
                        }
                    })
                })
            }
        }.toString()
        prefs.edit().putString(PREFERENCES_KEY_TARGETS, serialized).apply()
    }

    private fun loadPersistedTargets(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        runCatching {
            val stored = JSONArray(serialized)
            for (index in 0 until stored.length()) {
                val item = stored.getJSONObject(index)
                val name = item.getString("name").trim()
                val width = item.getInt("width")
                val height = item.getInt("height")
                val storedSamples = item.getJSONArray("samples")
                require(name.isNotEmpty() && width > 0 && height > 0)
                require(storedSamples.length() in 1..(SAMPLE_GRID * SAMPLE_GRID))
                val samples = ArrayList<Sample>(storedSamples.length())
                for (sampleIndex in 0 until storedSamples.length()) {
                    val values = storedSamples.getJSONArray(sampleIndex)
                    val sample = Sample(
                        x = values.getInt(0),
                        y = values.getInt(1),
                        red = values.getInt(2),
                        green = values.getInt(3),
                        blue = values.getInt(4)
                    )
                    require(sample.x in 0 until width && sample.y in 0 until height)
                    require(sample.red in 0..255 && sample.green in 0..255 && sample.blue in 0..255)
                    samples += sample
                }
                val threshold = item.getDouble("threshold")
                require(threshold.isFinite())
                val target = ImageTarget(
                    name = name,
                    width = width,
                    height = height,
                    samples = samples,
                    roiLeft = item.getInt("roi_left"),
                    roiTop = item.getInt("roi_top"),
                    roiRight = item.getInt("roi_right"),
                    roiBottom = item.getInt("roi_bottom"),
                    threshold = threshold.coerceIn(0.50, 0.999)
                )
                targets[normalizeName(name)] = target
            }
        }
    }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.ROOT)

    private const val SAMPLE_GRID = 8
    private const val COARSE_STRIDE = 2
    private const val HINT_RADIUS = 4
    private const val MAX_ENCODED_IMAGE_CHARS = 12 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_TEMPLATE_PIXELS = 16_000_000L
    private const val PREFERENCES_NAME = "image_targets"
    private const val PREFERENCES_KEY_TARGETS = "targets_v1"
}
