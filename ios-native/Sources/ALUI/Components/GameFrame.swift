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
// Z-order, from the TSX: the confetti canvas is `zIndex 40` full-bleed and
// `pointer-events: none`; the header row is `z-[41]`; the children carry no
// z-index. So confetti draws OVER the game content but UNDER the Menu button
// and the stars — the ZStack below reproduces exactly that sandwich.

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
    @State private var headerHeight: CGFloat = 0
    @State private var flowHeight: CGFloat = 0

    /// - Parameters:
    ///   - done: rounds completed — engines pass `done ? total : idx`.
    ///   - stars: per-round first-try flags (index = round).
    ///   - overlay: the confetti canvas slot — full-bleed, hit-testing off,
    ///     painted between the content and the header (z 40 vs z 41).
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
            }
            .frame(maxWidth: .infinity)
            .background(
                GeometryReader { proxy in
                    Color.clear.preference(key: FlowHeightKey.self, value: proxy.size.height)
                }
            )

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
        .background(Palette.stage.gradient)
        .clipShape(RoundedRectangle(cornerRadius: GameFrameMetrics.cornerRadius))
        .fontDesign(.rounded) // fontFamily: ui-rounded,'SF Pro Rounded',…
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
