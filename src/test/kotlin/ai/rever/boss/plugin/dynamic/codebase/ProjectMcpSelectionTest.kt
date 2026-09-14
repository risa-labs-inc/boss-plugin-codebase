package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectMcpSelectionTest {
    private val selected = mutableListOf<ProjectData>()
    private val provider = object : ProjectDataProvider {
        override val recentProjects = MutableStateFlow(emptyList<ProjectData>())
        override fun updateRecentProjects(project: ProjectData) = Unit
        override fun removeRecentProject(projectPath: String) = Unit
        override fun selectProject(project: ProjectData) { selected += project }
    }
    private val tool = CodebaseMcpToolProvider("test", null, provider, { null }, { null })
        .tools().first { it.name == "codebase_select_project" }

    @Test
    fun `MCP normalizes path before deriving the project name`() = runBlocking {
        val sep = PathUtils.platformSeparator
        tool.handler.call(McpToolArgs(mapOf("path" to "${sep}dev${sep}Boss$sep")))
        assertEquals(ProjectData("Boss", "${sep}dev${sep}Boss"), selected.single())
    }

    @Test
    fun `MCP preserves meaningful spaces and explicit display name`() = runBlocking {
        val sep = PathUtils.platformSeparator
        tool.handler.call(McpToolArgs(mapOf("path" to "${sep}dev${sep}Boss $sep", "name" to "Display")))
        assertEquals(ProjectData("Display", "${sep}dev${sep}Boss "), selected.single())
    }

    @Test
    fun `MCP rejects blank path without selecting`() = runBlocking {
        assertTrue(tool.handler.call(McpToolArgs(mapOf("path" to "   "))).isError)
        assertTrue(selected.isEmpty())
    }
}
