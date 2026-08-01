package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.design.fixedSp
import fr.dappit.attrapelettres.ui.design.opacity
import fr.dappit.attrapelettres.ui.interaction.pulseAlpha
import fr.dappit.attrapelettres.ui.interaction.rememberPulse
import fr.dappit.attrapelettres.ui.interaction.touchDown

// Port of `src/components/GameFrame.tsx` — the in-exercise chrome: the stage
// wash, « ← Menu », the star strip, and the confetti overlay slot. Written
// against the TSX, with `Sources/ALUI/Components/GameFrame.swift` as the worked
// example of the same port into a declarative framework.
//
// INVARIANT 8'S VISIBLE HALF LIVES HERE — farming never pays.
//
// Three mechanisms carry that invariant and this is one of them: each round's
// star greys on its FIRST wrong tap, AT POINTER-DOWN. The strip itself is pure
// rendering over `(done, total, stars)`; the greying happens because the engine
// calls [StarStrip.miss] synchronously inside its pick handler — the same beat
// as the shake and the « oh non » — and the `i == done && !stars[i]` branch in
// [StarStrip.cells] shows the LIVE round's star as lost the instant the flag
// flips. That immediacy is the point: the child sees the bonus slip at the
// moment of the miss, not at the recap. Do not debounce it, do not animate it,
// do not move it to the end of the round, and never let it wait for a lift.
//
// The other two mechanisms are elsewhere and must all three survive: the
// post-miss swallow window (`MISS_COOLDOWN_MS`, in the engines) and
// `sessionReward` being the only earner (`core/rewards/Rewards.kt`).
//
// Z-ORDER, measured in the TSX rather than assumed. Bottom to top on the web:
//
//   1. the stage gradient
//   2. the confetti canvas — `absolute inset-0 pointer-events-none`, `zIndex: 40`
//   3. the header row — `relative z-[41]` (GameFrame.tsx:35)
//   4. the children — every one of the nine exercise roots is
//      `relative z-[41] … px-4 pb-8 pt-2`, and `Finished` is `relative z-[41]` too
//
// So the burst passes BEHIND the tiles, the mascot and the word picture, not in
// front of them. It reads as confetti falling behind the game rather than
// splattering across it, and at the reward moment the child's eye stays on the
// word. Drawing it on top is a different, worse feeling — which is why this is a
// behaviour bug and not a cosmetic one, and why two iOS engine agents reported
// it independently before it was fixed there.
//
// 3 and 4 are TIED at z-index 41 and the tie breaks on DOM order. Compose gets
// that for free and more simply than SwiftUI did: the header and the children
// are the two children of ONE column that carries `zIndex(FrameLayer.FLOW.z)`,
// so they are jointly above the overlay and cannot fight each other — they are
// in normal flow, exactly as the web's flex column puts them. iOS had to spend a
// third z value (42) plus two `PreferenceKey` height measurements to rebuild the
// same thing out of a `ZStack`; none of that is needed here, and the deviation
// is recorded rather than copied.
//
// z-order is invisible to every assertion that does not actually draw — that is
// how it survived a full phase on iOS, where the draw list, the view type and
// 1115 other tests were equally happy either way. A host JUnit run cannot
// rasterise, so `GameFrameTest` pins the two halves it CAN reach: the numeric
// ordering of [FrameLayer], and a source scan proving the three modifiers below
// are the only place a z value is spelled.

// --------------------------------------------------------------------------
// The star strip rule — pure, host-tested
// --------------------------------------------------------------------------

/** One cell of the strip. The four states the TSX render distinguishes. */
enum class StarCell {
    /** `i < done`, star kept: a full star. */
    EARNED,

    /**
     * Star lost — played rounds AND the live round the instant a wrong tap
     * lands: greyed (`grayscale(1)`, opacity 0.45), kept VISIBLE so the round
     * still counts as played (invariants 3 and 8).
     */
    LOST,

    /** `i == done`, star still winnable: opacity 0.8, pulsing. */
    LIVE,

    /** `i > done`: a dot at opacity 0.28. */
    PENDING,
}

object StarStrip {

    /** `const fontSize = total > 9 ? 16 : 20;` */
    fun fontSize(total: Int): Dp = if (total > 9) 16.dp else 20.dp

    /**
     * The strip classifier, verbatim from the TSX render:
     * ```
     * if (i < done || (i === done && !stars[i]))  -> star (LOST when !stars[i])
     * if (i === done)                             -> star, pulsing
     * otherwise                                   -> dot
     * ```
     * An out-of-range `stars[i]` is `undefined` in JS — falsy — so a missing
     * flag reads as LOST, never as EARNED.
     */
    fun cells(done: Int, total: Int, stars: List<Boolean>): List<StarCell> =
        (0 until maxOf(0, total)).map { i ->
            val kept = stars.getOrElse(i) { false }
            when {
                i < done -> if (kept) StarCell.EARNED else StarCell.LOST
                i == done -> if (kept) StarCell.LIVE else StarCell.LOST
                else -> StarCell.PENDING
            }
        }

    /**
     * `missRound(i)` — greys star [index] exactly once, synchronously, in the
     * same beat as the shake and the nudge (invariant 8):
     * ```
     * if (!starsRef.current[i]) return;
     * starsRef.current = starsRef.current.map((s, j) => (j === i ? false : s));
     * ```
     * A second wrong tap in the same round changes nothing, so spam cannot
     * compound a penalty that is already paid. Out of range is a no-op (JS:
     * `!undefined` is true, so the guard returns early).
     *
     * Call this from inside the pointer-down handler. Returns whether anything
     * changed, so an engine can tell a first miss from a repeat without reading
     * the list back.
     */
    fun miss(index: Int, stars: MutableList<Boolean>): Boolean {
        if (index !in stars.indices || !stars[index]) return false
        stars[index] = false
        return true
    }

    /**
     * The count `sessionReward` is paid on — TSX:
     * `starsRef.current.filter(Boolean).length`.
     */
    fun kept(stars: List<Boolean>): Int = stars.count { it }
}

// --------------------------------------------------------------------------
// Authored metrics — GameFrame.tsx's Tailwind classes, as numbers
// --------------------------------------------------------------------------

object GameFrameMetrics {

    /** `rounded-3xl` = 24 px. */
    val cornerRadius: Dp = 24.dp

    /** `min-h-[620px]` — the same number as `Shell.minimumScreenHeight`. */
    val minHeight: Dp = Shell.minimumScreenHeight

    /** Header row: `gap-3` = 12 px. */
    val headerSpacing: Dp = 12.dp

    /** Header row: `px-4 pt-4` = 16 px. */
    val headerPadding: Dp = 16.dp

    /** Strip: `gap-x-1` = 4 px. */
    val stripGapX: Dp = 4.dp

    /** Strip: `gap-y-0.5` = 2 px. */
    val stripGapY: Dp = 2.dp

    /**
     * `hidden w-[84px] shrink-0 sm:block` — the balancing spacer exists only at
     * viewport >= 640 (Tailwind `sm:`). On a phone the strip is therefore NOT
     * optically centred; it is pushed right by the « ← Menu » button. Odd,
     * authored, frozen — three ports now agree on it.
     */
    val spacerWidth: Dp = 84.dp
    val spacerMinViewport: Dp = 640.dp

    /** The back button: `px-4 py-2`. */
    val backPaddingX: Dp = 16.dp
    val backPaddingY: Dp = 8.dp
}

/**
 * The frame's two paint layers, as the web's z-indices.
 *
 * The stage wash is not in the enum because it is not a sibling: it is drawn by
 * the frame box itself and is behind everything by construction.
 */
enum class FrameLayer(val z: Float) {
    /** The confetti canvas: `absolute inset-0`, `zIndex: 40`. */
    OVERLAY(40f),

    /**
     * The header and the children together: both `z-[41]` on the web, and here
     * both inside one column, so the DOM tie-break they rely on is just their
     * order in the flow.
     */
    FLOW(41f),
}

// --------------------------------------------------------------------------
// The wrapping strip row
// --------------------------------------------------------------------------

/**
 * `flex min-w-0 flex-1 flex-wrap items-center justify-center gap-x-1 gap-y-0.5`
 * — a greedy-wrap flow: lines centred horizontally, items centred vertically
 * within their line. A long run (`total > 9`) wraps on a narrow phone exactly as
 * the flexbox does.
 */
object StripFlow {

    /**
     * Greedy flexbox wrap over item widths in pixels: an item starts a new line
     * when it no longer fits. Returns the item INDICES per line.
     */
    fun wrapLines(widths: List<Int>, maxWidth: Int, gapX: Int): List<List<Int>> {
        val lines = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var lineWidth = 0
        widths.forEachIndexed { index, itemWidth ->
            val widthIfAppended = if (current.isEmpty()) itemWidth else lineWidth + gapX + itemWidth
            if (current.isNotEmpty() && widthIfAppended > maxWidth) {
                lines.add(current)
                current = mutableListOf(index)
                lineWidth = itemWidth
            } else {
                current.add(index)
                lineWidth = widthIfAppended
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    /** The laid-out width of one line: the items plus the gaps between them. */
    fun lineWidth(line: List<Int>, widths: List<Int>, gapX: Int): Int =
        line.sumOf { widths[it] } + gapX * maxOf(0, line.size - 1)
}

@Composable
private fun StripFlowRow(
    gapX: Dp,
    gapY: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gx = gapX.roundToPx()
        val gy = gapY.roundToPx()
        // `.unspecified` — a strip glyph takes its natural size and never the
        // slot's.
        val placeables = measurables.map { it.measure(Constraints()) }
        val widths = placeables.map { it.width }
        val slot = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val lines = StripFlow.wrapLines(widths, slot, gx)
        val lineHeights = lines.map { line -> line.maxOfOrNull { placeables[it].height } ?: 0 }
        val natural = lines.maxOfOrNull { StripFlow.lineWidth(it, widths, gx) } ?: 0

        // A flex item (`flex-1 min-w-0`) adopts the width it is given.
        val width = (if (constraints.hasBoundedWidth) constraints.maxWidth else natural)
            .coerceAtLeast(constraints.minWidth)
        val height = (lineHeights.sum() + gy * maxOf(0, lines.size - 1))
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            var y = 0
            lines.forEachIndexed { lineIndex, line ->
                // justify-center
                var x = (width - StripFlow.lineWidth(line, widths, gx)) / 2
                for (index in line) {
                    val placeable = placeables[index]
                    // items-center
                    placeable.place(x, y + (lineHeights[lineIndex] - placeable.height) / 2)
                    x += placeable.width + gx
                }
                y += lineHeights[lineIndex] + gy
            }
        }
    }
}

// --------------------------------------------------------------------------
// The frame
// --------------------------------------------------------------------------

/**
 * The chrome every exercise runs inside.
 *
 * @param done rounds completed — engines pass `if (done) total else idx`.
 * @param stars per-round first-try flags (index = round). A round's star starts
 *   winnable, stays gold when cleared first try, and greys THE INSTANT a wrong
 *   tap lands. Hold it as a `mutableStateListOf` and grey it with
 *   [StarStrip.miss] inside the pick handler — invariant 8.
 * @param reduceMotion gates the live star's pulse, and nothing else in this
 *   file. Press and shake are deliberately NOT gated (`MotionSurface`).
 * @param overlay the confetti canvas slot — full-bleed, hit-testing off, painted
 *   BENEATH both the header and the content. On the web the burst passes behind
 *   the tiles; see the z-order note in the file header.
 * @param content the exercise column, or `Finished`.
 */
@Composable
fun GameFrame(
    onBack: () -> Unit,
    done: Int,
    total: Int,
    stars: List<Boolean>,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val viewport = LocalViewportWidth.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = GameFrameMetrics.minHeight)
            // `overflow-hidden rounded-3xl`, then `background: STAGE` inside the
            // clip. The edge-to-edge wash behind the CARD is the root's job, not
            // the frame's.
            .clip(RoundedCornerShape(GameFrameMetrics.cornerRadius))
            .drawBehind { drawRect(Palette.stage.brush(size)) },
    ) {
        // `absolute inset-0 pointer-events-none`, `zIndex: 40`. `matchParentSize`
        // is the twin of `inset-0`: it takes the parent's size without driving
        // it. Nothing inside it takes a pointer, and it is hidden from the
        // accessibility tree the way `aria-hidden` hides the canvas.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(FrameLayer.OVERLAY.z)
                .clearAndSetSemantics {},
            content = overlay,
        )

        // The web's flex column: the header row, then the children. One z value
        // for both, above the canvas.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // fillMaxHeight is a no-op when the frame is measured with an
                // unbounded height, and a column with an unbounded main axis
                // gives its `weight(1f)` children ZERO — the exercise would
                // collapse. heightIn re-floors it at the authored 620 dp, which
                // is what `min-h-[620px]` gives the flex column on the web.
                .fillMaxHeight()
                .heightIn(min = GameFrameMetrics.minHeight)
                .zIndex(FrameLayer.FLOW.z),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FrameHeader(
                onBack = onBack,
                done = done,
                total = total,
                stars = stars,
                viewport = viewport,
                reduceMotion = reduceMotion,
            )
            content()
        }
    }
}

@Composable
private fun FrameHeader(
    onBack: () -> Unit,
    done: Int,
    total: Int,
    stars: List<Boolean>,
    viewport: Dp,
    reduceMotion: ReduceMotionSource,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = GameFrameMetrics.headerPadding,
                end = GameFrameMetrics.headerPadding,
                top = GameFrameMetrics.headerPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(GameFrameMetrics.headerSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackToMenu(onBack)
        StarStripRow(
            done = done,
            total = total,
            stars = stars,
            reduceMotion = reduceMotion,
            modifier = Modifier.weight(1f),
        )
        if (viewport >= GameFrameMetrics.spacerMinViewport) {
            Spacer(Modifier.width(GameFrameMetrics.spacerWidth))
        }
    }
}

/**
 * « ← Menu ».
 *
 * NAVIGATION, not gameplay — the TSX uses `onClick` here deliberately, so this
 * fires on the LIFT and only when the finger lifts inside the button. That is
 * `touchDown`'s `onUp(inside)`, not a second gesture primitive: the app has one
 * touch path and this is it with an empty down handler. Leaving an exercise by
 * accident costs a child their place, which is exactly what a pointer-up
 * confirmation is for.
 *
 * The label is the Text's own content (the web sets no `aria-label`), merged
 * upward, plus a click ACTION so TalkBack activates it properly.
 */
@Composable
private fun BackToMenu(onBack: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    onBack()
                    true
                }
            }
            .cssShadow(Shadows.tailwind, CircleShape)
            .background(Color.White.copy(alpha = Palette.White.o70), CircleShape)
            .touchDown(onUp = { inside -> if (inside) onBack() }) {
                // No feedback at down: the web animates tiles and nothing else.
            }
            .padding(
                horizontal = GameFrameMetrics.backPaddingX,
                vertical = GameFrameMetrics.backPaddingY,
            ),
    ) {
        Text(
            text = Copy.Frame.BACK_TO_MENU,
            style = Typography.style(
                size = Typography.Size.lg,
                weight = Typography.Weight.bold,
                color = Palette.ink.color,
                ratio = TEXT_LG_LEADING,
                fontScale = fontScale,
            ),
        )
    }
}

@Composable
private fun StarStripRow(
    done: Int,
    total: Int,
    stars: List<Boolean>,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
) {
    val cells = StarStrip.cells(done = done, total = total, stars = stars)
    val size = StarStrip.fontSize(total)
    StripFlowRow(
        gapX = GameFrameMetrics.stripGapX,
        gapY = GameFrameMetrics.stripGapY,
        modifier = modifier,
    ) {
        cells.forEach { cell -> StarCellGlyph(cell = cell, size = size, reduceMotion = reduceMotion) }
    }
}

/**
 * The glyph style for one strip cell.
 *
 * Deliberately NOT `Typography.style`: that applies `leading-none`, and a line
 * box the exact height of the em crops an emoji, whose ascent runs past it. The
 * TSX sets a font size on the span and nothing else, so neither do we — the
 * line height stays unspecified and the font decides.
 */
private fun glyphStyle(size: Dp, fontScale: Float, color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = fixedSp(size.value, fontScale),
    fontFamily = Typography.appFamily,
)

@Composable
private fun StarCellGlyph(cell: StarCell, size: Dp, reduceMotion: ReduceMotionSource) {
    val fontScale = LocalDensity.current.fontScale
    when (cell) {
        StarCell.EARNED -> Text(
            text = Copy.Frame.STAR,
            style = glyphStyle(size, fontScale, Palette.ink.color),
        )

        // `LOST = { filter: grayscale(1), opacity: 0.45 }` — greyed, never
        // removed: the round still counts as played.
        StarCell.LOST -> Text(
            text = Copy.Frame.STAR,
            modifier = Modifier
                .grayscale()
                .opacity(Palette.Lost.opacity),
            style = glyphStyle(size, fontScale, Palette.ink.color),
        )

        StarCell.LIVE -> LiveStar(size = size, reduceMotion = reduceMotion)

        StarCell.PENDING -> Text(
            text = Copy.Frame.FUTURE_ROUND,
            modifier = Modifier.opacity(Palette.futureDotOpacity),
            style = glyphStyle(size, fontScale, Color.Black),
        )
    }
}

/**
 * The winnable round's star: inline `opacity: 0.8`, `motion-safe:animate-pulse`.
 *
 * CSS semantics, verified against the Tailwind keyframes: `pulse` authors ONLY
 * the 50 % frame, so the endpoints are the element's own opacity and the star
 * breathes 0.8 -> 0.5 -> 0.8. Under reduced motion the class is dropped and the
 * inline 0.8 shows statically — which is exactly what `PulseMotion` does when
 * `MotionSurface.PULSE` is gated.
 *
 * The alpha is read inside a `graphicsLayer` lambda (`Modifier.pulseAlpha`), so
 * the breathing never touches the render path (invariant 2).
 */
@Composable
private fun LiveStar(size: Dp, reduceMotion: ReduceMotionSource) {
    val fontScale = LocalDensity.current.fontScale
    val pulse = rememberPulse(
        reduceMotion = reduceMotion,
        active = true,
        baseAlpha = Palette.liveStarOpacity,
    )
    Text(
        text = Copy.Frame.STAR,
        modifier = Modifier.pulseAlpha(pulse),
        style = glyphStyle(size, fontScale, Palette.ink.color),
    )
}
