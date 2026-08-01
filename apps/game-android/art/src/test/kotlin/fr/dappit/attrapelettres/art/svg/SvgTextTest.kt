package fr.dappit.attrapelettres.art.svg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The `textAnchor="middle"` + `dominantBaseline="central"` arithmetic, ported
// from the iOS `SVGTextMetricsTests`. Pure functions checked against known font
// metrics instead of only by eye: getting the anchoring wrong shifts a letter
// by a couple of units, which is invisible in review and loud in a pixel diff.

class SvgTextMetricsTest {

    @Test
    fun `dominant-baseline central puts the alphabetic baseline below the anchor`() {
        // A typical UI face's metrics at size 17: ascent about 0.956 em,
        // descent about 0.21 em.
        val m = SvgTextMetrics(ascent = 0.956 * 17, descent = 0.21 * 17)
        // baseline = y + (ascent - descent)/2 = 17.5 + (16.252 - 3.57)/2
        assertTrue(abs(m.baselineY(anchoredAt = 17.5) - 23.841) < 1e-3)
    }

    @Test
    fun `a symmetric font puts the line-box centre exactly on the anchor`() {
        // The identity the implementation relies on: top = y - (a + d)/2, so
        // the centre of the line box lands on y whatever a and d are.
        val m = SvgTextMetrics(ascent = 12.0, descent = 4.0)
        val topLeft = m.topLeft(x = 15.0, y = 17.5, width = 10.0)
        assertEquals(10f, topLeft.x) // textAnchor="middle"
        assertEquals(17.5f - 8f, topLeft.y) // (12 + 4) / 2
        assertEquals(17.5, topLeft.y + (12 + 4) / 2.0)
    }

    @Test
    fun `a descender-heavy face still centres on the anchor, it does not sit on the baseline`() {
        val m = SvgTextMetrics(ascent = 20.0, descent = 8.0)
        // The naive bug is anchoring the layout's centre computed from the
        // BASELINE instead of the line box, which would shift by (a - d)/2 = 6.
        assertEquals(-14f, m.topLeft(x = 0.0, y = 0.0, width = 0.0).y)
        assertEquals(6.0, m.baselineY(anchoredAt = 0.0))
    }

    @Test
    fun `an icon text carries the TSX defaults - weight 900, white fill`() {
        // The four glyphs in the catalog all rely on these defaults; a changed
        // default would silently restyle every letterform at once.
        val t = IconText("A", x = 15.0, y = 17.5, size = 17.0)
        assertEquals(900, t.weight)
        assertEquals("#fff", t.fill)
    }
}
