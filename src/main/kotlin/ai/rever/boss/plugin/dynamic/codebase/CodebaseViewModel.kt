package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.NodeLoadingStateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for the Codebase panel.
 *
 * Manages file tree state and file system operations.
 */
class CodebaseViewModel(
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val scope: CoroutineScope,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?
) {
    private val _rootNode = MutableStateFlow<FileNodeData?>(null)
    val rootNode: StateFlow<FileNodeData?> = _rootNode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    private val _selectedPath = MutableStateFlow<String?>(null)
    val selectedPath: StateFlow<String?> = _selectedPath.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val loadMutex = Mutex()

    // Cache for loaded children
    private val childrenCache = mutableMapOf<String, List<FileNodeData>>()

    /**
     * Initialize by loading the project root.
     */
    fun initialize() {
        val projectPath = getProjectPath()
        if (projectPath != null && projectPath.isNotEmpty()) {
            loadDirectory(projectPath)
        }
    }

    /**
     * Load a directory as the root.
     */
    fun loadDirectory(path: String) {
        scope.launch {
            loadMutex.withLock {
                try {
                    _isLoading.value = true
                    val node = fileSystemDataProvider?.scanDirectory(path)
                    if (node != null) {
                        _rootNode.value = node
                        // Auto-expand root
                        _expandedPaths.value = setOf(path)
                    } else {
                        _statusMessage.value = "Directory not found"
                    }
                } catch (e: Exception) {
                    _statusMessage.value = "Failed to load directory: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Refresh the current root directory.
     */
    fun refresh() {
        val root = _rootNode.value
        if (root != null) {
            childrenCache.clear()
            loadDirectory(root.path)
        }
    }

    /**
     * Toggle expansion of a directory.
     */
    fun toggleExpand(path: String) {
        val current = _expandedPaths.value
        if (path in current) {
            _expandedPaths.value = current - path
        } else {
            _expandedPaths.value = current + path
            // Load children if not cached
            if (path !in childrenCache) {
                loadChildren(path)
            }
        }
    }

    /**
     * Load children of a directory.
     */
    private fun loadChildren(path: String) {
        scope.launch {
            try {
                val node = fileSystemDataProvider?.scanDirectory(path)
                if (node != null) {
                    childrenCache[path] = node.children
                    // Update the tree by forcing a recomposition
                    _rootNode.value = _rootNode.value
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to load: ${e.message}"
            }
        }
    }

    /**
     * Get children for a path (from cache or node).
     */
    fun getChildren(path: String): List<FileNodeData> {
        return childrenCache[path] ?: emptyList()
    }

    /**
     * Check if a directory has children.
     */
    fun hasChildren(path: String): Boolean {
        return fileSystemDataProvider?.directoryHasChildren(path) ?: false
    }

    /**
     * Select a file or directory.
     */
    fun select(path: String) {
        _selectedPath.value = path
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
     * Update search query.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Clear status message.
     */
    fun clearStatusMessage() {
        _statusMessage.value = null
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

    // ============================================================
    // FILE OPERATION METHODS
    // ============================================================

    /**
     * Create a new file.
     *
     * @param parentPath Path to the parent directory
     * @param fileName Name of the new file
     * @param onResult Callback with the result
     */
    fun createFile(parentPath: String, fileName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            try {
                val result = fileSystemDataProvider?.createFile(parentPath, fileName)
                    ?: Result.failure(IllegalStateException("File system provider not available"))
                result.onSuccess {
                    // Refresh the parent directory to show the new file
                    refreshDirectory(parentPath)
                }
                onResult(result)
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Create a new folder.
     *
     * @param parentPath Path to the parent directory
     * @param folderName Name of the new folder
     * @param onResult Callback with the result
     */
    fun createFolder(parentPath: String, folderName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            try {
                val result = fileSystemDataProvider?.createFolder(parentPath, folderName)
                    ?: Result.failure(IllegalStateException("File system provider not available"))
                result.onSuccess {
                    // Refresh the parent directory to show the new folder
                    refreshDirectory(parentPath)
                }
                onResult(result)
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Delete a file or folder.
     *
     * @param path Path to the file or folder to delete
     * @param onResult Callback with the result
     */
    fun deleteItem(path: String, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                val result = fileSystemDataProvider?.delete(path)
                    ?: Result.failure(IllegalStateException("File system provider not available"))
                result.onSuccess {
                    // Refresh the parent directory to reflect the deletion
                    val parentPath = path.substringBeforeLast('/')
                    refreshDirectory(parentPath)
                }
                onResult(result)
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Rename a file or folder.
     *
     * @param path Path to the file or folder to rename
     * @param newName The new name
     * @param onResult Callback with the result
     */
    fun renameItem(path: String, newName: String, onResult: (Result<String>) -> Unit) {
        scope.launch {
            try {
                val result = fileSystemDataProvider?.rename(path, newName)
                    ?: Result.failure(IllegalStateException("File system provider not available"))
                result.onSuccess {
                    // Refresh the parent directory to reflect the rename
                    val parentPath = path.substringBeforeLast('/')
                    refreshDirectory(parentPath)
                }
                onResult(result)
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Copy a path to the clipboard.
     *
     * @param path The path to copy
     */
    fun copyPathToClipboard(path: String) {
        fileSystemDataProvider?.copyToClipboard(path)
    }

    /**
     * Copy a relative path to the clipboard.
     *
     * @param path The absolute path
     */
    fun copyRelativePathToClipboard(path: String) {
        val projectPath = getProjectPath() ?: ""
        val relativePath = if (projectPath.isNotEmpty() && path.startsWith(projectPath)) {
            path.removePrefix(projectPath).removePrefix("/")
        } else {
            path
        }
        fileSystemDataProvider?.copyToClipboard(relativePath)
    }

    /**
     * Reveal a file or folder in the system file manager.
     *
     * @param path Path to reveal
     */
    fun revealInFileManager(path: String) {
        fileSystemDataProvider?.revealInFileManager(path)
    }

    /**
     * Refresh a specific directory (clear cache and reload).
     */
    private fun refreshDirectory(path: String) {
        childrenCache.remove(path)
        // Reload if it's the root
        val root = _rootNode.value
        if (root != null && root.path == path) {
            loadDirectory(path)
        } else if (path in _expandedPaths.value) {
            loadChildren(path)
        }
    }
}
