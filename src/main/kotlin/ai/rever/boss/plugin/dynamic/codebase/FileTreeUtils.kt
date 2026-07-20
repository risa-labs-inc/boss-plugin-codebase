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
     * Flattens a tree into the row order the UI renders: children of a
     * directory are visible when its row path is in [expanded], and compacted
     * chains ("a/b/c") contribute the end node's children under the chain
     * top's path — mirroring FileTreeItem.
     */
    fun visibleRowPaths(root: FileNode?, expanded: Set<String>): List<String> {
        if (root == null) return emptyList()
        val result = mutableListOf<String>()
        fun walk(node: FileNode) {
            result.add(node.path)
            if (node.isDirectory && expanded.contains(node.path)) {
                node.getCompactEndNode().children.forEach { walk(it) }
            }
        }
        root.children.forEach { walk(it) }
        return result
    }

    /**
     * Drops paths nested under another path in the list — operating on the
     * ancestor covers them (used by bulk delete).
     */
    fun filterNestedPaths(
        paths: List<String>,
        separator: Char = PathUtils.platformSeparator
    ): List<String> =
        paths.filter { p -> paths.none { other -> other != p && PathUtils.isNestedUnder(p, other, separator) } }

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
                if (PathUtils.isNestedUnder(targetPath, child.path) || targetPath == child.path) {
                    updateNodeAtPath(child, targetPath, update)
                } else {
                    child
                }
            }
        )
    }
}
