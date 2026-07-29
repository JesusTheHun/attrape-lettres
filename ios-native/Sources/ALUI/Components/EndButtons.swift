import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/EndButtons.tsx` — leave, or keep going.                     */
/*                                                                             */
/* ```tsx                                                                      */
/* <div className="mt-1 flex flex-wrap items-center justify-center gap-3">     */
/*   <button                                                                   */
/*     onPointerDown={onMenu}                                                  */
/*     className="rounded-full bg-white/80 px-7 py-4 text-xl font-extrabold     */
/*                text-[#5A3A1E] shadow [touch-action:none]"                   */
/*   >🏠 Menu</button>                                                          */
/*   <button                                                                   */
/*     onPointerDown={onNext}                                                  */
/*     className="rounded-full bg-[#66BB6A] px-9 py-4 text-2xl font-extrabold   */
/*                text-white [touch-action:none]"                              */
/*     style={{ boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}   */
/*   >🎉 Suivant</button>                                                       */
/* </div>                                                                      */
/* ```                                                                         */
/*                                                                             */
/* Both buttons fire on `onPointerDown`, not on click — so both go through the */
/* app-wide `touchDown` primitive (D5, invariant 1). `[touch-action:none]` is  */
/* the web's way of saying "this gesture is mine, do not scroll"; the          */
/* recogniser handles that by construction.                                    */
/*                                                                             */
/* Neither button has a `font-black`: they are `font-extrabold` (800), which is */
/* SwiftUI `.heavy`, NOT `.black`. `Typography.Weight` spells that trap out.   */
/* -------------------------------------------------------------------------- */

public struct EndButtons: View {

    /// `gap-3`.
    public static let gap: CGFloat = 12
    /// `mt-1`.
    public static let topMargin: CGFloat = 4

    /// « 🏠 Menu » — `px-7 py-4 text-xl`, `bg-white/80`.
    public static let menuPaddingX: CGFloat = 28
    public static let menuPaddingY: CGFloat = 16

    /// « 🎉 Suivant » — `px-9 py-4 text-2xl`, `bg-[#66BB6A]`.
    public static let nextPaddingX: CGFloat = 36
    public static let nextPaddingY: CGFloat = 16

    /// `0 8px 0 #43A047`.
    public static let nextLipDrop: CGFloat = 8
    /// `0 14px 24px rgba(0,0,0,0.2)`.
    public static let nextSoftShadow = CSSShadow(y: 14, blur: 24, opacity: 0.2)

    /// Tailwind's plain `shadow` utility:
    /// `0 1px 3px 0 rgb(0 0 0/0.1), 0 1px 2px -1px rgb(0 0 0/0.1)`. The two
    /// layers are within a point of each other, so they collapse into one.
    public static let menuShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)

    public let onMenu: () -> Void
    public let onNext: () -> Void

    public init(onMenu: @escaping () -> Void, onNext: @escaping () -> Void) {
        self.onMenu = onMenu
        self.onNext = onNext
    }

    public var body: some View {
        ComponentsWrapRow(spacing: Self.gap, lineSpacing: Self.gap) {
            menuButton
            nextButton
        }
        .padding(.top, Self.topMargin)
    }

    private var menuButton: some View {
        Text(verbatim: Copy.EndButtons.menu)
            .font(Typography.rounded(Typography.Size.xl, Typography.Weight.extrabold))
            .foregroundStyle(Palette.ink.color)
            .padding(.horizontal, Self.menuPaddingX)
            .padding(.vertical, Self.menuPaddingY)
            .background {
                Capsule()
                    .fill(.white.opacity(Palette.White.o80))
                    .shadow(
                        color: .black.opacity(Self.menuShadow.opacity),
                        radius: Self.menuShadow.swiftUIRadius,
                        x: 0,
                        y: Self.menuShadow.y
                    )
            }
            .contentShape(Capsule())
            .touchDown { onMenu() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: Copy.EndButtons.menu))
            .accessibilityAddTraits(.isButton)
    }

    private var nextButton: some View {
        Text(verbatim: Copy.EndButtons.next)
            .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.extrabold))
            .foregroundStyle(.white)
            .padding(.horizontal, Self.nextPaddingX)
            .padding(.vertical, Self.nextPaddingY)
            .background {
                liftedCapsule(
                    fill: AnyShapeStyle(Palette.green.color),
                    lip: Palette.greenLip.color,
                    drop: Self.nextLipDrop,
                    soft: Self.nextSoftShadow
                )
            }
            .contentShape(Capsule())
            .touchDown { onNext() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: Copy.EndButtons.next))
            .accessibilityAddTraits(.isButton)
    }
}

/* -------------------------------------------------------------------------- */
/* `flex flex-wrap items-center justify-center gap-N`.                         */
/*                                                                             */
/* SwiftUI has no wrapping stack, and both places this module needs one — the  */
/* end buttons and the Finished star recap — are authored `flex-wrap` in the   */
/* TSX. An `HStack` would instead SHRINK its children when the row runs out of */
/* room, which on a narrow phone squashes « 🎉 Suivant » rather than dropping  */
/* it to a second line. One `Layout`, used by both.                            */
/* -------------------------------------------------------------------------- */

// NB: qualified. `ALCore` exports a `Layout` type (the mascot growth model), so
// a bare `Layout` here is « ambiguous for type lookup ». Same trap as
// `ALArt/Mascot/Rig.swift`; `LayoutSubviews` is spelled out for the same reason.
struct ComponentsWrapRow: SwiftUI.Layout {
    /// `gap-N` along the main axis.
    var spacing: CGFloat
    /// `gap-N` between wrapped lines (CSS `gap` is both).
    var lineSpacing: CGFloat

    struct Line {
        var indices: [Int] = []
        var sizes: [CGSize] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    func lines(_ subviews: LayoutSubviews, maxWidth: CGFloat) -> [Line] {
        let sizes = subviews.indices.map { subviews[$0].sizeThatFits(ProposedViewSize.unspecified) }
        return Self.lines(sizes: sizes, maxWidth: maxWidth, spacing: spacing)
    }

    /// The wrapping arithmetic, on measured sizes alone — `LayoutSubviews`
    /// cannot be constructed outside a running layout pass, and this is the
    /// part that has to be right.
    static func lines(sizes: [CGSize], maxWidth: CGFloat, spacing: CGFloat) -> [Line] {
        var out: [Line] = []
        var current = Line()
        for index in sizes.indices {
            let size = sizes[index]
            if current.indices.isEmpty {
                current.indices = [index]
                current.sizes = [size]
                current.width = size.width
                current.height = size.height
                continue
            }
            let widened = current.width + spacing + size.width
            if widened > maxWidth {
                out.append(current)
                current = Line(indices: [index], sizes: [size], width: size.width, height: size.height)
            } else {
                current.indices.append(index)
                current.sizes.append(size)
                current.width = widened
                current.height = Swift.max(current.height, size.height)
            }
        }
        if !current.indices.isEmpty { out.append(current) }
        return out
    }

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: LayoutSubviews,
        cache: inout ()
    ) -> CGSize {
        let limit: CGFloat = proposal.width ?? .infinity
        let rows = lines(subviews, maxWidth: limit)
        var width: CGFloat = 0
        var height: CGFloat = 0
        for row in rows {
            width = Swift.max(width, row.width)
            height += row.height
        }
        let gaps: CGFloat = CGFloat(Swift.max(0, rows.count - 1)) * lineSpacing
        return CGSize(width: width, height: height + gaps)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: LayoutSubviews,
        cache: inout ()
    ) {
        let rows = lines(subviews, maxWidth: bounds.width)
        var y = bounds.minY
        for row in rows {
            // justify-center
            var x = bounds.minX + (bounds.width - row.width) / 2
            for (slot, index) in row.indices.enumerated() {
                let size = row.sizes[slot]
                subviews[index].place(
                    at: CGPoint(x: x, y: y + row.height / 2),   // items-center
                    anchor: UnitPoint.leading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }
}
