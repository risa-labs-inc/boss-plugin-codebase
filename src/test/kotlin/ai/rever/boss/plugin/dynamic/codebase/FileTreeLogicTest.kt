package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure tree/selection logic: compact-chain resolution (the
 * source of the delete-targeting bug this branch fixed), bulk-delete root
 * filtering, and the visible-row flattening that drives shift-range select.
 */
class FileTreeLogicTest {

    private fun dir(path: String, children: List<FileNode> = emptyList(), loaded: Boolean = true) = FileNode(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = true,
        children = children,
        hasChildren = if (loaded) children.isNotEmpty() else null,
        loadingState = if (loaded) NodeLoadingState.LOADED else NodeLoadingState.UNKNOWN
    )

    private fun file(path: String) = FileNode(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        hasChildren = false,
        loadingState = NodeLoadingState.LOADED
    )

    @Test
    fun `compact chain resolves display name and end node`() {
        val c = dir("/p/a/b/c", children = listOf(file("/p/a/b/c/x.txt")))
        val b = dir("/p/a/b", children = listOf(c))
        val a = dir("/p/a", children = listOf(b))

        assertEquals("a/b/c", a.getCompactDisplayName())
        assertEquals("/p/a/b/c", a.getCompactEndNode().path)
    }

    @Test
    fun `compact chain stops at unloaded or multi-child nodes`() {
        val unloadedChild = dir("/p/a/b", loaded = false)
        val a = dir("/p/a", children = listOf(unloadedChild))
        assertEquals("a", a.getCompactDisplayName())
        assertEquals("/p/a", a.getCompactEndNode().path)

        val multi = dir("/p/m", children = listOf(dir("/p/m/x"), dir("/p/m/y")))
        assertEquals("m", multi.getCompactDisplayName())
        assertEquals("/p/m", multi.getCompactEndNode().path)
    }

    @Test
    fun `filterNestedPaths drops descendants but keeps prefix-sibling paths`() {
        val paths = listOf("/p/a", "/p/a/b", "/p/a/b/c", "/p/c", "/p/ab")

        // "/p/ab" starts with "/p/a" as a string but is NOT nested under it
        assertEquals(listOf("/p/a", "/p/c", "/p/ab"), FileTreeUtils.filterNestedPaths(paths))
    }

    @Test
    fun `filterNestedPaths keeps everything when nothing is nested`() {
        val paths = listOf("/p/x", "/p/y", "/p/z")
        assertEquals(paths, FileTreeUtils.filterNestedPaths(paths))
    }

    @Test
    fun `visibleRowPaths walks expanded dirs through compact chains`() {
        // /p
        //  ├── a          (compacts to a/b; expansion keyed on /p/a)
        //  │    └── b
        //  │        ├── one.txt
        //  │        └── two/   (not loaded)
        //  └── z.txt
        val b = dir("/p/a/b", children = listOf(file("/p/a/b/one.txt"), dir("/p/a/b/two", loaded = false)))
        val a = dir("/p/a", children = listOf(b))
        val root = dir("/p", children = listOf(a, file("/p/z.txt")))

        assertEquals(
            listOf("/p/a", "/p/a/b/one.txt", "/p/a/b/two", "/p/z.txt"),
            FileTreeUtils.visibleRowPaths(root, expanded = setOf("/p/a"))
        )
    }

    @Test
    fun `visibleRowPaths hides children of collapsed dirs`() {
        val b = dir("/p/a/b", children = listOf(file("/p/a/b/one.txt")))
        val a = dir("/p/a", children = listOf(b))
        val root = dir("/p", children = listOf(a, file("/p/z.txt")))

        assertEquals(
            listOf("/p/a", "/p/z.txt"),
            FileTreeUtils.visibleRowPaths(root, expanded = emptySet())
        )
    }

    @Test
    fun `visibleRowPaths of null tree is empty`() {
        assertEquals(emptyList(), FileTreeUtils.visibleRowPaths(null, expanded = setOf("/p")))
    }

    @Test
    fun `flattenVisibleRows assigns levels and emits placeholder rows`() {
        val loadedEmpty = dir("/p/empty") // loaded, no children
        val pending = dir("/p/pending", loaded = false) // expanded but not loaded yet
        val b = dir("/p/a/b", children = listOf(file("/p/a/b/one.txt")))
        val a = dir("/p/a", children = listOf(b))
        val root = dir("/p", children = listOf(a, loadedEmpty, pending))

        val rows = FileTreeUtils.flattenVisibleRows(
            root,
            expanded = setOf("/p/a", "/p/empty", "/p/pending")
        )

        assertEquals(
            listOf(
                VisibleRow.Node(a, 0),
                VisibleRow.Node(b.children[0], 1), // one.txt under compacted a/b
                VisibleRow.Node(loadedEmpty, 0),
                VisibleRow.Empty("/p/empty", 0),
                VisibleRow.Node(pending, 0),
                VisibleRow.Loading("/p/pending", 0)
            ),
            rows
        )
    }

    @Test
    fun `flattenVisibleRows keys are unique per row kind`() {
        val empty = dir("/p/x")
        val root = dir("/p", children = listOf(empty))
        val rows = FileTreeUtils.flattenVisibleRows(root, expanded = setOf("/p/x"))
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
