package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitBranchRefData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the branch-graph's structural rules: which pills a commit shows, in
 * what order, and what the picker offers.
 *
 * Both jobs are string surgery over git's `%D` decorations, and every mistake
 * in them is invisible until someone squints at a screenshot - which is how
 * [GitRefBadge] came to exist for a release without a single call site.
 */
class GitBranchModelTest {

    // ---- refPills ----

    @Test
    fun `the checked-out branch reads as a HEAD pill without the arrow`() {
        assertEquals(
            listOf(GitRefPill("main", GitRefKind.HEAD)),
            GitBranchModel.refPills(listOf("HEAD -> main")),
        )
    }

    @Test
    fun `a detached HEAD names no branch and draws no pill`() {
        assertEquals(emptyList(), GitBranchModel.refPills(listOf("HEAD")))
    }

    @Test
    fun `a tag drops its prefix and reads as a tag`() {
        assertEquals(
            listOf(GitRefPill("v1.0.90", GitRefKind.TAG)),
            GitBranchModel.refPills(listOf("tag: v1.0.90")),
        )
    }

    @Test
    fun `a remote symref is not a branch`() {
        assertEquals(emptyList(), GitBranchModel.refPills(listOf("origin/HEAD")))
    }

    @Test
    fun `a remote copy of a local branch on the same commit is not repeated`() {
        // "main" and "origin/main" side by side says nothing the first did not.
        assertEquals(
            listOf(GitRefPill("main", GitRefKind.HEAD)),
            GitBranchModel.refPills(listOf("HEAD -> main", "origin/main")),
        )
    }

    @Test
    fun `a remote branch with no local counterpart is kept`() {
        assertEquals(
            listOf(GitRefPill("origin/hotfix", GitRefKind.REMOTE)),
            GitBranchModel.refPills(listOf("origin/hotfix")),
        )
    }

    @Test
    fun `a slashed local branch is not mistaken for a remote`() {
        // `feature/x` and `origin/x` are the same shape; only the remote list
        // separates them, which is why the remotes are passed in.
        assertEquals(
            listOf(GitRefPill("feature/tab-completion", GitRefKind.LOCAL)),
            GitBranchModel.refPills(listOf("feature/tab-completion")),
        )
    }

    @Test
    fun `a remote named something other than origin is still a remote`() {
        assertEquals(
            listOf(GitRefPill("fork/main", GitRefKind.REMOTE)),
            GitBranchModel.refPills(listOf("fork/main"), remotes = setOf("fork")),
        )
        // ...and with the default remote set it reads as a local branch, which
        // is the honest answer when nothing has told us "fork" is a remote.
        assertEquals(
            listOf(GitRefPill("fork/main", GitRefKind.LOCAL)),
            GitBranchModel.refPills(listOf("fork/main")),
        )
    }

    @Test
    fun `pills order HEAD then local then tag then remote`() {
        val pills =
            GitBranchModel.refPills(
                listOf("origin/release", "tag: v9.5.3", "hotfix", "HEAD -> main"),
            )
        assertEquals(
            listOf(GitRefKind.HEAD, GitRefKind.LOCAL, GitRefKind.TAG, GitRefKind.REMOTE),
            pills.map { it.kind },
        )
        assertEquals("main", pills.first().label)
    }

    @Test
    fun `blank and whitespace-only decorations are dropped`() {
        assertEquals(emptyList(), GitBranchModel.refPills(listOf("", "   ", "tag:")))
    }

    // ---- options ----

    private fun local(name: String, current: Boolean = false) =
        GitBranchRefData(name = name, isCurrent = current, isRemote = false)

    private fun remote(name: String) =
        GitBranchRefData(name = name, isCurrent = false, isRemote = true)

    @Test
    fun `the picker puts the current branch first then locals then remotes`() {
        val options =
            GitBranchModel.options(
                branches = listOf(remote("origin/zeta"), local("zeta"), local("alpha"), local("main", current = true)),
            )
        assertEquals(listOf("main", "alpha", "zeta", "origin/zeta"), options.map { it.name })
        assertTrue(options.first().isCurrent)
    }

    @Test
    fun `a remote branch is offered even when a local of the same name exists`() {
        // origin/main and main are different histories the moment either
        // moves, and that divergence is what a graph is opened to look at.
        val options = GitBranchModel.options(listOf(local("main", current = true), remote("origin/main")))
        assertEquals(listOf("main", "origin/main"), options.map { it.name })
    }

    @Test
    fun `HEAD pointers are never offered as branches`() {
        val options = GitBranchModel.options(listOf(local("main"), remote("origin/HEAD"), local("HEAD")))
        assertEquals(listOf("main"), options.map { it.name })
    }

    @Test
    fun `a branch list carrying gits marker column is trimmed`() {
        // `git branch --format=%(refname:short)%(HEAD)` emits a SPACE for every
        // non-current branch. Left on, the name resolves to nothing at all.
        val options = GitBranchModel.options(listOf(local("side ")))
        assertEquals(listOf("side"), options.map { it.name })
    }

    @Test
    fun `the graph decorations are the fallback when the host has no branch list`() {
        val options =
            GitBranchModel.options(
                branches = emptyList(),
                graphRefs = listOf("main", "origin/hotfix"),
                current = "main",
            )
        assertEquals(listOf("main", "origin/hotfix"), options.map { it.name })
        assertTrue(options.first { it.name == "main" }.isCurrent)
        assertTrue(options.first { it.name == "origin/hotfix" }.isRemote)
    }

    @Test
    fun `the graph decorations are ignored once a real branch list exists`() {
        // The decorations only name branches inside the fetched window of
        // history; the real list is a superset and the authority.
        val options =
            GitBranchModel.options(
                branches = listOf(local("main", current = true)),
                graphRefs = listOf("a-branch-only-the-graph-saw"),
            )
        assertEquals(listOf("main"), options.map { it.name })
    }

    @Test
    fun `the current branch is marked by name when the host does not mark it`() {
        val options = GitBranchModel.options(listOf(local("main"), local("side")), current = "side")
        assertEquals("side", options.first().name)
        assertTrue(options.first().isCurrent)
    }

    @Test
    fun `duplicate names collapse to one entry`() {
        val options = GitBranchModel.options(listOf(local("main"), local("main")))
        assertEquals(1, options.size)
    }

    // ---- branchRefsInGraph ----

    private fun node(vararg refs: String) =
        ai.rever.boss.plugin.api.GitCommitNodeData(
            hash = "h", shortHash = "h", subject = "s", author = "a",
            authorEmail = "a@a", date = 0L, refs = refs.toList(), parents = emptyList(),
        )

    @Test
    fun `graph decorations yield branch names and keep the remote prefix`() {
        // CodebaseGitViewModel.branchesOf folds origin/x onto x for the
        // "Diff Against..." picker. The graph picker must not: origin/hotfix
        // is a different history to look at than a local hotfix would be.
        assertEquals(
            listOf("main", "origin/hotfix"),
            GitBranchModel.branchRefsInGraph(listOf(node("HEAD -> main"), node("origin/hotfix"))),
        )
    }

    @Test
    fun `graph decorations drop tags and duplicates`() {
        assertEquals(
            listOf("main"),
            GitBranchModel.branchRefsInGraph(
                listOf(node("HEAD -> main", "tag: v1"), node("main"), node("HEAD")),
            ),
        )
    }

    // ---- remoteNamesOf ----

    @Test
    fun `remote names come from the branch list and fall back to the usual two`() {
        assertEquals(
            setOf("fork", "origin"),
            GitBranchModel.remoteNamesOf(listOf(remote("fork/main"), remote("origin/main"), local("main"))),
        )
        assertEquals(GitBranchModel.DEFAULT_REMOTES, GitBranchModel.remoteNamesOf(listOf(local("main"))))
        assertEquals(GitBranchModel.DEFAULT_REMOTES, GitBranchModel.remoteNamesOf(emptyList()))
    }

    // ---- isSafeRef ----

    @Test
    fun `real refs are usable`() {
        assertTrue(GitBranchModel.isSafeRef("main"))
        assertTrue(GitBranchModel.isSafeRef("feature/tab-completion"))
        assertTrue(GitBranchModel.isSafeRef("origin/release-9.5"))
        assertTrue(GitBranchModel.isSafeRef("v1.0.90"))
    }

    @Test
    fun `anything git would read as an option is refused`() {
        assertFalse(GitBranchModel.isSafeRef("-n1"))
        assertFalse(GitBranchModel.isSafeRef("--upload-pack=sh"))
    }

    @Test
    fun `blanks whitespace and control characters are refused`() {
        assertFalse(GitBranchModel.isSafeRef(""))
        assertFalse(GitBranchModel.isSafeRef("   "))
        assertFalse(GitBranchModel.isSafeRef("has space"))
        assertFalse(GitBranchModel.isSafeRef("has\nnewline"))
        assertFalse(GitBranchModel.isSafeRef("has\u0000nul"))
        assertFalse(GitBranchModel.isSafeRef("a".repeat(256)))
    }
}
