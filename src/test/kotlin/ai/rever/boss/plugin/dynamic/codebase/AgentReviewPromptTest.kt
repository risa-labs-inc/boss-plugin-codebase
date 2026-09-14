package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the review brief the Atlas agent receives. */
class AgentReviewPromptTest {

    private fun status(path: String, staged: Boolean, type: GitFileStatusTypeData) =
        GitFileStatusData(
            path = path,
            indexStatus = if (staged) type else null,
            workTreeStatus = if (staged) null else type,
            isStaged = staged,
            isUnstaged = !staged,
        )

    @Test
    fun `no changes says so instead of an empty review`() {
        val prompt = AgentReviewPrompt.build("/p", emptyList(), emptyList(), null)
        assertTrue("no uncommitted changes" in prompt)
        assertTrue("/p" in prompt)
    }

    @Test
    fun `small diff is inlined with the file listing`() {
        val staged = listOf(status("a.kt", true, GitFileStatusTypeData.MODIFIED))
        val diff = "diff --git a.kt\n+hello"
        val prompt = AgentReviewPrompt.build("/p", staged, emptyList(), diff)
        assertTrue("Staged files:" in prompt)
        assertTrue("[MODIFIED] a.kt" in prompt)
        assertTrue("Full diff:" in prompt)
        assertTrue("+hello" in prompt)
        assertTrue("Do not apply any edits" in prompt)
    }

    @Test
    fun `a truncated diff is inlined with a note, not dropped`() {
        // The collector truncates to fit and then appends its own header and
        // marker, so a truncated result can land slightly OVER the budget.
        // The old length test replaced it with tool instructions - dropping
        // the diff exactly in the case the truncation exists to serve.
        val diff = "x".repeat(AgentReviewPrompt.INLINE_DIFF_BUDGET + 1) + "\n" + AgentReviewPrompt.TRUNCATION_MARKER + "\n"
        val unstaged = listOf(status("big.txt", false, GitFileStatusTypeData.MODIFIED))
        val prompt =
            AgentReviewPrompt.build("/p", emptyList(), unstaged, diff, diffTruncated = true)
        assertTrue(diff in prompt, "the truncated diff must still be inlined")
        assertTrue("INCOMPLETE" in prompt)
        assertTrue("git_diff" in prompt)
        assertTrue("[MODIFIED] big.txt" in prompt)
    }

    @Test
    fun `truncation is announced from the flag, not sniffed out of the text`() {
        // A collection that simply STOPPED early - a dozen medium files, budget
        // gone at file seven - carries no marker anywhere in the text. Keying
        // the warning off the marker made exactly that case read as complete.
        val whole = "--- a.kt\n+one\n\n--- b.kt\n+two\n"
        val unstaged = listOf(status("a.kt", false, GitFileStatusTypeData.MODIFIED))
        assertFalse(AgentReviewPrompt.TRUNCATION_MARKER in whole)

        val cut = AgentReviewPrompt.build("/p", emptyList(), unstaged, whole, diffTruncated = true)
        assertTrue("INCOMPLETE" in cut, "a short-stopped collection must still say so")

        val complete = AgentReviewPrompt.build("/p", emptyList(), unstaged, whole, diffTruncated = false)
        assertFalse("INCOMPLETE" in complete, "a complete diff must not be hedged")
    }

    @Test
    fun `missing diff degrades to tool instructions`() {
        val unstaged = listOf(status("a.kt", false, GitFileStatusTypeData.ADDED))
        val prompt = AgentReviewPrompt.build("/p", emptyList(), unstaged, null)
        assertTrue("git_diff" in prompt)
    }
}
