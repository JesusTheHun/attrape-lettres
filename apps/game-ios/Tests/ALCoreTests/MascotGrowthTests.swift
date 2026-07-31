import Testing

@testable import ALCore

// New coverage — `src/mascot/growth.ts` had no TypeScript suite of its own; it
// was only ever exercised through `anchors.test.ts` and by eye in Storybook.
//
// Every expected number here was produced by RUNNING the TypeScript (esbuild →
// node) and pasted in, including the ones that are ugly
// (`headCY` at stade 3 is 45.690000000000005, not 45.69). They are the oracle:
// if the Swift changes and one fails, the Swift is wrong. Rounding them to look
// tidy would be the port bug this file exists to catch.

@Suite("Growth — ramp, mix, stageScale, poseFor")
struct MascotGrowthPrimitiveTests {

    @Test("ramp clamps outside the stops and interpolates linearly inside")
    func rampClampsAndInterpolates() {
        let stops: [RampStop] = [(0, 5), (3, 11)]
        #expect(Growth.ramp(-1, stops) == 5)  // before the first stop
        #expect(Growth.ramp(0, stops) == 5)
        #expect(Growth.ramp(1.5, stops) == 8)  // half way
        #expect(Growth.ramp(3, stops) == 11)
        #expect(Growth.ramp(9, stops) == 11)  // after the last stop
    }

    @Test("ramp walks multi-segment stop lists, each segment on its own slope")
    func rampWalksSegments() {
        // Segment slopes differ, so a single-segment implementation passes the
        // ends and fails the middle.
        let stops: [RampStop] = [(0, 0), (1, 10), (5, 12)]
        #expect(Growth.ramp(0.5, stops) == 5)
        #expect(Growth.ramp(1, stops) == 10)
        #expect(Growth.ramp(3, stops) == 11)
    }

    @Test("ramp survives a one-stop list")
    func rampSingleStop() {
        #expect(Growth.ramp(4, [(2, 7)]) == 7)
        #expect(Growth.ramp(0, [(2, 7)]) == 7)
    }

    @Test("stageScale is the reward curve, clamped at both ends")
    func stageScaleTable() {
        let want: [Double] = [1, 1, 1, 1, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3]
        for s in 0..<10 { #expect(Growth.stageScale(s) == want[s], "stade \(s)") }
        #expect(Growth.stageScale(-3) == 1)
        #expect(Growth.stageScale(42) == 1.3)
        // Monotone: every stade from 3 up is at least as big as the one before,
        // which is the whole point ("the mascot visibly gets bigger").
        for s in 1..<10 { #expect(Growth.stageScale(s) >= Growth.stageScale(s - 1)) }
    }

    @Test("poseFor walks lying → wobbly → standing → proud")
    func poseForWalksTheArc() {
        #expect(Growth.poseFor(0) == .lying)
        #expect(Growth.poseFor(1) == .lying)
        #expect(Growth.poseFor(2) == .wobbly)
        #expect(Growth.poseFor(3) == .wobbly)
        #expect(Growth.poseFor(4) == .standing)
        #expect(Growth.poseFor(6) == .standing)
        #expect(Growth.poseFor(7) == .proud)
        #expect(Growth.poseFor(9) == .proud)
        // Unclamped in the TS — poseFor is called with the raw stage.
        #expect(Growth.poseFor(-1) == .lying)
        #expect(Growth.poseFor(99) == .proud)
    }

    @Test("mix blends two hex colours and always returns six lowercase digits")
    func mixBlends() {
        #expect(Growth.mix("#000000", "#FFFFFF", 0.5) == "#808080")
        #expect(Growth.mix("#5A3A1E", "#FFD54F", 0.25) == "#83612a")
        #expect(Growth.mix("#5A3A1E", "#FFD54F", 0) == "#5a3a1e")  // lowercased, not echoed
        #expect(Growth.mix("#5A3A1E", "#FFD54F", 1) == "#ffd54f")
        #expect(Growth.mix("#FF0000", "#00FF00", 1) == "#00ff00")
        #expect(Growth.mix("#FF8A65", "#7FD1D8", 0.5) == "#bfae9f")
        // Leading zeroes survive the `(1 << 24) + …` trick.
        #expect(Growth.mix("#000000", "#000102", 1) == "#000102")
        for c in [Growth.mix("#123456", "#654321", 0.37)] {
            #expect(c.count == 7)
            #expect(c.hasPrefix("#"))
            #expect(c.lowercased() == c)
        }
    }

    @Test("pick reads a slot with a per-species fallback")
    func pickFallsBack() {
        let colors = ["bodyColor": "#FFD6E8"]
        #expect(Growth.pick(colors, ColorSlot.Unicorn.body, "#F5ECFF") == "#FFD6E8")
        #expect(Growth.pick(colors, ColorSlot.Unicorn.horn, "#FFD54F") == "#FFD54F")
        // An unknown slot written by an older build round-trips untouched
        // (Mascot.swift's note on why the config is `[String: String]`).
        #expect(Growth.pick(["mysteryColor": "#ABCDEF"], "mysteryColor", "#000") == "#ABCDEF")
    }

    @Test("lerp and INK")
    func lerpAndInk() {
        #expect(Growth.lerp(0, 10, 0.25) == 2.5)
        #expect(Growth.lerp(10, 0, 1) == 0)
        #expect(Growth.ink == "#5A3A1E")
    }
}

@Suite("Growth — layoutFor, the per-stage geometry")
struct MascotGrowthLayoutTests {

    /// Golden geometry, straight out of the TypeScript.
    private struct Golden {
        let stage: Double
        let pose: Pose
        let standing: Bool
        let bodyCX: Double
        let bodyCY: Double
        let bodyRX: Double
        let bodyRY: Double
        let headCX: Double
        let headCY: Double
        let headR: Double
        let eyeR: Double
        let feetY: Double
        let legs: Int
    }

    private static let goldens: [Golden] = [
        Golden(stage: 0, pose: .lying, standing: false, bodyCX: 50, bodyCY: 80, bodyRX: 27, bodyRY: 9.84, headCX: 59, headCY: 70, headR: 28, eyeR: 8.68, feetY: 91, legs: 0),
        Golden(stage: 1, pose: .lying, standing: false, bodyCX: 50, bodyCY: 80, bodyRX: 27, bodyRY: 10.25, headCX: 54, headCY: 56, headR: 27, eyeR: 8.1, feetY: 91, legs: 0),
        Golden(stage: 2, pose: .wobbly, standing: true, bodyCX: 50, bodyCY: 74.5, bodyRX: 17.5, bodyRY: 15, headCX: 50, headCY: 49.79, headR: 24.5, eyeR: 6.37, feetY: 94, legs: 4),
        Golden(stage: 3, pose: .wobbly, standing: true, bodyCX: 50, bodyCY: 70, bodyRX: 18, bodyRY: 16.5, headCX: 50, headCY: 45.690000000000005, headR: 22, eyeR: 4.84, feetY: 94, legs: 4),
        Golden(stage: 5, pose: .standing, standing: true, bodyCX: 50, bodyCY: 62.5, bodyRX: 19.7, bodyRY: 19, headCX: 50, headCY: 38.470000000000006, headR: 18.5, eyeR: 3.145, feetY: 94, legs: 4),
        Golden(stage: 9, pose: .proud, standing: true, bodyCX: 50, bodyCY: 54, bodyRX: 22, bodyRY: 23, headCX: 50, headCY: 23.830000000000005, headR: 16.5, eyeR: 2.5575, feetY: 94, legs: 4),
        // Fractional stages are reachable — the ramps are continuous by design.
        Golden(stage: 4.5, pose: .standing, standing: true, bodyCX: 50, bodyCY: 64, bodyRX: 19.35, bodyRY: 18.5, headCX: 50, headCY: 39.885, headR: 19.25, eyeR: 3.465, feetY: 94, legs: 4),
        // Clamped: -1 is stade 0's geometry, 12 is stade 9's.
        Golden(stage: -1, pose: .lying, standing: false, bodyCX: 50, bodyCY: 80, bodyRX: 27, bodyRY: 9.84, headCX: 59, headCY: 70, headR: 28, eyeR: 8.68, feetY: 91, legs: 0),
        Golden(stage: 12, pose: .proud, standing: true, bodyCX: 50, bodyCY: 54, bodyRX: 22, bodyRY: 23, headCX: 50, headCY: 23.830000000000005, headR: 16.5, eyeR: 2.5575, feetY: 94, legs: 4),
    ]

    @Test("matches the TypeScript bit for bit at every pinned stage")
    func matchesTheTypeScript() {
        for g in Self.goldens {
            let l = Growth.layoutFor(g.stage)
            #expect(l.pose == g.pose, "pose @\(g.stage)")
            #expect(l.standing == g.standing, "standing @\(g.stage)")
            #expect(l.bodyCX == g.bodyCX, "bodyCX @\(g.stage)")
            #expect(l.bodyCY == g.bodyCY, "bodyCY @\(g.stage)")
            #expect(l.bodyRX == g.bodyRX, "bodyRX @\(g.stage)")
            #expect(l.bodyRY == g.bodyRY, "bodyRY @\(g.stage)")
            #expect(l.headCX == g.headCX, "headCX @\(g.stage)")
            #expect(l.headCY == g.headCY, "headCY @\(g.stage)")
            #expect(l.headR == g.headR, "headR @\(g.stage)")
            #expect(l.eyeR == g.eyeR, "eyeR @\(g.stage)")
            #expect(l.feetY == g.feetY, "feetY @\(g.stage)")
            #expect(l.legs.count == g.legs, "legs @\(g.stage)")
        }
    }

    /// The lying newborn's `bodyRX` is the ramped value + 8 ("wide splayed
    /// belly") and its `bodyRY` is ×0.82 ("flatter"). Dropping either of those
    /// two lines still passes a "does it draw" check.
    @Test("the lying newborn's belly is widened and flattened")
    func lyingBellyIsWidenedAndFlattened() {
        let baby = Growth.layoutFor(0)
        #expect(baby.bodyRX == 19 + 8)
        #expect(baby.bodyRY == 12 * 0.82)
        // …and the head rests low and FORWARD over it, then lifts at stade 1.
        #expect(baby.headCX == 59)
        #expect(Growth.layoutFor(1).headCX == 54)
        #expect(Growth.layoutFor(1).headCY < baby.headCY)
    }

    /// The silhouette arc in one assertion: the head shrinks and the body grows,
    /// monotonically, across the whole of the growth range. This is what makes
    /// clicking 3→4→5 feel like something happened.
    @Test("head shrinks and body grows monotonically, 0 → 9")
    func silhouetteArcIsMonotone() {
        var previous = Growth.layoutFor(0)
        for s in 1...9 {
            let l = Growth.layoutFor(Double(s))
            #expect(l.headR <= previous.headR, "headR grew at stade \(s)")
            #expect(l.eyeR <= previous.eyeR, "eyeR grew at stade \(s)")
            #expect(l.bodyRY >= previous.bodyRY, "bodyRY shrank at stade \(s)")
            previous = l
        }
        // …and it is a DRAMATIC arc, not a rounding error: the baby's head is
        // nearly 1.7× the adult's, its eye nearly 3.4×.
        #expect(Growth.layoutFor(0).headR / Growth.layoutFor(9).headR > 1.65)
        #expect(Growth.layoutFor(0).eyeR / Growth.layoutFor(9).eyeR > 3.3)
    }

    @Test("every stage produces DIFFERENT geometry — no two stades draw alike")
    func everyStageIsDistinct() {
        var seen = Set<Layout>()
        for s in 0..<10 { seen.insert(Growth.layoutFor(Double(s))) }
        #expect(seen.count == 10)
    }

    @Test("standing stages stand on the ground line with four legs")
    func standingStagesHaveFeetOnTheGround() {
        for s in 2..<10 {
            let l = Growth.layoutFor(Double(s))
            #expect(l.standing)
            #expect(l.legs.count == 4)
            #expect(l.feetY == 94)
            for leg in l.legs {
                #expect(leg.footY == l.feetY, "stade \(s) foot off the ground")
                #expect(leg.hipY == l.bodyCY + l.bodyRY * 0.4, "stade \(s) hip")
            }
            // Two back legs then two front legs, left-then-right in each pair.
            #expect(l.legs.map(\.back) == [true, true, false, false], "stade \(s) order")
            #expect(l.legs.map(\.side) == [-1, 1, -1, 1], "stade \(s) sides")
            // Back legs sit wider than front legs (sb > sf at every stage).
            #expect(abs(l.legs[0].hipX - l.bodyCX) > abs(l.legs[2].hipX - l.bodyCX), "stade \(s) spread")
        }
    }

    /// The knee bend straightens as the creature matures, and the foot is offset
    /// from the hip by `side * bend * 0.6` — sign included, so a mirrored leg
    /// splays outward, never inward.
    @Test("legs straighten with age and splay outward")
    func legsStraightenAndSplayOutward() {
        #expect(Growth.layoutFor(2).legs[0].bend == 6)
        #expect(Growth.layoutFor(9).legs[0].bend == 0)
        for s in 2..<10 {
            let l = Growth.layoutFor(Double(s))
            for leg in l.legs {
                #expect(leg.footX == leg.hipX + leg.side * leg.bend * 0.6)
                // Outward: a left leg's foot is never to the right of its hip.
                if leg.side < 0 { #expect(leg.footX <= leg.hipX) } else { #expect(leg.footX >= leg.hipX) }
            }
        }
    }

    /// The neck extension only starts at stade 7 — it is what makes the last
    /// three stades read as "proud" rather than just bigger.
    @Test("the neck only lengthens from stade 7")
    func neckLengthensOnlyAtTheEnd() {
        func gap(_ s: Double) -> Double {
            let l = Growth.layoutFor(s)
            return l.bodyCY - l.headCY
        }
        // headCY = bodyCY - bodyRY*0.7 - headR*0.58 - neckExtra, and neckExtra
        // is 0 up to stade 6.
        for s in 2...6 {
            let l = Growth.layoutFor(Double(s))
            #expect(l.headCY == l.bodyCY - l.bodyRY * 0.7 - l.headR * 0.58, "stade \(s)")
        }
        #expect(gap(9) > gap(7))
        #expect(gap(7) > gap(6))
    }
}
