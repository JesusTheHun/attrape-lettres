import ALCore
import SwiftUI

/* --------------------------------------------------------------------------
   `src/exercises/SoundTwinsExercise.tsx` — the app's ONE multi-select engine.

   Hear one sound (« Trouve tous les ko ! »), find EVERY tile that writes it:
   CO and KO stay, SO goes home. Each correct tap locks its tile into the
   collection strip and speaks that graphy's own anchor word (« Oui ! coq. »),
   so the feedback itself teaches which word owns which spelling.

   `TwinsModel` owns the verdict rules that make this machine different from the
   single-pick family (engines.md §4.3): a PARTIAL find speaks fire-and-forget
   and leaves the round running — the next tap can land while the line plays —
   while the COMPLETING find locks the round and awaits its line. Partial finds
   never grey the star and never lock a tile the child still needs; only
   intruders grey it. None of that is re-expressed here.

   What this file owns: the collection strip (one slot per twin to find), the
   tile palette (the tray ramp, blue first — NOT the pick ramp), and the labels.
   -------------------------------------------------------------------------- */

// MARK: - Authored metrics (SoundTwinsExercise.tsx, verbatim)

public enum SoundTwinsMetrics {
    /// This file's `TILE_COLORS` is the TRAY ramp — blue, green, gold, violet,
    /// coral. A twins tile at index 0 is therefore `#4FC3F7`, where a
    /// find-sound tile at index 0 is `#FF8A65`.
    public static let tileColorCount = 5

    /// `TILE_COLORS[i % TILE_COLORS.length]`.
    public static func paint(at index: Int) -> Palette.TilePaint {
        Palette.trayColors[index % tileColorCount]
    }

    /* ---- The listen button ------------------------------------------------- */

    /// `mt-2` / `mb-4` — twins sits the button tighter than the other two
    /// engines (they use `mb-6`), because the collection strip follows it.
    public static let listenTop: CGFloat = 8
    public static let listenBottom: CGFloat = 16

    /// The button's text. `TwinsModel` exposes no `listenText` (only the
    /// single-pick family varies it), and the TSX hard-codes « 🔊 Écouter ».
    public static let listenText = Copy.Exercise.listen

    /* ---- The collection strip ---------------------------------------------- */

    /// `mb-6` under the strip, `gap-2` between slots.
    public static let stripBottom: CGFloat = 24
    public static let stripGap: CGFloat = 8

    /// One slot: `minWidth: clamp(56px,16vw,84px)`,
    /// `height: clamp(48px,13vw,64px)`, `fontSize: clamp(20px,5.5vw,32px)`.
    public static let slotMinWidth = FluidSpec(min: 56, vw: 16, max: 84)
    public static let slotHeight = FluidSpec(min: 48, vw: 13, max: 64)
    public static let slotFontSize = FluidSpec(min: 20, vw: 5.5, max: 32)
    /// `padding: "0 10px"`, `borderRadius: 18`, `gap-1` inside.
    public static let slotPaddingX: CGFloat = 10
    public static let slotCornerRadius: CGFloat = 18
    public static let slotInnerGap: CGFloat = 4
    /// `border: 3px dashed #E4A15E` while the slot is still empty.
    public static let slotBorderWidth: CGFloat = 3

    /// `<span aria-hidden style={{ fontSize: "0.8em" }}>{tile.emoji}</span>` —
    /// the anchor picture, relative to the slot's own font size.
    public static let slotEmojiEm: CGFloat = 0.8

    public static func slotEmojiFontSize(base: CGFloat) -> CGFloat {
        base * slotEmojiEm
    }

    /* ---- The tiles -------------------------------------------------------- */

    /// `gap-3`.
    public static let tileGap: CGFloat = 12
    /// `size="clamp(60px,17vw,92px)"` — one point shy of the grid drill's 62,
    /// as authored.
    public static let tileSize = FluidSpec(min: 60, vw: 17, max: 92)
    /// `fontSize="clamp(24px,6.5vw,44px)"`.
    public static let tileFontSize = FluidSpec(min: 24, vw: 6.5, max: 44)

    /// `ariaLabel={`Syllabe ${tile.text}`}` — a twins tile is a graphy, and the
    /// TSX labels it « Syllabe », not « Son » (find-sound's word for the same
    /// kind of tile). Kept distinct on purpose.
    public static func tileLabel(_ tile: TwinTile) -> String {
        Copy.Exercise.syllableTile(tile.text)
    }

    /// `previewLabel={`Écouter ${tile.text}`}`.
    public static func previewLabel(_ tile: TwinTile) -> String {
        Copy.Exercise.listenTile(tile.text)
    }

    /// `<Tile …>{tile.text}</Tile>`.
    public static func tileFace(_ tile: TwinTile) -> String { tile.text }

    /// `onPreview={() => audio.say(tile.sound)}` — THIS tile's own family's
    /// sound, not the round's. An intruder auditions as itself (SO says « so »),
    /// which is how a child can tell it out; speaking the round's target here
    /// would make every tile sound correct.
    public static func previewText(_ tile: TwinTile) -> String { tile.sound }
}

// MARK: - The view

/// `SoundTwinsExercise`. One `TwinsModel`, built once in `.task` (D9).
@MainActor
public struct SoundTwinsView: View {
    private let level: Int
    private let host: EngineHost
    private let mascot: MascotConfig
    private let rng: RandomSource
    private let onBack: () -> Void
    private let onNext: () -> Void

    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    @State private var model: TwinsModel?
    @State private var confetti: ConfettiSystem?

    /// - Parameters:
    ///   - deps: `fireConfetti` is replaced with this run's system (see
    ///     `SoundEngineChrome.wire`); `award` must be `ProfileStore.award`.
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
                model = TwinsModel(
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

    private func round(_ model: TwinsModel) -> some View {
        VStack(spacing: 0) {
            if let headline = model.headline {
                SoundEngineChrome.consigne(headline)
            }

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            SoundEngineChrome.listenButton(
                text: SoundTwinsMetrics.listenText,
                accessibilityLabel: model.listenAccessibilityLabel,
                action: { model.replayPrompt() }
            )
            .padding(.top, SoundTwinsMetrics.listenTop)
            .padding(.bottom, SoundTwinsMetrics.listenBottom)

            strip(model)
                .padding(.bottom, SoundTwinsMetrics.stripBottom)

            tiles(model)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, SoundEngineChrome.columnPaddingX)
        .padding(.top, SoundEngineChrome.columnPaddingTop)
        .padding(.bottom, SoundEngineChrome.columnPaddingBottom)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .zIndex(SoundEngineChrome.contentZIndex)
    }

    /// The collection strip: one slot per twin to find, filled in TAP order, so
    /// the child sees the family assemble — and how many are still hiding.
    /// `targets.map((_, i) => …)`, keyed by the slot index.
    private func strip(_ model: TwinsModel) -> some View {
        let slots = model.targets.count
        return ComponentsWrapRow(
            spacing: SoundTwinsMetrics.stripGap,
            lineSpacing: SoundTwinsMetrics.stripGap
        ) {
            ForEach(0..<slots, id: \.self) { i in
                slot(model.foundTile(at: i))
            }
        }
    }

    private func slot(_ tile: TwinTile?) -> some View {
        let font = SoundTwinsMetrics.slotFontSize.resolve(viewport: viewport)
        let shape = RoundedRectangle(cornerRadius: SoundTwinsMetrics.slotCornerRadius)
        return HStack(spacing: SoundTwinsMetrics.slotInnerGap) {
            if let tile {
                Text(verbatim: tile.text)
                Text(verbatim: tile.emoji)
                    .font(.system(size: SoundTwinsMetrics.slotEmojiFontSize(base: font)))
                    .accessibilityHidden(true)   // aria-hidden
            }
        }
        .font(Typography.rounded(font, Typography.Weight.black))
        .foregroundStyle(Palette.ink.color)
        .padding(.horizontal, SoundTwinsMetrics.slotPaddingX)
        .frame(minWidth: SoundTwinsMetrics.slotMinWidth.resolve(viewport: viewport))
        .frame(height: SoundTwinsMetrics.slotHeight.resolve(viewport: viewport))
        .background {
            if tile != nil {
                // background "#FFFFFF", no border,
                // boxShadow 0 6px 14px rgba(0,0,0,0.12)
                shape
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.12), radius: 7, y: 6)
            } else {
                // 3px dashed #E4A15E on transparent, no shadow.
                shape.strokeBorder(
                    Palette.slotDashed.color,
                    style: StrokeStyle(
                        lineWidth: SoundTwinsMetrics.slotBorderWidth,
                        dash: SoundEngineChrome.dash(width: SoundTwinsMetrics.slotBorderWidth)
                    )
                )
            }
        }
    }

    /// `flex flex-wrap items-center justify-center gap-3`, keyed by `tile.id`.
    private func tiles(_ model: TwinsModel) -> some View {
        ComponentsWrapRow(
            spacing: SoundTwinsMetrics.tileGap,
            lineSpacing: SoundTwinsMetrics.tileGap
        ) {
            ForEach(Array(model.round.tiles.enumerated()), id: \.element.id) { index, tile in
                Tile(
                    paint: SoundTwinsMetrics.paint(at: index),
                    disabled: model.tileDisabled(tile),
                    highlight: model.tileHighlighted(tile),
                    size: SoundTwinsMetrics.tileSize,
                    fontSize: SoundTwinsMetrics.tileFontSize,
                    accessibilityLabel: SoundTwinsMetrics.tileLabel(tile),
                    onPick: {
                        // Invariant 1: synchronous, inside the touch-down call.
                        model.pick(tile)
                    },
                    onPreview: { model.preview(say: SoundTwinsMetrics.previewText(tile)) },
                    previewLabel: SoundTwinsMetrics.previewLabel(tile),
                    content: { Text(verbatim: SoundTwinsMetrics.tileFace(tile)) }
                )
            }
        }
    }
}
