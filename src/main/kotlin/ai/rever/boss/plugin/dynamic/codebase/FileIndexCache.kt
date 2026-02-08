package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.NodeLoadingStateData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * LRU cache for file system nodes with dynamic loading.
 * Converts between plugin API's FileNodeData and our FileNode type.
 */
class FileIndexCache(
    private val maxSize: Int = 1000,
    private val maxDepthInitial: Int = 2,
    private val fileSystemProvider: FileSystemDataProvider?
) {
    private val cache = mutableMapOf<String, CachedNode>()
    private val accessOrder = mutableListOf<String>()
    private val mutex = Mutex()

    data class CachedNode(
        val node: FileNode,
        var lastAccessed: Long = Clock.System.now().epochSeconds,
        var isFullyLoaded: Boolean = false,
        var loadDepth: Int = 0
    )

    suspend fun getNode(path: String, forceReload: Boolean = false): FileNode? = mutex.withLock {
        if (!forceReload) {
            cache[path]?.let { cached ->
                // Update access order
                accessOrder.remove(path)
                accessOrder.add(0, path)
                cached.lastAccessed = Clock.System.now().epochSeconds
                return cached.node
            }
        }

        // Load node from file system
        val nodeData = fileSystemProvider?.scanDirectory(path)
        val node = nodeData?.let { convertToFileNode(it) }

        node?.let {
            addToCache(path, it, maxDepthInitial)
        }

        return node
    }

    private fun addToCache(path: String, node: FileNode, depth: Int) {
        // Evict old entries if needed
        while (cache.size >= maxSize && accessOrder.isNotEmpty()) {
            val oldestPath = accessOrder.removeLast()
            cache.remove(oldestPath)
        }

        cache[path] = CachedNode(node, loadDepth = depth)
        accessOrder.add(0, path)

        // Also cache child directories for quick access
        if (node.isDirectory && depth > 0) {
            node.children.forEach { child ->
                if (child.isDirectory && !cache.containsKey(child.path)) {
                    addToCache(child.path, child, depth - 1)
                }
            }
        }
    }

    suspend fun clearCache() = mutex.withLock {
        cache.clear()
        accessOrder.clear()
    }

    /**
     * Convert plugin API's FileNodeData to our FileNode type.
     */
    private fun convertToFileNode(data: FileNodeData): FileNode {
        return FileNode(
            name = data.name,
            path = data.path,
            isDirectory = data.isDirectory,
            children = data.children.map { convertToFileNode(it) },
            hasChildren = data.hasChildren ?: (data.isDirectory && data.children.isNotEmpty()),
            loadingState = when (data.loadingState) {
                NodeLoadingStateData.UNKNOWN -> NodeLoadingState.UNKNOWN
                NodeLoadingStateData.CHECKING -> NodeLoadingState.CHECKING
                NodeLoadingStateData.LOADED -> NodeLoadingState.LOADED
            },
            loadDepth = data.loadDepth
        )
    }
}
