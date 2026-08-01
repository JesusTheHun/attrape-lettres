package fr.dappit.attrapelettres.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.fixedSp
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.Bezier
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// `src/shop/anim.ts` — the shop's micro-animations, as Compose.
//
// The twin of iOS's `Shop/ShopAnim.swift`, with Core Animation's particle
// layers replaced by the one shape :ui already uses for this: an `Animatable`
// read INSIDE a `Modifier.graphicsLayer { }` lambda.
//
// INVARIANT 2 — nothing here is ever read from a composable body.
//
// Every animated channel below (position, scale, alpha, rotation) is derived
// from a SINGLE `Animatable<Float>` progress value, sampled inside the
// `graphicsLayer` lambda through a [MotionTrack]. That is a deferred read: the
// lambda is not part of the composition, so a frame costs one re-layer and zero
// recompositions. The particle LIST is snapshot state, but it changes twice per
// celebration (spawn, and the last particle retiring), never per frame — the
// same bargain `ConfettiSystem.isRunning` strikes.
//
// The one thing that is NOT a `graphicsLayer` read in the shop is the savings
// meter's fill width, which is a painted band rather than a transform and is
// therefore read inside a `drawBehind` — see `Meter.kt`, and A12 on why a
// draw-phase read satisfies the same invariant.
//
// INVARIANT 6 / D29 — `shop/anim.ts` gates EVERY helper on
// `prefers-reduced-motion` (`if (!el || reducedMotion()) return`, lines 27, 32,
// 54, 62, 84 and 135). That is the OPPOSITE of `Tile.tsx`, whose press is
// deliberately ungated. So every entry point here takes a `ReduceMotionSource`
// and drops the take when it is reduced.
//
// The gate itself is `shopMotionAllowed`, next door in `ShopItem.kt`, and this
// file DELEGATES to it rather than restating it. One screen, one gate: two
// functions that both decide « does the shop animate » is exactly how the two
// halves of a screen end up disagreeing. Same reason the squish vocabulary is
// that file's `ShopTileMotion` / `Modifier.shopTilePress` and not a second copy
// here — the shop's press is `scale(0.94)` where a tile's is `scale(0.9)`, and
// ONE of those numbers is enough.
//
// UNITS. Everything in this file is in CSS px == Android dp: the web's
// `font-size: 22px`, `left: ${x0}px` and `translate(calc(-50% + ${mx}px), …)`
// are CSS pixels on the element, and a dp is the same length. The
// `graphicsLayer` converts once, with `.dp.toPx()`. Writing raw pixels into
// `translationX` would fling the stars three times as far on a 3x phone.
// ---------------------------------------------------------------------------

// --- Geometry ---------------------------------------------------------------

/**
 * An axis-aligned rectangle in DP, in the celebration overlay's own space.
 *
 * Deliberately not `androidx.compose.ui.geometry.Rect`: that one is in raw
 * pixels at every call site that produces it (`onGloballyPositioned`), and the
 * arithmetic below is authored in CSS px. Keeping a separate type is what stops
 * a px rect being handed to a dp spec, which is the bug that would silently
 * scale the whole flight by the display density. Same reasoning as :art's
 * `SvgRect`.
 */
data class ShopRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val midX: Float get() = x + width / 2f
    val midY: Float get() = y + height / 2f
}

/**
 * One animated channel of a particle: a keyframe track, sampled by progress.
 *
 * WAAPI's `easing` option applies to each keyframe INTERVAL rather than to the
 * timeline as a whole (`Anim.kt`'s porting note 1), so [easing] is re-applied
 * inside every span. Pure arithmetic, which is the point — the whole flight is
 * assertable on the host, where no frame clock exists.
 */
data class MotionTrack(
    val values: List<Float>,
    val keyTimes: List<Float>,
    val easing: Bezier,
) {
    init {
        require(values.size >= 2) { "a track needs at least two values" }
        require(values.size == keyTimes.size) { "one key time per value" }
        require(keyTimes.first() == 0f) { "the first key time is 0" }
        require(keyTimes.last() == 1f) { "the last key time is 1" }
    }

    /** Built once, not per sample: this is read sixty times a second. */
    private val curve: Easing = easing.toEasing()

    /** The channel's value at timeline position [t], 0..1. */
    fun at(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        for (i in 0 until values.size - 1) {
            val from = keyTimes[i]
            val to = keyTimes[i + 1]
            if (clamped > to) continue
            val span = to - from
            val local = if (span <= 0f) 1f else (clamped - from) / span
            // The two ends are returned VERBATIM rather than interpolated to.
            // `a + (b - a) * 1` is `b` only up to rounding, and a keyframe that
            // lands a float epsilon away from its authored value is a keyframe
            // no assertion can pin — which would make this whole file
            // untestable for the sake of one multiply.
            if (local <= 0f) return values[i]
            if (local >= 1f) return values[i + 1]
            return values[i] + (values[i + 1] - values[i]) * curve.transform(local)
        }
        return values.last()
    }
}

/**
 * One throwaway glyph of a celebration — the web's `document.createElement`
 * particle, as data.
 *
 * Positions are the particle's CENTRE, in dp, in the overlay's space: the web
 * writes `left/top` plus a `translate(-50%,-50%)`, which is a centred box.
 */
data class ShopParticle(
    val glyph: String,
    /** `font-size`, in dp. */
    val fontSize: Float,
    val durationMillis: Int,
    /** WAAPI `delay`. */
    val delayMillis: Int,
    val x: MotionTrack,
    val y: MotionTrack,
    val scale: MotionTrack,
    val alpha: MotionTrack,
    val rotationDegrees: MotionTrack,
)

// --- The authored numbers ----------------------------------------------------

/**
 * The half of `src/shop/anim.ts` that is NOT the press and the pop: the two
 * celebration curves, the throwaway layer's z-order, and the one gate.
 *
 * `PRESS` and `POP` live in `ShopItem.kt`'s [ShopAnimSpecs], with the
 * `ShopTileMotion` that plays them.
 */
object ShopAnim {

    /** `{ easing: "cubic-bezier(.3,.6,.4,1)" }` — the star flight. */
    val FLIGHT_CURVE = Bezier(0.3f, 0.6f, 0.4f, 1f)

    /** `{ easing: "cubic-bezier(.2,.7,.3,1)" }` — the sparkle fling, and the meter sweep. */
    val SPARK_CURVE = Bezier(0.2f, 0.7f, 0.3f, 1f)

    /** `z-index: 60` on the throwaway layer — over the try-on dialog's `z-50`. */
    const val Z_INDEX: Float = 60f

    /**
     * Invariant 6, once, for the whole screen. `false` means "do not run at
     * all", which is what the web's early return does.
     *
     * Delegated to `ShopItem.kt`'s `shopMotionAllowed` so the shop has exactly
     * one answer to this question.
     */
    fun shouldAnimate(reduceMotion: ReduceMotionSource): Boolean =
        shopMotionAllowed(reduceMotion)
}

// --- starFlight --------------------------------------------------------------

/**
 * `starFlight(from, to, count)` — a handful of ⭐ fly wallet → mascot, « so a
 * child SEES stars leave their purse when something is bought ».
 *
 * Pure, and host-tested number for number against `anim.ts`.
 */
object StarFlightSpec {

    const val GLYPH = "⭐"

    /** `font-size: 22px`. */
    const val FONT_SIZE = 22f

    const val START_SCALE = 0.5f
    const val MID_SCALE = 1.15f
    const val END_SCALE = 0.35f
    const val END_OPACITY = 0.2f

    /** `duration: 620 + i * 45`. */
    const val DURATION_BASE = 620
    const val DURATION_STEP = 45

    /** `delay: i * 70`. */
    const val DELAY_STEP = 70

    /**
     * The flock.
     *
     * `if (a.width === 0 || b.width === 0) return;` — a rect that has not laid
     * out yet aborts the whole flight, so this returns an EMPTY list rather
     * than a degenerate one.
     */
    fun particles(from: ShopRect?, to: ShopRect?, count: Int): List<ShopParticle> {
        if (from == null || to == null) return emptyList()
        if (from.width == 0f || to.width == 0f) return emptyList()
        val x0 = from.midX
        val y0 = from.midY
        val x1 = to.midX
        val y1 = to.midY
        return (0 until count).map { i ->
            // « Each star arcs on its own bow: the midpoint bulges sideways/
            // upward a bit more per star, so the flock fans out instead of
            // forming a single file. »
            val bow = (if (i % 2 == 0) 1f else -1f) * (14f + i * 7f)
            val mx = (x1 - x0) / 2f + bow
            val my = (y1 - y0) / 2f - 36f - i * 4f
            ShopParticle(
                glyph = GLYPH,
                fontSize = FONT_SIZE,
                durationMillis = DURATION_BASE + i * DURATION_STEP,
                delayMillis = i * DELAY_STEP,
                x = track(listOf(x0, x0 + mx, x1), ShopAnim.FLIGHT_CURVE),
                y = track(listOf(y0, y0 + my, y1), ShopAnim.FLIGHT_CURVE),
                scale = track(listOf(START_SCALE, MID_SCALE, END_SCALE), ShopAnim.FLIGHT_CURVE),
                alpha = track(listOf(0f, 1f, END_OPACITY), ShopAnim.FLIGHT_CURVE),
                rotationDegrees = track(listOf(0f, 0f, 0f), ShopAnim.FLIGHT_CURVE),
            )
        }
    }

    /** `offset: 0.5` on the middle keyframe — WAAPI's default spacing anyway. */
    private fun track(values: List<Float>, easing: Bezier) =
        MotionTrack(values, listOf(0f, 0.5f, 1f), easing)
}

// --- growBurst ---------------------------------------------------------------

/**
 * `growBurst(anchor)` — the cloud "poof" plus a ring of twelve sparkles when
 * the mascot grows a stage.
 */
object GrowBurstSpec {

    const val CLOUD_GLYPH = "☁️"
    val SPARKLE_GLYPHS = listOf("✨", "⭐", "🌟", "💫")

    /** `{ duration: 900 }` on every puff. */
    const val PUFF_DURATION = 900

    /** `const N = 12`. */
    const val SPARK_COUNT = 12

    /** `const cloud = rect.width * 0.42` — the puff's font size. */
    const val CLOUD_FRACTION = 0.42f

    /** `duration: 720 + (i % 3) * 120`. */
    const val SPARK_DURATION_BASE = 720
    const val SPARK_DURATION_STEP = 120

    /** `if (rect.width === 0) return;` */
    fun particles(around: ShopRect?): List<ShopParticle> {
        if (around == null || around.width == 0f) return emptyList()
        val cx = around.midX
        val cy = around.midY
        val out = ArrayList<ShopParticle>(3 + SPARK_COUNT)

        // Cloud puffs rising and fading — the "poof". The web's transforms are
        // `translate(dx, -40%) → (dx, -70%) → (dx, -120%)`, percentages of the
        // puff's OWN box, so a centre-anchored particle rides
        // `+0.1·h → −0.2·h → −0.7·h` off the anchor.
        val cloud = around.width * CLOUD_FRACTION
        val puffs = listOf(
            Triple(0f, 0, 1.3f),
            Triple(-cloud * 0.5f, 60, 1f),
            Triple(cloud * 0.5f, 120, 1f),
        )
        for ((dx, delayMillis, scale) in puffs) {
            out.add(
                ShopParticle(
                    glyph = CLOUD_GLYPH,
                    fontSize = cloud,
                    durationMillis = PUFF_DURATION,
                    delayMillis = delayMillis,
                    x = puffTrack(listOf(cx + dx, cx + dx, cx + dx)),
                    y = puffTrack(listOf(cy + 0.1f * cloud, cy - 0.2f * cloud, cy - 0.7f * cloud)),
                    scale = puffTrack(listOf(0.3f, scale, scale * 1.15f)),
                    alpha = puffTrack(listOf(0f, 0.95f, 0f)),
                    rotationDegrees = puffTrack(listOf(0f, 0f, 0f)),
                )
            )
        }

        // Sparkles flung outward in a ring.
        for (i in 0 until SPARK_COUNT) {
            val angle = i.toDouble() / SPARK_COUNT * 2.0 * Math.PI + (i % 2) * 0.26
            val dist = around.width * (0.55f + (i % 3) * 0.14f)
            val dx = (cos(angle) * dist).toFloat()
            val dy = (sin(angle) * dist).toFloat() - around.height * 0.12f
            out.add(
                ShopParticle(
                    glyph = SPARKLE_GLYPHS[i % SPARKLE_GLYPHS.size],
                    fontSize = 16f + (i % 3) * 6f,
                    durationMillis = SPARK_DURATION_BASE + (i % 3) * SPARK_DURATION_STEP,
                    delayMillis = 0,
                    x = sparkTrack(listOf(cx, cx + dx * 0.6f, cx + dx)),
                    y = sparkTrack(listOf(cy, cy + dy * 0.6f, cy + dy)),
                    scale = sparkTrack(listOf(0.2f, 1f, 0.3f)),
                    alpha = sparkTrack(listOf(0f, 1f, 0f)),
                    rotationDegrees = sparkTrack(listOf(0f, 90f, 200f)),
                )
            )
        }
        return out
    }

    /** `offset: 0.4` on the puff's middle keyframe, and CSS `ease-out`. */
    private fun puffTrack(values: List<Float>) =
        MotionTrack(values, listOf(0f, 0.4f, 1f), Anim.EASE_OUT)

    /** `offset: 0.35` on the sparkle's, on `cubic-bezier(.2,.7,.3,1)`. */
    private fun sparkTrack(values: List<Float>) =
        MotionTrack(values, listOf(0f, 0.35f, 1f), ShopAnim.SPARK_CURVE)
}

// --- The celebration overlay --------------------------------------------------

/** One particle in flight: its authored spec plus the progress driving it. */
@Stable
class LiveShopParticle(val spec: ShopParticle) {
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)
}

/**
 * The shop's throwaway particle layer — the web's `document.body` overlay,
 * `position: fixed; inset: 0; pointer-events: none; z-index: 60`.
 *
 * Hold ONE per shop screen, spawn into it from the purchase handler, and render
 * it with [ShopCelebrationOverlay] on top of everything else. Particles retire
 * themselves; the web's `if (--pending <= 0) layer.remove()` bookkeeping is the
 * list emptying.
 */
@Stable
class ShopCelebrations(private val reduceMotion: ReduceMotionSource) {

    private val live = mutableStateListOf<LiveShopParticle>()

    /** What the overlay draws. Never copies. */
    val particles: List<LiveShopParticle> get() = live

    /** `starFlight(from, to, count)`. A no-op under reduced motion. */
    fun starFlight(from: ShopRect?, to: ShopRect?, count: Int) {
        spawn(StarFlightSpec.particles(from, to, count))
    }

    /** `growBurst(anchor)`. A no-op under reduced motion. */
    fun growBurst(around: ShopRect?) {
        spawn(GrowBurstSpec.particles(around))
    }

    private fun spawn(specs: List<ShopParticle>) {
        if (!ShopAnim.shouldAnimate(reduceMotion)) return
        for (spec in specs) live.add(LiveShopParticle(spec))
    }

    /** One particle finished — the web's `pending` decrement. */
    fun retire(particle: LiveShopParticle) {
        live.remove(particle)
    }

    /** Drop everything, without waiting. Used when the shop is left mid-cheer. */
    fun clear() = live.clear()
}

/** One [ShopCelebrations] per shop screen. */
@Composable
fun rememberShopCelebrations(reduceMotion: ReduceMotionSource): ShopCelebrations =
    remember(reduceMotion) { ShopCelebrations(reduceMotion) }

/**
 * The particle layer.
 *
 * Give it a `Modifier.fillMaxSize()` in a `Box` above the shop's content — the
 * caller owns the z-order, exactly as `ConfettiOverlay`'s caller does. It never
 * takes a touch (there is no `pointerInput` on it at all, which is
 * `pointer-events: none` without needing a flag) and it is invisible to
 * TalkBack.
 *
 * PORT NOTE, recorded rather than fudged: during its `delay` a web particle
 * shows its computed style (visible, at the origin) because WAAPI's default
 * `fill: none` leaves the element alone until the take starts. Here the progress
 * sits at 0, which is the FIRST keyframe — alpha 0. A staggered star is
 * therefore invisible for its 70 ms of stagger instead of parked at the wallet.
 * Strictly quieter, never louder, and the alternative (a second "not yet
 * started" state per particle) buys 70 ms of a 22 dp glyph.
 */
@Composable
fun ShopCelebrationOverlay(celebrations: ShopCelebrations, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    Box(modifier.clearAndSetSemantics { }) {
        for (particle in celebrations.particles) {
            key(particle) {
                LaunchedEffect(particle) {
                    if (particle.spec.delayMillis > 0) delay(particle.spec.delayMillis.toLong())
                    // LINEAR, deliberately: the authored curves are per-INTERVAL
                    // and already applied inside each `MotionTrack`. Easing the
                    // progress as well would apply them twice.
                    particle.progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = particle.spec.durationMillis,
                            easing = LinearEasing,
                        ),
                    )
                    celebrations.retire(particle)
                }
                BasicText(
                    text = particle.spec.glyph,
                    style = TextStyle(
                        fontSize = fixedSp(particle.spec.fontSize, fontScale),
                        fontFamily = Typography.appFamily,
                    ),
                    modifier = Modifier.graphicsLayer {
                        // Every read below is inside this lambda. One re-layer
                        // per frame, zero recompositions.
                        val t = particle.progress.value
                        val s = particle.spec.scale.at(t)
                        scaleX = s
                        scaleY = s
                        rotationZ = particle.spec.rotationDegrees.at(t)
                        alpha = particle.spec.alpha.at(t)
                        // `translate(-50%,-50%)`: the authored point is the
                        // particle's CENTRE, and the layer's origin is its
                        // top-left.
                        translationX = particle.spec.x.at(t).dp.toPx() - size.width / 2f
                        translationY = particle.spec.y.at(t).dp.toPx() - size.height / 2f
                    },
                )
            }
        }
    }
}
