import CoreGraphics
import SwiftUI

import ALCore

/// ```tsx
/// export function ExerciseIcon({ id, size = 30 }: { id: ExerciseId; size?: number }) {
///   const { tint, glyph } = GLYPHS[id];
///   return (
///     <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden style={{ display: "block" }}>
///       <rect x={2} y={2} width={28} height={28} rx={8.5} fill={tint} />
///       {glyph}
///     </svg>
///   );
/// }
/// ```
///
/// One canvas for the whole icon (D15), so the badge, the pictogram and the
/// `<text>` glyphs share one CTM and cannot scale apart: the 32-unit viewBox is
/// mapped once and the font size is expressed in those same units.
///
/// `aria-hidden` → `.accessibilityHidden(true)`. The hub names every game in
/// text beside the icon; announcing "A" twice would be noise.
public struct ExerciseIcon: View {
    public let id: ExerciseId
    public var size: CGFloat

    public init(id: ExerciseId, size: CGFloat = 30) {
        self.id = id
        self.size = size
    }

    /// `viewBox="0 0 32 32"`.
    public static let viewBox = CGRect(x: 0, y: 0, width: 32, height: 32)

    /// `<rect x={2} y={2} width={28} height={28} rx={8.5} />`.
    public static let badge = Path(
        roundedRect: CGRect(x: 2, y: 2, width: 28, height: 28),
        cornerRadius: 8.5
    )

    public var body: some View {
        let spec = exerciseIconSpec(id)
        Canvas { context, canvasSize in
            var ctx = context
            ctx.concatenate(
                SVGCanvas.viewBoxTransform(
                    Self.viewBox,
                    fitting: CGRect(origin: .zero, size: canvasSize)
                )
            )
            ctx.fill(Self.badge, with: .color(Color(svgHex: spec.tint)))
            for node in spec.nodes {
                switch node {
                case let .shapes(shapes):
                    SVGCanvas.render(shapes, into: &ctx)
                case let .text(text):
                    text.draw(into: &ctx)
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
