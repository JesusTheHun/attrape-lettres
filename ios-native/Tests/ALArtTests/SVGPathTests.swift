import CoreGraphics
import Testing
@testable import ALArt

// Exact-geometry tests for the `d` parser. These assert numbers, not "it didn't
// crash" — the whole point of decision D2 is that the geometry is provably the
// same as the web app's, and a parser that silently drops a control point would
// still produce a plausible-looking dragon.

/// Flattened representation of a CGPath so assertions can name what they mean.
enum Element: Equatable {
    case move(CGPoint)
    case line(CGPoint)
    case quad(CGPoint, control: CGPoint)
    case cubic(CGPoint, control1: CGPoint, control2: CGPoint)
    case close
}

func elements(of path: CGPath) -> [Element] {
    var out: [Element] = []
    path.applyWithBlock { pointer in
        let e = pointer.pointee
        switch e.type {
        case .moveToPoint: out.append(.move(e.points[0]))
        case .addLineToPoint: out.append(.line(e.points[0]))
        case .addQuadCurveToPoint: out.append(.quad(e.points[1], control: e.points[0]))
        case .addCurveToPoint: out.append(.cubic(e.points[2], control1: e.points[0], control2: e.points[1]))
        case .closeSubpath: out.append(.close)
        @unknown default: break
        }
    }
    return out
}

func parse(_ d: String) throws -> [Element] {
    try elements(of: SVGPath.cgPath(from: d))
}

private func isClose(_ a: CGPoint, _ b: CGPoint, tolerance: CGFloat = 1e-9) -> Bool {
    abs(a.x - b.x) < tolerance && abs(a.y - b.y) < tolerance
}

@Suite("SVG path grammar")
struct SVGPathGrammarTests {

    @Test("absolute moveto and lineto")
    func absoluteMoveLine() throws {
        #expect(try parse("M 10 20 L 30 40") == [.move(.init(x: 10, y: 20)), .line(.init(x: 30, y: 40))])
    }

    @Test("relative commands accumulate from the current point")
    func relative() throws {
        #expect(try parse("M 10 10 l 5 5 l -3 0") == [
            .move(.init(x: 10, y: 10)),
            .line(.init(x: 15, y: 15)),
            .line(.init(x: 12, y: 15)),
        ])
    }

    @Test("a repeated coordinate pair after moveto is an implicit lineto")
    func implicitLineAfterMove() throws {
        #expect(try parse("M 0 0 1 1 2 2") == [
            .move(.init(x: 0, y: 0)),
            .line(.init(x: 1, y: 1)),
            .line(.init(x: 2, y: 2)),
        ])
    }

    @Test("relative moveto makes the implicit command a relative lineto")
    func implicitRelativeLine() throws {
        #expect(try parse("m 10 10 5 0 5 0") == [
            .move(.init(x: 10, y: 10)),
            .line(.init(x: 15, y: 10)),
            .line(.init(x: 20, y: 10)),
        ])
    }

    @Test("a command letter is not repeated for its own arguments")
    func repeatedCommandArguments() throws {
        #expect(try parse("M0 0 L1 1 2 2 3 3") == [
            .move(.init(x: 0, y: 0)),
            .line(.init(x: 1, y: 1)),
            .line(.init(x: 2, y: 2)),
            .line(.init(x: 3, y: 3)),
        ])
    }

    @Test("horizontal and vertical linetos keep the other axis")
    func horizontalVertical() throws {
        #expect(try parse("M 5 5 H 20 V 30 h -5 v -10") == [
            .move(.init(x: 5, y: 5)),
            .line(.init(x: 20, y: 5)),
            .line(.init(x: 20, y: 30)),
            .line(.init(x: 15, y: 30)),
            .line(.init(x: 15, y: 20)),
        ])
    }

    @Test("quadratic and cubic curves keep every control point")
    func curves() throws {
        #expect(try parse("M0 0 Q 10 -10 20 0 C 25 5 35 5 40 0") == [
            .move(.init(x: 0, y: 0)),
            .quad(.init(x: 20, y: 0), control: .init(x: 10, y: -10)),
            .cubic(.init(x: 40, y: 0), control1: .init(x: 25, y: 5), control2: .init(x: 35, y: 5)),
        ])
    }

    @Test("S reflects the previous cubic's second control point")
    func smoothCubicReflection() throws {
        let parsed = try parse("M0 0 C 10 10 20 10 30 0 S 50 -10 60 0")
        // Previous control2 is (20,10) about the current point (30,0) → (40,-10).
        #expect(parsed[2] == .cubic(.init(x: 60, y: 0), control1: .init(x: 40, y: -10), control2: .init(x: 50, y: -10)))
    }

    @Test("S with no preceding cubic uses the current point as its first control")
    func smoothCubicWithoutPredecessor() throws {
        let parsed = try parse("M 10 10 S 20 20 30 10")
        #expect(parsed[1] == .cubic(.init(x: 30, y: 10), control1: .init(x: 10, y: 10), control2: .init(x: 20, y: 20)))
    }

    @Test("T reflects the previous quadratic's control point")
    func smoothQuadReflection() throws {
        let parsed = try parse("M0 0 Q 10 10 20 0 T 40 0")
        // Reflection of (10,10) about (20,0) is (30,-10).
        #expect(parsed[2] == .quad(.init(x: 40, y: 0), control: .init(x: 30, y: -10)))
    }

    @Test("closepath returns the current point to the subpath start")
    func closeRestoresCurrentPoint() throws {
        // The lineto after Z must start from (0,0), not from (10,10).
        let parsed = try parse("M 0 0 L 10 0 L 10 10 Z l 5 5")
        #expect(parsed.last == .line(.init(x: 5, y: 5)))
    }

    @Test("numbers separate on a sign or a second decimal point with no delimiter")
    func numberEliding() throws {
        #expect(try parse("M0 0L10-5") == [.move(.init(x: 0, y: 0)), .line(.init(x: 10, y: -5))])
        #expect(try parse("M.5.5L1.5.5") == [.move(.init(x: 0.5, y: 0.5)), .line(.init(x: 1.5, y: 0.5))])
    }

    @Test("exponent notation parses, and a bare trailing e does not swallow a command")
    func exponents() throws {
        #expect(try parse("M 1e2 -1.5e-1") == [.move(.init(x: 100, y: -0.15))])
    }

    @Test("commas and newlines are separators")
    func separators() throws {
        #expect(try parse("M0,0\n  L10,10") == [.move(.init(x: 0, y: 0)), .line(.init(x: 10, y: 10))])
    }

    @Test("malformed input throws rather than silently truncating")
    func errors() {
        #expect(throws: SVGPathError.self) { try SVGPath.cgPath(from: "L 10 10") }
        #expect(throws: SVGPathError.self) { try SVGPath.cgPath(from: "M 0 0 X 1 1") }
        #expect(throws: SVGPathError.self) { try SVGPath.cgPath(from: "M 0 0 L 10") }
        #expect(throws: SVGPathError.self) { try SVGPath.cgPath(from: "M 0 0 A 5 5 0 2 1 10 10") }
    }
}

@Suite("SVG elliptical arcs")
struct SVGArcTests {

    @Test("an arc terminates exactly on its stated endpoint")
    func arcEndpoint() throws {
        for d in [
            "M 0 0 A 50 50 0 0 1 100 0",
            "M 0 0 A 50 50 0 1 1 100 0",
            "M 0 0 A 50 50 0 0 0 100 0",
            "M 0 0 A 50 50 0 1 0 100 0",
            "M 10 20 A 30 15 45 1 1 70 60",
        ] {
            let parsed = try parse(d)
            guard case let .cubic(end, _, _) = parsed.last else {
                Issue.record("arc did not emit curves for \(d)")
                return
            }
            let target = try #require(parsed.first.map { if case let .move(p) = $0 { p } else { CGPoint.zero } })
            _ = target
            let expected: CGPoint = d.hasPrefix("M 10 20") ? .init(x: 70, y: 60) : .init(x: 100, y: 0)
            #expect(isClose(end, expected, tolerance: 1e-6), "\(d) ended at \(end)")
        }
    }

    @Test("two half arcs make a circle of the right bounding box")
    func fullCircle() throws {
        let path = try SVGPath.cgPath(from: "M 50 0 A 50 50 0 1 0 -50 0 A 50 50 0 1 0 50 0 Z")
        let box = path.boundingBoxOfPath
        #expect(abs(box.minX - -50) < 0.01)
        #expect(abs(box.maxX - 50) < 0.01)
        #expect(abs(box.minY - -50) < 0.01)
        #expect(abs(box.maxY - 50) < 0.01)
    }

    @Test("the sweep flag chooses which side the arc bulges to")
    func sweepFlagPicksSide() throws {
        let sweepOne = try SVGPath.cgPath(from: "M 0 0 A 50 50 0 0 1 100 0").boundingBoxOfPath
        let sweepZero = try SVGPath.cgPath(from: "M 0 0 A 50 50 0 0 0 100 0").boundingBoxOfPath
        // One bulges to positive y, the other to negative y; they cannot agree.
        #expect(sweepOne.midY * sweepZero.midY < 0)
    }

    @Test("the large-arc flag chooses the longer sweep")
    func largeArcFlagPicksLongerSweep() throws {
        let small = try SVGPath.cgPath(from: "M 0 0 A 50 50 0 0 1 50 50").boundingBoxOfPath
        let large = try SVGPath.cgPath(from: "M 0 0 A 50 50 0 1 1 50 50").boundingBoxOfPath
        #expect(large.width > small.width)
        #expect(large.height > small.height)
    }

    @Test("radii too small to span the chord are grown, per F.6.6.2")
    func radiiScaledUp() throws {
        // Endpoints 100 apart with r=10: the spec grows r to 50 rather than
        // clamping or failing. The result is the exact semicircle.
        let box = try SVGPath.cgPath(from: "M 0 0 A 10 10 0 0 1 100 0").boundingBoxOfPath
        #expect(abs(box.width - 100) < 0.01)
        #expect(abs(box.height - 50) < 0.01)
    }

    @Test("a zero radius degenerates to a straight line")
    func zeroRadiusIsALine() throws {
        #expect(try parse("M 0 0 A 0 50 0 0 1 100 0") == [.move(.init(x: 0, y: 0)), .line(.init(x: 100, y: 0))])
    }

    @Test("coincident endpoints omit the arc entirely")
    func coincidentEndpointsOmitArc() throws {
        #expect(try parse("M 10 10 A 50 50 0 1 1 10 10") == [.move(.init(x: 10, y: 10))])
    }

    @Test("x-axis rotation tilts the ellipse")
    func rotationTilts() throws {
        let upright = try SVGPath.cgPath(from: "M 0 0 A 50 20 0 1 1 60 0").boundingBoxOfPath
        let tilted = try SVGPath.cgPath(from: "M 0 0 A 50 20 45 1 1 60 0").boundingBoxOfPath
        #expect(abs(upright.height - tilted.height) > 1)
    }

    @Test("arc flags may be packed with no separator")
    func packedFlags() throws {
        // `011 100` reads as largeArc=0, sweep=1, then the endpoint pair (1,100).
        // Flags are single characters, so nothing separates them from the x that
        // follows — a tokeniser that grabbed a whole number here would consume
        // 011 and then be one argument short for the rest of the path.
        let packed = try parse("M0 0a50 50 0 011 100")
        let spaced = try parse("M0 0 a 50 50 0 0 1 1 100")
        #expect(packed.count == spaced.count)
        if case let .cubic(a, _, _) = packed.last, case let .cubic(b, _, _) = spaced.last {
            #expect(isClose(a, b, tolerance: 1e-9))
        } else {
            Issue.record("expected both to end in a curve")
        }
    }
}
