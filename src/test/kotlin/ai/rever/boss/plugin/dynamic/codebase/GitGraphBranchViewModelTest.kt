package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitBranchRefData
import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitOperationResultData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the graph's branch scoping at the view model.
 *
 * The interesting case is the LEGACY host: the plugin compiles against
 * boss-plugin-api 1.0.87 and calls [GitDataProvider.logGraphFor], but a host
 * built against 1.0.89 never overrides it. The interface's default body has to
 * carry that call back to [GitDataProvider.logGraph] for a null ref, or the
 * graph goes blank on every host but the newest - which is the failure this
 * whole additive-with-defaults rule exists to prevent, and it is not something
 * a compile catches.
 */
class GitGraphBranchViewModelTest {

    private val viewModels = mutableListOf<CodebaseGitViewModel>()

    @AfterTest
    fun disposeAll() {
        viewModels.forEach { it.dispose() }
        viewModels.clear()
    }

    // ---- fakes ----

    private fun node(hash: String, refs: List<String> = emptyList()) =
        GitCommitNodeData(
            hash = hash,
            shortHash = hash.take(7),
            subject = hash,
            author = "t",
            authorEmail = "t@t",
            date = 0L,
            refs = refs,
            parents = emptyList(),
        )

    /** A host at 1.0.89: it knows logGraph and nothing added after it. */
    private open inner class LegacyHost(
        val head: List<GitCommitNodeData>,
    ) : GitDataProvider {
        val logGraphCalls = mutableListOf<Int>()

        override val fileStatus: StateFlow<List<GitFileStatusData>> = MutableStateFlow(emptyList())
        override val commitLog: StateFlow<List<GitCommitInfoData>> = MutableStateFlow(emptyList())
        override val isGitRepository: StateFlow<Boolean> = MutableStateFlow(true)
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun refreshStatus() {}

        override suspend fun refreshLog(limit: Int) {}

        override suspend fun logGraph(limit: Int): List<GitCommitNodeData> {
            logGraphCalls += limit
            return head
        }

        override suspend fun stage(filePath: String) = GitOperationResultData.Success()

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

    /** A host at 1.0.90: ref-scoped log and a real branch list. */
    private inner class ModernHost(
        head: List<GitCommitNodeData>,
        private val byRef: Map<String, List<GitCommitNodeData>> = emptyMap(),
        private val branchList: List<GitBranchRefData> = emptyList(),
    ) : LegacyHost(head) {
        val refsAsked = mutableListOf<String?>()

        override suspend fun logGraphFor(ref: String?, limit: Int): List<GitCommitNodeData> {
            refsAsked += ref
            return if (ref == null) head else byRef[ref].orEmpty()
        }

        override suspend fun branches(): List<GitBranchRefData> = branchList
    }

    private fun viewModel(provider: GitDataProvider): CodebaseGitViewModel =
        CodebaseGitViewModel(
            git = provider,
            onAgentReview = { _ -> },
            getProjectPath = { "/repo" },
        ).also { viewModels += it }

    /** The view model owns its own scope, so the assertions wait rather than race. */
    private fun awaitUntil(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    // ---- tests ----

    @Test
    fun `the graph starts on the checked-out branch`() {
        val host = ModernHost(head = listOf(node("h1", listOf("HEAD -> main"))))
        val vm = viewModel(host)

        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }

        assertNull(vm.graphRef.value, "null means the checked-out branch, not a pinned name")
        assertEquals(listOf<String?>(null), host.refsAsked)
        assertEquals("main", vm.currentBranch.value)
    }

    @Test
    fun `selecting a branch reloads the graph for that ref`() {
        val host =
            ModernHost(
                head = listOf(node("h1", listOf("HEAD -> main"))),
                byRef = mapOf("side" to listOf(node("s1"), node("s2"))),
            )
        val vm = viewModel(host)
        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }

        vm.selectGraphBranch("side")

        awaitUntil("the side graph") { vm.graph.value.size == 2 }
        assertEquals("side", vm.graphRef.value)
        assertTrue(host.refsAsked.contains("side"))
    }

    @Test
    fun `the checked-out branch name survives looking at another branch`() {
        // Another branch's tip carries no `HEAD ->` decoration. Reading the
        // current branch off it blanked the toolbar the moment you looked away
        // from HEAD.
        val host =
            ModernHost(
                head = listOf(node("h1", listOf("HEAD -> main"))),
                byRef = mapOf("side" to listOf(node("s1"))),
            )
        val vm = viewModel(host)
        awaitUntil("the initial graph") { vm.currentBranch.value == "main" }

        vm.selectGraphBranch("side")
        awaitUntil("the side graph") { vm.graphRef.value == "side" }

        assertEquals("main", vm.currentBranch.value)
    }

    @Test
    fun `going back to the current branch clears the ref`() {
        val host =
            ModernHost(
                head = listOf(node("h1", listOf("HEAD -> main"))),
                byRef = mapOf("side" to listOf(node("s1"))),
            )
        val vm = viewModel(host)
        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }

        vm.selectGraphBranch("side")
        awaitUntil("the side graph") { vm.graphRef.value == "side" }
        vm.showCurrentBranch()

        awaitUntil("the return to HEAD") {
            vm.graphRef.value == null && vm.graph.value.map { it.hash } == listOf("h1")
        }
        assertEquals(listOf("h1"), vm.graph.value.map { it.hash })
    }

    @Test
    fun `a ref git would read as an option never reaches the host`() {
        val host = ModernHost(head = listOf(node("h1", listOf("HEAD -> main"))))
        val vm = viewModel(host)
        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }
        val before = host.refsAsked.size

        vm.selectGraphBranch("--upload-pack=sh")

        awaitUntil("the refusal message") { vm.message.value != null }
        assertEquals(before, host.refsAsked.size, "nothing was asked of the host")
        assertNull(vm.graphRef.value)
    }

    @Test
    fun `the picker offers every branch the host lists`() {
        val host =
            ModernHost(
                head = listOf(node("h1", listOf("HEAD -> main"))),
                branchList =
                    listOf(
                        GitBranchRefData("main", isCurrent = true),
                        // A branch whose tip is older than the fetched window,
                        // so no graph decoration names it - this is why the
                        // picker cannot be built from the decorations.
                        GitBranchRefData("ancient"),
                        GitBranchRefData("origin/main", isRemote = true),
                    ),
            )
        val vm = viewModel(host)

        awaitUntil("the branch list") { vm.branchOptions.value.isNotEmpty() }

        assertEquals(
            listOf("main", "ancient", "origin/main"),
            vm.branchOptions.value.map { it.name },
        )
        assertEquals(setOf("origin"), vm.remoteNames.value)
    }

    @Test
    fun `a host that predates the ref-scoped log still draws the current branch`() {
        // LegacyHost overrides logGraph only. logGraphFor's DEFAULT body has to
        // carry the null-ref call through to it, or every pre-1.0.90 host shows
        // an empty graph.
        val host = LegacyHost(head = listOf(node("h1", listOf("HEAD -> main")), node("h2")))
        val vm = viewModel(host)

        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }

        assertEquals(listOf("h1", "h2"), vm.graph.value.map { it.hash })
        assertEquals(listOf(CodebaseGitViewModel.GRAPH_PAGE), host.logGraphCalls)
        assertEquals("main", vm.currentBranch.value)
    }

    @Test
    fun `a host that predates the branch list falls back to the graph decorations`() {
        val host =
            LegacyHost(
                head = listOf(node("h1", listOf("HEAD -> main")), node("h2", listOf("origin/hotfix"))),
            )
        val vm = viewModel(host)

        awaitUntil("the fallback branch list") { vm.branchOptions.value.isNotEmpty() }

        assertEquals(listOf("main", "origin/hotfix"), vm.branchOptions.value.map { it.name })
    }

    @Test
    fun `the change groups open as a tree and the toggle survives the view model`() {
        // The layout used to be a `remember` inside the GIT tab composable, so
        // it reset on every hop to FILES and back. It lives on the view model
        // now, which outlives the tab.
        val vm = viewModel(ModernHost(head = listOf(node("h1"))))

        assertEquals(GitChangeLayout.TREE, vm.changeLayout.value)

        vm.toggleChangeLayout()
        assertEquals(GitChangeLayout.LIST, vm.changeLayout.value)

        vm.setChangeLayout(GitChangeLayout.TREE)
        assertEquals(GitChangeLayout.TREE, vm.changeLayout.value)
    }

    @Test
    fun `a host that predates the remote verbs says so instead of failing silently`() {
        val host = LegacyHost(head = listOf(node("h1", listOf("HEAD -> main"))))
        val vm = viewModel(host)
        awaitUntil("the initial graph") { vm.graph.value.isNotEmpty() }

        vm.fetch()

        awaitUntil("the unsupported message") { vm.message.value != null }
        assertTrue(
            vm.message.value.orEmpty().contains("not supported", ignoreCase = true),
            "got: ${vm.message.value}",
        )
    }
}
