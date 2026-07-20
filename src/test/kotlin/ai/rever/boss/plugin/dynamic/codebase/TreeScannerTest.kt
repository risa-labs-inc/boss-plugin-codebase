package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TreeScanner routing (issue #6): provider-backed when the host advertises
 * supportsHiddenEntries, LocalFileScanner fallback otherwise.
 */
class TreeScannerTest {

    private val marker = FileNodeData(name = "FROM_PROVIDER", path = "/marker", isDirectory = true)

    /** Minimal fake: only the members TreeScanner touches are meaningful. */
    private open class FakeProvider(private val supportsHidden: Boolean, private val result: FileNodeData?) :
        FileSystemDataProvider {
        override val supportsHiddenEntries: Boolean get() = supportsHidden
        override suspend fun scanDirectory(path: String): FileNodeData? = result
        override suspend fun scanDirectory(path: String, showHidden: Boolean): FileNodeData? = result
        override suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int): FileNodeData? = result
        override suspend fun scanDirectoryWithDepth(path: String, maxDepth: Int, startDepth: Int, showHidden: Boolean): FileNodeData? = result
        override fun directoryHasChildren(path: String): Boolean = false
        override fun openFile(path: String, windowId: String) = Unit
        override suspend fun createFile(parentPath: String, fileName: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun createFolder(parentPath: String, folderName: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun delete(path: String): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun rename(path: String, newName: String): Result<String> = Result.failure(UnsupportedOperationException())
        override fun revealInFileManager(path: String): Result<Unit> = Result.failure(UnsupportedOperationException())
        override fun copyToClipboard(text: String): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun writeFile(path: String, content: String): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun readFile(path: String): Result<String> = Result.failure(UnsupportedOperationException())
        override fun getDownloadsDirectory(): String = ""
        override fun getHomeDirectory(): String = ""
    }

    @Test
    fun `routes to provider when it supports hidden entries`() {
        val scanner = TreeScanner(FakeProvider(supportsHidden = true, result = marker))
        assertTrue(scanner.usesProvider)
        runBlocking {
            assertEquals("FROM_PROVIDER", scanner.scanDirectory("/anything", showHidden = true)?.name)
            assertEquals("FROM_PROVIDER", scanner.scanDirectoryWithDepth("/anything", 1, 0, showHidden = false)?.name)
        }
    }

    @Test
    fun `falls back to local scanner when provider does not support hidden entries`() {
        val scanner = TreeScanner(FakeProvider(supportsHidden = false, result = marker))
        assertFalse(scanner.usesProvider)

        val dir = File.createTempFile("treescanner-test", null).apply { delete(); mkdirs() }
        try {
            File(dir, "real.txt").writeText("x")
            runBlocking {
                val scanned = scanner.scanDirectory(dir.absolutePath, showHidden = false)
                // Local scan of the real directory, not the provider's marker
                assertEquals(listOf("real.txt"), scanned?.children?.map { it.name })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `null provider always uses the local scanner`() {
        assertFalse(TreeScanner(null).usesProvider)
    }
}
