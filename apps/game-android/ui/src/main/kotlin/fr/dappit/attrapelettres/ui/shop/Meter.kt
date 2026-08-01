package fr.dappit.attrapelettres.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.fixedSp
import fr.dappit.attrapelettres.ui.interaction.Anim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// `src/shop/Meter.tsx` — SavingsMeter: « how close am I to affording this? »
// without any arithmetic.
//
//   const MIN_FILL = 0.07;
//
//   function fillRatio(balance: number, cost: number): number {
//     if (balance <= 0 || cost <= 0) return 0;
//     return Math.max(MIN_FILL, Math.min(1, balance / cost));
//   }
//
//   export function SavingsMeter({ cost, balance, since, height = 10 }) {
//     const shownRef = useRef(fillRatio(since, cost));   // ratio on screen
//     const ratio = fillRatio(balance, cost);
//     useEffect(() => {
//       const from = shownRef.current;
//       shownRef.current = ratio;
//       if (from === ratio) return;
//       meterFill(fillRef.current, from * 100, ratio * 100);
//       if (ratio > from) tipSparkle(tipRef.current);
//     }, [ratio]);
//     … role="img" aria-label={`${balance} étoiles sur ${cost}`}
//   }
//
// A fat gold bar filling toward the price: a six-year-old reads fills, not
// subtraction. On mount it sweeps from the balance the child LAST SAW in the
// shop (`since`) to today's, so stars earned between visits read as motion plus
// a sparkle rather than an invisibly small static delta. A non-empty wallet
// always shows a sliver (`MIN_FILL`), so « I have some » is always visible.
//
// INVARIANT 8 / INVARIANT 9 — nothing here persists, accumulates or computes a
// balance. `balance` and `since` are handed in by the shop, which reads them
// from `ProfileStore`; this file divides one number by another to get a width
// and does nothing else with either.
//
// INVARIANT 2 — the fill width is an `Animatable` read inside a `drawBehind`
// lambda, not `Modifier.width(animatedDp)`. A width is a LAYOUT property: as
// snapshot state it would re-measure the growth card sixty times a second. As a
// painted band it costs one re-draw of this leaf. That is the same trade A12
// records for `Tile`'s highlight ring, and it is why the bar is drawn rather
// than sized.
// ---------------------------------------------------------------------------

// --- The arithmetic (pure, host-tested) --------------------------------------

object SavingsMeterMetrics {

    /**
     * `MIN_FILL` — never render progress smaller than this once the wallet is
     * non-empty.
     */
    const val MIN_FILL: Double = 0.07

    /** `height = 10` — the TSX default. « Keep it fat: thin bars hide small progress. » */
    val DEFAULT_HEIGHT: Dp = 10.dp

    /**
     * `rgba(90,58,30,0.14)` — the track. 90/58/30 is `#5A3A1E`, i.e. the ink
     * brown at 14 %.
     */
    const val TRACK_OPACITY: Float = 0.14f

    /** `text-sm` — the ✨ at the fill tip. */
    val SPARKLE_SIZE: Dp = Typography.Size.sm

    /** `meterFill` — `{ duration: 900, easing: "cubic-bezier(.2,.7,.3,1)" }`. */
    const val SWEEP_DURATION_MS: Int = 900
    val SWEEP_CURVE = ShopAnim.SPARK_CURVE

    /**
     * `tipSparkle` — `{ duration: 620, delay: 780, easing: "ease-out" }` over
     * `scale 0.2 → 1.4 (offset .5) → 0.6` and `opacity 0 → 1 → 0`. « Lands as
     * the fill arrives. »
     */
    const val SPARKLE_DURATION_MS: Int = 620
    const val SPARKLE_DELAY_MS: Int = 780

    val SPARKLE_SCALE = MotionTrack(
        values = listOf(0.2f, 1.4f, 0.6f),
        keyTimes = listOf(0f, 0.5f, 1f),
        easing = Anim.EASE_OUT,
    )

    val SPARKLE_ALPHA = MotionTrack(
        values = listOf(0f, 1f, 0f),
        keyTimes = listOf(0f, 0.5f, 1f),
        easing = Anim.EASE_OUT,
    )
}

/**
 * `fillRatio(balance, cost)` — 0…1, with the `MIN_FILL` sliver.
 *
 * Both guards are the TSX's and both matter: a zero or negative balance is a
 * genuinely empty bar (no sliver), and a zero or negative cost would divide by
 * nothing.
 *
 * `Double`, not `Float`: a JS number is a double and the test compares against a
 * transcription of the TypeScript, where `7 / 100` must be exactly `0.07`.
 */
fun savingsFillRatio(balance: Int, cost: Int): Double {
    if (balance <= 0 || cost <= 0) return 0.0
    return maxOf(
        SavingsMeterMetrics.MIN_FILL,
        minOf(1.0, balance.toDouble() / cost.toDouble()),
    )
}

/**
 * What the mount effect decides: where the bar starts, where it lands, whether
 * it moves at all, and whether the tip sparkles.
 *
 * Extracted so the rule — *sparkle only when the fill GREW* — is asserted on the
 * host rather than only visible on a device.
 */
data class SavingsMeterSweep(val from: Double, val to: Double) {

    /** `if (from === ratio) return;` — no animation when nothing changed. */
    val animates: Boolean get() = from != to

    /**
     * `if (ratio > from) tipSparkle(...)` — a shrinking bar (the child spent)
     * still sweeps, silently. No sparkle for losing stars.
     */
    val sparkles: Boolean get() = to > from
}

/** The mount sweep for a (balance, since, cost) triple. */
fun savingsMeterSweep(balance: Int, since: Int, cost: Int): SavingsMeterSweep =
    SavingsMeterSweep(
        from = savingsFillRatio(since, cost),
        to = savingsFillRatio(balance, cost),
    )

// --- The two animations ------------------------------------------------------

/**
 * The bar's on-screen ratio (the port of `shownRef`) and the tip sparkle.
 *
 * Both are `Animatable`s and neither is snapshot state a composable body reads.
 * Both are gated, because `shop/anim.ts` gates `meterFill` and `tipSparkle`
 * itself — and the bar still LANDS on the new value either way: a child with the
 * setting on sees the right width, just not the trip.
 */
@Stable
class SavingsMeterMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
    initialRatio: Float,
) {
    /** The ratio currently ON SCREEN. Read inside `drawBehind` only. */
    val shown: Animatable<Float, AnimationVector1D> = Animatable(initialRatio)

    /** The sparkle's timeline, 0..1. Read inside `graphicsLayer` only. */
    val sparkle: Animatable<Float, AnimationVector1D> = Animatable(0f)

    private var sweepJob: Job? = null
    private var sparkleJob: Job? = null

    val gated: Boolean get() = !ShopAnim.shouldAnimate(reduceMotion)

    /**
     * `useEffect(..., [ratio])`: read where the bar stands, remember the new
     * target, and animate the journey.
     */
    fun sweep(to: Double) {
        val step = SavingsMeterSweep(shown.value.toDouble(), to)
        if (!step.animates) return
        sweepJob?.cancel()
        if (gated) {
            sweepJob = scope.launch { shown.snapTo(to.toFloat()) }
            return
        }
        sweepJob = scope.launch {
            shown.animateTo(
                targetValue = to.toFloat(),
                animationSpec = tween(
                    durationMillis = SavingsMeterMetrics.SWEEP_DURATION_MS,
                    easing = SavingsMeterMetrics.SWEEP_CURVE.toEasing(),
                ),
            )
        }
        if (step.sparkles) flash()
    }

    /**
     * `tipSparkle`. LINEAR progress on purpose: the authored ease-out is
     * per-INTERVAL and already lives in the two [MotionTrack]s.
     *
     * No `fill` mode in the TSX's `el.animate(…)`, so the resting opacity of 0
     * stands once the take ends — the sparkle vanishes rather than sticking,
     * which is what snapping back to 0 reproduces.
     */
    private fun flash() {
        sparkleJob?.cancel()
        sparkleJob = scope.launch {
            sparkle.snapTo(0f)
            delay(SavingsMeterMetrics.SPARKLE_DELAY_MS.toLong())
            sparkle.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SavingsMeterMetrics.SPARKLE_DURATION_MS,
                    easing = LinearEasing,
                ),
            )
            sparkle.snapTo(0f)
        }
    }
}

// --- The view -----------------------------------------------------------------

/**
 * `<SavingsMeter cost balance since height />`.
 *
 * @param cost the price the bar fills toward.
 * @param balance the child's stars right now.
 * @param since the balance at the previous shop visit — where the fill animates
 *   FROM. Seeded into [SavingsMeterMotion] at construction, exactly as the TSX
 *   seeds `shownRef`, so the first frame paints where the child left the bar and
 *   the sweep has somewhere to come from.
 * @param height bar height. Keep it fat.
 */
@Composable
fun SavingsMeter(
    cost: Int,
    balance: Int,
    since: Int,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    height: Dp = SavingsMeterMetrics.DEFAULT_HEIGHT,
) {
    val ratio = savingsFillRatio(balance, cost)
    val scope = rememberCoroutineScope()
    val fontScale = LocalDensity.current.fontScale
    // Keyed on `cost` and NOT on `balance`: re-seeding on every balance change
    // would make the bar jump instead of sweep, which is the whole point of the
    // `since` prop.
    val motion = remember(scope, reduceMotion, cost) {
        SavingsMeterMotion(scope, reduceMotion, savingsFillRatio(since, cost).toFloat())
    }

    LaunchedEffect(motion, ratio) { motion.sweep(ratio) }

    // The track's width in px, for placing the sparkle at the fill tip. Written
    // on layout only — never per frame.
    val widthPx = remember { mutableFloatStateOf(0f) }

    val trackColor = Palette.ink.color.copy(alpha = SavingsMeterMetrics.TRACK_OPACITY)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { widthPx.floatValue = it.width.toFloat() }
            // `role="img"` + `aria-label={`${balance} étoiles sur ${cost}`}`.
            // Note « étoiles » is NOT pluralised by the TSX, so 1 star still
            // says « étoiles »; ported as authored.
            .semantics(mergeDescendants = true) {
                role = Role.Image
                contentDescription = Copy.Shop.savings(balance, cost)
            }
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                // `block w-full overflow-hidden rounded-full` + the 14 % track.
                drawRoundRect(color = trackColor, cornerRadius = radius)
                // THE DEFERRED READ. Draw phase, so a frame of the sweep costs
                // one re-draw of this leaf and zero recompositions.
                val filled = (motion.shown.value * size.width).coerceIn(0f, size.width)
                if (filled > 0f) {
                    val band = Size(filled, size.height)
                    // `linear-gradient(90deg,#FFC107,#FFD54F)` across the FILL,
                    // not across the track — the gradient travels with the bar.
                    drawRoundRect(
                        brush = Palette.savingsFill.brush(band),
                        size = band,
                        cornerRadius = radius,
                    )
                }
            },
    ) {
        // « Sparkle parked (invisible) at the fill tip; tipSparkle() flashes
        // it. » `overflow-visible` on the outer span is why it may hang past the
        // bar, which a Compose `Box` allows by default.
        BasicText(
            text = Copy.Shop.SAVINGS_SPARKLE,
            style = TextStyle(
                fontSize = fixedSp(SavingsMeterMetrics.SPARKLE_SIZE.value, fontScale),
                fontFamily = Typography.appFamily,
            ),
            modifier = Modifier.graphicsLayer {
                val t = motion.sparkle.value
                val s = SavingsMeterMetrics.SPARKLE_SCALE.at(t)
                scaleX = s
                scaleY = s
                alpha = SavingsMeterMetrics.SPARKLE_ALPHA.at(t)
                // `left: ${ratio * 100}%; top: 50%` plus the web's
                // `translate(-50%,-50%)`.
                // `size` inside this lambda is the SPARKLE's own box; the bar's
                // height comes from the authored parameter, converted here
                // because a `GraphicsLayerScope` is a `Density`.
                translationX = ratio.toFloat() * widthPx.floatValue - size.width / 2f
                translationY = height.toPx() / 2f - size.height / 2f
            },
        )
    }
}
