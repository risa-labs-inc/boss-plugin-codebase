package ai.rever.boss.plugin.dynamic.codebase

import java.io.File
import java.io.IOException

/**
 * Opens the native file manager without going through the host provider.
 *
 * Some host versions accidentally delegate FileSystemDataProvider's
 * revealInFileManager method back to itself, causing a StackOverflowError.
 * Keeping the small OS bridge here makes both row and project-root reveal
 * actions safe on affected hosts while preserving the intended behavior.
 */
internal object FileManagerRevealer {

    fun reveal(
        path: String,
        osName: String = System.getProperty("os.name").orEmpty(),
        launch: (List<String>) -> Unit = { command ->
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .outputStream
                .close()
            Unit
        },
        launchCommandLine: (String) -> Unit = { command ->
            @Suppress("DEPRECATION")
            Runtime.getRuntime().exec(command).apply {
                outputStream.close()
                inputStream.close()
                errorStream.close()
            }
            Unit
        }
    ): Result<Unit> {
        if (path.isBlank()) return Result.success(Unit)

        val file = File(path)
        val normalizedOsName = osName.lowercase()
        return try {
            when {
                normalizedOsName.contains("mac") -> {
                    launch(listOf("open", "-R", file.absolutePath))
                }

                normalizedOsName.contains("win") -> {
                    val absolutePath = file.absolutePath
                    if (absolutePath.contains("  ")) {
                        // Runtime.exec(String) collapses repeated spaces while
                        // tokenizing. Open the parent instead of selecting a
                        // corrupted path in that uncommon case.
                        launch(listOf("explorer.exe", (file.parentFile ?: file).absolutePath))
                    } else {
                        // Explorer requires /select,"path" as one command-line
                        // fragment; argv launching quotes the entire fragment
                        // and Explorer silently opens its default location.
                        launchCommandLine("explorer.exe /select,\"$absolutePath\"")
                    }
                }

                else -> {
                    val directory = (file.parentFile ?: file).absolutePath
                    try {
                        launch(listOf("xdg-open", directory))
                    } catch (_: IOException) {
                        launch(listOf("gio", "open", directory))
                    }
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
