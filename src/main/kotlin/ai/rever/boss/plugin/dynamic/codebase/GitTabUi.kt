package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The GIT tab - VS Code's Source Control view, plus a commit graph below it.
 *
 * Layout, top to bottom: a toolbar naming the branch, the commit message box
 * with a full-width Commit button, the change groups (Staged / Changes /
 * Untracked), a draggable splitter, then the lane graph. The splitter exists
 * because the panel is user-resizable: a fixed 50/50 weight left the graph
 * unusable in a short panel and the changes list unusable in a tall one.
 *
 * Destructive rows (discard, revert, cherry-pick, checkout) go through
 * [GitConfirmDialog]. Discard in particular was one un-confirmed click away
 * from throwing work away.
 */
@Composable
fun CodebaseGitContent(
    viewModel: CodebaseGitViewModel,
    modifier: Modifier = Modifier,
) {
    if (viewModel.providerOrNull == null) {
        CodebaseEmptyState("Git is unavailable on this host build.", modifier.fillMaxSize())
        return
    }

    val fileStatus by viewModel.fileStatus.collectAsState()
    val graph by viewModel.graph.collectAsState()
    val reviewing by viewModel.reviewing.collectAsState()
    val hasMoreGraph by viewModel.hasMoreGraph.collectAsState()
    val graphBusy by viewModel.graphBusy.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val isRepo by viewModel.isGitRepository.collectAsState()
    val branch by viewModel.currentBranch.collectAsState()
    val commitMessage by viewModel.commitMessage.collectAsState()
    val noRepoHint by viewModel.noRepoHint.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val reviewInstructions by viewModel.reviewInstructions.collectAsState()
    val reviewDeep by viewModel.reviewDeep.collectAsState()
    val reviewBase by viewModel.reviewBase.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val branchOptions by viewModel.branchOptions.collectAsState()
    val remoteNames by viewModel.remoteNames.collectAsState()
    val graphRef by viewModel.graphRef.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val layout by viewModel.changeLayout.collectAsState()

    var settingsOpen by remember { mutableStateOf(false) }
    var stagedOpen by remember { mutableStateOf(true) }
    var changesOpen by remember { mutableStateOf(true) }
    var untrackedOpen by remember { mutableStateOf(true) }
    var graphOpen by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<GitConfirmation?>(null) }
    // One set across all three change groups on purpose: a directory you
    // have folded away is folded away, and the same path appearing in two
    // groups is the same directory in the same project.
    var collapsedDirs by remember { mutableStateOf(emptySet<String>()) }
    // In the view model, not a `remember`: the tab composable tears down on
    // every tab hop, and a layout preference should not reset with it.
    val splitFractionState = viewModel.splitFraction.collectAsState()
    val splitFraction by splitFractionState

    val staged = remember(fileStatus) { fileStatus.filter { it.isStaged } }
    val changed = remember(fileStatus) { fileStatus.filter { it.isUnstaged && !it.isUntracked } }
    val untracked = remember(fileStatus) { fileStatus.filter { it.isUntracked } }

    // Poll only while this tab is on screen, and keep a graph the user has
    // already paged through instead of resetting it on every tab switch.
    DisposableEffect(Unit) {
        viewModel.refreshStatus()
        viewModel.startStatusTimer()
        onDispose { viewModel.stopStatusTimer() }
    }

    // Nothing staged but tracked edits present: commit stages them first, the
    // way VS Code's Commit does, and the label says so. It stages exactly
    // `changed` - the tracked edits the label counts - and never the UNTRACKED
    // group, which the user has not touched.
    val commitStagesAll = staged.isEmpty() && changed.isNotEmpty()
    val canCommit = (staged.isNotEmpty() || commitStagesAll) && commitMessage.isNotBlank() && !busy
    val doCommit = {
        if (canCommit) {
            viewModel.commit(stageFirst = if (commitStagesAll) changed.map { it.path } else emptyList())
        }
    }

    // Box, not a bare Column - see the note in CodebaseSearchContent. Emitted
    // after the Column, GitConfirmDialog was measured at zero height, so
    // Discard / Revert / Cherry-pick / Checkout silently did nothing.
    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        GitToolbar(
            branch = branch,
            isRepo = isRepo,
            busy = busy,
            onRefresh = {
                viewModel.refreshStatus()
                viewModel.loadGraph(reset = true)
            },
            layout = layout,
            onToggleLayout = { viewModel.toggleChangeLayout() },
        )

        // ── Commit box: field on its own line, button under it ─────────────
        // Side by side, the field collapsed to nothing once the panel was
        // narrowed - the whole point of stacking them.
        Column(modifier = Modifier.padding(horizontal = CodebaseMetrics.Gutter, vertical = 6.dp)) {
            CodebaseTextField(
                value = commitMessage,
                onValueChange = viewModel::setCommitMessage,
                placeholder = "Message (${commitShortcutHint()} to commit)",
                modifier = Modifier.fillMaxWidth(),
                keyHandler = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        (event.isMetaPressed || event.isCtrlPressed)
                    ) {
                        doCommit()
                        true
                    } else {
                        false
                    }
                },
                trailing = {
                    if (generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = CodebasePalette.Accent,
                        )
                    } else {
                        CodebaseIconButton(
                            icon = Icons.Outlined.AutoAwesome,
                            tooltip = "Generate a commit message from the changes",
                            onClick = { viewModel.generateCommitMessage() },
                            enabled = staged.isNotEmpty() || changed.isNotEmpty(),
                        )
                    }
                },
            )
            Spacer(Modifier.height(5.dp))
            CodebasePrimaryButton(
                label = if (commitStagesAll) "Commit All" else "Commit",
                onClick = doCommit,
                enabled = canCommit,
                icon = Icons.Rounded.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CodebaseHRule()

        if (!isRepo) {
            if (!loaded) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CodebasePalette.Accent,
                    )
                }
            } else {
                CodebaseEmptyState(
                    noRepoHint.ifBlank { "No Git repository in this project." },
                    Modifier.weight(1f),
                    icon = Icons.Rounded.AccountTree,
                )
            }
        } else {
            // ── Changes over graph, split by a draggable divider ───────────
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val totalPx = with(LocalDensity.current) { maxHeight.toPx() }
                val topWeight = if (graphOpen) splitFraction else 1f
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(topWeight).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 4.dp),
                    ) {
                        changeGroup(
                            title = "STAGED",
                            files = staged,
                            expanded = stagedOpen,
                            onToggle = { stagedOpen = !stagedOpen },
                            layout = layout,
                            collapsedDirs = collapsedDirs,
                            onToggleDir = { path ->
                                collapsedDirs = if (path in collapsedDirs) collapsedDirs - path else collapsedDirs + path
                            },
                            headerActions = {
                                CodebaseIconButton(Icons.Rounded.Remove, "Unstage all", { viewModel.unstageAll() }, enabled = !busy)
                            },
                            directoryActions = { files, hovered ->
                                CodebaseIconButton(
                                    Icons.Rounded.Remove,
                                    "Unstage this folder",
                                    { viewModel.unstagePaths(files.map { it.path }) },
                                    enabled = !busy,
                                    visible = hovered,
                                )
                            },
                            onOpen = { viewModel.openFileDiff(it.path, staged = true) },
                            rowActions = { f, hovered ->
                                CodebaseIconButton(Icons.Outlined.Difference, "Open changes", { viewModel.openFileDiff(f.path, staged = true) }, visible = hovered)
                                CodebaseIconButton(Icons.Rounded.Remove, "Unstage", { viewModel.unstage(f.path) }, enabled = !busy, visible = hovered)
                            },
                        )
                        changeGroup(
                            title = "CHANGES",
                            files = changed,
                            expanded = changesOpen,
                            onToggle = { changesOpen = !changesOpen },
                            layout = layout,
                            collapsedDirs = collapsedDirs,
                            onToggleDir = { path ->
                                collapsedDirs = if (path in collapsedDirs) collapsedDirs - path else collapsedDirs + path
                            },
                            headerActions = {
                                CodebaseIconButton(Icons.AutoMirrored.Rounded.Undo, "Discard all changes", onClick = {
                                    confirm = GitConfirmation(
                                        title = "Discard all changes",
                                        body = "Discard changes to ${changed.size} file(s)? This cannot be undone.",
                                        confirmLabel = "Discard all",
                                        destructive = true,
                                        action = { viewModel.discardPaths(changed.map { it.path }) },
                                    )
                                }, enabled = !busy)
                                // Stage exactly this group. The provider's
                                // stageAll is `git add -A`, which also stages
                                // untracked files - surprising under a button
                                // that sits beside a separate UNTRACKED group.
                                CodebaseIconButton(
                                    Icons.Rounded.Add,
                                    "Stage all changes",
                                    { viewModel.stagePaths(changed.map { it.path }) },
                                    enabled = !busy,
                                )
                            },
                            directoryActions = { files, hovered ->
                                CodebaseIconButton(
                                    Icons.AutoMirrored.Rounded.Undo,
                                    "Discard this folder's changes",
                                    {
                                        confirm = GitConfirmation(
                                            title = "Discard folder",
                                            body = "Discard changes to ${files.size} file(s) in this folder? This cannot be undone.",
                                            confirmLabel = "Discard",
                                            destructive = true,
                                            action = { viewModel.discardPaths(files.map { it.path }) },
                                        )
                                    },
                                    enabled = !busy,
                                    visible = hovered,
                                )
                                CodebaseIconButton(
                                    Icons.Rounded.Add,
                                    "Stage this folder",
                                    { viewModel.stagePaths(files.map { it.path }) },
                                    enabled = !busy,
                                    visible = hovered,
                                )
                            },
                            onOpen = { viewModel.openFileDiff(it.path, staged = false) },
                            rowActions = { f, hovered ->
                                // Editing is offered on the working tree only.
                                // The staged copy of a file is a snapshot in the
                                // index; "edit" there would silently edit the
                                // work tree instead, which is a different file.
                                CodebaseIconButton(Icons.Rounded.Edit, "Edit file", { viewModel.openFile(f.path) }, visible = hovered)
                                CodebaseIconButton(Icons.Outlined.Difference, "Open changes", { viewModel.openFileDiff(f.path, staged = false) }, visible = hovered)
                                CodebaseIconButton(Icons.AutoMirrored.Rounded.Undo, "Discard changes", onClick = {
                                    confirm = GitConfirmation(
                                        title = "Discard changes",
                                        body = "Discard changes to ${f.path}? This cannot be undone.",
                                        confirmLabel = "Discard",
                                        destructive = true,
                                        action = { viewModel.discard(f.path) },
                                    )
                                }, enabled = !busy, visible = hovered)
                                CodebaseIconButton(Icons.Rounded.Add, "Stage changes", { viewModel.stage(f.path) }, enabled = !busy, visible = hovered)
                            },
                        )
                        changeGroup(
                            title = "UNTRACKED",
                            files = untracked,
                            expanded = untrackedOpen,
                            onToggle = { untrackedOpen = !untrackedOpen },
                            layout = layout,
                            collapsedDirs = collapsedDirs,
                            onToggleDir = { path ->
                                collapsedDirs = if (path in collapsedDirs) collapsedDirs - path else collapsedDirs + path
                            },
                            headerActions = {
                                CodebaseIconButton(Icons.Rounded.Add, "Stage all untracked", { viewModel.stagePaths(untracked.map { it.path }) }, enabled = !busy)
                            },
                            directoryActions = { files, hovered ->
                                CodebaseIconButton(
                                    Icons.Rounded.Add,
                                    "Stage this folder",
                                    { viewModel.stagePaths(files.map { it.path }) },
                                    enabled = !busy,
                                    visible = hovered,
                                )
                            },
                            onOpen = { viewModel.openFile(it.path) },
                            rowActions = { f, hovered ->
                                CodebaseIconButton(Icons.Rounded.Edit, "Edit file", { viewModel.openFile(f.path) }, visible = hovered)
                                CodebaseIconButton(Icons.Rounded.Add, "Stage file", { viewModel.stage(f.path) }, enabled = !busy, visible = hovered)
                            },
                        )
                        if (loaded && staged.isEmpty() && changed.isEmpty() && untracked.isEmpty()) {
                            item {
                                CodebaseEmptyState(
                                    "No changes.",
                                    Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.DoneAll,
                                )
                            }
                        }
                    }

                    // A plain footer, pinned below the change list and above
                    // the graph: it acts on the changes, so it belongs at the
                    // end of them rather than in the toolbar, where it pushed
                    // the list down the panel.
                    //
                    // Deliberately NOT a CodebaseSectionHeader accordion. An
                    // accordion promises content you expand to work inside,
                    // and there is none here - one split button and a caption
                    // saying what it will act on. The header spent a row and a
                    // collapse state to hide two controls that are the reason
                    // this footer exists. The rules above and below are the
                    // separation it was really providing.
                    CodebaseHRule()
                    GitAgentReview(
                        expanded = settingsOpen,
                        onToggleExpanded = { settingsOpen = !settingsOpen },
                        instructions = reviewInstructions,
                        onInstructionsChange = viewModel::setReviewInstructions,
                        deep = reviewDeep,
                        onDeepChange = viewModel::setReviewDeep,
                        base = reviewBase,
                        branches = branches,
                        onBaseChange = viewModel::setReviewBase,
                        // reviewing, not busy: busy is every git operation, so
                        // staging a file used to relabel this pill "Reviewing…"
                        // and an unrelated fetch made it unclickable.
                        reviewing = reviewing,
                        onFindIssues = { viewModel.startAgentReview() },
                    )

                    CodebaseHRule()
                    CodebaseSectionHeader(
                        // No count. CodebaseSectionHeader defaults it to null,
                        // and the number it used to show was `graph.size` - how
                        // many commits are LOADED, which pages 50 at a time
                        // behind "Load more". That says nothing about the
                        // repository, only about how far you have scrolled, and
                        // it read as a fact about the branch. The change groups
                        // keep their counts: those are complete sets.
                        title = "GRAPH",
                        expanded = graphOpen,
                        onToggle = { graphOpen = !graphOpen },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        // Always on screen, not hover-revealed: the branch
                        // picker opens a popup, and un-hovering the header to
                        // reach that popup would close it - see the parameter's
                        // note in CodebaseUi.
                        actionsAlwaysVisible = true,
                        actions = {
                            GitGraphHeaderActions(
                                displayedRef = graphRef ?: branch,
                                onCurrentBranch = graphRef == null,
                                options = branchOptions,
                                busy = busy,
                                onSelect = { viewModel.selectGraphBranch(it) },
                                onBackToCurrent = { viewModel.showCurrentBranch() },
                                onFetch = { viewModel.fetch() },
                                onPull = { viewModel.pull() },
                                onPush = {
                                    confirm = GitConfirmation(
                                        title = "Push",
                                        body =
                                            "Push ${branch.ifBlank { "the current branch" }} to origin? " +
                                                "This publishes your commits to the remote.",
                                        confirmLabel = "Push",
                                        action = { viewModel.push() },
                                    )
                                },
                                onRefresh = { viewModel.loadGraph(reset = true) },
                            )
                        },
                    )
                    if (graphOpen) {
                        CodebaseSplitter(splitFractionState, viewModel::setSplitFraction, totalPx)
                        GitGraphList(
                            graph = graph,
                            graphBusy = graphBusy,
                            remoteNames = remoteNames,
                            hasMore = hasMoreGraph,
                            modifier = Modifier.weight((1f - splitFraction).coerceAtLeast(0.05f)),
                            onOpen = { viewModel.openCommitDiff(it.hash) },
                            onAction = { confirm = it },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        GitStatusLine(message = message, busy = busy, onDismiss = viewModel::clearMessage)
    }

    confirm?.let { c ->
        GitConfirmDialog(
            confirmation = c,
            onDismiss = { confirm = null },
            onConfirm = {
                c.action()
                confirm = null
            },
        )
    }
    }
}

/** The toolbar row: branch name, Agent Review, refresh, settings. */
@Composable
private fun GitToolbar(
    branch: String,
    isRepo: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    layout: GitChangeLayout,
    onToggleLayout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CodebaseMetrics.RowHeight + 6.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(CodebaseMetrics.Glyph),
            tint = CodebasePalette.Secondary,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = when {
                !isRepo -> "No repository"
                branch.isNotEmpty() -> branch
                else -> "…"
            },
            fontSize = CodebaseMetrics.SecondaryText,
            fontWeight = FontWeight.Medium,
            color = CodebasePalette.Foreground,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = CodebasePalette.Accent,
            )
            Spacer(Modifier.width(6.dp))
        }
        CodebaseIconButton(
            icon =
                if (layout == GitChangeLayout.TREE) Icons.Rounded.AccountTree
                else Icons.AutoMirrored.Rounded.FormatListBulleted,
            tooltip = if (layout == GitChangeLayout.TREE) "View as list" else "View as tree",
            onClick = onToggleLayout,
        )
        CodebaseIconButton(Icons.Outlined.Refresh, "Refresh", onRefresh)
    }
}

/**
 * The AGENT REVIEW section: one primary action, with the options that change
 * what it reviews folded behind a chevron.
 *
 * Replaces a settings row whose only control was an auto-start toggle - a
 * preference, occupying the space where the action should be.
 */
@Composable
private fun GitAgentReview(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    deep: Boolean,
    onDeepChange: (Boolean) -> Unit,
    base: String,
    branches: List<String>,
    onBaseChange: (String) -> Unit,
    reviewing: Boolean,
    onFindIssues: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Asymmetric on purpose. The 22dp section header used to sit
            // between the rule above and this button; with it gone, 2dp left
            // the button all but touching that rule. The bottom needs less
            // because the caption below already ends in an 8dp spacer, so the
            // block reads as 8dp above / 10dp below inside its two rules.
            .padding(start = CodebaseMetrics.Gutter, end = CodebaseMetrics.Gutter, top = 8.dp, bottom = 2.dp),
    ) {
        // One pill, split by a hairline: the action, and the disclosure that
        // opens what it will act on - the split-button shape the reference uses.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CodebaseMetrics.InputHeight)
                .clip(RoundedCornerShape(CodebaseMetrics.ButtonRadius))
                .background(
                    if (reviewing) CodebasePalette.Accent.copy(alpha = 0.25f)
                    else CodebasePalette.Accent.copy(alpha = 0.85f),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onAccent = CodebasePalette.onAccent()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (reviewing) Modifier else Modifier.clickable(onClick = onFindIssues)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(CodebaseMetrics.Glyph),
                    tint = if (reviewing) onAccent.copy(alpha = 0.45f) else onAccent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (reviewing) "Reviewing…" else "Fluck Agent Review",
                    fontSize = CodebaseMetrics.SecondaryText,
                    fontWeight = FontWeight.Medium,
                    color = if (reviewing) onAccent.copy(alpha = 0.45f) else onAccent,
                    maxLines = 1,
                    // "Fluck Agent Review" is two and a half times the width of
                    // the old "Find Issues", and it shares this pill with a
                    // sparkle icon and a 26dp chevron segment. Unweighted and
                    // with the default Clip overflow, a narrow panel cut the
                    // label off mid-glyph with no ellipsis to say so; the
                    // weight lets it be measured against what is actually left
                    // and shorten honestly.
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(onAccent.copy(alpha = 0.30f)),
            )
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .fillMaxHeight()
                    .clickable(onClick = onToggleExpanded),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        if (expanded) Icons.Rounded.KeyboardArrowDown
                        else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (expanded) "Hide review options" else "Review options",
                    modifier = Modifier.size(CodebaseMetrics.Glyph),
                    tint = onAccent,
                )
                // The options float under the button rather than expanding
                // above it: an inline block pushed the button down the panel
                // every time it opened, so the control moved out from under
                // the cursor that had just opened it.
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = onToggleExpanded,
                    modifier = Modifier
                        .width(REVIEW_MENU_WIDTH)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CodebasePalette.Raised)
                        .border(1.dp, CodebasePalette.BorderStrong, RoundedCornerShape(8.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        CodebaseTextField(
                            value = instructions,
                            onValueChange = onInstructionsChange,
                            placeholder = "Optional instructions…",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 4,
                            minHeight = 56.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        GitOptionDropdown(
                            label = "Approach",
                            value = if (deep) "Deep" else "Quick",
                            options = listOf("Quick", "Deep"),
                            onSelect = { onDeepChange(it == "Deep") },
                        )
                        GitOptionDropdown(
                            label = "Diff against…",
                            value = base.ifBlank { "HEAD" },
                            options = branches,
                            emptyHint = "No branches in the loaded history.",
                            onSelect = onBaseChange,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        CodebaseTooltip(
            // Says what is actually attached. "this branch's diff" read as a
            // full baseRef..HEAD review, which is not what is collected - the
            // brief asks the agent to fetch that half itself if it needs it.
            text = "Runs the agent over the uncommitted changes and reports what it finds. " +
                "Nothing is edited.",
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (base.isBlank()) "Review uncommitted changes."
                        else "Review uncommitted changes, targeting $base.",
                    fontSize = CodebaseMetrics.MetaText,
                    color = CodebasePalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A label with its current value on the right, opening a real dropdown.
 *
 * Previously the choices expanded inline underneath, which pushed the button
 * down the panel and read as a nested list rather than a control. A menu
 * leaves the surrounding layout still, which is what the reference does.
 */
@Composable
private fun GitOptionDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    emptyHint: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        CodebaseListRow(onClick = { open = true }, height = CodebaseMetrics.RowHeight + 2.dp) { _ ->
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = CodebaseMetrics.SecondaryText,
                color = CodebasePalette.Secondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                fontSize = CodebaseMetrics.SecondaryText,
                fontWeight = FontWeight.Medium,
                color = CodebasePalette.Foreground,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = CodebasePalette.Secondary,
            )
            Spacer(Modifier.width(2.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .background(CodebasePalette.Raised)
                .heightIn(max = 260.dp),
        ) {
            if (options.isEmpty()) {
                Text(
                    text = emptyHint ?: "Nothing to choose.",
                    fontSize = CodebaseMetrics.MetaText,
                    color = CodebasePalette.Muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text(
                        text = option,
                        fontSize = CodebaseMetrics.SecondaryText,
                        color = if (option == value) CodebasePalette.Foreground else CodebasePalette.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == value) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(13.dp),
                            tint = CodebasePalette.Accent,
                        )
                    }
                }
            }
        }
    }
}

/** A borderless text action, 11sp - the panel has no room for a Material TextButton. */
@Composable
private fun GitTextAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text = label,
        fontSize = CodebaseMetrics.MetaText,
        fontWeight = FontWeight.Medium,
        color = if (enabled) CodebasePalette.Accent else CodebasePalette.Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(CodebaseMetrics.ChipRadius))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** One change group: a hover-actioned header plus its file rows. */
private fun LazyListScope.changeGroup(
    title: String,
    files: List<GitFileStatusData>,
    expanded: Boolean,
    onToggle: () -> Unit,
    layout: GitChangeLayout,
    collapsedDirs: Set<String>,
    onToggleDir: (String) -> Unit,
    headerActions: @Composable RowScope.() -> Unit,
    onOpen: (GitFileStatusData) -> Unit,
    rowActions: @Composable RowScope.(GitFileStatusData, Boolean) -> Unit,
    directoryActions: @Composable RowScope.(List<GitFileStatusData>, Boolean) -> Unit,
) {
    if (files.isEmpty()) return
    item(key = "header-$title") {
        CodebaseSectionHeader(
            title = title,
            count = files.size,
            expanded = expanded,
            onToggle = onToggle,
            modifier = Modifier.padding(horizontal = 4.dp),
            actions = headerActions,
        )
    }
    if (!expanded) return
    if (layout == GitChangeLayout.LIST) {
        items(files, key = { "$title-${it.path}" }) { f ->
            GitFileRow(file = f, onOpen = { onOpen(f) }, actions = rowActions)
        }
        return
    }
    val rows = GitChangeTree.rows(files, collapsedDirs)
    // Precomputed once per group, not inside the directory row's composable
    // lambda: there it was an O(files) filter re-run on every recomposition
    // of every directory row.
    val filesByDir = rows.filterIsInstance<GitChangeTree.Row.Directory>()
        .associate { it.path to GitChangeTree.filesUnder(files, it.path) }
    items(rows, key = { row ->
        when (row) {
            is GitChangeTree.Row.Directory -> "$title-dir-${row.path}"
            is GitChangeTree.Row.FileRow -> "$title-file-${row.file.path}"
        }
    }) { row ->
        when (row) {
            is GitChangeTree.Row.Directory ->
                GitDirectoryRow(
                    row = row,
                    collapsed = row.path in collapsedDirs,
                    onToggle = { onToggleDir(row.path) },
                    actions = { hovered ->
                        directoryActions(filesByDir[row.path].orEmpty(), hovered)
                    },
                )

            is GitChangeTree.Row.FileRow ->
                GitFileRow(
                    file = row.file,
                    displayName = row.name,
                    displayDir = "",
                    indent = CodebaseMetrics.Indent + 8.dp + (row.depth * 12).dp,
                    onOpen = { onOpen(row.file) },
                    actions = rowActions,
                )
        }
    }
}

/** A collapsible directory row in tree mode. */
@Composable
private fun GitDirectoryRow(
    row: GitChangeTree.Row.Directory,
    collapsed: Boolean,
    onToggle: () -> Unit,
    actions: @Composable RowScope.(Boolean) -> Unit,
) {
    CodebaseListRow(onClick = onToggle) { hovered ->
        Spacer(Modifier.width(CodebaseMetrics.Indent + (row.depth * 12).dp))
        Icon(
            imageVector =
                if (collapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight
                else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = CodebasePalette.Secondary,
        )
        Text(
            text = row.label,
            fontSize = CodebaseMetrics.PrimaryText,
            color = CodebasePalette.Secondary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        actions(hovered)
        CodebaseCountBadge(row.fileCount, color = CodebasePalette.Secondary)
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * One changed file. VS Code's shape: name at full contrast, its directory
 * trailing behind it muted, hover actions, then the status letter pinned to
 * the right edge - so the letter stays put as actions appear and vanish.
 */
@Composable
private fun GitFileRow(
    file: GitFileStatusData,
    onOpen: () -> Unit,
    actions: @Composable RowScope.(GitFileStatusData, Boolean) -> Unit,
    displayName: String? = null,
    displayDir: String? = null,
    indent: androidx.compose.ui.unit.Dp = CodebaseMetrics.Indent + 8.dp,
) {
    val split = remember(file.path) { splitPathForDisplay(file.path) }
    val name = displayName ?: split.first
    val dir = displayDir ?: split.second
    val status = if (file.isStaged) file.indexStatus else file.workTreeStatus
    CodebaseListRow(onClick = onOpen) { hovered ->
        Spacer(Modifier.width(indent))
        // One weighted slot for the label pair. Left unweighted, a long file
        // name is measured against the full row width and shoves the actions
        // and the status letter past the right edge of the panel.
        CodebaseTooltip(file.path, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = CodebaseMetrics.PrimaryText,
                    color = CodebasePalette.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (dir.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = dir,
                        fontSize = CodebaseMetrics.MetaText,
                        color = CodebasePalette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        actions(file, hovered)
        Text(
            text = statusGlyph(status),
            fontSize = CodebaseMetrics.SecondaryText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = statusColor(status),
            modifier = Modifier.width(16.dp),
        )
    }
}

/** The lane graph, its own scroller under the splitter. */
@Composable
private fun GitGraphList(
    graph: List<GitCommitNodeData>,
    graphBusy: Boolean,
    remoteNames: Set<String>,
    hasMore: Boolean,
    modifier: Modifier,
    onOpen: (GitCommitNodeData) -> Unit,
    onAction: (GitConfirmation) -> Unit,
    viewModel: CodebaseGitViewModel,
) {
    val lanes = remember(graph) { GitGraphLayout.assignLanes(graph) }
    val graphRows = remember(graph, lanes) { GitGraphEdges.build(graph, lanes) }
    val laneCount = remember(graphRows) { GitGraphEdges.laneCount(graphRows).coerceAtLeast(1) }

    if (graph.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (graphBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CodebasePalette.Accent,
                )
            } else {
                CodebaseEmptyState("No commits.")
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        // Keyed by index AND hash. A duplicate key throws IllegalArgumentException
        // out of LazyColumn rather than rendering oddly, and a commit hash is
        // only unique if the list is - GitGraphEdges.build already uses
        // putIfAbsent for its row lookup, i.e. it does not assume that.
        itemsIndexed(graph, key = { i, n -> "$i-${n.hash}" }) { index, node ->
            GitCommitRow(
                node = node,
                graphRow = graphRows.getOrElse(index) {
                    GitGraphEdges.Row(lane = 0, isMerge = false, segments = emptyList())
                },
                laneCount = laneCount,
                remoteNames = remoteNames,
                onClick = { onOpen(node) },
                onAction = onAction,
                viewModel = viewModel,
            )
        }
        if (hasMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(CodebaseMetrics.RowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    if (graphBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = CodebasePalette.Accent,
                        )
                    } else {
                        GitTextAction("Load more", { viewModel.loadGraph(reset = false) })
                    }
                }
            }
        }
    }
}

/**
 * One commit row: lane canvas, subject, ref badges, relative date, and -
 * only while hovered - the history actions, each behind a confirmation.
 * The date and actions share a slot so the subject keeps its width.
 */
@Composable
private fun GitCommitRow(
    node: GitCommitNodeData,
    graphRow: GitGraphEdges.Row,
    laneCount: Int,
    remoteNames: Set<String>,
    onClick: () -> Unit,
    onAction: (GitConfirmation) -> Unit,
    viewModel: CodebaseGitViewModel,
) {
    CodebaseListRow(onClick = onClick, height = GRAPH_ROW_HEIGHT) { hovered ->
        GraphRowCanvas(row = graphRow, laneCount = laneCount)
        Spacer(Modifier.width(4.dp))
        // The row shows the relative age; the tooltip carries the absolute date
        // formatCommitDate was written for.
        val absoluteDate = remember(node.date) { formatCommitDate(node.date) }
        val tooltip =
            if (absoluteDate.isNotEmpty()) "${node.shortHash}  ${node.subject}\n${node.author}  •  $absoluteDate"
            else "${node.shortHash}  ${node.subject}\n${node.author}"
        CodebaseTooltip(
            tooltip,
            modifier = Modifier.weight(1f),
        ) {
            // Pills between the subject and the author, both of which give up
            // width to them: a branch head is the one thing on this row you
            // scan a graph FOR, and a long subject must not push it off the
            // edge. They were modelled and never drawn - GitRefBadge existed
            // but nothing called it, so no commit has ever shown a ref.
            val pills = remember(node.refs, remoteNames) {
                GitBranchModel.refPills(node.refs, remoteNames)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.subject,
                    fontSize = CodebaseMetrics.PrimaryText,
                    color = CodebasePalette.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                pills.take(MAX_REF_BADGES).forEach { pill ->
                    Spacer(Modifier.width(5.dp))
                    GitRefBadge(pill)
                }
                if (node.author.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = node.author.substringBefore(' '),
                        fontSize = CodebaseMetrics.MetaText,
                        color = CodebasePalette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (hovered) {
            CodebaseIconButton(Icons.Outlined.Difference, "Open commit diff", onClick)
            CodebaseIconButton(
                Icons.Outlined.Replay,
                "Revert commit",
                onClick = {
                    onAction(
                        GitConfirmation(
                            title = "Revert commit",
                            body = "Revert ${node.shortHash} (\"${node.subject}\")? This creates a new commit.",
                            confirmLabel = "Revert",
                            action = { viewModel.revert(node.hash) },
                        ),
                    )
                },
            )
            CodebaseIconButton(
                Icons.Rounded.ContentCopy,
                "Cherry-pick commit",
                onClick = {
                    onAction(
                        GitConfirmation(
                            title = "Cherry-pick commit",
                            body = "Apply ${node.shortHash} (\"${node.subject}\") onto the current branch?",
                            confirmLabel = "Cherry-pick",
                            action = { viewModel.cherryPick(node.hash) },
                        ),
                    )
                },
            )
            CodebaseIconButton(
                Icons.Rounded.Check,
                "Checkout commit",
                onClick = {
                    onAction(
                        GitConfirmation(
                            title = "Checkout commit",
                            body = "Check out ${node.shortHash}? This detaches HEAD from the current branch.",
                            confirmLabel = "Checkout",
                            destructive = true,
                            // The full hash, like revert and cherry-pick above:
                            // an abbreviation can collide in a large repository.
                            action = { viewModel.checkout(node.hash) },
                        ),
                    )
                },
            )
        } else {
            Text(
                text = formatRelativeDate(node.date),
                fontSize = CodebaseMetrics.MetaText,
                color = CodebasePalette.Muted,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * One ref pill.
 *
 * A branch HEAD reads as a filled chip because it is the thing the graph is
 * scanned for; a tag or a remote mirror reads as an outline, because it is
 * information about a commit rather than a place you can be. Colour comes from
 * the palette's semantic tokens, so both survive a light theme - a hardcoded
 * amber would not.
 */
@Composable
private fun GitRefBadge(pill: GitRefPill) {
    val color = when (pill.kind) {
        GitRefKind.HEAD -> CodebasePalette.Accent
        GitRefKind.LOCAL -> CodebasePalette.Warn
        GitRefKind.REMOTE -> CodebasePalette.Data
        GitRefKind.TAG -> CodebasePalette.Secondary
    }
    val filled = pill.kind == GitRefKind.HEAD || pill.kind == GitRefKind.LOCAL
    val content = if (filled) CodebasePalette.onAccent(color) else color
    val glyph = when (pill.kind) {
        GitRefKind.HEAD, GitRefKind.LOCAL -> Icons.Rounded.AccountTree
        GitRefKind.REMOTE -> Icons.Rounded.CloudQueue
        GitRefKind.TAG -> Icons.Rounded.LocalOffer
    }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .widthIn(max = REF_BADGE_MAX_WIDTH)
            // An EXPLICIT height, not one that falls out of the font.
            //
            // Without it the pill measured `padding + the label's full line
            // box`, and an untrimmed 10sp line box is ~13-14dp of ascent and
            // descent around ~7dp of glyph - so the chip came out very nearly
            // as tall as the 26dp row and read as a heavy badge rather than a
            // chip sitting inside it. Stating the height makes the proportion
            // arithmetic (REF_BADGE_HEIGHT vs GRAPH_ROW_HEIGHT) instead of a
            // font metric nobody can predict, and GitRefBadgeMetricsTest pins it.
            .height(REF_BADGE_HEIGHT)
            .clip(shape)
            .then(
                if (filled) Modifier.background(color)
                else Modifier.border(1.dp, color.copy(alpha = 0.55f), shape)
            )
            .padding(start = 3.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = content,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = pill.label,
            // Trimmed to the glyphs, like CodebaseCountBadge: a bare Text
            // centres its LINE BOX, whose ascent/descent padding is
            // asymmetric, so the label sat high in the pill AND forced it
            // taller than the text needs.
            style = TextStyle(
                fontSize = REF_BADGE_TEXT,
                lineHeight = REF_BADGE_TEXT,
                fontWeight = FontWeight.Medium,
                color = content,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The GRAPH header's controls: which branch the graph shows, the way back to
 * the checked-out one, and the three remote verbs.
 *
 * The picker is a chip rather than a full-width dropdown row because it shares
 * a 22dp section header with four icon buttons and a count badge. It is
 * unweighted and width-capped, so it is measured before the "GRAPH" title
 * (which carries the weight) - the controls keep their size and the title
 * truncates, rather than the controls being pushed off the panel's edge.
 */
@Composable
private fun GitGraphHeaderActions(
    displayedRef: String,
    onCurrentBranch: Boolean,
    options: List<GitBranchOption>,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onBackToCurrent: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onRefresh: () -> Unit,
) {
    GitBranchChip(
        label = displayedRef,
        onCurrentBranch = onCurrentBranch,
        options = options,
        onSelect = onSelect,
    )
    // Only while the graph is off HEAD: an always-present "back" that is
    // already where it would take you is a control that does nothing.
    if (!onCurrentBranch) {
        CodebaseIconButton(
            Icons.Rounded.MyLocation,
            "Back to the current branch",
            onBackToCurrent,
            tint = CodebasePalette.Accent,
        )
    }
    CodebaseIconButton(Icons.Rounded.CloudDownload, "Fetch from remote", onFetch, enabled = !busy)
    CodebaseIconButton(Icons.Rounded.ArrowDownward, "Pull", onPull, enabled = !busy)
    // Push is the only action here whose effect leaves this machine, so it
    // goes through GitConfirmDialog like the destructive row actions do.
    CodebaseIconButton(Icons.Rounded.ArrowUpward, "Push", onPush, enabled = !busy)
    CodebaseIconButton(Icons.Outlined.Refresh, "Reload graph", onRefresh)
}

/** The branch selector: a truncating chip that opens the branch list. */
@Composable
private fun GitBranchChip(
    label: String,
    onCurrentBranch: Boolean,
    options: List<GitBranchOption>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .widthIn(max = BRANCH_CHIP_MAX_WIDTH)
                .wrapContentWidth()
                .clip(RoundedCornerShape(CodebaseMetrics.ChipRadius))
                .background(if (onCurrentBranch) Color.Transparent else CodebasePalette.Selected)
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                // Accent while the graph is showing something other than the
                // checked-out branch: that is a state you can forget you are in.
                tint = if (onCurrentBranch) CodebasePalette.Secondary else CodebasePalette.Accent,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = label.ifBlank { "HEAD" },
                fontSize = CodebaseMetrics.MetaText,
                fontWeight = FontWeight.Medium,
                color = if (onCurrentBranch) CodebasePalette.Foreground else CodebasePalette.Accent,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Choose a branch",
                modifier = Modifier.size(12.dp),
                tint = CodebasePalette.Secondary,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier
                .background(CodebasePalette.Raised)
                .heightIn(max = 300.dp),
        ) {
            if (options.isEmpty()) {
                Text(
                    text = "No branches found.",
                    fontSize = CodebaseMetrics.MetaText,
                    color = CodebasePalette.Muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            options.forEach { option ->
                val selected = option.name == label
                DropdownMenuItem(
                    onClick = {
                        onSelect(option.name)
                        open = false
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Icon(
                        imageVector =
                            if (option.isRemote) Icons.Rounded.CloudQueue else Icons.Rounded.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (option.isCurrent) CodebasePalette.Accent else CodebasePalette.Secondary,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = option.name,
                        fontSize = CodebaseMetrics.SecondaryText,
                        color = if (selected) CodebasePalette.Foreground else CodebasePalette.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (option.isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "current",
                            fontSize = 9.sp,
                            color = CodebasePalette.Muted,
                            maxLines = 1,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Shown in the graph",
                            modifier = Modifier.size(13.dp),
                            tint = CodebasePalette.Accent,
                        )
                    }
                }
            }
        }
    }
}

/** The operation result line; dismissible, so a stale message never sticks. */
@Composable
private fun GitStatusLine(message: String?, busy: Boolean, onDismiss: () -> Unit) {
    if (message.isNullOrBlank()) return
    val failed = message.startsWith("Failed:")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (failed) CodebasePalette.Error.copy(alpha = 0.14f) else CodebasePalette.Hover)
            .padding(start = CodebaseMetrics.Gutter, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = CodebaseMetrics.MetaText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (failed) CodebasePalette.Error else CodebasePalette.Secondary,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = CodebasePalette.Accent,
            )
            Spacer(Modifier.width(6.dp))
        }
        CodebaseIconButton(Icons.Rounded.Close, "Dismiss", onDismiss)
    }
}

/** A pending destructive/history operation awaiting confirmation. */
internal data class GitConfirmation(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/**
 * Confirmation sheet. Drawn in-panel rather than as a Material `AlertDialog`:
 * the host's browser surface renders above plugin dialogs, so a dialog can
 * end up behind the content it is asking about.
 */
@Composable
private fun GitConfirmDialog(
    confirmation: GitConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // A modal sheet must EAT input: without a consuming pointer modifier the
    // scrim never consumes the click, so the rows underneath stay live - with
    // Discard and Push behind this, another row's "Discard changes" would fire
    // while the confirmation is on screen. The Box also needs focus for the
    // Escape handler to run: onPreviewKeyEvent on an unfocused node never
    // fires.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CodebaseScrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Every event, not only Press: consuming the down change
                    // is what stops a click reaching the rows underneath, and
                    // consuming scroll stops the list behind the sheet moving
                    // while it is up. Consumed on the MAIN pass, which runs
                    // child-first, so this sheet's own buttons still see it.
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(CodebaseMetrics.ButtonRadius))
                .background(CodebasePalette.Raised)
                .padding(14.dp),
        ) {
            Text(
                text = confirmation.title,
                fontSize = CodebaseMetrics.PrimaryText,
                fontWeight = FontWeight.SemiBold,
                color = CodebasePalette.Foreground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = confirmation.body,
                fontSize = CodebaseMetrics.SecondaryText,
                color = CodebasePalette.Secondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                GitTextAction("Cancel", onDismiss)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = confirmation.confirmLabel,
                    fontSize = CodebaseMetrics.SecondaryText,
                    fontWeight = FontWeight.SemiBold,
                    color = if (confirmation.destructive) CodebasePalette.Error else CodebasePalette.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(CodebaseMetrics.ChipRadius))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private fun commitShortcutHint(): String =
    if (System.getProperty("os.name").lowercase().contains("mac")) "⌘Enter" else "Ctrl+Enter"

/**
 * Relative age, the way a git UI shows it - "3h", "2d", "5mo". An absolute
 * date needs ~11 characters the panel does not have; the tooltip carries the
 * full commit detail.
 */
internal fun formatRelativeDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val deltaSec = (System.currentTimeMillis() / 1000L) - epochSeconds
    if (deltaSec < 0) return "now"
    val minutes = TimeUnit.SECONDS.toMinutes(deltaSec)
    val hours = TimeUnit.SECONDS.toHours(deltaSec)
    val days = TimeUnit.SECONDS.toDays(deltaSec)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}

/** Absolute commit date, for tooltips and tests. */
internal fun formatCommitDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    return try {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        ""
    }
}

/** One commit row. Fixed, so no child of the row can stretch it. */
internal val GRAPH_ROW_HEIGHT = 26.dp
private val GRAPH_LANE_WIDTH = 13.dp
private const val GRAPH_MAX_LANES = 6

/** Lane hue by index, cycling BOSS's status palette. */
internal fun laneColor(lane: Int): Color {
    val palette = CodebasePalette.laneColors
    return palette[lane % palette.size]
}

/**
 * One row of the lane graph. Each row owns its own canvas, so a LazyColumn
 * can recycle rows without spanning rows:
 *  - the top segment of lane L is drawn when the row above sat in L,
 *  - the bottom segment of lane L when the row below sits in L,
 *  - when this row's lane differs from the row below's, an elbow at the row
 *    mid connects the two lanes (and the bottom segment follows the new lane).
 *
 * Every coordinate goes through `toPx()`. DrawScope works in pixels, so the
 * previous `GRAPH_LANE_WIDTH_DP.toFloat()` drew the whole graph at 1/density
 * scale - on a Retina panel the lanes bunched into the left quarter of a
 * canvas twice as wide, with hairline strokes.
 */
@Composable
private fun GraphRowCanvas(
    row: GitGraphEdges.Row,
    laneCount: Int,
) {
    // Lanes past the ceiling all draw in the last one. That is a deliberate
    // width cap on a panel this narrow, not an oversight - but it means a
    // repository with more than GRAPH_MAX_LANES concurrent branches shows
    // several lines sharing the rightmost column.
    val drawnLanes = laneCount.coerceIn(1, GRAPH_MAX_LANES)
    val palette = CodebasePalette.laneColors
    Canvas(
        modifier = Modifier
            .width(GRAPH_LANE_WIDTH * drawnLanes + 6.dp)
            .height(GRAPH_ROW_HEIGHT),
    ) {
        val laneW = GRAPH_LANE_WIDTH.toPx()
        val inset = 3.dp.toPx()
        val stroke = 2.dp.toPx()
        val radius = 4.dp.toPx()
        val h = size.height
        val midY = h / 2f
        fun cx(lane: Int): Float = (lane.coerceIn(0, drawnLanes - 1) + 0.5f) * laneW + inset
        fun color(lane: Int): Color = palette[lane.coerceAtLeast(0) % palette.size]

        for (seg in row.segments) {
            val c = color(seg.colorLane)
            val x1 = cx(seg.fromLane)
            val x2 = cx(seg.toLane)
            val yTop = if (seg.fromNode) midY else 0f
            val yBottom = if (seg.toNode) midY else h
            if (seg.fromLane == seg.toLane) {
                drawLine(c, Offset(x1, yTop), Offset(x2, yBottom), stroke)
            } else {
                // A branch changing lanes reads as a curve, not an elbow: the
                // control points sit half a row apart so the bend stays inside
                // this row and meets the next one vertically.
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x1, yTop)
                    cubicTo(x1, yTop + (yBottom - yTop) * 0.55f, x2, yBottom - (yBottom - yTop) * 0.45f, x2, yBottom)
                }
                drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            }
        }

        val nodeX = cx(row.lane)
        val nodeColor = color(row.lane)
        if (row.isMerge) {
            // A merge reads as a ring, so a commit that joins history is
            // distinguishable from one that just continues it.
            drawCircle(nodeColor, radius = radius, center = Offset(nodeX, midY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            drawCircle(nodeColor, radius = radius * 0.35f, center = Offset(nodeX, midY))
        } else {
            drawCircle(nodeColor, radius = radius, center = Offset(nodeX, midY))
        }
    }
}

internal fun statusGlyph(type: GitFileStatusTypeData?): String =
    when (type) {
        null -> "?"
        GitFileStatusTypeData.MODIFIED -> "M"
        GitFileStatusTypeData.ADDED -> "A"
        GitFileStatusTypeData.DELETED -> "D"
        GitFileStatusTypeData.RENAMED -> "R"
        GitFileStatusTypeData.COPIED -> "C"
        GitFileStatusTypeData.UNTRACKED -> "U"
        GitFileStatusTypeData.IGNORED -> "I"
        GitFileStatusTypeData.UNMERGED -> "!"
    }

/** VS Code's `gitDecoration.*` colours, so the letters read the same way. */
@Composable
internal fun statusColor(type: GitFileStatusTypeData?): Color =
    when (type) {
        null -> CodebasePalette.Muted
        GitFileStatusTypeData.MODIFIED, GitFileStatusTypeData.RENAMED, GitFileStatusTypeData.COPIED ->
            CodebasePalette.Modified
        GitFileStatusTypeData.ADDED -> CodebasePalette.Added
        GitFileStatusTypeData.UNTRACKED -> CodebasePalette.Untracked
        GitFileStatusTypeData.DELETED -> CodebasePalette.Deleted
        GitFileStatusTypeData.UNMERGED -> CodebasePalette.Conflict
        GitFileStatusTypeData.IGNORED -> CodebasePalette.Muted
    }

/** Width of the review options popup. */
private val REVIEW_MENU_WIDTH = 260.dp

/** A ref pill shortens rather than crowding the commit subject out of its row. */
private val REF_BADGE_MAX_WIDTH = 120.dp

/**
 * Height of a ref pill - deliberately well under [GRAPH_ROW_HEIGHT].
 *
 * A chip inside the row, not a band across it: the leftover 10dp is the
 * breathing room above and below, which only exists because the pill is
 * shorter than the row it is centred in.
 */
internal val REF_BADGE_HEIGHT = 16.dp

/**
 * Label size on a ref pill - well under [CodebaseMetrics.PrimaryText] (13sp),
 * so the branch name reads as an annotation on the subject rather than as a
 * second subject competing with it.
 */
internal val REF_BADGE_TEXT = 9.sp

/**
 * Pills drawn on one commit before the rest are dropped.
 *
 * A release commit can carry a dozen refs; past two the row is pills and no
 * subject, which is the opposite of useful. The tooltip still names the commit.
 */
private const val MAX_REF_BADGES = 2

/** The header's branch chip truncates rather than pushing the icons off the panel. */
private val BRANCH_CHIP_MAX_WIDTH = 116.dp
