import CoreGraphics
import SwiftUI

/* -------------------------------------------------------------------------- */
/* The CSS `clamp()` analogue — D16.                                           */
/*                                                                             */
/* The PWA sizes nearly everything with `clamp(min, N vw, max)`: the hub title, */
/* every tile and its font, the badge paddings, the mascot pedestal, the big    */
/* emoji. `vw` is 1 % of the VIEWPORT (window) width and — this is the part a   */
/* naive port gets wrong — NOT 1 % of the card. `index.css` caps `#root > *` at */
/* 480 px while `vw` keeps growing, so on a 1024 pt iPad `8vw` is 82 pt even    */
/* though the card is 480 pt wide. Measuring the container with a              */
/* `GeometryReader` would yield 38 pt: visibly wrong, and silently plausible.   */
/*                                                                             */
/* Hence one function, one definition of "viewport", injected once at the app   */
/* root from the window size and read from the environment everywhere else.     */
/* -------------------------------------------------------------------------- */

/// CSS `clamp(min, vw·viewport/100, max)`.
///
/// Semantics are the CSS ones exactly: `clamp(MIN, VAL, MAX)` is defined as
/// `max(MIN, min(VAL, MAX))`, which means **MIN wins** when the authored
/// minimum exceeds the authored maximum. (Writing it the other way round —
/// `min(max(MIN, VAL), MAX)` — agrees on every clamp this app authors, because
/// they all have `min < max`, but it would disagree on a future inverted pair
/// and there is no reason to carry a second definition.)
///
/// - Parameters:
///   - lo: the CSS first argument, in px → pt.
///   - vw: the CSS middle argument's coefficient — `8vw` is `vw: 8`, i.e. 8 %.
///   - hi: the CSS third argument, in px → pt.
///   - viewport: the window width. See ``EnvironmentValues/alViewportWidth``.
public func fluid(min lo: CGFloat, vw: CGFloat, max hi: CGFloat, viewport: CGFloat) -> CGFloat {
    let preferred = viewport * vw / 100
    return Swift.max(lo, Swift.min(preferred, hi))
}

/// CSS `clamp(min, N%, max)` resolved against a CONTAINER, not the viewport.
///
/// One clamp in the app uses a percentage instead of `vw`: the Dashboard's
/// mascot pedestal, `clamp(190px, 62%, 300px)`, whose `62%` is 62 % of the
/// card's own width. It is deliberately a different function with a different
/// name — folding it into ``fluid(min:vw:max:viewport:)`` would invite a call
/// site to pass a container width where a viewport width is meant, which is the
/// exact bug D16 exists to prevent.
public func fluidPercent(min lo: CGFloat, percent: CGFloat, max hi: CGFloat, of container: CGFloat) -> CGFloat {
    let preferred = container * percent / 100
    return Swift.max(lo, Swift.min(preferred, hi))
}

/// An authored `clamp(min, N vw, max)` triple, so a call site can name one and
/// resolve it later. Pure value type; `resolve` is the same arithmetic as
/// ``fluid(min:vw:max:viewport:)``.
public struct FluidSpec: Hashable, Sendable {
    public let min: CGFloat
    public let vw: CGFloat
    public let max: CGFloat

    public init(min: CGFloat, vw: CGFloat, max: CGFloat) {
        self.min = min
        self.vw = vw
        self.max = max
    }

    public func resolve(viewport: CGFloat) -> CGFloat {
        fluid(min: min, vw: vw, max: max, viewport: viewport)
    }
}

/* -------------------------------------------------------------------------- */
/* The viewport, injected once.                                               */
/* -------------------------------------------------------------------------- */

private struct ALViewportWidthKey: EnvironmentKey {
    /// 390 pt — an iPhone 14/15/16 portrait width. Only ever seen by a view
    /// rendered outside the app root (a preview, a unit test), never in the
    /// running app, where `RootView` injects the real window width.
    static let defaultValue: CGFloat = 390
}

extension EnvironmentValues {
    /// The window width, in points — the `vw` basis for every ``fluid`` call.
    ///
    /// Set exactly once, at the app root, from the window/scene size. Never set
    /// it from a `GeometryReader` around the card: the card is capped at 480 pt
    /// and `vw` is not.
    public var alViewportWidth: CGFloat {
        get { self[ALViewportWidthKey.self] }
        set { self[ALViewportWidthKey.self] = newValue }
    }
}

extension View {
    /// Publishes the window width as the `vw` basis for this subtree.
    public func alViewport(width: CGFloat) -> some View {
        environment(\.alViewportWidth, width)
    }

    /// Measures this view and publishes ITS width as the viewport. Correct only
    /// at the app root, where the view fills the window.
    public func alViewportFromSelf() -> some View {
        background(
            GeometryReader { proxy in
                Color.clear.preference(key: ALViewportWidthPreference.self, value: proxy.size.width)
            }
        )
        .modifier(ALViewportReader())
    }
}

/// Carries a measured root width up so ``View/alViewportFromSelf()`` can push it
/// back down as an environment value.
public struct ALViewportWidthPreference: PreferenceKey {
    public static let defaultValue: CGFloat = 0
    public static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 { value = next }
    }
}

private struct ALViewportReader: ViewModifier {
    @State private var width: CGFloat = ALViewportWidthKey.defaultValue

    func body(content: Content) -> some View {
        content
            .onPreferenceChange(ALViewportWidthPreference.self) { new in
                if new > 0 { width = new }
            }
            .environment(\.alViewportWidth, width)
    }
}

/* -------------------------------------------------------------------------- */
/* The card shell — `index.css` `#root` + `#root > *`, ported once.            */
/* -------------------------------------------------------------------------- */

public enum Shell {
    /// `#root > * { max-width: 480px }`.
    public static let cardMaxWidth: CGFloat = 480

    /// `#root { padding: max(16px, env(safe-area-inset-*)) }` — the floor.
    public static let minimumInset: CGFloat = 16

    /// `min-h-[620px]` on every screen root.
    public static let minimumScreenHeight: CGFloat = 620

    /// The card width for a given window: capped at 480, minus the 16 pt gutter
    /// on each side when the window is narrower than that.
    public static func cardWidth(viewport: CGFloat) -> CGFloat {
        Swift.min(cardMaxWidth, viewport - 2 * minimumInset)
    }
}

extension View {
    /// The screen wash, painted EDGE TO EDGE — D51.
    ///
    /// Every screen root ends in one of these. The wash is `Palette.stage` (the
    /// play surfaces) or `Palette.stageAdult` (the adult/roster/shop ones); the
    /// only difference is where the cream stops, and both are authored in
    /// `Palette`.
    ///
    /// Why a modifier rather than `.background(g.gradient)` at each root: the
    /// gradient has to escape the shell's gutter, and that only works if it is
    /// spelled `ignoresSafeArea()` on the BACKGROUND view — not on the composed
    /// view, which would drag the content under the status bar with it. Doing
    /// that at eight call sites is eight chances to write the wrong one.
    ///
    /// The web draws the gutter as `#root { padding: max(16px,
    /// env(safe-area-inset-*)) }` over `body { background: #efe6da }`, so the
    /// cream frames the card on every screen. On a 390 pt phone that reads as a
    /// grey band under the status bar and a second one over the home indicator,
    /// which is not what a native app looks like. `RootView` therefore spends
    /// that 16 pt as SAFE-AREA padding instead of layout padding: the content
    /// keeps exactly the same insets, and a background that ignores the safe
    /// area now reaches the window's edge. `Palette.page` still shows where it
    /// has a job — beside the 480 pt card on an iPad, which is what
    /// `max-width: 480px` was for.
    ///
    /// Call it AFTER any `clipShape` (the web's `overflow-hidden`): the clip is
    /// for the content, the wash is behind it and deliberately unclipped.
    func stageWash(_ wash: HexGradient) -> some View {
        background { wash.gradient.ignoresSafeArea() }
    }
}
