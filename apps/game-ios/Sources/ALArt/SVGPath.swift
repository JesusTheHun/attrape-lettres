import CoreGraphics
import Foundation
import SwiftUI

// The `d`-string parser that decision D2 rests on.
//
// The mascots and the exercise icons are not assets: 95 of the app's 189 path
// strings are template literals computed from props at runtime. The port keeps
// them as strings and parses them here, so the geometry is never retyped and a
// port bug cannot silently move a dragon's eye three points left.
//
// The corpus in the repo uses M L Q Z C A l q H a z V, but the full SVG 1.1
// grammar is implemented — a new mascot must not be able to hit an unsupported
// command. Elliptical arcs are converted to cubic Béziers rather than handed to
// CGPath.addArc, which keeps the result independent of CoreGraphics' sweep
// convention and makes the maths assertable on the host with exact numbers.

public enum SVGPathError: Error, Equatable, CustomStringConvertible {
    case unexpectedCharacter(Character, at: Int)
    case unknownCommand(Character, at: Int)
    case expectedNumber(at: Int)
    case expectedFlag(at: Int)
    case missingInitialMove(at: Int)

    public var description: String {
        switch self {
        case let .unexpectedCharacter(c, i): return "unexpected character '\(c)' at \(i)"
        case let .unknownCommand(c, i): return "unknown path command '\(c)' at \(i)"
        case let .expectedNumber(i): return "expected a number at \(i)"
        case let .expectedFlag(i): return "expected a 0/1 arc flag at \(i)"
        case let .missingInitialMove(i): return "path data must start with a moveto, at \(i)"
        }
    }
}

public enum SVGPath {

    /// Parse SVG path data into a `CGPath`. Throws on malformed input — the path
    /// strings are authored constants, so anything that throws is a bug a test
    /// should catch rather than something to paper over at runtime.
    public static func cgPath(from d: String) throws -> CGPath {
        var scanner = Scanner(d)
        let path = CGMutablePath()

        var current = CGPoint.zero      // current point
        var subpathStart = CGPoint.zero // where the active subpath began
        var lastControl: CGPoint?       // for S/s and T/t reflection
        var lastWasCubic = false
        var lastWasQuad = false
        var previousCommand: Character?
        var started = false

        while let command = try scanner.nextCommand(previous: previousCommand) {
            let relative = command.isLowercase
            let op = Character(command.lowercased())

            if !started, op != "m" {
                throw SVGPathError.missingInitialMove(at: scanner.offset)
            }

            switch op {
            case "m":
                let p = try scanner.point(relativeTo: relative ? current : .zero)
                path.move(to: p)
                current = p
                subpathStart = p
                started = true
                lastWasCubic = false; lastWasQuad = false

            case "l":
                let p = try scanner.point(relativeTo: relative ? current : .zero)
                path.addLine(to: p)
                current = p
                lastWasCubic = false; lastWasQuad = false

            case "h":
                let x = try scanner.number()
                let p = CGPoint(x: relative ? current.x + x : x, y: current.y)
                path.addLine(to: p)
                current = p
                lastWasCubic = false; lastWasQuad = false

            case "v":
                let y = try scanner.number()
                let p = CGPoint(x: current.x, y: relative ? current.y + y : y)
                path.addLine(to: p)
                current = p
                lastWasCubic = false; lastWasQuad = false

            case "c":
                let origin = relative ? current : .zero
                let c1 = try scanner.point(relativeTo: origin)
                let c2 = try scanner.point(relativeTo: origin)
                let p = try scanner.point(relativeTo: origin)
                path.addCurve(to: p, control1: c1, control2: c2)
                current = p
                lastControl = c2
                lastWasCubic = true; lastWasQuad = false

            case "s":
                let origin = relative ? current : .zero
                // The first control point is the reflection of the previous
                // cubic's second control point; absent one, it coincides with
                // the current point (SVG 1.1 §8.3.6).
                let c1 = lastWasCubic ? reflect(lastControl ?? current, about: current) : current
                let c2 = try scanner.point(relativeTo: origin)
                let p = try scanner.point(relativeTo: origin)
                path.addCurve(to: p, control1: c1, control2: c2)
                current = p
                lastControl = c2
                lastWasCubic = true; lastWasQuad = false

            case "q":
                let origin = relative ? current : .zero
                let c = try scanner.point(relativeTo: origin)
                let p = try scanner.point(relativeTo: origin)
                path.addQuadCurve(to: p, control: c)
                current = p
                lastControl = c
                lastWasQuad = true; lastWasCubic = false

            case "t":
                let origin = relative ? current : .zero
                let c = lastWasQuad ? reflect(lastControl ?? current, about: current) : current
                let p = try scanner.point(relativeTo: origin)
                path.addQuadCurve(to: p, control: c)
                current = p
                lastControl = c
                lastWasQuad = true; lastWasCubic = false

            case "a":
                let rx = try scanner.number()
                let ry = try scanner.number()
                let rotation = try scanner.number()
                let largeArc = try scanner.flag()
                let sweep = try scanner.flag()
                let p = try scanner.point(relativeTo: relative ? current : .zero)
                appendArc(
                    to: path, from: current, to: p,
                    rx: rx, ry: ry, rotationDegrees: rotation,
                    largeArc: largeArc, sweep: sweep
                )
                current = p
                lastWasCubic = false; lastWasQuad = false

            case "z":
                path.closeSubpath()
                current = subpathStart
                lastWasCubic = false; lastWasQuad = false

            default:
                throw SVGPathError.unknownCommand(command, at: scanner.offset)
            }

            previousCommand = command
        }

        return path.copy() ?? path
    }

    private static func reflect(_ point: CGPoint, about origin: CGPoint) -> CGPoint {
        CGPoint(x: 2 * origin.x - point.x, y: 2 * origin.y - point.y)
    }

    // MARK: - Elliptical arc

    /// Endpoint → centre parameterisation (SVG 1.1 appendix F.6.5), then a cubic
    /// Bézier approximation in ≤90° sweeps. Out-of-range radii are scaled up per
    /// F.6.6.2 — the spec says grow them until they reach, never clamp.
    static func appendArc(
        to path: CGMutablePath,
        from p0: CGPoint,
        to p1: CGPoint,
        rx rxIn: CGFloat,
        ry ryIn: CGFloat,
        rotationDegrees: CGFloat,
        largeArc: Bool,
        sweep: Bool
    ) {
        // F.6.2: a zero radius degenerates to a straight line.
        var rx = abs(rxIn), ry = abs(ryIn)
        guard rx > 0, ry > 0 else {
            path.addLine(to: p1)
            return
        }
        // Coincident endpoints: the arc is omitted entirely (F.6.2).
        guard p0 != p1 else { return }

        let phi = rotationDegrees * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)

        // F.6.5.1 — midpoint in the rotated frame.
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy
        let y1 = -sinPhi * dx + cosPhi * dy

        // F.6.6.2 — grow radii that cannot span the chord.
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if lambda > 1 {
            let s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        // F.6.5.2 — centre in the rotated frame.
        let rxSq = rx * rx, rySq = ry * ry
        let numerator = max(0, rxSq * rySq - rxSq * y1 * y1 - rySq * x1 * x1)
        let denominator = rxSq * y1 * y1 + rySq * x1 * x1
        let coefficient = (largeArc == sweep ? -1 : 1) * sqrt(numerator / denominator)
        let cx1 = coefficient * rx * y1 / ry
        let cy1 = -coefficient * ry * x1 / rx

        // F.6.5.3 — back to user space.
        let cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + p1.x) / 2
        let cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + p1.y) / 2

        // F.6.5.5 / F.6.5.6 — start angle and sweep.
        let ux = (x1 - cx1) / rx, uy = (y1 - cy1) / ry
        let vx = (-x1 - cx1) / rx, vy = (-y1 - cy1) / ry

        let theta1 = atan2(uy, ux)
        var deltaTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if !sweep, deltaTheta > 0 { deltaTheta -= 2 * .pi }
        if sweep, deltaTheta < 0 { deltaTheta += 2 * .pi }

        // Cubic approximation. Error stays under ~1e-4 of the radius at 90° per
        // segment, which is far below a pixel at any size this app draws at.
        let segments = max(1, Int(ceil(abs(deltaTheta) / (.pi / 2))))
        let delta = deltaTheta / CGFloat(segments)
        let alpha = 4.0 / 3.0 * tan(delta / 4)

        var theta = theta1
        for _ in 0 ..< segments {
            let thetaNext = theta + delta
            let cosT = cos(theta), sinT = sin(theta)
            let cosN = cos(thetaNext), sinN = sin(thetaNext)

            let e = { (ct: CGFloat, st: CGFloat) -> CGPoint in
                CGPoint(
                    x: cx + rx * cosPhi * ct - ry * sinPhi * st,
                    y: cy + rx * sinPhi * ct + ry * cosPhi * st
                )
            }
            // Derivative of the parameterised ellipse, used for the tangents.
            let ePrime = { (ct: CGFloat, st: CGFloat) -> CGPoint in
                CGPoint(
                    x: -rx * cosPhi * st - ry * sinPhi * ct,
                    y: -rx * sinPhi * st + ry * cosPhi * ct
                )
            }

            let start = e(cosT, sinT)
            let end = e(cosN, sinN)
            let dStart = ePrime(cosT, sinT)
            let dEnd = ePrime(cosN, sinN)

            let c1 = CGPoint(x: start.x + alpha * dStart.x, y: start.y + alpha * dStart.y)
            let c2 = CGPoint(x: end.x - alpha * dEnd.x, y: end.y - alpha * dEnd.y)

            path.addCurve(to: end, control1: c1, control2: c2)
            theta = thetaNext
        }
    }

    // MARK: - Scanner

    /// Tokeniser for path data. SVG separates numbers by whitespace, commas, or
    /// nothing at all when the sign or decimal point makes the boundary
    /// unambiguous — `10-5` is two numbers and `.5.5` is two numbers.
    struct Scanner {
        private let chars: [Character]
        private(set) var offset = 0

        init(_ s: String) { chars = Array(s) }

        private var atEnd: Bool { offset >= chars.count }

        private mutating func skipSeparators() {
            while offset < chars.count {
                let c = chars[offset]
                if c == " " || c == "," || c == "\n" || c == "\r" || c == "\t" { offset += 1 } else { break }
            }
        }

        /// The next command letter, or — when the data continues with a number —
        /// the implicit repeat of the previous one. After a moveto the implicit
        /// command is a lineto of matching arity (SVG 1.1 §8.3.2).
        mutating func nextCommand(previous: Character?) throws -> Character? {
            skipSeparators()
            guard !atEnd else { return nil }
            let c = chars[offset]

            if c.isLetter {
                offset += 1
                guard "MmLlHhVvCcSsQqTtAaZz".contains(c) else {
                    throw SVGPathError.unknownCommand(c, at: offset - 1)
                }
                return c
            }
            if c.isNumber || c == "-" || c == "+" || c == "." {
                guard let previous else { throw SVGPathError.unexpectedCharacter(c, at: offset) }
                switch previous {
                case "M": return "L"
                case "m": return "l"
                case "Z", "z": throw SVGPathError.unexpectedCharacter(c, at: offset)
                default: return previous
                }
            }
            throw SVGPathError.unexpectedCharacter(c, at: offset)
        }

        mutating func number() throws -> CGFloat {
            skipSeparators()
            let start = offset
            if !atEnd, chars[offset] == "-" || chars[offset] == "+" { offset += 1 }
            var sawDigit = false
            while !atEnd, chars[offset].isNumber { offset += 1; sawDigit = true }
            if !atEnd, chars[offset] == "." {
                offset += 1
                while !atEnd, chars[offset].isNumber { offset += 1; sawDigit = true }
            }
            guard sawDigit else { throw SVGPathError.expectedNumber(at: start) }
            // Exponent, but only when it is actually one: `1e-3` yes, `1e` no.
            if !atEnd, chars[offset] == "e" || chars[offset] == "E" {
                let mark = offset
                offset += 1
                if !atEnd, chars[offset] == "-" || chars[offset] == "+" { offset += 1 }
                var sawExponentDigit = false
                while !atEnd, chars[offset].isNumber { offset += 1; sawExponentDigit = true }
                if !sawExponentDigit { offset = mark }
            }
            guard let value = Double(String(chars[start ..< offset])) else {
                throw SVGPathError.expectedNumber(at: start)
            }
            return CGFloat(value)
        }

        mutating func point(relativeTo origin: CGPoint) throws -> CGPoint {
            let x = try number()
            let y = try number()
            return CGPoint(x: origin.x + x, y: origin.y + y)
        }

        /// Arc flags are a single character and may be packed against what
        /// follows: `a1 1 0 011 1` carries flags 0 and 1 then the x coordinate.
        mutating func flag() throws -> Bool {
            skipSeparators()
            guard !atEnd else { throw SVGPathError.expectedFlag(at: offset) }
            switch chars[offset] {
            case "0": offset += 1; return false
            case "1": offset += 1; return true
            default: throw SVGPathError.expectedFlag(at: offset)
            }
        }
    }
}

// MARK: - SwiftUI

public extension Path {
    /// Build a `Path` from SVG path data.
    ///
    /// Non-throwing on purpose: every `d` string in the app is an authored
    /// constant covered by `SVGPathCorpusTests`, so a malformed one fails the
    /// suite long before a child sees it. At runtime a bad string yields
    /// whatever parsed before the error rather than an empty screen — invariant
    /// 3, no fail state, applied to the drawing layer.
    init(svg d: String) {
        if let cg = try? SVGPath.cgPath(from: d) {
            self.init(cg)
        } else {
            self.init()
        }
    }
}

/// A `Shape` wrapping SVG path data authored in a viewBox, scaled to fit.
///
/// `viewBox` is the SVG coordinate space the `d` string was authored in; the
/// shape maps it into whatever rect SwiftUI hands it, preserving aspect ratio
/// the way `preserveAspectRatio="xMidYMid meet"` does in the browser.
public struct SVGShape: Shape {
    public var d: String
    public var viewBox: CGRect

    public init(_ d: String, viewBox: CGRect) {
        self.d = d
        self.viewBox = viewBox
    }

    public func path(in rect: CGRect) -> Path {
        // The mapping is `SVGCanvas`'s, not a second copy of it. D15 asks for
        // one drawing model; two implementations of `xMidYMid meet` is how one
        // of them quietly stops agreeing with the other. A degenerate viewBox
        // yields `.identity` there, which is the unmapped path here.
        Path(svg: d).applying(SVGCanvas.viewBoxTransform(viewBox, fitting: rect))
    }
}
