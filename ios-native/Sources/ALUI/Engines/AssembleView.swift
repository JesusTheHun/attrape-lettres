import SwiftUI

import ALCore

/* --------------------------------------------------------------------------
 `src/exercises/AssembleExercise.tsx` — ONE engine for the three
 `SyllableMode`s (`fill-blank`, `order`, `order-distractor`), as CLAUDE.md
 requires: **the mode reaches `buildSyllableRound` and nothing else.**

 In this file that rule is structural, not a promise. `SyllableMode` appears
 exactly twice — as the view's stored property and as the argument handed to
 `AssemblyModel.assemble(exercise:mode:level:deps:rng:)`. There is no `switch`
 over it, no mode-keyed metric, no mode-keyed copy: the headline the child
 reads is `model.headline` (the factory's `Levels.modeHint[mode]`), the slots
 come from `round.slots` / `round.locked`, and the tray from `round.tray`. A
 fill-blank round differs from an order round only in the DATA those three
 arrays carry, which is why one projection renders all three drills.

 THE VIEW HOLDS NO RULE. Everything behavioural is `AssemblyModel`'s
 (`pick`, `removeAt`, the awaited « Oh non » pacing, the award). What lives
 here is the projection and the authored metrics:

   - `AssembleMetrics` — the TSX inline styles / Tailwind classes as numbers.
   - `AssembleSlot`    — what one assembly slot LOOKS like, given the slot
                         value and the pre-revealed mask.
   - `AssembleTrayTile`— a tray tile's paint, labels and greyed state.

 Those three are plain values so `AssembleViewTests` can assert them against
 the TSX without a renderer.

 INVARIANT 1 — the pick path is `Tile` → `touchDown` → `TilePress.pointerDown`
 → `model.pick(tileID:value:)`, all synchronous inside the touch-down callback.
 Nothing on that path is deferred, and the two other pointerdown affordances
 the TSX has (the big 🔊 button, a filled slot's undo) go through `touchDown`
 for the same reason.

 INVARIANT 3 — there is no failure branch in this file. A wrong tile is simply
 a tile in a slot; only a COMPLETE row is judged, by the model, and a wrong row
 wipes back to the seeded slots. Nothing here can lock, disable-because-wrong,
 or navigate away.

 INVARIANT 8 — the star strip is `model.stars` (greyed by the model at
 pointerdown) and `earned` is whatever `sessionReward` returned. This file does
 no arithmetic on either.
 -------------------------------------------------------------------------- */

/// The concrete assembly model behind « Construis le mot » and its two
/// siblings. One type, three modes.
public typealias AssembleModel = AssemblyModel<SyllableWord, SyllableRound, String>

// MARK: - Authored metrics (AssembleExercise.tsx, verbatim)

/// Every number in `AssembleExercise.tsx`'s markup, named. Tailwind classes are
/// carried as their computed px values (`mb-5` = 20), `clamp()`s as
/// ``FluidSpec``s — shell.md §5.1: the Swift code carries the value, not the
/// class name.
public enum AssembleMetrics {

    /* ---- The round column: `px-4 pb-8 pt-2`, `z-[41]` --------------------- */

    /// `px-4`.
    public static let contentPaddingX: CGFloat = 16
    /// `pt-2`.
    public static let contentPaddingTop: CGFloat = 8
    /// `pb-8`.
    public static let contentPaddingBottom: CGFloat = 32
    /// `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`).
    public static let zIndex: Double = 41

    /* ---- The consigne line: `m-0 mb-1 text-base font-bold text-[#7A5A3A]` - */

    /// `text-base`.
    public static let headlineFontSize: CGFloat = Typography.Size.base
    /// `mb-1` (`m-0` is the reset — no other margin).
    public static let headlineSpacing: CGFloat = 4

    /* ---- The mascot ------------------------------------------------------- */

    /// `<Mascot config mood />` — `Mascot.tsx`'s `size = 88` default.
    public static let mascotSize: CGFloat = 88

    /* ---- The picture: `<div style={{ margin: "2px 0" }}>` ----------------- */

    /// `margin: "2px 0"` — 2 pt above AND below.
    public static let wordIconMarginY: CGFloat = 2
    /// `size="clamp(64px,22vw,120px)"`.
    public static let wordIconSize = FluidSpec(min: 64, vw: 22, max: 120)

    /* ---- The 🔊 button: `mb-5 rounded-full bg-white/70 px-5 py-2 text-lg` -- */

    /// `mb-5`.
    public static let listenSpacing: CGFloat = 20
    /// `px-5`.
    public static let listenPaddingX = ListenPillMetrics.paddingX
    /// `py-2`.
    public static let listenPaddingY = ListenPillMetrics.paddingY
    /// `text-lg`.
    public static let listenFontSize: CGFloat = Typography.Size.lg

    /* ---- The slot row: `<FitLine className="mb-6" rowClassName="gap-2">` -- */

    /// `mb-6` on the FitLine wrapper.
    public static let slotRowSpacing: CGFloat = 24
    /// `gap-2` on the inner row.
    public static let slotGap: CGFloat = 8
    /// `minWidth` AND `height`: `clamp(56px,16vw,84px)` — the slot is square at
    /// its minimum and grows only with its content.
    public static let slotSide = FluidSpec(min: 56, vw: 16, max: 84)
    /// `fontSize: "clamp(22px,6vw,40px)"`.
    public static let slotFontSize = FluidSpec(min: 22, vw: 6, max: 40)
    /// `padding: "0 10px"`.
    public static let slotPaddingX: CGFloat = 10
    /// `borderRadius: 20`.
    public static let slotCornerRadius: CGFloat = 20
    /// `3px dashed …` on an empty slot.
    public static let slotBorderWidth: CGFloat = 3
    /// `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` on a FILLED slot.
    public static let slotShadowOpacity: Double = 0.12
    public static let slotShadowRadius: CGFloat = 7  // CSS blur 14 → radius 14/2
    public static let slotShadowY: CGFloat = 6

    /// CSS `border-style: dashed` has no specified dash length; Blink and WebKit
    /// draw `kDashRatio * width` on, the same off — 9 pt on / 9 pt off at the
    /// authored 3 pt width. Recorded as an approximation of a browser
    /// implementation detail, not as an authored value (see `deviations`).
    public static let slotDashPattern: [CGFloat] = [9, 9]

    /* ---- The tray: `flex flex-wrap items-center justify-center gap-3` ----- */

    /// `gap-3`, both axes (CSS `gap` is one value for both).
    public static let trayGap: CGFloat = 12
    /// `size="clamp(64px,18vw,100px)"`. NOTE: below `TileMetrics.defaultSize`'s
    /// 92 pt floor — the TSX authors it that way and behaviour is frozen (see
    /// `deviations`).
    public static let tileSide = FluidSpec(min: 64, vw: 18, max: 100)
    /// `fontSize="clamp(20px,5.5vw,36px)"`.
    public static let tileFontSize = FluidSpec(min: 20, vw: 5.5, max: 36)
}

// MARK: - One assembly slot, as data

/// What a single slot in the word row shows, derived from the slot's value and
/// the round's pre-revealed mask — the TSX's `removable` const plus its inline
/// `style` object, as a value:
///
/// ```tsx
/// const removable = s != null && !round.locked[i];
/// background: s ? "#FFFFFF" : "transparent",
/// border: s ? "none" : round.locked[i] ? "3px dashed #C9A87A"
///                                      : "3px dashed #E4A15E",
/// boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
/// … {s ?? ""}
/// ```
public struct AssembleSlot: Equatable, Sendable {
    /// `{s ?? ""}` — an empty slot renders an empty string, not a placeholder.
    public let text: String
    /// `s != null`.
    public let isFilled: Bool
    /// `round.locked[i]` — a fill-blank slot the round revealed for free.
    public let isPreRevealed: Bool
    /// `removable` — the slot is a BUTTON that pops its tile back to the tray.
    ///
    /// The same expression as `AssemblyModel.isSlotRemovable(_:)`, duplicated
    /// here exactly as the TSX duplicates it (`removable` in the render vs the
    /// guard inside `removeAt`). This copy decides only what the slot LOOKS
    /// like; the model still owns whether a tap does anything.
    public let isRemovable: Bool
    /// `aria-label={`Retirer ${s}`}` — nil unless the slot is removable.
    public let removeLabel: String?
    /// The dashed border's colour, as its authored hex; nil once filled
    /// (`border: "none"`).
    public let borderHex: String?

    /// The whole row, in slot order. `filled` is `AssemblyModel.slots`, `locked`
    /// its `lockedMask`.
    ///
    /// Mode-free by construction: fill-blank arrives as a `locked` mask with
    /// `true`s and a `filled` array with syllables already in it, order /
    /// order-distractor as all-`false` and all-`nil`. Nothing here asks which.
    public static func row(filled: [String?], locked: [Bool]) -> [AssembleSlot] {
        filled.indices.map { i in
            let value = filled[i]
            let isPreRevealed = locked.indices.contains(i) ? locked[i] : false
            let removable = value != nil && !isPreRevealed
            return AssembleSlot(
                text: value ?? "",
                isFilled: value != nil,
                isPreRevealed: isPreRevealed,
                isRemovable: removable,
                removeLabel: removable ? Copy.Exercise.remove(value ?? "") : nil,
                borderHex: value != nil
                    ? nil
                    : (isPreRevealed ? Palette.slotDashedLocked.hex : Palette.slotDashed.hex)
            )
        }
    }
}

// MARK: - One tray tile, as data

/// A syllable tile in the tray: its paint, its two labels and whether it is
/// greyed out because it currently sits in a slot.
///
/// ```tsx
/// bg={TRAY_COLORS[i % TRAY_COLORS.length].bg}
/// ink={TRAY_COLORS[i % TRAY_COLORS.length].ink}
/// disabled={used.has(t.id)}
/// previewLabel={`Écouter ${t.syllable}`}
/// ariaLabel={`Syllabe ${t.syllable}`}
/// ```
public struct AssembleTrayTile: Equatable, Sendable {
    public let id: Int
    public let syllable: String
    /// `TRAY_COLORS[i % TRAY_COLORS.length]` — rotation by POSITION in the tray,
    /// not by tile id.
    public let paint: Palette.TilePaint
    /// `ariaLabel`.
    public let label: String
    /// `previewLabel`.
    public let previewLabel: String
    /// `disabled` — dropped tiles grey out and come back via `removeAt`.
    public let isDisabled: Bool

    /// The tray, in `round.tray` order.
    ///
    /// - Parameter used: `AssemblyModel.isTrayTileUsed(id:)`. Injected rather
    ///   than recomputed so the "is this tile spent" rule stays the model's.
    public static func row(_ tiles: [SyllableTile], used: (Int) -> Bool) -> [AssembleTrayTile] {
        tiles.enumerated().map { i, tile in
            let paint = Palette.trayColors[i % Palette.trayColors.count]
            return AssembleTrayTile(
                id: tile.id,
                syllable: tile.syllable,
                paint: paint,
                label: Copy.Exercise.syllableTile(tile.syllable),
                previewLabel: Copy.Exercise.listenTile(tile.syllable),
                isDisabled: used(tile.id)
            )
        }
    }
}

// MARK: - The view

/// « Construis le mot » / « Remets dans l'ordre » / « Range et évite l'intrus »
/// — one view, three modes.
public struct AssembleView: View {

    /// The hub row that opened this run (`EXERCISES`' id) — the award key.
    public let exercise: ExerciseId
    /// Reaches `buildSyllableRound` and nothing else.
    public let mode: SyllableMode
    public let level: Int
    /// `useProfile().profile.config` — the child's companion.
    public let mascot: MascotConfig
    /// The audio channel, the clock, and `award` — the ONLY point source
    /// (invariant 8). This run's confetti is added in `.task`.
    public let host: EngineHost
    /// ← Menu, and `Finished`'s « 🏠 Menu ».
    public let onBack: () -> Void
    /// `Finished`'s « 🎉 Suivant ».
    public let onNext: () -> Void
    /// Seeding, injected so the render harness can pin a run (D9).
    public let rng: RandomSource

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// Created ONCE, in `.task` — never in `init` or a `@State` default, which
    /// SwiftUI re-evaluates on every parent re-render (D9, and the header
    /// comment of `RoundRunner.swift`).
    @State private var model: AssembleModel?
    @State private var confetti: ConfettiSystem?

    public init(
        exercise: ExerciseId,
        mode: SyllableMode,
        level: Int,
        mascot: MascotConfig,
        host: EngineHost,
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void,
        rng: RandomSource = .system()
    ) {
        self.exercise = exercise
        self.mode = mode
        self.level = level
        self.mascot = mascot
        self.host = host
        self.onBack = onBack
        self.onNext = onNext
        self.rng = rng
    }

    public var body: some View {
        GameFrame(
            onBack: onBack,
            done: model?.progressDone ?? 0,
            total: model?.totalRounds ?? 0,
            stars: model?.stars ?? [],
            overlay: {
                Group {
                    if let confetti {
                        ConfettiOverlay(system: confetti)
                    }
                }
            },
            content: {
                Group {
                    if let model {
                        if model.done {
                            Finished(
                                stars: model.stars,
                                earned: model.earned,
                                title: model.finishedTitle,
                                reduceMotion: reduceMotion,
                                onMenu: onBack,
                                onNext: onNext
                            )
                        } else {
                            round(model)
                        }
                    } else {
                        // One frame at most: `.task` runs before the next
                        // commit. The stage gradient is already painted by
                        // GameFrame, so nothing flashes.
                        Color.clear.frame(minHeight: 0)
                    }
                }
            }
        )
        .task {
            if model == nil {
                let system = ConfettiSystem(reduceMotion: reduceMotion)
                confetti = system
                model = .assemble(
                    exercise: exercise,
                    mode: mode,
                    level: level,
                    deps: host.deps(fireConfetti: { system.fire() }),
                    rng: rng
                )
            }
            // `useEffect(() => { audio.unlock() })` + the 350 ms announce.
            model?.activate()
        }
        .onDisappear { model?.deactivate() }
    }

    // MARK: the round

    /// `<div className="relative z-[41] flex w-full flex-1 flex-col items-center
    /// px-4 pb-8 pt-2">`
    @ViewBuilder
    private func round(_ model: AssembleModel) -> some View {
        VStack(spacing: 0) {
            if let headline = model.headline {
                Text(verbatim: headline)
                    .font(
                        Typography.rounded(
                            AssembleMetrics.headlineFontSize, Typography.Weight.bold)
                    )
                    .foregroundStyle(Palette.inkSoft.color)
                    .padding(.bottom, AssembleMetrics.headlineSpacing)
            }

            Ollie(
                config: mascot,
                mood: model.mood,
                size: AssembleMetrics.mascotSize,
                reduceMotion: reduceMotion
            )

            WordIcon(
                emoji: model.round.word.emoji,
                img: model.round.word.img,
                size: AssembleMetrics.wordIconSize.resolve(viewport: viewport)
            )
            .padding(.vertical, AssembleMetrics.wordIconMarginY)

            listenButton(model)
                .padding(.bottom, AssembleMetrics.listenSpacing)

            // The word row. FitLine keeps it on ONE line, shrinking if the
            // syllables (or a 4-syllable word on a narrow phone) overflow.
            FitLine(rowSpacing: AssembleMetrics.slotGap) {
                let slots = AssembleSlot.row(filled: model.slots, locked: model.lockedMask)
                ForEach(Array(slots.enumerated()), id: \.offset) { index, slot in
                    slotView(slot, at: index, model: model)
                }
            }
            .padding(.bottom, AssembleMetrics.slotRowSpacing)

            tray(model)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.horizontal, AssembleMetrics.contentPaddingX)
        .padding(.top, AssembleMetrics.contentPaddingTop)
        .padding(.bottom, AssembleMetrics.contentPaddingBottom)
        .zIndex(AssembleMetrics.zIndex)
    }

    // MARK: 🔊 Écouter

    /// ```tsx
    /// <button onPointerDown={() => { if (locked.current) return;
    ///                                void audio.say(round.word.word); }}
    ///         aria-label="Répéter le mot" className="… bg-white/70 …">
    ///   🔊 Écouter
    /// </button>
    /// ```
    /// The locked guard (« don't cut the success line mid-celebration ») lives
    /// in `model.replayPrompt()`.
    private func listenButton(_ model: AssembleModel) -> some View {
        ListenPill(
            text: Copy.Exercise.listen,
            accessibilityLabel: model.listenAccessibilityLabel,
            action: { model.replayPrompt() })
    }

    // MARK: a slot

    @ViewBuilder
    private func slotView(_ slot: AssembleSlot, at index: Int, model: AssembleModel) -> some View {
        let box = slotBox(slot)
        if slot.isRemovable {
            box
                // `onPointerDown={() => removeAt(i)}` — touch-down, like every
                // other gameplay affordance (D5). No press animation: the TSX
                // slot button has none.
                .touchDown { model.removeAt(index) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(slot.removeLabel ?? "")
                .accessibilityAddTraits(.isButton)
        } else {
            // A plain `<div>`: pre-revealed syllables and empty slots are read
            // as text, exactly as on the web.
            box
        }
    }

    private func slotBox(_ slot: AssembleSlot) -> some View {
        let side = AssembleMetrics.slotSide.resolve(viewport: viewport)
        let radius = AssembleMetrics.slotCornerRadius
        return Text(verbatim: slot.text)
            .font(
                Typography.rounded(
                    AssembleMetrics.slotFontSize.resolve(viewport: viewport),
                    Typography.Weight.black
                )
            )
            .foregroundStyle(Palette.ink.color)
            .padding(.horizontal, AssembleMetrics.slotPaddingX)
            .frame(minWidth: side)
            .frame(height: side)
            .background(
                slot.isFilled ? Color.white : Color.clear,
                in: RoundedRectangle(cornerRadius: radius)
            )
            .overlay {
                if let hex = slot.borderHex {
                    RoundedRectangle(cornerRadius: radius)
                        .strokeBorder(
                            HexColor(hex).color,
                            style: StrokeStyle(
                                lineWidth: AssembleMetrics.slotBorderWidth,
                                dash: AssembleMetrics.slotDashPattern
                            )
                        )
                }
            }
            .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
            .shadow(
                color: .black.opacity(slot.isFilled ? AssembleMetrics.slotShadowOpacity : 0),
                radius: AssembleMetrics.slotShadowRadius,
                y: slot.isFilled ? AssembleMetrics.slotShadowY : 0
            )
    }

    // MARK: the tray

    /// `<div className="flex flex-wrap items-center justify-center gap-3">`
    private func tray(_ model: AssembleModel) -> some View {
        let tiles = AssembleTrayTile.row(model.round.tray, used: model.isTrayTileUsed(id:))
        return ComponentsWrapRow(
            spacing: AssembleMetrics.trayGap,
            lineSpacing: AssembleMetrics.trayGap
        ) {
            ForEach(tiles, id: \.id) { tile in
                Tile(
                    paint: tile.paint,
                    disabled: tile.isDisabled,
                    size: AssembleMetrics.tileSide,
                    fontSize: AssembleMetrics.tileFontSize,
                    accessibilityLabel: tile.label,
                    // INVARIANT 1: synchronous, at touch-down, inside
                    // `TilePress.pointerDown`.
                    onPick: { model.pick(tileID: tile.id, value: tile.syllable) },
                    onPreview: { model.preview(say: tile.syllable) },
                    previewLabel: tile.previewLabel
                ) {
                    Text(verbatim: tile.syllable)
                }
            }
        }
    }
}
