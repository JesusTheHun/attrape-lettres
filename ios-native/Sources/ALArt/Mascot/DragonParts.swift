import CoreGraphics
import Foundation
import SwiftUI
import ALCore

// Port of `src/mascot/dragonParts.tsx`.
//
// Kept as ONE file mirroring the TSX so the two can be read side by side;
// every `d` string is the TSX template copied verbatim into a Swift string
// interpolation (D2) and drawn in TSX statement order (D15).
//
// Not ported — design-run candidates unreachable from Dragon.tsx (spec §8.6,
// recorded as a deviation): `CloudWings`, `DomeFin`, `Bolt`, `RainCloud`,
// `Gem`, `AngularWings`, `FrillBand`, `DustPuffs`, `KnightHelmet`, `Shield`,
// `ChestArmor`, `EggShield`, the `Horns` "curly" variant, the `SpadeTail`
// "bolt" tip and its `gem` inset, and the `Crest` `gems` option (`Gem` and the
// METAL/METAL_EDGE constants are reachable only from those). `TailClub` IS
// reachable (tailStyle "club") and is here.

/**
 * Dragon part library — everything Braise wears and breathes, kept out of
 * Dragon.tsx so the rig file stays a readable STAGE_SPEC timeline.
 * Pure SVG, numeric props, kawaii house style.
 */

let EGG_FILL = "#FFF9EE"
let EGG_EDGE = "#E3D2BA"

/* -- The stade-0 egg ----------------------------------------------------- */

/** Cracked egg cup, fixed to the stade-0 lying layout: jagged rim rises at the
 * sides and dips in FRONT of the resting face, so the newborn peeks out of the
 * broken shell. Drawn OVER the body/chin (the dragon is IN the egg). */
let CUP_PATH =
    "M24 68 L28 63 L31.5 69 L35.5 64 L39 70 L43 65 " +
    "L45.5 84 L49 88.5 L52.5 84.5 L56 88.5 L59.5 84.5 L63 88.5 L66.5 84.5 L70 88.5 L73.5 84.5 L76.5 88 " +
    "L79 70 L82.5 74.5 L86 68.5 " +
    "C90 80 89 90 78 94.5 C68 98 42 98 32 94 C23.5 90.5 21 78 24 68 Z"

// NB: `uid` from the TSX prop list has no port — the clipPath is a value here,
// not a document-namespace def (spec §4).
public func drawEggCup(into c: inout SVGCanvas, speckle: String, suitColor: String? = nil) {
    c.fill(Path(svg: CUP_PATH), with: .hex(EGG_FILL))
    c.stroke(Path(svg: CUP_PATH), with: .hex(EGG_EDGE), style: StrokeStyle(lineWidth: 1.2, lineJoin: .round))
    if let suitColor {
        c.save()
        c.clip(to: Path(svg: CUP_PATH))
        c.fill(Path(CGRect(x: 20, y: 83, width: 70, height: 16)), with: .hex(suitColor))
        for x in [40.0, 56.0] {
            c.stroke(
                Path(svg: "M\(x) 82 q 2.6 6 0 14"),
                with: .hex("#FFF6EE", opacity: 0.9),
                style: StrokeStyle(lineWidth: 2)
            )
        }
        c.restore()
    }
    // crack lines + speckles
    c.stroke(Path(svg: "M39 92 L42.5 84.5 L40 78.5"), with: .hex(EGG_EDGE, opacity: 0.7), style: StrokeStyle(lineWidth: 1))
    c.stroke(Path(svg: "M78 92.5 L75 86.5"), with: .hex(EGG_EDGE, opacity: 0.6), style: StrokeStyle(lineWidth: 1))
    c.fill(dpCircle(32, 84, 1.3), with: .hex(speckle))
    c.fill(dpCircle(71, 92, 1.2), with: .hex(speckle))
    c.fill(dpCircle(47, 93.5, 1.1), with: .hex(speckle))
    c.fill(dpCircle(82, 81, 1.1), with: .hex(speckle))
}

/** Half-shell cap worn on the head (the classic hatchling hat). */
public func drawShellCap(into c: inout SVGCanvas, x: Double, y: Double, s: Double, tilt: Double, speckle: String) {
    c.save()
    c.translate(x, y)
    c.rotate(degrees: tilt)
    c.scale(s)
    let d = "M-12 1 C-11 -8 -5 -12.5 0 -12.5 C5 -12.5 11 -8 12 1 L8 5.5 L4 1.5 L0 5.8 L-4 1.5 L-8 5.5 Z"
    c.fill(Path(svg: d), with: .hex(EGG_FILL))
    c.stroke(Path(svg: d), with: .hex(EGG_EDGE), style: StrokeStyle(lineWidth: 1.1, lineJoin: .round))
    c.fill(dpCircle(3, -6, 1.2), with: .hex(speckle))
    c.fill(dpCircle(-4.5, -3.5, 0.9), with: .hex(speckle))
    c.restore()
}

/** Small shard of shell resting on the back/rump at stade 1. */
public func drawShellShard(into c: inout SVGCanvas, x: Double, y: Double, s: Double, tilt: Double, speckle: String) {
    c.save()
    c.translate(x, y)
    c.rotate(degrees: tilt)
    c.scale(s)
    let d = "M0 0 L3 -6 L6.5 -1 L10 -5 L12 1 C8 4 3 4 0 0 Z"
    c.fill(Path(svg: d), with: .hex(EGG_FILL))
    c.stroke(Path(svg: d), with: .hex(EGG_EDGE), style: StrokeStyle(lineWidth: 1, lineJoin: .round))
    c.fill(dpCircle(6, -1, 0.9), with: .hex(speckle))
    c.restore()
}

/* -- Wings ---------------------------------------------------------------- */

/** Scalloped bat-style dragon wings, drawn BEHIND the body. Outlined + finger
 * ridges so they stay legible against any body colour. */
public func drawBatWings(into c: inout SVGCanvas, cx: Double, cy: Double, s: Double, membrane: String, edge: String) {
    if s <= 0 { return }
    func wing(_ d: Double) {
        let sx = cx + d * 9
        let sy = cy
        let tx = sx + d * 21 * s
        let ty = sy - 17 * s
        let p1x = sx + d * 16 * s
        let p1y = sy - 4 * s
        let p2x = sx + d * 8.5 * s
        let p2y = sy - 0.5 * s
        let path =
            "M\(sx) \(sy) C\(sx + d * 3 * s) \(sy - 12 * s) \(sx + d * 11 * s) \(sy - 18 * s) \(tx) \(ty) " +
            "Q\((tx + p1x) / 2 - d * 3 * s) \((ty + p1y) / 2) \(p1x) \(p1y) " +
            "Q\((p1x + p2x) / 2 - d * 2 * s) \((p1y + p2y) / 2 + 1.5 * s) \(p2x) \(p2y) " +
            "Q\((p2x + sx) / 2) \(sy + 1.5 * s) \(sx) \(sy) Z"
        c.fill(Path(svg: path), with: .hex(membrane))
        c.stroke(Path(svg: path), with: .hex(edge), style: StrokeStyle(lineWidth: 1.1, lineJoin: .round))
        c.group(opacity: 0.5) { g in
            g.stroke(
                Path(svg: "M\(sx + d * 2 * s) \(sy - 2 * s) L\(tx - d * 2 * s) \(ty + 2 * s)"),
                with: .hex(edge),
                style: StrokeStyle(lineWidth: 0.8)
            )
            g.stroke(
                Path(svg: "M\(sx + d * 2 * s) \(sy - s) L\(p1x - d * 1.5 * s) \(p1y - s)"),
                with: .hex(edge),
                style: StrokeStyle(lineWidth: 0.8)
            )
        }
    }
    wing(-1)
    wing(1)
}

/* -- Tail ----------------------------------------------------------------- */

/// The reachable arms of the TSX `tip` union: `"spade" | "club" | "flame"`.
/// `"bolt"` and `"none"` have no runtime path from Dragon.tsx (spec §8.6); an
/// unknown `tailStyle` string from an old profile maps to `nil`, which draws
/// the curve with no tip — exactly what the TSX conditionals do when no arm
/// matches.
public enum DragonTailTip: String {
    case spade
    case club
    case flame
}

/** Quadratic tail stroke ending in a spade / bolt / mace-club / flame tip that
 * follows the curve's tangent. Optional gem set into the spade (candidate C). */
// NB: the `gem` inset and the "bolt" arm are not ported (spec §8.6).
public func drawSpadeTail(
    into c: inout SVGCanvas,
    p0: (Double, Double),
    p1: (Double, Double),
    p2: (Double, Double),
    w: Double,
    color: String,
    /** Outline colour — a same-as-body tail vanishes into the silhouette without it. */
    edge: String? = nil,
    tip: DragonTailTip? = .spade,
    tipColor: String? = nil,
    tipS: Double = 1
) {
    let ang = atan2(p2.1 - p1.1, p2.0 - p1.0) * 180 / Double.pi
    let curve = "M\(p0.0) \(p0.1) Q\(p1.0) \(p1.1) \(p2.0) \(p2.1)"
    if let edge {
        c.stroke(Path(svg: curve), with: .hex(edge), style: StrokeStyle(lineWidth: w + 2.4, lineCap: .round))
    }
    c.stroke(Path(svg: curve), with: .hex(color), style: StrokeStyle(lineWidth: w, lineCap: .round))
    switch tip {
    case .spade:
        c.save()
        c.translate(p2.0, p2.1)
        c.rotate(degrees: ang)
        c.scale(tipS)
        let d = "M7 0 L-2.5 -5 Q-0.5 0 -2.5 5 Z"
        c.fill(Path(svg: d), with: .hex(tipColor ?? color))
        // strokeWidth={edge ? 1 : 0} — width 0 records nothing (SVGCanvas.stroke).
        c.stroke(
            Path(svg: d),
            with: edge.map { .hex($0) } ?? .none,
            style: StrokeStyle(lineWidth: edge != nil ? 1 : 0, lineJoin: .round)
        )
        c.restore()
    case .club:
        drawTailClub(into: &c, x: p2.0, y: p2.1, s: tipS, color: tipColor ?? color, edge: edge ?? Growth.ink)
    case .flame:
        c.fill(dpCircle(p2.0, p2.1, w * 0.55), with: .hex(color))
        c.stroke(
            dpCircle(p2.0, p2.1, w * 0.55),
            with: edge.map { .hex($0) } ?? .none,
            style: StrokeStyle(lineWidth: edge != nil ? 1 : 0)
        )
        drawFlamePuff(into: &c, x: p2.0, y: p2.1, s: tipS * 0.42, rot: ang + 90)
    case nil:
        break
    }
}

/* -- Head gear ------------------------------------------------------------ */

/// The reachable arms of the TSX `variant` union — "curly" is not ported
/// (spec §8.6; Dragon.tsx only ever selects straight or double).
public enum DragonHornVariant: String {
    case straight
    case double
}

/** Two little horns on the dome. Straight cones, curled ram horns, or the
 * double pair (a big cone + a smaller one in front — the classic dragon crown
 * of four horns). */
public func drawHorns(
    into c: inout SVGCanvas,
    hx: Double,
    hy: Double,
    headR: Double,
    h: Double,
    variant: DragonHornVariant = .straight,
    color: String,
    edge: String,
    tipDot: String? = nil
) {
    if h <= 0 { return }
    func horn(_ d: Double) {
        let bx = hx + d * headR * 0.52
        let by = hy - headR * 0.72
        if variant == .double {
            // Small pair OUTSIDE and below the big one, at the silhouette edge —
            // tucked between the horns it drowns in the head fill and the crest.
            func cone(_ cx: Double, _ cy: Double, _ hh: Double, _ ww: Double, _ lean: Double) -> String {
                "M\(cx - d * ww) \(cy + 1.5) Q\(cx + d * hh * 0.02) \(cy - hh * 0.6) \(cx + d * hh * lean) \(cy - hh) Q\(cx + d * (ww + hh * 0.18)) \(cy - hh * 0.4) \(cx + d * ww) \(cy + 1.5) Z"
            }
            let w = headR * 0.12 + h * 0.055
            let small = cone(hx + d * headR * 0.82, hy - headR * 0.42, h * 0.62, w * 0.72, 0.85)
            c.fill(Path(svg: small), with: .hex(color))
            c.stroke(Path(svg: small), with: .hex(edge), style: StrokeStyle(lineWidth: 0.8, lineJoin: .round))
            let big = cone(bx, by, h, w, 0.5)
            c.fill(Path(svg: big), with: .hex(color))
            c.stroke(Path(svg: big), with: .hex(edge), style: StrokeStyle(lineWidth: 0.8, lineJoin: .round))
            if let tipDot {
                c.fill(dpCircle(bx + d * h * 0.5, by - h, 1.7), with: .hex(tipDot))
                c.stroke(dpCircle(bx + d * h * 0.5, by - h, 1.7), with: .hex(edge), style: StrokeStyle(lineWidth: 0.5))
            }
            return
        }
        let w = headR * 0.13 + h * 0.06
        let tx = bx + d * h * 0.45
        let ty = by - h
        let d2 = "M\(bx - d * w) \(by + 1.5) Q\(bx + d * h * 0.02) \(by - h * 0.6) \(tx) \(ty) Q\(bx + d * (w + h * 0.16)) \(by - h * 0.45) \(bx + d * w) \(by + 1.5) Z"
        c.fill(Path(svg: d2), with: .hex(color))
        c.stroke(Path(svg: d2), with: .hex(edge), style: StrokeStyle(lineWidth: 0.8, lineJoin: .round))
        if let tipDot {
            c.fill(dpCircle(tx, ty, 1.7), with: .hex(tipDot))
            c.stroke(dpCircle(tx, ty, 1.7), with: .hex(edge), style: StrokeStyle(lineWidth: 0.5))
        }
    }
    horn(-1)
    horn(1)
}

/** Row of crest bumps fanned along the head dome, between the horns.
 * `round` swaps pointy triangles for soft bubbles; `gems` tips each spike;
 * `scale` grows the bumps (bone back-plates are big rounded ones). */
// NB: the `gems` option is not ported — unreachable from Dragon.tsx and the
// only in-rig consumer of `Gem` (spec §8.6).
public func drawCrest(
    into c: inout SVGCanvas,
    hx: Double,
    hy: Double,
    headR: Double,
    n: Int,
    round: Bool = false,
    color: String,
    edge: String,
    scale: Double = 1
) {
    if n <= 0 { return }
    for i in 0..<n {
        let t = n == 1 ? 0.5 : Double(i) / Double(n - 1)
        let a = ((t - 0.5) * 76 * Double.pi) / 180
        let rx = sin(a)
        let ry = -cos(a)
        let bx = hx + rx * headR * 0.9
        let by = hy + ry * headR * 0.88
        let hgt = headR * (0.36 - 0.08 * abs(t - 0.5) * 2) * scale
        let px = -ry
        let py = rx
        let wHalf = headR * 0.12 * scale
        if round {
            c.fill(dpCircle(bx + rx * hgt * 0.42, by + ry * hgt * 0.42, headR * 0.14 * scale), with: .hex(color))
            c.stroke(dpCircle(bx + rx * hgt * 0.42, by + ry * hgt * 0.42, headR * 0.14 * scale), with: .hex(edge), style: StrokeStyle(lineWidth: 0.8))
            continue
        }
        let tx = bx + rx * hgt
        let ty = by + ry * hgt
        let d = "M\(bx - px * wHalf) \(by - py * wHalf) L\(tx) \(ty) L\(bx + px * wHalf) \(by + py * wHalf) Z"
        c.fill(Path(svg: d), with: .hex(color))
        c.stroke(Path(svg: d), with: .hex(edge), style: StrokeStyle(lineWidth: 0.8, lineJoin: .round))
    }
}

/* -- Belly / scales -------------------------------------------------------- */

/** Reptile plate lines across the belly ellipse (chord-width, no clip needed). */
public func drawBellyPlates(into c: inout SVGCanvas, cx: Double, cy: Double, rx: Double, ry: Double, line: String) {
    c.group(opacity: 0.6) { g in
        for k in [-0.25, 0.12, 0.48] {
            let halfW = rx * (1 - k * k).squareRoot() * 0.9
            g.stroke(
                Path(svg: "M\(cx - halfW) \(cy + ry * k) Q\(cx) \(cy + ry * k + 2.6) \(cx + halfW) \(cy + ry * k)"),
                with: .hex(line),
                style: StrokeStyle(lineWidth: 1.3, lineCap: .round)
            )
        }
    }
}

/* -- Fire / storm / treasure bits ----------------------------------------- */

/** Small two-tone kawaii flame (points up at rot=0). Recolourable for the
 * legendary blue breath. */
public func drawFlamePuff(
    into c: inout SVGCanvas,
    x: Double,
    y: Double,
    s: Double,
    rot: Double = 0,
    outer: String = "#FF7043",
    inner: String = "#FFE082"
) {
    c.save()
    c.translate(x, y)
    c.rotate(degrees: rot)
    c.scale(s)
    c.fill(Path(svg: "M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z"), with: .hex(outer))
    c.fill(Path(svg: "M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z"), with: .hex(inner))
    c.restore()
}

/* -- Boy-coded parts (reboot) ---------------------------------------------- */

/** Spiky mace-ball tail tip (drawn at p2 of a SpadeTail with tip="none"). */
public func drawTailClub(into c: inout SVGCanvas, x: Double, y: Double, s: Double, color: String, edge: String) {
    for i in 0..<7 {
        let a = (Double(i) / 7) * Double.pi * 2 - Double.pi / 2
        let bx = x + cos(a) * 3.1 * s
        let by = y + sin(a) * 3.1 * s
        let tx = x + cos(a) * 5.4 * s
        let ty = y + sin(a) * 5.4 * s
        let px = -sin(a) * 1.15 * s
        let py = cos(a) * 1.15 * s
        let d = "M\(bx - px) \(by - py) L\(tx) \(ty) L\(bx + px) \(by + py) Z"
        c.fill(Path(svg: d), with: .hex(color))
        c.stroke(Path(svg: d), with: .hex(edge), style: StrokeStyle(lineWidth: 0.7, lineJoin: .round))
    }
    c.fill(dpCircle(x, y, 3.4 * s), with: .hex(color))
    c.stroke(dpCircle(x, y, 3.4 * s), with: .hex(edge), style: StrokeStyle(lineWidth: 0.9))
    c.fill(dpCircle(x - s, y - s, 0.9 * s), with: .hex("#FFFFFF", opacity: 0.45))
}

/** Glowing lava/charge cracks — short zigzags on the body flanks. */
public func drawCracks(into c: inout SVGCanvas, cx: Double, cy: Double, rx: Double, ry: Double, color: String) {
    func zig(_ x: Double, _ y: Double, _ d: Double) -> String {
        "M\(x) \(y) l\(2.4 * d) -2.6 l\(2.4 * d) 2.6 l\(2.4 * d) -2.6"
    }
    let style = StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)
    c.stroke(Path(svg: zig(cx - rx * 0.88, cy - ry * 0.25, 1)), with: .hex(color), style: style)
    c.stroke(Path(svg: zig(cx + rx * 0.35, cy - ry * 0.55, 1)), with: .hex(color), style: style)
    c.stroke(Path(svg: zig(cx - rx * 0.5, cy + ry * 0.42, 1)), with: .hex(color, opacity: 0.85), style: style)
}

/** Two little smoke curls drifting OUTWARD from the nostrils — mostly sideways
 * so they clear the eyes (rising straight up read as tears next to them). */
public func drawSmokePuffs(into c: inout SVGCanvas, hx: Double, hy: Double, headR: Double) {
    func puff(_ d: Double) {
        let x = hx + d * headR * 0.24
        let y = hy + headR * 0.3
        c.group(opacity: 0.85) { g in
            g.fill(dpCircle(x + d * headR * 0.28, y - 1, 1.4), with: .hex("#C3C7CE"))
            g.fill(dpCircle(x + d * headR * 0.52, y - 3, 1.9), with: .hex("#C3C7CE"))
            g.fill(dpCircle(x + d * headR * 0.82, y - 5.6, 2.5), with: .hex("#C3C7CE", opacity: 0.8))
        }
    }
    puff(-1)
    puff(1)
}

/** White claw nicks on a foot (drawn over the dark hoof). */
public func drawClaws(into c: inout SVGCanvas, x: Double, y: Double, w: Double) {
    c.group(opacity: 0.95) { g in
        for f in [-0.3, 0.12, 0.54] {
            g.fill(Path(svg: "M\(x + f * w) \(y - 1) l1.1 2.6 l1.1 -2.6 Z"), with: .hex("#FFFFFF"))
        }
    }
}

/** Two tiny fangs peeking from the mouth corners. */
public func drawFangs(into c: inout SVGCanvas, cx: Double, y: Double, w: Double) {
    let style = StrokeStyle(lineWidth: 0.35)
    let left = "M\(cx - w) \(y - 0.6) l0.9 3 l1.5 -2.4 Z"
    let right = "M\(cx + w) \(y - 0.6) l-0.9 3 l-1.5 -2.4 Z"
    c.fill(Path(svg: left), with: .hex("#FFFFFF"))
    c.stroke(Path(svg: left), with: .hex(Growth.ink), style: style)
    c.fill(Path(svg: right), with: .hex("#FFFFFF"))
    c.stroke(Path(svg: right), with: .hex(Growth.ink), style: style)
}

/* -- Knight accessories (items run, candidate A) ---------------------------- */

let CAPE = "#C64F4F"
let CAPE_EDGE = "#8F3535"
// NB: METAL / METAL_EDGE not ported — only the skipped KnightHelmet used them.
let GOLD_TRIM = "#F2C14E"
let GOLD_TRIM_EDGE = "#B07E1E"

/** Hero cape hanging from the shoulders, drawn BEHIND the body. */
public func drawCapeBack(into c: inout SVGCanvas, cx: Double, topY: Double, w: Double, h: Double) {
    let d = "M\(cx - w * 0.62) \(topY) Q\(cx) \(topY - 3) \(cx + w * 0.62) \(topY) C\(cx + w * 0.9) \(topY + h * 0.55) \(cx + w * 0.82) \(topY + h * 0.9) \(cx + w * 0.7) \(topY + h) L\(cx + w * 0.28) \(topY + h - 2) L\(cx) \(topY + h) L\(cx - w * 0.28) \(topY + h - 2) L\(cx - w * 0.7) \(topY + h) C\(cx - w * 0.82) \(topY + h * 0.9) \(cx - w * 0.9) \(topY + h * 0.55) \(cx - w * 0.62) \(topY) Z"
    c.fill(Path(svg: d), with: .hex(CAPE))
    c.stroke(Path(svg: d), with: .hex(CAPE_EDGE), style: StrokeStyle(lineWidth: 1.1, lineJoin: .round))
}

/** The cape's gold clasp + throat cord, drawn OVER the chest. */
public func drawCapeClasp(into c: inout SVGCanvas, x: Double, y: Double, w: Double) {
    c.stroke(
        Path(svg: "M\(x - w) \(y - 1) Q\(x) \(y + 2.5) \(x + w) \(y - 1)"),
        with: .hex(GOLD_TRIM),
        style: StrokeStyle(lineWidth: 1.8, lineCap: .round)
    )
    c.fill(dpCircle(x, y + 1.6, 2.5), with: .hex(GOLD_TRIM))
    c.stroke(dpCircle(x, y + 1.6, 2.5), with: .hex(GOLD_TRIM_EDGE), style: StrokeStyle(lineWidth: 0.8))
    c.fill(dpCircle(x - 0.7, y + 0.9, 0.8), with: .hex("#FFFDF4", opacity: 0.8))
}

/* -- Accessories ----------------------------------------------------------- */

/* -- The treasure hoard ------------------------------------------------------
 * Design rule (user): a coin or a jewel NEVER changes size with growth — only
 * the QUANTITY does: 0-2 a single gold coin · 3-6 gold + jewellery · 7-9 the
 * full hoard, jewels set with precious stones.
 * Art direction (DA jeunesse redlines): three golds and NO olive outlines —
 * model with flat tones like the dragon itself; coins are cylinders (edge
 * offset), the upright coin wears an engraved star; the heap is a tight 3:2
 * shingled mound (never a ribbon); one shared ground shadow anchors the hoard
 * in the dragon's world; gems are two-tone with tone-on-tone dark rims (white
 * halos die on the cream background); sparkles are GOLD, white only on gold.
 * At 7-9 the hoard gets its wooden chest: same three-tone modelling in wood,
 * mound brimming over the rim, and everything that overflows (upright coin,
 * spilled coins, ring) tumbles out on the DRAGON's side — he guards it. */

let OR_LIGHT = "#FFE08A"
let OR = "#FFC94D"
let OR_DEEP = "#E09B3A"
let OR_EDGE = "#C9822E"
let OR_PALE = "#FFDB6E"
let RUBY = "#E0533B"
let SAPPHIRE = "#5E7EB5"
let EMERALD = "#4CAF7D"
let AMETHYST = "#9575CD"
let GEM_DARK: [String: String] = [
    RUBY: "#B23B28",
    SAPPHIRE: "#46618F",
    EMERALD: "#37835C",
    AMETHYST: "#74569F",
]

let COIN_R = 2.8
let COIN_RY = 1.15

/** Lying coin = a CYLINDER (edge slice under the face), not an outlined pill. */
func drawTCoin(into c: inout SVGCanvas, cx: Double, cy: Double) {
    c.fill(dpEllipse(cx, cy + 0.7, COIN_R, COIN_RY), with: .hex(OR_DEEP))
    c.fill(dpEllipse(cx, cy, COIN_R, COIN_RY), with: .hex(OR))
    c.stroke(dpEllipse(cx, cy, 1.64, 0.67), with: .hex(OR_DEEP), style: StrokeStyle(lineWidth: 0.45))
    c.fill(dpEllipse(cx - 0.95, cy - 0.35, 0.7, 0.3), with: .hex(OR_LIGHT))
}

/** The storybook coin: upright, engraved star, shaded rim — leans on the heap
 * once there is one (`lean` in degrees, pivot at its ground contact). */
func drawTUprightCoin(into c: inout SVGCanvas, cx: Double, cy: Double, lean: Double = 0) {
    c.save()
    c.rotate(degrees: lean, about: CGPoint(x: cx, y: cy + COIN_R))
    c.fill(dpCircle(cx, cy, COIN_R), with: .hex(OR))
    c.stroke(dpCircle(cx, cy, COIN_R), with: .hex(OR_EDGE), style: StrokeStyle(lineWidth: 0.35))
    c.fill(dpCircle(cx, cy, 2.18), with: .hex(OR_PALE))
    // bottom-right shading crescent between rim and inner disc
    c.fill(
        Path(svg: "M\(cx + 2.63) \(cy + 0.96) A2.8 2.8 0 0 1 \(cx - 0.49) \(cy + 2.76) L\(cx - 0.38) \(cy + 2.15) A2.18 2.18 0 0 0 \(cx + 2.05) \(cy + 0.75) Z"),
        with: .hex(OR_DEEP, opacity: 0.5)
    )
    c.fill(
        Path(svg: "M\(cx) \(cy - 1.58) L\(cx + 0.49) \(cy - 0.49) L\(cx + 1.58) \(cy) L\(cx + 0.49) \(cy + 0.49) L\(cx) \(cy + 1.58) L\(cx - 0.49) \(cy + 0.49) L\(cx - 1.58) \(cy) L\(cx - 0.49) \(cy - 0.49) Z"),
        with: .hex(OR_DEEP)
    )
    c.fill(dpCircle(cx - 0.92, cy - 0.92, 0.48), with: .hex("#FFF6D8"))
    c.restore()
}

/** Two-tone faceted gem, tone-on-tone dark rim (no white halo on cream bg). */
// NB: `GEM_DARK[color]` can miss for a colour outside the four gems — the TSX
// then renders the facet with SVG's default fill (black) and no rim stroke.
// Unreachable from the slot tables, but mirrored rather than "fixed".
func drawTGem(into c: inout SVGCanvas, x: Double, y: Double, s: Double, color: String) {
    let dark = GEM_DARK[color]
    let body = "M\(x) \(y - s) L\(x + s * 0.85) \(y) L\(x) \(y + s) L\(x - s * 0.85) \(y) Z"
    c.fill(Path(svg: body), with: .hex(color))
    c.stroke(Path(svg: body), with: dark.map { .hex($0) } ?? .none, style: StrokeStyle(lineWidth: 0.3, lineJoin: .round))
    c.fill(
        Path(svg: "M\(x - s * 0.85) \(y) L\(x) \(y + s) L\(x + s * 0.85) \(y) Z"),
        with: .hex(dark ?? "#000000", opacity: 0.35)
    )
    c.fill(dpCircle(x - s * 0.2, y - s * 0.3, s * 0.2), with: .hex("#FFFFFF", opacity: 0.85))
}

/** Gold ring: golden band with rim light + contact shade, mounted stone at 7+. */
func drawTRing(into c: inout SVGCanvas, cx: Double, cy: Double, gem: Bool = false) {
    c.stroke(dpCircle(cx, cy, 1.9), with: .hex(OR), style: StrokeStyle(lineWidth: 1.4))
    c.stroke(
        Path(svg: "M\(cx - 1.9) \(cy) A1.9 1.9 0 0 0 \(cx + 1.9) \(cy)"),
        with: .hex(OR_DEEP),
        style: StrokeStyle(lineWidth: 0.5)
    )
    c.stroke(
        Path(svg: "M\(cx - 1.9) \(cy) A1.9 1.9 0 0 1 \(cx) \(cy - 1.9)"),
        with: .hex(OR_LIGHT),
        style: StrokeStyle(lineWidth: 0.4)
    )
    if gem { drawTGem(into: &c, x: cx, y: cy - 2.6, s: 1, color: RUBY) }
}

/** Gold crown: base band + three peaks with light-gold balls + one set ruby.
 * `y` is the band's bottom centre; tilted a touch (a crooked crown is cuter). */
func drawTCrown(into c: inout SVGCanvas, x: Double, y: Double) {
    let s = 1.15
    let bandTop = y - 1.0
    c.save()
    c.rotate(degrees: -6, about: CGPoint(x: x, y: y - 1.7))
    c.fill(
        Path(svg: "M\(x - 2.8 * s) \(bandTop + 0.1) L\(x - 1.87 * s) \(bandTop - 1.9 * s) L\(x - 0.93 * s) \(bandTop - 0.4 * s) L\(x) \(bandTop - 2.55 * s) L\(x + 0.93 * s) \(bandTop - 0.4 * s) L\(x + 1.87 * s) \(bandTop - 1.9 * s) L\(x + 2.8 * s) \(bandTop + 0.1) Z"),
        with: .hex(OR)
    )
    c.fill(dpCircle(x - 1.87 * s, bandTop - 1.9 * s, 0.5), with: .hex(OR_PALE))
    c.fill(dpCircle(x, bandTop - 2.55 * s, 0.5), with: .hex(OR_PALE))
    c.fill(dpCircle(x + 1.87 * s, bandTop - 1.9 * s, 0.5), with: .hex(OR_PALE))
    c.fill(Path(roundedRect: CGRect(x: x - 2.8 * s, y: bandTop, width: 5.6 * s, height: 1.0), cornerRadius: 0.4), with: .hex(OR))
    c.stroke(
        Path(svg: "M\(x - 2.4 * s) \(y - 0.12) L\(x + 2.4 * s) \(y - 0.12)"),
        with: .hex(OR_DEEP),
        style: StrokeStyle(lineWidth: 0.4, lineCap: .round)
    )
    c.fill(dpCircle(x, y - 0.5, 0.6), with: .hex(RUBY))
    c.restore()
}

/* Wooden chest — three wood tones, mirroring the three golds (plus the named
 * base-shade token so no blended fourth tone hides in an opacity). */
let WOOD_LT = "#C9985F"
let WOOD = "#AF7A45"
let WOOD_DK = "#8A5C33"
let WOOD_SHADE = "#9A6A3E"

/** Open lid, tipped back a touch (crooked like the crown — cuter). We see its
 * wooden edge and the darker inside face. Drawn BEHIND the coin mound. */
func drawTChestLid(into c: inout SVGCanvas, x: Double, rimY: Double) {
    // Tall enough that the lid + dark inside stay readable ABOVE the full s9
    // mound (rows top out at rimY-4.95) — the open-lid read must survive the
    // climax stade. The low corner sinks behind the front wall, no overhang.
    c.save()
    c.rotate(degrees: -9, about: CGPoint(x: x, y: rimY))
    c.fill(
        Path(svg: "M\(x - 7.2) \(rimY - 0.2) L\(x - 7.2) \(rimY - 6.2) Q\(x - 7.2) \(rimY - 8.4) \(x - 4.6) \(rimY - 8.4) L\(x + 4.6) \(rimY - 8.4) Q\(x + 7.2) \(rimY - 8.4) \(x + 7.2) \(rimY - 6.2) L\(x + 7.2) \(rimY - 0.2) Z"),
        with: .hex(WOOD)
    )
    c.fill(
        Path(svg: "M\(x - 6.3) \(rimY - 0.2) L\(x - 6.3) \(rimY - 5.9) Q\(x - 6.3) \(rimY - 7.3) \(x - 4.6) \(rimY - 7.3) L\(x + 4.6) \(rimY - 7.3) Q\(x + 6.3) \(rimY - 7.3) \(x + 6.3) \(rimY - 5.9) L\(x + 6.3) \(rimY - 0.2) Z"),
        with: .hex(WOOD_DK)
    )
    c.restore()
}

/** Chest front wall + gold straps + lock. Drawn AFTER the mound so the bottom
 * coin row sinks behind the rim — the chest reads as FULL, not decorated. */
func drawTChestBody(into c: inout SVGCanvas, x: Double, rimY: Double, groundY: Double) {
    let h = groundY - rimY
    c.fill(Path(roundedRect: CGRect(x: x - 7.5, y: rimY, width: 15, height: h), cornerRadius: 1.1), with: .hex(WOOD))
    c.fill(Path(roundedRect: CGRect(x: x - 7.3, y: groundY - 1.4, width: 14.6, height: 1.4), cornerRadius: 0.7), with: .hex(WOOD_SHADE))
    c.fill(Path(roundedRect: CGRect(x: x - 7.5, y: rimY, width: 15, height: 1.1), cornerRadius: 0.55), with: .hex(WOOD_LT))
    c.fill(Path(CGRect(x: x - 5.0, y: rimY, width: 1.7, height: h)), with: .hex(OR))
    c.fill(Path(CGRect(x: x + 3.3, y: rimY, width: 1.7, height: h)), with: .hex(OR))
    c.fill(Path(CGRect(x: x - 5.0, y: rimY, width: 1.7, height: 1.1)), with: .hex(OR_DEEP, opacity: 0.5))
    c.fill(Path(CGRect(x: x + 3.3, y: rimY, width: 1.7, height: 1.1)), with: .hex(OR_DEEP, opacity: 0.5))
    c.fill(Path(roundedRect: CGRect(x: x - 1.5, y: rimY + 1.9, width: 3, height: 3.4), cornerRadius: 1), with: .hex(OR))
    c.fill(dpCircle(x, rimY + 3.1, 0.55), with: .hex(OR_EDGE))
    c.fill(Path(svg: "M\(x) \(rimY + 3.3) L\(x - 0.45) \(rimY + 4.5) L\(x + 0.45) \(rimY + 4.5) Z"), with: .hex(OR_EDGE))
}

/** Strand of little gold pearls draped over the chest's LEFT front corner —
 * the "it overflows" storytelling beat, joins at stade 8. */
func drawTBeads(into c: inout SVGCanvas, x: Double, rimY: Double) {
    // A strand, not rivets: the thread ties the pearls together, the first two
    // sit on the rim (it climbs OUT of the chest) and the last one touches the
    // ground — that contact is the "it overflows" read.
    let pts: [(Double, Double)] = [
        (-4.6, -0.9), (-5.2, -0.5), (-5.7, -0.2), (-6.3, 0.9), (-6.7, 2.1), (-6.9, 3.3), (-6.8, 4.5), (-6.6, 5.9),
    ]
    c.stroke(
        Path(svg: "M\(x + pts[0].0) \(rimY + pts[0].1) " + pts.dropFirst().map { dx, dy in "L\(x + dx) \(rimY + dy)" }.joined(separator: " ")),
        with: .hex(OR_EDGE),
        style: StrokeStyle(lineWidth: 0.35, lineJoin: .round)
    )
    for (dx, dy) in pts {
        c.fill(dpCircle(x + dx, rimY + dy, 0.85), with: .hex(OR_PALE))
        c.stroke(dpCircle(x + dx, rimY + dy, 0.85), with: .hex(OR_EDGE), style: StrokeStyle(lineWidth: 0.25))
    }
}

/** Mound slots INSIDE the chest, dy relative to the rim. Base row fills first —
 * a treasure chest always reads full; growth adds the rows that overflow. */
let CHEST_SLOTS: [(Double, Double)] = [
    (0, -0.4), (-2.9, -0.3), (2.9, -0.3), (-5.4, -0.1), (5.4, -0.1),
    (-1.45, -2.1), (1.45, -2.1), (-4.2, -1.9), (4.2, -1.9),
    (0, -3.8), (-2.8, -3.6), (2.8, -3.6),
]

/** Coins tumbled out on the ground, dragon's side, dy relative to ground.
 * Each stays ≥40% visible beside the hero coin — a spill nobody can see is
 * a spill that does not exist: s7 against the wall, s8 behind the ring,
 * s9 perched on the wall corner. */
let SPILL_SLOTS: [(Double, Double)] = [
    (7.4, -1.1), (12.8, -1.8), (6.8, -2.9),
]

/** Gems for the chest stages — on the mound, then one on the spill pile.
 * dy relative to groundY (rim sits at -6.6). */
let CHEST_GEM_SLOTS: [(Double, Double, String)] = [
    (1.5, -9.9, RUBY),
    (-3.0, -9.7, SAPPHIRE),
    (4.6, -9.2, EMERALD),
    (-1.4, -11.5, AMETHYST),
    (6.2, -7.2, SAPPHIRE),
]

/** Heap slots — tight 2.9/1.8 shingle so any N reads as a 3:2 mound, never a
 * ribbon. Fill order grows a balanced pyramid (centre out, up before wide). */
let COIN_SLOTS: [(Double, Double)] = [
    (0, -1.1), (-2.9, -1.1), (-1.45, -2.9),
    (2.9, -1.1), (1.45, -2.9),
    (-5.8, -1.1), (5.8, -1.1),
    (0, -4.7),
    (-4.35, -2.9), (4.35, -2.9),
    (-2.9, -4.7), (2.9, -4.7),
    (0, -6.5),
]

/** Loose gems, in appearance order — every one seated on a coin that is filled
 * by the stade it appears at, or nestled against the mound's base. */
let GEM_SLOTS: [(Double, Double, String)] = [
    (3.1, -3.3, RUBY),
    (-3.1, -3.3, SAPPHIRE),
    (8.0, -1.2, EMERALD),
    (-6.6, -1.0, AMETHYST),
    (6.0, -3.2, SAPPHIRE),
]

struct TSpec {
    var coins: Int
    var ring: Bool = false
    var crown: Bool = false
    var gems: Int
    var chest: Bool = false
    var spill: Int = 0
    var beads: Bool = false
}

let T_STAGES: [TSpec] = [
    TSpec(coins: 1, gems: 0), // 0 une simple pièce d'or
    TSpec(coins: 1, gems: 0),
    TSpec(coins: 1, gems: 0),
    TSpec(coins: 3, ring: true, gems: 0), // 3 l'or + le premier bijou
    TSpec(coins: 4, ring: true, gems: 0),
    TSpec(coins: 5, ring: true, crown: true, gems: 0), // 5 la couronne rejoint le butin
    TSpec(coins: 6, ring: true, crown: true, gems: 0),
    TSpec(coins: 7, ring: true, crown: true, gems: 2, chest: true, spill: 1), // 7 le coffre !
    TSpec(coins: 9, ring: true, crown: true, gems: 3, chest: true, spill: 2, beads: true),
    TSpec(coins: 12, ring: true, crown: true, gems: 5, chest: true, spill: 3, beads: true), // 9 il déborde
]

/** y of the highest filled coin slot, for seating the crown on the heap. */
func heapTopY(_ coins: Int) -> Double {
    if coins >= 13 { return -6.5 }
    if coins >= 8 { return -4.7 }
    if coins >= 3 { return -2.9 }
    return -1.1
}

/// `[...slots.slice(0, n)].sort((a, b) => a[1] - b[1])` — JS `sort` is stable
/// (ES2019) and adjacent coins in a row OVERLAP by design, so ties must keep
/// authored order or the shingling flips. Swift's `sorted` is not guaranteed
/// stable; the index tie-break makes it so.
func stableSortedByDY(_ slots: [(Double, Double)]) -> [(Double, Double)] {
    slots.enumerated()
        .sorted { ($0.element.1, $0.offset) < ($1.element.1, $1.offset) }
        .map(\.element)
}

/** The treasure accessory. Element size is constant; stage drives quantity. */
public func drawTreasure(into c: inout SVGCanvas, stage: Int, x: Double, groundY: Double) {
    let spec = T_STAGES[max(0, min(9, stage))]
    let set = stage >= 7 // jewels get their precious stones
    if spec.chest {
        let rimY = groundY - 6.6
        let top: Double = spec.coins >= 10 ? -3.8 : -2.1 // highest filled mound row
        // Everything that overflows goes on the DRAGON's side: standing coin
        // leaning on the right wall, spilled coins around it, ring past them.
        let upX = 9.7
        let ringX = 13.4
        let left = -8.2
        let right = ringX + 2.6
        c.fill(dpEllipse(x + (left + right) / 2, groundY + 0.5, (right - left) / 2 + 1.5, 1.3), with: .hex("#000", opacity: 0.1))
        drawTChestLid(into: &c, x: x, rimY: rimY)
        for (dx, dy) in stableSortedByDY(Array(CHEST_SLOTS.prefix(spec.coins))) {
            drawTCoin(into: &c, cx: x + dx, cy: rimY + dy)
        }
        if spec.crown { drawTCrown(into: &c, x: x + 0.6, y: rimY + top - 1.2) }
        drawTChestBody(into: &c, x: x, rimY: rimY, groundY: groundY)
        if spec.beads { drawTBeads(into: &c, x: x, rimY: rimY) }
        for (dx, dy) in stableSortedByDY(Array(SPILL_SLOTS.prefix(spec.spill))) {
            drawTCoin(into: &c, cx: x + dx, cy: groundY + dy)
        }
        // the hero coin stays IN FRONT of the spill — its star face is the read
        drawTUprightCoin(into: &c, cx: x + upX, cy: groundY - COIN_R, lean: -10)
        drawTRing(into: &c, cx: x + ringX, cy: groundY - 1.9, gem: true)
        for (dx, dy, color) in CHEST_GEM_SLOTS.prefix(spec.gems) {
            drawTGem(into: &c, x: x + dx, y: groundY + dy, s: 1.3, color: color)
        }
        drawSparkles(
            into: &c,
            points: [
                (x - 4.8, rimY + top - 4.3, 1.1),
                (x + 4.2, rimY + top - 5.7, 0.85),
            ],
            color: OR
        )
        // white glint allowed ONLY on gold, never on the bare background
        drawSparkles(into: &c, points: [(x + 0.9, rimY - 1.6, 0.7)], color: "#FFFFFF")
        return
    }
    if spec.coins == 1 {
        c.fill(dpEllipse(x, groundY + 0.6, 2.9, 0.7), with: .hex("#000", opacity: 0.1))
        drawTUprightCoin(into: &c, cx: x, cy: groundY - COIN_R)
        drawSparkles(into: &c, points: [(x + 3.0, groundY - 7.0, 0.85)], color: OR)
        return
    }
    // Side pieces hug the mound: the upright coin leans on row 1's left edge,
    // the ring sits just past it, AWAY from the dragon (no more donut-on-toe).
    let leftRow: Double = spec.coins >= 6 ? -5.8 : -2.9
    let upX = leftRow - COIN_R * 2 + 1
    let ringX = spec.ring ? upX - COIN_R - 1.9 : upX
    let left = ringX - 1.9
    let right = (spec.coins >= 6 ? 5.8 : spec.coins >= 4 ? 2.9 : 0) + COIN_R + (set ? 1.4 : 0)
    // one shared ground shadow — same token as the pet's, glues it to the floor
    c.fill(dpEllipse(x + (left + right) / 2, groundY + 0.5, (right - left) / 2 + 2, 1.2), with: .hex("#000", opacity: 0.1))
    for (dx, dy) in stableSortedByDY(Array(COIN_SLOTS.prefix(spec.coins))) {
        drawTCoin(into: &c, cx: x + dx, cy: groundY + dy)
    }
    drawTUprightCoin(into: &c, cx: x + upX, cy: groundY - COIN_R, lean: -12)
    if spec.ring { drawTRing(into: &c, cx: x + ringX, cy: groundY - 1.9, gem: set) }
    for (dx, dy, color) in GEM_SLOTS.prefix(spec.gems) {
        drawTGem(into: &c, x: x + dx, y: groundY + dy, s: 1.3, color: color)
    }
    if spec.crown { drawTCrown(into: &c, x: x, y: groundY + heapTopY(spec.coins) - 1.2) }
    drawSparkles(
        into: &c,
        points: [
            (x - 4.8, groundY + heapTopY(spec.coins) - 4.4, 1.1),
            (x + 3.6, groundY + heapTopY(spec.coins) - 6.0, 0.85),
        ],
        color: OR
    )
    // white glint allowed ONLY on gold, never on the bare background
    if set { drawSparkles(into: &c, points: [(x + 1.5, groundY - 3.1, 0.7)], color: "#FFFFFF") }
}

/** Cord necklace with a little white fang pendant, hung at the throat anchor
 * (his first baby fang, kept as a trophy). */
public func drawFangPendant(into c: inout SVGCanvas, x: Double, y: Double, w: Double) {
    c.stroke(
        Path(svg: "M\(x - w) \(y - 1.2) Q\(x) \(y + 2.2) \(x + w) \(y - 1.2)"),
        with: .hex("#8D5A3B"),
        style: StrokeStyle(lineWidth: 1.6, lineCap: .round)
    )
    let fang = "M\(x - 1.9) \(y + 1.2) Q\(x - 1.7) \(y + 6.2) \(x + 0.6) \(y + 7.6) Q\(x + 2) \(y + 4) \(x + 1.5) \(y + 1)"
    c.fill(Path(svg: fang), with: .hex("#FFFDF4"))
    c.stroke(Path(svg: fang), with: .hex("#B8A98C"), style: StrokeStyle(lineWidth: 0.8, lineJoin: .round))
    c.stroke(
        Path(svg: "M\(x - 2.3) \(y + 1.4) q2.2 1.7 4.2 0"),
        with: .hex("#8D5A3B"),
        style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
    )
}

/** Aviator goggles resting on the upper dome (never over the eyes). */
public func drawGoggles(into c: inout SVGCanvas, x: Double, y: Double, headR: Double) {
    let ly = y + headR * 0.22
    let r = headR * 0.21
    let dx = headR * 0.3
    c.stroke(
        Path(svg: "M\(x - headR * 0.72) \(ly + r * 0.3) Q\(x) \(y - headR * 0.14) \(x + headR * 0.72) \(ly + r * 0.3)"),
        with: .hex("#8D5A3B"),
        style: StrokeStyle(lineWidth: 2.2)
    )
    c.fill(dpCircle(x - dx, ly, r), with: .hex("#CDEBF7"))
    c.stroke(dpCircle(x - dx, ly, r), with: .hex("#C98A5B"), style: StrokeStyle(lineWidth: 1.8))
    c.fill(dpCircle(x + dx, ly, r), with: .hex("#CDEBF7"))
    c.stroke(dpCircle(x + dx, ly, r), with: .hex("#C98A5B"), style: StrokeStyle(lineWidth: 1.8))
    c.stroke(
        Path(svg: "M\(x - dx + r) \(ly) Q\(x) \(ly - r * 0.5) \(x + dx - r) \(ly)"),
        with: .hex("#C98A5B"),
        style: StrokeStyle(lineWidth: 1.5)
    )
    c.fill(dpCircle(x - dx - r * 0.3, ly - r * 0.35, r * 0.28), with: .hex("#FFFFFF", opacity: 0.8))
    c.fill(dpCircle(x + dx - r * 0.3, ly - r * 0.35, r * 0.28), with: .hex("#FFFFFF", opacity: 0.8))
}

/** Dragon snout: wide muzzle + nostrils (the nose dots make it a dragon, not a cat). */
public func drawSnout(into c: inout SVGCanvas, hx: Double, hy: Double, headR: Double, color: String) {
    c.fill(dpEllipse(hx, hy + headR * 0.45, headR * 0.46, headR * 0.32), with: .hex(color))
    c.group(opacity: 0.75) { g in
        g.fill(dpEllipse(hx - headR * 0.13, hy + headR * 0.32, 1.15, 1.5), with: .hex(Growth.ink))
        g.fill(dpEllipse(hx + headR * 0.13, hy + headR * 0.32, 1.15, 1.5), with: .hex(Growth.ink))
    }
}

// MARK: - Shape helpers (`<circle>` / `<ellipse>` are not path data — D2)

private func dpCircle(_ cx: Double, _ cy: Double, _ r: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
}

private func dpEllipse(_ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
}
