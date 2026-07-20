package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.NodeLoadingStateData
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Plugin-local file scanner.
 *
 * The host's FileSystemDataProvider.scanDirectory* always filters out
 * dot-entries with no way to opt in, so the "show hidden" toggle scans the
 * filesystem directly. Mirrors the host scanner's behavior (sorting,
 * hasChildren, loading states) with an additional [showHidden] flag;
 * `build`/`node_modules` stay skipped regardless, matching the host.
 *
 * DRIFT RISK: this duplicates BossConsole's DesktopFileScanner — if that
 * changes (filter set, sorting, placeholder semantics), this copy silently
 * diverges; the unit tests only pin the copy, they can't detect divergence.
 * Tracked in https://github.com/risa-labs-inc/boss-plugin-codebase/issues/6:
 * delete this file and go back through the provider once
 * FileSystemDataProvider gains a showHidden opt-in. Note writes, deletes,
 * renames, and opens still go through the host provider; only read-side
 * tree scans happen here.
 *
 * ACCESS CONTROL: verified (2026-07) that the host's FileSystemDataProviderImpl
 * is a plain Dispatchers.IO wrapper over the same unscoped platform scanner —
 * no project-root scoping or RBAC on reads — so scanning directly does not
 * bypass a security boundary. If the provider ever adds read scoping this
 * becomes a bypass — revisit via issue #6 (same tracking issue as above).
 * (The host does canonical-path validation on writes; those stay on the provider.)
 *
 * PATH SEPARATORS: emits File.absolutePath verbatim, exactly like the host
 * scanner (neither normalizes separators), so node keys stay comparable with
 * host-sourced paths (e.g. getProjectPath()) on every platform. The plugin's
 * '/'-based string logic predates this file and would need a coordinated
 * plugin+host pass to be Windows-correct; do not "fix" it here unilaterally.
 */
object LocalFileScanner {

    private val skippedDirectoryNames = setOf("build", "node_modules")

    private fun isVisibleEntry(name: String, showHidden: Boolean): Boolean =
        (showHidden || !name.startsWith(".")) && name !in skippedDirectoryNames

    fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? {
        val file = File(path)
        if (!file.exists()) return null

        // Initial scan is shallow - only immediate children
        return scanFileRecursively(file, maxDepth = 1, showHidden = showHidden)
    }

    fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean
    ): FileNodeData? {
        val file = File(path)
        if (!file.exists()) return null

        return scanFileRecursively(file, currentDepth = startDepth, maxDepth = maxDepth, showHidden = showHidden)
    }

    /**
     * Quick check if a directory has any visible children without listing all
     * of them (streams entries, stops at the first visible one).
     */
    fun directoryHasChildren(path: String, showHidden: Boolean): Boolean {
        return try {
            val dir = Paths.get(path)
            if (!Files.isDirectory(dir)) return false

            Files.newDirectoryStream(dir).use { stream ->
                stream.any { isVisibleEntry(it.fileName.toString(), showHidden) }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun scanFileRecursively(
        file: File,
        currentDepth: Int = 0,
        maxDepth: Int = 5,
        showHidden: Boolean
    ): FileNodeData {
        val isDirectory = file.isDirectory
        val shouldLoadChildren = isDirectory && currentDepth < maxDepth

        val children: List<FileNodeData> = if (shouldLoadChildren) {
            file.listFiles()
                ?.filter { isVisibleEntry(it.name, showHidden) }
                // Stat each entry once up front — sort comparators re-invoke their
                // selectors, and File.isDirectory is a syscall per call.
                ?.map { it to it.isDirectory }
                ?.sortedWith(compareBy({ !it.second }, { it.first.name.lowercase() }))
                ?.map { (childFile, childIsDirectory) ->
                    // For directories at the edge of our scan depth, just create a placeholder
                    if (childIsDirectory && currentDepth + 1 >= maxDepth) {
                        val hasKids = directoryHasChildren(childFile.absolutePath, showHidden)
                        FileNodeData(
                            name = childFile.name,
                            path = childFile.absolutePath,
                            isDirectory = true,
                            children = emptyList(),
                            hasChildren = hasKids,
                            loadingState = NodeLoadingStateData.UNKNOWN,
                            loadDepth = currentDepth + 1
                        )
                    } else {
                        scanFileRecursively(childFile, currentDepth + 1, maxDepth, showHidden)
                    }
                }
                ?: emptyList()
        } else {
            emptyList()
        }

        val loadingState = when {
            !isDirectory -> NodeLoadingStateData.LOADED
            currentDepth >= maxDepth - 1 -> NodeLoadingStateData.UNKNOWN
            else -> NodeLoadingStateData.LOADED
        }

        // When we already listed this directory, the filtered children list is
        // the authoritative answer; only fall back to a directory stream when
        // listing was skipped due to the depth limit.
        val hasChildren = when {
            !isDirectory -> false
            children.isNotEmpty() -> true
            shouldLoadChildren -> false
            else -> directoryHasChildren(file.absolutePath, showHidden)
        }

        return FileNodeData(
            name = file.name,
            path = file.absolutePath,
            isDirectory = isDirectory,
            children = children,
            hasChildren = hasChildren,
            loadingState = loadingState,
            loadDepth = currentDepth
        )
    }
}
