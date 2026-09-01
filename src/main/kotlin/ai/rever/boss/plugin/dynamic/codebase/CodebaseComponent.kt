package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.ProjectSearchProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three top tabs of the codebase panel (P7). */
enum class CodebaseTab(val label: String, val storageKey: String) {
    FILES("Files", "files"),
    SEARCH("Search", "search"),
    GIT("Git", "git");

    val icon: ImageVector
        get() =
            when (this) {
                FILES -> Icons.Rounded.FolderOpen
                SEARCH -> Icons.Rounded.Search
                GIT -> Icons.Rounded.AccountTree
            }

    companion object {
        fun fromStorage(key: String?): CodebaseTab =
            entries.firstOrNull { it.storageKey == key } ?: FILES
    }
}

/**
 * Codebase panel component (Dynamic Plugin).
 *
 * P7: the panel is a FILES/SEARCH/GIT tab switch (Cursor-style, on BOSS's
 * palette). FILES keeps the original file tree; SEARCH is global search &
 * replace on the host's [ProjectSearchProvider]; GIT is the change groups +
 * lane graph + Agent Review. The selected tab persists across restarts via
 * [storage].
 */
class CodebaseComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val contextMenuProvider: ContextMenuProvider?,
    private val directoryPickerProvider: DirectoryPickerProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val gitDataProvider: GitDataProvider?,
    private val searchProvider: ProjectSearchProvider?,
    private val storage: PluginStorageProvider?,
    private val onAgentReview: (prompt: String) -> Unit,
    private val scope: CoroutineScope,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?,
    private val onSelectProject: ((String, String) -> Unit)?,
    private val aiGateway: () -> ai.rever.boss.plugin.api.AiGatewayAPI? = { null },
    private val aiUnavailable: () -> String? = { null }
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        var selectedTab by remember { mutableStateOf(CodebaseTab.FILES) }

        // Both view models own a coroutine scope, so they are created once and
        // cancelled when the panel leaves composition. They used to be built
        // by assigning to state *during* composition and never disposed, which
        // leaked a scope (and a 15s git poll) per panel open.
        val searchViewModel = remember {
            CodebaseSearchViewModel(
                provider = searchProvider,
                splitViewOperations = splitViewOperations,
                getProjectPath = getProjectPath,
            )
        }
        val gitViewModel = remember {
            CodebaseGitViewModel(
                git = gitDataProvider,
                getProjectPath = getProjectPath,
                getWindowId = getWindowId,
                onAgentReview = onAgentReview,
                aiGateway = aiGateway,
                aiUnavailable = aiUnavailable,
            )
        }
        DisposableEffect(Unit) {
            onDispose {
                searchViewModel.dispose()
                gitViewModel.dispose()
            }
        }

        // Three separate collectors on purpose. A StateFlow collect never
        // completes, so chaining these in one LaunchedEffect body left every
        // statement after the first collect unreachable - the splitter
        // position was neither loaded nor persisted.
        LaunchedEffect(Unit) {
            // The reads are blocking I/O - and the tab-switch write below is
            // already off the UI thread, so keep this path consistent: a
            // blocking storage implementation must not stall the first frame.
            val saved = withContext(Dispatchers.IO) { storage?.getString("codebase.tab") }
            selectedTab = CodebaseTab.fromStorage(saved)
        }
        LaunchedEffect(Unit) {
            // The change-group layout was a `remember` inside the GIT tab, so
            // it reset on every hop to FILES and back. It is a preference;
            // persist it beside the selected tab. Seeded BEFORE the collector
            // below starts, so the load is not immediately overwritten.
            gitViewModel.setChangeLayout(
                withContext(Dispatchers.IO) {
                    GitChangeLayout.fromStorage(storage?.getString("codebase.gitLayout"))
                },
            )
            gitViewModel.changeLayout.collect { layout ->
                withContext(Dispatchers.IO) {
                    storage?.putString("codebase.gitLayout", layout.storageKey)
                }
            }
        }
        LaunchedEffect(Unit) {
            gitViewModel.setSplitFraction(
                withContext(Dispatchers.IO) {
                    storage?.getString("codebase.gitSplit")?.toFloatOrNull() ?: DEFAULT_SPLIT
                },
            )
            // collectLatest, not collect: a splitter drag emits a value per
            // pointer move and each one would be a file write. The pending
            // write is cancelled by the next value, so only where the drag
            // settled is stored. (collectLatest and not `debounce`, which is
            // still a @FlowPreview API.)
            gitViewModel.splitFraction.collectLatest { fraction ->
                delay(SPLIT_SETTLE_MS)
                withContext(Dispatchers.IO) {
                    storage?.putString("codebase.gitSplit", fraction.toString())
                }
            }
        }
        // The selected project is exposed as a plain getter, not a flow, so it
        // is sampled: the header has to follow a project switch made anywhere
        // (the top bar, the FILES picker, another panel), and only re-renders
        // when the value actually changes.
        var projectPath by remember { mutableStateOf(getProjectPath()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(PROJECT_POLL_MS)
                val current = getProjectPath()
                if (current != projectPath) {
                    projectPath = current
                    // GIT and SEARCH keep per-project state: the commit graph,
                    // branch chip and result tree all describe the project
                    // that was active when they loaded. Reset both against
                    // the new project instead of keeping the previous one on
                    // screen until a manual refresh.
                    gitViewModel.onProjectChanged()
                    searchViewModel.clear()
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(CodebasePalette.Panel)) {
            CodebaseProjectHeader(projectPath)
            CodebaseTabStrip(selected = selectedTab) { tab ->
                selectedTab = tab
                scope.launch(Dispatchers.IO) {
                    storage?.putString("codebase.tab", tab.storageKey)
                }
            }
            when (selectedTab) {
                CodebaseTab.FILES ->
                    CodebaseContent(
                        fileSystemDataProvider = fileSystemDataProvider,
                        directoryPickerProvider = directoryPickerProvider,
                        splitViewOperations = splitViewOperations,
                        contextMenuProvider = contextMenuProvider,
                        scope = scope,
                        getWindowId = getWindowId,
                        getProjectPath = getProjectPath,
                        onSelectProject = onSelectProject,
                    )

                CodebaseTab.SEARCH ->
                    CodebaseSearchContent(viewModel = searchViewModel, modifier = Modifier.fillMaxSize())

                CodebaseTab.GIT ->
                    CodebaseGitContent(viewModel = gitViewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}


/**
 * The project the panel is showing, above the tab strip - so which directory
 * you are in is answered without switching to FILES. Name on top, the path
 * home-collapsed underneath it, the full absolute path on hover.
 */
@Composable
private fun CodebaseProjectHeader(projectPath: String?) {
    val path = projectPath.orEmpty()
    val hasProject = path.isNotEmpty()
    val name = if (hasProject) PathUtils.name(path).ifEmpty { path } else "No project"
    val display = if (hasProject) collapseHome(path) else "Open a folder in Files"

    CodebaseTooltip(
        text = if (hasProject) path else "No project selected",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodebasePalette.Panel)
                .padding(start = CodebaseMetrics.Gutter, end = CodebaseMetrics.Gutter, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(CodebaseMetrics.Glyph),
                tint = if (hasProject) CodebasePalette.Secondary else CodebasePalette.Muted,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = CodebaseMetrics.SecondaryText,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    color = if (hasProject) CodebasePalette.Foreground else CodebasePalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = display,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CodebasePalette.Muted,
                    maxLines = 1,
                    // The tail identifies the directory; the head is the part
                    // worth dropping when the panel is narrow.
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

/**
 * `/Users/me/src/app` reads as `~/src/app` - the home prefix carries nothing.
 *
 * Separator taken from [PathUtils] rather than hardcoded to '/': these paths
 * come from `File.absolutePath`, so on Windows both the home directory and
 * the project path are backslash-joined and a '/'-only rule never matched.
 */
internal fun collapseHome(
    path: String,
    separator: Char = PathUtils.platformSeparator,
    userHome: String? = System.getProperty("user.home"),
): String {
    val home = userHome?.trimEnd(separator).orEmpty()
    if (home.isEmpty()) return path
    return when {
        path == home -> "~"
        PathUtils.isNestedUnder(path, home, separator) -> "~" + path.removePrefix(home)
        else -> path
    }
}

/**
 * How often the selected project is sampled for the header. The getter changes
 * at most once per project switch (minutes apart at best), so 1s only added IPC
 * churn - 5s is indistinguishable in practice.
 */
private const val PROJECT_POLL_MS = 5_000L

/** Splitter position when storage holds none. Matches CodebaseSplitter's clamp midpoint. */
private const val DEFAULT_SPLIT = 0.55f

/** How long a splitter drag must settle before its position is written. */
private const val SPLIT_SETTLE_MS = 300L

/**
 * The panel's tab strip: icon + label while the panel is wide enough, icons
 * alone once it is narrow. The panel is user-resizable, so a fixed three-up
 * label row is exactly the layout that breaks - the labels either clip or
 * squeeze the icons off-centre.
 */
@Composable
private fun CodebaseTabStrip(
    selected: CodebaseTab,
    onSelect: (CodebaseTab) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val showLabels = maxWidth >= LABEL_WIDTH_THRESHOLD
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CodebaseMetrics.TabStripHeight),
        ) {
            CodebaseTab.entries.forEach { tab ->
                CodebaseTabButton(
                    tab = tab,
                    selected = tab == selected,
                    showLabel = showLabels,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    CodebaseHRule()
}

@Composable
private fun CodebaseTabButton(
    tab: CodebaseTab,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint =
        when {
            selected -> CodebasePalette.Foreground
            hovered -> CodebasePalette.Secondary
            else -> CodebasePalette.Muted
        }
    Box(
        modifier = modifier
            .fillMaxSize()
            .hoverable(interaction)
            .background(if (hovered && !selected) CodebasePalette.Hover else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                modifier = Modifier.size(CodebaseMetrics.Glyph),
                tint = tint,
            )
            if (showLabel) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = tab.label.uppercase(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.0.sp,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
        // Selection sits on the bottom edge, VS Code's active-tab border.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) CodebasePalette.Accent else Color.Transparent),
        )
    }
}

/** Below this the labels are dropped and the strip goes icon-only. */
private val LABEL_WIDTH_THRESHOLD = 240.dp
