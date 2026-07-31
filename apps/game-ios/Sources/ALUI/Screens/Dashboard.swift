import Foundation
import QuartzCore
import SwiftUI

import ALArt
import ALCore

#if canImport(UIKit)
import UIKit
#endif

/* -------------------------------------------------------------------------- */
/* `src/components/Dashboard.tsx` — « Mon copain », the child's home base.      */
/*                                                                             */
/* A live mascot, the big gold balance, a friendly growth meter, and the two    */
/* doors (Boutique / Changer de copain). Everything reads from `ProfileStore`;  */
/* no storage access, no arithmetic beyond a division for a width.              */
/*                                                                             */
/* ── INVARIANT 9 LIVES ON THIS SCREEN ──────────────────────────────────────── */
/* This is the screen that SHOWS a total, so it is the screen most likely to be */
/* "optimised" into keeping one. It must not.                                   */
/*                                                                             */
/*   • `balance` is `store.profile.balance`, i.e. `balanceOf(stars)` — the      */
/*     merge fold over per-device `earned`/`spent` counters, recomputed on      */
/*     every read. There is no stored property, no `@State`, no cache and no    */
/*     `+=` anywhere in this file.                                             */
/*   • `PersistedProfile` has no balance property to write even if one tried.    */
/*   • Nothing here calls a mutating `ProfileStore` method at all: the whole    */
/*     screen is a read plus three callbacks. It cannot mint a point            */
/*     (invariant 8's other half).                                             */
/*                                                                             */
/* ── The growth sweep, and why it is Core Animation ─────────────────────────── */
/* `Dashboard.tsx:49-62`:                                                       */
/*                                                                             */
/* ```ts                                                                        */
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
/* Two things the port must keep, in order:                                     */
/*                                                                             */
/*  1. **The bar LANDS at `pct` either way.** The width is written before the   */
/*     reduced-motion check, so a child with Reduce Motion on sees the correct  */
/*     bar, just not the trip (D29 — this is one of the few web animations that */
/*     IS gated, and it is gated here for that reason and no other).            */
/*  2. **The sweep is not a re-render.** Invariant 2. `Anim` deliberately       */
/*     carries no width sweep: every animation in it is transform-or-opacity    */
/*     only, which is what makes it layer work, and width is neither. So the    */
/*     fill is a `CAGradientLayer` of its own (`GrowthFillView`, below) and the */
/*     sweep is two `CABasicAnimation`s on that layer — `bounds.size.width`     */
/*     0 → W plus the matching `position.x` so the growth happens from the LEFT */
/*     edge. Nothing in SwiftUI observes it: no `@State` changes, no            */
/*     `withAnimation`, no per-frame body evaluation. The gradient layer keeps  */
/*     `cornerRadius = height / 2` throughout, so the leading cap stays round   */
/*     at every intermediate width exactly as `rounded-full` does on the web —  */
/*     which a `transform.scale.x` trick would have squashed into an ellipse.   */
/*                                                                             */
/* macOS (D1): `UIViewRepresentable` does not exist, so the fill renders as a   */
/* plain `Capsule` at the final width and the sweep is skipped. The animation   */
/* itself is asserted AS DATA in `DashboardTests`, which is how the rest of the */
/* port tests Core Animation on the host.                                       */
/* -------------------------------------------------------------------------- */

// MARK: - Authored metrics

public enum DashboardMetrics {

    /* -- the stage --------------------------------------------------------- */

    /// `min-h-[620px] w-full … rounded-3xl px-5 pb-10 pt-6`, `gap-5`.
    public static let stagePaddingX: CGFloat = 20
    public static let stagePaddingTop: CGFloat = 24
    public static let stagePaddingBottom: CGFloat = 40
    public static let stageGap: CGFloat = 20
    public static let cornerRadius: CGFloat = 24

    /* -- header ------------------------------------------------------------ */

    /// « ← Menu »: `rounded-full bg-white/80 px-4 py-2 text-lg font-bold`.
    public static let backPaddingX: CGFloat = 16
    public static let backPaddingY: CGFloat = 8
    /// `<div className="w-[84px]" aria-hidden />` — the right-hand ballast that
    /// keeps « Mon copain » optically centred.
    public static let headerSpacer: CGFloat = 84

    /* -- mascot ------------------------------------------------------------ */

    /// `mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)))`.
    public static let mascotMin: CGFloat = 140
    public static let mascotMax: CGFloat = 230
    public static let mascotRatio: CGFloat = 0.46

    /// The pedestal: `width: clamp(190px,62%,300px)`, `aspect-square`.
    public static let pedestalMin: CGFloat = 190
    public static let pedestalPercent: CGFloat = 62
    public static let pedestalMax: CGFloat = 300

    /* -- the balance pill --------------------------------------------------- */

    /// `padding: clamp(8px,2.6vw,15px) clamp(22px,6.5vw,36px)`.
    public static let balancePaddingY = FluidSpec(min: 8, vw: 2.6, max: 15)
    public static let balancePaddingX = FluidSpec(min: 22, vw: 6.5, max: 36)
    /// `fontSize: clamp(34px,11vw,62px)`, `gap-2`.
    public static let balanceFontSize = FluidSpec(min: 34, vw: 11, max: 62)
    public static let balanceGap: CGFloat = 8
    /// `boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"`.
    public static let balanceLipDrop: CGFloat = 8
    public static let balanceSoftShadow = CSSShadow(y: 16, blur: 26, opacity: 0.2)
    /// `-mt-3` on « étoiles à dépenser », pulling it back into the pill.
    public static let captionTopInset: CGFloat = -12

    /* -- the growth card ---------------------------------------------------- */

    /// `w-full max-w-[420px] rounded-3xl bg-white/70 p-4 shadow`.
    public static let cardMaxWidth: CGFloat = 420
    public static let cardPadding: CGFloat = 16
    /// `mb-2` under the card's header row.
    public static let cardHeaderSpacing: CGFloat = 8
    /// Tailwind `shadow` — `0 1px 3px rgb(0 0 0 / 0.1)`.
    public static let cardShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
    /// `h-5` — the bar, track and fill alike.
    public static let barHeight: CGFloat = 20

    /* -- the two doors ------------------------------------------------------ */

    /// `mt-1` above « Boutique 🛍️ »; both doors are `w-full max-w-[420px]`.
    public static let shopTopInset: CGFloat = 4
    /// `px-8` on both, `py-4` on Boutique and `py-3` on Changer de copain.
    public static let doorPaddingX: CGFloat = 32
    public static let shopPaddingY: CGFloat = 16
    public static let switchPaddingY: CGFloat = 12
    /// `boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"`.
    public static let shopLipDrop: CGFloat = 8
    public static let shopSoftShadow = CSSShadow(y: 14, blur: 24, opacity: 0.2)
    /// `boxShadow: "0 5px 0 rgba(0,0,0,0.08)"` — a lip and no blur at all.
    public static let switchLipDrop: CGFloat = 5
    public static let switchLipOpacity: Double = 0.08

    /* -- the sweep ---------------------------------------------------------- */

    /// `{ duration: 900, easing: "cubic-bezier(.2,.9,.3,1)" }`.
    public static let sweepDuration: Double = 0.9
    public static let sweepCurve: (Double, Double, Double, Double) = (0.2, 0.9, 0.3, 1)
}

// MARK: - The two derived numbers (pure, host-tested)

/// `mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)))`.
///
/// `box` is the ResizeObserver's `contentRect.width`, i.e. the stage width
/// **minus** its `px-5` padding — content-box is `ResizeObserver`'s default and
/// the observed element is the padded stage itself.
///
/// `Math.round` rounds halves toward +∞; the clamp floors the value at 140, so
/// only positives ever reach the rounding and `.toNearestOrAwayFromZero` agrees.
public func dashboardMascotSize(box: CGFloat) -> CGFloat {
    let clamped = Swift.min(
        DashboardMetrics.mascotMax,
        Swift.max(DashboardMetrics.mascotMin, box * DashboardMetrics.mascotRatio))
    return clamped.rounded(.toNearestOrAwayFromZero)
}

/// The growth meter, resolved. `pct = ((stage + 1) / GROWTH_STAGES) * 100`.
///
/// Every figure a child or a screen reader gets comes from `stage` alone — no
/// running total is stored, summed or persisted anywhere (invariant 9).
public struct GrowthMeter: Equatable, Sendable {
    /// `config.stage`, 0-based.
    public let stage: Int
    /// `GROWTH_STAGES`.
    public let stages: Int

    public init(stage: Int, stages: Int = growthStages) {
        self.stage = stage
        self.stages = stages
    }

    /// `aria-valuenow={stage + 1}` — and the numerator of the `n/N` caption.
    public var value: Int { stage + 1 }

    /// `aria-valuemin={1}` / `aria-valuemax={GROWTH_STAGES}`.
    public var minimum: Int { 1 }
    public var maximum: Int { stages }

    /// `{stage + 1}/{GROWTH_STAGES}` — the `aria-hidden` counter beside 🌱.
    public var counterText: String { "\(value)/\(stages)" }

    /// `pct`, 0…100.
    public var percent: Double { Double(value) / Double(stages) * 100 }

    /// `width: ${pct}%` resolved against a track of `trackWidth` points.
    public func fillWidth(track: CGFloat) -> CGFloat {
        Swift.max(0, track * CGFloat(percent) / 100)
    }
}

/// What the mount effect does, as data.
///
/// The order in the TSX is the behaviour: the final width is written FIRST and
/// unconditionally, then the sweep is skipped under reduced motion. A port that
/// gated the whole effect would leave the bar empty for a child with Reduce
/// Motion on.
public struct GrowthSweep: Equatable, Sendable {
    /// Where the bar ends up. Always `pct`, reduced motion or not.
    public let settled: Double
    /// `[{ width: "0%" }, { width: `${pct}%` }]` — the replayed start.
    public let from: Double
    /// Whether `el.animate(...)` is reached at all.
    public let animates: Bool

    public init(percent: Double, reduceMotion: Bool) {
        self.settled = percent
        self.from = 0
        self.animates = !reduceMotion
    }
}

// MARK: - The sweep, as Core Animation

/// `el.animate([{width: "0%"}, {width: "pct%"}], {duration: 900, easing: …})`.
///
/// Two animations rather than one because a CALayer's width lives in
/// `bounds.size` and its anchor is centred: growing the bounds alone would open
/// the bar out from the middle. Animating `position.x` in step pins the LEFT
/// edge, which is where a `width` transition grows from in CSS.
///
/// Deliberately NOT added to `Anim`: `Anim` is the shared interaction vocabulary
/// (press / shake / pop / pulse) and every entry in it is transform-or-opacity
/// only. A geometry animation belongs to the one screen that has one.
enum GrowthAnim {
    static let widthKey = "ALGrowthSweepWidth"
    static let positionKey = "ALGrowthSweepPosition"

    static func timing() -> CAMediaTimingFunction {
        let c = DashboardMetrics.sweepCurve
        return CAMediaTimingFunction(
            controlPoints: Float(c.0), Float(c.1), Float(c.2), Float(c.3))
    }

    /// `bounds.size.width`: 0 → the resolved `pct%` width.
    static func widthAnimation(to width: CGFloat) -> CABasicAnimation {
        let a = CABasicAnimation(keyPath: "bounds.size.width")
        a.fromValue = 0
        a.toValue = width
        a.duration = DashboardMetrics.sweepDuration
        a.timingFunction = timing()
        return a
    }

    /// `position.x`: the centre a zero-width bar would have, → the settled one.
    /// `centerX` is the layer's model position, i.e. where it rests.
    static func positionAnimation(centerX: CGFloat, width: CGFloat) -> CABasicAnimation {
        let a = CABasicAnimation(keyPath: "position.x")
        a.fromValue = centerX - width / 2
        a.toValue = centerX
        a.duration = DashboardMetrics.sweepDuration
        a.timingFunction = timing()
        return a
    }

    /// Fire the sweep. Reduced motion returns having done nothing — the layer is
    /// already AT its settled width, because SwiftUI laid it out there.
    @MainActor
    static func widthSweep(_ layer: CALayer?, reduceMotion: Bool) {
        guard let layer, !reduceMotion else { return }
        let width = layer.bounds.width
        guard width > 0 else { return }
        layer.add(widthAnimation(to: width), forKey: widthKey)
        layer.add(
            positionAnimation(centerX: layer.position.x, width: width), forKey: positionKey)
    }
}

// MARK: - The fill

/// The growth bar's gradient fill: a `CAGradientLayer` SwiftUI sizes and Core
/// Animation sweeps.
struct GrowthBarFill: View {
    let width: CGFloat
    let height: CGFloat
    /// The sweep re-runs when THIS changes, not when `width` does — the TSX's
    /// effect depends on `[pct]`, so a container resize repaints without
    /// replaying the animation.
    let percent: Double
    let reduceMotion: ReduceMotionSource

    var body: some View {
        #if canImport(UIKit)
        GrowthFillRepresentable(
            height: height, percent: percent, reduceMotion: reduceMotion.isReduced
        )
        .frame(width: width, height: height)
        #else
        Capsule()
            .fill(Palette.growthFill.gradient)
            .frame(width: width, height: height)
        #endif
    }
}

#if canImport(UIKit)

private struct GrowthFillRepresentable: UIViewRepresentable {
    let height: CGFloat
    let percent: Double
    let reduceMotion: Bool

    func makeUIView(context: Context) -> GrowthFillView { GrowthFillView() }

    func updateUIView(_ view: GrowthFillView, context: Context) {
        view.apply(height: height, percent: percent, reduceMotion: reduceMotion)
    }
}

/// `layerClass` rather than a sublayer: the view's own backing layer IS the
/// gradient, so SwiftUI's frame is the animated geometry and there is no second
/// layer to keep in sync.
final class GrowthFillView: UIView {
    override class var layerClass: AnyClass { CAGradientLayer.self }

    private var lastPercent: Double?
    private var pendingSweep = false
    private var reduceMotion = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        guard let gradient = layer as? CAGradientLayer else { return }
        // `linear-gradient(90deg,#AED581,#66BB6A)` — 90° is left → right.
        gradient.colors = Palette.growthFill.stops.map {
            UIColor(Color(svgHex: $0.hex)).cgColor
        }
        gradient.locations = Palette.growthFill.stops.map { NSNumber(value: $0.location) }
        gradient.startPoint = CGPoint(x: 0, y: 0.5)
        gradient.endPoint = CGPoint(x: 1, y: 0.5)
        // `rounded-full` on the fill itself, so the leading cap stays round at
        // every width the sweep passes through.
        gradient.masksToBounds = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("GrowthFillView is code-only")
    }

    func apply(height: CGFloat, percent: Double, reduceMotion: Bool) {
        layer.cornerRadius = height / 2
        self.reduceMotion = reduceMotion
        guard lastPercent != percent else { return }
        lastPercent = percent
        pendingSweep = true
        setNeedsLayout()
    }

    /// The sweep needs the settled geometry, which only exists after layout —
    /// the same beat as the web's effect, which runs after the browser has
    /// resolved `width: pct%`.
    override func layoutSubviews() {
        super.layoutSubviews()
        guard pendingSweep, bounds.width > 0 else { return }
        pendingSweep = false
        GrowthAnim.widthSweep(layer, reduceMotion: reduceMotion)
    }
}

#endif

// MARK: - The bar

/// The `role="progressbar"` row: an `overflow-hidden rounded-full` track in
/// `#E9DCC7` with the gradient fill laid over it, leading-aligned.
///
/// Its own view rather than a method on the screen so the host suite can
/// rasterise it — "what covers what" is invisible to every assertion that does
/// not actually draw (the lesson of `GameFrameZOrderTests`).
struct GrowthBar: View {
    let meter: GrowthMeter
    let reduceMotion: ReduceMotionSource

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.growthTrack.color)
                GrowthBarFill(
                    width: meter.fillWidth(track: proxy.size.width),
                    height: DashboardMetrics.barHeight,
                    percent: meter.percent,
                    reduceMotion: reduceMotion
                )
            }
            .frame(height: DashboardMetrics.barHeight)
            // `overflow-hidden` on the track.
            .clipShape(Capsule())
        }
        .frame(height: DashboardMetrics.barHeight)
        // `role="progressbar" aria-valuemin={1} aria-valuemax={GROWTH_STAGES}
        //  aria-valuenow={stage + 1} aria-label="Croissance de ton copain"`.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: Copy.Dashboard.growthBar))
        .accessibilityValue(Text(verbatim: "\(meter.value)"))
    }
}

// MARK: - The screen

@MainActor
public struct DashboardView: View {

    @Environment(ProfileStore.self) private var store
    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    /// `← Menu`.
    public let onBack: () -> Void
    /// `Boutique 🛍️`.
    public let onShop: () -> Void
    /// `Changer de copain 🔄` — non-destructive; each friend keeps its progress.
    public let onSwitch: () -> Void

    /// `ResizeObserver` → `contentRect.width`. Pure layout, never on an
    /// animation path (the mascot sits inside a percentage-clamped pedestal, so
    /// it cannot feed its own measurement back).
    @State private var box: CGFloat = 0

    public init(
        onBack: @escaping () -> Void,
        onShop: @escaping () -> Void,
        onSwitch: @escaping () -> Void
    ) {
        self.onBack = onBack
        self.onShop = onShop
        self.onSwitch = onSwitch
    }

    /// `const { config, balance } = profile` — a READ of the folded counters,
    /// recomputed every time. Nothing is cached and nothing is written
    /// (invariant 9).
    private var meter: GrowthMeter { GrowthMeter(stage: store.profile.config.stage) }

    public var body: some View {
        VStack(spacing: DashboardMetrics.stageGap) {
            header
            pedestal
            balancePill
            caption
            growthCard
            shopDoor
            switchDoor
        }
        .background(
            GeometryReader { proxy in
                Color.clear.preference(
                    key: DashboardBoxWidth.self, value: proxy.size.width)
            }
        )
        .onPreferenceChange(DashboardBoxWidth.self) { width in
            if width > 0 { box = width }
        }
        .frame(maxWidth: .infinity, minHeight: Shell.minimumScreenHeight, alignment: .top)
        .padding(.horizontal, DashboardMetrics.stagePaddingX)
        .padding(.top, DashboardMetrics.stagePaddingTop)
        .padding(.bottom, DashboardMetrics.stagePaddingBottom)
        .clipShape(RoundedRectangle(cornerRadius: DashboardMetrics.cornerRadius))
        .stageWash(Palette.stage)
        .fontDesign(.rounded)  // fontFamily: ui-rounded,'SF Pro Rounded',…
    }

    // MARK: header

    private var header: some View {
        HStack {
            Button(action: onBack) {
                Text(verbatim: Copy.Dashboard.backToMenuLabel)
                    .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
                    .foregroundStyle(Palette.ink.color)
                    .padding(.horizontal, DashboardMetrics.backPaddingX)
                    .padding(.vertical, DashboardMetrics.backPaddingY)
                    .background(Color.white.opacity(Palette.White.o80), in: Capsule())
                    .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                    .shadow(
                        color: .black.opacity(DashboardMetrics.cardShadow.opacity),
                        radius: DashboardMetrics.cardShadow.swiftUIRadius,
                        y: DashboardMetrics.cardShadow.y
                    )
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: Copy.Dashboard.backToMenu))

            Spacer(minLength: 0)

            Text(verbatim: Copy.Dashboard.heading)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(Palette.inkSoft.color)

            Spacer(minLength: 0)

            // `<div className="w-[84px]" aria-hidden />`.
            Color.clear
                .frame(width: DashboardMetrics.headerSpacer, height: 0)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: mascot

    private var pedestal: some View {
        let side = fluidPercent(
            min: DashboardMetrics.pedestalMin,
            percent: DashboardMetrics.pedestalPercent,
            max: DashboardMetrics.pedestalMax,
            of: box)
        return MascotRigView(
            config: store.profile.config,
            mood: .idle,
            size: dashboardMascotSize(box: box)
        )
        .frame(width: side, height: side)
        .background {
            // `radial-gradient(circle at 50% 42%, #FFFFFF 0%,
            //  rgba(255,255,255,0.4) 55%, rgba(255,255,255,0) 72%)`.
            RadialGradient(
                stops: [
                    .init(color: .white, location: 0),
                    .init(color: .white.opacity(0.4), location: 0.55),
                    .init(color: .white.opacity(0), location: 0.72),
                ],
                center: UnitPoint(x: 0.5, y: 0.42),
                startRadius: 0,
                endRadius: side / 2
            )
            .clipShape(Circle())
        }
    }

    // MARK: balance

    /// The big gold pill. `usePopFlourish` fires on every dashboard open,
    /// because the view is created afresh each time the route is taken.
    private var balancePill: some View {
        let balance = store.profile.balance
        return HStack(spacing: DashboardMetrics.balanceGap) {
            Text(verbatim: Copy.Dashboard.balanceGlyph)
                .accessibilityHidden(true)
            Text(verbatim: "\(balance)")
        }
        .font(
            Typography.rounded(
                DashboardMetrics.balanceFontSize.resolve(viewport: viewport),
                Typography.Weight.black)
        )
        .foregroundStyle(Palette.goldInk.color)
        .padding(.horizontal, DashboardMetrics.balancePaddingX.resolve(viewport: viewport))
        .padding(.vertical, DashboardMetrics.balancePaddingY.resolve(viewport: viewport))
        .background {
            liftedCapsule(
                fill: AnyShapeStyle(Palette.goldPill.gradient),
                lip: Palette.goldLip.color,
                drop: DashboardMetrics.balanceLipDrop,
                soft: DashboardMetrics.balanceSoftShadow
            )
        }
        .popFlourish(reduceMotion: reduceMotion)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: Copy.Dashboard.balance(balance)))
    }

    private var caption: some View {
        Text(verbatim: Copy.Dashboard.balanceCaption)
            .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
            .foregroundStyle(Palette.inkSoft.color)
            .padding(.top, DashboardMetrics.captionTopInset)
    }

    // MARK: growth

    private var growthCard: some View {
        let meter = self.meter
        return VStack(spacing: DashboardMetrics.cardHeaderSpacing) {
            HStack {
                Text(verbatim: Copy.Dashboard.growth)
                Spacer(minLength: 0)
                Text(verbatim: meter.counterText)
                    .accessibilityHidden(true)
            }
            .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
            .foregroundStyle(Palette.ink.color)

            GrowthBar(meter: meter, reduceMotion: reduceMotion)
        }
        .padding(DashboardMetrics.cardPadding)
        .frame(maxWidth: DashboardMetrics.cardMaxWidth)
        .background {
            RoundedRectangle(cornerRadius: DashboardMetrics.cornerRadius)
                .fill(Color.white.opacity(Palette.White.o70))
                .shadow(
                    color: .black.opacity(DashboardMetrics.cardShadow.opacity),
                    radius: DashboardMetrics.cardShadow.swiftUIRadius,
                    y: DashboardMetrics.cardShadow.y
                )
        }
    }

    // MARK: doors

    private var shopDoor: some View {
        Button(action: onShop) {
            Text(verbatim: Copy.Dashboard.shopDoor)
                .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.extrabold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, DashboardMetrics.doorPaddingX)
                .padding(.vertical, DashboardMetrics.shopPaddingY)
                .background {
                    liftedCapsule(
                        fill: AnyShapeStyle(Palette.green.color),
                        lip: Palette.greenLip.color,
                        drop: DashboardMetrics.shopLipDrop,
                        soft: DashboardMetrics.shopSoftShadow
                    )
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: DashboardMetrics.cardMaxWidth)
        .padding(.top, DashboardMetrics.shopTopInset)
    }

    private var switchDoor: some View {
        Button(action: onSwitch) {
            Text(verbatim: Copy.Dashboard.switchCompanion)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, DashboardMetrics.doorPaddingX)
                .padding(.vertical, DashboardMetrics.switchPaddingY)
                .background {
                    // `0 5px 0 rgba(0,0,0,0.08)` — a flat lip, no blur.
                    ZStack {
                        Capsule()
                            .fill(Color.black.opacity(DashboardMetrics.switchLipOpacity))
                            .offset(y: DashboardMetrics.switchLipDrop)
                        Capsule()
                            .fill(Color.white.opacity(Palette.White.o80))
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: DashboardMetrics.cardMaxWidth)
    }
}

/// The stage's content width, measured for `mascotSize` and the pedestal clamp.
struct DashboardBoxWidth: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 { value = next }
    }
}
