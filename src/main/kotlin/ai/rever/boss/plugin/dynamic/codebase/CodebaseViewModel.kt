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
}
