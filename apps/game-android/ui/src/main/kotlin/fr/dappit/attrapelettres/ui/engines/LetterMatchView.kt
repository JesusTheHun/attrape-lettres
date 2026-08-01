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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchRound
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.domain.faceLabel
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography

// ===========================================================================
// `src/exercises/LetterMatchExercise.tsx` — ONE view, two catalog rows:
//
//   match-case    (kind CASE)    majuscule ⇄ minuscule, both in print
//   match-script  (kind SCRIPT)  script ⇄ « attachée », at ONE shared case
//
// Worked example: `Sources/ALUI/Engines/LetterMatchView.swift`. The router
// (`App.tsx`) passes the row's `match` field through as [LetterMatchKind]; the
// `ExerciseId` travels BESIDE it rather than being derived from it, because it
// keys the reward ledger and a wrong id would move a child's stars to another
// key (invariant 8).
//
// « The prompt never names the target, so the child must read the shape; the
//   name is spoken only on success as reinforcement. » Both of those lines are
// the model's (`letterMatchPrompt` / `letterMatchSuccess` in :core). What this
// file owns is that the prompt is DRAWN in its own script, that a tile is drawn
// in the counterpart script, and that both carry `core.domain.faceLabel` as
// their content description — « Lettre A majuscule », « Lettre a minuscule
// attachée » — because a screen reader cannot see a letterform (invariant 6).
//
// The judge is `face.base`, i.e. the letter's IDENTITY, not its form: the tiles
// are ALL in the counterpart form already, so the child's job is to recognise
// WHICH letter, and any tile whose base matches wins. That lives in the
// descriptor (`targetKey = { it.prompt.base }`); this file just hands `pick` the
// same `base` it labels — and [LetterMatchStage.pick] is a tested function
// rather than a line inside a composable precisely because keying it on the
// GLYPH would make every majuscule→minuscule round unwinnable, silently.
//
// OPEN RISK — THE CURSIVE FACE. `Typography.letterFamily(CURSIVE)` is
// `FontFamily.Cursive`, the generic family. AOSP aliases it to a joined
// handwriting face, which is what « attachée » needs; a device whose `fonts.xml`
// has no `cursive` alias falls back to the default sans, and match-script then
// teaches the WRONG letterform while looking perfectly fine. No host test can
// see that — a font is resolved by the platform at draw time. What
// [LetterMatchStage.family] and its test CAN pin is which token this view asks
// for, so a change of token is caught even though a change of device is not.
// Fixing it means shipping a licensed cursive asset; that is not this file's
// change.
//
// The exercise column, the tile row and the 🔊 pill are the letter family's
// shared chrome from `FirstLetterView.kt` — composed, not re-authored.
// ===========================================================================

// --- What a letter-form tile is, as data ------------------------------------

/**
 * One counterpart-form tile. [pickKey] is `face.base`; `face.glyph` and
 * `face.script` are what gets drawn.
 */
data class LetterMatchTile(
    val face: LetterFace,
    val paint: TilePaint,
    /** `ariaLabel={faceLabel(face)}` — never « Lettre A »: the FORM is the task. */
    val label: String,
    /** `previewLabel={`Écouter ${face.base}`}`; the preview speaks `face.base`. */
    val previewLabel: String,
) {
    /** `onPick={() => pick(face)}` → judged on `face.base !== round.prompt.base`. */
    val pickKey: String get() = face.base
}

/** The letter-match row's data rules — the part of this view a test can reach. */
object LetterMatchStage {

    /**
     * `TILE_COLORS` in `LetterMatchExercise.tsx`: the first FOUR paints of the
     * shared ramp, cycled `i % 4`. The fourth is `#AED581/#213606` — the
     * STANDARD green, NOT the lighter `#A5D6A7/#123B18` the syllable grid
     * substitutes at the same index.
     */
    val palette: List<TilePaint> = Palette.tileColors.take(4)

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = palette[index % palette.size]

    fun tiles(round: LetterMatchRound): List<LetterMatchTile> =
        round.choices.mapIndexed { index, face ->
            LetterMatchTile(
                face = face,
                paint = paint(index),
                label = faceLabel(face),
                previewLabel = Copy.Exercise.listenTile(face.base),
            )
        }

    /** `style={{ fontSize: "clamp(80px,28vw,150px)" }}` on the prompt glyph. */
    val promptSize = FluidSpec(min = 80f, vw = 28f, max = 150f)

    /**
     * `style={{ fontFamily: SCRIPT_FONT[face.script] }}` — on the prompt AND on
     * every tile glyph.
     *
     * The one place this view names a typography token, and the reason it is a
     * function: see the cursive risk in the file header. A host test pins which
     * token is asked for; only a device can say what the token resolves to.
     */
    fun family(script: LetterScript): FontFamily = Typography.letterFamily(script)

    /* ---- The view's wiring, as functions (see FirstLetterStage) ----------- */

    /**
     * `onPick={() => pick(face)}` → judged on `face.base`, the letter's
     * IDENTITY. Synchronous, inside the touch-down call (invariant 1).
     */
    fun pick(tile: LetterMatchTile, model: SinglePickModel<LetterMatchRound>): Verdict =
        model.pick(tile.pickKey)

    /**
     * `onPreview={() => { audio.unlock(); void audio.say(face.base); }}` — the
     * letter's NAME, never the glyph (« a » is not a spoken word, and the
     * cursive form has no separate name). NOT locked-guarded: the TSX guards
     * only ReadImage's preview.
     */
    fun preview(tile: LetterMatchTile, model: SinglePickModel<LetterMatchRound>) {
        model.preview(tile.face.base)
    }

    /** `highlight={flash === face.base}`. */
    fun isHighlighted(tile: LetterMatchTile, model: SinglePickModel<LetterMatchRound>): Boolean =
        model.flash == tile.pickKey
}

// --- The view ---------------------------------------------------------------

/**
 * `LetterMatchExercise` — « Majuscule et minuscule » / « Écriture attachée ».
 *
 * The model is seeded EXACTLY ONCE per entry (iOS D9): `remember`, never a bare
 * call in the body, which would replay different letters on every
 * recomposition. Invariant 5 — every level is reachable at any time and nothing
 * here gates, unlocks or checks one; a level change is the `remember(level)`
 * key, the port of React's `key={…}`.
 *
 * @param exercise `match-case` or `match-script`. It keys the reward ledger, so
 *   it travels WITH [kind] rather than being derived from it.
 * @param kind the `EXERCISES` row's `match` field.
 * @param reduceMotion gates the mascot and the confetti, never press/shake.
 * @param rng injected so a test (and a preview) can replay a session.
 */
@Composable
fun LetterMatchView(
    exercise: ExerciseId,
    kind: LetterMatchKind,
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
    val confetti = rememberConfettiSystem(reduceMotion, key = listOf(exercise, kind, level))
    // Keyed on the FULL identity the factory seeds from, not just `level`: this
    // one composable serves match-case AND match-script, and `remember(level)`
    // would carry a case model straight into the script row at the same level —
    // wrong content, wrong ledger, and it recomposes cleanly enough to look fine.
    val model = remember(exercise, kind, level) {
        SinglePickModel.letterMatch(
            exercise = exercise,
            kind = kind,
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
        LetterMatchRoundView(model = model, mascot = mascot, reduceMotion = reduceMotion)
    }
}

/**
 * The round. This engine prints NO consigne above the mascot — `model.headline`
 * is null for LetterMatch, the same as FirstLetter — because naming the target
 * would give the answer away.
 */
@Composable
private fun ColumnScope.LetterMatchRoundView(
    model: SinglePickModel<LetterMatchRound>,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val prompt = model.current.prompt

    LettersStage {
        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        // The letter to read, in ITS OWN script, with `margin: "6px 0"`.
        // Labelled, because the FORM is the task and a letterform is invisible
        // to TalkBack (invariant 6): `clearAndSetSemantics` replaces the glyph —
        // « a », which a screen reader would read as the letter A in print —
        // with `faceLabel`'s « Lettre A minuscule attachée ».
        BasicText(
            text = prompt.glyph,
            modifier = Modifier
                .padding(vertical = LetterStageMetrics.promptMargin)
                .clearAndSetSemantics { contentDescription = faceLabel(prompt) },
            style = Typography.style(
                size = LetterMatchStage.promptSize.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                family = LetterMatchStage.family(prompt.script),
                ratio = LetterStageMetrics.PROMPT_LINE_HEIGHT,
                fontScale = fontScale,
            ),
        )

        LettersListenPill(
            text = model.listenText,
            contentDescription = model.listenAccessibilityLabel,
            onListen = model::replayPrompt,
        )

        LettersTileRow {
            LetterMatchStage.tiles(model.current).forEach { tile ->
                Tile(
                    paint = tile.paint,
                    contentDescription = tile.label,
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { LetterMatchStage.pick(tile, model) },
                    disabled = model.tilesDisabled,
                    highlight = LetterMatchStage.isHighlighted(tile, model),
                    // `<span style={{ fontFamily: SCRIPT_FONT[face.script] }}>`
                    // inside the tile — inherits the tile's font size, swaps only
                    // the family.
                    glyphFamily = LetterMatchStage.family(tile.face.script),
                    onPreview = { LetterMatchStage.preview(tile, model) },
                    previewLabel = tile.previewLabel,
                ) {
                    TileGlyph(tile.face.glyph)
                }
            }
        }
    }
}
