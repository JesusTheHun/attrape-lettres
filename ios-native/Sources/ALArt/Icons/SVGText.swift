import CoreGraphics
import SwiftUI

// The one `<text>` element in the whole app (spec/shell.md §3.5).
//
// `ExerciseIcon.tsx` draws four letterforms — `A`, `V`, `A`, `a` — with
//
//   <text x y textAnchor="middle" dominantBaseline="central"
//         fontFamily="ui-rounded,'SF Pro Rounded',system-ui,sans-serif"
//         fontWeight={900} fontSize={size} fill="#fff" />
//
// and there is no path data behind them. Two options existed: trace the glyphs
// into `d` strings, or draw real text. Tracing is *exactly* what D2 forbids —
// retyped geometry, with no source string to copy from, frozen at one OS
// version. So this is a `GraphicsContext.draw(_:at:)` overlay in the same
// canvas as the paths, which is also *closer* to the web: on iOS WebKit already
// resolves `ui-rounded` to SF Pro Rounded, which is precisely the face
// `Font.system(design: .rounded)` returns.
//
// What has to be reimplemented is the anchoring, because the two systems
// measure from different places:
//
//   SVG      anchors the glyph run on a BASELINE chosen by `dominant-baseline`.
//   SwiftUI  centres/aligns a text on its LINE BOX.
//
// See `SVGTextMetrics` for the arithmetic. Getting it wrong shifts a letter by
// a couple of units, which is invisible in review and loud in a pixel diff —
// spec/shell.md predicts this will be the first thing the D3 diff argues about.

/// `ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif"` — the font
/// stack the TSX asks for. `Font.system(design: .rounded)` is what iOS resolves
/// the first entry to, so that is the port.
public enum IconFont {
    public static func rounded(size: Double, weight: Font.Weight) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }
}

/// One `<text>` node: a string anchored at `(x, y)` in the icon's 32-unit space.
public struct IconText: Equatable, Sendable {
    public let string: String
    public let x: Double
    public let y: Double
    /// `fontSize`, in the same 32-unit space as every coordinate here.
    public let size: Double
    /// `fontWeight={900}` → `.black`.
    public let weight: Font.Weight
    /// `fill="#fff"`.
    public let fill: String

    public init(
        _ string: String,
        x: Double,
        y: Double,
        size: Double,
        weight: Font.Weight = .black,
        fill: String = "#fff"
    ) {
        self.string = string
        self.x = x
        self.y = y
        self.size = size
        self.weight = weight
        self.fill = fill
    }
}

/// The `textAnchor="middle"` + `dominantBaseline="central"` arithmetic, pulled
/// out as pure functions so it can be checked on the host against known font
/// metrics instead of only by eye.
///
/// `ascent` and `descent` are both POSITIVE distances from the alphabetic
/// baseline (up and down respectively) — the CoreText sign convention flipped
/// for descent, which is what `GraphicsContext.ResolvedText` reports when you
/// take `firstBaseline(in:)` and `measure(in:).height - firstBaseline(in:)`.
public struct SVGTextMetrics: Equatable, Sendable {
    public let ascent: Double
    public let descent: Double

    public init(ascent: Double, descent: Double) {
        self.ascent = ascent
        self.descent = descent
    }

    /// Where the alphabetic baseline lands for `dominant-baseline="central"`.
    ///
    /// "central" is the baseline halfway between the ascender and the descender,
    /// i.e. `(ascent − descent) / 2` ABOVE the alphabetic one. SVG puts *that*
    /// baseline on `y`, so the alphabetic baseline sits below it by the same
    /// amount.
    public func baselineY(anchoredAt y: Double) -> Double {
        y + (ascent - descent) / 2
    }

    /// Top-left of the line box to hand to `GraphicsContext.draw(_:at:anchor: .topLeading)`.
    ///
    /// The line box runs from `baseline − ascent` to `baseline + descent`, so
    ///
    ///     top = y + (ascent − descent)/2 − ascent = y − (ascent + descent)/2
    ///
    /// — the line box centre lands exactly on `y`. That identity is the whole
    /// result: `dominant-baseline="central"` and a line-box-centred draw agree,
    /// as long as the font reports no asymmetric line gap. `textAnchor="middle"`
    /// is the trivial half of it.
    public func topLeft(x: Double, y: Double, width: Double) -> CGPoint {
        CGPoint(x: x - width / 2, y: y - (ascent + descent) / 2)
    }
}

extension IconText {
    /// Draw into a context whose CTM already maps the 32-unit icon space, so the
    /// font size is in icon units too and text and paths cannot drift apart.
    func draw(into context: inout GraphicsContext) {
        let resolved = context.resolve(
            Text(string)
                .font(IconFont.rounded(size: size, weight: weight))
                .foregroundStyle(Color(svgHex: fill))
        )
        let box = resolved.measure(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
        let ascent = resolved.firstBaseline(in: box)
        let metrics = SVGTextMetrics(ascent: ascent, descent: box.height - ascent)
        context.draw(
            resolved,
            at: metrics.topLeft(x: x, y: y, width: box.width),
            anchor: .topLeading
        )
    }
}
