package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DiffHunk
import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitDiffData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * compactDiff turns a whole-file-context diff (the host uses -U100000) into the
 * changed lines plus a little context - so a char-budgeted prompt is not spent on
 * context padding and never comes back empty for a small change in a big file.
 */
class GitTabCompactDiffTest {
    private val vm =
        CodebaseGitViewModel(
            git = null,
            onAgentReview = {},
            getProjectPath = { null },
        )

    @AfterTest
    fun dispose() {
        // The view model starts a CoroutineScope in its constructor; without
        // this the class leaks one per test run.
        vm.dispose()
    }

    private fun ctx(t: String) = DiffLine(t, DiffLineKind.CONTEXT)

    private fun add(t: String) = DiffLine(t, DiffLineKind.ADDED)

    private fun diff(lines: List<DiffLine>) =
        GitDiffData(
            path = "F.kt",
            additions = 1,
            deletions = 0,
            hunks = listOf(DiffHunk(1, lines.size, 1, lines.size, "", lines)),
        )

    @Test
    fun `one change in a large file yields a small, non-empty compact diff`() {
        val lines = (1..500).map { ctx("line $it") }.toMutableList()
        lines[250] = add("the new line")
        val out = vm.compactDiff(diff(lines), context = 2)
        assertTrue(out.isNotEmpty(), "compact diff was empty for a real change")
        assertTrue(out.contains("+the new line"), out)
        assertTrue(out.length < 200, "expected a compact result, got ${out.length} chars")
    }

    @Test
    fun `two distant changes collapse the unchanged run between them`() {
        val lines = (1..500).map { ctx("line $it") }.toMutableList()
        lines[100] = add("first change")
        lines[400] = add("second change")
        val out = vm.compactDiff(diff(lines), context = 2)
        assertTrue(out.contains("+first change") && out.contains("+second change"), out)
        assertTrue(out.contains("…"), "the gap between the two changes should collapse to an ellipsis")
    }

    @Test
    fun `no changes yields empty`() {
        assertEquals("", vm.compactDiff(diff(listOf(ctx("a"), ctx("b")))))
    }
}
