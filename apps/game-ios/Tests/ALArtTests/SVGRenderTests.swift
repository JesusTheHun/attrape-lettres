import CoreGraphics
import SwiftUI
import Testing
@testable import ALArt

// Rasterisation tests. Everything else in ALArtTests asserts on the recorded
// draw list; these actually run the replay through a real `GraphicsContext` and
// read the pixels back.
//
// That matters for exactly one reason: `SVGCanvas` composes its own CTM, and
// `render` hands that matrix to `GraphicsContext.concatenate`. If the two
// compose in opposite directions every mascot in the app is wrong in a way no
// draw-list assertion can see. `ImageRenderer` works on macOS, so this runs on
// the host with the rest of the suite — no simulator involved.

#if canImport(AppKit) || canImport(UIKit)

/// Rasterise a view and read back sRGB pixels.
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

private func pixel(_ r: (px: [UInt8], w: Int, h: Int), _ x: Int, _ y: Int) -> (Int, Int, Int, Int) {
    let i = (y * r.w + x) * 4
    return (Int(r.px[i]), Int(r.px[i + 1]), Int(r.px[i + 2]), Int(r.px[i + 3]))
}

/// A view that replays a draw list built by `build`.
private struct Replay: View {
    let build: (inout SVGCanvas) -> Void
    var body: some View {
        Canvas { context, _ in
            var canvas = SVGCanvas()
            build(&canvas)
            var ctx = context
            canvas.render(into: &ctx)
        }
    }
}

@Suite("SVG canvas — rasterisation", .serialized)
@MainActor
struct SVGRenderTests {

    @Test("a translated square lands where the CTM says it does")
    func translationDirection() throws {
        // The single highest-consequence check in ALArt: SVGCanvas composes its
        // own matrix, GraphicsContext.concatenate applies it. If the two
        // disagree about direction the square appears at (0,0) instead of
        // (40,40) and every mascot is silently wrong.
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.translate(40, 40)
                c.fill(Path(CGRect(x: 0, y: 0, width: 20, height: 20)), with: .hex("#FF0000"))
            }
        })
        let inside = pixel(r, 50, 50)
        let origin = pixel(r, 5, 5)
        #expect(inside.0 > 200 && inside.1 < 60, "expected red at (50,50), got \(inside)")
        #expect(origin.3 < 20, "expected nothing at the origin, got \(origin)")
    }

    @Test("composition order matches an SVG transform list")
    func compositionOrderRasterised() throws {
        // transform="translate(40 40) scale(2)" on a 10x10 square at the origin
        // covers 40..60 in both axes. Scale applies to the geometry first.
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.translate(40, 40)
                c.scale(2)
                c.fill(Path(CGRect(x: 0, y: 0, width: 10, height: 10)), with: .hex("#FF0000"))
            }
        })
        #expect(pixel(r, 50, 50).0 > 200, "expected red at the centre of 40…60")
        #expect(pixel(r, 58, 58).0 > 200, "expected red near the far corner")
        #expect(pixel(r, 70, 70).3 < 20, "expected nothing past 60")
    }

    @Test("an anchored rotation pivots about its anchor")
    func anchoredRotationRasterised() throws {
        // rotate(90 50 50) takes the square x∈[50,70], y∈[45,55] to
        // x∈[45,55], y∈[50,70]. Sampling (52,60) hits only the rotated one.
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.rotate(degrees: 90, about: CGPoint(x: 50, y: 50))
                c.fill(Path(CGRect(x: 50, y: 45, width: 20, height: 10)), with: .hex("#0000FF"))
            }
        })
        #expect(pixel(r, 52, 60).2 > 200, "expected blue where the anchored rotation puts it")
        #expect(pixel(r, 60, 52).3 < 20, "expected nothing where an UNrotated square would be")
    }

    @Test("a bounding-box radial gradient stretches with the shape, it does not draw a circle")
    func boundingBoxGradientIsAnisotropic() throws {
        // Every gradient in the app is objectBoundingBox (none declares
        // gradientUnits), so this is the normal case, not an edge case. On a
        // 80x20 ellipse the gradient must reach the left and right edges at the
        // same relative position it reaches the top and bottom — a circular
        // gradient would fade out long before x=8.
        let stops: [Gradient.Stop] = [
            .svg("#FFFFFF", at: 0, opacity: 1),
            .svg("#FFFFFF", at: 1, opacity: 0),
        ]
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.fill(Path(ellipseIn: CGRect(x: 10, y: 40, width: 80, height: 20)),
                       with: .radial(stops))
            }
        })
        let centre = pixel(r, 50, 50)
        let nearLeftEdge = pixel(r, 16, 50)
        #expect(centre.3 > 200, "expected the gradient centre to be opaque")
        // Under a circular gradient of radius 10 (half the SHORT axis) this
        // sample would be fully transparent; under the correct elliptical one
        // it is still partly covered.
        #expect(nearLeftEdge.3 > 20, "bbox gradient collapsed to a circle: alpha \(nearLeftEdge.3) at x=16")
    }

    @Test("group opacity composites once, so overlapping children do not double-darken")
    func groupOpacityCompositesAsOneLayer() throws {
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.group(opacity: 0.5) { g in
                    g.fill(Path(CGRect(x: 10, y: 10, width: 50, height: 50)), with: .hex("#000000"))
                    g.fill(Path(CGRect(x: 30, y: 30, width: 50, height: 50)), with: .hex("#000000"))
                }
            }
        })
        let single = pixel(r, 20, 20).3
        let overlap = pixel(r, 40, 40).3
        #expect(single > 100 && single < 160, "expected ~50% alpha, got \(single)")
        #expect(abs(overlap - single) < 12, "overlap double-darkened: \(single) vs \(overlap)")
    }

    @Test("a clip bounds what is drawn")
    func clipBounds() throws {
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.clip(to: Path(CGRect(x: 0, y: 0, width: 50, height: 100)))
                c.fill(Path(CGRect(x: 0, y: 0, width: 100, height: 100)), with: .hex("#FF0000"))
            }
        })
        #expect(pixel(r, 25, 50).0 > 200, "expected red inside the clip")
        #expect(pixel(r, 75, 50).3 < 20, "expected nothing outside the clip")
    }

    @Test("a mask modulates content by the matte's alpha")
    func maskUsesAlpha() throws {
        let r = try #require(raster(CGSize(width: 100, height: 100)) {
            Replay { c in
                c.mask({ m in
                    m.fill(Path(CGRect(x: 0, y: 0, width: 50, height: 100)), with: .hex("#FFFFFF"))
                }, content: { body in
                    body.fill(Path(CGRect(x: 0, y: 0, width: 100, height: 100)), with: .hex("#00FF00"))
                })
            }
        })
        #expect(pixel(r, 25, 50).1 > 200, "expected green where the matte is opaque")
        #expect(pixel(r, 75, 50).3 < 20, "expected nothing where the matte is absent")
    }

    @Test("stroke width scales with the CTM, as SVG scales it")
    func strokeWidthScalesWithCTM() throws {
        func coveredColumn(scale: Double) throws -> Int {
            let r = try #require(raster(CGSize(width: 100, height: 100)) {
                Replay { c in
                    c.scale(scale)
                    c.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .none)
                    c.stroke(
                        Path { p in
                            p.move(to: CGPoint(x: 10, y: 0))
                            p.addLine(to: CGPoint(x: 10, y: 100))
                        },
                        with: .hex("#000000"),
                        style: StrokeStyle(lineWidth: 2)
                    )
                }
            })
            return (0 ..< 100).filter { pixel(r, $0, 50).3 > 128 }.count
        }
        let thin = try coveredColumn(scale: 1)
        let thick = try coveredColumn(scale: 3)
        #expect(thin > 0, "expected the 1x stroke to draw")
        #expect(thick > thin, "stroke width did not scale with the CTM: \(thin) vs \(thick)")
    }
}

#endif
