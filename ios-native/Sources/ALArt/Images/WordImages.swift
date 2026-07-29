import CoreGraphics
import SwiftUI

import ALCore

// The four standalone word illustrations — `src/img/{igloo,jupe,macaron,pyjama}.svg`
// — through the same `Path(svg:)` parser and the same `SVGCanvas` substrate as
// everything else (D2 / D15). Every `d` string, every `cx`/`cy`/`rx`, every hex
// and every gradient stop is copied verbatim from the file.
//
// `content.ts` reaches them as Vite asset URLs (`import jupe from "../img/jupe.svg"`);
// ALCore knows nothing about bundles, so it carries `ImageKey` and this file
// owns the key → drawing table. That table is an **exhaustive `switch`**, never
// a dictionary, for the same reason as the exercise icons: a dictionary lets a
// new key compile and render nothing.
//
// Gradients: 12 across the four files, none of them declaring `gradientUnits`,
// so all 12 are `objectBoundingBox` — which is `SVGPaint`'s default (D24).
// Eleven run `(0,0) → (1,1)` diagonally; the igloo's door is the one vertical
// `(0,0) → (0,1)`.
//
// NB: the files carry `fill-opacity` / `stroke-opacity` (drop shadows at 0.10,
// jupe's pleat strokes at 0.4, pyjama's dots at 0.8, the highlight ellipses at
// 0.22 / 0.28 / 0.45). Those ride on the paint's `opacity`, not on a group.

public enum WordImages {

    /// Every one of the four files is `viewBox="0 0 128 128"`.
    public static let viewBox = CGRect(x: 0, y: 0, width: 128, height: 128)

    /// The `<title>` element, which is what `aria-labelledby` announces.
    public static func label(_ key: ImageKey) -> String {
        switch key {
        case .igloo: return "Igloo"
        case .jupe: return "Jupe"
        case .macaron: return "Macaron"
        case .pyjama: return "Pyjama"
        }
    }

    /// Draw `key` into a canvas whose user space is the 128-unit viewBox.
    ///
    /// Exhaustive `switch`, no `default:` — see the file header.
    public static func draw(_ key: ImageKey, into c: inout SVGCanvas) {
        switch key {
        case .igloo: drawIgloo(into: &c)
        case .jupe: drawJupe(into: &c)
        case .macaron: drawMacaron(into: &c)
        case .pyjama: drawPyjama(into: &c)
        }
    }
}

// MARK: - Shared helpers

/// `<linearGradient x1="0" y1="0" x2="1" y2="1">` with two stops — eleven of
/// the twelve gradients in these files.
private func diagonal(_ from: String, _ to: String) -> SVGPaint {
    .linear(
        [.svg(from, at: 0), .svg(to, at: 1)],
        from: CGPoint(x: 0, y: 0),
        to: CGPoint(x: 1, y: 1)
    )
}

private func ellipse(_ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double) -> Path {
    Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: 2 * rx, height: 2 * ry))
}

private func circle(_ cx: Double, _ cy: Double, _ r: Double) -> Path {
    ellipse(cx, cy, r, r)
}

private func stroke(_ width: Double, cap: CGLineCap = .butt, join: CGLineJoin = .miter) -> StrokeStyle {
    StrokeStyle(lineWidth: width, lineCap: cap, lineJoin: join)
}

/// `<ellipse … transform="rotate(a cx cy)">` — the rotation goes on the CTM,
/// never on the rect (D15).
private func rotatedEllipse(
    _ c: inout SVGCanvas,
    degrees: Double,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    paint: SVGPaint
) {
    c.save()
    c.rotate(degrees: degrees, about: CGPoint(x: cx, y: cy))
    c.fill(ellipse(cx, cy, rx, ry), with: paint)
    c.restore()
}

/// `fill="#000000" fill-opacity="0.10"` — the ground shadow under all four.
private let dropShadow = SVGPaint.hex("#000000", opacity: 0.10)

// MARK: - Jupe

private func drawJupe(into c: inout SVGCanvas) {
    // <defs>
    let skirt = diagonal("#FBA895", "#EF7A64")   // ju-skirt
    let band = diagonal("#F08B76", "#DB6650")    // ju-band
    let limb = diagonal("#CFD4D9", "#AFB5BC")    // ju-limb
    let shirt = diagonal("#BEC4CB", "#A2A9B1")   // ju-shirt
    let hair = diagonal("#A2A8AF", "#868C93")    // ju-hair
    let outline = SVGPaint.hex("#9BA1A8")

    c.fill(ellipse(64, 114, 27, 5), with: dropShadow)

    // legs
    let legL = Path(svg: "M56 70 L55 106 Q55 109 59 109 L61 109 Q63 109 63 106 L63 70 Z")
    c.fill(legL, with: limb)
    c.stroke(legL, with: outline, style: stroke(1))
    let legR = Path(svg: "M65 70 L65 106 Q65 109 67 109 L69 109 Q73 109 73 106 L72 70 Z")
    c.fill(legR, with: limb)
    c.stroke(legR, with: outline, style: stroke(1))

    // shoes
    c.fill(
        Path(svg: "M52 106 Q52 104 55 104 L63 104 L63 109 Q63 111 60 111 L54 111 Q52 111 52 109 Z"),
        with: .hex("#8B9198")
    )
    c.fill(
        Path(svg: "M65 104 L73 104 Q76 104 76 106 L76 109 Q76 111 74 111 L68 111 Q65 111 65 109 Z"),
        with: .hex("#8B9198")
    )

    // torso / shirt
    let torso = Path(svg: "M50 44 Q50 42 53 42 L75 42 Q78 42 78 44 L79 62 Q79 63 77 63 L51 63 Q49 63 49 62 Z")
    c.fill(torso, with: shirt)
    c.stroke(torso, with: outline, style: stroke(1))

    // arms
    let armL = Path(svg: "M50 45 Q45 47 44 53 L43 65 Q43 68 46 68 Q49 68 49 65 L50 54 Q50 48 52 46 Z")
    c.fill(armL, with: limb)
    c.stroke(armL, with: outline, style: stroke(1))
    let armR = Path(svg: "M78 45 Q83 47 84 53 L85 65 Q85 68 82 68 Q79 68 79 65 L78 54 Q78 48 76 46 Z")
    c.fill(armR, with: limb)
    c.stroke(armR, with: outline, style: stroke(1))
    let handL = circle(46, 68, 3)
    c.fill(handL, with: limb)
    c.stroke(handL, with: outline, style: stroke(0.8))
    let handR = circle(82, 68, 3)
    c.fill(handR, with: limb)
    c.stroke(handR, with: outline, style: stroke(0.8))

    // SKIRT (the only colour)
    let skirtPath = Path(
        svg: "M52 64 L42 88 Q46 92 50 88 Q54 92 58 88 Q62 92 66 88 Q70 92 74 88 Q78 92 82 88 Q84 91 86 88 L76 64 Z"
    )
    c.fill(skirtPath, with: skirt)
    c.stroke(skirtPath, with: .hex("#D9604B"), style: stroke(1.4))

    // <g fill="none" stroke="#D9604B" stroke-width="1.4" stroke-opacity="0.4" stroke-linecap="round">
    let pleat = SVGPaint.hex("#D9604B", opacity: 0.4)
    let pleatStyle = stroke(1.4, cap: .round)
    c.stroke(Path(svg: "M53 66 L44 88"), with: pleat, style: pleatStyle)
    c.stroke(Path(svg: "M57 66 L50 88"), with: pleat, style: pleatStyle)
    c.stroke(Path(svg: "M61 66 L58 88"), with: pleat, style: pleatStyle)
    c.stroke(Path(svg: "M65 66 L66 88"), with: pleat, style: pleatStyle)
    c.stroke(Path(svg: "M69 66 L74 88"), with: pleat, style: pleatStyle)
    c.stroke(Path(svg: "M73 66 L84 88"), with: pleat, style: pleatStyle)

    let waist = Path(svg: "M52 60 Q52 58 55 58 L73 58 Q76 58 76 60 L76 64 L52 64 Z")
    c.fill(waist, with: band)
    c.stroke(waist, with: .hex("#D9604B"), style: stroke(1.4))

    rotatedEllipse(&c, degrees: 6, cx: 56, cy: 74, rx: 7, ry: 9, paint: .hex("#FFFFFF", opacity: 0.22))

    // neck
    c.fill(Path(roundedRect: CGRect(x: 60, y: 35, width: 8, height: 8), cornerRadius: 2), with: limb)

    // hair back
    c.fill(
        Path(
            svg: "M64 13 C53 13 51 20 51 29 C51 34 52 37 54 39 L56 31 C56 23 59 19 64 19 C69 19 72 23 72 31 L74 39 C76 37 77 34 77 29 C77 20 75 13 64 13 Z"
        ),
        with: hair
    )

    // head
    let head = circle(64, 27, 12)
    c.fill(head, with: limb)
    c.stroke(head, with: outline, style: stroke(1))

    // fringe
    c.fill(Path(svg: "M53 24 Q56 15 64 15 Q72 15 75 24 Q64 19 53 24 Z"), with: hair)

    // face
    c.fill(circle(59.5, 27, 1.4), with: .hex("#6B7178"))
    c.fill(circle(68.5, 27, 1.4), with: .hex("#6B7178"))
    c.stroke(Path(svg: "M60 32 Q64 35 68 32"), with: .hex("#6B7178"), style: stroke(1.3, cap: .round))
}

// MARK: - Pyjama

private func drawPyjama(into c: inout SVGCanvas) {
    let fab = diagonal("#B7E8DD", "#83CCBD")  // py-fab
    let cuf = diagonal("#6FC2B3", "#4EA494")  // py-cuf
    let edge = SVGPaint.hex("#4EA494")
    let dot = SVGPaint.hex("#FFFFFF", opacity: 0.8)

    c.fill(ellipse(64, 107, 40, 6), with: dropShadow)

    let sleeveL = Path(svg: "M50 33 L28 43 Q25 45 25 49 L26 55 Q26 58 30 57 L40 53 L50 47 Z")
    c.fill(sleeveL, with: fab)
    c.stroke(sleeveL, with: edge, style: stroke(1.3))
    let sleeveR = Path(svg: "M78 33 L100 43 Q103 45 103 49 L102 55 Q102 58 98 57 L88 53 L78 47 Z")
    c.fill(sleeveR, with: fab)
    c.stroke(sleeveR, with: edge, style: stroke(1.3))
    let top = Path(svg: "M47 35 Q47 32 50 32 L78 32 Q81 32 81 35 L82 60 Q82 63 79 63 L49 63 Q46 63 46 60 Z")
    c.fill(top, with: fab)
    c.stroke(top, with: edge, style: stroke(1.3))

    let collar = Path(svg: "M58 31 L70 31 L68 38 L60 38 Z")
    c.fill(collar, with: cuf)
    c.stroke(collar, with: edge, style: stroke(1))

    c.fill(Path(svg: "M24 50 L32 47 L35 55 L27 58 Q24 58 24 55 Z"), with: cuf)
    c.fill(Path(svg: "M104 50 L96 47 L93 55 L101 58 Q104 58 104 55 Z"), with: cuf)
    c.fill(Path(svg: "M46 58 L82 58 L82 60 Q82 63 79 63 L49 63 Q46 63 46 60 Z"), with: cuf)

    // <line x1="64" y1="39" x2="64" y2="57" stroke="#4EA494" stroke-width="1.2"/>
    var placket = Path()
    placket.move(to: CGPoint(x: 64, y: 39))
    placket.addLine(to: CGPoint(x: 64, y: 57))
    c.stroke(placket, with: edge, style: stroke(1.2))

    for cy in [42.0, 49.0, 56.0] {
        let button = circle(64, cy, 1.8)
        c.fill(button, with: .hex("#FDF6E7"))
        c.stroke(button, with: edge, style: stroke(0.7))
    }

    // <g fill="#FFFFFF" fill-opacity="0.8">
    c.fill(circle(55, 45, 1.6), with: dot)
    c.fill(circle(73, 47, 1.6), with: dot)
    c.fill(circle(58, 54, 1.6), with: dot)
    c.fill(circle(74, 55, 1.6), with: dot)

    let waistband = Path(svg: "M48 68 Q48 66 51 66 L77 66 Q80 66 80 68 L80 73 L48 73 Z")
    c.fill(waistband, with: cuf)
    c.stroke(waistband, with: edge, style: stroke(1.2))

    let trousers = Path(
        svg: "M48 73 L45 99 Q45 102 49 102 L59 102 Q62 102 62 99 L64 84 L66 99 Q66 102 69 102 L79 102 Q83 102 82 99 L80 73 Z"
    )
    c.fill(trousers, with: fab)
    c.stroke(trousers, with: edge, style: stroke(1.3))

    c.fill(Path(svg: "M45 97 L59 97 L59 99 Q59 102 56 102 L49 102 Q45 102 45 99 Z"), with: cuf)
    c.fill(Path(svg: "M66 97 L80 97 L80 99 Q80 102 77 102 L69 102 Q66 102 66 99 Z"), with: cuf)

    // <g fill="#FFFFFF" fill-opacity="0.8">
    c.fill(circle(53, 82, 1.6), with: dot)
    c.fill(circle(74, 84, 1.6), with: dot)
    c.fill(circle(51, 92, 1.6), with: dot)
    c.fill(circle(75, 93, 1.6), with: dot)
    c.fill(circle(58, 90, 1.6), with: dot)
    c.fill(circle(71, 92, 1.6), with: dot)

    rotatedEllipse(&c, degrees: -18, cx: 56, cy: 42, rx: 13, ry: 7, paint: .hex("#FFFFFF", opacity: 0.28))
}

// MARK: - Macaron

private func drawMacaron(into c: inout SVGCanvas) {
    let side = diagonal("#F2A7C1", "#DE7CA1")  // mac-side
    let top = diagonal("#FBD1DF", "#F1A7C2")   // mac-top
    let fill = diagonal("#FDEDCE", "#EFCC8E")  // mac-fill
    let shellEdge = SVGPaint.hex("#DA82A5")

    c.fill(ellipse(64, 93, 38, 6), with: dropShadow)

    let ganache = Path(svg: "M28 55 A36 7 0 0 0 100 55 L98 70 L30 70 Z")
    c.fill(ganache, with: fill)
    c.stroke(ganache, with: .hex("#E3BC7C"), style: stroke(0.8))

    let bottom = Path(
        svg: "M30 70 Q34.25 74.8 38.5 74.6 Q42.75 77.85 47 76.1 Q51.25 78.95 55.5 76.8 Q59.75 79.4 64 77 Q68.25 79.4 72.5 76.8 Q76.75 78.95 81 76.1 Q85.25 77.85 89.5 74.6 Q93.75 74.8 98 70 C101 82 86 89 64 89 C42 89 28 82 30 70 Z"
    )
    c.fill(bottom, with: side)
    c.stroke(bottom, with: shellEdge, style: stroke(1.3))

    let upper = Path(
        svg: "M29 42 A35 10 0 0 0 99 42 L100 55 Q95.5 59.8 91 59.6 Q86.5 62.85 82 61.1 Q77.5 63.95 73 61.8 Q68.5 64.4 64 62 Q59.5 64.4 55 61.8 Q50.5 63.95 46 61.1 Q41.5 62.85 37 59.6 Q32.5 59.8 28 55 Z"
    )
    c.fill(upper, with: side)
    c.stroke(upper, with: shellEdge, style: stroke(1.3))

    let cap = ellipse(64, 42, 35, 10)
    c.fill(cap, with: top)
    c.stroke(cap, with: shellEdge, style: stroke(1.2))

    rotatedEllipse(&c, degrees: -16, cx: 53, cy: 38, rx: 15, ry: 5, paint: .hex("#FFFFFF", opacity: 0.45))
}

// MARK: - Igloo

private func drawIgloo(into c: inout SVGCanvas) {
    let dome = diagonal("#FFFFFF", "#D2E1EF")  // ig-dome
    // ig-door is the one VERTICAL gradient: x1="0" y1="0" x2="0" y2="1".
    let door = SVGPaint.linear(
        [.svg("#B4CADC", at: 0), .svg("#8DA8C0", at: 1)],
        from: CGPoint(x: 0, y: 0),
        to: CGPoint(x: 0, y: 1)
    )
    let domeEdge = SVGPaint.hex("#A9C6DE")
    let brick = SVGPaint.hex("#B4CFE4")

    c.fill(ellipse(64, 98, 46, 7), with: dropShadow)

    let shell = Path(svg: "M20 92 A44 40 0 0 1 108 92 Z")
    c.fill(shell, with: dome)
    c.stroke(shell, with: domeEdge, style: stroke(2))

    rotatedEllipse(&c, degrees: -20, cx: 46, cy: 62, rx: 20, ry: 12, paint: .hex("#FFFFFF", opacity: 0.45))

    // <g fill="none" stroke="#B4CFE4" stroke-width="2" stroke-linecap="round">
    let brickStyle = stroke(2, cap: .round)
    for d in [
        "M24 80 Q64 72 104 80",
        "M28 66 Q64 59 100 66",
        "M40 56 Q64 51 88 56",
        "M44 90 L45 81",
        "M64 91 L64 80",
        "M84 90 L83 81",
        "M53 78 L54 67",
        "M75 78 L74 67",
        "M54 64 L55 57",
        "M74 64 L73 57",
    ] {
        c.stroke(Path(svg: d), with: brick, style: brickStyle)
    }

    let porch = Path(svg: "M47 92 L47 78 A17 17 0 0 1 81 78 L81 92 Z")
    c.fill(porch, with: dome)
    c.stroke(porch, with: domeEdge, style: stroke(2))

    c.fill(Path(svg: "M53 92 L53 79 A11 11 0 0 1 75 79 L75 92 Z"), with: door)

    c.stroke(Path(svg: "M47 78 A17 17 0 0 1 81 78"), with: brick, style: stroke(1.6))
}

// MARK: - View

/// A word illustration, scaled to fill whatever square it is given.
///
/// The web renders these as `<img src={word.img}>` and lets the exercise decide
/// the box, so this view carries no intrinsic size either — put it in a frame.
public struct WordImage: View {
    public let key: ImageKey

    public init(key: ImageKey) {
        self.key = key
    }

    public var body: some View {
        let key = key
        Canvas { context, canvasSize in
            var canvas = SVGCanvas(
                viewBox: WordImages.viewBox,
                fitting: CGRect(origin: .zero, size: canvasSize)
            )
            WordImages.draw(key, into: &canvas)
            var ctx = context
            canvas.render(into: &ctx)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement()
        .accessibilityLabel(WordImages.label(key))
        .accessibilityAddTraits(.isImage)
    }
}
