package ai.rever.boss.plugin.dynamic.codebase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectPathPollingTest {
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
