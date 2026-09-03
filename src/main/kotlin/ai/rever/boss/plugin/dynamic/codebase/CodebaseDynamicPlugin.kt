package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Codebase dynamic plugin - Loaded from external JAR.
 *
 * P7: FILES/SEARCH/GIT tab panel (Cursor-style). FILES is the original file
 * tree; SEARCH is global search & replace (absorbed from the retired
 * search-replace plugin); GIT is the changes accordion + lane graph + Agent
 * Review (absorbed from the retired git-status/git-log plugins). All 16
 * git_* / project_* MCP tools now register from this plugin.
 */
class CodebaseDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.codebase"
    override val displayName: String = "Codebase (Dynamic)"
    override val version: String = readManifestVersion()
    override val description: String = "Files, search & replace, and git status/log/graph for the current project"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-codebase"

    private var fileSystemDataProvider: FileSystemDataProvider? = null
    private var contextMenuProvider: ContextMenuProvider? = null
    private var directoryPickerProvider: DirectoryPickerProvider? = null
    private var splitViewOperations: SplitViewOperations? = null
    private var projectDataProvider: ProjectDataProvider? = null
    private var gitDataProvider: ai.rever.boss.plugin.api.GitDataProvider? = null
    private var searchProvider: ai.rever.boss.plugin.api.ProjectSearchProvider? = null
    private var pluginScope: CoroutineScope? = null
    private var getWindowId: () -> String? = { null }
    private var getProjectPath: () -> String? = { null }
    private var publishReview: (prompt: String) -> Unit = { _ -> }

    /** Kept so AI availability can be re-checked per action, not cached at load. */
    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context
        // Capture providers from context
        fileSystemDataProvider = context.fileSystemDataProvider
        contextMenuProvider = context.contextMenuProvider
        directoryPickerProvider = context.directoryPickerProvider
        splitViewOperations = context.splitViewOperations
        projectDataProvider = context.projectDataProvider
        gitDataProvider = context.gitDataProvider
        searchProvider = context.projectSearchProvider
        pluginScope = context.pluginScope
        getWindowId = { context.windowId }
        getProjectPath = { context.projectPath }

        val storage = context.pluginStorageFactory?.createStorage(pluginId)

        // Agent Review wiring: publish the event the fluck-agent (Atlas)
        // listens for, then raise/focus its panel.
        //
        // NOTE: this is a BROADCAST on the application event bus, not a
        // targeted send, and the payload carries up to
        // AgentReviewPrompt.INLINE_DIFF_BUDGET characters of source diff. Every
        // loaded plugin in this process can observe it. That is acceptable
        // because the bus is in-process and the plugin set is host-governed -
        // but it is source content leaving this plugin, so a targeted channel
        // would be the better shape if the API ever grows one.
        val eventBus = context.applicationEventBus
        val panelEvents = context.panelEventProvider
        // autoStart is always true: the button IS the request to run the
        // review. It used to carry a persisted preference that no control
        // ever set, so a stored `false` (codebase.reviewAutoStart) left every
        // click merely typing the brief into the agent's composer, with no
        // way back. The key is now ignored.
        publishReview = { prompt ->
            eventBus?.publish(
                CustomPluginEvent(
                    sourcePluginId = pluginId,
                    eventName = EVENT_ATLAS_REVIEW,
                    payload = mapOf(
                        "prompt" to prompt,
                        "projectPath" to (getProjectPath().orEmpty()),
                        "autoStart" to true,
                    ),
                ),
            )
            val window = getWindowId().orEmpty()
            pluginScope?.launch {
                panelEvents?.openPanel(PanelId(ATLAS_PANEL_ID, ATLAS_PANEL_ORDINAL), window)
            }
        }

        context.panelRegistry.registerPanel(CodebaseInfo) { ctx, panelInfo ->
            CodebaseComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                // Resolved per call, never cached: plugin load order is not
                // guaranteed, so a null now may be a gateway that has simply
                // not registered yet.
                aiGateway = { pluginContext?.getPluginAPI(AiGatewayAPI::class.java) },
                // The readiness -> message mapping lives in
                // AiUnavailableMessage, not inline: it returns null for the
                // HAPPY path, and an elvis operator here treated that null as
                // "no plugin context" and reported a perfectly configured
                // gateway as "AI is unavailable on this host."
                aiUnavailable = {
                    AiUnavailableMessage.of(pluginContext?.let { AiAvailability.check(it) })
                },
                fileSystemDataProvider = fileSystemDataProvider,
                contextMenuProvider = contextMenuProvider,
                directoryPickerProvider = directoryPickerProvider,
                splitViewOperations = splitViewOperations,
                gitDataProvider = gitDataProvider,
                searchProvider = searchProvider,
                storage = storage,
                onAgentReview = { prompt ->
                    pluginScope?.launch { publishReview(prompt) }
                        ?: publishReview(prompt)
                },
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
        // Contribute codebase_* MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(
            CodebaseMcpToolProvider(
                providerId = pluginId,
                fileSystem = fileSystemDataProvider,
                projects = projectDataProvider,
                getWindowId = getWindowId,
                getProjectPath = getProjectPath,
            )
        )
        // P7: the git_* / project_* tools absorbed from the retired
        // git-status, git-log and search-replace plugins. A distinct provider
        // id - the registry keys providers by id, and a same-id re-registration
        // would replace the codebase_* tools above.
        context.registerMcpToolProvider(
            CodebaseGitMcpToolProvider(
                providerId = "$pluginId.git",
                // Suppliers, not values: see the constructor's KDoc. The
                // gateway above is resolved per call for the same reason.
                git = { gitDataProvider },
                search = { searchProvider },
            )
        )
    }

    /**
     * Drop every host reference on disable/unload. Without this the plugin
     * keeps a strong reference to [PluginContext] - and to every provider
     * hanging off it - for the life of the process, which is exactly the leak
     * AGENTS.md names as the reason the entry-point contract has a dispose().
     */
    override fun dispose() {
        fileSystemDataProvider = null
        contextMenuProvider = null
        directoryPickerProvider = null
        splitViewOperations = null
        projectDataProvider = null
        gitDataProvider = null
        searchProvider = null
        pluginScope = null
        getWindowId = { null }
        getProjectPath = { null }
        publishReview = { _ -> }
        pluginContext = null
    }

    companion object {
        const val EVENT_ATLAS_REVIEW = "atlas.review"

        /**
         * The fluck-agent panel Agent Review raises. The ordinal is
         * [PanelId]'s registration slot in the host, not a magic number this
         * plugin picks - it has to match the value fluck-agent registers with
         * or openPanel silently addresses nothing.
         */
        private const val ATLAS_PANEL_ID = "atlas"
        private const val ATLAS_PANEL_ORDINAL = 16

        /** Last-resort value when the bundled manifest cannot be read. */
        private const val FALLBACK_VERSION = "0.0.0"

        /**
         * The version from the bundled plugin.json, whose version field the
         * build's processResources filter syncs from build.gradle.kts. Reading
         * it here removes the hand-maintained third copy of the version -
         * which has drifted before (the field said 1.0.9 while gradle said
         * 1.5.8).
         */
        private fun readManifestVersion(): String {
            // Every plugin's manifest lives at the SAME resource path, so
            // getResourceAsStream returns whichever one the classloader
            // reaches first - another plugin's, under parent-first delegation
            // or the shared classloader of the in-process fallback mode this
            // manifest declares. Walk them all and take the one whose
            // pluginId is ours.
            val resources =
                CodebaseDynamicPlugin::class.java.classLoader
                    ?.getResources("META-INF/boss-plugin/plugin.json")
                    ?: return FALLBACK_VERSION
            val pluginId = "ai.rever.boss.plugin.dynamic.codebase"
            for (url in resources) {
                val text = runCatching { url.openStream().use { it.readBytes().decodeToString() } }
                    .getOrNull() ?: continue
                if (field(text, "pluginId") != pluginId) continue
                field(text, "version")?.let { return it }
            }
            return FALLBACK_VERSION
        }

        /** One top-level string field out of a manifest, without a JSON library on the classpath. */
        private fun field(json: String, name: String): String? =
            Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                .find(json)
                ?.groupValues
                ?.get(1)
    }
}

/**
 * Why AI is unavailable, or null when it is ready.
 *
 * Its own object because the contract - null means EVERYTHING IS FINE - is
 * the shape that invites `?:`. Written inline, `context?.let { …READY -> null… }
 * ?: "AI is unavailable on this host."` reported a working gateway as broken,
 * because elvis cannot tell "the let returned null" from "there was no
 * context". Only a null [readiness], i.e. no plugin context at all, is
 * unavailability here.
 */
internal object AiUnavailableMessage {

    const val NO_HOST = "AI is unavailable on this host."
    const val NO_GATEWAY = "The AI Gateway plugin is not loaded. Install or enable it in the Toolbox."
    const val NO_PROVIDER = "No AI model selected. Pick a provider or a CLI engine in the AI Gateway."

    /** @param readiness null when there is no plugin context to ask. */
    fun of(readiness: AiReadiness?): String? =
        when (readiness) {
            null -> NO_HOST
            AiReadiness.READY -> null
            AiReadiness.GATEWAY_MISSING -> NO_GATEWAY
            // AiReadiness is documented as an open set; anything new reads as
            // "configure a provider", which is the likelier of the two fixes.
            else -> NO_PROVIDER
        }
}
