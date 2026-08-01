package fr.dappit.attrapelettres.art.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import kotlin.math.roundToInt

// The mascot's mood animation and the "Arc-en-ciel magique" sheen sweep, ported
// from `src/mascot/Mascot.tsx` (the WAAPI keyframes) and `src/index.css`
// (`@keyframes alSheen`).
//
// INVARIANT 2 — animation stays OFF the render path. Everything here is a
// transform over an already-recorded draw list. The animated value is an
// `Animatable`, and it is read INSIDE a `Modifier.graphicsLayer { }` lambda —
// a deferred read, which re-runs the layer block and nothing else. No
// recomposition, no re-measure, and above all no second pass through the rig:
// the draw list is not rebuilt, no `d` string is re-parsed and no Compose `Path`
// is re-created to move the mascot a pixel. The web is the same shape:
// `el.animate(...)` on the `<svg>` element runs off React's render path, and the
// sheen is pure CSS for the same stated reason.
//
// The rule that follows from it, and the one an edit is most likely to break:
// NEVER hoist the animated transform into state the mascot itself reads. A
// `var t by remember { mutableStateOf(...) }` consumed by the Canvas would
// recompose and re-record the whole rig sixty times a second, and it would look
// identical on a fast phone.
//
// Two unit traps, both real, both from the iOS spec section 10:
//
//   1. The bob's `translateY(-5px)` is on the `<svg>` ELEMENT, so those px are
//      SCREEN pixels — a 220 dp mascot bobs by the same 5 dp as an 88 dp one.
//      (A CSS pixel and a dp are the same thing here: both are 1/160 inch at
//      the nominal density, which is why `Dp` is the right unit and not a
//      fraction of `size`.) The sheen's plus-or-minus 150 px are on an element
//      INSIDE the SVG, so they are viewBox USER UNITS and must be multiplied by
//      `size / 100`. Scaling the first or not scaling the second is a bug that
//      only shows up at a non-default size, which is exactly where nobody looks.
//   2. React cancels the previous animation on a mood change, which reverts the
//      element to its base (identity) transform before the new one starts. A
//      naive port leaves a mid-flight scale composing with the incoming bob and
//      the mascot drifts. Here the `Animatable` is keyed on the PLAN
//      (`remember(plan)`), so a new mood builds a new one starting at identity,
//      and the effect `snapTo(Identity)` before animating. Every plan's first
//      keyframe is written at time 0, exactly as WAAPI applies an offset-0
//      frame, so the jump to it is instantaneous.
//
// INVARIANT 6 — under reduced motion there is no bob, no pop, no cheer, and the
// sheen is FROZEN at its base transform, not hidden: a static rainbow band still
// crosses the pet (`animation: none !important` in the media query leaves the
// element at `translateX(0)`). `ReduceMotionSource` is INJECTED rather than read
// from an ambient, so the tests and the preview harness can force it, and so
// that there is exactly one source of the setting for the whole app.
//
// Note the asymmetry that is deliberate: the mascot's idle motion IS gated,
// while tile press and shake are NOT — the web leaves those ungated and this
// port keeps that difference exactly.

// --- The animated value (pure) -----------------------------------------------

/**
 * The three transform channels the web keyframes touch, in one animatable value.
 *
 * [y] is in SCREEN dp (see trap 1). [rotation] is degrees, positive clockwise,
 * as in CSS. Plain `Double`s and no Compose type in sight, so the plans below
 * are assertable in a host test.
 */
data class MascotTransform(
    val y: Double = 0.0,
    val rotation: Double = 0.0,
    val scale: Double = 1.0,
) {
    companion object {
        /** The un-animated rig. Every plan restarts from here (trap 2). */
        val Identity = MascotTransform()
    }
}

/**
 * WAAPI's `easing`, which applies to each keyframe INTERVAL and not to the
 * timeline as a whole — so it maps onto a per-segment curve, not onto one curve
 * stretched over the whole track.
 */
enum class MascotEasing { EASE_IN_OUT, EASE_OUT }

/** One entry of a WAAPI keyframe list. [offset] is 0..1 along the timeline. */
data class MascotKeyframe(val offset: Double, val transform: MascotTransform)

/** One `(value, duration)` step of a timeline — what a keyframe spec consumes. */
data class MascotSegment(val transform: MascotTransform, val duration: Double)

/**
 * A whole `el.animate(keyframes, options)` call AS DATA, so the timings and the
 * keyframe shape can be asserted on the host without a running animation.
 */
data class MascotMotionPlan(
    val keyframes: List<MascotKeyframe>,
    /** Seconds (the TSX quotes milliseconds). */
    val duration: Double,
    /** `iterations: Infinity` — true for the idle bob only. */
    val repeats: Boolean,
    val easing: MascotEasing,
) {
    /**
     * The timeline as `(value, duration)` segments.
     *
     * The FIRST segment has duration 0: it is the offset-0 keyframe, which WAAPI
     * applies instantly, and which is what makes the restart-from-identity in
     * trap 2 visually correct for the bob — whose offset-0 frame is
     * `rotate(-1.5deg)`, not identity.
     */
    val segments: List<MascotSegment>
        get() {
            val first = keyframes.firstOrNull() ?: return emptyList()
            val out = ArrayList<MascotSegment>(keyframes.size)
            out.add(MascotSegment(first.transform, 0.0))
            for (index in 1 until keyframes.size) {
                val previous = keyframes[index - 1].offset
                out.add(MascotSegment(keyframes[index].transform, (keyframes[index].offset - previous) * duration))
            }
            return out
        }
}

// --- The three plans (pure) --------------------------------------------------

object MascotMotion {

    // `el.style.transformOrigin = "50% 82%"`. Kept as two plain floats rather
    // than a Compose `TransformOrigin` so the number a test reads is the number
    // the layer is given, with no packed-long value class in between.
    const val ORIGIN_X: Float = 0.5f
    const val ORIGIN_Y: Float = 0.82f

    /**
     * ```ts
     * const bob: Keyframe[] = [
     *   { transform: "translateY(0) rotate(-1.5deg)" },
     *   { transform: "translateY(-5px) rotate(1.5deg)", offset: 0.5 },
     *   { transform: "translateY(0) rotate(-1.5deg)" },
     * ];
     * ```
     * 2600 ms, ease-in-out, `iterations: Infinity`.
     */
    val bob = MascotMotionPlan(
        keyframes = listOf(
            MascotKeyframe(0.0, MascotTransform(y = 0.0, rotation = -1.5, scale = 1.0)),
            MascotKeyframe(0.5, MascotTransform(y = -5.0, rotation = 1.5, scale = 1.0)),
            MascotKeyframe(1.0, MascotTransform(y = 0.0, rotation = -1.5, scale = 1.0)),
        ),
        duration = 2.6,
        repeats = true,
        easing = MascotEasing.EASE_IN_OUT,
    )

    /**
     * ```ts
     * const pop: Keyframe[] = [
     *   { transform: "scale(1)" },
     *   { transform: "scale(1.16) rotate(4deg)", offset: 0.4 },
     *   { transform: "scale(0.98)", offset: 0.72 },
     *   { transform: "scale(1)" },
     * ];
     * ```
     * 480 ms, ease-out, one shot.
     *
     * NB frames 3 and 4 write `scale(...)` with no `rotate`, which in CSS means
     * rotation 0 — the pop snaps back to square, it does not hold the 4 degrees.
     */
    val pop = MascotMotionPlan(
        keyframes = listOf(
            MascotKeyframe(0.0, MascotTransform(y = 0.0, rotation = 0.0, scale = 1.0)),
            MascotKeyframe(0.4, MascotTransform(y = 0.0, rotation = 4.0, scale = 1.16)),
            MascotKeyframe(0.72, MascotTransform(y = 0.0, rotation = 0.0, scale = 0.98)),
            MascotKeyframe(1.0, MascotTransform(y = 0.0, rotation = 0.0, scale = 1.0)),
        ),
        duration = 0.48,
        repeats = false,
        easing = MascotEasing.EASE_OUT,
    )

    /**
     * ```ts
     * const cheer: Keyframe[] = [
     *   { transform: "scale(1) rotate(0deg)" },
     *   { transform: "scale(1.2) rotate(-6deg)", offset: 0.25 },
     *   { transform: "scale(1.1) rotate(6deg)", offset: 0.5 },
     *   { transform: "scale(1.22) rotate(-4deg)", offset: 0.74 },
     *   { transform: "scale(1) rotate(0deg)" },
     * ];
     * ```
     * 680 ms, ease-out, one shot.
     */
    val cheer = MascotMotionPlan(
        keyframes = listOf(
            MascotKeyframe(0.0, MascotTransform(y = 0.0, rotation = 0.0, scale = 1.0)),
            MascotKeyframe(0.25, MascotTransform(y = 0.0, rotation = -6.0, scale = 1.2)),
            MascotKeyframe(0.5, MascotTransform(y = 0.0, rotation = 6.0, scale = 1.1)),
            MascotKeyframe(0.74, MascotTransform(y = 0.0, rotation = -4.0, scale = 1.22)),
            MascotKeyframe(1.0, MascotTransform(y = 0.0, rotation = 0.0, scale = 1.0)),
        ),
        duration = 0.68,
        repeats = false,
        easing = MascotEasing.EASE_OUT,
    )

    /**
     * The whole of `Mascot.tsx`'s effect body as one pure decision.
     *
     * ```ts
     * if (preview) return;                              // shop thumbnails never bob
     * const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
     * if (reduced) return;                              // NOTHING runs, not "less"
     * mood === "idle" ? bob : mood === "cheer" ? cheer : pop
     * ```
     *
     * `null` means "start no animation at all", which is the web behaviour — not
     * a shortened one.
     */
    fun plan(mood: Mood, preview: Boolean, reduceMotion: Boolean): MascotMotionPlan? {
        // "Shop thumbnails never bob — a grid of jittering mascots is noise,
        // not signal."
        if (preview) return null
        if (reduceMotion) return null
        return when (mood) {
            Mood.IDLE -> bob
            Mood.CHEER -> cheer
            Mood.HAPPY -> pop
        }
    }
}

// --- The sheen sweep (pure) --------------------------------------------------

/**
 * `@keyframes alSheen` from `src/index.css`, bound by `.al-sheen` on the
 * `RainbowSheen` band.
 *
 * ```css
 * @keyframes alSheen {
 *   from { transform: translateX(var(--al-from, -150px)); }
 *   to   { transform: translateX(var(--al-to, 150px)); }
 * }
 * .al-sheen { animation: alSheen 3.6s linear infinite; }
 * ```
 *
 * `RainbowSheen` sets `--al-from: -150px` / `--al-to: 150px`, i.e. the defaults.
 * These are viewBox USER UNITS (trap 1): the rect they move lives inside the
 * `<svg>`, so a CSS pixel there is one of the 100 mascot units.
 */
object MascotSheen {
    const val FROM_UNITS: Double = -150.0
    const val TO_UNITS: Double = 150.0

    /**
     * Seconds. Linear, infinite, NO autoreverse — it wraps, and the wrap happens
     * off the pet where the silhouette mask hides it.
     */
    const val DURATION: Double = 3.6

    /**
     * Where the band sits when the animation is off. This is the CSS BASE
     * transform (no `translateX`), NOT [FROM_UNITS] — invariant 6 wants a static
     * rainbow band across the centre of the pet: visible, not hidden, and not
     * parked 150 units off where the mask would swallow it. It is also the
     * position `drawRainbowSheen` records, so the still rig and the reduced
     * rig are the same picture.
     */
    const val PARKED_UNITS: Double = 0.0

    /**
     * The band's x offset in viewBox units at time [t] seconds. Pure, so the
     * wrap and the reduced-motion park are testable without a running clock.
     */
    fun offsetUnits(t: Double, reduceMotion: Boolean): Double {
        if (reduceMotion) return PARKED_UNITS
        val phase = ((t % DURATION) + DURATION) % DURATION / DURATION
        return FROM_UNITS + (TO_UNITS - FROM_UNITS) * phase
    }
}

// --- Compose plumbing --------------------------------------------------------
//
// From here down the file knows Compose exists. Nothing above it does, and
// nothing below it decides anything: the plans are already chosen, these just
// hand them to the animation system.

/**
 * The CSS timing functions, spelled out.
 *
 * NOT `FastOutSlowInEasing` / `LinearOutSlowInEasing`: those are Material's
 * curves (0.4, 0, 0.2, 1) and they are not what a browser runs. CSS
 * `ease-in-out` is `cubic-bezier(0.42, 0, 0.58, 1)` and CSS `ease-out` is
 * `cubic-bezier(0, 0, 0.58, 1)`, and the web app is the source of truth.
 */
private val EASE_IN_OUT_CURVE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EASE_OUT_CURVE = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private val MascotEasing.curve: Easing
    get() = when (this) {
        MascotEasing.EASE_IN_OUT -> EASE_IN_OUT_CURVE
        MascotEasing.EASE_OUT -> EASE_OUT_CURVE
    }

/**
 * The three channels as one animation vector, so a single `Animatable` carries
 * the whole transform and the three channels can never drift out of phase with
 * each other (three separate animations would each round their own frame time).
 */
val MascotTransformConverter: TwoWayConverter<MascotTransform, AnimationVector3D> =
    TwoWayConverter(
        convertToVector = {
            AnimationVector3D(it.y.toFloat(), it.rotation.toFloat(), it.scale.toFloat())
        },
        convertFromVector = {
            MascotTransform(it.v1.toDouble(), it.v2.toDouble(), it.v3.toDouble())
        },
    )

/**
 * The plan as a Compose animation spec.
 *
 * Every keyframe carries the plan's easing, because in Compose — as in WAAPI —
 * `using` sets the curve for the interval that STARTS at that keyframe. One
 * curve over the whole track would flatten the bob's two half-cycles into one
 * and the pop would overshoot on the way back.
 */
fun MascotMotionPlan.toAnimationSpec(): AnimationSpec<MascotTransform> {
    val totalMs = (duration * 1000).roundToInt()
    val frames = keyframes<MascotTransform> {
        durationMillis = totalMs
        // Both `keyframes` and `easing` are qualified: inside this lambda the
        // implicit receiver is the spec config, and an unqualified name that
        // happens to match one of its members later would silently retarget.
        for (frame in this@toAnimationSpec.keyframes) {
            frame.transform at (frame.offset * totalMs).roundToInt() using this@toAnimationSpec.easing.curve
        }
    }
    return if (repeats) {
        infiniteRepeatable(animation = frames, repeatMode = RepeatMode.Restart)
    } else {
        frames
    }
}

/**
 * `Mascot.tsx`'s mood effect, as a modifier over an already-drawn rig.
 *
 * Apply it to the WHOLE mascot — the web animates the `<svg>` element, ground
 * shadow included — and never to a part.
 *
 * The CSS transform list is `translateY(-5px) rotate(1.5deg)`, which applies
 * right to left to the geometry: rotate first, then translate. A `graphicsLayer`
 * composes in exactly that order (scale and rotation about `transformOrigin`,
 * then `translation` in the parent's space), and CSS `translate` has no origin
 * either, so the two agree channel for channel.
 */
@Composable
fun Modifier.mascotMoodMotion(
    mood: Mood,
    preview: Boolean,
    reduceMotion: ReduceMotionSource,
): Modifier {
    val plan = MascotMotion.plan(mood, preview, reduceMotion.isReduced)
    // Keyed on the plan: a mood change builds a NEW Animatable sitting at
    // identity, which is React's `anim.cancel()` reverting the element to its
    // base transform before the next animation starts (trap 2).
    val animated = remember(plan) { Animatable(MascotTransform.Identity, MascotTransformConverter) }
    LaunchedEffect(plan) {
        if (plan == null) return@LaunchedEffect
        animated.snapTo(MascotTransform.Identity)
        animated.animateTo(plan.keyframes.last().transform, plan.toAnimationSpec())
    }
    return graphicsLayer {
        // The deferred read that invariant 2 is about: this lambda re-runs when
        // `animated.value` changes, and nothing else does. The composition above
        // is not invalidated, so the rig is not re-recorded.
        val t = animated.value
        transformOrigin = TransformOrigin(MascotMotion.ORIGIN_X, MascotMotion.ORIGIN_Y)
        rotationZ = t.rotation.toFloat()
        scaleX = t.scale.toFloat()
        scaleY = t.scale.toFloat()
        // Screen dp, NOT viewBox units — trap 1.
        translationY = t.y.dp.toPx()
    }
}

/**
 * `.al-sheen` — the rainbow band's sweep.
 *
 * Apply to the BAND ONLY, inside the silhouette mask and INSIDE the band's own
 * `rotate(-20)`: the CSS `translateX` sits on the rect, under the rotated group,
 * so the sweep runs along the rotated x axis and not along the screen's. In a
 * modifier chain that is `Modifier.graphicsLayer { rotationZ = -20f }` OUTSIDE
 * this one. The mask itself never moves.
 *
 * [unitScale] is pixels per viewBox unit (`sizePx / 100` for the mascot). See
 * trap 1: these offsets are user units and MUST be scaled, while the mood bob's
 * are screen dp and must not be.
 *
 * NB `MascotRig.draw` already records the band PARKED at
 * [MascotSheen.PARKED_UNITS], which is the correct still picture and the
 * reduced-motion picture. This modifier exists for a host that draws the band as
 * its own layer so the sweep can run without re-recording the rig.
 */
@Composable
fun Modifier.mascotSheenMotion(
    unitScale: Float,
    reduceMotion: ReduceMotionSource,
): Modifier {
    val reduced = reduceMotion.isReduced
    val offset = remember(reduced) {
        Animatable(if (reduced) MascotSheen.PARKED_UNITS.toFloat() else MascotSheen.FROM_UNITS.toFloat())
    }
    LaunchedEffect(reduced) {
        if (reduced) {
            // `animation: none !important` — the band parks at the CSS base
            // transform and stays VISIBLE (invariant 6). Do not hide it.
            offset.snapTo(MascotSheen.PARKED_UNITS.toFloat())
            return@LaunchedEffect
        }
        offset.snapTo(MascotSheen.FROM_UNITS.toFloat())
        offset.animateTo(
            MascotSheen.TO_UNITS.toFloat(),
            infiniteRepeatable(
                animation = tween(
                    durationMillis = (MascotSheen.DURATION * 1000).roundToInt(),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
        )
    }
    return graphicsLayer { translationX = offset.value * unitScale }
}
