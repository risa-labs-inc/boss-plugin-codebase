package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SplitViewOperations
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Codebase panel component (Dynamic Plugin)
 *
 * Provides file browser functionality for project exploration.
 * Ported from bundled plugin v8.16.22 with exact UI parity.
 */
class CodebaseComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val directoryPickerProvider: DirectoryPickerProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?,
    private val onSelectProject: ((String, String) -> Unit)?
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        CodebaseContent(
            fileSystemDataProvider = fileSystemDataProvider,
            directoryPickerProvider = directoryPickerProvider,
            splitViewOperations = splitViewOperations,
            contextMenuProvider = contextMenuProvider,
            scope = scope,
            getWindowId = getWindowId,
            getProjectPath = getProjectPath,
            onSelectProject = onSelectProject
        )
    }
}
