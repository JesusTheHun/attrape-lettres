import QuartzCore
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/Meter.tsx` — SavingsMeter: "how close am I to affording this?"     */
/* without any arithmetic.                                                     */
/*                                                                             */
/* ```tsx                                                                      */
/* const MIN_FILL = 0.07;                                                      */
/*                                                                             */
/* function fillRatio(balance: number, cost: number): number {                 */
/*   if (balance <= 0 || cost <= 0) return 0;                                  */
/*   return Math.max(MIN_FILL, Math.min(1, balance / cost));                   */
/* }                                                                           */
/*                                                                             */
/* export function SavingsMeter({ cost, balance, since, height = 10 }) {       */
/*   const shownRef = useRef(fillRatio(since, cost)); // ratio on screen        */
/*   const ratio = fillRatio(balance, cost);                                    */
/*   useEffect(() => {                                                          */
/*     const from = shownRef.current;                                           */
/*     shownRef.current = ratio;                                                */
/*     if (from === ratio) return;                                              */
/*     meterFill(fillRef.current, from * 100, ratio * 100);                     */
/*     if (ratio > from) tipSparkle(tipRef.current);                            */
/*   }, [ratio]);                                                               */
/*   … role="img" aria-label={`${balance} étoiles sur ${cost}`}                 */
/*     track  rounded-full, height, rgba(90,58,30,0.14)                         */
/*     fill   width: `${ratio * 100}%`, linear-gradient(90deg,#FFC107,#FFD54F)  */
/*     tip    ✨ absolute left: `${ratio * 100}%`, top: 50%, opacity-0           */
/* }                                                                            */
/* ```                                                                          */
/*                                                                             */
/* A fat gold bar filling toward the price: a six-year-old reads fills, not      */
/* subtraction. On mount it sweeps from the balance the child LAST SAW in the    */
/* shop (`since`) to today's, so stars earned between visits read as motion      */
/* plus a sparkle rather than an invisibly small static delta. A non-empty       */
/* wallet always shows a sliver (`MIN_FILL`), so "I have some" is always         */
/* visible.                                                                     */
/*                                                                             */
/* INVARIANT 9 — nothing here persists, accumulates or computes a balance.       */
/* `balance` and `since` are handed in by the shop, which reads them from        */
/* `ProfileStore`; this file only divides one number by another to get a width.  */
/* -------------------------------------------------------------------------- */

// MARK: - The arithmetic (pure, host-tested)

public enum SavingsMeterMetrics {
    /// `MIN_FILL` — never render progress smaller than this once the wallet is
    /// non-empty.
    public static let minFill: Double = 0.07

    /// `height = 10` — the TSX default. "Keep it fat: thin bars hide small
    /// progress."
    public static let defaultHeight: CGFloat = 10

    /// `rgba(90,58,30,0.14)` — the track. 90/58/30 is `#5A3A1E`, i.e. the ink
    /// brown at 14 %.
    public static let trackOpacity: Double = 0.14

    /// `text-sm` — the ✨ at the fill tip.
    public static let sparkleFontSize: CGFloat = Typography.Size.sm

    /// `meterFill` — `{ duration: 900, easing: "cubic-bezier(.2,.7,.3,1)" }`.
    public static let sweepDuration: Double = 0.9
    public static let sweepCurve: (Double, Double, Double, Double) = (0.2, 0.7, 0.3, 1)

    /// `tipSparkle` — `{ duration: 620, delay: 780, easing: "ease-out" }`,
    /// scale 0.2 → 1.4 (at 50 %) → 0.6, opacity 0 → 1 → 0. "lands as the fill
    /// arrives."
    public static let sparkleDuration: Double = 0.62
    public static let sparkleDelay: Double = 0.78
    public static let sparkleScales: [Double] = [0.2, 1.4, 0.6]
    public static let sparkleOpacities: [Double] = [0, 1, 0]
}

/// `fillRatio(balance, cost)` — 0…1, with the `MIN_FILL` sliver.
///
/// Both guards are the TSX's and both matter: a zero or negative balance is a
/// genuinely empty bar (no sliver), and a zero or negative cost would divide by
/// nothing.
public func savingsFillRatio(balance: Int, cost: Int) -> Double {
    if balance <= 0 || cost <= 0 { return 0 }
    return Swift.max(
        SavingsMeterMetrics.minFill,
        Swift.min(1, Double(balance) / Double(cost)))
}

/// What the mount effect decides: where the bar starts, where it lands, whether
/// it moves at all, and whether the tip sparkles.
///
/// Extracted so the rule — *sparkle only when the fill GREW* — is asserted on
/// the host rather than only visible on a device.
public struct SavingsMeterSweep: Equatable, Sendable {
    /// `from = shownRef.current`, i.e. `fillRatio(since, cost)` on mount.
    public let from: Double
    /// `ratio`, i.e. `fillRatio(balance, cost)`.
    public let to: Double

    public init(from: Double, to: Double) {
        self.from = from
        self.to = to
    }

    /// `if (from === ratio) return;` — no animation when nothing changed.
    public var animates: Bool { from != to }

    /// `if (ratio > from) tipSparkle(...)` — a shrinking bar (the child spent)
    /// still sweeps, silently.
    public var sparkles: Bool { to > from }
}

/// The mount sweep for a (balance, since, cost) triple.
public func savingsMeterSweep(balance: Int, since: Int, cost: Int) -> SavingsMeterSweep {
    SavingsMeterSweep(
        from: savingsFillRatio(balance: since, cost: cost),
        to: savingsFillRatio(balance: balance, cost: cost))
}

// MARK: - The view

/// `<SavingsMeter cost balance since height />`.
public struct SavingsMeter: View {
    private let cost: Int
    private let balance: Int
    private let since: Int
    private let height: CGFloat

    @Environment(\.alReduceMotion) private var reduceMotion

    /// The ratio currently ON SCREEN — the port of `shownRef`. Seeded from
    /// `since` in `init`, exactly as the ref is, so the first frame paints where
    /// the child left the bar and the sweep has somewhere to come from.
    @State private var shown: Double
    @State private var sparkleHandle = LayerHandle()

    /// - Parameters:
    ///   - cost: the price the bar fills toward.
    ///   - balance: the child's stars right now.
    ///   - since: the balance at the previous shop visit — where the fill
    ///     animates FROM.
    ///   - height: bar height in points. Keep it fat.
    public init(cost: Int, balance: Int, since: Int, height: CGFloat = SavingsMeterMetrics.defaultHeight) {
        self.cost = cost
        self.balance = balance
        self.since = since
        self.height = height
        _shown = State(initialValue: savingsFillRatio(balance: since, cost: cost))
    }

    /// `fillRatio(balance, cost)` — the width React writes into `style`.
    public var ratio: Double { savingsFillRatio(balance: balance, cost: cost) }

    /// `aria-label={`${balance} étoiles sur ${cost}`}` on a `role="img"`.
    public var accessibilityText: String { Copy.Shop.savings(balance, of: cost) }

    public var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            ZStack(alignment: .leading) {
                // `block w-full overflow-hidden rounded-full` + the 14 % track.
                Capsule()
                    .fill(Palette.ink.color.opacity(SavingsMeterMetrics.trackOpacity))

                // `block h-full rounded-full` + `linear-gradient(90deg,…)`.
                // Clipped by the track, so a full bar has square-ish shoulders
                // exactly as `overflow-hidden` gives on the web.
                Capsule()
                    .fill(Palette.savingsFill.gradient)
                    .frame(width: Swift.max(0, shown * width))

                // The sparkle is parked (invisible) AT the fill tip and flashed
                // by `tipSparkle`; the outer span is `overflow-visible` so it
                // may hang past the bar.
                LayerHost(handle: sparkleHandle) {
                    Text(verbatim: Copy.Shop.savingsSparkle)
                        .font(Typography.rounded(SavingsMeterMetrics.sparkleFontSize))
                        .opacity(0)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .position(x: ratio * width, y: height / 2)
            }
            .frame(height: height)
            .onAppear { sweep(to: ratio) }
            .onChange(of: ratio) { _, next in sweep(to: next) }
        }
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(Text(verbatim: accessibilityText))
    }

    /// `useEffect(..., [ratio])`: read where the bar stands, remember the new
    /// target, and animate the journey. Both animations are reduced-motion
    /// gated because `shop/anim.ts` gates them (`meterFill`/`tipSparkle` both
    /// start `if (!el || reducedMotion()) return`), and the bar still LANDS on
    /// the new value either way — a child with Reduce Motion on sees the right
    /// width, just not the trip.
    private func sweep(to next: Double) {
        let step = SavingsMeterSweep(from: shown, to: next)
        guard step.animates else { return }
        guard !reduceMotion.isReduced else {
            shown = next
            return
        }
        withAnimation(
            .timingCurve(
                SavingsMeterMetrics.sweepCurve.0,
                SavingsMeterMetrics.sweepCurve.1,
                SavingsMeterMetrics.sweepCurve.2,
                SavingsMeterMetrics.sweepCurve.3,
                duration: SavingsMeterMetrics.sweepDuration)
        ) {
            shown = next
        }
        if step.sparkles { MeterAnim.tipSparkle(sparkleHandle.layer) }
    }
}

// MARK: - The tip sparkle, as Core Animation

/// `tipSparkle` from `src/shop/anim.ts`, transform + opacity only, on the hosted
/// layer — off the render path (invariant 2), exactly like `Anim.press`.
///
/// Kept local to this file rather than added to `Anim`: `Anim` is the *shared*
/// interaction vocabulary (press / shake / pop / pulse) and the shop's sparkle
/// is one screen's ornament. If the shop package grows its own `anim.ts` port,
/// this is the function to fold into it.
///
/// macOS (D1): `LayerHost` is a no-op there, so `layer` is nil and this returns
/// having done nothing — the meter still renders and still lands on the right
/// width. The keyframes are asserted as data in the tests instead.
enum MeterAnim {

    @MainActor
    static func tipSparkle(_ layer: CALayer?) {
        guard let layer else { return }
        layer.add(sparkleAnimation(), forKey: "ALMeterTipSparkle")
    }

    /// `[{scale 0.2, opacity 0}, {scale 1.4, opacity 1, offset .5},
    ///   {scale 0.6, opacity 0}]`, 620 ms, 780 ms delay, ease-out.
    ///
    /// The web's `translate(-50%,-50%)` is not part of this: the layer is
    /// already centred on the fill tip by `.position(x:y:)`, so only the scale
    /// and the opacity are animated. No `fill` mode, so the model values (a
    /// resting opacity of 0) stand once the take ends — the sparkle vanishes.
    static func sparkleAnimation() -> CAAnimationGroup {
        let scale = keyframes(
            keyPath: "transform.scale",
            values: SavingsMeterMetrics.sparkleScales)
        let opacity = keyframes(
            keyPath: "opacity",
            values: SavingsMeterMetrics.sparkleOpacities)
        let group = CAAnimationGroup()
        group.animations = [scale, opacity]
        group.duration = SavingsMeterMetrics.sparkleDuration
        group.beginTime = CACurrentMediaTime() + SavingsMeterMetrics.sparkleDelay
        return group
    }

    /// WAAPI's `easing` applies per keyframe INTERVAL, not to the whole
    /// timeline — the same porting note `Anim` carries.
    static func keyframes(keyPath: String, values: [Double]) -> CAKeyframeAnimation {
        let animation = CAKeyframeAnimation(keyPath: keyPath)
        animation.values = values
        animation.keyTimes = [0, 0.5, 1].map { NSNumber(value: $0) }
        animation.timingFunctions = (0..<(values.count - 1)).map { _ in
            CAMediaTimingFunction(controlPoints: 0, 0, 0.58, 1)  // CSS ease-out
        }
        animation.duration = SavingsMeterMetrics.sparkleDuration
        return animation
    }
}
