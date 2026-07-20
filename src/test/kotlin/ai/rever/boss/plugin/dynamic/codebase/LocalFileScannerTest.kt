package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the plugin-local scanner's hidden-entry filtering (must mirror
 * the host's DesktopFileScanner) and the FileIndexCache showHidden
 * invalidation added on review.
 */
class LocalFileScannerTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("scanner-test", null).apply {
            delete()
            mkdirs()
        }
        File(root, "src").mkdirs()
        File(root, "build").mkdirs() // always skipped, hidden toggle or not
        File(root, ".hidden").mkdirs()
        File(root, "readme.md").writeText("x")
        File(root, ".dotfile").writeText("x")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `hidden entries are filtered by default`() {
        val node = LocalFileScanner.scanDirectory(root.absolutePath, showHidden = false)!!
        // Directories sort before files, then case-insensitive by name
        assertEquals(listOf("src", "readme.md"), node.children.map { it.name })
    }

    @Test
    fun `showHidden reveals dot entries but build stays skipped`() {
        val node = LocalFileScanner.scanDirectory(root.absolutePath, showHidden = true)!!
        assertEquals(listOf(".hidden", "src", ".dotfile", "readme.md"), node.children.map { it.name })
    }

    @Test
    fun `directoryHasChildren respects the hidden filter`() {
        val onlyHidden = File(root, "onlyHidden").apply { mkdirs() }
        File(onlyHidden, ".secret").writeText("x")

        assertTrue(!LocalFileScanner.directoryHasChildren(onlyHidden.absolutePath, showHidden = false))
        assertTrue(LocalFileScanner.directoryHasChildren(onlyHidden.absolutePath, showHidden = true))
    }

    @Test
    fun `cache treats a different showHidden setting as a miss`() {
        runBlocking {
            val cache = FileIndexCache(maxSize = 10, maxDepthInitial = 1)

            val without = cache.getNode(root.absolutePath, showHidden = false)!!
            assertTrue(without.children.none { it.name.startsWith(".") })

            // Same path, flipped setting: must rescan, not serve the stale entry
            val with = cache.getNode(root.absolutePath, showHidden = true)!!
            assertTrue(with.children.any { it.name.startsWith(".") })

            // And flipping back also rescans
            val withoutAgain = cache.getNode(root.absolutePath, showHidden = false)!!
            assertTrue(withoutAgain.children.none { it.name.startsWith(".") })
        }
    }
}
