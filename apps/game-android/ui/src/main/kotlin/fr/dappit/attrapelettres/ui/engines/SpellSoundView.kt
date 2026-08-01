package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.SoundRound
import fr.dappit.attrapelettres.core.domain.SoundTarget
import fr.dappit.attrapelettres.core.domain.SoundTile
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.FitLine
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.ListenPill
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown

// ===========================================================================
// `src/exercises/SpellSoundExercise.tsx` — hear a sound, re-spell it with
// letter tiles. (Worked example: `apps/game-ios/Sources/ALUI/Engines/
// SpellSoundView.swift`.)
//
// « Same forgiving loop as AssembleExercise (feedback on pointerdown, WAAPI
// shake, canvas confetti), but the tiles are LETTERS and the target is a
// grapheme. The upper levels reuse a sound across several spellings so the
// child learns o / au / eau, f / ph, …. »
//
// THE VIEW HOLDS NO RULE. Every one of them lives in `AssemblyModel.spellSound`
// (the session, the round, the whole-row judgement, the « Oh non » pacing, the
// award) — this file reads that model's state and calls its handlers. What it
// owns is the plumbing the model deliberately does not: which glyph goes on a
// tile, which label a slot carries, and the authored metrics of the row and the
// tray. Those decisions are PURE FUNCTIONS on [SpellSoundView], because a host
// test cannot invoke a composable (A11) and an untested decision is an
// untested screen.
//
// Invariant 1 lives in the pick path and nowhere else:
//   Tile → touchDown → TileInteraction.pointerDown → model.pick(tileId, value)
// all synchronous, all inside the finger-down turn. Same for the filled slot's
// `removeAt` and the big 🔊 pill's `replayPrompt` (both `onPointerDown` in the
// TSX, so both `touchDown` here — never `Modifier.clickable`, which fires on
// lift, behind the ripple; A12).
//
// NAMING, once for the wave: `object SpellSoundView` carries the metrics and
// the pure rules (the audit surface against the TSX, and what the tests drive);
// `@Composable fun SpellSoundScreen` is the renderer. Kotlin would tolerate one
// name for both — an object is not invokable — but a call that resolves to a
// function while every reader sees a type is not worth the symmetry with Swift.
//
// THIS FILE ALSO OWNS THE CHROME THE TWO SPELLING ENGINES SHARE (the tray, the
// slot box, the listen pill), because they are shared by exactly two files and
// a third copy is how the web's eight identical listen buttons became four
// divergent Swift ones.
// ===========================================================================

/** `AssemblyModel` as SpellSound instantiates it. */
typealias SpellSoundModel = AssemblyModel<SoundTarget, SoundRound, String>

// --- Chrome shared by the two spelling engines -------------------------------

/**
 * The letter tray, identical in `SpellSoundExercise.tsx` and
 * `SpellSyllableExercise.tsx`: the same five `TRAY_COLORS`, the same `size` /
 * `fontSize` clamps, the same `gap-3` wrap row.
 */
object SpellTray {

    /**
     * `TRAY_COLORS[i % TRAY_COLORS.length]`, `i` = the tile's position in the
     * tray. `Palette.trayColors` is that table (blue first) — deliberately NOT
     * `Palette.tileColors`, which is the same five paints in a different
     * rotation.
     */
    fun paint(index: Int): TilePaint {
        val count = Palette.trayColors.size
        return Palette.trayColors[((index % count) + count) % count]
    }

    /**
     * `size="clamp(60px,17vw,92px)"` — the 60 dp floor is below `TileMetrics`'
     * 92 dp default but is what both TSX files author; the tray is a row of
     * many. It still clears `TileMetrics.PLATFORM_MINIMUM_TAP_TARGET`.
     */
    val SIZE = FluidSpec(min = 60f, vw = 17f, max = 92f)

    /** `fontSize="clamp(26px,7vw,48px)"`. */
    val FONT_SIZE = FluidSpec(min = 26f, vw = 7f, max = 48f)

    /** `gap-3` on the wrapping tray row. */
    val GAP: Dp = 12.dp
}

/** How a slot box is painted. The three faces the two spelling engines draw. */
enum class SpellSlotFace {
    /** `background: transparent; border: 3px dashed #E4A15E; boxShadow: none`. */
    EMPTY,

    /** `background: #FFFFFF; border: none; boxShadow: 0 6px 14px rgba(0,0,0,.12)`. */
    FILLED,

    /**
     * SpellSyllable's already-written letter: `background: #FFF3E0`, no border,
     * no shadow.
     */
    REVEALED,
}

/** One slot box's authored geometry (the TSX inline `style` object). */
data class SpellSlotMetrics(
    val minWidth: Dp,
    val height: Dp,
    val horizontalPadding: Dp,
    val fontSize: Dp,
    val cornerRadius: Dp,
)

object SpellSlot {

    /** `border: 3px dashed`. */
    val BORDER_WIDTH: Dp = 3.dp

    /**
     * CSS `border-style: dashed` has no specified dash length. WebKit's
     * `GraphicsContext` uses `patternWidth = 3 * width` for both the dash and
     * the gap (`width` for `dotted`), so a 3 dp dashed border draws 9 on / 9
     * off. Reproduced here as a constant; WebKit additionally nudges the phase
     * so a whole number of dashes fits each side, which `PathEffect` cannot
     * express — a sub-pixel difference nothing but a pixel diff will show.
     */
    val DASH: List<Dp> = listOf(BORDER_WIDTH * 3f, BORDER_WIDTH * 3f)

    /**
     * `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` on a filled slot.
     *
     * Kept as the authored CSS triple and drawn by [cssShadow], which halves the
     * blur (CSS defines it as twice the Gaussian sigma) and clips the border box
     * out — so the white slot never shows the shadow through itself. iOS had to
     * write the halved radius by hand and add `.compositingGroup()` so the
     * GLYPH did not cast its own shadow; neither is needed here.
     */
    val FILLED_SHADOW = CssShadow(y = 6.dp, blur = 14.dp, opacity = 0.12f)
}

/**
 * A spelling slot / written cell. Pure chrome: it draws the box and hosts a
 * glyph, and knows nothing about rounds.
 *
 * [modifier] is applied FIRST in the chain, so a caller's `touchDown` covers the
 * whole box rather than the area inside its padding (the same rule `Tile`
 * follows, and the reason a filled slot is a comfortable target).
 */
@Composable
fun SpellSlotBox(
    face: SpellSlotFace,
    metrics: SpellSlotMetrics,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(metrics.cornerRadius)
    val dashed = Palette.slotDashed.color
    val fill = when (face) {
        SpellSlotFace.EMPTY -> Color.Transparent
        SpellSlotFace.FILLED -> Color.White
        SpellSlotFace.REVEALED -> Palette.slotRevealed.color
    }

    Box(
        modifier = modifier
            .then(
                if (face == SpellSlotFace.FILLED) {
                    Modifier.cssShadow(SpellSlot.FILLED_SHADOW, shape)
                } else {
                    Modifier
                },
            )
            .background(color = fill, shape = shape)
            .drawBehind {
                if (face != SpellSlotFace.EMPTY) return@drawBehind
                // A CSS border is drawn INSIDE the border box; a Compose stroke
                // straddles the path, so the rect is inset by half the width.
                val stroke = SpellSlot.BORDER_WIDTH.toPx()
                val inset = stroke / 2f
                val dash = SpellSlot.DASH.map { it.toPx() }.toFloatArray()
                drawRoundRect(
                    color = dashed,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(
                        (metrics.cornerRadius.toPx() - inset).coerceAtLeast(0f),
                    ),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(dash),
                    ),
                )
            }
            .height(metrics.height)
            .defaultMinSize(minWidth = metrics.minWidth)
            .padding(horizontal = metrics.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        // `font-black`, `color: #5A3A1E`. A caller that needs a different FAMILY
        // (SpellSyllable's script font) merges it onto this style — see
        // [SpellGlyph].
        CompositionLocalProvider(
            LocalTextStyle provides Typography.style(
                size = metrics.fontSize,
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
        ) {
            content()
        }
    }
}

/**
 * A glyph inside a [SpellSlotBox] or a tray [Tile], in the writing the round
 * asks for. [family] defaults to the app face, which is what SpellSound (always
 * uppercase print) wants.
 */
@Composable
fun SpellGlyph(text: String, family: FontFamily = Typography.appFamily) {
    BasicText(
        text = text,
        style = LocalTextStyle.current.copy(fontFamily = family),
        maxLines = 1,
    )
}

/**
 * The big « 🔊 Écouter » pill under the mascot, shared by both spelling engines
 * (they differ only in the label and the margin below).
 *
 * `onPointerDown`, so `touchDown` — that lives inside [ListenPill]. The spelling
 * engines differ from the rest only in showing the bare « 🔊 Écouter » rather
 * than the prompt. The `locked` guard that stops a replay cutting a success line
 * belongs to the model (`replayPrompt`), not here.
 */
@Composable
fun SpellListenPill(
    contentDescription: String,
    modifier: Modifier = Modifier,
    onListen: () -> Unit,
) {
    ListenPill(
        text = Copy.Exercise.LISTEN,
        contentDescription = contentDescription,
        modifier = modifier,
        onListen = onListen,
    )
}

// --- The rules and metrics this file owns (pure, host-tested) ----------------

object SpellSoundView {

    /* ---- authored metrics, from the TSX inline styles ------------------- */

    /**
     * `{target.emoji ?? "🎧"}` — the headphones stand in when the sound has no
     * anchor word to picture.
     */
    const val FALLBACK_EMOJI = "🎧"

    /** `fontSize: "clamp(56px,18vw,104px)"`, `lineHeight: 1.1`, `margin: 2px 0`. */
    val EMOJI_SIZE = FluidSpec(min = 56f, vw = 18f, max = 104f)
    const val EMOJI_LINE_HEIGHT: Float = 1.1f
    val EMOJI_MARGIN_Y: Dp = 2.dp

    /**
     * The slot row: `minWidth`/`height` `clamp(52px,15vw,76px)`,
     * `padding: 0 8px`, `fontSize: clamp(24px,7vw,44px)`, `borderRadius: 20`.
     */
    val SLOT_SIDE = FluidSpec(min = 52f, vw = 15f, max = 76f)
    val SLOT_FONT_SIZE = FluidSpec(min = 24f, vw = 7f, max = 44f)
    val SLOT_HORIZONTAL_PADDING: Dp = 8.dp
    val SLOT_CORNER_RADIUS: Dp = 20.dp

    /** `<FitLine className="mb-6" rowClassName="gap-2">`. */
    val ROW_SPACING: Dp = 8.dp
    val ROW_MARGIN_BOTTOM: Dp = 24.dp

    /** The listen button's `mb-5` (SpellSyllable's is `mb-4` — not shared). */
    val LISTEN_MARGIN_BOTTOM: Dp = 20.dp

    /** `m-0 mb-1` on the headline `<p>`. */
    val HEADLINE_MARGIN_BOTTOM: Dp = 4.dp

    /** `px-4 pb-8 pt-2` on the content column. */
    val PADDING_TOP: Dp = 8.dp
    val PADDING_HORIZONTAL: Dp = 16.dp
    val PADDING_BOTTOM: Dp = 32.dp

    /**
     * `relative z-[41]` on the content column — one ABOVE `GameFrame`'s confetti
     * canvas (`zIndex: 40`), so on the web the tiles paint over the burst.
     * Declared for the record and applied like every sibling engine, but inert
     * as things stand: `GameFrame` puts the content in the flow column and the
     * overlay in a sibling `Box`, so no zIndex on a child of the content can
     * lift it past the overlay. Reported, not patched here.
     */
    const val CONTENT_Z_INDEX: Float = 41f

    /* ---- the rules ------------------------------------------------------- */

    /** `{target.emoji ?? "🎧"}`. */
    fun promptEmoji(target: SoundTarget): String = target.emoji ?: FALLBACK_EMOJI

    /**
     * What one slot renders. The TSX branch, as data:
     * ```
     * s != null ? <button aria-label={`Retirer ${s}`}>{s}</button>
     *           : <div style={dashed}>{""}</div>
     * ```
     */
    sealed interface SlotRender {

        /** The box face this render wears. */
        val face: SpellSlotFace

        data object Empty : SlotRender {
            override val face: SpellSlotFace get() = SpellSlotFace.EMPTY
        }

        data class Filled(val letter: String, val removeLabel: String) : SlotRender {
            override val face: SpellSlotFace get() = SpellSlotFace.FILLED
        }
    }

    fun render(slot: String?): SlotRender =
        if (slot == null) SlotRender.Empty else SlotRender.Filled(slot, Copy.Exercise.remove(slot))

    /**
     * A tray tile's screen-reader label — `Lettre ${t.letter}`. SpellSound
     * letters are always plain uppercase print, so there is no `faceLabel` here;
     * that is SpellSyllable's job.
     */
    fun trayLabel(tile: SoundTile): String = Copy.Exercise.letterTile(tile.letter)
}

// --- The screen ---------------------------------------------------------------

/**
 * « Écoute le son et écris-le avec les lettres ».
 *
 * @param host the audio channel, the clock and `award` — the ONLY point source
 *   (invariant 8). This run's confetti and coroutine scope are completed here,
 *   because only the screen can supply them.
 * @param rng seeding, injected so a preview or a harness can pin a run.
 */
@Composable
fun SpellSoundScreen(
    level: Int,
    mascot: MascotConfig,
    host: EngineHost,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    rng: RandomSource = SystemRandomSource(),
) {
    val scope = rememberCoroutineScope()
    val confetti = rememberConfettiSystem(reduceMotion, key = level)
    // Seeded ONCE per entry into the exercise (RoundRunner.kt's header): a plain
    // call in the composable body would re-seed on every recomposition and
    // silently replay different sounds. `level` is the key because it is the only
    // input this screen seeds from (spell-sound is one catalog row); the route's
    // `key(exercise, level)` is belt to this file's braces, not a substitute for
    // it — a router that forgets it must not silently replay level 1 forever.
    val model = remember(level) {
        AssemblyModel.spellSound(
            level = level,
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
            SpellSoundRound(model = model, mascot = mascot, reduceMotion = reduceMotion)
        }
    }
}

/**
 * `<div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4
 * pb-8 pt-2">` — a top-packed column, everything centred across.
 */
@Composable
private fun ColumnScope.SpellSoundRound(
    model: SpellSoundModel,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = Modifier
            .zIndex(SpellSoundView.CONTENT_Z_INDEX)
            .fillMaxWidth()
            .weight(1f)
            .padding(
                start = SpellSoundView.PADDING_HORIZONTAL,
                end = SpellSoundView.PADDING_HORIZONTAL,
                top = SpellSoundView.PADDING_TOP,
                bottom = SpellSoundView.PADDING_BOTTOM,
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
            modifier = Modifier.padding(bottom = SpellSoundView.HEADLINE_MARGIN_BOTTOM),
        )

        Ollie(config = mascot, mood = model.mood, reduceMotion = reduceMotion)

        // `<div aria-hidden style={{ fontSize: clamp(56px,18vw,104px) }}>`.
        BasicText(
            text = SpellSoundView.promptEmoji(model.round.target),
            style = Typography.style(
                size = SpellSoundView.EMOJI_SIZE.resolve(viewport),
                ratio = SpellSoundView.EMOJI_LINE_HEIGHT,
                fontScale = fontScale,
            ),
            modifier = Modifier
                .clearAndSetSemantics { }
                .padding(vertical = SpellSoundView.EMOJI_MARGIN_Y),
        )

        SpellListenPill(
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(bottom = SpellSoundView.LISTEN_MARGIN_BOTTOM),
            onListen = { model.replayPrompt() },
        )

        SpellSoundSlotRow(
            model = model,
            modifier = Modifier.padding(bottom = SpellSoundView.ROW_MARGIN_BOTTOM),
        )

        SpellSoundTray(model)
    }
}

/**
 * The spelling slots. A filled one is a button that pops its letter back to the
 * tray, so a misplacement is fixable mid-row (invariant 3: nothing about a wrong
 * letter is terminal).
 */
@Composable
private fun SpellSoundSlotRow(model: SpellSoundModel, modifier: Modifier = Modifier) {
    val viewport = LocalViewportWidth.current
    val side = SpellSoundView.SLOT_SIDE.resolve(viewport)
    val metrics = SpellSlotMetrics(
        minWidth = side,
        height = side,
        horizontalPadding = SpellSoundView.SLOT_HORIZONTAL_PADDING,
        fontSize = SpellSoundView.SLOT_FONT_SIZE.resolve(viewport),
        cornerRadius = SpellSoundView.SLOT_CORNER_RADIUS,
    )

    FitLine(modifier = modifier, rowSpacing = SpellSoundView.ROW_SPACING) {
        model.slots.forEachIndexed { index, slot ->
            when (val cell = SpellSoundView.render(slot)) {
                is SpellSoundView.SlotRender.Empty ->
                    SpellSlotBox(face = cell.face, metrics = metrics) { SpellGlyph("") }

                is SpellSoundView.SlotRender.Filled -> {
                    val label = cell.removeLabel
                    val remove: () -> Unit = { model.removeAt(index) }
                    SpellSlotBox(
                        face = cell.face,
                        metrics = metrics,
                        modifier = Modifier
                            .touchDown { remove() }
                            .clearAndSetSemantics {
                                // Aliased and qualified: inside this lambda the
                                // receiver carries a property of the same name.
                                this.contentDescription = label
                                this.role = Role.Button
                                onClick {
                                    remove()
                                    true
                                }
                            },
                    ) {
                        SpellGlyph(cell.letter)
                    }
                }
            }
        }
    }
}

/** `<div className="flex flex-wrap items-center justify-center gap-3">`. */
@Composable
private fun SpellSoundTray(model: SpellSoundModel) {
    WrapRow(spacing = SpellTray.GAP) {
        model.round.tray.forEachIndexed { index, tile ->
            Tile(
                paint = SpellTray.paint(index),
                contentDescription = SpellSoundView.trayLabel(tile),
                onPick = { model.pick(tile.id, tile.letter) },
                disabled = model.isTrayTileUsed(tile.id),
                size = SpellTray.SIZE,
                fontSize = SpellTray.FONT_SIZE,
                onPreview = { model.preview(tile.letter) },
                previewLabel = Copy.Exercise.listenTile(tile.letter),
            ) {
                SpellGlyph(tile.letter)
            }
        }
    }
}
