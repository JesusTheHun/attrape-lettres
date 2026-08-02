import SwiftUI

import ALCore

// Port of `src/components/GameFrame.tsx` — the in-exercise chrome: stage
// gradient, ← Menu, the star strip, and the confetti overlay slot.
//
// Invariant 8's VISIBLE half lives here: each round's star greys on its FIRST
// wrong tap, at pointerdown. The strip is pure rendering over `(done, total,
// stars)` — the greying happens because the engine mutates `stars`
// synchronously inside the pick handler (`StarStrip.miss`), and the
// `i == done && !stars[i]` branch below shows the LIVE round's star as lost
// the instant the flag flips. That immediacy is the point: the child sees the
// bonus slip at the moment of the miss, not at the recap. Do not debounce,
// animate, or batch it.
//
// Z-order, measured in the TSX rather than assumed — an earlier version of this
// comment claimed "the children carry no z-index", and two engine agents caught
// it. They do. Bottom to top on the web:
//
//   1. the stage gradient
//   2. the confetti canvas — `absolute inset-0 pointer-events-none`, `zIndex: 40`
//   3. the header row — `relative z-[41]` (GameFrame.tsx:35)
//   4. the children — every one of the nine exercise roots is
//      `relative z-[41] … px-4 pb-8 pt-2`, and `Finished` is `relative z-[41]` too
//
// So the burst passes BEHIND the tiles, the mascot and the word picture, not in
// front of them. It reads as confetti falling behind the game rather than
// splattering across it, and at the reward moment the child's eye stays on the
// word. Drawing it on top is a different, worse feeling — which is why this is a
// behaviour bug and not a cosmetic one.
//
// 3 and 4 are TIED at z-index 41 and the tie breaks on DOM order, in the
// children's favour. SwiftUI has no tie, so the numbers below are 40 / 41 / 42:
// the 42 encodes that DOM tie-break, it is not a web value. It matters where a
// centred `Finished` on a short screen reaches up into the header's band.

// MARK: - The star strip rule (pure, host-tested)

/// One cell of the strip. shell.md §4.5's four states.
public enum StarCell: Equatable, Sendable {
    /// `i < done`, star kept: ⭐ full.
    case earned
    /// star lost — played rounds AND the live round the instant a wrong tap
    /// lands: ⭐ greyed (`grayscale(1)`, opacity 0.45), kept visible so the
    /// round still counts as played (invariants 3 and 8).
    case lost
    /// `i == done`, star still winnable: ⭐ at opacity 0.8, pulsing.
    case live
    /// `i > done`: a `•` at opacity 0.28.
    case pending
}

public enum StarStrip {
    /// `const fontSize = total > 9 ? 16 : 20;`
    public static func fontSize(total: Int) -> CGFloat {
        total > 9 ? 16 : 20
    }

    /// The strip classifier, verbatim from the TSX render:
    /// ```
    /// if (i < done || (i === done && !stars[i]))  → ⭐ (LOST when !stars[i])
    /// if (i === done)                             → ⭐ pulsing
    /// otherwise                                   → •
    /// ```
    /// An out-of-range `stars[i]` is `undefined` in JS — falsy — so a missing
    /// flag reads as lost, never as earned.
    public static func cells(done: Int, total: Int, stars: [Bool]) -> [StarCell] {
        (0..<max(0, total)).map { i in
            let kept = stars.indices.contains(i) ? stars[i] : false
            if i < done { return kept ? .earned : .lost }
            if i == done { return kept ? .live : .lost }
            return .pending
        }
    }

    /// `missRound(i)` — greys star `i` exactly once, synchronously, in the
    /// same beat as the shake and the nudge (invariant 8):
    /// ```
    /// if (!starsRef.current[i]) return;
    /// starsRef.current = starsRef.current.map((s, j) => (j === i ? false : s));
    /// ```
    /// A second wrong tap in the same round changes nothing. Out-of-range is a
    /// no-op (JS: `!undefined` is true → early return).
    public static func miss(_ i: Int, in stars: inout [Bool]) {
        guard stars.indices.contains(i), stars[i] else { return }
        stars[i] = false
    }
}

// MARK: - Authored metrics (GameFrame.tsx classes, verbatim)

public enum GameFrameMetrics {
    /// `rounded-3xl` = 24 pt.
    public static let cornerRadius: CGFloat = 24
    /// `min-h-[620px]` — also `Shell.minimumScreenHeight`.
    public static let minHeight: CGFloat = Shell.minimumScreenHeight
    /// Header row: `gap-3` = 12 pt, `px-4 pt-4` = 16 pt.
    public static let headerSpacing: CGFloat = 12
    public static let headerPadding: CGFloat = 16
    /// Strip: `gap-x-1 gap-y-0.5` = 4 pt / 2 pt.
    public static let stripGapX: CGFloat = 4
    public static let stripGapY: CGFloat = 2
    /// `hidden w-[84px] shrink-0 sm:block` — the balancing spacer exists only
    /// at viewport ≥ 640 (Tailwind `sm:`). On a phone the strip is NOT
    /// optically centred — pushed right by the Menu button. Odd, frozen.
    public static let spacerWidth: CGFloat = 84
    public static let spacerMinViewport: CGFloat = 640
}

// MARK: - The frame

public struct GameFrame<Overlay: View, Content: View>: View {
    private let onBack: () -> Void
    private let done: Int
    private let total: Int
    private let stars: [Bool]
    private let overlay: Overlay
    private let content: Content

    @Environment(\.alViewportWidth) private var viewport
    /// nil outside the play route — a preview or a test renders exactly the
    /// frame it always did. `RootView` sets it; see `CorrectionLink.swift`.
    @Environment(\.alCorrectionReport) private var correctionReport
    @State private var headerHeight: CGFloat = 0
    @State private var flowHeight: CGFloat = 0
    /// The adult door at the foot of the exercise. Owned here so that every one
    /// of the nine engines gets it without a call site that could forget one.
    @State private var correction = CorrectionFlowModel()

    /// - Parameters:
    ///   - done: rounds completed — engines pass `done ? total : idx`.
    ///   - stars: per-round first-try flags (index = round).
    ///   - overlay: the confetti canvas slot — full-bleed, hit-testing off,
    ///     painted BENEATH both the header and the content (z 40, vs 41 and 42):
    ///     on the web the burst passes behind the tiles. See the file header.
    public init(
        onBack: @escaping () -> Void,
        done: Int,
        total: Int,
        stars: [Bool],
        @ViewBuilder overlay: () -> Overlay,
        @ViewBuilder content: () -> Content
    ) {
        self.onBack = onBack
        self.done = done
        self.total = total
        self.stars = stars
        self.overlay = overlay()
        self.content = content()
    }

    public var body: some View {
        ZStack(alignment: .top) {
            // The normal flow: header space, then children (the TSX column).
            // The header itself is a ZStack sibling (it must paint ABOVE the
            // confetti), so its slot in the flow is reserved from measurement.
            VStack(spacing: 0) {
                Color.clear.frame(height: headerHeight)
                content
                // « Suggérer une correction ». Below the exercise column, which
                // already claims `maxHeight: .infinity`, so this lands at the
                // foot of the frame and never between the child and a tile.
                if correctionReport != nil {
                    CorrectionFooter(model: correction)
                }
            }
            .frame(maxWidth: .infinity)
            .background(
                GeometryReader { proxy in
                    Color.clear.preference(key: FlowHeightKey.self, value: proxy.size.height)
                }
            )
            // The children's own `relative z-[41]`, plus the DOM tie-break that
            // puts them above the equally-ranked header (see the header note).
            .zIndex(42)

            // `absolute inset-0 … pointer-events-none`, `zIndex: 40`. Sized to
            // the measured flow (never less than the 620 pt minimum) so the
            // canvas covers the frame without driving the frame's height.
            overlay
                .frame(maxWidth: .infinity)
                .frame(height: max(flowHeight, GameFrameMetrics.minHeight))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .zIndex(40)

            header
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: HeaderHeightKey.self, value: proxy.size.height)
                    }
                )
                .accessibilitySortPriority(1) // DOM order: Menu + stars first
                .zIndex(41)
        }
        .onPreferenceChange(HeaderHeightKey.self) { headerHeight = $0 }
        .onPreferenceChange(FlowHeightKey.self) { flowHeight = $0 }
        .frame(maxWidth: .infinity, minHeight: GameFrameMetrics.minHeight, alignment: .top)
        .clipShape(RoundedRectangle(cornerRadius: GameFrameMetrics.cornerRadius))
        .stageWash(Palette.stage)
        .fontDesign(.rounded) // fontFamily: ui-rounded,'SF Pro Rounded',…
        // The gate, OVER the frame — over the confetti, the header and the
        // children alike (40 / 41 / 42 above). Applied outside the clip so the
        // scrim covers the rounded corners too, and as an overlay rather than a
        // sheet so the exercise underneath is never torn down: the child comes
        // back to the same round, the same star and the same audio (invariant 3).
        .overlay {
            if let correctionReport {
                CorrectionGate(report: correctionReport, model: correction)
                    .zIndex(CorrectionMetrics.gateZIndex)
            }
        }
    }

    // MARK: header row — ← Menu, the strip, the sm: spacer

    private var header: some View {
        HStack(spacing: GameFrameMetrics.headerSpacing) {
            // Navigation, not gameplay: fires on touch-UP (the TSX uses
            // `onClick` here, deliberately) — an ordinary SwiftUI Button.
            Button(action: onBack) {
                Text(Copy.Frame.backToMenu)
                    .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
                    .foregroundStyle(Palette.ink.color)
                    .padding(.horizontal, 16) // px-4
                    .padding(.vertical, 8) // py-2
                    .background(Color.white.opacity(Palette.White.o70), in: Capsule())
                    .compositingGroup()  // D54 — the box casts, not the label
                    // Tailwind `shadow`: 0 1px 3px rgba(0,0,0,0.1),
                    //                    0 1px 2px -1px rgba(0,0,0,0.1)
                    .shadow(color: .black.opacity(0.1), radius: 1.5, y: 1)
                    .shadow(color: .black.opacity(0.1), radius: 1, y: 1)
            }
            .buttonStyle(.plain)

            strip
                .frame(maxWidth: .infinity) // min-w-0 flex-1

            if viewport >= GameFrameMetrics.spacerMinViewport {
                Color.clear
                    .frame(width: GameFrameMetrics.spacerWidth, height: 0)
                    .accessibilityHidden(true) // aria-hidden
            }
        }
        .padding(.horizontal, GameFrameMetrics.headerPadding)
        .padding(.top, GameFrameMetrics.headerPadding)
    }

    private var strip: some View {
        let cells = StarStrip.cells(done: done, total: total, stars: stars)
        let size = StarStrip.fontSize(total: total)
        return StripFlowLayout(gapX: GameFrameMetrics.stripGapX, gapY: GameFrameMetrics.stripGapY) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                switch cell {
                case .earned:
                    Text(Copy.Frame.star)
                        .font(.system(size: size))
                case .lost:
                    // LOST = { filter: grayscale(1), opacity: 0.45 } — greyed,
                    // never removed: the round still counts as played.
                    Text(Copy.Frame.star)
                        .font(.system(size: size))
                        .saturation(Palette.Lost.saturation)
                        .opacity(Palette.Lost.opacity)
                case .live:
                    LiveStar(fontSize: size)
                case .pending:
                    Text(Copy.Frame.futureRound)
                        .font(.system(size: size))
                        .foregroundStyle(Color.black)
                        .opacity(Palette.futureDotOpacity)
                }
            }
        }
    }
}

extension GameFrame where Overlay == EmptyView {
    /// A frame with no confetti overlay (previews, tests).
    public init(
        onBack: @escaping () -> Void,
        done: Int,
        total: Int,
        stars: [Bool],
        @ViewBuilder content: () -> Content
    ) {
        self.init(
            onBack: onBack,
            done: done,
            total: total,
            stars: stars,
            overlay: { EmptyView() },
            content: content
        )
    }
}

// MARK: - The live star

/// The winnable round's star: inline `opacity: 0.8`, `motion-safe:animate-pulse`.
///
/// CSS semantics (verified against the Tailwind stub, see `Anim.pulse`): the
/// pulse keyframes author ONLY the 50% frame, so the endpoints are the
/// element's own opacity — the star breathes 0.8 → 0.5 → 0.8. Under reduced
/// motion the class is dropped and the inline 0.8 shows statically. Hence:
/// base opacity 0.8 on the hosted layer, `Anim.pulse` from it (which no-ops
/// under reduced motion, leaving the static 0.8) — all off the render path
/// (invariant 2).
private struct LiveStar: View {
    let fontSize: CGFloat

    @Environment(\.alReduceMotion) private var reduceMotion
    @State private var handle = LayerHandle()

    var body: some View {
        #if canImport(UIKit)
        LayerHost(handle: handle) {
            Text(Copy.Frame.star)
                .font(.system(size: fontSize))
                // Inside the LayerHost so `handle.layer` is guaranteed set by
                // the time onAppear fires (the content lives in the hosting
                // controller `makeUIView` created).
                .onAppear {
                    handle.layer?.opacity = Float(Palette.liveStarOpacity)
                    Anim.pulse(handle.layer, reduceMotion: reduceMotion)
                }
                .onDisappear {
                    Anim.endPulse(handle.layer)
                }
        }
        #else
        // macOS host build (D1): no hosted layer — the star shows the static
        // base opacity, as under reduced motion.
        Text(Copy.Frame.star)
            .font(.system(size: fontSize))
            .opacity(Palette.liveStarOpacity)
        #endif
    }
}

// MARK: - The wrapping strip row

/// `flex min-w-0 flex-1 flex-wrap items-center justify-center gap-x-1 gap-y-0.5`
/// — a greedy-wrap flow: lines centred horizontally, items centred vertically
/// within their line. Long runs (total > 9) wrap on narrow phones exactly as
/// the flexbox does.
struct StripFlowLayout: SwiftUI.Layout { // qualified: ALCore has a mascot `Layout`
    var gapX: CGFloat
    var gapY: CGFloat

    /// Greedy flexbox wrap: an item starts a new line when it no longer fits.
    static func wrapLines(sizes: [CGSize], maxWidth: CGFloat, gapX: CGFloat) -> [[Int]] {
        var lines: [[Int]] = []
        var current: [Int] = []
        var lineWidth: CGFloat = 0
        for (index, size) in sizes.enumerated() {
            let widthIfAppended = current.isEmpty ? size.width : lineWidth + gapX + size.width
            if !current.isEmpty && widthIfAppended > maxWidth {
                lines.append(current)
                current = [index]
                lineWidth = size.width
            } else {
                current.append(index)
                lineWidth = widthIfAppended
            }
        }
        if !current.isEmpty { lines.append(current) }
        return lines
    }

    private func lineWidth(_ line: [Int], _ sizes: [CGSize]) -> CGFloat {
        let widths = line.map { sizes[$0].width }.reduce(0, +)
        return widths + gapX * CGFloat(max(0, line.count - 1))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: LayoutSubviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let maxWidth = proposal.width ?? .infinity
        let lines = Self.wrapLines(sizes: sizes, maxWidth: maxWidth, gapX: gapX)
        let heights = lines.map { line in line.map { sizes[$0].height }.max() ?? 0 }
        let height = heights.reduce(0, +) + gapY * CGFloat(max(0, lines.count - 1))
        let natural = lines.map { lineWidth($0, sizes) }.max() ?? 0
        // A flex item (`flex-1 min-w-0`) adopts the width it is given.
        let width = proposal.width ?? natural
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: LayoutSubviews, cache: inout ()) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let lines = Self.wrapLines(sizes: sizes, maxWidth: bounds.width, gapX: gapX)
        var y = bounds.minY
        for line in lines {
            let lineH = line.map { sizes[$0].height }.max() ?? 0
            var x = bounds.minX + (bounds.width - lineWidth(line, sizes)) / 2 // justify-center
            for index in line {
                let size = sizes[index]
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (lineH - size.height) / 2), // items-center
                    anchor: .topLeading,
                    proposal: .unspecified
                )
                x += size.width + gapX
            }
            y += lineH + gapY
        }
    }
}

private struct HeaderHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct FlowHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
