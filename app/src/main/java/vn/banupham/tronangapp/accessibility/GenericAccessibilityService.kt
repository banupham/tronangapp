package vn.banupham.tronangapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
import vn.banupham.tronangapp.runtime.AppProfileLauncher
import vn.banupham.tronangapp.runtime.AutomationPlan
import vn.banupham.tronangapp.runtime.AutomationPlanScheduler
import vn.banupham.tronangapp.runtime.AutomationPlanStore
import vn.banupham.tronangapp.runtime.AutomationMode
import vn.banupham.tronangapp.runtime.ImageClickTiming
import vn.banupham.tronangapp.runtime.NodeSnapshot
import vn.banupham.tronangapp.runtime.SavedWorkflow
import vn.banupham.tronangapp.runtime.SavedWorkflowStore
import vn.banupham.tronangapp.runtime.WorkflowEngine
import vn.banupham.tronangapp.runtime.WorkflowStatus
import vn.banupham.tronangapp.ui.MainActivity
import vn.banupham.tronangapp.vision.ImageTargetRuntime
import vn.banupham.tronangapp.vision.ScreenCaptureService
import vn.banupham.tronangapp.receiver.PendingAutomationPlanStore

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
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val legacyRequestCounter = AtomicLong(0L)
    private val pendingPlanNames = ArrayDeque<String>()
    private val activePlanScripts = ArrayDeque<String>()
    private var activePlanName: String? = null
    private var activePlanStep = 0

    private var snapshotScheduled = false
    private var snapshotBurstStartedMs = 0L
    private var pendingEventLabel: String? = null
    private var autoCaptureConsentDeadlineMs = 0L

    @Volatile
    private var lastTreeScanDurationMs = 0L

    @Volatile
    private var lastTreeScanFinishedMs = 0L

    private val snapshotRunnable = Runnable {
        snapshotScheduled = false
        snapshotBurstStartedMs = 0L
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
        WorkflowEngine(
            service = this,
            onStatusChanged = { status ->
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
                if (status.state in TERMINAL_WORKFLOW_STATES) {
                    mainHandler.post { handlePlanWorkflowTerminal(status) }
                }
            },
            onImageClickTiming = { timing ->
                remoteSocket.send(imageClickTimingJson(timing))
            }
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ImageTargetRuntime.initialize(this)
        ImageTargetRuntime.onMatch = { match ->
            mainHandler.post {
                remoteSocket.send(imageMatchJson(match))
                workflowEngine.onImageMatched(match)
            }
        }
        remoteSocket.connectSaved()
        refreshSnapshot("service_connected")
        mainHandler.postDelayed({ launchAutoCaptureRequest() }, AUTO_CAPTURE_LAUNCH_DELAY_MS)
        consumePendingAutomationPlans()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (AutomationMode.paused) return
        maybeApproveAutoCaptureConsent(event)
        val label = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) }

        workflowEngine.onAccessibilitySource(event?.source)
        scheduleSnapshotRefresh(label)
    }

    fun armAutoCaptureConsent() {
        autoCaptureConsentDeadlineMs = SystemClock.elapsedRealtime() + AUTO_CAPTURE_CONSENT_WINDOW_MS
    }

    fun disarmAutoCaptureConsent() {
        autoCaptureConsentDeadlineMs = 0L
    }

    private fun launchAutoCaptureRequest() {
        if (ScreenCaptureService.running) return
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_AUTO_CAPTURE, true)
            })
        }
    }

    private fun maybeApproveAutoCaptureConsent(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (autoCaptureConsentDeadlineMs <= now) {
            autoCaptureConsentDeadlineMs = 0L
            return
        }
        if (event?.packageName?.toString() != SYSTEM_UI_PACKAGE) return
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("Trợ năng App").isEmpty()) return
        val positive = root.findAccessibilityNodeInfosByViewId("android:id/button1")
            .firstOrNull { it.isEnabled && it.isClickable }
            ?: return
        autoCaptureConsentDeadlineMs = 0L
        positive.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scheduleSnapshotRefresh(lastEvent: String?) {
        pendingEventLabel = lastEvent
        val now = SystemClock.elapsedRealtime()

        if (!snapshotScheduled) {
            snapshotScheduled = true
            snapshotBurstStartedMs = now
        }

        mainHandler.removeCallbacks(snapshotRunnable)
        val burstAge = (now - snapshotBurstStartedMs).coerceAtLeast(0L)
        val remainingUntilForced =
            (TREE_REFRESH_MAX_LATENCY_MS - burstAge).coerceAtLeast(0L)
        val delay = minOf(TREE_REFRESH_DEBOUNCE_MS, remainingUntilForced)
        mainHandler.postDelayed(snapshotRunnable, delay)
    }

    private fun deferSnapshotForRealtimeCommand() {
        if (!snapshotScheduled) return

        val now = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(snapshotRunnable)
        val burstAge = (now - snapshotBurstStartedMs).coerceAtLeast(0L)
        val remainingUntilForced =
            (TREE_REFRESH_MAX_LATENCY_MS - burstAge).coerceAtLeast(0L)
        val delay = minOf(TREE_REFRESH_COMMAND_GRACE_MS, remainingUntilForced)
        mainHandler.postDelayed(snapshotRunnable, delay)
    }

    private fun refreshSnapshot(lastEvent: String?): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return false
        val activePackage = root.packageName?.toString()
        val output = ArrayList<NodeSnapshot>()
        val newIndex = HashMap<String, MutableList<IndexedNode>>()
        val visibleValues = LinkedHashSet<String>()
        val readyValues = LinkedHashSet<String>()

        collectNode(
            node = root,
            parentKey = null,
            key = "0",
            output = output,
            index = newIndex,
            visibleValues = visibleValues,
            readyValues = readyValues,
            depth = 0
        )

        liveNodeIndex = newIndex.mapValues { it.value.toList() }
        AgentRuntime.update(
            packageName = activePackage,
            newNodes = output,
            generation = ++generation,
            lastEvent = lastEvent,
            normalizedVisibleValues = visibleValues,
            normalizedReadyValues = readyValues
        )

        val finishedAt = SystemClock.elapsedRealtime()
        lastTreeScanDurationMs = (finishedAt - startedAt).coerceAtLeast(0L)
        lastTreeScanFinishedMs = finishedAt
        return true
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        parentKey: String?,
        key: String,
        output: MutableList<NodeSnapshot>,
        index: MutableMap<String, MutableList<IndexedNode>>,
        visibleValues: MutableSet<String>,
        readyValues: MutableSet<String>,
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

            addToIndex(index, node.text?.toString(), node, fieldPriority = 0)?.let { normalized ->
                visibleValues += normalized
                if (node.isEnabled) readyValues += normalized
            }
            addToIndex(index, node.contentDescription?.toString(), node, fieldPriority = 1)?.let { normalized ->
                visibleValues += normalized
                if (node.isEnabled) readyValues += normalized
            }
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
                visibleValues = visibleValues,
                readyValues = readyValues,
                depth = depth + 1
            )
        }
    }

    private fun addToIndex(
        index: MutableMap<String, MutableList<IndexedNode>>,
        rawValue: String?,
        node: AccessibilityNodeInfo,
        fieldPriority: Int
    ): String? {
        val normalized = AgentRuntime.normalizeForMatch(rawValue.orEmpty())
        if (normalized.isBlank()) return null
        index.getOrPut(normalized) { ArrayList() }
            .add(IndexedNode(node, fieldPriority))
        return normalized
    }

    fun swipe(direction: String): Boolean =
        !AutomationMode.paused && dispatchSwipe(direction, callback = null)

    fun swipeForWorkflow(direction: String, onComplete: (Boolean) -> Unit): Boolean {
        if (AutomationMode.paused) return false
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

    fun swipeForWorkflow(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        onComplete: (Boolean) -> Unit
    ): Boolean {
        if (AutomationMode.paused) return false
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        if (
            startX !in 0 until width || endX !in 0 until width ||
            startY !in 0 until height || endY !in 0 until height
        ) return false

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
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
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            callback,
            null
        )
    }

    private fun dispatchSwipe(direction: String, callback: GestureResultCallback?): Boolean {
        val upward = direction.equals("up", ignoreCase = true)
        val downward = direction.equals("down", ignoreCase = true)
        val leftward = direction.equals("left", ignoreCase = true)
        val rightward = direction.equals("right", ignoreCase = true)
        if (!upward && !downward && !leftward && !rightward) return false

        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            when {
                upward -> {
                    moveTo(width * 0.50f, height * 0.75f)
                    lineTo(width * 0.50f, height * 0.30f)
                }

                downward -> {
                    moveTo(width * 0.50f, height * 0.30f)
                    lineTo(width * 0.50f, height * 0.75f)
                }

                leftward -> {
                    moveTo(width * 0.75f, height * 0.50f)
                    lineTo(width * 0.25f, height * 0.50f)
                }

                else -> {
                    moveTo(width * 0.25f, height * 0.50f)
                    lineTo(width * 0.75f, height * 0.50f)
                }
            }
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
        if (AutomationMode.paused) return false
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

    fun performSystemAction(action: String): Boolean =
        if (AutomationMode.paused) false else when (action.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents", "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }

    fun delayForWorkflow(delayMs: Long, onComplete: () -> Unit) {
        mainHandler.postDelayed(onComplete, delayMs.coerceAtLeast(0L))
    }

    fun clickText(requestedText: String): Boolean {
        if (AutomationMode.paused) return false
        val expected = AgentRuntime.normalizeForMatch(requestedText)
        if (expected.isBlank()) return false

        if (clickFromLiveIndex(expected)) return true

        val root = rootInActiveWindow ?: return false
        val candidates = ArrayList<ClickCandidate>()
        collectClickCandidates(root, expected, candidates, depth = 0)
        var gestureFallback: AccessibilityNodeInfo? = null
        for (candidate in candidates.sortedBy { it.priority }) {
            val target = clickableNode(candidate.node) ?: continue
            if (gestureFallback == null) gestureFallback = target
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return gestureFallback?.let(::tapNodeCenter) == true
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

    fun isTargetReady(requestedText: String): Boolean =
        !AutomationMode.paused && AgentRuntime.isReadyTarget(requestedText)

    fun isTargetReadyInSubtree(start: AccessibilityNodeInfo, requestedText: String): Boolean {
        if (AutomationMode.paused) return false
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
        if (AutomationMode.paused) return false
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
        if (AutomationMode.paused) return "automation_paused"
        if (!ScreenCaptureService.running) return "screen_capture_not_running"
        if (!ImageTargetRuntime.hasTarget(target)) return "image_target_not_registered"
        return if (ImageTargetRuntime.startWatch(target)) null else "image_watch_not_started"
    }

    fun cancelImageWatch() {
        ImageTargetRuntime.clearWatch()
    }

    fun displaySizeForWorkflow(): Pair<Int, Int> =
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    fun probeImage(target: String, callback: (Result<Boolean>) -> Unit): String? {
        if (AutomationMode.paused) return "automation_paused"
        if (!ScreenCaptureService.running) return "screen_capture_not_running"
        if (!ImageTargetRuntime.hasTarget(target)) return "image_target_not_registered"
        return if (
            ScreenCaptureService.probeImageTarget(target) { result ->
                callback(result.map { match -> match != null })
            }
        ) null else "screen_capture_not_running"
    }

    fun runWorkflow(script: String, requestId: String? = null): WorkflowStatus =
        if (AutomationMode.paused) {
            WorkflowStatus(state = "failed", error = "automation_paused", requestId = requestId)
        } else {
            workflowEngine.start(script, requestId)
        }

    fun stopWorkflow(): WorkflowStatus = workflowEngine.stop()

    fun workflowStatus(): WorkflowStatus = workflowEngine.status

    fun runAutomationPlan(name: String): Boolean {
        if (AutomationPlanStore.find(this, name) == null) return false
        if (pendingPlanNames.none { it.equals(name, true) } && !activePlanName.equals(name, true)) {
            pendingPlanNames.addLast(name)
        }
        maybeStartNextAutomationPlan()
        return true
    }

    fun consumePendingAutomationPlans() {
        PendingAutomationPlanStore.drain(this).forEach { name ->
            if (pendingPlanNames.none { it.equals(name, true) } && !activePlanName.equals(name, true)) {
                pendingPlanNames.addLast(name)
            }
        }
        maybeStartNextAutomationPlan()
    }

    private fun maybeStartNextAutomationPlan() {
        if (AutomationMode.paused || activePlanName != null) return
        if (workflowEngine.status.state !in TERMINAL_WORKFLOW_STATES + setOf("idle")) return
        while (pendingPlanNames.isNotEmpty()) {
            val name = pendingPlanNames.removeFirst()
            val plan = AutomationPlanStore.find(this, name) ?: continue
            val scripts = buildPlanScripts(plan)
            if (scripts == null) {
                sendAutomationPlanState(name, "failed", "saved_workflow_not_found")
                continue
            }
            activePlanName = plan.name
            activePlanStep = 0
            activePlanScripts.clear()
            activePlanScripts.addAll(scripts)
            sendAutomationPlanState(plan.name, "running", null)
            startNextAutomationPlanWorkflow()
            return
        }
    }

    private fun buildPlanScripts(plan: AutomationPlan): List<String>? {
        val workflows = SavedWorkflowStore.list(this).associateBy { it.name.lowercase() }
        return buildList {
            plan.items.forEach { item ->
                val workflow = workflows[item.workflowName.lowercase()] ?: return null
                repeat(item.repetitions) { add(workflow.compiledScript()) }
            }
        }
    }

    private fun startNextAutomationPlanWorkflow() {
        val name = activePlanName ?: return
        if (activePlanScripts.isEmpty()) {
            sendAutomationPlanState(name, "completed", null)
            activePlanName = null
            activePlanStep = 0
            maybeStartNextAutomationPlan()
            return
        }
        activePlanStep++
        workflowEngine.start(
            activePlanScripts.removeFirst(),
            "plan:${name}:${activePlanStep}:${SystemClock.elapsedRealtime()}"
        )
    }

    private fun handlePlanWorkflowTerminal(status: WorkflowStatus) {
        val name = activePlanName ?: run {
            maybeStartNextAutomationPlan()
            return
        }
        if (!status.requestId.orEmpty().startsWith("plan:$name:")) return
        if (status.state == "completed") {
            startNextAutomationPlanWorkflow()
        } else {
            activePlanScripts.clear()
            activePlanName = null
            sendAutomationPlanState(name, "failed", status.error ?: status.state)
            maybeStartNextAutomationPlan()
        }
    }

    private fun sendAutomationPlanState(name: String, state: String, error: String?) {
        remoteSocket.send(JSONObject().apply {
            put("type", "automation_plan")
            put("name", name)
            put("state", state)
            put("step", activePlanStep)
            put("remaining", activePlanScripts.size)
            put("error", error ?: JSONObject.NULL)
        }.toString())
    }

    fun connectSocket(url: String): Boolean = remoteSocket.connect(url, persist = true)

    fun disconnectSocket(clearSavedUrl: Boolean = true) {
        remoteSocket.disconnect(clearSavedUrl)
    }

    fun socketState(): String = remoteSocket.state

    fun socketUrl(): String? = remoteSocket.url

    fun openPackage(packageName: String, profileSerial: Long?): Boolean =
        !AutomationMode.paused && AppProfileLauncher.launch(this, packageName, profileSerial)

    fun setAutomationPaused(paused: Boolean) {
        if (AutomationMode.paused != paused) {
            AutomationMode.setPaused(paused)
            if (paused) {
                mainHandler.removeCallbacks(snapshotRunnable)
                snapshotScheduled = false
                snapshotBurstStartedMs = 0L
                ImageTargetRuntime.clearWatch()
                workflowEngine.stop()
            } else {
                refreshSnapshot("automation_resumed")
            }
        }
        ScreenCaptureService.applyPausedState(paused)
        sendAutomationMode()
    }

    private fun sendAutomationMode() {
        remoteSocket.send(JSONObject().apply {
            put("type", "automation_mode")
            put("state", if (AutomationMode.paused) "paused" else "active")
        }.toString())
    }

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

            "automation_status" -> sendAutomationMode()

            "automation_pause" -> setAutomationPaused(true)

            "automation_resume" -> setAutomationPaused(false)

            "stop" -> enqueueStop(requestIdFrom(json))

            "run", "workflow" -> {
                val requestId = requestIdFrom(json)
                val script = json.optString("script")
                if (script.isBlank()) {
                    remoteSocket.send(commandAckJson(requestId, "received"))
                    remoteSocket.send(errorJson("empty_workflow"))
                    remoteSocket.send(commandAckJson(requestId, "failed", "empty_workflow"))
                } else {
                    var scriptToRun = script
                    val saveAs = json.optString("save_as").trim()
                    if (saveAs.isNotBlank()) {
                        val workflow = savedWorkflowFromJson(json, saveAs, script)
                        val validation = runCatching { WorkflowEngine.parse(workflow.compiledScript()) }
                        if (validation.isFailure || !SavedWorkflowStore.save(this, workflow)) {
                            remoteSocket.send(commandAckJson(requestId, "failed", "workflow_save_failed"))
                            return
                        }
                        scriptToRun = workflow.compiledScript()
                    }
                    enqueueWorkflow(scriptToRun, requestId)
                }
            }

            "workflow_save" -> {
                val name = json.optString("name").trim()
                val script = json.optString("script").trim()
                if (name.isBlank() || script.isBlank()) {
                    remoteSocket.send(errorJson("workflow_save_requires_name_and_script"))
                } else {
                    val workflow = savedWorkflowFromJson(json, name, script)
                    val valid = runCatching { WorkflowEngine.parse(workflow.compiledScript()) }.isSuccess
                    val success = valid && SavedWorkflowStore.save(this, workflow)
                    remoteSocket.send(savedWorkflowResultJson("workflow_save", workflow, success))
                }
            }

            "workflow_list" -> sendSavedWorkflowList()

            "workflow_remove" -> {
                val name = json.optString("name").trim()
                val removed = name.isNotBlank() && SavedWorkflowStore.remove(this, name)
                remoteSocket.send(JSONObject().apply {
                    put("type", "workflow_remove")
                    put("name", name)
                    put("success", removed)
                }.toString())
            }

            "workflow_run_saved" -> {
                val requestId = requestIdFrom(json)
                val name = json.optString("name").trim()
                val workflow = SavedWorkflowStore.find(this, name)
                if (workflow == null) {
                    remoteSocket.send(commandAckJson(requestId, "received"))
                    remoteSocket.send(commandAckJson(requestId, "failed", "saved_workflow_not_found"))
                } else {
                    enqueueWorkflow(workflow.compiledScript(), requestId)
                }
            }

            "automation_plan_save", "plan_save" -> {
                val plan = AutomationPlanStore.parse(json)
                val workflowsExist = plan?.items?.all {
                    SavedWorkflowStore.find(this, it.workflowName) != null
                } == true
                val success = plan != null && workflowsExist &&
                    AutomationPlanStore.save(this, plan) && AutomationPlanScheduler.schedule(this, plan)
                remoteSocket.send(JSONObject().apply {
                    put("type", "automation_plan_save")
                    put("success", success)
                    put("name", plan?.name ?: json.optString("name"))
                    if (!workflowsExist) put("error", "saved_workflow_not_found")
                }.toString())
            }

            "automation_plan_list", "plan_list" -> sendAutomationPlanList()

            "automation_plan_remove", "plan_remove" -> {
                val name = json.optString("name").trim()
                AutomationPlanScheduler.cancel(this, name)
                val removed = name.isNotBlank() && AutomationPlanStore.remove(this, name)
                remoteSocket.send(JSONObject().apply {
                    put("type", "automation_plan_remove")
                    put("name", name)
                    put("success", removed)
                }.toString())
            }

            "automation_plan_enable", "plan_enable" -> {
                val name = json.optString("name").trim()
                val enabled = json.optBoolean("enabled", true)
                val plan = AutomationPlanStore.setEnabled(this, name, enabled)
                val success = plan != null && if (enabled) {
                    AutomationPlanScheduler.schedule(this, plan)
                } else {
                    AutomationPlanScheduler.cancel(this, name)
                    true
                }
                remoteSocket.send(JSONObject().apply {
                    put("type", "automation_plan_enable")
                    put("name", name)
                    put("enabled", enabled)
                    put("success", success)
                }.toString())
            }

            "automation_plan_run", "plan_run" -> {
                val name = json.optString("name").trim()
                val success = name.isNotBlank() && runAutomationPlan(name)
                remoteSocket.send(JSONObject().apply {
                    put("type", "automation_plan_run")
                    put("name", name)
                    put("success", success)
                }.toString())
            }

            "app_profile_list" -> {
                remoteSocket.send(JSONObject().apply {
                    put("type", "app_profile_list")
                    put("apps", JSONArray().apply {
                        AppProfileLauncher.listTargets(this@GenericAccessibilityService).forEach { target ->
                            put(JSONObject().apply {
                                put("label", target.label)
                                put("package_name", target.packageName)
                                put("profile_serial", target.profileSerial)
                                put("profile_label", target.profileLabel)
                            })
                        }
                    })
                }.toString())
            }

            "app_open" -> {
                val packageName = json.optString("package_name").trim()
                val rawProfile = json.opt("profile_serial")
                val profileText = rawProfile?.takeUnless { it === JSONObject.NULL }
                    ?.toString()?.takeIf { it.isNotBlank() }
                val profileSerial = profileText?.toLongOrNull()
                val validProfile = profileText == null || profileSerial != null
                val success = validProfile && packageName.isNotBlank() &&
                    openPackage(packageName, profileSerial)
                remoteSocket.send(JSONObject().apply {
                    put("type", "app_open")
                    put("success", success)
                    if (!validProfile) put("error", "invalid_profile_serial")
                    put("package_name", packageName)
                    put("profile_serial", profileSerial ?: JSONObject.NULL)
                }.toString())
            }

            "image_put" -> registerImageFromSocket(json)

            "image_capture_put" -> registerImageFromCurrentFrame(json)

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
                    put("capture_width", ScreenCaptureService.captureWidth)
                    put("capture_height", ScreenCaptureService.captureHeight)
                    put("capture_density_dpi", ScreenCaptureService.captureDensityDpi)
                    put("active_watch", ImageTargetRuntime.activeWatchName() ?: JSONObject.NULL)
                    put("targets", JSONArray(ImageTargetRuntime.targetNames()))
                }.toString())
            }

            "nodes", "node_list", "tree" -> sendNodesSnapshot(json)

            "capture_status" -> {
                remoteSocket.send(JSONObject().apply {
                    put("type", "capture_status")
                    put("running", ScreenCaptureService.running)
                    put("automation_state", if (AutomationMode.paused) "paused" else "active")
                    put("capture_width", ScreenCaptureService.captureWidth)
                    put("capture_height", ScreenCaptureService.captureHeight)
                    put("capture_density_dpi", ScreenCaptureService.captureDensityDpi)
                    put("targets", ImageTargetRuntime.targetCount())
                    put("active_watch", ImageTargetRuntime.activeWatchName() ?: JSONObject.NULL)
                }.toString())
            }

            "screen_stream_start" -> {
                if (AutomationMode.paused) {
                    remoteSocket.send(errorJson("automation_paused"))
                } else if (!ScreenCaptureService.running) {
                    remoteSocket.send(errorJson("screen_capture_not_running"))
                } else {
                    val fps = json.optInt("fps", 4).coerceIn(1, 12)
                    val width = json.optInt("width", 360).coerceIn(240, 720)
                    val quality = json.optInt("quality", 55).coerceIn(35, 80)
                    ScreenCaptureService.configureStream(true, fps, width, quality) {
                            encoded, frameWidth, frameHeight, capturedAt ->
                        remoteSocket.sendTransient(JSONObject().apply {
                            put("type", "screen_frame")
                            put("width", frameWidth)
                            put("height", frameHeight)
                            put("source_width", ScreenCaptureService.captureWidth)
                            put("source_height", ScreenCaptureService.captureHeight)
                            put("captured_ms", capturedAt)
                            put("jpeg", encoded)
                        }.toString())
                    }
                    remoteSocket.send(JSONObject().apply {
                        put("type", "screen_stream")
                        put("state", "started")
                        put("fps", fps)
                        put("width", width)
                        put("quality", quality)
                    }.toString())
                }
            }

            "screen_stream_stop" -> {
                ScreenCaptureService.configureStream(false)
                remoteSocket.send("{\"type\":\"screen_stream\",\"state\":\"stopped\"}")
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

    private fun enqueueWorkflow(script: String, requestId: String) {
        remoteSocket.send(commandAckJson(requestId, "received"))
        if (AutomationMode.paused) {
            remoteSocket.send(commandAckJson(requestId, "failed", "automation_paused"))
            return
        }
        mainHandler.postAtFrontOfQueue {
            deferSnapshotForRealtimeCommand()
            remoteSocket.send(commandAckJson(requestId, "started"))
            runWorkflow(script, requestId)
        }
    }

    private fun enqueueStop(requestId: String) {
        remoteSocket.send(commandAckJson(requestId, "received"))
        mainHandler.postAtFrontOfQueue {
            deferSnapshotForRealtimeCommand()
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

    private fun savedWorkflowFromJson(json: JSONObject, name: String, script: String): SavedWorkflow {
        val rawProfile = json.opt("profile_serial")
        val profileSerial = when {
            rawProfile == null || rawProfile === JSONObject.NULL || rawProfile.toString().isBlank() -> null
            else -> rawProfile.toString().toLongOrNull()
        }
        return SavedWorkflow(
            name = name,
            script = script,
            packageName = json.optString("package_name").trim().takeIf { it.isNotBlank() },
            profileSerial = profileSerial
        )
    }

    private fun savedWorkflowResultJson(
        type: String,
        workflow: SavedWorkflow,
        success: Boolean
    ): String = JSONObject().apply {
        put("type", type)
        put("success", success)
        put("name", workflow.name)
        put("script", workflow.script)
        put("package_name", workflow.packageName ?: JSONObject.NULL)
        put("profile_serial", workflow.profileSerial ?: JSONObject.NULL)
    }.toString()

    private fun sendSavedWorkflowList() {
        remoteSocket.send(JSONObject().apply {
            put("type", "workflow_list")
            put("workflows", JSONArray().apply {
                SavedWorkflowStore.list(this@GenericAccessibilityService).forEach { workflow ->
                    put(JSONObject(savedWorkflowResultJson("workflow", workflow, true)))
                }
            })
        }.toString())
    }

    private fun sendAutomationPlanList() {
        remoteSocket.send(JSONObject().apply {
            put("type", "automation_plan_list")
            put("plans", JSONArray().apply {
                AutomationPlanStore.list(this@GenericAccessibilityService).forEach { plan ->
                    put(AutomationPlanStore.toJson(plan))
                }
            })
        }.toString())
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

        backgroundExecutor.execute {
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

    private fun registerImageFromCurrentFrame(json: JSONObject) {
        val name = json.optString("name").trim()
        val template = json.optJSONObject("template")
        val roi = json.optJSONObject("roi")
        if (name.isBlank() || template == null || roi == null) {
            remoteSocket.send(errorJson("image_capture_requires_name_template_roi"))
            return
        }
        val started = ScreenCaptureService.registerImageTarget(
            name = name,
            templateLeft = template.optInt("left", -1),
            templateTop = template.optInt("top", -1),
            templateRight = template.optInt("right", -1),
            templateBottom = template.optInt("bottom", -1),
            roiLeft = roi.optInt("left", 0),
            roiTop = roi.optInt("top", 0),
            roiRight = roi.optInt("right", ScreenCaptureService.captureWidth),
            roiBottom = roi.optInt("bottom", ScreenCaptureService.captureHeight),
            threshold = json.optDouble("threshold", DEFAULT_IMAGE_THRESHOLD)
        ) { result ->
            val response = result.fold(
                onSuccess = { target ->
                    JSONObject().apply {
                        put("type", "image_put")
                        put("source", "screen_capture")
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
                onFailure = { error -> errorJson(error.message ?: "image_capture_failed") }
            )
            remoteSocket.send(response)
        }
        if (!started) remoteSocket.send(errorJson("screen_capture_not_running"))
    }

    private fun sendNodesSnapshot(request: JSONObject) {
        val limit = request.optInt("limit", DEFAULT_NODE_PAGE_SIZE)
            .coerceIn(1, MAX_NODE_PAGE_SIZE)
        val offset = request.optInt("offset", 0).coerceAtLeast(0)
        val filter = AgentRuntime.normalizeForMatch(request.optString("filter"))
        val requestId = request.opt("id")?.takeUnless { it === JSONObject.NULL }?.toString()

        backgroundExecutor.execute {
            val runtime = AgentRuntime.status
            val snapshot = AgentRuntime.nodes
            val filtered = if (filter.isBlank()) {
                snapshot
            } else {
                snapshot.filter { node ->
                    listOfNotNull(
                        node.text,
                        node.contentDescription,
                        node.viewId,
                        node.className
                    ).any { value -> AgentRuntime.normalizeForMatch(value).contains(filter) }
                }
            }
            val page = filtered.drop(offset).take(limit)
            val payload = JSONObject().apply {
                put("type", "nodes")
                put("request_id", requestId ?: JSONObject.NULL)
                put("package", runtime.packageName ?: JSONObject.NULL)
                put("generation", runtime.generation)
                put("total", filtered.size)
                put("offset", offset)
                put("returned", page.size)
                put("has_more", offset + page.size < filtered.size)
                put("nodes", JSONArray().apply {
                    page.forEach { node ->
                        put(JSONObject().apply {
                            put("key", node.key)
                            put("parent_key", node.parentKey ?: JSONObject.NULL)
                            put("text", node.text ?: JSONObject.NULL)
                            put("description", node.contentDescription ?: JSONObject.NULL)
                            put("view_id", node.viewId ?: JSONObject.NULL)
                            put("class", node.className ?: JSONObject.NULL)
                            put("left", node.left)
                            put("top", node.top)
                            put("right", node.right)
                            put("bottom", node.bottom)
                            put("enabled", node.enabled)
                            put("clickable", node.clickable)
                        })
                    }
                })
            }
            remoteSocket.send(payload.toString())
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
    ): String {
        val now = SystemClock.elapsedRealtime()
        return JSONObject().apply {
            put("type", "ack")
            put("id", requestId)
            put("state", state)
            put("phone_ms", now)
            put("last_tree_scan_ms", lastTreeScanDurationMs)
            put(
                "tree_scan_age_ms",
                if (lastTreeScanFinishedMs > 0L) now - lastTreeScanFinishedMs else JSONObject.NULL
            )
            if (!error.isNullOrBlank()) put("error", error)
        }.toString()
    }

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
        put("capture_width", ScreenCaptureService.captureWidth)
        put("capture_height", ScreenCaptureService.captureHeight)
        put("capture_density_dpi", ScreenCaptureService.captureDensityDpi)
        put("timestamp_ms", match.timestampMs)
    }.toString()

    private fun imageClickTimingJson(timing: ImageClickTiming): String = JSONObject().apply {
        put("type", "image_click_timing")
        put("request_id", timing.requestId ?: JSONObject.NULL)
        put("name", timing.name)
        put("score", timing.score)
        put("find_ms", timing.findMs)
        put("match_to_dispatch_ms", timing.matchToDispatchMs)
        put("gesture_ms", timing.gestureMs)
        put("match_to_click_ms", timing.matchToClickMs)
        put("total_ms", timing.totalMs)
        put("success", timing.success)
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

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val x = bounds.centerX().coerceIn(0, resources.displayMetrics.widthPixels - 1)
        val y = bounds.centerY().coerceIn(0, resources.displayMetrics.heightPixels - 1)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
            null,
            null
        )
    }

    override fun onInterrupt() {
        AgentRuntime.disconnect()
    }

    override fun onDestroy() {
        ScreenCaptureService.configureStream(false)
        if (instance === this) instance = null
        mainHandler.removeCallbacks(snapshotRunnable)
        snapshotScheduled = false
        snapshotBurstStartedMs = 0L
        ImageTargetRuntime.onMatch = null
        ImageTargetRuntime.clearWatch()
        backgroundExecutor.shutdownNow()
        remoteSocket.disconnect(clearSavedUrl = false)
        AgentRuntime.disconnect()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GenericAccessibilityService? = null
            private set

        private val TERMINAL_WORKFLOW_STATES = setOf("completed", "failed", "stopped", "cancelled")
        private const val SWIPE_DURATION_MS = 350L
        private const val TAP_DURATION_MS = 1L
        private const val MAX_NODES = 10_000
        private const val MAX_DEPTH = 100
        private const val MAX_CLICK_PARENT_DEPTH = 8
        private const val MAX_CLICK_CANDIDATES = 1_000
        private const val FAST_SOURCE_MAX_NODES = 96
        private const val FAST_SOURCE_MAX_CANDIDATES = 24
        private const val TREE_REFRESH_DEBOUNCE_MS = 70L
        private const val TREE_REFRESH_COMMAND_GRACE_MS = 90L
        private const val TREE_REFRESH_MAX_LATENCY_MS = 350L
        private const val DEFAULT_IMAGE_THRESHOLD = 0.90
        private const val DEFAULT_NODE_PAGE_SIZE = 200
        private const val MAX_NODE_PAGE_SIZE = 2_000
        private const val AUTO_CAPTURE_LAUNCH_DELAY_MS = 1_000L
        private const val AUTO_CAPTURE_CONSENT_WINDOW_MS = 10_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
