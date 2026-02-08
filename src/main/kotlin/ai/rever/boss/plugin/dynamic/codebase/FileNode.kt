package ai.rever.boss.plugin.dynamic.codebase

/**
 * Loading state for directory nodes (IntelliJ pattern).
 * Separates "checking if has children" from "loading children".
 */
enum class NodeLoadingState {
    /** Initial state - don't know if directory has children */
    UNKNOWN,
    /** Checking if directory has children (quick check) */
    CHECKING,
    /** Children have been fully loaded */
    LOADED
}

/**
 * File system node representation with IntelliJ-style lazy loading.
 *
 * Key patterns from IntelliJ:
 * - `hasChildren`: Quick check to show expand indicator (isAlwaysShowPlus pattern)
 * - `loadingState`: Separate "checking" from "loading" states
 * - Immutable data class for proper Compose recomposition
 */
data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList(),
    /** Quick check result - does this directory have any children? (null = unknown) */
    val hasChildren: Boolean? = null,
    /** Current loading state for this node */
    val loadingState: NodeLoadingState = NodeLoadingState.UNKNOWN,
    val loadDepth: Int = 0
) {
    /** Convenience property - is this node fully loaded? */
    val isLoaded: Boolean get() = loadingState == NodeLoadingState.LOADED

    /**
     * IntelliJ's isAlwaysShowPlus() pattern:
     * Should we show the expand indicator before children are loaded?
     * Returns true if:
     * - Directory with unknown children status (show + optimistically)
     * - Directory that we know has children
     */
    fun shouldShowExpandIndicator(): Boolean {
        if (!isDirectory) return false
        // If we know it has no children, don't show indicator
        if (hasChildren == false) return false
        // If we have loaded children, show based on actual count
        if (isLoaded) return children.isNotEmpty()
        // Unknown or known to have children - show indicator
        return true
    }

    /**
     * IntelliJ's smart expand pattern:
     * Should this folder be auto-expanded because it contains only one subfolder?
     * Returns true if the node has exactly one child and that child is a directory.
     */
    fun shouldSmartExpand(): Boolean {
        if (!isDirectory) return false
        if (!isLoaded) return false
        // Smart expand only if there's exactly one child and it's a directory
        return children.size == 1 && children[0].isDirectory
    }

    /**
     * IntelliJ's compact middle packages pattern:
     * Gets the chain of single-child directories starting from this node.
     * Returns a list of nodes that should be displayed as one compacted entry.
     * Example: src -> main -> kotlin becomes ["src", "main", "kotlin"]
     */
    fun getCompactChain(): List<FileNode> {
        val chain = mutableListOf(this)
        var current = this

        while (current.isLoaded &&
               current.children.size == 1 &&
               current.children[0].isDirectory) {
            current = current.children[0]
            chain.add(current)
        }

        return chain
    }

    /**
     * Gets the display name for compact middle packages.
     * Returns names joined with "/" like "src/main/kotlin"
     */
    fun getCompactDisplayName(): String {
        val chain = getCompactChain()
        return chain.joinToString("/") { it.name }
    }

    /**
     * Gets the final node in a compact chain (the one with actual children to display).
     */
    fun getCompactEndNode(): FileNode {
        var current = this
        while (current.isLoaded &&
               current.children.size == 1 &&
               current.children[0].isDirectory) {
            current = current.children[0]
        }
        return current
    }

    /**
     * Creates a deep copy of this node and all its children.
     * Used for immutable state updates in Compose.
     */
    fun deepCopy(): FileNode = FileNode(
        name = name,
        path = path,
        isDirectory = isDirectory,
        children = children.map { it.deepCopy() },
        hasChildren = hasChildren,
        loadingState = loadingState,
        loadDepth = loadDepth
    )
}
