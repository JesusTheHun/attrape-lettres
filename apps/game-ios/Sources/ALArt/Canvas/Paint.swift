import CoreGraphics
import SwiftUI

// Fills and strokes for the SVG canvas (D15).
//
// The whole app declares gradients WITHOUT a `gradientUnits` attribute, which
// means every single one uses SVG's default, `objectBoundingBox`: the gradient
// coordinates are fractions of the element's own bounding box, and the gradient
// is therefore stretched by that box's aspect ratio. On a 2:1 ellipse an SVG
// radial gradient is an ELLIPSE; a naïve `RadialGradient(center:radius:)` draws
// a circle and nobody notices until the ground glow looks wrong. That is not an
// edge case here, it is the only case — so the bounding-box mapping is the
// default in this file too, and `.userSpaceOnUse` is the opt-out.
//
// The technique for bbox units: clip to the shape, concatenate the box's own
// transform (translate to its origin, scale by its size), then fill a covering
// rect with the gradient expressed in the 0…1 unit square. The CTM does the
// stretching, exactly as it does in a browser.

/// Which coordinate space a gradient's numbers live in.
public enum SVGUnits: Sendable {
    /// Fractions of the filled element's bounding box. **SVG's default**, and
    /// what every gradient in this app uses.
    case objectBoundingBox
    /// The current user space, i.e. the same coordinates as the path data.
    case userSpaceOnUse
}

/// What to fill or stroke with: `fill="#FFF6EE"`, `fill="url(#someGradient)"`,
/// or `fill="none"`.
public struct SVGPaint {

    public enum Kind {
        /// `fill="none"` / `stroke="none"` — draws nothing at all.
        case none
        case color(Color)
        /// `<linearGradient x1 y1 x2 y2>`; SVG's defaults are 0,0 → 1,0.
        case linear(stops: [Gradient.Stop], start: CGPoint, end: CGPoint)
        /// `<radialGradient cx cy r>`; SVG's defaults are 0.5, 0.5, 0.5.
        /// No `fx`/`fy` — the app uses no focal offsets, and `GraphicsContext`
        /// has no way to express one, so an unsupported focus would have to be
        /// silently dropped. It is absent from the type rather than ignored.
        case radial(stops: [Gradient.Stop], center: CGPoint, radius: Double)
    }

    public var kind: Kind
    /// `fill-opacity` / `stroke-opacity`, multiplied into the context.
    public var opacity: Double
    public var units: SVGUnits

    public init(kind: Kind, opacity: Double = 1, units: SVGUnits = .objectBoundingBox) {
        self.kind = kind
        self.opacity = opacity
        self.units = units
    }

    // MARK: - Convenience

    public static let none = SVGPaint(kind: .none)

    public static func color(_ color: Color, opacity: Double = 1) -> SVGPaint {
        SVGPaint(kind: .color(color), opacity: opacity)
    }

    /// `fill="#FFF6EE"`. Accepts `#RGB`, `#RGBA`, `#RRGGBB` and `#RRGGBBAA`,
    /// with or without the leading `#`, which is every form the sources use.
    public static func hex(_ string: String, opacity: Double = 1) -> SVGPaint {
        SVGPaint(kind: .color(Color(svgHex: string)), opacity: opacity)
    }

    public static func linear(
        _ stops: [Gradient.Stop],
        from start: CGPoint = CGPoint(x: 0, y: 0),
        to end: CGPoint = CGPoint(x: 1, y: 0),
        opacity: Double = 1,
        units: SVGUnits = .objectBoundingBox
    ) -> SVGPaint {
        SVGPaint(kind: .linear(stops: stops, start: start, end: end), opacity: opacity, units: units)
    }

    public static func radial(
        _ stops: [Gradient.Stop],
        center: CGPoint = CGPoint(x: 0.5, y: 0.5),
        radius: Double = 0.5,
        opacity: Double = 1,
        units: SVGUnits = .objectBoundingBox
    ) -> SVGPaint {
        SVGPaint(kind: .radial(stops: stops, center: center, radius: radius), opacity: opacity, units: units)
    }

    // MARK: - Drawing

    /// Fill `path` (nonzero — D21) under `transform`.
    public func fill(_ path: Path, transform: CGAffineTransform, into context: inout GraphicsContext) {
        if case .none = kind { return }
        var c = context
        c.opacity *= opacity
        c.concatenate(transform)
        draw(shapeInUserSpace: path, into: &c)
    }

    /// Stroke `path` under `transform`. The stroke is centred on the path and
    /// its width is in user space, so the CTM scales it — as SVG does.
    ///
    /// For a gradient stroke the outline is converted to a fillable shape
    /// first: an SVG gradient on a stroke still resolves against the element's
    /// bounding box, and this is the only way to say that to `GraphicsContext`.
    public func stroke(
        _ path: Path,
        style: StrokeStyle,
        transform: CGAffineTransform,
        into context: inout GraphicsContext
    ) {
        switch kind {
        case .none:
            return
        case let .color(color):
            var c = context
            c.opacity *= opacity
            c.concatenate(transform)
            c.stroke(path, with: .color(color), style: style)
        case .linear, .radial:
            var c = context
            c.opacity *= opacity
            c.concatenate(transform)
            draw(shapeInUserSpace: path.strokedPath(style), into: &c)
        }
    }

    /// Fill `shape`, whose coordinates are already in the context's current
    /// user space, honouring `units`.
    private func draw(shapeInUserSpace shape: Path, into context: inout GraphicsContext) {
        switch kind {
        case .none:
            return

        case let .color(color):
            context.fill(shape, with: .color(color))

        case .linear, .radial:
            switch units {
            case .userSpaceOnUse:
                context.fill(shape, with: shading())

            case .objectBoundingBox:
                let box = shape.boundingRect
                // A zero-width or zero-height box has no bounding-box space to
                // map into; SVG says the element is not rendered at all.
                guard box.width > 0, box.height > 0 else { return }

                var c = context
                c.clip(to: shape)
                c.concatenate(
                    CGAffineTransform(scaleX: box.width, y: box.height)
                        .concatenating(CGAffineTransform(translationX: box.minX, y: box.minY))
                )
                // In unit space the shape is inside 0…1; the gradient may run
                // past it, so cover generously and let the clip do the work.
                c.fill(Path(CGRect(x: -1, y: -1, width: 3, height: 3)), with: shading())
            }
        }
    }

    private func shading() -> GraphicsContext.Shading {
        switch kind {
        case .none:
            return .color(.clear)
        case let .color(color):
            return .color(color)
        case let .linear(stops, start, end):
            return .linearGradient(Gradient(stops: stops), startPoint: start, endPoint: end)
        case let .radial(stops, center, radius):
            return .radialGradient(
                Gradient(stops: stops),
                center: center,
                startRadius: 0,
                endRadius: radius
            )
        }
    }
}

public extension Gradient.Stop {
    /// `<stop offset="55%" stopColor="#fff" stopOpacity="0.22" />`.
    /// `offset` is 0…1; pass 0.55 for "55%".
    static func svg(_ hex: String, at offset: Double, opacity: Double = 1) -> Gradient.Stop {
        Gradient.Stop(color: Color(svgHex: hex).opacity(opacity), location: offset)
    }

    static func svg(_ color: Color, at offset: Double, opacity: Double = 1) -> Gradient.Stop {
        Gradient.Stop(color: color.opacity(opacity), location: offset)
    }
}

public extension Color {
    /// Parse an SVG/CSS hex colour. Unparseable input yields clear rather than
    /// trapping — invariant 3 applied to the drawing layer. Every colour string
    /// in the app is an authored constant, so a bad one fails a test long
    /// before a child sees it.
    init(svgHex string: String) {
        var hex = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") { hex.removeFirst() }

        func component(_ slice: Substring) -> Double {
            Double(UInt8(slice, radix: 16) ?? 0) / 255
        }

        let chars = Array(hex)
        switch chars.count {
        case 3, 4: // #RGB / #RGBA — each digit doubled, per CSS
            let expanded = chars.map { String(repeating: String($0), count: 2) }.joined()
            self.init(svgHex: expanded)
        case 6, 8:
            let s = hex[...]
            let r = component(s.prefix(2))
            let g = component(s.dropFirst(2).prefix(2))
            let b = component(s.dropFirst(4).prefix(2))
            let a = chars.count == 8 ? component(s.dropFirst(6).prefix(2)) : 1
            self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
        default:
            self.init(.sRGB, red: 0, green: 0, blue: 0, opacity: 0)
        }
    }
}
