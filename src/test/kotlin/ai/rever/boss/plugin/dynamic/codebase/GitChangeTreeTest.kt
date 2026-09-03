package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The change group's tree mode. Row order and compaction are pinned because
 * the group re-renders on a short refresh: rows that reorder or re-indent
 * between ticks make the list unusable.
 */
class GitChangeTreeTest {

    private fun file(path: String) =
        GitFileStatusData(
            path = path,
            indexStatus = null,
            workTreeStatus = GitFileStatusTypeData.MODIFIED,
            isStaged = false,
            isUnstaged = true,
        )

    private fun render(paths: List<String>, collapsed: Set<String> = emptySet()) =
        GitChangeTree.rows(paths.map { file(it) }, collapsed).map { row ->
            when (row) {
                is GitChangeTree.Row.Directory -> "${"  ".repeat(row.depth)}[${row.label}] (${row.fileCount})"
                is GitChangeTree.Row.FileRow -> "${"  ".repeat(row.depth)}${row.name}"
            }
        }

    @Test
    fun `files in the root sit at depth zero`() {
        assertEquals(listOf("a.kt", "b.kt"), render(listOf("b.kt", "a.kt")))
    }

    @Test
    fun `a shared directory becomes one collapsible row`() {
        assertEquals(
            listOf("[src] (2)", "  a.kt", "  b.kt"),
            render(listOf("src/b.kt", "src/a.kt")),
        )
    }

    @Test
    fun `a single-child directory chain is compacted onto one row`() {
        // Three rows of indentation to say "there is only one way down" is
        // what VS Code's explorer compaction exists to avoid.
        assertEquals(
            listOf("[src/main/kotlin] (1)", "  A.kt"),
            render(listOf("src/main/kotlin/A.kt")),
        )
    }

    @Test
    fun `a branching directory is not compacted`() {
        val out = render(listOf("src/main/A.kt", "src/test/B.kt"))
        assertEquals(
            listOf("[src] (2)", "  [main] (1)", "    A.kt", "  [test] (1)", "    B.kt"),
            out,
        )
    }

    @Test
    fun `collapsing a directory hides its children but keeps its row`() {
        val out = render(listOf("src/a.kt", "src/b.kt", "top.kt"), collapsed = setOf("src"))
        assertEquals(listOf("[src] (2)", "top.kt"), out)
    }

    @Test
    fun `directories come before files at the same level`() {
        val out = render(listOf("z.kt", "sub/a.kt"))
        assertTrue(out.first().startsWith("[sub]"), out.toString())
        assertEquals("z.kt", out.last())
    }

    @Test
    fun `the file count covers every depth beneath a directory`() {
        val rows = GitChangeTree.rows(
            listOf("src/a.kt", "src/deep/b.kt", "src/deep/c.kt").map { file(it) },
            emptySet(),
        )
        val src = rows.filterIsInstance<GitChangeTree.Row.Directory>().first { it.label == "src" }
        assertEquals(3, src.fileCount)
    }

    @Test
    fun `order is stable across identical inputs in any order`() {
        val a = render(listOf("src/b.kt", "src/a.kt", "z.kt"))
        val b = render(listOf("z.kt", "src/a.kt", "src/b.kt"))
        assertEquals(a, b)
    }

    @Test
    fun `an empty change set renders nothing`() {
        assertTrue(GitChangeTree.rows(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `the layout toggle round-trips and defaults to tree`() {
        // Nothing stored, or something unrecognisable stored, means TREE: a
        // real change set is mostly repeated directory prefixes, which is what
        // the tree folds away.
        assertEquals(GitChangeLayout.TREE, GitChangeLayout.fromStorage(null))
        assertEquals(GitChangeLayout.TREE, GitChangeLayout.fromStorage("nonsense"))
        assertEquals(GitChangeLayout.LIST, GitChangeLayout.fromStorage("list"))
        assertEquals(GitChangeLayout.TREE, GitChangeLayout.fromStorage("tree"))
        assertEquals(GitChangeLayout.TREE, GitChangeLayout.LIST.toggled())
        assertEquals(GitChangeLayout.LIST, GitChangeLayout.TREE.toggled())
    }
}
