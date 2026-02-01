package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Codebase dynamic plugin - Loaded from external JAR.
 *
 * Browse and explore project files
 */
class CodebaseDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.codebase"
    override val displayName: String = "Codebase (Dynamic)"
    override val version: String = "1.0.0"
    override val description: String = "Browse and explore project files"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-codebase"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(CodebaseInfo) { ctx, panelInfo ->
            CodebaseComponent(ctx, panelInfo)
        }
    }
}
