import QuartzCore
import Testing

import ALCore
@testable import ALUI

// The interaction animations, asserted as DATA against the WAAPI calls in the
// TypeScript — never against the Swift. Sources of every expected number:
//
//   press  src/components/Tile.tsx        scale [1, 0.9, 1], 130 ms, ease-out
//   shake  src/components/Tile.tsx        translateX [0, -8, 8, -5, 0], 300 ms,
//                                         ease-in-out
//   pop    src/components/usePopFlourish  scale [0.4, 1.18@0.68, 1] +
//                                         opacity [0, 1@0.68, 1], 480 ms,
//                                         cubic-bezier(.2,1.35,.4,1)
//   pulse  tailwindcss config.full.js     `pulse 2s cubic-bezier(0.4,0,0.6,1)
//                                         infinite`, keyframes `50% {opacity:.5}`
//                                         — ONLY the 50% frame is defined, so
//                                         the endpoints are the element's own
//                                         opacity (GameFrame.tsx inlines 0.8)
//
// CSS Easing Functions Level 1: ease-out = cubic-bezier(0, 0, 0.58, 1),
// ease-in-out = cubic-bezier(0.42, 0, 0.58, 1). WAAPI's `easing` option applies
// per keyframe INTERVAL, hence one timing function per segment below.

private func controlPoints(_ f: CAMediaTimingFunction) -> [Float] {
    var out: [Float] = []
    for index in 0..<4 {
        var point: [Float] = [0, 0]
        f.getControlPoint(at: index, values: &point)
        out.append(contentsOf: point)
    }
    return out
}

/// The four bezier handles (points 1 and 2) — points 0 and 3 are always
/// (0,0) and (1,1).
private func bezier(_ f: CAMediaTimingFunction) -> [Float] {
    Array(controlPoints(f)[2...5])
}

private func doubles(_ animation: CAKeyframeAnimation) -> [Double] {
    (animation.values as? [Double]) ?? []
}

private func keyTimes(_ animation: CAKeyframeAnimation) -> [Double] {
    (animation.keyTimes ?? []).map { $0.doubleValue }
}

@Suite("Anim — the WAAPI keyframes, ported as data")
struct AnimSpecTests {

    @Test("press: scale 1 → 0.9 → 1, 130 ms, CSS ease-out per segment")
    func pressSpec() throws {
        let press = Anim.pressAnimation()
        #expect(press.keyPath == "transform.scale")
        #expect(doubles(press) == [1, 0.9, 1])
        // WAAPI keyframes with no offsets are evenly spaced.
        #expect(keyTimes(press) == [0, 0.5, 1])
        #expect(press.duration == 0.13)

        let timings = try #require(press.timingFunctions)
        #expect(timings.count == 2, Comment(rawValue: "one easing per keyframe interval — WAAPI semantics"))
        for timing in timings {
            #expect(bezier(timing) == [0, 0, 0.58, 1], Comment(rawValue: "CSS ease-out"))
        }
    }

    @Test("shake: translateX 0, -8, 8, -5, 0 pt, 300 ms, CSS ease-in-out per segment")
    func shakeSpec() throws {
        let shake = Anim.shakeAnimation()
        #expect(shake.keyPath == "transform.translation.x")
        #expect(doubles(shake) == [0, -8, 8, -5, 0])
        #expect(keyTimes(shake) == [0, 0.25, 0.5, 0.75, 1])
        #expect(shake.duration == 0.3)

        let timings = try #require(shake.timingFunctions)
        #expect(timings.count == 4)
        for timing in timings {
            #expect(bezier(timing) == [0.42, 0, 0.58, 1], Comment(rawValue: "CSS ease-in-out"))
        }
    }

    @Test("pop: scale .4 → 1.18@0.68 → 1 with opacity 0 → 1@0.68 → 1, 480 ms, overshoot bezier")
    func popSpec() throws {
        let group = Anim.popAnimation()
        #expect(group.duration == 0.48)

        let members = try #require(group.animations as? [CAKeyframeAnimation])
        #expect(members.count == 2)

        let scale = try #require(members.first { $0.keyPath == "transform.scale" })
        #expect(doubles(scale) == [0.4, 1.18, 1])
        #expect(keyTimes(scale) == [0, 0.68, 1])
        #expect(scale.duration == 0.48)

        let opacity = try #require(members.first { $0.keyPath == "opacity" })
        #expect(doubles(opacity) == [0, 1, 1])
        #expect(keyTimes(opacity) == [0, 0.68, 1])
        #expect(opacity.duration == 0.48)

        for member in members {
            let timings = try #require(member.timingFunctions)
            #expect(timings.count == 2)
            for timing in timings {
                #expect(bezier(timing) == [0.2, 1.35, 0.4, 1], Comment(rawValue: "cubic-bezier(.2,1.35,.4,1) — the overshoot"))
            }
        }
    }

    @Test("pulse: opacity breathes base → 0.5 → base over 2 s, forever, cubic-bezier(0.4,0,0.6,1)")
    func pulseSpec() throws {
        // The GameFrame live star: inline opacity 0.8, and Tailwind's pulse
        // defines only the 50% frame — so the endpoints are 0.8, NOT 1.
        let pulse = Anim.pulseAnimation(baseOpacity: 0.8)
        #expect(pulse.keyPath == "opacity")

        let values = doubles(pulse)
        #expect(values.count == 3)
        #expect(abs(values[0] - 0.8) < 1e-6)
        #expect(values[1] == 0.5)
        #expect(abs(values[2] - 0.8) < 1e-6)
        #expect(keyTimes(pulse) == [0, 0.5, 1])
        #expect(pulse.duration == 2)
        #expect(pulse.repeatCount == .infinity)

        let timings = try #require(pulse.timingFunctions)
        #expect(timings.count == 2)
        for timing in timings {
            #expect(bezier(timing) == [0.4, 0, 0.6, 1], Comment(rawValue: "Tailwind's pulse timing function"))
        }

        // CSS semantics: a different underlying opacity gives different
        // endpoints — the 50% frame alone is authored.
        let atFull = Anim.pulseAnimation(baseOpacity: 1)
        #expect(doubles(atFull) == [1, 0.5, 1])
    }
}

@Suite("Anim — application to a layer")
@MainActor
struct AnimApplyTests {

    private let animate = FixedReduceMotion(false)
    private let still = FixedReduceMotion(true)

    @Test("each entry point lands its animation on the layer under its key")
    func animationsLand() {
        let layer = CALayer()

        Anim.press(layer)
        #expect(layer.animation(forKey: "ALPress") != nil)

        Anim.pop(layer, reduceMotion: animate)
        #expect(layer.animation(forKey: "ALPop") != nil)

        Anim.pulse(layer, reduceMotion: animate)
        #expect(layer.animation(forKey: "ALPulse") != nil)

        Anim.shake(layer)
        #expect(layer.animation(forKey: "ALShake") != nil)
    }

    @Test("shake replaces a running press — WAAPI composites transform with replace, so a rejected tile only shakes")
    func shakeReplacesPress() {
        let layer = CALayer()

        // Tile.tsx handler order on a reject: press first, then shake.
        Anim.press(layer)
        Anim.shake(layer)

        #expect(layer.animation(forKey: "ALPress") == nil, Comment(rawValue: "the web's newest-wins replace: no squish during a shake"))
        #expect(layer.animation(forKey: "ALShake") != nil)
    }

    @Test("endPulse stops the pulse and is safe when nothing pulses")
    func endPulseRemoves() {
        let layer = CALayer()
        Anim.endPulse(layer) // nothing running — must not trap
        Anim.endPulse(nil)

        Anim.pulse(layer, reduceMotion: animate)
        #expect(layer.animation(forKey: "ALPulse") != nil)
        Anim.endPulse(layer)
        #expect(layer.animation(forKey: "ALPulse") == nil)
    }

    @Test("pulse endpoints come from the layer's own opacity — the underlying value, as in CSS")
    func pulseReadsLayerOpacity() throws {
        let layer = CALayer()
        layer.opacity = 0.8 // GameFrame's inline style
        Anim.pulse(layer, reduceMotion: animate)

        let pulse = try #require(layer.animation(forKey: "ALPulse") as? CAKeyframeAnimation)
        let values = try #require(pulse.values as? [Double])
        #expect(abs(values[0] - 0.8) < 1e-6)
        #expect(values[1] == 0.5)
    }

    @Test("reduced motion gates the decoration and nothing else — pop and pulse stop, press and shake do not (D29)")
    func reducedMotionGatesOnlyWhatTheWebGates() {
        // Measured in the PWA rather than assumed:
        //   usePopFlourish.ts:29         reads the media query  -> pop IS gated
        //   GameFrame.tsx motion-safe:   Tailwind's own gate    -> pulse IS gated
        //   Tile.tsx:59 / :61            bare el.animate(...)   -> NOT gated
        // CLAUDE.md invariant 6 scopes itself identically: "mascot + confetti".
        // A squish and a wobble are how a tap feels, not ornament; suppressing
        // them would make the game feel dead for a child who needs the setting.
        let layer = CALayer()
        var log: [String] = []

        Anim.press(layer) { log.append("press") }
        Anim.pop(layer, reduceMotion: still) { log.append("pop") }
        Anim.pulse(layer, reduceMotion: still)

        // The gated pair landed nothing…
        #expect(layer.animation(forKey: "ALPop") == nil)
        #expect(layer.animation(forKey: "ALPulse") == nil)
        // …while the tap feedback ran regardless of the setting.
        #expect(layer.animation(forKey: "ALPress") != nil)

        Anim.shake(layer) { log.append("shake") }
        #expect(layer.animation(forKey: "ALShake") != nil)

        // The GATED call's completion already ran, before this line — that is
        // the property that matters: a gate skips the animation, never the
        // caller's continuation, so gameplay sequenced behind a flourish cannot
        // stall for a child with the setting on.
        //
        // `press` and `shake` are absent from this log for the opposite reason,
        // and it is the reason this test is worth having: they are genuinely
        // animating, so their completions arrive later, from the animation
        // delegate. If someone re-gates them the log becomes
        // ["press", "pop", "shake"] and this line fails.
        #expect(log == ["pop"])
    }

    @Test("a nil layer (the macOS no-op host) drops the animation but still completes")
    func nilLayerCompletes() {
        var completed = 0
        Anim.press(nil) { completed += 1 }
        Anim.shake(nil) { completed += 1 }
        Anim.pop(nil, reduceMotion: FixedReduceMotion(false)) { completed += 1 }
        Anim.pulse(nil, reduceMotion: FixedReduceMotion(false))
        #expect(completed == 3)
    }
}
