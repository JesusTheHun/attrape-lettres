import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/Finished.tsx` — the shared end-of-session screen.           */
/*                                                                             */
/* ```tsx                                                                      */
/* <div className="relative z-[41] flex flex-1 flex-col items-center           */
/*                 justify-center gap-5 px-6 text-center">                     */
/*   <div style={{ fontSize: "clamp(64px,20vw,110px)", lineHeight: 1 }}>🤩</div>*/
/*   {earned > 0 && <EarnBadge earned={earned} />}                             */
/*   <div className="flex flex-wrap justify-center gap-1">                     */
/*     {stars.map((s, i) => (                                                  */
/*       <span key={i} style={{ fontSize: 28,                                  */
/*         ...(s ? null : { filter: "grayscale(1)", opacity: 0.45 }) }}>⭐</span>*/
/*     ))}                                                                     */
/*   </div>                                                                    */
/*   <h2 className="m-0 font-black text-[#5A3A1E]"                             */
/*       style={{ fontSize: "clamp(26px,7vw,40px)" }}>{title}</h2>             */
/*   <EndButtons onMenu={onMenu} onNext={onNext} />                            */
/* </div>                                                                      */
/* ```                                                                         */
/*                                                                             */
/* INVARIANT 8, twice over:                                                     */
/*                                                                             */
/*  - `earned` is `ALCore.sessionReward`'s return value, shown as-is. This file */
/*    performs no arithmetic on it. The only decision it makes is whether the   */
/*    pill APPEARS — `earned > 0`, a visibility rule. Every finished run now    */
/*    pays at least the curve's floor, so the guard no longer fires; it stays   */
/*    because the day an exercise pays nothing again, a « +0 » would read as a  */
/*    punishment.                                                              */
/*  - `stars` is the same per-round first-try array the in-game strip rendered. */
/*    A greyed star is still DRAWN: the round counts as played (invariants 3    */
/*    and 8). Never filter the array, never re-derive it.                       */
/* -------------------------------------------------------------------------- */

public struct Finished: View {

    /// `gap-5`.
    public static let gap: CGFloat = 20
    /// `px-6`.
    public static let paddingX: CGFloat = 24
    /// `gap-1` between recap stars.
    public static let starGap: CGFloat = 4
    /// `fontSize: 28` — a plain number in the TSX, not a clamp.
    public static let starSize: CGFloat = 28
    /// `clamp(64px, 20vw, 110px)` on the 🤩.
    public static let cheerSize = FluidSpec(min: 64, vw: 20, max: 110)
    /// `clamp(26px, 7vw, 40px)` on the title.
    public static let titleSize = FluidSpec(min: 26, vw: 7, max: 40)
    /// `z-[41]` — one above `GameFrame`'s confetti canvas (`zIndex: 40`).
    public static let zIndex: Double = 41

    /// Per-round first-try flags, in round order. `true` = cleared first try.
    public let stars: [Bool]
    /// What `sessionReward` returned. Displayed, not computed.
    public let earned: Int
    /// The exercise's end-of-run headline (`Copy.Finished.allFound`, …).
    public let title: String
    public let onMenu: () -> Void
    public let onNext: () -> Void
    /// D14 — passed down to the `EarnBadge`'s pop.
    public let reduceMotion: ReduceMotionSource

    @Environment(\.alViewportWidth) private var viewport

    public init(
        stars: [Bool],
        earned: Int,
        title: String,
        reduceMotion: ReduceMotionSource,
        onMenu: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) {
        self.stars = stars
        self.earned = earned
        self.title = title
        self.reduceMotion = reduceMotion
        self.onMenu = onMenu
        self.onNext = onNext
    }

    /// What the screen shows, as data — the whole of invariant 8's surface in
    /// this file, in one pure function `body` actually consumes.
    ///
    /// `earned` is `nil` exactly when the pill is hidden, and otherwise is the
    /// value handed in, unchanged. `stars` is the array handed in, unchanged
    /// and unfiltered.
    public struct Display: Equatable, Sendable {
        /// `earned > 0 && <EarnBadge earned>` — nil means "no pill".
        public let earned: Int?
        public let stars: [Bool]
        public let title: String
    }

    /// `{earned > 0 && <EarnBadge earned={earned} />}` and nothing else.
    public static func display(stars: [Bool], earned: Int, title: String) -> Display {
        Display(earned: earned > 0 ? earned : nil, stars: stars, title: title)
    }

    public var display: Display {
        Self.display(stars: stars, earned: earned, title: title)
    }

    public var body: some View {
        let model = display
        return VStack(spacing: Self.gap) {
            // No `aria-hidden` in the TSX — the 🤩 and the recap stars are
            // announced on the web, so they are announced here too. Freezing
            // behaviour means not "tidying" the screen-reader output either.
            Text(verbatim: Copy.Finished.cheerEmoji)
                .font(.system(size: Self.cheerSize.resolve(viewport: viewport)))

            if let shown = model.earned {
                EarnBadge(earned: shown, reduceMotion: reduceMotion)
            }

            starRecap(model.stars)

            Text(verbatim: model.title)
                .font(Typography.rounded(Self.titleSize.resolve(viewport: viewport), Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)

            EndButtons(onMenu: onMenu, onNext: onNext)
        }
        .multilineTextAlignment(.center)   // text-center
        .padding(.horizontal, Self.paddingX)
        // flex-1 + items-center + justify-center: fill the frame, centre both ways.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .zIndex(Self.zIndex)
    }

    /// The star row. `flex flex-wrap justify-center gap-1` — wraps on a long
    /// run rather than shrinking the stars.
    private func starRecap(_ stars: [Bool]) -> some View {
        ComponentsWrapRow(spacing: Self.starGap, lineSpacing: Self.starGap) {
            ForEach(Array(stars.enumerated()), id: \.offset) { _, won in
                Text(verbatim: Copy.Finished.star)
                    .font(.system(size: Self.starSize))
                    // `filter: grayscale(1); opacity: 0.45` on a lost round.
                    .saturation(won ? 1 : Palette.Lost.saturation)
                    .opacity(won ? 1 : Palette.Lost.opacity)
            }
        }
    }
}
