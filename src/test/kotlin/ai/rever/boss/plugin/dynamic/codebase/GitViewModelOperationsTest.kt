package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DiffHunk
import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * View-model behaviour that only shows up across a seam, so neither side's
 * own unit test catches it: the Agent Review diff budget (collector meets
 * prompt builder), "Load more" exhaustion, and batch's first-error-wins rule.
 */
class GitViewModelOperationsTest {

    private val viewModels = mutableListOf<CodebaseGitViewModel>()

    @AfterTest
    fun disposeAll() {
        viewModels.forEach { it.dispose() }
        viewModels.clear()
    }

    // ---- fakes ----

    private fun node(hash: String) =
        GitCommitNodeData(
            hash = hash,
            shortHash = hash.take(7),
            subject = hash,
            author = "t",
            authorEmail = "t@t",
            date = 0L,
            refs = emptyList(),
            parents = emptyList(),
        )

    private open inner class Host(
        val graph: List<GitCommitNodeData> = emptyList(),
        val fileDiffs: Map<String, List<GitDiffData>> = emptyMap(),
        /** Paths whose stage() fails, in the order the failure should be reported. */
        val stageFailures: Map<String, String> = emptyMap(),
    ) : GitDataProvider {
        val status = MutableStateFlow(emptyList<GitFileStatusData>())
        val graphLimits = mutableListOf<Int>()
        val staged = mutableListOf<String>()

        override val fileStatus: StateFlow<List<GitFileStatusData>> = status
        override val commitLog: StateFlow<List<GitCommitInfoData>> = MutableStateFlow(emptyList())
        override val isGitRepository: StateFlow<Boolean> = MutableStateFlow(true)
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun refreshStatus() {}

        override suspend fun refreshLog(limit: Int) {}

        override suspend fun logGraphFor(ref: String?, limit: Int): List<GitCommitNodeData> {
            graphLimits += limit
            return graph.take(limit)
        }

        override suspend fun diffFile(path: String, staged: Boolean): List<GitDiffData> =
            fileDiffs[path].orEmpty()

        override suspend fun stage(filePath: String): GitOperationResultData {
            staged += filePath
            return stageFailures[filePath]?.let { GitOperationResultData.Error(it) }
                ?: GitOperationResultData.Success()
        }

        override suspend fun unstage(filePath: String) = GitOperationResultData.Success()

        override suspend fun stageAll() = GitOperationResultData.Success()

        override suspend fun unstageAll() = GitOperationResultData.Success()

        override suspend fun discardChanges(filePath: String) = GitOperationResultData.Success()

        override suspend fun cherryPick(commitHash: String) = GitOperationResultData.Success()

        override suspend fun revert(commitHash: String) = GitOperationResultData.Success()

        override suspend fun checkout(ref: String) = GitOperationResultData.Success()

        override fun getCurrentProjectPath(): String? = "/repo"

        override fun openFile(filePath: String, windowId: String) {}
    }

    private fun viewModel(
        provider: GitDataProvider,
        onReview: (String) -> Unit = {},
    ): CodebaseGitViewModel =
        CodebaseGitViewModel(
            git = provider,
            onAgentReview = onReview,
            getProjectPath = { "/repo" },
        ).also { viewModels += it }

    private fun awaitUntil(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun unstagedFile(path: String) =
        GitFileStatusData(
            path = path,
            indexStatus = null,
            workTreeStatus = GitFileStatusTypeData.MODIFIED,
            isStaged = false,
            isUnstaged = true,
        )

    /** A compact diff comfortably larger than the inline budget. */
    private fun oversizedDiff(path: String): GitDiffData {
        val lines = (1..4_000).map { DiffLine("added line number $it", DiffLineKind.ADDED) }
        return GitDiffData(
            path = path,
            additions = lines.size,
            deletions = 0,
            hunks = listOf(DiffHunk(1, lines.size, 1, lines.size, "", lines)),
        )
    }

    // ---- the diff budget seam ----

    @Test
    fun `an oversized first file is truncated into the prompt, never dropped`() {
        // The collector fills the budget and then appends its per-file header
        // and truncation marker, so the result lands slightly OVER it. The
        // prompt builder used to re-test the length and replace the whole diff
        // with "use the git_diff tools" - dropping it in exactly the case the
        // truncation exists to serve. Neither side's own test sees this: they
        // are pinned separately.
        val host = Host(fileDiffs = mapOf("big.kt" to listOf(oversizedDiff("big.kt"))))
        var prompt: String? = null
        val vm = viewModel(host) { prompt = it }
        host.status.value = listOf(unstagedFile("big.kt"))

        awaitUntil("the file status to arrive") { vm.fileStatus.value.isNotEmpty() }
        vm.startAgentReview()
        awaitUntil("the review prompt") { prompt != null }

        val text = prompt!!
        assertTrue("Full diff:" in text, "the diff was dropped:\n${text.take(600)}")
        assertTrue("added line number 1" in text, "no diff content reached the prompt")
        assertTrue(AgentReviewPrompt.TRUNCATION_MARKER in text, "the truncation was not marked")
        assertFalse(
            "The diff could not be fetched inline" in text,
            "a truncated diff must not report as unfetchable",
        )
    }

    // ---- Load more ----

    @Test
    fun `a repository smaller than one page has nothing more to load`() {
        // hasMoreGraph used to be `size < GRAPH_MAX`, so a 12-commit repo
        // offered "Load more" forever and each click refetched the same 12.
        val host = Host(graph = (1..12).map { node("c$it") })
        val vm = viewModel(host)

        awaitUntil("the initial graph") { vm.graph.value.size == 12 }

        assertFalse(vm.hasMoreGraph(), "a 12-commit repository has no second page")
    }

    @Test
    fun `a full page leaves more to load`() {
        val host = Host(graph = (1..CodebaseGitViewModel.GRAPH_PAGE + 10).map { node("c$it") })
        val vm = viewModel(host)

        awaitUntil("the initial graph") { vm.graph.value.size == CodebaseGitViewModel.GRAPH_PAGE }

        assertTrue(vm.hasMoreGraph(), "a full first page means history continues")
    }

    // ---- batch ----

    @Test
    fun `a batch reports the FIRST failure, not the last result`() {
        // A later success would otherwise overwrite the error and the whole
        // operation would read as clean.
        val host = Host(stageFailures = mapOf("b.kt" to "b is locked"))
        val vm = viewModel(host)
        awaitUntil("the first load") { vm.loaded.value }

        vm.stagePaths(listOf("a.kt", "b.kt", "c.kt"))

        awaitUntil("the batch result") { vm.message.value != null && !vm.busy.value }
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), host.staged, "every path must still be attempted")
        assertTrue("b is locked" in vm.message.value!!, "reported: ${vm.message.value}")
    }
}
