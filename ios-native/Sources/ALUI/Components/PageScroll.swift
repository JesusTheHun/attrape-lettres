import SwiftUI

/* -------------------------------------------------------------------------- */
/* The page scrolls — because on the web, the page always scrolled.             */
/* -------------------------------------------------------------------------- */
//
// Two screens need this, for what look like different reasons and are the same
// one: on the web the document scrolls, and neither the keyboard nor a long list
// can ever put content permanently out of reach.
//
//   • the first-run name form (D42) — a keyboard inset that would otherwise
//     slide the 👋 under the notch;
//   • the species picker (D46) — five companion cards in a stage pinned to
//     `minHeight`, of which the fifth sat below the screen with no way to
//     reach it. The child could not choose the last animal at all.
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
// the EXERCISE tile grid would delay the press animation and the SFX by the
// pan-recognition window. Every exercise screen is therefore off limits.
//
// Two places scroll a `LayerHost` anyway: the shop tiles (which always did) and
// now the species picker, where the alternative was a card the child cannot
// reach at all. **Whether `delaysContentTouches` actually costs anything there
// is UNVERIFIED** — SwiftUI may already clear it, and neither the host suite nor
// `simctl` (which has no tap input) can measure touch-down latency. It needs a
// device check, and if it does cost something the fix is to clear the flag on
// the enclosing `UIScrollView`. Flagged in D46 rather than pre-emptively
// "fixed", because unverified UIKit interop is what the open crashes are made
// of.
//
// On `WhoIsPlayingView` no such waiver is needed: the wrapped branch is the form
// (plain SwiftUI buttons) and the screen's only `LayerHost` is `ChildCard`, in
// the mutually-exclusive grid branch.
//
// Do not promote this to `RootView`: the exercise engines live under it.
public struct PageScroll: ViewModifier {

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
    /// the notch. See `PageScroll` — NOT for subtrees containing tiles.
    public func alPageScroll(enabled: Bool = true) -> some View {
        modifier(PageScroll(enabled: enabled))
    }
}
