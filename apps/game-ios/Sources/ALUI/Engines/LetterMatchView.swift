import SwiftUI

import ALArt
import ALCore

/* ==========================================================================
   `src/exercises/LetterMatchExercise.tsx` — ONE engine, two exercises:

     match-case    (kind `.case`)   majuscule ⇄ minuscule, both in print
     match-script  (kind `.script`) print ⇄ cursive, at ONE shared case

   « The prompt never names the target, so the child must read the shape; the
     name is spoken only on success as reinforcement. » Both of those lines are
   the model's (`Levels.letterMatchPrompt` / `Levels.letterMatchSuccess`); what
   this file owns is that the prompt is DRAWN in its own script, that a tile is
   drawn in the counterpart script, and that both carry `ALCore.faceLabel` as
   their accessibility label — « Lettre A majuscule », « Lettre a minuscule
   attachée » — because a screen reader cannot see a letterform.

   The judge is `face.base`, i.e. the letter's IDENTITY, not its form: the tiles
   are all in the counterpart form already, so the child's job is to recognise
   WHICH letter, and any tile whose base matches wins. That lives in the
   descriptor (`targetKey: { $0.prompt.base }`); this file just hands `pick` the
   same `base` it labels.
   ========================================================================== */

// MARK: - What a letter-form tile is, as data

/// One counterpart-form tile. `face.base` is the pick key; `face.glyph` +
/// `face.script` are what gets drawn.
public struct LetterMatchTile: Equatable, Sendable {
    public let face: LetterFace
    public let paint: Palette.TilePaint
    /// `ariaLabel={faceLabel(face)}` — never « Lettre A »: the FORM is the task.
    public let label: String
    /// `previewLabel={`Écouter ${face.base}`}`; the preview speaks `face.base`.
    public let previewLabel: String

    /// `onPick={() => pick(face)}` → judged on `face.base !== round.prompt.base`.
    public var pickKey: String { face.base }
}

/// The letter-match row's data rules.
public enum LetterMatchStage {
    /// `TILE_COLORS` in `LetterMatchExercise.tsx`: the first FOUR paints of the
    /// shared ramp, cycled `i % 4`. The fourth is `#AED581/#213606` — the
    /// standard green, NOT the lighter `#A5D6A7/#123B18` the syllable grid
    /// substitutes at the same index.
    public static let palette: [Palette.TilePaint] = Array(Palette.tileColors.prefix(4))

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        palette[index % palette.count]
    }

    public static func tiles(for round: LetterMatchRound) -> [LetterMatchTile] {
        round.choices.enumerated().map { index, face in
            LetterMatchTile(
                face: face,
                paint: paint(at: index),
                label: faceLabel(face),
                previewLabel: Copy.Exercise.listenTile(face.base)
            )
        }
    }

    /// `style={{ fontSize: "clamp(80px,28vw,150px)" }}` on the prompt glyph.
    public static let promptSize = FluidSpec(min: 80, vw: 28, max: 150)

    /* ---- The view's wiring, as functions (see FirstLetterStage) ----------- */

    /// `onPick={() => pick(face)}` → judged on `face.base`, the letter's
    /// IDENTITY. Keying this on `face.glyph` would make every majuscule→
    /// minuscule round unwinnable, which is why it is a tested function and not
    /// a line inside `body`.
    @MainActor
    public static func pick(
        _ tile: LetterMatchTile,
        in model: SinglePickModel<LetterMatchRound>
    ) -> Verdict {
        model.pick(tile.pickKey)
    }

    /// `onPreview={() => { audio.unlock(); void audio.say(face.base); }}` — the
    /// letter's NAME, never the glyph (« a » is not a spoken word).
    @MainActor
    public static func preview(
        _ tile: LetterMatchTile,
        in model: SinglePickModel<LetterMatchRound>
    ) {
        model.preview(say: tile.face.base)
    }

    /// `highlight={flash === face.base}`.
    @MainActor
    public static func isHighlighted(
        _ tile: LetterMatchTile,
        in model: SinglePickModel<LetterMatchRound>
    ) -> Bool {
        model.flash == tile.pickKey
    }
}

// MARK: - The view

@MainActor
public struct LetterMatchView: View {
    private let exercise: ExerciseId
    private let kind: LetterMatchKind
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// D9 — see `FirstLetterView`.
    @State private var model: SinglePickModel<LetterMatchRound>?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - exercise: `match-case` or `match-script` — it keys the reward ledger,
    ///     so it travels with `kind` rather than being derived from it.
    ///   - kind: the `EXERCISES` row's `match` field.
    public init(
        exercise: ExerciseId,
        kind: LetterMatchKind,
        level: Int,
        host: EngineHost,
        mascot: MascotConfig,
        rng: RandomSource = .system(),
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
        self.exercise = exercise
        self.kind = kind
        self.level = level
        self.host = host
        self.mascot = mascot
        self.rng = rng
        self.onBack = onBack
        self.onNext = onNext
    }

    public var body: some View {
        LettersRunFrame(
            runner: model,
            confetti: confetti,
            onBack: onBack,
            onNext: onNext
        ) { model in
            round(model)
        }
        .task {
            if model == nil {
                let system = ConfettiSystem(reduceMotion: reduceMotion)
                confetti = system
                let wired = host.deps(fireConfetti: { system.fire() })
                model = .letterMatch(
                    exercise: exercise, kind: kind, level: level, deps: wired, rng: rng)
            }
            model?.activate()
        }
        .onDisappear { model?.deactivate() }
    }

    @ViewBuilder
    private func round(_ model: SinglePickModel<LetterMatchRound>) -> some View {
        let prompt = model.current.prompt
        let promptSize = LetterMatchStage.promptSize.resolve(viewport: viewport)
        LettersStage {
            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            // The letter to read, in ITS OWN script. Labelled, because the form
            // is invisible to VoiceOver (invariant 6).
            Text(verbatim: prompt.glyph)
                .font(Typography.letterFont(prompt.script, size: promptSize))
                .foregroundStyle(Palette.ink.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: promptSize,
                        ratio: LetterStageMetrics.promptLineHeight
                    )
                )
                .padding(.vertical, LetterStageMetrics.promptMargin)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(faceLabel(prompt))

            LettersListenPill(
                text: model.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )

            LettersTileRow {
                ForEach(LetterMatchStage.tiles(for: model.current), id: \.pickKey) { tile in
                    Tile(
                        paint: tile.paint,
                        disabled: model.tilesDisabled,
                        highlight: LetterMatchStage.isHighlighted(tile, in: model),
                        accessibilityLabel: tile.label,
                        // Invariant 1: synchronous, at touch-down.
                        onPick: { LetterMatchStage.pick(tile, in: model) },
                        onPreview: { LetterMatchStage.preview(tile, in: model) },
                        previewLabel: tile.previewLabel
                    ) {
                        // `<span style={{ fontFamily: SCRIPT_FONT[face.script] }}>`
                        // inside the tile — inherits the tile's font size, swaps
                        // only the family.
                        Text(verbatim: tile.face.glyph)
                            .font(
                                Typography.letterFont(
                                    tile.face.script,
                                    size: TileMetrics.defaultFontSize.resolve(viewport: viewport)
                                )
                            )
                    }
                }
            }
        }
    }
}
