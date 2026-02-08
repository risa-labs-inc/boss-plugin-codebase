package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope

// UI colors matching bundled plugin (IntelliJ dark theme)
private val BossDarkBackground = Color(0xFF1E1F22)
private val BossDarkBorder = Color(0xFF3C3F41)
private val BossDarkTextSecondary = Color(0xFF8C8C8C)
private val BossHeaderColor = Color(0xFF2B2D30)
private val BossAccentBlue = Color(0xFF365880)
private val BossLinkBlue = Color(0xFF6B9EFF)
private val BossErrorRed = Color(0xFFE74856)
private val BossTextColor = Color(0xFFCCCCCC)

/**
 * Main content composable for the Codebase panel.
 * Ported from bundled plugin v8.16.22 with exact UI parity.
 */
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

    val projectPath = getProjectPath()
    val projectName = projectPath?.substringAfterLast('/')?.ifEmpty { "Project" } ?: ""
    val hasProject = !projectPath.isNullOrEmpty()

    val tree by viewModel.fileTree.collectAsState()
    val expandedPaths by viewModel.expandedPaths.collectAsState()
    val listState = rememberLazyListState()

    // Dialog state for creating files/folders
    var showCreateFileDialog by remember { mutableStateOf<String?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, name)
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
                        color = Color.White
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
                            contentColor = Color.White
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
                        color = Color.White
                    )
                }
            }

            Divider(color = BossDarkBorder)

            // File tree
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp)
            ) {
                tree?.let { rootNode ->
                    items(rootNode.children, key = { it.path }) { node ->
                        FileTreeItem(
                            node = node,
                            level = 0,
                            expandedPaths = expandedPaths,
                            onToggleExpanded = viewModel::toggleExpanded,
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
                            contextMenuProvider = contextMenuProvider
                        )
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
                            onSuccess = {
                                showCreateFileDialog = null
                                errorMessage = null
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

/**
 * File tree item composable with IntelliJ-style compact paths.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTreeItem(
    node: FileNode,
    level: Int,
    expandedPaths: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onFileDoubleClick: (FileNode) -> Unit,
    onCreateFile: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onDelete: (String, String) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onCopyPath: (String) -> Unit = {},
    onCopyRelativePath: (String) -> Unit = {},
    onRevealInFileManager: (String) -> Unit = {},
    onOpenInTerminal: (String) -> Unit = {},
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
        node.path.substringBeforeLast('/')
    }

    // The actual path for this item (for operations like delete, rename, copy path)
    val itemPath = endNode.path
    val itemName = if (node.isDirectory) compactDisplayName else node.name

    // Build context menu items (IntelliJ-style order) - 8 items total
    val contextMenuItems = listOf(
        ContextMenuItemData(
            label = "New File",
            icon = Icons.AutoMirrored.Outlined.NoteAdd,
            onClick = { onCreateFile(targetDirectory) }
        ),
        ContextMenuItemData(
            label = "New Folder",
            icon = Icons.Outlined.CreateNewFolder,
            onClick = { onCreateFolder(targetDirectory) }
        ),
        ContextMenuItemData(
            label = "Copy Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = { onCopyPath(itemPath) }
        ),
        ContextMenuItemData(
            label = "Copy Relative Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = { onCopyRelativePath(itemPath) }
        ),
        ContextMenuItemData(
            label = getRevealInFileManagerLabel(),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = { onRevealInFileManager(itemPath) }
        ),
        ContextMenuItemData(
            label = "Open in Terminal",
            icon = Icons.Outlined.Terminal,
            onClick = { onOpenInTerminal(itemPath) }
        ),
        ContextMenuItemData(
            label = "Rename...",
            icon = Icons.Outlined.DriveFileRenameOutline,
            onClick = { onRename(itemPath, node.name) }
        ),
        ContextMenuItemData(
            label = "Delete",
            icon = Icons.Outlined.Delete,
            onClick = { onDelete(itemPath, itemName) }
        )
    )

    val baseModifier = Modifier
        .fillMaxWidth()
        .height(26.dp)
        .combinedClickable(
            onClick = {
                if (node.isDirectory && showExpandIndicator) {
                    onToggleExpanded(node.path)
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

    Column {
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

            // File/folder name (compact display for directories)
            Text(
                text = if (node.isDirectory) compactDisplayName else node.name,
                fontSize = 13.sp,
                color = BossTextColor
            )
        }

        // Show children if expanded
        if (node.isDirectory && isExpanded) {
            val childrenToShow = endNode.children
            val isLoading = endNode.loadingState == NodeLoadingState.CHECKING

            when {
                isLoading || (childrenToShow.isEmpty() && !endNode.isLoaded) -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(start = (32 + level * 16).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                    }
                }
                endNode.isLoaded && childrenToShow.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(start = (32 + level * 16).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "(empty)",
                            fontSize = 12.sp,
                            color = BossDarkTextSecondary.copy(alpha = 0.6f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
                else -> {
                    childrenToShow.forEach { child ->
                        FileTreeItem(
                            node = child,
                            level = level + 1,
                            expandedPaths = expandedPaths,
                            onToggleExpanded = onToggleExpanded,
                            onFileDoubleClick = onFileDoubleClick,
                            onCreateFile = onCreateFile,
                            onCreateFolder = onCreateFolder,
                            onDelete = onDelete,
                            onRename = onRename,
                            onCopyPath = onCopyPath,
                            onCopyRelativePath = onCopyRelativePath,
                            onRevealInFileManager = onRevealInFileManager,
                            onOpenInTerminal = onOpenInTerminal,
                            contextMenuProvider = contextMenuProvider
                        )
                    }
                }
            }
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
                        color = Color.White
                    )
                }

                Text(
                    text = "in: ${targetPath.substringAfterLast('/')}",
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
                        color = Color.White
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
                            contentColor = Color.White,
                            disabledBackgroundColor = BossAccentBlue.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
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
                        color = Color.White
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
                            contentColor = Color.White
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
                        color = Color.White
                    )
                }

                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        color = Color.White
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
                            contentColor = Color.White,
                            disabledBackgroundColor = BossAccentBlue.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
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
