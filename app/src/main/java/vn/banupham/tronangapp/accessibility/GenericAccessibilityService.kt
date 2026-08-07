package vn.banupham.tronangapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import vn.banupham.tronangapp.runtime.AgentRuntime
import vn.banupham.tronangapp.runtime.NodeSnapshot

class GenericAccessibilityService : AccessibilityService() {
    private data class ClickCandidate(
        val node: AccessibilityNodeInfo,
        val priority: Int
    )

    private var generation = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshSnapshot("service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val label = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) }
        refreshSnapshot(label)
    }

    private fun refreshSnapshot(lastEvent: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val activePackage = root.packageName?.toString()
        val output = ArrayList<NodeSnapshot>()
        collectNode(root, parentKey = null, key = "0", output = output, depth = 0)
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
                clickable = node.isClickable,
                parentKey = parentKey
            )
        }

        for (index in 0 until node.childCount) {
            if (output.size >= MAX_NODES) break
            val child = node.getChild(index) ?: continue
            collectNode(
                node = child,
                parentKey = key,
                key = "$key.$index",
                output = output,
                depth = depth + 1
            )
        }
    }

    fun swipe(direction: String): Boolean {
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
            null,
            null
        )
    }

    fun clickText(requestedText: String): Boolean {
        val expected = normalizeForMatch(requestedText)
        if (expected.isBlank()) return false

        val root = rootInActiveWindow ?: return false
        val candidates = ArrayList<ClickCandidate>()
        collectClickCandidates(root, expected, candidates, depth = 0)

        // Exact text/description is preferred. If there is no exact match,
        // a node containing the requested phrase is allowed. Matching ignores
        // case and every kind of whitespace, including line breaks and NBSP.
        for (candidate in candidates.sortedBy { it.priority }) {
            val target = clickableNode(candidate.node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun collectClickCandidates(
        node: AccessibilityNodeInfo,
        expected: String,
        output: MutableList<ClickCandidate>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_CLICK_CANDIDATES) return

        if (node.isVisibleToUser) {
            val text = normalizeForMatch(node.text?.toString().orEmpty())
            val description = normalizeForMatch(node.contentDescription?.toString().orEmpty())
            val priority = when {
                text.isNotBlank() && text == expected -> 0
                description.isNotBlank() && description == expected -> 1
                text.isNotBlank() && text.contains(expected) -> 2
                description.isNotBlank() && description.contains(expected) -> 3
                else -> null
            }
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

    private fun normalizeForMatch(value: String): String = value
        .lowercase(Locale.ROOT)
        .filterNot { ch ->
            ch.isWhitespace() ||
                Character.isSpaceChar(ch) ||
                ch == '\u200B' ||
                ch == '\uFEFF'
        }

    override fun onInterrupt() {
        AgentRuntime.disconnect()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AgentRuntime.disconnect()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GenericAccessibilityService? = null
            private set

        private const val SWIPE_DURATION_MS = 350L
        private const val MAX_NODES = 10_000
        private const val MAX_DEPTH = 100
        private const val MAX_CLICK_PARENT_DEPTH = 8
        private const val MAX_CLICK_CANDIDATES = 1_000
    }
}
