package vn.banupham.tronangapp.runtime

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.random.Random
import vn.banupham.tronangapp.accessibility.DynamicAccessibilityClick
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.vision.ImageTargetRuntime

sealed class WorkflowStep {
    data class Click(val target: String) : WorkflowStep()
    data class ClickDescriptionRegex(
        val className: String?,
        val pattern: String,
        val label: String
    ) : WorkflowStep()
    data class Tap(val x: Int, val y: Int) : WorkflowStep()
    data class TapPercent(val xPercent: Double, val yPercent: Double) : WorkflowStep()
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long
    ) : WorkflowStep()
    data class Wait(val target: String, val timeoutSeconds: Double? = null) : WorkflowStep()
    data class Sleep(val seconds: Double) : WorkflowStep()
    data class RandomSleep(val minSeconds: Double, val maxSeconds: Double) : WorkflowStep()
    data class WaitCountdown(
        val minExtraSeconds: Double,
        val maxExtraSeconds: Double,
        val random: Boolean
    ) : WorkflowStep()
    data class SwipePercent(
        val startXPercent: Double,
        val startYPercent: Double,
        val endXPercent: Double,
        val endYPercent: Double,
        val durationMs: Long
    ) : WorkflowStep()
    data class WaitImage(val target: String) : WorkflowStep()
    data class WaitImageTimeout(val target: String, val timeoutSeconds: Double) : WorkflowStep()
    data class WaitAnyImage(
        val targets: List<String>,
        val timeoutSeconds: Double,
        val timeoutLabel: String?,
        var timeoutDestination: Int = -1
    ) : WorkflowStep()
    data class ClickImage(val target: String, val timeoutSeconds: Double? = null) : WorkflowStep()
    data class Checkpoint(val clear: Boolean) : WorkflowStep()
    data class RequireDevice(
        val requirements: Set<String>,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class SetVariable(val name: String, val value: String) : WorkflowStep()
    data class IncrementVariable(val name: String, val amount: Double) : WorkflowStep()
    data class IfVariable(
        val name: String,
        val operator: String,
        val expected: String,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class OnError(val label: String?, var destination: Int = -1) : WorkflowStep()
    data class OpenApp(val packageName: String, val profileSerial: Long?) : WorkflowStep()
    data class Label(val name: String) : WorkflowStep()
    data class Goto(val label: String, var destination: Int = -1) : WorkflowStep()
    data class IfVisible(
        val target: String,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class IfNotVisible(
        val target: String,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class IfImageVisible(
        val target: String,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class IfImageNotVisible(
        val target: String,
        val label: String,
        var destination: Int = -1
    ) : WorkflowStep()
    data class IfCountdownVisible(
        val label: String,
        val negated: Boolean,
        var destination: Int = -1
    ) : WorkflowStep()
    data class LoopStart(val count: Int, var endIndex: Int = -1) : WorkflowStep()
    data class LoopEnd(var startIndex: Int = -1) : WorkflowStep()
    data class Break(var startIndex: Int = -1, var endIndex: Int = -1) : WorkflowStep()
    data class Continue(var endIndex: Int = -1) : WorkflowStep()
    data object Up : WorkflowStep()
    data object Down : WorkflowStep()
    data object Left : WorkflowStep()
    data object Right : WorkflowStep()
    data object Back : WorkflowStep()
    data object Home : WorkflowStep()
    data object Recents : WorkflowStep()
}

data class WorkflowStatus(
    val state: String = "idle",
    val stepIndex: Int = -1,
    val stepCount: Int = 0,
    val command: String? = null,
    val target: String? = null,
    val error: String? = null,
    val requestId: String? = null
)

data class ImageClickTiming(
    val requestId: String?,
    val name: String,
    val score: Double,
    val findMs: Long,
    val matchToDispatchMs: Long,
    val gestureMs: Long,
    val matchToClickMs: Long,
    val totalMs: Long,
    val success: Boolean
)

class WorkflowEngine(
    private val service: GenericAccessibilityService,
    private val onStatusChanged: (WorkflowStatus) -> Unit = {},
    private val onImageClickTiming: (ImageClickTiming) -> Unit = {}
) {
    @Volatile
    var status: WorkflowStatus = WorkflowStatus()
        private set

    private var steps: List<WorkflowStep> = emptyList()
    private var index = 0
    private var actionInFlight = false
    private var executionId = 0L
    private var currentRequestId: String? = null
    private var executedStepCount = 0
    private var imageWatchStartedMs = 0L
    private val loopCounters = HashMap<Int, Int>()
    private val variables = HashMap<String, String>()
    private var errorDestination = -1
    private var currentScript = ""

    @Synchronized
    fun start(script: String, requestId: String? = null): WorkflowStatus {
        return startAt(script, requestId, 0)
    }

    @Synchronized
    fun startAt(script: String, requestId: String? = null, startIndex: Int = 0): WorkflowStatus {
        cancelCurrentForReplacementLocked()
        executionId++
        service.cancelImageWatch()
        currentRequestId = requestId
        currentScript = script

        val parsed = try {
            parse(script)
        } catch (error: IllegalArgumentException) {
            steps = emptyList()
            index = 0
            actionInFlight = false
            setStatus(
                WorkflowStatus(
                    state = "failed",
                    error = error.message ?: "invalid_workflow",
                    requestId = currentRequestId
                )
            )
            currentRequestId = null
            return status
        }

        if (parsed.isEmpty()) {
            steps = emptyList()
            index = 0
            actionInFlight = false
            setStatus(
                WorkflowStatus(
                    state = "failed",
                    error = "empty_workflow",
                    requestId = currentRequestId
                )
            )
            currentRequestId = null
            return status
        }

        steps = parsed
        index = startIndex.coerceIn(0, parsed.size)
        actionInFlight = false
        executedStepCount = 0
        loopCounters.clear()
        variables.clear()
        errorDestination = -1
        setStatus(
            WorkflowStatus(
                state = "running",
                stepIndex = index,
                stepCount = steps.size,
                requestId = currentRequestId
            )
        )
        advanceLocked()
        return status
    }

    @Synchronized
    fun stop(): WorkflowStatus {
        val requestId = currentRequestId
        executionId++
        service.cancelImageWatch()
        steps = emptyList()
        index = 0
        actionInFlight = false
        executedStepCount = 0
        loopCounters.clear()
        variables.clear()
        errorDestination = -1
        setStatus(
            WorkflowStatus(
                state = "stopped",
                requestId = requestId
            )
        )
        currentRequestId = null
        return status
    }

    @Synchronized
    fun onTreeUpdated() {
        if (actionInFlight || status.state != "waiting") return
        if (steps.getOrNull(index) is WorkflowStep.Wait) {
            advanceLocked()
        }
    }

    /**
     * Fast path for WAIT. The Accessibility event source is checked before the
     * periodic full-tree snapshot is rebuilt. If WAIT is immediately followed
     * by CLICK for the same target, click directly from this small subtree.
     */
    @Synchronized
    fun onAccessibilitySource(source: AccessibilityNodeInfo?): Boolean {
        if (source == null || actionInFlight || status.state != "waiting") return false
        val waitStep = steps.getOrNull(index) as? WorkflowStep.Wait ?: return false
        if (!service.isTargetReadyInSubtree(source, waitStep.target)) return false

        index++
        val next = steps.getOrNull(index)
        if (
            next is WorkflowStep.Click &&
            sameTextTarget(waitStep.target, next.target) &&
            service.clickTextInSubtree(source, next.target)
        ) {
            setStatus(statusFor("running", next))
            index++
        }

        advanceLocked()
        return true
    }

    @Synchronized
    fun onImageMatched(match: ImageTargetRuntime.ImageMatch) {
        if (!actionInFlight) return
        val step = steps.getOrNull(index) ?: return

        when (step) {
            is WorkflowStep.WaitImage -> {
                if (!sameImageName(step.target, match.name)) return
                actionInFlight = false
                index++
                advanceLocked()
            }

            is WorkflowStep.WaitImageTimeout -> {
                if (!sameImageName(step.target, match.name)) return
                actionInFlight = false
                index++
                advanceLocked()
            }

            is WorkflowStep.ClickImage -> {
                if (!sameImageName(step.target, match.name)) return
                val token = executionId
                val matchedAt = match.timestampMs
                val watchStartedAt = imageWatchStartedMs.takeIf { it > 0L } ?: matchedAt
                setStatus(statusFor("running", step))
                val dispatchedAt = SystemClock.elapsedRealtime()
                val started = service.tapForWorkflow(match.centerX, match.centerY) { success ->
                    val completedAt = SystemClock.elapsedRealtime()
                    synchronized(this) {
                        if (token != executionId || !actionInFlight) return@synchronized
                        if (steps.getOrNull(index) != step) return@synchronized
                        onImageClickTiming(
                            ImageClickTiming(
                                requestId = currentRequestId,
                                name = match.name,
                                score = match.score,
                                findMs = (matchedAt - watchStartedAt).coerceAtLeast(0L),
                                matchToDispatchMs = (dispatchedAt - matchedAt).coerceAtLeast(0L),
                                gestureMs = (completedAt - dispatchedAt).coerceAtLeast(0L),
                                matchToClickMs = (completedAt - matchedAt).coerceAtLeast(0L),
                                totalMs = (completedAt - watchStartedAt).coerceAtLeast(0L),
                                success = success
                            )
                        )
                        imageWatchStartedMs = 0L
                        onAsyncActionFinished(token, success, step, "image_tap_cancelled")
                    }
                }
                if (!started) {
                    actionInFlight = false
                    failLocked("image_tap_not_started", step)
                }
            }

            else -> Unit
        }
    }

    private fun advanceLocked() {
        if (actionInFlight) return

        var synchronousSteps = 0
        while (index < steps.size) {
            val step = steps[index]
            if (synchronousSteps >= MAX_SYNCHRONOUS_STEPS) {
                yieldExecutionLocked()
                return
            }
            synchronousSteps++
            executedStepCount++
            if (executedStepCount > MAX_EXECUTED_STEPS) {
                failLocked("workflow_step_limit_exceeded", step)
                return
            }
            when (step) {
                is WorkflowStep.Wait -> {
                    if (!service.isTargetReady(step.target)) {
                        setStatus(statusFor("waiting", step))
                        scheduleStepTimeoutLocked(step, step.timeoutSeconds, "text_wait_timeout")
                        return
                    }
                    index++
                }

                is WorkflowStep.Click -> {
                    setStatus(statusFor("running", step))
                    if (!service.clickText(step.target)) {
                        failLocked("click_target_not_found_or_not_clickable", step)
                        return
                    }
                    index++
                }

                is WorkflowStep.ClickDescriptionRegex -> {
                    startDescriptionRegexClickLocked(step)
                    return
                }

                is WorkflowStep.Tap -> {
                    startTapLocked(step)
                    return
                }

                is WorkflowStep.TapPercent -> {
                    val (width, height) = service.displaySizeForWorkflow()
                    startTapLocked(
                        WorkflowStep.Tap(
                            (width * step.xPercent / 100.0).toInt().coerceIn(0, width - 1),
                            (height * step.yPercent / 100.0).toInt().coerceIn(0, height - 1)
                        ),
                        statusStep = step
                    )
                    return
                }

                is WorkflowStep.Swipe -> {
                    startCoordinateSwipeLocked(step)
                    return
                }

                is WorkflowStep.SwipePercent -> {
                    val (width, height) = service.displaySizeForWorkflow()
                    startCoordinateSwipeLocked(
                        WorkflowStep.Swipe(
                            (width * step.startXPercent / 100.0).toInt().coerceIn(0, width - 1),
                            (height * step.startYPercent / 100.0).toInt().coerceIn(0, height - 1),
                            (width * step.endXPercent / 100.0).toInt().coerceIn(0, width - 1),
                            (height * step.endYPercent / 100.0).toInt().coerceIn(0, height - 1),
                            step.durationMs
                        ),
                        statusStep = step
                    )
                    return
                }

                WorkflowStep.Up -> {
                    startSwipeLocked("up", step)
                    return
                }

                WorkflowStep.Down -> {
                    startSwipeLocked("down", step)
                    return
                }

                WorkflowStep.Left -> {
                    startSwipeLocked("left", step)
                    return
                }

                WorkflowStep.Right -> {
                    startSwipeLocked("right", step)
                    return
                }

                WorkflowStep.Back -> {
                    setStatus(statusFor("running", step))
                    if (!service.performSystemAction("back")) {
                        failLocked("back_not_applied", step)
                        return
                    }
                    index++
                }

                WorkflowStep.Home -> {
                    setStatus(statusFor("running", step))
                    if (!service.performSystemAction("home")) {
                        failLocked("home_not_applied", step)
                        return
                    }
                    index++
                }

                WorkflowStep.Recents -> {
                    setStatus(statusFor("running", step))
                    if (!service.performSystemAction("recents")) {
                        failLocked("recents_not_applied", step)
                        return
                    }
                    index++
                }

                is WorkflowStep.Sleep -> {
                    startSleepLocked(step)
                    return
                }

                is WorkflowStep.RandomSleep -> {
                    startRandomSleepLocked(step)
                    return
                }

                is WorkflowStep.WaitCountdown -> {
                    startCountdownSleepLocked(step)
                    return
                }

                is WorkflowStep.WaitImage -> {
                    startImageWaitLocked(step.target, step)
                    return
                }

                is WorkflowStep.WaitImageTimeout -> {
                    startImageWaitLocked(step.target, step, step.timeoutSeconds)
                    return
                }

                is WorkflowStep.WaitAnyImage -> {
                    startWaitAnyImageLocked(step)
                    return
                }

                is WorkflowStep.ClickImage -> {
                    startImageWaitLocked(step.target, step, step.timeoutSeconds)
                    return
                }

                is WorkflowStep.Checkpoint -> {
                    if (step.clear) service.clearWorkflowCheckpoint()
                    else service.saveWorkflowCheckpoint(currentScript, index + 1, currentRequestId)
                    index++
                }

                is WorkflowStep.RequireDevice -> {
                    index = if (service.deviceRequirementsMet(step.requirements)) index + 1
                    else step.destination
                }

                is WorkflowStep.OpenApp -> {
                    setStatus(statusFor("running", step))
                    if (!service.openPackage(step.packageName, step.profileSerial)) {
                        failLocked("app_package_or_profile_not_launchable", step)
                        return
                    }
                    index++
                }

                is WorkflowStep.Label -> index++

                is WorkflowStep.Goto -> index = step.destination

                is WorkflowStep.IfVisible -> {
                    index = if (service.isTargetReady(step.target)) step.destination else index + 1
                }

                is WorkflowStep.IfNotVisible -> {
                    index = if (!service.isTargetReady(step.target)) step.destination else index + 1
                }

                is WorkflowStep.SetVariable -> {
                    variables[step.name] = step.value
                    index++
                }

                is WorkflowStep.IncrementVariable -> {
                    val current = variables[step.name]?.toDoubleOrNull() ?: 0.0
                    variables[step.name] = (current + step.amount).toString()
                    index++
                }

                is WorkflowStep.IfVariable -> {
                    index = if (variableMatches(step)) step.destination else index + 1
                }

                is WorkflowStep.OnError -> {
                    errorDestination = step.destination
                    index++
                }

                is WorkflowStep.IfImageVisible -> {
                    startImageConditionalLocked(step.target, step.destination, false, step)
                    return
                }

                is WorkflowStep.IfImageNotVisible -> {
                    startImageConditionalLocked(step.target, step.destination, true, step)
                    return
                }

                is WorkflowStep.IfCountdownVisible -> {
                    val visible = DynamicAccessibilityClick.exists(
                        service = service,
                        className = null,
                        descriptionRegex = Regex(COUNTDOWN_DESCRIPTION_REGEX)
                    )
                    index = if (visible.xor(step.negated)) step.destination else index + 1
                }

                is WorkflowStep.LoopStart -> {
                    loopCounters.putIfAbsent(index, step.count)
                    index++
                }

                is WorkflowStep.LoopEnd -> {
                    val start = step.startIndex
                    val remaining = loopCounters[start]
                        ?: (steps[start] as WorkflowStep.LoopStart).count
                    if (remaining > 1) {
                        loopCounters[start] = remaining - 1
                        index = start + 1
                    } else {
                        loopCounters.remove(start)
                        index++
                    }
                }

                is WorkflowStep.Break -> {
                    loopCounters.remove(step.startIndex)
                    index = step.endIndex + 1
                }

                is WorkflowStep.Continue -> index = step.endIndex
            }
        }

        val requestId = currentRequestId
        setStatus(
            WorkflowStatus(
                state = "completed",
                stepIndex = steps.size,
                stepCount = steps.size,
                requestId = requestId
            )
        )
        currentRequestId = null
        loopCounters.clear()
    }

    private fun yieldExecutionLocked() {
        actionInFlight = true
        val token = executionId
        service.delayForWorkflow(0L) {
            synchronized(this) {
                if (token != executionId || !actionInFlight) return@synchronized
                actionInFlight = false
                advanceLocked()
            }
        }
    }

    private fun startDescriptionRegexClickLocked(step: WorkflowStep.ClickDescriptionRegex) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("running", step))

        val regex = runCatching { Regex(step.pattern) }.getOrElse {
            actionInFlight = false
            failLocked("description_regex_invalid", step)
            return
        }

        when (
            DynamicAccessibilityClick.start(
                service = service,
                className = step.className,
                descriptionRegex = regex
            ) { success ->
                onAsyncActionFinished(token, success, step, "description_regex_tap_cancelled")
            }
        ) {
            DynamicAccessibilityClick.StartResult.COMPLETED -> {
                actionInFlight = false
                index++
                advanceLocked()
            }

            DynamicAccessibilityClick.StartResult.STARTED -> Unit

            DynamicAccessibilityClick.StartResult.NOT_FOUND -> {
                actionInFlight = false
                failLocked("description_regex_target_not_found", step)
            }

            DynamicAccessibilityClick.StartResult.NOT_STARTED -> {
                actionInFlight = false
                failLocked("description_regex_tap_not_started", step)
            }
        }
    }

    private fun startTapLocked(step: WorkflowStep.Tap, statusStep: WorkflowStep = step) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("running", statusStep))
        val started = service.tapForWorkflow(step.x, step.y) { success ->
            onAsyncActionFinished(token, success, statusStep, "tap_cancelled")
        }
        if (!started) {
            actionInFlight = false
            failLocked("tap_not_started", statusStep)
        }
    }

    private fun startSwipeLocked(direction: String, step: WorkflowStep) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("running", step))
        val started = service.swipeForWorkflow(direction) { success ->
            onAsyncActionFinished(token, success, step, "swipe_cancelled")
        }
        if (!started) {
            actionInFlight = false
            failLocked("swipe_not_started", step)
        }
    }

    private fun startSleepLocked(step: WorkflowStep.Sleep) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("sleeping", step))
        service.delayForWorkflow((step.seconds * 1_000.0).toLong()) {
            onAsyncActionFinished(token, true, step, "sleep_cancelled")
        }
    }

    private fun startRandomSleepLocked(step: WorkflowStep.RandomSleep) {
        val seconds = if (step.minSeconds == step.maxSeconds) {
            step.minSeconds
        } else {
            Random.nextDouble(step.minSeconds, step.maxSeconds)
        }
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("sleeping", step))
        service.delayForWorkflow((seconds * 1_000.0).toLong()) {
            onAsyncActionFinished(token, true, step, "sleep_cancelled")
        }
    }

    private fun startCountdownSleepLocked(step: WorkflowStep.WaitCountdown) {
        val value = DynamicAccessibilityClick.findMatchingDescription(
            service = service,
            className = null,
            descriptionRegex = Regex(COUNTDOWN_DESCRIPTION_REGEX)
        )
        if (value == null) {
            failLocked("countdown_not_found", step)
            return
        }
        val parts = value.split(':')
        val minutes = parts.getOrNull(0)?.toLongOrNull()
        val seconds = parts.getOrNull(1)?.toLongOrNull()
        if (minutes == null || seconds == null || seconds !in 0L..59L) {
            failLocked("countdown_invalid", step)
            return
        }
        val extraSeconds = if (step.minExtraSeconds == step.maxExtraSeconds) {
            step.minExtraSeconds
        } else {
            Random.nextDouble(step.minExtraSeconds, step.maxExtraSeconds)
        }
        val delayMs = ((minutes * 60.0 + seconds + extraSeconds) * 1_000.0)
            .toLong()
            .coerceAtLeast(0L)
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("sleeping", step))
        service.delayForWorkflow(delayMs) {
            onAsyncActionFinished(token, true, step, "countdown_sleep_cancelled")
        }
    }

    private fun startImageConditionalLocked(
        target: String,
        destination: Int,
        negated: Boolean,
        step: WorkflowStep
    ) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("checking", step))
        val error = service.probeImage(target) { result ->
            synchronized(this) {
                if (token != executionId || !actionInFlight || steps.getOrNull(index) != step) {
                    return@synchronized
                }
                actionInFlight = false
                result.fold(
                    onSuccess = { visible ->
                        val shouldJump = if (negated) !visible else visible
                        index = if (shouldJump) destination else index + 1
                        advanceLocked()
                    },
                    onFailure = { failure ->
                        failLocked(failure.message ?: "image_probe_failed", step)
                    }
                )
            }
        }
        if (error != null) {
            actionInFlight = false
            failLocked(error, step)
        }
    }

    private fun startImageWaitLocked(
        target: String,
        step: WorkflowStep,
        timeoutSeconds: Double? = null
    ) {
        // Arm state before the matcher so an immediate frame match cannot race
        // ahead of the workflow state.
        actionInFlight = true
        imageWatchStartedMs = SystemClock.elapsedRealtime()
        val error = service.startImageWatch(target)
        if (error != null) {
            imageWatchStartedMs = 0L
            actionInFlight = false
            failLocked(error, step)
            return
        }
        setStatus(statusFor("waiting", step))
        if (timeoutSeconds != null) {
            val token = executionId
            service.delayForWorkflow((timeoutSeconds * 1_000.0).toLong()) {
                synchronized(this) {
                    if (token != executionId || !actionInFlight || steps.getOrNull(index) != step) {
                        return@synchronized
                    }
                    failLocked("image_wait_timeout", step)
                }
            }
        }
    }

    private fun scheduleStepTimeoutLocked(
        step: WorkflowStep,
        timeoutSeconds: Double?,
        error: String
    ) {
        if (timeoutSeconds == null) return
        val token = executionId
        service.delayForWorkflow((timeoutSeconds * 1_000.0).toLong()) {
            synchronized(this) {
                if (token != executionId || steps.getOrNull(index) != step || status.state != "waiting") {
                    return@synchronized
                }
                failLocked(error, step)
            }
        }
    }

    private fun startWaitAnyImageLocked(step: WorkflowStep.WaitAnyImage) {
        actionInFlight = true
        val token = executionId
        val deadline = SystemClock.elapsedRealtime() + (step.timeoutSeconds * 1_000.0).toLong()
        setStatus(statusFor("waiting", step))

        fun probeRound(targetIndex: Int) {
            synchronized(this) {
                if (token != executionId || !actionInFlight || steps.getOrNull(index) != step) return
                if (SystemClock.elapsedRealtime() >= deadline) {
                    actionInFlight = false
                    if (step.timeoutDestination >= 0) {
                        variables["LAST_IMAGE"] = ""
                        index = step.timeoutDestination
                        advanceLocked()
                    } else {
                        failLocked("wait_any_image_timeout", step)
                    }
                    return
                }
            }
            val target = step.targets[targetIndex]
            val error = service.probeImage(target) { result ->
                synchronized(this) {
                    if (token != executionId || !actionInFlight || steps.getOrNull(index) != step) {
                        return@synchronized
                    }
                    result.fold(
                        onSuccess = { visible ->
                            if (visible) {
                                actionInFlight = false
                                variables["LAST_IMAGE"] = target
                                index++
                                advanceLocked()
                            } else {
                                val next = (targetIndex + 1) % step.targets.size
                                service.delayForWorkflow(if (next == 0) 80L else 0L) { probeRound(next) }
                            }
                        },
                        onFailure = { failure ->
                            actionInFlight = false
                            failLocked(failure.message ?: "image_probe_failed", step)
                        }
                    )
                }
            }
            if (error != null) {
                synchronized(this) {
                    if (token == executionId && actionInFlight && steps.getOrNull(index) == step) {
                        actionInFlight = false
                        failLocked(error, step)
                    }
                }
            }
        }
        probeRound(0)
    }

    private fun variableMatches(step: WorkflowStep.IfVariable): Boolean {
        val actual = variables[step.name].orEmpty()
        val leftNumber = actual.toDoubleOrNull()
        val rightNumber = step.expected.toDoubleOrNull()
        return when (step.operator) {
            "==" -> if (leftNumber != null && rightNumber != null) leftNumber == rightNumber
                else actual.equals(step.expected, ignoreCase = true)
            "!=" -> if (leftNumber != null && rightNumber != null) leftNumber != rightNumber
                else !actual.equals(step.expected, ignoreCase = true)
            ">" -> leftNumber != null && rightNumber != null && leftNumber > rightNumber
            ">=" -> leftNumber != null && rightNumber != null && leftNumber >= rightNumber
            "<" -> leftNumber != null && rightNumber != null && leftNumber < rightNumber
            "<=" -> leftNumber != null && rightNumber != null && leftNumber <= rightNumber
            else -> false
        }
    }

    @Synchronized
    private fun onAsyncActionFinished(
        token: Long,
        success: Boolean,
        step: WorkflowStep,
        failureError: String
    ) {
        if (token != executionId || !actionInFlight) return
        if (steps.getOrNull(index) != step) return

        actionInFlight = false
        if (!success) {
            failLocked(failureError, step)
            return
        }
        index++
        advanceLocked()
    }

    private fun failLocked(error: String, step: WorkflowStep) {
        service.cancelImageWatch()
        actionInFlight = false
        if (errorDestination >= 0) {
            variables["LAST_ERROR"] = error
            index = errorDestination
            errorDestination = -1
            setStatus(statusFor("recovering", step, error))
            advanceLocked()
            return
        }
        val requestId = currentRequestId
        setStatus(
            statusFor(
                state = "failed",
                step = step,
                error = error
            ).copy(requestId = requestId)
        )
        currentRequestId = null
        loopCounters.clear()
        variables.clear()
    }

    private fun cancelCurrentForReplacementLocked() {
        val requestId = currentRequestId ?: return
        if (status.state in TERMINAL_STATES) return
        setStatus(
            WorkflowStatus(
                state = "cancelled",
                stepIndex = index,
                stepCount = steps.size,
                error = "replaced_by_new_workflow",
                requestId = requestId
            )
        )
        currentRequestId = null
    }

    private fun statusFor(
        state: String,
        step: WorkflowStep,
        error: String? = null
    ): WorkflowStatus = WorkflowStatus(
        state = state,
        stepIndex = index,
        stepCount = steps.size,
        command = commandName(step),
        target = targetOf(step),
        error = error,
        requestId = currentRequestId
    )

    private fun setStatus(newStatus: WorkflowStatus) {
        status = newStatus
        onStatusChanged(newStatus)
    }

    private fun commandName(step: WorkflowStep): String = when (step) {
        is WorkflowStep.Click -> "CLICK"
        is WorkflowStep.ClickDescriptionRegex -> step.label
        is WorkflowStep.Tap -> "TAP"
        is WorkflowStep.TapPercent -> "TAP_PERCENT"
        is WorkflowStep.Swipe -> "SWIPE"
        is WorkflowStep.SwipePercent -> "SWIPE_PERCENT"
        is WorkflowStep.Wait -> if (step.timeoutSeconds == null) "WAIT" else "WAIT_TIMEOUT"
        is WorkflowStep.Sleep -> "SLEEP"
        is WorkflowStep.RandomSleep -> "SLEEP_RANDOM"
        is WorkflowStep.WaitCountdown -> if (step.random) "WAIT_TIME_RANDOM" else "WAIT_TIME"
        is WorkflowStep.WaitImage -> "WAIT_IMG"
        is WorkflowStep.WaitImageTimeout -> "WAIT_IMG_TIMEOUT"
        is WorkflowStep.WaitAnyImage -> "WAIT_ANY_IMG"
        is WorkflowStep.ClickImage -> if (step.timeoutSeconds == null) "CLICK_IMG" else "CLICK_IMG_TIMEOUT"
        is WorkflowStep.Checkpoint -> "CHECKPOINT"
        is WorkflowStep.RequireDevice -> "REQUIRE_DEVICE"
        is WorkflowStep.SetVariable -> "SET"
        is WorkflowStep.IncrementVariable -> "INC"
        is WorkflowStep.IfVariable -> "IF_VAR"
        is WorkflowStep.OnError -> "ON_ERROR"
        is WorkflowStep.OpenApp -> "OPEN_APP"
        is WorkflowStep.Label -> "LABEL"
        is WorkflowStep.Goto -> "GOTO"
        is WorkflowStep.IfVisible -> "IF"
        is WorkflowStep.IfNotVisible -> "IF_NOT"
        is WorkflowStep.IfImageVisible -> "IF_IMG"
        is WorkflowStep.IfImageNotVisible -> "IF_NOT_IMG"
        is WorkflowStep.IfCountdownVisible -> if (step.negated) "IF_NOT_TIME" else "IF_TIME"
        is WorkflowStep.LoopStart -> "LOOP"
        is WorkflowStep.LoopEnd -> "END_LOOP"
        is WorkflowStep.Break -> "BREAK"
        is WorkflowStep.Continue -> "CONTINUE"
        WorkflowStep.Up -> "UP"
        WorkflowStep.Down -> "DOWN"
        WorkflowStep.Left -> "LEFT"
        WorkflowStep.Right -> "RIGHT"
        WorkflowStep.Back -> "BACK"
        WorkflowStep.Home -> "HOME"
        WorkflowStep.Recents -> "RECENTS"
    }

    private fun targetOf(step: WorkflowStep): String? = when (step) {
        is WorkflowStep.Click -> step.target
        is WorkflowStep.ClickDescriptionRegex ->
            if (step.className.isNullOrBlank()) step.pattern else "${step.className}|${step.pattern}"
        is WorkflowStep.Tap -> "${step.x},${step.y}"
        is WorkflowStep.TapPercent -> "${step.xPercent},${step.yPercent}"
        is WorkflowStep.Swipe ->
            "${step.startX},${step.startY},${step.endX},${step.endY},${step.durationMs}"
        is WorkflowStep.SwipePercent ->
            "${step.startXPercent},${step.startYPercent},${step.endXPercent},${step.endYPercent},${step.durationMs}"
        is WorkflowStep.Wait -> if (step.timeoutSeconds == null) step.target else "${step.target}|${step.timeoutSeconds}"
        is WorkflowStep.Sleep -> step.seconds.toString()
        is WorkflowStep.RandomSleep -> "${step.minSeconds},${step.maxSeconds}"
        is WorkflowStep.WaitCountdown ->
            if (step.random) "${step.minExtraSeconds},${step.maxExtraSeconds}" else null
        is WorkflowStep.WaitImage -> step.target
        is WorkflowStep.WaitImageTimeout -> "${step.target}|${step.timeoutSeconds}"
        is WorkflowStep.WaitAnyImage -> "${step.targets.joinToString(",")}|${step.timeoutSeconds}"
        is WorkflowStep.ClickImage -> if (step.timeoutSeconds == null) step.target else "${step.target}|${step.timeoutSeconds}"
        is WorkflowStep.Checkpoint -> if (step.clear) "CLEAR" else "SAVE"
        is WorkflowStep.RequireDevice -> "${step.requirements.joinToString(",")}|${step.label}"
        is WorkflowStep.SetVariable -> "${step.name}=${step.value}"
        is WorkflowStep.IncrementVariable -> "${step.name},${step.amount}"
        is WorkflowStep.IfVariable -> "${step.name}${step.operator}${step.expected}|${step.label}"
        is WorkflowStep.OnError -> step.label ?: "OFF"
        is WorkflowStep.OpenApp ->
            if (step.profileSerial == null) step.packageName
            else "${step.packageName}|${step.profileSerial}"
        is WorkflowStep.Label -> step.name
        is WorkflowStep.Goto -> step.label
        is WorkflowStep.IfVisible -> "${step.target}|${step.label}"
        is WorkflowStep.IfNotVisible -> "${step.target}|${step.label}"
        is WorkflowStep.IfImageVisible -> "${step.target}|${step.label}"
        is WorkflowStep.IfImageNotVisible -> "${step.target}|${step.label}"
        is WorkflowStep.IfCountdownVisible -> step.label
        is WorkflowStep.LoopStart -> step.count.toString()
        is WorkflowStep.LoopEnd,
        is WorkflowStep.Break,
        is WorkflowStep.Continue -> null
        WorkflowStep.Up,
        WorkflowStep.Down,
        WorkflowStep.Left,
        WorkflowStep.Right,
        WorkflowStep.Back,
        WorkflowStep.Home,
        WorkflowStep.Recents -> null
    }

    private fun sameImageName(left: String, right: String): Boolean =
        left.trim().equals(right.trim(), ignoreCase = true)

    private fun sameTextTarget(left: String, right: String): Boolean =
        AgentRuntime.normalizeForMatch(left) == AgentRuntime.normalizeForMatch(right)

    companion object {
        private val TERMINAL_STATES = setOf("completed", "failed", "stopped", "cancelled")
        private const val COUNTDOWN_DESCRIPTION_REGEX = "^[0-9]{1,3}:[0-5][0-9]$"

        fun parse(script: String): List<WorkflowStep> {
            val tokens = script
                .replace("\r", "\n")
                .split(';', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)

            val parsed = tokens.map { token ->
                val command = token.substringBefore(':').trim().uppercase(Locale.ROOT)
                val argument = token.substringAfter(':', "").trim()
                when (command) {
                    "CLICK" -> {
                        require(argument.isNotEmpty()) { "CLICK_requires_target" }
                        WorkflowStep.Click(argument)
                    }

                    "CLICK_TIME" -> {
                        WorkflowStep.ClickDescriptionRegex(
                            className = argument.ifBlank { null },
                            pattern = COUNTDOWN_DESCRIPTION_REGEX,
                            label = "CLICK_TIME"
                        )
                    }

                    "CLICK_DESC_REGEX" -> {
                        require(argument.isNotEmpty()) { "CLICK_DESC_REGEX_requires_pattern" }
                        requireValidRegex(argument, "CLICK_DESC_REGEX_invalid_pattern")
                        WorkflowStep.ClickDescriptionRegex(
                            className = null,
                            pattern = argument,
                            label = "CLICK_DESC_REGEX"
                        )
                    }

                    "CLICK_CLASS_DESC_REGEX" -> {
                        val separator = argument.indexOf('|')
                        require(separator > 0 && separator < argument.length - 1) {
                            "CLICK_CLASS_DESC_REGEX_requires_class_and_pattern"
                        }
                        val className = argument.substring(0, separator).trim()
                        val pattern = argument.substring(separator + 1).trim()
                        require(className.isNotEmpty() && pattern.isNotEmpty()) {
                            "CLICK_CLASS_DESC_REGEX_requires_class_and_pattern"
                        }
                        requireValidRegex(pattern, "CLICK_CLASS_DESC_REGEX_invalid_pattern")
                        WorkflowStep.ClickDescriptionRegex(
                            className = className,
                            pattern = pattern,
                            label = "CLICK_CLASS_DESC_REGEX"
                        )
                    }

                    "TAP", "CLICK_XY", "CLICKXY" -> {
                        val coordinateParts = argument
                            .replace(' ', ',')
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                        require(coordinateParts.size == 2) { "TAP_requires_x_y" }
                        val x = coordinateParts[0].toIntOrNull()
                            ?: throw IllegalArgumentException("TAP_invalid_x")
                        val y = coordinateParts[1].toIntOrNull()
                            ?: throw IllegalArgumentException("TAP_invalid_y")
                        require(x >= 0 && y >= 0) { "TAP_coordinates_must_be_non_negative" }
                        WorkflowStep.Tap(x, y)
                    }

                    "TAP_PERCENT", "TAP_PCT" -> {
                        val parts = parseNumberParts(argument, 2, "TAP_PERCENT_requires_x_y")
                        require(parts.all { it in 0.0..100.0 }) { "TAP_PERCENT_out_of_range" }
                        WorkflowStep.TapPercent(parts[0], parts[1])
                    }

                    "SWIPE" -> {
                        val parts = argument
                            .replace(' ', ',')
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                        require(parts.size == 5) { "SWIPE_requires_x1_y1_x2_y2_duration_ms" }
                        val values = parts.mapIndexed { position, value ->
                            value.toLongOrNull()
                                ?: throw IllegalArgumentException("SWIPE_invalid_value_${position + 1}")
                        }
                        require(values.take(4).all { it in 0..Int.MAX_VALUE.toLong() }) {
                            "SWIPE_coordinates_must_be_non_negative"
                        }
                        require(values[0] != values[2] || values[1] != values[3]) {
                            "SWIPE_start_and_end_must_differ"
                        }
                        require(values[4] in MIN_SWIPE_DURATION_MS..MAX_SWIPE_DURATION_MS) {
                            "SWIPE_duration_out_of_range"
                        }
                        WorkflowStep.Swipe(
                            values[0].toInt(),
                            values[1].toInt(),
                            values[2].toInt(),
                            values[3].toInt(),
                            values[4]
                        )
                    }

                    "WAIT", "CHO", "CHỜ" -> {
                        require(argument.isNotEmpty()) { "WAIT_requires_target" }
                        WorkflowStep.Wait(argument)
                    }

                    "WAIT_TIMEOUT" -> {
                        val (target, seconds) = parseTargetTimeout(argument, "WAIT_TIMEOUT")
                        WorkflowStep.Wait(target, seconds)
                    }

                    "SWIPE_PERCENT", "SWIPE_PCT" -> {
                        val parts = argument.replace(' ', ',').split(',').map(String::trim)
                            .filter(String::isNotEmpty)
                        require(parts.size == 5) {
                            "SWIPE_PERCENT_requires_x1_y1_x2_y2_duration_ms"
                        }
                        val coordinates = parts.take(4).map {
                            it.toDoubleOrNull()
                                ?: throw IllegalArgumentException("SWIPE_PERCENT_invalid_coordinate")
                        }
                        require(coordinates.all { it.isFinite() && it in 0.0..100.0 }) {
                            "SWIPE_PERCENT_coordinates_out_of_range"
                        }
                        val duration = parts[4].toLongOrNull()
                            ?: throw IllegalArgumentException("SWIPE_PERCENT_invalid_duration")
                        require(duration in MIN_SWIPE_DURATION_MS..MAX_SWIPE_DURATION_MS) {
                            "SWIPE_PERCENT_duration_out_of_range"
                        }
                        WorkflowStep.SwipePercent(
                            coordinates[0], coordinates[1], coordinates[2], coordinates[3], duration
                        )
                    }

                    "WAIT_IMG" -> {
                        require(argument.isNotEmpty()) { "WAIT_IMG_requires_target" }
                        WorkflowStep.WaitImage(argument)
                    }

                    "WAIT_IMG_TIMEOUT" -> {
                        val separator = argument.lastIndexOf('|')
                        require(separator > 0) { "WAIT_IMG_TIMEOUT_requires_target_seconds" }
                        val target = argument.substring(0, separator).trim()
                        val seconds = argument.substring(separator + 1).trim().toDoubleOrNull()
                            ?: throw IllegalArgumentException("WAIT_IMG_TIMEOUT_invalid_seconds")
                        require(target.isNotEmpty() && seconds > 0.0 && seconds <= MAX_SLEEP_SECONDS) {
                            "WAIT_IMG_TIMEOUT_out_of_range"
                        }
                        WorkflowStep.WaitImageTimeout(target, seconds)
                    }

                    "WAIT_ANY_IMG" -> parseWaitAnyImage(argument)

                    "CLICK_IMG" -> {
                        require(argument.isNotEmpty()) { "CLICK_IMG_requires_target" }
                        WorkflowStep.ClickImage(argument)
                    }

                    "CLICK_IMG_TIMEOUT" -> {
                        val (target, seconds) = parseTargetTimeout(argument, "CLICK_IMG_TIMEOUT")
                        WorkflowStep.ClickImage(target, seconds)
                    }

                    "CHECKPOINT" -> when (argument.uppercase(Locale.ROOT)) {
                        "", "SAVE", "ON" -> WorkflowStep.Checkpoint(clear = false)
                        "CLEAR", "OFF" -> WorkflowStep.Checkpoint(clear = true)
                        else -> throw IllegalArgumentException("CHECKPOINT_requires_SAVE_or_CLEAR")
                    }

                    "REQUIRE_DEVICE" -> parseDeviceRequirement(argument)

                    "SET" -> {
                        val separator = argument.indexOf('=')
                        require(separator > 0) { "SET_requires_name_equals_value" }
                        val name = normalizeVariable(argument.substring(0, separator))
                        val value = argument.substring(separator + 1).trim()
                        require(name.isNotEmpty()) { "SET_requires_name" }
                        WorkflowStep.SetVariable(name, value)
                    }

                    "INC" -> {
                        val parts = argument.split(',', limit = 2).map(String::trim)
                        val name = normalizeVariable(parts.firstOrNull().orEmpty())
                        val amount = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                        require(name.isNotEmpty() && amount.isFinite()) { "INC_invalid_argument" }
                        WorkflowStep.IncrementVariable(name, amount)
                    }

                    "IF_VAR" -> parseVariableConditional(argument)

                    "ON_ERROR" -> if (argument.equals("OFF", true) || argument.isBlank()) {
                        WorkflowStep.OnError(null)
                    } else {
                        WorkflowStep.OnError(normalizeLabel(argument))
                    }

                    "OPEN_APP", "OPEN_PACKAGE" -> {
                        val parts = argument.split('|', limit = 2).map(String::trim)
                        val packageName = parts.firstOrNull().orEmpty()
                        require(packageName.matches(Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$"))) {
                            "OPEN_APP_invalid_package"
                        }
                        val profileSerial = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                        if (parts.size == 2) require(profileSerial != null) { "OPEN_APP_invalid_profile" }
                        WorkflowStep.OpenApp(packageName, profileSerial)
                    }

                    "SLEEP", "REST", "NGHI", "NGHỈ" -> {
                        val seconds = argument.toDoubleOrNull()
                            ?: throw IllegalArgumentException("SLEEP_requires_seconds")
                        require(seconds >= 0.0 && seconds <= MAX_SLEEP_SECONDS) {
                            "SLEEP_seconds_out_of_range"
                        }
                        WorkflowStep.Sleep(seconds)
                    }

                    "SLEEP_RANDOM", "RANDOM_SLEEP" -> {
                        val parts = argument.replace('|', ',').split(',').map(String::trim)
                        require(parts.size == 2) { "SLEEP_RANDOM_requires_min_max_seconds" }
                        val minSeconds = parts[0].toDoubleOrNull()
                            ?: throw IllegalArgumentException("SLEEP_RANDOM_invalid_min")
                        val maxSeconds = parts[1].toDoubleOrNull()
                            ?: throw IllegalArgumentException("SLEEP_RANDOM_invalid_max")
                        require(
                            minSeconds.isFinite() && maxSeconds.isFinite() &&
                                minSeconds >= 0.0 && maxSeconds >= minSeconds &&
                                maxSeconds <= MAX_SLEEP_SECONDS
                        ) { "SLEEP_RANDOM_range_out_of_bounds" }
                        WorkflowStep.RandomSleep(minSeconds, maxSeconds)
                    }

                    "WAIT_TIME" -> {
                        require(argument.isEmpty()) { "WAIT_TIME_does_not_take_target" }
                        WorkflowStep.WaitCountdown(0.0, 0.0, random = false)
                    }

                    "WAIT_TIME_RANDOM" -> {
                        val parts = argument.replace('|', ',').split(',').map(String::trim)
                        require(parts.size == 2) { "WAIT_TIME_RANDOM_requires_min_max_seconds" }
                        val minSeconds = parts[0].toDoubleOrNull()
                            ?: throw IllegalArgumentException("WAIT_TIME_RANDOM_invalid_min")
                        val maxSeconds = parts[1].toDoubleOrNull()
                            ?: throw IllegalArgumentException("WAIT_TIME_RANDOM_invalid_max")
                        require(
                            minSeconds.isFinite() && maxSeconds.isFinite() &&
                                minSeconds >= 0.0 && maxSeconds >= minSeconds &&
                                maxSeconds <= MAX_SLEEP_SECONDS
                        ) { "WAIT_TIME_RANDOM_range_out_of_bounds" }
                        WorkflowStep.WaitCountdown(minSeconds, maxSeconds, random = true)
                    }

                    "UP" -> {
                        require(argument.isEmpty()) { "UP_does_not_take_target" }
                        WorkflowStep.Up
                    }

                    "DOWN" -> {
                        require(argument.isEmpty()) { "DOWN_does_not_take_target" }
                        WorkflowStep.Down
                    }

                    "LABEL" -> {
                        require(argument.isNotEmpty()) { "LABEL_requires_name" }
                        WorkflowStep.Label(normalizeLabel(argument))
                    }

                    "GOTO" -> {
                        require(argument.isNotEmpty()) { "GOTO_requires_label" }
                        WorkflowStep.Goto(normalizeLabel(argument))
                    }

                    "IF", "IF_VISIBLE" -> parseConditional(argument, negated = false)

                    "IF_NOT", "IF_NOT_VISIBLE" -> parseConditional(argument, negated = true)

                    "IF_IMG", "IF_IMAGE" -> parseImageConditional(argument, negated = false)

                    "IF_NOT_IMG", "IF_NOT_IMAGE" -> parseImageConditional(argument, negated = true)

                    "IF_TIME" -> parseCountdownConditional(argument, negated = false)

                    "IF_NOT_TIME" -> parseCountdownConditional(argument, negated = true)

                    "LOOP", "REPEAT" -> {
                        val count = argument.toIntOrNull()
                            ?: throw IllegalArgumentException("LOOP_requires_count")
                        require(count in 1..MAX_LOOP_COUNT) { "LOOP_count_out_of_range" }
                        WorkflowStep.LoopStart(count)
                    }

                    "END_LOOP", "ENDLOOP", "END_REPEAT" -> {
                        require(argument.isEmpty()) { "END_LOOP_does_not_take_target" }
                        WorkflowStep.LoopEnd()
                    }

                    "BREAK" -> {
                        require(argument.isEmpty()) { "BREAK_does_not_take_target" }
                        WorkflowStep.Break()
                    }

                    "CONTINUE" -> {
                        require(argument.isEmpty()) { "CONTINUE_does_not_take_target" }
                        WorkflowStep.Continue()
                    }

                    "LEFT" -> {
                        require(argument.isEmpty()) { "LEFT_does_not_take_target" }
                        WorkflowStep.Left
                    }

                    "RIGHT" -> {
                        require(argument.isEmpty()) { "RIGHT_does_not_take_target" }
                        WorkflowStep.Right
                    }

                    "BACK" -> {
                        require(argument.isEmpty()) { "BACK_does_not_take_target" }
                        WorkflowStep.Back
                    }

                    "HOME" -> {
                        require(argument.isEmpty()) { "HOME_does_not_take_target" }
                        WorkflowStep.Home
                    }

                    "RECENTS", "RECENT", "DA_NHIEM", "ĐA_NHIỆM" -> {
                        require(argument.isEmpty()) { "RECENTS_does_not_take_target" }
                        WorkflowStep.Recents
                    }

                    else -> throw IllegalArgumentException("unsupported_step:$command")
                }
            }
            return resolveControlFlow(parsed)
        }

        private fun parseConditional(argument: String, negated: Boolean): WorkflowStep {
            val separator = argument.lastIndexOf('|')
            require(separator > 0 && separator < argument.length - 1) {
                if (negated) "IF_NOT_requires_target_and_label" else "IF_requires_target_and_label"
            }
            val target = argument.substring(0, separator).trim()
            val label = normalizeLabel(argument.substring(separator + 1))
            require(target.isNotEmpty() && label.isNotEmpty()) {
                if (negated) "IF_NOT_requires_target_and_label" else "IF_requires_target_and_label"
            }
            return if (negated) {
                WorkflowStep.IfNotVisible(target, label)
            } else {
                WorkflowStep.IfVisible(target, label)
            }
        }

        private fun parseWaitAnyImage(argument: String): WorkflowStep.WaitAnyImage {
            val parts = argument.split('|').map(String::trim)
            require(parts.size in 2..3) {
                "WAIT_ANY_IMG_requires_names_timeout_optional_label"
            }
            val targets = parts[0].split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            val seconds = parts[1].toDoubleOrNull()
                ?: throw IllegalArgumentException("WAIT_ANY_IMG_invalid_timeout")
            require(targets.isNotEmpty() && targets.size <= 20) { "WAIT_ANY_IMG_invalid_targets" }
            require(seconds > 0.0 && seconds <= MAX_SLEEP_SECONDS) {
                "WAIT_ANY_IMG_timeout_out_of_range"
            }
            val label = parts.getOrNull(2)?.takeIf(String::isNotBlank)?.let(::normalizeLabel)
            return WorkflowStep.WaitAnyImage(targets, seconds, label)
        }

        private fun parseTargetTimeout(argument: String, command: String): Pair<String, Double> {
            val separator = argument.lastIndexOf('|')
            require(separator > 0) { "${command}_requires_target_seconds" }
            val target = argument.substring(0, separator).trim()
            val seconds = argument.substring(separator + 1).trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("${command}_invalid_seconds")
            require(target.isNotEmpty() && seconds > 0.0 && seconds <= MAX_SLEEP_SECONDS) {
                "${command}_out_of_range"
            }
            return target to seconds
        }

        private fun parseDeviceRequirement(argument: String): WorkflowStep.RequireDevice {
            val separator = argument.lastIndexOf('|')
            require(separator > 0) { "REQUIRE_DEVICE_requires_checks_and_label" }
            val requirements = argument.substring(0, separator).split(',')
                .map { it.trim().uppercase(Locale.ROOT) }.filter(String::isNotEmpty).toSet()
            val supported = setOf("UNLOCKED", "CAPTURE", "NETWORK", "PORTRAIT", "LANDSCAPE")
            require(requirements.isNotEmpty() && requirements.all { it in supported }) {
                "REQUIRE_DEVICE_unsupported_check"
            }
            require(!("PORTRAIT" in requirements && "LANDSCAPE" in requirements)) {
                "REQUIRE_DEVICE_conflicting_orientation"
            }
            val label = normalizeLabel(argument.substring(separator + 1))
            require(label.isNotEmpty()) { "REQUIRE_DEVICE_requires_label" }
            return WorkflowStep.RequireDevice(requirements, label)
        }

        private fun parseVariableConditional(argument: String): WorkflowStep.IfVariable {
            val separator = argument.lastIndexOf('|')
            require(separator > 0) { "IF_VAR_requires_expression_and_label" }
            val expression = argument.substring(0, separator).trim()
            val label = normalizeLabel(argument.substring(separator + 1))
            val match = Regex("^([A-Za-z_][A-Za-z0-9_]*)(==|!=|>=|<=|>|<)(.*)$")
                .matchEntire(expression)
                ?: throw IllegalArgumentException("IF_VAR_invalid_expression")
            val name = normalizeVariable(match.groupValues[1])
            val expected = match.groupValues[3].trim()
            require(expected.isNotEmpty() && label.isNotEmpty()) { "IF_VAR_invalid_expression" }
            return WorkflowStep.IfVariable(name, match.groupValues[2], expected, label)
        }

        private fun parseNumberParts(argument: String, count: Int, error: String): List<Double> {
            val parts = argument.replace(' ', ',').split(',').map(String::trim)
                .filter(String::isNotEmpty)
            require(parts.size == count) { error }
            return parts.map { it.toDoubleOrNull() ?: throw IllegalArgumentException(error) }
                .also { values -> require(values.all(Double::isFinite)) { error } }
        }

        private fun parseImageConditional(argument: String, negated: Boolean): WorkflowStep {
            val separator = argument.lastIndexOf('|')
            require(separator > 0 && separator < argument.length - 1) {
                if (negated) "IF_NOT_IMG_requires_target_and_label"
                else "IF_IMG_requires_target_and_label"
            }
            val target = argument.substring(0, separator).trim()
            val label = normalizeLabel(argument.substring(separator + 1))
            require(target.isNotEmpty() && label.isNotEmpty()) {
                if (negated) "IF_NOT_IMG_requires_target_and_label"
                else "IF_IMG_requires_target_and_label"
            }
            return if (negated) {
                WorkflowStep.IfImageNotVisible(target, label)
            } else {
                WorkflowStep.IfImageVisible(target, label)
            }
        }

        private fun parseCountdownConditional(argument: String, negated: Boolean): WorkflowStep {
            val label = normalizeLabel(argument)
            require(label.isNotEmpty()) {
                if (negated) "IF_NOT_TIME_requires_label" else "IF_TIME_requires_label"
            }
            return WorkflowStep.IfCountdownVisible(label = label, negated = negated)
        }

        private fun resolveControlFlow(steps: List<WorkflowStep>): List<WorkflowStep> {
            val labels = HashMap<String, Int>()
            val loopStack = ArrayList<Int>()

            steps.forEachIndexed { index, step ->
                when (step) {
                    is WorkflowStep.Label -> {
                        require(labels.put(step.name, index + 1) == null) {
                            "duplicate_label:${step.name}"
                        }
                    }

                    is WorkflowStep.LoopStart -> loopStack += index

                    is WorkflowStep.LoopEnd -> {
                        require(loopStack.isNotEmpty()) { "END_LOOP_without_LOOP" }
                        val start = loopStack.removeAt(loopStack.lastIndex)
                        step.startIndex = start
                        (steps[start] as WorkflowStep.LoopStart).endIndex = index
                    }

                    is WorkflowStep.Break -> {
                        require(loopStack.isNotEmpty()) { "BREAK_outside_LOOP" }
                        step.startIndex = loopStack.last()
                    }

                    is WorkflowStep.Continue -> {
                        require(loopStack.isNotEmpty()) { "CONTINUE_outside_LOOP" }
                        step.endIndex = -loopStack.last() - 1
                    }

                    else -> Unit
                }
            }
            require(loopStack.isEmpty()) { "LOOP_without_END_LOOP" }

            steps.forEach { step ->
                when (step) {
                    is WorkflowStep.Goto -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfVisible -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfNotVisible -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfImageVisible -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfImageNotVisible -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfCountdownVisible -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.IfVariable -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.RequireDevice -> step.destination = labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.OnError -> step.destination = if (step.label == null) -1 else labels[step.label]
                        ?: throw IllegalArgumentException("unknown_label:${step.label}")
                    is WorkflowStep.WaitAnyImage -> step.timeoutDestination = if (step.timeoutLabel == null) -1
                        else labels[step.timeoutLabel]
                            ?: throw IllegalArgumentException("unknown_label:${step.timeoutLabel}")
                    is WorkflowStep.Break ->
                        step.endIndex = (steps[step.startIndex] as WorkflowStep.LoopStart).endIndex
                    is WorkflowStep.Continue -> {
                        val start = -step.endIndex - 1
                        step.endIndex = (steps[start] as WorkflowStep.LoopStart).endIndex
                    }
                    else -> Unit
                }
            }
            return steps
        }

        private fun normalizeLabel(value: String): String = value.trim().uppercase(Locale.ROOT)

        private fun normalizeVariable(value: String): String =
            value.trim().uppercase(Locale.ROOT).takeIf {
                it.matches(Regex("^[A-Z_][A-Z0-9_]*$"))
            }.orEmpty()

        private fun requireValidRegex(pattern: String, error: String) {
            require(runCatching { Regex(pattern) }.isSuccess) { error }
        }

        private const val MAX_SLEEP_SECONDS = 3_600.0
        private const val MIN_SWIPE_DURATION_MS = 50L
        private const val MAX_SWIPE_DURATION_MS = 60_000L
        private const val MAX_LOOP_COUNT = 100_000
        private const val MAX_EXECUTED_STEPS = 100_000
        private const val MAX_SYNCHRONOUS_STEPS = 256
    }

    private fun startCoordinateSwipeLocked(
        step: WorkflowStep.Swipe,
        statusStep: WorkflowStep = step
    ) {
        actionInFlight = true
        val token = executionId
        setStatus(statusFor("running", statusStep))
        val started = service.swipeForWorkflow(
            step.startX,
            step.startY,
            step.endX,
            step.endY,
            step.durationMs
        ) { success ->
            onAsyncActionFinished(token, success, statusStep, "swipe_cancelled")
        }
        if (!started) {
            actionInFlight = false
            failLocked("swipe_not_started_or_coordinates_out_of_bounds", statusStep)
        }
    }
}
