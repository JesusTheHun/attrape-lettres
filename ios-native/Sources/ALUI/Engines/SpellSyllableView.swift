import SwiftUI

import ALCore

/* ==========================================================================
   `src/exercises/SpellSyllableExercise.tsx` — the word is printed with one (or
   two) syllable blanked into per-letter slots.

   « Same forgiving loop as SpellSoundExercise (feedback on pointerdown, WAAPI
   shake, canvas confetti, whole-row judgement), but the prompt is the printed
   word and the target is its missing letters. Mode only changes the tray / gap
   count. »

   FIVE EXERCISES, ONE VIEW. `mode` (`letters-exact` / `letters-extra` /
   `letters-two`) and `mixed` both come from the `EXERCISES` row and stay
   parameters, exactly as the TSX props do:

     - `mode` reaches `buildSpellSyllableRound` (how many syllables are blanked
       and whether intruders join the tray) and picks the headline.
     - `mixed` — the « écritures mêlées » twins — draws the whole word in ONE
       random writing (GRANDE / petite / attachée) and makes the intruders the
       same letters in the OTHER two writings. Judging is by FACE, so a right
       letter in the wrong writing fails the row. It also SWAPS the headline,
       « because matching the writing IS the task now ».

   Both of those live in `AssemblyModel.spellSyllable` and `Copy.Exercise`; this
   file only renders. What it owns: the cell row (written letters + gaps, each
   in the round's script font, with the per-syllable gap) and the tray tiles'
   glyphs and labels.

   Invariant 1: Tile → touchDown → TilePress.pointerDown → model.pick, all
   synchronous. The filled cells and the 🔊 pill are `onPointerDown` in the TSX
   too, hence `touchDown` and never a `Button`.
   ========================================================================== */

/// `AssemblyModel` as SpellSyllable instantiates it — the one engine whose slot
/// value is a `LetterFace` rather than a `String`, because the WRITING is part
/// of the answer in mixed rounds.
public typealias SpellSyllableModel = AssemblyModel<SyllableWord, SpellSyllableRound, LetterFace>

public struct SpellSyllableView: View {

    /* ---- authored metrics, from the TSX inline styles ------------------- */

    /// `<WordIcon size="clamp(48px,15vw,88px)" />` in a `margin: 2px 0` div.
    static let iconSize = FluidSpec(min: 48, vw: 15, max: 88)
    static let iconMarginY: CGFloat = 2

    /// A cell: `minWidth: clamp(40px,11vw,60px)`, `height: clamp(52px,14vw,72px)`,
    /// `padding: 0 6px`, `fontSize: clamp(24px,7vw,42px)`, `borderRadius: 16`.
    static let cellMinWidth = FluidSpec(min: 40, vw: 11, max: 60)
    static let cellHeight = FluidSpec(min: 52, vw: 14, max: 72)
    static let cellFontSize = FluidSpec(min: 24, vw: 7, max: 42)
    static let cellHorizontalPadding: CGFloat = 6
    static let cellCornerRadius: CGFloat = 16

    /// `marginLeft: c.syllableStart && i > 0 ? "clamp(8px,2.5vw,16px)" : 0` —
    /// « a small gap before each new syllable keeps the word's shape readable ».
    static let syllableGap = FluidSpec(min: 8, vw: 2.5, max: 16)

    /// `<FitLine ariaLabel="Mot à compléter" className="mb-6" rowClassName="gap-1.5">`.
    static let rowSpacing: CGFloat = 6
    static let rowMarginBottom: CGFloat = 24
    /// The listen button's `mb-4` — SpellSound authors `mb-5`. Not shared.
    static let listenMarginBottom: CGFloat = 16
    /// `m-0 mb-1` on the headline `<p>`.
    static let headlineMarginBottom: CGFloat = 4
    /// `px-4 pb-8 pt-2` on the content column.
    static let padding = EdgeInsets(top: 8, leading: 16, bottom: 32, trailing: 16)

    /// `relative z-[41]` — see `SpellSoundView.contentZIndex` for why this is
    /// authored and currently inert.
    static let contentZIndex: Double = 41

    /* ---- the rules this file owns (pure, host-tested) ------------------- */

    /// What one `SpellCell` renders — the TSX's three branches, as data.
    ///
    /// Note what `filled` carries: the glyph and script of the DROPPED TILE
    /// (`s.glyph`, `SCRIPT_FONT[s.script]`), never the cell's own — that is the
    /// whole point of a mixed round, where a wrong-writing letter must be
    /// visible in the row it spoiled until the « Oh non » wipes it. And the
    /// remove label names `s.base`, the canonical uppercase letter, not the
    /// glyph: « Retirer A » for a lowercase cursive « a ».
    enum CellRender: Equatable {
        /// `!c.fill` — already written, solid `#FFF3E0`, `aria-hidden`.
        case written(glyph: String, script: LetterScript)
        /// A gap nobody has filled yet: dashed, `aria-hidden`.
        case empty
        /// A gap holding a tile; tapping it sends the tile home.
        case filled(glyph: String, script: LetterScript, removeLabel: String)
    }

    static func render(cell: SpellCell, slots: [LetterFace?]) -> CellRender {
        guard cell.fill else {
            return .written(glyph: cell.glyph, script: cell.script)
        }
        // Defensive index guard — `slotIndex` is authored by
        // `buildSpellSyllableRound` and is always in range for a `fill` cell.
        // Reading out of bounds would crash a six-year-old's game; the TSX
        // would render an empty slot (`undefined`), and so does this.
        guard slots.indices.contains(cell.slotIndex), let face = slots[cell.slotIndex] else {
            return .empty
        }
        return .filled(
            glyph: face.glyph,
            script: face.script,
            removeLabel: Copy.Exercise.remove(face.base)
        )
    }

    /// `marginLeft` applies to a syllable's first letter EXCEPT the word's very
    /// first cell (`c.syllableStart && i > 0`).
    static func startsNewSyllable(_ cell: SpellCell, at index: Int) -> Bool {
        cell.syllableStart && index > 0
    }

    /// The face a tray tile drops into a slot — `{ base: t.letter, glyph:
    /// t.glyph, script: t.script }`. `base` is the canonical uppercase letter,
    /// so `sameFace` (glyph + script) is what judges the row.
    static func pickValue(_ tile: SpellLetterTile) -> LetterFace {
        LetterFace(base: tile.letter, glyph: tile.glyph, script: tile.script)
    }

    /// A tray tile's screen-reader label — `faceLabel(...)`, which names the
    /// case and (cursive only) the form: « Lettre A minuscule attachée ».
    static func trayLabel(_ tile: SpellLetterTile) -> String {
        faceLabel(pickValue(tile))
    }

    /* ---- inputs ---------------------------------------------------------- */

    /// The hub row that opened this run (`EXERCISES`' id) — the award key. Five
    /// rows share this view.
    public let exercise: ExerciseId
    /// Reaches `buildSpellSyllableRound` and the headline, nothing else.
    public let mode: SpellSyllableMode
    public let level: Int
    /// The « écritures mêlées » twin (`meta.mixed`).
    public let mixed: Bool
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

    /// D9: created ONCE, in `.task` — never in `init` or a `@State` default,
    /// which SwiftUI re-evaluates on every parent re-render.
    @State private var model: SpellSyllableModel?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - mixed: defaults to false, exactly as the TSX prop does
    ///     (`mixed = false` — the three plain rows pass `meta.mixed`,
    ///     which is `undefined` for them).
    public init(
        exercise: ExerciseId,
        mode: SpellSyllableMode,
        level: Int,
        mixed: Bool = false,
        mascot: MascotConfig,
        host: EngineHost,
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void,
        rng: RandomSource = .system()
    ) {
        self.exercise = exercise
        self.mode = mode
        self.level = level
        self.mixed = mixed
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
                if let confetti {
                    ConfettiOverlay(system: confetti)
                }
            },
            content: {
                if let model {
                    body(of: model)
                } else {
                    Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        )
        .task { start() }
        .onDisappear { model?.deactivate() }
    }

    @MainActor
    private func start() {
        let system = confetti ?? ConfettiSystem(reduceMotion: reduceMotion)
        if confetti == nil { confetti = system }
        if model == nil {
            model = .spellSyllable(
                exercise: exercise,
                mode: mode,
                level: level,
                mixed: mixed,
                deps: engineDeps(confetti: system),
                rng: rng
            )
        }
        model?.activate()
    }

    /// `award` is handed in whole — this file never computes a point
    /// (invariant 8).
    @MainActor
    private func engineDeps(confetti: ConfettiSystem) -> EngineDeps {
        host.deps(fireConfetti: { confetti.fire() })
    }

    @ViewBuilder
    private func body(of model: SpellSyllableModel) -> some View {
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
    }

    private func round(_ model: SpellSyllableModel) -> some View {
        VStack(spacing: 0) {
            Text(verbatim: model.headline ?? "")
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                .foregroundStyle(Palette.inkSoft.color)
                .padding(.bottom, Self.headlineMarginBottom)

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            WordIcon(
                emoji: model.round.word.emoji,
                img: model.round.word.img,
                size: Self.iconSize.resolve(viewport: viewport)
            )
            .padding(.vertical, Self.iconMarginY)

            SpellListenPill(accessibilityLabel: model.listenAccessibilityLabel) {
                model.replayPrompt()
            }
            .padding(.bottom, Self.listenMarginBottom)

            cellRow(model)
                .padding(.bottom, Self.rowMarginBottom)

            tray(model)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(Self.padding)
        .zIndex(Self.contentZIndex)
    }

    /// « The word, letter by letter: written letters + dashed slots for the
    /// gap. » One `FitLine` so the whole word always stays on one line.
    private func cellRow(_ model: SpellSyllableModel) -> some View {
        let metrics = SpellSlotMetrics(
            minWidth: Self.cellMinWidth.resolve(viewport: viewport),
            height: Self.cellHeight.resolve(viewport: viewport),
            horizontalPadding: Self.cellHorizontalPadding,
            fontSize: Self.cellFontSize.resolve(viewport: viewport),
            cornerRadius: Self.cellCornerRadius
        )
        let gap = Self.syllableGap.resolve(viewport: viewport)
        return FitLine(rowSpacing: Self.rowSpacing, accessibilityLabel: Copy.Exercise.wordToComplete) {
            ForEach(Array(model.round.cells.enumerated()), id: \.offset) { index, cell in
                self.cellView(cell, at: index, in: model, metrics: metrics)
                    .padding(.leading, Self.startsNewSyllable(cell, at: index) ? gap : 0)
            }
        }
    }

    @ViewBuilder
    private func cellView(
        _ cell: SpellCell,
        at index: Int,
        in model: SpellSyllableModel,
        metrics: SpellSlotMetrics
    ) -> some View {
        switch Self.render(cell: cell, slots: model.slots) {
        case .written(let glyph, let script):
            SpellSlotBox(face: .revealed, metrics: metrics) {
                Text(verbatim: glyph)
                    .font(Typography.letterFont(script, size: metrics.fontSize))
            }
            .accessibilityHidden(true)  // aria-hidden — the FitLine carries the label

        case .empty:
            SpellSlotBox(face: .empty, metrics: metrics) { Text(verbatim: "") }
                .accessibilityHidden(true)

        case .filled(let glyph, let script, let removeLabel):
            SpellSlotBox(face: .filled, metrics: metrics) {
                Text(verbatim: glyph)
                    .font(Typography.letterFont(script, size: metrics.fontSize))
            }
            .touchDown { model.removeAt(cell.slotIndex) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(removeLabel)
            .accessibilityAddTraits(.isButton)
        }
    }

    /// `<div className="flex flex-wrap items-center justify-center gap-3">` —
    /// the same tray chrome as SpellSound, with the glyph drawn in the tile's
    /// own script.
    private func tray(_ model: SpellSyllableModel) -> some View {
        ComponentsWrapRow(spacing: SpellTray.gap, lineSpacing: SpellTray.gap) {
            ForEach(Array(model.round.tray.enumerated()), id: \.element.id) { index, tile in
                Tile(
                    paint: SpellTray.paint(at: index),
                    disabled: model.isTrayTileUsed(id: tile.id),
                    size: SpellTray.size,
                    fontSize: SpellTray.fontSize,
                    accessibilityLabel: Self.trayLabel(tile),
                    onPick: { model.pick(tileID: tile.id, value: Self.pickValue(tile)) },
                    onPreview: { model.preview(say: tile.letter) },
                    previewLabel: Copy.Exercise.listenTile(tile.letter)
                ) {
                    Text(verbatim: tile.glyph)
                        .font(
                            Typography.letterFont(
                                tile.script,
                                size: SpellTray.fontSize.resolve(viewport: viewport)))
                }
            }
        }
    }
}
