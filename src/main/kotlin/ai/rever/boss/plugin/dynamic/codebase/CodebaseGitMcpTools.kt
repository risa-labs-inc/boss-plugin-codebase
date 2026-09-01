package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.ProjectSearchProvider

/**
 * The git / project-search MCP tools absorbed from the retired
 * git-status, git-log and search-replace plugins (P7).
 *
 * Tool names, schemas and RBAC are carried over verbatim - the P6.2 tool
 * tiers pin these names, so the provider move is transparent to Atlas.
 * Registered alongside the existing codebase_* tools in
 * [CodebaseDynamicPlugin.register]; auto-removed on disable/unload.
 */
internal class CodebaseGitMcpToolProvider(
    override val providerId: String,
    private val gitProvider: GitDataProvider?,
    private val searchProvider: ProjectSearchProvider?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        // ---- from git-status ----
        McpToolDefinition(
            name = "git_status",
            description = "Show the working-tree status (staged, unstaged, and untracked files) of " +
                "the current BOSS project, in a git-porcelain-style format (XY path).",
            readOnly = true,
            handler = McpToolHandler { status() },
        ),
        McpToolDefinition(
            name = "git_stage",
            description = "Stage a file for commit in the current project.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Staged", gitProvider?.stage(path))
            },
        ),
        McpToolDefinition(
            name = "git_unstage",
            description = "Unstage a previously staged file in the current project.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Unstaged", gitProvider?.unstage(path))
            },
        ),
        McpToolDefinition(
            name = "git_stage_all",
            description = "Stage all changed files in the current project.",
            readOnly = false,
            handler = McpToolHandler { op("Staged all", gitProvider?.stageAll()) },
        ),
        McpToolDefinition(
            name = "git_unstage_all",
            description = "Unstage all staged files in the current project.",
            readOnly = false,
            handler = McpToolHandler { op("Unstaged all", gitProvider?.unstageAll()) },
        ),
        McpToolDefinition(
            name = "git_discard",
            description = "Discard working-tree changes to a file (irreversible). Use with care.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Discarded", gitProvider?.discardChanges(path))
            },
        ),
        McpToolDefinition(
            name = "git_checkout",
            description = "Checkout a commit, branch, or tag in the current project.",
            inputSchema = REF_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val ref = args.string("ref")
                    ?: return@McpToolHandler missing("ref")
                op("Checked out $ref", gitProvider?.checkout(ref))
            },
        ),
        McpToolDefinition(
            name = "git_diff",
            description = "Show the unified diff of one file's working-tree (or staged, when " +
                "staged=true) changes vs HEAD.",
            inputSchema = DIFF_FILE_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val path = args.string("path")
                    ?: return@McpToolHandler missing("path")
                diffTexts(gitProvider?.diffFile(path, args.boolean("staged") ?: false).orEmpty())
            },
        ),
        McpToolDefinition(
            name = "git_diff_all",
            description = "List every changed file (git diff --name-status) as 'STATUS path' lines. " +
                "staged=true lists the index instead of the working tree.",
            inputSchema = STAGED_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val names = gitProvider?.diffNames(args.boolean("staged") ?: false) ?: emptyList()
                if (names.isEmpty()) McpToolResult("No changes.")
                else McpToolResult(names.joinToString("\n") { "${(it.indexStatus ?: it.workTreeStatus)?.name ?: "?"} ${it.path}" })
            },
        ),
        McpToolDefinition(
            name = "git_diff_ref",
            description = "Show the changes introduced by a single commit (git show), optionally " +
                "restricted to one path.",
            inputSchema = DIFF_REF_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val ref = args.string("ref")
                    ?: return@McpToolHandler missing("ref")
                diffTexts(gitProvider?.diffRef(ref, args.string("path")).orEmpty())
            },
        ),
        McpToolDefinition(
            name = "git_diff_between",
            description = "Show the diff between two refs (git diff from to), optionally restricted " +
                "to one path.",
            inputSchema = DIFF_BETWEEN_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val from = args.string("from")
                    ?: return@McpToolHandler missing("from")
                val to = args.string("to")
                    ?: return@McpToolHandler missing("to")
                diffTexts(gitProvider?.diffBetween(from, to, args.string("path")).orEmpty())
            },
        ),
        // ---- from git-log ----
        McpToolDefinition(
            name = "git_log",
            description = "List recent commits in the current BOSS project (short hash, subject, author).",
            inputSchema = LIMIT_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args ->
                val limit = args.int("limit") ?: 30
                gitProvider?.refreshLog(limit)
                val log = gitProvider?.commitLog?.value.orEmpty().take(limit)
                if (log.isEmpty()) McpToolResult("No commits.")
                else McpToolResult(log.joinToString("\n") { "${it.shortHash} ${it.subject} (${it.author})" })
            },
        ),
        McpToolDefinition(
            name = "git_cherry_pick",
            description = "Cherry-pick a commit onto the current branch of the current project.",
            inputSchema = HASH_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val hash = args.hash()
                if (hash == null) missing("hash") else op("Cherry-picked $hash", gitProvider?.cherryPick(hash))
            },
        ),
        McpToolDefinition(
            name = "git_revert",
            description = "Revert a commit in the current project (creates a new revert commit).",
            inputSchema = HASH_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val hash = args.hash()
                if (hash == null) missing("hash") else op("Reverted $hash", gitProvider?.revert(hash))
            },
        ),
        // ---- from search-replace ----
        McpToolDefinition(
            name = "project_search",
            description = "Search file contents across the current BOSS project (find in files). " +
                "Supports literal text or a regular expression, case and whole-word matching, and " +
                "a glob filter on the project-relative path. Binary and oversized files are skipped; " +
                "results are capped by maxResults.",
            inputSchema = SEARCH_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args -> search(args) },
        ),
        McpToolDefinition.withRbac(
            name = "project_replace",
            description = "Replace occurrences in an EXPLICIT list of files - never project-wide. " +
                "dryRun defaults to true (counts what would change without writing). Open buffers " +
                "are edited through the editor's undoable path; closed files are written to disk. " +
                "For regex queries the replacement supports $1..$9 capture references.",
            inputSchema = REPLACE_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf("project.replace"),
            handler = McpToolHandler { args -> replace(args) },
        ),
    )

    // ---- handlers ----

    private suspend fun search(args: McpToolArgs): McpToolResult {
        val provider = searchProvider ?: return unavailable()
        val query = args.string("query") ?: return missing("query")
        val matches =
            provider.searchInProject(
                query = query,
                pathPattern = args.string("pathPattern"),
                isRegex = args.boolean("isRegex") ?: false,
                caseSensitive = args.boolean("caseSensitive") ?: false,
                wholeWord = args.boolean("wholeWord") ?: false,
                maxResults = args.int("maxResults") ?: 100,
            )
        if (matches.isEmpty()) return McpToolResult("No matches.")
        val lines =
            matches.take(MAX_RESULT_LINES).joinToString("\n") { m ->
                "${m.path}:${m.line}:${m.column}: ${m.contextLine.trim()}"
            }
        val more =
            if (matches.size > MAX_RESULT_LINES) "\n... [${matches.size - MAX_RESULT_LINES} more matches]" else ""
        return McpToolResult("${matches.size} match(es):\n$lines$more")
    }

    private suspend fun replace(args: McpToolArgs): McpToolResult {
        val provider = searchProvider ?: return unavailable()
        val query = args.string("query") ?: return missing("query")
        val replacement = args.string("replacement") ?: return missing("replacement")
        val files =
            args.string("files")
                ?.split(",", "\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: return missing("files")
        if (files.isEmpty()) return McpToolResult("Argument 'files' must not be empty.", isError = true)
        val dryRun = args.boolean("dryRun") ?: true
        val summary =
            provider.replaceInProject(
                query = query,
                replacement = replacement,
                files = files,
                isRegex = args.boolean("isRegex") ?: false,
                caseSensitive = args.boolean("caseSensitive") ?: false,
                wholeWord = args.boolean("wholeWord") ?: false,
                dryRun = dryRun,
            )
        val verb = if (dryRun) "would replace" else "replaced"
        var out = "$verb ${summary.totalReplacements} occurrence(s) in ${summary.filesReplaced} file(s)."
        val errors = summary.files.filter { it.error != null }
        if (errors.isNotEmpty()) {
            out += "\nSkipped: " + errors.take(MAX_ERROR_LINES).joinToString("; ") { "${it.path}: ${it.error}" }
        }
        if (dryRun) out += "\n(dry run - nothing written; re-call with dryRun=false to apply)"
        return McpToolResult(out)
    }

    private suspend fun status(): McpToolResult {
        val provider = gitProvider ?: return unavailableGit()
        provider.refreshStatus()
        val status = provider.fileStatus.value
        if (status.isEmpty()) return McpToolResult("Working tree clean.")
        return McpToolResult(
            status.joinToString("\n") { s ->
                "${statusChar(s.indexStatus)}${statusChar(s.workTreeStatus)} ${s.path}"
            },
        )
    }

    private fun statusChar(type: ai.rever.boss.plugin.api.GitFileStatusTypeData?): String =
        when (type) {
            null -> " "
            ai.rever.boss.plugin.api.GitFileStatusTypeData.MODIFIED -> "M"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.ADDED -> "A"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.DELETED -> "D"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.RENAMED -> "R"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.COPIED -> "C"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.UNTRACKED -> "??"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.IGNORED -> "!!"
            ai.rever.boss.plugin.api.GitFileStatusTypeData.UNMERGED -> "U"
        }

    private fun unavailableGit(): McpToolResult =
        McpToolResult("Git is unavailable: no project open or the host predates GitDataProvider.", isError = true)

    private fun unavailable(): McpToolResult =
        McpToolResult("Project search is unavailable: the host predates the ProjectSearchProvider implementation.", isError = true)

    private fun op(
        label: String,
        result: ai.rever.boss.plugin.api.GitOperationResultData?,
    ): McpToolResult {
        return when (result) {
            null -> unavailableGit()
            is ai.rever.boss.plugin.api.GitOperationResultData.Success ->
                McpToolResult("$label${result.message?.let { ": $it" } ?: ""}")
            is ai.rever.boss.plugin.api.GitOperationResultData.Error ->
                McpToolResult("$label failed: ${result.message}", isError = true)
        }
    }

    /**
     * Renders EVERY file of a multi-file diff.
     *
     * The ref tools used to hand a single value to [diffText], which was the first
     * file of a commit presented as the whole commit - with nothing to tell the
     * caller the rest existed. An agent reading it drew conclusions from one file.
     */
    private fun diffTexts(diffs: List<ai.rever.boss.plugin.api.GitDiffData>): McpToolResult {
        if (diffs.isEmpty()) return McpToolResult("No diff.")
        if (diffs.size == 1) return diffText(diffs.first())
        val body = diffs.joinToString("\n\n") { renderOne(it) }
        return McpToolResult(capDiff("${diffs.size} files changed\n\n$body"))
    }

    private fun diffText(diff: ai.rever.boss.plugin.api.GitDiffData?): McpToolResult {
        if (diff == null) return McpToolResult("No diff.")
        return McpToolResult(capDiff(renderOne(diff)))
    }

    private fun renderOne(diff: ai.rever.boss.plugin.api.GitDiffData): String {
        val header =
            "diff --git ${diff.path} ${diff.oldPath?.let { "$it " } ?: ""}" +
                "(+${diff.additions}/-${diff.deletions})"
        val raw = diff.rawUnified.ifBlank {
            diff.hunks.joinToString("\n") { h ->
                "@@ -${h.oldStart},${h.oldLines} +${h.newStart},${h.newLines} @@\n" +
                    h.lines.joinToString("\n") { l -> l.text }
            }
        }
        return "$header\n$raw"
    }

    /**
     * Bounds a diff result. The host generates every diff with whole-file context
     * (-U100000), so one file is the whole file and a multi-file commit is every whole
     * file - unbounded MB into a single tool result. The retired git-status plugin
     * capped its copy; this one did not until it was the live one.
     */
    private fun capDiff(text: String): String =
        if (text.length <= MAX_DIFF_CHARS) {
            text
        } else {
            text.take(MAX_DIFF_CHARS) + "\n… [truncated, ${text.length - MAX_DIFF_CHARS} more chars]"
        }

    private fun missing(name: String): McpToolResult = McpToolResult("Missing required argument: $name", isError = true)

    private fun McpToolArgs.path(): String? = string("path")

    private fun McpToolArgs.hash(): String? = string("hash")

    private companion object {
        const val MAX_RESULT_LINES = 100

        /** Ceiling on a single diff tool result; whole-file-context diffs are otherwise unbounded. */
        const val MAX_DIFF_CHARS = 64_000

        const val MAX_ERROR_LINES = 10

        const val PATH_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path (repo-relative or absolute)."}},"required":["path"]}"""
        const val REF_SCHEMA =
            """{"type":"object","properties":{"ref":{"type":"string","description":"Commit hash, branch name, or tag to checkout."}},"required":["ref"]}"""
        const val HASH_SCHEMA =
            """{"type":"object","properties":{"hash":{"type":"string","description":"Hash of the commit."}},"required":["hash"]}"""
        const val LIMIT_SCHEMA =
            """{"type":"object","properties":{"limit":{"type":"integer","description":"Max commits to return (default 30)."}}}"""
        const val DIFF_FILE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path (repo-relative or absolute)."},"staged":{"type":"boolean","description":"Diff the index instead of the working tree (default false)."}},"required":["path"]}"""
        const val STAGED_SCHEMA =
            """{"type":"object","properties":{"staged":{"type":"boolean","description":"List the index instead of the working tree (default false)."}}}"""
        const val DIFF_REF_SCHEMA =
            """{"type":"object","properties":{"ref":{"type":"string","description":"Commit hash or ref."},"path":{"type":"string","description":"Optional single file to restrict to."}},"required":["ref"]}"""
        const val DIFF_BETWEEN_SCHEMA =
            """{"type":"object","properties":{"from":{"type":"string","description":"Source ref."},"to":{"type":"string","description":"Target ref."},"path":{"type":"string","description":"Optional single file to restrict to."}},"required":["from","to"]}"""
        const val SEARCH_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Literal text, or a regular expression when isRegex=true."},"pathPattern":{"type":"string","description":"Optional glob filter on the project-relative path (e.g. **/*.kt)."},"isRegex":{"type":"boolean","description":"Treat query as a regular expression (default false)."},"caseSensitive":{"type":"boolean","description":"Case-sensitive matching (default false)."},"wholeWord":{"type":"boolean","description":"Whole-word match only (default false)."},"maxResults":{"type":"integer","description":"Hard cap on returned matches (default 100)."}},"required":["query"]}"""
        const val REPLACE_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Literal text, or a regular expression when isRegex=true."},"replacement":{"type":"string","description":"Replacement text; ${'$'}1..${'$'}9 capture references for regex queries."},"files":{"type":"string","description":"Comma-separated file paths to touch (project-relative, or absolute inside the project). Paths outside the project are refused. Never empty."},"isRegex":{"type":"boolean","description":"Treat query as a regular expression (default false)."},"caseSensitive":{"type":"boolean","description":"Case-sensitive matching (default false)."},"wholeWord":{"type":"boolean","description":"Whole-word match only (default false)."},"dryRun":{"type":"boolean","description":"Count without writing (default true)."}},"required":["query","replacement","files"]}"""
    }
}