package vn.banupham.tronangapp.runtime

import java.util.Locale

data class NodeSnapshot(
    val key: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val enabled: Boolean,
    val clickable: Boolean,
    val parentKey: String?
)

data class RuntimeStatus(
    val connected: Boolean = false,
    val packageName: String? = null,
    val nodeCount: Int = 0,
    val generation: Long = 0,
    val lastEvent: String? = null
)

object AgentRuntime {
    @Volatile
    var status: RuntimeStatus = RuntimeStatus()
        private set

    @Volatile
    var nodes: List<NodeSnapshot> = emptyList()
        private set

    @Volatile
    private var visibleValues: Set<String> = emptySet()

    @Volatile
    private var readyValues: Set<String> = emptySet()

    @Synchronized
    fun update(packageName: String?, newNodes: List<NodeSnapshot>, generation: Long, lastEvent: String?) {
        nodes = newNodes

        val visible = LinkedHashSet<String>()
        val ready = LinkedHashSet<String>()
        newNodes.forEach { node ->
            listOfNotNull(node.text, node.contentDescription).forEach { raw ->
                val normalized = normalizeForMatch(raw)
                if (normalized.isNotBlank()) {
                    visible += normalized
                    if (node.enabled) ready += normalized
                }
            }
        }
        visibleValues = visible
        readyValues = ready

        status = RuntimeStatus(
            connected = true,
            packageName = packageName,
            nodeCount = newNodes.size,
            generation = generation,
            lastEvent = lastEvent
        )
    }

    fun isVisibleTarget(requested: String): Boolean = matches(visibleValues, requested)

    // WAIT is satisfied only by a node that Accessibility reports as both
    // visible and enabled. It therefore reacts to hidden/disabled -> ready
    // transitions without using a fixed sleep timer.
    fun isReadyTarget(requested: String): Boolean = matches(readyValues, requested)

    private fun matches(values: Set<String>, requested: String): Boolean {
        val expected = normalizeForMatch(requested)
        if (expected.isBlank()) return false
        return values.any { value -> value == expected || value.contains(expected) }
    }

    fun normalizeForMatch(value: String): String = value
        .lowercase(Locale.ROOT)
        .filterNot { ch ->
            ch.isWhitespace() ||
                Character.isSpaceChar(ch) ||
                ch == '\u200B' ||
                ch == '\uFEFF'
        }

    @Synchronized
    fun disconnect() {
        nodes = emptyList()
        visibleValues = emptySet()
        readyValues = emptySet()
        status = RuntimeStatus()
    }
}
