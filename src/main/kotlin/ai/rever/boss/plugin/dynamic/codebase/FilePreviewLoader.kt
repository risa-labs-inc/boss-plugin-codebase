package ai.rever.boss.plugin.dynamic.codebase

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface FilePreviewBody {
    data class Picture(val image: BufferedImage, val width: Int, val height: Int) : FilePreviewBody
    data class Text(val value: String, val truncated: Boolean) : FilePreviewBody
    data class Message(val value: String) : FilePreviewBody
}

internal data class FilePreview(
    val path: String,
    val size: Long? = null,
    val modifiedMillis: Long? = null,
    val body: FilePreviewBody,
    val kind: String = "File",
    val canOpenInBoss: Boolean = false,
    val canOpenDefault: Boolean = false,
)

/** Local, bounded previews only. Never execute a file or load its external references. */
internal object FilePreviewLoader {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 64_000_000L
    const val MAX_SOURCE_EDGE = 32_768
    const val MAX_THUMBNAIL_EDGE = 512
    const val MAX_TEXT_BYTES = 16 * 1024
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp")
    private val documentExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    // Only one decoder runs at a time, even when the user rapidly changes selection.
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun load(path: String): FilePreview = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        try {
            val file = Path.of(path)
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            fun result(body: FilePreviewBody) = FilePreview(
                path, attributes.size().takeUnless { attributes.isDirectory },
                attributes.lastModifiedTime().toMillis(), body,
                kind = when {
                    attributes.isDirectory -> "Folder"
                    file.fileName.toString().endsWith(".pdf", ignoreCase = true) -> "PDF document"
                    body is FilePreviewBody.Picture -> "Image"
                    body is FilePreviewBody.Text -> "UTF-8 text"
                    else -> "File"
                },
                canOpenInBoss = attributes.isRegularFile,
                canOpenDefault = attributes.isRegularFile || attributes.isDirectory
            )
            when {
                attributes.isDirectory -> result(FilePreviewBody.Message("Folder selected. Select a file to preview."))
                !attributes.isRegularFile -> result(FilePreviewBody.Message("This item cannot be previewed."))
                else -> {
                    val extension = file.fileName.toString().substringAfterLast('.', "").lowercase()
                    val body = when {
                        extension in imageExtensions -> readImage(file, attributes.size())
                        extension == "pdf" -> QuickLookPdfPreview.render(file)
                        extension in documentExtensions -> FilePreviewBody.Message(
                            "Preview is not available for this document. Use Open in BOSS or Default app."
                        )
                        else -> text(file)
                    }
                    currentCoroutineContext().ensureActive()
                    result(body)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            FilePreview(
                path, body = FilePreviewBody.Message("Unable to preview. The file may be missing or unreadable.")
            )
        } catch (error: SecurityException) {
            FilePreview(path, body = FilePreviewBody.Message("You do not have permission to preview this file."))
        } catch (error: RuntimeException) {
            FilePreview(path, body = FilePreviewBody.Message("This file cannot be previewed."))
        }
    }

    internal fun readImage(path: Path, size: Long): FilePreviewBody {
        if (size > MAX_IMAGE_BYTES) return FilePreviewBody.Message("Image exceeds the 16 MiB preview limit.")
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        if (bytes.size > MAX_IMAGE_BYTES) return FilePreviewBody.Message("Image exceeds the 16 MiB preview limit.")
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return FilePreviewBody.Message("This image format is unsupported or damaged.")
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE ||
                    width.toLong() * height > MAX_IMAGE_PIXELS
                ) {
                    return FilePreviewBody.Message("Image exceeds 64 megapixels or 32,768 pixels on one edge.")
                }
                val sample = ((maxOf(width, height) + MAX_THUMBNAIL_EDGE - 1) / MAX_THUMBNAIL_EDGE).coerceAtLeast(1)
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                return FilePreviewBody.Picture(reader.read(0, parameters), width, height)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun text(path: Path): FilePreviewBody {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_TEXT_BYTES + 1) }
        val truncated = bytes.size > MAX_TEXT_BYTES
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars = CharBuffer.allocate(MAX_TEXT_BYTES)
        val result = decoder.decode(ByteBuffer.wrap(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES)), chars, !truncated)
        if (result.isError) return FilePreviewBody.Message("No preview for binary files or text that is not UTF-8.")
        chars.flip()
        val value = chars.toString().removePrefix("\uFEFF")
        if ('\u0000' in value) {
            return FilePreviewBody.Message("No preview for binary files or text that is not UTF-8.")
        }
        // Compose renders plain text, so ANSI escapes and form feeds remain literal text,
        // never terminal commands. A NUL byte remains the binary-content discriminator.
        return FilePreviewBody.Text(value, truncated)
    }
}

internal fun previewFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
}
