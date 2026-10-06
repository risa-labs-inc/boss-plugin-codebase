package ai.rever.boss.plugin.dynamic.codebase

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuickLookPdfPreviewTest {
    private class FakeProcess(
        private val complete: Boolean,
        private val code: Int = 0,
        private val cancel: Boolean = false,
    ) : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (cancel && !destroyed) throw CancellationException("Selection changed")
            return complete || destroyed
        }
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun isAlive() = !complete && !destroyed
    }

    @Test
    fun `thumbnail uses returned filename including unicode normalization and deletes output`() = runBlocking {
        val directory = Files.createTempDirectory("quicklook-test-")
        val process = FakeProcess(true)
        val body = QuickLookPdfPreview.generate(
            Path.of("original ü.pdf"), createDirectory = { directory },
            startProcess = { _, target ->
                ImageIO.write(
                    BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", target.resolve("ü.pdf.png").toFile()
                )
                process
            }
        )
        assertIs<FilePreviewBody.Picture>(body)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `timed out process is destroyed and output removed`() = runBlocking {
        val directory = Files.createTempDirectory("quicklook-test-")
        val process = FakeProcess(false)
        val body = QuickLookPdfPreview.generate(
            Path.of("large.pdf"), timeoutMillis = 0, createDirectory = { directory },
            startProcess = { _, _ -> process }
        )
        assertTrue(assertIs<FilePreviewBody.Message>(body).value.contains("timed out"))
        assertTrue(process.destroyed)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `selection cancellation destroys process cleans output and propagates`() = runBlocking {
        val directory = Files.createTempDirectory("quicklook-test-")
        val process = FakeProcess(false, cancel = true)
        assertFailsWith<CancellationException> {
            QuickLookPdfPreview.generate(
                Path.of("file.pdf"), createDirectory = { directory }, startProcess = { _, _ -> process }
            )
        }
        assertTrue(process.destroyed)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `failed process and empty output have graceful fallback and clean output`() = runBlocking {
        for (code in listOf(0, 1)) {
            val directory = Files.createTempDirectory("quicklook-test-")
            val body = QuickLookPdfPreview.generate(
                Path.of("bad.pdf"), createDirectory = { directory }, startProcess = { _, _ -> FakeProcess(true, code) }
            )
            assertIs<FilePreviewBody.Message>(body)
            assertFalse(Files.exists(directory))
        }
    }

    @Test
    fun `file sizes are readable and locale independent`() {
        assertEquals("0 bytes", previewFileSize(0))
        assertEquals("1.5 KiB", previewFileSize(1536))
        assertEquals("2.0 MiB", previewFileSize(2 * 1024 * 1024))
    }
}
