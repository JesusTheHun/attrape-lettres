import SwiftUI

import ALArt
import ALCore

/* ==========================================================================
   `src/exercises/FirstLetterExercise.tsx` — hear « Trouve la première lettre
   de X. », tap the letter.

   THE VIEW HOLDS NO RULE. Everything that decides anything —  the session, the
   judge, the cooldown swallow, the star greying, the award — lives in
   `SinglePickModel.firstLetter` (see `RoundRunner.swift`). What lives here is
   the layout, the palette cycle, and the strings a tile carries; the two of
   those that are DATA (which paint a tile at index i wears, what its labels
   say) are extracted into `FirstLetterStage` so they are host-testable without
   a renderer.

   Invariant 1: the pick path is `Tile` → `touchDown` → `TilePress.pointerDown`
   → `model.pick(letter)`, synchronously. Nothing in this file defers a pick
   behind `@State`, a `Task` or an animation.

   This file also carries the chrome the three letter-form engines share
   (`LettersRunFrame`, `LettersStage`, `LettersTileRow`, `LettersListenPill`).
   The TSX duplicates that chrome in all nine exercises; here it is written
   once and the numbers are the Tailwind classes, resolved:

       relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2
       … gap-4 on the tile row, mb-6 under the 🔊 pill
   ========================================================================== */

// MARK: - Shared chrome (the letter family's port of the TSX exercise column)

/// The exercise column's authored metrics — the Tailwind classes above, in
/// points, with the class each number came from.
public enum LetterStageMetrics {
    /// `px-4`.
    public static let paddingX: CGFloat = 16
    /// `pt-2`.
    public static let paddingTop: CGFloat = 8
    /// `pb-8`.
    public static let paddingBottom: CGFloat = 32
    /// `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`).
    public static let zIndex: Double = 41
    /// `gap-4` on `flex flex-wrap items-center justify-center` (both axes).
    public static let tileGap: CGFloat = 16
    /// `mb-6` on the 🔊 pill.
    public static let listenBottomMargin: CGFloat = 24
    /// `px-5` / `py-2` on the 🔊 pill. One source of truth (`ListenPill`, D55);
    /// re-exported here because the TSX authors the class list per engine and
    /// the audit reads engine by engine.
    public static let listenPaddingX = ListenPillMetrics.paddingX
    public static let listenPaddingY = ListenPillMetrics.paddingY
    /// Tailwind `shadow` on the 🔊 pill:
    /// `0 1px 3px rgba(0,0,0,0.1), 0 1px 2px -1px rgba(0,0,0,0.1)`.
    public static let listenShadow = ListenPillMetrics.shadow
    public static let listenShadowTight = ListenPillMetrics.shadowTight
    /// `style={{ margin: "6px 0" }}` around FirstLetter's picture and
    /// LetterMatch's prompt glyph.
    public static let promptMargin: CGFloat = 6
    /// `lineHeight: 1.1` on the big prompt glyph / the printed word.
    public static let promptLineHeight: CGFloat = 1.1
}

/// `<GameFrame …>{done ? <Finished …/> : <div className="…">…</div>}</GameFrame>`
/// — the outer shell every single-pick exercise renders, over any
/// `RoundRunner`. `runner == nil` is the one frame before `.task` seeds the
/// model (D9: the session must be built exactly once, so it cannot be built in
/// a `@State` default).
@MainActor
struct LettersRunFrame<Runner: RoundRunner, Content: View>: View {
    let runner: Runner?
    let confetti: ConfettiSystem?
    let onBack: () -> Void
    let onNext: () -> Void
    @ViewBuilder let content: (Runner) -> Content

    @Environment(\.alReduceMotion) private var reduceMotion

    var body: some View {
        GameFrame(
            onBack: onBack,
            done: runner?.progressDone ?? 0,
            total: runner?.totalRounds ?? 0,
            stars: runner?.stars ?? [],
            overlay: {
                if let confetti {
                    ConfettiOverlay(system: confetti)
                }
            },
            content: {
                if let runner {
                    if runner.done {
                        Finished(
                            stars: runner.stars,
                            earned: runner.earned,
                            title: runner.finishedTitle,
                            reduceMotion: reduceMotion,
                            onMenu: onBack,
                            onNext: onNext
                        )
                    } else {
                        content(runner)
                    }
                } else {
                    Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        )
    }
}

/// The round column:
/// `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`.
/// No `gap` — the spacing between the mascot, the picture, the pill and the
/// tiles comes from the children's own margins, exactly as in the TSX.
@MainActor
struct LettersStage<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.horizontal, LetterStageMetrics.paddingX)
            .padding(.top, LetterStageMetrics.paddingTop)
            .padding(.bottom, LetterStageMetrics.paddingBottom)
            .zIndex(LetterStageMetrics.zIndex)
    }
}

/// `<div className="flex flex-wrap items-center justify-center gap-4">` — the
/// choice row. Wraps like the flexbox, centres each line, never scrolls (a
/// scroll view's touch delay would break invariant 1 — engines.md §4.4).
@MainActor
struct LettersTileRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ComponentsWrapRow(
            spacing: LetterStageMetrics.tileGap,
            lineSpacing: LetterStageMetrics.tileGap
        ) {
            content
        }
    }
}

/// The big 🔊 pill under the mascot, with the letter family's `mb-6`.
///
/// `ListenPill` (D55) is the button; this adds the margin and nothing else. The
/// margin is applied OUTSIDE the pill, because `mb-6` sits outside the button's
/// box on the web and must not become part of the tap target.
@MainActor
struct LettersListenPill: View {
    let text: String
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        ListenPill(text: text, accessibilityLabel: accessibilityLabel, action: action)
            .padding(.bottom, LetterStageMetrics.listenBottomMargin)
    }
}

// MARK: - What a first-letter tile is, as data

/// One tile of the choice row. `letter` is BOTH the glyph and the pick key —
/// `SinglePickModel.firstLetter` judges `$0.target.letter`, so handing it
/// anything else would silently make every round unwinnable.
public struct FirstLetterTile: Equatable, Sendable {
    /// The glyph shown, the pick key, and what the preview speaks (the TSX
    /// calls `audio.say(letter)`).
    public let letter: String
    public let paint: Palette.TilePaint
    /// `ariaLabel={`Lettre ${letter}`}`.
    public let label: String
    /// `previewLabel={`Écouter ${letter}`}`.
    public let previewLabel: String
}

/// The first-letter row's data rules — the part of this view a test can reach.
public enum FirstLetterStage {
    /// `TILE_COLORS` in `FirstLetterExercise.tsx`: the FIRST THREE paints of the
    /// shared ramp, cycled `i % 3`. The length is the cycle, so it is not
    /// interchangeable with ReadImage's five or LetterMatch's four.
    public static let palette: [Palette.TilePaint] = Array(Palette.tileColors.prefix(3))

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        palette[index % palette.count]
    }

    /// `round.choices.map((letter, i) => <Tile …>{letter}</Tile>)`.
    public static func tiles(for round: FirstLetterRound) -> [FirstLetterTile] {
        round.choices.enumerated().map { index, letter in
            FirstLetterTile(
                letter: letter,
                paint: paint(at: index),
                label: Copy.Exercise.letterTile(letter),
                previewLabel: Copy.Exercise.listenTile(letter)
            )
        }
    }

    /// `<WordIcon … size="clamp(80px,28vw,150px)" />`.
    public static let pictureSize = FluidSpec(min: 80, vw: 28, max: 150)

    /* ---- The three lines the view body would otherwise hide ---------------
       `onPick`, `onPreview` and `highlight` are the whole of the view's
       wiring, and a `body` is not reachable from `swift test`. They live here
       as functions so the key handed to `pick`, the text handed to `preview`
       and the flash comparison are all asserted on the host — a mis-keyed row
       would make every round unwinnable and nothing else would notice. */

    /// `onPick={() => pick(letter)}` — synchronous, at touch-down (invariant 1).
    @MainActor
    public static func pick(
        _ tile: FirstLetterTile,
        in model: SinglePickModel<FirstLetterRound>
    ) -> Verdict {
        model.pick(tile.letter)
    }

    /// `onPreview={() => { audio.unlock(); void audio.say(letter); }}`.
    @MainActor
    public static func preview(
        _ tile: FirstLetterTile,
        in model: SinglePickModel<FirstLetterRound>
    ) {
        model.preview(say: tile.letter)
    }

    /// `highlight={flash === letter}`.
    @MainActor
    public static func isHighlighted(
        _ tile: FirstLetterTile,
        in model: SinglePickModel<FirstLetterRound>
    ) -> Bool {
        model.flash == tile.letter
    }
}

// MARK: - The view

@MainActor
public struct FirstLetterView: View {
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// D9 — seeded in `.task`, never in a `@State` default (SwiftUI re-evaluates
    /// those on every parent re-render, which would rebuild the session).
    @State private var model: SinglePickModel<FirstLetterRound>?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - deps: the audio channel, the clock and `award`. `fireConfetti` is
    ///     REPLACED here with this run's `ConfettiSystem.fire` (the TSX calls
    ///     `useConfetti()` once per exercise); whatever the caller passed for it
    ///     is ignored.
    ///   - mascot: the TSX `profile.config`.
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
                model = .firstLetter(level: level, deps: wired, rng: rng)
            }
            model?.activate()
        }
        .onDisappear { model?.deactivate() }
    }

    @ViewBuilder
    private func round(_ model: SinglePickModel<FirstLetterRound>) -> some View {
        let target = model.current.target
        LettersStage {
            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            WordIcon(
                emoji: target.emoji,
                img: target.img,
                size: FirstLetterStage.pictureSize.resolve(viewport: viewport)
            )
            .padding(.vertical, LetterStageMetrics.promptMargin)

            LettersListenPill(
                text: model.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )

            LettersTileRow {
                ForEach(FirstLetterStage.tiles(for: model.current), id: \.letter) { tile in
                    Tile(
                        paint: tile.paint,
                        disabled: model.tilesDisabled,
                        highlight: FirstLetterStage.isHighlighted(tile, in: model),
                        accessibilityLabel: tile.label,
                        // Invariant 1: synchronous, at touch-down.
                        onPick: { FirstLetterStage.pick(tile, in: model) },
                        onPreview: { FirstLetterStage.preview(tile, in: model) },
                        previewLabel: tile.previewLabel
                    ) {
                        Text(verbatim: tile.letter)
                    }
                }
            }
        }
    }
}
