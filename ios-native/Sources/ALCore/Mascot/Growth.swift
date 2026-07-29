import Foundation

// Port of `src/mascot/growth.ts` — the PURE ARITHMETIC half of it.
//
// D8 — geometry maths belongs to ALCore, not ALArt: `accessoryAnchors` reads
// `Layout`, the shop preview reads `stageScale`, and the whole thing is
// host-testable with no SwiftUI in sight.
//
// NOT ported here, deliberately: the TS file's `RigProps` interface. It is a
// drawing prop bag (`uid` for <defs> gradient ids, `preview` for the shop
// thumbnail) with no meaning outside a rig, so it belongs to ALArt/Mascot with
// the rigs that consume it. Everything else — INK, lerp, pick, mix, ramp,
// stageScale, Pose, LegSpec, Layout, poseFor, layoutFor — is here, in
// declaration order.

/**
 * Growth model — CONTINUOUS PER-STAGE interpolation + a per-stage feature
 * timeline. Every stage 0..9 gets its OWN geometry (head:body ratio, limb
 * length, posture, eye size) via `ramp()` anchor curves, so a child clicking
 * 3→4→5 sees a real change each time. On top of that each species owns a
 * STAGE_SPEC selecting part variants (horn height, wing scale, tail step…).
 *
 * Silhouette arc: stade 0 fully folded helpless baby (oversized head, huge
 * eyes, stubby/absent limbs) → wobbling to rise (1) → crouched first steps (2)
 * → upright youngster (3-5) → slim adult (6) → proud (7-8) → majestic (9).
 *
 * Owned by AGENT A.
 */
public enum Growth {}

public enum Pose: String, CaseIterable, Hashable, Codable, Sendable {
    case lying
    case wobbly
    case standing
    case proud
}

public struct LegSpec: Hashable, Sendable {
    public var hipX: Double
    public var hipY: Double
    public var footX: Double
    public var footY: Double
    public var bend: Double
    public var side: Double
    public var back: Bool

    public init(
        hipX: Double, hipY: Double, footX: Double, footY: Double,
        bend: Double, side: Double, back: Bool
    ) {
        self.hipX = hipX
        self.hipY = hipY
        self.footX = footX
        self.footY = footY
        self.bend = bend
        self.side = side
        self.back = back
    }
}

public struct Layout: Hashable, Sendable {
    public var pose: Pose
    /// false only for lying babies (stades 0-1).
    public var standing: Bool
    public var bodyCX: Double
    public var bodyCY: Double
    public var bodyRX: Double
    public var bodyRY: Double
    public var headCX: Double
    public var headCY: Double
    public var headR: Double
    /// Centralised huge-baby → small-adult eye radius.
    public var eyeR: Double
    public var feetY: Double
    public var legs: [LegSpec]

    public init(
        pose: Pose, standing: Bool,
        bodyCX: Double, bodyCY: Double, bodyRX: Double, bodyRY: Double,
        headCX: Double, headCY: Double, headR: Double,
        eyeR: Double, feetY: Double, legs: [LegSpec]
    ) {
        self.pose = pose
        self.standing = standing
        self.bodyCX = bodyCX
        self.bodyCY = bodyCY
        self.bodyRX = bodyRX
        self.bodyRY = bodyRY
        self.headCX = headCX
        self.headCY = headCY
        self.headR = headR
        self.eyeR = eyeR
        self.feetY = feetY
        self.legs = legs
    }
}
// NB: worn-accessory placement (throat/hat/feet) is NOT a layout field — it lives
// in accessoryAnchors() (anchors.ts), head-relative so it tracks the shrinking
// head. Do not add a body-relative "neck" point here; the baby's head rides over
// it (that was the "bow in the middle of the face" bug).

/// Piecewise-linear interpolation anchor stop: `[stage, value]` in the TS.
public typealias RampStop = (stage: Double, value: Double)

extension Growth {

    public static let ink = "#5A3A1E"

    public static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double {
        a + (b - a) * t
    }

    static func clamp(_ n: Double, _ lo: Double, _ hi: Double) -> Double {
        max(lo, min(hi, n))
    }

    /// Read a colour/style slot with a per-species default.
    public static func pick(_ m: [String: String], _ slot: String, _ fallback: String) -> String {
        m[slot] ?? fallback
    }

    /// Linear blend between two #rrggbb hex colours (t: 0=a … 1=b).
    // NB: transcribed operation for operation, including the `(1 << 24) + …`
    // trick that guarantees six hex digits and the leading "1" that `.slice(1)`
    // throws away. `Math.round` rounds half toward +∞ (so `Math.round(-0.5)` is
    // `-0`), which is `floor(x + 0.5)`, NOT Swift's `.rounded()` — that rounds
    // half AWAY FROM ZERO. Identical for every value this can actually produce
    // (both endpoints are 0…255 and t is 0…1), different the moment someone
    // extrapolates, so it is written the JS way.
    public static func mix(_ a: String, _ b: String, _ t: Double) -> String {
        let pa = Int(a.dropFirst(), radix: 16) ?? 0
        let pb = Int(b.dropFirst(), radix: 16) ?? 0
        let r = jsRound(Double((pa >> 16) & 255) + Double(((pb >> 16) & 255) - ((pa >> 16) & 255)) * t)
        let g = jsRound(Double((pa >> 8) & 255) + Double(((pb >> 8) & 255) - ((pa >> 8) & 255)) * t)
        let c = jsRound(Double(pa & 255) + Double((pb & 255) - (pa & 255)) * t)
        return "#" + String((1 << 24) + (r << 16) + (g << 8) + c, radix: 16).dropFirst()
    }

    /// `Math.round` — half rounds toward +∞.
    private static func jsRound(_ x: Double) -> Int {
        Int((x + 0.5).rounded(.down))
    }

    /// Piecewise-linear interpolation over [stage, value] anchor stops.
    public static func ramp(_ stage: Double, _ stops: [RampStop]) -> Double {
        let first = stops[0]
        let last = stops[stops.count - 1]
        if stage <= first.stage { return first.value }
        if stage >= last.stage { return last.value }
        for i in 0..<(stops.count - 1) {
            let (s0, v0) = stops[i]
            let (s1, v1) = stops[i + 1]
            if stage >= s0 && stage <= s1 { return lerp(v0, v1, (stage - s0) / (s1 - s0)) }
        }
        return last.value
    }

    /**
     * Overall on-screen size multiplier per stage — pivoted at the feet so the
     * creature grows UPWARD off the ground line. This is a big part of why the
     * later stages feel like a reward: the mascot visibly gets bigger, not just
     * more decorated. Stades 0-2 stay 1 (the untouched early game).
     */
    static let stageScaleTable: [Double] = [1, 1, 1, 1.0, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3]

    // NB: `Int`, where the TS says `number`. `STAGE_SCALE[clamp(stage, 0, 9)]`
    // is an array index, so a fractional stage would read `undefined` and
    // poison the transform with NaN — a latent JS bug that cannot be reached
    // because the one call site passes `config.stage`. Typing it `Int` makes it
    // unreachable in Swift too rather than silently "fixing" the behaviour.
    public static func stageScale(_ stage: Int) -> Double {
        stageScaleTable[Int(clamp(Double(stage), 0, 9))]
    }

    public static func poseFor(_ stage: Double) -> Pose {
        if stage <= 1 { return .lying }
        if stage <= 3 { return .wobbly }
        if stage <= 6 { return .standing }
        return .proud
    }

    private static func quadLegs(
        _ bodyCX: Double,
        _ bodyCY: Double,
        _ bodyRY: Double,
        _ feetY: Double,
        _ sf: Double,
        _ sb: Double,
        _ bend: Double
    ) -> [LegSpec] {
        let hipY = bodyCY + bodyRY * 0.4
        func mk(_ dx: Double, _ back: Bool) -> LegSpec {
            // `Math.sign(dx) || 1` — sign(0) is 0, which is falsy, so a centred
            // leg counts as a right-hand leg.
            let sign = dx > 0 ? 1.0 : (dx < 0 ? -1.0 : 0.0)
            let side = sign == 0 ? 1.0 : sign
            return LegSpec(
                hipX: bodyCX + dx,
                hipY: hipY,
                footX: bodyCX + dx + side * bend * 0.6,
                footY: feetY,
                bend: bend,
                side: side,
                back: back
            )
        }
        return [mk(-sb, true), mk(sb, true), mk(-sf, false), mk(sf, false)]
    }

    /// Fully-computed, per-stage-distinct geometry.
    public static func layoutFor(_ stage: Double) -> Layout {
        let s = clamp(stage, 0, 9)
        let pose = poseFor(s)
        // Head shrinks a LOT from baby→adult; body grows → dramatic baby ratio.
        let headR = ramp(s, [(0, 28), (1, 27), (2, 24.5), (3, 22), (4, 20), (5, 18.5), (6, 17.5), (7, 17), (9, 16.5)])
        let bodyRX = ramp(s, [(0, 19), (1, 19), (2, 17.5), (3, 18), (4, 19), (5, 19.7), (6, 20.5), (9, 22)])
        let bodyRY = ramp(s, [(0, 12), (1, 12.5), (2, 15), (3, 16.5), (4, 18), (5, 19), (6, 20), (7, 21), (9, 23)])
        let eyeR = headR * ramp(s, [(0, 0.31), (1, 0.3), (2, 0.26), (3, 0.22), (4, 0.19), (5, 0.17), (6, 0.16), (9, 0.155)])

        if pose == .lying {
            let bodyCX = 50.0
            let bodyCY = 80.0
            let lyRX = bodyRX + 8  // wide splayed belly
            let lyRY = bodyRY * 0.82  // flatter
            // stade 0 head resting low & forward; stade 1 head lifted (rising).
            let headCX = ramp(s, [(0, 59), (1, 54)])
            let headCY = ramp(s, [(0, 70), (1, 56)])
            return Layout(
                pose: pose,
                standing: false,
                bodyCX: bodyCX,
                bodyCY: bodyCY,
                bodyRX: lyRX,
                bodyRY: lyRY,
                headCX: headCX,
                headCY: headCY,
                headR: headR,
                eyeR: eyeR,
                feetY: 91,
                legs: []
            )
        }

        let feetY = 94.0
        let legLen = ramp(s, [(2, 4.5), (3, 7.5), (4, 10.5), (5, 12.5), (6, 14), (7, 15), (8, 16), (9, 17)])
        let bodyCX = 50.0
        let bodyCY = feetY - legLen - bodyRY
        let neckExtra = ramp(s, [(6, 0), (7, 1.5), (8, 3), (9, 4.5)])
        let headCY = bodyCY - bodyRY * 0.7 - headR * 0.58 - neckExtra
        let sf = ramp(s, [(2, 10.5), (3, 9.8), (4, 9), (6, 8), (9, 8)])
        let sb = ramp(s, [(2, 17), (3, 16), (4, 15), (6, 14), (9, 13)])
        let bend = ramp(s, [(2, 6), (3, 4.2), (4, 2.6), (5, 1.6), (6, 1), (7, 0.5), (9, 0)])
        return Layout(
            pose: pose,
            standing: true,
            bodyCX: bodyCX,
            bodyCY: bodyCY,
            bodyRX: bodyRX,
            bodyRY: bodyRY,
            headCX: 50,
            headCY: headCY,
            headR: headR,
            eyeR: eyeR,
            feetY: feetY,
            legs: quadLegs(bodyCX, bodyCY, bodyRY, feetY, sf, sb, bend)
        )
    }
}
