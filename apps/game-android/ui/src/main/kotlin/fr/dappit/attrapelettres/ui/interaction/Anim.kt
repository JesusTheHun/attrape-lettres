package fr.dappit.attrapelettres.ui.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The interaction animations — press, shake, pop, pulse — ported 1:1 from the
// PWA's WAAPI calls. The twin of iOS's `Anim.swift`, with Core Animation's
// `CAKeyframeAnimation` on a `CALayer` replaced by Compose's `Animatable` read
// inside a `Modifier.graphicsLayer { }` lambda.
//
// INVARIANT 2 — animation stays OFF the render path.
//
// The web uses `el.animate(...)` precisely so React never re-renders to move a
// pixel. The Compose equivalent is an `Animatable` whose `value` is read INSIDE
// the `graphicsLayer` lambda. That is a DEFERRED read: the lambda is not part
// of the composition, so when the value changes Compose re-runs the layer block
// and re-draws the already-recorded layer, and nothing recomposes, re-measures
// or re-lays-out.
//
// The bug this is written to prevent, and the one a future edit will reach for
// first:
//
//     // WRONG — recomposes the whole tile sixty times a second
//     val s by scale.asState()
//     Box(Modifier.scale(s)) { ... }
//
//     // RIGHT — re-layers only
//     Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
//
// Both look identical on a fast phone, which is exactly why the wrong one
// survives review. So: never hoist an animated value into state a composable
// body reads, never call `animateFloatAsState` for these, and keep every
// animated channel transform- or alpha-only — no layout-affecting property.
//
// Starting an animation IS asynchronous here, and that is not a violation of
// invariant 1. `Animatable.animateTo` is a suspend function, so `press()`
// launches on a remembered scope; but `press()` itself is a plain non-suspend
// call, it is made synchronously inside the `touchDown` handler, and the sound
// that goes with it (`AudioEngine.pop()`) does not wait for it. The web is the
// same shape: `el.animate(...)` returns immediately and the animation starts on
// the next frame.
//
// INVARIANT 6 — reduced motion is respected where THE WEB respects it, and
// nowhere else. Measured, not assumed (iOS D29, ARCHITECTURE section 3 row 6):
//
//   gated    pop    `usePopFlourish.ts:29` reads the media query itself
//            pulse  `GameFrame.tsx:57` writes `motion-safe:animate-pulse`, and
//                   Tailwind's `motion-safe:` prefix means exactly this
//   UNGATED  press  `Tile.tsx:59`, a bare `el.animate(press, ...)`
//            shake  `Tile.tsx:61`, likewise
//
// `Tile.tsx` contains no `matchMedia` call at all, so a child with the setting
// on still gets the 130 ms squish and the 300 ms wobble on the web. They are
// tactile feedback for a tap, not decoration, and CLAUDE.md's invariant 6 names
// its scope precisely: "mascot + confetti". [TileMotion.press] and
// [TileMotion.shake] therefore take no `ReduceMotionSource` AT ALL; the absence
// of the parameter is what stops the gate being reintroduced by reflex, and
// `AnimTest.reducedMotionGatesOnlyWhatTheWebGates` fails if it is.
//
// On Android the setting is not `prefers-reduced-motion` (there is no such
// thing) but `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, read once in
// :platform and injected as `ReduceMotionSource` — see `core/platform/Motion.kt`.
//
// Where a gate DOES apply, reduced motion means the animation does not run; it
// does NOT mean the caller's completion is skipped. The completion always
// fires, synchronously when nothing animates, so gameplay sequenced behind a
// flourish never stalls for a child who needs the setting.
//
// WAAPI PORTING NOTES, both load-bearing:
//
//   1. WAAPI's `easing` option applies to each keyframe INTERVAL, not to the
//      timeline as a whole. In Compose's `keyframes { }` DSL, `using` sets the
//      curve for the interval that STARTS at that keyframe — the same
//      semantics — so every frame carries the spec's curve. One curve stretched
//      over the whole track would flatten the shake's four half-cycles into one
//      wobble.
//   2. WAAPI composites concurrent `transform` animations with `replace`: the
//      most recent animation wins the property outright. `Tile.tsx` starts
//      `press` and then, on a reject, `shake`, in the same handler — so on the
//      web the shake REPLACES the press for its whole run and a rejected tile
//      only shakes, it never squishes. Compose would instead compose `scaleX`
//      and `translationX` independently, so [TileMotion.shake] explicitly
//      cancels a running press and snaps the scale back to 1.
//
// UNITS. The web's shake is `translateX(-8px)`, CSS pixels on the element. A
// CSS pixel and a dp are the same thing (1/160 inch at the nominal density), so
// the keyframe values below are dp and the `graphicsLayer` converts with
// `.dp.toPx()`. Writing raw pixels into `translationX` would make the wobble
// three times wider on a 3x phone than on the web.

// --- The specs, as pure data -------------------------------------------------
//
// Everything above the Compose plumbing is plain Kotlin so a host test can
// assert the ported numbers against the TypeScript. A running `Animatable`
// cannot be sampled without a frame clock; a keyframe table can.

/**
 * A CSS `cubic-bezier(a, b, c, d)`, kept as four floats rather than as an
 * [Easing] so the value a test reads is the value the animation is given.
 */
data class Bezier(val a: Float, val b: Float, val c: Float, val d: Float) {
    fun toEasing(): Easing = CubicBezierEasing(a, b, c, d)
}

/** One interval of a keyframe track: from a value to the next, over a duration. */
data class MotionSegment(val from: Float, val to: Float, val durationMillis: Int)

/**
 * A whole `el.animate(keyframes, { duration, easing })` call AS DATA.
 *
 * [keyTimes] are WAAPI offsets, 0..1 along the timeline; a WAAPI keyframe list
 * with no explicit offsets is evenly spaced, which is why `press` reads
 * `[0, 0.5, 1]` and `shake` reads `[0, 0.25, 0.5, 0.75, 1]`.
 */
data class KeyframeSpec(
    val values: List<Float>,
    val keyTimes: List<Float>,
    val durationMillis: Int,
    val easing: Bezier,
) {
    init {
        require(values.size >= 2) { "a keyframe track needs at least two values" }
        require(values.size == keyTimes.size) { "one key time per value" }
        require(keyTimes.first() == 0f) { "the first key time is 0" }
        require(keyTimes.last() == 1f) { "the last key time is 1" }
    }

    /** Where the track starts. WAAPI applies an offset-0 frame instantly. */
    val start: Float get() = values.first()

    /** Where the track ends — the target an `animateTo` is given. */
    val target: Float get() = values.last()

    /**
     * The track as `(from, to, duration)` intervals, with the millisecond
     * boundaries rounded cumulatively so the segments always sum to
     * [durationMillis] exactly.
     */
    fun segments(): List<MotionSegment> {
        val out = ArrayList<MotionSegment>(values.size - 1)
        var previousMs = 0
        for (index in 0 until values.size - 1) {
            val endMs = (keyTimes[index + 1] * durationMillis).roundToInt()
            out.add(MotionSegment(values[index], values[index + 1], endMs - previousMs))
            previousMs = endMs
        }
        return out
    }

    /**
     * The track as a Compose spec. Every frame carries [easing] — porting
     * note 1.
     */
    fun toAnimationSpec(repeats: Boolean = false): AnimationSpec<Float> {
        val curve = easing.toEasing()
        val totalMs = durationMillis
        val frames = keyframes<Float> {
            // Qualified on both sides: inside this lambda the implicit receiver
            // is the spec config, which has its own `durationMillis`.
            durationMillis = totalMs
            for (index in this@KeyframeSpec.values.indices) {
                val atMs = (this@KeyframeSpec.keyTimes[index] * totalMs).roundToInt()
                this@KeyframeSpec.values[index] at atMs using curve
            }
        }
        return if (repeats) {
            infiniteRepeatable(animation = frames, repeatMode = RepeatMode.Restart)
        } else {
            frames
        }
    }
}

/**
 * Every animated surface in the app, and whether reduced motion silences it.
 *
 * This table IS invariant 6's scope, in one place, with the web line that
 * decides each row. The mascot and the confetti live in other files; they are
 * listed here so that "what does reduce-motion gate" has exactly one answer and
 * one test.
 */
enum class MotionSurface(val gatedByReduceMotion: Boolean) {
    /** Tile.tsx:59 — a bare `el.animate`. Tactile feedback, not decoration. */
    PRESS(false),

    /** Tile.tsx:61 — likewise. Invariant 3's entire wrong-answer penalty. */
    SHAKE(false),

    /** usePopFlourish.ts:29 — reads `prefers-reduced-motion` itself. */
    POP(true),

    /** GameFrame.tsx:57 — `motion-safe:animate-pulse`. */
    PULSE(true),

    /** Mascot.tsx — CLAUDE.md invariant 6 names it. Owned by :art. */
    MASCOT(true),

    /** Confetti.tsx — idem. Owned by the celebration layer. */
    CONFETTI(true),
}

/** The keyframe tables, read off the TypeScript. */
object Anim {

    // CSS Easing Functions Level 1, by the book. NOT Material's
    // `FastOutSlowInEasing` (0.4, 0, 0.2, 1): that is not what a browser runs,
    // and the web app is the source of truth.
    val EASE_OUT = Bezier(0f, 0f, 0.58f, 1f)
    val EASE_IN_OUT = Bezier(0.42f, 0f, 0.58f, 1f)

    /** `cubic-bezier(.2,1.35,.4,1)` — the pop's overshoot (usePopFlourish.ts). */
    val POP_OVERSHOOT = Bezier(0.2f, 1.35f, 0.4f, 1f)

    /** Tailwind's `pulse` timing function. */
    val PULSE_CURVE = Bezier(0.4f, 0f, 0.6f, 1f)

    // src/components/Tile.tsx:
    //   const press = [scale(1), scale(0.9), scale(1)]
    //   el.animate(press, { duration: 130, easing: "ease-out" })
    /** The tactile squish every pick tile plays at pointer-down. Unitless scale. */
    val PRESS = KeyframeSpec(
        values = listOf(1f, 0.9f, 1f),
        keyTimes = listOf(0f, 0.5f, 1f),
        durationMillis = 130,
        easing = EASE_OUT,
    )

    // src/components/Tile.tsx:
    //   const shake = [translateX(0), translateX(-8px), translateX(8px),
    //                  translateX(-5px), translateX(0)]
    //   el.animate(shake, { duration: 300, easing: "ease-in-out" })
    /** The soft "not this one" wobble. Values are dp — see the UNITS note. */
    val SHAKE = KeyframeSpec(
        values = listOf(0f, -8f, 8f, -5f, 0f),
        keyTimes = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        durationMillis = 300,
        easing = EASE_IN_OUT,
    )

    // src/components/usePopFlourish.ts:
    //   POP_IN = [ {scale(0.4), opacity 0},
    //              {scale(1.18), opacity 1, offset: 0.68},
    //              {scale(1),    opacity 1} ]
    //   { duration: 480, easing: "cubic-bezier(.2,1.35,.4,1)" }
    val POP_SCALE = KeyframeSpec(
        values = listOf(0.4f, 1.18f, 1f),
        keyTimes = listOf(0f, 0.68f, 1f),
        durationMillis = 480,
        easing = POP_OVERSHOOT,
    )

    val POP_ALPHA = KeyframeSpec(
        values = listOf(0f, 1f, 1f),
        keyTimes = listOf(0f, 0.68f, 1f),
        durationMillis = 480,
        easing = POP_OVERSHOOT,
    )

    // Tailwind's `animate-pulse` on the GameFrame strip's live star:
    //   animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
    //   @keyframes pulse { 50% { opacity: .5 } }
    // Tailwind defines ONLY the 50% frame, so the 0% and 100% frames take the
    // element's own computed opacity — GameFrame.tsx:57 inlines 0.8. The live
    // star therefore breathes 0.8 -> 0.5 -> 0.8, never up to 1.
    const val PULSE_BASE_ALPHA = 0.8f
    const val PULSE_MIN_ALPHA = 0.5f
    const val PULSE_DURATION_MS = 2000

    /** The pulse track for an element whose resting opacity is [baseAlpha]. */
    fun pulseSpec(baseAlpha: Float = PULSE_BASE_ALPHA) = KeyframeSpec(
        values = listOf(baseAlpha, PULSE_MIN_ALPHA, baseAlpha),
        keyTimes = listOf(0f, 0.5f, 1f),
        durationMillis = PULSE_DURATION_MS,
        easing = PULSE_CURVE,
    )

    /**
     * Invariant 6 as one pure decision. `false` means "do not animate at all" —
     * not "animate less" — which is what the web media query does.
     */
    fun shouldAnimate(surface: MotionSurface, reduceMotion: ReduceMotionSource): Boolean =
        !(surface.gatedByReduceMotion && reduceMotion.isReduced)
}

// --- Compose plumbing --------------------------------------------------------
//
// From here down the file knows Compose exists. Nothing above it does, and
// nothing below it decides anything.

/**
 * A tile's two animated channels: the press squish and the reject wobble.
 *
 * Both are `Animatable`s and NEITHER is snapshot state the composition reads —
 * see the invariant 2 note in the header. Hold one per tile with
 * [rememberTileMotion] and apply it with [Modifier.tileMotion].
 *
 * Neither entry point takes a `ReduceMotionSource`, deliberately: the web does
 * not gate them (invariant 6, and the header).
 */
@Stable
class TileMotion(private val scope: CoroutineScope) {

    /** Unitless scale, 1 at rest. Read inside a `graphicsLayer` lambda only. */
    val scale: Animatable<Float, AnimationVector1D> = Animatable(1f)

    /** Horizontal offset in DP, 0 at rest. Converted to px by the layer. */
    val shiftDp: Animatable<Float, AnimationVector1D> = Animatable(0f)

    private var pressJob: Job? = null
    private var shakeJob: Job? = null

    /**
     * The squish. Call it synchronously inside the `touchDown` handler, before
     * the pick verdict is computed — the same beat as `Tile.tsx`.
     *
     * [onFinished] fires whether the take ran to its end or was replaced by a
     * newer one, which is how WAAPI settles a replaced animation's `finished`
     * promise; a caller's continuation must never be dropped.
     */
    fun press(onFinished: (() -> Unit)? = null) {
        pressJob?.cancel()
        pressJob = scope.launch {
            try {
                scale.snapTo(Anim.PRESS.start)
                scale.animateTo(Anim.PRESS.target, Anim.PRESS.toAnimationSpec())
            } finally {
                onFinished?.invoke()
            }
        }
    }

    /**
     * The wobble on a `Verdict.REJECT` — a shake and nothing else: no lock, no
     * error state, no lost round (invariant 3).
     *
     * Cancels a running [press] and snaps the scale back to 1, which reproduces
     * WAAPI's newest-wins replace: on the web a rejected tile only shakes, it
     * never squishes (porting note 2).
     */
    fun shake(onFinished: (() -> Unit)? = null) {
        pressJob?.cancel()
        shakeJob?.cancel()
        shakeJob = scope.launch {
            try {
                scale.snapTo(1f)
                shiftDp.snapTo(Anim.SHAKE.start)
                shiftDp.animateTo(Anim.SHAKE.target, Anim.SHAKE.toAnimationSpec())
            } finally {
                onFinished?.invoke()
            }
        }
    }
}

/** One [TileMotion] per tile, tied to the composition's scope. */
@Composable
fun rememberTileMotion(): TileMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { TileMotion(scope) }
}

/**
 * Apply a tile's press and shake.
 *
 * The two `.value` reads happen INSIDE the `graphicsLayer` lambda. Moving
 * either of them into the composable body is invariant 2's failure mode.
 */
fun Modifier.tileMotion(motion: TileMotion): Modifier = this.graphicsLayer {
    val s = motion.scale.value
    scaleX = s
    scaleY = s
    translationX = motion.shiftDp.value.dp.toPx()
}

/**
 * The one-shot celebrate-into-view flourish — the balance pill, the earn badge.
 * `usePopFlourish.ts`, which fires once on mount and honours reduced motion.
 */
@Stable
class PopMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
) {
    // Resting at the natural state, not at the first keyframe: WAAPI's default
    // `fill: none` leaves the element at its computed style until the animation
    // actually starts, and under reduced motion it never starts at all.
    val scale: Animatable<Float, AnimationVector1D> = Animatable(1f)
    val alpha: Animatable<Float, AnimationVector1D> = Animatable(1f)

    private var job: Job? = null

    /** True when reduced motion is on: nothing will animate. */
    val gated: Boolean get() = !Anim.shouldAnimate(MotionSurface.POP, reduceMotion)

    /**
     * Play the flourish once. Under [gated], nothing animates and [onFinished]
     * fires SYNCHRONOUSLY — a gate skips the animation, never the caller's
     * continuation.
     */
    fun play(onFinished: (() -> Unit)? = null) {
        if (gated) {
            onFinished?.invoke()
            return
        }
        job?.cancel()
        job = scope.launch {
            try {
                coroutineScope {
                    launch {
                        scale.snapTo(Anim.POP_SCALE.start)
                        scale.animateTo(Anim.POP_SCALE.target, Anim.POP_SCALE.toAnimationSpec())
                    }
                    launch {
                        alpha.snapTo(Anim.POP_ALPHA.start)
                        alpha.animateTo(Anim.POP_ALPHA.target, Anim.POP_ALPHA.toAnimationSpec())
                    }
                }
            } finally {
                onFinished?.invoke()
            }
        }
    }
}

/**
 * A [PopMotion] that fires once when it enters the composition — the port of
 * `usePopFlourish`'s mount-only effect. [key] restarts it, for a badge that
 * should celebrate again on a new value.
 */
@Composable
fun rememberPopFlourish(reduceMotion: ReduceMotionSource, key: Any? = Unit): PopMotion {
    val scope = rememberCoroutineScope()
    val motion = remember(scope, reduceMotion) { PopMotion(scope, reduceMotion) }
    LaunchedEffect(motion, key) { motion.play() }
    return motion
}

/** Apply a pop flourish. Deferred reads, as everywhere in this file. */
fun Modifier.popFlourish(motion: PopMotion): Modifier = this.graphicsLayer {
    val s = motion.scale.value
    scaleX = s
    scaleY = s
    alpha = motion.alpha.value
}

/**
 * The repeating breathe on the GameFrame strip's live star.
 *
 * [baseAlpha] is the element's own resting opacity, and it is BOTH endpoints of
 * the track — Tailwind authors only the 50% frame. Under reduced motion the
 * star simply sits at [baseAlpha], which is what dropping a `motion-safe:`
 * class does.
 */
@Stable
class PulseMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
    val baseAlpha: Float = Anim.PULSE_BASE_ALPHA,
) {
    val alpha: Animatable<Float, AnimationVector1D> = Animatable(baseAlpha)

    private var job: Job? = null

    /** True when reduced motion is on: the star is static, not hidden. */
    val gated: Boolean get() = !Anim.shouldAnimate(MotionSurface.PULSE, reduceMotion)

    /** Start breathing. Idempotent — a second call does not stack a second run. */
    fun start() {
        if (gated) return
        if (job?.isActive == true) return
        val spec = Anim.pulseSpec(baseAlpha)
        job = scope.launch {
            alpha.snapTo(spec.start)
            alpha.animateTo(spec.target, spec.toAnimationSpec(repeats = true))
        }
    }

    /** Stop; the star returns to [baseAlpha]. Safe when nothing is running. */
    fun stop() {
        job?.cancel()
        job = null
        scope.launch { alpha.snapTo(baseAlpha) }
    }
}

/**
 * A [PulseMotion] that breathes while [active] — the strip's current round —
 * and rests otherwise.
 */
@Composable
fun rememberPulse(
    reduceMotion: ReduceMotionSource,
    active: Boolean,
    baseAlpha: Float = Anim.PULSE_BASE_ALPHA,
): PulseMotion {
    val scope = rememberCoroutineScope()
    val motion = remember(scope, reduceMotion, baseAlpha) {
        PulseMotion(scope, reduceMotion, baseAlpha)
    }
    LaunchedEffect(motion, active) {
        if (active) motion.start() else motion.stop()
    }
    return motion
}

/** Apply a pulse. Deferred read, as everywhere in this file. */
fun Modifier.pulseAlpha(motion: PulseMotion): Modifier = this.graphicsLayer {
    alpha = motion.alpha.value
}
