package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the degradation contract at the CALL SITES (TreeScannerTest covers
 * the facade itself): the show-hidden setter refuses on unsupporting hosts,
 * and codebase_tree tells the caller when include_hidden was ignored.
 */
class DegradationContractTest {

    private fun node() = FileNodeData(
        name = "proj",
        path = "/proj",
        isDirectory = true,
        children = listOf(FileNodeData(name = "src", path = "/proj/src", isDirectory = true))
    )

    // ---- CodebaseViewModel.setShowHidden ----

    private fun viewModel(provider: FileSystemDataProvider?) = CodebaseViewModel(
        fileSystemDataProvider = provider,
        directoryPickerProvider = null,
        splitViewOperations = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        getWindowId = { null },
        getProjectPath = { null },
        onSelectProject = null
    )

    @Test
    fun `setShowHidden is a no-op when the host lacks support`() {
        val vm = viewModel(LegacyFakeProvider { node() })
        assertFalse(vm.supportsShowHidden)

        vm.setShowHidden(true)
        assertFalse(vm.showHidden.value)
    }

    @Test
    fun `setShowHidden flips when the host supports hidden entries`() {
        val vm = viewModel(HiddenAwareFakeProvider { _, _ -> node() })
        assertTrue(vm.supportsShowHidden)

        vm.setShowHidden(true)
        assertTrue(vm.showHidden.value)
    }

    // ---- codebase_tree include_hidden note ----

    private fun treeToolText(provider: FileSystemDataProvider, includeHidden: Boolean): String = runBlocking {
        val tool = CodebaseMcpToolProvider(
            providerId = "test",
            fileSystem = provider,
            projects = null,
            getWindowId = { null },
            getProjectPath = { "/proj" },
        ).tools().first { it.name == "codebase_tree" }
        tool.handler.call(McpToolArgs(mapOf("path" to "/proj", "include_hidden" to includeHidden))).text
    }

    @Test
    fun `codebase_tree notes an ignored include_hidden on unsupporting hosts`() {
        val text = treeToolText(LegacyFakeProvider { node() }, includeHidden = true)
        assertTrue(text.startsWith("note: this host does not support include_hidden"))
        assertTrue(text.contains("src")) // the tree still renders below the note
    }

    @Test
    fun `codebase_tree adds no note when supported or when not requested`() {
        assertFalse(treeToolText(HiddenAwareFakeProvider { _, _ -> node() }, includeHidden = true).startsWith("note:"))
        assertFalse(treeToolText(LegacyFakeProvider { node() }, includeHidden = false).startsWith("note:"))
    }
}
