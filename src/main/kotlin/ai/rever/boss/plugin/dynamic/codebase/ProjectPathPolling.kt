package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.delay

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
