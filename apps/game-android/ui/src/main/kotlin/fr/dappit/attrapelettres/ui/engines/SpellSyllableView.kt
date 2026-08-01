package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.SpellCell
import fr.dappit.attrapelettres.core.domain.SpellLetterTile
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SpellSyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.domain.faceLabel
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.FitLine
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.WordIcon
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown

// ===========================================================================
// `src/exercises/SpellSyllableExercise.tsx` — the word is printed with one (or
// two) syllable blanked into per-letter slots. (Worked example:
// `apps/game-ios/Sources/ALUI/Engines/SpellSyllableView.swift`.)
//
// « Same forgiving loop as SpellSoundExercise (feedback on pointerdown, WAAPI
// shake, canvas confetti, whole-row judgement), but the prompt is the printed
// word and the target is its missing letters. Mode only changes the tray / gap
// count. »
//
// FIVE EXERCISES, ONE SCREEN. `mode` and `mixed` both come from the `EXERCISES`
// row and stay parameters, exactly as the TSX props do (`App.tsx`:
// `<SpellSyllableExercise exercise={view.exercise} mode={meta.spell}
// mixed={meta.mixed} level={view.level} … />`). The five rows are
//
//     spell-syllable              letters-exact   mixed = false
//     spell-syllable-plus         letters-extra   mixed = false
//     spell-two-syllables         letters-two     mixed = false
//     spell-syllable-plus-mixed   letters-extra   mixed = TRUE
//     spell-two-syllables-mixed   letters-two     mixed = TRUE
//
// and `exercise` is the AWARD KEY, so it must be the row the child tapped —
// never a constant, or five ladders' stars land in one ledger key. The router
// (W15) reads `meta.spell` / `meta.mixed`; this screen never inspects the
// catalog itself.
//
//   - `mode` reaches `buildSpellSyllableRound` (how many syllables are blanked
//     and whether intruders join the tray) and picks the headline.
//   - `mixed` — the « écritures mêlées » twins — draws the whole word in ONE
//     random writing (GRANDE / petite / attachée) and makes the intruders the
//     same letters in the OTHER two writings. Judging is by FACE, so a right
//     letter in the wrong writing fails the row. It also SWAPS the headline,
//     « because matching the writing IS the task now ».
//
// Both of those live in `AssemblyModel.spellSyllable` and `Copy.Exercise`; this
// file only renders. What it owns: the cell row (written letters + gaps, each in
// the round's script font, with the per-syllable gap) and the tray tiles' glyphs
// and labels — as PURE FUNCTIONS on [SpellSyllableView], because a host test
// cannot invoke a composable (A11).
//
// Invariant 1: Tile → touchDown → TileInteraction.pointerDown → model.pick, all
// synchronous. The filled cells and the 🔊 pill are `onPointerDown` in the TSX
// too, hence `touchDown` and never `Modifier.clickable` (A12).
// ===========================================================================

/**
 * `AssemblyModel` as SpellSyllable instantiates it — the one engine whose slot
 * value is a [LetterFace] rather than a `String`, because the WRITING is part of
 * the answer in mixed rounds.
 */
typealias SpellSyllableModel = AssemblyModel<SyllableWord, SpellSyllableRound, LetterFace>

object SpellSyllableView {

    /* ---- authored metrics, from the TSX inline styles ------------------- */

    /** `<WordIcon size="clamp(48px,15vw,88px)" />` in a `margin: 2px 0` div. */
    val ICON_SIZE = FluidSpec(min = 48f, vw = 15f, max = 88f)
    val ICON_MARGIN_Y: Dp = 2.dp

    /**
     * A cell: `minWidth: clamp(40px,11vw,60px)`, `height: clamp(52px,14vw,72px)`,
     * `padding: 0 6px`, `fontSize: clamp(24px,7vw,42px)`, `borderRadius: 16`.
     *
     * NOT the SpellSound slot box: narrower, shorter, tighter radius — a whole
     * word has to fit on one line.
     */
    val CELL_MIN_WIDTH = FluidSpec(min = 40f, vw = 11f, max = 60f)
    val CELL_HEIGHT = FluidSpec(min = 52f, vw = 14f, max = 72f)
    val CELL_FONT_SIZE = FluidSpec(min = 24f, vw = 7f, max = 42f)
    val CELL_HORIZONTAL_PADDING: Dp = 6.dp
    val CELL_CORNER_RADIUS: Dp = 16.dp

    /**
     * `marginLeft: c.syllableStart && i > 0 ? "clamp(8px,2.5vw,16px)" : 0` —
     * « a small gap before each new syllable keeps the word's shape readable ».
     */
    val SYLLABLE_GAP = FluidSpec(min = 8f, vw = 2.5f, max = 16f)

    /** `<FitLine ariaLabel="Mot à compléter" className="mb-6" rowClassName="gap-1.5">`. */
    val ROW_SPACING: Dp = 6.dp
    val ROW_MARGIN_BOTTOM: Dp = 24.dp

    /** The listen button's `mb-4` — SpellSound authors `mb-5`. Not shared. */
    val LISTEN_MARGIN_BOTTOM: Dp = 16.dp

    /** `m-0 mb-1` on the headline `<p>`. */
    val HEADLINE_MARGIN_BOTTOM: Dp = 4.dp

    /** `px-4 pb-8 pt-2` on the content column. */
    val PADDING_TOP: Dp = 8.dp
    val PADDING_HORIZONTAL: Dp = 16.dp
    val PADDING_BOTTOM: Dp = 32.dp

    /** `relative z-[41]` — see [SpellSoundView.CONTENT_Z_INDEX] for why it is inert. */
    const val CONTENT_Z_INDEX: Float = 41f

    /* ---- the rules ------------------------------------------------------- */

    /**
     * What one [SpellCell] renders — the TSX's three branches, as data.
     *
     * Note what [CellRender.Filled] carries: the glyph and script of the DROPPED
     * TILE (`s.glyph`, `SCRIPT_FONT[s.script]`), never the cell's own — that is
     * the whole point of a mixed round, where a wrong-writing letter must stay
     * visible in the row it spoiled until the « Oh non » wipes it. And the remove
     * label names `s.base`, the canonical uppercase letter, not the glyph:
     * « Retirer A » for a lowercase cursive « a ».
     */
    sealed interface CellRender {

        /** `!c.fill` — already written, solid `#FFF3E0`, `aria-hidden`. */
        data class Written(val glyph: String, val script: LetterScript) : CellRender

        /** A gap nobody has filled yet: dashed, `aria-hidden`. */
        data object Empty : CellRender

        /** A gap holding a tile; tapping it sends the tile home. */
        data class Filled(
            val glyph: String,
            val script: LetterScript,
            val removeLabel: String,
        ) : CellRender
    }

    fun render(cell: SpellCell, slots: List<LetterFace?>): CellRender {
        if (!cell.fill) return CellRender.Written(glyph = cell.glyph, script = cell.script)
        // Defensive index guard — `slotIndex` is authored by
        // `buildSpellSyllableRound` and is always in range for a `fill` cell.
        // Reading out of bounds would crash a six-year-old's game; the TSX would
        // render an empty slot (`undefined`), and so does this.
        val face = slots.getOrNull(cell.slotIndex) ?: return CellRender.Empty
        return CellRender.Filled(
            glyph = face.glyph,
            script = face.script,
            removeLabel = Copy.Exercise.remove(face.base),
        )
    }

    /**
     * `marginLeft` applies to a syllable's first letter EXCEPT the word's very
     * first cell (`c.syllableStart && i > 0`).
     */
    fun startsNewSyllable(cell: SpellCell, index: Int): Boolean = cell.syllableStart && index > 0

    /**
     * A tray tile's screen-reader label — `faceLabel({ base, glyph, script })`,
     * which names the case and (cursive only) the form: « Lettre A minuscule
     * attachée ». The writing HAS to be spoken, because in a mixed round it is
     * the task.
     *
     * The face itself comes from the spine's [spellTileFace] — the same function
     * the pick handler and the judge see, so a label and a pick can never
     * describe different tiles.
     */
    fun trayLabel(tile: SpellLetterTile): String = faceLabel(spellTileFace(tile))
}

// --- The screen ---------------------------------------------------------------

/**
 * The five fill-a-syllable rows.
 *
 * @param exercise the hub row that opened this run — the award key. Five rows
 *   share this screen and each keeps its own ledger.
 * @param mode reaches `buildSpellSyllableRound` and the headline, nothing else.
 * @param mixed the « écritures mêlées » twin (`meta.mixed`). NO DEFAULT, exactly
 *   as `AssemblyModel.spellSyllable` has none: it is catalog data, and a default
 *   lets a mixed row be built plain by omission — which compiles, runs, and
 *   quietly plays a different game. (The TSX defaults it to `false`; it can
 *   afford to, because its one caller always passes `meta.mixed`.)
 * @param host the audio channel, the clock and `award` — the ONLY point source
 *   (invariant 8).
 */
@Composable
fun SpellSyllableScreen(
    exercise: ExerciseId,
    mode: SpellSyllableMode,
    level: Int,
    mixed: Boolean,
    mascot: MascotConfig,
    host: EngineHost,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    rng: RandomSource = SystemRandomSource(),
) {
    val scope = rememberCoroutineScope()
    val confetti = rememberConfettiSystem(reduceMotion, key = listOf(exercise, mode, mixed, level))
    // Seeded ONCE per entry (RoundRunner.kt's header) — never in the composable
    // body, which would replay a different word on every recomposition.
    //
    // FIVE catalog rows fan into this one composable, and two of them differ from
    // their twin ONLY by `mixed`. Keying on the whole tuple is what stops a
    // `spell-syllable-plus` model being handed to `spell-syllable-plus-mixed`:
    // that failure plays the wrong game AND banks its stars under the wrong
    // `ledgerKey`, and nothing about it looks broken on screen.
    val model = remember(exercise, mode, mixed, level) {
        AssemblyModel.spellSyllable(
            exercise = exercise,
            mode = mode,
            level = level,
            mixed = mixed,
            deps = host.deps(scope) { confetti.fire() },
            rng = rng,
        )
    }
    DisposableEffect(model) {
        model.activate()
        onDispose { model.deactivate() }
    }

    GameFrame(
        onBack = onBack,
        done = model.progressDone,
        total = model.totalRounds,
        stars = model.stars,
        reduceMotion = reduceMotion,
        modifier = modifier,
        overlay = { ConfettiOverlay(confetti, Modifier.matchParentSize()) },
    ) {
        if (model.done) {
            Finished(
                stars = model.stars,
                earned = model.earned,
                title = model.finishedTitle,
                reduceMotion = reduceMotion,
                onMenu = onBack,
                onNext = onNext,
            )
        } else {
            SpellSyllableRound(model = model, mascot = mascot, reduceMotion = reduceMotion)
        }
    }
}

@Composable
private fun ColumnScope.SpellSyllableRound(
    model: SpellSyllableModel,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val word = model.round.word

    Column(
        modifier = Modifier
            .zIndex(SpellSyllableView.CONTENT_Z_INDEX)
            .fillMaxWidth()
            .weight(1f)
            .padding(
                start = SpellSyllableView.PADDING_HORIZONTAL,
                end = SpellSyllableView.PADDING_HORIZONTAL,
                top = SpellSyllableView.PADDING_TOP,
                bottom = SpellSyllableView.PADDING_BOTTOM,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = model.headline,
            style = Typography.style(
                size = Typography.Size.base,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
            modifier = Modifier.padding(bottom = SpellSyllableView.HEADLINE_MARGIN_BOTTOM),
        )

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        WordIcon(
            emoji = word.emoji,
            img = word.img,
            size = SpellSyllableView.ICON_SIZE.resolve(viewport),
            modifier = Modifier.padding(vertical = SpellSyllableView.ICON_MARGIN_Y),
        )

        SpellListenPill(
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(bottom = SpellSyllableView.LISTEN_MARGIN_BOTTOM),
            onListen = { model.replayPrompt() },
        )

        SpellSyllableCellRow(
            model = model,
            modifier = Modifier.padding(bottom = SpellSyllableView.ROW_MARGIN_BOTTOM),
        )

        SpellSyllableTray(model)
    }
}

/**
 * « The word, letter by letter: written letters + dashed slots for the gap. »
 * One [FitLine], so the whole word always stays on one line however long it is.
 */
@Composable
private fun SpellSyllableCellRow(model: SpellSyllableModel, modifier: Modifier = Modifier) {
    val viewport = LocalViewportWidth.current
    val metrics = SpellSlotMetrics(
        minWidth = SpellSyllableView.CELL_MIN_WIDTH.resolve(viewport),
        height = SpellSyllableView.CELL_HEIGHT.resolve(viewport),
        horizontalPadding = SpellSyllableView.CELL_HORIZONTAL_PADDING,
        fontSize = SpellSyllableView.CELL_FONT_SIZE.resolve(viewport),
        cornerRadius = SpellSyllableView.CELL_CORNER_RADIUS,
    )
    val gap = SpellSyllableView.SYLLABLE_GAP.resolve(viewport)

    FitLine(
        modifier = modifier,
        rowSpacing = SpellSyllableView.ROW_SPACING,
        contentDescription = Copy.Exercise.WORD_TO_COMPLETE,
    ) {
        model.round.cells.forEachIndexed { index, cell ->
            val lead = Modifier.padding(
                start = if (SpellSyllableView.startsNewSyllable(cell, index)) gap else 0.dp,
            )
            when (val render = SpellSyllableView.render(cell, model.slots)) {
                is SpellSyllableView.CellRender.Written ->
                    // aria-hidden — the FitLine carries the row's label.
                    SpellSlotBox(
                        face = SpellSlotFace.REVEALED,
                        metrics = metrics,
                        modifier = lead.clearAndSetSemantics { },
                    ) {
                        SpellGlyph(render.glyph, Typography.letterFamily(render.script))
                    }

                is SpellSyllableView.CellRender.Empty ->
                    SpellSlotBox(
                        face = SpellSlotFace.EMPTY,
                        metrics = metrics,
                        modifier = lead.clearAndSetSemantics { },
                    ) {
                        SpellGlyph("")
                    }

                is SpellSyllableView.CellRender.Filled -> {
                    val label = render.removeLabel
                    val remove: () -> Unit = { model.removeAt(cell.slotIndex) }
                    SpellSlotBox(
                        face = SpellSlotFace.FILLED,
                        metrics = metrics,
                        modifier = lead
                            .touchDown { remove() }
                            .clearAndSetSemantics {
                                // Qualified: the receiver carries a property of
                                // the same name as the local.
                                this.contentDescription = label
                                this.role = Role.Button
                                onClick {
                                    remove()
                                    true
                                }
                            },
                    ) {
                        SpellGlyph(render.glyph, Typography.letterFamily(render.script))
                    }
                }
            }
        }
    }
}

/**
 * `<div className="flex flex-wrap items-center justify-center gap-3">` — the
 * same tray chrome as SpellSound, with the glyph drawn in the tile's OWN script
 * and the label naming its writing.
 */
@Composable
private fun SpellSyllableTray(model: SpellSyllableModel) {
    WrapRow(spacing = SpellTray.GAP) {
        model.round.tray.forEachIndexed { index, tile ->
            Tile(
                paint = SpellTray.paint(index),
                contentDescription = SpellSyllableView.trayLabel(tile),
                onPick = { model.pick(tile.id, spellTileFace(tile)) },
                disabled = model.isTrayTileUsed(tile.id),
                size = SpellTray.SIZE,
                fontSize = SpellTray.FONT_SIZE,
                glyphFamily = Typography.letterFamily(tile.script),
                // `void audio.say(t.letter)` — the tile SPEAKS its canonical
                // letter, never its glyph: a cursive « a » is still « A ».
                onPreview = { model.preview(tile.letter) },
                previewLabel = Copy.Exercise.listenTile(tile.letter),
            ) {
                SpellGlyph(tile.glyph, Typography.letterFamily(tile.script))
            }
        }
    }
}
