import CoreGraphics
import SwiftUI
import Testing

import ALCore
@testable import ALArt

// The four `src/img/*.svg` word illustrations.
//
// The element counts below were counted BY HAND off the SVG files, element by
// element, with a fill and a stroke on the same element counting as two draws
// (that is what `SVGCanvas` records). They are the check that nothing was
// silently dropped in transcription — the most likely port bug in a 63-line
// file of near-identical `<path>` elements.

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
    for node in nodes {
        let r: CGRect
        switch node {
        case let .fill(path, transform, _, _): r = path.applying(transform).boundingRect
        case let .stroke(path, transform, _, _, _): r = path.applying(transform).boundingRect
        case let .group(_, children): r = bounds(children) ?? .null
        case let .mask(_, content): r = bounds(content) ?? .null
        }
        guard !r.isNull, !r.isInfinite else { continue }
        box = box.map { $0.union(r) } ?? r
    }
    return box
}

private func draw(_ key: ImageKey) -> [SVGDrawNode] {
    var canvas = SVGCanvas()
    WordImages.draw(key, into: &canvas)
    return canvas.nodes
}

private func paints(_ nodes: [SVGDrawNode]) -> [SVGPaint] {
    nodes.compactMap { node in
        switch node {
        case let .fill(_, _, _, paint): return paint
        case let .stroke(_, _, _, paint, _): return paint
        default: return nil
        }
    }
}

@Suite("Word images — the four illustrations")
struct WordImageTests {

    @Test("the <title> of each file is its accessibility label")
    func labels() {
        #expect(WordImages.label(.igloo) == "Igloo")
        #expect(WordImages.label(.jupe) == "Jupe")
        #expect(WordImages.label(.macaron) == "Macaron")
        #expect(WordImages.label(.pyjama) == "Pyjama")
        // Exhaustive by construction; this catches a label copied twice.
        let all = ImageKey.allCases.map(WordImages.label)
        #expect(Set(all).count == ImageKey.allCases.count)
    }

    @Test("every key draws something")
    func everyKeyDraws() {
        for key in ImageKey.allCases {
            #expect(leafCount(draw(key)) > 0, "\(key.rawValue) renders nothing")
        }
    }

    @Test("element counts match the SVG files, hand-counted")
    func elementCounts() {
        // jupe.svg: shadow 1 · 2 legs (fill+stroke) 4 · 2 shoes 2 · torso 2 ·
        //           2 arms 4 · 2 hands 4 · skirt 2 · 6 pleats 6 · band 2 ·
        //           highlight 1 · neck 1 · hair-back 1 · head 2 · fringe 1 ·
        //           2 eyes 2 · mouth 1  = 36
        #expect(leafCount(draw(.jupe)) == 36)
        // pyjama.svg: shadow 1 · 2 sleeves 4 · top 2 · collar 2 · 2 cuffs 2 ·
        //             hem 1 · placket 1 · 3 buttons 6 · 4 dots 4 · waistband 2 ·
        //             trousers 2 · 2 leg cuffs 2 · 6 dots 6 · highlight 1 = 36
        #expect(leafCount(draw(.pyjama)) == 36)
        // macaron.svg: shadow 1 · ganache 2 · bottom 2 · top 2 · cap 2 · highlight 1 = 10
        #expect(leafCount(draw(.macaron)) == 10)
        // igloo.svg: shadow 1 · dome 2 · highlight 1 · 10 bricks 10 · porch 2 ·
        //            door 1 · lintel arc 1 = 18
        #expect(leafCount(draw(.igloo)) == 18)
    }

    @Test("nothing escapes the 128-unit viewBox")
    func stayInTheBox() {
        for key in ImageKey.allCases {
            guard let box = bounds(draw(key)) else {
                Issue.record("\(key.rawValue) has no geometry")
                continue
            }
            #expect(box.minX >= 0, "\(key.rawValue): \(box)")
            #expect(box.minY >= 0, "\(key.rawValue): \(box)")
            #expect(box.maxX <= 128, "\(key.rawValue): \(box)")
            #expect(box.maxY <= 128, "\(key.rawValue): \(box)")
        }
    }

    @Test("gradients keep their axis — eleven diagonal, the igloo door vertical")
    func gradientAxes() {
        // Every <linearGradient> in these four files is x1=0 y1=0 x2=1 y2=1
        // EXCEPT ig-door, which is x1=0 y1=0 x2=0 y2=1. Swapping the two is
        // invisible in review and obvious on screen.
        func axes(_ key: ImageKey) -> [(CGPoint, CGPoint)] {
            paints(draw(key)).compactMap { paint in
                if case let .linear(_, start, end) = paint.kind { return (start, end) }
                return nil
            }
        }
        for key in [ImageKey.jupe, .pyjama, .macaron] {
            let all = axes(key)
            #expect(!all.isEmpty, "\(key.rawValue) lost its gradients")
            #expect(
                all.allSatisfy { $0.0 == CGPoint(x: 0, y: 0) && $0.1 == CGPoint(x: 1, y: 1) },
                "\(key.rawValue) has a non-diagonal gradient"
            )
        }
        let igloo = axes(.igloo)
        #expect(igloo.contains { $0.0 == CGPoint(x: 0, y: 0) && $0.1 == CGPoint(x: 0, y: 1) },
                "the igloo door's vertical gradient is missing")
        #expect(igloo.contains { $0.0 == CGPoint(x: 0, y: 0) && $0.1 == CGPoint(x: 1, y: 1) },
                "the igloo dome's diagonal gradient is missing")
    }

    @Test("every gradient resolves against the shape's bounding box, SVG's default")
    func gradientUnits() {
        // No file declares gradientUnits, so all twelve are objectBoundingBox
        // (D24). Passing userSpaceOnUse would smear each gradient across the
        // whole 128-unit canvas instead of the element it fills.
        for key in ImageKey.allCases {
            for paint in paints(draw(key)) {
                switch paint.kind {
                case .linear, .radial:
                    #expect(paint.units == .objectBoundingBox, "\(key.rawValue)")
                case .color, .none:
                    break
                }
            }
        }
    }

    @Test("each illustration opens with its 10%-black ground shadow")
    func groundShadow() {
        for key in ImageKey.allCases {
            let first = draw(key).first
            guard case let .fill(_, _, _, paint) = first else {
                Issue.record("\(key.rawValue) does not start with a fill")
                continue
            }
            #expect(paint.opacity == 0.10, "\(key.rawValue)'s shadow lost its fill-opacity")
        }
    }
}

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

@Suite("Word images — rendered", .serialized)
@MainActor
struct WordImageRenderTests {

    @Test("the macaron's shell is pink where the shell is, and the corners are empty")
    func macaronPixels() throws {
        let r = try #require(raster(CGSize(width: 128, height: 128)) { WordImage(key: .macaron) })
        // The top shell is an ellipse cx 64 cy 42 rx 35 ry 10, mac-top
        // #FBD1DF → #F1A7C2: pink, i.e. red clearly above blue above nothing.
        let shell = pixel(r, 64, 42)
        #expect(shell.a > 200, "expected the shell to be opaque, got \(shell)")
        #expect(shell.r > 230 && shell.g < 225 && shell.b > 180, "expected macaron pink, got \(shell)")
        #expect(pixel(r, 2, 2).a < 20, "the top-left corner should be empty")
    }

    @Test("the igloo's dome is pale blue and its doorway is darker")
    func iglooPixels() throws {
        let r = try #require(raster(CGSize(width: 128, height: 128)) { WordImage(key: .igloo) })
        // ig-dome runs #FFFFFF → #D2E1EF; the doorway is ig-door #B4CADC → #8DA8C0.
        // The dome is `M20 92 A44 40 0 0 1 108 92 Z`, so it spans y 52…92 —
        // sample inside it and clear of the brick lines at y ≈ 53.5 and 62.5.
        let dome = pixel(r, 64, 57)
        let door = pixel(r, 64, 88)
        #expect(dome.a > 200)
        #expect(door.a > 200)
        #expect(door.r < dome.r, "the doorway must be darker than the dome: \(door) vs \(dome)")
        #expect(door.b > door.r, "the doorway is blue-leaning: \(door)")
    }

    @Test("the jupe's skirt is the only warm colour on the figure")
    func jupePixels() throws {
        let r = try #require(raster(CGSize(width: 128, height: 128)) { WordImage(key: .jupe) })
        // ju-skirt #FBA895 → #EF7A64 around (64, 80); the shirt above it is
        // grey (ju-shirt #BEC4CB → #A2A9B1).
        let skirt = pixel(r, 64, 80)
        let shirt = pixel(r, 64, 50)
        #expect(skirt.a > 200 && shirt.a > 200)
        #expect(skirt.r - skirt.b > 60, "the skirt should be warm: \(skirt)")
        #expect(abs(shirt.r - shirt.b) < 25, "the shirt should be neutral grey: \(shirt)")
    }
}

#endif
