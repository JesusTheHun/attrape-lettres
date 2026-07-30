import ALCore
import SwiftUI

/* --------------------------------------------------------------------------
   `src/exercises/SyllableGridExercise.tsx` — ONE engine over the « tableau des
   syllabes », two ways round:

     hear   the syllable is spoken, the tiles WRITE it (VA VE VI VO VU VÉ).
     vowel  the syllable is spoken, its consonant is already written, and the
            tiles are the vowels: the child places the one that finishes it.

   ONE view, as CLAUDE.md requires: mode changes the distractor rule (which is
   `ALCore.buildGridRound`'s job, not this file's) and WHAT A TILE SHOWS. It
   never changes the loop, and `vowel` mode is not a second engine — it adds the
   half-written syllable above the listen button and swaps the tile's glyph.

   The trap this file exists to make impossible: a `vowel`-mode tile SHOWS
   « A » but is judged, labelled and flashed by « VA ». Shown text and pick key
   are two different functions below, and both are tested.
   -------------------------------------------------------------------------- */

// MARK: - Authored metrics (SyllableGridExercise.tsx, verbatim)

public enum SyllableGridMetrics {
    /// This file's own `TILE_COLORS` — four paints, and the fourth green is
    /// NOT the shared ramp's (`#A5D6A7`/`#123B18`, not `#AED581`/`#213606`).
    /// `Palette.gridTileColors` is that authored exception.
    public static let tileColorCount = 4

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        Palette.gridTileColors[index % tileColorCount]
    }

    /* ---- The half-written syllable (`vowel` mode only) --------------------- */

    /// `style={{ fontSize: "clamp(38px,11vw,64px)" }}` on the whole group.
    public static let syllableFontSize = FluidSpec(min: 38, vw: 11, max: 64)

    /// `mt-2` on the group.
    public static let halfSyllableTop: CGFloat = 8
    /// `gap-2` between the consonant and the gap.
    public static let halfSyllableGap: CGFloat = 8

    /// The gap box, in `em` of the group's font size: `minWidth: 0.9em`,
    /// `height: 1.1em`, `padding: 0 0.1em`.
    public static let gapMinWidthEm: CGFloat = 0.9
    public static let gapHeightEm: CGFloat = 1.1
    public static let gapPaddingXEm: CGFloat = 0.1
    /// `borderRadius: 16`.
    public static let gapCornerRadius: CGFloat = 16
    /// `border: 4px dashed #E4A15E` while empty.
    public static let gapBorderWidth: CGFloat = 4

    /// What the gap shows and how it is dressed.
    ///
    /// The TSX is `flash ? … : …` — TRUTHINESS of the flash key, not a
    /// comparison with the target. This port compares (D45), which today is
    /// **exactly equivalent**, and that equivalence is the reason the change is
    /// safe rather than the reason it is pointless:
    ///
    /// `SyllableGridExercise.tsx` calls `setFlash(text)` only AFTER the
    /// `text !== round.target.text` early return, so `flash` is non-nil if and
    /// only if the pick was correct, and then it IS `round.target.text`. Both
    /// forms therefore fill the gap on exactly the same picks. The original
    /// D36 note claimed a wrong pick could fill the gap with the target's
    /// vowel; re-reading the TSX and `SinglePickModel.pick` shows it cannot —
    /// a miss returns `.reject` before `flash` is ever assigned.
    ///
    /// What comparing buys is that it stays true. Truthiness only became
    /// harmless because of a guard three files away; if `flash` is ever set on
    /// a wrong pick (to highlight what the child tapped, say) the truthy form
    /// silently starts showing the ANSWER on a miss, in a game whose invariant 3
    /// is that a wrong tap costs nothing and reveals nothing.
    public struct Gap: Equatable, Sendable {
        /// `{flash ? round.target.vowel : ""}` — filled only for the target.
        public let text: String
        /// `background: flash ? "#FFFFFF" : "transparent"`, and the dashed
        /// border / drop shadow follow the same flag.
        public let filled: Bool
    }

    /// `SyllableGridExercise.tsx`'s gap span, as data.
    public static func gap(target: GridSyllable, flash: String?) -> Gap {
        flash == target.text ? Gap(text: target.vowel, filled: true) : Gap(text: "", filled: false)
    }

    /// `aria-label={`Syllabe à compléter : ${round.target.consonant}`}` on the
    /// GROUP (the gap span itself is `aria-hidden`), so VoiceOver announces the
    /// consonant and the task, never an empty box.
    public static func halfSyllableLabel(_ target: GridSyllable) -> String {
        Copy.Exercise.syllableToComplete(target.consonant)
    }

    /* ---- The listen button ------------------------------------------------- */

    /// `mt-3` / `mb-6` on the listen button.
    public static let listenTop: CGFloat = 12
    public static let listenBottom: CGFloat = 24

    /* ---- The tiles -------------------------------------------------------- */

    /// `gap-3`.
    public static let tileGap: CGFloat = 12
    /// `size="clamp(62px,17vw,96px)"`.
    public static let tileSize = FluidSpec(min: 62, vw: 17, max: 96)
    /// `fontSize="clamp(26px,7vw,46px)"`.
    public static let tileFontSize = FluidSpec(min: 26, vw: 7, max: 46)

    /// `{mode === "vowel" ? choice.vowel : choice.text}` — what the tile SHOWS.
    public static func tileFace(_ choice: GridSyllable, mode: SyllableGridMode) -> String {
        mode == .vowel ? choice.vowel : choice.text
    }

    /// `onPick={() => pick(choice.text)}` — the pick key, the flash key and the
    /// judge, in BOTH modes. Never the shown face: in `vowel` mode a tile reads
    /// « A » and picks « VA ».
    public static func pickKey(_ choice: GridSyllable) -> String {
        choice.text
    }

    /// `ariaLabel={`Syllabe ${choice.text}`}` — the FULL syllable in both
    /// modes, so a screen-reader user of `vowel` mode hears « Syllabe VA », not
    /// « Syllabe A ».
    public static func tileLabel(_ choice: GridSyllable) -> String {
        Copy.Exercise.syllableTile(choice.text)
    }

    /// `previewLabel={`Écouter ${choice.text}`}`.
    public static func previewLabel(_ choice: GridSyllable) -> String {
        Copy.Exercise.listenTile(choice.text)
    }

    /// `onPreview={() => audio.say(choice.sound)}` — the lowercase spoken form
    /// of the WHOLE syllable, in both modes. Hearing VA next to VI is the point
    /// of the drill, so a `vowel`-mode tile does NOT audition its bare vowel.
    public static func previewText(_ choice: GridSyllable) -> String {
        choice.sound
    }
}

// MARK: - The view

/// `SyllableGridExercise`. `exercise` and `mode` are the TSX props: the hub
/// passes `ExerciseMeta.grid` (`.hear` for « Écoute la syllabe », `.vowel` for
/// « La bonne voyelle »).
@MainActor
public struct SyllableGridView: View {
    private let exercise: ExerciseId
    private let mode: SyllableGridMode
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    @State private var model: SinglePickModel<GridRound>?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - mode: the TSX `meta.grid` prop — `.hear` for « Écoute la syllabe »,
    ///     `.vowel` for « La bonne voyelle ». It reaches the session builder,
    ///     the headline and the tile's face; nothing else.
    ///   - deps: `fireConfetti` is replaced with this run's system (see
    ///     `SoundEngineChrome.wire`).
    public init(
        exercise: ExerciseId,
        mode: SyllableGridMode,
        level: Int,
        host: EngineHost,
        mascot: MascotConfig,
        rng: RandomSource = .system(),
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
        self.exercise = exercise
        self.mode = mode
        self.level = level
        self.host = host
        self.mascot = mascot
        self.rng = rng
        self.onBack = onBack
        self.onNext = onNext
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
                    Color.clear
                }
            }
        )
        .task {
            if model == nil {
                let system = ConfettiSystem(reduceMotion: reduceMotion)
                confetti = system
                model = .syllableGrid(
                    exercise: exercise,
                    mode: mode,
                    level: level,
                    deps: SoundEngineChrome.wire(host, to: system),
                    rng: rng
                )
            }
            model?.activate()
        }
        .onDisappear { model?.deactivate() }
    }

    // MARK: the round

    private func round(_ model: SinglePickModel<GridRound>) -> some View {
        VStack(spacing: 0) {
            if let headline = model.headline {
                SoundEngineChrome.consigne(headline)
            }

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            // `{mode === "vowel" && …}` — the syllable half-written, so the
            // whole thing is read once, complete, before moving on.
            if mode == .vowel {
                halfSyllable(model)
                    .padding(.top, SyllableGridMetrics.halfSyllableTop)
            }

            SoundEngineChrome.listenButton(
                text: model.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )
            .padding(.top, SyllableGridMetrics.listenTop)
            .padding(.bottom, SyllableGridMetrics.listenBottom)

            tiles(model)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, SoundEngineChrome.columnPaddingX)
        .padding(.top, SoundEngineChrome.columnPaddingTop)
        .padding(.bottom, SoundEngineChrome.columnPaddingBottom)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .zIndex(SoundEngineChrome.contentZIndex)
    }

    /// The consonant + the gap the child fills. `aria-label` on the group, the
    /// gap span `aria-hidden` — so VoiceOver says « Syllabe à compléter : V »
    /// and nothing else.
    private func halfSyllable(_ model: SinglePickModel<GridRound>) -> some View {
        let target = model.current.target
        let size = SyllableGridMetrics.syllableFontSize.resolve(viewport: viewport)
        let gap = SyllableGridMetrics.gap(target: target, flash: model.flash)
        return HStack(spacing: SyllableGridMetrics.halfSyllableGap) {
            Text(verbatim: target.consonant)
            gapBox(gap, size: size)
        }
        .font(Typography.rounded(size, Typography.Weight.black))
        .foregroundStyle(Palette.ink.color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(SyllableGridMetrics.halfSyllableLabel(target))
    }

    @ViewBuilder
    private func gapBox(_ gap: SyllableGridMetrics.Gap, size: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: SyllableGridMetrics.gapCornerRadius)
        Text(verbatim: gap.text)
            .padding(.horizontal, size * SyllableGridMetrics.gapPaddingXEm)
            // `box-sizing: border-box` (Tailwind preflight): the authored
            // minWidth/height include the padding and the border.
            .frame(minWidth: size * SyllableGridMetrics.gapMinWidthEm)
            .frame(height: size * SyllableGridMetrics.gapHeightEm)
            .background {
                if gap.filled {
                    // background "#FFFFFF", no border,
                    // boxShadow 0 6px 14px rgba(0,0,0,0.12)
                    shape
                        .fill(Color.white)
                        .shadow(color: .black.opacity(0.12), radius: 7, y: 6)
                } else {
                    // 4px dashed #E4A15E on transparent, no shadow.
                    shape.strokeBorder(
                        Palette.slotDashed.color,
                        style: StrokeStyle(
                            lineWidth: SyllableGridMetrics.gapBorderWidth,
                            dash: SoundEngineChrome.dash(
                                width: SyllableGridMetrics.gapBorderWidth)
                        )
                    )
                }
            }
            .accessibilityHidden(true)
    }

    /// `flex flex-wrap items-center justify-center gap-3`.
    private func tiles(_ model: SinglePickModel<GridRound>) -> some View {
        ComponentsWrapRow(
            spacing: SyllableGridMetrics.tileGap,
            lineSpacing: SyllableGridMetrics.tileGap
        ) {
            ForEach(SoundEngineChrome.slots(model.current.choices, id: \.text)) { slot in
                let choice = slot.value
                let key = SyllableGridMetrics.pickKey(choice)
                Tile(
                    paint: SyllableGridMetrics.paint(at: slot.index),
                    disabled: model.tilesDisabled,
                    highlight: model.flash == key,
                    size: SyllableGridMetrics.tileSize,
                    fontSize: SyllableGridMetrics.tileFontSize,
                    accessibilityLabel: SyllableGridMetrics.tileLabel(choice),
                    onPick: {
                        // Invariant 1: synchronous, inside the touch-down call.
                        model.pick(key)
                    },
                    onPreview: { model.preview(say: SyllableGridMetrics.previewText(choice)) },
                    previewLabel: SyllableGridMetrics.previewLabel(choice),
                    content: {
                        Text(verbatim: SyllableGridMetrics.tileFace(choice, mode: mode))
                    }
                )
            }
        }
    }
}
