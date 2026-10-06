package ai.rever.boss.plugin.dynamic.codebase

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilePreviewLoaderTest {
    private fun fixture(name: String, block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("boss-preview-test-")
        try {
            block(directory.resolve(name))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `image thumbnail preserves aspect ratio and reports original dimensions`() = fixture("photo ü.png") { path ->
        val original = BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(original, "png", path.toFile())
        val preview = FilePreviewLoader.load(path.toString())
        val body = assertIs<FilePreviewBody.Picture>(preview.body)
        assertEquals(2048, body.width)
        assertEquals(1024, body.height)
        assertEquals(512, body.image.width)
        assertEquals(256, body.image.height)
        assertEquals(Files.size(path), preview.size)
        assertTrue(preview.modifiedMillis!! > 0)
    }

    @Test
    fun `large image byte stream is rejected without decoding`() = fixture("large.png") { path ->
        java.io.RandomAccessFile(path.toFile(), "rw").use { it.setLength(FilePreviewLoader.MAX_IMAGE_BYTES + 1L) }
        val body = assertIs<FilePreviewBody.Message>(FilePreviewLoader.load(path.toString()).body)
        assertTrue(body.value.contains("16 MiB"))
    }

    @Test
    fun `oversized image dimensions are rejected from metadata before pixel decoding`() = fixture("huge.png") { path ->
        ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        val bytes = Files.readAllBytes(path)
        java.nio.ByteBuffer.wrap(bytes).putInt(16, 100_000_000)
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }
        java.nio.ByteBuffer.wrap(bytes).putInt(29, crc.value.toInt())
        Files.write(path, bytes)
        val body = assertIs<FilePreviewBody.Message>(FilePreviewLoader.load(path.toString()).body)
        assertTrue(body.value.contains("megapixels"))
    }

    @Test
    fun `corrupt image has a graceful fallback`() = fixture("broken.png") { path ->
        Files.writeString(path, "not a png")
        assertIs<FilePreviewBody.Message>(FilePreviewLoader.load(path.toString()).body)
    }

    @Test
    fun `text preserves unicode and punctuation and strips only BOM`() = fixture("notes.md") { path ->
        Files.writeString(path, "\uFEFFHello ü 世界\n<not executable>\t'quoted'")
        val body = assertIs<FilePreviewBody.Text>(FilePreviewLoader.load(path.toString()).body)
        assertEquals("Hello ü 世界\n<not executable>\t'quoted'", body.value)
        assertEquals(false, body.truncated)
    }

    @Test
    fun `text cap handles unicode crossing byte boundary`() = fixture("big.txt") { path ->
        val prefix = "a".repeat(FilePreviewLoader.MAX_TEXT_BYTES - 1)
        Files.writeString(path, prefix + "世界")
        val body = assertIs<FilePreviewBody.Text>(FilePreviewLoader.load(path.toString()).body)
        assertEquals(prefix, body.value)
        assertTrue(body.truncated)
    }

    @Test
    fun `UTF8 logs preserve form feeds and ANSI escapes as plain text`() = fixture("output.log") { path ->
        val value = "first page\u000Csecond page\n\u001B[31mred log text\u001B[0m"
        Files.writeString(path, value)
        assertEquals(value, assertIs<FilePreviewBody.Text>(FilePreviewLoader.load(path.toString()).body).value)
    }

    @Test
    fun `binary and invalid UTF8 do not become text`() = fixture("binary.dat") { path ->
        for (bytes in listOf(byteArrayOf(65, 0, 66), byteArrayOf(65, -1, 66))) {
            Files.write(path, bytes)
            assertIs<FilePreviewBody.Message>(FilePreviewLoader.load(path.toString()).body)
        }
    }

    @Test
    fun `empty files missing files and folders do not crash`() = fixture("empty.txt") { path ->
        val missing = FilePreviewLoader.load(path.toString())
        assertIs<FilePreviewBody.Message>(missing.body)
        assertEquals(false, missing.canOpenInBoss)
        assertEquals(false, missing.canOpenDefault)
        Files.createFile(path)
        assertEquals("", assertIs<FilePreviewBody.Text>(FilePreviewLoader.load(path.toString()).body).value)
        val folder = FilePreviewLoader.load(path.parent.toString())
        assertIs<FilePreviewBody.Message>(folder.body)
        assertEquals(false, folder.canOpenInBoss)
        assertEquals(true, folder.canOpenDefault)
        assertEquals("Folder", folder.kind)
    }

    @Test
    fun `office documents use metadata fallback instead of binary reading`() = fixture("report.docx") { path ->
        Files.writeString(path, "Even plain text inside a document file should not be parsed as a document")
        val body = assertIs<FilePreviewBody.Message>(FilePreviewLoader.load(path.toString()).body)
        assertTrue(body.value.contains("document"))
    }
}
