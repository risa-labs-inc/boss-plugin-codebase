package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DirectoryPickerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the panel does with whatever the host's directory picker hands back.
 *
 * The macOS picker returns the directory WITH a trailing separator when nothing
 * inside it was selected, and that shape is the one that used to reach the host
 * as a project literally named "Unknown".
 */
class ProjectPickTest {

    /** Answers the picker callback synchronously with [result]. */
    private class FakePicker(private val result: String?) : DirectoryPickerProvider {
        var invocations = 0
        override fun pickDirectory(onResult: (String?) -> Unit) {
            invocations++
            onResult(result)
        }
    }

    private class Recorder {
        val selected = mutableListOf<Pair<String, String>>()
        val callback: (String, String) -> Unit = { name, path -> selected += name to path }
    }

    private fun viewModel(picker: DirectoryPickerProvider?, recorder: Recorder?) = CodebaseViewModel(
        fileSystemDataProvider = null,
        directoryPickerProvider = picker,
        splitViewOperations = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        getWindowId = { null },
        getProjectPath = { null },
        onSelectProject = recorder?.callback
    )

    @Test
    fun `a trailing separator still names the project after its directory`() {
        val recorder = Recorder()
        viewModel(FakePicker("${separator()}dev${separator()}BossTerm${separator()}"), recorder).pickDirectory()

        assertEquals(1, recorder.selected.size)
        val (name, path) = recorder.selected.single()
        assertEquals("BossTerm", name)
        // The trailing separator is stripped before the host sees it, so the path
        // matches what the recents list records for the same project.
        assertEquals("${separator()}dev${separator()}BossTerm", path)
    }

    @Test
    fun `a plain directory is passed through untouched`() {
        val recorder = Recorder()
        viewModel(FakePicker("${separator()}dev${separator()}Boss"), recorder).pickDirectory()

        assertEquals("Boss" to "${separator()}dev${separator()}Boss", recorder.selected.single())
    }

    @Test
    fun `a cancelled picker selects nothing`() {
        val recorder = Recorder()
        viewModel(FakePicker(null), recorder).pickDirectory()

        assertTrue(recorder.selected.isEmpty())
    }

    @Test
    fun `a picker that returns only separators selects nothing`() {
        val recorder = Recorder()
        viewModel(FakePicker("   "), recorder).pickDirectory()

        assertTrue(recorder.selected.isEmpty())
    }

    @Test
    fun `no picker provider is a no-op rather than a crash`() {
        val recorder = Recorder()
        viewModel(null, recorder).pickDirectory()

        assertTrue(recorder.selected.isEmpty())
    }

    @Test
    fun `no selection callback is a no-op rather than a crash`() {
        val picker = FakePicker("${separator()}dev${separator()}Boss")
        viewModel(picker, null).pickDirectory()

        assertEquals(1, picker.invocations)
    }

    @Test
    fun `selectProject forwards a row's name and path verbatim`() {
        val recorder = Recorder()
        viewModel(null, recorder).selectProject("Boss", "${separator()}dev${separator()}Boss")

        assertEquals("Boss" to "${separator()}dev${separator()}Boss", recorder.selected.single())
    }

    @Test
    fun `trimTrailingSeparator keeps a filesystem root and both separators`() {
        assertEquals("/dev/Boss", PathUtils.trimTrailingSeparator("/dev/Boss///", '/'))
        assertEquals("""C:\dev\Boss""", PathUtils.trimTrailingSeparator("""C:\dev\Boss\""", '\\'))
        // A root is all separator: keep one rather than empty it out.
        assertEquals("/", PathUtils.trimTrailingSeparator("//", '/'))
        assertEquals("/", PathUtils.trimTrailingSeparator("/", '/'))
        assertEquals("", PathUtils.trimTrailingSeparator("   ", '/'))
    }

    /**
     * pickDirectory uses the PLATFORM separator (paths come from the host's
     * File.absolutePath), so the fixtures have to as well.
     */
    private fun separator() = PathUtils.platformSeparator.toString()

    @Test
    fun `name of a trimmed path is never empty for a real directory`() {
        assertNull(
            listOf("/dev/Boss/", "/dev/Boss")
                .map { PathUtils.name(PathUtils.trimTrailingSeparator(it, '/'), '/') }
                .firstOrNull { it.isEmpty() }
        )
    }
}
