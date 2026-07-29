import ALCore
import CoreGraphics
import Foundation
import SwiftUI

// Port of `src/mascot/Rabbit.tsx`.
//
// Rabbit — « Lune » timeline (tsuki no usagi). Each stade 3→9 adds a clearly
// visible beat on the way to the legendary moon rabbit:
//  0-2 (untouched) curled kit, ears laid back → head up → first hops
//  3 downy chest + inner ears bloom lavender · 4 moonlit gold ear tips
//  5 grand moon pompon + gold star · 6 gold crescent forehead mark + sparkles
//  7 star-tipped ears · 8 a floating crescent moon glows beside the head
//  9 full moon: lavender aura, moonlight pooling on the ground, ring of stars.
// Blind-test lessons baked in: nightcap DROOPS below its brim (a symmetric cone
// between two upright ears reads "crown"), the premium star-dust stays OFF the
// zone right above the head (same crown bug), the star-tail draws OVER the hip
// (half-hidden it read "held object"), the swimsuit has shoulder straps.

struct RSpec {
    var earH: Double
    var pompon: Double
    var chest = false
    var bloom = false
    var dip = false
    var pomStar = false
    var mark = false
    var sparkle: Int?
    var starTips = false
    var halo: Double?
    var aura: Double?
    var ground = false
    var burst = false
}

private let STAGES: [RSpec] = [
    RSpec(earH: 0.78, pompon: 0.75), // 0
    RSpec(earH: 0.88, pompon: 0.8), // 1
    RSpec(earH: 0.98, pompon: 0.85), // 2
    RSpec(earH: 1.06, pompon: 0.9, chest: true, bloom: true), // 3 chest down + lavender ears
    RSpec(earH: 1.14, pompon: 0.95, chest: true, bloom: true, dip: true), // 4 gold tips
    RSpec(earH: 1.22, pompon: 1.42, chest: true, bloom: true, dip: true, pomStar: true), // 5 moon pompon
    RSpec(earH: 1.3, pompon: 1.46, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 2), // 6 crescent mark
    RSpec(earH: 1.4, pompon: 1.5, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 3, starTips: true), // 7 star tips
    RSpec(earH: 1.5, pompon: 1.54, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 4, starTips: true, halo: 0.6), // 8 crescent halo
    RSpec(earH: 1.66, pompon: 1.62, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 6, starTips: true, halo: 0.7, aura: 1, ground: true, burst: true), // 9 full moon
]

private let GOLD = "#FFD54F"
private let MOONLIGHT = "#EFE7FF"
private let STAR_SOFT = "#FFE082"
private let FLECK = "#B8A6E0"
private let CAP = "#8E9AD6"

/* -- Rabbit-specific parts ---------------------------------------------- */

/** One rabbit ear grown from a base point. `rot` is degrees from vertical:
 * 0 = straight up, ±12 = the classic upright tilt, negative sweeps back for
 * the lying baby. */
func drawEar(
    into c: inout SVGCanvas,
    bx: Double,
    by: Double,
    len: Double,
    wid: Double,
    rot: Double,
    outer: String,
    inner: String? = nil,
    dip: String? = nil,
    star: String? = nil
) {
    let rad = rot * .pi / 180
    let ux = sin(rad)
    let uy = -cos(rad)
    func at(_ t: Double) -> (Double, Double) { (bx + ux * len * t, by + uy * len * t) }
    let (cx, cy) = at(0.5)
    let (icx, icy) = at(0.58)
    let (dcx, dcy) = at(0.86)
    let (sx, sy) = at(1.14)
    c.save()
    c.rotate(degrees: rot, about: CGPoint(x: cx, y: cy))
    c.fill(ellipsePath(cx, cy, wid * 0.5, len * 0.52), with: .hex(outer))
    c.restore()
    if let inner {
        c.save()
        c.rotate(degrees: rot, about: CGPoint(x: icx, y: icy))
        c.fill(ellipsePath(icx, icy, wid * 0.27, len * 0.33), with: .hex(inner))
        c.restore()
    }
    if let dip {
        c.save()
        c.rotate(degrees: rot, about: CGPoint(x: dcx, y: dcy))
        c.fill(ellipsePath(dcx, dcy, wid * 0.36, len * 0.16), with: .hex(dip))
        c.restore()
    }
    if let star {
        c.fill(Path(svg: fourStar(sx, sy, 2.3)), with: .hex(star))
    }
}

/** An ear whose top folds over — the bought "Oreilles pliées" look. */
func drawKinkEar(
    into c: inout SVGCanvas,
    bx: Double,
    by: Double,
    len: Double,
    wid: Double,
    rot: Double,
    kink: Double,
    outer: String,
    inner: String? = nil,
    dip: String? = nil
) {
    let rad = rot * .pi / 180
    let ux = sin(rad)
    let uy = -cos(rad)
    let ex = bx + ux * len * 0.6
    let ey = by + uy * len * 0.6
    let rot2 = rot + kink
    let rad2 = rot2 * .pi / 180
    let ux2 = sin(rad2)
    let uy2 = -cos(rad2)
    let tl = len * 0.55
    let c1x = bx + ux * len * 0.34
    let c1y = by + uy * len * 0.34
    let c2x = ex + ux2 * tl * 0.45
    let c2y = ey + uy2 * tl * 0.45
    let icx = bx + ux * len * 0.38
    let icy = by + uy * len * 0.38
    let dcx = ex + ux2 * tl * 0.78
    let dcy = ey + uy2 * tl * 0.78
    c.save()
    c.rotate(degrees: rot, about: CGPoint(x: c1x, y: c1y))
    c.fill(ellipsePath(c1x, c1y, wid * 0.5, len * 0.42), with: .hex(outer))
    c.restore()
    if let inner {
        c.save()
        c.rotate(degrees: rot, about: CGPoint(x: icx, y: icy))
        c.fill(ellipsePath(icx, icy, wid * 0.26, len * 0.26), with: .hex(inner))
        c.restore()
    }
    c.save()
    c.rotate(degrees: rot2, about: CGPoint(x: c2x, y: c2y))
    c.fill(ellipsePath(c2x, c2y, wid * 0.44, tl * 0.55), with: .hex(outer))
    c.restore()
    if let dip {
        c.save()
        c.rotate(degrees: rot2, about: CGPoint(x: dcx, y: dcy))
        c.fill(ellipsePath(dcx, dcy, wid * 0.34, tl * 0.24), with: .hex(dip))
        c.restore()
    }
}

/** Round fluffy pompon tail — circles with an outline underlay so it separates
 * from a same-tone body. */
func drawPompon(into c: inout SVGCanvas, cx: Double, cy: Double, s: Double, color: String, edge: String) {
    let puffs: [(Double, Double, Double)] = [
        (0, 0, 4.4),
        (-3.2, -1.5, 3.1),
        (3, -1.7, 2.9),
        (-2.4, 2.3, 2.9),
        (2.7, 2.1, 2.8),
        (0, -3.2, 3),
    ]
    for (dx, dy, r) in puffs {
        c.fill(circlePath(cx + dx * s, cy + dy * s, (r + 0.9) * s), with: .hex(edge))
    }
    for (dx, dy, r) in puffs {
        c.fill(circlePath(cx + dx * s, cy + dy * s, r * s), with: .hex(color))
    }
}

/** Tiny rounded-triangle rabbit nose. */
func drawBunnyNose(into c: inout SVGCanvas, cx: Double, y: Double, s: Double) {
    c.fill(
        Path(svg: "M\(cx - 1.9 * s) \(y) Q\(cx) \(y - 1.7 * s) \(cx + 1.9 * s) \(y) Q\(cx) \(y + 2.4 * s) \(cx - 1.9 * s) \(y) Z"),
        with: .hex("#F0A0AE")
    )
}

/** Three whisker-freckle dots per cheek — the rabbit face signature. */
func drawWhiskerDots(into c: inout SVGCanvas, cx: Double, y: Double, dx: Double, s: Double) {
    c.group(opacity: 0.3) { g in
        for d in [-1.0, 1.0] {
            for i in 0..<3 {
                g.fill(
                    circlePath(
                        cx + d * (dx + Double(i % 2) * 1.6 * s),
                        y + (Double(i) - 1) * 1.7 * s,
                        0.58 * s
                    ),
                    with: .hex(Growth.ink)
                )
            }
        }
    }
}

/** Crescent moon, tips up/down, opening to the left. */
func drawCrescent(into c: inout SVGCanvas, cx: Double, cy: Double, r: Double, fill: String, rot: Double = 0) {
    let d = "M\(cx) \(cy - r) A\(r) \(r) 0 1 1 \(cx) \(cy + r) A\(r * 1.15) \(r * 1.15) 0 0 0 \(cx) \(cy - r) Z"
    if rot != 0 {
        c.save()
        c.rotate(degrees: rot, about: CGPoint(x: cx, y: cy))
        c.fill(Path(svg: d), with: .hex(fill))
        c.restore()
    } else {
        c.fill(Path(svg: d), with: .hex(fill))
    }
}

/** A shooting star: gold four-star head + a trail of shrinking stars (NOT a
 * straight stick — that read as a wand). `rot` = direction the trail streams. */
func drawComet(into c: inout SVGCanvas, x: Double, y: Double, s: Double, rot: Double) {
    let rad = rot * .pi / 180
    let ux = cos(rad)
    let uy = sin(rad)
    let px = -uy
    let py = ux
    c.fill(Path(svg: fourStar(x + (ux * 5.5 + px * 1.3) * s, y + (uy * 5.5 + py * 1.3) * s, 1.7 * s)), with: .hex("#FFE082", opacity: 0.9))
    c.fill(Path(svg: fourStar(x + (ux * 9.5 - px * 1.5) * s, y + (uy * 9.5 - py * 1.5) * s, 1.2 * s)), with: .hex("#FFE8A3", opacity: 0.8))
    c.fill(circlePath(x + (ux * 12.5 + px * 0.9) * s, y + (uy * 12.5 + py * 0.9) * s, 0.65 * s), with: .hex("#FFE8A3", opacity: 0.6))
    c.fill(Path(svg: fourStar(x, y, 2.9 * s)), with: .hex("#FFD54F"))
    c.fill(circlePath(x - s * 0.5, y - s * 0.5, 0.65 * s), with: .hex("#FFF7DC"))
}

/** Sleeping cap seated on the dome: the cone folds over and DROOPS below its
 * own brim on the right, pompom hanging at the tip — the droop is what stops
 * it reading as a crown between two upright ears. */
func drawNightcap(into c: inout SVGCanvas, hx: Double, hy: Double, headR: Double) {
    let bl: (Double, Double) = (hx - headR * 0.72, hy - headR * 0.52)
    let br: (Double, Double) = (hx + headR * 0.72, hy - headR * 0.52)
    let tipX = hx + headR * 1.28
    let tipY = hy - headR * 0.28
    c.fill(
        Path(svg: "M\(bl.0) \(bl.1) Q\(hx - headR * 0.25) \(hy - headR * 1.8) \(hx + headR * 0.25) \(hy - headR * 1.5) Q\(hx + headR * 0.78) \(hy - headR * 1.34) \(hx + headR * 0.98) \(hy - headR * 0.92) Q\(hx + headR * 1.1) \(hy - headR * 0.55) \(tipX) \(tipY) Q\(hx + headR * 0.92) \(hy - headR * 0.4) \(br.0) \(br.1) Z"),
        with: .hex(CAP)
    )
    c.stroke(
        Path(svg: "M\(hx + headR * 0.28) \(hy - headR * 1.44) Q\(hx + headR * 0.74) \(hy - headR * 1.12) \(hx + headR * 0.92) \(hy - headR * 0.74)"),
        with: .hex("#7A85C2"),
        style: StrokeStyle(lineWidth: headR * 0.06, lineCap: .round)
    )
    c.fill(Path(svg: fourStar(hx - headR * 0.2, hy - headR * 1.05, headR * 0.09)), with: .hex("#FFF6DE", opacity: 0.95))
    c.fill(Path(svg: fourStar(hx + headR * 0.3, hy - headR * 0.95, headR * 0.07)), with: .hex("#FFF6DE", opacity: 0.85))
    c.stroke(
        Path(svg: "M\(bl.0) \(bl.1) Q\(hx) \(hy - headR * 1.18) \(br.0) \(br.1)"),
        with: .hex("#FFF3E0"),
        style: StrokeStyle(lineWidth: headR * 0.22, lineCap: .round)
    )
    c.fill(circlePath(tipX + headR * 0.06, tipY + headR * 0.1, headR * 0.19), with: .hex("#FFF3E0"))
    c.stroke(
        circlePath(tipX + headR * 0.06, tipY + headR * 0.1, headR * 0.19),
        with: .hex("#E8D9C0", opacity: 0.7),
        style: StrokeStyle(lineWidth: 0.7)
    )
}

/* -- The rig ------------------------------------------------------------- */

public func drawRabbitRig(
    into c: inout SVGCanvas,
    config: MascotConfig,
    // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
    layout: ALCore.Layout,
    stage: Int,
    mood: Mood,
    preview: Bool = false
) {
    typealias C = ColorSlot.Rabbit
    typealias S = StyleSlot.Rabbit
    typealias A = Accessory.Rabbit
    let body = Growth.pick(config.colors, C.body, "#F6EFE3")
    let belly = Growth.pick(config.colors, C.belly, "#FFFFFF")
    let innerBase = Growth.pick(config.colors, C.inner, "#D9CCEE")
    let foldEars = Growth.pick(config.styles, S.ear, "hautes") == "pliees"
    let starTail = Growth.pick(config.styles, S.tail, "pompon") == "etoile"
    let flecks = Growth.pick(config.styles, S.fur, "uni") == "flocons"
    func has(_ id: String) -> Bool { config.accessories.contains(id) }
    let stardust = has(A.stardust) && stage >= 4

    var spec = STAGES[max(0, min(9, stage))]
    // Shop thumbnail: strip the free per-stage magic; keep ears + pompon (sold
    // parts) and the inner-ear bloom so colour tiles show their true tint.
    if preview { spec = RSpec(earH: spec.earH, pompon: spec.pompon, bloom: spec.bloom) }

    let bodyCX = layout.bodyCX
    let bodyCY = layout.bodyCY
    let bodyRX = layout.bodyRX
    let bodyRY = layout.bodyRY
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let eyeR = layout.eyeR
    let feetY = layout.feetY
    let anchor = accessoryAnchors(.rabbit, layout)
    let inner = Growth.mix(body, innerBase, spec.bloom ? 1 : 0.2)
    let tailEdge = Growth.mix(body, Growth.ink, 0.18)
    let pom = spec.pompon
    let legW = 7.0
    let earW = headR * 0.4
    let earL = headR * spec.earH
    let hoofC = Growth.mix(body, Growth.ink, 0.3)

    // Upright tilted ears when standing; swept back over the shoulder when lying.
    let ears: [(bx: Double, by: Double, rot: Double, k: Double)] =
        layout.standing
        ? [
            (headCX - headR * 0.42, headCY - headR * 0.62, -12, 1),
            (headCX + headR * 0.42, headCY - headR * 0.62, 12, 1),
        ]
        : [
            (headCX - headR * 0.55, headCY - headR * 0.48, -66, 0.95),
            (headCX - headR * 0.2, headCY - headR * 0.6, -30, 1),
        ]

    let pomX = layout.standing ? bodyCX - bodyRX * 0.98 : bodyCX - bodyRX * 0.9
    let pomY = layout.standing ? bodyCY + bodyRY * 0.5 : bodyCY - bodyRY * 0.35

    if spec.ground {
        drawGroundGlow(into: &c, cx: 50, y: feetY + 2, rx: bodyRX + 18, color: "#D9CCF2", opacity: 0.85)
    }
    if (spec.aura ?? 0) > 0 {
        drawAura(into: &c, cx: bodyCX, cy: bodyCY - 4, r: bodyRX + 24, color: MOONLIGHT, opacity: spec.aura ?? 0)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if !layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.15, rx: bodyRX * 1.15, color: "#FFD54F")
    }

    // fluffy pompon tail peeking on the rump (star-tail variant drawn OVER
    // the hip later — half-hidden it read as a "held object")
    if !starTail {
        drawPompon(into: &c, cx: pomX, cy: pomY, s: pom, color: "#FFFFFF", edge: tailEdge)
        if spec.pomStar {
            c.fill(Path(svg: fourStar(pomX, pomY - 2 * pom, 2.4)), with: .hex(GOLD))
        }
    }

    // back legs
    for l in layout.legs where l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: hoofC)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FFD54F", part: .back)
    }

    // body + belly
    c.fill(ellipsePath(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
    c.fill(ellipsePath(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), with: .hex(belly))
    // star-flecks scatter across body AND belly — big enough to read at a glance
    if flecks {
        let rows: [(Double, Double, Double)] = [
            (-0.55, -0.3, 2.6),
            (0.42, -0.5, 2.2),
            (-0.05, -0.62, 1.9),
            (0.6, 0.12, 2.4),
            (-0.5, 0.38, 2.2),
            (0.18, 0.32, 2.0),
            (-0.16, 0.72, 1.8),
            (0.45, 0.62, 1.6),
        ]
        for (i, row) in rows.enumerated() {
            let (dx, dy, r) = row
            c.fill(
                Path(svg: fourStar(bodyCX + dx * bodyRX, bodyCY + dy * bodyRY, r * 1.15)),
                with: .hex(i % 2 != 0 ? "#CFC2EC" : FLECK, opacity: 0.95)
            )
        }
    }
    // bought star-tail: a full gold star riding the hip edge, never occluded
    if starTail {
        c.save()
        c.rotate(degrees: 12, about: CGPoint(x: pomX, y: pomY))
        c.fill(Path(svg: fourStar(pomX, pomY, 6.8 * pom)), with: .hex(GOLD))
        c.fill(Path(svg: fourStar(pomX, pomY, 4.2 * pom)), with: .hex("#FFF3D6"))
        c.restore()
    }
    if !layout.standing {
        drawFoldedLegs(into: &c, bodyCX: bodyCX, bodyCY: bodyCY, bodyRX: bodyRX, color: body, hoof: hoofC)
    }
    // lying nappy-culotte hugs the rump end of the loaf (gated stade 2+ in
    // the catalog, kept for safety)
    if !layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX - bodyRX * 0.3, cy: bodyCY + bodyRY * 0.1, rx: bodyRX * 0.7, ry: bodyRY * 0.9, color: "#5AA9E0", lying: true)
    }

    // neck
    c.stroke(
        Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
        with: .hex(body),
        style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
    )

    // front legs
    for l in layout.legs where !l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: hoofC)
    }

    // downy chest
    if spec.chest {
        drawPlume(into: &c, x: headCX, y: headCY + headR * 0.8, color: belly, len: 5.5, wide: headR * 0.65, rot: 0, n: 3)
    }

    // floating crescent moon beside the head (behind the ears)
    if (spec.halo ?? 0) > 0 {
        drawAura(into: &c, cx: headCX + headR * 1.05, cy: headCY - headR * 0.9, r: headR * 0.95, color: "#FFF3C4", opacity: (spec.halo ?? 0) + 0.15)
        drawCrescent(into: &c, cx: headCX + headR * 1.05, cy: headCY - headR * 0.9, r: headR * 0.4, fill: STAR_SOFT, rot: 24)
    }

    // ears (behind the head)
    for (i, e) in ears.enumerated() {
        if foldEars {
            drawKinkEar(
                into: &c,
                bx: e.bx, by: e.by, len: earL * e.k, wid: earW, rot: e.rot,
                kink: (layout.standing ? (i == 0 ? -1.0 : 1.0) : -1.0) * 100,
                outer: body, inner: inner, dip: spec.dip ? GOLD : nil
            )
        } else {
            drawEar(
                into: &c,
                bx: e.bx, by: e.by, len: earL * e.k, wid: earW, rot: e.rot,
                outer: body, inner: inner, dip: spec.dip ? GOLD : nil,
                star: spec.starTips ? STAR_SOFT : nil
            )
        }
    }

    // head
    c.fill(ellipsePath(headCX, headCY, headR, headR * 0.96), with: .hex(body))

    // face
    drawEyes(into: &c, cx: headCX, y: headCY - headR * 0.05, dx: headR * 0.38, r: eyeR, mood: mood, sleepy: stage == 0)
    drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.32, dx: headR * 0.62, r: headR * 0.13)
    drawWhiskerDots(into: &c, cx: headCX, y: headCY + headR * 0.34, dx: headR * 0.8, s: headR * 0.05)
    drawBunnyNose(into: &c, cx: headCX, y: headCY + headR * 0.28, s: headR * 0.055)
    drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.48, w: headR * 0.13, mood: mood)
    if spec.mark {
        drawCrescent(into: &c, cx: headCX, cy: headCY - headR * 0.55, r: 2.7, fill: GOLD, rot: 18)
    }

    // accessories — placement from the shared anchor resolver
    if layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.15, rx: bodyRX * 0.98, ry: bodyRY * 0.88, color: "#5AA9E0", star: stage >= 6)
        // shoulder straps make it read "maillot une-pièce", not a shirt
        for d in [-1.0, 1.0] {
            c.stroke(
                Path(svg: "M\(bodyCX + d * bodyRX * 0.36) \(bodyCY + bodyRY * 0.08) Q\(bodyCX + d * bodyRX * 0.44) \(bodyCY - bodyRY * 0.45) \(bodyCX + d * bodyRX * 0.4) \(bodyCY - bodyRY * 0.92)"),
                with: .hex("#5AA9E0"),
                style: StrokeStyle(lineWidth: 2.6, lineCap: .round)
            )
        }
    }
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FFD54F", duck: stage >= 7, part: .front)
    }
    if has(A.bow) {
        drawBow(into: &c, x: anchor.neck.x, y: anchor.neck.y, s: max(0.75, headR * 0.048), color: CAP)
        c.fill(Path(svg: fourStar(anchor.neck.x, anchor.neck.y, max(0.75, headR * 0.048) * 1.7)), with: .hex(GOLD))
    }
    if has(A.nightcap) {
        drawNightcap(into: &c, hx: headCX, hy: headCY, headR: headR)
    }

    // premium « Poussière d'étoiles » — gold star-dust orbiting the pet.
    // Own beats: denser dust at 7+, a shooting star joins at 9.
    if stardust {
        var dust: [(Double, Double, Double)] = [
            (10, 15, 2.6),
            (60, 13, 1.8),
            (150, 16, 2.2),
            (200, 14, 1.7),
            (335, 18, 2.8),
        ]
        if stage >= 7 {
            dust += [(25, 21, 2.0), (170, 22, 2.4)]
        }
        drawSparkles(
            into: &c,
            points: dust.map { deg, rad, r in
                let a = deg * .pi / 180
                return (bodyCX + cos(a) * (bodyRX + rad), bodyCY + sin(a) * (bodyRY * 0.55 + rad) - 4, r)
            },
            color: GOLD
        )
        for (deg, rad, r) in [(95.0, 10.0, 1.2), (185, 12, 1.0), (350, 9, 1.1)] {
            let a = deg * .pi / 180
            c.fill(
                circlePath(bodyCX + cos(a) * (bodyRX + rad), bodyCY + sin(a) * (bodyRY * 0.55 + rad) - 4, r),
                with: .hex("#FFE8A3", opacity: 0.8)
            )
        }
        if stage >= 9 {
            drawComet(into: &c, x: bodyCX - bodyRX - 18, y: bodyCY - bodyRY - 20, s: 0.9, rot: 200)
        }
    }

    // stade-9 ring of stars + free sparkles
    if spec.burst {
        drawBurst(into: &c, cx: bodyCX, cy: bodyCY - 6, rx: bodyRX + 16, ry: bodyRY + 14, n: 8, color: STAR_SOFT)
    }
    if (spec.sparkle ?? 0) > 0 {
        drawSparkles(
            into: &c,
            points: (0..<(spec.sparkle ?? 0)).map { i in
                let a = Double(i) / Double(spec.sparkle ?? 1) * .pi * 2 + 0.9
                return (bodyCX + cos(a) * (bodyRX + 13), bodyCY + sin(a) * (bodyRY + 10) - 3, 1.5 + Double(i % 3))
            },
            color: "#FFF1B8"
        )
    }
}
