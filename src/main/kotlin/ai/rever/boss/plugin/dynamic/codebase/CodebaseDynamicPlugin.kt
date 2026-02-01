package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope

/**
 * Codebase dynamic plugin - Loaded from external JAR.
 *
 * Browse and explore project files with tree view navigation.
 */
class CodebaseDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.codebase"
    override val displayName: String = "Codebase (Dynamic)"
    override val version: String = "1.0.1"
    override val description: String = "Browse and explore project files"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-codebase"

    private var fileSystemDataProvider: FileSystemDataProvider? = null
    private var pluginScope: CoroutineScope? = null
    private var getWindowId: () -> String? = { null }
    private var getProjectPath: () -> String? = { null }

    override fun register(context: PluginContext) {
        // Capture providers from context
        fileSystemDataProvider = context.fileSystemDataProvider
        pluginScope = context.pluginScope
        getWindowId = { context.windowId }
        getProjectPath = { context.projectPath }

        context.panelRegistry.registerPanel(CodebaseInfo) { ctx, panelInfo ->
            CodebaseComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                fileSystemDataProvider = fileSystemDataProvider,
                scope = pluginScope ?: error("Plugin scope not available"),
                getWindowId = getWindowId,
                getProjectPath = getProjectPath
            )
        }
    }
}
