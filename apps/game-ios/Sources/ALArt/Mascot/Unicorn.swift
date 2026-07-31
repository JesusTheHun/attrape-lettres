import ALCore
import CoreGraphics
import Foundation
import SwiftUI

// Port of `src/mascot/Unicorn.tsx`.
//
// Unicorn — "Céleste" timeline. Every stade 3→9 adds ONE clearly visible,
// high-contrast beat so a child sees the reward at each level:
//  0-2 (untouched) folded newborn → lifting head → horn nub
//  3 real horn + outlined sky-blue wings open · 4 wings spread wide
//  5 flowing mane crest + first sparkle · 6 glowing halo
//  7 star crown · 8 rainbow mane + shining horn
//  9 majestic alicorn: huge starry wings, halo, crown, rainbow mane, horn beam,
//    ground glow, sparkle burst.
// Wings are OUTLINED (see drawWings) so they never vanish into the pale body.

struct USpec {
    /** horn height in viewBox units; 0 = hornless. */
    var horn: Double
    var shine: Bool
    /** wing scale; 0 = none. */
    var wing: Double
    /** 0..1 magical glow. */
    var aura: Double
    var sparkle: Int
    // NB: `halo`/`crown` are declared before `rainbow` (the TS interface says
    // rainbow first) so Swift's memberwise init accepts the STAGES rows in the
    // same argument order as the TSX object literals.
    /** flowing mane crest length (× headR); nil = base mane only. */
    var maneFlow: Double?
    /** head halo opacity 0..1. */
    var halo: Double?
    /** star crown on the head. */
    var crown: Bool?
    /** multi-colour flowing mane (supersedes maneFlow). */
    var rainbow: Bool?
    /** light beam + sparkle burst from the horn tip. */
    var beam: Bool?
    /** magical pool of light under the hooves. */
    var ground: Bool?
}

private let RAINBOW = ["#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8", "#BA9EE8"]

private let STAGES: [USpec] = [
    USpec(horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0), // 0 newborn, hornless
    USpec(horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0), // 1 lifting head, hornless
    USpec(horn: 3, shine: false, wing: 0, aura: 0, sparkle: 0), // 2 nub
    USpec(horn: 9, shine: false, wing: 0.8, aura: 0, sparkle: 0), // 3 horn + wings open
    USpec(horn: 12, shine: false, wing: 1.1, aura: 0.1, sparkle: 0), // 4 wings spread
    USpec(horn: 15, shine: false, wing: 1.3, aura: 0.25, sparkle: 1, maneFlow: 1.15), // 5 mane crest
    USpec(horn: 17, shine: false, wing: 1.5, aura: 0.4, sparkle: 2, maneFlow: 1.43, halo: 0.5), // 6 halo
    USpec(horn: 19, shine: false, wing: 1.7, aura: 0.6, sparkle: 3, maneFlow: 1.7, halo: 0.5, crown: true), // 7 crown
    USpec(horn: 21, shine: true, wing: 1.9, aura: 0.85, sparkle: 4, halo: 0.9, crown: true, rainbow: true), // 8 rainbow
    USpec(horn: 26, shine: true, wing: 2.2, aura: 1, sparkle: 7, halo: 0.9, crown: true, rainbow: true, beam: true, ground: true), // 9 majestic
]

func drawHorn(
    into c: inout SVGCanvas,
    h: Double,
    x: Double,
    baseY: Double,
    color: String,
    spiral: Bool,
    shine: Bool
) {
    if h <= 0 { return }
    let apexY = baseY - h
    let hw = 2 + h * 0.15
    // The shine is the TSX's `shineId` linearGradient (x1=0 y1=1 x2=1 y2=0) —
    // objectBoundingBox units over the horn path's own bounding box (D24).
    let fill: SVGPaint =
        shine
        ? .linear(
            [
                .svg(color, at: 0),
                .svg("#FFF6C4", at: 0.5),
                .svg(color, at: 1),
            ],
            from: CGPoint(x: 0, y: 1),
            to: CGPoint(x: 1, y: 0)
        )
        : .hex(color)
    let rings = max(1, Int((h / 4).rounded()))
    c.fill(Path(svg: "M\(x) \(apexY) L\(x - hw) \(baseY) Q\(x) \(baseY + hw * 0.6) \(x + hw) \(baseY) Z"), with: fill)
    if spiral {
        for i in 0..<rings {
            let t = (Double(i) + 0.5) / Double(rings)
            let yy = baseY - h * t
            let ww = hw * (1 - t) + 0.4
            c.stroke(
                linePath(x - ww, yy + 1.3, x + ww, yy - 1.3),
                with: .hex(Growth.ink, opacity: 0.45),
                style: StrokeStyle(lineWidth: 0.8, lineCap: .round)
            )
        }
    }
}

/** Celestial wings — saturated sky-blue with a real outline + feather lines, so
 * they stay legible against the pale body (a small pale wing is invisible). */
func drawWings(into c: inout SVGCanvas, s: Double, cx: Double, cy: Double, starry: Bool) {
    if s <= 0 { return }
    func wing(_ dir: Double, into c: inout SVGCanvas) {
        c.save()
        // transform="translate(cx + dir*6, cy) scale(dir*s, s)" — the left wing
        // is mirrored by the NEGATIVE x scale, exactly as in SVG.
        c.translate(cx + dir * 6, cy)
        c.scale(dir * s, s)
        let membrane = Path(svg: "M0 0 Q18 -24 34 -13 Q25 -6 30 4 Q19 -2 23 11 Q13 3 15 15 Q7 6 6 17 Q1 8 0 0 Z")
        c.fill(membrane, with: .hex("#BFE3FF"))
        c.stroke(membrane, with: .hex("#5AA9E0"), style: StrokeStyle(lineWidth: 1.3, lineJoin: .round))
        c.stroke(Path(svg: "M4 2 Q15 -14 28 -9"), with: .hex("#5AA9E0", opacity: 0.7), style: StrokeStyle(lineWidth: 0.9))
        c.stroke(Path(svg: "M3 7 Q13 -1 24 2"), with: .hex("#5AA9E0", opacity: 0.55), style: StrokeStyle(lineWidth: 0.8))
        if starry {
            for (x, y) in [(12.0, -7.0), (18, -1), (22, 6), (14, 9)] {
                c.fill(Path(svg: fourStar(x, y, 1.5)), with: .hex("#fff"))
            }
        }
        c.restore()
    }
    wing(-1, into: &c)
    wing(1, into: &c)
}

/** Flowing tail attached to the rump. Straight = smooth swish, curly = coiled. */
func drawUnicornTail(into c: inout SVGCanvas, x: Double, y: Double, len: Double, color: String, curly: Bool) {
    // wedge blending into the rump so the tail reads attached
    c.fill(
        Path(svg: "M\(x + 8) \(y - 7) Q\(x - 3) \(y + 2) \(x - 2) \(y + 11) L\(x + 9) \(y + 5) Z"),
        with: .hex(color)
    )
    drawPlume(into: &c, x: x, y: y, color: color, len: len, wide: 9, rot: -24, n: 3, wave: curly)
    if curly {
        c.stroke(
            Path(svg: "M\(x - len * 0.28) \(y + len * 0.62) q -8 3 -6 10 q 2 6 -4 8 q -6 2 -2 8"),
            with: .hex(color),
            style: StrokeStyle(lineWidth: 4.2, lineCap: .round)
        )
    } else {
        c.stroke(
            Path(svg: "M\(x - 2) \(y + 3) q -5 \(len * 0.4) -8 \(len * 0.7)"),
            with: .hex("#ffffff", opacity: 0.45),
            style: StrokeStyle(lineWidth: 1, lineCap: .round)
        )
    }
}

public func drawUnicornRig(
    into c: inout SVGCanvas,
    config: MascotConfig,
    // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
    layout: ALCore.Layout,
    stage: Int,
    mood: Mood,
    preview: Bool = false
) {
    typealias C = ColorSlot.Unicorn
    typealias S = StyleSlot.Unicorn
    typealias A = Accessory.Unicorn
    let body = Growth.pick(config.colors, C.body, "#F5ECFF")
    let hornCol = Growth.pick(config.colors, C.horn, "#FFD54F")
    let maneCol = Growth.pick(config.colors, C.mane, "#BA9EE8")
    let tailCol = Growth.pick(config.colors, C.tail, "#F49AC2")
    let curlyTail = Growth.pick(config.styles, S.tail, "straight") == "curly"
    let spiralHorn = Growth.pick(config.styles, S.horn, "smooth") == "spiral"
    func has(_ id: String) -> Bool { config.accessories.contains(id) }
    let earIn = "#FBE4F1"

    var spec = STAGES[max(0, min(9, stage))]
    // Shop thumbnail: keep the horn (a sold part) but drop every magical extra so a
    // ghost unicorn shows ONLY the colour/style/accessory this tile is about.
    if preview {
        spec.shine = false
        spec.wing = 0
        spec.aura = 0
        spec.sparkle = 0
        spec.maneFlow = nil
        spec.rainbow = false
        spec.halo = nil
        spec.crown = false
        spec.beam = false
        spec.ground = false
    }
    let hoof = "#EADAC6"
    let bodyCX = layout.bodyCX
    let bodyCY = layout.bodyCY
    let bodyRX = layout.bodyRX
    let bodyRY = layout.bodyRY
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let eyeR = layout.eyeR
    let anchor = accessoryAnchors(.unicorn, layout)
    let hornBaseY = headCY - headR * 0.72
    let apexY = hornBaseY - spec.horn
    let legW = layout.pose == .proud ? 6.5 : 7.0

    if spec.ground == true {
        drawGroundGlow(into: &c, cx: 50, y: layout.feetY + 2, rx: bodyRX + 20, color: "#FFE29A", opacity: 0.9)
    }
    if spec.aura > 0 {
        drawAura(into: &c, cx: bodyCX, cy: bodyCY - 2, r: bodyRX + 24, color: "#FFE29A", opacity: spec.aura)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if !layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.15, rx: bodyRX * 1.15, color: "#FF8FB1")
    }

    drawWings(into: &c, s: spec.wing, cx: bodyCX, cy: bodyCY - bodyRY * 0.28, starry: spec.wing >= 2)

    // tail attached at rump, behind body
    drawUnicornTail(into: &c, x: bodyCX - bodyRX * 0.5, y: bodyCY - bodyRY * 0.12, len: bodyRY * 1.6, color: tailCol, curly: curlyTail)

    for l in layout.legs where l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: hoof)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FF8FB1", part: .back)
    }

    c.fill(ellipsePath(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
    if !layout.standing {
        drawFoldedLegs(into: &c, bodyCX: bodyCX, bodyCY: bodyCY, bodyRX: bodyRX, color: body, hoof: hoof)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if !layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#7FD1D8", lying: true)
    }
    c.stroke(
        Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
        with: .hex(body),
        style: StrokeStyle(lineWidth: headR * 0.9, lineCap: .round)
    )

    for l in layout.legs where !l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: hoof)
    }

    // halo (glowing ring behind the head)
    if let halo = spec.halo {
        drawHalo(into: &c, cx: headCX, cy: headCY, r: headR * 1.7, opacity: halo)
    }

    // mane behind head + top crest
    drawPlume(into: &c, x: headCX + headR * 0.22, y: headCY - headR * 0.05, color: maneCol, len: headR * 1.2, wide: headR * 0.65, rot: 22, n: 4)
    if spec.horn > 0 {
        drawPlume(into: &c, x: headCX - headR * 0.15, y: headCY - headR * 0.85, color: maneCol, len: headR * 0.55, wide: headR * 0.35, rot: -6, n: 2)
    }
    // flowing mane crest → rainbow at the top stages
    if spec.rainbow == true {
        for (i, col) in RAINBOW.enumerated() {
            drawPlume(
                into: &c,
                x: headCX + headR * 0.24,
                y: headCY - headR * 0.08 + (Double(i) / 4) * headR,
                color: col,
                len: headR * 1.5,
                wide: headR * 0.5,
                rot: 26 + Double(i) * 5,
                n: 3
            )
        }
    } else if let maneFlow = spec.maneFlow {
        drawPlume(into: &c, x: headCX + headR * 0.24, y: headCY - headR * 0.08, color: maneCol, len: headR * maneFlow, wide: headR * 0.62, rot: 24, n: 4)
    }

    // head
    c.fill(ellipsePath(headCX, headCY, headR, headR * 0.98), with: .hex(body))

    // rounded ears (never horn-like)
    for d in [-1.0, 1.0] {
        let ex = headCX + d * headR * 0.6
        let ey = headCY - headR * 0.58
        c.save()
        c.rotate(degrees: d * 22, about: CGPoint(x: ex, y: ey))
        c.fill(ellipsePath(ex, ey, headR * 0.16, headR * 0.3), with: .hex(body))
        c.fill(ellipsePath(ex, ey + headR * 0.04, headR * 0.08, headR * 0.18), with: .hex(earIn))
        c.restore()
    }

    // star crown — UNDER the horn (drawn next) so the horn always pokes
    // through the open band; the flower crown accessory replaces it.
    if spec.crown == true && !has(A.flowerCrown) {
        drawCrown(into: &c, cx: headCX, cy: headCY - headR * 0.5, r: headR * 0.95, band: hornCol, gem: "#FF7EA8", open: true)
    }

    // forelock — two symmetric locks framing the face, clear of the eyes
    drawPlume(into: &c, x: headCX - headR * 0.24, y: headCY - headR * 0.6, color: maneCol, len: headR * 0.42, wide: 4, rot: -20, n: 2)
    drawPlume(into: &c, x: headCX + headR * 0.24, y: headCY - headR * 0.6, color: maneCol, len: headR * 0.42, wide: 4, rot: 20, n: 2)

    // horn (on top, centred between the forelock tufts)
    drawHorn(into: &c, h: spec.horn, x: headCX, baseY: hornBaseY, color: hornCol, spiral: spiralHorn, shine: spec.shine)
    if spec.shine {
        drawSparkles(into: &c, points: [(headCX, apexY - 1.5, 3)])
    }
    // horn light beam (majestic)
    if spec.beam == true {
        c.fill(
            Path(svg: "M\(headCX - 5) \(apexY) L\(headCX) \(apexY - 26) L\(headCX + 5) \(apexY) Z"),
            with: .hex("#FFF6C4", opacity: 0.5)
        )
        c.fill(Path(svg: fourStar(headCX, apexY - 2, 4.2)), with: .hex("#FFF3C4"))
    }

    // face
    drawEyes(into: &c, cx: headCX, y: headCY + headR * 0.06, dx: headR * 0.42, r: eyeR, mood: mood, sleepy: stage == 0)
    drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.36, dx: headR * 0.62, r: headR * 0.15)
    drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.5, w: headR * 0.16, mood: mood)

    // hoof aura (majestic)
    if spec.aura > 0.4 {
        for l in layout.legs {
            drawAura(into: &c, cx: l.footX, cy: l.footY, r: 8, color: "#FFF0B8", opacity: spec.aura)
        }
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#7FD1D8", star: stage >= 6)
    }
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FF8FB1", duck: stage >= 7, part: .front)
    }
    // ribbon: baby (lying, no neck yet) wears it as a hair bow ON the head;
    // once standing it drops to the throat.
    if has(A.ribbon) {
        if layout.standing {
            drawBow(into: &c, x: anchor.neck.x, y: anchor.neck.y, s: anchor.head.r * 0.052, color: "#FF7EA8")
        } else {
            drawBow(into: &c, x: headCX + headR * 0.34, y: headCY - headR * 0.5, s: headR * 0.05, color: "#FF7EA8")
        }
    }
    // crest left OPEN at the top so the horn (or its stade-2 nub) always pokes
    // through — a crown that swallows the just-earned horn is a heartbreak
    if has(A.flowerCrown) {
        let palette = ["#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8"]
        for (i, deg) in [-72.0, -38.0, 38.0, 72.0].enumerated() {
            let rad = (deg - 90) * .pi / 180
            let fx = anchor.head.x + cos(rad) * anchor.head.r * 0.98
            let fy = anchor.head.y + sin(rad) * anchor.head.r * 0.98
            drawFlower(into: &c, x: fx, y: fy, r: 3.8, petal: palette[i], center: "#FFF3C4")
        }
    }
    // "Arc-en-ciel magique" (A.starClip) is a WHOLE-IMAGE overlay drawn by
    // the rig shell (Rig.swift) on top of every rig — wings included — so it
    // isn't handled here.

    // sparkles (proud/majestic)
    if spec.sparkle > 0 {
        drawSparkles(
            into: &c,
            points: (0..<spec.sparkle).map { i in
                let a = Double(i) / Double(spec.sparkle) * .pi * 2
                return (bodyCX + cos(a) * (bodyRX + 15), bodyCY - 4 + sin(a) * (bodyRY + 12), 1.8 + Double(i % 3))
            }
        )
    }
}
