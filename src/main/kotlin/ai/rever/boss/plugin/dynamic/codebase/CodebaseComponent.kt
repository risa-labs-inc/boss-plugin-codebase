package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Codebase panel component (Dynamic Plugin)
 *
 * Provides file browser functionality for project exploration.
 */
class CodebaseComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val scope: CoroutineScope,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        CodebaseContent(
            fileSystemDataProvider = fileSystemDataProvider,
            scope = scope,
            getWindowId = getWindowId,
            getProjectPath = getProjectPath,
            contextMenuProvider = contextMenuProvider
        )
    }
}
