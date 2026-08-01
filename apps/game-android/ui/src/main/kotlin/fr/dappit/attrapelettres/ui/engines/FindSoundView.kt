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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.BasicSound
import fr.dappit.attrapelettres.core.domain.FindSoundRound
import fr.dappit.attrapelettres.core.domain.MascotConfig
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
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.components.WordIcon
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography
import kotlinx.coroutines.CoroutineScope

// ===========================================================================
// `src/exercises/FindSoundExercise.tsx` — hear a sound with its anchor
// (« ou, comme dans hibou »), tap the tile that WRITES it.
//
// The rules live in `SinglePickModel.findSound` (the pick loop, the cooldown
// swallow, the star greying, the announce timer, the award). This file is the
// PROJECTION: it reads state, hands the model a key at touch-down, and owns
// nothing but layout, palette and the two accessibility labels the model does
// not build.
//
// Invariant 1 — the pick path is `Tile` → `Modifier.touchDown` →
// `TileInteraction.pointerDown` → `model.pick(graphy)`, all synchronous.
// Nothing in this file defers a pick behind snapshot state, a coroutine or an
// animation.
//
// Invariant 5 — nothing here reads a profile, a clear count or a level lock.
// `level` is whatever the router passed; every level is playable, always.
//
// Invariant 6 — every tile carries a contentDescription built from `Copy`, and
// find-sound tiles keep `Tile`'s default `clamp(92,27vw,150)` side, which IS
// the tap-target floor.
//
// This file also carries [SoundEngineChrome], the chrome the THREE sound-ladder
// engines author identically in their TSX — the consigne line, the big 🔊
// replay button, the tile-row slots and the `EngineDeps` wiring. It lives here,
// not in a fourth file, for the same reason it lives in `FindSoundView.swift`
// on iOS: they are the same fifteen lines of JSX in all three files, and a
// divergence between three copies would be invisible. `SoundTwinsView` CONSUMES
// it and declares none of it. `SyllableGridView` was written beside this one,
// blind to it, and restates the same four paddings inside its own
// `SyllableGridMetrics`; folding that back is a job for whoever compiles the
// wave and can see both files, not for either file alone.
// ===========================================================================

// --- Chrome shared by FindSoundView, SyllableGridView and SoundTwinsView ----

/**
 * The parts of the three sound engines' JSX that are character-for-character
 * identical, plus the wiring their model setup needs.
 */
object SoundEngineChrome {

    // The content column:
    // `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`
    // — verbatim in all three files.

    /** `px-4`. */
    val COLUMN_PADDING_X: Dp = 16.dp

    /** `pt-2`. */
    val COLUMN_PADDING_TOP: Dp = 8.dp

    /** `pb-8`. */
    val COLUMN_PADDING_BOTTOM: Dp = 32.dp

    /**
     * `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`), i.e.
     * the exercise content paints IN FRONT of the burst.
     *
     * Recorded, not applied. `GameFrame` puts its header and its content slot
     * in ONE column carrying `FrameLayer.FLOW.z` (= 41) above the overlay box,
     * so the exercise column inherits exactly this value and a second
     * `Modifier.zIndex` here would be a duplicate spelling of the same number.
     * iOS had to restate it per engine because its `GameFrame` is a `ZStack`.
     */
    const val CONTENT_Z_INDEX: Float = 41f

    /** `mb-1` under the consigne line. */
    val CONSIGNE_BOTTOM: Dp = 4.dp

    /** `px-5` / `py-2` on the replay pill — `ListenPill` already owns both. */
    val LISTEN_PADDING_X: Dp = ListenPillMetrics.paddingX
    val LISTEN_PADDING_Y: Dp = ListenPillMetrics.paddingY

    /**
     * CSS `border-style: dashed`, in dp. The rhythm is UA-defined; WebKit draws
     * roughly square dashes at about twice the border width, which is what this
     * approximates. The exact rhythm is pixel-diff territory, not a rule.
     */
    fun dash(width: Dp): List<Dp> = listOf(width * 2f, width * 2f)

    /**
     * One tile's place in a row: the INDEX drives `TILE_COLORS[i % n]` and the
     * ID is the TSX `key`. A separate type because the pairing of the two is
     * worth testing once instead of three times.
     */
    data class Slot<Value>(val id: String, val index: Int, val value: Value)

    /** `round.choices.map((choice, i) => …)` with `key={id(choice)}`. */
    fun <Value> slots(values: List<Value>, id: (Value) -> String): List<Slot<Value>> =
        values.mapIndexed { index, value -> Slot(id = id(value), index = index, value = value) }

    /**
     * `useConfetti()` is called ONCE per exercise component, and its `fire` is
     * what the pick handler calls. So a run's own [ConfettiSystem] is what
     * reaches the model — the caller cannot know this run's system, and a stale
     * one would burst into the previous exercise's overlay. [EngineHost]
     * therefore has no `fireConfetti` member at all, and this completes it
     * alongside the composition's own [CoroutineScope]: every awaited voice
     * line and every pending announce dies with the screen.
     */
    fun wire(host: EngineHost, scope: CoroutineScope, confetti: ConfettiSystem): EngineDeps =
        host.deps(scope = scope, fireConfetti = { confetti.fire() })

    /**
     * The line above the mascot —
     * `<p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">`. The string
     * is the model's ([RoundRunner.headline]); this only dresses it.
     */
    @Composable
    fun Consigne(text: String, modifier: Modifier = Modifier) {
        BasicText(
            text = text,
            modifier = modifier.padding(bottom = CONSIGNE_BOTTOM),
            style = Typography.style(
                size = Typography.Size.base,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = LocalDensity.current.fontScale,
            ).copy(textAlign = TextAlign.Center),
        )
    }

    /**
     * The « 🔊 Écouter » button under the mascot. It speaks on POINTER-DOWN
     * (`ListenPill`'s `touchDown`, never a click), and the model's
     * `replayPrompt()` carries the `locked` guard that stops it cutting a
     * success line.
     */
    @Composable
    fun ListenButton(
        text: String,
        contentDescription: String,
        modifier: Modifier = Modifier,
        onListen: () -> Unit,
    ) {
        ListenPill(
            text = text,
            contentDescription = contentDescription,
            modifier = modifier,
            onListen = onListen,
        )
    }
}

// --- Authored metrics (FindSoundExercise.tsx, verbatim) ---------------------

object FindSoundMetrics {

    /**
     * `TILE_COLORS` in this file is the first THREE of the shared ramp, and the
     * tiles index it `i % 3` — so a fourth tile would wear the first colour
     * again. `FIND_SOUND_LEVELS` never asks for more than three (1 + at most 2
     * distractors), but the modulo is what the TSX wrote.
     */
    const val TILE_COLOR_COUNT = 3

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = Palette.tileColors[index % TILE_COLOR_COUNT]

    /** `<WordIcon size="clamp(80px,28vw,150px)" />`. */
    val WORD_ICON_SIZE = FluidSpec(min = 80f, vw = 28f, max = 150f)

    /** `style={{ margin: "6px 0" }}` on the WordIcon's wrapper. */
    val WORD_ICON_MARGIN_Y: Dp = 6.dp

    /** `mb-6` on the listen button. */
    val LISTEN_BOTTOM: Dp = 24.dp

    /** `gap-4` between tiles. */
    val TILE_GAP: Dp = 16.dp

    // Shown vs. judged vs. spoken ---------------------------------------------

    /** `<Tile …>{choice.graphy}</Tile>` — the tile shows the GRAPHY, uppercase. */
    fun tileFace(choice: BasicSound): String = choice.graphy

    /** `onPick={() => pick(choice.graphy)}` — the judge and the flash key. */
    fun pickKey(choice: BasicSound): String = choice.graphy

    /**
     * `onPreview={() => audio.say(choice.sound)}` — the tile speaks its SOUND
     * (« ou »), never the graphy it shows (« OU »). Two different strings, and
     * only one of them has a baked clip for this use.
     */
    fun previewText(choice: BasicSound): String = choice.sound

    /**
     * ``ariaLabel={`Son ${choice.graphy}`}`` — « Son », where the grid and
     * twins drills say « Syllabe ». Kept distinct, as authored.
     */
    fun tileLabel(choice: BasicSound): String = Copy.Exercise.soundTile(choice.graphy)

    /** ``previewLabel={`Écouter ${choice.graphy}`}``. */
    fun previewLabel(choice: BasicSound): String = Copy.Exercise.listenTile(choice.graphy)
}

// --- The view ---------------------------------------------------------------

/**
 * `FindSoundExercise`.
 *
 * The model is built ONCE, in a `remember` keyed on [level]: its constructor
 * seeds the whole run through `buildFindSoundSession`, so building it in the
 * composable body would re-seed — different words, silently — on every
 * recomposition. The router should still `key(exercise, level)` around the
 * screen; the key here is the belt to that pair of braces.
 *
 * @param host the audio channel, the clock, `award` (which MUST be
 *   `ProfileStore::award` — invariant 8) and the announce sleep. The run's
 *   confetti and the composition's scope are completed here.
 * @param mascot the TSX `profile.config`.
 * @param reduceMotion gates the mascot and the confetti, and nothing else
 *   (invariant 6).
 */
@Composable
fun FindSoundView(
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
    val confetti = rememberConfettiSystem(reduceMotion = reduceMotion, key = level)
    val model = remember(level) {
        SinglePickModel.findSound(
            level = level,
            deps = SoundEngineChrome.wire(host, scope, confetti),
            rng = rng,
        )
    }

    // The TSX mount/unmount effects: unlock + the 350 ms announce on the way in,
    // cancel + fade + freeze on the way out.
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
            FindSoundRoundContent(model, mascot, reduceMotion)
        }
    }
}

/** `flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`. */
@Composable
private fun ColumnScope.FindSoundRoundContent(
    model: SinglePickModel<FindSoundRound>,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(
                start = SoundEngineChrome.COLUMN_PADDING_X,
                end = SoundEngineChrome.COLUMN_PADDING_X,
                top = SoundEngineChrome.COLUMN_PADDING_TOP,
                bottom = SoundEngineChrome.COLUMN_PADDING_BOTTOM,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        model.headline?.let { SoundEngineChrome.Consigne(it) }

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        WordIcon(
            emoji = model.current.target.emoji,
            size = FindSoundMetrics.WORD_ICON_SIZE.resolve(viewport),
            modifier = Modifier.padding(vertical = FindSoundMetrics.WORD_ICON_MARGIN_Y),
        )

        SoundEngineChrome.ListenButton(
            text = model.listenText,
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(bottom = FindSoundMetrics.LISTEN_BOTTOM),
            onListen = { model.replayPrompt() },
        )

        // `flex flex-wrap items-center justify-center gap-4`.
        // `key={choice.graphy}` + the row index that drives `TILE_COLORS[i % 3]`.
        val slots = SoundEngineChrome.slots(model.current.choices) { it.graphy }
        WrapRow(spacing = FindSoundMetrics.TILE_GAP) {
            for (slot in slots) {
                val choice = slot.value
                val key = FindSoundMetrics.pickKey(choice)
                Tile(
                    paint = FindSoundMetrics.paint(slot.index),
                    contentDescription = FindSoundMetrics.tileLabel(choice),
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { model.pick(key) },
                    disabled = model.tilesDisabled,
                    highlight = model.flash == key,
                    onPreview = { model.preview(FindSoundMetrics.previewText(choice)) },
                    previewLabel = FindSoundMetrics.previewLabel(choice),
                ) {
                    TileGlyph(FindSoundMetrics.tileFace(choice))
                }
            }
        }
    }
}
