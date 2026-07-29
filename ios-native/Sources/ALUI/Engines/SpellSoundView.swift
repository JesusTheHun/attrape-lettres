import SwiftUI

import ALCore

/* ==========================================================================
   `src/exercises/SpellSoundExercise.tsx` — hear a sound, re-spell it with
   letter tiles.

   « Same forgiving loop as AssembleExercise (feedback on pointerdown, WAAPI
   shake, canvas confetti), but the tiles are LETTERS and the target is a
   grapheme. The upper levels reuse a sound across several spellings so the
   child learns o / au / eau, f / ph, …. »

   THE VIEW HOLDS NO RULE. Every one of them lives in
   `AssemblyModel.spellSound` (the session, the round, the whole-row judgement,
   the « Oh non » pacing, the award) — this file reads that model's state and
   calls its handlers. What it owns is the plumbing the model deliberately does
   not: which glyph goes on a tile, which label a slot carries, and the
   authored metrics of the row and the tray.

   Invariant 1 lives in the pick path and nowhere else:
     Tile → touchDown → TilePress.pointerDown → model.pick(tileID:value:)
   all synchronous, all inside the finger-down turn. Same for the filled slot's
   `removeAt` and the big 🔊 button's `replayPrompt` (both `onPointerDown` in
   the TSX, so both `touchDown` here — never a `Button`, which fires on lift).
   ========================================================================== */

/// `AssemblyModel` as SpellSound instantiates it.
public typealias SpellSoundModel = AssemblyModel<SoundTarget, SoundRound, String>

// MARK: - Chrome shared by the two spelling engines

/// The letter tray, identical in `SpellSoundExercise.tsx` and
/// `SpellSyllableExercise.tsx`: the same five `TRAY_COLORS`, the same
/// `size` / `fontSize` clamps, the same `gap-3` wrap row.
enum SpellTray {
    /// `TRAY_COLORS[i % TRAY_COLORS.length]`, `i` = the tile's position in the
    /// tray. `Palette.trayColors` is that table (blue first).
    static func paint(at index: Int) -> Palette.TilePaint {
        Palette.trayColors[((index % Palette.trayColors.count) + Palette.trayColors.count) % Palette.trayColors.count]
    }

    /// `size="clamp(60px,17vw,92px)"` — the 60 pt floor is below the 92 pt
    /// default but is what both TSX files author; the tray is a row of many.
    static let size = FluidSpec(min: 60, vw: 17, max: 92)
    /// `fontSize="clamp(26px,7vw,48px)"`.
    static let fontSize = FluidSpec(min: 26, vw: 7, max: 48)
    /// `gap-3` on the wrapping tray row.
    static let gap: CGFloat = 12
}

/// How a slot box is painted. The three faces the two spelling engines draw.
enum SpellSlotFace: Equatable {
    /// `background: transparent; border: 3px dashed #E4A15E; boxShadow: none`.
    case empty
    /// `background: #FFFFFF; border: none; boxShadow: 0 6px 14px rgba(0,0,0,.12)`.
    case filled
    /// SpellSyllable's already-written letter: `background: #FFF3E0`, no border,
    /// no shadow.
    case revealed
}

/// One slot box's authored geometry (the TSX inline `style` object).
struct SpellSlotMetrics {
    var minWidth: CGFloat
    var height: CGFloat
    var horizontalPadding: CGFloat
    var fontSize: CGFloat
    var cornerRadius: CGFloat
}

enum SpellSlot {
    /// `border: 3px dashed`.
    static let borderWidth: CGFloat = 3

    /// CSS `border-style: dashed` has no specified dash length. WebKit's
    /// `GraphicsContext` uses `patternWidth = 3 * width` for both the dash and
    /// the gap (`width` for `dotted`), so a 3 px dashed border draws 9 on / 9
    /// off. Reproduced here as a constant; WebKit additionally nudges the phase
    /// so a whole number of dashes fits each side, which SwiftUI's
    /// `StrokeStyle` cannot express — a sub-pixel difference the D3 pixel diff
    /// will show and nothing else will.
    static let dash: [CGFloat] = [borderWidth * 3, borderWidth * 3]

    /// `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` on a filled slot. CSS blur
    /// radius is twice SwiftUI's.
    static let filledShadowOpacity: Double = 0.12
    static let filledShadowRadius: CGFloat = 7
    static let filledShadowY: CGFloat = 6
}

/// A spelling slot / written cell. Pure chrome: it draws the box and hosts a
/// glyph, and knows nothing about rounds.
struct SpellSlotBox<Content: View>: View {
    let face: SpellSlotFace
    let metrics: SpellSlotMetrics
    @ViewBuilder var content: Content

    var body: some View {
        content
            // `font-black`, `color: #5A3A1E`. A caller that needs a different
            // FAMILY (SpellSyllable's script font) sets `.font` on its own
            // `Text`, which wins over this environment font.
            .font(Typography.rounded(metrics.fontSize, Typography.Weight.black))
            .foregroundStyle(Palette.ink.color)
            .padding(.horizontal, metrics.horizontalPadding)
            .frame(minWidth: metrics.minWidth)
            .frame(height: metrics.height)
            .background(fill)
            .overlay { dashedBorder }
            .shadow(
                color: .black.opacity(face == .filled ? SpellSlot.filledShadowOpacity : 0),
                radius: SpellSlot.filledShadowRadius,
                y: SpellSlot.filledShadowY
            )
    }

    @ViewBuilder private var fill: some View {
        let shape = RoundedRectangle(cornerRadius: metrics.cornerRadius)
        switch face {
        case .empty: shape.fill(Color.clear)
        case .filled: shape.fill(Color.white)
        case .revealed: shape.fill(Palette.slotRevealed.color)
        }
    }

    @ViewBuilder private var dashedBorder: some View {
        if face == .empty {
            RoundedRectangle(cornerRadius: metrics.cornerRadius)
                .strokeBorder(
                    Palette.slotDashed.color,
                    style: StrokeStyle(lineWidth: SpellSlot.borderWidth, dash: SpellSlot.dash)
                )
        }
    }
}

/// The big « 🔊 Écouter » pill under the mascot, shared by both spelling
/// engines (they differ only in the label and the margin below).
///
/// `onPointerDown`, so `touchDown` — and no press animation: the TSX calls
/// `el.animate` on tiles only, never on this button.
struct SpellListenPill: View {
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Text(verbatim: Copy.Exercise.listen)
            .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
            .foregroundStyle(Palette.ink.color)
            .padding(.horizontal, 20)  // px-5
            .padding(.vertical, 8)  // py-2
            .background(Color.white.opacity(Palette.White.o70), in: Capsule())
            // Tailwind `shadow`.
            .shadow(color: .black.opacity(0.1), radius: 1.5, y: 1)
            .shadow(color: .black.opacity(0.1), radius: 1, y: 1)
            .touchDown(action)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityAddTraits(.isButton)
    }
}

// MARK: - The view

public struct SpellSoundView: View {

    /* ---- authored metrics, from the TSX inline styles ------------------- */

    /// `{target.emoji ?? "🎧"}` — the headphones stand in when the sound has no
    /// anchor word to picture.
    static let fallbackEmoji = "🎧"

    /// `fontSize: "clamp(56px,18vw,104px)"`, `lineHeight: 1.1`, `margin: 2px 0`.
    static let emojiSize = FluidSpec(min: 56, vw: 18, max: 104)
    static let emojiLineHeight: CGFloat = 1.1
    static let emojiMarginY: CGFloat = 2

    /// The slot row: `minWidth`/`height` `clamp(52px,15vw,76px)`,
    /// `padding: 0 8px`, `fontSize: clamp(24px,7vw,44px)`, `borderRadius: 20`.
    static let slotSide = FluidSpec(min: 52, vw: 15, max: 76)
    static let slotFontSize = FluidSpec(min: 24, vw: 7, max: 44)
    static let slotHorizontalPadding: CGFloat = 8
    static let slotCornerRadius: CGFloat = 20

    /// `<FitLine className="mb-6" rowClassName="gap-2">`.
    static let rowSpacing: CGFloat = 8
    static let rowMarginBottom: CGFloat = 24
    /// The listen button's `mb-5` (SpellSyllable's is `mb-4` — not shared).
    static let listenMarginBottom: CGFloat = 20
    /// `m-0 mb-1` on the headline `<p>`.
    static let headlineMarginBottom: CGFloat = 4
    /// `px-4 pb-8 pt-2` on the content column.
    static let padding = EdgeInsets(top: 8, leading: 16, bottom: 32, trailing: 16)

    /// `relative z-[41]` on the content column — one ABOVE `GameFrame`'s
    /// confetti canvas (`zIndex: 40`), so on the web the tiles paint over the
    /// burst. Declared for the record and applied like every sibling engine,
    /// but inert as things stand: `GameFrame` puts the content in the flow
    /// `VStack` and the overlay in a ZStack sibling, so no zIndex on a child of
    /// the content can lift it past the overlay. Reported, not patched here.
    static let contentZIndex: Double = 41

    /* ---- the rules this file owns (pure, host-tested) ------------------- */

    /// `{target.emoji ?? "🎧"}`.
    static func promptEmoji(_ target: SoundTarget) -> String {
        target.emoji ?? fallbackEmoji
    }

    /// What one slot renders. The TSX branch, as data:
    /// ```
    /// s != null ? <button aria-label={`Retirer ${s}`}>{s}</button>
    ///           : <div style={dashed}>{""}</div>
    /// ```
    enum SlotRender: Equatable {
        case empty
        case filled(letter: String, removeLabel: String)

        var face: SpellSlotFace {
            switch self {
            case .empty: return .empty
            case .filled: return .filled
            }
        }
    }

    static func render(slot: String?) -> SlotRender {
        guard let letter = slot else { return .empty }
        return .filled(letter: letter, removeLabel: Copy.Exercise.remove(letter))
    }

    /// A tray tile's screen-reader label — `Lettre ${t.letter}`.
    static func trayLabel(_ tile: SoundTile) -> String {
        Copy.Exercise.letterTile(tile.letter)
    }

    /* ---- inputs ---------------------------------------------------------- */

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

    /// D9: created ONCE, in `.task` — never in `init` or a `@State` default,
    /// which SwiftUI re-evaluates on every parent re-render.
    @State private var model: SpellSoundModel?
    @State private var confetti: ConfettiSystem?

    public init(
        level: Int,
        mascot: MascotConfig,
        host: EngineHost,
        onBack: @escaping () -> Void,
        onNext: @escaping () -> Void,
        rng: RandomSource = .system()
    ) {
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
                if let confetti {
                    ConfettiOverlay(system: confetti)
                }
            },
            content: {
                if let model {
                    body(of: model)
                } else {
                    // One frame, before `.task` runs. `flex-1` with nothing in it.
                    Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        )
        .task { start() }
        .onDisappear { model?.deactivate() }
    }

    /// The TSX mount: `useConfetti()`, then the engine, then its effects
    /// (`audio.unlock()` + the 350 ms announce) — `activate()`.
    @MainActor
    private func start() {
        let system = confetti ?? ConfettiSystem(reduceMotion: reduceMotion)
        if confetti == nil { confetti = system }
        if model == nil {
            model = .spellSound(level: level, deps: engineDeps(confetti: system), rng: rng)
        }
        model?.activate()
    }

    /// `award` is handed in whole — this file never computes a point
    /// (invariant 8). `fireConfetti` is `useConfetti().fire`, whose
    /// reduce-motion gate lives inside `ConfettiSystem` (D29).
    @MainActor
    private func engineDeps(confetti: ConfettiSystem) -> EngineDeps {
        host.deps(fireConfetti: { confetti.fire() })
    }

    @ViewBuilder
    private func body(of model: SpellSoundModel) -> some View {
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

    /// `<div className="relative z-[41] flex w-full flex-1 flex-col items-center
    /// px-4 pb-8 pt-2">` — a top-packed column (flexbox `justify-content:
    /// flex-start`), everything centred across.
    private func round(_ model: SpellSoundModel) -> some View {
        VStack(spacing: 0) {
            Text(verbatim: model.headline ?? "")
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                .foregroundStyle(Palette.inkSoft.color)
                .padding(.bottom, Self.headlineMarginBottom)

            Ollie(config: mascot, mood: model.mood, reduceMotion: reduceMotion)

            Text(verbatim: Self.promptEmoji(model.round.target))
                .font(.system(size: Self.emojiSize.resolve(viewport: viewport)))
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Self.emojiSize.resolve(viewport: viewport),
                        ratio: Self.emojiLineHeight))
                .accessibilityHidden(true)  // aria-hidden
                .padding(.vertical, Self.emojiMarginY)

            SpellListenPill(accessibilityLabel: model.listenAccessibilityLabel) {
                model.replayPrompt()
            }
            .padding(.bottom, Self.listenMarginBottom)

            slotRow(model)
                .padding(.bottom, Self.rowMarginBottom)

            tray(model)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(Self.padding)
        .zIndex(Self.contentZIndex)
    }

    /// The spelling slots. A filled one is a button that pops its letter back
    /// to the tray, so a misplacement is fixable mid-row (invariant 3: nothing
    /// about a wrong letter is terminal).
    private func slotRow(_ model: SpellSoundModel) -> some View {
        let metrics = SpellSlotMetrics(
            minWidth: Self.slotSide.resolve(viewport: viewport),
            height: Self.slotSide.resolve(viewport: viewport),
            horizontalPadding: Self.slotHorizontalPadding,
            fontSize: Self.slotFontSize.resolve(viewport: viewport),
            cornerRadius: Self.slotCornerRadius
        )
        return FitLine(rowSpacing: Self.rowSpacing) {
            ForEach(Array(model.slots.enumerated()), id: \.offset) { index, slot in
                switch Self.render(slot: slot) {
                case .empty:
                    SpellSlotBox(face: .empty, metrics: metrics) { Text(verbatim: "") }
                case .filled(let letter, let removeLabel):
                    SpellSlotBox(face: .filled, metrics: metrics) { Text(verbatim: letter) }
                        .touchDown { model.removeAt(index) }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(removeLabel)
                        .accessibilityAddTraits(.isButton)
                }
            }
        }
    }

    /// `<div className="flex flex-wrap items-center justify-center gap-3">`.
    private func tray(_ model: SpellSoundModel) -> some View {
        ComponentsWrapRow(spacing: SpellTray.gap, lineSpacing: SpellTray.gap) {
            ForEach(Array(model.round.tray.enumerated()), id: \.element.id) { index, tile in
                Tile(
                    paint: SpellTray.paint(at: index),
                    disabled: model.isTrayTileUsed(id: tile.id),
                    size: SpellTray.size,
                    fontSize: SpellTray.fontSize,
                    accessibilityLabel: Self.trayLabel(tile),
                    onPick: { model.pick(tileID: tile.id, value: tile.letter) },
                    onPreview: { model.preview(say: tile.letter) },
                    previewLabel: Copy.Exercise.listenTile(tile.letter)
                ) {
                    Text(verbatim: tile.letter)
                }
            }
        }
    }
}
