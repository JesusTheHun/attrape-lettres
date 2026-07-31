package fr.dappit.attrapelettres.core.mascot

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * New coverage — `src/mascot/growth.ts` had no TypeScript suite of its own; it
 * was only ever exercised through `anchors.test.ts` and by eye in Storybook.
 * This is a port of the iOS suite that filled the gap (`MascotGrowthTests.swift`).
 *
 * Every expected number here was produced by RUNNING the TypeScript (esbuild →
 * node) and pasted in, including the ones that are ugly
 * (`headCY` at stade 3 is 45.690000000000005, not 45.69). They are the oracle:
 * if the Kotlin changes and one fails, the Kotlin is wrong. Rounding them to
 * look tidy would be the port bug this file exists to catch.
 */
class GrowthTest {

    /* ramp, mix, stageScale, poseFor ----------------------------------------- */

    @Test
    fun `ramp clamps outside the stops and interpolates linearly inside`() {
        val stops = listOf(RampStop(0.0, 5.0), RampStop(3.0, 11.0))
        assertEquals(5.0, ramp(-1.0, stops)) // before the first stop
        assertEquals(5.0, ramp(0.0, stops))
        assertEquals(8.0, ramp(1.5, stops)) // half way
        assertEquals(11.0, ramp(3.0, stops))
        assertEquals(11.0, ramp(9.0, stops)) // after the last stop
    }

    @Test
    fun `ramp walks multi-segment stop lists, each segment on its own slope`() {
        // Segment slopes differ, so a single-segment implementation passes the
        // ends and fails the middle.
        val stops = listOf(RampStop(0.0, 0.0), RampStop(1.0, 10.0), RampStop(5.0, 12.0))
        assertEquals(5.0, ramp(0.5, stops))
        assertEquals(10.0, ramp(1.0, stops))
        assertEquals(11.0, ramp(3.0, stops))
    }

    @Test
    fun `ramp survives a one-stop list`() {
        assertEquals(7.0, ramp(4.0, listOf(RampStop(2.0, 7.0))))
        assertEquals(7.0, ramp(0.0, listOf(RampStop(2.0, 7.0))))
    }

    @Test
    fun `stageScale is the reward curve, clamped at both ends`() {
        val want = listOf(1.0, 1.0, 1.0, 1.0, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3)
        for (s in 0 until 10) assertEquals(want[s], stageScale(s), "stade $s")
        assertEquals(1.0, stageScale(-3))
        assertEquals(1.3, stageScale(42))
        // Monotone: every stade from 3 up is at least as big as the one before,
        // which is the whole point ("the mascot visibly gets bigger").
        for (s in 1 until 10) assertTrue(stageScale(s) >= stageScale(s - 1))
    }

    @Test
    fun `poseFor walks lying, wobbly, standing, proud`() {
        assertEquals(Pose.LYING, poseFor(0.0))
        assertEquals(Pose.LYING, poseFor(1.0))
        assertEquals(Pose.WOBBLY, poseFor(2.0))
        assertEquals(Pose.WOBBLY, poseFor(3.0))
        assertEquals(Pose.STANDING, poseFor(4.0))
        assertEquals(Pose.STANDING, poseFor(6.0))
        assertEquals(Pose.PROUD, poseFor(7.0))
        assertEquals(Pose.PROUD, poseFor(9.0))
        // Unclamped in the TS — poseFor is called with the raw stage.
        assertEquals(Pose.LYING, poseFor(-1.0))
        assertEquals(Pose.PROUD, poseFor(99.0))
    }

    @Test
    fun `mix blends two hex colours and always returns six lowercase digits`() {
        assertEquals("#808080", mix("#000000", "#FFFFFF", 0.5))
        assertEquals("#83612a", mix("#5A3A1E", "#FFD54F", 0.25))
        assertEquals("#5a3a1e", mix("#5A3A1E", "#FFD54F", 0.0)) // lowercased, not echoed
        assertEquals("#ffd54f", mix("#5A3A1E", "#FFD54F", 1.0))
        assertEquals("#00ff00", mix("#FF0000", "#00FF00", 1.0))
        assertEquals("#bfae9f", mix("#FF8A65", "#7FD1D8", 0.5))
        // Leading zeroes survive the `(1 shl 24) + …` trick.
        assertEquals("#000102", mix("#000000", "#000102", 1.0))
        val c = mix("#123456", "#654321", 0.37)
        assertEquals(7, c.length)
        assertTrue(c.startsWith("#"))
        assertEquals(c.lowercase(), c)
    }

    @Test
    fun `pick reads a slot with a per-species fallback`() {
        val colors = mapOf("bodyColor" to "#FFD6E8")
        assertEquals("#FFD6E8", pick(colors, ColorSlot.Unicorn.body, "#F5ECFF"))
        assertEquals("#FFD54F", pick(colors, ColorSlot.Unicorn.horn, "#FFD54F"))
        // An unknown slot written by an older build round-trips untouched
        // (Mascot.kt's note on why the config is Map<String, String>).
        assertEquals("#ABCDEF", pick(mapOf("mysteryColor" to "#ABCDEF"), "mysteryColor", "#000"))
    }

    @Test
    fun `lerp and INK`() {
        assertEquals(2.5, lerp(0.0, 10.0, 0.25))
        assertEquals(0.0, lerp(10.0, 0.0, 1.0))
        assertEquals("#5A3A1E", INK)
    }

    /* layoutFor, the per-stage geometry --------------------------------------- */

    /** Golden geometry, straight out of the TypeScript. */
    private data class Golden(
        val stage: Double,
        val pose: Pose,
        val standing: Boolean,
        val bodyCX: Double,
        val bodyCY: Double,
        val bodyRX: Double,
        val bodyRY: Double,
        val headCX: Double,
        val headCY: Double,
        val headR: Double,
        val eyeR: Double,
        val feetY: Double,
        val legs: Int,
    )

    private val goldens = listOf(
        Golden(0.0, Pose.LYING, false, 50.0, 80.0, 27.0, 9.84, 59.0, 70.0, 28.0, 8.68, 91.0, 0),
        Golden(1.0, Pose.LYING, false, 50.0, 80.0, 27.0, 10.25, 54.0, 56.0, 27.0, 8.1, 91.0, 0),
        Golden(2.0, Pose.WOBBLY, true, 50.0, 74.5, 17.5, 15.0, 50.0, 49.79, 24.5, 6.37, 94.0, 4),
        Golden(3.0, Pose.WOBBLY, true, 50.0, 70.0, 18.0, 16.5, 50.0, 45.690000000000005, 22.0, 4.84, 94.0, 4),
        Golden(5.0, Pose.STANDING, true, 50.0, 62.5, 19.7, 19.0, 50.0, 38.470000000000006, 18.5, 3.145, 94.0, 4),
        Golden(9.0, Pose.PROUD, true, 50.0, 54.0, 22.0, 23.0, 50.0, 23.830000000000005, 16.5, 2.5575, 94.0, 4),
        // Fractional stages are reachable — the ramps are continuous by design.
        Golden(4.5, Pose.STANDING, true, 50.0, 64.0, 19.35, 18.5, 50.0, 39.885, 19.25, 3.465, 94.0, 4),
        // Clamped: -1 is stade 0's geometry, 12 is stade 9's.
        Golden(-1.0, Pose.LYING, false, 50.0, 80.0, 27.0, 9.84, 59.0, 70.0, 28.0, 8.68, 91.0, 0),
        Golden(12.0, Pose.PROUD, true, 50.0, 54.0, 22.0, 23.0, 50.0, 23.830000000000005, 16.5, 2.5575, 94.0, 4),
    )

    @Test
    fun `layoutFor matches the TypeScript bit for bit at every pinned stage`() {
        for (g in goldens) {
            val l = layoutFor(g.stage)
            assertEquals(g.pose, l.pose, "pose @${g.stage}")
            assertEquals(g.standing, l.standing, "standing @${g.stage}")
            assertEquals(g.bodyCX, l.bodyCX, "bodyCX @${g.stage}")
            assertEquals(g.bodyCY, l.bodyCY, "bodyCY @${g.stage}")
            assertEquals(g.bodyRX, l.bodyRX, "bodyRX @${g.stage}")
            assertEquals(g.bodyRY, l.bodyRY, "bodyRY @${g.stage}")
            assertEquals(g.headCX, l.headCX, "headCX @${g.stage}")
            assertEquals(g.headCY, l.headCY, "headCY @${g.stage}")
            assertEquals(g.headR, l.headR, "headR @${g.stage}")
            assertEquals(g.eyeR, l.eyeR, "eyeR @${g.stage}")
            assertEquals(g.feetY, l.feetY, "feetY @${g.stage}")
            assertEquals(g.legs, l.legs.size, "legs @${g.stage}")
        }
    }

    @Test
    fun `the lying newborn's belly is widened and flattened`() {
        // The lying newborn's `bodyRX` is the ramped value + 8 ("wide splayed
        // belly") and its `bodyRY` is ×0.82 ("flatter"). Dropping either of
        // those two lines still passes a "does it draw" check.
        val baby = layoutFor(0.0)
        assertEquals(19.0 + 8, baby.bodyRX)
        assertEquals(12 * 0.82, baby.bodyRY)
        // …and the head rests low and FORWARD over it, then lifts at stade 1.
        assertEquals(59.0, baby.headCX)
        assertEquals(54.0, layoutFor(1.0).headCX)
        assertTrue(layoutFor(1.0).headCY < baby.headCY)
    }

    @Test
    fun `head shrinks and body grows monotonically, 0 to 9`() {
        // The silhouette arc in one assertion: this is what makes clicking
        // 3→4→5 feel like something happened.
        var previous = layoutFor(0.0)
        for (s in 1..9) {
            val l = layoutFor(s.toDouble())
            assertTrue(l.headR <= previous.headR, "headR grew at stade $s")
            assertTrue(l.eyeR <= previous.eyeR, "eyeR grew at stade $s")
            assertTrue(l.bodyRY >= previous.bodyRY, "bodyRY shrank at stade $s")
            previous = l
        }
        // …and it is a DRAMATIC arc, not a rounding error: the baby's head is
        // nearly 1.7× the adult's, its eye nearly 3.4×.
        assertTrue(layoutFor(0.0).headR / layoutFor(9.0).headR > 1.65)
        assertTrue(layoutFor(0.0).eyeR / layoutFor(9.0).eyeR > 3.3)
    }

    @Test
    fun `every stage produces different geometry, no two stades draw alike`() {
        val seen = (0 until 10).map { layoutFor(it.toDouble()) }.toSet()
        assertEquals(10, seen.size)
    }

    @Test
    fun `standing stages stand on the ground line with four legs`() {
        for (s in 2 until 10) {
            val l = layoutFor(s.toDouble())
            assertTrue(l.standing)
            assertEquals(4, l.legs.size)
            assertEquals(94.0, l.feetY)
            for (leg in l.legs) {
                assertEquals(l.feetY, leg.footY, "stade $s foot off the ground")
                assertEquals(l.bodyCY + l.bodyRY * 0.4, leg.hipY, "stade $s hip")
            }
            // Two back legs then two front legs, left-then-right in each pair.
            assertEquals(listOf(true, true, false, false), l.legs.map { it.back }, "stade $s order")
            assertEquals(listOf(-1.0, 1.0, -1.0, 1.0), l.legs.map { it.side }, "stade $s sides")
            // Back legs sit wider than front legs (sb > sf at every stage).
            assertTrue(abs(l.legs[0].hipX - l.bodyCX) > abs(l.legs[2].hipX - l.bodyCX), "stade $s spread")
        }
    }

    @Test
    fun `legs straighten with age and splay outward`() {
        // The knee bend straightens as the creature matures, and the foot is
        // offset from the hip by `side * bend * 0.6` — sign included, so a
        // mirrored leg splays outward, never inward.
        assertEquals(6.0, layoutFor(2.0).legs[0].bend)
        assertEquals(0.0, layoutFor(9.0).legs[0].bend)
        for (s in 2 until 10) {
            for (leg in layoutFor(s.toDouble()).legs) {
                assertEquals(leg.hipX + leg.side * leg.bend * 0.6, leg.footX)
                // Outward: a left leg's foot is never to the right of its hip.
                if (leg.side < 0) assertTrue(leg.footX <= leg.hipX) else assertTrue(leg.footX >= leg.hipX)
            }
        }
    }

    @Test
    fun `the neck only lengthens from stade 7`() {
        // The neck extension is what makes the last three stades read as
        // "proud" rather than just bigger.
        fun gap(s: Double): Double {
            val l = layoutFor(s)
            return l.bodyCY - l.headCY
        }
        // headCY = bodyCY - bodyRY*0.7 - headR*0.58 - neckExtra, and neckExtra
        // is 0 up to stade 6.
        for (s in 2..6) {
            val l = layoutFor(s.toDouble())
            assertEquals(l.bodyCY - l.bodyRY * 0.7 - l.headR * 0.58, l.headCY, "stade $s")
        }
        assertTrue(gap(9.0) > gap(7.0))
        assertTrue(gap(7.0) > gap(6.0))
    }
}
