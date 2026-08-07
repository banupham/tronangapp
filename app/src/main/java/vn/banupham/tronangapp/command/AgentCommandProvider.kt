package vn.banupham.tronangapp.command

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.runtime.AgentRuntime
import vn.banupham.tronangapp.runtime.WorkflowStatus

class AgentCommandProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceLocalCaller()
        return when (uri.lastPathSegment) {
            "nodes" -> nodeCursor()
            "status" -> statusCursor()
            else -> error("Only /status and /nodes are supported")
        }
    }

    private fun statusCursor(): Cursor {
        val runtime = AgentRuntime.status
        val service = GenericAccessibilityService.instance
        val workflow = service?.workflowStatus() ?: WorkflowStatus()
        return MatrixCursor(STATUS_COLUMNS).apply {
            addRow(
                arrayOf(
                    runtime.connected,
                    runtime.packageName,
                    runtime.nodeCount,
                    runtime.generation,
                    runtime.lastEvent,
                    service != null,
                    true,
                    false,
                    workflow.state,
                    workflow.stepIndex,
                    workflow.stepCount,
                    workflow.command,
                    workflow.target,
                    workflow.error,
                    service?.socketState() ?: "disconnected",
                    service?.socketUrl()
                )
            )
        }
    }

    private fun nodeCursor(): Cursor = MatrixCursor(NODE_COLUMNS).apply {
        AgentRuntime.nodes.forEach { node ->
            addRow(
                arrayOf(
                    node.text,
                    node.contentDescription,
                    node.viewId,
                    node.className,
                    node.left,
                    node.top,
                    node.right,
                    node.bottom,
                    node.enabled,
                    node.clickable,
                    node.parentKey,
                    node.key
                )
            )
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceLocalCaller()
        val service = GenericAccessibilityService.instance
            ?: return result(false, "accessibility_service_not_connected")

        return when (method.lowercase()) {
            "click", "click_text" -> {
                val success = arg != null && service.clickText(arg)
                result(success, if (success) null else "command_not_applied")
            }

            "swipe" -> {
                val success = arg != null && service.swipe(arg)
                result(success, if (success) null else "command_not_applied")
            }

            "up" -> {
                val success = service.swipe("up")
                result(success, if (success) null else "command_not_applied")
            }

            "down" -> {
                val success = service.swipe("down")
                result(success, if (success) null else "command_not_applied")
            }

            "wait" -> {
                if (arg.isNullOrBlank()) return result(false, "WAIT_requires_target")
                workflowResult(service.runWorkflow("WAIT:$arg"))
            }

            "workflow", "workflow_run" -> {
                if (arg.isNullOrBlank()) return result(false, "empty_workflow")
                workflowResult(service.runWorkflow(arg))
            }

            "workflow_stop" -> workflowResult(service.stopWorkflow())

            "socket_connect" -> {
                if (arg.isNullOrBlank()) return result(false, "socket_url_required")
                val success = service.connectSocket(arg)
                result(success, if (success) null else "invalid_socket_url").apply {
                    putString("socket_state", service.socketState())
                    putString("socket_url", service.socketUrl())
                }
            }

            "socket_disconnect" -> {
                service.disconnectSocket(clearSavedUrl = true)
                result(true).apply {
                    putString("socket_state", service.socketState())
                }
            }

            "auto" -> result(false, "auto_actions_disabled")
            else -> result(false, "unsupported_command")
        }
    }

    private fun workflowResult(status: WorkflowStatus): Bundle {
        val success = status.state != "failed"
        return result(success, status.error).apply {
            putString("workflow_state", status.state)
            putInt("workflow_step", status.stepIndex)
            putInt("workflow_total", status.stepCount)
            putString("workflow_command", status.command)
            putString("workflow_target", status.target)
        }
    }

    private fun result(success: Boolean, error: String? = null): Bundle = Bundle().apply {
        putBoolean("success", success)
        if (error != null) putString("error", error)
    }

    private fun enforceLocalCaller() {
        val caller = Binder.getCallingUid()
        check(caller == Process.SHELL_UID || caller == Process.myUid()) {
            "Only adb shell or this app may use the command provider"
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.vn.banupham.tronangapp.status"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val STATUS_COLUMNS = arrayOf(
            "connected",
            "package",
            "nodes",
            "generation",
            "last_event",
            "service_connected",
            "click_actions_enabled",
            "auto_actions_enabled",
            "workflow_state",
            "workflow_step",
            "workflow_total",
            "workflow_command",
            "workflow_target",
            "workflow_error",
            "socket_state",
            "socket_url"
        )

        private val NODE_COLUMNS = arrayOf(
            "text",
            "description",
            "view_id",
            "class",
            "left",
            "top",
            "right",
            "bottom",
            "enabled",
            "clickable",
            "parent_key",
            "key"
        )
    }
}
