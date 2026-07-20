package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * CodebaseViewModel.refreshChangedDirectories — the watcher-driven quiet
 * refresh. The key contract: a structurally identical rescan (e.g. only an
 * mtime moved under the root during a build) must be a full no-op — no new
 * tree instance, so no cache clear and no selection re-prune downstream.
 */
class QuietRefreshTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var viewModel: CodebaseViewModel? = null

    @AfterTest
    fun cleanup() {
        viewModel?.dispose()
        scope.cancel()
    }

    private fun rootData(children: List<FileNodeData>) = FileNodeData(
        name = "p",
        path = "/p",
        isDirectory = true,
        children = children,
        loadingState = NodeLoadingStateData.LOADED
    )

    private fun fileData(name: String) = FileNodeData(
        name = name,
        path = "/p/$name",
        isDirectory = false,
        loadingState = NodeLoadingStateData.LOADED
    )

    private fun viewModelWith(scan: () -> FileNodeData?): CodebaseViewModel =
        CodebaseViewModel(
            fileSystemDataProvider = HiddenAwareFakeProvider { _, _ -> scan() },
            directoryPickerProvider = null,
            splitViewOperations = null,
            scope = scope,
            getWindowId = { null },
            getProjectPath = { "/p" },
            onSelectProject = null
        ).also { viewModel = it }

    @Test
    fun `structurally identical rescan leaves the tree instance untouched`() = runBlocking {
        val vm = viewModelWith { rootData(listOf(fileData("a.txt"))) }
        vm.loadFileTree("/p")
        val before = vm.fileTree.value

        vm.refreshChangedDirectories(setOf("/p"))

        assertSame(before, vm.fileTree.value, "no-op refresh must not produce a new tree")
    }

    @Test
    fun `real change replaces the tree and reflects the new children`() = runBlocking {
        var children = listOf(fileData("a.txt"))
        val vm = viewModelWith { rootData(children) }
        vm.loadFileTree("/p")
        val before = vm.fileTree.value

        children = listOf(fileData("a.txt"), fileData("b.txt"))
        vm.refreshChangedDirectories(setOf("/p"))

        assertNotSame(before, vm.fileTree.value)
        assertEquals(
            listOf("a.txt", "b.txt"),
            vm.fileTree.value?.children?.map { it.name }
        )
    }

    @Test
    fun `vanished entries drop out of the selection, surviving ones stay`() = runBlocking {
        var children = listOf(fileData("keep.txt"), fileData("gone.txt"))
        val vm = viewModelWith { rootData(children) }
        vm.loadFileTree("/p")
        vm.selectOnly("/p/keep.txt")
        vm.toggleSelection("/p/gone.txt")

        children = listOf(fileData("keep.txt"))
        vm.refreshChangedDirectories(setOf("/p"))

        assertEquals(setOf("/p/keep.txt"), vm.selectedPaths.value)
    }
}
