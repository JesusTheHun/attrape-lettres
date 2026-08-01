package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.ui.interaction.MotionSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// `shop/ShopAnim.kt` against `src/shop/anim.ts`.
//
// A host JUnit run has no frame clock, so nothing here drives an `Animatable`.
// What it CAN pin is everything the port could get wrong without a device
// noticing: the authored numbers, the per-interval easing, the geometry of the
// two celebrations, and the reduce-motion gate that separates this file from
// `Tile.tsx`.
// ---------------------------------------------------------------------------

class ShopCelebrationGateTest {

    /** The two authored bezier curves, as CSS writes them. */
    @Test
    fun `the flight and sparkle curves are the authored beziers`() {
        // `cubic-bezier(.3,.6,.4,1)`
        assertEquals(0.3f, ShopAnim.FLIGHT_CURVE.a)
        assertEquals(0.6f, ShopAnim.FLIGHT_CURVE.b)
        assertEquals(0.4f, ShopAnim.FLIGHT_CURVE.c)
        assertEquals(1f, ShopAnim.FLIGHT_CURVE.d)
        // `cubic-bezier(.2,.7,.3,1)` — shared with the meter sweep.
        assertEquals(0.2f, ShopAnim.SPARK_CURVE.a)
        assertEquals(0.7f, ShopAnim.SPARK_CURVE.b)
        assertEquals(0.3f, ShopAnim.SPARK_CURVE.c)
        assertEquals(1f, ShopAnim.SPARK_CURVE.d)
        // `z-index: 60` on the throwaway layer, over the try-on dialog's z-50.
        assertEquals(60f, ShopAnim.Z_INDEX)
    }

    /**
     * INVARIANT 6 / D29. `shop/anim.ts` gates every helper on
     * `prefers-reduced-motion`; `Tile.tsx` gates nothing. That asymmetry is why
     * the shop has its own gate at all — and the celebrations must share the
     * ONE the shop already has, not answer the question a second time.
     */
    @Test
    fun `the celebrations share the shop gate, and the tile keeps none`() {
        val still = FixedReduceMotion(true)
        val moving = FixedReduceMotion(false)

        assertFalse(MotionSurface.PRESS.gatedByReduceMotion)

        assertEquals(shopMotionAllowed(still), ShopAnim.shouldAnimate(still))
        assertEquals(shopMotionAllowed(moving), ShopAnim.shouldAnimate(moving))
        assertFalse(ShopAnim.shouldAnimate(still))
        assertTrue(ShopAnim.shouldAnimate(moving))
    }
}

class MotionTrackTest {

    /**
     * WAAPI's `easing` applies to each keyframe INTERVAL, not to the timeline.
     * The endpoints of every interval must therefore land exactly on the
     * authored keyframe values, whatever curve is in play.
     */
    @Test
    fun `every keyframe is hit exactly`() {
        val track = MotionTrack(
            values = listOf(0f, 10f, 4f),
            keyTimes = listOf(0f, 0.35f, 1f),
            easing = ShopAnim.SPARK_CURVE,
        )
        assertEquals(0f, track.at(0f))
        assertEquals(10f, track.at(0.35f))
        assertEquals(4f, track.at(1f))
    }

    /** Out-of-range progress clamps rather than extrapolating. */
    @Test
    fun `progress clamps at both ends`() {
        val track = MotionTrack(listOf(2f, 8f), listOf(0f, 1f), ShopAnim.SPARK_CURVE)
        assertEquals(2f, track.at(-3f))
        assertEquals(8f, track.at(9f))
    }

    /** Between keyframes the value stays inside the interval it belongs to. */
    @Test
    fun `an interval stays inside its own two values`() {
        val track = MotionTrack(listOf(0f, 100f, 0f), listOf(0f, 0.5f, 1f), ShopAnim.SPARK_CURVE)
        val early = track.at(0.25f)
        val late = track.at(0.75f)
        assertTrue(early > 0f && early < 100f, "early = $early")
        assertTrue(late > 0f && late < 100f, "late = $late")
    }
}

class StarFlightSpecTest {

    private val wallet = ShopRect(0f, 0f, 40f, 40f)
    private val mascot = ShopRect(200f, 300f, 60f, 60f)

    /**
     * `if (a.width === 0 || b.width === 0) return;` — a rect that has not laid
     * out yet aborts the WHOLE flight, not just one star.
     */
    @Test
    fun `an unmeasured rect aborts the flight`() {
        assertTrue(StarFlightSpec.particles(null, mascot, 5).isEmpty())
        assertTrue(StarFlightSpec.particles(wallet, null, 5).isEmpty())
        assertTrue(
            StarFlightSpec.particles(ShopRect(0f, 0f, 0f, 10f), mascot, 5).isEmpty(),
        )
        assertTrue(
            StarFlightSpec.particles(wallet, ShopRect(0f, 0f, 0f, 10f), 5).isEmpty(),
        )
    }

    /** `duration: 620 + i * 45`, `delay: i * 70` — the stagger that fans the flock. */
    @Test
    fun `each star is slower and later than the last`() {
        val stars = StarFlightSpec.particles(wallet, mascot, 4)
        assertEquals(4, stars.size)
        assertEquals(listOf(620, 665, 710, 755), stars.map { it.durationMillis })
        assertEquals(listOf(0, 70, 140, 210), stars.map { it.delayMillis })
        assertTrue(stars.all { it.glyph == "⭐" && it.fontSize == 22f })
    }

    /**
     * Every star starts at the wallet's centre and ends at the mascot's, whatever
     * its bow: the flight has to READ as stars leaving the purse.
     */
    @Test
    fun `every star flies wallet-centre to mascot-centre`() {
        val stars = StarFlightSpec.particles(wallet, mascot, 6)
        for (star in stars) {
            assertEquals(wallet.midX, star.x.at(0f))
            assertEquals(wallet.midY, star.y.at(0f))
            assertEquals(mascot.midX, star.x.at(1f))
            assertEquals(mascot.midY, star.y.at(1f))
        }
    }

    /**
     * « Each star arcs on its own bow: the midpoint bulges sideways/upward a bit
     * more per star, so the flock fans out instead of forming a single file. »
     * `bow = (i % 2 === 0 ? 1 : -1) * (14 + i * 7)` — alternating sides, growing.
     */
    @Test
    fun `the bows alternate sides and grow`() {
        val stars = StarFlightSpec.particles(wallet, mascot, 4)
        val straightMidX = wallet.midX + (mascot.midX - wallet.midX) / 2f
        val bows = stars.map { it.x.at(0.5f) - straightMidX }
        assertEquals(listOf(14f, -21f, 28f, -35f), bows)
        // …and every midpoint is lifted, more so per star: `-36 - i * 4`.
        val straightMidY = wallet.midY + (mascot.midY - wallet.midY) / 2f
        assertEquals(listOf(-36f, -40f, -44f, -48f), stars.map { it.y.at(0.5f) - straightMidY })
    }

    /** `scale 0.5 → 1.15 → 0.35`, `opacity 0 → 1 → 0.2`. */
    @Test
    fun `a star swells then shrinks away`() {
        val star = StarFlightSpec.particles(wallet, mascot, 1).single()
        assertEquals(0.5f, star.scale.at(0f))
        assertEquals(1.15f, star.scale.at(0.5f))
        assertEquals(0.35f, star.scale.at(1f))
        assertEquals(0f, star.alpha.at(0f))
        assertEquals(1f, star.alpha.at(0.5f))
        assertEquals(0.2f, star.alpha.at(1f))
    }
}

class GrowBurstSpecTest {

    private val anchor = ShopRect(0f, 0f, 100f, 100f)

    /** `if (rect.width === 0) return;` */
    @Test
    fun `an unmeasured anchor bursts nothing`() {
        assertTrue(GrowBurstSpec.particles(null).isEmpty())
        assertTrue(GrowBurstSpec.particles(ShopRect(0f, 0f, 0f, 80f)).isEmpty())
    }

    /** Three ☁️ puffs and twelve sparkles, in that order. */
    @Test
    fun `three puffs and twelve sparkles`() {
        val burst = GrowBurstSpec.particles(anchor)
        assertEquals(15, burst.size)
        assertEquals(3, burst.count { it.glyph == "☁️" })
        assertEquals(12, burst.count { it.glyph != "☁️" })
        assertTrue(burst.take(3).all { it.glyph == "☁️" })
    }

    /**
     * `const cloud = rect.width * 0.42` is BOTH the puff's font size and the
     * unit its sideways offsets are measured in, and the three are staggered by
     * 60 ms.
     */
    @Test
    fun `the puffs are sized and staggered off the anchor`() {
        val puffs = GrowBurstSpec.particles(anchor).take(3)
        assertTrue(puffs.all { it.fontSize == 42f })
        assertTrue(puffs.all { it.durationMillis == 900 })
        assertEquals(listOf(0, 60, 120), puffs.map { it.delayMillis })
        // `dx` of 0, −cloud/2, +cloud/2 off the anchor's centre.
        assertEquals(listOf(50f, 29f, 71f), puffs.map { it.x.at(0f) })
        // The centre rides `+0.1·h → −0.2·h → −0.7·h`.
        assertEquals(50f + 4.2f, puffs[0].y.at(0f))
        assertEquals(50f - 8.4f, puffs[0].y.at(0.4f))
        assertEquals(50f - 29.4f, puffs[0].y.at(1f))
    }

    /** The four sparkle glyphs cycle, and sizes/durations run in threes. */
    @Test
    fun `the sparkles cycle their glyphs and their tempos`() {
        val sparks = GrowBurstSpec.particles(anchor).drop(3)
        assertEquals(
            listOf("✨", "⭐", "🌟", "💫", "✨", "⭐", "🌟", "💫", "✨", "⭐", "🌟", "💫"),
            sparks.map { it.glyph },
        )
        assertEquals(listOf(16f, 22f, 28f, 16f), sparks.take(4).map { it.fontSize })
        assertEquals(listOf(720, 840, 960, 720), sparks.take(4).map { it.durationMillis })
        // Every sparkle starts at the anchor's centre and spins as it flies.
        for (spark in sparks) {
            assertEquals(anchor.midX, spark.x.at(0f))
            assertEquals(anchor.midY, spark.y.at(0f))
            assertEquals(0f, spark.rotationDegrees.at(0f))
            assertEquals(200f, spark.rotationDegrees.at(1f))
            assertEquals(0f, spark.alpha.at(0f))
            assertEquals(0f, spark.alpha.at(1f))
        }
    }
}

class ShopCelebrationsTest {

    private val wallet = ShopRect(0f, 0f, 40f, 40f)
    private val mascot = ShopRect(200f, 300f, 60f, 60f)

    /** A celebration spawns its particles and they retire one at a time. */
    @Test
    fun `particles spawn and retire`() {
        val celebrations = ShopCelebrations(FixedReduceMotion(false))
        assertTrue(celebrations.particles.isEmpty())
        celebrations.starFlight(wallet, mascot, 5)
        assertEquals(5, celebrations.particles.size)
        celebrations.retire(celebrations.particles.first())
        assertEquals(4, celebrations.particles.size)
        celebrations.clear()
        assertTrue(celebrations.particles.isEmpty())
    }

    /** INVARIANT 6 — reduced motion means nothing is spawned at all. */
    @Test
    fun `reduced motion spawns nothing`() {
        val celebrations = ShopCelebrations(FixedReduceMotion(true))
        celebrations.starFlight(wallet, mascot, 5)
        celebrations.growBurst(mascot)
        assertTrue(celebrations.particles.isEmpty())
    }
}
