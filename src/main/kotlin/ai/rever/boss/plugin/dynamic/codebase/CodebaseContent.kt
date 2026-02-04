package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope

// IntelliJ-like colors
private val BossDarkBackground = Color(0xFF1E1F22)
private val BossDarkBorder = Color(0xFF393B40)
private val BossDarkTextSecondary = Color(0xFF8C8C8C)

/**
 * Codebase panel content (Dynamic Plugin).
 *
 * File browser with tree view for project exploration.
 */
@Composable
fun CodebaseContent(
    fileSystemDataProvider: FileSystemDataProvider?,
    scope: CoroutineScope,
    getWindowId: () -> String?,
    getProjectPath: () -> String?,
    contextMenuProvider: ContextMenuProvider? = null
) {
    val viewModel = remember(fileSystemDataProvider, scope) {
        CodebaseViewModel(fileSystemDataProvider, scope, getWindowId, getProjectPath)
    }

    // Dialog state
    var showCreateFileDialog by remember { mutableStateOf<String?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, name)
    var showRenameDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, currentName)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BossTheme {
        if (!viewModel.isAvailable()) {
            NoProviderMessage()
        } else if (!viewModel.hasProject()) {
            NoProjectMessage()
        } else {
            FileTreeContent(
                viewModel = viewModel,
                contextMenuProvider = contextMenuProvider,
                onCreateFile = { targetPath -> showCreateFileDialog = targetPath },
                onCreateFolder = { targetPath -> showCreateFolderDialog = targetPath },
                onDelete = { path, name -> showDeleteDialog = Pair(path, name) },
                onRename = { path, name -> showRenameDialog = Pair(path, name) }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
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
        val isDirectory = java.io.File(path).isDirectory
        DeleteConfirmationDialog(
            itemName = name,
            isDirectory = isDirectory,
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

@Composable
private fun NoProviderMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colors.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Codebase",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "File system provider not available",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Please ensure the host provides file access",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun NoProjectMessage() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colors.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Project Open",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Open a project to browse files",
                fontSize = 13.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun FileTreeContent(
    viewModel: CodebaseViewModel,
    contextMenuProvider: ContextMenuProvider?,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDelete: (String, String) -> Unit,
    onRename: (String, String) -> Unit
) {
    val rootNode by viewModel.rootNode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedPaths by viewModel.expandedPaths.collectAsState()
    val selectedPath by viewModel.selectedPath.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val listState = rememberLazyListState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar
            FileTreeToolbar(
                searchQuery = searchQuery,
                onSearchChange = viewModel::updateSearchQuery,
                onRefresh = viewModel::refresh
            )

            Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

            // Status message
            if (statusMessage != null) {
                StatusMessage(
                    message = statusMessage!!,
                    onDismiss = viewModel::clearStatusMessage
                )
            }

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colors.primary
                )
            }

            // File tree
            Box(modifier = Modifier.fillMaxSize()) {
                if (rootNode == null) {
                    EmptyMessage()
                } else {
                    val flattenedNodes = remember(rootNode, expandedPaths, searchQuery) {
                        flattenTree(rootNode!!, expandedPaths, searchQuery, viewModel)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .lazyListScrollbar(listState, Orientation.Vertical, getPanelScrollbarConfig())
                    ) {
                        items(
                            items = flattenedNodes,
                            key = { it.node.path }
                        ) { item ->
                            FileNodeItem(
                                node = item.node,
                                depth = item.depth,
                                isExpanded = item.node.path in expandedPaths,
                                isSelected = item.node.path == selectedPath,
                                hasChildren = item.node.isDirectory && viewModel.hasChildren(item.node.path),
                                onToggleExpand = { viewModel.toggleExpand(item.node.path) },
                                onSelect = { viewModel.select(item.node.path) },
                                onDoubleClick = {
                                    if (item.node.isDirectory) {
                                        viewModel.toggleExpand(item.node.path)
                                    } else {
                                        viewModel.openFile(item.node.path)
                                    }
                                },
                                contextMenuProvider = contextMenuProvider,
                                onCreateFile = onCreateFile,
                                onCreateFolder = onCreateFolder,
                                onDelete = onDelete,
                                onRename = onRename,
                                onCopyPath = { viewModel.copyPathToClipboard(item.node.path) },
                                onCopyRelativePath = { viewModel.copyRelativePathToClipboard(item.node.path) },
                                onRevealInFileManager = { viewModel.revealInFileManager(item.node.path) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTreeToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search field
        Row(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.background.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search files...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear",
                    modifier = Modifier
                        .size(12.dp)
                        .clickable { onSearchChange("") },
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Refresh button
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StatusMessage(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.primary.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.primary
            )
        }
    }
}

@Composable
private fun EmptyMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Loading files...",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileNodeItem(
    node: FileNodeData,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
    contextMenuProvider: ContextMenuProvider?,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDelete: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onCopyPath: () -> Unit,
    onCopyRelativePath: () -> Unit,
    onRevealInFileManager: () -> Unit
) {
    // Calculate target directory for create operations
    val targetDirectory = if (node.isDirectory) {
        node.path
    } else {
        node.path.substringBeforeLast('/')
    }

    // Build context menu items (IntelliJ-style order)
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
        ContextMenuItemData(label = "", isDivider = true),
        ContextMenuItemData(
            label = "Copy Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = onCopyPath
        ),
        ContextMenuItemData(
            label = "Copy Relative Path",
            icon = Icons.Outlined.ContentCopy,
            onClick = onCopyRelativePath
        ),
        ContextMenuItemData(
            label = getRevealInFileManagerLabel(),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = onRevealInFileManager
        ),
        ContextMenuItemData(label = "", isDivider = true),
        ContextMenuItemData(
            label = "Rename...",
            icon = Icons.Outlined.DriveFileRenameOutline,
            onClick = { onRename(node.path, node.name) }
        ),
        ContextMenuItemData(
            label = "Delete",
            icon = Icons.Outlined.Delete,
            onClick = { onDelete(node.path, node.name) }
        )
    )

    // Base modifier with click handling
    val baseModifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = {
                onSelect()
                if (node.isDirectory && hasChildren) {
                    // Single click on directory toggles expand
                }
            },
            onDoubleClick = onDoubleClick
        )
        .background(
            if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.1f)
            else MaterialTheme.colors.background
        )
        .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp)

    // Apply context menu if provider is available
    val modifier = if (contextMenuProvider != null) {
        contextMenuProvider.applyContextMenu(baseModifier, contextMenuItems)
    } else {
        baseModifier
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand/collapse icon for directories
        if (node.isDirectory) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onToggleExpand),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // File/folder icon - use FileIcons for official brand icons
        val iconInfo = if (node.isDirectory) {
            FileIcons.forFolder(isExpanded)
        } else {
            FileIcons.forFile(node.name)
        }
        Icon(
            imageVector = iconInfo.icon,
            contentDescription = if (node.isDirectory) "Folder" else "File",
            modifier = Modifier.size(16.dp),
            tint = iconInfo.color
        )

        Spacer(modifier = Modifier.width(6.dp))

        // File name
        Text(
            text = node.name,
            fontSize = 12.sp,
            color = if (isSelected) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
 *
 * @param name The name to validate
 * @return Error message if invalid, null if valid
 */
private fun validateFileName(name: String): String? {
    // Check for empty/blank
    if (name.isBlank()) {
        return "Name cannot be empty"
    }

    // Check max length
    if (name.length > 255) {
        return "Name is too long (max 255 characters)"
    }

    // Check for path separators
    if (name.contains('/') || name.contains('\\')) {
        return "Name cannot contain path separators"
    }

    // Check for path traversal (including embedded '..')
    if (name == ".." || name == ".") {
        return "Name cannot be '.' or '..'"
    }
    if (name.contains("..")) {
        return "Name cannot contain '..'"
    }

    // Check for invalid characters (Windows-style restrictions apply universally for portability)
    val invalidChars = listOf('<', '>', ':', '"', '|', '?', '*')
    for (char in invalidChars) {
        if (name.contains(char)) {
            return "Name cannot contain '$char'"
        }
    }

    // Check for control characters
    for (char in name) {
        if (char.code < 32) {
            return "Name cannot contain control characters"
        }
    }

    // Check for trailing dots or spaces
    if (name.endsWith('.') || name.endsWith(' ')) {
        return "Name cannot end with a dot or space"
    }

    // Check for Windows reserved names
    val reservedNames = listOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
    val nameWithoutExtension = name.substringBefore('.').uppercase()
    if (nameWithoutExtension in reservedNames) {
        return "Name '$nameWithoutExtension' is reserved"
    }

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

    // Request focus when dialog opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2B2D30),
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF6B9EFF),
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

                // Target path display
                Text(
                    text = "in: ${targetPath.substringAfterLast('/')}",
                    fontSize = 11.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Input field
                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        color = Color.White
                    ),
                    cursorBrush = SolidColor(Color(0xFF6B9EFF)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (errorMessage != null) Color(0xFFE74856) else BossDarkBorder,
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

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = Color(0xFFE74856),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
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
                            backgroundColor = Color(0xFF365880),
                            contentColor = Color.White,
                            disabledBackgroundColor = Color(0xFF365880).copy(alpha = 0.5f),
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
            color = Color(0xFF2B2D30),
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = Color(0xFFE74856),
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

                // Confirmation message
                Text(
                    text = "Are you sure you want to delete \"$itemName\"?",
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
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

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = Color(0xFFE74856),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons
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
                            backgroundColor = Color(0xFFE74856),
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

    // Request focus when dialog opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2B2D30),
            elevation = 8.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = Color(0xFF6B9EFF),
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

                // Input field
                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        color = Color.White
                    ),
                    cursorBrush = SolidColor(Color(0xFF6B9EFF)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (errorMessage != null) Color(0xFFE74856) else BossDarkBorder,
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

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = Color(0xFFE74856),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
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
                            backgroundColor = Color(0xFF365880),
                            contentColor = Color.White,
                            disabledBackgroundColor = Color(0xFF365880).copy(alpha = 0.5f),
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



/**
 * Data class for flattened tree items.
 */
private data class FlattenedNode(
    val node: FileNodeData,
    val depth: Int
)

/**
 * Flatten the tree for display in LazyColumn.
 */
private fun flattenTree(
    root: FileNodeData,
    expandedPaths: Set<String>,
    searchQuery: String,
    viewModel: CodebaseViewModel
): List<FlattenedNode> {
    val result = mutableListOf<FlattenedNode>()

    fun traverse(node: FileNodeData, depth: Int) {
        // Filter by search query
        if (searchQuery.isNotEmpty()) {
            if (!node.name.contains(searchQuery, ignoreCase = true)) {
                // If directory, still check children
                if (node.isDirectory) {
                    val children = if (node.path in expandedPaths) {
                        viewModel.getChildren(node.path).ifEmpty { node.children }
                    } else {
                        node.children
                    }
                    children.forEach { child -> traverse(child, depth) }
                }
                return
            }
        }

        result.add(FlattenedNode(node, depth))

        if (node.isDirectory && node.path in expandedPaths) {
            val children = viewModel.getChildren(node.path).ifEmpty { node.children }
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { child -> traverse(child, depth + 1) }
        }
    }

    // Start with root's children (don't show root itself)
    val children = viewModel.getChildren(root.path).ifEmpty { root.children }
    children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .forEach { child -> traverse(child, 0) }

    return result
}
