package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileMatch
import ai.rever.boss.plugin.api.ProjectSearchProvider
import ai.rever.boss.plugin.api.ReplaceSummary
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the one tab that writes file contents.
 *
 * The dry run and the write are two calls separated by however long a
 * whole-project scan takes, with the search field live throughout - so the
 * thing worth pinning is that they always describe the SAME operation, and
 * that a scan finishing in between cannot let a second write start.
 */
class SearchViewModelTest {

    private val viewModels = mutableListOf<CodebaseSearchViewModel>()

    @AfterTest
    fun disposeAll() {
        viewModels.forEach { it.dispose() }
        viewModels.clear()
    }

    // ---- fake ----

    private class ReplaceCall(
        val query: String,
        val replacement: String,
        val files: List<String>,
        val isRegex: Boolean,
        val caseSensitive: Boolean,
        val wholeWord: Boolean,
        val dryRun: Boolean,
    )

    private class FakeSearch(
        var matches: List<FileMatch> = emptyList(),
    ) : ProjectSearchProvider {
        val searchQueries = mutableListOf<String>()
        val replaceCalls = mutableListOf<ReplaceCall>()

        /** When set, the next replaceInProject blocks until it completes. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun searchInProject(
            query: String,
            pathPattern: String?,
            excludePattern: String?,
            isRegex: Boolean,
            caseSensitive: Boolean,
            wholeWord: Boolean,
            maxResults: Int,
        ): List<FileMatch> {
            searchQueries += query
            return matches
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
            replaceCalls +=
                ReplaceCall(query, replacement, files, isRegex, caseSensitive, wholeWord, dryRun)
            gate?.await()
            return ReplaceSummary(filesReplaced = files.size, totalReplacements = 3, dryRun = dryRun)
        }
    }

    private fun match(path: String) = FileMatch(path, 1, 1, 3, "a line")

    private fun vm(search: FakeSearch): CodebaseSearchViewModel =
        CodebaseSearchViewModel(
            provider = search,
            splitViewOperations = null,
            getProjectPath = { "/repo" },
        ).also { viewModels += it }

    private fun awaitUntil(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** Search for [query] and wait for the results to land. */
    private fun search(vm: CodebaseSearchViewModel, query: String) {
        vm.setQuery(query)
        vm.runSearch()
        awaitUntil("results for \"$query\"") { vm.searched.value && !vm.busy.value }
    }

    // ---- the dry run and the write must describe one operation ----

    @Test
    fun `the write uses exactly the operation the dry run measured`() {
        val provider = FakeSearch(listOf(match("a.kt"), match("b.kt")))
        val vm = vm(provider)
        search(vm, "needle")
        vm.setReplacement("thread")
        vm.setSearchOption(SearchToggle.CASE, true)
        awaitUntil("the re-scan after the toggle") { !vm.busy.value }

        vm.previewReplacement()
        awaitUntil("the dry run") { vm.dryRun.value != null }
        vm.applyReplacement()
        awaitUntil("the write") { provider.replaceCalls.any { !it.dryRun } }

        val preview = provider.replaceCalls.first { it.dryRun }
        val write = provider.replaceCalls.first { !it.dryRun }
        assertEquals(preview.query, write.query)
        assertEquals(preview.replacement, write.replacement)
        assertEquals(preview.files, write.files)
        assertEquals(preview.isRegex, write.isRegex)
        assertEquals(preview.caseSensitive, write.caseSensitive)
        assertEquals(preview.wholeWord, write.wholeWord)
        assertEquals("needle", write.query)
        assertEquals(listOf("a.kt", "b.kt"), write.files)
    }

    @Test
    fun `results changing under an open sheet do not widen the write`() {
        // A re-scan does not retract the sheet - it has no reason to, the
        // query is unchanged - so the results tree can grow while the
        // confirmation is on screen. The write must still touch only the files
        // the sheet counted, never the ones that arrived afterwards.
        val provider = FakeSearch(listOf(match("a.kt")))
        val vm = vm(provider)
        search(vm, "needle")

        vm.previewReplacement()
        awaitUntil("the dry run") { vm.dryRun.value != null }

        // A new match appears and the auto-refresh picks it up.
        provider.matches = listOf(match("a.kt"), match("late.kt"))
        vm.runSearch()
        awaitUntil("the wider result set") { vm.results.value.size == 2 }
        assertNotNull(vm.dryRun.value, "the sheet is still up - this is the case under test")

        vm.applyReplacement()
        awaitUntil("the write") { provider.replaceCalls.any { !it.dryRun } }

        assertEquals(
            listOf("a.kt"),
            provider.replaceCalls.first { !it.dryRun }.files,
            "the write must match what the sheet measured, not the newer results",
        )
    }

    @Test
    fun `editing the query while a dry run is in flight retracts the sheet`() {
        // The window the snapshot exists for: the search field stays live for
        // the whole dry run. setQuery nulls _dryRun precisely to retract the
        // sheet, and the in-flight preview must not put it back describing an
        // operation the user has moved past.
        val provider = FakeSearch(listOf(match("a.kt")))
        val vm = vm(provider)
        search(vm, "needle")

        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        vm.previewReplacement()
        awaitUntil("the dry run to start") { provider.replaceCalls.any { it.dryRun } }

        vm.setQuery("something else")
        provider.gate = null
        gate.complete(Unit)

        awaitUntil("the preview to finish") { !vm.busy.value }
        assertNull(vm.dryRun.value, "a stale preview must not republish the sheet")
        assertTrue(provider.replaceCalls.none { !it.dryRun }, "and nothing may be written")
    }

    // ---- a scan must not release the write mutex ----

    @Test
    fun `a scan finishing mid-write cannot let a second write start`() {
        // These used to share one flag: execute() set and cleared it
        // unconditionally while the replace path used it as a mutex, so a scan
        // completing during a write cleared the mutex and a second click ran a
        // duplicate write.
        val provider = FakeSearch(listOf(match("a.kt")))
        val vm = vm(provider)
        search(vm, "needle")

        vm.previewReplacement()
        awaitUntil("the dry run") { vm.dryRun.value != null }

        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        vm.applyReplacement()
        awaitUntil("the write to start") { provider.replaceCalls.any { !it.dryRun } }

        // A scan runs and completes while the write is still in flight.
        vm.runSearch()
        awaitUntil("the scan to finish") { provider.searchQueries.size >= 2 }

        vm.applyReplacement()
        Thread.sleep(50)
        assertEquals(
            1,
            provider.replaceCalls.count { !it.dryRun },
            "the write mutex must survive a scan completing",
        )

        provider.gate = null
        gate.complete(Unit)
        awaitUntil("the write to finish") { vm.dryRun.value == null && !vm.busy.value }
    }

    @Test
    fun `busy stays true for the whole write, not just until a scan ends`() {
        val provider = FakeSearch(listOf(match("a.kt")))
        val vm = vm(provider)
        search(vm, "needle")
        vm.previewReplacement()
        awaitUntil("the dry run") { vm.dryRun.value != null }

        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        vm.applyReplacement()
        awaitUntil("busy") { vm.busy.value }
        vm.runSearch()
        awaitUntil("the scan to finish") { provider.searchQueries.size >= 2 }

        assertTrue(vm.busy.value, "busy must still report the in-flight write")
        provider.gate = null
        gate.complete(Unit)
        awaitUntil("idle") { !vm.busy.value }
    }

    // ---- scan semantics ----

    @Test
    fun `a capped result set refuses the replace before running a dry run`() {
        // The sheet disables Replace while capped, so measuring first buys a
        // whole-project scan and nothing else.
        val provider = FakeSearch((1..CodebaseSearchViewModel.MAX_RESULTS).map { match("f$it.kt") })
        val vm = vm(provider)
        search(vm, "needle")
        assertTrue(vm.capped.value, "this test needs a capped result set")

        vm.previewReplacement()
        Thread.sleep(50)

        assertTrue(provider.replaceCalls.isEmpty(), "no dry run may run while capped")
        assertNotNull(vm.message.value)
        assertTrue("narrow" in vm.message.value!!, "reported: ${vm.message.value}")
    }

    @Test
    fun `clearing mid-scan is not undone when the scan lands`() {
        val provider = FakeSearch(listOf(match("a.kt")))
        val vm = vm(provider)
        search(vm, "needle")
        assertTrue(vm.searched.value)

        vm.clear()

        assertEquals("", vm.query.value)
        Thread.sleep(80)
        assertTrue(vm.results.value.isEmpty(), "a scan landing after clear must not republish")
        assertTrue(!vm.searched.value, "nor resurrect the searched state")
    }
}
