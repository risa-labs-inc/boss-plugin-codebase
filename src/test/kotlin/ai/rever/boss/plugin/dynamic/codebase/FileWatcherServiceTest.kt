package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FileWatcherService: the snapshot-diff signature that decides "this
 * directory changed", plus one end-to-end poll-loop test.
 */
class FileWatcherServiceTest {

    private val root: Path = Files.createTempDirectory("codebase-watcher-test")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun signature(showHidden: Boolean = false) =
        FileWatcherService.directorySignature(root.toString(), showHidden)

    @Test
    fun `signature is stable while the directory is unchanged`() {
        Files.createFile(root.resolve("a.txt"))
        Files.createDirectory(root.resolve("sub"))
        assertEquals(signature(), signature())
    }

    @Test
    fun `adding and removing entries changes the signature`() {
        val before = signature()
        val file = Files.createFile(root.resolve("new.txt"))
        val added = signature()
        assertNotEquals(before, added)

        Files.delete(file)
        assertEquals(before, signature())
    }

    @Test
    fun `renaming an entry changes the signature`() {
        val file = Files.createFile(root.resolve("old-name.txt"))
        val before = signature()
        Files.move(file, root.resolve("new-name.txt"))
        assertNotEquals(before, signature())
    }

    @Test
    fun `file content edits do not change the signature`() {
        val file = Files.createFile(root.resolve("a.txt"))
        val before = signature()
        Thread.sleep(20) // let mtime tick in case it ever mattered
        Files.writeString(file, "content changed")
        assertEquals(before, signature())
    }

    @Test
    fun `changes inside a subdirectory change the parent signature via dir mtime`() {
        val sub = Files.createDirectory(root.resolve("sub"))
        val before = signature()
        Thread.sleep(20) // ensure the dir mtime moves past the creation stamp
        Files.createFile(sub.resolve("appeared.txt"))
        assertNotEquals(before, signature())
    }

    @Test
    fun `hidden entries are ignored unless showHidden is on`() {
        val visibleBefore = signature(showHidden = false)
        val hiddenBefore = signature(showHidden = true)

        Files.createFile(root.resolve(".dotfile"))

        assertEquals(visibleBefore, signature(showHidden = false))
        assertNotEquals(hiddenBefore, signature(showHidden = true))
    }

    @Test
    fun `missing directory has a stable signature that changes when it appears`() {
        val ghost = root.resolve("not-yet").toString()
        val missing = FileWatcherService.directorySignature(ghost, showHidden = false)
        assertEquals(missing, FileWatcherService.directorySignature(ghost, showHidden = false))

        Files.createDirectory(root.resolve("not-yet"))
        Files.createFile(root.resolve("not-yet").resolve("x.txt"))
        assertNotEquals(missing, FileWatcherService.directorySignature(ghost, showHidden = false))
    }

    @Test
    fun `poll loop reports a changed directory and only after the baseline`() = runBlocking {
        Files.createFile(root.resolve("existing.txt"))

        val changed = Channel<Set<String>>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watcher = FileWatcherService(scope, pollIntervalMs = 25) { dirs ->
            changed.trySend(dirs)
        }
        try {
            watcher.setWatchedDirectories(setOf(root.toString()), showHidden = false)
            watcher.start()

            // Several ticks over pre-existing content: baseline, no reports.
            delay(200)
            assertTrue(changed.tryReceive().isFailure, "baseline must not be reported as a change")

            Files.createFile(root.resolve("created-later.txt"))
            val batch = withTimeout(3_000) { changed.receive() }
            assertTrue(root.toString() in batch)
        } finally {
            watcher.stop()
            scope.cancel()
        }
    }
}
