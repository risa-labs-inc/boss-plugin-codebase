package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FileIndexCache's showHidden-aware entries (kept from the pre-retirement
 * suite): a hit under a different hidden-files setting is a miss and
 * rescans; a hit under the same setting is served without rescanning.
 */
class FileIndexCacheTest {

    private fun tree(showHidden: Boolean) = FileNodeData(
        name = "proj",
        path = "/proj",
        isDirectory = true,
        children = buildList {
            add(FileNodeData(name = "src", path = "/proj/src", isDirectory = true))
            if (showHidden) add(FileNodeData(name = ".git", path = "/proj/.git", isDirectory = true))
        }
    )

    @Test
    fun `cache treats a different showHidden setting as a miss`() {
        runBlocking {
            val cache = FileIndexCache(
                maxSize = 10,
                maxDepthInitial = 1,
                scanner = TreeScanner(HiddenAwareFakeProvider { _, showHidden -> tree(showHidden) })
            )

            val without = cache.getNode("/proj", showHidden = false)!!
            assertTrue(without.children.none { it.name.startsWith(".") })

            // Same path, flipped setting: must rescan, not serve the stale entry
            val with = cache.getNode("/proj", showHidden = true)!!
            assertTrue(with.children.any { it.name.startsWith(".") })

            // And flipping back also rescans
            val withoutAgain = cache.getNode("/proj", showHidden = false)!!
            assertTrue(withoutAgain.children.none { it.name.startsWith(".") })
        }
    }

    @Test
    fun `cache serves a same-setting hit without rescanning`() {
        runBlocking {
            var scans = 0
            val cache = FileIndexCache(
                maxSize = 10,
                maxDepthInitial = 1,
                scanner = TreeScanner(HiddenAwareFakeProvider { _, showHidden ->
                    scans++
                    tree(showHidden)
                })
            )

            cache.getNode("/proj", showHidden = false)
            cache.getNode("/proj", showHidden = false)
            assertEquals(1, scans)
        }
    }
}
