package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.NodeLoadingStateData
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.SplitViewOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for the Codebase panel.
 *
 * This component provides file tree browsing with:
 * - IntelliJ-style lazy loading
 * - Compact middle packages display
 * - LRU caching for file system nodes
 */
class CodebaseViewModel(
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val directoryPickerProvider: DirectoryPickerProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?,
    private val onSelectProject: ((String, String) -> Unit)?
) {
    private val _fileTree = MutableStateFlow<FileNode?>(null)
    val fileTree: StateFlow<FileNode?> = _fileTree.asStateFlow()

    private val _expandedPaths = MutableStateFlow(setOf<String>())
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    // Anchor row for shift-click range selection (the last plainly/cmd-selected row).
    private var selectionAnchor: String? = null

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    private val fileCache = FileIndexCache(
        maxSize = 1000,
        maxDepthInitial = 2
    )

    // Mutex to prevent race conditions during tree updates
    private val treeUpdateMutex = Mutex()

    /**
     * Directories that should not be compactly loaded due to deep hierarchies.
     */
    private val excludedDirectories = setOf(
        "node_modules",
        ".git",
        ".gradle",
        ".idea",
        "__pycache__",
        "target",
        "build",
        ".next",
        "dist",
        "vendor"
    )

    /**
     * Load file tree for the given root path.
     */
    suspend fun loadFileTree(rootPath: String) {
        if (rootPath.isEmpty()) {
            _fileTree.value = null
            return
        }

        // Callers may be on the Main dispatcher (e.g. LaunchedEffect) — keep the scan off it.
        _fileTree.value = withContext(Dispatchers.IO) { fileCache.getNode(rootPath, _showHidden.value) }
    }

    /**
     * Toggle visibility of hidden (dot) files and folders, then rebuild the
     * tree. Previously expanded directories are re-loaded so they don't sit
     * on a loading spinner after the rebuild.
     */
    fun setShowHidden(show: Boolean) {
        if (_showHidden.value == show) return
        _showHidden.value = show

        scope.launch {
            val rootPath = getProjectPath()
            if (rootPath.isNullOrEmpty()) return@launch
            fileCache.clearCache()
            loadFileTree(rootPath)
            // Ancestors first (shorter paths) so children exist in the tree
            // by the time their own reload runs.
            _expandedPaths.value.sortedBy { it.length }.forEach { path ->
                loadNodeChildren(path)
            }
            // Drop selected paths that are no longer in the rebuilt tree
            // (e.g. a selected dot-file after hiding hidden files) so bulk
            // operations can't act on invisible items.
            pruneSelectionToTree()
        }
    }

    /**
     * Keep only selected paths that still resolve to a node in the tree.
     */
    private fun pruneSelectionToTree() {
        val tree = _fileTree.value
        if (tree == null) {
            clearSelection()
            return
        }
        val remaining = _selectedPaths.value
            .filter { FileTreeUtils.findNodeByPath(tree, it) != null }
            .toSet()
        if (remaining.size != _selectedPaths.value.size) {
            _selectedPaths.value = remaining
            if (selectionAnchor !in remaining) selectionAnchor = remaining.firstOrNull()
        }
    }

    /**
     * Toggle expansion state for a directory path.
     */
    fun toggleExpanded(path: String) {
        val expanded = _expandedPaths.value.toMutableSet()

        if (expanded.contains(path)) {
            expanded.remove(path)
            _expandedPaths.value = expanded
        } else {
            expanded.add(path)
            _expandedPaths.value = expanded

            scope.launch {
                loadNodeChildren(path)
            }
        }
    }

    /**
     * Load children for a node asynchronously.
     */
    private suspend fun loadNodeChildren(path: String) {
        val currentTree = _fileTree.value ?: return
        val node = FileTreeUtils.findNodeByPath(currentTree, path)
        if (node?.isDirectory != true) return

        val endNode = node.getCompactEndNode()
        var targetPath = endNode.path
        if (endNode.isLoaded && endNode.children.isNotEmpty()) return

        // Mark as CHECKING state
        treeUpdateMutex.lock()
        try {
            val treeForUpdate = _fileTree.value ?: return
            val nodeAfterLock = FileTreeUtils.findNodeByPath(treeForUpdate, path)
            if (nodeAfterLock?.isDirectory != true) return

            val endNodeAfterLock = nodeAfterLock.getCompactEndNode()
            if (endNodeAfterLock.isLoaded && endNodeAfterLock.children.isNotEmpty()) return

            targetPath = endNodeAfterLock.path

            _fileTree.value = FileTreeUtils.updateNodeAtPath(treeForUpdate, targetPath) { existingNode ->
                existingNode.copy(loadingState = NodeLoadingState.CHECKING)
            }
        } finally {
            treeUpdateMutex.unlock()
        }

        // Load children. The scan already fills hasChildren for edge directories,
        // so no per-child directoryHasChildren round-trips are needed here.
        val scannedNode = try {
            withContext(Dispatchers.IO) {
                LocalFileScanner.scanDirectoryWithDepth(targetPath, maxDepth = 1, startDepth = 0, showHidden = _showHidden.value)
            }
        } catch (e: Exception) {
            null
        }

        val loadedChildren = scannedNode?.children?.map { convertToFileNode(it) }

        // Update tree with loaded children
        treeUpdateMutex.lock()
        try {
            val latestTree = _fileTree.value ?: return

            if (loadedChildren != null) {
                _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, targetPath) { existingNode ->
                    existingNode.copy(
                        children = loadedChildren,
                        hasChildren = loadedChildren.isNotEmpty(),
                        loadingState = NodeLoadingState.LOADED,
                        loadDepth = 1
                    )
                }
            } else {
                _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, targetPath) { existingNode ->
                    existingNode.copy(
                        children = emptyList(),
                        hasChildren = false,
                        loadingState = NodeLoadingState.LOADED
                    )
                }
            }
        } finally {
            treeUpdateMutex.unlock()
        }

        // Compact loading for single-child directories
        if (loadedChildren != null) {
            compactLoadIfNeeded(loadedChildren, currentDepth = 0)
        }
    }

    private suspend fun compactLoadIfNeeded(
        children: List<FileNode>,
        currentDepth: Int,
        maxDepth: Int = 10
    ) {
        if (currentDepth >= maxDepth) return

        if (children.size == 1 && children[0].isDirectory) {
            val singleChild = children[0]

            if (excludedDirectories.contains(singleChild.name)) {
                return
            }

            loadNodeChildrenForCompact(singleChild.path, currentDepth + 1, maxDepth)
        }
    }

    private suspend fun loadNodeChildrenForCompact(
        path: String,
        currentDepth: Int = 0,
        maxDepth: Int = 10
    ) {
        val currentTree = _fileTree.value ?: return
        val node = FileTreeUtils.findNodeByPath(currentTree, path)
        if (node?.isDirectory != true) return
        if (node.isLoaded) return

        val scannedNode = try {
            withContext(Dispatchers.IO) {
                LocalFileScanner.scanDirectoryWithDepth(path, maxDepth = 1, startDepth = 0, showHidden = _showHidden.value)
            }
        } catch (e: Exception) {
            null
        }

        val loadedChildren = scannedNode?.children?.map { convertToFileNode(it) }

        treeUpdateMutex.lock()
        try {
            val latestTree = _fileTree.value ?: return
            val nodeAfterLock = FileTreeUtils.findNodeByPath(latestTree, path)
            if (nodeAfterLock?.isDirectory != true) return
            if (nodeAfterLock.isLoaded) return

            if (loadedChildren != null) {
                _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, path) { existingNode ->
                    existingNode.copy(
                        children = loadedChildren,
                        hasChildren = loadedChildren.isNotEmpty(),
                        loadingState = NodeLoadingState.LOADED,
                        loadDepth = 1
                    )
                }
            } else {
                _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, path) { existingNode ->
                    existingNode.copy(
                        children = emptyList(),
                        hasChildren = false,
                        loadingState = NodeLoadingState.LOADED
                    )
                }
            }
        } finally {
            treeUpdateMutex.unlock()
        }

        if (loadedChildren != null) {
            compactLoadIfNeeded(loadedChildren, currentDepth, maxDepth)
        }
    }

    /**
     * Clear the file cache.
     */
    suspend fun clearCache() {
        fileCache.clearCache()
    }

    /**
     * Clear the tree state.
     */
    fun clearTree() {
        _fileTree.value = null
        _expandedPaths.value = emptySet()
        clearSelection()
    }

    /**
     * Open a file in the editor.
     */
    fun openFile(path: String) {
        val windowId = getWindowId()
        if (windowId != null) {
            fileSystemDataProvider?.openFile(path, windowId)
        }
    }

    /**
     * Force-open a file in the browser tab (for images, PDFs, etc.).
     */
    fun openFileInBrowser(path: String) {
        val fileName = path.substringAfterLast('/')
        splitViewOperations?.openFileInBrowser(path, fileName)
    }

    /**
     * Force-open a file in the code editor (overriding smart routing).
     */
    fun openFileInEditor(path: String) {
        val fileName = path.substringAfterLast('/')
        splitViewOperations?.openFileInEditor(path, fileName)
    }

    /**
     * Open a file with the system default application.
     */
    fun openWithDefaultApp(path: String) {
        try {
            val file = java.io.File(path)
            if (file.exists()) {
                java.awt.Desktop.getDesktop().open(file)
            }
        } catch (_: Exception) {
            // Silently fail if no default app is configured
        }
    }

    /**
     * Replace the selection with a single file or directory.
     */
    fun selectOnly(path: String) {
        _selectedPaths.value = setOf(path)
        selectionAnchor = path
    }

    /**
     * Toggle one item in the selection (Cmd/Ctrl+click).
     */
    fun toggleSelection(path: String) {
        val current = _selectedPaths.value.toMutableSet()
        if (path in current) {
            current.remove(path)
            if (selectionAnchor == path) selectionAnchor = current.firstOrNull()
        } else {
            current.add(path)
            selectionAnchor = path
        }
        _selectedPaths.value = current
    }

    /**
     * Select the contiguous range of visible rows between the anchor and
     * [path] (Shift+click). Falls back to single selection without an anchor.
     */
    fun selectRangeTo(path: String) {
        val anchor = selectionAnchor ?: run {
            selectOnly(path)
            return
        }
        val rows = visibleRowPaths()
        val anchorIndex = rows.indexOf(anchor)
        val targetIndex = rows.indexOf(path)
        if (anchorIndex == -1 || targetIndex == -1) {
            selectOnly(path)
            return
        }
        val range = rows.subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1)
        _selectedPaths.value = range.toSet()
        // The anchor stays put so further shift-clicks re-pivot around it.
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
        selectionAnchor = null
    }

    /**
     * Flatten the tree into the row order the UI renders: children of a
     * directory are visible when its row path is expanded, and compacted
     * chains ("a/b/c") contribute the end node's children under the chain
     * top's path — mirroring FileTreeItem.
     */
    private fun visibleRowPaths(): List<String> {
        val root = _fileTree.value ?: return emptyList()
        val expanded = _expandedPaths.value
        val result = mutableListOf<String>()
        fun walk(node: FileNode) {
            result.add(node.path)
            if (node.isDirectory && expanded.contains(node.path)) {
                node.getCompactEndNode().children.forEach { walk(it) }
            }
        }
        root.children.forEach { walk(it) }
        return result
    }

    /**
     * Order a set of selected paths in visible-row order; paths no longer
     * visible (collapsed ancestors) are appended at the end.
     */
    private fun orderSelected(paths: List<String>): List<String> {
        val set = paths.toSet()
        val visible = visibleRowPaths().filter { it in set }
        val visibleSet = visible.toSet()
        return visible + paths.filterNot { it in visibleSet }
    }

    /**
     * Pick a directory and select it as the project.
     */
    fun pickDirectory() {
        directoryPickerProvider?.pickDirectory { path ->
            path?.let {
                val projectName = it.substringAfterLast('/').ifEmpty { "Unknown" }
                onSelectProject?.invoke(projectName, it)
            }
        }
    }

    /**
     * Check if the provider is available.
     */
    fun isAvailable(): Boolean {
        return fileSystemDataProvider != null
    }

    /**
     * Check if there's a project loaded.
     */
    fun hasProject(): Boolean {
        val projectPath = getProjectPath()
        return projectPath != null && projectPath.isNotEmpty()
    }

    /**
     * Get the current project name.
     */
    fun getProjectName(): String {
        val projectPath = getProjectPath() ?: return ""
        return projectPath.substringAfterLast('/').ifEmpty { "Project" }
    }

    /**
     * Create a new file in the specified directory.
     *
     * @param parentPath The parent directory path
     * @param fileName The name of the file to create
     * @param onResult Callback with the result (success path or error message)
     */
    fun createFile(parentPath: String, fileName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            val result = fileSystemDataProvider?.createFile(parentPath, fileName)
                ?: Result.failure(IllegalStateException("File system provider not available"))
            onResult(result)
            if (result.isSuccess) {
                refreshNode(parentPath)
            }
        }
    }

    /**
     * Create a new folder in the specified directory.
     *
     * @param parentPath The parent directory path
     * @param folderName The name of the folder to create
     * @param onResult Callback with the result (success path or error message)
     */
    fun createFolder(parentPath: String, folderName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            val result = fileSystemDataProvider?.createFolder(parentPath, folderName)
                ?: Result.failure(IllegalStateException("File system provider not available"))
            onResult(result)
            if (result.isSuccess) {
                refreshNode(parentPath)
            }
        }
    }

    /**
     * Refresh a specific node in the tree after creation/deletion.
     */
    fun refreshNode(path: String) {
        scope.launch {
            // Mark as CHECKING state - check node validity inside the lock to prevent race conditions
            treeUpdateMutex.lock()
            try {
                val treeForUpdate = _fileTree.value ?: return@launch
                val node = FileTreeUtils.findNodeByPath(treeForUpdate, path)
                if (node?.isDirectory != true) return@launch

                _fileTree.value = FileTreeUtils.updateNodeAtPath(treeForUpdate, path) { existingNode ->
                    existingNode.copy(loadingState = NodeLoadingState.CHECKING)
                }
            } finally {
                treeUpdateMutex.unlock()
            }

            // Reload children on IO dispatcher
            val loadedChildren = try {
                withContext(Dispatchers.IO) {
                    val scannedNode = LocalFileScanner.scanDirectoryWithDepth(path, maxDepth = 1, startDepth = 0, showHidden = _showHidden.value)
                    scannedNode?.children?.map { convertToFileNode(it) }
                }
            } catch (e: Exception) {
                null
            }

            // Update tree with refreshed children
            treeUpdateMutex.lock()
            try {
                val latestTree = _fileTree.value ?: return@launch

                if (loadedChildren != null) {
                    _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, path) { existingNode ->
                        existingNode.copy(
                            children = loadedChildren,
                            hasChildren = loadedChildren.isNotEmpty(),
                            loadingState = NodeLoadingState.LOADED,
                            loadDepth = 1
                        )
                    }
                } else {
                    _fileTree.value = FileTreeUtils.updateNodeAtPath(latestTree, path) { existingNode ->
                        existingNode.copy(
                            children = emptyList(),
                            hasChildren = false,
                            loadingState = NodeLoadingState.LOADED
                        )
                    }
                }
            } finally {
                treeUpdateMutex.unlock()
            }

            // Make sure the node is expanded
            val expanded = _expandedPaths.value.toMutableSet()
            if (!expanded.contains(path)) {
                expanded.add(path)
                _expandedPaths.value = expanded
            }
        }
    }

    /**
     * Delete a file or folder.
     *
     * @param path The path to delete
     * @param onResult Callback with the result
     */
    fun deleteItem(path: String, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = fileSystemDataProvider?.delete(path)
                ?: Result.failure(IllegalStateException("File system provider not available"))
            onResult(result)
            if (result.isSuccess) {
                pruneSelection(listOf(path))
                // Refresh parent directory
                val parentPath = path.substringBeforeLast('/')
                if (parentPath.isNotEmpty()) {
                    refreshNode(parentPath)
                }
            }
        }
    }

    /**
     * Delete multiple files/folders (bulk operation).
     *
     * Paths nested under another path in the batch are skipped — deleting the
     * ancestor removes them. Failures don't stop the batch; the result lists
     * every item that failed.
     */
    fun deleteItems(paths: List<String>, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val provider = fileSystemDataProvider
            if (provider == null) {
                onResult(Result.failure(IllegalStateException("File system provider not available")))
                return@launch
            }

            val roots = paths.filter { p -> paths.none { other -> other != p && p.startsWith("$other/") } }
            val deleted = mutableListOf<String>()
            val failedNames = mutableListOf<String>()
            // Deleting many items (recursive folder removals) is slow IO — keep
            // the whole loop off the scope's dispatcher.
            withContext(Dispatchers.IO) {
                for (path in roots) {
                    if (provider.delete(path).isSuccess) {
                        deleted.add(path)
                    } else {
                        failedNames.add(path.substringAfterLast('/'))
                    }
                }
            }

            if (deleted.isNotEmpty()) {
                pruneSelection(deleted)
                deleted.map { it.substringBeforeLast('/') }
                    .distinct()
                    .filter { it.isNotEmpty() }
                    .forEach { refreshNode(it) }
            }

            onResult(
                if (failedNames.isEmpty()) Result.success(Unit)
                else Result.failure(Exception("Failed to delete: ${failedNames.joinToString(", ")}"))
            )
        }
    }

    /**
     * Drop deleted paths (and anything under them) from the selection.
     */
    private fun pruneSelection(deletedPaths: List<String>) {
        val remaining = _selectedPaths.value.filterNot { selected ->
            deletedPaths.any { selected == it || selected.startsWith("$it/") }
        }.toSet()
        _selectedPaths.value = remaining
        if (selectionAnchor !in remaining) selectionAnchor = remaining.firstOrNull()
    }

    /**
     * Rename a file or folder.
     *
     * @param path The current path
     * @param newName The new name
     * @param onResult Callback with the result (new path or error)
     */
    fun renameItem(path: String, newName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            val result = fileSystemDataProvider?.rename(path, newName)
                ?: Result.failure(IllegalStateException("File system provider not available"))
            onResult(result)
            if (result.isSuccess) {
                // Refresh parent directory
                val parentPath = path.substringBeforeLast('/')
                if (parentPath.isNotEmpty()) {
                    refreshNode(parentPath)
                }
            }
        }
    }

    /**
     * Reveal file or folder in system file manager.
     */
    fun revealInFileManager(path: String) {
        fileSystemDataProvider?.revealInFileManager(path)
    }

    /**
     * Open terminal at directory.
     */
    fun openInTerminal(path: String) {
        // Get the directory path (if file, use parent directory)
        val file = java.io.File(path)
        val directory = if (file.isDirectory) file.absolutePath else file.parent ?: return
        val initialCommand = if (!file.isDirectory) "\"${file.absolutePath}\"" else null

        // Use SplitViewOperations to add a terminal tab
        val tabsComponent = splitViewOperations?.getActiveTabsComponent()
        if (tabsComponent != null) {
            val terminalId = "terminal-${UUID.randomUUID()}"
            tabsComponent.addTerminalTab(terminalId, "Terminal", directory, initialCommand)
        }
    }

    /**
     * Copy absolute path to clipboard.
     */
    fun copyPath(path: String) {
        fileSystemDataProvider?.copyToClipboard(path)
    }

    /**
     * Copy relative path (from project root) to clipboard.
     */
    fun copyRelativePath(path: String) {
        val projectPath = getProjectPath() ?: ""
        val relativePath = if (projectPath.isNotEmpty() && path.startsWith(projectPath)) {
            path.removePrefix(projectPath).removePrefix("/")
        } else {
            path
        }
        fileSystemDataProvider?.copyToClipboard(relativePath)
    }

    /**
     * Copy multiple absolute paths to the clipboard, newline-separated,
     * in visible-row order.
     */
    fun copyPaths(paths: List<String>) {
        val resolved = orderSelected(paths).map { toCopyPath(it) }
        fileSystemDataProvider?.copyToClipboard(resolved.joinToString("\n"))
    }

    /**
     * Copy multiple project-relative paths to the clipboard, newline-separated,
     * in visible-row order.
     */
    fun copyRelativePaths(paths: List<String>) {
        val projectPath = getProjectPath() ?: ""
        val relativePaths = orderSelected(paths).map { toCopyPath(it) }.map { path ->
            if (projectPath.isNotEmpty() && path.startsWith(projectPath)) {
                path.removePrefix(projectPath).removePrefix("/")
            } else {
                path
            }
        }
        fileSystemDataProvider?.copyToClipboard(relativePaths.joinToString("\n"))
    }

    /**
     * Resolve a selected row path (a compact-chain top) to the path the
     * single-item Copy Path uses: the innermost directory of the chain.
     * Keeps single and bulk copy in agreement on compacted rows; delete
     * intentionally differs (it removes the whole displayed chain).
     */
    private fun toCopyPath(path: String): String {
        val tree = _fileTree.value ?: return path
        val node = FileTreeUtils.findNodeByPath(tree, path) ?: return path
        return if (node.isDirectory) node.getCompactEndNode().path else node.path
    }

    /**
     * Convert plugin API's FileNodeData to our FileNode type.
     */
    private fun convertToFileNode(data: FileNodeData): FileNode {
        return FileNode(
            name = data.name,
            path = data.path,
            isDirectory = data.isDirectory,
            children = data.children.map { convertToFileNode(it) },
            // Providers that don't report hasChildren leave it unknown (null) so the
            // expand chevron still shows (isAlwaysShowPlus) instead of hiding the dir.
            hasChildren = data.hasChildren ?: when {
                !data.isDirectory -> false
                data.children.isNotEmpty() -> true
                else -> null
            },
            loadingState = when (data.loadingState) {
                NodeLoadingStateData.UNKNOWN -> NodeLoadingState.UNKNOWN
                NodeLoadingStateData.CHECKING -> NodeLoadingState.CHECKING
                NodeLoadingStateData.LOADED -> NodeLoadingState.LOADED
            },
            loadDepth = data.loadDepth
        )
    }
}
