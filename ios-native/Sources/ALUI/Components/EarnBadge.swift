import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/EarnBadge.tsx` — the « +N ⭐ » pill on a Finished screen.   */
/*                                                                             */
/* ```tsx                                                                      */
/* <div                                                                        */
/*   ref={usePopFlourish()}                                                    */
/*   aria-label={`Tu gagnes ${earned} étoiles`}                                */
/*   className="flex items-center gap-2 rounded-full font-black text-[#4A3B00]"*/
/*   style={{                                                                  */
/*     background: "linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)",          */
/*     padding: "clamp(8px,2.4vw,14px) clamp(18px,5vw,30px)",                  */
/*     fontSize: "clamp(28px,8vw,46px)",                                       */
/*     boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)",              */
/*   }}                                                                        */
/* >                                                                           */
/*   <span>+{earned}</span>                                                    */
/*   <span aria-hidden>⭐</span>                                                */
/* </div>                                                                      */
/* ```                                                                         */
/*                                                                             */
/* INVARIANT 8 — this component DISPLAYS. `earned` is whatever                 */
/* `ALCore.sessionReward` returned; there is no arithmetic in this file, no    */
/* rounding, no clamping and no `max(0, …)`. Read the value, show the value.   */
/* (`Finished` decides whether to show the pill at all — `earned > 0` — which  */
/* is a visibility rule, not a change to the number.)                          */
/* -------------------------------------------------------------------------- */

public struct EarnBadge: View {

    /// `clamp(8px, 2.4vw, 14px)` — the pill's vertical padding.
    public static let paddingY = FluidSpec(min: 8, vw: 2.4, max: 14)
    /// `clamp(18px, 5vw, 30px)` — its horizontal padding.
    public static let paddingX = FluidSpec(min: 18, vw: 5, max: 30)
    /// `clamp(28px, 8vw, 46px)` — the digits.
    public static let fontSize = FluidSpec(min: 28, vw: 8, max: 46)
    /// `gap-2`.
    public static let gap: CGFloat = 8

    /// `0 8px 0 #E0A800` — the hard lip's drop.
    public static let lipDrop: CGFloat = 8
    /// `0 16px 26px rgba(0,0,0,0.2)` — the soft shadow.
    public static let softShadow = CSSShadow(y: 16, blur: 26, opacity: 0.2)

    /// The points `sessionReward` returned. Displayed, never adjusted.
    public let earned: Int
    /// D14 — the pop flourish's reduced-motion gate.
    public let reduceMotion: ReduceMotionSource

    @Environment(\.alViewportWidth) private var viewport

    public init(earned: Int, reduceMotion: ReduceMotionSource) {
        self.earned = earned
        self.reduceMotion = reduceMotion
    }

    /// `<span>+{earned}</span>` — the ONLY transformation applied to the
    /// reward, and it is string interpolation. No `abs`, no `max`, no rounding:
    /// invariant 8's mechanical guard sits on this property, and `body` reads
    /// it rather than formatting inline so a test can hold it still.
    public var amountText: String { Copy.EarnBadge.amount(earned) }

    /// The pill's `aria-label`.
    public var accessibilityText: String { Copy.EarnBadge.label(earned) }

    public var body: some View {
        HStack(spacing: Self.gap) {
            Text(verbatim: amountText)
            Text(verbatim: Copy.EarnBadge.star)
        }
        .font(Typography.rounded(Self.fontSize.resolve(viewport: viewport), Typography.Weight.black))
        .foregroundStyle(Palette.goldInk.color)
        .padding(.vertical, Self.paddingY.resolve(viewport: viewport))
        .padding(.horizontal, Self.paddingX.resolve(viewport: viewport))
        .background {
            liftedCapsule(
                fill: AnyShapeStyle(Palette.goldPill.gradient),
                lip: Palette.goldLip.color,
                drop: Self.lipDrop,
                soft: Self.softShadow
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: accessibilityText))
        .popFlourish(reduceMotion: reduceMotion)
    }
}

/* -------------------------------------------------------------------------- */
/* The two-shadow pill, shared by `EarnBadge` and the « Suivant » button.       */
/* -------------------------------------------------------------------------- */

/// A CSS `box-shadow` with a blur, as authored.
///
/// SwiftUI's `.shadow(radius:)` is roughly the CSS blur RADIUS halved (CSS
/// defines its blur as twice the Gaussian standard deviation). Recorded here
/// rather than at each call site so both pills convert identically.
public struct CSSShadow: Hashable, Sendable {
    public let y: CGFloat
    public let blur: CGFloat
    public let opacity: Double

    public init(y: CGFloat, blur: CGFloat, opacity: Double) {
        self.y = y
        self.blur = blur
        self.opacity = opacity
    }

    /// The SwiftUI radius for this CSS blur.
    public var swiftUIRadius: CGFloat { blur / 2 }
}

/// `box-shadow: 0 Npx 0 <lip>, 0 Ypx Bpx rgba(0,0,0,α)` on a `rounded-full` box.
///
/// The order is the CSS one and it is load-bearing: a box-shadow list paints
/// LAST-first, so the soft blur sits below the hard lip, which sits below the
/// box. Rendering the lip as a real offset capsule (rather than a second
/// `.shadow`) is what makes the lip a flat, un-blurred colour — a `.shadow`
/// with radius 0 would also tint the *soft* shadow beneath it.
@ViewBuilder
func liftedCapsule(
    fill: AnyShapeStyle,
    lip: Color,
    drop: CGFloat,
    soft: CSSShadow
) -> some View {
    ZStack {
        // …the blurred shadow, cast by the box itself.
        Capsule()
            .fill(fill)
            .shadow(color: .black.opacity(soft.opacity), radius: soft.swiftUIRadius, x: 0, y: soft.y)
        // …then the hard lip.
        Capsule()
            .fill(lip)
            .offset(y: drop)
        // …then the box.
        Capsule()
            .fill(fill)
    }
}
