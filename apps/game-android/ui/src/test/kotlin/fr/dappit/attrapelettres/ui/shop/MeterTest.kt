package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.inertScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// `shop/Meter.kt` against `src/shop/Meter.tsx` + `src/shop/anim.ts`.
//
// Every expected number below is read out of the TYPESCRIPT, never out of the
// Kotlin: an assertion sourced from the code under test proves only that the
// code equals itself.
//
//   const MIN_FILL = 0.07;
//   function fillRatio(balance, cost) {
//     if (balance <= 0 || cost <= 0) return 0;
//     return Math.max(MIN_FILL, Math.min(1, balance / cost));
//   }
// ---------------------------------------------------------------------------

/**
 * `MIN_FILL` in `Meter.tsx`, typed out here rather than referenced, so a change
 * to the Kotlin constant fails this suite instead of silently redefining truth.
 */
private const val TS_MIN_FILL = 0.07

/** `Math.max(MIN_FILL, Math.min(1, balance / cost))`, transcribed. */
private fun tsFillRatio(balance: Int, cost: Int): Double {
    if (balance <= 0 || cost <= 0) return 0.0
    return maxOf(TS_MIN_FILL, minOf(1.0, balance.toDouble() / cost.toDouble()))
}

class SavingsFillRatioTest {

    /**
     * `if (balance <= 0 || cost <= 0) return 0` — an empty wallet is a genuinely
     * empty bar. The `MIN_FILL` sliver is for « I have SOME », not « I have
     * none ».
     */
    @Test
    fun `an empty wallet is an empty bar, not a sliver`() {
        assertEquals(0.0, savingsFillRatio(0, 100))
        assertEquals(0.0, savingsFillRatio(-1, 100))
        assertEquals(0.0, savingsFillRatio(-999, 100))
    }

    /** The second guard: a zero or negative price would divide by nothing. */
    @Test
    fun `a non-positive cost is zero, not infinity`() {
        assertEquals(0.0, savingsFillRatio(40, 0))
        assertEquals(0.0, savingsFillRatio(40, -8))
        assertEquals(0.0, savingsFillRatio(0, 0))
    }

    /**
     * « A non-empty wallet always shows a sliver, so "I have some" is always
     * visible. » One star against a hundred is 1 %, drawn as 7 %.
     */
    @Test
    fun `one star against a hundred is floored at MIN_FILL`() {
        assertEquals(TS_MIN_FILL, savingsFillRatio(1, 100))
        assertEquals(TS_MIN_FILL, savingsFillRatio(6, 100))
    }

    /**
     * The floor's exact boundary: 7/100 IS 0.07, and 8/100 is already above it,
     * so the `Math.max` stops biting at exactly `MIN_FILL`.
     */
    @Test
    fun `the floor releases at exactly MIN_FILL`() {
        assertEquals(TS_MIN_FILL, savingsFillRatio(7, 100))
        assertEquals(0.08, savingsFillRatio(8, 100))
    }

    @Test
    fun `midway is the plain quotient`() {
        assertEquals(0.5, savingsFillRatio(50, 100))
        assertEquals(0.75, savingsFillRatio(3, 4))
        assertEquals(tsFillRatio(1, 3), savingsFillRatio(1, 3))
    }

    /**
     * Exactly affordable — `Math.min(1, 1)`. The bar is full the instant the
     * child can buy, not one star later.
     */
    @Test
    fun `exactly affordable is full`() {
        assertEquals(1.0, savingsFillRatio(100, 100))
        assertEquals(1.0, savingsFillRatio(4, 4))
    }

    /** Past full — `Math.min(1, …)` clamps, so a rich child never overflows. */
    @Test
    fun `past full clamps to one`() {
        assertEquals(1.0, savingsFillRatio(101, 100))
        assertEquals(1.0, savingsFillRatio(100_000, 4))
    }

    /** The whole curve, against a transcription of the TypeScript body. */
    @Test
    fun `agrees with the TypeScript across the range`() {
        for (cost in listOf(1, 3, 4, 12, 100)) {
            for (balance in listOf(-3, 0, 1, 2, 7, 8, 11, 50, 99, 100, 400)) {
                assertEquals(
                    tsFillRatio(balance, cost),
                    savingsFillRatio(balance, cost),
                    "balance $balance / cost $cost",
                )
            }
        }
    }

    /** `MIN_FILL`, `height = 10`, and the track's `rgba(90,58,30,0.14)`. */
    @Test
    fun `metrics match the authored CSS`() {
        assertEquals(TS_MIN_FILL, SavingsMeterMetrics.MIN_FILL)
        assertEquals(10f, SavingsMeterMetrics.DEFAULT_HEIGHT.value)
        assertEquals(0.14f, SavingsMeterMetrics.TRACK_OPACITY)
        // 90/58/30 is #5A3A1E, the ink brown, at 14 %.
        assertEquals("#5A3A1E", Palette.ink.hex)
        // `linear-gradient(90deg,#FFC107,#FFD54F)`.
        assertEquals(90f, Palette.savingsFill.degrees)
        assertEquals(listOf("#FFC107", "#FFD54F"), Palette.savingsFill.stops.map { it.hex })
        // `text-sm` on the ✨.
        assertEquals(Typography.Size.sm, SavingsMeterMetrics.SPARKLE_SIZE)
        assertEquals(14f, SavingsMeterMetrics.SPARKLE_SIZE.value)
    }

    /** `aria-label={`${balance} étoiles sur ${cost}`}` — « étoiles » is NOT pluralised. */
    @Test
    fun `the accessibility label is the TSX template, unpluralised`() {
        assertEquals("5 étoiles sur 12", Copy.Shop.savings(5, 12))
        assertEquals("1 étoiles sur 12", Copy.Shop.savings(1, 12))
        assertEquals("0 étoiles sur 12", Copy.Shop.savings(0, 12))
    }
}

// ---------------------------------------------------------------------------
// The mount sweep:
//   const from = shownRef.current;   // fillRatio(since, cost)
//   if (from === ratio) return;
//   meterFill(fill, from * 100, ratio * 100);
//   if (ratio > from) tipSparkle(tip);
// ---------------------------------------------------------------------------

class SavingsMeterSweepTest {

    @Test
    fun `sweeps from the balance the child last saw`() {
        val step = savingsMeterSweep(balance = 60, since = 20, cost = 100)
        assertEquals(0.2, step.from)
        assertEquals(0.6, step.to)
        assertTrue(step.animates)
        assertTrue(step.sparkles)
    }

    /**
     * `if (from === ratio) return` — a shop visit with nothing earned in between
     * does not animate at all.
     */
    @Test
    fun `an unchanged balance does not animate`() {
        val step = savingsMeterSweep(balance = 60, since = 60, cost = 100)
        assertEquals(step.from, step.to)
        assertFalse(step.animates)
        assertFalse(step.sparkles)
    }

    /**
     * `if (ratio > from)` — a shrinking bar (the child spent) still sweeps, but
     * silently. No sparkle for losing stars.
     */
    @Test
    fun `spending sweeps without a sparkle`() {
        val step = savingsMeterSweep(balance = 10, since = 90, cost = 100)
        assertEquals(0.9, step.from)
        assertEquals(0.1, step.to)
        assertTrue(step.animates)
        assertFalse(step.sparkles)
    }

    /**
     * The `MIN_FILL` floor swallows a real gain: 1 → 5 stars against 100 both
     * render at 0.07, so `from === ratio` and the TSX skips both animations.
     * Ported behaviour, not a bug — the pixels genuinely did not move.
     */
    @Test
    fun `a gain hidden by the floor is not animated`() {
        val step = savingsMeterSweep(balance = 5, since = 1, cost = 100)
        assertEquals(TS_MIN_FILL, step.from)
        assertEquals(TS_MIN_FILL, step.to)
        assertFalse(step.animates)
        assertFalse(step.sparkles)
    }

    /** First ever visit: `since` is 0, so the bar grows out of nothing and sparkles. */
    @Test
    fun `a first visit grows from empty`() {
        val step = savingsMeterSweep(balance = 3, since = 0, cost = 100)
        assertEquals(0.0, step.from)
        assertEquals(TS_MIN_FILL, step.to)
        assertTrue(step.animates)
        assertTrue(step.sparkles)
    }

    /** Both ends clamp: a child who could already afford it twice sees no movement. */
    @Test
    fun `both ends clamp at full`() {
        val step = savingsMeterSweep(balance = 300, since = 200, cost = 100)
        assertEquals(1.0, step.from)
        assertEquals(1.0, step.to)
        assertFalse(step.animates)
    }

    /**
     * `savingsMeterSweep` is `SavingsMeterSweep(from, to)` over `fillRatio` —
     * asserted independently so the convenience cannot drift from the pair.
     */
    @Test
    fun `the convenience matches the two ratios`() {
        val cases = listOf(
            Triple(0, 0, 5),
            Triple(9, 4, 12),
            Triple(12, 0, 12),
            Triple(2, 40, 40),
        )
        for ((balance, since, cost) in cases) {
            val step = savingsMeterSweep(balance = balance, since = since, cost = cost)
            assertEquals(tsFillRatio(since, cost), step.from)
            assertEquals(tsFillRatio(balance, cost), step.to)
        }
    }
}

// ---------------------------------------------------------------------------
// The two WAAPI takes, as data.
// ---------------------------------------------------------------------------

class SavingsMeterMotionTest {

    /** `{ duration: 900, easing: "cubic-bezier(.2,.7,.3,1)" }` in `meterFill`. */
    @Test
    fun `the fill sweep is nine hundred milliseconds on the authored curve`() {
        assertEquals(900, SavingsMeterMetrics.SWEEP_DURATION_MS)
        assertEquals(0.2f, SavingsMeterMetrics.SWEEP_CURVE.a)
        assertEquals(0.7f, SavingsMeterMetrics.SWEEP_CURVE.b)
        assertEquals(0.3f, SavingsMeterMetrics.SWEEP_CURVE.c)
        assertEquals(1f, SavingsMeterMetrics.SWEEP_CURVE.d)
    }

    /**
     * `tipSparkle`: `{ duration: 620, delay: 780, easing: "ease-out" }` over
     * `scale 0.2 → 1.4 (offset .5) → 0.6` and `opacity 0 → 1 → 0`.
     */
    @Test
    fun `the sparkle keyframes are the authored ones`() {
        assertEquals(620, SavingsMeterMetrics.SPARKLE_DURATION_MS)
        assertEquals(780, SavingsMeterMetrics.SPARKLE_DELAY_MS)
        assertEquals(listOf(0.2f, 1.4f, 0.6f), SavingsMeterMetrics.SPARKLE_SCALE.values)
        assertEquals(listOf(0f, 1f, 0f), SavingsMeterMetrics.SPARKLE_ALPHA.values)
        assertEquals(listOf(0f, 0.5f, 1f), SavingsMeterMetrics.SPARKLE_SCALE.keyTimes)
        // CSS `ease-out` = cubic-bezier(0, 0, 0.58, 1) — NOT Material's.
        assertEquals(Anim.EASE_OUT, SavingsMeterMetrics.SPARKLE_SCALE.easing)
        assertEquals(Anim.EASE_OUT, SavingsMeterMetrics.SPARKLE_ALPHA.easing)
    }

    /**
     * The endpoints of both tracks, sampled. The resting opacity is 0 at BOTH
     * ends: there is no `fill` mode in the TSX's `el.animate(…)`, so the sparkle
     * vanishes rather than sticking.
     */
    @Test
    fun `the sparkle starts and ends invisible`() {
        assertEquals(0f, SavingsMeterMetrics.SPARKLE_ALPHA.at(0f))
        assertEquals(1f, SavingsMeterMetrics.SPARKLE_ALPHA.at(0.5f))
        assertEquals(0f, SavingsMeterMetrics.SPARKLE_ALPHA.at(1f))
        assertEquals(0.2f, SavingsMeterMetrics.SPARKLE_SCALE.at(0f))
        assertEquals(1.4f, SavingsMeterMetrics.SPARKLE_SCALE.at(0.5f))
        assertEquals(0.6f, SavingsMeterMetrics.SPARKLE_SCALE.at(1f))
    }

    /**
     * INVARIANT 6. `shop/anim.ts` gates `meterFill` and `tipSparkle` itself, so
     * a reduced-motion meter animates nothing — and still lands on the right
     * width, which is what [SavingsMeterMotion.sweep]'s `snapTo` branch is for.
     */
    @Test
    fun `reduced motion silences the meter but never hides the value`() {
        val still = SavingsMeterMotion(inertScope(), FixedReduceMotion(true), 0f)
        val moving = SavingsMeterMotion(inertScope(), FixedReduceMotion(false), 0f)
        assertTrue(still.gated)
        assertFalse(moving.gated)
        // The bar's resting value is the ratio it was seeded with, not zero-ing
        // out under the gate.
        assertEquals(
            0.42f,
            SavingsMeterMotion(inertScope(), FixedReduceMotion(true), 0.42f).shown.value,
        )
    }
}
