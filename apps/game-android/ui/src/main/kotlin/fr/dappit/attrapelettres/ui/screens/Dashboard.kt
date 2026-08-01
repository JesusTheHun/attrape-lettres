package fr.dappit.attrapelettres.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.art.mascot.MascotRigView
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.liftedPill
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.fluidPercent
import fr.dappit.attrapelettres.ui.interaction.Bezier
import fr.dappit.attrapelettres.ui.interaction.KeyframeSpec
import fr.dappit.attrapelettres.ui.interaction.PopMotion
import fr.dappit.attrapelettres.ui.interaction.popFlourish
import fr.dappit.attrapelettres.ui.interaction.rememberPopFlourish
import fr.dappit.attrapelettres.ui.interaction.touchDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===========================================================================
// `src/components/Dashboard.tsx` — « Mon copain », the child's home base.
// Worked example: `apps/game-ios/Sources/ALUI/Screens/Dashboard.swift`.
//
// A live mascot, the big gold balance, a friendly growth meter, and the two
// doors (Boutique / Changer de copain). Everything reads from `ProfileStore`;
// no storage access, and no arithmetic beyond one division for a width.
//
// -- INVARIANT 9 LIVES ON THIS SCREEN ---------------------------------------
// This is the screen that SHOWS a total, so it is the screen most likely to be
// "optimised" into keeping one. It must not.
//
//   - `balance` is `profiles.profile.balance`, i.e. `balanceOf(stars)` — the
//     merge fold over per-device earned/spent counters, recomputed on every
//     read. There is no stored property, no remembered copy, no cache and no
//     `+=` anywhere in this file.
//   - `PersistedProfile` has no balance property to write even if one tried.
//   - Nothing here calls a mutating `ProfileStore` method AT ALL: the whole
//     screen is a read plus three callbacks. It cannot mint a point, which is
//     invariant 8's other half (`sessionReward` is the only earner, and it is
//     three modules away from here).
//
// -- INVARIANT 5's sibling --------------------------------------------------
// Neither door is ever locked. There is no `enabled` flag, no ledger read and
// no growth precondition below: the shop and the companion picker are one tap
// away at stage 1 exactly as they are at stage 10.
//
// -- THE GROWTH SWEEP, AND WHY IT IS A DRAW-PHASE READ ----------------------
// `Dashboard.tsx:49-62`:
//
//     useEffect(() => {
//       const el = fillRef.current;
//       if (!el) return;
//       el.style.width = `${pct}%`;
//       const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
//       if (reduce) return;
//       const anim = el.animate([{ width: "0%" }, { width: `${pct}%` }],
//         { duration: 900, easing: "cubic-bezier(.2,.9,.3,1)" });
//       return () => anim.cancel();
//     }, [pct]);
//
// Two things the port must keep, in order:
//
//  1. THE BAR LANDS AT `pct` EITHER WAY. The width is written BEFORE the
//     reduced-motion check, so a child with the setting on sees the correct
//     bar, just not the trip. This is one of the few web animations that IS
//     gated (iOS D29), and it is gated here for that reason and no other.
//     [GrowthSweep] is that ordering as data, so a host test can pin it.
//  2. THE SWEEP IS NOT A RE-RENDER (invariant 2). A width is a LAYOUT
//     property, so the obvious Compose spellings — animating a `Modifier.width`
//     or hoisting a fraction into state a body reads — would remeasure the card
//     sixty times a second. Instead the fill is painted by hand inside a
//     `drawBehind` lambda that reads the `Animatable` there: a DRAW-phase read,
//     zero recompositions and zero relayouts (A12's second half, the same
//     construction `Tile.kt` uses for its highlight ring). Painting it also
//     keeps `cornerRadius = height / 2` at every intermediate width, so the
//     leading cap stays round exactly as `rounded-full` does on the web — which
//     a `scaleX` trick would have squashed into an ellipse.
//
// The sweep is deliberately NOT added to `Anim.kt` / `MotionSurface`, for the
// same reason iOS keeps `GrowthAnim` out of `Anim.swift`: that file is the
// shared INTERACTION vocabulary (press / shake / pop / pulse) and every entry
// in it is transform-or-opacity only. A one-screen geometry animation belongs
// to the one screen that has one, and its gate is spelled out here.
// ===========================================================================

// --- Authored metrics --------------------------------------------------------

/**
 * Every number in this file, read straight off the TSX. Tailwind spacing is
 * n x 4 px and CSS px == Android dp, so the authored values travel unchanged.
 */
object DashboardMetrics {

    /* -- the stage -------------------------------------------------------- */

    /** `min-h-[620px] w-full ... rounded-3xl px-5 pb-10 pt-6`, `gap-5`. */
    val STAGE_PADDING_X: Dp = 20.dp
    val STAGE_PADDING_TOP: Dp = 24.dp
    val STAGE_PADDING_BOTTOM: Dp = 40.dp
    val STAGE_GAP: Dp = 20.dp
    val CORNER_RADIUS: Dp = 24.dp

    /* -- header ----------------------------------------------------------- */

    /** « ← Menu »: `rounded-full bg-white/80 px-4 py-2 text-lg font-bold`. */
    val BACK_PADDING_X: Dp = 16.dp
    val BACK_PADDING_Y: Dp = 8.dp

    /**
     * `<div className="w-[84px]" aria-hidden />` — the right-hand ballast that
     * keeps « Mon copain » optically centred against the back button.
     */
    val HEADER_SPACER: Dp = 84.dp

    /* -- mascot ----------------------------------------------------------- */

    /** `mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)))`. */
    const val MASCOT_MIN = 140f
    const val MASCOT_MAX = 230f
    const val MASCOT_RATIO = 0.46f

    /** The pedestal: `width: clamp(190px,62%,300px)` on an `aspect-square`. */
    const val PEDESTAL_MIN = 190f
    const val PEDESTAL_PERCENT = 62f
    const val PEDESTAL_MAX = 300f

    /**
     * `radial-gradient(circle at 50% 42%, #FFFFFF 0%, rgba(255,255,255,0.4)
     * 55%, rgba(255,255,255,0) 72%)` — the soft plinth under the companion.
     */
    val PEDESTAL_CENTER = Offset(0.5f, 0.42f)
    val PEDESTAL_STOPS: List<Pair<Float, Float>> =
        listOf(0f to 1f, 0.55f to 0.4f, 0.72f to 0f)

    /* -- the balance pill -------------------------------------------------- */

    /** `padding: clamp(8px,2.6vw,15px) clamp(22px,6.5vw,36px)`. */
    val BALANCE_PADDING_Y = FluidSpec(8f, 2.6f, 15f)
    val BALANCE_PADDING_X = FluidSpec(22f, 6.5f, 36f)

    /** `fontSize: clamp(34px,11vw,62px)`, `gap-2`. */
    val BALANCE_FONT_SIZE = FluidSpec(34f, 11f, 62f)
    val BALANCE_GAP: Dp = 8.dp

    /** `boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"`. */
    val BALANCE_LIP_DROP: Dp = 8.dp
    val BALANCE_SOFT_SHADOW = CssShadow(y = 16.dp, blur = 26.dp, opacity = 0.2f)

    /** `-mt-3` on « étoiles à dépenser », pulling it back up into the pill. */
    val CAPTION_TOP_INSET: Dp = 12.dp

    /* -- the growth card --------------------------------------------------- */

    /** `w-full max-w-[420px] rounded-3xl bg-white/70 p-4 shadow`. */
    val CARD_MAX_WIDTH: Dp = 420.dp
    val CARD_PADDING: Dp = 16.dp

    /** `mb-2` under the card's header row. */
    val CARD_HEADER_SPACING: Dp = 8.dp

    /** `h-5` — the bar, track and fill alike. */
    val BAR_HEIGHT: Dp = 20.dp

    /* -- the two doors ----------------------------------------------------- */

    /** `mt-1` above « Boutique 🛍️ »; both doors are `w-full max-w-[420px]`. */
    val SHOP_TOP_INSET: Dp = 4.dp

    /** `px-8` on both, `py-4` on Boutique and `py-3` on Changer de copain. */
    val DOOR_PADDING_X: Dp = 32.dp
    val SHOP_PADDING_Y: Dp = 16.dp
    val SWITCH_PADDING_Y: Dp = 12.dp

    /** `boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"`. */
    val SHOP_LIP_DROP: Dp = 8.dp
    val SHOP_SOFT_SHADOW = CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f)

    /**
     * `boxShadow: "0 5px 0 rgba(0,0,0,0.08)"` — a lip and no blur at all.
     *
     * Spelled as a [CssShadow] rather than as `liftedPill`'s hard lip because
     * the face above it is `bg-white/80`, i.e. TRANSLUCENT. `liftedPill` paints
     * its lip full-size and the face over it, which is right for the opaque
     * gold pill and wrong here: an 8 % black capsule would show THROUGH the
     * white and grey the button from the inside. `cssShadow` clips the border
     * box out of the shadow exactly as the CSS painting rule does, and a blur
     * of 0 makes it the flat lip the class authors.
     */
    val SWITCH_LIP = CssShadow(y = 5.dp, blur = 0.dp, opacity = 0.08f)

    /* -- the sweep ---------------------------------------------------------- */

    /** `{ duration: 900, easing: "cubic-bezier(.2,.9,.3,1)" }`. */
    const val SWEEP_DURATION_MS = 900
    val SWEEP_CURVE = Bezier(0.2f, 0.9f, 0.3f, 1f)

    /**
     * The sweep as a keyframe track: the fill's WIDTH FRACTION of its settled
     * width, 0 -> 1. Expressed as a fraction rather than as a percentage so the
     * animation is independent of the meter — the resting geometry is `pct` of
     * the track and the animation only says how much of that is shown yet.
     */
    val SWEEP = KeyframeSpec(
        values = listOf(0f, 1f),
        keyTimes = listOf(0f, 1f),
        durationMillis = SWEEP_DURATION_MS,
        easing = SWEEP_CURVE,
    )
}

// --- The two derived numbers (pure, host-tested) ------------------------------

/**
 * `mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)))`.
 *
 * [box] is the `ResizeObserver`'s `contentRect.width`, i.e. the stage width
 * MINUS its `px-5` padding — content-box is `ResizeObserver`'s default and the
 * observed element is the padded stage itself.
 *
 * `Math.round` rounds halves toward +infinity and so does [roundToInt] (it is
 * `floor(x + 0.5)`); the clamp floors the value at 140, so only positives ever
 * reach the rounding and the two agree everywhere.
 */
fun dashboardMascotSize(box: Float): Float =
    min(
        DashboardMetrics.MASCOT_MAX,
        max(DashboardMetrics.MASCOT_MIN, box * DashboardMetrics.MASCOT_RATIO),
    ).roundToInt().toFloat()

/** [dashboardMascotSize] in `Dp`, which is what `MascotRigView` wants. */
fun dashboardMascotSize(box: Dp): Dp = dashboardMascotSize(box.value).dp

/**
 * The pedestal's side: `clamp(190px, 62%, 300px)` of the CARD, not of the
 * window. Named so both the screen and its test resolve the same clamp.
 */
fun dashboardPedestalSide(box: Float): Float = fluidPercent(
    min = DashboardMetrics.PEDESTAL_MIN,
    percent = DashboardMetrics.PEDESTAL_PERCENT,
    max = DashboardMetrics.PEDESTAL_MAX,
    container = box,
)

fun dashboardPedestalSide(box: Dp): Dp = dashboardPedestalSide(box.value).dp

/**
 * The growth meter, resolved. `pct = ((stage + 1) / GROWTH_STAGES) * 100`.
 *
 * Every figure a child or a screen reader gets comes from `stage` ALONE — no
 * running total is stored, summed or persisted anywhere (invariant 9). Two
 * meters built from the same stage are equal; there is no history, no
 * accumulator and no clock in this type.
 */
data class GrowthMeter(val stage: Int, val stages: Int = GROWTH_STAGES) {

    /** `aria-valuenow={stage + 1}` — and the numerator of the `n/N` caption. */
    val value: Int get() = stage + 1

    /** `aria-valuemin={1}`. */
    val minimum: Int get() = 1

    /** `aria-valuemax={GROWTH_STAGES}`. */
    val maximum: Int get() = stages

    /** `{stage + 1}/{GROWTH_STAGES}` — the `aria-hidden` counter beside 🌱. */
    val counterText: String get() = "$value/$stages"

    /** `pct`, 0..100. */
    val percent: Double get() = value.toDouble() / stages.toDouble() * 100.0

    /** `width: ${pct}%` resolved against a track of [track] pixels or dp. */
    fun fillWidth(track: Float): Float = max(0f, track * (percent / 100.0).toFloat())

    fun fillWidth(track: Dp): Dp = fillWidth(track.value).dp
}

/**
 * What the mount effect does, as data.
 *
 * The ORDER in the TSX is the behaviour: the final width is written first and
 * unconditionally, and only then is the sweep skipped under reduced motion. A
 * port that gated the whole effect would leave the bar empty for a child who
 * needs the setting — which is why [settled] does not depend on [animates].
 */
data class GrowthSweep(val settled: Double, val from: Double, val animates: Boolean) {

    constructor(percent: Double, reduceMotion: Boolean) :
        this(settled = percent, from = 0.0, animates = !reduceMotion)
}

// --- The sweep, as a draw-phase animation ------------------------------------

/**
 * The growth fill's revealed FRACTION of its settled width, 0..1.
 *
 * Rests at 1: the bar is already at `pct` before anything animates, which is
 * the TSX's `el.style.width = pct%` happening before the reduced-motion check.
 * A gated sweep therefore shows the right bar and simply never moves.
 *
 * Read [fraction] only inside a `drawBehind` lambda — see the header.
 */
@Stable
class GrowthFillMotion(
    private val scope: CoroutineScope,
    private val reduceMotion: ReduceMotionSource,
) {

    val fraction: Animatable<Float, AnimationVector1D> = Animatable(1f)

    private var job: Job? = null

    /** True when the setting is on: the bar is correct, it just does not travel. */
    val gated: Boolean get() = reduceMotion.isReduced

    /**
     * Replay the entrance for [percent]. From 0 every time, not from wherever
     * the bar was: `[{ width: "0%" }, { width: pct% }]` is an entrance, not a
     * delta (the delta is the shop meter's animation, and it is a different one).
     */
    fun sweep(percent: Double) {
        val plan = GrowthSweep(percent, gated)
        if (!plan.animates) return
        job?.cancel()
        job = scope.launch {
            fraction.snapTo(DashboardMetrics.SWEEP.start)
            fraction.animateTo(
                DashboardMetrics.SWEEP.target,
                DashboardMetrics.SWEEP.toAnimationSpec(),
            )
        }
    }
}

/**
 * A [GrowthFillMotion] that replays whenever `pct` changes — the TSX effect's
 * `[pct]` dependency, which is also why a container resize repaints the bar
 * without replaying the animation.
 */
@Composable
fun rememberGrowthFill(
    reduceMotion: ReduceMotionSource,
    percent: Double,
): GrowthFillMotion {
    val scope = rememberCoroutineScope()
    val motion = remember(scope, reduceMotion) { GrowthFillMotion(scope, reduceMotion) }
    LaunchedEffect(motion, percent) { motion.sweep(percent) }
    return motion
}

// --- The screen ---------------------------------------------------------------

/**
 * « Mon copain ».
 *
 * @param profiles the roster. READ ONLY on this screen — see the invariant 9
 *   note in the header; not one mutating method of the store is called below.
 * @param audio accepted because the root hands every screen the one audio
 *   engine, and deliberately unused: `Dashboard.tsx` plays nothing. A voice
 *   line here would talk over the hub's « Écouter mes points », which is the
 *   button that already reads the balance aloud.
 * @param reduceMotion gates the mascot's idle motion (owned by :art) and the
 *   growth sweep, and nothing else on this screen.
 */
@Composable
fun DashboardView(
    profiles: ProfileStore,
    audio: AudioEngine,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onShop: () -> Unit,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Subscribes the screen to every roster write, so a purchase made in the
    // shop is reflected the moment this screen comes back.
    val roster by profiles.rosterFlow.collectAsState()

    // `const { config, balance } = profile` — a READ of the folded counters,
    // recomputed on every roster change. Nothing is cached and nothing written.
    val profile = remember(roster) { profiles.profile }
    val balance = profile.balance
    val meter = GrowthMeter(profile.config.stage)

    // The `ResizeObserver`. Pure layout, and it cannot feed itself: the mascot
    // sits inside a percentage-clamped pedestal, so its size never drives the
    // column's width.
    var box by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val viewport = LocalViewportWidth.current
    val fontScale = density.fontScale

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Shell.minimumScreenHeight)
            // `rounded-3xl` then `background: STAGE` inside the clip. The play
            // surfaces' wash stops the cream at 38 %; the adult ones at 40 %.
            .clip(RoundedCornerShape(DashboardMetrics.CORNER_RADIUS))
            .drawBehind { drawRect(Palette.stage.brush(size)) }
            .padding(
                start = DashboardMetrics.STAGE_PADDING_X,
                end = DashboardMetrics.STAGE_PADDING_X,
                top = DashboardMetrics.STAGE_PADDING_TOP,
                bottom = DashboardMetrics.STAGE_PADDING_BOTTOM,
            )
            // AFTER the padding, so this is the CONTENT box — which is what
            // `ResizeObserver` reports by default and what `mascotSize` divides.
            .onSizeChanged { measured ->
                val width = with(density) { measured.width.toDp() }
                if (width > 0.dp) box = width
            },
        verticalArrangement = Arrangement.spacedBy(DashboardMetrics.STAGE_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DashboardHeader(onBack = onBack, fontScale = fontScale)

        Pedestal(config = profile.config, box = box, reduceMotion = reduceMotion)

        BalancePill(balance = balance, viewport = viewport, fontScale = fontScale, reduceMotion = reduceMotion)

        BasicText(
            text = Copy.Dashboard.BALANCE_CAPTION,
            // `-mt-3`. Compose padding cannot be negative, so the margin is
            // reproduced as a layout: the caption moves up 12 dp AND reports a
            // 12 dp shorter height, which is exactly what a negative CSS margin
            // does to the flow below it.
            modifier = Modifier.negativeTopMargin(DashboardMetrics.CAPTION_TOP_INSET),
            style = Typography.style(
                size = Typography.Size.base,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )

        GrowthCard(meter = meter, reduceMotion = reduceMotion, fontScale = fontScale)

        ShopDoor(onShop = onShop, fontScale = fontScale)

        SwitchDoor(onSwitch = onSwitch, fontScale = fontScale)
    }
}

// --- header -------------------------------------------------------------------

@Composable
private fun DashboardHeader(onBack: () -> Unit, fontScale: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                // Invariant 6: the label a screen reader reads is « Retour au
                // menu », not the arrow. TalkBack's double-tap reaches the same
                // handler through the semantics action; the touch path is
                // `touchDown` and nothing else (A12).
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = Copy.Dashboard.BACK_TO_MENU
                    onClick {
                        onBack()
                        true
                    }
                }
                .cssShadow(Shadows.tailwind, CircleShape)
                .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
                // Acts on the LIFT, like `GameFrame`'s twin and like the TSX's
                // `onClick`: leaving a screen by accident costs a child their
                // place, which is the one case where waiting is kinder.
                .touchDown(onUp = { inside -> if (inside) onBack() }) {
                    // No feedback at down: the web animates tiles and nothing else.
                }
                .padding(
                    horizontal = DashboardMetrics.BACK_PADDING_X,
                    vertical = DashboardMetrics.BACK_PADDING_Y,
                ),
        ) {
            BasicText(
                text = Copy.Dashboard.BACK_TO_MENU_LABEL,
                style = Typography.style(
                    size = Typography.Size.lg,
                    weight = Typography.Weight.bold,
                    color = Palette.ink.color,
                    fontScale = fontScale,
                ),
            )
        }

        BasicText(
            text = Copy.Dashboard.HEADING,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.black,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )

        // `<div className="w-[84px]" aria-hidden />`.
        Spacer(modifier = Modifier.width(DashboardMetrics.HEADER_SPACER))
    }
}

// --- mascot -------------------------------------------------------------------

@Composable
private fun Pedestal(config: MascotConfig, box: Dp, reduceMotion: ReduceMotionSource) {
    val side = dashboardPedestalSide(box)
    Box(
        modifier = Modifier
            .size(side)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colorStops = DashboardMetrics.PEDESTAL_STOPS
                            .map { (location, alpha) -> location to Color.White.copy(alpha = alpha) }
                            .toTypedArray(),
                        center = Offset(
                            size.width * DashboardMetrics.PEDESTAL_CENTER.x,
                            size.height * DashboardMetrics.PEDESTAL_CENTER.y,
                        ),
                        radius = size.width / 2f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        MascotRigView(
            config = config,
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = dashboardMascotSize(box),
        )
    }
}

// --- balance ------------------------------------------------------------------

@Composable
private fun BalancePill(
    balance: Int,
    viewport: Dp,
    fontScale: Float,
    reduceMotion: ReduceMotionSource,
) {
    // `usePopFlourish` fires on every dashboard open, because the view is
    // created afresh each time the route is taken. Keyed on the value so a
    // balance that changed while the shop was open celebrates again.
    val pop: PopMotion = rememberPopFlourish(reduceMotion, key = balance)
    val style = Typography.style(
        size = DashboardMetrics.BALANCE_FONT_SIZE.resolve(viewport),
        weight = Typography.Weight.black,
        color = Palette.goldInk.color,
        fontScale = fontScale,
    )

    Row(
        modifier = Modifier
            .popFlourish(pop)
            .liftedPill(
                fill = { size -> Palette.goldPill.brush(size) },
                lip = Palette.goldLip.color,
                drop = DashboardMetrics.BALANCE_LIP_DROP,
                soft = DashboardMetrics.BALANCE_SOFT_SHADOW,
            )
            .padding(
                horizontal = DashboardMetrics.BALANCE_PADDING_X.resolve(viewport),
                vertical = DashboardMetrics.BALANCE_PADDING_Y.resolve(viewport),
            )
            // The whole pill is ONE label, as the `aria-label` makes it: the
            // star is `aria-hidden` and « ⭐ 35 » read out piecemeal is noise.
            .clearAndSetSemantics { contentDescription = Copy.Dashboard.balance(balance) },
        horizontalArrangement = Arrangement.spacedBy(DashboardMetrics.BALANCE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = Copy.Dashboard.BALANCE_GLYPH, style = style)
        // INVARIANT 8/9: the number is printed, never computed. `balance` is
        // whatever `balanceOf(stars)` returned.
        BasicText(text = balance.toString(), style = style)
    }
}

// --- growth --------------------------------------------------------------------

@Composable
private fun GrowthCard(
    meter: GrowthMeter,
    reduceMotion: ReduceMotionSource,
    fontScale: Float,
) {
    Column(
        modifier = Modifier
            .widthIn(max = DashboardMetrics.CARD_MAX_WIDTH)
            .fillMaxWidth()
            .cssShadow(Shadows.tailwind, RoundedCornerShape(DashboardMetrics.CORNER_RADIUS))
            .background(
                Color.White.copy(alpha = Palette.White.o70),
                RoundedCornerShape(DashboardMetrics.CORNER_RADIUS),
            )
            .padding(DashboardMetrics.CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(DashboardMetrics.CARD_HEADER_SPACING),
    ) {
        val headerStyle = Typography.style(
            size = Typography.Size.lg,
            weight = Typography.Weight.black,
            color = Palette.ink.color,
            fontScale = fontScale,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text = Copy.Dashboard.GROWTH, style = headerStyle)
            BasicText(
                text = meter.counterText,
                // `aria-hidden` — the bar's own range info already says 4 of 10.
                modifier = Modifier.clearAndSetSemantics {},
                style = headerStyle,
            )
        }

        GrowthBar(meter = meter, reduceMotion = reduceMotion)
    }
}

/**
 * The `role="progressbar"` row: an `overflow-hidden rounded-full` track in
 * `#E9DCC7` with the gradient fill painted over it, leading-aligned.
 *
 * Both are drawn in ONE `drawBehind`, in DOM order (track first, fill on top),
 * so "what covers what" is a single expression rather than a stack whose order
 * no host assertion can see.
 */
@Composable
private fun GrowthBar(meter: GrowthMeter, reduceMotion: ReduceMotionSource) {
    val motion = rememberGrowthFill(reduceMotion, meter.percent)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DashboardMetrics.BAR_HEIGHT)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = Palette.growthTrack.color,
                    size = size,
                    cornerRadius = radius,
                )
                // INVARIANT 2: the ONLY read of the animated value, and it is
                // here, inside the draw lambda. Hoisting it into the composable
                // body would remeasure the card every frame.
                val settled = meter.fillWidth(size.width)
                val shown = settled * motion.fraction.value
                if (shown <= 0f) return@drawBehind
                val fill = Size(shown, size.height)
                drawRoundRect(
                    brush = Palette.growthFill.brush(fill),
                    size = fill,
                    cornerRadius = radius,
                )
            }
            // `aria-valuemin={1} aria-valuemax={GROWTH_STAGES}
            //  aria-valuenow={stage + 1} aria-label="Croissance de ton copain"`.
            .clearAndSetSemantics {
                contentDescription = Copy.Dashboard.GROWTH_BAR
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = meter.value.toFloat(),
                    range = meter.minimum.toFloat()..meter.maximum.toFloat(),
                )
            },
    )
}

// --- the two doors --------------------------------------------------------------

@Composable
private fun ShopDoor(onShop: () -> Unit, fontScale: Float) {
    Box(
        modifier = Modifier
            .padding(top = DashboardMetrics.SHOP_TOP_INSET)
            .widthIn(max = DashboardMetrics.CARD_MAX_WIDTH)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    onShop()
                    true
                }
            }
            .liftedPill(
                fill = { SolidColor(Palette.green.color) },
                lip = Palette.greenLip.color,
                drop = DashboardMetrics.SHOP_LIP_DROP,
                soft = DashboardMetrics.SHOP_SOFT_SHADOW,
            )
            .touchDown(onUp = { inside -> if (inside) onShop() }) {}
            .padding(
                horizontal = DashboardMetrics.DOOR_PADDING_X,
                vertical = DashboardMetrics.SHOP_PADDING_Y,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = Copy.Dashboard.SHOP_DOOR,
            style = Typography.style(
                size = Typography.Size.xxl,
                weight = Typography.Weight.extrabold,
                color = Color.White,
                fontScale = fontScale,
            ),
        )
    }
}

/** Non-destructive: each friend keeps its own progress, so this never warns. */
@Composable
private fun SwitchDoor(onSwitch: () -> Unit, fontScale: Float) {
    Box(
        modifier = Modifier
            .widthIn(max = DashboardMetrics.CARD_MAX_WIDTH)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    onSwitch()
                    true
                }
            }
            .cssShadow(DashboardMetrics.SWITCH_LIP, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o80), CircleShape)
            .touchDown(onUp = { inside -> if (inside) onSwitch() }) {}
            .padding(
                horizontal = DashboardMetrics.DOOR_PADDING_X,
                vertical = DashboardMetrics.SWITCH_PADDING_Y,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = Copy.Dashboard.SWITCH_COMPANION,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        )
    }
}

// --- CSS's negative margin ------------------------------------------------------

/**
 * Tailwind's `-mt-3`: shift this element up by [amount] AND report a height
 * shorter by the same, so everything after it follows.
 *
 * `Modifier.padding` rejects a negative value and `Modifier.offset` would move
 * the caption without closing the gap under it, which on this screen would push
 * the growth card 12 dp down. A one-line `layout` is the honest translation.
 */
private fun Modifier.negativeTopMargin(amount: Dp): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = amount.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
        placeable.place(0, -shift)
    }
}
