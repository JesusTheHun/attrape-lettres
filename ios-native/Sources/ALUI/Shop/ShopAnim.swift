import QuartzCore
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/anim.ts` — the shop's micro-animations, as Core Animation.        */
/*                                                                             */
/* Invariant 2 — everything here is a `CAAnimation` added straight to a hosted  */
/* layer (`LayerHost`), exactly as the web's WAAPI `el.animate` never touches   */
/* the React tree. No `withAnimation`, no per-frame state.                      */
/*                                                                             */
/* Invariant 6 / D29 — `shop/anim.ts` gates EVERY helper on                     */
/* `prefers-reduced-motion` (`if (!el || reducedMotion()) return` at lines 27,  */
/* 32, 54, 62, 84 and 135). That is the OPPOSITE of `Tile.tsx`, whose press is  */
/* deliberately ungated — so, unlike `Anim.press`, every entry point here takes */
/* a `ReduceMotionSource` and drops the take when it is reduced. Per-helper,    */
/* exactly where the web has it.                                               */
/*                                                                             */
/* The meter's pair (`meterFill` / `tipSparkle`) is NOT here: `Shop/Meter.swift`*/
/* (another agent's file) already owns both, same gate, same numbers. This file */
/* owns the rest of `anim.ts`: `press`, `pop`, `starFlight`, `growBurst`.       */
/*                                                                             */
/* `starFlight` and `growBurst` are the web's "throwaway DOM on document.body"  */
/* layers: here they spawn `CATextLayer` particles on a full-screen overlay     */
/* layer the shop hosts above everything (`z-index: 60` on the web), and the    */
/* container removes itself when the last particle finishes — the exact         */
/* `if (--pending <= 0) layer.remove()` bookkeeping.                            */
/*                                                                             */
/* The geometry is extracted into `StarFlightSpec` / `GrowBurstSpec` — pure     */
/* value types the host tests assert number for number against `anim.ts`.       */
/* -------------------------------------------------------------------------- */

public enum ShopAnim {

    // MARK: - Metrics (the `anim.ts` numbers, verbatim)

    public enum Metrics {
        /// `PRESS` — `[scale(1), scale(0.94), scale(1)]`, 130 ms, ease-out.
        /// NOT `Anim.press` (0.9, ungated): the shop's squish is its own, and
        /// it IS gated (D29).
        public static let pressScale: Double = 0.94
        public static let pressDuration: Double = 0.13

        /// `POP` — `[scale(1), scale(1.12), scale(1)]`, 260 ms, ease-out. "A
        /// happy little bounce — used on the live preview when something is
        /// equipped."
        public static let popScale: Double = 1.12
        public static let popDuration: Double = 0.26
    }

    static let pressKey = "ALShopPress"
    static let popKey = "ALShopPop"

    // MARK: - press / pop

    /// `press(el)` — the tactile squish on shop tiles and buttons at
    /// pointerdown. Gated: `if (!el || reducedMotion()) return`.
    @MainActor
    public static func press(_ layer: CALayer?, reduceMotion: any ReduceMotionSource) {
        guard let layer, !reduceMotion.isReduced else { return }
        layer.add(pressAnimation(), forKey: pressKey)
    }

    /// `pop(el)` — the equip bounce on the header mascot. Gated likewise.
    @MainActor
    public static func pop(_ layer: CALayer?, reduceMotion: any ReduceMotionSource) {
        guard let layer, !reduceMotion.isReduced else { return }
        layer.add(popAnimation(), forKey: popKey)
    }

    static func pressAnimation() -> CAKeyframeAnimation {
        scaleKeyframes(
            values: [1, Metrics.pressScale, 1],
            duration: Metrics.pressDuration)
    }

    static func popAnimation() -> CAKeyframeAnimation {
        scaleKeyframes(
            values: [1, Metrics.popScale, 1],
            duration: Metrics.popDuration)
    }

    private static func scaleKeyframes(values: [Double], duration: Double) -> CAKeyframeAnimation {
        let animation = CAKeyframeAnimation(keyPath: "transform.scale")
        animation.values = values
        animation.keyTimes = [0, 0.5, 1]
        // WAAPI easing applies per keyframe INTERVAL (Anim's porting note 1).
        animation.timingFunctions = [Anim.cssEaseOut(), Anim.cssEaseOut()]
        animation.duration = duration
        return animation
    }

    // MARK: - starFlight

    /// `starFlight(from, to, count)` — a handful of ⭐ fly wallet → mascot so a
    /// child SEES stars leave the purse. Rects are captured synchronously (the
    /// web's `getBoundingClientRect`) by converting the two layers' bounds into
    /// the overlay's space; a nil layer or a zero-width rect is the web's
    /// `if (!from || !to)` / `a.width === 0` no-op. Gated (anim.ts:84).
    @MainActor
    public static func starFlight(
        from: CALayer?,
        to: CALayer?,
        overlay: CALayer?,
        count: Int,
        reduceMotion: any ReduceMotionSource
    ) {
        guard let from, let to, let overlay, !reduceMotion.isReduced else { return }
        let a = from.convert(from.bounds, to: overlay)
        let b = to.convert(to.bounds, to: overlay)
        guard let spec = StarFlightSpec(from: a, to: b, count: count) else { return }
        run(spec, on: overlay)
    }

    /// The layer half, split from the gate so a test can drive a known spec.
    @MainActor
    static func run(_ spec: StarFlightSpec, on overlay: CALayer) {
        let container = CALayer()
        container.frame = overlay.bounds
        container.zPosition = StarFlightSpec.zIndex
        overlay.addSublayer(container)
        let cleanup = ParticleCleanup(count: spec.stars.count, container: container)
        let t0 = CACurrentMediaTime()

        for star in spec.stars {
            let particle = particleLayer(glyph: StarFlightSpec.glyph, fontSize: StarFlightSpec.fontSize)
            particle.position = star.start
            container.addSublayer(particle)

            let position = CAKeyframeAnimation(keyPath: "position")
            position.values = [star.start, star.mid, star.end].map(pointValue)
            let scale = CAKeyframeAnimation(keyPath: "transform.scale")
            scale.values = [StarFlightSpec.startScale, StarFlightSpec.midScale, StarFlightSpec.endScale]
            let opacity = CAKeyframeAnimation(keyPath: "opacity")
            opacity.values = [0, 1, StarFlightSpec.endOpacity]

            let group = CAAnimationGroup()
            group.animations = [position, scale, opacity].map { track in
                track.keyTimes = [0, 0.5, 1]
                track.timingFunctions = [flightCurve(), flightCurve()]
                track.duration = star.duration
                return track
            }
            group.duration = star.duration
            // `delay: i * 70` — before its start the particle shows its model
            // values (visible at the origin), exactly as the web element does
            // during its stagger delay; `fill: "forwards"` holds the end frame
            // until the container is torn down.
            group.beginTime = t0 + star.delay
            group.fillMode = .forwards
            group.isRemovedOnCompletion = false
            group.delegate = cleanup
            particle.add(group, forKey: "ALShopStarFlight")
        }
    }

    /// `{ easing: "cubic-bezier(.3,.6,.4,1)" }`.
    static func flightCurve() -> CAMediaTimingFunction {
        CAMediaTimingFunction(controlPoints: 0.3, 0.6, 0.4, 1)
    }

    // MARK: - growBurst

    /// `growBurst(anchor)` — the cloud "poof" + sparkle ring when the mascot
    /// grows a stage. Same overlay, same cleanup, gated (anim.ts:135).
    @MainActor
    public static func growBurst(
        around anchor: CALayer?,
        overlay: CALayer?,
        reduceMotion: any ReduceMotionSource
    ) {
        guard let anchor, let overlay, !reduceMotion.isReduced else { return }
        let rect = anchor.convert(anchor.bounds, to: overlay)
        guard let spec = GrowBurstSpec(around: rect) else { return }
        run(spec, on: overlay)
    }

    @MainActor
    static func run(_ spec: GrowBurstSpec, on overlay: CALayer) {
        let container = CALayer()
        container.frame = overlay.bounds
        container.zPosition = StarFlightSpec.zIndex
        overlay.addSublayer(container)
        let cleanup = ParticleCleanup(
            count: spec.puffs.count + spec.sparks.count,
            container: container)
        let t0 = CACurrentMediaTime()

        for puff in spec.puffs {
            let particle = particleLayer(glyph: GrowBurstSpec.cloudGlyph, fontSize: puff.size)
            let cx = spec.center.x + puff.dx
            particle.position = CGPoint(x: cx, y: spec.center.y + 0.1 * puff.size)
            container.addSublayer(particle)

            // `translate(dx, -40%) → (dx, -70%) → (dx, -120%)`: the offsets are
            // percentages of the puff's own box, so with a centre-anchored
            // layer the centre rides `+0.1·h → −0.2·h → −0.7·h` off the anchor.
            let position = CAKeyframeAnimation(keyPath: "position")
            position.values = [
                CGPoint(x: cx, y: spec.center.y + 0.1 * puff.size),
                CGPoint(x: cx, y: spec.center.y - 0.2 * puff.size),
                CGPoint(x: cx, y: spec.center.y - 0.7 * puff.size),
            ].map(pointValue)
            let scale = CAKeyframeAnimation(keyPath: "transform.scale")
            scale.values = [0.3, puff.scale, puff.scale * 1.15]
            let opacity = CAKeyframeAnimation(keyPath: "opacity")
            opacity.values = [0, 0.95, 0]

            let group = CAAnimationGroup()
            group.animations = [position, scale, opacity].map { track in
                track.keyTimes = [0, 0.4, 1]
                track.timingFunctions = [Anim.cssEaseOut(), Anim.cssEaseOut()]
                track.duration = GrowBurstSpec.puffDuration
                return track
            }
            group.duration = GrowBurstSpec.puffDuration
            group.beginTime = t0 + puff.delay
            group.fillMode = .forwards
            group.isRemovedOnCompletion = false
            group.delegate = cleanup
            particle.add(group, forKey: "ALShopGrowPuff")
        }

        for spark in spec.sparks {
            let particle = particleLayer(glyph: spark.glyph, fontSize: spark.size)
            particle.position = spec.center
            container.addSublayer(particle)

            let position = CAKeyframeAnimation(keyPath: "position")
            position.values = [
                spec.center,
                CGPoint(x: spec.center.x + spark.dx * 0.6, y: spec.center.y + spark.dy * 0.6),
                CGPoint(x: spec.center.x + spark.dx, y: spec.center.y + spark.dy),
            ].map(pointValue)
            let scale = CAKeyframeAnimation(keyPath: "transform.scale")
            scale.values = [0.2, 1, 0.3]
            let rotation = CAKeyframeAnimation(keyPath: "transform.rotation")
            rotation.values = [0, Double.pi / 2, 200 * Double.pi / 180]
            let opacity = CAKeyframeAnimation(keyPath: "opacity")
            opacity.values = [0, 1, 0]

            let group = CAAnimationGroup()
            group.animations = [position, scale, rotation, opacity].map { track in
                track.keyTimes = [0, 0.35, 1]
                track.timingFunctions = [sparkCurve(), sparkCurve()]
                track.duration = spark.duration
                return track
            }
            group.duration = spark.duration
            group.fillMode = .forwards
            group.isRemovedOnCompletion = false
            group.delegate = cleanup
            particle.add(group, forKey: "ALShopGrowSpark")
        }
    }

    /// `{ easing: "cubic-bezier(.2,.7,.3,1)" }` — the sparkle fling.
    static func sparkCurve() -> CAMediaTimingFunction {
        CAMediaTimingFunction(controlPoints: 0.2, 0.7, 0.3, 1)
    }

    // MARK: - Plumbing

    private static func particleLayer(glyph: String, fontSize: CGFloat) -> CATextLayer {
        let layer = CATextLayer()
        layer.string = glyph
        layer.fontSize = fontSize
        layer.alignmentMode = .center
        layer.contentsScale = 3
        // Generous box: an emoji glyph draws a little wider than its point size.
        let side = fontSize * 1.6
        layer.bounds = CGRect(x: 0, y: 0, width: side, height: side)
        return layer
    }

    private static func pointValue(_ point: CGPoint) -> NSValue {
        #if canImport(UIKit)
        return NSValue(cgPoint: point)
        #else
        return NSValue(point: point)
        #endif
    }
}

/// The web's `pending` counter: every particle decrements on finish and the
/// last one removes the whole throwaway layer. `animationDidStop` also fires
/// for a cancelled take, so the layer can never leak.
private final class ParticleCleanup: NSObject, CAAnimationDelegate {
    private var pending: Int
    private weak var container: CALayer?

    init(count: Int, container: CALayer) {
        self.pending = count
        self.container = container
    }

    func animationDidStop(_ anim: CAAnimation, finished flag: Bool) {
        pending -= 1
        if pending <= 0 {
            container?.removeFromSuperlayer()
        }
    }
}

// MARK: - The specs (pure, host-tested)

/// One purchase's flock of stars — every number from `starFlight` in `anim.ts`.
public struct StarFlightSpec: Equatable, Sendable {
    public struct Star: Equatable, Sendable {
        /// The wallet/buy-button centre.
        public let start: CGPoint
        /// The bowed midpoint (offset 0.5).
        public let mid: CGPoint
        /// The header mascot's centre.
        public let end: CGPoint
        /// `620 + i * 45` ms.
        public let duration: Double
        /// `i * 70` ms.
        public let delay: Double
    }

    public static let glyph = "⭐"
    /// `font-size: 22px`.
    public static let fontSize: CGFloat = 22
    /// `z-index: 60` — over the try-on dialog's `z-50`.
    public static let zIndex: CGFloat = 60
    public static let startScale: Double = 0.5
    public static let midScale: Double = 1.15
    public static let endScale: Double = 0.35
    public static let endOpacity: Double = 0.2

    public let stars: [Star]

    /// `if (a.width === 0 || b.width === 0) return;` — a rect that has not laid
    /// out yet aborts the whole flight.
    public init?(from: CGRect, to: CGRect, count: Int) {
        guard from.width != 0, to.width != 0 else { return nil }
        let x0 = from.midX
        let y0 = from.midY
        let x1 = to.midX
        let y1 = to.midY
        stars = (0..<count).map { i in
            // "Each star arcs on its own bow: the midpoint bulges sideways/
            // upward a bit more per star, so the flock fans out."
            let bow = Double(i % 2 == 0 ? 1 : -1) * (14 + Double(i) * 7)
            let mx = Double(x1 - x0) / 2 + bow
            let my = Double(y1 - y0) / 2 - 36 - Double(i) * 4
            return Star(
                start: CGPoint(x: x0, y: y0),
                mid: CGPoint(x: x0 + mx, y: y0 + my),
                end: CGPoint(x: x1, y: y1),
                duration: (620 + Double(i) * 45) / 1000,
                delay: Double(i) * 70 / 1000)
        }
    }
}

/// One growth celebration — the three ☁️ puffs and twelve flung sparkles.
public struct GrowBurstSpec: Equatable, Sendable {
    public struct Puff: Equatable, Sendable {
        /// `font-size` = `rect.width * 0.42`.
        public let size: CGFloat
        public let dx: Double
        public let delay: Double
        public let scale: Double
    }

    public struct Spark: Equatable, Sendable {
        public let glyph: String
        /// `16 + (i % 3) * 6`.
        public let size: CGFloat
        public let dx: Double
        public let dy: Double
        /// `720 + (i % 3) * 120` ms.
        public let duration: Double
    }

    public static let cloudGlyph = "☁️"
    public static let sparkleGlyphs = ["✨", "⭐", "🌟", "💫"]
    /// `{ duration: 900 }` on every puff.
    public static let puffDuration: Double = 0.9
    public static let sparkCount = 12

    public let center: CGPoint
    public let puffs: [Puff]
    public let sparks: [Spark]

    /// `if (rect.width === 0) return;`
    public init?(around rect: CGRect) {
        guard rect.width != 0 else { return nil }
        center = CGPoint(x: rect.midX, y: rect.midY)

        let cloud = rect.width * 0.42
        puffs = [
            Puff(size: cloud, dx: 0, delay: 0, scale: 1.3),
            Puff(size: cloud, dx: -Double(cloud) * 0.5, delay: 0.06, scale: 1),
            Puff(size: cloud, dx: Double(cloud) * 0.5, delay: 0.12, scale: 1),
        ]

        let n = Self.sparkCount
        sparks = (0..<n).map { i in
            let angle = Double(i) / Double(n) * .pi * 2 + Double(i % 2) * 0.26
            let dist = Double(rect.width) * (0.55 + Double(i % 3) * 0.14)
            return Spark(
                glyph: Self.sparkleGlyphs[i % Self.sparkleGlyphs.count],
                size: 16 + CGFloat(i % 3) * 6,
                dx: cos(angle) * dist,
                dy: sin(angle) * dist - Double(rect.height) * 0.12,
                duration: (720 + Double(i % 3) * 120) / 1000)
        }
    }
}
