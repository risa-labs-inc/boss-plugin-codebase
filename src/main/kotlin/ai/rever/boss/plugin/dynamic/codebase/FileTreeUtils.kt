package ai.rever.boss.plugin.dynamic.codebase

/**
 * One renderable row of the file tree, produced by
 * [FileTreeUtils.flattenVisibleRows]. The panel renders one LazyColumn item
 * per row (issue #8), so deep expanded trees stay virtualized instead of
 * composing recursively inside a single item.
 */
sealed interface VisibleRow {
    val level: Int

    /** Stable LazyColumn key — unique across row kinds. */
    val key: String

    /** A file or directory row (directories may represent a compacted chain). */
    data class Node(val node: FileNode, override val level: Int) : VisibleRow {
        override val key: String get() = "n:${node.path}"
    }

    /** Loading indicator under an expanded directory whose children are being fetched. */
    data class Loading(val parentPath: String, override val level: Int) : VisibleRow {
        override val key: String get() = "l:$parentPath"
    }

    /** "(empty)" placeholder under an expanded, loaded, childless directory. */
    data class Empty(val parentPath: String, override val level: Int) : VisibleRow {
        override val key: String get() = "e:$parentPath"
    }
}

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
     * Flattens a tree into the exact row list the UI renders: children of a
     * directory are visible when its row path is in [expanded], compacted
     * chains ("a/b/c") contribute the end node's children under the chain
     * top's path, and expanded directories that are loading / empty get a
     * placeholder row. This IS the render order — the panel and the
     * selection logic both derive from it.
     */
    fun flattenVisibleRows(root: FileNode?, expanded: Set<String>): List<VisibleRow> {
        if (root == null) return emptyList()
        val rows = mutableListOf<VisibleRow>()
        fun walk(node: FileNode, level: Int) {
            rows.add(VisibleRow.Node(node, level))
            if (node.isDirectory && expanded.contains(node.path)) {
                val endNode = node.getCompactEndNode()
                val children = endNode.children
                val isLoading = endNode.loadingState == NodeLoadingState.CHECKING
                when {
                    isLoading || (children.isEmpty() && !endNode.isLoaded) ->
                        rows.add(VisibleRow.Loading(node.path, level))
                    children.isEmpty() ->
                        rows.add(VisibleRow.Empty(node.path, level))
                    else -> children.forEach { walk(it, level + 1) }
                }
            }
        }
        root.children.forEach { walk(it, 0) }
        return rows
    }

    /**
     * Paths of the visible node rows, in render order (shift-range select,
     * copy ordering).
     */
    fun visibleRowPaths(root: FileNode?, expanded: Set<String>): List<String> =
        flattenVisibleRows(root, expanded).mapNotNull { (it as? VisibleRow.Node)?.node?.path }

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
