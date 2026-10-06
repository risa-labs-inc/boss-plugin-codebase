package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** A failed host read must retain the last confirmed project, not clear it. */
internal fun readProjectPath(
    getProjectPath: () -> String?,
    previousPath: String?,
    onFailure: (Exception) -> Unit,
): String? = try {
    getProjectPath()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onFailure(failure)
    previousPath
}

/** Wait briefly for host confirmation, stopping once the selected path changes. */
internal suspend fun pollForProjectChange(
    attempts: Int = 20,
    intervalMillis: Long = 250L,
    refresh: suspend () -> Boolean,
) {
    repeat(attempts) { attempt ->
        if (refresh()) return
        if (attempt + 1 < attempts) delay(intervalMillis)
    }
}
