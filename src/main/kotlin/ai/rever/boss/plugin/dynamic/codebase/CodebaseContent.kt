package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope

// UI chrome colors now consume reactive BOSS theme tokens so the panel
// re-skins automatically when the host theme changes. These are thin aliases
// onto BossThemeColors so the existing call sites keep their descriptive names.
private val BossDarkBackground: Color get() = BossThemeColors.BackgroundColor
private val BossDarkBorder: Color get() = BossThemeColors.BorderColor
private val BossDarkTextSecondary: Color get() = BossThemeColors.TextSecondary
private val BossHeaderColor: Color get() = BossThemeColors.SurfaceColor
private val BossAccentBlue: Color get() = BossThemeColors.AccentColor
private val BossLinkBlue: Color get() = BossThemeColors.AccentColor
private val BossErrorRed: Color get() = BossThemeColors.ErrorColor
private val BossTextColor: Color get() = BossThemeColors.TextPrimary
private val TreeRowHeight = 26.dp

/**
 * Main content composable for the Codebase panel.
 * Ported from bundled plugin v8.16.22 with exact UI parity.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CodebaseContent(
    fileSystemDataProvider: FileSystemDataProvider?,
    directoryPickerProvider: DirectoryPickerProvider?,
    splitViewOperations: SplitViewOperations?,
    contextMenuProvider: ContextMenuProvider?,
    scope: CoroutineScope,
    getWindowId: () -> String?,
    getProjectPath: () -> String?,
    onSelectProject: ((String, String) -> Unit)?
) {
    val viewModel = remember(fileSystemDataProvider, directoryPickerProvider, splitViewOperations) {
        CodebaseViewModel(
            fileSystemDataProvider = fileSystemDataProvider,
            directoryPickerProvider = directoryPickerProvider,
            splitViewOperations = splitViewOperations,
            scope = scope,
            getWindowId = getWindowId,
            getProjectPath = getProjectPath,
            onSelectProject = onSelectProject
        )
    }

    // The ViewModel runs a file-watcher poll loop on the plugin-lifetime
    // scope; stop it when this panel instance leaves the composition (or the
    // ViewModel is recreated with new providers).
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    val projectPath = getProjectPath()
    val projectName = projectPath?.let { PathUtils.name(it) }?.ifEmpty { "Project" } ?: ""
    val hasProject = !projectPath.isNullOrEmpty()

    val tree by viewModel.fileTree.collectAsState()
    val expandedPaths by viewModel.expandedPaths.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val listState = rememberLazyListState()

    // Dialog state for creating files/folders
    var showCreateFileDialog by remember { mutableStateOf<String?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, name)
    var showBulkDeleteDialog by remember { mutableStateOf<List<String>?>(null) } // paths
    var showRenameDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, currentName)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Reload tree when project changes
    LaunchedEffect(projectPath) {
        if (!projectPath.isNullOrEmpty()) {
            viewModel.clearCache()
            viewModel.loadFileTree(projectPath)
        } else {
            viewModel.clearTree()
        }
    }

    BossTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossDarkBackground)
    ) {
        if (!hasProject) {
            // Empty state - show Open Project button
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "No project open",
                        tint = BossDarkTextSecondary,
                        modifier = Modifier.size(48.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "No project opened",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Open a project to browse files",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { viewModel.pickDirectory() },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossAccentBlue,
                            contentColor = BossThemeColors.TextPrimary
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Project",
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            // Header with project info
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = BossHeaderColor,
                elevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "Project",
                        tint = BossLinkBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = projectName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Hidden on host binaries that predate the showHidden
                    // overloads — the flag would be silently ignored there.
                    if (viewModel.supportsShowHidden) {
                        TooltipArea(
                            tooltip = {
                                Surface(
                                    color = BossHeaderColor,
                                    shape = RoundedCornerShape(4.dp),
                                    elevation = 4.dp,
                                    border = BorderStroke(1.dp, BossDarkBorder)
                                ) {
                                    Text(
                                        text = if (showHidden) {
                                            "Hide hidden files (dotfiles)"
                                        } else {
                                            "Show hidden files (dotfiles) — build/ and node_modules/ stay hidden"
                                        },
                                        fontSize = 11.sp,
                                        color = BossTextColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = if (showHidden) "Hide hidden files (dotfiles)" else "Show hidden files (dotfiles)",
                                tint = if (showHidden) BossAccentBlue else BossDarkTextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { viewModel.setShowHidden(!showHidden) }
                            )
                        }
                    }
                }
            }

            Divider(color = BossDarkBorder)

            // File tree, fully virtualized: the visible tree is flattened into
            // one LazyColumn item per row (issue #8), so deep expanded subtrees
            // don't compose eagerly inside a single item.
            val rows = remember(tree, expandedPaths) {
                FileTreeUtils.flattenVisibleRows(tree, expandedPaths)
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val topPadding = 4.dp
                val emptySpaceHeight = (maxHeight - topPadding - TreeRowHeight * rows.size).coerceAtLeast(0.dp)
                val emptySpaceBaseModifier = Modifier
                    .fillMaxWidth()
                    .height(emptySpaceHeight)
                    // Clear the row selection before the project-root menu opens.
                    // Observe without consuming so the host context-menu handler
                    // still receives the same secondary-button press.
                    .pointerInput(projectPath) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    viewModel.clearSelection()
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { viewModel.clearSelection() }
                    }
                val emptySpaceMenuItems = projectRootContextMenuItems(
                    projectPath = projectPath.orEmpty(),
                    onCreateFile = { showCreateFileDialog = it },
                    onCreateFolder = { showCreateFolderDialog = it },
                    onCopyPath = viewModel::copyPath,
                    onRevealInFileManager = viewModel::revealInFileManager,
                    onOpenInTerminal = viewModel::openInTerminal
                )
                val emptySpaceModifier = if (contextMenuProvider != null) {
                    contextMenuProvider.applyContextMenu(emptySpaceBaseModifier, emptySpaceMenuItems)
                } else {
                    emptySpaceBaseModifier
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPadding)
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is VisibleRow.Node -> FileTreeItem(
                                node = row.node,
                                level = row.level,
                                expandedPaths = expandedPaths,
                                selectedPaths = selectedPaths,
                                onToggleExpanded = viewModel::toggleExpanded,
                                onSelectOnly = viewModel::selectOnly,
                                onToggleSelect = viewModel::toggleSelection,
                                onRangeSelect = viewModel::selectRangeTo,
                                onFileDoubleClick = { file ->
                                    if (!file.isDirectory) {
                                        viewModel.openFile(file.path)
                                    }
                                },
                                onCreateFile = { targetPath -> showCreateFileDialog = targetPath },
                                onCreateFolder = { targetPath -> showCreateFolderDialog = targetPath },
                                onDelete = { path, name -> showDeleteDialog = Pair(path, name) },
                                onRename = { path, name -> showRenameDialog = Pair(path, name) },
                                onCopyPath = { path -> viewModel.copyPath(path) },
                                onCopyRelativePath = { path -> viewModel.copyRelativePath(path) },
                                onRevealInFileManager = { path -> viewModel.revealInFileManager(path) },
                                onOpenInTerminal = { path -> viewModel.openInTerminal(path) },
                                onOpenInEditor = { path -> viewModel.openFileInEditor(path) },
                                onOpenInBrowser = { path -> viewModel.openFileInBrowser(path) },
                                onOpenWithDefaultApp = { path -> viewModel.openWithDefaultApp(path) },
                                onBulkCopyPaths = { paths -> viewModel.copyPaths(paths) },
                                onBulkCopyRelativePaths = { paths -> viewModel.copyRelativePaths(paths) },
                                onBulkDelete = { paths -> showBulkDeleteDialog = paths },
                                contextMenuProvider = contextMenuProvider
                            )
                            is VisibleRow.Loading -> TreeStatusRow(level = row.level, loading = true)
                            is VisibleRow.Empty -> TreeStatusRow(level = row.level, loading = false)
                        }
                    }
                    if (emptySpaceHeight > 0.dp) {
                        item(key = "project-root-empty-space") {
                            Box(modifier = emptySpaceModifier)
                        }
                    }
                }
            }
        }
    }

    // Create File Dialog
    showCreateFileDialog?.let { targetPath ->
        CreateItemDialog(
            title = "New File",
            icon = Icons.AutoMirrored.Outlined.NoteAdd,
            placeholder = "Enter file name",
            targetPath = targetPath,
            errorMessage = errorMessage,
            onDismiss = {
                showCreateFileDialog = null
                errorMessage = null
            },
            onCreate = { fileName ->
                val validationError = validateFileName(fileName)
                if (validationError != null) {
                    errorMessage = validationError
                } else {
                    viewModel.createFile(targetPath, fileName) { result ->
                        result.fold(
                            onSuccess = { createdPath ->
                                showCreateFileDialog = null
                                errorMessage = null
                                // Open the new file: routes .ipynb to the notebook panel and
                                // other files to the editor (via the host's file router).
                                viewModel.openFile(createdPath)
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Failed to create file"
                            }
                        )
                    }
                }
            }
        )
    }

    // Create Folder Dialog
    showCreateFolderDialog?.let { targetPath ->
        CreateItemDialog(
            title = "New Folder",
            icon = Icons.Outlined.CreateNewFolder,
            placeholder = "Enter folder name",
            targetPath = targetPath,
            errorMessage = errorMessage,
            onDismiss = {
                showCreateFolderDialog = null
                errorMessage = null
            },
            onCreate = { folderName ->
                val validationError = validateFileName(folderName)
                if (validationError != null) {
                    errorMessage = validationError
                } else {
                    viewModel.createFolder(targetPath, folderName) { result ->
                        result.fold(
                            onSuccess = {
                                showCreateFolderDialog = null
                                errorMessage = null
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Failed to create folder"
                            }
                        )
                    }
                }
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteDialog?.let { (path, name) ->
        DeleteConfirmationDialog(
            itemName = name,
            isDirectory = java.io.File(path).isDirectory,
            errorMessage = errorMessage,
            onDismiss = {
                showDeleteDialog = null
                errorMessage = null
            },
            onConfirm = {
                viewModel.deleteItem(path) { result ->
                    result.fold(
                        onSuccess = {
                            showDeleteDialog = null
                            errorMessage = null
                        },
                        onFailure = { error ->
                            errorMessage = error.message ?: "Failed to delete"
                        }
                    )
                }
            }
        )
    }

    // Bulk Delete Confirmation Dialog
    showBulkDeleteDialog?.let { paths ->
        BulkDeleteConfirmationDialog(
            itemNames = viewModel.displayNamesFor(paths),
            errorMessage = errorMessage,
            onDismiss = {
                showBulkDeleteDialog = null
                errorMessage = null
            },
            onConfirm = {
                viewModel.deleteItems(paths) { result ->
                    result.fold(
                        onSuccess = {
                            showBulkDeleteDialog = null
                            errorMessage = null
                        },
                        onFailure = { error ->
                            errorMessage = error.message ?: "Failed to delete"
                        }
                    )
                }
            }
        )
    }

    // Rename Dialog
    showRenameDialog?.let { (path, currentName) ->
        RenameItemDialog(
            currentName = currentName,
            errorMessage = errorMessage,
            onDismiss = {
                showRenameDialog = null
                errorMessage = null
            },
            onRename = { newName ->
                val validationError = validateFileName(newName)
                if (validationError != null) {
                    errorMessage = validationError
                } else {
                    viewModel.renameItem(path, newName) { result ->
                        result.fold(
                            onSuccess = {
                                showRenameDialog = null
                                errorMessage = null
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Failed to rename"
                            }
                        )
                    }
                }
            }
        )
    }
    }
}

/**
 * Context menu for whitespace below the tree. Whitespace represents the
 * project root, which is not rendered as a normal tree row, so expose the
 * useful directory actions without destructive rename/delete operations.
 */
internal fun projectRootContextMenuItems(
    projectPath: String,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onRevealInFileManager: (String) -> Unit,
    onOpenInTerminal: (String) -> Unit
): List<ContextMenuItemData> = listOf(
    ContextMenuItemData(
        label = "New File",
        icon = Icons.AutoMirrored.Outlined.NoteAdd,
        onClick = { onCreateFile(projectPath) }
    ),
    ContextMenuItemData(
        label = "New Folder",
        icon = Icons.Outlined.CreateNewFolder,
        onClick = { onCreateFolder(projectPath) }
    ),
    ContextMenuItemData(
        label = "Copy Path",
        icon = Icons.Outlined.ContentCopy,
        onClick = { onCopyPath(projectPath) }
    ),
    ContextMenuItemData(
        label = getRevealInFileManagerLabel(),
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        onClick = { onRevealInFileManager(projectPath) }
    ),
    ContextMenuItemData(
        label = "Open in Terminal",
        icon = Icons.Outlined.Terminal,
        onClick = { onOpenInTerminal(projectPath) }
    )
)

/**
 * File tree item composable with IntelliJ-style compact paths.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileTreeItem(
    node: FileNode,
    level: Int,
    expandedPaths: Set<String>,
    selectedPaths: Set<String> = emptySet(),
    onToggleExpanded: (String) -> Unit,
    onSelectOnly: (String) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    onRangeSelect: (String) -> Unit = {},
    onFileDoubleClick: (FileNode) -> Unit,
    onCreateFile: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onDelete: (String, String) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onCopyPath: (String) -> Unit = {},
    onCopyRelativePath: (String) -> Unit = {},
    onRevealInFileManager: (String) -> Unit = {},
    onOpenInTerminal: (String) -> Unit = {},
    onOpenInEditor: (String) -> Unit = {},
    onOpenInBrowser: (String) -> Unit = {},
    onOpenWithDefaultApp: (String) -> Unit = {},
    onBulkCopyPaths: (List<String>) -> Unit = {},
    onBulkCopyRelativePaths: (List<String>) -> Unit = {},
    onBulkDelete: (List<String>) -> Unit = {},
    contextMenuProvider: ContextMenuProvider?
) {
    // IntelliJ's compact middle packages pattern
    val endNode = node.getCompactEndNode()
    val compactDisplayName = node.getCompactDisplayName()
    val isExpanded = expandedPaths.contains(node.path)
    val showExpandIndicator = endNode.shouldShowExpandIndicator()

    // Calculate target directory for create operations
    val targetDirectory = if (node.isDirectory) {
        endNode.path
    } else {
        PathUtils.parent(node.path)
    }

    // The actual path for this item (for operations like delete, rename, copy path)
    val itemPath = endNode.path
    val itemName = if (node.isDirectory) compactDisplayName else node.name

    // Rows are identified by the top of their compact chain — matches tree keys
    val isSelected = selectedPaths.contains(node.path)
    val isMultiSelection = isSelected && selectedPaths.size > 1

    // Build context menu items: bulk menu when right-clicking inside a
    // multi-selection, IntelliJ-style single-item menu otherwise
    val contextMenuItems = if (isMultiSelection) {
        val selection = selectedPaths.toList()
        val count = selection.size
        buildList {
            add(ContextMenuItemData(
                label = "Copy $count Paths",
                icon = Icons.Outlined.ContentCopy,
                onClick = { onBulkCopyPaths(selection) }
            ))
            add(ContextMenuItemData(
                label = "Copy $count Relative Paths",
                icon = Icons.Outlined.ContentCopy,
                onClick = { onBulkCopyRelativePaths(selection) }
            ))
            add(ContextMenuItemData(
                label = "Delete $count Items...",
                icon = Icons.Outlined.Delete,
                onClick = { onBulkDelete(selection) }
            ))
        }
    } else buildList {
        add(ContextMenuItemData(
            label = "New File",
            icon = Icons.AutoMirrored.Outlined.NoteAdd,
            onClick = { onCreateFile(targetDirectory) }
        ))
        add(ContextMenuItemData(
            label = "New Folder",
            icon = Icons.Outlined.CreateNewFolder,
            onClick = { onCreateFolder(targetDirectory) }
        ))
        add(ContextMenuItemData(
            label = "Copy Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = { onCopyPath(itemPath) }
        ))
        add(ContextMenuItemData(
            label = "Copy Relative Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = { onCopyRelativePath(itemPath) }
        ))
        add(ContextMenuItemData(
            label = getRevealInFileManagerLabel(),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = { onRevealInFileManager(itemPath) }
        ))
        add(ContextMenuItemData(
            label = "Open in Terminal",
            icon = Icons.Outlined.Terminal,
            onClick = { onOpenInTerminal(itemPath) }
        ))
        if (!node.isDirectory) {
            add(ContextMenuItemData(
                label = "Open With",
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                subMenu = listOf(
                    ContextMenuItemData(
                        label = "Editor",
                        icon = Icons.Outlined.Code,
                        onClick = { onOpenInEditor(itemPath) }
                    ),
                    ContextMenuItemData(
                        label = "Browser",
                        icon = Icons.Outlined.Language,
                        onClick = { onOpenInBrowser(itemPath) }
                    ),
                    ContextMenuItemData(
                        label = "Terminal",
                        icon = Icons.Outlined.Terminal,
                        onClick = { onOpenInTerminal(itemPath) }
                    ),
                    ContextMenuItemData(
                        label = "Default App",
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        onClick = { onOpenWithDefaultApp(itemPath) }
                    )
                )
            ))
        }
        add(ContextMenuItemData(
            label = "Rename...",
            icon = Icons.Outlined.DriveFileRenameOutline,
            // Rename targets the innermost dir of a compacted chain (itemPath is
            // endNode.path), so prefill with that dir's name, not the chain top's.
            onClick = { onRename(itemPath, endNode.name) }
        ))
        add(ContextMenuItemData(
            label = "Delete",
            icon = Icons.Outlined.Delete,
            // Delete the top of the compacted chain: the row displays the whole
            // chain ("a/b/c"), so deleting must remove all of it — deleting the
            // end node would only remove the innermost nested folder.
            onClick = { onDelete(node.path, itemName) }
        ))
    }

    // Read the live keyboard modifier state at click time to distinguish
    // plain click / Cmd(Ctrl)+click / Shift+click.
    val windowInfo = LocalWindowInfo.current

    val baseModifier = Modifier
        .fillMaxWidth()
        .height(TreeRowHeight)
        .background(if (isSelected) BossAccentBlue.copy(alpha = 0.25f) else Color.Transparent)
        // Right-clicking a row OUTSIDE the current selection collapses the
        // selection to that row (standard file-manager behavior), so the
        // highlight always matches what the context menu will act on.
        // Observed on the Initial pass without consuming, so the host's
        // context-menu gesture detection is unaffected.
        // TIMING ASSUMPTION: menu items are baked into the modifier at
        // composition; a row outside the selection already carries the
        // single-item menu, so even if the menu opens before recomposition
        // the shown items match the clicked row. Only the highlight depends
        // on the state update landing first.
        .pointerInput(node.path, isSelected) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press &&
                        event.buttons.isSecondaryPressed &&
                        !isSelected
                    ) {
                        onSelectOnly(node.path)
                    }
                }
            }
        }
        .combinedClickable(
            onClick = {
                val modifiers = windowInfo.keyboardModifiers
                when {
                    modifiers.isMetaPressed || modifiers.isCtrlPressed -> onToggleSelect(node.path)
                    modifiers.isShiftPressed -> onRangeSelect(node.path)
                    else -> {
                        onSelectOnly(node.path)
                        if (node.isDirectory && showExpandIndicator) {
                            onToggleExpanded(node.path)
                        }
                    }
                }
            },
            onDoubleClick = {
                if (!node.isDirectory) {
                    onFileDoubleClick(node)
                }
            }
        )
        .padding(start = (16 + level * 16).dp)

    val modifierWithContextMenu = if (contextMenuProvider != null) {
        contextMenuProvider.applyContextMenu(baseModifier, contextMenuItems)
    } else {
        baseModifier
    }

    // Children are NOT composed here: the tree is flattened into the
    // LazyColumn (VisibleRow), so this composable renders exactly one row.
    Row(
        modifier = modifierWithContextMenu,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand/collapse icon for directories
        when {
            node.isDirectory && showExpandIndicator -> {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = BossDarkTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            else -> {
                Spacer(modifier = Modifier.width(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // File/folder icon
        val iconInfo = if (node.isDirectory) {
            FileIcons.forFolder(isExpanded)
        } else {
            FileIcons.forFile(node.name)
        }

        Icon(
            imageVector = iconInfo.icon,
            contentDescription = if (node.isDirectory) "Folder" else "File",
            tint = iconInfo.color,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // File/folder name (compact display for directories); names that
        // don't fit end in an ellipsis instead of clipping at the edge
        Text(
            text = if (node.isDirectory) compactDisplayName else node.name,
            fontSize = 13.sp,
            color = BossTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Placeholder row under an expanded directory: loading spinner or "(empty)".
 * [level] is the parent directory's level, matching the pre-flattening indent.
 */
@Composable
private fun TreeStatusRow(level: Int, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TreeRowHeight)
            .padding(start = (32 + level * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.dp,
                color = BossDarkTextSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Loading...",
                fontSize = 12.sp,
                color = BossDarkTextSecondary
            )
        } else {
            Text(
                text = "(empty)",
                fontSize = 12.sp,
                color = BossDarkTextSecondary.copy(alpha = 0.6f),
                fontStyle = FontStyle.Italic
            )
        }
    }
}

/**
 * Get platform-appropriate label for revealing files in the system file manager.
 */
private fun getRevealInFileManagerLabel(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") -> "Reveal in Finder"
        osName.contains("windows") -> "Show in Explorer"
        else -> "Show in File Manager"
    }
}

/**
 * Validate a file or folder name.
 */
private fun validateFileName(name: String): String? {
    if (name.isBlank()) return "Name cannot be empty"
    if (name.length > 255) return "Name is too long (max 255 characters)"
    if (name.contains('/') || name.contains('\\')) return "Name cannot contain path separators"
    if (name == ".." || name == ".") return "Name cannot be '.' or '..'"
    if (name.contains("..")) return "Name cannot contain '..'"

    val invalidChars = listOf('<', '>', ':', '"', '|', '?', '*')
    for (char in invalidChars) {
        if (name.contains(char)) return "Name cannot contain '$char'"
    }

    for (char in name) {
        if (char.code < 32) return "Name cannot contain control characters"
    }

    if (name.endsWith('.') || name.endsWith(' ')) return "Name cannot end with a dot or space"

    val reservedNames = listOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
    val nameWithoutExtension = name.substringBefore('.').uppercase()
    if (nameWithoutExtension in reservedNames) return "Name '$nameWithoutExtension' is reserved"

    return null
}

/**
 * Dialog for creating a new file or folder.
 */
@Composable
private fun CreateItemDialog(
    title: String,
    icon: ImageVector,
    placeholder: String,
    targetPath: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var inputValue by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BossHeaderColor,
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = BossLinkBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary
                    )
                }

                Text(
                    text = "in: ${PathUtils.name(targetPath)}",
                    fontSize = 11.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        color = BossThemeColors.TextPrimary
                    ),
                    cursorBrush = SolidColor(BossLinkBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BossDarkBackground, RoundedCornerShape(4.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (errorMessage != null) BossErrorRed else BossDarkBorder,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputValue.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    fontSize = 13.sp,
                                    color = BossDarkTextSecondary
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = BossErrorRed,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onCreate(inputValue) },
                        enabled = inputValue.isNotBlank() && validateFileName(inputValue) == null,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossAccentBlue,
                            contentColor = BossThemeColors.TextPrimary,
                            disabledBackgroundColor = BossAccentBlue.copy(alpha = 0.5f),
                            disabledContentColor = BossThemeColors.TextPrimary.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Create", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Delete confirmation dialog.
 */
@Composable
private fun DeleteConfirmationDialog(
    itemName: String,
    isDirectory: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BossHeaderColor,
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = BossErrorRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete ${if (isDirectory) "Folder" else "File"}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary
                    )
                }

                Text(
                    text = "Are you sure you want to delete \"$itemName\"?",
                    fontSize = 13.sp,
                    color = BossTextColor,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (isDirectory) {
                    Text(
                        text = "This will delete the folder and all its contents.",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = BossErrorRed,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossErrorRed,
                            contentColor = BossThemeColors.TextPrimary
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Delete", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Bulk delete confirmation dialog.
 */
@Composable
private fun BulkDeleteConfirmationDialog(
    itemNames: List<String>,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BossHeaderColor,
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = BossErrorRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete ${itemNames.size} Items",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary
                    )
                }

                Text(
                    text = "Are you sure you want to delete these ${itemNames.size} items?",
                    fontSize = 13.sp,
                    color = BossTextColor,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val previewNames = itemNames.take(5)
                previewNames.forEach { name ->
                    Text(
                        text = "• $name",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (itemNames.size > previewNames.size) {
                    Text(
                        text = "…and ${itemNames.size - previewNames.size} more",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary,
                        fontStyle = FontStyle.Italic
                    )
                }

                Text(
                    text = "Folders will be deleted with all their contents.",
                    fontSize = 12.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = BossErrorRed,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossErrorRed,
                            contentColor = BossThemeColors.TextPrimary
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Delete", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Rename item dialog.
 */
@Composable
private fun RenameItemDialog(
    currentName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var inputValue by remember { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BossHeaderColor,
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = BossLinkBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Rename",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossThemeColors.TextPrimary
                    )
                }

                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        color = BossThemeColors.TextPrimary
                    ),
                    cursorBrush = SolidColor(BossLinkBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BossDarkBackground, RoundedCornerShape(4.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (errorMessage != null) BossErrorRed else BossDarkBorder,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputValue.isEmpty()) {
                                Text(
                                    text = "Enter new name",
                                    fontSize = 13.sp,
                                    color = BossDarkTextSecondary
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = BossErrorRed,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onRename(inputValue) },
                        enabled = inputValue.isNotBlank() &&
                                inputValue != currentName &&
                                validateFileName(inputValue) == null,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossAccentBlue,
                            contentColor = BossThemeColors.TextPrimary,
                            disabledBackgroundColor = BossAccentBlue.copy(alpha = 0.5f),
                            disabledContentColor = BossThemeColors.TextPrimary.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Rename", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
