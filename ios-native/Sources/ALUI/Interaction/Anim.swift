import QuartzCore

import ALCore

// The interaction animations — press, shake, pop, pulse — as Core Animation on
// a hosted CALayer (see `LayerHost`), ported 1:1 from the PWA's WAAPI calls.
//
// Invariant 2 — animation stays OFF the render path. The web uses
// `el.animate(...)` precisely so React never re-renders to move a pixel; the
// Swift equivalent is `CAKeyframeAnimation` added straight to a layer, NOT
// `withAnimation`. Everything here is transform- or opacity-only: no
// layout-affecting property, no state mutation per frame.
//
// Invariant 6 — reduced motion is respected where THE WEB respects it, and
// nowhere else. This was measured, not assumed (D29):
//
//   gated   `pop`   — usePopFlourish.ts:29 reads the media query itself
//           `pulse` — GameFrame.tsx writes `motion-safe:animate-pulse`, and
//                     Tailwind's `motion-safe:` prefix means exactly this
//   UNGATED `press` — Tile.tsx:59, a bare `el.animate(press, …)`
//           `shake` — Tile.tsx:61, likewise
//
// `Tile.tsx` contains no `matchMedia` call at all, so a child with Reduce
// Motion on still gets the 130 ms squish and the 300 ms wobble on the web —
// they are tactile feedback for a tap, not decoration, and CLAUDE.md's
// invariant 6 names its scope precisely: "mascot + confetti". `press` and
// `shake` therefore take no `ReduceMotionSource` at all; the absence of the
// parameter is what stops the gate being reintroduced by reflex.
//
// Where a gate DOES apply, reduced motion means the animation does not run; it
// does NOT mean the caller's completion is skipped — the completion always
// fires (synchronously when nothing animates), so gameplay sequenced behind a
// flourish never stalls for a child with reduced motion on.
//
// WAAPI porting notes, both load-bearing:
//
//   1. WAAPI's `easing` option applies to each keyframe INTERVAL, not to the
//      whole timeline — so it maps onto `timingFunctions` (one per segment) of
//      a `CAKeyframeAnimation`, never onto one curve over the whole track.
//   2. WAAPI composites concurrent `transform` animations with `replace`: the
//      most recent animation wins the property outright. Tile.tsx starts
//      `press` then, on a reject, `shake` in the same handler — on the web the
//      shake REPLACES the press for its whole run, so a rejected tile only
//      shakes, it never squishes. Core Animation would instead COMPOSE
//      `transform.scale` with `transform.translation.x`, so `shake` explicitly
//      removes a running press to reproduce the web's replace semantics.

public enum Anim {

    // MARK: Animation keys (also how re-triggering replaces a running take,
    // matching WAAPI's newest-wins composite order)

    static let pressKey = "ALPress"
    static let shakeKey = "ALShake"
    static let popKey = "ALPop"
    static let pulseKey = "ALPulse"

    // MARK: CSS timing functions, by the book
    // (CSS Easing Functions Level 1: ease-out = cubic-bezier(0, 0, 0.58, 1),
    //  ease-in-out = cubic-bezier(0.42, 0, 0.58, 1).)

    static func cssEaseOut() -> CAMediaTimingFunction {
        CAMediaTimingFunction(controlPoints: 0, 0, 0.58, 1)
    }

    static func cssEaseInOut() -> CAMediaTimingFunction {
        CAMediaTimingFunction(controlPoints: 0.42, 0, 0.58, 1)
    }

    // MARK: - press
    //
    // src/components/Tile.tsx:
    //   const press = [scale(1), scale(0.9), scale(1)]
    //   el.animate(press, { duration: 130, easing: "ease-out" })

    /// The tactile squish every pick tile plays at pointerdown. Call it
    /// synchronously inside the `touchDown` handler, before `onPick()` returns.
    ///
    /// Takes no `ReduceMotionSource`: the web does not gate it (see the header).
    @MainActor
    public static func press(
        _ layer: CALayer?,
        completion: (() -> Void)? = nil
    ) {
        guard let layer else {
            completion?()
            return
        }
        run(pressAnimation(), key: pressKey, on: layer, completion: completion)
    }

    static func pressAnimation() -> CAKeyframeAnimation {
        keyframes(
            keyPath: "transform.scale",
            values: [1, 0.9, 1],
            keyTimes: [0, 0.5, 1],
            duration: 0.13,
            timing: cssEaseOut
        )
    }

    // MARK: - shake
    //
    // src/components/Tile.tsx:
    //   const shake = [translateX(0), translateX(-8px), translateX(8px),
    //                  translateX(-5px), translateX(0)]
    //   el.animate(shake, { duration: 300, easing: "ease-in-out" })

    /// The soft "not this one" wobble on a `.reject` verdict — a shake and
    /// nothing else: no lock, no error state (invariant 3).
    ///
    /// Takes no `ReduceMotionSource`: the web does not gate it (see the header).
    @MainActor
    public static func shake(
        _ layer: CALayer?,
        completion: (() -> Void)? = nil
    ) {
        guard let layer else {
            completion?()
            return
        }
        // WAAPI replace semantics: on the web the shake, created after the
        // press in the same handler, replaces it for the whole 300 ms — a
        // rejected tile never squishes (porting note 2 above).
        layer.removeAnimation(forKey: pressKey)
        run(shakeAnimation(), key: shakeKey, on: layer, completion: completion)
    }

    static func shakeAnimation() -> CAKeyframeAnimation {
        keyframes(
            keyPath: "transform.translation.x",
            values: [0, -8, 8, -5, 0],
            keyTimes: [0, 0.25, 0.5, 0.75, 1],
            duration: 0.3,
            timing: cssEaseInOut
        )
    }

    // MARK: - pop
    //
    // src/components/usePopFlourish.ts:
    //   const POP_IN = [
    //     { transform: "scale(0.4)",  opacity: 0 },
    //     { transform: "scale(1.18)", opacity: 1, offset: 0.68 },
    //     { transform: "scale(1)",    opacity: 1 },
    //   ]
    //   el.animate(POP_IN, { duration: 480, easing: "cubic-bezier(.2,1.35,.4,1)" })

    /// The one-shot celebrate-into-view flourish (balance pill, EarnBadge).
    /// Fires once on appear; the caller triggers it from `onAppear`, this
    /// function only performs it.
    @MainActor
    public static func pop(
        _ layer: CALayer?,
        reduceMotion: ReduceMotionSource,
        completion: (() -> Void)? = nil
    ) {
        guard let layer, !reduceMotion.isReduced else {
            completion?()
            return
        }
        run(popAnimation(), key: popKey, on: layer, completion: completion)
    }

    static func popAnimation() -> CAAnimationGroup {
        let overshoot = { CAMediaTimingFunction(controlPoints: 0.2, 1.35, 0.4, 1) }
        let scale = keyframes(
            keyPath: "transform.scale",
            values: [0.4, 1.18, 1],
            keyTimes: [0, 0.68, 1],
            duration: 0.48,
            timing: overshoot
        )
        let opacity = keyframes(
            keyPath: "opacity",
            values: [0, 1, 1],
            keyTimes: [0, 0.68, 1],
            duration: 0.48,
            timing: overshoot
        )
        let group = CAAnimationGroup()
        group.animations = [scale, opacity]
        group.duration = 0.48
        return group
    }

    // MARK: - pulse
    //
    // Tailwind's `animate-pulse` on the GameFrame strip's live star
    // (src/components/GameFrame.tsx, `motion-safe:animate-pulse` with inline
    // `opacity: 0.8`):
    //   animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
    //   @keyframes pulse { 50% { opacity: .5 } }
    //
    // Tailwind defines ONLY the 50% frame (verified in
    // node_modules/tailwindcss/stubs/config.full.js), so the 0%/100% frames
    // take the element's underlying computed opacity — the inline 0.8. The
    // live star therefore breathes 0.8 → 0.5 → 0.8. This function reproduces
    // that CSS rule by reading the layer's model `opacity` as the endpoints;
    // the caller owns the base value (0.8 for the star), set on the layer, and
    // shows it statically under reduced motion (`motion-safe:` drops the class).

    /// A repeating breathe on the layer's opacity. Runs until `endPulse`.
    @MainActor
    public static func pulse(_ layer: CALayer?, reduceMotion: ReduceMotionSource) {
        guard let layer, !reduceMotion.isReduced else { return }
        layer.add(pulseAnimation(baseOpacity: layer.opacity), forKey: pulseKey)
    }

    /// Stops a running pulse (the round advanced; the star is no longer live).
    /// Safe to call when nothing pulses.
    @MainActor
    public static func endPulse(_ layer: CALayer?) {
        layer?.removeAnimation(forKey: pulseKey)
    }

    static func pulseAnimation(baseOpacity: Float) -> CAKeyframeAnimation {
        let animation = keyframes(
            keyPath: "opacity",
            values: [Double(baseOpacity), 0.5, Double(baseOpacity)],
            keyTimes: [0, 0.5, 1],
            duration: 2,
            timing: { CAMediaTimingFunction(controlPoints: 0.4, 0, 0.6, 1) }
        )
        animation.repeatCount = .infinity
        return animation
    }

    // MARK: - Plumbing

    /// A WAAPI `el.animate(keyframes, { duration, easing })` call as a
    /// `CAKeyframeAnimation`: values with explicit key times, and the ONE
    /// easing repeated per segment (porting note 1).
    private static func keyframes(
        keyPath: String,
        values: [Double],
        keyTimes: [Double],
        duration: CFTimeInterval,
        timing: () -> CAMediaTimingFunction
    ) -> CAKeyframeAnimation {
        let animation = CAKeyframeAnimation(keyPath: keyPath)
        animation.values = values
        animation.keyTimes = keyTimes.map { NSNumber(value: $0) }
        animation.timingFunctions = (0..<(values.count - 1)).map { _ in timing() }
        animation.duration = duration
        return animation
    }

    @MainActor
    private static func run(
        _ animation: CAAnimation,
        key: String,
        on layer: CALayer,
        completion: (() -> Void)?
    ) {
        if let completion {
            // Per-animation completion — the WAAPI `finish` event, not a
            // CATransaction block (a transaction commit would also flush the
            // enclosing implicit transaction mid-handler). CAAnimation retains
            // its delegate.
            animation.delegate = CompletionDelegate(completion)
        }
        layer.add(animation, forKey: key)
    }
}

private final class CompletionDelegate: NSObject, CAAnimationDelegate {
    private let completion: () -> Void

    init(_ completion: @escaping () -> Void) {
        self.completion = completion
    }

    func animationDidStop(_ anim: CAAnimation, finished flag: Bool) {
        // Fires whether the take ran to its end or was replaced by a newer one
        // (WAAPI settles `finished` either way); the caller's continuation must
        // never be dropped.
        completion()
    }
}
