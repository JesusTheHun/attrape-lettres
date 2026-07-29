import ALCore
import SwiftUI

/* --------------------------------------------------------------------------
   `src/exercises/FindSoundExercise.tsx` — hear a sound with its anchor
   (« ou, comme dans hibou »), tap the tile that WRITES it.

   The rules live in `SinglePickModel` (the pick loop, the cooldown swallow, the
   star greying, the announce timer, the award). This file is the PROJECTION:
   it reads state, hands the model a key at touch-down, and owns nothing but
   layout, palette and the two accessibility labels the model does not build.

   Invariant 1 — the pick path is `Tile.touchDown` → `TilePress.pointerDown` →
   `model.pick(graphy)`, all synchronous. Nothing in this file defers a pick
   behind `@State`, a `Task` or an animation.

   This file also carries `SoundEngineChrome`, the chrome the THREE sound-ladder
   engines author identically in their TSX — the consigne line, the big 🔊
   replay button, the tile-row slots and the `EngineDeps` wiring. Shared here
   rather than duplicated three times because a divergence between the three
   would be invisible: they are the same fifteen lines of JSX in all three files.
   -------------------------------------------------------------------------- */

// MARK: - Chrome shared by FindSoundView, SyllableGridView and SoundTwinsView

/// The parts of the three sound engines' JSX that are character-for-character
/// identical, plus the wiring their `@State` setup needs.
enum SoundEngineChrome {

    /* ---- The content column ------------------------------------------------
       `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`
       — verbatim in all three files.                                        */

    /// `px-4`.
    static let columnPaddingX: CGFloat = 16
    /// `pb-8`.
    static let columnPaddingBottom: CGFloat = 32
    /// `pt-2`.
    static let columnPaddingTop: CGFloat = 8
    /// `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`), i.e.
    /// on the web the exercise content paints IN FRONT of the burst. See the
    /// note in `FindSoundView`'s divergences: `GameFrame`'s Swift ZStack puts
    /// its overlay slot above the content, which inverts this.
    static let contentZIndex: Double = 41

    /* ---- The consigne line -------------------------------------------------
       `<p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">`          */

    /// `mb-1`.
    static let consigneBottom: CGFloat = 4

    /// The line above the mascot. The string is the model's (`headline`); this
    /// only dresses it.
    static func consigne(_ text: String) -> some View {
        Text(verbatim: text)
            .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
            .foregroundStyle(Palette.inkSoft.color)
            .multilineTextAlignment(.center)
            .padding(.bottom, consigneBottom)
    }

    /* ---- The big replay button ---------------------------------------------
       `rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E]
        shadow [touch-action:none]` with `onPointerDown`.                     */

    /// `px-5`.
    static let listenPaddingX: CGFloat = 20
    /// `py-2`.
    static let listenPaddingY: CGFloat = 8

    /// The « 🔊 Écouter » button under the mascot. It speaks on POINTERDOWN
    /// (D5's `touchDown`, not a `Button`), and the model's `replayPrompt()`
    /// carries the `locked` guard that stops it cutting a success line.
    ///
    /// No press animation: `Tile` animates, these buttons never did.
    static func listenButton(
        text: String,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        Text(verbatim: text)
            .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
            .foregroundStyle(Palette.ink.color)
            .padding(.horizontal, listenPaddingX)
            .padding(.vertical, listenPaddingY)
            .background(Color.white.opacity(Palette.White.o70), in: Capsule())
            // Tailwind `shadow`: 0 1px 3px rgba(0,0,0,0.1),
            //                    0 1px 2px -1px rgba(0,0,0,0.1)
            .shadow(color: .black.opacity(0.1), radius: 1.5, y: 1)
            .shadow(color: .black.opacity(0.1), radius: 1, y: 1)
            .touchDown(action)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityAddTraits(.isButton)
    }

    /* ---- CSS `dashed` ------------------------------------------------------ */

    /// `border-style: dashed`. The rhythm is UA-defined; WebKit draws roughly
    /// square dashes at about twice the border width, which is what this
    /// approximates. The exact rhythm is D3 pixel-diff territory, not a rule.
    static func dash(width: CGFloat) -> [CGFloat] { [width * 2, width * 2] }

    /* ---- Tile rows --------------------------------------------------------- */

    /// One tile's place in a row: the INDEX drives `TILE_COLORS[i % n]` and the
    /// ID is the TSX `key`. A separate type because `ForEach` cannot take a key
    /// path into an `enumerated()` tuple, and because the pairing of the two is
    /// worth testing once instead of three times.
    struct Slot<Value>: Identifiable {
        let id: String
        let index: Int
        let value: Value
    }

    /// `round.choices.map((choice, i) => …)` with `key={id(choice)}`.
    static func slots<Value>(_ values: [Value], id: (Value) -> String) -> [Slot<Value>] {
        values.enumerated().map { Slot(id: id($0.element), index: $0.offset, value: $0.element) }
    }

    /* ---- `useConfetti`, as EngineDeps -------------------------------------- */

    /// `useConfetti()` is called ONCE per exercise component, and its `fire` is
    /// what the pick handler calls. So the run's own `ConfettiSystem` replaces
    /// whatever `fireConfetti` the caller handed in — the caller cannot know
    /// this run's system, and passing a stale one would burst into the previous
    /// exercise's overlay. Everything else in `deps` is passed through.
    ///
    /// `@MainActor` because `ConfettiSystem` is, and `EngineDeps.fireConfetti`
    /// is a plain `() -> Void` called from the pick handler — which is always
    /// on the main actor.
    @MainActor
    static func wire(_ host: EngineHost, to confetti: ConfettiSystem) -> EngineDeps {
        host.deps(fireConfetti: { confetti.fire() })
    }
}

// MARK: - Authored metrics (FindSoundExercise.tsx, verbatim)

public enum FindSoundMetrics {
    /// `TILE_COLORS` in this file is the first THREE of the shared ramp, and
    /// the tiles index it `i % 3` — so a fourth tile would wear the first
    /// colour again. `FIND_SOUND_LEVELS` never asks for more than three
    /// (1 + max 2 distractors), but the modulo is what the TSX wrote.
    public static let tileColorCount = 3

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        Palette.tileColors[index % tileColorCount]
    }

    /// `<WordIcon size="clamp(80px,28vw,150px)" />`.
    public static let wordIconSize = FluidSpec(min: 80, vw: 28, max: 150)

    /// `style={{ margin: "6px 0" }}` on the WordIcon's wrapper.
    public static let wordIconMarginY: CGFloat = 6

    /// `mb-6` on the listen button.
    public static let listenBottom: CGFloat = 24

    /// `gap-4` between tiles.
    public static let tileGap: CGFloat = 16

    /* ---- Shown vs. judged vs. spoken --------------------------------------- */

    /// `<Tile …>{choice.graphy}</Tile>` — the tile shows the GRAPHY, uppercase.
    public static func tileFace(_ choice: BasicSound) -> String { choice.graphy }

    /// `onPick={() => pick(choice.graphy)}` — the judge and the flash key.
    public static func pickKey(_ choice: BasicSound) -> String { choice.graphy }

    /// `onPreview={() => audio.say(choice.sound)}` — the tile speaks its SOUND
    /// (« ou »), never the graphy it shows (« OU »). Two different strings, and
    /// only one of them has a baked clip for this use (D17).
    public static func previewText(_ choice: BasicSound) -> String { choice.sound }

    /// `ariaLabel={`Son ${choice.graphy}`}` — « Son », where the grid and twins
    /// drills say « Syllabe ». Kept distinct, as authored.
    public static func tileLabel(_ choice: BasicSound) -> String {
        Copy.Exercise.soundTile(choice.graphy)
    }

    /// `previewLabel={`Écouter ${choice.graphy}`}`.
    public static func previewLabel(_ choice: BasicSound) -> String {
        Copy.Exercise.listenTile(choice.graphy)
    }
}

// MARK: - The view

/// `FindSoundExercise`. Create one per entry into the exercise; the model is
/// built ONCE in `.task` (D9 — never in `init` or a `@State` default, which
/// SwiftUI re-evaluates on every parent re-render and would re-seed the run).
@MainActor
public struct FindSoundView: View {
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    @State private var model: SinglePickModel<FindSoundRound>?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - deps: the audio channel, the clock and `award` (which must be
    ///     `ProfileStore.award` — invariant 8). `fireConfetti` is REPLACED with
    ///     this run's `ConfettiSystem.fire`; whatever is passed for it is
    ///     ignored.
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
                model = .findSound(
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

    private func round(_ model: SinglePickModel<FindSoundRound>) -> some View {
        VStack(spacing: 0) {
            if let headline = model.headline {
                SoundEngineChrome.consigne(headline)
            }

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            WordIcon(
                emoji: model.current.target.emoji,
                size: FindSoundMetrics.wordIconSize.resolve(viewport: viewport)
            )
            .padding(.vertical, FindSoundMetrics.wordIconMarginY)

            SoundEngineChrome.listenButton(
                text: model.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )
            .padding(.bottom, FindSoundMetrics.listenBottom)

            tiles(model)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, SoundEngineChrome.columnPaddingX)
        .padding(.top, SoundEngineChrome.columnPaddingTop)
        .padding(.bottom, SoundEngineChrome.columnPaddingBottom)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .zIndex(SoundEngineChrome.contentZIndex)
    }

    /// `flex flex-wrap items-center justify-center gap-4`.
    private func tiles(_ model: SinglePickModel<FindSoundRound>) -> some View {
        ComponentsWrapRow(
            spacing: FindSoundMetrics.tileGap,
            lineSpacing: FindSoundMetrics.tileGap
        ) {
            ForEach(SoundEngineChrome.slots(model.current.choices, id: \.graphy)) { slot in
                let choice = slot.value
                let key = FindSoundMetrics.pickKey(choice)
                Tile(
                    paint: FindSoundMetrics.paint(at: slot.index),
                    disabled: model.tilesDisabled,
                    highlight: model.flash == key,
                    accessibilityLabel: FindSoundMetrics.tileLabel(choice),
                    onPick: {
                        // Invariant 1: synchronous, inside the touch-down call.
                        model.pick(key)
                    },
                    onPreview: { model.preview(say: FindSoundMetrics.previewText(choice)) },
                    previewLabel: FindSoundMetrics.previewLabel(choice),
                    content: { Text(verbatim: FindSoundMetrics.tileFace(choice)) }
                )
            }
        }
    }
}
