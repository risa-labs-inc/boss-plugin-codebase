package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

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
    getProjectPath: () -> String?
) {
    val viewModel = remember(fileSystemDataProvider, scope) {
        CodebaseViewModel(fileSystemDataProvider, scope, getWindowId, getProjectPath)
    }

    BossTheme {
        if (!viewModel.isAvailable()) {
            NoProviderMessage()
        } else if (!viewModel.hasProject()) {
            NoProjectMessage()
        } else {
            FileTreeContent(viewModel)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
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
private fun FileTreeContent(viewModel: CodebaseViewModel) {
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
                                }
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

@Composable
private fun FileNodeItem(
    node: FileNodeData,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit
) {
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 300) {
                    clickCount++
                } else {
                    clickCount = 1
                }
                lastClickTime = currentTime

                if (clickCount >= 2) {
                    onDoubleClick()
                    clickCount = 0
                } else {
                    onSelect()
                }
            }
            .background(
                if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.1f)
                else MaterialTheme.colors.background
            )
            .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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

        // File/folder icon
        Icon(
            imageVector = getFileIcon(node),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = getFileIconColor(node)
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
 * Get the appropriate icon for a file node.
 */
private fun getFileIcon(node: FileNodeData): ImageVector {
    if (node.isDirectory) {
        return Icons.Outlined.Folder
    }

    val extension = node.name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "kt", "kts" -> Icons.Outlined.Code
        "java" -> Icons.Outlined.Code
        "py" -> Icons.Outlined.Code
        "js", "jsx", "ts", "tsx" -> Icons.Outlined.Javascript
        "json" -> Icons.Outlined.DataObject
        "xml", "html", "htm" -> Icons.Outlined.Code
        "md", "txt", "readme" -> Icons.Outlined.Description
        "yaml", "yml" -> Icons.Outlined.Settings
        "gradle" -> Icons.Outlined.Build
        "properties" -> Icons.Outlined.Settings
        "png", "jpg", "jpeg", "gif", "svg", "ico" -> Icons.Outlined.Image
        "pdf" -> Icons.Outlined.PictureAsPdf
        "zip", "tar", "gz", "rar" -> Icons.Outlined.Archive
        "sh", "bash", "zsh" -> Icons.Outlined.Terminal
        "css", "scss", "sass" -> Icons.Outlined.Palette
        "sql" -> Icons.Outlined.Storage
        "gitignore", "gitattributes" -> Icons.Outlined.Source
        else -> Icons.Outlined.InsertDriveFile
    }
}

/**
 * Get the icon color for a file node.
 */
@Composable
private fun getFileIconColor(node: FileNodeData): Color {
    if (node.isDirectory) {
        return Color(0xFFFFB74D) // Orange for folders
    }

    val extension = node.name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "kt", "kts" -> Color(0xFF7F52FF) // Kotlin purple
        "java" -> Color(0xFFE76F00) // Java orange
        "py" -> Color(0xFF3776AB) // Python blue
        "js", "jsx" -> Color(0xFFF7DF1E) // JavaScript yellow
        "ts", "tsx" -> Color(0xFF3178C6) // TypeScript blue
        "json" -> Color(0xFF5C5C5C) // Gray
        "xml", "html", "htm" -> Color(0xFFE44D26) // HTML orange
        "md", "txt" -> Color(0xFF757575) // Gray
        "yaml", "yml" -> Color(0xFFCC1018) // Red
        "gradle" -> Color(0xFF02303A) // Gradle dark
        "css", "scss", "sass" -> Color(0xFF264DE4) // CSS blue
        "sql" -> Color(0xFFCC7832) // SQL orange
        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
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
