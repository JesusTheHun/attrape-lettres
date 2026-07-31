import SwiftUI

// Port of `src/components/FitLine.tsx` — the word presented to the child must
// always fit on ONE line. FitLine lays its children out in a no-wrap row at
// their natural size and, when that row would overflow the available width,
// shrinks the whole row with a TRANSFORM so it still fits. The wrapper's
// height is pinned to the scaled height so the layout below stays snug
// against the row.
//
// The TSX carries a bug fix this port must keep ("stop the shrunk word row
// collapsing onto the tray"): the row is measured at its NATURAL size,
// independent of the wrapper's pinned height. On the web that is `items-start`
// on the wrapper — without it the row would stretch to the pinned height, the
// next measurement would read a smaller natural height, compute a smaller
// scale, pin a smaller height, and so on: a ResizeObserver feedback loop that
// visually collapses the word row onto the tray beneath it.
//
// The SwiftUI analogue is `.fixedSize()` on the row: it lays out at its IDEAL
// size regardless of the proposal, so the measured `natural` cannot change
// when the outer width or the pinned height changes — the loop is structurally
// impossible. Three rules for anyone touching this file (shell.md §4.6):
//
//   1. `.fixedSize()` stays. Removing it re-opens the exact bug in SwiftUI
//      form (a PreferenceKey write → layout → new preference → write cycle).
//   2. Never feed the outer proxy size back into the MEASURED value — only
//      into the scale.
//   3. `FitLineFit` is pure and clamps at 1: the row is never enlarged, only
//      shrunk.
//
// Fitting is recomputed on size change only — never per frame, never
// re-rendering the exercise (invariant 2's spirit; the TSX writes the
// transform straight to the DOM for the same reason).

// MARK: - The fit rule (pure, host-tested)

/// `FitLine.tsx` `fit()`, as data:
/// ```
/// const k = natural > 0 && natural > available ? available / natural : 1;
/// inner.style.transform = k < 1 ? `scale(${k})` : "";
/// outer.style.height    = k < 1 ? `${naturalHeight * k}px` : "";
/// ```
public enum FitLineFit {
    /// The shrink factor. 1 when the row fits (or nothing is measured yet);
    /// `available / natural` when it would overflow. Never above 1.
    public static func scale(natural: CGFloat, available: CGFloat) -> CGFloat {
        natural > 0 && natural > available ? available / natural : 1
    }

    /// The wrapper's pinned height — only pinned while actually shrunk
    /// (`k < 1`); `nil` means "natural height, no override".
    public static func pinnedHeight(naturalHeight: CGFloat, scale k: CGFloat) -> CGFloat? {
        k < 1 ? naturalHeight * k : nil
    }
}

// MARK: - The view

/// The one-line shrink-to-fit row. Callers put margins OUTSIDE (the TSX
/// `className`) and pass the row gap here (the TSX `rowClassName`, `gap-2` =
/// 8 pt, `gap-1.5` = 6 pt) — the four call sites port unchanged.
public struct FitLine<Content: View>: View {
    private let rowSpacing: CGFloat
    private let label: String?
    private let content: Content

    @State private var natural: CGSize = .zero
    @State private var available: CGFloat = 0

    /// - Parameters:
    ///   - rowSpacing: the inner row's gap (TSX `rowClassName`).
    ///   - accessibilityLabel: TSX `ariaLabel` on the outer wrapper
    ///     (SpellSyllable passes « Mot à compléter »).
    public init(
        rowSpacing: CGFloat = 0,
        accessibilityLabel: String? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.rowSpacing = rowSpacing
        self.label = accessibilityLabel
        self.content = content()
    }

    public var body: some View {
        let k = FitLineFit.scale(natural: natural.width, available: available)
        labelled(
            HStack(spacing: rowSpacing) { content } // flex-nowrap
                // Rule 1: natural size, independent of any proposal — the
                // `items-start` of this port. The measurement below therefore
                // cannot be re-triggered by its own consequences.
                .fixedSize()
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: FitNaturalSizeKey.self, value: proxy.size)
                    }
                )
                // transformOrigin: "center top" — layout is untouched (as CSS
                // transforms are), the wrapper below pins the visual height.
                .scaleEffect(k, anchor: .top)
                // The outer wrapper: full width, row centred in it.
                .frame(maxWidth: .infinity)
                // Pinned only while shrunk; `nil` leaves the natural height.
                .frame(
                    height: FitLineFit.pinnedHeight(naturalHeight: natural.height, scale: k),
                    alignment: .top
                )
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: FitAvailableWidthKey.self, value: proxy.size.width)
                    }
                )
                .onPreferenceChange(FitNaturalSizeKey.self) { natural = $0 }
                .onPreferenceChange(FitAvailableWidthKey.self) { available = $0 }
        )
    }

    @ViewBuilder
    private func labelled(_ view: some View) -> some View {
        if let label {
            view
                .accessibilityElement(children: .contain)
                .accessibilityLabel(label)
        } else {
            view
        }
    }
}

private struct FitNaturalSizeKey: PreferenceKey {
    static let defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

private struct FitAvailableWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
