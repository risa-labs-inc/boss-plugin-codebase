package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Watches the directories whose contents are materialized in the file tree
 * and reports the ones whose direct entries changed on disk (git switches,
 * external renames, builds), so the panel refreshes without a manual reload.
 *
 * Deliberately NOT java.nio.file.WatchService: on macOS the JDK falls back
 * to a polling implementation with a fixed ~10s period, and a recursive
 * registration over a whole repo (node_modules, .git, build output) is
 * exactly the performance penalty this must avoid. Instead this is a
 * bounded snapshot-diff poller:
 *
 * - Cost is O(watched dirs), never O(repo size). The watch set is the root
 *   plus expanded directories — what the user can actually see.
 * - One directory listing per watched dir per tick, one stat per entry;
 *   signatures are 64-bit sums, so memory is a single Long per dir.
 * - File content edits don't change the signature (the tree shows names
 *   only); subdirectory mtimes are included, because a dir's mtime changes
 *   when entries are added/removed inside it — this keeps the expand
 *   chevron of a visible-but-collapsed directory honest.
 * - Dot-entries are skipped when hidden files are off, so .git index churn
 *   from ordinary git commands doesn't trigger anything.
 * - With no watched dirs (no project open) the loop suspends entirely.
 *
 * A changed dir that the host scan renders identically (e.g. an excluded
 * build/ entry's mtime under the root) costs one cheap depth-1 rescan whose
 * merged result equals the old tree — StateFlow equality then drops it, so
 * no recomposition happens.
 */
internal class FileWatcherService(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val onDirectoriesChanged: (Set<String>) -> Unit
) {
    private data class WatchTargets(val dirs: Set<String>, val showHidden: Boolean)

    private val targets = MutableStateFlow(WatchTargets(emptySet(), showHidden = false))
    private var pollJob: Job? = null

    /** Replace the watched set. Cheap and thread-safe; takes effect next tick. */
    fun setWatchedDirectories(dirs: Set<String>, showHidden: Boolean) {
        targets.value = WatchTargets(dirs, showHidden)
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) { pollLoop() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        targets.value = WatchTargets(emptySet(), showHidden = false)
    }

    private suspend fun pollLoop() {
        val signatures = HashMap<String, Long>()
        var baselineShowHidden: Boolean? = null
        while (currentCoroutineContext().isActive) {
            // Suspends (no ticking at all) while there is nothing to watch.
            val current = targets.first { it.dirs.isNotEmpty() }

            if (baselineShowHidden != current.showHidden) {
                // The hidden-files toggle changes what counts as an entry —
                // re-baseline silently; setShowHidden already reloads the tree.
                signatures.clear()
                baselineShowHidden = current.showHidden
            }
            signatures.keys.retainAll(current.dirs)

            val dirty = mutableSetOf<String>()
            for (dir in current.dirs) {
                val signature = directorySignature(dir, current.showHidden)
                val previous = signatures.put(dir, signature)
                // First sighting is a baseline, not a change: expanding a
                // directory must not immediately re-refresh it.
                if (previous != null && previous != signature) dirty.add(dir)
            }
            if (dirty.isNotEmpty()) onDirectoriesChanged(dirty)

            delay(pollIntervalMs)
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1500L

        /** Signature of a directory that can't be listed (deleted/renamed/no access). */
        private const val MISSING = 0L

        /**
         * Order-independent 64-bit signature of a directory's DIRECT entries:
         * per entry the name and kind, plus mtime for subdirectory entries
         * (see class doc). Hash collisions are possible in principle; the
         * consequence is a missed refresh, never wrong data.
         */
        internal fun directorySignature(dir: String, showHidden: Boolean): Long {
            return try {
                var signature = 1L
                Files.newDirectoryStream(Path.of(dir)).use { stream ->
                    for (entry in stream) {
                        val name = entry.fileName?.toString() ?: continue
                        if (!showHidden && name.startsWith(".")) continue
                        val attrs = try {
                            Files.readAttributes(
                                entry,
                                BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS
                            )
                        } catch (_: Exception) {
                            continue // entry vanished mid-listing
                        }
                        var h = name.hashCode() * FNV_PRIME
                        if (attrs.isDirectory) {
                            h = h xor DIR_MARK xor (attrs.lastModifiedTime().toMillis() * 31L)
                        }
                        signature += h // '+' keeps the combine order-independent
                    }
                }
                signature
            } catch (_: Exception) {
                MISSING
            }
        }

        private const val FNV_PRIME = 1099511628211L
        private const val DIR_MARK = 0x517cc1b727220a95L
    }
}
