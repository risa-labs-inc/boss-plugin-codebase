package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * FileTreeUtils.mergeFreshChildren — the watcher-refresh merge that keeps
 * loaded subtrees alive while taking the fresh scan as the authority on
 * what exists.
 */
class FileTreeMergeTest {

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
    fun `keeps the loaded subtree of a surviving directory`() {
        val loadedSub = dir("/p/a", children = listOf(file("/p/a/x.txt")))
        val current = listOf(loadedSub, file("/p/old.txt"))
        val fresh = listOf(dir("/p/a", loaded = false), file("/p/new.txt"))

        val merged = FileTreeUtils.mergeFreshChildren(current, fresh)

        assertEquals(listOf("/p/a", "/p/new.txt"), merged.map { it.path })
        assertSame(loadedSub, merged[0], "loaded directory subtree must be preserved as-is")
    }

    @Test
    fun `fresh list is the authority on additions and removals`() {
        val current = listOf(file("/p/gone.txt"))
        val fresh = listOf(file("/p/here.txt"))

        assertEquals(listOf("/p/here.txt"), FileTreeUtils.mergeFreshChildren(current, fresh).map { it.path })
    }

    @Test
    fun `a path that changed kind takes the fresh node`() {
        val current = listOf(dir("/p/thing", children = listOf(file("/p/thing/inner"))))
        val fresh = listOf(file("/p/thing"))

        val merged = FileTreeUtils.mergeFreshChildren(current, fresh)
        assertEquals(false, merged.single().isDirectory)
    }

    @Test
    fun `unloaded surviving directory takes the fresh node`() {
        val current = listOf(dir("/p/a", loaded = false))
        val freshNode = dir("/p/a", loaded = false).copy(hasChildren = true)
        val merged = FileTreeUtils.mergeFreshChildren(current, listOf(freshNode))
        assertSame(freshNode, merged.single())
    }
}
