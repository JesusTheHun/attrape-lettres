package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.ImageKey
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.ReadImageRound
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.FitLine
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.WordIcon
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography

// ===========================================================================
// `src/exercises/ReadImageExercise.tsx` — the mirror of FirstLetter: the WORD
// is printed and the child taps the picture that matches it, so READING the
// word is the task. Worked example: `Sources/ALUI/Engines/ReadImageView.swift`.
//
// Two consequences the layout has to honour, both from the TSX comment:
//
//   « The word to READ — the whole task. Never spoken, so the child works
//     from the letters, not the ear. FitLine keeps it on a single line,
//     shrinking if needed. »
//
//   - the word is NEVER spoken. The consigne is the constant
//     `READ_IMAGE_PROMPT` (« Trouve la bonne image. ») and only the SUCCESS
//     line names the word. That is the model's business
//     (`SinglePickModel.readImage`); this file must simply never hand the word
//     to `preview` or to `replayPrompt`.
//   - the word goes in a `FitLine`, because « Escargot » at
//     `clamp(38px,11vw,68px)` does not fit a narrow phone on one line.
//
// The one asymmetry with its four single-pick siblings: ReadImage's TILE
// preview IS `locked`-guarded (the others are not). That guard lives in the
// descriptor (`previewGuardedByLock = true`), so [ReadImageStage.preview] reads
// exactly like its siblings and the asymmetry is asserted once, on the model.
//
// THE VIEW HOLDS NO RULE. Which tiles, what a tap means, when the round
// advances, what is spoken, what is awarded — all of that is
// `SinglePickModel`. What is left here is data plumbing (the palette, the three
// strings a tile carries, the authored `clamp()` triples), and it lives in
// [ReadImageStage] so a plain JUnit test can call it without a composition
// (A11). The exercise column, the tile row and the 🔊 pill are the letter
// family's shared chrome from `FirstLetterView.kt` — composed, not re-authored.
//
// The pictures are :art's; `WordIcon` picks the dedicated drawing when the word
// has one and the emoji otherwise. Nothing is redrawn here.
// ===========================================================================

// --- What a read-the-word tile is, as data ---------------------------------

/**
 * One picture tile. [word] is the pick key (`SinglePickModel.readImage` judges
 * `it.target.word`) AND what the preview speaks.
 */
data class ReadImageTile(
    /** `choice.word` — the pick key, the preview text, and the row's identity. */
    val word: String,
    /**
     * The picture: the dedicated drawing when the word has one, else the emoji
     * (`WordIcon`'s own rule).
     */
    val emoji: String,
    val img: ImageKey?,
    val paint: TilePaint,
    /** `ariaLabel={`Image : ${choice.word}`}`. */
    val label: String,
    /** `previewLabel={`Écouter ${choice.word}`}`. */
    val previewLabel: String,
)

/** The read-the-word row's data rules — the part of this view a test can reach. */
object ReadImageStage {

    /**
     * `TILE_COLORS` in `ReadImageExercise.tsx`: ALL FIVE paints of the shared
     * ramp, cycled `i % 5`. Level 4 shows five pictures, so this is the only
     * single-pick engine that reaches the violet — the length IS the cycle and
     * it is not interchangeable with FirstLetter's three or LetterMatch's four.
     */
    val palette: List<TilePaint> = Palette.tileColors

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = palette[index % palette.size]

    fun tiles(round: ReadImageRound): List<ReadImageTile> =
        round.choices.mapIndexed { index, choice ->
            ReadImageTile(
                word = choice.word,
                emoji = choice.emoji,
                img = choice.img,
                paint = paint(index),
                label = Copy.Exercise.imageTile(choice.word),
                previewLabel = Copy.Exercise.listenTile(choice.word),
            )
        }

    /**
     * `className="… uppercase …"` on the printed word. The content stores
     * « Escargot »; the child reads « ESCARGOT ».
     *
     * The no-arg, locale-invariant `uppercase()` on purpose, as in
     * `core.domain.faceLabel` — a Turkish locale would map « i » to « İ ».
     */
    fun displayWord(round: ReadImageRound): String = round.target.word.uppercase()

    /** `style={{ fontSize: "clamp(38px,11vw,68px)" }}` on the word. */
    val wordSize = FluidSpec(min = 38f, vw = 11f, max = 68f)

    /** `<WordIcon … size="clamp(60px,19vw,104px)" />` inside a tile. */
    val pictureSize = FluidSpec(min = 60f, vw = 19f, max = 104f)

    /** `className="m-0 mb-1 …"` on the consigne above the mascot. */
    val headlineBottomMargin: Dp = 4.dp

    /** `<FitLine className="my-1.5">` — 6 px above and below the word. */
    val wordMargin: Dp = 6.dp

    /* ---- The view's wiring, as functions (see FirstLetterStage) ----------- */

    /**
     * `onPick={() => pick(choice)}` → judged on `choice.word`. Synchronous,
     * called straight from `touchDown`'s `onDown` (invariant 1).
     */
    fun pick(tile: ReadImageTile, model: SinglePickModel<ReadImageRound>): Verdict =
        model.pick(tile.word)

    /**
     * `onPreview={() => { if (locked.current) return; audio.unlock(); void
     * audio.say(choice.word); }}` — the `locked` guard is the descriptor's
     * (`previewGuardedByLock = true`), which is why this reads exactly like its
     * four siblings.
     */
    fun preview(tile: ReadImageTile, model: SinglePickModel<ReadImageRound>) {
        model.preview(tile.word)
    }

    /** `highlight={flash === choice.word}`. */
    fun isHighlighted(tile: ReadImageTile, model: SinglePickModel<ReadImageRound>): Boolean =
        model.flash == tile.word
}

// --- The view ---------------------------------------------------------------

/**
 * `ReadImageExercise`. The printed word is the prompt and the whole task; the
 * pictures are the tiles; the 🔊 pill repeats the constant consigne and never
 * the word.
 *
 * The model is seeded EXACTLY ONCE per entry (iOS D9) — `remember`, never a
 * bare call in the body, which would rebuild the session on every recomposition
 * and silently replay different words. Invariant 5: nothing here gates,
 * unlocks or checks a level; a level change is the `remember(level)` key, the
 * port of React's `key={…}`.
 *
 * @param host the audio channel, the clock, `award` and the announce sleep. The
 *   run's confetti and this composition's scope are completed here, because
 *   only the screen can supply them.
 * @param mascot the TSX `profile.config`.
 * @param reduceMotion gates the mascot and the confetti, never press/shake.
 * @param rng injected so a test (and a preview) can replay a session.
 */
@Composable
fun ReadImageView(
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
    val model = remember(level) {
        SinglePickModel.readImage(
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
        ReadImageRoundView(model = model, mascot = mascot, reduceMotion = reduceMotion)
    }
}

@Composable
private fun ColumnScope.ReadImageRoundView(
    model: SinglePickModel<ReadImageRound>,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val round = model.current

    LettersStage {
        // `<p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">` — the
        // one single-pick engine besides FindSound that prints a consigne.
        model.headline?.let { headline ->
            BasicText(
                text = headline,
                modifier = Modifier.padding(bottom = ReadImageStage.headlineBottomMargin),
                style = Typography.style(
                    size = Typography.Size.base,
                    weight = Typography.Weight.bold,
                    color = Palette.inkSoft.color,
                    fontScale = fontScale,
                ),
            )
        }

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        // The word to READ. One line, shrunk rather than wrapped — and never
        // spoken by anything on this screen. No content description: the word is
        // its own text and TalkBack reads it as written.
        FitLine(modifier = Modifier.padding(vertical = ReadImageStage.wordMargin)) {
            BasicText(
                text = ReadImageStage.displayWord(round),
                style = Typography.style(
                    size = ReadImageStage.wordSize.resolve(viewport),
                    weight = Typography.Weight.black,
                    color = Palette.ink.color,
                    ratio = LetterStageMetrics.PROMPT_LINE_HEIGHT,
                    fontScale = fontScale,
                ).copy(textAlign = TextAlign.Center),
            )
        }

        LettersListenPill(
            text = model.listenText,
            contentDescription = model.listenAccessibilityLabel,
            onListen = model::replayPrompt,
        )

        LettersTileRow {
            ReadImageStage.tiles(round).forEach { tile ->
                Tile(
                    paint = tile.paint,
                    contentDescription = tile.label,
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { ReadImageStage.pick(tile, model) },
                    disabled = model.tilesDisabled,
                    highlight = ReadImageStage.isHighlighted(tile, model),
                    onPreview = { ReadImageStage.preview(tile, model) },
                    previewLabel = tile.previewLabel,
                ) {
                    // The tile already announces « Image : … », so the picture is
                    // decorative and `alt` stays empty, exactly as the TSX leaves
                    // it — labelling it would say the word twice.
                    WordIcon(
                        emoji = tile.emoji,
                        size = ReadImageStage.pictureSize.resolve(viewport),
                        img = tile.img,
                    )
                }
            }
        }
    }
}
