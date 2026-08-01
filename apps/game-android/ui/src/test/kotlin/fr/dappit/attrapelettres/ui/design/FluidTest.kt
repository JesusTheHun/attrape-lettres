package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Every expected number below was computed BY HAND from the CSS in the TSX,
// never read back out of `fluid`. The arithmetic is `viewport * vw / 100`, then
// CSS clamp: `max(min, min(preferred, max))`.
//
// The three device widths are portrait dp widths: a small phone (375), a
// Pixel-class phone (402) and a tablet in its full window (1024). They are the
// same three the iOS suite uses, so the two ports' numbers can be diffed.

private const val SE = 375f
private const val PRO = 402f
private const val PAD = 1024f

private fun near(actual: Float, expected: Float, what: String) {
    assertTrue(
        kotlin.math.abs(actual - expected) < 1e-3f,
        "$what: got $actual, expected $expected",
    )
}

class FluidTest {

    // Boundaries -------------------------------------------------------------

    // `clamp(8px, 2.5vw, 16px)` -- the gap before a second syllable in
    // `SpellSyllableExercise`. Chosen because both boundaries land on a whole
    // viewport width: 8 / 0.025 = 320, 16 / 0.025 = 640.
    @Test
    fun hitsBothClampBoundariesExactly() {
        near(fluid(8f, 2.5f, 16f, 320f), 8f, "lower boundary")
        near(fluid(8f, 2.5f, 16f, 640f), 16f, "upper boundary")
        near(fluid(8f, 2.5f, 16f, 319f), 8f, "just below the lower boundary")
        near(fluid(8f, 2.5f, 16f, 324f), 8.1f, "just above the lower boundary")
        near(fluid(8f, 2.5f, 16f, 636f), 15.9f, "just below the upper boundary")
        near(fluid(8f, 2.5f, 16f, 641f), 16f, "just above the upper boundary")
    }

    @Test
    fun belowTheMinAndAboveTheMaxAreBothPinned() {
        // clamp(28px, 8vw, 44px) -- the hub title. 200 * 0.08 = 16 -> pinned to 28.
        near(fluid(28f, 8f, 44f, 200f), 28f, "far below")
        // 2000 * 0.08 = 160 -> pinned to 44.
        near(fluid(28f, 8f, 44f, 2000f), 44f, "far above")
    }

    @Test
    fun aZeroOrNegativeViewportStillYieldsTheAuthoredMinimum() {
        near(fluid(92f, 27f, 150f, 0f), 92f, "zero viewport")
        near(fluid(92f, 27f, 150f, -100f), 92f, "negative viewport")
    }

    // CSS defines `clamp(MIN, VAL, MAX)` as `max(MIN, min(VAL, MAX))`, so when an
    // author inverts the pair the MINIMUM wins. The other plausible spelling --
    // `min(max(MIN, VAL), MAX)` -- would return 10 here.
    @Test
    fun anInvertedClampResolvesToTheMinAsCssSpecifies() {
        near(fluid(50f, 1f, 10f, 1000f), 50f, "inverted clamp")
    }

    // The ramp is monotone non-decreasing in the viewport: a wider window never
    // makes a fluid element smaller. A `min`/`max` swapped anywhere in the
    // arithmetic breaks this and nothing else would notice.
    @Test
    fun theRampNeverGoesBackwardsAsTheViewportGrows() {
        val specs = listOf(
            FluidSpec(92f, 27f, 150f),
            FluidSpec(28f, 8f, 44f),
            FluidSpec(30f, 9f, 64f),
            FluidSpec(8f, 2.5f, 16f),
            FluidSpec(34f, 11f, 62f),
        )
        for (spec in specs) {
            var previous = spec.resolve(100f)
            var w = 101f
            while (w <= 1600f) {
                val current = spec.resolve(w)
                assertTrue(current >= previous, "$spec went backwards at $w")
                assertTrue(current in spec.min..spec.max, "$spec left its bounds at $w")
                previous = current
                w += 1f
            }
        }
    }

    // Real clamps at real device widths ---------------------------------------

    @Test
    fun hubTitleClamp28px8vw44px() {
        near(fluid(28f, 8f, 44f, SE), 30f, "small phone") // 375 * .08
        near(fluid(28f, 8f, 44f, PRO), 32.16f, "Pixel-class phone") // 402 * .08
        near(fluid(28f, 8f, 44f, PAD), 44f, "tablet") // 81.92 -> 44
    }

    @Test
    fun tileSideClamp92px27vw150px() {
        near(fluid(92f, 27f, 150f, SE), 101.25f, "small phone")
        near(fluid(92f, 27f, 150f, PRO), 108.54f, "Pixel-class phone")
        near(fluid(92f, 27f, 150f, PAD), 150f, "tablet") // 276.48 -> 150
    }

    @Test
    fun tileGlyphClamp30px9vw64px() {
        near(fluid(30f, 9f, 64f, SE), 33.75f, "small phone")
        near(fluid(30f, 9f, 64f, PRO), 36.18f, "Pixel-class phone")
        near(fluid(30f, 9f, 64f, PAD), 64f, "tablet") // 92.16 -> 64
    }

    @Test
    fun listenButtonAndTheInvariantSixFloor() {
        near(fluid(40f, 11f, 52f, SE), 41.25f, "height, small phone")
        near(fluid(40f, 11f, 52f, PRO), 44.22f, "height, Pixel-class phone")
        near(fluid(40f, 11f, 52f, PAD), 52f, "height, tablet")

        near(fluid(16f, 4.5f, 22f, SE), 16.875f, "type, small phone")
        near(fluid(16f, 4.5f, 22f, PRO), 18.09f, "type, Pixel-class phone")
        near(fluid(16f, 4.5f, 22f, PAD), 22f, "type, tablet")

        // Invariant 6's floor: the Écouter button never drops under 40 dp, and the
        // tile it sits under never under 92 dp, at ANY device width.
        var w = 200f
        while (w <= 1400f) {
            assertTrue(fluid(40f, 11f, 52f, w) >= 40f, "Écouter under 40 dp at $w")
            assertTrue(fluid(92f, 27f, 150f, w) >= 92f, "tile under 92 dp at $w")
            w += 1f
        }
    }

    @Test
    fun bigEmojiClamps() {
        near(fluid(64f, 20f, 110f, SE), 75f, "celebration, small phone")
        near(fluid(64f, 20f, 110f, PRO), 80.4f, "celebration, Pixel-class phone")
        near(fluid(64f, 20f, 110f, PAD), 110f, "celebration, tablet")

        near(fluid(56f, 17f, 90f, SE), 63.75f, "pause, small phone")
        near(fluid(56f, 17f, 90f, PRO), 68.34f, "pause, Pixel-class phone")
        near(fluid(56f, 17f, 90f, PAD), 90f, "pause, tablet")
    }

    @Test
    fun dashboardBalance() {
        near(fluid(34f, 11f, 62f, SE), 41.25f, "type, small phone")
        near(fluid(34f, 11f, 62f, PRO), 44.22f, "type, Pixel-class phone")
        near(fluid(34f, 11f, 62f, PAD), 62f, "type, tablet")

        near(fluid(8f, 2.6f, 15f, SE), 9.75f, "pad-y, small phone")
        near(fluid(22f, 6.5f, 36f, SE), 24.375f, "pad-x, small phone")
    }

    // The trap D16 exists to prevent -------------------------------------------

    // `index.css` caps the card at 480 px while `vw` keeps growing. On a tablet
    // the hub title is 44 dp (8vw of 1024 = 81.92, clamped) -- NOT 38.4 dp, which
    // is what a `BoxWithConstraints` around the 480 dp card would produce. A
    // naive port passes every other test in this file and fails this one.
    @Test
    fun vwResolvesAgainstTheWindowNotThe480dpCard() {
        val card = Shell.cardWidth(PAD)
        near(card, 480f, "tablet card width")

        val correct = fluid(28f, 8f, 44f, PAD)
        val wrong = fluid(28f, 8f, 44f, card)
        near(correct, 44f, "title against the window")
        near(wrong, 38.4f, "title against the card -- the bug")
        assertNotEquals(correct, wrong)
    }

    @Test
    fun theCardShellMirrorsIndexCss() {
        near(Shell.cardWidth(375f), 343f, "small phone") // 375 - 32
        near(Shell.cardWidth(402f), 370f, "Pixel-class phone")
        near(Shell.cardWidth(1024f), 480f, "tablet") // capped
        assertEquals(480.dp, Shell.cardMaxWidth)
        assertEquals(16.dp, Shell.minimumInset)
        assertEquals(620.dp, Shell.minimumScreenHeight)
        assertEquals(343.dp, Shell.cardWidth(375.dp))
    }

    // Percentage clamps ---------------------------------------------------------

    // The Dashboard's mascot pedestal, `clamp(190px, 62%, 300px)`, is the one
    // clamp resolved against the CONTAINER. 480 * 0.62 = 297.6.
    @Test
    fun fluidPercentResolvesAgainstTheContainer() {
        near(fluidPercent(190f, 62f, 300f, 480f), 297.6f, "480 dp card")
        near(fluidPercent(190f, 62f, 300f, 343f), 212.66f, "343 dp card")
        near(fluidPercent(190f, 62f, 300f, 600f), 300f, "over the max")
        near(fluidPercent(190f, 62f, 300f, 280f), 190f, "under the min")
        near(fluidPercent(190.dp, 62f, 300.dp, 480.dp).value, 297.6f, "the Dp overload")
    }

    // The Dp overloads and the spec value type ----------------------------------

    @Test
    fun theDpOverloadAgreesWithTheRawArithmetic() {
        for (w in listOf(200f, 320f, 375f, 402f, 480f, 640f, 1024f, 1400f)) {
            near(
                fluid(92.dp, 27f, 150.dp, w.dp).value,
                fluid(92f, 27f, 150f, w),
                "Dp overload at $w",
            )
        }
    }

    @Test
    fun fluidSpecResolvesIdenticallyToTheFreeFunction() {
        val spec = FluidSpec(92f, 27f, 150f)
        for (w in listOf(200f, 320f, 375f, 402f, 480f, 640f, 1024f, 1400f)) {
            near(spec.resolve(w), fluid(92f, 27f, 150f, w), "at $w")
            near(spec.resolve(w.dp).value, fluid(92f, 27f, 150f, w), "Dp at $w")
        }
    }

    // A font size resolved from a clamp must not move with the system font
    // scale: the tile it sits in came from the same clamp in dp, which does not
    // move either, so a scaled glyph would overflow a fixed tile. Dividing by the
    // scale is what cancels Compose's scaling.
    @Test
    fun fixedSpCancelsTheSystemFontScale() {
        near(fixedSp(64f, 1f).value, 64f, "no scaling")
        near(fixedSp(64f, 2f).value, 32f, "2x font scale")
        near(fixedSp(64f, 0.85f).value, 64f / 0.85f, "shrunken font scale")
        // A degenerate scale must not divide by zero and blank the screen.
        near(fixedSp(64f, 0f).value, 64f, "zero font scale")

        val spec = FluidSpec(30f, 9f, 64f)
        near(spec.resolveSp(PRO.dp, 1f).value, 36.18f, "glyph at 1x")
        near(spec.resolveSp(PRO.dp, 2f).value, 18.09f, "glyph at 2x")
    }
}
