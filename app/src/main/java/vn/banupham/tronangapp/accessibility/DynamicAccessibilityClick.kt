package vn.banupham.tronangapp.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Finds nodes whose contentDescription matches a dynamic pattern and clicks them.
 *
 * ACTION_CLICK is preferred when the node (or one of its parents) exposes a
 * clickable Accessibility action. Some apps expose the visual target but mark
 * the whole chain clickable=false; in that case we fall back to an Accessibility
 * gesture at the current center of the matched node bounds.
 *
 * [className] may optionally include a screen ROI using:
 *   android.view.ViewGroup|left,top,right,bottom
 *
 * The node's current center must be inside that ROI. This keeps dynamic selectors
 * fast while avoiding a similarly shaped description elsewhere on the screen.
 */
object DynamicAccessibilityClick {
    enum class StartResult {
        COMPLETED,
        STARTED,
        NOT_FOUND,
        NOT_STARTED
    }

    private data class Selector(
        val className: String?,
        val roi: Rect?
    )

    fun start(
        service: GenericAccessibilityService,
        className: String?,
        descriptionRegex: Regex,
        onComplete: (Boolean) -> Unit
    ): StartResult {
        val selector = parseSelector(className) ?: return StartResult.NOT_FOUND
        val root = service.rootInActiveWindow ?: return StartResult.NOT_FOUND
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)

        var inspected = 0
        while (
            queue.isNotEmpty() &&
            inspected < MAX_NODES &&
            candidates.size < MAX_CANDIDATES
        ) {
            val node = queue.removeFirst()
            inspected++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                classMatches(node, selector.className) &&
                descriptionMatches(node, descriptionRegex) &&
                roiMatches(node, selector.roi)
            ) {
                candidates += node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }

        if (candidates.isEmpty()) return StartResult.NOT_FOUND

        // Prefer semantic Accessibility click first.
        for (candidate in candidates) {
            val clickable = clickableNode(candidate) ?: continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return StartResult.COMPLETED
            }
        }

        // Accessibility metadata is sometimes incomplete. Use the actual current
        // node bounds as a safe dynamic coordinate fallback instead of hardcoding
        // a screen position.
        for (candidate in candidates) {
            val bounds = Rect().also(candidate::getBoundsInScreen)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val started = service.tapForWorkflow(bounds.centerX(), bounds.centerY(), onComplete)
            return if (started) StartResult.STARTED else StartResult.NOT_STARTED
        }

        return StartResult.NOT_FOUND
    }

    private fun parseSelector(raw: String?): Selector? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Selector(className = null, roi = null)

        val separator = value.indexOf('|')
        if (separator < 0) return Selector(className = value, roi = null)

        val className = value.substring(0, separator).trim()
        val roiText = value.substring(separator + 1).trim()
        if (className.isEmpty() || roiText.isEmpty()) return null

        val parts = roiText
            .split(',')
            .map(String::trim)
        if (parts.size != 4) return null

        val left = parts[0].toIntOrNull() ?: return null
        val top = parts[1].toIntOrNull() ?: return null
        val right = parts[2].toIntOrNull() ?: return null
        val bottom = parts[3].toIntOrNull() ?: return null
        if (left < 0 || top < 0 || right <= left || bottom <= top) return null

        return Selector(
            className = className,
            roi = Rect(left, top, right, bottom)
        )
    }

    private fun classMatches(node: AccessibilityNodeInfo, expectedClass: String?): Boolean {
        if (expectedClass.isNullOrBlank()) return true
        return node.className?.toString()?.equals(expectedClass.trim(), ignoreCase = true) == true
    }

    private fun descriptionMatches(node: AccessibilityNodeInfo, regex: Regex): Boolean {
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        return description.isNotEmpty() && regex.matches(description)
    }

    private fun roiMatches(node: AccessibilityNodeInfo, roi: Rect?): Boolean {
        if (roi == null) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return bounds.centerX() >= roi.left &&
            bounds.centerX() < roi.right &&
            bounds.centerY() >= roi.top &&
            bounds.centerY() < roi.bottom
    }

    private fun clickableNode(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        repeat(MAX_CLICK_PARENT_DEPTH) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private const val MAX_NODES = 10_000
    private const val MAX_CANDIDATES = 128
    private const val MAX_CLICK_PARENT_DEPTH = 8
}
