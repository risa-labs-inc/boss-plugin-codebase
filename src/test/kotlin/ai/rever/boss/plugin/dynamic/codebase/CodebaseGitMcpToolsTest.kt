package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileMatch
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData as T
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.ProjectSearchProvider
import ai.rever.boss.plugin.api.ReplaceSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * The parts of [CodebaseGitMcpToolProvider] that an agent can actually hit,
 * pinned against fakes: the RBAC tier on every tool, argument validation,
 * diff result truncation, multi-file diff rendering, and the two accepted
 * spellings of the `files` argument.
 */
class CodebaseGitMcpToolsTest {

    private val git = FakeGitProvider()
    private val search = FakeSearchProvider()
    private val tools =
        CodebaseGitMcpToolProvider("codebase", { git }, { search }, flowSettleMs = SETTLE_MS).tools()

    private fun tool(name: String) = tools.first { it.name == name }

    private companion object {
        /**
         * Short on purpose. The real value is 2 s; at that length every call
         * whose refresh publishes nothing burned two seconds of wall clock,
         * which was most of this class's runtime.
         */
        const val SETTLE_MS = 60L
    }

    private fun invoke(name: String, args: Map<String, Any?>): McpToolResult =
        runBlocking { tool(name).handler.call(McpToolArgs(args)) }

    // ---- RBAC tiers --------------------------------------------------------

    @Test
    fun `mutating git tools sit behind git write, reads stay open`() {
        listOf(
            "git_stage", "git_unstage", "git_stage_all", "git_unstage_all",
            "git_discard", "git_checkout", "git_cherry_pick", "git_revert",
        ).forEach { name ->
            assertEquals(listOf("git.write"), tool(name).requiredPermissions, name)
            assertFalse(tool(name).readOnly, name)
        }
        listOf(
            "git_status", "git_log", "git_diff", "git_diff_all",
            "git_diff_ref", "git_diff_between", "project_search",
        ).forEach { name ->
            assertTrue(tool(name).requiredPermissions.isEmpty(), name)
            assertTrue(tool(name).readOnly, name)
        }
        assertEquals(listOf("project.replace"), tool("project_replace").requiredPermissions)
        assertFalse(tool("project_replace").readOnly)
    }

    // ---- argument validation ------------------------------------------------

    @Test
    fun `missing required arguments are clean errors, not exceptions`() {
        listOf(
            "git_diff" to "path",
            "git_diff_ref" to "ref",
            "git_diff_between" to "from",
            "git_discard" to "path",
            "project_search" to "query",
            "project_replace" to "query",
        ).forEach { (name, arg) ->
            val result = invoke(name, emptyMap())
            assertTrue(result.isError, name)
            assertTrue(result.text.contains(arg), "$name said: ${result.text}")
        }
    }

    @Test
    fun `a blank files list is refused before it reaches the provider`() {
        val result =
            invoke(
                "project_replace",
                mapOf("query" to "a", "replacement" to "b", "files" to " ,  "),
            )
        assertTrue(result.isError)
        assertTrue(result.text.contains("files"), result.text)
        assertTrue(search.replaceCalls.isEmpty())
    }

    // ---- diff rendering ------------------------------------------------------

    @Test
    fun `an oversized diff result is truncated, not returned whole`() {
        git.diffs["big"] = listOf(GitDiffData(path = "big", rawUnified = "x".repeat(100_000)))
        val result = invoke("git_diff", mapOf("path" to "big"))
        assertTrue(result.text.contains("truncated"), result.text.take(200))
        assertTrue(result.text.length < 70_000)
    }

    @Test
    fun `a multi-file diff names how many files the answer holds`() {
        // The one-file-of-N rendering already bit an agent once: the result
        // must announce the set it is showing.
        git.diffs["abc123"] =
            listOf(GitDiffData(path = "a.kt", rawUnified = "+one"), GitDiffData(path = "b.kt", rawUnified = "+two"))
        val result = invoke("git_diff_ref", mapOf("ref" to "abc123"))
        assertTrue(result.text.startsWith("2 files changed"), result.text.take(40))
        assertTrue(result.text.contains("a.kt"))
        assertTrue(result.text.contains("b.kt"))
    }

    @Test
    fun `git log without a provider reports unavailability, not a crash`() {
        val none = CodebaseGitMcpToolProvider("codebase", { null }, { null }).tools()
        val result =
            runBlocking {
                none.first { it.name == "git_log" }.handler.call(McpToolArgs(emptyMap()))
            }
        assertTrue(result.isError)
        assertTrue(result.text.contains("unavailable"), result.text)
    }

    // ---- files argument spellings ---------------------------------------------

    @Test
    fun `the files argument accepts a JSON array so paths may contain commas`() {
        val result =
            invoke(
                "project_replace",
                mapOf("query" to "a", "replacement" to "b", "files" to """["a.kt", "b, c.kt"]"""),
            )
        assertFalse(result.isError, result.text)
        assertEquals(listOf("a.kt", "b, c.kt"), search.replaceCalls.single().files)
    }

    @Test
    fun `a leading-dash ref is refused before it reaches the provider`() {
        // git_checkout / git_diff_ref / git_diff_between take an LLM-supplied
        // ref across the plugin API; a leading dash would become a git flag.
        listOf(
            "git_diff_ref" to mapOf("ref" to "-n"),
            "git_diff_between" to mapOf("from" to "main", "to" to "--all"),
            "git_checkout" to mapOf("ref" to "-b"),
        ).forEach { (name, args) ->
            val result = invoke(name, args)
            assertTrue(result.isError, name)
            assertTrue(result.text.contains("not a usable ref"), "$name said: ${result.text}")
        }
    }

    @Test
    fun `a JSON array unescapes backslashes so Windows paths survive`() {
        // The array form is the recommended carrier for awkward paths; the
        // scanner used to append the backslash AND the escaped character, so
        // every escape came back doubled and " stayed ".
        invoke(
            "project_replace",
            mapOf(
                "query" to "a",
                "replacement" to "b",
                "files" to "[\"C:\\\\Users\\\\me\\\\x.kt\", \"a\\\"b.kt\"]",
            ),
        )
        assertEquals(listOf("C:\\Users\\me\\x.kt", "a\"b.kt"), search.replaceCalls.single().files)
    }

    @Test
    fun `the files argument still accepts the flat comma form`() {
        invoke(
            "project_replace",
            mapOf("query" to "a", "replacement" to "b", "files" to "a.kt, b.kt\n c.kt"),
        )
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), search.replaceCalls.single().files)
    }

    @Test
    fun `a malformed array falls back to the flat form rather than guessing`() {
        // `["a.kt` - unbalanced. The scanner must not half-parse it into one
        // invented path; flat splitting of the whole string is the fallback.
        val malformed = "[\"a.kt"
        invoke(
            "project_replace",
            mapOf("query" to "a", "replacement" to "b", "files" to malformed),
        )
        assertEquals(listOf(malformed), search.replaceCalls.single().files)
    }

    @Test
    fun `git status renders every status type with its porcelain char`() {
        // statusChar is an exhaustive when; pinning all eight arms (plus the
        // empty index/work-tree cells) keeps a renamed enum value from
        // silently rendering as a blank.
        git.status.value = listOf(
            GitFileStatusData("m.kt", T.MODIFIED, null, isStaged = true, isUnstaged = false),
            GitFileStatusData("a.kt", null, T.ADDED, isStaged = false, isUnstaged = true),
            GitFileStatusData("d.kt", T.DELETED, null, isStaged = true, isUnstaged = false),
            GitFileStatusData("r.kt", T.RENAMED, null, isStaged = true, isUnstaged = false),
            GitFileStatusData("c.kt", T.COPIED, null, isStaged = true, isUnstaged = false),
            GitFileStatusData("u.kt", null, T.UNTRACKED, isStaged = false, isUnstaged = true),
            GitFileStatusData("i.kt", null, T.IGNORED, isStaged = false, isUnstaged = true),
            GitFileStatusData("x.kt", T.UNMERGED, T.UNMERGED, isStaged = false, isUnstaged = true),
        )
        val result = invoke("git_status", emptyMap())
        assertFalse(result.isError, result.text)
        // Whole LINES, not `contains`: the tool advertises porcelain "XY path",
        // and contains("?? u.kt") passes just as happily on a misaligned
        // " ?? u.kt" - which is what an untracked row rendered as while
        // statusChar returned two characters for one cell.
        assertEquals(
            listOf("M  m.kt", " A a.kt", "D  d.kt", "R  r.kt", "C  c.kt", "?? u.kt", "!! i.kt", "UU x.kt"),
            result.text.lines(),
        )
        assertTrue(
            result.text.lines().all { it.length > 3 && it[2] == ' ' },
            "every row must be exactly two status columns then a space:\n${result.text}",
        )
    }

    @Test
    fun `an untracked file staged alongside an index change keeps two columns`() {
        // git reports ?? as the whole cell, never as a work-tree token bolted
        // onto an index one.
        git.status.value = listOf(
            GitFileStatusData("u.kt", T.UNTRACKED, T.UNTRACKED, isStaged = false, isUnstaged = true),
        )
        assertEquals(listOf("?? u.kt"), invoke("git_status", emptyMap()).text.lines())
    }

    // ---- argument clamping -----------------------------------------------

    @Test
    fun `a negative git log limit is a clean error path, not an exception`() {
        // List.take(-1) THROWS. Every other bad argument here returns an
        // isError result, so a model guessing limit=-1 must not be the one
        // input that escapes as an exception.
        val result = invoke("git_log", mapOf("limit" to -1))
        assertFalse(result.isError, result.text)
        assertEquals(1, git.logLimits.last(), "a negative limit must clamp to 1")
    }

    @Test
    fun `an enormous git log limit is clamped rather than passed through`() {
        invoke("git_log", mapOf("limit" to 100_000))
        assertEquals(500, git.logLimits.last(), "the limit must be bounded above")
    }

    @Test
    fun `an enormous project search maxResults is clamped`() {
        invoke("project_search", mapOf("query" to "x", "maxResults" to 1_000_000))
        assertEquals(2_000, search.searchCalls.last(), "maxResults must be bounded above")
    }

    @Test
    fun `an explicit empty JSON array is refused, not read as a file named brackets`() {
        // The scanner returned null for "[]" (its single element is empty), the
        // flat fallback split it into the one path "[]", and the caller's
        // isEmpty() guard never fired - so the provider was handed a file
        // literally called "[]".
        val result =
            invoke(
                "project_replace",
                mapOf("query" to "a", "replacement" to "b", "files" to "[]"),
            )
        assertTrue(result.isError, result.text)
        assertTrue("must not be empty" in result.text, result.text)
        assertTrue(search.replaceCalls.isEmpty(), "nothing may reach the provider")
    }

    // ---- awaitFresh --------------------------------------------------------

    @Test
    fun `git status reports the state the refresh published, not the one before it`() {
        // The whole point of awaitFresh: the provider refreshes asynchronously
        // (over IPC out-of-process), so reading fileStatus.value straight after
        // refreshStatus() returns the PRE-refresh tree. An agent calling
        // git_status right after git_stage would act on a stale premise.
        git.status.value = listOf(
            GitFileStatusData("old.kt", null, T.MODIFIED, isStaged = false, isUnstaged = true),
        )
        git.pendingStatus = listOf(
            GitFileStatusData("new.kt", T.ADDED, null, isStaged = true, isUnstaged = false),
        )

        val result = invoke("git_status", emptyMap())

        assertEquals(listOf("A  new.kt"), result.text.lines())
    }

    @Test
    fun `a refresh that publishes nothing falls back to the latest value`() {
        // A no-op refresh emits nothing at all (StateFlow conflates an
        // identical value), so the wait must time out onto what is already
        // there rather than hanging or reporting empty.
        git.status.value = listOf(
            GitFileStatusData("same.kt", null, T.MODIFIED, isStaged = false, isUnstaged = true),
        )
        git.pendingStatus = null

        val result = invoke("git_status", emptyMap())

        assertEquals(listOf(" M same.kt"), result.text.lines())
    }

    @Test
    fun `git log reports the commits the refresh published`() {
        git.pendingLog = listOf(
            GitCommitInfoData(
                hash = "abcdef1234",
                shortHash = "abcdef1",
                subject = "a subject",
                author = "someone",
                authorEmail = "s@example.com",
                date = 0L,
                refs = emptyList(),
            ),
        )

        val result = invoke("git_log", emptyMap())

        assertFalse(result.isError, result.text)
        assertEquals(listOf("abcdef1 a subject (someone)"), result.text.lines())
    }

    // ---- fakes ---------------------------------------------------------------

    private class FakeGitProvider : GitDataProvider {
        val status = MutableStateFlow(emptyList<GitFileStatusData>())
        val diffs = mutableMapOf<String, List<GitDiffData>>()
        val logLimits = mutableListOf<Int>()
        val log = MutableStateFlow(emptyList<GitCommitInfoData>())

        /**
         * What [refreshStatus] will publish, or null to publish nothing.
         *
         * The real provider re-reads the work tree and PUBLISHES on its flow;
         * a fake whose refresh does nothing never lets awaitFresh resolve, so
         * every call paid the full settle timeout and the behaviour awaitFresh
         * exists for was never exercised.
         */
        var pendingStatus: List<GitFileStatusData>? = null
        var pendingLog: List<GitCommitInfoData>? = null

        override val fileStatus: StateFlow<List<GitFileStatusData>> = status
        override val commitLog: StateFlow<List<GitCommitInfoData>> = log
        override val isGitRepository: StateFlow<Boolean> = MutableStateFlow(true)
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun refreshStatus() {
            pendingStatus?.let { status.value = it }
        }

        override suspend fun refreshLog(limit: Int) {
            logLimits += limit
            pendingLog?.let { log.value = it }
        }

        override suspend fun stage(filePath: String) = GitOperationResultData.Success()

        override suspend fun unstage(filePath: String) = GitOperationResultData.Success()

        override suspend fun stageAll() = GitOperationResultData.Success()

        override suspend fun unstageAll() = GitOperationResultData.Success()

        override suspend fun discardChanges(filePath: String) = GitOperationResultData.Success()

        override suspend fun cherryPick(commitHash: String) = GitOperationResultData.Success()

        override suspend fun revert(commitHash: String) = GitOperationResultData.Success()

        override suspend fun checkout(ref: String) = GitOperationResultData.Success()

        override fun getCurrentProjectPath(): String? = "/repo"

        override fun openFile(filePath: String, windowId: String) {}

        override suspend fun diffFile(path: String, staged: Boolean): List<GitDiffData> = diffs[path].orEmpty()

        override suspend fun diffRef(ref: String, path: String?): List<GitDiffData> = diffs[ref].orEmpty()

        override suspend fun diffNames(staged: Boolean): List<GitFileStatusData> =
            listOf(
                GitFileStatusData(
                    path = "a.kt",
                    indexStatus = T.MODIFIED,
                    workTreeStatus = null,
                    isStaged = true,
                    isUnstaged = false,
                ),
            )
    }

    private class ReplaceCall(
        val files: List<String>,
    )

    private class FakeSearchProvider : ProjectSearchProvider {
        val replaceCalls = mutableListOf<ReplaceCall>()
        val searchCalls = mutableListOf<Int>()

        override suspend fun searchInProject(
            query: String,
            pathPattern: String?,
            excludePattern: String?,
            isRegex: Boolean,
            caseSensitive: Boolean,
            wholeWord: Boolean,
            maxResults: Int,
        ): List<FileMatch> {
            searchCalls += maxResults
            return emptyList()
        }

        override suspend fun replaceInProject(
            query: String,
            replacement: String,
            files: List<String>,
            isRegex: Boolean,
            caseSensitive: Boolean,
            wholeWord: Boolean,
            dryRun: Boolean,
        ): ReplaceSummary {
            replaceCalls += ReplaceCall(files)
            return ReplaceSummary(filesReplaced = 0, totalReplacements = 0, dryRun = dryRun)
        }
    }
}
