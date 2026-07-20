package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileNodeData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TreeScanner (issue #6, post-retirement): provider-backed scans with the
 * showHidden flag passed through, graceful legacy delegation on host
 * binaries that predate the opt-in, and null-provider degradation.
 */
class TreeScannerTest {

    private fun node(name: String) = FileNodeData(name = name, path = "/$name", isDirectory = true)

    @Test
    fun `passes the showHidden flag through to a supporting provider`() {
        val received = mutableListOf<Boolean>()
        val scanner = TreeScanner(HiddenAwareFakeProvider { _, showHidden ->
            received.add(showHidden)
            node("x")
        })

        assertTrue(scanner.supportsHiddenEntries)
        runBlocking {
            scanner.scanDirectory("/p", showHidden = true)
            scanner.scanDirectoryWithDepth("/p", 1, 0, showHidden = false)
        }
        assertEquals(listOf(true, false), received)
    }

    @Test
    fun `interface defaults delegate to legacy scans on pre-opt-in hosts`() {
        val scanner = TreeScanner(LegacyFakeProvider { node("legacy") })

        assertFalse(scanner.supportsHiddenEntries)
        runBlocking {
            // The flag is ignored but trees still load — graceful degradation
            assertEquals("legacy", scanner.scanDirectory("/p", showHidden = true)?.name)
            assertEquals("legacy", scanner.scanDirectoryWithDepth("/p", 1, 0, showHidden = true)?.name)
        }
    }

    @Test
    fun `null provider yields null scans and no hidden support`() {
        val scanner = TreeScanner(null)
        assertFalse(scanner.supportsHiddenEntries)
        runBlocking {
            assertNull(scanner.scanDirectory("/p", showHidden = false))
            assertNull(scanner.scanDirectoryWithDepth("/p", 1, 0, showHidden = false))
        }
    }
}
