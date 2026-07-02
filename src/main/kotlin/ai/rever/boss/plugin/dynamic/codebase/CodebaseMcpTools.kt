package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider

/**
 * MCP tools contributed by the Codebase plugin: browse the project file tree,
 * read/write files, open a file in the editor, and switch projects. Registered
 * in [CodebaseDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class CodebaseMcpToolProvider(
    override val providerId: String,
    private val fileSystem: FileSystemDataProvider?,
    private val projects: ProjectDataProvider?,
    private val getWindowId: () -> String?,
    private val getProjectPath: () -> String?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "codebase_tree",
            description = "List the file tree under a directory (defaults to the current project root).",
            inputSchema = TREE_SCHEMA,
            handler = McpToolHandler { args ->
                val fs = fileSystem ?: return@McpToolHandler unavailable()
                val path = args.string("path") ?: getProjectPath()
                    ?: return@McpToolHandler McpToolResult("No path given and no project is open.", isError = true)
                val depth = (args.int("depth") ?: 2).coerceIn(1, 6)
                val root = fs.scanDirectoryWithDepth(path, depth, 0)
                    ?: return@McpToolHandler McpToolResult("Could not scan: $path", isError = true)
                val sb = StringBuilder()
                renderTree(root, "", sb)
                McpToolResult(sb.toString().trimEnd())
            },
        ),
        McpToolDefinition(
            name = "codebase_read",
            description = "Read a text file's contents (truncated if very large).",
            inputSchema = pathSchema("File path to read."),
            handler = McpToolHandler { args ->
                val fs = fileSystem ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                fs.readFile(path).fold(
                    onSuccess = { content ->
                        val max = 50_000
                        if (content.length > max) McpToolResult(content.take(max) + "\n… [truncated ${content.length - max} chars]")
                        else McpToolResult(content)
                    },
                    onFailure = { McpToolResult("Read failed: ${it.message}", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "codebase_write",
            description = "Write (create or overwrite) a text file with the given content.",
            inputSchema = WRITE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val fs = fileSystem ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                // Error rather than default to "" — a missing/null content must not
                // silently truncate the target file to empty.
                val content = args.string("content")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: content", isError = true)
                fs.writeFile(path, content).fold(
                    onSuccess = { McpToolResult("Wrote ${content.length} chars to $path.") },
                    onFailure = { McpToolResult("Write failed: ${it.message}", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "codebase_open",
            description = "Open a file in the BOSS editor.",
            inputSchema = pathSchema("File path to open."),
            readOnly = false,
            handler = McpToolHandler { args ->
                val fs = fileSystem ?: return@McpToolHandler unavailable()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                fs.openFile(path, getWindowId() ?: "unknown")
                McpToolResult("Requested open of $path.")
            },
        ),
        McpToolDefinition(
            name = "codebase_projects",
            description = "List recent projects (name and path).",
            handler = McpToolHandler {
                val p = projects ?: return@McpToolHandler McpToolResult("Project provider unavailable.", isError = true)
                val recent = p.recentProjects.value
                if (recent.isEmpty()) McpToolResult("No recent projects.")
                else McpToolResult(recent.joinToString("\n") { "${it.name}\t${it.path}" })
            },
        ),
        McpToolDefinition(
            name = "codebase_select_project",
            description = "Open/select a project by path (and optional display name).",
            inputSchema = SELECT_PROJECT_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val p = projects ?: return@McpToolHandler McpToolResult("Project provider unavailable.", isError = true)
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                val name = args.string("name") ?: path.substringAfterLast('/')
                p.selectProject(ProjectData(name = name, path = path))
                McpToolResult("Selected project $name ($path).")
            },
        ),
    )

    private fun renderTree(node: FileNodeData, indent: String, sb: StringBuilder) {
        sb.append(indent).append(node.name).append(if (node.isDirectory) "/" else "").append('\n')
        val childIndent = "$indent  "
        node.children
            .sortedWith(compareByDescending<FileNodeData> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { renderTree(it, childIndent, sb) }
    }

    private fun unavailable(): McpToolResult =
        McpToolResult("File system provider unavailable in this context.", isError = true)

    private fun pathSchema(desc: String): String =
        """{"type":"object","properties":{"path":{"type":"string","description":"$desc"}},"required":["path"]}"""

    private companion object {
        const val TREE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"Directory to list (default: project root)."},"depth":{"type":"integer","description":"Max depth 1-6 (default 2)."}}}"""
        const val WRITE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path to write."},"content":{"type":"string","description":"New file content."}},"required":["path","content"]}"""
        const val SELECT_PROJECT_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"Absolute project path."},"name":{"type":"string","description":"Optional display name."}},"required":["path"]}"""
    }
}
