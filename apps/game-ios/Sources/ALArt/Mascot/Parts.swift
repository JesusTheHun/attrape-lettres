import ALCore
import CoreGraphics
import Foundation
import SwiftUI

// Port of `src/mascot/parts.tsx`.
//
// Generic flat-kawaii part library. Species files compose these + their own
// species-specific parts. Everything is pure SVG driven by numeric props.
//
// Each TSX component becomes one `draw…(into:)` function over the `SVGCanvas`
// substrate (D15), transcribed statement by statement — painter's order is the
// call order. Every `d` template is copied VERBATIM into a Swift string
// interpolation (D2); `<circle>`/`<ellipse>`/`<rect>` attributes go through the
// small helpers below with their numbers untouched.
//
// The TSX `id` props (Aura, Swimsuit, SwimRing, Halo, GroundGlow) existed only
// because SVG defs share a document namespace; here a gradient is a value and a
// clipPath is `save()`/`clip(to:)`/`restore()`, so the ids have no port (spec
// §4 — nothing may re-introduce a uid).

let DARK = "#4A3222"
let BLUSH = "#FF9AA2"

// MARK: - Shape helpers (verbatim attribute → Path)

/// `<ellipse cx cy rx ry>`.
func ellipsePath(_ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
}

/// `<circle cx cy r>`.
func circlePath(_ cx: Double, _ cy: Double, _ r: Double) -> Path {
    ellipsePath(cx, cy, r, r)
}

/// `<line x1 y1 x2 y2>`.
func linePath(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> Path {
    Path { p in
        p.move(to: CGPoint(x: x1, y: y1))
        p.addLine(to: CGPoint(x: x2, y: y2))
    }
}

/** A crisp 4-point sparkle star centred at (cx,cy). */
public func fourStar(_ cx: Double, _ cy: Double, _ r: Double) -> String {
    let i = r * 0.36
    return "M\(cx) \(cy - r) L\(cx + i) \(cy - i) L\(cx + r) \(cy) L\(cx + i) \(cy + i) L\(cx) \(cy + r) L\(cx - i) \(cy + i) L\(cx - r) \(cy) L\(cx - i) \(cy - i) Z"
}

/* -- Face --------------------------------------------------------------- */

public func drawEyes(
    into c: inout SVGCanvas,
    cx: Double,
    y: Double,
    dx: Double,
    r: Double,
    mood: Mood,
    /** Peaceful closed eyes for the helpless stade-0 baby (idle only). */
    sleepy: Bool = false
) {
    let lx = cx - dx
    let rx = cx + dx
    if sleepy && mood == .idle {
        let w = r * 1.1
        let style = StrokeStyle(lineWidth: r * 0.5, lineCap: .round)
        c.stroke(Path(svg: "M\(lx - w) \(y) Q\(lx) \(y + r * 0.9) \(lx + w) \(y)"), with: .hex(DARK), style: style)
        c.stroke(Path(svg: "M\(rx - w) \(y) Q\(rx) \(y + r * 0.9) \(rx + w) \(y)"), with: .hex(DARK), style: style)
        return
    }
    if mood == .cheer {
        c.fill(Path(svg: fourStar(lx, y, r * 1.35)), with: .hex(DARK))
        c.fill(Path(svg: fourStar(rx, y, r * 1.35)), with: .hex(DARK))
        return
    }
    if mood == .happy {
        let w = r * 1.25
        let style = StrokeStyle(lineWidth: r * 0.78, lineCap: .round)
        c.stroke(Path(svg: "M\(lx - w) \(y + r * 0.5) Q\(lx) \(y - r) \(lx + w) \(y + r * 0.5)"), with: .hex(DARK), style: style)
        c.stroke(Path(svg: "M\(rx - w) \(y + r * 0.5) Q\(rx) \(y - r) \(rx + w) \(y + r * 0.5)"), with: .hex(DARK), style: style)
        return
    }
    c.fill(circlePath(lx, y, r), with: .hex(DARK))
    c.fill(circlePath(rx, y, r), with: .hex(DARK))
    c.fill(circlePath(lx - r * 0.32, y - r * 0.36, r * 0.36), with: .hex("#fff"))
    c.fill(circlePath(rx - r * 0.32, y - r * 0.36, r * 0.36), with: .hex("#fff"))
    c.fill(circlePath(lx + r * 0.34, y + r * 0.34, r * 0.16), with: .hex("#fff", opacity: 0.8))
    c.fill(circlePath(rx + r * 0.34, y + r * 0.34, r * 0.16), with: .hex("#fff", opacity: 0.8))
}

public func drawCheeks(into c: inout SVGCanvas, cx: Double, y: Double, dx: Double, r: Double) {
    c.group(opacity: 0.6) { g in
        g.fill(ellipsePath(cx - dx, y, r, r * 0.68), with: .hex(BLUSH))
        g.fill(ellipsePath(cx + dx, y, r, r * 0.68), with: .hex(BLUSH))
    }
}

public func drawMouth(into c: inout SVGCanvas, cx: Double, y: Double, w: Double, mood: Mood) {
    if mood == .idle {
        c.stroke(
            Path(svg: "M\(cx - w) \(y) Q\(cx) \(y + w * 0.95) \(cx + w) \(y)"),
            with: .hex(DARK),
            style: StrokeStyle(lineWidth: 1.5, lineCap: .round)
        )
        return
    }
    c.fill(Path(svg: "M\(cx - w) \(y - w * 0.2) Q\(cx) \(y + w * 1.5) \(cx + w) \(y - w * 0.2) Z"), with: .hex(DARK))
    c.fill(
        Path(svg: "M\(cx - w * 0.5) \(y + w * 0.5) Q\(cx) \(y + w * 1.1) \(cx + w * 0.5) \(y + w * 0.5) Z"),
        with: .hex("#FF7C93")
    )
}

/* -- Fur / hair plume (tails, manes, tufts) ----------------------------- */

/** A fan of rotated lock-ellipses. `wave` toggles a curly zig-zag. */
public func drawPlume(
    into c: inout SVGCanvas,
    x: Double,
    y: Double,
    color: String,
    len: Double,
    wide: Double,
    rot: Double,
    n: Int,
    wave: Bool = false
) {
    for i in 0..<n {
        let t = n == 1 ? 0.5 : Double(i) / Double(n - 1)
        let ox = x + (t - 0.5) * wide
        let oy = y + abs(t - 0.5) * len * 0.18
        let r = wave ? rot + sin(Double(i) * 1.9) * 22 : rot + (t - 0.5) * 26
        let rad = r * .pi / 180
        let tipX = ox + sin(rad) * len
        let tipY = oy + cos(rad) * len
        let mx = (ox + tipX) / 2
        let my = (oy + tipY) / 2
        c.save()
        c.rotate(degrees: r, about: CGPoint(x: mx, y: my))
        c.fill(ellipsePath(mx, my, len * 0.26, len * 0.52), with: .hex(color))
        c.restore()
    }
}

/* -- Legs --------------------------------------------------------------- */

public func drawLeg(into c: inout SVGCanvas, spec: LegSpec, w: Double, color: String, hoof: String) {
    let mx = (spec.hipX + spec.footX) / 2 + spec.side * spec.bend
    let my = (spec.hipY + spec.footY) / 2
    c.stroke(
        Path(svg: "M\(spec.hipX) \(spec.hipY) Q\(mx) \(my) \(spec.footX) \(spec.footY)"),
        with: .hex(color),
        style: StrokeStyle(lineWidth: w, lineCap: .round)
    )
    c.fill(ellipsePath(spec.footX, spec.footY, w * 0.62, w * 0.4), with: .hex(hoof))
}

/** Tucked folded legs for the lying (can't-stand) baby pose. */
public func drawFoldedLegs(
    into c: inout SVGCanvas,
    bodyCX: Double,
    bodyCY: Double,
    bodyRX: Double,
    color: String,
    hoof: String
) {
    let y = bodyCY + 8
    let x = bodyCX + bodyRX * 0.35
    let style = StrokeStyle(lineWidth: 7, lineCap: .round)
    c.stroke(Path(svg: "M\(x - 4) \(y - 5) Q\(x + 10) \(y + 2) \(x + 16) \(y)"), with: .hex(color), style: style)
    c.stroke(Path(svg: "M\(x - 10) \(y - 2) Q\(x + 4) \(y + 6) \(x + 12) \(y + 4)"), with: .hex(color), style: style)
    c.fill(ellipsePath(x + 16, y, 4, 2.6), with: .hex(hoof))
    c.fill(ellipsePath(x + 12, y + 4, 4, 2.6), with: .hex(hoof))
}

/* -- Shine / glow ------------------------------------------------------- */

/** Soft radial glow. */
public func drawAura(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    color: String,
    opacity: Double = 1
) {
    c.group(opacity: opacity) { g in
        g.fill(
            circlePath(cx, cy, r),
            with: .radial([
                .svg(color, at: 0, opacity: 0.6),
                .svg(color, at: 0.55, opacity: 0.22),
                .svg(color, at: 1, opacity: 0),
            ])
        )
    }
}

public func drawSparkles(
    into c: inout SVGCanvas,
    points: [(Double, Double, Double)],
    color: String = "#FFF7D6"
) {
    if points.isEmpty { return }
    for (x, y, r) in points {
        c.fill(Path(svg: fourStar(x, y, r)), with: .hex(color))
    }
}

/* -- "Arc-en-ciel magique" premium overlay ------------------------------ */

/**
 * Premium "Arc-en-ciel magique" overlay — the ultimate reward. A single rainbow
 * prism of light that sweeps diagonally across the WHOLE image (wings included)
 * on a slow loop, like tilting a holographic card. No stars (the free growth
 * timeline already owns those) and NO colour film / glow over the pet — just the
 * travelling spectrum glint. Clipped to the viewBox so it never spills onto
 * surrounding UI. Motion is pure CSS (index.css: alSheen) — off the React render
 * path; under prefers-reduced-motion the animation freezes to a static rainbow
 * band across the centre. `id` must be unique per mascot instance.
 *
 * NB (port): the CSS sweep (`--al-from: -150px` → `--al-to: 150px`, viewBox
 * units) is view-level motion, owned by the motion layer (spec §10). This draw
 * function records the band at its base transform — exactly the static rainbow
 * band across the centre that reduced motion shows.
 */
public func drawRainbowSheen(into c: inout SVGCanvas) {
    let sheenW = 26.0  // half-width of the prism band, in viewBox units
    // Sweep range comfortably exceeds the pet's on-screen extent (wings overflow
    // the 0..100 box at the top stades). The band lives off the pet at both ends —
    // where the silhouette mask hides it — so the linear wrap is never seen.

    // the sweeping glint IS a rainbow prism — a spectrum band, soft at both edges
    let sheen: [Gradient.Stop] = [
        .svg("#FF6F91", at: 0, opacity: 0),
        .svg("#FF6F91", at: 0.26, opacity: 0),
        .svg("#FF6F91", at: 0.38, opacity: 0.75),
        .svg("#FFD24C", at: 0.47, opacity: 0.8),
        .svg("#7CE8B0", at: 0.53, opacity: 0.8),
        .svg("#5BC8FF", at: 0.60, opacity: 0.8),
        .svg("#B98CFF", at: 0.68, opacity: 0.75),
        .svg("#B98CFF", at: 0.80, opacity: 0),
        .svg("#B98CFF", at: 1, opacity: 0),
    ]

    // rainbow prism sweeping across the pet (confined by the silhouette mask)
    c.save()
    c.rotate(degrees: -20, about: CGPoint(x: 50, y: 50))
    c.fill(
        Path(CGRect(x: 50 - sheenW, y: -120, width: sheenW * 2, height: 340)),
        with: .linear(sheen)
    )
    c.restore()
}

/* -- Accessories shared shapes ----------------------------------------- */

public func drawBow(into c: inout SVGCanvas, x: Double, y: Double, s: Double, color: String) {
    c.fill(Path(svg: "M\(x) \(y) L\(x - 7 * s) \(y - 5 * s) Q\(x - 9.5 * s) \(y) \(x - 7 * s) \(y + 5 * s) Z"), with: .hex(color))
    c.fill(Path(svg: "M\(x) \(y) L\(x + 7 * s) \(y - 5 * s) Q\(x + 9.5 * s) \(y) \(x + 7 * s) \(y + 5 * s) Z"), with: .hex(color))
    c.fill(circlePath(x, y, 2.6 * s), with: .hex(color))
    c.fill(circlePath(x - 0.6 * s, y - 0.6 * s, 1.1 * s), with: .hex("#fff", opacity: 0.4))
}

/** One-piece striped bathing suit — a colour band CLIPPED to the torso ellipse so
 * it hugs the belly. Standing pets wear a waist band (champions, stade 6+, earn a
 * white `star` badge). `lying` newborns wear a little nappy-culotte on the RUMP
 * (away from the oversized resting head) with vertical stripes — the rig draws it
 * UNDER the head/neck so the face always stays on top. */
public func drawSwimsuit(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    color: String,
    stripe: String = "#FFF6EE",
    lying: Bool = false,
    star: Bool = false
) {
    if lying {
        let edge = cx - rx * 0.16  // culotte covers the left (rump) side of the loaf
        c.save()
        c.clip(to: ellipsePath(cx, cy, rx, ry))
        c.fill(
            Path(CGRect(x: cx - rx - 1, y: cy - ry - 1, width: edge - (cx - rx - 1), height: ry * 2 + 2)),
            with: .hex(color)
        )
        for k in [0.72, 0.44] {
            c.stroke(
                Path(svg: "M\(cx - rx * k) \(cy - ry) q 2.6 \(ry * 0.5) 0 \(ry) q -2.6 \(ry * 0.5) 0 \(ry)"),
                with: .hex(stripe, opacity: 0.9),
                style: StrokeStyle(lineWidth: 2)
            )
        }
        c.restore()
        c.stroke(
            Path(svg: "M\(edge) \(cy - ry * 0.9) Q\(edge + 2) \(cy) \(edge) \(cy + ry * 0.9)"),
            with: .hex(DARK, opacity: 0.28),
            style: StrokeStyle(lineWidth: 1)
        )
        return
    }
    let top = cy - ry * 0.12
    c.save()
    c.clip(to: ellipsePath(cx, cy, rx, ry))
    c.fill(Path(CGRect(x: cx - rx - 1, y: top, width: rx * 2 + 2, height: ry * 2)), with: .hex(color))
    for k in [0.34, 0.68] {
        c.stroke(
            Path(svg: "M\(cx - rx) \(top + ry * k) q \(rx * 0.5) -3 \(rx) 0 q \(rx * 0.5) 3 \(rx) 0"),
            with: .hex(stripe, opacity: 0.9),
            style: StrokeStyle(lineWidth: 2.2)
        )
    }
    c.restore()
    c.stroke(
        Path(svg: "M\(cx - rx * 0.97) \(top + 1.5) Q\(cx) \(top - 2) \(cx + rx * 0.97) \(top + 1.5)"),
        with: .hex(DARK, opacity: 0.28),
        style: StrokeStyle(lineWidth: 1)
    )
    if star {
        c.fill(Path(svg: fourStar(cx, cy + ry * 0.38, 3.1)), with: .hex("#fff", opacity: 0.95))
    }
}

public enum SwimRingPart {
    case full
    case back
    case front
}

/** Classic segmented swim ring — a thick ellipse torus with alternating
 * colour/cream segments and faint rim outlines so it reads as a puffy
 * inflatable. To make the pet sit INSIDE the tube, rigs draw it twice: the
 * `back` half behind the body, the `front` half over it (each half is the same
 * geometry clipped at the tube's midline). `full` is for lying babies who rest
 * ON the ring. `duck` (stade 7+) perches an inflatable duck head on the tube —
 * drawn with the front half so the body never hides it. */
public func drawSwimRing(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    color: String,
    duck: Bool = false,
    part: SwimRingPart = .full
) {
    let ry = rx * 0.38
    let w = rx * 0.32
    // ~1/8 of the ellipse perimeter (Ramanujan) → 4 colour + 4 cream segments.
    let per = Double.pi * (3 * (rx + ry) - ((3 * rx + ry) * (rx + 3 * ry)).squareRoot())
    let dash = per / 8
    let pad = w + 2
    let hx = cx + rx * 0.95
    let hy = cy - w * 0.55
    let hr = w * 0.8
    if duck && part != .back {
        c.fill(circlePath(hx, hy, hr), with: .hex("#FFE082"))
        c.stroke(circlePath(hx, hy, hr), with: .hex(DARK, opacity: 0.25), style: StrokeStyle(lineWidth: 0.7))
        c.fill(
            Path(svg: "M\(hx + hr * 0.6) \(hy - 1.4) L\(hx + hr + 3.6) \(hy + 0.4) L\(hx + hr * 0.6) \(hy + 1.8) Z"),
            with: .hex("#FF8A50")
        )
        c.fill(circlePath(hx + hr * 0.25, hy - hr * 0.25, 0.9), with: .hex(DARK))
    }
    c.save()
    switch part {
    case .full:
        break
    case .back:
        c.clip(to: Path(CGRect(x: cx - rx - pad, y: cy - ry - pad, width: (rx + pad) * 2, height: ry + pad)))
    case .front:
        c.clip(to: Path(CGRect(x: cx - rx - pad, y: cy, width: (rx + pad) * 2, height: ry + pad)))
    }
    c.stroke(ellipsePath(cx, cy, rx, ry), with: .hex("#FFF6EE"), style: StrokeStyle(lineWidth: w))
    c.stroke(ellipsePath(cx, cy, rx, ry), with: .hex(color), style: StrokeStyle(lineWidth: w, dash: [dash, dash]))
    c.stroke(ellipsePath(cx, cy, rx + w / 2, ry + w / 2), with: .hex(DARK, opacity: 0.22), style: StrokeStyle(lineWidth: 0.8))
    c.stroke(ellipsePath(cx, cy, rx - w / 2, ry - w / 2), with: .hex(DARK, opacity: 0.22), style: StrokeStyle(lineWidth: 0.8))
    c.restore()
}

public func drawFlower(
    into c: inout SVGCanvas,
    x: Double,
    y: Double,
    r: Double,
    petal: String,
    center: String
) {
    for i in 0..<5 {
        let a = Double(i) / 5 * .pi * 2 - .pi / 2
        c.fill(circlePath(x + cos(a) * r, y + sin(a) * r, r * 0.72), with: .hex(petal))
    }
    c.fill(circlePath(x, y, r * 0.6), with: .hex(center))
}

/* -- Majestic-stage shared parts ---------------------------------------- */
/* These carry the late-stage "wow". Every one is OUTLINED / non-body-coloured
 * on purpose: features that share the body's colour vanish into the silhouette
 * and a child never sees the reward. Keep the edges. */

/** Glowing head halo (soft aura + a crisp ring). */
public func drawHalo(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    opacity: Double,
    color: String = "#FFF3C4"
) {
    drawAura(into: &c, cx: cx, cy: cy, r: r, color: color, opacity: opacity)
    c.stroke(
        circlePath(cx, cy, r * 0.62),
        with: .hex(color, opacity: opacity * 0.8),
        style: StrokeStyle(lineWidth: 1.4)
    )
}

/** Flat radial glow pooled under the feet. */
public func drawGroundGlow(
    into c: inout SVGCanvas,
    cx: Double,
    y: Double,
    rx: Double,
    color: String,
    opacity: Double
) {
    c.group(opacity: opacity) { g in
        g.fill(
            ellipsePath(cx, y, rx, rx * 0.3),
            with: .radial([
                .svg(color, at: 0, opacity: 0.8),
                .svg(color, at: 0.55, opacity: 0.2),
                .svg(color, at: 1, opacity: 0),
            ])
        )
    }
}

/** Outlined tiara/crown arcing over the head, gem-tipped spikes. */
/** `open` drops the tall CENTRE spike — for wearers whose horn rises exactly
 * there (the rig also draws the horn OVER the band so it always pokes through). */
public func drawCrown(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    band: String = "#FFD54F",
    gem: String = "#FF7EA8",
    edge: String = "#B07E1E",
    open: Bool = false
) {
    struct Spike {
        var bx: Double
        var by: Double
        var tx: Double
        var ty: Double
        var big: Bool
    }
    var spikes: [Spike] = []
    for (i, f) in [-0.62, -0.31, 0, 0.31, 0.62].enumerated() {
        if open && i == 2 { continue }
        let a = (-90 + f * 78) * (.pi / 180)
        let bx = cx + cos(a) * r
        let by = cy + sin(a) * r
        let h: Double = i == 2 ? 8.5 : 5
        spikes.append(Spike(bx: bx, by: by, tx: cx + cos(a) * (r + h), ty: cy + sin(a) * (r + h), big: i == 2))
    }
    let bandPath = "M\(cx - r * 0.72) \(cy - r * 0.55) Q\(cx) \(cy - r * 1.02) \(cx + r * 0.72) \(cy - r * 0.55)"
    c.stroke(Path(svg: bandPath), with: .hex(edge), style: StrokeStyle(lineWidth: 4.6, lineCap: .round))
    c.stroke(Path(svg: bandPath), with: .hex(band), style: StrokeStyle(lineWidth: 3.2, lineCap: .round))
    for s in spikes {
        let spike = Path(svg: "M\(s.bx - 2) \(s.by) L\(s.tx) \(s.ty) L\(s.bx + 2) \(s.by) Z")
        c.fill(spike, with: .hex(band))
        c.stroke(spike, with: .hex(edge), style: StrokeStyle(lineWidth: 0.9, lineJoin: .round))
        let gemR: Double = s.big ? 2.4 : 1.7
        c.fill(circlePath(s.tx, s.ty, gemR), with: .hex(gem))
        c.stroke(circlePath(s.tx, s.ty, gemR), with: .hex(edge), style: StrokeStyle(lineWidth: 0.6))
    }
}

/** A ring of sparkle-stars around a centre (a celebratory burst). */
public func drawBurst(
    into c: inout SVGCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    n: Int,
    color: String? = nil
) {
    drawSparkles(
        into: &c,
        points: (0..<n).map { i in
            let a = Double(i) / Double(n) * .pi * 2
            return (cx + cos(a) * rx, cy + sin(a) * ry, 1.6 + Double(i % 3))
        },
        color: color ?? "#FFF7D6"
    )
}
