import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/usePopFlourish.ts` — the one-shot celebrate-into-view pop.  */
/*                                                                             */
/* ```ts                                                                       */
/* const POP_IN: Keyframe[] = [                                                */
/*   { transform: "scale(0.4)",  opacity: 0 },                                 */
/*   { transform: "scale(1.18)", opacity: 1, offset: 0.68 },                   */
/*   { transform: "scale(1)",    opacity: 1 },                                 */
/* ];                                                                          */
/* const POP_OPTIONS = { duration: 480, easing: "cubic-bezier(.2,1.35,.4,1)" };*/
/*                                                                             */
/* useEffect(() => {                                                           */
/*   const el = ref.current;                                                   */
/*   if (!el) return;                                                          */
/*   if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;       */
/*   const anim = el.animate(keyframes, options);                              */
/*   return () => anim.cancel();                                               */
/* }, []);            // mount-only: a fresh flourish each time it appears      */
/* ```                                                                         */
/*                                                                             */
/* The keyframes themselves are NOT re-typed here — `Anim.pop` already carries */
/* them, asserted against this same TypeScript in `AnimTests`. This file is    */
/* only the hook's plumbing: get a layer, fire once on mount, honour reduced   */
/* motion (invariant 6), and never re-render to animate (invariant 2).         */
/* -------------------------------------------------------------------------- */

/// The React hook as a view modifier: attach it to whatever should celebrate
/// into view (today: `EarnBadge`, and the Dashboard's balance pill).
///
/// `onAppear` is the `useEffect` with an empty dependency list — it runs when
/// the element enters the tree and again on every fresh appearance, which is
/// the comment's "a fresh flourish each time the element appears".
public struct PopFlourish: ViewModifier {
    /// D14 — injected, never read straight from the environment.
    public let reduceMotion: ReduceMotionSource

    @State private var handle = LayerHandle()

    public init(reduceMotion: ReduceMotionSource) {
        self.reduceMotion = reduceMotion
    }

    // NB: `ALCore` exports a namespace `enum Content`, which shadows
    // `ViewModifier`'s inferred `Content` associated type at file scope and
    // makes the conformance fail. Spelling the default witness out fixes it.
    public typealias Content = _ViewModifier_Content<Self>

    public func body(content: Content) -> some View {
        LayerHost(handle: handle) {
            content
        }
        .onAppear {
            // `el.animate(...)` — Core Animation on the hosted layer, off the
            // SwiftUI update path. Under reduced motion `Anim.pop` does
            // nothing at all (the web returns before `el.animate`), and the
            // element is simply there, at rest.
            Anim.pop(handle.layer, reduceMotion: reduceMotion)
        }
    }
}

extension View {
    /// `usePopFlourish()` — the mount-only pop.
    public func popFlourish(reduceMotion: ReduceMotionSource) -> some View {
        modifier(PopFlourish(reduceMotion: reduceMotion))
    }
}
