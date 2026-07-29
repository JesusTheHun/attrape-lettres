import CoreGraphics
import SwiftUI
import Testing

import ALCore
@testable import ALArt

// The mood animation and the sheen sweep, asserted as DATA. A running
// `keyframeAnimator` cannot be sampled on the host, but everything that can be
// got wrong in the port can: the keyframe shape, the timings, the two unit
// systems, and the three gates (preview / reduced motion / mood).
//
// Every expectation below is read off `src/mascot/Mascot.tsx` and
// `src/index.css`, never off the Swift.

@Suite("Mascot motion — the three plans")
struct MascotMotionPlanTests {

    @Test("idle bobs, happy pops, cheer cheers")
    func moodDispatch() {
        // mood === "idle" ? bob : mood === "cheer" ? cheer : pop
        #expect(MascotMotion.plan(mood: .idle, preview: false, reduceMotion: false) == MascotMotion.bob)
        #expect(MascotMotion.plan(mood: .cheer, preview: false, reduceMotion: false) == MascotMotion.cheer)
        #expect(MascotMotion.plan(mood: .happy, preview: false, reduceMotion: false) == MascotMotion.pop)
    }

    @Test("preview never animates — a grid of jittering mascots is noise")
    func previewIsStill() {
        for mood in Mood.allCases {
            #expect(MascotMotion.plan(mood: mood, preview: true, reduceMotion: false) == nil)
        }
    }

    @Test("reduced motion starts NOTHING — not a shorter bob, nothing")
    func reducedMotionIsStill() {
        for mood in Mood.allCases {
            #expect(MascotMotion.plan(mood: mood, preview: false, reduceMotion: true) == nil)
            #expect(MascotMotion.plan(mood: mood, preview: true, reduceMotion: true) == nil)
        }
    }

    @Test("the bob's keyframe shape: rotate DOWN at rest, UP and rotated at the midpoint")
    func bobShape() {
        let bob = MascotMotion.bob
        #expect(bob.duration == 2.6)
        #expect(bob.repeats)
        #expect(bob.easing == .easeInOut)
        #expect(bob.keyframes.count == 3)

        #expect(bob.keyframes[0].offset == 0)
        #expect(bob.keyframes[0].transform == MascotTransform(y: 0, rotation: -1.5, scale: 1))
        #expect(bob.keyframes[1].offset == 0.5)
        #expect(bob.keyframes[1].transform == MascotTransform(y: -5, rotation: 1.5, scale: 1))
        // The loop is seamless only because the last frame equals the first.
        #expect(bob.keyframes[2].offset == 1)
        #expect(bob.keyframes[2].transform == bob.keyframes[0].transform)
    }

    @Test("the pop drops its rotation on the way back — scale(0.98) has no rotate()")
    func popShape() {
        let pop = MascotMotion.pop
        #expect(pop.duration == 0.48)
        #expect(!pop.repeats)
        #expect(pop.easing == .easeOut)
        #expect(pop.keyframes.map(\.offset) == [0, 0.4, 0.72, 1])
        #expect(pop.keyframes[1].transform == MascotTransform(y: 0, rotation: 4, scale: 1.16))
        // `{ transform: "scale(0.98)" }` — no rotate, so CSS reads 0°.
        #expect(pop.keyframes[2].transform.rotation == 0)
        #expect(pop.keyframes[2].transform.scale == 0.98)
        #expect(pop.keyframes[3].transform == MascotTransform.identity)
    }

    @Test("the cheer's biggest scale is the THIRD peak, not the first")
    func cheerShape() {
        let cheer = MascotMotion.cheer
        #expect(cheer.duration == 0.68)
        #expect(!cheer.repeats)
        #expect(cheer.easing == .easeOut)
        #expect(cheer.keyframes.map(\.offset) == [0, 0.25, 0.5, 0.74, 1])
        #expect(cheer.keyframes.map(\.transform.scale) == [1, 1.2, 1.1, 1.22, 1])
        #expect(cheer.keyframes.map(\.transform.rotation) == [0, -6, 6, -4, 0])
        // Nothing in the cheer translates — only the bob does.
        #expect(cheer.keyframes.allSatisfy { $0.transform.y == 0 })
    }

    @Test("no plan translates in y except the bob, and its −5 is SCREEN points")
    func onlyTheBobTranslates() {
        // Trap 1: this value must NOT be scaled by size/100. It is on the
        // <svg> element, so a 220 pt mascot bobs the same 5 pt as an 88 pt one.
        #expect(MascotMotion.bob.keyframes[1].transform.y == -5)
        #expect(MascotMotion.pop.keyframes.allSatisfy { $0.transform.y == 0 })
        #expect(MascotMotion.cheer.keyframes.allSatisfy { $0.transform.y == 0 })
    }

    @Test("transform-origin is 50% 82%")
    func origin() {
        #expect(MascotMotion.origin == UnitPoint(x: 0.5, y: 0.82))
    }
}

@Suite("Mascot motion — segments and the restart-from-identity trap")
struct MascotMotionSegmentTests {

    @Test("every plan opens with a zero-duration frame, so a restart cannot inherit a mid-flight transform")
    func firstSegmentIsInstant() {
        for plan in [MascotMotion.bob, MascotMotion.pop, MascotMotion.cheer] {
            let segments = plan.segments
            #expect(segments.first?.duration == 0)
            #expect(segments.first?.transform == plan.keyframes.first?.transform)
            #expect(segments.count == plan.keyframes.count)
        }
    }

    @Test("segment durations are the offset gaps, and they sum to the whole")
    func segmentDurations() {
        // bob: offsets 0, 0.5, 1 over 2600 ms → 0, 1300, 1300.
        let bob = MascotMotion.bob.segments.map(\.duration)
        #expect(bob.count == 3)
        #expect(abs(bob[1] - 1.3) < 1e-12)
        #expect(abs(bob[2] - 1.3) < 1e-12)
        #expect(abs(bob.reduce(0, +) - 2.6) < 1e-12)

        // cheer: 0.25, 0.25, 0.24, 0.26 of 680 ms.
        let cheer = MascotMotion.cheer.segments.map(\.duration)
        #expect(abs(cheer[1] - 0.17) < 1e-12)
        #expect(abs(cheer.reduce(0, +) - 0.68) < 1e-12)
    }

    @Test("identity is the un-animated rig, and the bob's first frame is NOT identity")
    func identity() {
        #expect(MascotTransform.identity == MascotTransform(y: 0, rotation: 0, scale: 1))
        // If it were, the leading zero-duration frame would be redundant; it is
        // not, and dropping it would start every bob square instead of tilted.
        #expect(MascotMotion.bob.keyframes[0].transform != MascotTransform.identity)
    }
}

@Suite("Mascot motion — the rainbow sheen")
struct MascotSheenTests {

    @Test("the sweep runs −150 → +150 viewBox units over 3.6 s, and wraps")
    func sweep() {
        #expect(MascotSheen.fromUnits == -150)
        #expect(MascotSheen.toUnits == 150)
        #expect(MascotSheen.duration == 3.6)

        #expect(MascotSheen.offsetUnits(at: 0, reduceMotion: false) == -150)
        #expect(abs(MascotSheen.offsetUnits(at: 1.8, reduceMotion: false) - 0) < 1e-12)
        // Linear, infinite, no autoreverse: t = duration is t = 0 again.
        #expect(abs(MascotSheen.offsetUnits(at: 3.6, reduceMotion: false) - (-150)) < 1e-12)
        #expect(abs(MascotSheen.offsetUnits(at: 5.4, reduceMotion: false) - 0) < 1e-12)
    }

    @Test("under reduced motion the band FREEZES CENTRED — it is not hidden and not parked off the pet")
    func frozenBandStaysVisible() {
        // Invariant 6, explicitly: `animation: none !important` leaves the rect
        // at its base transform, which is translateX(0) — a static rainbow band
        // across the middle of the creature. Parking it at `fromUnits` would
        // put it 150 units off the pet, where the silhouette mask hides it, and
        // the accessory a family paid 200 stars for would vanish.
        #expect(MascotSheen.parkedUnits == 0)
        #expect(MascotSheen.parkedUnits != MascotSheen.fromUnits)
        for t in [0.0, 1.0, 2.5, 9.9] {
            #expect(MascotSheen.offsetUnits(at: t, reduceMotion: true) == 0)
        }
    }
}

#if canImport(AppKit) || canImport(UIKit)

/// Rasterise a view and read back sRGB pixels.
@MainActor
private func raster(_ size: CGSize, @ViewBuilder _ content: () -> some View) -> (px: [UInt8], w: Int, h: Int)? {
    let renderer = ImageRenderer(content: content().frame(width: size.width, height: size.height))
    renderer.scale = 1
    guard let cg = renderer.cgImage else { return nil }
    let w = cg.width, h = cg.height
    var buffer = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(
        data: &buffer, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    return (buffer, w, h)
}

private func alpha(_ r: (px: [UInt8], w: Int, h: Int), _ x: Int, _ y: Int) -> Int {
    Int(r.px[(y * r.w + x) * 4 + 3])
}

@Suite("Mascot motion — the transform actually applied", .serialized)
@MainActor
struct MascotTransformApplicationTests {

    /// A 4×4 marker at `(x, y)` in a 100×100 frame.
    private struct Marker: View {
        let x: CGFloat
        let y: CGFloat
        var body: some View {
            Canvas { context, _ in
                context.fill(
                    Path(CGRect(x: x - 2, y: y - 2, width: 4, height: 4)),
                    with: .color(.red)
                )
            }
        }
    }

    @Test("translateY moves by exactly the value, in screen points")
    func translateIsScreenPoints() throws {
        let still = try #require(raster(CGSize(width: 100, height: 100)) {
            Marker(x: 50, y: 50)
        })
        let bobbed = try #require(raster(CGSize(width: 100, height: 100)) {
            Marker(x: 50, y: 50).mascotTransform(MascotTransform(y: -5))
        })
        #expect(alpha(still, 50, 50) > 200, "the reference marker should be at y=50")
        #expect(alpha(bobbed, 50, 45) > 200, "translateY(-5) should put it at y=45")
        #expect(alpha(bobbed, 50, 50) < 40, "…and nothing should be left at y=50")
    }

    @Test("rotation pivots about 50% 82%, not about the centre")
    func rotationAnchor() throws {
        // A marker sitting exactly on the transform origin cannot move; one at
        // the geometric centre must, because the origin is not the centre.
        let onOrigin = try #require(raster(CGSize(width: 100, height: 100)) {
            Marker(x: 50, y: 82).mascotTransform(MascotTransform(rotation: 90))
        })
        #expect(alpha(onOrigin, 50, 82) > 200, "the point on the origin must stay put")

        let atCentre = try #require(raster(CGSize(width: 100, height: 100)) {
            Marker(x: 50, y: 50).mascotTransform(MascotTransform(rotation: 90))
        })
        #expect(alpha(atCentre, 50, 50) < 40, "a centre marker must NOT survive a 90° turn about (50, 82)")
        // (50,50) is (0,−32) from the origin; a 90° clockwise turn in y-down
        // space sends (0,−32) to (32,0), i.e. the marker lands at (82, 82).
        #expect(alpha(atCentre, 82, 82) > 200, "expected the marker at (82, 82)")
    }

    @Test("scale grows about the same origin, so the feet stay planted")
    func scaleAnchor() throws {
        let scaled = try #require(raster(CGSize(width: 100, height: 100)) {
            Marker(x: 50, y: 82).mascotTransform(MascotTransform(scale: 1.22))
        })
        #expect(alpha(scaled, 50, 82) > 200, "a marker on the origin must not move when scaled")
    }
}

#endif
