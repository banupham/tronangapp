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
        val status = AgentRuntime.status
        return MatrixCursor(STATUS_COLUMNS).apply {
            addRow(
                arrayOf(
                    status.connected,
                    status.packageName,
                    status.nodeCount,
                    status.generation,
                    status.lastEvent,
                    GenericAccessibilityService.instance != null,
                    true,
                    false
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

        if (service == null) {
            return Bundle().apply {
                putBoolean("success", false)
                putString("error", "accessibility_service_not_connected")
            }
        }

        val success = when (method) {
            "swipe" -> arg != null && service.swipe(arg)
            "click_text" -> arg != null && service.clickText(arg)
            "auto" -> false
            else -> false
        }

        return Bundle().apply {
            putBoolean("success", success)
            when {
                method == "auto" -> putString("error", "auto_actions_disabled")
                method != "swipe" && method != "click_text" -> putString("error", "unsupported_command")
                !success -> putString("error", "command_not_applied")
            }
        }
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
            "auto_actions_enabled"
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
            "clickable",
            "parent_key",
            "key"
        )
    }
}
