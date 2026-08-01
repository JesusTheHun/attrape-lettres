package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.FirstLetterRound
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.ConfettiSystem
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.ListenPill
import fr.dappit.attrapelettres.ui.components.ListenPillMetrics
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Shadows
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.components.WordIcon
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette

// ===========================================================================
// `src/exercises/FirstLetterExercise.tsx` — hear « Trouve la première lettre de
// X. », tap the letter.
//
// THE VIEW HOLDS NO RULE. Everything that decides anything — the session, the
// judge, the cooldown swallow, the star greying, the announce, the award —
// lives in `SinglePickModel.firstLetter` (see RoundRunner.kt). What lives here
// is the layout, the palette cycle, and the strings a tile carries; the two of
// those that are DATA (which paint a tile at index i wears, what its labels
// say) are extracted into [FirstLetterStage] so they are host-testable without
// a renderer (A11).
//
// Invariant 1: the pick path is `Tile` → `touchDown` → `model.pick(letter)`,
// synchronously. Nothing in this file defers a pick behind state, a coroutine
// or an animation.
//
// Invariant 5: the level arrives as a plain Int and is used as one. There is no
// unlock check here, in the model, or in :core's ladders — every level is
// always playable.
//
// This file also carries the chrome the three letter-form engines share
// ([LettersRunFrame], [LettersStage], [LettersTileRow], [LettersListenPill]),
// exactly as `FirstLetterView.swift` does for the iOS port, so ReadImage and
// LetterMatch compose it instead of re-authoring it. The TSX duplicates that
// chrome in all nine exercises; here it is written once and the numbers are the
// Tailwind classes, resolved:
//
//     relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2
//     … gap-4 on the tile row, mb-6 under the 🔊 pill
// ===========================================================================

// --- Shared chrome (the letter family's port of the TSX exercise column) -----

/**
 * The exercise column's authored metrics — the Tailwind classes above, in dp,
 * with the class each number came from.
 */
object LetterStageMetrics {

    /** `px-4`. */
    val paddingX: Dp = 16.dp

    /** `pt-2`. */
    val paddingTop: Dp = 8.dp

    /** `pb-8`. */
    val paddingBottom: Dp = 32.dp

    /** `gap-4` on `flex flex-wrap items-center justify-center` (both axes). */
    val tileGap: Dp = 16.dp

    /** `mb-6` on the 🔊 pill. */
    val listenBottomMargin: Dp = 24.dp

    /**
     * `px-5` / `py-2` on the 🔊 pill. One source of truth (`ListenPill`);
     * re-exported here because the TSX authors the class list per engine and an
     * audit reads engine by engine.
     */
    val listenPaddingX = ListenPillMetrics.paddingX
    val listenPaddingY = ListenPillMetrics.paddingY

    /**
     * Tailwind `shadow` on the 🔊 pill:
     * `0 1px 3px rgba(0,0,0,0.1), 0 1px 2px -1px rgba(0,0,0,0.1)`.
     */
    val listenShadow = Shadows.tailwind

    /**
     * `style={{ margin: "6px 0" }}` around FirstLetter's picture and
     * LetterMatch's prompt glyph.
     */
    val promptMargin: Dp = 6.dp

    /** `lineHeight: 1.1` on the big prompt glyph / the printed word. */
    const val PROMPT_LINE_HEIGHT = 1.1f
}

/**
 * `<GameFrame …>{done ? <Finished …/> : <div className="…">…</div>}</GameFrame>`
 * — the outer shell every single-pick exercise renders, over any [RoundRunner].
 *
 * Deviation from the iOS twin, and an improvement: there is no `runner == null`
 * frame. SwiftUI had to seed the model in `.task` (D9) because a `@State`
 * default is re-evaluated on a parent re-render, so the view spends one frame
 * with no session; Compose's `remember` runs exactly once during the first
 * composition, so the caller can build the model before it ever renders and this
 * takes a non-null runner.
 */
@Composable
fun LettersRunFrame(
    runner: RoundRunner,
    confetti: ConfettiSystem,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GameFrame(
        onBack = onBack,
        done = runner.progressDone,
        total = runner.totalRounds,
        stars = runner.stars,
        reduceMotion = reduceMotion,
        modifier = modifier,
        overlay = { ConfettiOverlay(confetti, Modifier.matchParentSize()) },
    ) {
        if (runner.done) {
            Finished(
                stars = runner.stars,
                earned = runner.earned,
                title = runner.finishedTitle,
                reduceMotion = reduceMotion,
                onMenu = onBack,
                onNext = onNext,
                modifier = Modifier.weight(1f),
            )
        } else {
            content()
        }
    }
}

/**
 * The round column:
 * `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`.
 *
 * No `gap` — the spacing between the mascot, the picture, the pill and the tiles
 * comes from the children's own margins, exactly as in the TSX. The `z-[41]` is
 * `GameFrame`'s job here (one flow column above the confetti canvas), so this
 * carries no z value of its own.
 */
@Composable
fun ColumnScope.LettersStage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(
                start = LetterStageMetrics.paddingX,
                end = LetterStageMetrics.paddingX,
                top = LetterStageMetrics.paddingTop,
                bottom = LetterStageMetrics.paddingBottom,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * `<div className="flex flex-wrap items-center justify-center gap-4">` — the
 * choice row. Wraps like the flexbox, centres each line, and NEVER scrolls: a
 * scrollable ancestor competes for the same down event and would break
 * invariant 1 (ARCHITECTURE.md §3, row 1).
 */
@Composable
fun LettersTileRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    WrapRow(spacing = LetterStageMetrics.tileGap, modifier = modifier, content = content)
}

/**
 * The big 🔊 pill under the mascot, with the letter family's `mb-6`.
 *
 * `ListenPill` is the button; this adds the margin and nothing else. The margin
 * is applied OUTSIDE the pill, because `mb-6` sits outside the button's box on
 * the web and must not become part of the tap target.
 */
@Composable
fun LettersListenPill(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onListen: () -> Unit,
) {
    ListenPill(
        text = text,
        contentDescription = contentDescription,
        modifier = modifier.padding(bottom = LetterStageMetrics.listenBottomMargin),
        onListen = onListen,
    )
}

// --- What a first-letter tile is, as data -----------------------------------

/**
 * One tile of the choice row. [letter] is BOTH the glyph and the pick key —
 * `SinglePickModel.firstLetter` judges `target.letter`, so handing it anything
 * else would silently make every round unwinnable.
 */
data class FirstLetterTile(
    /**
     * The glyph shown, the pick key, and what the preview speaks (the TSX calls
     * `audio.say(letter)`).
     */
    val letter: String,
    val paint: TilePaint,
    /** `ariaLabel={`Lettre ${letter}`}`. */
    val label: String,
    /** `previewLabel={`Écouter ${letter}`}`. */
    val previewLabel: String,
)

/** The first-letter row's data rules — the part of this view a test can reach. */
object FirstLetterStage {

    /**
     * `TILE_COLORS` in `FirstLetterExercise.tsx`: the FIRST THREE paints of the
     * shared ramp, cycled `i % 3`. The length IS the cycle, so it is not
     * interchangeable with ReadImage's five or LetterMatch's four — a row of
     * four here wears coral again, never the green only ReadImage reaches.
     */
    val palette: List<TilePaint> = Palette.tileColors.take(3)

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = palette[index % palette.size]

    /** `round.choices.map((letter, i) => <Tile …>{letter}</Tile>)`. */
    fun tiles(round: FirstLetterRound): List<FirstLetterTile> =
        round.choices.mapIndexed { index, letter ->
            FirstLetterTile(
                letter = letter,
                paint = paint(index),
                label = Copy.Exercise.letterTile(letter),
                previewLabel = Copy.Exercise.listenTile(letter),
            )
        }

    /** `<WordIcon … size="clamp(80px,28vw,150px)" />`. */
    val pictureSize = FluidSpec(min = 80f, vw = 28f, max = 150f)

    /* ---- The three lines the composable would otherwise hide ---------------
       `onPick`, `onPreview` and `highlight` are the whole of the view's wiring,
       and a @Composable body is not reachable from a host JUnit run. They live
       here as functions so the key handed to `pick`, the text handed to
       `preview` and the flash comparison are all asserted on the host — a
       mis-keyed row would make every round unwinnable and nothing else would
       notice. */

    /** `onPick={() => pick(letter)}` — synchronous, at touch-down (invariant 1). */
    fun pick(tile: FirstLetterTile, model: SinglePickModel<FirstLetterRound>): Verdict =
        model.pick(tile.letter)

    /** `onPreview={() => { audio.unlock(); void audio.say(letter); }}`. */
    fun preview(tile: FirstLetterTile, model: SinglePickModel<FirstLetterRound>) {
        model.preview(tile.letter)
    }

    /** `highlight={flash === letter}`. */
    fun isHighlighted(tile: FirstLetterTile, model: SinglePickModel<FirstLetterRound>): Boolean =
        model.flash == tile.letter
}

// --- The view ---------------------------------------------------------------

/**
 * `FirstLetterExercise`. The picture is the prompt; the 🔊 pill repeats the
 * spoken line and, on levels 1–3 only, prints the word beside the speaker (the
 * model's `listenText` owns that rule — `level < FIRST_LETTER_LEVELS.size - 1`).
 *
 * @param host the audio channel, the clock, `award` and the announce sleep. The
 *   run's confetti and this composition's scope are completed here, because only
 *   the screen can supply them.
 * @param mascot the TSX `profile.config`.
 * @param reduceMotion gates the mascot and the confetti, never press/shake.
 * @param rng injected so a test (and a preview) can replay a session.
 */
@Composable
fun FirstLetterView(
    level: Int,
    host: EngineHost,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    rng: RandomSource = SystemRandomSource(),
) {
    val scope = rememberCoroutineScope()
    val confetti = rememberConfettiSystem(reduceMotion, key = level)
    // Seeded EXACTLY ONCE per entry (iOS D9): `remember`, never a bare call in
    // the body, which would rebuild the session on every recomposition and
    // silently replay different words.
    val model = remember(level) {
        SinglePickModel.firstLetter(
            level = level,
            deps = host.deps(scope = scope, fireConfetti = confetti::fire),
            rng = rng,
        )
    }
    DisposableEffect(model) {
        model.activate()
        onDispose { model.deactivate() }
    }

    LettersRunFrame(
        runner = model,
        confetti = confetti,
        reduceMotion = reduceMotion,
        onBack = onBack,
        onNext = onNext,
        modifier = modifier,
    ) {
        FirstLetterRoundView(model = model, mascot = mascot, reduceMotion = reduceMotion)
    }
}

@Composable
private fun ColumnScope.FirstLetterRoundView(
    model: SinglePickModel<FirstLetterRound>,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val target = model.current.target
    LettersStage {
        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        // `<div style={{ margin: "6px 0" }}><WordIcon …/></div>`. The picture is
        // decorative: the word is SPOKEN and the tiles carry the labels, so an
        // `alt` here would announce the answer's word twice over.
        WordIcon(
            emoji = target.emoji,
            size = FirstLetterStage.pictureSize.resolve(viewport),
            modifier = Modifier.padding(vertical = LetterStageMetrics.promptMargin),
            img = target.img,
        )

        LettersListenPill(
            text = model.listenText,
            contentDescription = model.listenAccessibilityLabel,
            onListen = model::replayPrompt,
        )

        LettersTileRow {
            FirstLetterStage.tiles(model.current).forEach { tile ->
                Tile(
                    paint = tile.paint,
                    contentDescription = tile.label,
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { FirstLetterStage.pick(tile, model) },
                    disabled = model.tilesDisabled,
                    highlight = FirstLetterStage.isHighlighted(tile, model),
                    onPreview = { FirstLetterStage.preview(tile, model) },
                    previewLabel = tile.previewLabel,
                ) {
                    TileGlyph(tile.letter)
                }
            }
        }
    }
}
