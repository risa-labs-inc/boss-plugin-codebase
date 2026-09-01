package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitFileStatusData

/**
 * The two shapes VS Code offers for a change group: a flat list of paths, and
 * a collapsible directory tree.
 *
 * The flat list is right for a handful of files; past that the repeated
 * directory prefixes are most of what you read. The tree collapses shared
 * prefixes so the files stand out - and, as in VS Code, a chain of
 * single-child directories is compacted onto one row (`src/main/kotlin`)
 * rather than costing three rows of indentation to say nothing.
 */
internal object GitChangeTree {

    /** One rendered row: either a directory or a changed file. */
    sealed interface Row {
        val depth: Int

        data class Directory(
            /** Full project-relative path of this directory, the collapse key. */
            val path: String,
            /** What to print - possibly several segments, when the chain was compacted. */
            val label: String,
            override val depth: Int,
            /** Files under this directory, at any depth. */
            val fileCount: Int,
        ) : Row

        data class FileRow(
            val file: GitFileStatusData,
            /** Just the file name; the directory is the row above. */
            val name: String,
            override val depth: Int,
        ) : Row
    }

    /**
     * The files a directory row stands for - itself and every depth beneath.
     *
     * Group actions operate on this, so staging a directory means staging
     * exactly the rows the user can see under it, not a path glob that might
     * also catch files in another group.
     */
    fun filesUnder(
        files: List<GitFileStatusData>,
        directoryPath: String,
    ): List<GitFileStatusData> {
        if (directoryPath.isEmpty()) return files
        val prefix = "$directoryPath/"
        return files.filter { it.path.startsWith(prefix) }
    }

    private class Node(val path: String, val name: String) {
        val dirs = LinkedHashMap<String, Node>()
        val files = mutableListOf<GitFileStatusData>()
        var fileCount = 0
    }

    /**
     * Flatten [files] into tree rows, honouring [collapsed] (directory paths
     * whose children are hidden).
     *
     * Directories sort before files and both sort by name, so the order is
     * stable across refreshes - a list that reorders under a 2.5s refresh is
     * unusable.
     */
    fun rows(
        files: List<GitFileStatusData>,
        collapsed: Set<String>,
    ): List<Row> {
        if (files.isEmpty()) return emptyList()
        val root = Node("", "")
        for (file in files) {
            val segments = file.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var node = root
            node.fileCount++
            for (i in 0 until segments.size - 1) {
                val name = segments[i]
                val path = if (node.path.isEmpty()) name else "${node.path}/$name"
                node = node.dirs.getOrPut(path) { Node(path, name) }
                node.fileCount++
            }
            node.files.add(file)
        }

        val out = mutableListOf<Row>()
        emit(root, depth = 0, collapsed = collapsed, out = out, prefixLabel = null)
        return out
    }

    private fun emit(
        node: Node,
        depth: Int,
        collapsed: Set<String>,
        out: MutableList<Row>,
        prefixLabel: String?,
    ) {
        for (child in node.dirs.values.sortedBy { it.name.lowercase() }) {
            // Compact a single-child directory chain onto one row, the way VS
            // Code's explorer does: src > main > kotlin reads as src/main/kotlin.
            var current = child
            val label = StringBuilder(prefixLabel?.let { "$it/${child.name}" } ?: child.name)
            while (current.files.isEmpty() && current.dirs.size == 1) {
                val only = current.dirs.values.first()
                // Stop compacting at a collapsed directory: its own row has to
                // exist for the chevron to have something to toggle.
                if (current.path in collapsed) break
                label.append('/').append(only.name)
                current = only
            }
            out.add(
                Row.Directory(
                    path = current.path,
                    label = label.toString(),
                    depth = depth,
                    fileCount = current.fileCount,
                ),
            )
            if (current.path !in collapsed) {
                emit(current, depth + 1, collapsed, out, prefixLabel = null)
            }
        }
        for (file in node.files.sortedBy { it.path.lowercase() }) {
            out.add(Row.FileRow(file = file, name = file.path.substringAfterLast('/'), depth = depth))
        }
    }
}

/**
 * How a change group lays its rows out. Persisted per panel under
 * `codebase.gitLayout`.
 *
 * Public only because [CodebaseGitViewModel] is: an internal type on a public
 * flow does not compile.
 */
enum class GitChangeLayout(val storageKey: String) {
    TREE("tree"),
    LIST("list"),
    ;

    fun toggled(): GitChangeLayout = if (this == TREE) LIST else TREE

    companion object {
        /**
         * The stored choice, defaulting to [TREE].
         *
         * A flat list is only the better read for a handful of files; a real
         * change set is mostly repeated directory prefixes, which is exactly
         * what the tree folds away. An unknown key means a manifest from a
         * newer build or a corrupted value - the default, not a crash.
         */
        fun fromStorage(key: String?): GitChangeLayout =
            entries.firstOrNull { it.storageKey == key } ?: TREE
    }
}
