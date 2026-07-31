import CoreGraphics
import SwiftUI
import Testing

import ALCore
@testable import ALArt

// Invariant 7's runtime half. The compile-time half is the `switch` with no
// `default:` in `ExerciseIconCatalog.swift`, which catches a MISSING icon; only
// a test catches a branch that compiles because it was copy-pasted from its
// neighbour.
//
// Every number here is read off `src/components/ExerciseIcon.tsx`.

// MARK: - Draw-list helpers

private func leafCount(_ nodes: [SVGDrawNode]) -> Int {
    nodes.reduce(0) { total, node in
        switch node {
        case .fill, .stroke: return total + 1
        case let .group(_, children): return total + leafCount(children)
        case let .mask(_, content): return total + leafCount(content)
        }
    }
}

private func bounds(_ nodes: [SVGDrawNode]) -> CGRect? {
    var box: CGRect?
    func add(_ r: CGRect) {
        guard !r.isNull, !r.isInfinite else { return }
        box = box.map { $0.union(r) } ?? r
    }
    for node in nodes {
        switch node {
        case let .fill(path, transform, _, _):
            add(path.applying(transform).boundingRect)
        case let .stroke(path, transform, _, _, _):
            add(path.applying(transform).boundingRect)
        case let .group(_, children):
            if let b = bounds(children) { add(b) }
        case let .mask(_, content):
            if let b = bounds(content) { add(b) }
        }
    }
    return box
}

private func shapes(_ spec: ExerciseIconSpec) -> [SVGDrawNode] {
    spec.nodes.flatMap { node -> [SVGDrawNode] in
        if case let .shapes(list) = node { return list }
        return []
    }
}

private func texts(_ spec: ExerciseIconSpec) -> [IconText] {
    spec.nodes.compactMap { node in
        if case let .text(t) = node { return t }
        return nil
    }
}

private func strokeStyles(_ nodes: [SVGDrawNode]) -> [StrokeStyle] {
    nodes.compactMap { node in
        if case let .stroke(_, _, _, _, style) = node { return style }
        return nil
    }
}

// MARK: - Totality

@Suite("Exercise icons — invariant 7")
struct ExerciseIconTotalityTests {

    @Test("every exercise has an icon with real geometry in it")
    func everyIconIsDrawn() {
        for id in ExerciseId.allCases {
            let spec = exerciseIconSpec(id)
            #expect(!spec.nodes.isEmpty, "\(id.rawValue) has no glyph at all")

            let drawn = leafCount(shapes(spec)) + texts(spec).count
            #expect(drawn > 0, "\(id.rawValue) draws nothing")

            // A `.shapes([])` node would satisfy "non-empty nodes" and render
            // nothing; forbid it outright.
            for node in spec.nodes {
                switch node {
                case let .shapes(list):
                    #expect(!list.isEmpty, "\(id.rawValue) has an empty shape run")
                case let .text(t):
                    #expect(!t.string.isEmpty, "\(id.rawValue) has an empty text node")
                    #expect(t.size > 0, "\(id.rawValue) has a zero-size glyph")
                }
            }
        }
    }

    @Test("all 17 tints are distinct — a copy-pasted branch compiles fine")
    func tintsAreDistinct() {
        let tints = ExerciseId.allCases.map { exerciseIconSpec($0).tint }
        #expect(tints.count == 17)
        #expect(Set(tints).count == tints.count, "two exercises share a badge colour: \(tints)")
        // The TSX writes every tint as a 6-digit hex.
        #expect(tints.allSatisfy { $0.count == 7 && $0.hasPrefix("#") })
    }

    @Test("no two exercises share the same drawing either")
    func glyphsAreDistinct() {
        // Bounding box + leaf count + text content is a cheap fingerprint; two
        // identical branches collide on all three.
        var seen: [String: ExerciseId] = [:]
        for id in ExerciseId.allCases {
            let spec = exerciseIconSpec(id)
            let box = bounds(shapes(spec)) ?? .zero
            let key = "\(leafCount(shapes(spec)))|\(texts(spec).map(\.string).joined())|"
                + String(format: "%.3f,%.3f,%.3f,%.3f", box.minX, box.minY, box.width, box.height)
            if let other = seen[key] {
                Issue.record("\(id.rawValue) draws the same thing as \(other.rawValue)")
            }
            seen[key] = id
        }
    }

    @Test("every icon stays inside the 32-unit badge")
    func geometryStaysInTheBox() {
        // Nothing overflows in the TSX; the widest reach is the magnifier
        // handle at (23.5, 24) and the pleat/pencil marks, all < 28.
        for id in ExerciseId.allCases {
            guard let box = bounds(shapes(exerciseIconSpec(id))) else { continue }
            #expect(box.minX >= 0, "\(id.rawValue) draws left of the box: \(box)")
            #expect(box.minY >= 0, "\(id.rawValue) draws above the box: \(box)")
            #expect(box.maxX <= 32, "\(id.rawValue) draws right of the box: \(box)")
            #expect(box.maxY <= 32, "\(id.rawValue) draws below the box: \(box)")
        }
    }
}

// MARK: - Individual glyphs

@Suite("Exercise icons — the glyphs themselves")
struct ExerciseIconGlyphTests {

    @Test("the shared `line` style is the TSX's: white, 2.4, round, round")
    func lineStyle() {
        let line = IconStroke.line
        #expect(line.color == "#fff")
        #expect(line.width == 2.4)
        #expect(line.opacity == 1)
        #expect(line.dash.isEmpty)
        #expect(line.lineCap == .round)
        #expect(line.lineJoin == .round)
    }

    @Test("there are exactly four <text> glyphs in the whole file: A, V, A, a")
    func theFourGlyphs() {
        let all = ExerciseId.allCases.flatMap { texts(exerciseIconSpec($0)) }
        #expect(all.count == 4)
        #expect(all.map(\.string) == ["A", "V", "A", "a"])
        #expect(all.allSatisfy { $0.weight == .black })
        #expect(all.allSatisfy { $0.fill == "#fff" })

        // <Glyph x={15} y={17.5} size={17}>A</Glyph>
        #expect(all[0] == IconText("A", x: 15, y: 17.5, size: 17))
        // <Glyph x={11} y={16} size={17}>V</Glyph>
        #expect(all[1] == IconText("V", x: 11, y: 16, size: 17))
        // <Glyph x={12} y={18} size={16}>A</Glyph> / <Glyph x={22} y={19} size={11}>a</Glyph>
        #expect(all[2] == IconText("A", x: 12, y: 18, size: 16))
        #expect(all[3] == IconText("a", x: 22, y: 19, size: 11))
    }

    @Test("first-letter: the sparkle draws AFTER the A, and sits top-right of it")
    func firstLetterOrder() {
        let spec = exerciseIconSpec(.firstLetter)
        #expect(spec.tint == "#FF8A5B")
        // Painter's order: <Glyph> then <path>. Reordering would put the
        // sparkle under the letterform.
        #expect(spec.nodes.count == 2)
        guard case .text = spec.nodes[0] else {
            Issue.record("the A must be drawn first")
            return
        }
        guard case .shapes = spec.nodes[1] else {
            Issue.record("the sparkle must be drawn second")
            return
        }
        // d="M24 5.5 … L20.5 9 …" — x ∈ [20.5, 27.5], y ∈ [5.5, 12.5].
        let box = bounds(shapes(spec))
        #expect(abs((box?.minX ?? 0) - 20.5) < 1e-6)
        #expect(abs((box?.maxX ?? 0) - 27.5) < 1e-6)
        #expect(abs((box?.minY ?? 0) - 5.5) < 1e-6)
        #expect(abs((box?.maxY ?? 0) - 12.5) < 1e-6)
    }

    @Test("pick-vowel: the V is drawn before its dashed box, dash 2.5/2.3, square caps")
    func pickVowelDash() {
        let spec = exerciseIconSpec(.pickVowel)
        guard case .text = spec.nodes[0] else {
            Issue.record("the V must be drawn first")
            return
        }
        let styles = strokeStyles(shapes(spec))
        #expect(styles.count == 1)
        #expect(styles[0].dash == [2.5, 2.3])
        #expect(styles[0].lineWidth == 1.9)
        // This node does NOT spread `line`, so SVG's defaults apply.
        #expect(styles[0].lineCap == .butt)
        #expect(styles[0].lineJoin == .miter)
    }

    @Test("fill-blank: only the MIDDLE slot is dashed")
    func fillBlankDash() {
        let nodes = shapes(exerciseIconSpec(.fillBlank))
        #expect(leafCount(nodes) == 3)
        let styles = strokeStyles(nodes)
        #expect(styles.count == 1, "exactly one of the three slots is an outline")
        #expect(styles[0].dash == [2.4, 2.2])
    }

    @Test("the two mêlées twins wear the shuffle chip; their un-mixed siblings do not")
    func shuffleChipOnlyOnTheMixedPair() {
        // The chip is a white circle at (23.5, 23.5) r 6.6 → it pushes the
        // drawing out to x = 30.1. The plain bullseye stops at 24.
        for id in [ExerciseId.spellSyllablePlusMixed, .spellTwoSyllablesMixed] {
            let box = bounds(shapes(exerciseIconSpec(id)))
            #expect(abs((box?.maxX ?? 0) - 30.1) < 1e-6, "\(id.rawValue) is missing the chip: \(String(describing: box))")
            #expect(abs((box?.maxY ?? 0) - 30.1) < 1e-6)
        }
        let plain = bounds(shapes(exerciseIconSpec(.spellSyllablePlus)))
        #expect(abs((plain?.maxX ?? 0) - 24) < 1e-6, "the un-mixed bullseye must not wear a chip")
    }

    @Test("find-intruder's little x keeps a round cap but SVG's default miter join")
    func intruderCross() {
        let styles = strokeStyles(shapes(exerciseIconSpec(.findIntruder)))
        #expect(styles.count == 3)
        // circle + handle use `line`; the x names its own attributes.
        let cross = styles[2]
        #expect(cross.lineWidth == 1.8)
        #expect(cross.lineCap == .round)
        #expect(cross.lineJoin == .miter)
    }

    @Test("element counts match the TSX, hand-counted per icon")
    func elementCounts() {
        // Counted off `GLYPHS` element by element (a `<circle {...line}>` is
        // one stroke; the ShuffleChip is 1 fill + 4 strokes). Catches a dropped
        // wave, tile or arrow that every other assertion here would tolerate.
        let expected: [ExerciseId: Int] = [
            .firstLetter: 1,               // sparkle (+1 text)
            .findSound: 5,
            .hearSyllable: 5,
            .pickVowel: 1,                 // dashed box (+1 text)
            .fillBlank: 3,
            .orderSyllables: 5,
            .findIntruder: 3,
            .spellSound: 3,
            .spellSyllable: 2,
            .spellSyllablePlus: 3,
            .spellTwoSyllables: 3,
            .readImage: 3,
            .matchCase: 0,                 // two texts, no shapes
            .matchScript: 1,
            .soundTwins: 3,
            .spellSyllablePlusMixed: 3 + 5,
            .spellTwoSyllablesMixed: 3 + 5,
        ]
        for id in ExerciseId.allCases {
            #expect(leafCount(shapes(exerciseIconSpec(id))) == expected[id], "\(id.rawValue)")
        }
    }

    @Test("the arc commands actually produce arcs — an unparsed `a` would draw nothing")
    func arcsParse() {
        // find-sound's outer wave is `M15.6 10.4 a5.2 5.2 0 0 1 0 7.2`: a chord
        // of 7.2 on a circle of radius 5.2, so it bulges by the sagitta
        // 5.2 − √(5.2² − 3.6²) = 1.4477. A parser that dropped the arc would
        // leave a degenerate point.
        let nodes = shapes(exerciseIconSpec(.findSound))
        guard case let .stroke(path, transform, _, _, _) = nodes[4] else {
            Issue.record("expected the outer wave last")
            return
        }
        let box = path.applying(transform).boundingRect
        #expect(abs(box.height - 7.2) < 1e-3, "\(box)")
        #expect(abs(box.width - 1.4477) < 5e-3, "\(box)")
    }

    @Test("match-case is two letterforms and nothing else")
    func matchCaseIsTextOnly() {
        let spec = exerciseIconSpec(.matchCase)
        #expect(spec.nodes.count == 2)
        #expect(texts(spec).count == 2)
        #expect(shapes(spec).isEmpty)
    }
}

// MARK: - Rasterisation

#if canImport(AppKit) || canImport(UIKit)

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

private func pixel(_ r: (px: [UInt8], w: Int, h: Int), _ x: Int, _ y: Int) -> (r: Int, g: Int, b: Int, a: Int) {
    let i = (y * r.w + x) * 4
    return (Int(r.px[i]), Int(r.px[i + 1]), Int(r.px[i + 2]), Int(r.px[i + 3]))
}

@Suite("Exercise icons — rendered", .serialized)
@MainActor
struct ExerciseIconRenderTests {

    @Test("the badge fills the tile in its tint and the corners are rounded away")
    func badge() throws {
        // #7C6FF0 at 128 pt.
        let r = try #require(raster(CGSize(width: 128, height: 128)) {
            ExerciseIcon(id: .fillBlank, size: 128)
        })
        let centre = pixel(r, 64, 100)
        #expect(centre.a > 200, "the badge should be opaque")
        #expect(centre.r > 100 && centre.r < 145, "expected #7C6FF0-ish red, got \(centre)")
        #expect(centre.b > 220, "expected #7C6FF0-ish blue, got \(centre)")
        // rx = 8.5 of 32 → the very corner is outside the rounded rect.
        #expect(pixel(r, 2, 2).a < 40, "the corner should be clear, got \(pixel(r, 2, 2))")
    }

    @Test("the <text> glyphs actually render — match-case is nothing but text")
    func textRenders() throws {
        // If the Text overlay silently failed this icon would be a flat orange
        // square, which no geometry assertion above can see.
        let r = try #require(raster(CGSize(width: 128, height: 128)) {
            ExerciseIcon(id: .matchCase, size: 128)
        })
        var whitish = 0
        for y in 0 ..< r.h {
            for x in 0 ..< r.w {
                let p = pixel(r, x, y)
                if p.a > 200, p.r > 235, p.g > 235, p.b > 235 { whitish += 1 }
            }
        }
        #expect(whitish > 200, "expected white letterforms on the badge, found \(whitish) pixels")
    }

    @Test("a pictogram-only icon also puts white on the badge")
    func pictogramRenders() throws {
        let r = try #require(raster(CGSize(width: 128, height: 128)) {
            ExerciseIcon(id: .spellSound, size: 128)
        })
        var whitish = 0
        for y in 0 ..< r.h {
            for x in 0 ..< r.w {
                let p = pixel(r, x, y)
                if p.a > 200, p.r > 235, p.g > 235, p.b > 235 { whitish += 1 }
            }
        }
        #expect(whitish > 200, "expected the white speaker, found \(whitish) pixels")
    }
}

#endif

// MARK: - Text anchoring

@Suite("Exercise icons — SVG text anchoring")
struct SVGTextMetricsTests {

    @Test("dominant-baseline=central puts the alphabetic baseline below the anchor")
    func centralBaseline() {
        // SF Pro's metrics at size 17: ascent ≈ 0.956 em, descent ≈ 0.21 em.
        let m = SVGTextMetrics(ascent: 0.956 * 17, descent: 0.21 * 17)
        // baseline = y + (ascent − descent)/2 = 17.5 + (16.252 − 3.57)/2
        #expect(abs(m.baselineY(anchoredAt: 17.5) - 23.841) < 1e-3)
    }

    @Test("a symmetric font puts the line-box centre exactly on the anchor")
    func lineBoxCentre() {
        // The identity the implementation relies on: top = y − (a + d)/2, so
        // the centre of the line box lands on y whatever a and d are.
        let m = SVGTextMetrics(ascent: 12, descent: 4)
        let topLeft = m.topLeft(x: 15, y: 17.5, width: 10)
        #expect(topLeft.x == 10)            // textAnchor="middle"
        #expect(topLeft.y == 17.5 - 8)      // (12 + 4) / 2
        #expect(topLeft.y + (12 + 4) / 2 == 17.5)
    }

    @Test("a descender-heavy face still centres on the anchor, it does not sit on the baseline")
    func notTheBaseline() {
        let m = SVGTextMetrics(ascent: 20, descent: 8)
        // The naive bug is `at: (x, y), anchor: .center` computed from the
        // BASELINE instead of the line box, which would shift by (a − d)/2 = 6.
        #expect(m.topLeft(x: 0, y: 0, width: 0).y == -14)
        #expect(m.baselineY(anchoredAt: 0) == 6)
    }
}
