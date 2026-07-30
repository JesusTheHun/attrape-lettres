import SwiftUI

/* -------------------------------------------------------------------------- */
/* Absorb the keyboard by SCROLLING, never by translating.                      */
/* -------------------------------------------------------------------------- */
//
// There is no TSX counterpart to this file, and that is the whole point (D42).
// The web has no notch and no keyboard inset: when a soft keyboard opens in
// mobile Safari the *visual viewport* shrinks and the browser scrolls the
// focused field into view. The page top is never clipped — you can always
// scroll back up to it.
//
// SwiftUI's default keyboard avoidance does something different: it insets the
// safe area and slides the whole view up. For a stage pinned to `minHeight:
// Shell.minimumScreenHeight` (620) that cannot compress, "slides up" means the
// top runs off under the status bar and the notch. On the first-run picker that
// ate the 👋 above « Comment tu t'appelles ? » — found on a simulator, invisible
// to all 1423 host tests, because no assertion that does not put a real keyboard
// on a real screen can see it.
//
// Wrapping the stage in a `ScrollView` restores the browser's behaviour exactly:
// keyboard avoidance becomes a bottom *content inset* instead of a translation,
// so the top stays anchored and the focused field is reachable by scrolling.
// `.basedOnSize` keeps it inert when the content already fits — no rubber-band
// on a screen that has room, which is what the web does too.
//
// **Why this is a modifier with an `enabled` flag rather than something applied
// once at the shell.** A `UIScrollView` sets `delaysContentTouches`, which holds
// a touch back to see whether it becomes a pan. That is precisely invariant 1 —
// "feedback fires on `pointerdown`, before React commits" — and putting one over
// a tile grid would delay the press animation and the SFX by the pan-recognition
// window. So this goes ONLY over subtrees that (a) own a keyboard and (b) carry
// no `LayerHost`. On `WhoIsPlayingView` the split is exact: the form branch is
// plain SwiftUI buttons, and the only `LayerHost` on that screen lives in
// `ChildCard`, in the grid branch, which is never wrapped.
//
// Do not promote this to `RootView`.
public struct KeyboardScroll: ViewModifier {

    /// False leaves the subtree completely untouched — no scroll view is built.
    let enabled: Bool

    public init(enabled: Bool) {
        self.enabled = enabled
    }

    @ViewBuilder
    public func body(content: Content) -> some View {
        if enabled {
            ScrollView(.vertical) {
                content
            }
            // Inert while the content fits, so a keyboard-less screen renders
            // pixel-identical to the unwrapped one (`KeyboardScrollTests`).
            .scrollBounceBehavior(.basedOnSize)
            // A platform affordance with no web equivalent to be faithful to:
            // the web keyboard is the OS keyboard and dismissing it is the OS's
            // business. Dragging it down is what every other iOS form does.
            .scrollDismissesKeyboard(.interactively)
        } else {
            content
        }
    }
}

extension View {
    /// Let the keyboard inset scroll this subtree instead of sliding it under
    /// the notch. See `KeyboardScroll` — NOT for subtrees containing tiles.
    public func alKeyboardScroll(enabled: Bool = true) -> some View {
        modifier(KeyboardScroll(enabled: enabled))
    }
}
