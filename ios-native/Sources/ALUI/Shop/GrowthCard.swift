import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/GrowthCard.tsx` — growth upgrade, the headline spend.             */
/*                                                                             */
/* Grows the mascot one stage at a time. Price rises with maturity so growing   */
/* stays a meaningful goal. NO FAIL STATE (invariant 3): too poor = disabled +  */
/* « pas encore »; at the top = « Niveau max ✨ ». Nothing here mints a point   */
/* (invariant 8): the spend goes through `ProfileStore.spend`, which refuses    */
/* an unaffordable price, and the stage bump goes through `setConfig` — the     */
/* same choke points as everything else, so it merges like everything else      */
/* (invariant 9: counters, never a total).                                     */
/* -------------------------------------------------------------------------- */

/// `growthPrice(stage)` — the local price curve, rising with the current stage.
public func growthPrice(stage: Int) -> Int { 30 * (stage + 1) }

// MARK: - The card's state (pure, host-tested)

/// Everything the card shows for a (stage, balance) pair.
public struct GrowthCardSurface: Equatable, Sendable {
    public let stage: Int
    public let price: Int
    /// `config.stage >= GROWTH_STAGES - 1`.
    public let atMax: Bool
    public let affordable: Bool
    /// `atMax || !affordable` — the button's disabled flag.
    public let disabled: Bool
    /// `Math.min(config.stage + 1, GROWTH_STAGES - 1)` — the peek preview.
    public let nextStage: Int
    /// « 3/10 » beside the title.
    public let counter: String
    /// `aria-label` on the pip row.
    public let meterLabel: String
    /// Pips `i <= stage` fill green.
    public let filledPips: Int
    /// The button's `aria-label`.
    public let accessibilityLabel: String
    /// The button's text.
    public let buttonLabel: String
    /// `!atMax && !affordable` — the fattest savings meter of all.
    public let showsMeter: Bool

    public init(stage: Int, balance: Int) {
        let atMax = stage >= growthStages - 1
        let price = growthPrice(stage: stage)
        let affordable = balance >= price

        self.stage = stage
        self.price = price
        self.atMax = atMax
        self.affordable = affordable
        self.disabled = atMax || !affordable
        self.nextStage = Swift.min(stage + 1, growthStages - 1)
        self.counter = "\(stage + 1)/\(growthStages)"
        self.meterLabel = Copy.Shop.Growth.meter(stage + 1, of: growthStages)
        self.filledPips = stage + 1
        self.accessibilityLabel = atMax
            ? Copy.Shop.Growth.atMax
            : affordable ? Copy.Shop.Growth.grow(price) : Copy.Shop.Growth.cannotAfford(price)
        self.buttonLabel = atMax
            ? Copy.Shop.Growth.atMaxLabel
            : affordable ? Copy.Shop.Growth.growLabel(price) : Copy.Shop.Growth.notYetLabel(price)
        self.showsMeter = !atMax && !affordable
    }
}

// MARK: - Metrics (the Tailwind classes, verbatim)

enum GrowthCardMetrics {
    /// `gap-4 rounded-3xl p-4`.
    static let spacing: CGFloat = 16
    static let cornerRadius: CGFloat = 24
    static let padding: CGFloat = 16
    /// `0 8px 18px rgba(0,0,0,0.08)`.
    static let shadow = CSSShadow(y: 8, blur: 18, opacity: 0.08)

    /// The peek: `rounded-2xl bg-white/70 p-2`, `<Mascot size={64} />`.
    static let peekRadius: CGFloat = 16
    static let peekPadding: CGFloat = 8
    static let peekMascotSize: CGFloat = 64

    /// The column: `flex-1 flex-col gap-2`.
    static let columnSpacing: CGFloat = 8

    /// Pips: `flex gap-1`, each `h-2 flex-1 rounded-full`; empty at ink 15 %.
    static let pipSpacing: CGFloat = 4
    static let pipHeight: CGFloat = 8
    static let pipEmptyOpacity: Double = 0.15

    /// The button: `rounded-full px-5 py-3 text-base font-extrabold`.
    static let buttonPaddingX: CGFloat = 20
    static let buttonPaddingY: CGFloat = 12
    /// `opacity: disabled && !atMax ? 0.6 : 1`.
    static let unaffordableOpacity: Double = 0.6
    /// `0 6px 0 rgba(0,0,0,0.12)` — the hard lip when enabled.
    static let buttonLipDrop: CGFloat = 6
    static let buttonLipOpacity: Double = 0.12

    /// The savings meter under it: `height={12}`.
    static let meterHeight: CGFloat = 12
}

// MARK: - The grow action

/// `grow()` — the ONLY way a stage is bought, ported line for line:
/// `if (atMax) return; if (spend(price)) { setConfig(stage + 1); onGrew(price) }`.
/// Returns the price actually spent, or nil when nothing happened (at max, or
/// the wallet is short — `spend` refuses, and refusal is a quiet no-op, never
/// an error).
@MainActor
@discardableResult
public func performGrow(on store: ProfileStore) -> Int? {
    let profile = store.profile
    let surface = GrowthCardSurface(stage: profile.config.stage, balance: profile.balance)
    guard !surface.atMax else { return nil }
    guard store.spend(cost: surface.price) else { return nil }
    store.setConfig { c in
        var c = c
        c.stage = Swift.min(c.stage + 1, growthStages - 1)
        return c
    }
    return surface.price
}

// MARK: - The view

/// `<GrowthCard sinceBalance onGrew />`. The parent (ShopView) supplies the
/// profile it already reads and receives `onGrew(price)` to run the star
/// flight + burst; the spend itself goes through `performGrow`.
public struct GrowthCardView: View {
    public let profile: ProfileView
    /// Wallet at the previous shop visit — the savings meter animates from it.
    public let sinceBalance: Int
    public let onGrow: () -> Void

    @Environment(\.alReduceMotion) private var reduceMotion
    @State private var buttonHandle = LayerHandle()

    public init(profile: ProfileView, sinceBalance: Int, onGrow: @escaping () -> Void) {
        self.profile = profile
        self.sinceBalance = sinceBalance
        self.onGrow = onGrow
    }

    public var surface: GrowthCardSurface {
        GrowthCardSurface(stage: profile.config.stage, balance: profile.balance)
    }

    public var body: some View {
        let surface = self.surface
        return HStack(alignment: .center, spacing: GrowthCardMetrics.spacing) {
            // A peek at what the next stage looks like.
            peek(surface)

            VStack(alignment: .leading, spacing: GrowthCardMetrics.columnSpacing) {
                HStack(alignment: .firstTextBaseline, spacing: GrowthCardMetrics.columnSpacing) {
                    Text(verbatim: Copy.Shop.Growth.title)
                        .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                        .foregroundStyle(Palette.ink.color)
                    Spacer(minLength: 0)
                    Text(verbatim: surface.counter)
                        .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                        .foregroundStyle(Palette.inkSoft.color)
                }

                pips(surface)

                growButton(surface)

                // Growth is the longest save, so it gets the fattest meter.
                if surface.showsMeter {
                    SavingsMeter(
                        cost: surface.price,
                        balance: profile.balance,
                        since: sinceBalance,
                        height: GrowthCardMetrics.meterHeight)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(GrowthCardMetrics.padding)
        .frame(maxWidth: .infinity)
        .background(
            Palette.growthCard.gradient,
            in: RoundedRectangle(cornerRadius: GrowthCardMetrics.cornerRadius))
        .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
        .shadow(
            color: .black.opacity(GrowthCardMetrics.shadow.opacity),
            radius: GrowthCardMetrics.shadow.swiftUIRadius,
            x: 0,
            y: GrowthCardMetrics.shadow.y)
    }

    private func peek(_ surface: GrowthCardSurface) -> some View {
        Ollie(
            config: peekConfig(surface),
            mood: .idle,
            size: GrowthCardMetrics.peekMascotSize,
            reduceMotion: reduceMotion
        )
        .padding(GrowthCardMetrics.peekPadding)
        .background(
            .white.opacity(Palette.White.o70),
            in: RoundedRectangle(cornerRadius: GrowthCardMetrics.peekRadius))
        .accessibilityHidden(true)  // aria-hidden
    }

    /// `{ ...config, stage: nextStage }`.
    private func peekConfig(_ surface: GrowthCardSurface) -> MascotConfig {
        var config = profile.config
        config.stage = surface.nextStage
        return config
    }

    /// The growth meter — `role="img"` + its French label.
    private func pips(_ surface: GrowthCardSurface) -> some View {
        HStack(spacing: GrowthCardMetrics.pipSpacing) {
            ForEach(0..<growthStages, id: \.self) { i in
                Capsule()
                    .fill(
                        i < surface.filledPips
                            ? Palette.green.color
                            : Palette.ink.color.opacity(GrowthCardMetrics.pipEmptyOpacity))
                    .frame(height: GrowthCardMetrics.pipHeight)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(Text(verbatim: surface.meterLabel))
    }

    private func growButton(_ surface: GrowthCardSurface) -> some View {
        LayerHost(handle: buttonHandle) {
            Text(verbatim: surface.buttonLabel)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.extrabold))
                .foregroundStyle(.white)
                .padding(.horizontal, GrowthCardMetrics.buttonPaddingX)
                .padding(.vertical, GrowthCardMetrics.buttonPaddingY)
                .background(
                    surface.atMax ? Palette.disabled.color : Palette.green.color,
                    in: Capsule())
                .compositingGroup()
                .shadow(
                    color: .black.opacity(surface.disabled ? 0 : GrowthCardMetrics.buttonLipOpacity),
                    radius: 0,
                    x: 0,
                    y: surface.disabled ? 0 : GrowthCardMetrics.buttonLipDrop)
        }
        .opacity(surface.disabled && !surface.atMax ? GrowthCardMetrics.unaffordableOpacity : 1)
        .contentShape(Capsule())
        .shopTilePress(
            enabled: !surface.disabled,
            handle: buttonHandle,
            reduceMotion: reduceMotion,
            onTap: onGrow)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: surface.accessibilityLabel))
        .accessibilityAddTraits(.isButton)
    }
}
