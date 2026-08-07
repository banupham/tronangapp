package vn.banupham.tronangapp.runtime

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

    @Synchronized
    fun update(packageName: String?, newNodes: List<NodeSnapshot>, generation: Long, lastEvent: String?) {
        nodes = newNodes
        status = RuntimeStatus(
            connected = true,
            packageName = packageName,
            nodeCount = newNodes.size,
            generation = generation,
            lastEvent = lastEvent
        )
    }

    @Synchronized
    fun disconnect() {
        nodes = emptyList()
        status = RuntimeStatus()
    }
}
