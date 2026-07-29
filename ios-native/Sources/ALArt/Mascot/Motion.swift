import SwiftUI

import ALCore

// The mascot's mood animation and the "Arc-en-ciel magique" sheen sweep —
// ported from `src/mascot/Mascot.tsx` (WAAPI keyframes) and `src/index.css`
// (`@keyframes alSheen`).
//
// Invariant 2 — animation stays OFF the render path. Everything here is a
// transform on an already-built rig: `rotationEffect` / `scaleEffect` /
// `offset`, driven by `keyframeAnimator`, which hands the interpolation to the
// render server. Nothing in this file re-parses a path, rebuilds a draw list or
// pushes state through a `TimelineView` to move a pixel. The web is the same
// shape: `el.animate(...)` on the `<svg>` element runs off React's render path,
// and the sheen is pure CSS for the same stated reason.
//
// Two unit traps, both real, both from spec/mascot.md §10:
//
//   1. The bob's `translateY(-5px)` is on the `<svg>` ELEMENT, so those px are
//      SCREEN pixels — a 220 pt mascot bobs by the same 5 pt as an 88 pt one.
//      The sheen's ±150px are on an element INSIDE the SVG, so they are viewBox
//      USER UNITS and must be multiplied by `size / 100`. Scaling the first or
//      not scaling the second is a bug that only shows up at a non-default size,
//      which is exactly where nobody looks.
//   2. React cancels the previous animation on a mood change, which reverts the
//      element to its base (identity) transform before the new one starts. A
//      naïve port leaves a mid-flight scale composing with the incoming bob and
//      the mascot drifts. `keyframeAnimator(initialValue: .identity, trigger:)`
//      is the equivalent: the trigger restarts the timeline FROM the initial
//      value, and every plan's first keyframe is emitted with duration 0 so the
//      jump to it is instantaneous, exactly as WAAPI applies an offset-0 frame.
//
// Invariant 6 — under reduced motion there is no bob, no pop, no cheer, and the
// sheen is FROZEN at its base transform, not hidden: a static rainbow band
// still crosses the pet (`animation: none !important` in the media query leaves
// the element at `translateX(0)`). `ReduceMotionSource` is injected (D14) so the
// D3 render harness and the tests can force it.

// MARK: - The animated value

/// The three transform channels the web keyframes touch, in one animatable
/// value. `y` is in SCREEN points (see trap 1).
public struct MascotTransform: Equatable, Sendable {
    public var y: Double
    /// Degrees, positive = clockwise, as in CSS.
    public var rotation: Double
    public var scale: Double

    public init(y: Double = 0, rotation: Double = 0, scale: Double = 1) {
        self.y = y
        self.rotation = rotation
        self.scale = scale
    }

    /// The un-animated rig. Every plan restarts from here (trap 2).
    public static let identity = MascotTransform()
}

/// WAAPI's `easing`, which applies to each keyframe INTERVAL, not to the
/// timeline as a whole — so it maps onto `LinearKeyframe`'s `timingCurve`
/// per segment, not onto one curve over the whole track.
public enum MascotEasing: Sendable {
    case easeInOut
    case easeOut

    var unitCurve: UnitCurve {
        switch self {
        case .easeInOut: return .easeInOut
        case .easeOut: return .easeOut
        }
    }
}

/// One entry of a WAAPI keyframe list: `offset` is 0…1 along the timeline.
public struct MascotKeyframe: Equatable, Sendable {
    public let offset: Double
    public let transform: MascotTransform

    public init(offset: Double, transform: MascotTransform) {
        self.offset = offset
        self.transform = transform
    }
}

/// A whole `el.animate(keyframes, options)` call as data, so the timings and the
/// keyframe SHAPE can be asserted on the host without a running animation.
public struct MascotMotionPlan: Equatable, Sendable {
    public let keyframes: [MascotKeyframe]
    /// Seconds (the TSX quotes milliseconds).
    public let duration: Double
    /// `iterations: Infinity` — true for the idle bob only.
    public let repeats: Bool
    public let easing: MascotEasing

    /// The timeline as `(value, duration)` segments, ready for
    /// `LinearKeyframe`. The FIRST segment has duration 0: it is the offset-0
    /// keyframe, which WAAPI applies instantly, and which is what makes the
    /// restart-from-identity in trap 2 visually correct for the bob (whose
    /// offset-0 frame is `rotate(-1.5deg)`, not identity).
    public var segments: [(transform: MascotTransform, duration: Double)] {
        guard let first = keyframes.first else { return [] }
        var out: [(transform: MascotTransform, duration: Double)] = [(first.transform, 0)]
        for (index, frame) in keyframes.enumerated().dropFirst() {
            let previous = keyframes[index - 1].offset
            out.append((frame.transform, (frame.offset - previous) * duration))
        }
        return out
    }
}

// MARK: - The three plans

public enum MascotMotion {

    /// `el.style.transformOrigin = "50% 82%"`.
    public static let origin = UnitPoint(x: 0.5, y: 0.82)

    /// ```ts
    /// const bob: Keyframe[] = [
    ///   { transform: "translateY(0) rotate(-1.5deg)" },
    ///   { transform: "translateY(-5px) rotate(1.5deg)", offset: 0.5 },
    ///   { transform: "translateY(0) rotate(-1.5deg)" },
    /// ];
    /// ``` — 2600 ms, ease-in-out, iterations: Infinity.
    public static let bob = MascotMotionPlan(
        keyframes: [
            MascotKeyframe(offset: 0, transform: MascotTransform(y: 0, rotation: -1.5, scale: 1)),
            MascotKeyframe(offset: 0.5, transform: MascotTransform(y: -5, rotation: 1.5, scale: 1)),
            MascotKeyframe(offset: 1, transform: MascotTransform(y: 0, rotation: -1.5, scale: 1)),
        ],
        duration: 2.6,
        repeats: true,
        easing: .easeInOut
    )

    /// ```ts
    /// const pop: Keyframe[] = [
    ///   { transform: "scale(1)" },
    ///   { transform: "scale(1.16) rotate(4deg)", offset: 0.4 },
    ///   { transform: "scale(0.98)", offset: 0.72 },
    ///   { transform: "scale(1)" },
    /// ];
    /// ``` — 480 ms, ease-out, one shot.
    ///
    /// NB: frames 3 and 4 write `scale(…)` with no `rotate`, which in CSS means
    /// rotation 0 — the pop snaps back to square, it does not hold the 4°.
    public static let pop = MascotMotionPlan(
        keyframes: [
            MascotKeyframe(offset: 0, transform: MascotTransform(y: 0, rotation: 0, scale: 1)),
            MascotKeyframe(offset: 0.4, transform: MascotTransform(y: 0, rotation: 4, scale: 1.16)),
            MascotKeyframe(offset: 0.72, transform: MascotTransform(y: 0, rotation: 0, scale: 0.98)),
            MascotKeyframe(offset: 1, transform: MascotTransform(y: 0, rotation: 0, scale: 1)),
        ],
        duration: 0.48,
        repeats: false,
        easing: .easeOut
    )

    /// ```ts
    /// const cheer: Keyframe[] = [
    ///   { transform: "scale(1) rotate(0deg)" },
    ///   { transform: "scale(1.2) rotate(-6deg)", offset: 0.25 },
    ///   { transform: "scale(1.1) rotate(6deg)", offset: 0.5 },
    ///   { transform: "scale(1.22) rotate(-4deg)", offset: 0.74 },
    ///   { transform: "scale(1) rotate(0deg)" },
    /// ];
    /// ``` — 680 ms, ease-out, one shot.
    public static let cheer = MascotMotionPlan(
        keyframes: [
            MascotKeyframe(offset: 0, transform: MascotTransform(y: 0, rotation: 0, scale: 1)),
            MascotKeyframe(offset: 0.25, transform: MascotTransform(y: 0, rotation: -6, scale: 1.2)),
            MascotKeyframe(offset: 0.5, transform: MascotTransform(y: 0, rotation: 6, scale: 1.1)),
            MascotKeyframe(offset: 0.74, transform: MascotTransform(y: 0, rotation: -4, scale: 1.22)),
            MascotKeyframe(offset: 1, transform: MascotTransform(y: 0, rotation: 0, scale: 1)),
        ],
        duration: 0.68,
        repeats: false,
        easing: .easeOut
    )

    /// The whole of `Mascot.tsx`'s effect body as one pure decision.
    ///
    /// ```ts
    /// if (preview) return;                              // shop thumbnails never bob
    /// const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
    /// if (reduced) return;                              // NOTHING runs, not "less"
    /// mood === "idle" ? bob : mood === "cheer" ? cheer : pop
    /// ```
    ///
    /// `nil` means "start no animation at all", which is the web behaviour —
    /// not a shortened one.
    public static func plan(mood: Mood, preview: Bool, reduceMotion: Bool) -> MascotMotionPlan? {
        // "Shop thumbnails never bob — a grid of jittering mascots is noise,
        // not signal."
        if preview { return nil }
        if reduceMotion { return nil }
        switch mood {
        case .idle: return bob
        case .cheer: return cheer
        case .happy: return pop
        }
    }
}

// MARK: - The sheen sweep

/// `@keyframes alSheen` from `src/index.css`, bound by `.al-sheen` on the
/// `RainbowSheen` band.
///
/// ```css
/// @keyframes alSheen {
///   from { transform: translateX(var(--al-from, -150px)); }
///   to   { transform: translateX(var(--al-to, 150px)); }
/// }
/// .al-sheen { animation: alSheen 3.6s linear infinite; }
/// ```
///
/// `RainbowSheen` sets `--al-from: -150px` / `--al-to: 150px`, i.e. the
/// defaults. These are viewBox user units (trap 1): the rect they move lives
/// inside the `<svg>`, so a CSS pixel there is one of the 100 mascot units.
public enum MascotSheen {
    public static let fromUnits: Double = -150
    public static let toUnits: Double = 150
    /// Seconds. Linear, infinite, no autoreverse — it wraps, and the wrap
    /// happens off the pet where the silhouette mask hides it.
    public static let duration: Double = 3.6

    /// Where the band sits when the animation is off. This is the CSS BASE
    /// transform (no `translateX`), not `fromUnits` — invariant 6 wants a
    /// static rainbow band across the centre of the pet, visible, not hidden
    /// and not parked off-screen at −150.
    public static let parkedUnits: Double = 0

    /// The band's x offset in viewBox units at time `t` seconds. Pure, so the
    /// wrap and the reduced-motion park are testable without a running clock.
    public static func offsetUnits(at t: Double, reduceMotion: Bool) -> Double {
        if reduceMotion { return parkedUnits }
        let phase = (t.truncatingRemainder(dividingBy: duration) + duration)
            .truncatingRemainder(dividingBy: duration) / duration
        return fromUnits + (toUnits - fromUnits) * phase
    }
}

// MARK: - View plumbing

extension View {
    /// Apply a `MascotTransform` the way the CSS transform list does.
    ///
    /// CSS reads `translateY(-5px) rotate(1.5deg)` right-to-left when applying
    /// it to the geometry: rotate first, then translate. SwiftUI modifiers
    /// apply bottom-up in the same sense, so `.rotationEffect(…).offset(…)`
    /// is that order. `transform-origin: 50% 82%` becomes the anchor on the
    /// two effects that have one; `translate` has no origin in CSS either.
    /// Scale and rotation are both uniform and share the anchor, so their
    /// relative order does not matter.
    nonisolated func mascotTransform(_ t: MascotTransform) -> some View {
        rotationEffect(.degrees(t.rotation), anchor: MascotMotion.origin)
            .scaleEffect(t.scale, anchor: MascotMotion.origin)
            .offset(y: t.y)
    }
}

@KeyframeTrackContentBuilder<Double>
private func channel(
    _ plan: MascotMotionPlan,
    _ key: KeyPath<MascotTransform, Double>
) -> some KeyframeTrackContent<Double> {
    for segment in plan.segments {
        LinearKeyframe(
            segment.transform[keyPath: key],
            duration: segment.duration,
            timingCurve: plan.easing.unitCurve
        )
    }
}

@KeyframesBuilder<MascotTransform>
private func tracks(_ plan: MascotMotionPlan) -> some Keyframes<MascotTransform> {
    KeyframeTrack(\MascotTransform.y) { channel(plan, \.y) }
    KeyframeTrack(\MascotTransform.rotation) { channel(plan, \.rotation) }
    KeyframeTrack(\MascotTransform.scale) { channel(plan, \.scale) }
}

/// The mood animation, as a modifier over an already-drawn rig.
///
/// Apply it to the WHOLE mascot (the web animates the `<svg>` element, shadow
/// included), never to a part.
public struct MascotMoodMotion: ViewModifier {
    public let mood: Mood
    public let preview: Bool
    public let reduceMotion: ReduceMotionSource

    public init(mood: Mood, preview: Bool, reduceMotion: ReduceMotionSource) {
        self.mood = mood
        self.preview = preview
        self.reduceMotion = reduceMotion
    }

    // NB: `ALCore` exports a namespace `enum Content`, which shadows
    // `ViewModifier`'s inferred `Content` associated type at file scope and
    // makes the conformance fail with "Content does not conform to View".
    // Spelling the default witness out fixes it. Any ViewModifier in a file
    // that imports ALCore needs this line.
    public typealias Content = _ViewModifier_Content<Self>

    @ViewBuilder
    public func body(content: Content) -> some View {
        let plan = MascotMotion.plan(mood: mood, preview: preview, reduceMotion: reduceMotion.isReduced)
        // The if/else is load-bearing, not style: the two branches are distinct
        // view identities, so switching between the looping bob and a one-shot
        // reaction tears the old animator down and starts the new one from
        // `initialValue` — React's `anim.cancel()` (trap 2). Within the
        // one-shot branch the `trigger:` does the same job for happy → cheer.
        if let plan, plan.repeats {
            content.keyframeAnimator(initialValue: MascotTransform.identity, repeating: true) { view, value in
                view.mascotTransform(value)
            } keyframes: { _ in
                tracks(plan)
            }
        } else if let plan {
            content.keyframeAnimator(initialValue: MascotTransform.identity, trigger: mood) { view, value in
                view.mascotTransform(value)
            } keyframes: { _ in
                tracks(plan)
            }
        } else {
            // preview or reduced motion: the rig sits at its base transform.
            content
        }
    }
}

/// The sheen band's sweep. Apply to the gradient band ONLY, inside the
/// silhouette mask — the mask itself never moves.
///
/// `unitScale` is points per viewBox unit (`size / 100` for the mascot). See
/// trap 1: these offsets are user units and MUST be scaled; the mood bob's are
/// screen points and must NOT be.
public struct MascotSheenMotion: ViewModifier {
    public let unitScale: CGFloat
    public let reduceMotion: ReduceMotionSource

    public init(unitScale: CGFloat, reduceMotion: ReduceMotionSource) {
        self.unitScale = unitScale
        self.reduceMotion = reduceMotion
    }

    // NB: `ALCore` exports a namespace `enum Content`, which shadows
    // `ViewModifier`'s inferred `Content` associated type at file scope and
    // makes the conformance fail with "Content does not conform to View".
    // Spelling the default witness out fixes it. Any ViewModifier in a file
    // that imports ALCore needs this line.
    public typealias Content = _ViewModifier_Content<Self>

    @ViewBuilder
    public func body(content: Content) -> some View {
        if reduceMotion.isReduced {
            // `animation: none !important` — the band parks at the CSS base
            // transform and stays VISIBLE (invariant 6). Do not hide it.
            content.offset(x: CGFloat(MascotSheen.parkedUnits) * unitScale)
        } else {
            content.keyframeAnimator(initialValue: MascotSheen.fromUnits, repeating: true) { view, units in
                view.offset(x: CGFloat(units) * unitScale)
            } keyframes: { _ in
                KeyframeTrack {
                    LinearKeyframe(MascotSheen.toUnits, duration: MascotSheen.duration)
                }
            }
        }
    }
}

public extension View {
    /// `Mascot.tsx`'s mood effect.
    func mascotMoodMotion(mood: Mood, preview: Bool, reduceMotion: ReduceMotionSource) -> some View {
        modifier(MascotMoodMotion(mood: mood, preview: preview, reduceMotion: reduceMotion))
    }

    /// `.al-sheen`. `unitScale` = points per viewBox unit.
    func mascotSheenMotion(unitScale: CGFloat, reduceMotion: ReduceMotionSource) -> some View {
        modifier(MascotSheenMotion(unitScale: unitScale, reduceMotion: reduceMotion))
    }
}
