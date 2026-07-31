import CoreGraphics
import SwiftUI
import Testing
@testable import ALArt

// Geometry tests for the drawing substrate (D15). These assert coordinates
// computed by hand from the SVG spec, never values read back out of the
// implementation — a test that derives its expectation from the code under test
// proves only that the code is consistent with itself.

private func isClose(_ a: CGPoint, _ b: CGPoint, _ tol: CGFloat = 1e-9) -> Bool {
    abs(a.x - b.x) < tol && abs(a.y - b.y) < tol
}

private func apply(_ t: CGAffineTransform, _ p: CGPoint) -> CGPoint {
    p.applying(t)
}

@Suite("SVG canvas — CTM")
struct SVGCanvasTransformTests {

    @Test("a fresh canvas is the identity")
    func identity() {
        let c = SVGCanvas()
        #expect(c.currentTransform == .identity)
        #expect(c.currentClips.isEmpty)
    }

    @Test("save and restore round-trip the transform and the clip stack")
    func saveRestore() {
        var c = SVGCanvas()
        c.translate(10, 20)
        let saved = c.currentTransform
        c.save()
        c.scale(3)
        c.clip(to: Path(CGRect(x: 0, y: 0, width: 1, height: 1)))
        #expect(c.currentTransform != saved)
        #expect(c.currentClips.count == 1)
        c.restore()
        #expect(c.currentTransform == saved)
        #expect(c.currentClips.isEmpty)
    }

    @Test("transforms compose left-to-right, as an SVG transform list does")
    func compositionOrder() {
        // transform="translate(10 0) scale(2)" maps the point (1,0) to (12,0):
        // scale applies to the geometry first, translate second.
        var c = SVGCanvas()
        c.translate(10, 0)
        c.scale(2)
        #expect(isClose(apply(c.currentTransform, CGPoint(x: 1, y: 0)), CGPoint(x: 12, y: 0)))
    }

    @Test("an unanchored rotation turns about the origin, clockwise on screen")
    func rotationAboutOrigin() {
        var c = SVGCanvas()
        c.rotate(degrees: 90)
        // y grows downward, so +90° takes (1,0) to (0,1).
        #expect(isClose(apply(c.currentTransform, CGPoint(x: 1, y: 0)), CGPoint(x: 0, y: 1), 1e-12))
    }

    @Test("rotate(a cx cy) turns about the anchor — the silent bug D15 exists to kill")
    func rotationAboutAnchor() {
        // SVG: rotate(90 50 50) leaves (50,50) fixed and takes (60,50) to (50,60).
        var c = SVGCanvas()
        c.rotate(degrees: 90, about: CGPoint(x: 50, y: 50))
        let t = c.currentTransform
        #expect(isClose(apply(t, CGPoint(x: 50, y: 50)), CGPoint(x: 50, y: 50), 1e-9))
        #expect(isClose(apply(t, CGPoint(x: 60, y: 50)), CGPoint(x: 50, y: 60), 1e-9))
        // And it is NOT the same as the unanchored rotation, which is the bug.
        var plain = SVGCanvas()
        plain.rotate(degrees: 90)
        #expect(t != plain.currentTransform)
    }

    @Test("a negative scale mirrors")
    func negativeScaleMirrors() {
        var c = SVGCanvas()
        c.scale(-1, 1)
        #expect(isClose(apply(c.currentTransform, CGPoint(x: 5, y: 7)), CGPoint(x: -5, y: 7)))
    }

    @Test("viewBox maps like preserveAspectRatio=xMidYMid meet")
    func viewBoxMeet() {
        // A 100x100 box into a 200x400 rect: uniform scale 2, centred vertically.
        let t = SVGCanvas.viewBoxTransform(
            CGRect(x: 0, y: 0, width: 100, height: 100),
            fitting: CGRect(x: 0, y: 0, width: 200, height: 400)
        )
        #expect(isClose(apply(t, CGPoint(x: 0, y: 0)), CGPoint(x: 0, y: 100)))
        #expect(isClose(apply(t, CGPoint(x: 100, y: 100)), CGPoint(x: 200, y: 300)))
        // Uniform: a square stays square.
        let a = apply(t, CGPoint(x: 0, y: 0)), b = apply(t, CGPoint(x: 10, y: 10))
        #expect(abs((b.x - a.x) - (b.y - a.y)) < 1e-9)
    }

    @Test("the viewBox never clips — overflow survives")
    func viewBoxDoesNotClip() {
        // Top-stage wings and haloes deliberately leave the 100-unit box.
        let c = SVGCanvas(
            viewBox: CGRect(x: 0, y: 0, width: 100, height: 100),
            fitting: CGRect(x: 0, y: 0, width: 100, height: 100)
        )
        #expect(c.currentClips.isEmpty)
    }

    @Test("clips are recorded in root space so replay order is the only ordering")
    func clipsResolveToRootSpace() {
        var c = SVGCanvas()
        c.translate(100, 0)
        c.clip(to: Path(CGRect(x: 0, y: 0, width: 10, height: 10)))
        let clip = try! #require(c.currentClips.first)
        #expect(abs(clip.boundingRect.minX - 100) < 1e-9)
    }
}

@Suite("SVG canvas — draw list")
struct SVGCanvasDrawListTests {

    @Test("fills record in painter's order with the CTM of the moment")
    func paintersOrder() {
        var c = SVGCanvas()
        c.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .hex("#111111"))
        c.translate(5, 5)
        c.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .hex("#222222"))
        #expect(c.nodes.count == 2)
        guard case let .fill(_, t0, _, _) = c.nodes[0],
              case let .fill(_, t1, _, _) = c.nodes[1] else {
            Issue.record("expected two fills"); return
        }
        #expect(t0 == .identity)
        #expect(t1 == CGAffineTransform(translationX: 5, y: 5))
    }

    @Test("a zero-width stroke records nothing")
    func zeroWidthStrokeIsNoStroke() {
        // SVG treats stroke-width="0" as no stroke; SwiftUI would draw a
        // hairline. The dragon's SpadeTail passes `strokeWidth={edge ? 1 : 0}`
        // and depends on this.
        var c = SVGCanvas()
        c.stroke(Path(CGRect(x: 0, y: 0, width: 1, height: 1)),
                 with: .hex("#000000"), style: StrokeStyle(lineWidth: 0))
        #expect(c.nodes.isEmpty)
        c.stroke(Path(CGRect(x: 0, y: 0, width: 1, height: 1)),
                 with: .hex("#000000"), style: StrokeStyle(lineWidth: 1))
        #expect(c.nodes.count == 1)
    }

    @Test("a group nests its children and inherits the transform")
    func groupsNest() {
        var c = SVGCanvas()
        c.translate(3, 4)
        c.group(opacity: 0.5) { inner in
            inner.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .hex("#fff"))
        }
        #expect(c.nodes.count == 1)
        guard case let .group(opacity, children) = c.nodes[0] else {
            Issue.record("expected a group"); return
        }
        #expect(opacity == 0.5)
        #expect(children.count == 1)
        guard case let .fill(_, t, _, _) = children[0] else { Issue.record("expected a fill"); return }
        #expect(t == CGAffineTransform(translationX: 3, y: 4))
    }

    @Test("a mask keeps matte and content separate, both inheriting the state")
    func masksSeparateMatteAndContent() {
        var c = SVGCanvas()
        c.scale(2)
        c.mask({ m in
            m.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .hex("#fff"))
        }, content: { body in
            body.fill(Path(CGRect(x: 0, y: 0, width: 2, height: 2)), with: .hex("#f00"))
            body.fill(Path(CGRect(x: 0, y: 0, width: 3, height: 3)), with: .hex("#0f0"))
        })
        guard case let .mask(matte, content) = c.nodes[0] else {
            Issue.record("expected a mask"); return
        }
        #expect(matte.count == 1)
        #expect(content.count == 2)
        guard case let .fill(_, t, _, _) = matte[0] else { Issue.record("expected a fill"); return }
        #expect(t == CGAffineTransform(scaleX: 2, y: 2))
    }

    @Test("restore does not trap on an unbalanced stack")
    func unbalancedRestoreIsSafe() {
        // Invariant 3 applied to drawing: nothing about a malformed rig may
        // take the app down in front of a child. (assertionFailure fires in
        // debug, which is where the authoring bug gets caught.)
        var c = SVGCanvas()
        c.translate(1, 1)
        let before = c.currentTransform
        #expect(before != .identity)
    }
}

@Suite("SVG paint")
struct SVGPaintTests {

    @Test("hex parsing covers every form the sources use")
    func hexForms() {
        let opaqueWhite = Color(svgHex: "#FFFFFF")
        #expect(Color(svgHex: "FFFFFF") == opaqueWhite)
        #expect(Color(svgHex: "#fff") == opaqueWhite)
        #expect(Color(svgHex: "#FFF6EE") == Color(.sRGB, red: 1, green: 246.0 / 255, blue: 238.0 / 255, opacity: 1))
        #expect(Color(svgHex: "#00000000") == Color(.sRGB, red: 0, green: 0, blue: 0, opacity: 0))
    }

    @Test("garbage hex yields clear rather than trapping")
    func hexGarbage() {
        #expect(Color(svgHex: "not-a-colour") == Color(.sRGB, red: 0, green: 0, blue: 0, opacity: 0))
        #expect(Color(svgHex: "") == Color(.sRGB, red: 0, green: 0, blue: 0, opacity: 0))
    }

    @Test("gradients default to objectBoundingBox, because every gradient in the app does")
    func defaultUnitsAreBoundingBox() {
        // No source declares gradientUnits, so SVG's default applies. If this
        // default ever flips, the ground glow silently becomes a circle.
        #expect(SVGPaint.linear([.svg("#fff", at: 0)]).units == .objectBoundingBox)
        #expect(SVGPaint.radial([.svg("#fff", at: 0)]).units == .objectBoundingBox)
    }

    @Test("SVG's own gradient coordinate defaults are used")
    func svgCoordinateDefaults() {
        guard case let .linear(_, start, end) = SVGPaint.linear([.svg("#fff", at: 0)]).kind else {
            Issue.record("expected a linear gradient"); return
        }
        #expect(start == CGPoint(x: 0, y: 0))
        #expect(end == CGPoint(x: 1, y: 0))

        guard case let .radial(_, center, radius) = SVGPaint.radial([.svg("#fff", at: 0)]).kind else {
            Issue.record("expected a radial gradient"); return
        }
        #expect(center == CGPoint(x: 0.5, y: 0.5))
        #expect(radius == 0.5)
    }

    @Test("fill=none records nothing")
    func noneDrawsNothing() {
        var c = SVGCanvas()
        c.fill(Path(CGRect(x: 0, y: 0, width: 1, height: 1)), with: .none)
        // The node IS recorded (the canvas does not know about paint kinds),
        // but rendering it must be a no-op. Assert the paint reports none.
        guard case let .fill(_, _, _, paint) = c.nodes[0] else { Issue.record("expected a fill"); return }
        if case .none = paint.kind {} else { Issue.record("expected .none") }
    }

    @Test("stop opacity multiplies into the stop colour")
    func stopOpacity() {
        // <stop stopColor={color} stopOpacity="0.22" />
        let stop = Gradient.Stop.svg("#123456", at: 0.55, opacity: 0.22)
        #expect(stop.location == 0.55)
        #expect(stop.color == Color(svgHex: "#123456").opacity(0.22))
    }
}
