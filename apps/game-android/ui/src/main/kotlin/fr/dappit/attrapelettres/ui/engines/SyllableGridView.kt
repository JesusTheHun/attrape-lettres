package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.GridRound
import fr.dappit.attrapelettres.core.domain.GridSyllable
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.ListenPill
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography

// ===========================================================================
// `src/exercises/SyllableGridExercise.tsx` — ONE engine over the « tableau des
// syllabes », two ways round:
//
//   hear   the syllable is spoken, the tiles WRITE it (VA VE VI VO VU VÉ).
//   vowel  the syllable is spoken, its consonant is already written, and the
//          tiles are the vowels: the child places the one that finishes it.
//
// ONE view, as CLAUDE.md requires. The mode changes the distractor rule (which
// is :core's `buildSyllableGridSession` job, not this file's) and WHAT A TILE
// SHOWS. It never changes the loop, and `vowel` mode is not a second engine —
// it adds the half-written syllable above the listen button and swaps the
// tile's glyph.
//
// This is the consonant × vowel rung that comes BEFORE any word exercise, so
// it has to feel simple: one prompt, one tap, every tile auditionable. Hearing
// VA next to VI is how the contrast is learnt, which is why a `vowel`-mode tile
// still auditions the WHOLE syllable and not its bare vowel.
//
// THE TRAP THIS FILE EXISTS TO MAKE IMPOSSIBLE: a `vowel`-mode tile SHOWS « A »
// but is judged, labelled and flashed by « VA ». Shown face and pick key are two
// different functions in [SyllableGridMetrics], and both are tested.
//
// NO RULE LIVES HERE. The session, the judge, the cooldown swallow, the star
// greying, the announce and the award are all `SinglePickModel.syllableGrid`
// (see RoundRunner.kt). What this file holds is the layout and the data rules —
// which paint a tile at index i wears, what it shows, what it says — and those
// are extracted into [SyllableGridMetrics] so a host JUnit run can reach them
// without a composition (A11).
//
// Invariant 1: the pick path is `Tile` → `touchDown` → `model.pick(key)`,
// synchronously. Nothing here defers a pick behind state, a coroutine or an
// animation.
//
// WHY THIS FILE CARRIES ITS OWN COLUMN CHROME. iOS put the shared consigne /
// listen / column metrics of the three sound-family engines in
// `FindSoundView.swift`'s `SoundEngineChrome`. This wave writes the nine screens
// in parallel, one agent per file, so reaching into a sibling file's namespace
// would be either an unresolved reference or a duplicate declaration depending
// on who lands first. The numbers below are the same Tailwind classes, resolved,
// and they are named for THIS engine; folding them together is a job for
// whoever compiles the wave, not for a file that cannot see its siblings.
// ===========================================================================

/** The authored metrics and data rules — `SyllableGridExercise.tsx`, verbatim. */
object SyllableGridMetrics {

    /* ---- The content column ---------------------------------------------
       `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`
       — identical in all nine engines.                                      */

    /** `px-4`. */
    val columnPaddingX: Dp = 16.dp

    /** `pt-2`. */
    val columnPaddingTop: Dp = 8.dp

    /** `pb-8`. */
    val columnPaddingBottom: Dp = 32.dp

    /* ---- The consigne ----------------------------------------------------
       `<p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">`          */

    /** `mb-1`. */
    val consigneBottom: Dp = 4.dp

    /* ---- The tiles ------------------------------------------------------- */

    /**
     * This file's own `TILE_COLORS` — four paints, and the fourth green is NOT
     * the shared ramp's (`#A5D6A7`/`#123B18`, not `#AED581`/`#213606`).
     * `Palette.gridTileColors` is that authored exception.
     */
    const val TILE_COLOR_COUNT = 4

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = Palette.gridTileColors[index % TILE_COLOR_COUNT]

    /** `gap-3`. */
    val tileGap: Dp = 12.dp

    /** `size="clamp(62px,17vw,96px)"` — smaller than `Tile`'s default, because
     *  level 4+ puts six of them on one line. Still well over the 44 dp floor
     *  at every viewport (invariant 6), which `SyllableGridViewTest` pins. */
    val tileSize = FluidSpec(min = 62f, vw = 17f, max = 96f)

    /** `fontSize="clamp(26px,7vw,46px)"`. */
    val tileFontSize = FluidSpec(min = 26f, vw = 7f, max = 46f)

    /** `{mode === "vowel" ? choice.vowel : choice.text}` — what the tile SHOWS. */
    fun tileFace(choice: GridSyllable, mode: SyllableGridMode): String =
        if (mode == SyllableGridMode.VOWEL) choice.vowel else choice.text

    /**
     * `onPick={() => pick(choice.text)}` — the pick key, the flash key and the
     * judge, in BOTH modes. Never the shown face: in `vowel` mode a tile reads
     * « A » and picks « VA ».
     */
    fun pickKey(choice: GridSyllable): String = choice.text

    /**
     * `ariaLabel={`Syllabe ${choice.text}`}` — the FULL syllable in both modes,
     * so a TalkBack user of `vowel` mode hears « Syllabe VA », not « Syllabe A ».
     */
    fun tileLabel(choice: GridSyllable): String = Copy.Exercise.syllableTile(choice.text)

    /** `previewLabel={`Écouter ${choice.text}`}`. */
    fun previewLabel(choice: GridSyllable): String = Copy.Exercise.listenTile(choice.text)

    /**
     * `onPreview={() => audio.say(choice.sound)}` — the lowercase spoken form of
     * the WHOLE syllable, in both modes. Hearing VA next to VI is the point of
     * the drill, so a `vowel`-mode tile does NOT audition its bare vowel.
     */
    fun previewText(choice: GridSyllable): String = choice.sound

    /* ---- The listen button ----------------------------------------------- */

    /** `mt-3` on the listen button. */
    val listenTop: Dp = 12.dp

    /** `mb-6` on the listen button. */
    val listenBottom: Dp = 24.dp

    /* ---- The half-written syllable (`vowel` mode only) -------------------- */

    /** `style={{ fontSize: "clamp(38px,11vw,64px)" }}` on the whole group. */
    val syllableFontSize = FluidSpec(min = 38f, vw = 11f, max = 64f)

    /** `mt-2` on the group. */
    val halfSyllableTop: Dp = 8.dp

    /** `gap-2` between the consonant and the gap. */
    val halfSyllableGap: Dp = 8.dp

    /**
     * The gap box, in `em` of the group's font size: `minWidth: 0.9em`,
     * `height: 1.1em`, `padding: 0 0.1em`.
     */
    const val GAP_MIN_WIDTH_EM = 0.9f
    const val GAP_HEIGHT_EM = 1.1f
    const val GAP_PADDING_X_EM = 0.1f

    /** `borderRadius: 16`. */
    val gapCornerRadius: Dp = 16.dp

    /** `border: 4px dashed #E4A15E` while empty. */
    val gapBorderWidth: Dp = 4.dp

    /** `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` once filled. */
    val gapFilledShadow = CssShadow(y = 6.dp, blur = 14.dp, opacity = 0.12f)

    /**
     * What the gap shows and how it is dressed.
     *
     * The TSX is `flash ? … : …` — TRUTHINESS of the flash key, not a comparison
     * with the target. This port compares (iOS D45), which today is EXACTLY
     * EQUIVALENT, and that equivalence is the reason the change is safe rather
     * than the reason it is pointless: `SinglePickModel.pick` assigns `flash`
     * only AFTER the `key != targetKey(round)` early return, so `flash` is
     * non-null if and only if the pick was correct, and then it IS the target's
     * text.
     *
     * What comparing buys is that it stays true. If `flash` is ever set on a
     * wrong pick (to highlight what the child tapped, say) the truthy form
     * silently starts showing the ANSWER on a miss, in a game whose invariant 3
     * is that a wrong tap costs nothing and reveals nothing.
     */
    data class Gap(
        /** `{flash ? round.target.vowel : ""}` — filled only for the target. */
        val text: String,
        /**
         * `background: flash ? "#FFFFFF" : "transparent"`; the dashed border and
         * the drop shadow follow the same flag.
         */
        val filled: Boolean,
    )

    /** The gap span of `SyllableGridExercise.tsx`, as data. */
    fun gap(target: GridSyllable, flash: String?): Gap =
        if (flash == target.text) Gap(text = target.vowel, filled = true) else Gap("", false)

    /**
     * `aria-label={`Syllabe à compléter : ${round.target.consonant}`}` on the
     * GROUP (the gap span itself is `aria-hidden`), so a screen reader announces
     * the consonant and the task, never an empty box.
     */
    fun halfSyllableLabel(target: GridSyllable): String =
        Copy.Exercise.syllableToComplete(target.consonant)

    /**
     * `border-style: dashed`. The rhythm is UA-defined; WebKit draws roughly
     * square dashes at about twice the border width, which is what this
     * approximates. The exact rhythm is a pixel-diff question, not a rule.
     */
    fun dash(width: Float): FloatArray = floatArrayOf(width * 2f, width * 2f)
}

/**
 * `SyllableGridExercise`. [exercise] and [mode] are the TSX props: the hub
 * passes `ExerciseMeta.grid` — `HEAR` for « Écoute la syllabe », `VOWEL` for
 * « La bonne voyelle ».
 *
 * @param host the audio channel, the clock, `award` and the announce sleep. The
 *   run's confetti and this composition's scope are completed here, because only
 *   the screen can supply them.
 * @param mascot the TSX `profile.config`.
 * @param reduceMotion gates the mascot and the confetti, never press/shake.
 * @param rng injected so a test (and a preview) can replay a session.
 */
@Composable
fun SyllableGridView(
    exercise: ExerciseId,
    mode: SyllableGridMode,
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
    val confetti = rememberConfettiSystem(reduceMotion, key = listOf(exercise, mode, level))
    // Seeded EXACTLY ONCE per entry (iOS D9): `remember` and never a bare call
    // in the body, which would rebuild the session on every recomposition and
    // silently replay different syllables.
    val model = remember(level, mode, exercise) {
        SinglePickModel.syllableGrid(
            exercise = exercise,
            mode = mode,
            level = level,
            deps = host.deps(scope = scope, fireConfetti = confetti::fire),
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
                modifier = Modifier.weight(1f),
            )
        } else {
            SyllableGridRound(
                model = model,
                mode = mode,
                mascot = mascot,
                reduceMotion = reduceMotion,
            )
        }
    }
}

@Composable
private fun ColumnScope.SyllableGridRound(
    model: SinglePickModel<GridRound>,
    mode: SyllableGridMode,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(
                start = SyllableGridMetrics.columnPaddingX,
                end = SyllableGridMetrics.columnPaddingX,
                top = SyllableGridMetrics.columnPaddingTop,
                bottom = SyllableGridMetrics.columnPaddingBottom,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // `<p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">` — the
        // consigne is the model's `headline` (GRID_PROMPT[mode]); this dresses it.
        model.headline?.let { line ->
            Text(
                text = line,
                style = Typography.style(
                    size = Typography.Size.base,
                    weight = Typography.Weight.bold,
                    color = Palette.inkSoft.color,
                    fontScale = fontScale,
                ),
                modifier = Modifier.padding(bottom = SyllableGridMetrics.consigneBottom),
            )
        }

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        // `{mode === "vowel" && …}` — the syllable half-written, so the whole
        // thing is read once, complete, before moving on.
        if (mode == SyllableGridMode.VOWEL) {
            HalfSyllable(
                target = model.current.target,
                flash = model.flash,
                size = SyllableGridMetrics.syllableFontSize.resolve(viewport.value),
                fontScale = fontScale,
                modifier = Modifier.padding(top = SyllableGridMetrics.halfSyllableTop),
            )
        }

        ListenPill(
            text = model.listenText,
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(
                top = SyllableGridMetrics.listenTop,
                bottom = SyllableGridMetrics.listenBottom,
            ),
            onListen = model::replayPrompt,
        )

        // `flex flex-wrap items-center justify-center gap-3`.
        WrapRow(spacing = SyllableGridMetrics.tileGap) {
            model.current.choices.forEachIndexed { index, choice ->
                val key = SyllableGridMetrics.pickKey(choice)
                Tile(
                    paint = SyllableGridMetrics.paint(index),
                    contentDescription = SyllableGridMetrics.tileLabel(choice),
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { model.pick(key) },
                    disabled = model.tilesDisabled,
                    highlight = model.flash == key,
                    size = SyllableGridMetrics.tileSize,
                    fontSize = SyllableGridMetrics.tileFontSize,
                    onPreview = { model.preview(SyllableGridMetrics.previewText(choice)) },
                    previewLabel = SyllableGridMetrics.previewLabel(choice),
                ) {
                    TileGlyph(SyllableGridMetrics.tileFace(choice, mode))
                }
            }
        }
    }
}

/**
 * The consonant + the gap the child fills. The label is on the GROUP and the
 * children are cleared out of the semantics tree, which is the TSX's
 * `aria-label` on the row plus `aria-hidden` on the box: a screen reader says
 * « Syllabe à compléter : V » and nothing else.
 */
@Composable
private fun HalfSyllable(
    target: GridSyllable,
    flash: String?,
    size: Float,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val gap = SyllableGridMetrics.gap(target, flash)
    val label = SyllableGridMetrics.halfSyllableLabel(target)
    val style = Typography.style(
        size = size.dp,
        weight = Typography.Weight.black,
        color = Palette.ink.color,
        fontScale = fontScale,
    )
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(SyllableGridMetrics.halfSyllableGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = target.consonant, style = style)
        GapBox(gap = gap, size = size, style = style)
    }
}

@Composable
private fun GapBox(gap: SyllableGridMetrics.Gap, size: Float, style: TextStyle) {
    val shape = RoundedCornerShape(SyllableGridMetrics.gapCornerRadius)
    // `box-sizing: border-box` (Tailwind preflight): the authored minWidth and
    // height INCLUDE the padding and the border, which is what putting the
    // padding inside the size constraints gives us.
    val dressed = if (gap.filled) {
        Modifier
            .cssShadow(listOf(SyllableGridMetrics.gapFilledShadow), shape)
            .background(Color.White, shape)
    } else {
        Modifier.drawBehind {
            val stroke = SyllableGridMetrics.gapBorderWidth.toPx()
            if (this.size.width <= stroke || this.size.height <= stroke) return@drawBehind
            drawRoundRect(
                color = Palette.slotDashed.color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                cornerRadius = CornerRadius(SyllableGridMetrics.gapCornerRadius.toPx()),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(SyllableGridMetrics.dash(stroke)),
                ),
            )
        }
    }
    Box(
        modifier = dressed
            .height((size * SyllableGridMetrics.GAP_HEIGHT_EM).dp)
            .defaultMinSize(minWidth = (size * SyllableGridMetrics.GAP_MIN_WIDTH_EM).dp)
            .padding(horizontal = (size * SyllableGridMetrics.GAP_PADDING_X_EM).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = gap.text, style = style)
    }
}
