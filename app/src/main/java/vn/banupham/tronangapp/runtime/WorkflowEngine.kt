package vn.banupham.tronangapp.runtime

import java.util.Locale
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService

sealed class WorkflowStep {
    data class Click(val target: String) : WorkflowStep()
    data class Wait(val target: String) : WorkflowStep()
    data object Up : WorkflowStep()
    data object Down : WorkflowStep()
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

    @Synchronized
    fun start(script: String): WorkflowStatus {
        val parsed = try {
            parse(script)
        } catch (error: IllegalArgumentException) {
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

        if (parsed.isEmpty()) {
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
        steps = emptyList()
        index = 0
        actionInFlight = false
        setStatus(WorkflowStatus(state = "stopped"))
        return status
    }

    @Synchronized
    fun onTreeUpdated() {
        if (status.state == "waiting" && !actionInFlight) {
            advanceLocked()
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
        setStatus(statusFor("running", step))
        val started = service.swipeForWorkflow(direction) { success ->
            onSwipeFinished(success, step)
        }
        if (!started) {
            actionInFlight = false
            failLocked("swipe_not_started", step)
        }
    }

    @Synchronized
    private fun onSwipeFinished(success: Boolean, step: WorkflowStep) {
        if (!actionInFlight) return
        actionInFlight = false
        if (!success) {
            failLocked("swipe_cancelled", step)
            return
        }
        index++
        advanceLocked()
    }

    private fun failLocked(error: String, step: WorkflowStep) {
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
        WorkflowStep.Up -> "UP"
        WorkflowStep.Down -> "DOWN"
    }

    private fun targetOf(step: WorkflowStep): String? = when (step) {
        is WorkflowStep.Click -> step.target
        is WorkflowStep.Wait -> step.target
        WorkflowStep.Up,
        WorkflowStep.Down -> null
    }

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
                    "UP" -> {
                        require(argument.isEmpty()) { "UP_does_not_take_target" }
                        WorkflowStep.Up
                    }
                    "DOWN" -> {
                        require(argument.isEmpty()) { "DOWN_does_not_take_target" }
                        WorkflowStep.Down
                    }
                    else -> throw IllegalArgumentException("unsupported_step:$command")
                }
            }
        }
    }
}
