package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileMatch
import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel's pure presentation rules: branch derivation, result grouping,
 * the name/directory split every dense row uses, and the relative-date
 * format. These are the parts of the FILES/SEARCH/GIT rework that can be
 * pinned without a Compose harness.
 */
class PanelUiLogicTest {

    private fun node(refs: List<String>) =
        GitCommitNodeData(
            hash = "abc123def",
            shortHash = "abc123d",
            subject = "s",
            author = "a",
            authorEmail = "a@b.c",
            date = 0L,
            refs = refs,
            parents = emptyList(),
        )

    // ---- branch name ------------------------------------------------------

    @Test
    fun `branch comes from the HEAD arrow, not from a remote ref`() {
        val branch =
            CodebaseGitViewModel.branchOf(
                listOf(node(listOf("origin/feat/x", "HEAD -> feat/x", "tag: v1"))),
            )
        assertEquals("feat/x", branch)
    }

    @Test
    fun `a detached HEAD reports detached rather than a remote name`() {
        assertEquals("detached", CodebaseGitViewModel.branchOf(listOf(node(listOf("HEAD", "origin/main")))))
    }

    @Test
    fun `no refs and no commits yield an empty branch`() {
        assertEquals("", CodebaseGitViewModel.branchOf(listOf(node(emptyList()))))
        assertEquals("", CodebaseGitViewModel.branchOf(emptyList()))
    }

    // ---- search result grouping ------------------------------------------

    private fun match(path: String, line: Int, column: Int = 1) =
        FileMatch(path = path, line = line, column = column, matchLength = 3, contextLine = "ctx")

    @Test
    fun `results group by file, path-sorted, matches in line order`() {
        val grouped =
            groupMatchesByFile(
                listOf(
                    match("src/z.kt", 9),
                    match("src/a.kt", 20),
                    match("src/a.kt", 2),
                    match("src/z.kt", 1),
                ),
            )

        assertEquals(listOf("src/a.kt", "src/z.kt"), grouped.map { it.path })
        assertEquals(listOf(2, 20), grouped[0].matches.map { it.line })
        assertEquals(listOf(1, 9), grouped[1].matches.map { it.line })
    }

    @Test
    fun `two matches on one line sort by column`() {
        val grouped = groupMatchesByFile(listOf(match("a.kt", 3, column = 40), match("a.kt", 3, column = 5)))
        assertEquals(listOf(5, 40), grouped.single().matches.map { it.column })
    }

    // ---- row display ------------------------------------------------------

    @Test
    fun `path splits into name and directory`() {
        assertEquals("Foo.kt" to "src/main", splitPathForDisplay("src/main/Foo.kt"))
        assertEquals("README.md" to "", splitPathForDisplay("README.md"))
    }

    // ---- relative dates ---------------------------------------------------

    @Test
    fun `relative date is compact and bucketed`() {
        val now = System.currentTimeMillis() / 1000L
        assertEquals("now", formatRelativeDate(now))
        assertEquals("5m", formatRelativeDate(now - 5 * 60))
        assertEquals("3h", formatRelativeDate(now - 3 * 3600))
        assertEquals("2d", formatRelativeDate(now - 2 * 86_400))
        assertEquals("2w", formatRelativeDate(now - 15 * 86_400))
        assertEquals("3mo", formatRelativeDate(now - 95 * 86_400))
        assertEquals("2y", formatRelativeDate(now - 800 * 86_400))
    }

    @Test
    fun `a missing commit date renders as nothing, not as the epoch`() {
        assertEquals("", formatRelativeDate(0L))
        assertEquals("", formatRelativeDate(-1L))
        assertEquals("", formatCommitDate(0L))
    }

    // ---- status grouping --------------------------------------------------

    private fun status(
        path: String,
        index: GitFileStatusTypeData?,
        workTree: GitFileStatusTypeData?,
        staged: Boolean,
        unstaged: Boolean,
    ) = GitFileStatusData(path, index, workTree, staged, unstaged)

    @Test
    fun `untracked files stay out of the changes group`() {
        val untracked = status("new.kt", null, GitFileStatusTypeData.UNTRACKED, staged = false, unstaged = true)
        val modified = status("old.kt", null, GitFileStatusTypeData.MODIFIED, staged = false, unstaged = true)

        assertTrue(untracked.isUntracked)
        assertTrue(!modified.isUntracked)
    }

    @Test
    fun `a partially staged file appears in both staged and changes`() {
        // `git add -p` leaves index and work tree both dirty; the file has to
        // show under STAGED CHANGES *and* CHANGES, as it does in VS Code.
        val partial =
            status(
                "half.kt",
                GitFileStatusTypeData.MODIFIED,
                GitFileStatusTypeData.MODIFIED,
                staged = true,
                unstaged = true,
            )
        assertTrue(partial.isStaged)
        assertTrue(partial.isUnstaged)
        assertTrue(!partial.isUntracked, "a tracked edit must not be classed untracked")
    }

    @Test
    fun `every status type has a distinct single-character glyph`() {
        val glyphs = GitFileStatusTypeData.entries.map { statusGlyph(it) }
        assertTrue(glyphs.all { it.length == 1 }, "multi-character glyph would widen every row: $glyphs")
        assertEquals(glyphs.size, glyphs.toSet().size, "duplicate glyphs: $glyphs")
        assertEquals("?", statusGlyph(null))
    }

    // ---- agent review options ---------------------------------------------

    @Test
    fun `the branch list folds remote refs onto their local name`() {
        // Folding follows the repository's real remote set, not a hardcoded
        // "origin": upstream/x folds when the repo has an upstream remote,
        // and a local someone/something never does.
        val branches =
            CodebaseGitViewModel.branchesOf(
                listOf(
                    node(listOf("HEAD -> feat/x", "origin/feat/x", "tag: v1", "main", "HEAD", "upstream/feat/x", "someone/something")),
                ),
                setOf("origin", "upstream"),
            )
        assertEquals(listOf("feat/x", "main", "someone/something"), branches)
    }

    @Test
    fun `the review base defaults to main when the history has one`() {
        assertEquals("main", CodebaseGitViewModel.defaultBase(listOf("dev", "main"), "feat/x"))
        assertEquals("master", CodebaseGitViewModel.defaultBase(listOf("dev", "master"), "feat/x"))
    }

    @Test
    fun `without a main branch the review base is the current branch`() {
        assertEquals("feat/x", CodebaseGitViewModel.defaultBase(listOf("dev"), "feat/x"))
        assertEquals("", CodebaseGitViewModel.defaultBase(emptyList(), ""))
    }

    @Test
    fun `the review prompt carries the base, the depth and the instructions`() {
        val prompt =
            AgentReviewPrompt.build(
                projectPath = "/p",
                staged = emptyList(),
                unstaged = listOf(
                    GitFileStatusData("a.kt", null, GitFileStatusTypeData.MODIFIED, false, true),
                ),
                diffText = "diff",
                instructions = "focus on the parser",
                deep = true,
                baseRef = "main",
            )
        assertTrue(prompt.contains("ahead of landing them on `main`"), prompt.take(200))
        assertTrue(prompt.contains("Go deep"), prompt.take(400))
        assertTrue(prompt.contains("focus on the parser"), prompt.take(600))
    }

    @Test
    fun `a quick review says so and omits an empty instruction block`() {
        val prompt =
            AgentReviewPrompt.build(
                projectPath = "/p",
                staged = emptyList(),
                unstaged = listOf(
                    GitFileStatusData("a.kt", null, GitFileStatusTypeData.MODIFIED, false, true),
                ),
                diffText = "diff",
            )
        assertTrue(prompt.contains("Keep it quick"), prompt.take(400))
        assertTrue(!prompt.contains("The reviewer also asked"), prompt.take(600))
    }

    // ---- generated commit messages ----------------------------------------

    @Test
    fun `a fenced reply is unwrapped`() {
        assertEquals(
            "fix: handle an empty index",
            CommitMessagePrompt.clean("```\nfix: handle an empty index\n```"),
        )
    }

    @Test
    fun `a labelled or quoted reply is unwrapped`() {
        assertEquals("feat: add tree view", CommitMessagePrompt.clean("Commit message: feat: add tree view"))
        assertEquals("feat: add tree view", CommitMessagePrompt.clean("\"feat: add tree view\""))
    }

    @Test
    fun `a plain multi-line message keeps its body`() {
        val raw = "fix: stop double-opening tabs\n\nThe path was compared as a raw string."
        assertEquals(raw, CommitMessagePrompt.clean(raw))
    }

    @Test
    fun `the request carries the file list and the diff`() {
        val request =
            CommitMessagePrompt.request(
                listOf(GitFileStatusData("src/A.kt", GitFileStatusTypeData.MODIFIED, null, true, false)),
                "--- src/A.kt\n@@ -1 +1 @@",
            )
        val body = request.messages.single().text
        assertTrue(body.contains("src/A.kt"), body)
        assertTrue(body.contains("@@ -1 +1 @@"), body)
        assertTrue(request.system.contains("imperative"), request.system)
    }

    @Test
    fun `an empty diff still produces a request from the file list alone`() {
        val request =
            CommitMessagePrompt.request(
                listOf(GitFileStatusData("a.kt", null, GitFileStatusTypeData.MODIFIED, false, true)),
                "",
            )
        val body = request.messages.single().text
        assertTrue(body.contains("a.kt"), body)
        assertTrue(!body.contains("Diff:"), body)
    }

    // ---- no-repository hint ----------------------------------------------

    @Test
    fun `the hint names the folder when it holds no repositories`() {
        val dir = java.nio.file.Files.createTempDirectory("panel-norepo").toFile()
        java.io.File(dir, "plain").mkdirs()

        assertEquals(
            "${dir.name} is not a Git repository.",
            CodebaseGitViewModel.describeMissingRepo(dir.absolutePath),
        )
        dir.deleteRecursively()
    }

    @Test
    fun `the hint counts repositories sitting directly inside the folder`() {
        // The case that reads as a bug: a folder of sibling checkouts.
        val dir = java.nio.file.Files.createTempDirectory("panel-holder").toFile()
        java.io.File(dir, "repo-a/.git").mkdirs()
        java.io.File(dir, "repo-b/.git").mkdirs()
        java.io.File(dir, "notes").mkdirs()

        val hint = CodebaseGitViewModel.describeMissingRepo(dir.absolutePath)
        assertTrue(hint.contains("holds 2 of them"), hint)
        assertTrue(hint.startsWith(dir.name), hint)
        dir.deleteRecursively()
    }

    @Test
    fun `a single nested repository is phrased in the singular`() {
        val dir = java.nio.file.Files.createTempDirectory("panel-one").toFile()
        java.io.File(dir, "only/.git").mkdirs()

        val hint = CodebaseGitViewModel.describeMissingRepo(dir.absolutePath)
        assertTrue(hint.contains("one folder inside it is"), hint)
        dir.deleteRecursively()
    }

    @Test
    fun `no project and a missing directory degrade to plain sentences`() {
        assertEquals("No project open.", CodebaseGitViewModel.describeMissingRepo(null))
        assertEquals("No project open.", CodebaseGitViewModel.describeMissingRepo("  "))
        assertEquals(
            "No Git repository in this project.",
            CodebaseGitViewModel.describeMissingRepo("/definitely/not/here"),
        )
    }

    // ---- lane colours -----------------------------------------------------

    @Test
    fun `lane colours cycle and never index out of range`() {
        val first = laneColor(0)
        assertEquals(first, laneColor(CodebasePalette.laneColors.size))
        // A pathological history must not throw.
        laneColor(999)
    }
}
