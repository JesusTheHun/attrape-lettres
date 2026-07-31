package fr.dappit.attrapelettres.core.mascot

import kotlin.math.roundToInt
import kotlin.math.sign

// Port of `src/mascot/growth.ts` — the PURE ARITHMETIC half of it.
//
// Geometry maths belongs to :core, not :art: `accessoryAnchors` reads `Layout`,
// the shop preview reads `stageScale`, and the whole thing is host-testable with
// no Compose in sight.
//
// NOT ported here, deliberately: the TS file's `RigProps` interface. It is a
// drawing prop bag (`uid` for <defs> gradient ids, `preview` for the shop
// thumbnail) with no meaning outside a rig, so it belongs to :art with the rigs
// that consume it. Everything else — INK, lerp, pick, mix, ramp, stageScale,
// Pose, LegSpec, Layout, poseFor, layoutFor — is here, in declaration order.

/**
 * Growth model — CONTINUOUS PER-STAGE interpolation + a per-stage feature
 * timeline. Every stage 0..9 gets its OWN geometry (head:body ratio, limb
 * length, posture, eye size) via `ramp()` anchor curves, so a child clicking
 * 3→4→5 sees a real change each time. On top of that each species owns a
 * STAGE_SPEC selecting part variants (horn height, wing scale, tail step…).
 *
 * Silhouette arc: stade 0 fully folded helpless baby (oversized head, huge
 * eyes, stubby/absent limbs) → wobbling to rise (1) → crouched first steps (2)
 * → upright youngster (3-5) → slim adult (6) → proud (7-8) → majestic (9).
 *
 * Owned by AGENT A.
 */

const val INK = "#5A3A1E"

fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Read a colour/style slot with a per-species default. */
fun pick(m: Map<String, String>, slot: String, fallback: String): String = m[slot] ?: fallback

/** Linear blend between two #rrggbb hex colours (t: 0=a … 1=b). */
// NB: transcribed operation for operation, including the `(1 shl 24) + …` trick
// that guarantees six hex digits and the leading "1" that `.slice(1)` throws
// away. JS `Math.round` rounds half toward +∞, and so does Kotlin's
// `roundToInt()` ("ties are rounded towards positive infinity") — the one
// rounding function here that matches, unlike `Math.rint`'s half-to-even. The
// `?: 0` mirrors `parseInt` never throwing: garbage in, black out.
fun mix(a: String, b: String, t: Double): String {
    val pa = a.substring(1).toIntOrNull(16) ?: 0
    val pb = b.substring(1).toIntOrNull(16) ?: 0
    val r = (((pa shr 16) and 255) + (((pb shr 16) and 255) - ((pa shr 16) and 255)) * t).roundToInt()
    val g = (((pa shr 8) and 255) + (((pb shr 8) and 255) - ((pa shr 8) and 255)) * t).roundToInt()
    val c = ((pa and 255) + ((pb and 255) - (pa and 255)) * t).roundToInt()
    return "#" + ((1 shl 24) + (r shl 16) + (g shl 8) + c).toString(16).substring(1)
}

/** Piecewise-linear interpolation anchor stop: `[stage, value]` in the TS. */
data class RampStop(val stage: Double, val value: Double)

/** Piecewise-linear interpolation over [stage, value] anchor stops. */
fun ramp(stage: Double, stops: List<RampStop>): Double {
    val first = stops.first()
    val last = stops.last()
    if (stage <= first.stage) return first.value
    if (stage >= last.stage) return last.value
    for (i in 0 until stops.size - 1) {
        val (s0, v0) = stops[i]
        val (s1, v1) = stops[i + 1]
        if (stage >= s0 && stage <= s1) return lerp(v0, v1, (stage - s0) / (s1 - s0))
    }
    return last.value
}

/**
 * Overall on-screen size multiplier per stage — pivoted at the feet so the
 * creature grows UPWARD off the ground line. This is a big part of why the
 * later stages feel like a reward: the mascot visibly gets bigger, not just
 * more decorated. Stades 0-2 stay 1 (the untouched early game).
 */
private val STAGE_SCALE = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3)

// NB: `Int`, where the TS says `number`. `STAGE_SCALE[clamp(stage, 0, 9)]` is
// an array index, so a fractional stage would read `undefined` and poison the
// transform with NaN — a latent JS bug that cannot be reached because the one
// call site passes `config.stage`. Typing it `Int` makes it unreachable in
// Kotlin too rather than silently "fixing" the behaviour.
fun stageScale(stage: Int): Double = STAGE_SCALE[stage.coerceIn(0, 9)]

enum class Pose { LYING, WOBBLY, STANDING, PROUD }

data class LegSpec(
    val hipX: Double,
    val hipY: Double,
    val footX: Double,
    val footY: Double,
    val bend: Double,
    val side: Double,
    val back: Boolean,
)

data class Layout(
    val pose: Pose,
    /** false only for lying babies (stades 0-1). */
    val standing: Boolean,
    val bodyCX: Double,
    val bodyCY: Double,
    val bodyRX: Double,
    val bodyRY: Double,
    val headCX: Double,
    val headCY: Double,
    val headR: Double,
    /** Centralised huge-baby → small-adult eye radius. */
    val eyeR: Double,
    val feetY: Double,
    val legs: List<LegSpec>,
)
// NB: worn-accessory placement (throat/hat/feet) is NOT a layout field — it lives
// in accessoryAnchors() (AccessoryAnchors.kt), head-relative so it tracks the
// shrinking head. Do not add a body-relative "neck" point here; the baby's head
// rides over it (that was the "bow in the middle of the face" bug).

fun poseFor(stage: Double): Pose {
    if (stage <= 1) return Pose.LYING
    if (stage <= 3) return Pose.WOBBLY
    if (stage <= 6) return Pose.STANDING
    return Pose.PROUD
}

private fun quadLegs(
    bodyCX: Double,
    bodyCY: Double,
    bodyRY: Double,
    feetY: Double,
    sf: Double,
    sb: Double,
    bend: Double,
): List<LegSpec> {
    val hipY = bodyCY + bodyRY * 0.4
    fun mk(dx: Double, back: Boolean): LegSpec {
        // `Math.sign(dx) || 1` — sign(0) is 0, which is falsy, so a centred leg
        // counts as a right-hand leg.
        val side = if (dx.sign == 0.0) 1.0 else dx.sign
        return LegSpec(
            hipX = bodyCX + dx,
            hipY = hipY,
            footX = bodyCX + dx + side * bend * 0.6,
            footY = feetY,
            bend = bend,
            side = side,
            back = back,
        )
    }
    return listOf(mk(-sb, true), mk(sb, true), mk(-sf, false), mk(sf, false))
}

/** Fully-computed, per-stage-distinct geometry. */
fun layoutFor(stage: Double): Layout {
    val s = stage.coerceIn(0.0, 9.0)
    val pose = poseFor(s)
    // Head shrinks a LOT from baby→adult; body grows → dramatic baby ratio.
    val headR = ramp(s, listOf(
        RampStop(0.0, 28.0), RampStop(1.0, 27.0), RampStop(2.0, 24.5), RampStop(3.0, 22.0),
        RampStop(4.0, 20.0), RampStop(5.0, 18.5), RampStop(6.0, 17.5), RampStop(7.0, 17.0), RampStop(9.0, 16.5),
    ))
    val bodyRX = ramp(s, listOf(
        RampStop(0.0, 19.0), RampStop(1.0, 19.0), RampStop(2.0, 17.5), RampStop(3.0, 18.0),
        RampStop(4.0, 19.0), RampStop(5.0, 19.7), RampStop(6.0, 20.5), RampStop(9.0, 22.0),
    ))
    val bodyRY = ramp(s, listOf(
        RampStop(0.0, 12.0), RampStop(1.0, 12.5), RampStop(2.0, 15.0), RampStop(3.0, 16.5),
        RampStop(4.0, 18.0), RampStop(5.0, 19.0), RampStop(6.0, 20.0), RampStop(7.0, 21.0), RampStop(9.0, 23.0),
    ))
    val eyeR = headR * ramp(s, listOf(
        RampStop(0.0, 0.31), RampStop(1.0, 0.3), RampStop(2.0, 0.26), RampStop(3.0, 0.22),
        RampStop(4.0, 0.19), RampStop(5.0, 0.17), RampStop(6.0, 0.16), RampStop(9.0, 0.155),
    ))

    if (pose == Pose.LYING) {
        val bodyCX = 50.0
        val bodyCY = 80.0
        val lyRX = bodyRX + 8 // wide splayed belly
        val lyRY = bodyRY * 0.82 // flatter
        // stade 0 head resting low & forward; stade 1 head lifted (rising).
        val headCX = ramp(s, listOf(RampStop(0.0, 59.0), RampStop(1.0, 54.0)))
        val headCY = ramp(s, listOf(RampStop(0.0, 70.0), RampStop(1.0, 56.0)))
        return Layout(
            pose = pose,
            standing = false,
            bodyCX = bodyCX,
            bodyCY = bodyCY,
            bodyRX = lyRX,
            bodyRY = lyRY,
            headCX = headCX,
            headCY = headCY,
            headR = headR,
            eyeR = eyeR,
            feetY = 91.0,
            legs = emptyList(),
        )
    }

    val feetY = 94.0
    val legLen = ramp(s, listOf(
        RampStop(2.0, 4.5), RampStop(3.0, 7.5), RampStop(4.0, 10.5), RampStop(5.0, 12.5),
        RampStop(6.0, 14.0), RampStop(7.0, 15.0), RampStop(8.0, 16.0), RampStop(9.0, 17.0),
    ))
    val bodyCX = 50.0
    val bodyCY = feetY - legLen - bodyRY
    val neckExtra = ramp(s, listOf(RampStop(6.0, 0.0), RampStop(7.0, 1.5), RampStop(8.0, 3.0), RampStop(9.0, 4.5)))
    val headCY = bodyCY - bodyRY * 0.7 - headR * 0.58 - neckExtra
    val sf = ramp(s, listOf(RampStop(2.0, 10.5), RampStop(3.0, 9.8), RampStop(4.0, 9.0), RampStop(6.0, 8.0), RampStop(9.0, 8.0)))
    val sb = ramp(s, listOf(RampStop(2.0, 17.0), RampStop(3.0, 16.0), RampStop(4.0, 15.0), RampStop(6.0, 14.0), RampStop(9.0, 13.0)))
    val bend = ramp(s, listOf(
        RampStop(2.0, 6.0), RampStop(3.0, 4.2), RampStop(4.0, 2.6), RampStop(5.0, 1.6),
        RampStop(6.0, 1.0), RampStop(7.0, 0.5), RampStop(9.0, 0.0),
    ))
    return Layout(
        pose = pose,
        standing = true,
        bodyCX = bodyCX,
        bodyCY = bodyCY,
        bodyRX = bodyRX,
        bodyRY = bodyRY,
        headCX = 50.0,
        headCY = headCY,
        headR = headR,
        eyeR = eyeR,
        feetY = feetY,
        legs = quadLegs(bodyCX, bodyCY, bodyRY, feetY, sf, sb, bend),
    )
}
