package ai.rever.boss.plugin.dynamic.codebase

/**
 * Utility functions for immutable file tree operations.
 */
object FileTreeUtils {

    /**
     * Finds a node by its path using recursive DFS.
     */
    fun findNodeByPath(root: FileNode?, targetPath: String): FileNode? {
        if (root == null) return null
        if (root.path == targetPath) return root
        for (child in root.children) {
            val found = findNodeByPath(child, targetPath)
            if (found != null) return found
        }
        return null
    }

    /**
     * Creates a new tree with the node at targetPath updated using the provided transform.
     * This ensures immutable state updates for proper Compose recomposition.
     * Only nodes along the path are copied; other subtrees are shared.
     */
    fun updateNodeAtPath(
        root: FileNode,
        targetPath: String,
        update: (FileNode) -> FileNode
    ): FileNode {
        if (root.path == targetPath) {
            return update(root)
        }

        // Recursively update, creating new nodes along the path to the target
        return root.copy(
            children = root.children.map { child ->
                if (targetPath.startsWith(child.path + "/") || targetPath == child.path) {
                    updateNodeAtPath(child, targetPath, update)
                } else {
                    child
                }
            }
        )
    }
}
