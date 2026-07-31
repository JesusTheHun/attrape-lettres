import CoreGraphics
import Foundation
import SwiftUI
import ALCore

// Port of `src/mascot/Dragon.tsx` — the « Braise » rig.
//
// The TSX component becomes `drawDragonRig(into:config:layout:stage:mood:preview:)`
// per spec/mascot.md §3: `RigProps.uid` has no port (gradients/clips are values,
// not document-namespace defs) and `layout` is computed by the caller
// (`Growth.layoutFor`). Draw order is the TSX statement order, verbatim (D15).

/**
 * Dragon — « Braise » timeline. A deep-green fire dragon whose volcano wakes as
 * he grows: horn nubs (2) → ember wings (3) → charcoal mohawk crest (4) →
 * belly plates (5) → first flame (6) → fangs + claws + gold horn tips (7) →
 * lava cracks + nostril smoke (8) → fire storm with ground glow (9).
 * Stades 0-1 hatch from a cracked egg, shell kept as souvenirs.
 * Wardrobe: colours body ×4 / ventre magma / ailes ×2 / cornes ×2 · styles
 * cornes doubles, crête de lave, queue massue, queue de feu · accessories cape
 * de chevalier (2+), lunettes d'aviateur (3+), collier de croc (2+), petit
 * trésor (dès l'œuf), premium « Flamme bleue » (own beats at 4/7/9).
 * No swim pair — the cross-species tradition is deliberately broken here.
 */

struct DSpec {
    enum Egg { case full, bits }
    var egg: Egg? = nil
    var horn: Double
    var wing: Double
    var crest: Int
    var plates: Bool
    var flame: Double
    var fierce: Bool = false
    var cracks: Bool = false
    var smoke: Bool = false
    var aura: Double
    var ember: Int
    var goldTips: Bool = false
    var ground: Bool = false
}

private let STAGES: [DSpec] = [
    DSpec(egg: .full, horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0), // 0 in the egg
    DSpec(egg: .bits, horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0), // 1 shell souvenirs
    DSpec(horn: 3.5, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0), // 2 horn nubs
    DSpec(horn: 5, wing: 0.9, crest: 0, plates: false, flame: 0, aura: 0, ember: 0), // 3 ember wings
    DSpec(horn: 6, wing: 1.15, crest: 3, plates: false, flame: 0, aura: 0, ember: 0), // 4 charcoal mohawk
    DSpec(horn: 7, wing: 1.3, crest: 4, plates: true, flame: 0, aura: 0, ember: 0), // 5 belly plates
    DSpec(horn: 8, wing: 1.45, crest: 4, plates: true, flame: 0.5, aura: 0.12, ember: 2), // 6 first flame
    DSpec(horn: 10, wing: 1.7, crest: 5, plates: true, flame: 0.62, fierce: true, aura: 0.3, ember: 3, goldTips: true), // 7 fangs + claws
    DSpec(horn: 11, wing: 1.9, crest: 5, plates: true, flame: 0.72, fierce: true, cracks: true, smoke: true, aura: 0.55, ember: 4, goldTips: true), // 8 volcano wakes
    DSpec(horn: 13, wing: 2.2, crest: 6, plates: true, flame: 1.1, fierce: true, cracks: true, smoke: true, aura: 1, ember: 8, goldTips: true, ground: true), // 9 fire storm
]

private let EGG_SPECKLE = "#E8C49A"
private let HORN_FILL = "#EDE3CE"
private let HORN_EDGE = "#B8A98C"
private let CREST = "#5F6470"
private let CREST_EDGE = "#3E4148"
private let LAVA_CREST = "#FF8A50"
private let LAVA_CREST_EDGE = "#C2502E"
private let WING_EDGE = "#A83E28"
private let EMBER = "#FF8A50"
private let BLUE_OUTER = "#5BC8FF"
private let BLUE_INNER = "#E8F7FF"
private let BLUE_EMBER = "#7FD1FF"

public func drawDragonRig(
    into c: inout SVGCanvas,
    config: MascotConfig,
    // NB: qualified — SwiftUI declares a `Layout` protocol that shadows ALCore's.
    layout: ALCore.Layout,
    stage: Int,
    mood: Mood,
    preview: Bool = false
) {
    typealias C = ColorSlot.Dragon
    typealias S = StyleSlot.Dragon
    typealias A = Accessory.Dragon
    let body = Growth.pick(config.colors, C.body, "#7DB874")
    let belly = Growth.pick(config.colors, C.belly, "#E9DFB2")
    let wingCol = Growth.pick(config.colors, C.wing, "#E2694F")
    let hornCol = Growth.pick(config.colors, C.horn, HORN_FILL)
    let hornEdge = hornCol == HORN_FILL ? HORN_EDGE : Growth.mix(hornCol, Growth.ink, 0.35)
    let doubleHorns = Growth.pick(config.styles, S.horn, "straight") == "double"
    let lavaCrest = Growth.pick(config.styles, S.crest, "charbon") == "lava"
    let tailPick = Growth.pick(config.styles, S.tail, "spade")
    func has(_ id: String) -> Bool { config.accessories.contains(id) }
    let blue = has(A.blueFlame)

    var spec = STAGES[max(0, min(9, stage))]
    // Shop thumbnail: strip the egg + all free per-stage magic so a ghost dragon
    // shows only what a tile sells (sold styles/accessories stay visible).
    if preview {
        spec.egg = nil
        spec.flame = 0
        spec.aura = 0
        spec.ember = 0
        spec.fierce = false
        spec.cracks = false
        spec.smoke = false
        spec.goldTips = false
        spec.ground = false
    }

    let bodyCX = layout.bodyCX
    let bodyCY = layout.bodyCY
    let bodyRX = layout.bodyRX
    let bodyRY = layout.bodyRY
    let headCX = layout.headCX
    let headCY = layout.headCY
    let headR = layout.headR
    let eyeR = layout.eyeR
    let anchor = accessoryAnchors(.dragon, layout)
    let legW = 7.0
    let tailEdge = Growth.mix(body, Growth.ink, 0.35)
    let crestCol = lavaCrest ? LAVA_CREST : CREST
    let crestEdge = lavaCrest ? LAVA_CREST_EDGE : CREST_EDGE
    // The flame tail lights up with the walking stades; before that it stays a spade.
    let tailTipRaw = tailPick == "flame" && stage < 2 && !preview ? "spade" : tailPick
    // NB: an unknown tailStyle string maps to nil → curve, no tip — same as the
    // TSX cast falling through every `tip === …` branch.
    let tailTip = DragonTailTip(rawValue: tailTipRaw)
    let tailTipColor: String? =
        tailTip == .club ? Growth.mix(body, Growth.ink, 0.12) : tailTip == .spade ? belly : nil

    /* -- Stade 0: hatching in the cracked egg ------------------------------ */
    if spec.egg == .full {
        if has(A.treasure) { drawTreasure(into: &c, stage: stage, x: 14, groundY: layout.feetY + 2) }
        c.fill(ellipse(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
        c.stroke(
            Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
            with: .hex(body),
            style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
        )
        c.fill(ellipse(headCX, headCY, headR, headR * 0.96), with: .hex(body))
        drawSnout(into: &c, hx: headCX, hy: headCY, headR: headR, color: belly)
        drawEyes(into: &c, cx: headCX, y: headCY - headR * 0.05, dx: headR * 0.36, r: eyeR * 0.85, mood: mood, sleepy: stage == 0)
        drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.32, dx: headR * 0.62, r: headR * 0.13)
        drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.42, w: headR * 0.1, mood: mood)
        drawEggCup(into: &c, speckle: EGG_SPECKLE)
        drawShellCap(into: &c, x: headCX + 2, y: headCY - headR * 0.86, s: 1, tilt: 10, speckle: EGG_SPECKLE)
        drawSpadeTail(into: &c, p0: (81, 87), p1: (89, 83), p2: (87.5, 75), w: 4.5, color: body, edge: tailEdge, tip: tailTip, tipColor: tailTipColor, tipS: 0.7)
        return
    }

    /* -- Stade 1: hatched, shell souvenirs ---------------------------------- */
    if !layout.standing {
        if has(A.treasure) { drawTreasure(into: &c, stage: stage, x: bodyCX - bodyRX - 9, groundY: layout.feetY + 2) }
        drawSpadeTail(
            into: &c,
            p0: (bodyCX + bodyRX * 0.65, bodyCY + 2),
            p1: (bodyCX + bodyRX + 9, bodyCY),
            p2: (bodyCX + bodyRX + 8, bodyCY - 9),
            w: 5.5,
            color: body,
            edge: tailEdge,
            tip: tailTip,
            tipColor: tailTipColor,
            tipS: 0.8
        )
        c.fill(ellipse(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
        c.fill(ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), with: .hex(belly))
        drawFoldedLegs(into: &c, bodyCX: bodyCX, bodyCY: bodyCY, bodyRX: bodyRX, color: body, hoof: Growth.ink)
        c.stroke(
            Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
            with: .hex(body),
            style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
        )
        c.fill(ellipse(headCX, headCY, headR, headR * 0.96), with: .hex(body))
        drawSnout(into: &c, hx: headCX, hy: headCY, headR: headR, color: belly)
        drawEyes(into: &c, cx: headCX, y: headCY - headR * 0.05, dx: headR * 0.4, r: eyeR, mood: mood, sleepy: false)
        drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.32, dx: headR * 0.62, r: headR * 0.13)
        drawMouth(into: &c, cx: headCX, y: headCY + headR * 0.52, w: headR * 0.13, mood: mood)
        if spec.egg == .bits {
            drawShellCap(into: &c, x: headCX + 3, y: headCY - headR * 0.88, s: 0.82, tilt: -12, speckle: EGG_SPECKLE)
            drawShellShard(into: &c, x: bodyCX - bodyRX * 0.62, y: bodyCY + bodyRY * 0.42, s: 0.9, tilt: -10, speckle: EGG_SPECKLE)
        }
        return
    }

    /* -- Stades 2-9: on its feet -------------------------------------------- */
    let tf = Growth.ramp(Double(stage), [(2, 0), (5, 0.5), (9, 1)])
    let tailRoot = (bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5)
    let tailCtrl = (bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9)
    let tailEnd = (bodyCX + bodyRX * (1.42 + 0.1 * tf), bodyCY + bodyRY * (0.45 - (0.5 + 0.55 * tf)))
    let mouthY = headCY + headR * 0.52
    let mouthW = headR * 0.13
    // ramp() clamps below its first stop — gate by stage or the "blue from 4"
    // breath would leak down to the wobbly stades.
    let flameS = blue && stage >= 4
        ? max(spec.flame, Growth.ramp(Double(stage), [(4, 0.5), (6, 0.62), (7, 0.85), (9, 1.25)]))
        : spec.flame
    let emberN = blue && stage >= 7 ? max(spec.ember, 4) : spec.ember
    let emberCol = blue ? BLUE_EMBER : EMBER

    if spec.ground {
        drawGroundGlow(into: &c, cx: 50, y: layout.feetY + 2, rx: bodyRX + 18, color: blue ? "#9FD4FF" : "#FF9A66", opacity: 0.9)
    }
    if spec.aura > 0 {
        drawAura(into: &c, cx: bodyCX, cy: bodyCY - 2, r: bodyRX + 22, color: blue ? "#B8E2FF" : "#FFB27A", opacity: spec.aura)
    }

    drawBatWings(into: &c, cx: bodyCX, cy: bodyCY - bodyRY * 0.4, s: spec.wing, membrane: wingCol, edge: WING_EDGE)
    if has(A.cape) {
        drawCapeBack(into: &c, cx: bodyCX, topY: bodyCY - bodyRY * 0.62, w: bodyRX * 1.3, h: layout.feetY - 2 - (bodyCY - bodyRY * 0.62))
    }
    drawSpadeTail(into: &c, p0: tailRoot, p1: tailCtrl, p2: tailEnd, w: 6 + 1.5 * tf, color: body, edge: tailEdge, tip: tailTip, tipColor: tailTipColor, tipS: 0.85 + 0.8 * tf)

    for l in layout.legs.filter({ $0.back }) {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: Growth.ink)
    }

    c.fill(ellipse(bodyCX, bodyCY, bodyRX, bodyRY), with: .hex(body))
    if spec.cracks { drawCracks(into: &c, cx: bodyCX, cy: bodyCY, rx: bodyRX, ry: bodyRY, color: emberCol) }
    c.fill(ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), with: .hex(belly))
    if spec.plates {
        drawBellyPlates(into: &c, cx: bodyCX, cy: bodyCY + bodyRY * 0.3, rx: bodyRX * 0.55, ry: bodyRY * 0.6, line: "#C4B584")
    }

    c.stroke(
        Path(svg: "M\(bodyCX) \(bodyCY - bodyRY * 0.5) L\(headCX) \(headCY + headR * 0.4)"),
        with: .hex(body),
        style: StrokeStyle(lineWidth: headR * 0.95, lineCap: .round)
    )
    for l in layout.legs.filter({ !$0.back }) {
        drawLeg(into: &c, spec: l, w: legW, color: body, hoof: Growth.ink)
    }
    if spec.fierce {
        for l in layout.legs.filter({ !$0.back }) {
            drawClaws(into: &c, x: l.footX - 1.4, y: l.footY - 0.6, w: 4)
        }
    }

    drawCrest(into: &c, hx: headCX, hy: headCY, headR: headR, n: spec.crest, color: crestCol, edge: crestEdge)
    drawHorns(
        into: &c,
        hx: headCX,
        hy: headCY,
        headR: headR,
        h: spec.horn,
        variant: doubleHorns ? .double : .straight,
        color: hornCol,
        edge: hornEdge,
        tipDot: spec.goldTips ? "#FFD54F" : nil
    )
    c.fill(ellipse(headCX, headCY, headR, headR * 0.96), with: .hex(body))

    drawSnout(into: &c, hx: headCX, hy: headCY, headR: headR, color: belly)
    if spec.smoke { drawSmokePuffs(into: &c, hx: headCX, hy: headCY, headR: headR) }
    drawEyes(into: &c, cx: headCX, y: headCY - headR * 0.05, dx: headR * 0.4, r: eyeR, mood: mood, sleepy: false)
    drawCheeks(into: &c, cx: headCX, y: headCY + headR * 0.32, dx: headR * 0.62, r: headR * 0.13)
    drawMouth(into: &c, cx: headCX, y: mouthY, w: mouthW, mood: mood)
    if spec.fierce { drawFangs(into: &c, cx: headCX, y: mouthY + 0.4, w: mouthW * 1.15) }

    if flameS > 0 {
        if blue {
            drawFlamePuff(into: &c, x: headCX + headR * 0.3, y: headCY + headR * 0.6, s: flameS, rot: 125, outer: BLUE_OUTER, inner: BLUE_INNER)
        } else {
            drawFlamePuff(into: &c, x: headCX + headR * 0.3, y: headCY + headR * 0.6, s: flameS, rot: 125)
        }
        // legendary double breath — the blue flame's own growth beat
        if blue && stage >= 7 {
            drawFlamePuff(into: &c, x: headCX + headR * 0.05, y: headCY + headR * 0.72, s: flameS * 0.55, rot: 145, outer: BLUE_OUTER, inner: BLUE_INNER)
        }
    }

    // accessories over the body
    if stage >= 3 && has(A.goggles) {
        drawGoggles(into: &c, x: headCX, y: headCY - headR * 0.72, headR: headR)
    }
    // if the cape clasp already sits on the throat, the fang cord drops a touch
    if has(A.fang) {
        drawFangPendant(into: &c, x: anchor.neck.x, y: anchor.neck.y + (has(A.cape) ? 3 : 0), w: anchor.neck.w)
    }
    if has(A.cape) {
        drawCapeClasp(into: &c, x: anchor.neck.x, y: anchor.neck.y, w: anchor.neck.w)
    }
    // fixed-size coins/jewels — the QUANTITY grows with the stage. x is
    // clamped left of the back hoof: the wobbly stades splay their legs and
    // the hoof otherwise catches the gold (DA redline B1).
    if has(A.treasure) {
        let backFootL = layout.legs[0].footX - 4.6
        let tRight: Double = stage >= 7 ? 17.0 : stage >= 6 ? 8.6 : stage >= 3 ? 5.7 : 2.8
        let tLeft: Double = bodyCX - bodyRX - 5 - 4 * tf
        let tX = min(tLeft, backFootL - tRight - 1)
        drawTreasure(into: &c, stage: stage, x: tX, groundY: layout.feetY + 1)
    }

    if emberN > 0 {
        for i in 0..<emberN {
            let a = (Double(i) / Double(emberN)) * Double.pi * 2 + 0.8
            let r = 1.1 + Double(i % 3) * 0.55
            c.fill(
                ellipse(bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 9) - 4, r, r),
                with: .hex(emberCol, opacity: 0.55 + 0.15 * Double(i % 3))
            )
        }
    }
}

// MARK: - Shape helpers (`<ellipse>` is not path data — D2)

private func ellipse(_ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
}
