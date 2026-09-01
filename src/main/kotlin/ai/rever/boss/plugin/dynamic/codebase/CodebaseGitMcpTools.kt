package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.ProjectSearchProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The git / project-search MCP tools absorbed from the retired
 * git-status, git-log and search-replace plugins (P7).
 *
 * Tool names and schemas are carried over verbatim so the move is transparent
 * to agents that already know them. RBAC is deliberate, in three tiers:
 * - read-only tools (git_status, git_log, git_diff*, project_search) carry no
 *   permission and are open to every local agent session;
 * - every tool that mutates git state (stage/unstage/discard/checkout/
 *   cherry-pick/revert) requires [GIT_WRITE]. An empty requirement list is NOT
 *   a neutral default here: the host's MCP registry exposes such a tool to
 *   every session, including one where no user is signed in, so the
 *   irreversible git_discard must sit behind a grant, exactly like
 *   project_replace sits behind project.replace;
 * - project_replace (writing file contents) requires its own "project.replace".
 * Admins bypass all of the above.
 *
 * Registered alongside the existing codebase_* tools in
 * [CodebaseDynamicPlugin.register]; auto-removed on disable/unload.
 */
internal class CodebaseGitMcpToolProvider(
    override val providerId: String,
    /**
     * Resolved per call, never captured at registration - the same reasoning
     * the AI gateway is resolved lazily for. Plugin load order is not
     * guaranteed, so a host that registers its GitDataProvider after this
     * plugin would otherwise leave all 13 git tools answering "Git is
     * unavailable" for the rest of the session.
     */
    private val git: () -> GitDataProvider?,
    private val search: () -> ProjectSearchProvider?,
) : McpToolProvider {

    private val gitProvider: GitDataProvider? get() = git()

    private val searchProvider: ProjectSearchProvider? get() = search()

    override fun tools(): List<McpToolDefinition> = listOf(
        // ---- from git-status ----
        McpToolDefinition(
            name = "git_status",
            description = "Show the working-tree status (staged, unstaged, and untracked files) of " +
                "the current BOSS project, in a git-porcelain-style format (XY path).",
            readOnly = true,
            handler = McpToolHandler { status() },
        ),
        McpToolDefinition.withRbac(
            name = "git_stage",
            description = "Stage a file for commit in the current project.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Staged", gitProvider?.stage(path))
            },
        ),
        McpToolDefinition.withRbac(
            name = "git_unstage",
            description = "Unstage a previously staged file in the current project.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Unstaged", gitProvider?.unstage(path))
            },
        ),
        McpToolDefinition.withRbac(
            name = "git_stage_all",
            description = "Stage all changed files in the current project.",
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { op("Staged all", gitProvider?.stageAll()) },
        ),
        McpToolDefinition.withRbac(
            name = "git_unstage_all",
            description = "Unstage all staged files in the current project.",
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { op("Unstaged all", gitProvider?.unstageAll()) },
        ),
        McpToolDefinition.withRbac(
            name = "git_discard",
            description = "Discard working-tree changes to a file (irreversible). Use with care.",
            inputSchema = PATH_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { args ->
                val path = args.path() ?: return@McpToolHandler missing("path")
                op("Discarded", gitProvider?.discardChanges(path))
            },
        ),
        McpToolDefinition.withRbac(
            name = "git_checkout",
            description = "Checkout a commit, branch, or tag in the current project.",
            inputSchema = REF_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { args ->
                val ref = args.string("ref")
                    ?: return@McpToolHandler missing("ref")
                if (!GitBranchModel.isSafeRef(ref)) return@McpToolHandler unsafeRef(ref)
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
                else
                    McpToolResult(
                        capDiff(
                            names.joinToString("\n") {
                                "${(it.indexStatus ?: it.workTreeStatus)?.name ?: "?"} ${it.path}"
                            },
                        ),
                    )
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
                if (!GitBranchModel.isSafeRef(ref)) return@McpToolHandler unsafeRef(ref)
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
                if (!GitBranchModel.isSafeRef(from)) return@McpToolHandler unsafeRef(from)
                val to = args.string("to")
                    ?: return@McpToolHandler missing("to")
                if (!GitBranchModel.isSafeRef(to)) return@McpToolHandler unsafeRef(to)
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
                val provider = gitProvider ?: return@McpToolHandler unavailableGit()
                // Clamped, not taken raw. List.take(-1) THROWS, so a model
                // guessing limit=-1 was the one bad argument here that escaped
                // as an exception instead of a clean isError result - and
                // limit=100000 is one call away from a very large result.
                val limit = (args.int("limit") ?: DEFAULT_LOG_LIMIT).coerceIn(1, MAX_LOG_LIMIT)
                val log = awaitFresh(provider.commitLog) { provider.refreshLog(limit) }.take(limit)
                if (log.isEmpty()) McpToolResult("No commits.")
                else McpToolResult(log.joinToString("\n") { "${it.shortHash} ${it.subject} (${it.author})" })
            },
        ),
        McpToolDefinition.withRbac(
            name = "git_cherry_pick",
            description = "Cherry-pick a commit onto the current branch of the current project.",
            inputSchema = HASH_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
            handler = McpToolHandler { args ->
                val hash = args.hash()
                if (hash == null) missing("hash") else op("Cherry-picked $hash", gitProvider?.cherryPick(hash))
            },
        ),
        McpToolDefinition.withRbac(
            name = "git_revert",
            description = "Revert a commit in the current project (creates a new revert commit).",
            inputSchema = HASH_SCHEMA,
            readOnly = false,
            requiredPermissions = listOf(GIT_WRITE),
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
            requiredPermissions = listOf(PROJECT_REPLACE),
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
                excludePattern = args.string("excludePattern"),
                isRegex = args.boolean("isRegex") ?: false,
                caseSensitive = args.boolean("caseSensitive") ?: false,
                wholeWord = args.boolean("wholeWord") ?: false,
                // Clamped for the same reason as git_log's limit: maxResults =
                // 1_000_000 makes the host scan the entire project to render at
                // most MAX_RESULT_LINES lines.
                maxResults = (args.int("maxResults") ?: DEFAULT_SEARCH_RESULTS).coerceIn(1, MAX_SEARCH_RESULTS),
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
            args.string("files")?.let(::parseFiles)
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

    /**
     * `files` arrives either as a JSON array of path strings (preferred: a path
     * may legally contain a comma, which the flat form cannot express) or as the
     * flat comma/newline-separated form. The array form is parsed by a small
     * strict scanner (the plugin's classpath carries no JSON library); anything
     * the scanner does not recognize falls back to flat splitting.
     */
    private fun parseFiles(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // An explicit empty array is EMPTY, not unparseable: without this
            // the scanner returned null (its one element is empty), the flat
            // fallback split "[]" into the single path "[]", and the caller's
            // isEmpty() guard never fired - so the provider was handed a file
            // literally named "[]".
            if (trimmed.length == 2 || trimmed.substring(1, trimmed.length - 1).isBlank()) {
                return emptyList()
            }
            parseJsonStringArray(trimmed)?.let { return it }
        }
        return trimmed
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Strict scan of `["a", "b, c", ...]`: double-quoted elements with
     * proper JSON unescaping, commas only between elements. Returns null
     * for anything else (nesting, bare tokens, unbalanced quotes, unknown
     * escape) so the caller falls back to the flat form rather than
     * guessing at a partial parse.
     *
     * Unescaping matters for the main use case: the array form is the
     * recommended carrier for awkward paths, and `["C:\\Users\\x.kt"]`
     * must come back as `C:\Users\x.kt`, not with doubled backslashes.
     */
    private fun parseJsonStringArray(text: String): List<String>? {
        val body = text.substring(1, text.length - 1)
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inString && c == '\\' -> {
                    i++
                    if (i >= body.length) return null
                    when (val esc = body[i]) {
                        '"' -> current.append('"')
                        '\\' -> current.append('\\')
                        '/' -> current.append('/')
                        'n' -> current.append('\n')
                        't' -> current.append('\t')
                        'r' -> current.append('\r')
                        'b' -> current.append('\b')
                        'f' -> current.append(0x0C.toChar())
                        'u' -> {
                            if (i + 4 >= body.length) return null
                            val code = body.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                            current.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                c == '"' -> inString = !inString
                c == ',' && !inString -> {
                    items += current.toString()
                    current.clear()
                }
                !inString && c.isWhitespace() -> Unit
                inString -> current.append(c)
                else -> return null
            }
            i++
        }
        if (inString) return null
        items += current.toString()
        // No trim: whitespace BETWEEN elements is skipped by the scanner
        // above (`!inString && c.isWhitespace()`), so anything left here was
        // inside the quotes and is part of the path. The array form exists to
        // carry awkward paths faithfully; trimming here undid that.
        return if (items.all { it.isNotEmpty() }) items else null
    }

    /**
     * Runs [refresh] and then returns the first value of [flow] that differs from
     * its pre-refresh snapshot, waiting at most [FLOW_SETTLE_MS]. Falls back to the
     * latest value if no new emission arrives (a no-op refresh emits nothing).
     *
     * The provider refreshes asynchronously - and over IPC when this plugin runs
     * out-of-process - so sampling `flow.value` right after `refreshStatus()` /
     * `refreshLog()` returns the state from BEFORE the refresh. An agent calling
     * `git_status` right after `git_stage` would get the pre-stage tree and feed a
     * wrong premise into its next step. In-process the host publishes before
     * `refresh*()` returns, so the wait resolves immediately. When the refresh is a
     * no-op the StateFlow conflates (no emission) and the wait times out, falling
     * back to the latest value - which is already correct in that case.
     */
    private suspend fun <T : Any> awaitFresh(
        flow: StateFlow<T>,
        refresh: suspend () -> Unit,
    ): T {
        val before = flow.value
        refresh()
        return withTimeoutOrNull(FLOW_SETTLE_MS) { flow.first { it != before } }
            ?: flow.value
    }

    private suspend fun status(): McpToolResult {
        val provider = gitProvider ?: return unavailableGit()
        val status = awaitFresh(provider.fileStatus) { provider.refreshStatus() }
        if (status.isEmpty()) return McpToolResult("Working tree clean.")
        return McpToolResult(status.joinToString("\n") { s -> "${statusCell(s)} ${s.path}" })
    }

    /**
     * The two-character XY cell the tool description promises.
     *
     * [statusChar] returns ONE character per side, deliberately: git emits
     * `??` and `!!` as the whole cell (index AND worktree), not as a worktree
     * token. Returning "??" from the worktree side and concatenating it after
     * the index side produced a three-column ` ??` that no agent slicing
     * fixed columns can parse.
     */
    internal fun statusCell(s: GitFileStatusData): String {
        val untrackedOrIgnored =
            listOf(s.indexStatus, s.workTreeStatus).firstOrNull {
                it == GitFileStatusTypeData.UNTRACKED ||
                    it == GitFileStatusTypeData.IGNORED
            }
        return when (untrackedOrIgnored) {
            GitFileStatusTypeData.UNTRACKED -> "??"
            GitFileStatusTypeData.IGNORED -> "!!"
            else -> "${statusChar(s.indexStatus)}${statusChar(s.workTreeStatus)}"
        }
    }

    private fun statusChar(type: GitFileStatusTypeData?): String =
        when (type) {
            null -> " "
            GitFileStatusTypeData.MODIFIED -> "M"
            GitFileStatusTypeData.ADDED -> "A"
            GitFileStatusTypeData.DELETED -> "D"
            GitFileStatusTypeData.RENAMED -> "R"
            GitFileStatusTypeData.COPIED -> "C"
            GitFileStatusTypeData.UNTRACKED -> "?"
            GitFileStatusTypeData.IGNORED -> "!"
            GitFileStatusTypeData.UNMERGED -> "U"
        }

    private fun unavailableGit(): McpToolResult =
        McpToolResult("Git is unavailable: no project open or the host predates GitDataProvider.", isError = true)

    private fun unavailable(): McpToolResult =
        McpToolResult("Project search is unavailable: the host predates the ProjectSearchProvider implementation.", isError = true)

    private fun op(
        label: String,
        result: GitOperationResultData?,
    ): McpToolResult {
        return when (result) {
            null -> unavailableGit()
            is GitOperationResultData.Success ->
                McpToolResult("$label${result.message?.let { ": $it" } ?: ""}")
            is GitOperationResultData.Error ->
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
    private fun diffTexts(diffs: List<GitDiffData>): McpToolResult {
        if (diffs.isEmpty()) return McpToolResult("No diff.")
        if (diffs.size == 1) return diffText(diffs.first())
        val body = diffs.joinToString("\n\n") { renderOne(it) }
        return McpToolResult(capDiff("${diffs.size} files changed\n\n$body"))
    }

    private fun diffText(diff: GitDiffData?): McpToolResult {
        if (diff == null) return McpToolResult("No diff.")
        return McpToolResult(capDiff(renderOne(diff)))
    }

    private fun renderOne(diff: GitDiffData): String {
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

    /**
     * LLM-supplied refs cross the plugin API, just like the graph picker's
     * branch name: the UI path checks [GitBranchModel.isSafeRef] precisely
     * because of that, and the MCP path must too. A leading dash would
     * become a git flag, and control characters do not survive IPC.
     */
    private fun unsafeRef(ref: String): McpToolResult =
        McpToolResult("Failed: \"$ref\" is not a usable ref (no blank, leading dash, or control characters).", isError = true)

    private fun McpToolArgs.path(): String? = string("path")

    private fun McpToolArgs.hash(): String? = string("hash")

    private companion object {
        const val MAX_RESULT_LINES = 100

        const val DEFAULT_LOG_LIMIT = 30

        /** Ceiling on git_log's `limit`; also stops List.take from seeing a negative. */
        const val MAX_LOG_LIMIT = 500

        const val DEFAULT_SEARCH_RESULTS = 100

        /** Ceiling on project_search's `maxResults`, so the host cannot be asked to scan everything. */
        const val MAX_SEARCH_RESULTS = 2_000

        /** Permission required by every git tool that mutates repository state. */
        const val GIT_WRITE = "git.write"

        /** Permission required to write file CONTENTS, as opposed to git state. */
        const val PROJECT_REPLACE = "project.replace"

        /**
         * How long [awaitFresh] waits, after calling a `refresh*()` method, for
         * the provider's flow to move off its pre-refresh value.
         */
        const val FLOW_SETTLE_MS = 2_000L

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
            """{"type":"object","properties":{"limit":{"type":"integer","description":"Max commits to return (default 30, clamped to 1..500)."}}}"""
        const val DIFF_FILE_SCHEMA =
            """{"type":"object","properties":{"path":{"type":"string","description":"File path (repo-relative or absolute)."},"staged":{"type":"boolean","description":"Diff the index instead of the working tree (default false)."}},"required":["path"]}"""
        const val STAGED_SCHEMA =
            """{"type":"object","properties":{"staged":{"type":"boolean","description":"List the index instead of the working tree (default false)."}}}"""
        const val DIFF_REF_SCHEMA =
            """{"type":"object","properties":{"ref":{"type":"string","description":"Commit hash or ref."},"path":{"type":"string","description":"Optional single file to restrict to."}},"required":["ref"]}"""
        const val DIFF_BETWEEN_SCHEMA =
            """{"type":"object","properties":{"from":{"type":"string","description":"Source ref."},"to":{"type":"string","description":"Target ref."},"path":{"type":"string","description":"Optional single file to restrict to."}},"required":["from","to"]}"""
        const val SEARCH_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Literal text, or a regular expression when isRegex=true."},"pathPattern":{"type":"string","description":"Optional glob filter on the project-relative path (e.g. **/*.kt)."},"excludePattern":{"type":"string","description":"Optional glob filter for paths to EXCLUDE (e.g. **/build/**). Applied inside the engine, so excluded matches do not consume the maxResults cap."},"isRegex":{"type":"boolean","description":"Treat query as a regular expression (default false)."},"caseSensitive":{"type":"boolean","description":"Case-sensitive matching (default false)."},"wholeWord":{"type":"boolean","description":"Whole-word match only (default false)."},"maxResults":{"type":"integer","description":"Hard cap on returned matches (default 100, clamped to 1..2000)."}},"required":["query"]}"""
        const val REPLACE_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Literal text, or a regular expression when isRegex=true."},"replacement":{"type":"string","description":"Replacement text; ${'$'}1..${'$'}9 capture references for regex queries."},"files":{"type":"string","description":"Comma-separated file paths to touch, or a JSON array of path strings - use the array form when a path contains a comma. Project-relative, or absolute inside the project. Paths outside the project are refused. Never empty."},"isRegex":{"type":"boolean","description":"Treat query as a regular expression (default false)."},"caseSensitive":{"type":"boolean","description":"Case-sensitive matching (default false)."},"wholeWord":{"type":"boolean","description":"Whole-word match only (default false)."},"dryRun":{"type":"boolean","description":"Count without writing (default true)."}},"required":["query","replacement","files"]}"""
    }
}
