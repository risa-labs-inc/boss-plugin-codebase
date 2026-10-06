package ai.rever.boss.plugin.dynamic.codebase

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** macOS-only thumbnail generation; qlmanage -o renders offscreen, without opening a window. */
internal object QuickLookPdfPreview {
    const val MAX_PDF_BYTES = 64L * 1024 * 1024
    private val executable = Path.of("/usr/bin/qlmanage")
    private const val TIMEOUT_MILLIS = 10_000L

    suspend fun render(path: Path): FilePreviewBody {
        if (!System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true) ||
            !Files.isExecutable(executable)
        ) {
            return FilePreviewBody.Message("PDF thumbnails are available on macOS. Use Open in BOSS or Default app.")
        }
        if (Files.size(path) > MAX_PDF_BYTES) {
            return FilePreviewBody.Message("PDF exceeds the 64 MiB preview limit. Open the file to view it.")
        }
        return generate(path)
    }

    internal suspend fun generate(
        path: Path,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        createDirectory: () -> Path = { Files.createTempDirectory("boss-file-preview-") },
        startProcess: (Path, Path) -> Process = { source, directory ->
            ProcessBuilder(
                executable.toString(), "-t", "-s", "512", "-o", directory.toString(),
                source.toAbsolutePath().toString()
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        },
    ): FilePreviewBody {
        val directory = createDirectory()
        var process: Process? = null
        try {
            currentCoroutineContext().ensureActive()
            process = startProcess(path, directory)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) {
                    return FilePreviewBody.Message("PDF preview timed out. Open the file to view it.")
                }
            }
            currentCoroutineContext().ensureActive()
            if (process.exitValue() != 0) return unavailable()
            // Quick Look may normalize Unicode in the output name. Inspect this request's
            // private directory instead of guessing the name from the original path.
            val output = Files.list(directory).use { files ->
                files.filter {
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().endsWith(".png")
                }.toList().singleOrNull()
            } ?: return unavailable()
            return FilePreviewLoader.readImage(output, Files.size(output))
        } finally {
            // Cleanup must not replace the original cancellation, failure, or thumbnail result.
            // Attempt directory cleanup independently even if process shutdown fails.
            bestEffortCleanup {
                process?.let {
                    if (it.isAlive) {
                        it.destroyForcibly()
                        it.waitFor(1, TimeUnit.SECONDS)
                    }
                }
            }
            bestEffortCleanup {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        bestEffortCleanup { Files.deleteIfExists(path) }
                    }
                }
            }
        }
    }

    private inline fun bestEffortCleanup(action: () -> Unit) {
        try {
            action()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // The directory may already be gone, or the process may have exited externally.
        }
    }

    private fun unavailable() = FilePreviewBody.Message(
        "A thumbnail could not be generated for this PDF. It may be encrypted or damaged. Open it to view more."
    )
}
