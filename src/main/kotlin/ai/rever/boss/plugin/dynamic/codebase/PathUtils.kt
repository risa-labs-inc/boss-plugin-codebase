package ai.rever.boss.plugin.dynamic.codebase

/**
 * Separator-aware path string helpers (issue #7).
 *
 * All paths in this plugin come from File.absolutePath (the host provider
 * emits it verbatim), so segments are joined with the PLATFORM separator —
 * backslash on Windows. These helpers replace
 * the previously hardcoded '/' logic. They take the separator as a parameter
 * (defaulting to the platform's) so tests can exercise Windows-style paths
 * on any OS.
 *
 * Deliberately not normalizing to '/': paths are used as tree/selection keys
 * and compared against host-sourced paths (e.g. getProjectPath()), so they
 * must stay byte-identical to what the sources emit.
 */
object PathUtils {

    val platformSeparator: Char = java.io.File.separatorChar

    /**
     * Last path segment (the file or directory name). Not trailing-separator
     * safe on its own: "/p/a/" yields "" — call sites guard with ifEmpty.
     */
    fun name(path: String, separator: Char = platformSeparator): String =
        path.substringAfterLast(separator)

    /** Everything before the last segment ("" if there is no separator). */
    fun parent(path: String, separator: Char = platformSeparator): String =
        path.substringBeforeLast(separator, missingDelimiterValue = "")

    /** True if [path] is strictly inside [ancestor] (not equal to it). */
    fun isNestedUnder(path: String, ancestor: String, separator: Char = platformSeparator): Boolean =
        path.startsWith(ancestor + separator)

    /** [path] relative to [root], or [path] unchanged when it isn't under root. */
    fun relativize(path: String, root: String, separator: Char = platformSeparator): String =
        if (root.isNotEmpty() && (path == root || isNestedUnder(path, root, separator))) {
            path.removePrefix(root).trimStart(separator)
        } else {
            path
        }
}
