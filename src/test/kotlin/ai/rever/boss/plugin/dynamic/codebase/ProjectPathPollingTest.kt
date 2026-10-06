package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProjectPathPollingTest {
    @Test
    fun `provider failure preserves the confirmed project and the next read recovers`() = runBlocking {
        var path: String? = "/old"
        var reads = 0
        val failures = mutableListOf<Exception>()
        val getter = {
            if (++reads == 1) error("Host temporarily unavailable")
            "/new"
        }
        pollForProjectChange(intervalMillis = 0) {
            val next = readProjectPath(getter, path, failures::add)
            if (reads == 1) assertEquals("/old", next)
            (next != path).also { path = next }
        }
        assertEquals("/new", path)
        assertEquals(2, reads)
        assertEquals(1, failures.size)
    }

    @Test
    fun `failed initial sample recovers without an existing project`() {
        val failures = mutableListOf<Exception>()
        val initial = readProjectPath({ error("Host unavailable") }, null, failures::add)
        assertEquals(null, initial)
        assertEquals("/new", readProjectPath({ "/new" }, initial, failures::add))
        assertEquals(1, failures.size)
    }

    @Test
    fun `provider cancellation ends polling without logging or retrying`() = runBlocking {
        var reads = 0
        val failures = mutableListOf<Exception>()
        assertFailsWith<CancellationException> {
            pollForProjectChange(intervalMillis = 0) {
                readProjectPath({ reads++; throw CancellationException("Panel closed") }, "/old", failures::add)
                false
            }
        }
        assertEquals(1, reads)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `confirmed switch stops polling immediately`() = runBlocking {
        var reads = 0
        pollForProjectChange(intervalMillis = 0) { ++reads == 1 }
        assertEquals(1, reads)
    }

    @Test
    fun `delayed host confirmation stops further reads`() = runBlocking {
        var reads = 0
        pollForProjectChange(intervalMillis = 0) { ++reads == 3 }
        assertEquals(3, reads)
    }

    @Test
    fun `declined switch stays bounded`() = runBlocking {
        var reads = 0
        pollForProjectChange(intervalMillis = 0) { reads++; false }
        assertEquals(20, reads)
    }

    @Test
    fun `disposing the panel cancels polling`() = runBlocking {
        var reads = 0
        val firstRead = CompletableDeferred<Unit>()
        val polling = launch {
            pollForProjectChange(intervalMillis = 60_000L) {
                reads++
                firstRead.complete(Unit)
                false
            }
        }
        firstRead.await()
        polling.cancelAndJoin()
        assertTrue(polling.isCancelled)
        assertEquals(1, reads)
    }
}
