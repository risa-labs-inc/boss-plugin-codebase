package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider

/**
 * Fake provider overriding ONLY the legacy members — the showHidden
 * overloads run the interface DEFAULT implementations, exactly like a host
 * binary that predates boss-plugin-api 1.0.66's opt-in.
 */
internal open class LegacyFakeProvider(
    private val legacyScan: (String) -> FileNodeData? = { null }
) : FileSystemDataProvider {
    override suspend fun scanDirectory(path: String): FileNodeData? = legacyScan(path)
    override suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int): FileNodeData? = legacyScan(path)
    override fun directoryHasChildren(path: String): Boolean = false
    override fun openFile(path: String, windowId: String) = Unit
    override suspend fun createFile(parentPath: String, fileName: String): Result<String> = unsupported()
    override suspend fun createFolder(parentPath: String, folderName: String): Result<String> = unsupported()
    override suspend fun delete(path: String): Result<Unit> = unsupported()
    override suspend fun rename(path: String, newName: String): Result<String> = unsupported()
    override fun revealInFileManager(path: String): Result<Unit> = unsupported()
    override fun copyToClipboard(text: String): Result<Unit> = unsupported()
    override suspend fun writeFile(path: String, content: String): Result<Unit> = unsupported()
    override suspend fun readFile(path: String): Result<String> = unsupported()
    override fun getDownloadsDirectory(): String = ""
    override fun getHomeDirectory(): String = ""

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
}

/**
 * Fake provider that honors the showHidden flag, like a host built from
 * BossConsole#881 onward.
 */
internal class HiddenAwareFakeProvider(
    private val scan: (path: String, showHidden: Boolean) -> FileNodeData?
) : LegacyFakeProvider(legacyScan = { path -> scan(path, false) }) {
    override val supportsHiddenEntries: Boolean get() = true
    override suspend fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? = scan(path, showHidden)
    override suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int, showHidden: Boolean): FileNodeData? =
        scan(path, showHidden)
}
