package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-side scan facade (issue #6 — LocalFileScanner retirement complete).
 *
 * All tree scans go through the host provider's showHidden overloads
 * (boss-plugin-api >= 1.0.66). These are plain static calls: plugin.json's
 * minApiVersion guarantees the runtime api layer declares the members, so
 * the host's BinaryCompatibilityValidator is satisfied without reflection.
 *
 * Host BINARIES that predate the overloads' implementation still work: the
 * interface default implementations delegate to the legacy dot-filtering
 * scans, so trees load normally, the showHidden flag is ignored, and
 * [supportsHiddenEntries] is false — the UI hides the toggle instead of
 * offering a dead switch, and the MCP tool reports the limitation.
 *
 * With no provider (context without a file system), scans yield null and
 * the panel shows its existing empty state.
 */
internal class TreeScanner(private val provider: FileSystemDataProvider?) {

    /** True when the host honors the showHidden flag (gates the UI toggle). */
    val supportsHiddenEntries: Boolean = provider?.supportsHiddenEntries == true

    // Both entry points dispatch to IO so callers never need to; the host
    // provider dispatches internally too, which nests as a cheap no-op.

    suspend fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? =
        withContext(Dispatchers.IO) {
            provider?.scanDirectory(path, showHidden)
        }

    suspend fun scanDirectoryWithDepth(
        path: String,
        maxDepth: Int,
        startDepth: Int,
        showHidden: Boolean
    ): FileNodeData? =
        withContext(Dispatchers.IO) {
            provider?.scanDirectoryWithDepth(path, maxDepth, startDepth, showHidden)
        }
}
