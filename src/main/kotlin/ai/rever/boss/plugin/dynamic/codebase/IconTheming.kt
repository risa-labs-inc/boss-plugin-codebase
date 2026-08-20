package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Makes fixed brand icon colors readable on whatever surface the host theme
 * paints underneath them.
 *
 * The file tree tints its icons with official brand colors (Kotlin purple,
 * GitHub/Apple/Markdown white, Prisma navy, …) while the host theme is user
 * selectable and may be light (Daylight, Blueprint Light) or dark (Blueprint,
 * Operator, Clean). A white mark disappears on paper; a near-black mark
 * disappears on ink.
 *
 * [adapt] keeps a brand color's hue and saturation and moves only its HSL
 * lightness — darker on light backgrounds, lighter on dark ones — until the
 * mark clears a contrast floor against the surface it is drawn on. Colors that
 * already clear the floor are returned untouched, so brand identity survives on
 * the theme the brand was picked for.
 */
object IconTheming {

    /**
     * Contrast floor for a color that carries a hue: WCAG 1.4.11's 3:1 for
     * non-text UI, nudged up a little because these glyphs are 16.dp. Kept low
     * on purpose — pushing a saturated brand color further than legibility
     * requires is what makes retinted icons look muddy.
     */
    private const val CHROMATIC_MIN_CONTRAST = 3.5f

    /**
     * Contrast floor for a near-neutral color (white, black, plain grey). These
     * have no hue to protect and read as "foreground colored" marks, so they
     * get a text-like floor instead: on a light theme a white mark lands near
     * the text color rather than at a barely-legible mid grey.
     */
    private const val NEUTRAL_MIN_CONTRAST = 6.0f

    /** Saturation at or below which a color is treated as neutral. */
    private const val NEUTRAL_SATURATION = 0.12f

    /** Binary-search steps over lightness; 12 lands well inside 8-bit precision. */
    private const val SEARCH_STEPS = 12

    /**
     * Cache of adapted colors, keyed by (background, brand) ARGB. Bounded in
     * practice by the number of themes times the number of brand colors, and
     * this runs per icon per recomposition, so the search must not.
     */
    private val cache = ConcurrentHashMap<Long, Color>()

    /**
     * Returns [brand] adapted for [background], or [brand] itself when it is
     * already legible there.
     *
     * @param brand the icon's official brand color
     * @param background the opaque surface the icon is drawn on (composite any
     *   translucent row fill over the panel background before passing it in)
     */
    fun adapt(brand: Color, background: Color): Color {
        // Fully transparent tints carry no color to fix.
        if (brand.alpha == 0f) return brand
        val key = (background.toArgb().toLong() shl 32) or (brand.toArgb().toLong() and 0xFFFFFFFFL)
        return cache.getOrPut(key) { compute(brand, background) }
    }

    private fun compute(brand: Color, background: Color): Color {
        val hsl = brand.toHsl()
        val floor = if (hsl.saturation <= NEUTRAL_SATURATION) {
            NEUTRAL_MIN_CONTRAST
        } else {
            CHROMATIC_MIN_CONTRAST
        }
        if (contrastRatio(brand, background) >= floor) return brand

        // Head whichever way has more headroom: on a light surface that is
        // darker, on a dark surface lighter. Deciding from the achievable
        // extremes (rather than a luminance threshold) also does the right
        // thing on the mid-tone surfaces some themes use.
        val backgroundLuminance = background.luminance()
        val towardBlack = (backgroundLuminance + 0.05f) / 0.05f
        val towardWhite = 1.05f / (backgroundLuminance + 0.05f)
        val lighten = towardWhite > towardBlack

        // Smallest lightness change that clears the floor. Contrast is
        // monotonic in lightness on the chosen side of the background's own
        // luminance, and every candidate on the wrong side falls below the
        // floor (which is >= 3:1), so the search cannot settle there.
        var lo = if (lighten) hsl.lightness else 0f
        var hi = if (lighten) 1f else hsl.lightness
        val extreme = hsl.copy(lightness = if (lighten) 1f else 0f).toColor(brand.alpha)
        // Some surfaces (mid greys) simply cannot host this floor; then the
        // most legible color available is the best answer.
        if (contrastRatio(extreme, background) < floor) return extreme

        var best = extreme
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2f
            val candidate = hsl.copy(lightness = mid).toColor(brand.alpha)
            if (contrastRatio(candidate, background) >= floor) {
                // Legible — record it and pull back toward the brand color.
                best = candidate
                if (lighten) hi = mid else lo = mid
            } else {
                if (lighten) lo = mid else hi = mid
            }
        }
        return best
    }

    /** WCAG relative-luminance contrast ratio, in 1..21. */
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

    private fun Color.toHsl(): Hsl {
        val r = red
        val g = green
        val b = blue
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val lightness = (maxC + minC) / 2f
        if (delta == 0f) return Hsl(0f, 0f, lightness)
        val saturation = delta / (1f - abs(2f * lightness - 1f))
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return Hsl(if (hue < 0f) hue + 360f else hue, saturation.coerceIn(0f, 1f), lightness)
    }

    private fun Hsl.toColor(alpha: Float): Color {
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val hPrime = hue / 60f
        val x = c * (1f - abs((hPrime % 2f) - 1f))
        val (r1, g1, b1) = when {
            hPrime < 1f -> Triple(c, x, 0f)
            hPrime < 2f -> Triple(x, c, 0f)
            hPrime < 3f -> Triple(0f, c, x)
            hPrime < 4f -> Triple(0f, x, c)
            hPrime < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = lightness - c / 2f
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = alpha
        )
    }
}
