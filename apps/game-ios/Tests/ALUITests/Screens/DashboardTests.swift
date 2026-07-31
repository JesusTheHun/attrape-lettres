import CoreGraphics
import Foundation
import QuartzCore
import SwiftUI
import Testing

import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `src/components/Dashboard.tsx`, asserted against the TypeScript.             */
/*                                                                             */
/* ```tsx                                                                       */
/* const { config, balance } = profile;                                         */
/* const stage = config.stage;                                                  */
/* const pct = ((stage + 1) / GROWTH_STAGES) * 100;                             */
/*                                                                              */
/* const mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)));     */
/*                                                                              */
/* useEffect(() => {                                                            */
/*   const el = fillRef.current;                                                */
/*   if (!el) return;                                                           */
/*   el.style.width = `${pct}%`;                                                */
/*   const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)")     */
/*     .matches ?? false;                                                       */
/*   if (reduce) return;                                                        */
/*   const anim = el.animate([{ width: "0%" }, { width: `${pct}%` }], {         */
/*     duration: 900, easing: "cubic-bezier(.2,.9,.3,1)",                       */
/*   });                                                                        */
/*   return () => anim.cancel();                                                */
/* }, [pct]);                                                                   */
/* ```                                                                          */
/*                                                                             */
/* `GROWTH_STAGES = 10` (`src/types.ts:280`). Tailwind spacing is n × 4 px:     */
/* px-5 = 20, pt-6 = 24, pb-10 = 40, gap-5 = 20, p-4 = 16, mb-2 = 8, mt-1 = 4,  */
/* -mt-3 = -12, px-8 = 32, py-4 = 16, py-3 = 12, px-4 = 16, py-2 = 8, h-5 = 20, */
/* gap-2 = 8.                                                                   */
/* -------------------------------------------------------------------------- */

/// `GROWTH_STAGES` in `src/types.ts` — typed out here, not read from ALCore.
private let tsGrowthStages = 10

// MARK: - mascotSize

@Suite("Dashboard — the ResizeObserver sizing")
struct DashboardMascotSizeTests {

    /// Every expectation below was produced by running the TypeScript
    /// expression `Math.round(Math.min(230, Math.max(140, b * 0.46)))` in node.
    @Test("the clamp and the rounding are the TSX's")
    func sizes() {
        #expect(dashboardMascotSize(box: 0) == 140)
        #expect(dashboardMascotSize(box: 100) == 140)
        #expect(dashboardMascotSize(box: 304) == 140)   // 139.84 → floor of the clamp
        #expect(dashboardMascotSize(box: 305) == 140)   // 140.3
        #expect(dashboardMascotSize(box: 306) == 141)   // 140.76
        #expect(dashboardMascotSize(box: 400) == 184)
        #expect(dashboardMascotSize(box: 401) == 184)   // 184.46
        #expect(dashboardMascotSize(box: 402) == 185)   // 184.92
        #expect(dashboardMascotSize(box: 499) == 230)   // 229.54 → 230
        #expect(dashboardMascotSize(box: 500) == 230)
        #expect(dashboardMascotSize(box: 1000) == 230)  // capped, not scaled
    }

    @Test("a half rounds UP, as Math.round does")
    func halfRoundsUp() {
        // 375 × 0.46 = 172.5 exactly; JS answers 173.
        #expect(dashboardMascotSize(box: 375) == 173)
    }

    @Test("the size never leaves 140…230, for any width a tablet can produce")
    func alwaysInRange() {
        for box in stride(from: CGFloat(0), through: 2000, by: 7) {
            let size = dashboardMascotSize(box: box)
            #expect(size >= 140 && size <= 230, Comment(rawValue: "box \(box) → \(size)"))
        }
    }

    @Test("the pedestal is clamp(190px, 62%, 300px) of the CARD, not of the window")
    func pedestal() {
        // `width: "clamp(190px,62%,300px)"` on a `aspect-square` circle.
        let side: (CGFloat) -> CGFloat = {
            fluidPercent(
                min: DashboardMetrics.pedestalMin,
                percent: DashboardMetrics.pedestalPercent,
                max: DashboardMetrics.pedestalMax,
                of: $0)
        }
        #expect(side(0) == 190)
        #expect(side(300) == 190)      // 186 → floor
        #expect(side(400) == 248)      // 248
        #expect(side(500) == 300)      // 310 → cap
        #expect(DashboardMetrics.pedestalMin == 190)
        #expect(DashboardMetrics.pedestalPercent == 62)
        #expect(DashboardMetrics.pedestalMax == 300)
    }
}

// MARK: - the growth meter

@Suite("Dashboard — the growth meter")
struct GrowthMeterTests {

    @Test("GROWTH_STAGES is 10 and the meter counts from 1")
    func stagesAndCounter() {
        #expect(growthStages == tsGrowthStages)
        #expect(GrowthMeter(stage: 0).counterText == "1/10")
        #expect(GrowthMeter(stage: 4).counterText == "5/10")
        #expect(GrowthMeter(stage: 9).counterText == "10/10")
    }

    @Test("pct = ((stage + 1) / GROWTH_STAGES) * 100")
    func percent() {
        #expect(GrowthMeter(stage: 0).percent == 10)
        #expect(GrowthMeter(stage: 1).percent == 20)
        #expect(GrowthMeter(stage: 4).percent == 50)
        #expect(GrowthMeter(stage: 9).percent == 100)
        // A brand-new companion still shows a sliver: the bar is never empty,
        // because the first stage already counts as one of ten.
        #expect(GrowthMeter(stage: 0).percent > 0)
    }

    @Test("the ARIA range is 1…GROWTH_STAGES with valuenow = stage + 1")
    func ariaRange() {
        // `aria-valuemin={1} aria-valuemax={GROWTH_STAGES} aria-valuenow={stage + 1}`.
        let m = GrowthMeter(stage: 3)
        #expect(m.minimum == 1)
        #expect(m.maximum == tsGrowthStages)
        #expect(m.value == 4)
    }

    @Test("the fill resolves the percentage against the track it is given")
    func fillWidth() {
        #expect(GrowthMeter(stage: 4).fillWidth(track: 400) == 200)
        #expect(GrowthMeter(stage: 9).fillWidth(track: 400) == 400)
        #expect(GrowthMeter(stage: 0).fillWidth(track: 400) == 40)
        // No track yet (first layout pass): no fill, and no negative width.
        #expect(GrowthMeter(stage: 4).fillWidth(track: 0) == 0)
    }

    @Test("the meter is a function of stage alone — nothing is stored or summed")
    func derivedFromStageOnly() {
        // Invariant 9's shape at this scale: two meters built from the same
        // stage are equal, and there is no history, no accumulator, no clock.
        #expect(GrowthMeter(stage: 6) == GrowthMeter(stage: 6))
        #expect(GrowthMeter(stage: 6) != GrowthMeter(stage: 7))
    }
}

// MARK: - the sweep

@Suite("Dashboard — the growth sweep")
struct GrowthSweepTests {

    @Test("the bar LANDS at pct whether or not the sweep runs")
    func alwaysSettles() {
        // `el.style.width = `${pct}%`` happens BEFORE the reduced-motion check.
        // A port that gated the whole effect would show a child with Reduce
        // Motion on an empty bar.
        #expect(GrowthSweep(percent: 50, reduceMotion: false).settled == 50)
        #expect(GrowthSweep(percent: 50, reduceMotion: true).settled == 50)
    }

    @Test("reduced motion skips the animation and only the animation (D29)")
    func gating() {
        #expect(GrowthSweep(percent: 50, reduceMotion: false).animates == true)
        #expect(GrowthSweep(percent: 50, reduceMotion: true).animates == false)
    }

    @Test("the sweep replays from 0%, not from wherever the bar was")
    func startsFromZero() {
        // `[{ width: "0%" }, { width: `${pct}%` }]` — an entrance, not a delta
        // (that is the shop meter's job, and it is a different animation).
        #expect(GrowthSweep(percent: 90, reduceMotion: false).from == 0)
    }

    @Test("900 ms on cubic-bezier(.2,.9,.3,1)")
    func timings() {
        #expect(DashboardMetrics.sweepDuration == 0.9)
        #expect(DashboardMetrics.sweepCurve == (0.2, 0.9, 0.3, 1))
        // NOT the shop meter's curve — `meterFill` is cubic-bezier(.2,.7,.3,1)
        // at the same duration, and the two must not be merged.
        #expect(DashboardMetrics.sweepCurve != SavingsMeterMetrics.sweepCurve)
    }

    @Test("the width animation is bounds.size.width, 0 → the settled width")
    func widthAnimation() {
        let a = GrowthAnim.widthAnimation(to: 200)
        #expect(a.keyPath == "bounds.size.width")
        #expect(a.fromValue as? CGFloat == 0)
        #expect(a.toValue as? CGFloat == 200)
        #expect(a.duration == 0.9)
    }

    @Test("the position animation pins the LEFT edge, so the bar grows rightwards")
    func positionAnimation() {
        // A centred anchor would open the bar out from the middle; CSS `width`
        // grows from the inline start.
        let a = GrowthAnim.positionAnimation(centerX: 100, width: 200)
        #expect(a.keyPath == "position.x")
        #expect(a.fromValue as? CGFloat == 0)     // a zero-width bar's centre
        #expect(a.toValue as? CGFloat == 100)
        #expect(a.duration == 0.9)
    }

    @Test("the curve is the authored one, as control points")
    func curve() {
        var points = [Float](repeating: 0, count: 2)
        let timing = GrowthAnim.timing()
        timing.getControlPoint(at: 1, values: &points)
        #expect(points[0] == 0.2)
        #expect(points[1] == 0.9)
        timing.getControlPoint(at: 2, values: &points)
        #expect(points[0] == 0.3)
        #expect(points[1] == 1.0)
    }

    @MainActor
    @Test("reduced motion adds no animation to the layer at all")
    func reducedMotionAddsNothing() {
        let layer = CALayer()
        layer.frame = CGRect(x: 0, y: 0, width: 200, height: 20)
        GrowthAnim.widthSweep(layer, reduceMotion: true)
        #expect(layer.animation(forKey: GrowthAnim.widthKey) == nil)
        #expect(layer.animation(forKey: GrowthAnim.positionKey) == nil)
    }

    @MainActor
    @Test("otherwise both animations land on the layer, sized from its own bounds")
    func addsBothAnimations() throws {
        let layer = CALayer()
        layer.frame = CGRect(x: 0, y: 0, width: 200, height: 20)  // position = (100, 10)
        GrowthAnim.widthSweep(layer, reduceMotion: false)
        let width = try #require(layer.animation(forKey: GrowthAnim.widthKey) as? CABasicAnimation)
        let position = try #require(
            layer.animation(forKey: GrowthAnim.positionKey) as? CABasicAnimation)
        #expect(width.toValue as? CGFloat == 200)
        #expect(position.fromValue as? CGFloat == 0)
        #expect(position.toValue as? CGFloat == 100)
    }

    @MainActor
    @Test("a zero-width bar and a missing layer are both no-ops, not crashes")
    func degenerateCases() {
        GrowthAnim.widthSweep(nil, reduceMotion: false)
        let empty = CALayer()
        GrowthAnim.widthSweep(empty, reduceMotion: false)
        #expect(empty.animation(forKey: GrowthAnim.widthKey) == nil)
    }
}

// MARK: - invariant 9

@Suite("Dashboard — invariant 9: the balance is a fold, never a total")
@MainActor
struct DashboardBalanceTests {

    private static let t0: Millis = 1_700_000_000_000

    @Test("the displayed number is the merge fold over per-device counters")
    func balanceIsAFold() throws {
        // Two devices each earned; one spent. The dashboard shows the fold, and
        // nowhere in the persisted shape is that number written down.
        var profile = defaultProfile
        profile.stars.earned = ["phone": 30, "tablet": 12]
        profile.stars.spent = ["phone": 7]
        let kv = InMemoryKVStore()
        let store = ProfileStore(kv: kv, device: { "phone" }, now: { Self.t0 })
        store.createChild(name: "Léa")
        let id = try #require(store.activeId)
        // Reach the counters through the store's own save path so the test is
        // reading what the app would read.
        var roster = store.roster
        roster.children[0].profile = profile
        ProfileStorage.saveRoster(roster, to: kv)
        let reloaded = ProfileStore(kv: kv, device: { "phone" }, now: { Self.t0 })
        reloaded.selectChild(id: id)

        #expect(reloaded.profile.balance == 30 + 12 - 7)
        #expect(Copy.Dashboard.balance(reloaded.profile.balance) == "Tu as 35 étoiles")
    }

    @Test("PersistedProfile has no balance field to persist")
    func noBalanceField() {
        let labels = Mirror(reflecting: defaultProfile).children.compactMap(\.label)
        #expect(!labels.contains("balance"))
        #expect(labels.contains("stars"))
    }

    @Test("the balance label pluralises on > 1, so zero takes the singular")
    func pluralisation() {
        // `aria-label={`Tu as ${balance} ${balance > 1 ? "étoiles" : "étoile"}`}`.
        #expect(Copy.Dashboard.balance(0) == "Tu as 0 étoile")
        #expect(Copy.Dashboard.balance(1) == "Tu as 1 étoile")
        #expect(Copy.Dashboard.balance(2) == "Tu as 2 étoiles")
        #expect(Copy.Dashboard.balance(35) == "Tu as 35 étoiles")
    }

    @Test("the screen's fixed copy is the TSX's")
    func copy() {
        #expect(Copy.Dashboard.backToMenuLabel == "← Menu")
        #expect(Copy.Dashboard.backToMenu == "Retour au menu")
        #expect(Copy.Dashboard.heading == "Mon copain")
        #expect(Copy.Dashboard.balanceGlyph == "⭐")
        #expect(Copy.Dashboard.balanceCaption == "étoiles à dépenser")
        #expect(Copy.Dashboard.growth == "🌱 Croissance")
        #expect(Copy.Dashboard.growthBar == "Croissance de ton copain")
        #expect(Copy.Dashboard.shopDoor == "Boutique 🛍️")
        #expect(Copy.Dashboard.switchCompanion == "Changer de copain 🔄")
    }
}

// MARK: - authored metrics

@Suite("Dashboard — the authored numbers")
struct DashboardMetricsTests {

    @Test("the stage: px-5 pb-10 pt-6, gap-5, rounded-3xl, min-h-[620px]")
    func stage() {
        #expect(DashboardMetrics.stagePaddingX == 20)
        #expect(DashboardMetrics.stagePaddingTop == 24)
        #expect(DashboardMetrics.stagePaddingBottom == 40)
        #expect(DashboardMetrics.stageGap == 20)
        #expect(DashboardMetrics.cornerRadius == 24)
        #expect(Shell.minimumScreenHeight == 620)
    }

    @Test("the header: px-4 py-2 on « ← Menu » and an 84 pt counterweight")
    func header() {
        #expect(DashboardMetrics.backPaddingX == 16)
        #expect(DashboardMetrics.backPaddingY == 8)
        #expect(DashboardMetrics.headerSpacer == 84)
    }

    @Test("the balance pill's three clamps and its two shadows")
    func balancePill() {
        #expect(DashboardMetrics.balancePaddingY == FluidSpec(min: 8, vw: 2.6, max: 15))
        #expect(DashboardMetrics.balancePaddingX == FluidSpec(min: 22, vw: 6.5, max: 36))
        #expect(DashboardMetrics.balanceFontSize == FluidSpec(min: 34, vw: 11, max: 62))
        #expect(DashboardMetrics.balanceGap == 8)
        // `0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)`.
        #expect(DashboardMetrics.balanceLipDrop == 8)
        #expect(DashboardMetrics.balanceSoftShadow == CSSShadow(y: 16, blur: 26, opacity: 0.2))
        #expect(DashboardMetrics.captionTopInset == -12)
    }

    @Test("the balance clamps resolve on a phone and on a tablet")
    func balanceClamps() {
        // 390 pt phone: 11vw = 42.9 → inside the 34…62 band.
        #expect(DashboardMetrics.balanceFontSize.resolve(viewport: 390) == 42.9)
        // 820 pt tablet: 90.2 → capped at 62.
        #expect(DashboardMetrics.balanceFontSize.resolve(viewport: 820) == 62)
        // 280 pt: 30.8 → floored at 34.
        #expect(DashboardMetrics.balanceFontSize.resolve(viewport: 280) == 34)
    }

    @Test("the growth card: max-w-[420px], p-4, mb-2, h-5")
    func growthCard() {
        #expect(DashboardMetrics.cardMaxWidth == 420)
        #expect(DashboardMetrics.cardPadding == 16)
        #expect(DashboardMetrics.cardHeaderSpacing == 8)
        #expect(DashboardMetrics.barHeight == 20)
    }

    @Test("the two doors: px-8, py-4 / py-3, and two different lips")
    func doors() {
        #expect(DashboardMetrics.shopTopInset == 4)
        #expect(DashboardMetrics.doorPaddingX == 32)
        #expect(DashboardMetrics.shopPaddingY == 16)
        #expect(DashboardMetrics.switchPaddingY == 12)
        // `0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)` on Boutique…
        #expect(DashboardMetrics.shopLipDrop == 8)
        #expect(DashboardMetrics.shopSoftShadow == CSSShadow(y: 14, blur: 24, opacity: 0.2))
        // …and `0 5px 0 rgba(0,0,0,0.08)` on Changer de copain: a lip, no blur.
        #expect(DashboardMetrics.switchLipDrop == 5)
        #expect(DashboardMetrics.switchLipOpacity == 0.08)
    }

    @Test("the play-surface wash, not the adult one: the cream stops at 38 %")
    func stageGradient() {
        // `linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)`.
        // Onboarding / WhoIsPlaying / the shop stop it at 40 %; the two
        // constants are load-bearing and must not be unified.
        #expect(Palette.stage.degrees == 180)
        #expect(Palette.stage.stops.map(\.hex) == ["#FFE7C9", "#FFEFD6", "#DCEFFB"])
        #expect(Palette.stage.stops[1].location == 0.38)
        #expect(Palette.stage.stops[1].location != Palette.stageAdult.stops[1].location)
    }

    @Test("the bar's two colours are the authored ones")
    func barColours() {
        // track `#E9DCC7`, fill `linear-gradient(90deg,#AED581,#66BB6A)`.
        #expect(Palette.growthTrack.hex == "#E9DCC7")
        #expect(Palette.growthFill.degrees == 90)
        #expect(Palette.growthFill.stops.map(\.hex) == ["#AED581", "#66BB6A"])
    }
}

// MARK: - what covers what

#if canImport(AppKit) || canImport(UIKit)

@MainActor
private func pixel(
    _ size: CGSize,
    at point: CGPoint,
    @ViewBuilder _ content: () -> some View
) -> (r: Int, g: Int, b: Int)? {
    let renderer = ImageRenderer(
        content: content().frame(width: size.width, height: size.height))
    renderer.scale = 1
    guard let cg = renderer.cgImage else { return nil }
    var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
    guard
        let space = CGColorSpace(name: CGColorSpace.sRGB),
        let ctx = CGContext(
            data: &buffer, width: cg.width, height: cg.height,
            bitsPerComponent: 8, bytesPerRow: cg.width * 4,
            space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    let x = Int(point.x), y = Int(point.y)
    guard x >= 0, y >= 0, x < cg.width, y < cg.height else { return nil }
    let i = (y * cg.width + x) * 4
    return (Int(buffer[i]), Int(buffer[i + 1]), Int(buffer[i + 2]))
}

/// The bar is 20 pt tall and its ends are round, so every sample sits on the
/// vertical centre line where only the fill and the track compete.
@Suite("Dashboard — the growth bar, rasterised", .serialized)
@MainActor
struct GrowthBarRenderTests {

    private static let size = CGSize(width: 400, height: 20)

    /// `#E9DCC7`.
    private static let track = (r: 0xE9, g: 0xDC, b: 0xC7)

    private func isGreenish(_ px: (r: Int, g: Int, b: Int)) -> Bool {
        // Anywhere between #AED581 and #66BB6A: green dominates, blue is lowest.
        px.g > px.r && px.r > px.b
    }

    private func near(_ px: (r: Int, g: Int, b: Int), _ want: (r: Int, g: Int, b: Int)) -> Bool {
        abs(px.r - want.r) <= 4 && abs(px.g - want.g) <= 4 && abs(px.b - want.b) <= 4
    }

    @Test("at stage 5 of 10 the left half is fill and the right half is track")
    func halfFull() throws {
        let bar = { GrowthBar(meter: GrowthMeter(stage: 4), reduceMotion: FixedReduceMotion(true)) }
        let left = try #require(pixel(Self.size, at: CGPoint(x: 100, y: 10), bar))
        let right = try #require(pixel(Self.size, at: CGPoint(x: 300, y: 10), bar))
        #expect(isGreenish(left), Comment(rawValue: "left half was \(left), expected the fill"))
        #expect(near(right, Self.track), Comment(rawValue: "right half was \(right), expected #E9DCC7"))
    }

    @Test("the fill paints OVER the track, not behind it")
    func fillCoversTrack() throws {
        // The ZStack order is the DOM order: the fill is a child of the track.
        // Only pixels can tell a reversed stack from a correct one.
        let px = try #require(
            pixel(Self.size, at: CGPoint(x: 10, y: 10)) {
                GrowthBar(meter: GrowthMeter(stage: 9), reduceMotion: FixedReduceMotion(true))
            })
        #expect(isGreenish(px), Comment(rawValue: "a full bar showed \(px) at its left edge"))
    }

    @Test("a first-stage bar still shows a sliver of fill")
    func firstStageIsVisible() throws {
        // 1/10 of 400 = 40 pt. A child who has just chosen a companion must see
        // that they have started.
        let bar = { GrowthBar(meter: GrowthMeter(stage: 0), reduceMotion: FixedReduceMotion(true)) }
        let inside = try #require(pixel(Self.size, at: CGPoint(x: 20, y: 10), bar))
        let outside = try #require(pixel(Self.size, at: CGPoint(x: 200, y: 10), bar))
        #expect(isGreenish(inside))
        #expect(near(outside, Self.track))
    }

    @Test("reduced motion changes what MOVES, never what is shown")
    func reducedMotionRendersTheSameBar() throws {
        let awake = try #require(
            pixel(Self.size, at: CGPoint(x: 100, y: 10)) {
                GrowthBar(meter: GrowthMeter(stage: 4), reduceMotion: FixedReduceMotion(false))
            })
        let asleep = try #require(
            pixel(Self.size, at: CGPoint(x: 100, y: 10)) {
                GrowthBar(meter: GrowthMeter(stage: 4), reduceMotion: FixedReduceMotion(true))
            })
        #expect(awake == asleep)
    }
}

#endif

// MARK: - source scan

@Suite("Dashboard — what the file may not do")
struct DashboardSourceScanTests {

    private static let source: URL =
        URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // …/Tests/ALUITests/Screens
        .deletingLastPathComponent()  // …/Tests/ALUITests
        .deletingLastPathComponent()  // …/Tests
        .deletingLastPathComponent()  // …/apps/game-ios
        .appendingPathComponent("Sources/ALUI/Screens/Dashboard.swift")

    private func code() throws -> String {
        try String(contentsOf: Self.source, encoding: .utf8)
            .split(separator: "\n")
            .filter { line in
                let t = line.trimmingCharacters(in: .whitespaces)
                return !t.hasPrefix("//") && !t.hasPrefix("/*") && !t.hasPrefix("*")
            }
            .joined(separator: "\n")
    }

    @Test("the scan can find the file it is meant to scan")
    func fileExists() {
        #expect(FileManager.default.fileExists(atPath: Self.source.path))
    }

    /// Invariant 9, and invariant 8's other half. The dashboard is the screen
    /// that SHOWS a total, so it is the one most likely to be "optimised" into
    /// keeping one.
    @Test("the dashboard stores no total and mints no point")
    func readsOnly() throws {
        let code = try code()
        for forbidden in [
            "award(", "spend(", ".buy(", "setConfig(", "saveRoster", "sessionReward",
            "balance +", "var balance:", "var balance =", "+= ",
        ] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "Dashboard.swift contains \(forbidden); the balance is a read"))
        }
        // …and it really does read the fold.
        #expect(code.contains("store.profile.balance"))
    }

    /// Invariant 2. The sweep is Core Animation on a layer, not a SwiftUI
    /// animation of a frame — which would re-evaluate the body every frame.
    @Test("the sweep never goes through the render path")
    func animationIsOffTheRenderPath() throws {
        let code = try code()
        #expect(!code.contains("withAnimation"))
        #expect(!code.contains(".animation("))
        #expect(code.contains("CABasicAnimation"))
    }

    /// Invariant 5. Nothing on this screen may be gated on progress.
    @Test("neither door is ever locked")
    func doorsAreAlwaysOpen() throws {
        let code = try code()
        for forbidden in ["locked", "disabled(", "isEnabled"] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "Dashboard.swift names \(forbidden); every door is always open"))
        }
    }
}
