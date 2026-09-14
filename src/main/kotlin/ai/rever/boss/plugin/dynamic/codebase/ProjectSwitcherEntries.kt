package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ProjectData

/**
 * One row in the header's project dropdown.
 *
 * [path] retains the host's string for row identity. Selection normalizes
 * trailing separators at the ViewModel boundary before returning it to the host.
 */
internal data class ProjectSwitcherEntry(
    val name: String,
    val path: String,
    val isCurrent: Boolean
)

/**
 * Turns the host's recent-projects list into the dropdown's rows.
 *
 * Pure on purpose: the ordering and matching rules are the part worth pinning
 * in tests, and none of them need a composition to exercise.
 */
internal object ProjectSwitcherEntries {

    /**
     * @param recentProjects the host's list, most-recently-opened first.
     * @param currentPath the project this window has open, if any.
     * @param separator path separator, injectable so Windows paths are testable
     *   on any OS (see [PathUtils]).
     */
    fun build(
        recentProjects: List<ProjectData>,
        currentPath: String?,
        separator: Char = PathUtils.platformSeparator
    ): List<ProjectSwitcherEntry> {
        val currentKey = matchKey(currentPath.orEmpty(), separator)
        // Keyed by normalized path so the same project recorded twice with a
        // differing trailing separator collapses into one row; insertion order
        // preserves the host's most-recent-first ordering.
        val byKey = LinkedHashMap<String, ProjectSwitcherEntry>()

        fun put(name: String, path: String) {
            val key = matchKey(path, separator)
            if (key.isEmpty() || byKey.containsKey(key)) return
            byKey[key] = ProjectSwitcherEntry(
                // A blank recorded name would render an empty row, so fall back
                // to the directory name and then to the path itself.
                name = name.trim().ifEmpty { PathUtils.name(key, separator) }.ifEmpty { key },
                path = path,
                isCurrent = key == currentKey
            )
        }

        recentProjects.forEach { put(it.name, it.path) }
        // The open project belongs in the menu even when the recents list does
        // not carry it — a path opened by deep link, or a recents file that
        // failed to load, would otherwise leave the menu with no current row.
        currentPath?.let { put("", it) }

        // The open project is the menu's anchor, so it leads regardless of where
        // it sits in the recents ordering. sortedByDescending is stable, so
        // everything else keeps that ordering.
        return byKey.values.sortedByDescending { it.isCurrent }
    }

    /**
     * The path as shown under a row's name: the PARENT directory (the row's
     * name already says the leaf), with the user's home collapsed to `~`.
     * Empty when the parent is nothing worth showing.
     */
    fun locationLabel(
        path: String,
        separator: Char = PathUtils.platformSeparator,
        homeDirectory: String? = System.getProperty("user.home")
    ): String {
        val parent = PathUtils.parent(matchKey(path, separator), separator)
        if (parent.isEmpty()) return ""
        return collapseHome(parent, separator, homeDirectory)

    }

    /**
     * Normalized form used for dedupe and current-project matching only.
     * Only trailing separators are ignored; whitespace can belong to a name.
     */
    private fun matchKey(path: String, separator: Char): String =
        PathUtils.trimTrailingSeparator(path, separator)
}
