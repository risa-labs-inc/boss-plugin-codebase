package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.ProjectData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ordering and matching rules behind the header's project dropdown. Each case
 * passes the separator explicitly where it matters, so Windows behavior is
 * pinned even when the suite runs on macOS/Linux (see [PathUtilsTest]).
 */
class ProjectSwitcherEntriesTest {

    private fun project(name: String, path: String) = ProjectData(name = name, path = path)

    @Test
    fun `keeps the host's recents ordering`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(
                project("Boss", "/dev/Boss"),
                project("BossTerm", "/dev/BossTerm"),
                project("Notes", "/dev/Notes")
            ),
            currentPath = null,
            separator = '/'
        )

        assertEquals(listOf("Boss", "BossTerm", "Notes"), entries.map { it.name })
        assertTrue(entries.none { it.isCurrent })
    }

    @Test
    fun `the open project leads and is marked current`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(
                project("Boss", "/dev/Boss"),
                project("BossTerm", "/dev/BossTerm"),
                project("Notes", "/dev/Notes")
            ),
            currentPath = "/dev/Notes",
            separator = '/'
        )

        assertEquals(listOf("Notes", "Boss", "BossTerm"), entries.map { it.name })
        assertEquals(listOf(true, false, false), entries.map { it.isCurrent })
    }

    @Test
    fun `the open project appears even when the recents list omits it`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(project("Boss", "/dev/Boss")),
            currentPath = "/dev/OpenedByDeepLink",
            separator = '/'
        )

        assertEquals(listOf("OpenedByDeepLink", "Boss"), entries.map { it.name })
        assertEquals("/dev/OpenedByDeepLink", entries.first().path)
        assertTrue(entries.first().isCurrent)
    }

    @Test
    fun `a trailing separator is the same project, not a second row`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(
                project("Boss", "/dev/Boss/"),
                project("Boss", "/dev/Boss")
            ),
            currentPath = "/dev/Boss",
            separator = '/'
        )

        assertEquals(1, entries.size)
        assertTrue(entries.single().isCurrent)
        // The first occurrence wins, verbatim: selectProject gets the host's own
        // string back rather than one this file rewrote.
        assertEquals("/dev/Boss/", entries.single().path)
    }

    @Test
    fun `a blank recorded name falls back to the directory name`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(project("   ", "/dev/Boss/")),
            currentPath = null,
            separator = '/'
        )

        assertEquals("Boss", entries.single().name)
    }

    @Test
    fun `windows paths dedupe and match on their own separator`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(
                project("Boss", """C:\dev\Boss\"""),
                project("Boss", """C:\dev\Boss"""),
                project("Term", """C:\dev\Term""")
            ),
            currentPath = """C:\dev\Term""",
            separator = '\\'
        )

        assertEquals(listOf("Term", "Boss"), entries.map { it.name })
        assertEquals(listOf(true, false), entries.map { it.isCurrent })
    }

    @Test
    fun `an empty current path selects nothing`() {
        val entries = ProjectSwitcherEntries.build(
            recentProjects = listOf(project("Boss", "/dev/Boss")),
            currentPath = "",
            separator = '/'
        )

        assertEquals(listOf("Boss"), entries.map { it.name })
        assertTrue(entries.none { it.isCurrent })
    }

    @Test
    fun `location label shows the parent with home collapsed`() {
        assertEquals(
            "~/Development",
            ProjectSwitcherEntries.locationLabel("/Users/k/Development/Boss", '/', "/Users/k")
        )
        assertEquals(
            "~",
            ProjectSwitcherEntries.locationLabel("/Users/k/Boss", '/', "/Users/k")
        )
        // Outside home, and a home-prefixed sibling that is not inside home.
        assertEquals(
            "/opt/src",
            ProjectSwitcherEntries.locationLabel("/opt/src/Boss", '/', "/Users/k")
        )
        assertEquals(
            "/Users/kate",
            ProjectSwitcherEntries.locationLabel("/Users/kate/Boss", '/', "/Users/k")
        )
    }

    @Test
    fun `location label is empty when there is no parent to show`() {
        assertEquals("", ProjectSwitcherEntries.locationLabel("Boss", '/', "/Users/k"))
        assertEquals("", ProjectSwitcherEntries.locationLabel("/Boss", '/', "/Users/k"))
    }

    @Test
    fun `location label tolerates a missing home directory`() {
        assertEquals(
            "/dev",
            ProjectSwitcherEntries.locationLabel("/dev/Boss", '/', null)
        )
    }
}
