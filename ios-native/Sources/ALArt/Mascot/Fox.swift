import ALCore
import CoreGraphics
import Foundation
import SwiftUI

// Port of `src/mascot/Fox.tsx`.
//
// Fox — "Kitsune" timeline. Each stade 3→9 sprouts a clearly visible beat on
// the way to a many-tailed fire-spirit:
//  0-2 (untouched) curled kit → lifting head → first steps
//  3 ear-tufts + ruff · 4 a 2nd tail · 5 a 3rd tail
//  6 sparkle + flame-tipped tails · 7 five tails · 8 forehead mark + halo
//  9 seven glowing tails, flame tips, halo, ground glow, sparkle burst.
// The extra tails are a DEEPER orange with a dark outline + cream tips, so they
// separate from the same-coloured body instead of merging into one mass.

struct FSpec {
    var tail: Double
    var ruff: Double
    var tuft: Bool
    var aura: Double
    var sparkle: Int
    /** number of extra fanned kitsune tails (on top of the base tail). */
    var extraTails: Int?
    /** flame tips on the fanned tails. */
    var flames: Bool?
    /** forehead spirit-mark. */
    var mark: Bool?
    /** head halo opacity 0..1. */
    var halo: Double?
    /** pool of light under the paws. */
    var ground: Bool?
}

private let TAIL_DEEP = "#F26B3C"
private let TAIL_EDGE = "#B8431C"
private let TAIL_TIP = "#FFF6EE"

private let STAGES: [FSpec] = [
    FSpec(tail: 0.55, ruff: 0.4, tuft: false, aura: 0, sparkle: 0), // 0
    FSpec(tail: 0.65, ruff: 0.45, tuft: false, aura: 0, sparkle: 0), // 1
    FSpec(tail: 0.8, ruff: 0.55, tuft: false, aura: 0, sparkle: 0), // 2
    FSpec(tail: 0.95, ruff: 0.7, tuft: true, aura: 0, sparkle: 0), // 3 ear tufts + ruff
    FSpec(tail: 1.05, ruff: 0.8, tuft: true, aura: 0, sparkle: 0, extraTails: 1), // 4 2nd tail
    FSpec(tail: 1.15, ruff: 0.9, tuft: true, aura: 0, sparkle: 0, extraTails: 2), // 5 3 tails
    FSpec(tail: 1.25, ruff: 1.0, tuft: true, aura: 0.2, sparkle: 1, extraTails: 2, flames: true), // 6 flame tips
    FSpec(tail: 1.4, ruff: 1.1, tuft: true, aura: 0.4, sparkle: 2, extraTails: 4, flames: true), // 7 5 tails
    FSpec(tail: 1.55, ruff: 1.2, tuft: true, aura: 0.7, sparkle: 3, extraTails: 4, flames: true, mark: true, halo: 0.55), // 8
    FSpec(tail: 1.8, ruff: 1.3, tuft: true, aura: 1, sparkle: 6, extraTails: 6, flames: true, mark: true, halo: 0.55, ground: true), // 9 kitsune
]

/** Small two-tone flame. */
func drawFlame(into c: inout SVGCanvas, x: Double, y: Double, s: Double) {
    c.save()
    c.translate(x, y)
    c.scale(s)
    c.fill(Path(svg: "M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z"), with: .hex("#FF7043"))
    c.fill(Path(svg: "M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z"), with: .hex("#FFE082"))
    c.restore()
}

/** One fanned kitsune tail — a bushy fur-clump curve with an outline underlay
 * + a deeper fill + cream tip, so it reads apart from the body and its siblings.
 * NB: the TSX takes a `ki` prop that is only a React key; it has no port. */
func drawFanTail(
    into c: inout SVGCanvas,
    rootX: Double,
    rootY: Double,
    angleDeg: Double,
    length: Double,
    rB: Double,
    flame: Bool
) {
    let a = angleDeg * .pi / 180
    let perp = a + .pi / 2
    let ex = rootX + cos(a) * length
    let ey = rootY + sin(a) * length
    let cx = rootX + cos(a) * length * 0.5 + cos(perp) * length * 0.16
    let cy = rootY + sin(a) * length * 0.5 + sin(perp) * length * 0.16
    func bez(_ t: Double) -> (Double, Double) {
        (
            (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * cx + t * t * ex,
            (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * cy + t * t * ey
        )
    }
    let clumps = (0..<6).map { i -> (px: Double, py: Double, r: Double, tip: Bool) in
        let t = Double(i) / 5
        let (px, py) = bez(t)
        return (px, py, rB * (0.55 + 0.5 * sin(.pi * (0.12 + 0.8 * t))), t > 0.72)
    }
    let (tx, ty) = bez(1)
    for b in clumps {
        c.fill(circlePath(b.px, b.py, b.r + 1.1), with: .hex(TAIL_EDGE))
    }
    for b in clumps {
        c.fill(circlePath(b.px, b.py, b.r), with: .hex(b.tip ? TAIL_TIP : TAIL_DEEP))
    }
    if flame {
        drawFlame(into: &c, x: tx, y: ty, s: 0.6)
    }
}

public func drawFoxRig(
    into c: inout SVGCanvas,
    config: MascotConfig,
    // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
    layout: ALCore.Layout,
    stage: Int,
    mood: Mood,
    preview: Bool = false
) {
    typealias C = ColorSlot.Fox
    typealias S = StyleSlot.Fox
    typealias A = Accessory.Fox
    let body = Growth.pick(config.colors, C.body, "#FF8A65")
    // Default belly is pure white so the warm "Ventre crème" option reads clearly.
    let belly = Growth.pick(config.colors, C.belly, "#FFFFFF")
    let tailTip = Growth.pick(config.colors, C.tailTip, "#FFFFFF")
    let pattern = Growth.pick(config.styles, S.fur, "plain")
    let longTail = Growth.pick(config.styles, S.tail, "long") == "long"
    func has(_ id: String) -> Bool { config.accessories.contains(id) }

    var spec = STAGES[max(0, min(9, stage))]
    // Shop thumbnail: keep the base tail + ruff (sold parts) but drop the extra
    // kitsune tails, flames, spirit-mark, halo and glow so a ghost fox shows ONLY
    // what this tile changes.
    if preview {
        spec.tuft = false
        spec.aura = 0
        spec.sparkle = 0
        spec.extraTails = 0
        spec.flames = false
        spec.mark = false
        spec.halo = nil
        spec.ground = false
    }
    let tailF = spec.tail * (longTail ? 1 : 0.66)
    let bodyCX = layout.bodyCX
    let bodyCY = layout.bodyCY
    let bodyRX = layout.bodyRX
    let bodyRY = layout.bodyRY
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let eyeR = layout.eyeR
    let anchor = accessoryAnchors(.fox, layout)
    let legW = 7.0

    // Bushy tail = fur clumps along a curve; it tapers to a pointed TIP whose fur
    // clumps carry the recolour, so the tint follows the tail silhouette to a point.
    let rootX = bodyCX + bodyRX * 0.55
    let rootY = bodyCY - bodyRY * 0.22
    let ctrlX = bodyCX + bodyRX * 1.28
    let ctrlY = bodyCY + bodyRY * 0.18
    let endX = bodyCX + bodyRX * 0.92
    let endY = bodyCY + bodyRY * 0.98
    let rB = 5 + 6 * tailF
    func bez(_ t: Double) -> (Double, Double) {
        (
            (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * ctrlX + t * t * endX,
            (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * ctrlY + t * t * endY
        )
    }
    let bodyClumps = (0..<6).map { i -> (px: Double, py: Double, r: Double) in
        let t = Double(i) / 5 * 0.72
        let (px, py) = bez(t)
        return (px, py, rB * (0.75 + 0.35 * sin(.pi * (t / 0.72))))
    }
    let tipClumps = (0..<4).map { i -> (px: Double, py: Double, r: Double) in
        let t = 0.76 + Double(i) / 3 * 0.24
        let (px, py) = bez(t)
        return (px, py, rB * (0.62 - 0.36 * (Double(i) / 3)))
    }

    let extra = spec.extraTails ?? 0

    if spec.ground == true {
        drawGroundGlow(into: &c, cx: 50, y: layout.feetY + 2, rx: bodyRX + 18, color: "#FFD59A", opacity: 0.85)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if !layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.15, rx: bodyRX * 1.15, color: "#FFD54F")
    }

    // extra kitsune tails, fanned symmetrically behind the body
    for i in 0..<extra {
        let spread = extra == 1 ? 0 : Double(i) / Double(extra - 1) - 0.5
        drawFanTail(
            into: &c,
            rootX: bodyCX,
            rootY: bodyCY + bodyRY * 0.02,
            angleDeg: 270 + spread * 132,
            length: bodyRX * (1.12 + 0.42 * tailF),
            rB: 4 + 3 * tailF,
            flame: spec.flames == true
        )
    }

    // bushy tail behind body; recolourable tapering tip
    if spec.aura > 0 {
        drawAura(into: &c, cx: endX, cy: endY, r: rB * 1.3, color: "#FFF1C4", opacity: spec.aura)
    }
    for b in bodyClumps {
        c.fill(circlePath(b.px, b.py, b.r), with: .hex(body))
    }
    for b in tipClumps {
        c.fill(circlePath(b.px, b.py, b.r), with: .hex(tailTip))
    }

    // back legs
    for l in layout.legs where l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: Growth.ink)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FFD54F", part: .back)
    }

    // body
    c.fill(ellipsePath(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))

    // fur pattern (clearly visible)
    if pattern == "spots" {
        for (dx, dy) in [(-0.42, -0.28), (0.28, -0.4), (0.02, -0.05), (-0.15, 0.28), (0.36, 0.05)] {
            c.fill(ellipsePath(bodyCX + dx * bodyRX, bodyCY + dy * bodyRY, 3.2, 3.2), with: .hex("#A24A2C", opacity: 0.85))
        }
    }
    if pattern == "stripes" {
        for dy in [-0.4, -0.05, 0.3] {
            c.stroke(
                Path(svg: "M\(bodyCX - bodyRX * 0.72) \(bodyCY + dy * bodyRY) Q\(bodyCX) \(bodyCY + dy * bodyRY - 5) \(bodyCX + bodyRX * 0.72) \(bodyCY + dy * bodyRY)"),
                with: .hex("#8A4326", opacity: 0.4),
                style: StrokeStyle(lineWidth: 2.6, lineCap: .round)
            )
        }
    }

    c.fill(ellipsePath(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), with: .hex(belly))
    if !layout.standing {
        drawFoldedLegs(into: &c, bodyCX: bodyCX, bodyCY: bodyCY, bodyRX: bodyRX, color: body, hoof: Growth.ink)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if !layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#5AA9E0", lying: true)
    }

    // neck
    c.stroke(
        Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
        with: .hex(body),
        style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
    )

    // front legs
    for l in layout.legs where !l.back {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: Growth.ink)
    }

    // chest ruff
    c.fill(
        Path(svg: "M\(headCX - headR * 0.5 * spec.ruff) \(headCY + headR * 0.7) Q\(headCX) \(headCY + headR * 1.5 * spec.ruff) \(headCX + headR * 0.5 * spec.ruff) \(headCY + headR * 0.7) Z"),
        with: .hex(belly)
    )

    // halo (behind the head)
    if let halo = spec.halo {
        drawHalo(into: &c, cx: headCX, cy: headCY, r: headR * 1.7, opacity: halo, color: "#FFD59A")
    }

    // head
    c.fill(ellipsePath(headCX, headCY, headR, headR * 0.96), with: .hex(body))

    // ears — tall but ROUNDED tips, soft dark tip
    for d in [-1.0, 1.0] {
        let ex = headCX + d * headR * 0.58
        let ey = headCY - headR * 0.5
        let th = headR * Growth.ramp(Double(stage), [(0, 0.55), (4, 0.72), (9, 0.78)])
        let tipX = ex + d * th * 0.32
        let tipY = ey - th
        c.fill(
            Path(svg: "M\(ex - d * headR * 0.5) \(ey) Q\(tipX - d * th * 0.15) \(tipY - 2) \(tipX + d * 1.5) \(tipY + th * 0.14) Q\(tipX + d * th * 0.12) \(tipY + th * 0.1) \(ex + d * headR * 0.05) \(ey - headR * 0.05) Z"),
            with: .hex(body)
        )
        c.fill(
            Path(svg: "M\(tipX) \(tipY + th * 0.05) Q\(tipX + d * th * 0.14) \(tipY + th * 0.12) \(tipX + d * 1) \(tipY + th * 0.3) Q\(tipX - d * th * 0.06) \(tipY + th * 0.24) \(tipX) \(tipY + th * 0.05) Z"),
            with: .hex(Growth.ink)
        )
        if spec.tuft {
            drawPlume(into: &c, x: ex - d * headR * 0.02, y: ey - headR * 0.02, color: belly, len: 5, wide: 3, rot: d * 10, n: 2)
        }
    }

    // white cheek ruff (grows)
    for d in [-1.0, 1.0] {
        drawPlume(into: &c, x: headCX + d * headR * 0.72, y: headCY + headR * 0.3, color: belly, len: 6 + 5 * spec.ruff, wide: 5, rot: d * 60, n: 3)
    }

    // snout
    c.fill(ellipsePath(headCX, headCY + headR * 0.42, headR * 0.42, headR * 0.32), with: .hex(belly))
    c.fill(ellipsePath(headCX, headCY + headR * 0.3, 2.4, 2), with: .hex(Growth.ink))

    // face — softened, rounder eyes
    drawEyes(into: &c, cx: headCX, y: headCY - headR * 0.02, dx: headR * 0.4, r: eyeR * 1.05, mood: mood, sleepy: stage == 0)
    drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.32, dx: headR * 0.62, r: headR * 0.13)
    drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.5, w: headR * 0.14, mood: mood)

    // forehead spirit-mark (kitsune)
    if spec.mark == true {
        c.fill(Path(svg: fourStar(headCX, headCY - headR * 0.55, 2.6)), with: .hex("#FFF3C4"))
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if layout.standing && has(A.swimsuit) {
        drawSwimsuit(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: "#5AA9E0", star: stage >= 6)
    }
    if layout.standing && has(A.swimRing) {
        drawSwimRing(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 1.22, color: "#FFD54F", duck: stage >= 7, part: .front)
    }
    if has(A.scarf) {
        c.stroke(
            Path(svg: "M\(anchor.neck.x - anchor.neck.w) \(anchor.neck.y) Q\(anchor.neck.x) \(anchor.neck.y + 7) \(anchor.neck.x + anchor.neck.w) \(anchor.neck.y)"),
            with: .hex("#66BB6A"),
            style: StrokeStyle(lineWidth: 5, lineCap: .round)
        )
        c.fill(
            Path(svg: "M\(anchor.neck.x + anchor.neck.w * 0.5) \(anchor.neck.y + 2) l3 10 l-6 1 Z"),
            with: .hex("#4FA84E")
        )
    }
    if has(A.beanie) {
        c.fill(
            Path(svg: "M\(headCX - headR) \(headCY - headR * 0.5) Q\(headCX) \(headCY - headR * 1.7) \(headCX + headR) \(headCY - headR * 0.5) Z"),
            with: .hex("#4FC3F7")
        )
        c.fill(
            Path(
                roundedRect: CGRect(x: headCX - headR, y: headCY - headR * 0.62, width: headR * 2, height: headR * 0.3),
                cornerRadius: headR * 0.15
            ),
            with: .hex("#2E9BD6")
        )
        c.fill(circlePath(headCX, headCY - headR * 1.55, 3.4), with: .hex("#FFF3E6"))
    }
    if has(A.boots) {
        for l in anchor.feet {
            c.fill(
                Path(roundedRect: CGRect(x: l.footX - 4, y: l.footY - 6, width: 8, height: 8), cornerRadius: 3),
                with: .hex("#8D5A3B")
            )
            c.fill(
                Path(roundedRect: CGRect(x: l.footX - 5, y: l.footY - 7, width: 10, height: 3), cornerRadius: 1.5),
                with: .hex("#C98A5B")
            )
        }
    }

    if spec.sparkle > 0 {
        drawSparkles(
            into: &c,
            points: (0..<spec.sparkle).map { i in
                let a = Double(i) / Double(spec.sparkle) * .pi * 2 + 1
                return (bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 8), 1.6 + Double(i % 3))
            }
        )
    }
}
