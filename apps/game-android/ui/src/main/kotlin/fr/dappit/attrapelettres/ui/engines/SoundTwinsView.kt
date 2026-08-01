package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.TwinTile
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography

// ===========================================================================
// `src/exercises/SoundTwinsExercise.tsx` — the app's ONE multi-select engine.
//
// Hear one sound (« Trouve tous les ko ! »), find EVERY tile that writes it:
// CO and KO stay, SO goes home. Each correct tap locks its tile into the
// collection strip and speaks that graphy's own anchor word (« Oui ! coq. »),
// so the feedback itself teaches which word owns which spelling.
//
// [TwinsModel] owns the verdict rules that make this machine different from the
// single-pick family: a PARTIAL find speaks fire-and-forget and leaves the round
// running — the next tap can land while the line plays — while the COMPLETING
// find locks the round and awaits its line. Partial finds never grey the star
// and never lock a tile the child still needs; only intruders grey it. None of
// that is re-expressed here.
//
// What this file owns: the collection strip (one slot per twin to find), the
// tile palette (the TRAY ramp, blue first — NOT the pick ramp), and the labels.
//
// Together with « Trouve le son », this is the drill that carries the consonants
// whose sound flips with the vowel (C, G, K, QU). They are deliberately kept out
// of the syllable grid and `:core`'s levels test guards it — which is why every
// tile, every intruder and every anchor word below comes from `buildTwinSession`
// and nothing is authored here (invariant 4).
// ===========================================================================

// --- Authored metrics (SoundTwinsExercise.tsx, verbatim) --------------------

object SoundTwinsMetrics {

    /**
     * This file's `TILE_COLORS` is the TRAY ramp — blue, green, gold, violet,
     * coral. A twins tile at index 0 is therefore `#4FC3F7`, where a find-sound
     * tile at index 0 is `#FF8A65`. The two lists must not be collapsed.
     */
    const val TILE_COLOR_COUNT = 5

    /** `TILE_COLORS[i % TILE_COLORS.length]`. */
    fun paint(index: Int): TilePaint = Palette.trayColors[index % TILE_COLOR_COUNT]

    // The listen button --------------------------------------------------------

    /**
     * `mt-2` / `mb-4` — twins sits the button tighter than the other two sound
     * engines (they use `mb-6`), because the collection strip follows it.
     */
    val LISTEN_TOP: Dp = 8.dp
    val LISTEN_BOTTOM: Dp = 16.dp

    /**
     * The button's text. [TwinsModel] exposes no `listenText` (only the
     * single-pick family varies it) and the TSX hard-codes « 🔊 Écouter ».
     */
    val LISTEN_TEXT: String = Copy.Exercise.LISTEN

    // The collection strip -----------------------------------------------------

    /** `mb-6` under the strip, `gap-2` between slots. */
    val STRIP_BOTTOM: Dp = 24.dp
    val STRIP_GAP: Dp = 8.dp

    /**
     * One slot: `minWidth: clamp(56px,16vw,84px)`,
     * `height: clamp(48px,13vw,64px)`, `fontSize: clamp(20px,5.5vw,32px)`.
     */
    val SLOT_MIN_WIDTH = FluidSpec(min = 56f, vw = 16f, max = 84f)
    val SLOT_HEIGHT = FluidSpec(min = 48f, vw = 13f, max = 64f)
    val SLOT_FONT_SIZE = FluidSpec(min = 20f, vw = 5.5f, max = 32f)

    /** `padding: "0 10px"`, `borderRadius: 18`, `gap-1` inside. */
    val SLOT_PADDING_X: Dp = 10.dp
    val SLOT_CORNER_RADIUS: Dp = 18.dp
    val SLOT_INNER_GAP: Dp = 4.dp

    /** `border: 3px dashed #E4A15E` while the slot is still empty. */
    val SLOT_BORDER_WIDTH: Dp = 3.dp

    /** `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` once the slot is filled. */
    val SLOT_SHADOW = CssShadow(y = 6.dp, blur = 14.dp, opacity = 0.12f)

    /**
     * `<span aria-hidden style={{ fontSize: "0.8em" }}>{tile.emoji}</span>` —
     * the anchor picture, relative to the slot's own font size.
     */
    const val SLOT_EMOJI_EM: Float = 0.8f

    /**
     * Raw dp in, raw dp out — ONE overload, deliberately. A `Dp` twin would
     * compile (Kotlin mangles a value-class parameter's JVM name) but it would
     * put two spellings of the same arithmetic in front of the next reader.
     */
    fun slotEmojiFontSize(base: Float): Float = base * SLOT_EMOJI_EM

    // The tiles ----------------------------------------------------------------

    /** `gap-3`. */
    val TILE_GAP: Dp = 12.dp

    /**
     * `size="clamp(60px,17vw,92px)"` — one point shy of the grid drill's 62, as
     * authored. Below `Tile`'s default 92 floor, and still far above the 44 dp
     * platform minimum at every viewport (pinned in the suite).
     */
    val TILE_SIZE = FluidSpec(min = 60f, vw = 17f, max = 92f)

    /** `fontSize="clamp(24px,6.5vw,44px)"`. */
    val TILE_FONT_SIZE = FluidSpec(min = 24f, vw = 6.5f, max = 44f)

    /**
     * ``ariaLabel={`Syllabe ${tile.text}`}`` — a twins tile is a graphy, and the
     * TSX labels it « Syllabe », not « Son » (find-sound's word for the same
     * kind of tile). Kept distinct on purpose.
     */
    fun tileLabel(tile: TwinTile): String = Copy.Exercise.syllableTile(tile.text)

    /** ``previewLabel={`Écouter ${tile.text}`}``. */
    fun previewLabel(tile: TwinTile): String = Copy.Exercise.listenTile(tile.text)

    /** `<Tile …>{tile.text}</Tile>`. */
    fun tileFace(tile: TwinTile): String = tile.text

    /**
     * `onPreview={() => audio.say(tile.sound)}` — THIS tile's own family's
     * sound, not the round's. An intruder auditions as itself (SO says « so »),
     * which is how a child can tell it out; speaking the round's target here
     * would make every tile sound correct and the drill unplayable by ear.
     */
    fun previewText(tile: TwinTile): String = tile.sound
}

// --- The view ---------------------------------------------------------------

/**
 * `SoundTwinsExercise`. One [TwinsModel], seeded once inside a `remember` — see
 * the note on [FindSoundView].
 */
@Composable
fun SoundTwinsView(
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
        TwinsModel(
            level = level,
            deps = SoundEngineChrome.wire(host, scope, confetti),
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
            SoundTwinsRoundContent(model, mascot, reduceMotion)
        }
    }
}

/** `flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`. */
@Composable
private fun ColumnScope.SoundTwinsRoundContent(
    model: TwinsModel,
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
        SoundEngineChrome.Consigne(model.headline)

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        SoundEngineChrome.ListenButton(
            text = SoundTwinsMetrics.LISTEN_TEXT,
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(
                top = SoundTwinsMetrics.LISTEN_TOP,
                bottom = SoundTwinsMetrics.LISTEN_BOTTOM,
            ),
            onListen = { model.replayPrompt() },
        )

        // The collection strip: one slot per twin to find, filled in TAP order,
        // so the child sees the family assemble — and how many are still hiding.
        // `targets.map((_, i) => …)`, keyed by the slot index.
        WrapRow(
            spacing = SoundTwinsMetrics.STRIP_GAP,
            modifier = Modifier.padding(bottom = SoundTwinsMetrics.STRIP_BOTTOM),
        ) {
            for (i in model.targets.indices) {
                CollectionSlot(tile = model.foundTile(i), viewport = viewport)
            }
        }

        // `flex flex-wrap items-center justify-center gap-3`, keyed by tile.id.
        WrapRow(spacing = SoundTwinsMetrics.TILE_GAP) {
            model.round.tiles.forEachIndexed { index, tile ->
                Tile(
                    paint = SoundTwinsMetrics.paint(index),
                    contentDescription = SoundTwinsMetrics.tileLabel(tile),
                    // Invariant 1: synchronous, inside the touch-down call.
                    onPick = { model.pick(tile) },
                    disabled = model.tileDisabled(tile),
                    highlight = model.tileHighlighted(tile),
                    size = SoundTwinsMetrics.TILE_SIZE,
                    fontSize = SoundTwinsMetrics.TILE_FONT_SIZE,
                    onPreview = { model.preview(SoundTwinsMetrics.previewText(tile)) },
                    previewLabel = SoundTwinsMetrics.previewLabel(tile),
                ) {
                    TileGlyph(SoundTwinsMetrics.tileFace(tile))
                }
            }
        }
    }
}

/**
 * One strip slot: a white card carrying the found graphy and its anchor
 * picture, or a dashed outline for a twin still hiding.
 */
@Composable
private fun CollectionSlot(tile: TwinTile?, viewport: Dp) {
    val font = SoundTwinsMetrics.SLOT_FONT_SIZE.resolve(viewport)
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(SoundTwinsMetrics.SLOT_CORNER_RADIUS)

    // Filled: `background: #FFFFFF`, no border, `0 6px 14px rgba(0,0,0,0.12)`.
    // Empty: `3px dashed #E4A15E` on transparent, no shadow.
    val skin = if (tile != null) {
        Modifier
            .cssShadow(SoundTwinsMetrics.SLOT_SHADOW, shape)
            .background(Color.White, shape)
    } else {
        // Parsed once per composition, not once per frame: `HexColor.color` runs
        // the hex through :art's parser on every read.
        val dashColor = Palette.slotDashed.color
        Modifier.drawBehind {
            val stroke = SoundTwinsMetrics.SLOT_BORDER_WIDTH.toPx()
            val intervals = SoundEngineChrome
                .dash(SoundTwinsMetrics.SLOT_BORDER_WIDTH)
                .map { it.toPx() }
                .toFloatArray()
            // A CSS border is painted INSIDE the border box, so the stroke is
            // inset by half its width rather than centred on the edge.
            drawRoundRect(
                color = dashColor,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(SoundTwinsMetrics.SLOT_CORNER_RADIUS.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(intervals)),
            )
        }
    }

    Box(
        modifier = skin
            .height(SoundTwinsMetrics.SLOT_HEIGHT.resolve(viewport))
            .defaultMinSize(minWidth = SoundTwinsMetrics.SLOT_MIN_WIDTH.resolve(viewport))
            .padding(horizontal = SoundTwinsMetrics.SLOT_PADDING_X),
        contentAlignment = Alignment.Center,
    ) {
        if (tile != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SoundTwinsMetrics.SLOT_INNER_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = tile.text,
                    style = Typography.style(
                        size = font,
                        weight = Typography.Weight.black,
                        color = Palette.ink.color,
                        fontScale = fontScale,
                    ),
                )
                BasicText(
                    text = tile.emoji,
                    // `aria-hidden` — the graphy beside it is what is announced.
                    modifier = Modifier.clearAndSetSemantics { },
                    style = Typography.style(
                        size = SoundTwinsMetrics.slotEmojiFontSize(font.value).dp,
                        weight = Typography.Weight.black,
                        color = Palette.ink.color,
                        fontScale = fontScale,
                    ),
                )
            }
        }
    }
}
