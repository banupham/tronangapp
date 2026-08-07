package vn.banupham.tronangapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import vn.banupham.tronangapp.remote.RemoteSocketClient
import vn.banupham.tronangapp.runtime.AgentRuntime
import vn.banupham.tronangapp.runtime.NodeSnapshot
import vn.banupham.tronangapp.runtime.WorkflowEngine
import vn.banupham.tronangapp.runtime.WorkflowStatus
import vn.banupham.tronangapp.vision.ImageTargetRuntime
import vn.banupham.tronangapp.vision.ScreenCaptureService

class GenericAccessibilityService : AccessibilityService() {
    private data class ClickCandidate(
        val node: AccessibilityNodeInfo,
        val priority: Int
    )

    private data class IndexedNode(
        val node: AccessibilityNodeInfo,
        val fieldPriority: Int
    )

    private var generation = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val legacyRequestCounter = AtomicLong(0L)

    private var snapshotScheduled = false
    private var pendingEventLabel: String? = null
    private val snapshotRunnable = Runnable {
        snapshotScheduled = false
        val label = pendingEventLabel
        if (refreshSnapshot(label)) {
            workflowEngine.onTreeUpdated()
        }
    }

    @Volatile
    private var liveNodeIndex: Map<String, List<IndexedNode>> = emptyMap()

    private val remoteSocket by lazy {
        RemoteSocketClient(this, ::handleRemoteCommand)
    }

    private val workflowEngine by lazy {
        WorkflowEngine(this) { status ->
            remoteSocket.send(workflowStatusJson(status))
            val requestId = status.requestId
            if (requestId != null && status.state in TERMINAL_WORKFLOW_STATES) {
                remoteSocket.send(
                    commandAckJson(
                        requestId = requestId,
                        state = status.state,
                        error = status.error
                    )
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ImageTargetRuntime.onMatch = { match ->
            mainHandler.post {
                remoteSocket.send(imageMatchJson(match))
                workflowEngine.onImageMatched(match)
            }
        }
        remoteSocket.connectSaved()
        refreshSnapshot("service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val label = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) }

        // Realtime path first. WAIT can react to event.source immediately and,
        // for WAIT -> CLICK of the same text, click without waiting for a full
        // Accessibility tree rebuild.
        workflowEngine.onAccessibilitySource(event?.source)

        // Full-tree indexing is useful for diagnostics and generic CLICK, but
        // rebuilding it for every event can starve socket commands. Coalesce
        // bursts and rebuild the latest tree at most once per short window.
        scheduleSnapshotRefresh(label)
    }

    private fun scheduleSnapshotRefresh(lastEvent: String?) {
        pendingEventLabel = lastEvent
        if (snapshotScheduled) return
        snapshotScheduled = true
        mainHandler.postDelayed(snapshotRunnable, TREE_REFRESH_COALESCE_MS)
    }

    private fun refreshSnapshot(lastEvent: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val activePackage = root.packageName?.toString()
        val output = ArrayList<NodeSnapshot>()
        val newIndex = HashMap<String, MutableList<IndexedNode>>()
        collectNode(
            node = root,
            parentKey = null,
            key = "0",
            output = output,
            index = newIndex,
            depth = 0
        )
        liveNodeIndex = newIndex.mapValues { it.value.toList() }
        AgentRuntime.update(
            packageName = activePackage,
            newNodes = output,
            generation = ++generation,
            lastEvent = lastEvent
        )
        return true
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        parentKey: String?,
        key: String,
        output: MutableList<NodeSnapshot>,
        index: MutableMap<String, MutableList<IndexedNode>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_NODES) return

        if (node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output += NodeSnapshot(
                key = key,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                enabled = node.isEnabled,
                clickable = node.isClickable,
                parentKey = parentKey
            )

            addToIndex(index, node.text?.toString(), node, fieldPriority = 0)
            addToIndex(index, node.contentDescription?.toString(), node, fieldPriority = 1)
        }

        for (childIndex in 0 until node.childCount) {
            if (output.size >= MAX_NODES) break
            val child = node.getChild(childIndex) ?: continue
            collectNode(
                node = child,
                parentKey = key,
                key = "$key.$childIndex",
                output = output,
                index = index,
                depth = depth + 1
            )
        }
    }

    private fun addToIndex(
        index: MutableMap<String, MutableList<IndexedNode>>,
        rawValue: String?,
        node: AccessibilityNodeInfo,
        fieldPriority: Int
    ) {
        val normalized = AgentRuntime.normalizeForMatch(rawValue.orEmpty())
        if (normalized.isBlank()) return
        index.getOrPut(normalized) { ArrayList() }
            .add(IndexedNode(node, fieldPriority))
    }

    fun swipe(direction: String): Boolean = dispatchSwipe(direction, callback = null)

    fun swipeForWorkflow(direction: String, onComplete: (Boolean) -> Unit): Boolean {
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete(false)
            }
        }
        return dispatchSwipe(direction, callback)
    }

    private fun dispatchSwipe(direction: String, callback: GestureResultCallback?): Boolean {
        val upward = direction.equals("up", ignoreCase = true)
        val downward = direction.equals("down", ignoreCase = true)
        if (!upward && !downward) return false

        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val startY = if (upward) height * 0.75f else height * 0.30f
        val endY = if (upward) height * 0.30f else height * 0.75f
        val path = Path().apply {
            moveTo(width * 0.50f, startY)
            lineTo(width * 0.50f, endY)
        }

        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
                .build(),
            callback,
            null
        )
    }

    fun tapForWorkflow(x: Int, y: Int, onComplete: (Boolean) -> Unit): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete(false)
            }
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
            callback,
            null
        )
    }

    fun performSystemAction(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents", "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    fun delayForWorkflow(delayMs: Long, onComplete: () -> Unit) {
        mainHandler.postDelayed(onComplete, delayMs.coerceAtLeast(0L))
    }

    fun clickText(requestedText: String): Boolean {
        val expected = AgentRuntime.normalizeForMatch(requestedText)
        if (expected.isBlank()) return false

        // Fast path: use the most recently indexed Accessibility tree.
        if (clickFromLiveIndex(expected)) return true

        // Fallback for a stale index or a target that appeared since the last
        // coalesced snapshot.
        val root = rootInActiveWindow ?: return false
        val candidates = ArrayList<ClickCandidate>()
        collectClickCandidates(root, expected, candidates, depth = 0)
        for (candidate in candidates.sortedBy { it.priority }) {
            val target = clickableNode(candidate.node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun clickFromLiveIndex(expected: String): Boolean {
        val snapshot = liveNodeIndex
        val exact = snapshot[expected].orEmpty().sortedBy { it.fieldPriority }
        for (candidate in exact) {
            val target = clickableNode(candidate.node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }

        val contains = snapshot.asSequence()
            .filter { (value, _) -> value.contains(expected) }
            .flatMap { (_, nodes) -> nodes.asSequence() }
            .sortedBy { it.fieldPriority }
        for (candidate in contains) {
            val target = clickableNode(candidate.node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun isTargetReady(requestedText: String): Boolean = AgentRuntime.isReadyTarget(requestedText)

    fun isTargetReadyInSubtree(start: AccessibilityNodeInfo, requestedText: String): Boolean {
        val expected = AgentRuntime.normalizeForMatch(requestedText)
        if (expected.isBlank()) return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(start)
        var inspected = 0
        while (queue.isNotEmpty() && inspected < FAST_SOURCE_MAX_NODES) {
            val node = queue.removeFirst()
            inspected++
            if (node.isVisibleToUser && node.isEnabled && nodeMatches(node, expected)) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        return false
    }

    fun clickTextInSubtree(start: AccessibilityNodeInfo, requestedText: String): Boolean {
        val expected = AgentRuntime.normalizeForMatch(requestedText)
        if (expected.isBlank()) return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<ClickCandidate>()
        queue.add(start)
        var inspected = 0

        while (
            queue.isNotEmpty() &&
            inspected < FAST_SOURCE_MAX_NODES &&
            candidates.size < FAST_SOURCE_MAX_CANDIDATES
        ) {
            val node = queue.removeFirst()
            inspected++
            if (node.isVisibleToUser && node.isEnabled) {
                val priority = matchPriority(node, expected)
                if (priority != null) candidates += ClickCandidate(node, priority)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }

        for (candidate in candidates.sortedBy { it.priority }) {
            val target = clickableNode(candidate.node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, expected: String): Boolean =
        matchPriority(node, expected) != null

    private fun matchPriority(node: AccessibilityNodeInfo, expected: String): Int? {
        val text = AgentRuntime.normalizeForMatch(node.text?.toString().orEmpty())
        val description = AgentRuntime.normalizeForMatch(node.contentDescription?.toString().orEmpty())
        return when {
            text.isNotBlank() && text == expected -> 0
            description.isNotBlank() && description == expected -> 1
            text.isNotBlank() && text.contains(expected) -> 2
            description.isNotBlank() && description.contains(expected) -> 3
            else -> null
        }
    }

    fun startImageWatch(target: String): String? {
        if (!ScreenCaptureService.running) return "screen_capture_not_running"
        if (!ImageTargetRuntime.hasTarget(target)) return "image_target_not_registered"
        return if (ImageTargetRuntime.startWatch(target)) null else "image_watch_not_started"
    }

    fun cancelImageWatch() {
        ImageTargetRuntime.clearWatch()
    }

    fun runWorkflow(script: String, requestId: String? = null): WorkflowStatus =
        workflowEngine.start(script, requestId)

    fun stopWorkflow(): WorkflowStatus = workflowEngine.stop()

    fun workflowStatus(): WorkflowStatus = workflowEngine.status

    fun connectSocket(url: String): Boolean = remoteSocket.connect(url, persist = true)

    fun disconnectSocket(clearSavedUrl: Boolean = true) {
        remoteSocket.disconnect(clearSavedUrl)
    }

    fun socketState(): String = remoteSocket.state

    fun socketUrl(): String? = remoteSocket.url

    private fun handleRemoteCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        if (command.startsWith("{")) {
            handleJsonRemoteCommand(command)
            return
        }

        when {
            command.equals("PING", ignoreCase = true) -> {
                remoteSocket.send("{\"type\":\"pong\"}")
            }

            command.equals("STOP", ignoreCase = true) -> {
                enqueueStop(nextLegacyRequestId())
            }

            else -> enqueueWorkflow(command, nextLegacyRequestId())
        }
    }

    private fun handleJsonRemoteCommand(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrElse { error ->
            remoteSocket.send(errorJson("invalid_json", error.message))
            return
        }

        when (json.optString("cmd").trim().lowercase()) {
            "ping" -> remoteSocket.send("{\"type\":\"pong\"}")

            "stop" -> enqueueStop(requestIdFrom(json))

            "run", "workflow" -> {
                val requestId = requestIdFrom(json)
                val script = json.optString("script")
                if (script.isBlank()) {
                    remoteSocket.send(commandAckJson(requestId, "received"))
                    remoteSocket.send(errorJson("empty_workflow"))
                    remoteSocket.send(commandAckJson(requestId, "failed", "empty_workflow"))
                } else {
                    enqueueWorkflow(script, requestId)
                }
            }

            "image_put" -> registerImageFromSocket(json)

            "image_remove" -> {
                val name = json.optString("name")
                val removed = name.isNotBlank() && ImageTargetRuntime.remove(name)
                remoteSocket.send(JSONObject().apply {
                    put("type", "image_remove")
                    put("name", name)
                    put("success", removed)
                }.toString())
            }

            "image_list" -> {
                remoteSocket.send(JSONObject().apply {
                    put("type", "image_list")
                    put("capture_running", ScreenCaptureService.running)
                    put("active_watch", ImageTargetRuntime.activeWatchName() ?: JSONObject.NULL)
                    put("targets", JSONArray(ImageTargetRuntime.targetNames()))
                }.toString())
            }

            "capture_status" -> {
                remoteSocket.send(JSONObject().apply {
                    put("type", "capture_status")
                    put("running", ScreenCaptureService.running)
                    put("targets", ImageTargetRuntime.targetCount())
                    put("active_watch", ImageTargetRuntime.activeWatchName() ?: JSONObject.NULL)
                }.toString())
            }

            "image_find" -> {
                val requestId = requestIdFrom(json)
                val name = json.optString("name")
                if (name.isBlank()) {
                    remoteSocket.send(commandAckJson(requestId, "received"))
                    remoteSocket.send(errorJson("image_name_required"))
                    remoteSocket.send(commandAckJson(requestId, "failed", "image_name_required"))
                    return
                }
                val click = json.optBoolean("click", false)
                enqueueWorkflow((if (click) "CLICK_IMG:" else "WAIT_IMG:") + name, requestId)
            }

            else -> remoteSocket.send(errorJson("unsupported_socket_command"))
        }
    }

    /**
     * RECEIVED is emitted on OkHttp's WebSocket callback thread before the
     * command is queued on Android's main looper. STARTED is emitted from the
     * main looper immediately before WorkflowEngine starts. Their phone_ms
     * difference therefore exposes main-thread/tree queueing independently of
     * PC/phone clock synchronization.
     */
    private fun enqueueWorkflow(script: String, requestId: String) {
        remoteSocket.send(commandAckJson(requestId, "received"))
        mainHandler.post {
            remoteSocket.send(commandAckJson(requestId, "started"))
            runWorkflow(script, requestId)
        }
    }

    private fun enqueueStop(requestId: String) {
        remoteSocket.send(commandAckJson(requestId, "received"))
        mainHandler.post {
            remoteSocket.send(commandAckJson(requestId, "started"))
            stopWorkflow()
            remoteSocket.send(commandAckJson(requestId, "completed"))
        }
    }

    private fun requestIdFrom(json: JSONObject): String {
        val raw = json.opt("id")
        return if (raw == null || raw === JSONObject.NULL || raw.toString().isBlank()) {
            nextLegacyRequestId()
        } else {
            raw.toString()
        }
    }

    private fun nextLegacyRequestId(): String =
        "phone-${SystemClock.elapsedRealtime()}-${legacyRequestCounter.incrementAndGet()}"

    private fun registerImageFromSocket(json: JSONObject) {
        val name = json.optString("name")
        val encoded = when {
            json.has("png_base64") -> json.optString("png_base64")
            json.has("data") -> json.optString("data")
            else -> ""
        }
        if (name.isBlank() || encoded.isBlank()) {
            remoteSocket.send(errorJson("image_name_and_data_required"))
            return
        }

        val roi = json.optJSONObject("roi")
        val left = roi?.optInt("left", 0) ?: 0
        val top = roi?.optInt("top", 0) ?: 0
        val right = roi?.optInt("right", -1) ?: -1
        val bottom = roi?.optInt("bottom", -1) ?: -1
        val threshold = json.optDouble("threshold", DEFAULT_IMAGE_THRESHOLD)

        imageExecutor.execute {
            val result = ImageTargetRuntime.registerBase64(
                name = name,
                encodedImage = encoded,
                roiLeft = left,
                roiTop = top,
                roiRight = right,
                roiBottom = bottom,
                threshold = threshold
            )
            val response = result.fold(
                onSuccess = { target ->
                    JSONObject().apply {
                        put("type", "image_put")
                        put("success", true)
                        put("name", target.name)
                        put("width", target.width)
                        put("height", target.height)
                        put("threshold", target.threshold)
                        put("roi", JSONObject().apply {
                            put("left", target.roiLeft)
                            put("top", target.roiTop)
                            put("right", target.roiRight)
                            put("bottom", target.roiBottom)
                        })
                    }.toString()
                },
                onFailure = { error ->
                    errorJson(error.message ?: "image_register_failed")
                }
            )
            remoteSocket.send(response)
        }
    }

    private fun workflowStatusJson(status: WorkflowStatus): String = JSONObject().apply {
        put("type", "workflow")
        put("state", status.state)
        put("step", status.stepIndex)
        put("total", status.stepCount)
        put("command", status.command ?: JSONObject.NULL)
        put("target", status.target ?: JSONObject.NULL)
        put("error", status.error ?: JSONObject.NULL)
        put("request_id", status.requestId ?: JSONObject.NULL)
        put("phone_ms", SystemClock.elapsedRealtime())
    }.toString()

    private fun commandAckJson(
        requestId: String,
        state: String,
        error: String? = null
    ): String = JSONObject().apply {
        put("type", "ack")
        put("id", requestId)
        put("state", state)
        put("phone_ms", SystemClock.elapsedRealtime())
        if (!error.isNullOrBlank()) put("error", error)
    }.toString()

    private fun imageMatchJson(match: ImageTargetRuntime.ImageMatch): String = JSONObject().apply {
        put("type", "image_match")
        put("name", match.name)
        put("score", match.score)
        put("left", match.left)
        put("top", match.top)
        put("right", match.right)
        put("bottom", match.bottom)
        put("x", match.centerX)
        put("y", match.centerY)
        put("timestamp_ms", match.timestampMs)
    }.toString()

    private fun errorJson(error: String, detail: String? = null): String = JSONObject().apply {
        put("type", "error")
        put("error", error)
        if (!detail.isNullOrBlank()) put("detail", detail)
    }.toString()

    private fun collectClickCandidates(
        node: AccessibilityNodeInfo,
        expected: String,
        output: MutableList<ClickCandidate>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_CLICK_CANDIDATES) return

        if (node.isVisibleToUser) {
            val priority = matchPriority(node, expected)
            if (priority != null) output += ClickCandidate(node, priority)
        }

        for (index in 0 until node.childCount) {
            if (output.size >= MAX_CLICK_CANDIDATES) break
            val child = node.getChild(index) ?: continue
            collectClickCandidates(child, expected, output, depth + 1)
        }
    }

    private fun clickableNode(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        repeat(MAX_CLICK_PARENT_DEPTH) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    override fun onInterrupt() {
        AgentRuntime.disconnect()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.removeCallbacks(snapshotRunnable)
        snapshotScheduled = false
        ImageTargetRuntime.onMatch = null
        ImageTargetRuntime.clearWatch()
        imageExecutor.shutdownNow()
        remoteSocket.disconnect(clearSavedUrl = false)
        AgentRuntime.disconnect()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GenericAccessibilityService? = null
            private set

        private val TERMINAL_WORKFLOW_STATES = setOf("completed", "failed", "stopped", "cancelled")
        private const val TREE_REFRESH_COALESCE_MS = 40L
        private const val FAST_SOURCE_MAX_NODES = 128
        private const val FAST_SOURCE_MAX_CANDIDATES = 32
        private const val SWIPE_DURATION_MS = 350L
        private const val TAP_DURATION_MS = 1L
        private const val MAX_NODES = 10_000
        private const val MAX_DEPTH = 100
        private const val MAX_CLICK_PARENT_DEPTH = 8
        private const val MAX_CLICK_CANDIDATES = 1_000
        private const val DEFAULT_IMAGE_THRESHOLD = 0.90
    }
}
