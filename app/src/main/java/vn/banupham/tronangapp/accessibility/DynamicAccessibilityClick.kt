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
 */
object DynamicAccessibilityClick {
    enum class StartResult {
        COMPLETED,
        STARTED,
        NOT_FOUND,
        NOT_STARTED
    }

    fun start(
        service: GenericAccessibilityService,
        className: String?,
        descriptionRegex: Regex,
        onComplete: (Boolean) -> Unit
    ): StartResult {
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
                classMatches(node, className) &&
                descriptionMatches(node, descriptionRegex)
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

    private fun classMatches(node: AccessibilityNodeInfo, expectedClass: String?): Boolean {
        if (expectedClass.isNullOrBlank()) return true
        return node.className?.toString()?.equals(expectedClass.trim(), ignoreCase = true) == true
    }

    private fun descriptionMatches(node: AccessibilityNodeInfo, regex: Regex): Boolean {
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        return description.isNotEmpty() && regex.matches(description)
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
