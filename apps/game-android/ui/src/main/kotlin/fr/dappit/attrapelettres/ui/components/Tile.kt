package fr.dappit.attrapelettres.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.platform.TimeSource
import fr.dappit.attrapelettres.core.rewards.MISS_COOLDOWN_MS
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.HexColor
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.opacity
import fr.dappit.attrapelettres.ui.design.overdraw
import fr.dappit.attrapelettres.ui.interaction.TileMotion
import fr.dappit.attrapelettres.ui.interaction.rememberTileMotion
import fr.dappit.attrapelettres.ui.interaction.tileMotion
import fr.dappit.attrapelettres.ui.interaction.touchDown

// Port of `apps/game-web/src/components/Tile.tsx` — the pick primitive every
// exercise builds on, and the single most-touched surface in the game. The twin
// of iOS's `Sources/ALUI/Components/Tile.swift`.
//
// Three invariants land in this file.
//
// INVARIANT 1 — feedback fires at pointer-DOWN, before Compose commits.
//   The pipeline is [TileInteraction.pointerDown], invoked synchronously from
//   `Modifier.touchDown`'s `onDown` (ui/interaction/TouchDown.kt): press
//   animation, then `onPick()` — whose SFX the engine fires inside that same
//   call — then the shake when the verdict is `Verdict.REJECT`. Nothing on that
//   path goes through `mutableStateOf`, a `LaunchedEffect` or a dispatched
//   coroutine. `Modifier.clickable` is never used: it fires on UP, behind
//   gesture arbitration and behind Material's ripple.
//
// INVARIANT 3 — NO FAIL STATE.
//   A `REJECT` shakes and does nothing else. [TileVisual] has four members and
//   none of them is "locked", "disabled", "wrong" or "failed": the state a
//   rejected tile lands in is REJECTED, whose only difference from IDLE is that
//   a 300 ms wobble is in flight, and from which the very next touch is
//   accepted. `disabled` is a PARAMETER the exercise passes (an already-used
//   tray tile, the celebration lock while the success line plays) — it is never
//   set by this file and never by a wrong answer. `TileStateMachineTest` proves
//   both halves: every state is immediately pressable again, and the enum
//   carries no failure member.
//
// INVARIANT 6 — the accessibility floor.
//   `contentDescription` is a REQUIRED parameter with no default (the TSX prop
//   is optional; every engine passes one, so making it structural costs nothing
//   and cannot be forgotten), and the default tile side floors at 92 dp —
//   `clamp(92px, 27vw, 150px)` — at every viewport. Reduced motion is NOT
//   consulted here: the web gates the mascot and the confetti, never a tap
//   (iOS D29, and the table in `ui/interaction/Anim.kt`). A child who needs the
//   setting still gets the 130 ms squish and the 300 ms wobble, because those
//   are tactile feedback, not decoration.
//
// The optional « Écouter » affordance is a SEPARATE sibling button stacked
// below the tile with a real 8 dp gap (`gap-2` on the TSX column), so hearing a
// tile can never commit its pick. It speaks at pointer-down, the same beat as a
// pick, and it has no verdict — so it can never shake.

// --- Authored metrics --------------------------------------------------------
//
// Every number below is an inline style in `Tile.tsx`, ported as data so a host
// test asserts the TypeScript rather than the Kotlin.

/**
 * One CSS `box-shadow` layer: `0 {offsetY} {blur} rgba(0,0,0,{alpha})`.
 *
 * Kept as the authored CSS triple rather than as an Android elevation because
 * the two are not the same model and the conversion is lossy — see
 * [softElevation].
 */
data class BoxShadow(val offsetY: Dp, val blur: Dp, val alpha: Float) {

    /** The shadow colour Compose wants: black at the authored alpha. */
    val color: Color get() = Color.Black.copy(alpha = alpha)

    /**
     * The Android elevation that stands in for this shadow.
     *
     * CSS gives a shadow an offset, a blur and a colour. Android gives a view a
     * HEIGHT and derives the shadow from two lights (ambient + spot), so there
     * is no exact conversion, and there is no spread/offset control at all. The
     * y-offset is the perceptually dominant term of the two (a 12 px drop reads
     * as "floating" whatever the blur is), so elevation tracks it. Verified by
     * eye on a device, not by arithmetic — flagged as such in the port notes.
     *
     * A blur-less layer (the `0 8px 0` lip) is NOT an elevation at all: it is a
     * hard-edged rounded rect drawn 8 dp lower, which this file paints itself.
     */
    val softElevation: Dp get() = offsetY
}

object TileMetrics {

    /**
     * `clamp(92px, 27vw, 150px)` — `dim`, the DEFAULT tile side.
     *
     * 92 dp is the floor for a tile that uses the default, and invariant 6's
     * number. It is NOT a hard app-wide minimum, and three engines prove it:
     * the syllable-grid drill authors `clamp(62px, 17vw, 96px)`, the twins
     * `clamp(60px, 17vw, 92px)` and the assembly tray `clamp(64px, 18vw, 100px)`
     * — deliberate values for grids that must fit a consonant-by-vowel table on
     * a phone. Behaviour is frozen, so they are ported as written. Where an
     * engine overrides the size, check the override against
     * [PLATFORM_MINIMUM_TAP_TARGET], not against this number.
     */
    val DEFAULT_SIZE = FluidSpec(min = 92f, vw = 27f, max = 150f)

    /** `fontSize: clamp(30px, 9vw, 64px)` — the default glyph size. */
    val DEFAULT_FONT_SIZE = FluidSpec(min = 30f, vw = 9f, max = 64f)

    /** `padding: 0 clamp(10px, 3vw, 20px)`. */
    val HORIZONTAL_PADDING = FluidSpec(min = 10f, vw = 3f, max = 20f)

    /** `borderRadius: 28`. */
    val CORNER_RADIUS: Dp = 28.dp

    /** `disabled:opacity-40` — on the tile AND on its Écouter button. */
    const val DISABLED_OPACITY = 0.4f

    /**
     * The highlight ring, `0 0 0 6px #66BB6A`: a 6 dp SPREAD, i.e. a band drawn
     * OUTSIDE the border box, whose radius therefore grows to 28 + 6 = 34.
     */
    val HIGHLIGHT_RING_WIDTH: Dp = 6.dp
    val HIGHLIGHT_RING_RADIUS: Dp = CORNER_RADIUS + HIGHLIGHT_RING_WIDTH

    /**
     * The margin a tile's graphics layers are grown by so the ring and the lip
     * have somewhere to land — see `design/Overdraw.kt`. It is invisible to
     * layout, and it must cover the widest thing that paints outside the border
     * box: the ring's 6 dp spread and the lip's 8 dp drop.
     */
    val OVERDRAW: Dp = 8.dp

    /** The same headroom for the « Écouter » pill, whose lip drops 3 dp. */
    val PREVIEW_OVERDRAW: Dp = 3.dp

    /** `transition: box-shadow 0.15s`. */
    const val HIGHLIGHT_TRANSITION_MS = 150

    /** The tile column when an Écouter button exists: `gap-2` = 8 dp. */
    val COLUMN_GAP: Dp = 8.dp

    /** Écouter: `height: clamp(40px, 11vw, 52px)`. */
    val PREVIEW_HEIGHT = FluidSpec(min = 40f, vw = 11f, max = 52f)

    /** Écouter: `fontSize: clamp(16px, 4.5vw, 22px)`. */
    val PREVIEW_FONT_SIZE = FluidSpec(min = 16f, vw = 4.5f, max = 22f)

    // The resting tile: `0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)`.
    // The first layer has ZERO blur — it is the hard "lip" that makes the tile
    // read as a physical key a finger can push down, and it is the reason this
    // file paints its own shadow layer instead of handing everything to
    // `Modifier.shadow`.
    val RESTING_LIP = BoxShadow(offsetY = 8.dp, blur = 0.dp, alpha = 0.12f)
    val RESTING_CAST = BoxShadow(offsetY = 12.dp, blur = 20.dp, alpha = 0.14f)

    /**
     * The highlighted tile: `0 0 0 6px #66BB6A, 0 10px 22px rgba(0,0,0,0.18)`.
     * Note what is MISSING — the lip. A highlighted tile is a tile that has just
     * been answered correctly; it stops looking pressable.
     */
    val HIGHLIGHT_CAST = BoxShadow(offsetY = 10.dp, blur = 22.dp, alpha = 0.18f)

    // Écouter: `0 3px 0 rgba(0,0,0,0.10), 0 5px 12px rgba(0,0,0,0.12)`.
    val PREVIEW_LIP = BoxShadow(offsetY = 3.dp, blur = 0.dp, alpha = 0.10f)
    val PREVIEW_CAST = BoxShadow(offsetY = 5.dp, blur = 12.dp, alpha = 0.12f)

    /**
     * The platform floor an engine's size override has to clear. Apple's 44 pt,
     * kept rather than Material's 48 dp because the authored sizes are shared
     * with the iOS app and both apps must render the same grid; every override
     * in the app clears 44 at every viewport from 320 to 1024.
     */
    val PLATFORM_MINIMUM_TAP_TARGET: Dp = 44.dp
}

// --- The visual state machine ------------------------------------------------

/**
 * What a tile is doing right now.
 *
 * Four states, and the absence of a fifth is invariant 3 made structural: there
 * is no LOCKED, no DISABLED, no WRONG and no FAILED. A tile that has just been
 * rejected is in [REJECTED], which differs from [IDLE] only in that a wobble is
 * in flight, and which accepts the very next touch.
 *
 * `disabled` is deliberately NOT a member: it is an input the exercise supplies
 * (a tray tile already used, the celebration lock while the success line plays),
 * never a state this file enters, and modelling it here would be the first step
 * towards a tile that disables itself on a wrong answer.
 */
enum class TileVisual {
    /** At rest. Nothing is animating. */
    IDLE,

    /** The finger is down and the 130 ms squish is running. */
    PRESSED,

    /** The pick was right. The exercise is playing its success line. */
    ACCEPTED,

    /** The pick was wrong: a 300 ms wobble, a soft nudge, and nothing else. */
    REJECTED,
}

/**
 * The transitions, as pure functions — no Compose, no coroutine, no clock, so
 * `./gradlew :ui:testDebugUnitTest` drives the whole machine on the host.
 */
object TileStateMachine {

    /**
     * The finger landed. A disabled tile does not move at all (`Tile.tsx`:
     * `if (disabled) return`, BEFORE the animation).
     */
    fun onPointerDown(current: TileVisual, disabled: Boolean): TileVisual =
        if (disabled) current else TileVisual.PRESSED

    /**
     * The verdict the exercise returned, in the same synchronous beat as the
     * touch. Neither branch is terminal.
     */
    fun onVerdict(current: TileVisual, verdict: Verdict): TileVisual = when (verdict) {
        Verdict.ACCEPT -> TileVisual.ACCEPTED
        Verdict.REJECT -> TileVisual.REJECTED
    }

    /**
     * An animation reached its end (or was replaced). [finished] says which one.
     *
     * A shake that is still running keeps the tile in [TileVisual.REJECTED];
     * everything else comes to rest. This is what stops the press's completion —
     * which WAAPI, and therefore [TileMotion], also fires when the press is
     * REPLACED by a shake — from clearing a wobble that has only just started.
     */
    fun onSettled(current: TileVisual, finished: TileVisual): TileVisual =
        if (current == TileVisual.REJECTED && finished != TileVisual.REJECTED) {
            current
        } else {
            TileVisual.IDLE
        }
}

/**
 * The two animations a tile can play, behind an interface.
 *
 * [TileMotion] drives real `Animatable`s, which need a frame clock and so cannot
 * run in a host test. The pipeline's ORDERING is the part invariant 1 is about,
 * and this seam is what lets a plain JUnit test assert it: a recording
 * implementation notes when `press` was called relative to `onPick`.
 */
interface TileFeedback {

    /** The 130 ms squish, at pointer-down. */
    fun press(onFinished: () -> Unit = {})

    /** The 300 ms wobble on a rejected pick. Never anything more (invariant 3). */
    fun shake(onFinished: () -> Unit = {})
}

/** Adapts the real [TileMotion] to [TileFeedback]. */
fun tileFeedback(motion: TileMotion): TileFeedback = object : TileFeedback {
    override fun press(onFinished: () -> Unit) = motion.press(onFinished)
    override fun shake(onFinished: () -> Unit) = motion.shake(onFinished)
}

/**
 * The `onPointerDown` pipeline of `Tile.tsx`, and the home of invariant 1.
 *
 * ```
 * if (disabled) return;
 * el?.animate(press, { duration: 130, easing: "ease-out" });
 * if (onPick() === "reject") el?.animate(shake, { duration: 300, easing: "ease-in-out" });
 * ```
 *
 * [visual] is an ordinary `var`, NOT `mutableStateOf`, and that is load-bearing:
 * snapshot state here would recompose the tile on every touch, which is exactly
 * what invariant 2 forbids and what the animation channels in
 * `ui/interaction/Anim.kt` exist to avoid. The composition never reads it; it is
 * the machine's trace, for the tests and for a future debug overlay.
 *
 * Deliberately NOT annotated `@Stable`: that annotation is a promise that the
 * composition is notified when a public property changes, and [visual] promises
 * the opposite.
 */
class TileInteraction(private val feedback: TileFeedback) {

    var visual: TileVisual = TileVisual.IDLE
        private set

    /**
     * A pick. [onPick] is called synchronously, between the press animation
     * starting and the shake starting — the exact order of the TSX handler, and
     * the order `TileInteractionTest` pins.
     */
    fun pointerDown(disabled: Boolean, onPick: () -> Verdict) {
        if (disabled) return
        visual = TileStateMachine.onPointerDown(visual, disabled = false)
        feedback.press { settle(TileVisual.PRESSED) }
        val verdict = onPick()
        visual = TileStateMachine.onVerdict(visual, verdict)
        if (verdict == Verdict.REJECT) {
            feedback.shake { settle(TileVisual.REJECTED) }
        }
    }

    /**
     * The Écouter sibling: press on its OWN channel, then speak. There is no
     * verdict to return, so a preview can never shake and can never reject.
     */
    fun previewDown(disabled: Boolean, onPreview: () -> Unit) {
        if (disabled) return
        visual = TileStateMachine.onPointerDown(visual, disabled = false)
        feedback.press { settle(TileVisual.PRESSED) }
        onPreview()
    }

    private fun settle(finished: TileVisual) {
        visual = TileStateMachine.onSettled(visual, finished)
    }
}

/** One [TileInteraction] per interactive surface, tied to its motion channels. */
@Composable
fun rememberTileInteraction(motion: TileMotion): TileInteraction =
    remember(motion) { TileInteraction(tileFeedback(motion)) }

// --- The swallow window (invariant 8) ----------------------------------------

/**
 * The `coolUntil` ref every single-pick engine carries
 * (`FindSoundExercise.tsx` and its siblings):
 *
 * ```
 * const coolUntil = useRef(0);
 * if (performance.now() < coolUntil.current) return "reject";  // silent
 * // …on a miss:
 * coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
 * ```
 *
 * After a miss, picks are SWALLOWED for `MISS_COOLDOWN_MS` (800 ms): the verdict
 * is `REJECT` with zero audio and zero state change, while the press and the
 * shake still play. The swallow is silent, not invisible — a six-year-old
 * spamming tiles still sees the tile answer them, they just cannot grind points
 * that way (invariant 8). It is a swallow WINDOW, never a lock: invariant 3
 * survives it because nothing is disabled and nothing is lost.
 *
 * The comparison is strict `<`, so the tap at exactly +800 ms lands.
 *
 * Time is injected so the window is host-testable without sleeping. Assembly
 * engines have NO cooldown by design — their pacing is the awaited
 * « Oh non ! On recommence. » line; do not hand them one for symmetry.
 */
class MissCooldown(private val time: TimeSource) {

    private var coolUntilMillis: Long = 0

    /** `performance.now() < coolUntil.current` — true while picks are swallowed. */
    val isSwallowing: Boolean get() = time.nowMillis < coolUntilMillis

    /** Call in the same synchronous beat as the miss itself. */
    fun registerMiss() {
        coolUntilMillis = time.nowMillis + MISS_COOLDOWN_MS
    }
}

// --- Type -------------------------------------------------------------------

/**
 * The tile glyph's style: the TSX `font-black`, the data-driven ink, and the
 * resolved `fontSize` clamp.
 *
 * Pure, so a host test can read the resolved size back. [fontScale] is divided
 * out inside `Typography.style` — a glyph sized from the same clamp as its tile
 * has to stay inside a tile whose side is fixed dp (see `Typography.fixedSp`).
 */
fun tileGlyphStyle(
    fontSize: FluidSpec,
    ink: Color,
    viewport: Dp,
    fontScale: Float,
    family: FontFamily = Typography.appFamily,
): TextStyle = Typography.style(
    size = fontSize.resolve(viewport),
    weight = Typography.Weight.black,
    color = ink,
    family = family,
    fontScale = fontScale,
)

/**
 * A tile's text content, styled from the ambient tile style.
 *
 * Convenience only: `Tile(...) { TileGlyph("A") }` is the shape eight of the
 * nine engines want. Any other content — a word image, a composed row — goes in
 * the slot directly and inherits the same `LocalTextStyle`.
 */
@Composable
fun TileGlyph(text: String, modifier: Modifier = Modifier) {
    BasicText(text = text, modifier = modifier, style = LocalTextStyle.current, maxLines = 1)
}

// --- The Tile ---------------------------------------------------------------

/**
 * The pick primitive.
 *
 * @param bg the tile face. Data-driven, exactly the TSX `bg` string.
 * @param ink the glyph colour, the TSX `ink`.
 * @param contentDescription TSX `ariaLabel`. REQUIRED — invariant 6 made
 *   structural. Build it from `Copy.Exercise.letterTile` / `syllableTile` /
 *   `soundTile` / `imageTile`, or from `faceLabel(face)`; never leave it blank.
 * @param onPick runs SYNCHRONOUSLY at pointer-down, inside the pointer dispatch.
 *   Play the SFX in it. Return [Verdict.REJECT] to shake — and nothing else
 *   happens, ever (invariant 3).
 * @param disabled the exercise's own lock: a tray tile already used, or the
 *   celebration window while a success line plays. NEVER set because an answer
 *   was wrong.
 * @param highlight the green ring on the tile that was just answered correctly.
 * @param size / fontSize the authored clamps; override them for a grid that has
 *   to fit (see [TileMetrics.DEFAULT_SIZE]).
 * @param onPreview the « hear it first » affordance. When non-null a separate
 *   full-width Écouter button is stacked BELOW the tile, gap-separated, with its
 *   own finger-sized target — so auditioning a tile can never commit its pick.
 */
@Composable
fun Tile(
    bg: HexColor,
    ink: HexColor,
    contentDescription: String,
    onPick: () -> Verdict,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    highlight: Boolean = false,
    size: FluidSpec = TileMetrics.DEFAULT_SIZE,
    fontSize: FluidSpec = TileMetrics.DEFAULT_FONT_SIZE,
    glyphFamily: FontFamily = Typography.appFamily,
    onPreview: (() -> Unit)? = null,
    previewLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val viewport = LocalViewportWidth.current
    val side = size.resolve(viewport)

    if (onPreview == null) {
        PickSurface(
            bg = bg,
            ink = ink,
            contentDescription = contentDescription,
            onPick = onPick,
            modifier = modifier,
            disabled = disabled,
            highlight = highlight,
            side = side,
            fontSize = fontSize,
            glyphFamily = glyphFamily,
            content = content,
        )
        return
    }

    // Column: the big pick tile on top, its own Écouter button below with a real
    // gap. Both are finger-sized, single-purpose targets that never overlap
    // (`flex flex-col items-stretch gap-2`).
    //
    // `IntrinsicSize.Min` is the Compose spelling of `items-stretch`: the column
    // takes the widest child's minimum intrinsic width — the tile's, since it
    // floors at `side` — and `fillMaxWidth()` then stretches the button to it.
    // iOS had to measure the tile with a PreferenceKey and feed the width back;
    // intrinsics do it in one pass with no state and no feedback loop.
    Column(
        modifier = modifier.width(IntrinsicSize.Min),
        verticalArrangement = Arrangement.spacedBy(TileMetrics.COLUMN_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PickSurface(
            bg = bg,
            ink = ink,
            contentDescription = contentDescription,
            onPick = onPick,
            modifier = Modifier,
            disabled = disabled,
            highlight = highlight,
            side = side,
            fontSize = fontSize,
            glyphFamily = glyphFamily,
            content = content,
        )
        ListenSurface(
            contentDescription = previewLabel ?: Copy.Tile.LISTEN_FALLBACK,
            onPreview = onPreview,
            disabled = disabled,
            viewport = viewport,
        )
    }
}

/** The palette convenience — `TILE_COLORS[i % TILE_COLORS.length]`. */
@Composable
fun Tile(
    paint: TilePaint,
    contentDescription: String,
    onPick: () -> Verdict,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    highlight: Boolean = false,
    size: FluidSpec = TileMetrics.DEFAULT_SIZE,
    fontSize: FluidSpec = TileMetrics.DEFAULT_FONT_SIZE,
    glyphFamily: FontFamily = Typography.appFamily,
    onPreview: (() -> Unit)? = null,
    previewLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Tile(
        bg = paint.bg,
        ink = paint.ink,
        contentDescription = contentDescription,
        onPick = onPick,
        modifier = modifier,
        disabled = disabled,
        highlight = highlight,
        size = size,
        fontSize = fontSize,
        glyphFamily = glyphFamily,
        onPreview = onPreview,
        previewLabel = previewLabel,
        content = content,
    )
}

@Composable
private fun PickSurface(
    bg: HexColor,
    ink: HexColor,
    contentDescription: String,
    onPick: () -> Verdict,
    modifier: Modifier,
    disabled: Boolean,
    highlight: Boolean,
    side: Dp,
    fontSize: FluidSpec,
    glyphFamily: FontFamily,
    content: @Composable () -> Unit,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    val interaction = rememberTileInteraction(motion)
    val shape = RoundedCornerShape(TileMetrics.CORNER_RADIUS)

    // `transition: box-shadow 0.15s`. An `Animatable` read INSIDE `drawBehind`
    // is a deferred DRAW read: the ring fades without recomposing the tile
    // (invariant 2). `animateFloatAsState` here would re-run this composable
    // once per frame for 150 ms, on the frame right after a child answered.
    val ring = remember { Animatable(if (highlight) 1f else 0f) }
    LaunchedEffect(highlight) {
        ring.animateTo(
            targetValue = if (highlight) 1f else 0f,
            animationSpec = tween(durationMillis = TileMetrics.HIGHLIGHT_TRANSITION_MS),
        )
    }

    val cast = if (highlight) TileMetrics.HIGHLIGHT_CAST else TileMetrics.RESTING_CAST
    val face = bg.color
    val ringColor = Palette.green.color
    val lip = TileMetrics.RESTING_LIP
    // Aliased before the semantics lambda: inside it the implicit receiver
    // carries an extension property of the SAME name, and a bare
    // `contentDescription = contentDescription` would resolve to a
    // self-assignment rather than to this composable's parameter.
    val label = contentDescription

    // The pick, in ONE place, so the pointer path and the TalkBack path cannot
    // drift apart.
    val pick: () -> Unit = { interaction.pointerDown(disabled = disabled, onPick = onPick) }

    Box(
        modifier = modifier
            // touchDown FIRST: a pointer-input node measures the size of what
            // stands to its right, so putting it ahead of the padding and the
            // size modifiers makes the whole tile the target rather than the
            // area inside its padding. INVARIANT 1 IS THIS LINE.
            .touchDown(enabled = !disabled) { pick() }
            // `.accessibilityElement(children: .ignore)` on iOS: the tile
            // announces its authored label, not the glyph inside it. The
            // `onClick` action is what makes a TalkBack double-tap reach the
            // same pipeline — without it the node is not activatable, since this
            // file (rightly) has no `clickable` to supply one.
            .clearAndSetSemantics {
                this.contentDescription = label
                this.role = Role.Button
                onClick {
                    pick()
                    true
                }
            }
            // HEADROOM, and it must come before the layers. Every graphics
            // layer below records at its node's size, so without this the ring
            // and the lip — both of which paint OUTSIDE the border box, the way
            // a CSS `box-shadow` does — are cropped away. See
            // `design/Overdraw.kt`. It sits AFTER `touchDown` so the hit rect
            // stays the tile and not the margin.
            .overdraw(TileMetrics.OVERDRAW)
            .tileMotion(motion)
            // `opacity`, NOT `Modifier.alpha` — see `design/Opacity.kt`. The
            // stock modifier adds a clipping layer of its own, and this tile is
            // `disabled` during the celebration window, i.e. at exactly the
            // moment it is also `highlight`ed.
            .opacity(if (disabled) TileMetrics.DISABLED_OPACITY else 1f)
            // The blurred cast. Android has no offset+blur shadow, only an
            // elevation; see `BoxShadow.softElevation` for why that is the
            // closest honest mapping. `clip = false` keeps the ring and the lip,
            // which both paint outside the border box, from being cut off.
            .drawBehind {
                val ringAlpha = ring.value
                // This node is `OVERDRAW` bigger than the tile on every side,
                // so the tile's own origin is at (margin, margin) and its size
                // is `size` minus the two margins. Every number below is stated
                // in the tile's coordinates and then shifted, exactly as the
                // CSS is written against the border box.
                val margin = TileMetrics.OVERDRAW.toPx()
                val faceWidth = size.width - margin * 2f
                val faceHeight = size.height - margin * 2f

                // `0 0 0 6px #66BB6A` — a filled rounded rect one ring-width
                // larger on every side, drawn behind the face. The face covers
                // its middle, so what shows is a 6 dp band at radius 34.
                if (ringAlpha > 0f) {
                    val spread = TileMetrics.HIGHLIGHT_RING_WIDTH.toPx()
                    drawRoundRect(
                        color = ringColor,
                        topLeft = Offset(margin - spread, margin - spread),
                        size = Size(faceWidth + spread * 2f, faceHeight + spread * 2f),
                        cornerRadius = CornerRadius(TileMetrics.HIGHLIGHT_RING_RADIUS.toPx()),
                        alpha = ringAlpha,
                    )
                }

                // `0 8px 0 rgba(0,0,0,0.12)` — the hard lip, no blur, which is
                // what makes the tile read as a key. It belongs to the RESTING
                // shadow list only, so it fades out as the ring fades in: the
                // web swaps the whole `box-shadow` value, it does not add to it.
                val lipAlpha = (1f - ringAlpha) * lip.alpha
                if (lipAlpha > 0f) {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(margin, margin + lip.offsetY.toPx()),
                        size = Size(faceWidth, faceHeight),
                        cornerRadius = CornerRadius(TileMetrics.CORNER_RADIUS.toPx()),
                        alpha = lipAlpha,
                    )
                }
            }
            // Inside the headroom again: the elevation cast must trace the FACE,
            // not the margin, so this sits after the padding that restores the
            // tile's own size.
            .padding(TileMetrics.OVERDRAW)
            .shadow(
                elevation = cast.softElevation,
                shape = shape,
                clip = false,
                ambientColor = cast.color,
                spotColor = cast.color,
            )
            .background(color = face, shape = shape)
            .height(side)
            // `minWidth: dim; width: auto` — a two-letter tile grows, a
            // one-letter tile never shrinks below the floor.
            .defaultMinSize(minWidth = side)
            .padding(horizontal = TileMetrics.HORIZONTAL_PADDING.resolve(viewport)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides tileGlyphStyle(
                fontSize = fontSize,
                ink = ink.color,
                viewport = viewport,
                fontScale = fontScale,
                family = glyphFamily,
            ),
        ) {
            content()
        }
    }
}

/**
 * The « Écouter » sibling. A standalone button — no propagation to worry about —
 * so hearing a tile can never commit its pick. It speaks at pointer-down, the
 * same beat as a pick.
 */
@Composable
private fun ListenSurface(
    contentDescription: String,
    onPreview: () -> Unit,
    disabled: Boolean,
    viewport: Dp,
) {
    val fontScale = LocalDensity.current.fontScale
    val motion = rememberTileMotion()
    val interaction = rememberTileInteraction(motion)
    val cast = TileMetrics.PREVIEW_CAST
    val lip = TileMetrics.PREVIEW_LIP
    val label = contentDescription

    val speak: () -> Unit = { interaction.previewDown(disabled = disabled, onPreview = onPreview) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .touchDown(enabled = !disabled) { speak() }
            .clearAndSetSemantics {
                this.contentDescription = label
                this.role = Role.Button
                onClick {
                    speak()
                    true
                }
            }
            // Headroom for the lip, which drops 3 dp below the pill. Same
            // reason and same order as the tile above: after `touchDown`, before
            // every graphics layer. See `design/Overdraw.kt`.
            .overdraw(TileMetrics.PREVIEW_OVERDRAW)
            .tileMotion(motion)
            // `opacity`, not `Modifier.alpha`: one more clipping layer would
            // undo the headroom this just bought. See `design/Opacity.kt`.
            .opacity(if (disabled) TileMetrics.DISABLED_OPACITY else 1f)
            .drawBehind {
                val margin = TileMetrics.PREVIEW_OVERDRAW.toPx()
                val pillWidth = size.width - margin * 2f
                val pillHeight = size.height - margin * 2f
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(margin, margin + lip.offsetY.toPx()),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = CornerRadius(pillHeight / 2f),
                    alpha = lip.alpha,
                )
            }
            .padding(TileMetrics.PREVIEW_OVERDRAW)
            .shadow(
                elevation = cast.softElevation,
                shape = CircleShape,
                clip = false,
                ambientColor = cast.color,
                spotColor = cast.color,
            )
            .background(color = Color.White, shape = CircleShape)
            .height(TileMetrics.PREVIEW_HEIGHT.resolve(viewport)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = Copy.Tile.LISTEN_GLYPH,
            style = Typography.style(
                size = TileMetrics.PREVIEW_FONT_SIZE.resolve(viewport),
                weight = Typography.Weight.bold,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
            maxLines = 1,
        )
    }
}
