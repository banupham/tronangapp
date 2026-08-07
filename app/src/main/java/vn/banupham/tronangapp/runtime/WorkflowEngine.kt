package vn.banupham.tronangapp.runtime

import java.util.Locale
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.vision.ImageTargetRuntime

sealed class WorkflowStep {
    data class Click(val target: String) : WorkflowStep()
    data class Wait(val target: String) : WorkflowStep()
    data class Sleep(val seconds: Double) : WorkflowStep()
    data class WaitImage(val target: String) : WorkflowStep()
    data class ClickImage(val target: String) : WorkflowStep()
    data object Up : WorkflowStep()
    data object Down : WorkflowStep()
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
    val error: String? = null
)

class WorkflowEngine(
    private val service: GenericAccessibilityService,
    private val onStatusChanged: (WorkflowStatus) -> Unit = {}
) {
    @Volatile
    var status: WorkflowStatus = WorkflowStatus()
        private set

    private var steps: List<WorkflowStep> = emptyList()
    private var index = 0
    private var actionInFlight = false
    private var executionId = 0L

    @Synchronized
    fun start(script: String): WorkflowStatus {
        val parsed = try {
            parse(script)
        } catch (error: IllegalArgumentException) {
            executionId++
            service.cancelImageWatch()
            steps = emptyList()
            index = 0
            actionInFlight = false
            setStatus(
                WorkflowStatus(
                    state = "failed",
                    error = error.message ?: "invalid_workflow"
                )
            )
            return status
        }

        executionId++
        service.cancelImageWatch()

        if (parsed.isEmpty()) {
            steps = emptyList()
            index = 0
            actionInFlight = false
            setStatus(WorkflowStatus(state = "failed", error = "empty_workflow"))
            return status
        }

        steps = parsed
        index = 0
        actionInFlight = false
        setStatus(
            WorkflowStatus(
                state = "running",
                stepIndex = 0,
                stepCount = steps.size
            )
        )
        advanceLocked()
        return status
    }

    @Synchronized
    fun stop(): WorkflowStatus {
        executionId++
        service.cancelImageWatch()
        steps = emptyList()
        index = 0
        actionInFlight = false
        setStatus(WorkflowStatus(state = "stopped"))
        return status
    }

    @Synchronized
    fun onTreeUpdated() {
        if (actionInFlight || status.state != "waiting") return
        if (steps.getOrNull(index) is WorkflowStep.Wait) {
            advanceLocked()
        }
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

            is WorkflowStep.ClickImage -> {
                if (!sameImageName(step.target, match.name)) return
                val token = executionId
                setStatus(statusFor("running", step))
                val started = service.tapForWorkflow(match.centerX, match.centerY) { success ->
                    onAsyncActionFinished(token, success, step, "image_tap_cancelled")
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

        while (index < steps.size) {
            val step = steps[index]
            when (step) {
                is WorkflowStep.Wait -> {
                    if (!service.isTargetReady(step.target)) {
                        setStatus(statusFor("waiting", step))
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

                WorkflowStep.Up -> {
                    startSwipeLocked("up", step)
                    return
                }

                WorkflowStep.Down -> {
                    startSwipeLocked("down", step)
                    return
                }

                WorkflowStep.Back -> {
                    if (!service.performSystemAction("back")) {
                        failLocked("back_not_applied", step)
                        return
                    }
                    index++
                }

                WorkflowStep.Home -> {
                    if (!service.performSystemAction("home")) {
                        failLocked("home_not_applied", step)
                        return
                    }
                    index++
                }

                WorkflowStep.Recents -> {
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

                is WorkflowStep.WaitImage -> {
                    startImageWaitLocked(step.target, step)
                    return
                }

                is WorkflowStep.ClickImage -> {
                    startImageWaitLocked(step.target, step)
                    return
                }
            }
        }

        setStatus(
            WorkflowStatus(
                state = "completed",
                stepIndex = steps.size,
                stepCount = steps.size
            )
        )
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

    private fun startImageWaitLocked(target: String, step: WorkflowStep) {
        // Arm the state before the matcher so a frame that matches immediately
        // cannot race ahead of the workflow state.
        actionInFlight = true
        val error = service.startImageWatch(target)
        if (error != null) {
            actionInFlight = false
            failLocked(error, step)
            return
        }
        setStatus(statusFor("waiting", step))
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
        setStatus(
            statusFor(
                state = "failed",
                step = step,
                error = error
            )
        )
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
        error = error
    )

    private fun setStatus(newStatus: WorkflowStatus) {
        status = newStatus
        onStatusChanged(newStatus)
    }

    private fun commandName(step: WorkflowStep): String = when (step) {
        is WorkflowStep.Click -> "CLICK"
        is WorkflowStep.Wait -> "WAIT"
        is WorkflowStep.Sleep -> "SLEEP"
        is WorkflowStep.WaitImage -> "WAIT_IMG"
        is WorkflowStep.ClickImage -> "CLICK_IMG"
        WorkflowStep.Up -> "UP"
        WorkflowStep.Down -> "DOWN"
        WorkflowStep.Back -> "BACK"
        WorkflowStep.Home -> "HOME"
        WorkflowStep.Recents -> "RECENTS"
    }

    private fun targetOf(step: WorkflowStep): String? = when (step) {
        is WorkflowStep.Click -> step.target
        is WorkflowStep.Wait -> step.target
        is WorkflowStep.Sleep -> step.seconds.toString()
        is WorkflowStep.WaitImage -> step.target
        is WorkflowStep.ClickImage -> step.target
        WorkflowStep.Up,
        WorkflowStep.Down,
        WorkflowStep.Back,
        WorkflowStep.Home,
        WorkflowStep.Recents -> null
    }

    private fun sameImageName(left: String, right: String): Boolean =
        left.trim().equals(right.trim(), ignoreCase = true)

    companion object {
        fun parse(script: String): List<WorkflowStep> {
            val tokens = script
                .replace("\r", "\n")
                .split(';', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)

            return tokens.map { token ->
                val command = token.substringBefore(':').trim().uppercase(Locale.ROOT)
                val argument = token.substringAfter(':', "").trim()
                when (command) {
                    "CLICK" -> {
                        require(argument.isNotEmpty()) { "CLICK_requires_target" }
                        WorkflowStep.Click(argument)
                    }

                    "WAIT", "CHO", "CHỜ" -> {
                        require(argument.isNotEmpty()) { "WAIT_requires_target" }
                        WorkflowStep.Wait(argument)
                    }

                    "WAIT_IMG" -> {
                        require(argument.isNotEmpty()) { "WAIT_IMG_requires_target" }
                        WorkflowStep.WaitImage(argument)
                    }

                    "CLICK_IMG" -> {
                        require(argument.isNotEmpty()) { "CLICK_IMG_requires_target" }
                        WorkflowStep.ClickImage(argument)
                    }

                    "SLEEP", "REST", "NGHI", "NGHỈ" -> {
                        val seconds = argument.toDoubleOrNull()
                            ?: throw IllegalArgumentException("SLEEP_requires_seconds")
                        require(seconds >= 0.0 && seconds <= MAX_SLEEP_SECONDS) {
                            "SLEEP_seconds_out_of_range"
                        }
                        WorkflowStep.Sleep(seconds)
                    }

                    "UP" -> {
                        require(argument.isEmpty()) { "UP_does_not_take_target" }
                        WorkflowStep.Up
                    }

                    "DOWN" -> {
                        require(argument.isEmpty()) { "DOWN_does_not_take_target" }
                        WorkflowStep.Down
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
        }

        private const val MAX_SLEEP_SECONDS = 3_600.0
    }
}
