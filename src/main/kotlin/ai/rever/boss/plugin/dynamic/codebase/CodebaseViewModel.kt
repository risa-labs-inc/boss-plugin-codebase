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

    private val _selectedPath = MutableStateFlow<String?>(null)
    val selectedPath: StateFlow<String?> = _selectedPath.asStateFlow()

    private val fileCache = FileIndexCache(
        maxSize = 1000,
        maxDepthInitial = 2,
        fileSystemProvider = fileSystemDataProvider
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

        _fileTree.value = fileCache.getNode(rootPath)
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

        // Load children
        val scannedNode = try {
            withContext(Dispatchers.IO) {
                fileSystemDataProvider?.scanDirectoryWithDepth(targetPath, maxDepth = 1, startDepth = 0)
            }
        } catch (e: Exception) {
            null
        }

        val loadedChildren = scannedNode?.children?.map { child ->
            if (child.isDirectory) {
                val hasKids = try {
                    fileSystemDataProvider?.directoryHasChildren(child.path) ?: false
                } catch (e: Exception) {
                    false
                }
                convertToFileNode(child).copy(hasChildren = hasKids)
            } else {
                convertToFileNode(child)
            }
        }

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
                fileSystemDataProvider?.scanDirectoryWithDepth(path, maxDepth = 1, startDepth = 0)
            }
        } catch (e: Exception) {
            null
        }

        val loadedChildren = scannedNode?.children?.map { child ->
            if (child.isDirectory) {
                val hasKids = try {
                    fileSystemDataProvider?.directoryHasChildren(child.path) ?: false
                } catch (e: Exception) {
                    false
                }
                convertToFileNode(child).copy(hasChildren = hasKids)
            } else {
                convertToFileNode(child)
            }
        }

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
     * Select a file or directory.
     */
    fun select(path: String) {
        _selectedPath.value = path
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
                    val scannedNode = fileSystemDataProvider?.scanDirectoryWithDepth(path, maxDepth = 1, startDepth = 0)
                    scannedNode?.children?.map { child ->
                        if (child.isDirectory) {
                            val hasKids = try {
                                fileSystemDataProvider?.directoryHasChildren(child.path) ?: false
                            } catch (e: Exception) {
                                false
                            }
                            convertToFileNode(child).copy(hasChildren = hasKids)
                        } else {
                            convertToFileNode(child)
                        }
                    }
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
                // Refresh parent directory
                val parentPath = path.substringBeforeLast('/')
                if (parentPath.isNotEmpty()) {
                    refreshNode(parentPath)
                }
            }
        }
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

        // Use SplitViewOperations to add a terminal tab
        val tabsComponent = splitViewOperations?.getActiveTabsComponent()
        if (tabsComponent != null) {
            val terminalId = "terminal-${UUID.randomUUID()}"
            tabsComponent.addTerminalTab(terminalId, "Terminal", directory)
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
     * Convert plugin API's FileNodeData to our FileNode type.
     */
    private fun convertToFileNode(data: FileNodeData): FileNode {
        return FileNode(
            name = data.name,
            path = data.path,
            isDirectory = data.isDirectory,
            children = data.children.map { convertToFileNode(it) },
            hasChildren = data.hasChildren ?: (data.isDirectory && data.children.isNotEmpty()),
            loadingState = when (data.loadingState) {
                NodeLoadingStateData.UNKNOWN -> NodeLoadingState.UNKNOWN
                NodeLoadingStateData.CHECKING -> NodeLoadingState.CHECKING
                NodeLoadingStateData.LOADED -> NodeLoadingState.LOADED
            },
            loadDepth = data.loadDepth
        )
    }
}
