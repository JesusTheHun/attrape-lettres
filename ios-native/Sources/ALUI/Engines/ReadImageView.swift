import SwiftUI

import ALArt
import ALCore

/* ==========================================================================
   `src/exercises/ReadImageExercise.tsx` — the mirror of FirstLetter: the WORD
   is printed and the child taps the picture that matches it, so READING the
   word is the task.

   Two consequences the layout has to honour, both from the TSX comment:

     « The word to READ — the whole task. Never spoken, so the child works
       from the letters, not the ear. FitLine keeps it on a single line,
       shrinking if needed. »

   - the word is never spoken: the consigne is the constant
     `Levels.readImagePrompt` (« Trouve la bonne image. »), and only the
     SUCCESS line names the word. That is the model's business
     (`SinglePickModel.readImage`); this file must simply never hand the word
     to `preview`/`replayPrompt`.
   - the word goes in a `FitLine`, because « Escargot » at
     `clamp(38px,11vw,68px)` does not fit a narrow phone on one line.

   The one asymmetry with its four siblings: ReadImage's tile preview IS
   `locked`-guarded (the others are not). That guard lives in the descriptor
   (`previewGuardedByLock: true`), so the view calls `model.preview(say:)`
   identically in all five engines.
   ========================================================================== */

// MARK: - What a read-the-word tile is, as data

/// One picture tile. `word` is the pick key (`SinglePickModel.readImage`
/// judges `$0.target.word`) AND what the preview speaks.
public struct ReadImageTile: Equatable, Sendable {
    /// `choice.word` — the pick key, the preview text, and the row's identity.
    public let word: String
    /// The picture: the dedicated drawing when the word has one, else the emoji
    /// (`WordIcon`'s own rule).
    public let emoji: String
    public let img: ImageKey?
    public let paint: Palette.TilePaint
    /// `ariaLabel={`Image : ${choice.word}`}`.
    public let label: String
    /// `previewLabel={`Écouter ${choice.word}`}`.
    public let previewLabel: String
}

/// The read-the-word row's data rules.
public enum ReadImageStage {
    /// `TILE_COLORS` in `ReadImageExercise.tsx`: ALL FIVE paints of the shared
    /// ramp, cycled `i % 5` (level 4 shows five pictures).
    public static let palette: [Palette.TilePaint] = Palette.tileColors

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        palette[index % palette.count]
    }

    public static func tiles(for round: ReadImageRound) -> [ReadImageTile] {
        round.choices.enumerated().map { index, choice in
            ReadImageTile(
                word: choice.word,
                emoji: choice.emoji,
                img: choice.img,
                paint: paint(at: index),
                label: Copy.Exercise.imageTile(choice.word),
                previewLabel: Copy.Exercise.listenTile(choice.word)
            )
        }
    }

    /// `className="… uppercase …"` on the printed word. The content stores
    /// « Escargot »; the child reads « ESCARGOT ».
    ///
    /// Non-locale `uppercased()` on purpose, as in `ALCore.faceLabel` — a
    /// Turkish locale would map « i » to « İ ».
    public static func displayWord(_ round: ReadImageRound) -> String {
        round.target.word.uppercased()
    }

    /// `style={{ fontSize: "clamp(38px,11vw,68px)" }}` on the word.
    public static let wordSize = FluidSpec(min: 38, vw: 11, max: 68)

    /// `<WordIcon … size="clamp(60px,19vw,104px)" />` inside a tile.
    public static let pictureSize = FluidSpec(min: 60, vw: 19, max: 104)

    /// `className="m-0 mb-1 …"` on the consigne line above the mascot.
    public static let headlineBottomMargin: CGFloat = 4
    /// `<FitLine className="my-1.5">`.
    public static let wordMargin: CGFloat = 6

    /* ---- The view's wiring, as functions (see FirstLetterStage) ----------- */

    /// `onPick={() => pick(choice)}` → judged on `choice.word`.
    @MainActor
    public static func pick(
        _ tile: ReadImageTile,
        in model: SinglePickModel<ReadImageRound>
    ) -> Verdict {
        model.pick(tile.word)
    }

    /// `onPreview={() => { if (locked.current) return; audio.unlock(); void audio.say(choice.word); }}`
    /// — the `locked` guard is the descriptor's (`previewGuardedByLock: true`),
    /// which is why this reads exactly like its four siblings.
    @MainActor
    public static func preview(
        _ tile: ReadImageTile,
        in model: SinglePickModel<ReadImageRound>
    ) {
        model.preview(say: tile.word)
    }

    /// `highlight={flash === choice.word}`.
    @MainActor
    public static func isHighlighted(
        _ tile: ReadImageTile,
        in model: SinglePickModel<ReadImageRound>
    ) -> Bool {
        model.flash == tile.word
    }
}

// MARK: - The view

@MainActor
public struct ReadImageView: View {
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// D9 — see `FirstLetterView`.
    @State private var model: SinglePickModel<ReadImageRound>?
    @State private var confetti: ConfettiSystem?

    public init(
        level: Int,
        host: EngineHost,
        mascot: MascotConfig,
        rng: RandomSource = .system(),
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
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
                model = .readImage(level: level, deps: wired, rng: rng)
            }
            model?.activate()
        }
        .onDisappear { model?.deactivate() }
    }

    @ViewBuilder
    private func round(_ model: SinglePickModel<ReadImageRound>) -> some View {
        LettersStage {
            if let headline = model.headline {
                Text(verbatim: headline)
                    .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                    .foregroundStyle(Palette.inkSoft.color)
                    .padding(.bottom, ReadImageStage.headlineBottomMargin)
            }

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            // The word to READ. One line, shrunk rather than wrapped.
            FitLine {
                let size = ReadImageStage.wordSize.resolve(viewport: viewport)
                Text(verbatim: ReadImageStage.displayWord(model.current))
                    .font(Typography.rounded(size, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
                    .lineSpacing(
                        Typography.lineSpacing(
                            size: size,
                            ratio: LetterStageMetrics.promptLineHeight
                        )
                    )
                    .multilineTextAlignment(.center)
            }
            .padding(.vertical, ReadImageStage.wordMargin)

            LettersListenPill(
                text: model.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )

            LettersTileRow {
                ForEach(ReadImageStage.tiles(for: model.current), id: \.word) { tile in
                    Tile(
                        paint: tile.paint,
                        disabled: model.tilesDisabled,
                        highlight: ReadImageStage.isHighlighted(tile, in: model),
                        accessibilityLabel: tile.label,
                        // Invariant 1: synchronous, at touch-down.
                        onPick: { ReadImageStage.pick(tile, in: model) },
                        onPreview: { ReadImageStage.preview(tile, in: model) },
                        previewLabel: tile.previewLabel
                    ) {
                        WordIcon(
                            emoji: tile.emoji,
                            img: tile.img,
                            size: ReadImageStage.pictureSize.resolve(viewport: viewport)
                        )
                    }
                }
            }
        }
    }
}
