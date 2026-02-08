package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import kotlinx.coroutines.CoroutineScope

/**
 * Codebase dynamic plugin - Loaded from external JAR.
 *
 * Browse and explore project files with tree view navigation.
 * Features context menus for file operations: create, rename, delete, copy path, reveal in Finder.
 * Ported from bundled plugin v8.16.22 with exact UI parity.
 */
class CodebaseDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.codebase"
    override val displayName: String = "Codebase (Dynamic)"
    override val version: String = "1.0.8"
    override val description: String = "Browse and explore project files"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-codebase"

    private var fileSystemDataProvider: FileSystemDataProvider? = null
    private var contextMenuProvider: ContextMenuProvider? = null
    private var directoryPickerProvider: DirectoryPickerProvider? = null
    private var splitViewOperations: SplitViewOperations? = null
    private var projectDataProvider: ProjectDataProvider? = null
    private var pluginScope: CoroutineScope? = null
    private var getWindowId: () -> String? = { null }
    private var getProjectPath: () -> String? = { null }

    override fun register(context: PluginContext) {
        // Capture providers from context
        fileSystemDataProvider = context.fileSystemDataProvider
        contextMenuProvider = context.contextMenuProvider
        directoryPickerProvider = context.directoryPickerProvider
        splitViewOperations = context.splitViewOperations
        projectDataProvider = context.projectDataProvider
        pluginScope = context.pluginScope
        getWindowId = { context.windowId }
        getProjectPath = { context.projectPath }

        context.panelRegistry.registerPanel(CodebaseInfo) { ctx, panelInfo ->
            CodebaseComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                fileSystemDataProvider = fileSystemDataProvider,
                contextMenuProvider = contextMenuProvider,
                directoryPickerProvider = directoryPickerProvider,
                splitViewOperations = splitViewOperations,
                scope = pluginScope ?: error("Plugin scope not available"),
                getWindowId = getWindowId,
                getProjectPath = getProjectPath,
                onSelectProject = { name, path ->
                    // Use ProjectDataProvider to select the project
                    projectDataProvider?.selectProject(
                        ai.rever.boss.plugin.api.ProjectData(name = name, path = path)
                    )
                }
            )
        }
    }
}
