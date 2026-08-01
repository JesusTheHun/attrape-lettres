package fr.dappit.attrapelettres.ui.engines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableTile
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.ConfettiOverlay
import fr.dappit.attrapelettres.ui.components.CssShadow
import fr.dappit.attrapelettres.ui.components.Finished
import fr.dappit.attrapelettres.ui.components.FitLine
import fr.dappit.attrapelettres.ui.components.GameFrame
import fr.dappit.attrapelettres.ui.components.ListenPill
import fr.dappit.attrapelettres.ui.components.ListenPillMetrics
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.components.OllieDefaults
import fr.dappit.attrapelettres.ui.components.Tile
import fr.dappit.attrapelettres.ui.components.TileGlyph
import fr.dappit.attrapelettres.ui.components.WordIcon
import fr.dappit.attrapelettres.ui.components.WrapRow
import fr.dappit.attrapelettres.ui.components.cssShadow
import fr.dappit.attrapelettres.ui.components.rememberConfettiSystem
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.HexColor
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.TilePaint
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.interaction.touchDown

// ===========================================================================
// `src/exercises/AssembleExercise.tsx` — ONE engine for the three
// `SyllableMode`s (`fill-blank`, `order`, `order-distractor`), as the root
// CLAUDE.md requires: THE MODE REACHES `buildSyllableRound` AND NOTHING ELSE.
//
// In this file that rule is structural rather than a promise. `SyllableMode`
// appears exactly twice — as [AssembleView]'s parameter and as the argument
// handed to `AssemblyModel.assemble`. There is no `when` over it, no mode-keyed
// metric and no mode-keyed copy: the consigne the child reads is
// `model.headline` (the factory's `MODE_HINT[mode]`, straight out of :core), the
// slots come from `round.slots` / `round.locked`, and the tray from `round.tray`.
// A fill-blank round differs from an order round only in the DATA those three
// arrays carry, which is why one projection renders all three drills. Adding a
// branch on `mode` below is the change that breaks the invariant, and it will
// look perfectly reasonable at the time.
//
// THE VIEW HOLDS NO RULE. Everything behavioural belongs to [AssemblyModel] —
// `pick`, `removeAt`, the awaited « Oh non » pacing, the gated advance, the
// award. A host JUnit run cannot invoke a composable (A11), so a decision buried
// here would be a decision nothing tests. What lives in this file is the
// projection plus the authored metrics, and all three of those pieces are PLAIN
// VALUES so `AssembleViewTest` can assert them against the TSX with no renderer:
//
//   [AssembleMetrics]   — the TSX inline styles / Tailwind classes, as numbers.
//   [AssembleSlot]      — what ONE assembly slot looks like, given the slot value
//                         and the pre-revealed mask.
//   [AssembleTrayTile]  — a tray tile's paint, its two labels, its greyed state.
//
// NO MISS COOLDOWN — deliberately, and it is not an omission to "fix". This
// family paces a retry with the awaited « Oh non ! On recommence. » line, which
// already costs more real time than the 800 ms swallow window would buy; a
// cooldown on top would make a corrected row feel broken. See the header of
// `AssemblyModel.kt` and [EngineLines.OH_NON].
//
// INVARIANT 1 — the pick path is `Tile` → `touchDown` → `TileInteraction`
// `.pointerDown` → `model.pick(tileId, value)`, all synchronous inside the
// touch-down callback. Nothing on that path is deferred. The two other
// pointer-down affordances the TSX has — the big 🔊 pill and a filled slot's
// undo — go through `touchDown` for the same reason (`ListenPill` owns the
// first, [slotBox] the second).
//
// INVARIANT 3 — there is no failure branch in this file. A wrong tile is simply
// a tile in a slot; only a COMPLETE row is judged, by the model, and a wrong row
// wipes back to the seeded slots. Nothing here can lock, disable-because-wrong,
// or navigate away. `disabled` on a tray tile means « that tile is currently
// sitting in a slot », and `removeAt` takes it straight back.
//
// INVARIANT 5 — no level gate anywhere. `level` is passed to the factory and is
// otherwise inert; there is nothing to unlock.
//
// INVARIANT 8 — the star strip is `model.stars` (greyed by the model at
// pointer-down, inside `GameFrame`) and `earned` is whatever `sessionReward`
// returned. This file does no arithmetic on either and never awards.
// ===========================================================================

/**
 * The concrete assembly model behind « Construis le mot » and its two siblings.
 * One type, three modes.
 */
typealias AssembleModel = AssemblyModel<SyllableWord, SyllableRound, String>

// --- Authored metrics (AssembleExercise.tsx, verbatim) -----------------------

/**
 * Every number in `AssembleExercise.tsx`'s markup, named. Tailwind classes are
 * carried as their computed dp values (`mb-5` = 20), `clamp()`s as [FluidSpec]s
 * — this module carries the VALUE, not the class name, so a host test asserts
 * the TypeScript rather than the Kotlin.
 */
object AssembleMetrics {

    // The round column: `relative z-[41] flex w-full flex-1 flex-col
    // items-center px-4 pb-8 pt-2`.

    /** `px-4`. */
    val CONTENT_PADDING_X: Dp = 16.dp

    /** `pt-2`. */
    val CONTENT_PADDING_TOP: Dp = 8.dp

    /** `pb-8`. */
    val CONTENT_PADDING_BOTTOM: Dp = 32.dp

    /** `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`). */
    const val Z_INDEX: Float = 41f

    // The consigne line: `m-0 mb-1 text-base font-bold text-[#7A5A3A]`.

    /** `text-base`. */
    val HEADLINE_FONT_SIZE: Dp = Typography.Size.base

    /** `mb-1` (`m-0` is the reset — there is no other margin). */
    val HEADLINE_SPACING: Dp = 4.dp

    /** `<Mascot config mood />` — `Mascot.tsx`'s `size = 88` default. */
    val MASCOT_SIZE: Dp = OllieDefaults.SIZE

    // The picture: `<div style={{ margin: "2px 0" }}>`.

    /** `margin: "2px 0"` — 2 dp above AND below. */
    val WORD_ICON_MARGIN_Y: Dp = 2.dp

    /** `size="clamp(64px,22vw,120px)"`. */
    val WORD_ICON_SIZE = FluidSpec(min = 64f, vw = 22f, max = 120f)

    // The 🔊 button: `mb-5 rounded-full bg-white/70 px-5 py-2 text-lg …`.

    /** `mb-5`. */
    val LISTEN_SPACING: Dp = 20.dp

    /** `px-5` — owned by `ListenPill`, restated here so the test reads the TSX. */
    val LISTEN_PADDING_X: Dp = ListenPillMetrics.paddingX

    /** `py-2`. */
    val LISTEN_PADDING_Y: Dp = ListenPillMetrics.paddingY

    /** `text-lg`. */
    val LISTEN_FONT_SIZE: Dp = Typography.Size.lg

    // The slot row: `<FitLine className="mb-6" rowClassName="gap-2">`.

    /** `mb-6` on the FitLine wrapper. */
    val SLOT_ROW_SPACING: Dp = 24.dp

    /** `gap-2` on the inner row. */
    val SLOT_GAP: Dp = 8.dp

    /**
     * `minWidth` AND `height`: `clamp(56px,16vw,84px)` — the slot is square at
     * its minimum and grows only with its content.
     */
    val SLOT_SIDE = FluidSpec(min = 56f, vw = 16f, max = 84f)

    /** `fontSize: "clamp(22px,6vw,40px)"`. */
    val SLOT_FONT_SIZE = FluidSpec(min = 22f, vw = 6f, max = 40f)

    /** `padding: "0 10px"`. */
    val SLOT_PADDING_X: Dp = 10.dp

    /** `borderRadius: 20`. */
    val SLOT_CORNER_RADIUS: Dp = 20.dp

    /** `3px dashed …` on an empty slot. */
    val SLOT_BORDER_WIDTH: Dp = 3.dp

    /**
     * `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` on a FILLED slot, kept as the
     * authored CSS triple. [CssShadow.blurRadius] halves the blur, because CSS
     * defines it as twice the Gaussian's standard deviation.
     */
    val SLOT_SHADOW = CssShadow(y = 6.dp, blur = 14.dp, opacity = 0.12f)

    /**
     * CSS `border-style: dashed` has no specified dash length; Blink and WebKit
     * draw `3 * width` on and the same off, i.e. 9 dp / 9 dp at the authored
     * 3 dp width. Recorded as an approximation of a browser implementation
     * detail, NOT as an authored value.
     */
    val SLOT_DASH_ON: Dp = 9.dp
    val SLOT_DASH_OFF: Dp = 9.dp

    // The tray: `flex flex-wrap items-center justify-center gap-3`.

    /** `gap-3`, both axes (CSS `gap` is one value for both). */
    val TRAY_GAP: Dp = 12.dp

    /**
     * `size="clamp(64px,18vw,100px)"`. NOTE: below `TileMetrics.DEFAULT_SIZE`'s
     * 92 dp floor — the TSX authors it that way, behaviour is frozen, and it
     * still clears `TileMetrics.PLATFORM_MINIMUM_TAP_TARGET` at every viewport.
     */
    val TILE_SIDE = FluidSpec(min = 64f, vw = 18f, max = 100f)

    /** `fontSize="clamp(20px,5.5vw,36px)"`. */
    val TILE_FONT_SIZE = FluidSpec(min = 20f, vw = 5.5f, max = 36f)
}

// --- One assembly slot, as data ----------------------------------------------

/**
 * What a single slot in the word row shows, derived from the slot's value and
 * the round's pre-revealed mask — the TSX's `removable` const plus its inline
 * `style` object, as a value:
 *
 * ```tsx
 * const removable = s != null && !round.locked[i];
 * background: s ? "#FFFFFF" : "transparent",
 * border: s ? "none" : round.locked[i] ? "3px dashed #C9A87A"
 *                                      : "3px dashed #E4A15E",
 * boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
 * … {s ?? ""}
 * ```
 */
data class AssembleSlot(
    /** `{s ?? ""}` — an empty slot renders an empty string, not a placeholder. */
    val text: String,
    /** `s != null`. */
    val isFilled: Boolean,
    /** `round.locked[i]` — a fill-blank slot the round revealed for free. */
    val isPreRevealed: Boolean,
    /**
     * `removable` — the slot is a BUTTON that pops its tile back to the tray.
     *
     * The same expression as `AssemblyModel.isSlotRemovable`, duplicated here
     * exactly as the TSX duplicates it (`removable` in the render vs the guard
     * inside `removeAt`). This copy decides only what the slot LOOKS like; the
     * model still owns whether a tap does anything.
     */
    val isRemovable: Boolean,
    /** ``aria-label={`Retirer ${s}`}`` — null unless the slot is removable. */
    val removeLabel: String?,
    /**
     * The dashed border's colour, as its authored hex; null once filled
     * (`border: "none"`).
     */
    val borderHex: String?,
) {

    companion object {

        /**
         * The whole row, in slot order. [filled] is `AssemblyModel.slots`,
         * [locked] its `lockedMask`.
         *
         * Mode-free by construction: fill-blank arrives as a [locked] mask with
         * `true`s and a [filled] array with syllables already in it, order /
         * order-distractor as all-`false` and all-`null`. Nothing here asks
         * which, and nothing here may start asking.
         */
        fun row(filled: List<String?>, locked: List<Boolean>): List<AssembleSlot> =
            filled.indices.map { i ->
                val value = filled[i]
                // `round.locked[i]` past the end of the mask is `undefined` in
                // JS, which is falsy — so a short mask reads as not-locked.
                val preRevealed = locked.getOrElse(i) { false }
                val removable = value != null && !preRevealed
                AssembleSlot(
                    text = value ?: "",
                    isFilled = value != null,
                    isPreRevealed = preRevealed,
                    isRemovable = removable,
                    removeLabel = if (removable) Copy.Exercise.remove(value) else null,
                    borderHex = when {
                        value != null -> null
                        preRevealed -> Palette.slotDashedLocked.hex
                        else -> Palette.slotDashed.hex
                    },
                )
            }
    }
}

// --- One tray tile, as data ---------------------------------------------------

/**
 * A syllable tile in the tray: its paint, its two labels, and whether it is
 * greyed out because it currently sits in a slot.
 *
 * ```tsx
 * bg={TRAY_COLORS[i % TRAY_COLORS.length].bg}
 * ink={TRAY_COLORS[i % TRAY_COLORS.length].ink}
 * disabled={used.has(t.id)}
 * previewLabel={`Écouter ${t.syllable}`}
 * ariaLabel={`Syllabe ${t.syllable}`}
 * ```
 */
data class AssembleTrayTile(
    val id: Int,
    val syllable: String,
    /**
     * `TRAY_COLORS[i % TRAY_COLORS.length]` — rotation by POSITION in the tray,
     * never by tile id. `TileIdAllocator` hands out consecutive ids, so the two
     * agree in production and a rotation keyed on the wrong one would ship.
     */
    val paint: TilePaint,
    /** `ariaLabel` — invariant 6. */
    val label: String,
    /** `previewLabel`. */
    val previewLabel: String,
    /** `disabled` — dropped tiles grey out and come back via `removeAt`. */
    val isDisabled: Boolean,
) {

    companion object {

        /**
         * The tray, in `round.tray` order.
         *
         * @param used `AssemblyModel::isTrayTileUsed`. Injected rather than
         *   recomputed, so the « is this tile spent » rule stays the model's.
         */
        fun row(tiles: List<SyllableTile>, used: (Int) -> Boolean): List<AssembleTrayTile> =
            tiles.mapIndexed { i, tile ->
                AssembleTrayTile(
                    id = tile.id,
                    syllable = tile.syllable,
                    paint = Palette.trayColors[i % Palette.trayColors.size],
                    label = Copy.Exercise.syllableTile(tile.syllable),
                    previewLabel = Copy.Exercise.listenTile(tile.syllable),
                    isDisabled = used(tile.id),
                )
            }
    }
}

// --- The screen ---------------------------------------------------------------

/**
 * « Construis le mot » / « Remets dans l'ordre » / « Range et évite l'intrus »
 * — one screen, three modes.
 *
 * @param exercise the hub row that opened this run (`EXERCISES`' id) — the award
 *   key, and the reason this is a parameter rather than a constant.
 * @param mode reaches `buildSyllableRound` and nothing else.
 * @param mascot `useProfile().profile.config` — the child's companion.
 * @param host the audio channel, the clock and `award`, the ONLY point source
 *   (invariant 8). This run's confetti and coroutine scope are completed here.
 * @param onBack ← Menu, and `Finished`'s « 🏠 Menu ».
 * @param onNext `Finished`'s « 🎉 Suivant ».
 * @param rng seeding, injected so a preview or a screenshot harness can pin a run.
 */
@Composable
fun AssembleView(
    exercise: ExerciseId,
    mode: SyllableMode,
    level: Int,
    mascot: MascotConfig,
    host: EngineHost,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    rng: RandomSource = SystemRandomSource(),
) {
    // The screen owns the confetti (it also has to place the canvas) and the
    // scope (every pending voice line must die with the composition); those are
    // exactly the two members `EngineHost` cannot supply.
    val confetti = rememberConfettiSystem(reduceMotion = reduceMotion, key = listOf(exercise, mode, level))
    val scope = rememberCoroutineScope()

    // SEEDED ONCE. The constructor draws the whole session through :core's
    // builders, so a plain call in the composable body would re-seed on every
    // recomposition and silently replay different words (iOS D9). The keys are
    // the port of React's `key={`${ex}-${level}`}`: a level change is a new run,
    // a recomposition is not.
    val model = remember(exercise, mode, level) {
        AssemblyModel.assemble(
            exercise = exercise,
            mode = mode,
            level = level,
            deps = host.deps(scope = scope, fireConfetti = { confetti.fire() }),
            rng = rng,
        )
    }

    // `useEffect(() => audio.unlock())` + the 350 ms announce, and on the way
    // out `audio.stop()` + the cancelled timer + a frozen model.
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
                modifier = Modifier.weight(1f), // `flex-1`, as the TSX authors it
            )
        } else {
            AssembleRound(
                model = model,
                mascot = mascot,
                reduceMotion = reduceMotion,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * `<div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4
 * pb-8 pt-2">`
 */
@Composable
private fun AssembleRound(
    model: AssembleModel,
    mascot: MascotConfig,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = modifier
            .zIndex(AssembleMetrics.Z_INDEX)
            .fillMaxWidth()
            .padding(
                start = AssembleMetrics.CONTENT_PADDING_X,
                end = AssembleMetrics.CONTENT_PADDING_X,
                top = AssembleMetrics.CONTENT_PADDING_TOP,
                bottom = AssembleMetrics.CONTENT_PADDING_BOTTOM,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // `MODE_HINT[mode]`, straight off the model. The ONLY thing the mode
        // reaches on this screen besides the round builder.
        BasicText(
            text = model.headline,
            modifier = Modifier.padding(bottom = AssembleMetrics.HEADLINE_SPACING),
            style = Typography.style(
                size = AssembleMetrics.HEADLINE_FONT_SIZE,
                weight = Typography.Weight.bold,
                color = Palette.inkSoft.color,
                fontScale = fontScale,
            ),
        )

        Ollie(
            config = mascot,
            mood = model.mood,
            reduceMotion = reduceMotion,
            size = AssembleMetrics.MASCOT_SIZE,
        )

        WordIcon(
            emoji = model.round.word.emoji,
            img = model.round.word.img,
            size = AssembleMetrics.WORD_ICON_SIZE.resolve(viewport),
            modifier = Modifier.padding(vertical = AssembleMetrics.WORD_ICON_MARGIN_Y),
        )

        // The locked guard (« don't cut the success line mid-celebration ») is
        // inside `model.replayPrompt()`, not here.
        ListenPill(
            text = Copy.Exercise.LISTEN,
            contentDescription = model.listenAccessibilityLabel,
            modifier = Modifier.padding(bottom = AssembleMetrics.LISTEN_SPACING),
            onListen = { model.replayPrompt() },
        )

        // The word row. FitLine keeps it on ONE line, shrinking if a
        // four-syllable word would overflow a narrow phone.
        FitLine(
            modifier = Modifier.padding(bottom = AssembleMetrics.SLOT_ROW_SPACING),
            rowSpacing = AssembleMetrics.SLOT_GAP,
        ) {
            AssembleSlot.row(filled = model.slots, locked = model.lockedMask)
                .forEachIndexed { index, slot ->
                    SlotView(slot = slot, index = index, model = model)
                }
        }

        Tray(model)
    }
}

/**
 * One slot. A filled, non-pre-revealed one is a BUTTON that pops its tile back
 * to the tray — `onPointerDown={() => removeAt(i)}`, at touch-down like every
 * other gameplay affordance, and with no press animation because the TSX slot
 * button has none. Everything else is a plain `<div>`: pre-revealed syllables
 * and empty slots are read as text, exactly as on the web.
 */
@Composable
private fun SlotView(slot: AssembleSlot, index: Int, model: AssembleModel) {
    // `touchDown` is attached UNCONDITIONALLY and gated by `enabled`, so the
    // composable structure does not change as a slot fills and empties; only the
    // semantics node comes and goes, and that is a plain (non-composable)
    // modifier. `model.removeAt` guards the same condition again — the TSX
    // duplicates it the same way (`removable` in the render, a guard in the
    // handler), and this is the copy that decides only what the slot LOOKS like.
    val label = slot.removeLabel
    SlotBox(
        slot = slot,
        modifier = Modifier
            .touchDown(enabled = slot.isRemovable) { model.removeAt(index) }
            .then(
                if (label != null) {
                    Modifier.clearAndSetSemantics {
                        this.contentDescription = label
                        this.role = Role.Button
                        onClick {
                            model.removeAt(index)
                            true
                        }
                    }
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun SlotBox(slot: AssembleSlot, modifier: Modifier = Modifier) {
    val viewport = LocalViewportWidth.current
    val fontScale = LocalDensity.current.fontScale
    val side = AssembleMetrics.SLOT_SIDE.resolve(viewport)
    val shape = RoundedCornerShape(AssembleMetrics.SLOT_CORNER_RADIUS)
    val border = slot.borderHex?.let { HexColor(it).color }
    val face = if (slot.isFilled) Color.White else Color.Transparent

    Box(
        modifier = modifier
            // `boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none"`. Drawn by
            // hand rather than as an elevation: the CSS painting rule clips the
            // shadow out of the border box, which is what keeps it from greying
            // the slot from the inside (see CssEffects.kt).
            .then(
                if (slot.isFilled) {
                    Modifier.cssShadow(AssembleMetrics.SLOT_SHADOW, shape)
                } else {
                    Modifier
                },
            )
            .then(if (border != null) Modifier.dashedSlotBorder(border) else Modifier)
            .background(color = face, shape = shape)
            .height(side)
            // `minWidth: clamp(…)` with `width: auto` — a two-glyph syllable
            // grows the slot, a one-glyph one never shrinks it.
            .defaultMinSize(minWidth = side)
            .padding(horizontal = AssembleMetrics.SLOT_PADDING_X),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = slot.text,
            style = Typography.style(
                size = AssembleMetrics.SLOT_FONT_SIZE.resolve(viewport),
                weight = Typography.Weight.black,
                color = Palette.ink.color,
                fontScale = fontScale,
            ),
            maxLines = 1,
        )
    }
}

/**
 * `border: "3px dashed <hex>"`, drawn inset so the stroke sits INSIDE the border
 * box the way a CSS border does (Compose strokes are centred on the path).
 *
 * It is built as a `Path` and dashed with a `PathEffect` rather than passed to
 * `drawRoundRect(style = Stroke(pathEffect = …))`: a path effect on a primitive
 * shape has historically been ignored by the hardware-accelerated canvas, and a
 * silently-solid border would look like a design change rather than a bug. A
 * dashed `drawPath` is supported everywhere. Both are draw-phase work, so this
 * costs no recomposition (invariant 2) — and neither is reachable from a host
 * test, which is why nothing about it is asserted beyond the authored numbers.
 */
private fun Modifier.dashedSlotBorder(color: Color): Modifier = this.drawBehind {
    val stroke = AssembleMetrics.SLOT_BORDER_WIDTH.toPx()
    val inset = stroke / 2f
    if (size.width <= stroke || size.height <= stroke) return@drawBehind
    val radius = (AssembleMetrics.SLOT_CORNER_RADIUS.toPx() - inset).coerceAtLeast(0f)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(
                    offset = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                ),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(
                    AssembleMetrics.SLOT_DASH_ON.toPx(),
                    AssembleMetrics.SLOT_DASH_OFF.toPx(),
                ),
                0f,
            ),
        ),
    )
}

/** `<div className="flex flex-wrap items-center justify-center gap-3">` */
@Composable
private fun Tray(model: AssembleModel) {
    val tiles = AssembleTrayTile.row(model.round.tray) { id -> model.isTrayTileUsed(id) }
    WrapRow(spacing = AssembleMetrics.TRAY_GAP) {
        tiles.forEach { tile ->
            Tile(
                paint = tile.paint,
                contentDescription = tile.label,
                // INVARIANT 1: synchronous, at touch-down, inside
                // `TileInteraction.pointerDown`.
                onPick = { model.pick(tileId = tile.id, value = tile.syllable) },
                disabled = tile.isDisabled,
                size = AssembleMetrics.TILE_SIDE,
                fontSize = AssembleMetrics.TILE_FONT_SIZE,
                onPreview = { model.preview(tile.syllable) },
                previewLabel = tile.previewLabel,
            ) {
                TileGlyph(tile.syllable)
            }
        }
    }
}
