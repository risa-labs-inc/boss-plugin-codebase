package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The path handling behind the SEARCH tab: turning the engine's
 * project-relative match paths into something the editor can open, and the
 * header's home-collapsed path.
 *
 * Exclude-glob semantics are NOT here: exclusion moved into the host engine
 * (ContentSearchService), which already compiled the include side, so the
 * cases live next to the one implementation - see ContentSearchServiceTest.
 */
class SearchPathTest {

    private val viewModels = mutableListOf<CodebaseSearchViewModel>()

    @AfterTest
    fun disposeAll() {
        // CodebaseSearchViewModel starts a CoroutineScope in its constructor;
        // without this, each vm() below leaks a scope for the JVM's lifetime.
        viewModels.forEach { it.dispose() }
        viewModels.clear()
    }

    private fun vm(root: String?): CodebaseSearchViewModel {
        val viewModel =
            CodebaseSearchViewModel(provider = null, splitViewOperations = null, getProjectPath = { root })
        viewModels += viewModel
        return viewModel
    }

    // ---- relative -> absolute --------------------------------------------

    @Test
    fun `a relative match path resolves against the project root`() {
        // FileMatch.path is project-relative; handing that to the editor is
        // what produced "file not found" on every result click.
        assertEquals("/w/proj/src/Foo.kt", vm("/w/proj").absolutePathOf("src/Foo.kt"))
    }

    @Test
    fun `a trailing separator on the project root does not double up`() {
        assertEquals("/w/proj/src/Foo.kt", vm("/w/proj/").absolutePathOf("src/Foo.kt"))
    }

    @Test
    fun `an already absolute path is left alone`() {
        assertEquals("/abs/Foo.kt", vm("/w/proj").absolutePathOf("/abs/Foo.kt"))
        assertEquals("C:\\abs\\Foo.kt", vm("/w/proj").absolutePathOf("C:\\abs\\Foo.kt"))
        assertEquals("D:/abs/Foo.kt", vm("/w/proj").absolutePathOf("D:/abs/Foo.kt"))
    }

    @Test
    fun `with no project the path is returned unchanged rather than corrupted`() {
        assertEquals("src/Foo.kt", vm(null).absolutePathOf("src/Foo.kt"))
        assertEquals("src/Foo.kt", vm("").absolutePathOf("src/Foo.kt"))
    }

    // ---- replace reporting ------------------------------------------------

    private fun summary(
        total: Int,
        files: Int,
        results: List<ai.rever.boss.plugin.api.FileReplaceResult>,
    ) = ai.rever.boss.plugin.api.ReplaceSummary(filesReplaced = files, totalReplacements = total, files = results)

    private fun fileResult(path: String, replacements: Int, error: String? = null) =
        ai.rever.boss.plugin.api.FileReplaceResult(path = path, replacements = replacements, error = error)

    @Test
    fun `a clean replace reports the totals`() {
        val msg = vm(null).describeReplacement(summary(4, 2, listOf(fileResult("a.kt", 2), fileResult("b.kt", 2))))
        assertEquals("Replaced 4 in 2 file(s)", msg)
    }

    @Test
    fun `a per-file failure is named with its reason`() {
        // "Replaced 0 in 0 file(s)" was all the UI said when every file was
        // skipped - the reason ("unsupported" from an editor plugin that
        // predates applyEdit) never reached the user.
        val msg = vm(null).describeReplacement(summary(0, 0, listOf(fileResult("src/a.kt", 0, "unsupported"))))
        assertTrue(msg.startsWith("Failed: a.kt - unsupported"), msg)
    }

    @Test
    fun `a partial replace reports both the totals and the first failure`() {
        val msg =
            vm(null).describeReplacement(
                summary(
                    2,
                    1,
                    listOf(fileResult("a.kt", 2), fileResult("b.kt", 0, "binary file"), fileResult("c.kt", 0, "file too large")),
                ),
            )
        assertTrue(msg.startsWith("Replaced 2 in 1 file(s). Failed: b.kt - binary file"), msg)
        assertTrue(msg.endsWith("(+1 more)"), msg)
    }

    @Test
    fun `a missing provider is reported as such`() {
        assertTrue(vm(null).describeReplacement(null).contains("no search provider"))
    }

    // ---- header path ------------------------------------------------------

    @Test
    fun `the header collapses the home prefix`() {
        val home = System.getProperty("user.home").trimEnd('/')
        assertEquals("~/src/app", collapseHome("$home/src/app"))
        assertEquals("~", collapseHome(home))
        assertEquals("/opt/elsewhere", collapseHome("/opt/elsewhere"))
    }

    @Test
    fun `a sibling of home is not mistaken for it`() {
        val home = System.getProperty("user.home").trimEnd('/')
        assertEquals("${home}2/app", collapseHome("${home}2/app"))
    }

    @Test
    fun `a backslash-joined home collapses on a Windows-style path`() {
        // These paths come from File.absolutePath, so on Windows both the home
        // directory and the project are backslash-joined and the '/'-only rule
        // never matched. The separator is a parameter so the case is testable
        // on any OS.
        val winHome = "C:\\Users\\me"
        assertEquals("~\\src\\app", collapseHome("$winHome\\src\\app", '\\', winHome))
        assertEquals("~", collapseHome(winHome, '\\', winHome))
        assertEquals(
            "C:\\Users\\meelsewhere\\app",
            collapseHome("C:\\Users\\meelsewhere\\app", '\\', winHome),
        )
    }
}
