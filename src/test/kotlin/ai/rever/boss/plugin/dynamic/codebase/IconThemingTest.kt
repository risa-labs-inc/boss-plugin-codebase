package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for [IconTheming]: brand colors stay themselves where they are
 * already legible, and only move far enough to become legible where they are
 * not. Backgrounds below are the real host theme floors (BossThemes' `ink`).
 */
class IconThemingTest {

    private val darkInk = Color(0xFF05070B) // Blueprint
    private val lightPaper = Color(0xFFF5F7FB) // Blueprint Light / Daylight

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun hue(color: Color): Float {
        val maxC = maxOf(color.red, color.green, color.blue)
        val minC = minOf(color.red, color.green, color.blue)
        val delta = maxC - minC
        if (delta == 0f) return 0f
        val h = when (maxC) {
            color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
            color.green -> 60f * (((color.blue - color.red) / delta) + 2f)
            else -> 60f * (((color.red - color.green) / delta) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    @Test
    fun `white brand marks darken on a light theme`() {
        val adapted = IconTheming.adapt(Color.White, lightPaper)

        assertTrue(
            adapted.luminance() < lightPaper.luminance(),
            "white should end up darker than the paper it sits on, was $adapted"
        )
        assertTrue(
            contrast(adapted, lightPaper) >= 6f,
            "neutral marks need a text-like floor, got ${contrast(adapted, lightPaper)}"
        )
    }

    @Test
    fun `white brand marks are untouched on a dark theme`() {
        assertEquals(Color.White, IconTheming.adapt(Color.White, darkInk))
    }

    @Test
    fun `near-black brand marks lighten on a dark theme`() {
        val prismaNavy = Color(0xFF2D3748)

        val adapted = IconTheming.adapt(prismaNavy, darkInk)

        assertTrue(
            adapted.luminance() > prismaNavy.luminance(),
            "navy should be lifted off the ink, was $adapted"
        )
        assertTrue(
            contrast(adapted, darkInk) >= 3.5f,
            "chromatic marks need the 3.5:1 floor, got ${contrast(adapted, darkInk)}"
        )
    }

    @Test
    fun `legible brand colors are returned unchanged on both themes`() {
        // Mid-lightness hues that already clear 3.5:1 against ink and paper
        // alike - the common case, and the one that must stay pixel-identical.
        val kotlinPurple = Color(0xFF7F52FF)
        val pythonBlue = Color(0xFF3776AB)

        assertEquals(kotlinPurple, IconTheming.adapt(kotlinPurple, darkInk))
        assertEquals(kotlinPurple, IconTheming.adapt(kotlinPurple, lightPaper))
        assertEquals(pythonBlue, IconTheming.adapt(pythonBlue, darkInk))
        assertEquals(pythonBlue, IconTheming.adapt(pythonBlue, lightPaper))
    }

    @Test
    fun `a mid-contrast brand color is nudged only as far as the floor`() {
        // Go's cyan sits at 2.5:1 on paper: legible-ish, but under the floor.
        // It should darken just enough, not all the way to a muddy teal.
        val goCyan = Color(0xFF00ADD8)

        val adapted = IconTheming.adapt(goCyan, lightPaper)

        val ratio = contrast(adapted, lightPaper)
        assertTrue(ratio >= 3.5f, "under the floor at $ratio:1")
        assertTrue(ratio < 4.5f, "overshot the floor at $ratio:1 ($adapted)")
        assertTrue(
            abs(hue(adapted) - hue(goCyan)) < 2f,
            "hue drifted from ${hue(goCyan)} to ${hue(adapted)}"
        )
    }

    @Test
    fun `retinting preserves the brand hue`() {
        val javascriptYellow = Color(0xFFF7DF1E)

        val adapted = IconTheming.adapt(javascriptYellow, lightPaper)

        assertTrue(
            adapted != javascriptYellow,
            "yellow at 1.2:1 on paper has to move"
        )
        assertTrue(
            abs(hue(adapted) - hue(javascriptYellow)) < 2f,
            "hue drifted from ${hue(javascriptYellow)} to ${hue(adapted)}"
        )
    }

    @Test
    fun `every brand color in the palette clears its floor on every theme floor`() {
        // The real surfaces: Blueprint, Operator, Clean, and the two light themes.
        val backgrounds = listOf(
            Color(0xFF05070B),
            Color(0xFF15171A),
            Color(0xFFF5F7FB),
            Color(0xFFF5F7FA),
            Color(0xFFFFFFFF)
        )
        val palette = listOf(
            // Neutrals and near-neutrals, the ones that vanish today.
            Color(0xFFFFFFFF), Color(0xFF8C8C8C), Color(0xFF808080),
            // The extremes at the other end.
            Color(0xFF1D365D), Color(0xFF2D3748), Color(0xFF05070B),
            // A spread of saturated brand hues.
            Color(0xFF7F52FF), Color(0xFFF7DF1E), Color(0xFF00ADD8),
            Color(0xFFE10098), Color(0xFF4EAA25), Color(0xFFCB171E)
        )

        for (background in backgrounds) {
            for (brand in palette) {
                val adapted = IconTheming.adapt(brand, background)
                assertTrue(
                    contrast(adapted, background) >= 3.4f,
                    "$brand on $background stayed at ${contrast(adapted, background)}:1 (as $adapted)"
                )
            }
        }
    }

    @Test
    fun `alpha is preserved`() {
        val translucentWhite = Color.White.copy(alpha = 0.6f)

        assertEquals(0.6f, IconTheming.adapt(translucentWhite, lightPaper).alpha, 0.001f)
    }

    @Test
    fun `a fully transparent tint is left alone`() {
        assertEquals(Color.Transparent, IconTheming.adapt(Color.Transparent, lightPaper))
    }
}
