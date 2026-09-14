package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory

/** Shared picker/selection boundary, with no tree state or background jobs. */
internal class ProjectSelection(
    private val directoryPickerProvider: DirectoryPickerProvider?,
    private val onSelectProject: ((String, String) -> Unit)?,
) {
    private val logger = BossLogger.forComponent("ProjectSelection")

    /**
     * Pick a directory and select it as the project.
     *
     * Logged at INFO at every step on purpose: the picker is a native dialog
     * driven by the host, so when a pick appears to do nothing there is no other
     * way to tell "dialog cancelled" from "no provider" from "callback ran and
     * the host ignored it".
     */
    fun pickDirectory() {
        val picker = directoryPickerProvider
        if (picker == null) {
            logger.warn(LogCategory.FILE, "No directory picker provider - cannot open a project")
            return
        }
        logger.info(LogCategory.FILE, "Opening the project directory picker")
        try {
            picker.pickDirectory { picked ->
                // The macOS picker returns the directory WITH a trailing separator when
                // nothing inside it was selected, and PathUtils.name of that is "" -
                // which is how such a project ended up named "Unknown".
                val path = PathUtils.trimTrailingSeparator(picked.orEmpty())
                if (path.isBlank()) {
                    logger.info(LogCategory.FILE, "Project picker dismissed with no directory")
                    return@pickDirectory
                }
                selectProject(PathUtils.name(path).ifEmpty { path }, path)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(LogCategory.FILE, "Project picker failed", mapOf("error" to failure.toString()))
        }
    }

    /**
     * Hand a project to the host so the whole window switches to it, not just
     * this panel. Null callback means the host exposed no ProjectDataProvider.
     */
    fun selectProject(name: String, path: String) {
        val select = onSelectProject
        if (select == null) {
            logger.warn(
                LogCategory.FILE,
                "No project selection callback - project not switched",
                mapOf("path" to path)
            )
            return
        }
        logger.info(LogCategory.FILE, "Selecting project", mapOf("name" to name, "path" to path))
        try {
            select(name, PathUtils.trimTrailingSeparator(path))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(LogCategory.FILE, "Project selection failed", mapOf("error" to failure.toString()))
        }
    }

}
