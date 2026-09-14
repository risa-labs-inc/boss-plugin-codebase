package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds the ref pill's proportions inside a commit row.
 *
 * These are the numbers that made the pill read as a heavy badge: with no
 * explicit height it measured the label's full, untrimmed line box and came
 * out very nearly as tall as the row. The fix was to state the height rather
 * than inherit it from a font metric - and a stated number is only worth
 * anything if something notices when it drifts back.
 *
 * Arithmetic, not rendering: this pins intent (a chip inside the row, a label
 * quieter than the subject), which is the part that has actually regressed
 * here before. It does NOT prove what the pill looks like on screen.
 */
class GitRefBadgeMetricsTest {

    @Test
    fun `the pill is a chip inside the row, not a band across it`() {
        assertTrue(
            REF_BADGE_HEIGHT < GRAPH_ROW_HEIGHT,
            "a pill at least as tall as the row has no breathing room: $REF_BADGE_HEIGHT vs $GRAPH_ROW_HEIGHT",
        )
        // Roughly two thirds of the row: enough to read, short enough to leave
        // clear space above and below.
        val ratio = REF_BADGE_HEIGHT.value / GRAPH_ROW_HEIGHT.value
        assertTrue(ratio in 0.50f..0.70f, "pill/row ratio drifted to $ratio")
    }

    @Test
    fun `there is visible space above and below the pill`() {
        val slack = GRAPH_ROW_HEIGHT.value - REF_BADGE_HEIGHT.value
        // Split by the row's CenterVertically alignment, so half lands on each side.
        assertTrue(slack / 2f >= 4f, "only ${slack / 2f}dp above and below the pill")
    }

    @Test
    fun `the label is noticeably smaller than the commit subject`() {
        assertTrue(
            REF_BADGE_TEXT.value < CodebaseMetrics.PrimaryText.value,
            "the pill label must not compete with the subject",
        )
        assertTrue(
            REF_BADGE_TEXT.value <= CodebaseMetrics.MetaText.value - 1f,
            "'noticeably smaller' means smaller than the row's meta text too: " +
                "${REF_BADGE_TEXT.value} vs ${CodebaseMetrics.MetaText.value}",
        )
    }

    @Test
    fun `the pill still fits its label plus glyph within the height`() {
        // The icon (9dp) and the trimmed 9sp label both have to sit inside the
        // pill with its own padding; a height under ~12dp would clip them.
        assertTrue(REF_BADGE_HEIGHT.value >= 12f, "the glyph and label would clip")
    }
}
