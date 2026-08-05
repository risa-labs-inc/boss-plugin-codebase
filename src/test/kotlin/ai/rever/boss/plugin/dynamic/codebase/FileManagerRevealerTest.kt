package ai.rever.boss.plugin.dynamic.codebase

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `blank path is a no-op`() {
        var launched = false

        val result = FileManagerRevealer.reveal(
            path = " ",
            launch = { launched = true },
            launchCommandLine = { launched = true }
        )

        assertTrue(result.isSuccess)
        assertEquals(false, launched)
    }
}
