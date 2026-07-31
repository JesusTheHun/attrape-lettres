import ALCore
import CoreGraphics
import Foundation
import SwiftUI

// Port of `src/mascot/Cat.tsx`.
//
// Cat — "Lynx Royal" timeline. Each stade 3→9 adds a clear, high-contrast beat
// on the way to a majestic big-cat:
//  0-2 (untouched) curled newborn → lifting head → first steps
//  3 whiskers sprout · 4 lynx ear-tufts · 5 neck ruff starts
//  6 growing mane + sparkle · 7 big mane · 8 aura + XL mane + halo
//  9 lion mane, gold crown, ground glow, sparkle burst.
// The mane is a soft two-tone scallop ruff (see drawMane) so the cat stays kawaii
// while still reading "lion" against the same-coloured head.

enum Tuft: String {
    case none
    case small
    case big
}

struct CSpec {
    /** tail length/bushiness multiplier. */
    var tail: Double
    var tuft: Tuft
    var aura: Double
    var sparkle: Int
    // NB: `whiskers` is declared before `mane` (the TS interface says mane
    // first) so Swift's memberwise init accepts the STAGES rows in the same
    // argument order as the TSX object literals, which list whiskers first.
    /** whiskers — sprout at stade 3 (a newborn's bare face is its own beat). */
    var whiskers: Bool?
    /** lion-mane plume length; nil/0 = none. */
    var mane: Double?
    /** head halo opacity 0..1. */
    var halo: Double?
    /** royal crown. */
    var crown: Bool?
    /** pool of light under the paws. */
    var ground: Bool?
}

private let MANE = "#FFC98A"
private let MANE_EDGE = "#E08A3C"

private let STAGES: [CSpec] = [
    CSpec(tail: 0.5, tuft: .none, aura: 0, sparkle: 0), // 0
    CSpec(tail: 0.6, tuft: .none, aura: 0, sparkle: 0), // 1
    CSpec(tail: 0.8, tuft: .none, aura: 0, sparkle: 0), // 2
    CSpec(tail: 1.05, tuft: .none, aura: 0, sparkle: 0, whiskers: true), // 3 whiskers
    CSpec(tail: 1.2, tuft: .small, aura: 0, sparkle: 0, whiskers: true), // 4 lynx ear-tufts
    CSpec(tail: 1.4, tuft: .small, aura: 0, sparkle: 0, whiskers: true, mane: 5), // 5 ruff
    CSpec(tail: 1.55, tuft: .small, aura: 0.15, sparkle: 1, whiskers: true, mane: 6.5), // 6 mane + sparkle
    CSpec(tail: 1.6, tuft: .big, aura: 0.4, sparkle: 2, whiskers: true, mane: 8), // 7 big mane
    CSpec(tail: 1.75, tuft: .big, aura: 0.7, sparkle: 3, whiskers: true, mane: 9.5, halo: 0.6), // 8 XL mane + halo
    CSpec(tail: 2.0, tuft: .big, aura: 1, sparkle: 6, whiskers: true, mane: 11, halo: 0.6, crown: true, ground: true), // 9 lion king
]

// NB: the table is total over the closed `Tuft` enum; the `?? 0` only exists
// because Swift dictionary lookups are optional.
private let TUFT_LEN: [Tuft: Double] = [.none: 0, .small: 4, .big: 7]

/** Lion mane, kawaii — an ARC of soft round fur scallops in warm two-tone
 * (spiky dark plumes read "angry"; round pastel clumps read "plush"). The
 * chest is left clear so the head keeps its silhouette instead of melting
 * into a blob. */
func drawMane(into c: inout SVGCanvas, cx: Double, cy: Double, r: Double, size: Double) {
    let n = 12
    func ring(_ rad: Double, _ cr: Double, _ col: String, into c: inout SVGCanvas) {
        for i in 0..<n {
            let a = Double(i) / Double(n) * .pi * 2
            if sin(a) > 0.62 { continue } // skip the chest wedge
            let x = cx + cos(a) * rad
            let y = cy + sin(a) * rad + r * 0.1
            c.fill(circlePath(x, y, cr), with: .hex(col))
        }
    }
    ring(r + size * 0.5, size * 0.92, MANE_EDGE, into: &c)
    ring(r + size * 0.28, size * 0.72, MANE, into: &c)
}

public func drawCatRig(
    into c: inout SVGCanvas,
    config: MascotConfig,
    // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
    layout: ALCore.Layout,
    stage: Int,
    mood: Mood,
    preview: Bool = false
) {
    typealias C = ColorSlot.Cat
    typealias S = StyleSlot.Cat
    typealias A = Accessory.Cat
    let body = Growth.pick(config.colors, C.body, "#F6A96B")
    let belly = Growth.pick(config.colors, C.belly, "#FFF3E4")
    let tailCol = Growth.pick(config.colors, C.tail, body)
    let fluffy = Growth.pick(config.styles, S.hair, "short") == "fluffy"
    let longTail = Growth.pick(config.styles, S.tail, "long") == "long"
    func has(_ id: String) -> Bool { config.accessories.contains(id) }
    let furEdge = Growth.mix(body, "#FFFFFF", 0.34) // lighter fluff so scallops are visible

    var spec = STAGES[max(0, min(9, stage))]
    // Shop thumbnail: keep the tail (a sold part) but drop mane/tufts/halo/crown/
    // glow so a ghost cat shows ONLY what this tile changes.
    if preview {
        spec.tuft = .none
        spec.aura = 0
        spec.sparkle = 0
        spec.mane = nil
        spec.whiskers = false
        spec.halo = nil
        spec.crown = false
        spec.ground = false
    }
    let tailF = spec.tail * (longTail ? 1 : 0.62)
    let raise = Growth.ramp(Double(stage), [(0, 0), (2, 0.1), (4, 0.4), (6, 0.75), (9, 1)])
    let earScale = Growth.ramp(Double(stage), [(0, 0.44), (2, 0.5), (4, 0.58), (6, 0.64), (9, 0.68)])
    let earPoint = Growth.ramp(Double(stage), [(0, 0.6), (3, 0.85), (6, 1), (9, 1)])
    let bodyCX = layout.bodyCX
    let bodyCY = layout.bodyCY
    let bodyRX = layout.bodyRX
    let bodyRY = layout.bodyRY
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let eyeR = layout.eyeR
    let anchor = accessoryAnchors(.cat, layout)
    let legW = 7.0

    // Lush fur-clump tail: BUSHINESS (rB) scales with age; reach stays bounded so
    // the tail curls beside the body instead of flying off-canvas.
    let tx = bodyCX + bodyRX * 0.72
    let ty = bodyCY + bodyRY * 0.02
    let cx1 = tx + 13 + 4 * raise
    let cy1 = ty - (4 + 11 * raise)
    let tipX = tx + 8 + 6 * raise
    let tipY = ty - (10 + 24 * raise)
    let rB = 3.2 + 4.9 * tailF
    func bez(_ t: Double) -> (Double, Double) {
        (
            (1 - t) * (1 - t) * tx + 2 * (1 - t) * t * cx1 + t * t * tipX,
            (1 - t) * (1 - t) * ty + 2 * (1 - t) * t * cy1 + t * t * tipY
        )
    }
    let clumps = (0..<7).map { i -> (px: Double, py: Double, r: Double) in
        let t = Double(i) / 6
        let (px, py) = bez(t)
        return (px, py, rB * (0.62 + 0.5 * sin(.pi * (0.16 + 0.8 * t))))
    }

    if spec.ground == true {
        drawGroundGlow(into: &c, cx: 50, y: layout.feetY + 2, rx: bodyRX + 18, color: "#FFE29A", opacity: 0.85)
    }
    if spec.aura > 0 {
        drawAura(into: &c, cx: tipX, cy: tipY, r: rB * 1.5, color: "#FFE6B0", opacity: spec.aura)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if !layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.15, rx: bodyRX * 1.15, color: "#FF6B6B")
    }

    // tail behind body — core + fur clumps + fluffy tip
    c.stroke(
        Path(svg: "M\(tx) \(ty) Q\(cx1) \(cy1) \(tipX) \(tipY)"),
        with: .hex(tailCol),
        style: StrokeStyle(lineWidth: rB * 0.7, lineCap: .round)
    )
    for b in clumps {
        c.fill(circlePath(b.px, b.py, b.r), with: .hex(tailCol))
    }
    if fluffy {
        for (i, b) in clumps.enumerated() where i % 2 == 1 {
            c.fill(circlePath(b.px - b.r * 0.4, b.py - b.r * 0.4, b.r * 0.5), with: .hex(furEdge))
        }
    }
    drawPlume(into: &c, x: tipX, y: tipY, color: tailCol, len: 5 + 5 * tailF, wide: 4 + 4 * tailF, rot: -12, n: 4)

    // back legs
    for l in layout.legs where l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: body)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FF6B6B", part: .back)
    }

    // fluffy: lighter scalloped fur halo breaking the body outline
    if fluffy {
        for i in 0..<16 {
            let a = Double(i) / 16 * .pi * 2
            c.fill(circlePath(bodyCX + cos(a) * bodyRX * 1.02, bodyCY + sin(a) * bodyRY * 1.02, 5.4), with: .hex(furEdge))
        }
    }

    // body
    c.fill(ellipsePath(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
    c.fill(ellipsePath(bodyCX, bodyCY + bodyRY * 0.28, bodyRX * 0.6, bodyRY * 0.62), with: .hex(belly))
    if !layout.standing {
        drawFoldedLegs(into: &c, bodyCX: bodyCX, bodyCY: bodyCY, bodyRX: bodyRX, color: body, hoof: belly)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if !layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#4FC3F7", lying: true)
    }
    c.stroke(
        Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
        with: .hex(body),
        style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
    )

    // front legs
    for l in layout.legs where !l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: body)
    }

    // halo + lion mane (behind the head)
    if let halo = spec.halo {
        drawHalo(into: &c, cx: headCX, cy: headCY, r: headR * 1.7, opacity: halo)
    }
    if let mane = spec.mane {
        drawMane(into: &c, cx: headCX, cy: headCY, r: headR * 1.02, size: mane)
    }

    // fluffy: lighter fur halo around the head
    if fluffy {
        for i in 0..<12 {
            let a = Double(i) / 12 * .pi * 2
            c.fill(circlePath(headCX + cos(a) * headR * 1.0, headCY + sin(a) * headR * 1.0, 4.6), with: .hex(furEdge))
        }
    }

    // head
    c.fill(ellipsePath(headCX, headCY, headR, headR), with: .hex(body))

    // ears — grow & sharpen with age; rounded tip for babies
    for d in [-1.0, 1.0] {
        let ex = headCX + d * headR * 0.62
        let ey = headCY - headR * 0.58
        let th = headR * earScale
        let tipYe = ey - th
        let tipXe = ex + d * th * 0.35
        c.fill(
            Path(svg: "M\(ex - d * headR * 0.02) \(ey) Q\(tipXe - d * th * 0.1) \(tipYe) \(tipXe) \(tipYe + th * (1 - earPoint) * 0.2) Q\(tipXe + d * th * 0.05) \(tipYe + 2) \(ex - d * headR * 0.6) \(ey - headR * 0.02) Z"),
            with: .hex(body)
        )
        c.fill(
            Path(svg: "M\(ex) \(ey - 1) L\(tipXe - d * th * 0.05) \(tipYe + th * 0.28) L\(ex - d * headR * 0.34) \(ey - headR * 0.02) Z"),
            with: .hex("#FF9AA2")
        )
        if (TUFT_LEN[spec.tuft] ?? 0) > 0 {
            drawPlume(into: &c, x: tipXe, y: tipYe + 1, color: body, len: TUFT_LEN[spec.tuft] ?? 0, wide: 2, rot: d * 12, n: 2)
        }
    }

    // fluffy cheeks + head tuft (lighter, unmistakable)
    if fluffy {
        for d in [-1.0, 1.0] {
            drawPlume(into: &c, x: headCX + d * headR * 0.92, y: headCY + headR * 0.2, color: furEdge, len: 11, wide: 7, rot: d * 74, n: 4)
        }
        drawPlume(into: &c, x: headCX, y: headCY - headR * 0.92, color: furEdge, len: 8, wide: 7, rot: 0, n: 3)
    }

    // face
    drawEyes(into: &c, cx: headCX, y: headCY, dx: headR * 0.4, r: eyeR, mood: mood, sleepy: stage == 0)
    drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.36, dx: headR * 0.58, r: headR * 0.14)
    c.fill(
        Path(svg: "M\(headCX - 2.4) \(headCY + headR * 0.3) L\(headCX + 2.4) \(headCY + headR * 0.3) L\(headCX) \(headCY + headR * 0.44) Z"),
        with: .hex("#FF7C93")
    )
    drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.52, w: headR * 0.15, mood: mood)
    // whiskers — the stade-3 beat
    if spec.whiskers == true {
        for d in [-1.0, 1.0] {
            let style = StrokeStyle(lineWidth: 0.8, lineCap: .round)
            c.stroke(
                linePath(headCX + d * headR * 0.35, headCY + headR * 0.4, headCX + d * headR, headCY + headR * 0.3),
                with: .hex("#B79A82"),
                style: style
            )
            c.stroke(
                linePath(headCX + d * headR * 0.35, headCY + headR * 0.48, headCX + d * headR, headCY + headR * 0.5),
                with: .hex("#B79A82"),
                style: style
            )
        }
    }

    // royal crown (majestic)
    if spec.crown == true {
        drawCrown(into: &c, cx: headCX, cy: headCY - headR * 0.5, r: headR * 0.95, band: "#FFD54F", gem: "#E0533B")
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#4FC3F7", star: stage >= 6)
    }
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FF6B6B", duck: stage >= 7, part: .front)
    }
    // collar grows up with the cat: kitten bell → studded leather + gold medal
    // from stade 5 (a baby bell on a lion reads wrong)
    if has(A.bellCollar) {
        if stage >= 5 {
            c.stroke(
                Path(svg: "M\(anchor.neck.x - anchor.neck.w) \(anchor.neck.y) Q\(anchor.neck.x) \(anchor.neck.y + 6) \(anchor.neck.x + anchor.neck.w) \(anchor.neck.y)"),
                with: .hex("#6B4226"),
                style: StrokeStyle(lineWidth: 3.6, lineCap: .round)
            )
            for t in [0.18, 0.38, 0.62, 0.82] {
                let sx = (1 - t) * (1 - t) * (anchor.neck.x - anchor.neck.w) + 2 * (1 - t) * t * anchor.neck.x + t * t * (anchor.neck.x + anchor.neck.w)
                let sy = (1 - t) * (1 - t) * anchor.neck.y + 2 * (1 - t) * t * (anchor.neck.y + 6) + t * t * anchor.neck.y
                c.fill(circlePath(sx, sy, 0.9), with: .hex("#FFD54F"))
            }
            c.fill(circlePath(anchor.neck.x, anchor.neck.y + 5.4, 2.9), with: .hex("#FFD54F"))
            c.stroke(circlePath(anchor.neck.x, anchor.neck.y + 5.4, 2.9), with: .hex("#B98A22"), style: StrokeStyle(lineWidth: 0.8))
            c.stroke(circlePath(anchor.neck.x, anchor.neck.y + 5.4, 1.1), with: .hex("#B98A22"), style: StrokeStyle(lineWidth: 0.7))
        } else {
            c.stroke(
                Path(svg: "M\(anchor.neck.x - anchor.neck.w) \(anchor.neck.y) Q\(anchor.neck.x) \(anchor.neck.y + 6) \(anchor.neck.x + anchor.neck.w) \(anchor.neck.y)"),
                with: .hex("#EF6F6C"),
                style: StrokeStyle(lineWidth: 3.4, lineCap: .round)
            )
            c.fill(circlePath(anchor.neck.x, anchor.neck.y + 4, 3), with: .hex("#FFD54F"))
            c.fill(circlePath(anchor.neck.x, anchor.neck.y + 4, 0.9), with: .hex("#B98A22"))
        }
    }
    if has(A.bow) {
        drawBow(into: &c, x: anchor.headTop.x - headR * 0.5, y: anchor.headTop.y + headR * 0.18, s: 0.95, color: "#FF7EA8")
    }
    if has(A.partyHat) {
        c.fill(
            Path(svg: "M\(headCX) \(headCY - headR * 1.7) L\(headCX - headR * 0.5) \(headCY - headR * 0.8) L\(headCX + headR * 0.5) \(headCY - headR * 0.8) Z"),
            with: .hex("#4FC3F7")
        )
        c.fill(
            Path(svg: "M\(headCX - headR * 0.18) \(headCY - headR * 1.2) L\(headCX + headR * 0.26) \(headCY - headR * 1.1) L\(headCX + headR * 0.2) \(headCY - headR * 0.87) L\(headCX - headR * 0.24) \(headCY - headR * 0.95) Z"),
            with: .hex("#FFD54F")
        )
        c.fill(circlePath(headCX, headCY - headR * 1.73, 2.6), with: .hex("#FF8A65"))
    }

    if spec.sparkle > 0 {
        drawSparkles(
            into: &c,
            points: (0..<spec.sparkle).map { i in
                let a = Double(i) / Double(spec.sparkle) * .pi * 2 + 0.5
                return (bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 8), 1.6 + Double(i % 3))
            }
        )
    }
}
