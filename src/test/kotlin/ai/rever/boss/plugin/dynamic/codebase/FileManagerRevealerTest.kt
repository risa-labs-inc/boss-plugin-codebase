package ai.rever.boss.plugin.dynamic.codebase

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileManagerRevealerTest {

    @Test
    fun `mac reveal launches Finder with selection`() {
        val commands = mutableListOf<List<String>>()
        val path = "/project/file with spaces.txt"

        val result = FileManagerRevealer.reveal(
            path = path,
            osName = "Mac OS X",
            launch = { commands += it }
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(listOf("open", "-R", File(path).absolutePath)), commands)
    }

    @Test
    fun `linux reveal falls back to gio when xdg-open is unavailable`() {
        val commands = mutableListOf<List<String>>()
        val path = "/project/file.txt"

        val result = FileManagerRevealer.reveal(
            path = path,
            osName = "Linux",
            launch = { command ->
                commands += command
                if (command.first() == "xdg-open") throw IOException("missing")
            }
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                listOf("xdg-open", "/project"),
                listOf("gio", "open", "/project")
            ),
            commands
        )
    }

    @Test
    fun `windows reveal selects the file with a quoted command line`() {
        val commands = mutableListOf<String>()
        val path = "/project/file with spaces.txt"

        val result = FileManagerRevealer.reveal(
            path = path,
            osName = "Windows 11",
            launchCommandLine = { commands += it }
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("explorer.exe /select,\"${File(path).absolutePath}\""),
            commands
        )
    }

    @Test
    fun `windows reveal with doubled spaces opens the parent`() {
        val commands = mutableListOf<List<String>>()
        val path = "/project/a  b/file.txt"

        val result = FileManagerRevealer.reveal(
            path = path,
            osName = "Windows 11",
            launch = { commands += it }
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(listOf("explorer.exe", File(path).parentFile.absolutePath)),
            commands
        )
    }

    @Test
    fun `blank path is a no-op`() {
        var launched = false

        val result = FileManagerRevealer.reveal(
            path = " ",
            launch = { launched = true },
            launchCommandLine = { launched = true }
        )

        assertTrue(result.isSuccess)
        assertFalse(launched)
    }

    @Test
    fun `fatal errors are not converted to ordinary reveal failures`() {
        assertFailsWith<StackOverflowError> {
            FileManagerRevealer.reveal(
                path = "/project/file.txt",
                osName = "Mac OS X",
                launch = { throw StackOverflowError("fatal") }
            )
        }
    }
}
