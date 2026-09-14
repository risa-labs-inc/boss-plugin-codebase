package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.AiReadiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one thing about this mapping that is easy to get wrong: null is
 * the SUCCESS value.
 *
 * Written inline in the plugin's panel registration it read
 * `pluginContext?.let { …READY -> null… } ?: "AI is unavailable on this host."`,
 * where the elvis operator cannot tell "the let returned null because AI is
 * ready" from "there was no plugin context". A fully working gateway - Claude
 * CLI resolved, a model selected - therefore reported itself as unavailable,
 * and every AI action in the panel refused with a message that was simply
 * false. Only a null readiness may produce that string.
 */
class AiUnavailableMessageTest {

    @Test
    fun `READY is not an unavailability and maps to null`() {
        assertNull(AiUnavailableMessage.of(AiReadiness.READY))
    }

    @Test
    fun `only a missing plugin context reports the host as unable`() {
        assertEquals(AiUnavailableMessage.NO_HOST, AiUnavailableMessage.of(null))
    }

    @Test
    fun `the two failures keep their own fixes`() {
        // Collapsing them into one message sends half of users to the wrong place.
        assertEquals(AiUnavailableMessage.NO_GATEWAY, AiUnavailableMessage.of(AiReadiness.GATEWAY_MISSING))
        assertEquals(AiUnavailableMessage.NO_PROVIDER, AiUnavailableMessage.of(AiReadiness.NO_PROVIDER))
    }

    @Test
    fun `every readiness other than READY produces a message`() {
        // AiReadiness is documented as an open set: a value added later must
        // still explain itself rather than falling through to null, which the
        // panel reads as "AI is fine".
        AiReadiness.entries
            .filterNot { it == AiReadiness.READY }
            .forEach { readiness ->
                assertEquals(
                    true,
                    !AiUnavailableMessage.of(readiness).isNullOrBlank(),
                    "$readiness must explain itself",
                )
            }
    }
}
