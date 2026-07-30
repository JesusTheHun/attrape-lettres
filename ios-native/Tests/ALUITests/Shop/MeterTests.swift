import QuartzCore
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `Shop/Meter.swift` against `src/shop/Meter.tsx` + `src/shop/anim.ts`.        */
/*                                                                             */
/* Every expected number below is read out of the TypeScript, never out of the  */
/* Swift: this file's whole reason to exist is that `Meter.swift` landed        */
/* untested, so an assertion sourced from the Swift would prove nothing.        */
/*                                                                             */
/*   const MIN_FILL = 0.07;                                                     */
/*   function fillRatio(balance, cost) {                                        */
/*     if (balance <= 0 || cost <= 0) return 0;                                 */
/*     return Math.max(MIN_FILL, Math.min(1, balance / cost));                  */
/*   }                                                                          */
/* -------------------------------------------------------------------------- */

/// `MIN_FILL` in `Meter.tsx`, typed out here rather than referenced, so a change
/// to the Swift constant fails this suite instead of silently redefining truth.
private let tsMinFill = 0.07

/// `height = 10` — the TSX's default prop. Typed `CGFloat` because that is what
/// a SwiftUI length is; a `Double` literal here compares through the implicit
/// CGFloat bridge and does not mean what it reads as.
private let tsDefaultHeight: CGFloat = 10

/// `Math.max(MIN_FILL, Math.min(1, balance / cost))`, transcribed.
private func tsFillRatio(_ balance: Int, _ cost: Int) -> Double {
    if balance <= 0 || cost <= 0 { return 0 }
    return max(tsMinFill, min(1, Double(balance) / Double(cost)))
}

@Suite struct SavingsFillRatioTests {

    /// `if (balance <= 0 || cost <= 0) return 0` — an empty wallet is a genuinely
    /// empty bar. The `MIN_FILL` sliver is for "I have SOME", not for "I have
    /// none".
    @Test func emptyWalletIsAnEmptyBarNotASliver() {
        #expect(savingsFillRatio(balance: 0, cost: 100) == 0)
        #expect(savingsFillRatio(balance: -1, cost: 100) == 0)
        #expect(savingsFillRatio(balance: -999, cost: 100) == 0)
    }

    /// The second guard: a zero or negative price would divide by nothing.
    @Test func nonPositiveCostIsZeroNotInfinity() {
        #expect(savingsFillRatio(balance: 40, cost: 0) == 0)
        #expect(savingsFillRatio(balance: 40, cost: -8) == 0)
        #expect(savingsFillRatio(balance: 0, cost: 0) == 0)
    }

    /// « A non-empty wallet always shows a sliver (min fill), so "I have some" is
    /// always visible. » One star against a hundred is 1 %, drawn as 7 %.
    @Test func oneStarAgainstAHundredIsFlooredAtMinFill() {
        #expect(savingsFillRatio(balance: 1, cost: 100) == tsMinFill)
        #expect(savingsFillRatio(balance: 6, cost: 100) == tsMinFill)
    }

    /// The floor's exact boundary: 7/100 IS 0.07, and 8/100 is already above it,
    /// so the `Math.max` stops biting at exactly `MIN_FILL`.
    @Test func theFloorReleasesAtExactlyMinFill() {
        #expect(savingsFillRatio(balance: 7, cost: 100) == tsMinFill)
        #expect(savingsFillRatio(balance: 8, cost: 100) == 0.08)
    }

    @Test func midwayIsThePlainQuotient() {
        #expect(savingsFillRatio(balance: 50, cost: 100) == 0.5)
        #expect(savingsFillRatio(balance: 3, cost: 4) == 0.75)
        #expect(savingsFillRatio(balance: 1, cost: 3) == tsFillRatio(1, 3))
    }

    /// Exactly affordable — `Math.min(1, 1)`. The bar is full the instant the
    /// child can buy, not one star later.
    @Test func exactlyAffordableIsFull() {
        #expect(savingsFillRatio(balance: 100, cost: 100) == 1)
        #expect(savingsFillRatio(balance: 4, cost: 4) == 1)
    }

    /// Past full — `Math.min(1, …)` clamps, so a rich child never overflows the
    /// track.
    @Test func pastFullClampsToOne() {
        #expect(savingsFillRatio(balance: 101, cost: 100) == 1)
        #expect(savingsFillRatio(balance: 100_000, cost: 4) == 1)
    }

    /// The whole curve, against a transcription of the TypeScript body.
    @Test func agreesWithTheTypeScriptAcrossTheRange() {
        for cost in [1, 3, 4, 12, 100] {
            for balance in [-3, 0, 1, 2, 7, 8, 11, 50, 99, 100, 400] {
                #expect(
                    savingsFillRatio(balance: balance, cost: cost) == tsFillRatio(balance, cost),
                    Comment(rawValue: "balance \(balance) / cost \(cost)"))
            }
        }
    }

    /// `MIN_FILL`, `height = 10`, and the track's `rgba(90,58,30,0.14)` — the
    /// numbers the view reads out of `SavingsMeterMetrics`.
    @Test func metricsMatchTheAuthoredCSS() {
        #expect(SavingsMeterMetrics.minFill == tsMinFill)
        #expect(SavingsMeterMetrics.defaultHeight == tsDefaultHeight)
        #expect(SavingsMeterMetrics.trackOpacity == 0.14)
        // 90/58/30 is #5A3A1E, the ink brown, at 14 %.
        #expect(Palette.ink.hex == "#5A3A1E")
        // `linear-gradient(90deg,#FFC107,#FFD54F)`.
        #expect(Palette.savingsFill.degrees == 90)
        #expect(Palette.savingsFill.stops.map(\.hex) == ["#FFC107", "#FFD54F"])
        // `text-sm` on the ✨.
        #expect(SavingsMeterMetrics.sparkleFontSize == 14)
    }
}

/* -------------------------------------------------------------------------- */
/* The mount sweep:                                                            */
/*   const from = shownRef.current;   // fillRatio(since, cost)                */
/*   if (from === ratio) return;                                               */
/*   meterFill(fill, from * 100, ratio * 100);                                 */
/*   if (ratio > from) tipSparkle(tip);                                        */
/* -------------------------------------------------------------------------- */

@Suite struct SavingsMeterSweepTests {

    @Test func sweepsFromTheBalanceTheChildLastSaw() {
        let step = savingsMeterSweep(balance: 60, since: 20, cost: 100)
        #expect(step.from == 0.2)
        #expect(step.to == 0.6)
        #expect(step.animates)
        #expect(step.sparkles)
    }

    /// `if (from === ratio) return` — a shop visit with nothing earned in between
    /// does not animate at all.
    @Test func anUnchangedBalanceDoesNotAnimate() {
        let step = savingsMeterSweep(balance: 60, since: 60, cost: 100)
        #expect(step.from == step.to)
        #expect(step.animates == false)
        #expect(step.sparkles == false)
    }

    /// `if (ratio > from)` — a shrinking bar (the child spent) still sweeps, but
    /// silently. No sparkle for losing stars.
    @Test func spendingSweepsWithoutASparkle() {
        let step = savingsMeterSweep(balance: 10, since: 90, cost: 100)
        #expect(step.from == 0.9)
        #expect(step.to == 0.1)
        #expect(step.animates)
        #expect(step.sparkles == false)
    }

    /// The `MIN_FILL` floor swallows a real gain: 1 → 5 stars against 100 both
    /// render at 0.07, so `from === ratio` and the TSX skips both animations.
    /// Ported behaviour, not a bug — the pixels genuinely did not move.
    @Test func aGainHiddenByTheFloorIsNotAnimated() {
        let step = savingsMeterSweep(balance: 5, since: 1, cost: 100)
        #expect(step.from == tsMinFill)
        #expect(step.to == tsMinFill)
        #expect(step.animates == false)
        #expect(step.sparkles == false)
    }

    /// First ever visit: `since` is 0, so the bar grows out of nothing and
    /// sparkles.
    @Test func aFirstVisitGrowsFromEmpty() {
        let step = savingsMeterSweep(balance: 3, since: 0, cost: 100)
        #expect(step.from == 0)
        #expect(step.to == tsMinFill)
        #expect(step.animates)
        #expect(step.sparkles)
    }

    /// Both ends clamp: a child who could already afford it twice over and now
    /// three times sees no movement.
    @Test func bothEndsClampAtFull() {
        let step = savingsMeterSweep(balance: 300, since: 200, cost: 100)
        #expect(step.from == 1)
        #expect(step.to == 1)
        #expect(step.animates == false)
    }

    /// `savingsMeterSweep` is `SavingsMeterSweep(from:to:)` over `fillRatio` —
    /// asserted independently so the convenience cannot drift from the pair.
    @Test func theConvenienceMatchesTheTwoRatios() {
        for (balance, since, cost) in [(0, 0, 5), (9, 4, 12), (12, 0, 12), (2, 40, 40)] {
            let step = savingsMeterSweep(balance: balance, since: since, cost: cost)
            #expect(step.from == tsFillRatio(since, cost))
            #expect(step.to == tsFillRatio(balance, cost))
        }
    }
}

/* -------------------------------------------------------------------------- */
/* aria-label and the WAAPI takes.                                             */
/* -------------------------------------------------------------------------- */

@MainActor
@Suite struct SavingsMeterViewTests {

    /// `aria-label={`${balance} étoiles sur ${cost}`}` — note « étoiles » is NOT
    /// pluralised by the TSX, so 1 star still says « étoiles ».
    @Test func accessibilityLabelIsTheTSXTemplate() {
        #expect(SavingsMeter(cost: 12, balance: 5, since: 0).accessibilityText == "5 étoiles sur 12")
        #expect(SavingsMeter(cost: 12, balance: 1, since: 0).accessibilityText == "1 étoiles sur 12")
        #expect(SavingsMeter(cost: 12, balance: 0, since: 0).accessibilityText == "0 étoiles sur 12")
    }

    /// `width: `${ratio * 100}%`` — the view's `ratio` is the target, not the
    /// animated value.
    @Test func ratioIsTheTargetFill() {
        #expect(SavingsMeter(cost: 100, balance: 40, since: 90).ratio == 0.4)
        #expect(SavingsMeter(cost: 100, balance: 0, since: 90).ratio == 0)
    }

    /// `{ duration: 900, easing: "cubic-bezier(.2,.7,.3,1)" }` in `meterFill`.
    @Test func fillSweepIsNineHundredMillisecondsOnTheAuthoredCurve() {
        #expect(SavingsMeterMetrics.sweepDuration == 0.9)
        #expect(SavingsMeterMetrics.sweepCurve == (0.2, 0.7, 0.3, 1))
    }

    /// `tipSparkle`: `{ duration: 620, delay: 780, easing: "ease-out" }` over
    /// `scale 0.2 → 1.4 (offset .5) → 0.6` and `opacity 0 → 1 → 0`.
    @Test func sparkleKeyframesAreTheAuthoredOnes() throws {
        let group = MeterAnim.sparkleAnimation()
        #expect(group.duration == 0.62)
        // `delay: 780`, expressed as a begin time in the future.
        #expect(abs(group.beginTime - CACurrentMediaTime() - 0.78) < 0.05)

        let takes = try #require(group.animations as? [CAKeyframeAnimation])
        #expect(takes.count == 2)

        let scale = try #require(takes.first { $0.keyPath == "transform.scale" })
        #expect(scale.values as? [Double] == [0.2, 1.4, 0.6])
        #expect(scale.keyTimes?.map(\.doubleValue) == [0, 0.5, 1])
        #expect(scale.duration == 0.62)

        let opacity = try #require(takes.first { $0.keyPath == "opacity" })
        #expect(opacity.values as? [Double] == [0, 1, 0])
        #expect(opacity.keyTimes?.map(\.doubleValue) == [0, 0.5, 1])
    }

    /// WAAPI's `easing` applies per keyframe INTERVAL, so a three-keyframe take
    /// needs two timing functions, both CSS `ease-out` = (0,0,0.58,1).
    @Test func easeOutIsAppliedPerInterval() throws {
        let group = MeterAnim.sparkleAnimation()
        let takes = try #require(group.animations as? [CAKeyframeAnimation])
        for take in takes {
            #expect(take.timingFunctions?.count == 2)
            for function in take.timingFunctions ?? [] {
                var points = [Float](repeating: 0, count: 2)
                function.getControlPoint(at: 1, values: &points)
                #expect(points[0] == 0)
                #expect(points[1] == 0)
                function.getControlPoint(at: 2, values: &points)
                #expect(points[0] == 0.58)
                #expect(points[1] == 1)
            }
        }
    }

    /// No `fill` mode in the TSX's `el.animate(…)`, so the resting opacity of 0
    /// stands once the take ends: the sparkle vanishes rather than sticking.
    @Test func sparkleDoesNotPersistItsFinalFrame() {
        let group = MeterAnim.sparkleAnimation()
        #expect(group.isRemovedOnCompletion)
        #expect(group.fillMode == .removed)
    }

    /// `tipSparkle(null)` returns early in the TSX; the Swift takes a nil layer
    /// (which is every macOS run — `LayerHost` is a no-op there, D1) and must not
    /// trap.
    @Test func aNilLayerIsANoOp() {
        MeterAnim.tipSparkle(nil)
    }
}
