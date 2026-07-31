import Observation
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/Shop.tsx` — the shop / dressing-room, the spending area.          */
/*                                                                             */
/* Buying is a two-step ceremony a 6yo can follow: tapping an unowned tile      */
/* opens a TRY-ON DIALOG (the mascot wears it in the card, nothing is spent,    */
/* VO says the price), and only its big « Acheter · ⭐ N » button spends. Owned */
/* items equip on tap, no dialog.                                              */
/*                                                                             */
/* INVARIANT 8 (the other side) — the shop SPENDS points and never mints them:  */
/* every wallet mutation below is `ProfileStore.buy` / `ProfileStore.spend`,    */
/* both of which only ever bump the `spent` counter. No arithmetic in this      */
/* file increases a balance.                                                   */
/* INVARIANT 9 — no bare total is ever written: the one thing the shop persists */
/* itself (`shop-seen`, the meters' animate-from point) stores what the child   */
/* SAW, per child id, and is never merged, summed or read back as money.        */
/* A child can never wedge here: a raced-empty wallet makes `buy` a quiet       */
/* no-op with the dialog still open (invariant 3 — nothing locks, nothing is    */
/* lost), and the ✕ / backdrop always closes it.                               */
/* -------------------------------------------------------------------------- */

// MARK: - Star-flight sizing

/// `flightSize(cost)` — how many stars fly wallet→mascot on a purchase:
/// pricier = more of a shower. `Math.min(8, Math.max(3, Math.round(cost/10)))`,
/// with JS `Math.round` (half toward +∞).
public func shopFlightSize(cost: Int) -> Int {
    let rounded = Int((Double(cost) / 10 + 0.5).rounded(.down))
    return Swift.min(8, Swift.max(3, rounded))
}

// MARK: - The wallet count (pure, host-tested)

/// `AnimatedNumber` — the wallet figure counts down (or up) instead of jumping,
/// so a child watches the price actually LEAVE the purse. This is the math of
/// one count: `dur = Math.min(900, 350 + |Δ| * 12)` ms, eased `1 - (1-k)²`.
public struct WalletCount: Equatable, Sendable {
    public let from: Int
    public let to: Int

    public init(from: Int, to: Int) {
        self.from = from
        self.to = to
    }

    /// `if (from === value) return` — nothing to animate.
    public var animates: Bool { from != to }

    /// Seconds.
    public var duration: Double {
        Swift.min(0.9, 0.35 + Double(abs(to - from)) * 0.012)
    }

    /// The figure shown `elapsed` seconds into the count (clamped both ends).
    public func value(at elapsed: Double) -> Int {
        let k = Swift.max(0, Swift.min(1, elapsed / duration))
        let eased = 1 - (1 - k) * (1 - k)
        // JS Math.round — half toward +∞.
        return Int((Double(from) + Double(to - from) * eased + 0.5).rounded(.down))
    }
}

// MARK: - Zone grouping (pure, host-tested)

/// One tile — a factory look or a catalog item.
public enum ShopTile: Equatable, Sendable {
    case factory(DefaultLook)
    case item(CustomizationOption)

    /// The web's React key: `default.<category>.<slot>` / the option id.
    public var id: String {
        switch self {
        case .factory(let look): return "default.\(look.category.rawValue).\(look.slot)"
        case .item(let option): return option.id
        }
    }
}

/// Tiles bucketed under one body-part label (« Queue », « Corps », …) — the way
/// a child thinks about dressing — with accessories pooled under their own
/// label.
public struct ShopTileGroup: Equatable, Sendable {
    public let label: String
    public var tiles: [ShopTile]
}

/// `labelOf(slot, category)`.
public func shopSlotLabel(slot: String, category: CustomizationCategory) -> String {
    category == .accessory ? Copy.Shop.accessoryLabel : (Copy.Shop.slotLabel[slot] ?? slot)
}

/// `grouped(entries)` — first-seen label order, exactly the TSX fold.
func shopGrouped(_ entries: [(label: String, tile: ShopTile)]) -> [ShopTileGroup] {
    var out: [ShopTileGroup] = []
    for entry in entries {
        if let i = out.firstIndex(where: { $0.label == entry.label }) {
            out[i].tiles.append(entry.tile)
        } else {
            out.append(ShopTileGroup(label: entry.label, tiles: [entry.tile]))
        }
    }
    return out
}

/// The wardrobe: every factory look + everything bought. No prices in here.
public func shopArmoireGroups(for profile: ProfileView) -> [ShopTileGroup] {
    let species = profile.config.species
    let defaults = MascotCatalog.defaultLooks[species] ?? []
    let ownedItems = MascotCatalog.catalog.filter {
        $0.species == species && profile.owned.contains($0.id)
    }
    return shopGrouped(
        defaults.map { (shopSlotLabel(slot: $0.slot, category: $0.category), ShopTile.factory($0)) }
            + ownedItems.map { (shopSlotLabel(slot: $0.slot, category: $0.category), ShopTile.item($0)) }
    )
}

/// The store: only what is NOT yet owned — a bought item moves to the wardrobe.
public func shopStoreGroups(for profile: ProfileView) -> [ShopTileGroup] {
    let species = profile.config.species
    let forSale = MascotCatalog.catalog.filter {
        $0.species == species && !profile.owned.contains($0.id)
    }
    return shopGrouped(
        forSale.map { (shopSlotLabel(slot: $0.slot, category: $0.category), ShopTile.item($0)) }
    )
}

// MARK: - The try-on dialog's surface (pure, host-tested)

/// What the dialog shows for (option, balance) — the one place a purchase is
/// decided.
public struct TryOnSurface: Equatable, Sendable {
    /// `aria-label={`Essayer ${option.name}`}` on the dialog.
    public let title: String
    public let name: String
    public let affordable: Bool
    /// « Acheter · ⭐ N » / « ⭐ N · pas encore ».
    public let buyLabel: String
    /// The buy button's `aria-label`.
    public let buyAccessibilityLabel: String
    /// `!affordable` — the gap meter under the buttons.
    public let showsMeter: Bool

    public init(option: CustomizationOption, balance: Int) {
        let affordable = balance >= option.cost
        self.title = Copy.Shop.TryOn.title(option.name)
        self.name = option.name
        self.affordable = affordable
        self.buyLabel = affordable
            ? Copy.Shop.TryOn.buyLabel(option.cost)
            : Copy.Shop.TryOn.notYetLabel(option.cost)
        self.buyAccessibilityLabel = affordable
            ? Copy.Shop.TryOn.buy(option.name, cost: option.cost)
            : Copy.Shop.TryOn.cannotAfford(option.name)
        self.showsMeter = !affordable
    }
}

// MARK: - The model

/// The `Shop` component's logic, extracted so the host tests drive the whole
/// buy/equip/grow surface without a renderer.
///
/// The celebration hooks are installed by the view (they need layer geometry);
/// each fires synchronously at the exact point its `anim.ts` call sits in the
/// TSX. A nil hook is simply no confetti — never a stall.
@MainActor
@Observable
public final class ShopModel {
    public let store: ProfileStore
    let audio: any AudioEngine
    let kv: any KVStore

    /// The try-on "cart": at most ONE unowned option, shown worn in the dialog
    /// but NOT bought. Any real config change clears it.
    public private(set) var cart: CustomizationOption?

    /// Balance this child last SAW here — the savings meters animate from it.
    /// Captured once per mount, exactly like `useState(() => loadShopSeen()…)`.
    public let sinceBalance: Int

    /// Purchase ceremony: star flight buy-button → mascot + preview pop.
    @ObservationIgnored public var onPurchaseCelebrate: ((CustomizationOption) -> Void)?
    /// The preview `pop()` on an equip / toggle / factory-look tap.
    @ObservationIgnored public var onEquipPop: (() -> Void)?
    /// Growth: star flight wallet → mascot (fires before the success SFX).
    @ObservationIgnored public var onGrewFlight: ((Int) -> Void)?
    /// Growth: preview pop + grow burst (fires after the spoken line starts).
    @ObservationIgnored public var onGrewCelebrate: (() -> Void)?

    public init(store: ProfileStore, audio: any AudioEngine, kv: any KVStore) {
        self.store = store
        self.audio = audio
        self.kv = kv
        self.sinceBalance = ProfileStorage.loadShopSeen(kv)[store.activeId ?? ""] ?? 0
    }

    /// `cartAffordable` — the buy button's enabled flag, read live.
    public var cartAffordable: Bool {
        guard let cart else { return false }
        return store.profile.balance >= cart.cost
    }

    // MARK: Actions — each is one TSX handler, in its statement order

    /// A catalogue tile's tap: owned = equip/toggle right away (free);
    /// unowned = try-on, never a spend.
    public func tapItem(_ option: CustomizationOption) {
        if store.profile.owned.contains(option.id) {
            audio.unlock()
            audio.pop()
            cart = nil
            if option.category == .accessory {
                toggleAccessory(option)
            } else {
                applyVariant(option)
            }
        } else {
            tryOn(option)
        }
    }

    /// Free try-on: open the dialog, say the price. Nothing is spent here.
    public func tryOn(_ option: CustomizationOption) {
        audio.unlock()
        audio.pop()
        cart = option
        let line = store.profile.balance >= option.cost
            ? VO.shopCostLine(option.cost)
            : "\(VO.shopCostLine(option.cost)) \(VO.shopNeedMore)"
        say(line)
    }

    /// The ONLY place shop items are paid for: the dialog's buy button.
    public func confirmBuy() {
        guard let cart else { return }
        audio.unlock()
        // Wallet raced empty — soft no-op, never an error, dialog stays open.
        guard store.buy(cart) else { return }
        onPurchaseCelebrate?(cart)
        audio.success()
        say(VO.shopBought)
        self.cart = nil
    }

    /// Backdrop tap / ✕ — « non merci ».
    public func cancelTryOn() {
        audio.unlock()
        audio.pop()
        cart = nil
    }

    /// Colours & style variants just apply (free re-apply once owned).
    private func applyVariant(_ option: CustomizationOption) {
        store.buy(option)
        onEquipPop?()
    }

    /// Accessories toggle: tapping the equipped one takes it off; else equip.
    private func toggleAccessory(_ option: CustomizationOption) {
        if store.profile.config.accessories.contains(option.id) {
            store.setConfig { c in
                var c = c
                c.accessories.removeAll { $0 == option.id }
                return c
            }
        } else {
            store.buy(option)
        }
        onEquipPop?()
    }

    /// Return one colour/style slot to its factory look (clearing it → rig
    /// default).
    public func clearSlot(_ look: DefaultLook) {
        audio.unlock()
        audio.pop()
        cart = nil
        store.setConfig { c in
            var c = c
            if look.category == .color {
                c.colors[look.slot] = nil
            } else {
                c.styles[look.slot] = nil
            }
            return c
        }
        onEquipPop?()
    }

    /// The growth spend — `GrowthCard.grow()` + the shop's `onGrew`, in the
    /// web's exact order: flight, success SFX, spoken line, pop + burst.
    public func grow() {
        guard let price = performGrow(on: store) else { return }
        onGrewFlight?(price)
        audio.success()
        say(VO.shopGrew)
        onGrewCelebrate?()
    }

    /// The `useEffect([activeId, balance])` half of shop-seen: remember what
    /// this child saw, so next visit's meters animate from here. Merges over
    /// the stored map — other children's entries survive.
    public func recordSeen() {
        guard let id = store.activeId else { return }
        var seen = ProfileStorage.loadShopSeen(kv)
        seen[id] = store.profile.balance
        ProfileStorage.saveShopSeen(seen, to: kv)
    }

    /// The in-flight `void audio.say(...)`. The app never awaits it — speech is
    /// fire-and-forget, exactly like the web's dangling promise — but a test
    /// can `await` it to settle the transcript deterministically.
    @ObservationIgnored public private(set) var speechTask: Task<Void, Never>?

    /// `void audio.say(...)` — fire and forget; the shop never gates on speech.
    private func say(_ text: String) {
        let audio = self.audio
        speechTask = Task { await audio.say(text) }
    }
}

// MARK: - Metrics (the Tailwind classes and inline styles, verbatim)

enum ShopMetrics {
    /// Root: `min-h-[620px] gap-4 rounded-3xl pb-10`.
    static let minHeight: CGFloat = Shell.minimumScreenHeight
    static let rootGap: CGFloat = 16
    static let cornerRadius: CGFloat = 24
    static let paddingBottom: CGFloat = 40

    /// Header: `gap-2 rounded-b-3xl px-5 pb-4 pt-4` + the 82 % cream blur.
    static let headerGap: CGFloat = 8
    static let headerPaddingX: CGFloat = 20
    static let headerPaddingY: CGFloat = 16
    /// `<Mascot size={128} />` — the header friend, the REAL look.
    static let headerMascotSize: CGFloat = 128
    /// `drop-shadow-lg` (approximated as its stronger layer).
    static let mascotShadow = CSSShadow(y: 4, blur: 8, opacity: 0.1)

    /// Chips: `px-4 py-2`, back `text-base`, wallet `text-lg`.
    static let chipPaddingX: CGFloat = 16
    static let chipPaddingY: CGFloat = 8
    static let chipShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
    /// `active:scale-95` on the back button.
    static let backActiveScale: CGFloat = 0.95

    /// Zones column: `gap-5 px-5`.
    static let zoneGap: CGFloat = 20
    static let zonePaddingX: CGFloat = 20
    /// A zone: `rounded-3xl p-4`, heading `mb-3`, children `gap-4`.
    static let zonePadding: CGFloat = 16
    static let zoneHeadingGap: CGFloat = 12
    static let zoneContentGap: CGFloat = 16
    static let zoneShadow = CSSShadow(y: 8, blur: 18, opacity: 0.07)
    /// The heading chip: `h-10 w-10 rounded-2xl text-xl`, white 85 %.
    static let zoneChipSide: CGFloat = 40
    static let zoneChipRadius: CGFloat = 16
    /// Heading row `gap-2.5`.
    static let zoneHeadingSpacing: CGFloat = 10

    /// Group grids: `mb-1` under the label, `grid-cols-2 gap-2.5`.
    static let groupLabelGap: CGFloat = 4
    static let gridSpacing: CGFloat = 10

    /// Dialog: `p-5` around, card `max-w-sm gap-3 rounded-3xl p-6`,
    /// `0 24px 60px rgba(0,0,0,0.35)`, mascot 150, meter `w-4/5` × 12 high.
    static let dialogPadding: CGFloat = 20
    static let cardMaxWidth: CGFloat = 384
    static let cardGap: CGFloat = 12
    static let cardPadding: CGFloat = 24
    static let cardShadow = CSSShadow(y: 24, blur: 60, opacity: 0.35)
    static let cardMascotSize: CGFloat = 150
    static let cardMeterFraction: CGFloat = 0.8
    static let cardMeterHeight: CGFloat = 12
    /// Buttons row `gap-2`; ✕ is `h-12 w-12`.
    static let cardButtonGap: CGFloat = 8
    static let cancelSide: CGFloat = 48
    /// Buy: `px-6 py-3 text-lg`, lip `0 6px 0 rgba(0,0,0,0.12)`.
    static let buyPaddingX: CGFloat = 24
    static let buyPaddingY: CGFloat = 12
    static let buyLipDrop: CGFloat = 6
    static let buyLipOpacity: Double = 0.12
}

// MARK: - The view

/// `<Shop onBack />`.
public struct ShopView: View {
    @State private var model: ShopModel
    private let onBack: () -> Void

    @Environment(\.alReduceMotion) private var reduceMotion

    @State private var walletHandle = LayerHandle()
    @State private var previewHandle = LayerHandle()
    @State private var buyHandle = LayerHandle()
    /// The web's throwaway `document.body` layer: a full-screen, hit-invisible
    /// host ABOVE the dialog (`z-index: 60` over `z-50`) that the star flights
    /// and the grow burst draw on.
    @State private var flightHandle = LayerHandle()

    public init(
        store: ProfileStore,
        audio: any AudioEngine,
        kv: any KVStore,
        onBack: @escaping () -> Void
    ) {
        _model = State(initialValue: ShopModel(store: store, audio: audio, kv: kv))
        self.onBack = onBack
    }

    /// Tests inject a prepared model (e.g. with a try-on already open).
    init(model: ShopModel, onBack: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.onBack = onBack
    }

    public var body: some View {
        let profile = model.store.profile
        ZStack {
            ScrollView {
                zones(profile)
                    .padding(.top, ShopMetrics.rootGap)
                    .padding(.bottom, ShopMetrics.paddingBottom)
            }
            .safeAreaInset(edge: .top, spacing: 0) { header(profile) }

            // The try-on dialog — `fixed inset-0 z-50`, over the sticky header.
            if let cart = model.cart {
                TryOnDialogView(
                    option: cart,
                    config: profile.config,
                    balance: profile.balance,
                    sinceBalance: model.sinceBalance,
                    buyHandle: buyHandle,
                    reduceMotion: reduceMotion,
                    onBuy: { model.confirmBuy() },
                    onCancel: { model.cancelTryOn() })
            }

            // The particle layer — over everything, touching nothing.
            LayerHost(handle: flightHandle) { Color.clear }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: ShopMetrics.minHeight)
        .clipShape(RoundedRectangle(cornerRadius: ShopMetrics.cornerRadius))
        .stageWash(Palette.stageAdult)
        .fontDesign(.rounded)  // ui-rounded,'SF Pro Rounded',…
        .onAppear {
            wireCelebrations()
            model.recordSeen()
        }
        .onChange(of: profile.balance) { _, _ in model.recordSeen() }
        .onChange(of: model.store.activeId) { _, _ in model.recordSeen() }
    }

    /// The `anim.ts` calls, installed where the geometry lives. Every one is
    /// reduced-motion gated inside `ShopAnim` (D29 — the shop's set is gated,
    /// unlike the exercise tiles').
    private func wireCelebrations() {
        let reduceMotion = self.reduceMotion
        let wallet = walletHandle
        let preview = previewHandle
        let buy = buyHandle
        let flight = flightHandle

        model.onPurchaseCelebrate = { option in
            // « the stars fly from the tapped button to the HEADER mascot »;
            // the wallet stands in if the button never laid out.
            ShopAnim.starFlight(
                from: buy.layer ?? wallet.layer,
                to: preview.layer,
                overlay: flight.layer,
                count: shopFlightSize(cost: option.cost),
                reduceMotion: reduceMotion)
            ShopAnim.pop(preview.layer, reduceMotion: reduceMotion)
        }
        model.onEquipPop = {
            ShopAnim.pop(preview.layer, reduceMotion: reduceMotion)
        }
        model.onGrewFlight = { price in
            ShopAnim.starFlight(
                from: wallet.layer,
                to: preview.layer,
                overlay: flight.layer,
                count: shopFlightSize(cost: price),
                reduceMotion: reduceMotion)
        }
        model.onGrewCelebrate = {
            ShopAnim.pop(preview.layer, reduceMotion: reduceMotion)
            ShopAnim.growBurst(
                around: preview.layer,
                overlay: flight.layer,
                reduceMotion: reduceMotion)
        }
    }

    // MARK: Header

    private func header(_ profile: ProfileView) -> some View {
        VStack(spacing: ShopMetrics.headerGap) {
            HStack {
                backChip
                Spacer(minLength: 0)
                wallet(profile.balance)
            }

            // The header friend is the REAL look — what's owned and worn.
            // Trying on happens in the dialog, so this never flickers with
            // maybes.
            LayerHost(handle: previewHandle) {
                Ollie(
                    config: profile.config,
                    mood: .idle,
                    size: ShopMetrics.headerMascotSize,
                    reduceMotion: reduceMotion)
            }
            .shadow(
                color: .black.opacity(ShopMetrics.mascotShadow.opacity),
                radius: ShopMetrics.mascotShadow.swiftUIRadius,
                x: 0,
                y: ShopMetrics.mascotShadow.y)

            Text(verbatim: Copy.Shop.tagline)
                .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                .foregroundStyle(Palette.inkSoft.color)
        }
        .padding(.horizontal, ShopMetrics.headerPaddingX)
        .padding(.vertical, ShopMetrics.headerPaddingY)
        .frame(maxWidth: .infinity)
        .background {
            // `rgba(255,244,224,0.82)` + `backdrop-filter: blur(8px)`.
            let shape = UnevenRoundedRectangle(
                bottomLeadingRadius: ShopMetrics.cornerRadius,
                bottomTrailingRadius: ShopMetrics.cornerRadius)
            shape.fill(.ultraThinMaterial)
            shape.fill(Palette.shopHeader)
        }
    }

    private var backChip: some View {
        Button(action: onBack) {
            Text(verbatim: Copy.Shop.backLabel)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .padding(.horizontal, ShopMetrics.chipPaddingX)
                .padding(.vertical, ShopMetrics.chipPaddingY)
                .background {
                    Capsule()
                        .fill(.white.opacity(Palette.White.o85))
                        .shadow(
                            color: .black.opacity(ShopMetrics.chipShadow.opacity),
                            radius: ShopMetrics.chipShadow.swiftUIRadius,
                            x: 0,
                            y: ShopMetrics.chipShadow.y)
                }
        }
        .buttonStyle(PickerActiveScaleStyle(scale: ShopMetrics.backActiveScale))
        .accessibilityLabel(Text(verbatim: Copy.Shop.back))
    }

    private func wallet(_ balance: Int) -> some View {
        LayerHost(handle: walletHandle) {
            HStack(spacing: 0) {
                Text(verbatim: "\(Copy.Shop.walletGlyph) ")
                AnimatedNumberText(value: balance, reduceMotion: reduceMotion)
            }
            .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
            .foregroundStyle(Palette.goldInk.color)
            .padding(.horizontal, ShopMetrics.chipPaddingX)
            .padding(.vertical, ShopMetrics.chipPaddingY)
            .background {
                Capsule()
                    .fill(Palette.wallet.color)
                    .shadow(
                        color: .black.opacity(ShopMetrics.chipShadow.opacity),
                        radius: ShopMetrics.chipShadow.swiftUIRadius,
                        x: 0,
                        y: ShopMetrics.chipShadow.y)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: Copy.Shop.wallet(balance)))
    }

    // MARK: Zones

    private func zones(_ profile: ProfileView) -> some View {
        VStack(spacing: ShopMetrics.zoneGap) {
            // Yours first: dressing what you own is the everyday action.
            zone(
                icon: Copy.Shop.wardrobeGlyph,
                title: Copy.Shop.wardrobeTitle,
                tint: Palette.wardrobeZone
            ) {
                tileGroups(shopArmoireGroups(for: profile), profile: profile)
            }

            // Then the store: growth (the headline spend) + everything unowned.
            zone(
                icon: Copy.Shop.storeGlyph,
                title: Copy.Shop.storeTitle,
                tint: Palette.storeZone
            ) {
                GrowthCardView(
                    profile: profile,
                    sinceBalance: model.sinceBalance,
                    onGrow: { model.grow() })

                let groups = shopStoreGroups(for: profile)
                if groups.isEmpty {
                    Text(verbatim: Copy.Shop.storeEmpty)
                        .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                        .foregroundStyle(Palette.inkSoft.color)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                } else {
                    tileGroups(groups, profile: profile)
                }
            }
        }
        .padding(.horizontal, ShopMetrics.zonePaddingX)
    }

    /// A zone is a ROOM, not a heading: distinct tints + an icon chip make the
    /// wardrobe/store split spatial, so ownership is a place a pre-reader can
    /// see.
    private func zone(
        icon: String,
        title: String,
        tint: HexGradient,
        @ViewBuilder content: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: ShopMetrics.zoneHeadingSpacing) {
                Text(verbatim: icon)
                    .font(.system(size: Typography.Size.xl))
                    .frame(width: ShopMetrics.zoneChipSide, height: ShopMetrics.zoneChipSide)
                    .background(
                        .white.opacity(Palette.White.o85),
                        in: RoundedRectangle(cornerRadius: ShopMetrics.zoneChipRadius))
                    .shadow(
                        color: .black.opacity(ShopMetrics.chipShadow.opacity),
                        radius: ShopMetrics.chipShadow.swiftUIRadius,
                        x: 0,
                        y: ShopMetrics.chipShadow.y)
                    .accessibilityHidden(true)

                Text(verbatim: title)
                    .font(Typography.rounded(Typography.Size.xl, Typography.Weight.black))
                    .foregroundStyle(Palette.ink.color)
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.bottom, ShopMetrics.zoneHeadingGap)

            VStack(spacing: ShopMetrics.zoneContentGap) {
                content()
            }
        }
        .padding(ShopMetrics.zonePadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            tint.gradient,
            in: RoundedRectangle(cornerRadius: ShopMetrics.cornerRadius))
        .shadow(
            color: .black.opacity(ShopMetrics.zoneShadow.opacity),
            radius: ShopMetrics.zoneShadow.swiftUIRadius,
            x: 0,
            y: ShopMetrics.zoneShadow.y)
    }

    private func tileGroups(_ groups: [ShopTileGroup], profile: ProfileView) -> some View {
        ForEach(groups, id: \.label) { group in
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: group.label)
                    .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                    .foregroundStyle(Palette.inkSoft.color)
                    .padding(.bottom, ShopMetrics.groupLabelGap)

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: ShopMetrics.gridSpacing),
                        GridItem(.flexible(), spacing: ShopMetrics.gridSpacing),
                    ],
                    spacing: ShopMetrics.gridSpacing
                ) {
                    ForEach(group.tiles, id: \.id) { tile in
                        self.tile(tile, profile: profile)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func tile(_ tile: ShopTile, profile: ProfileView) -> some View {
        switch tile {
        case .factory(let look):
            DefaultTileView(
                look: look,
                species: profile.config.species,
                surface: DefaultLookSurface(look: look, config: profile.config),
                onTap: { model.clearSlot(look) })
        case .item(let option):
            ShopItemView(
                option: option,
                surface: ShopItemSurface(
                    option: option, profile: profile, cartId: model.cart?.id),
                balance: profile.balance,
                sinceBalance: model.sinceBalance,
                onTap: { model.tapItem(option) })
        }
    }
}

// MARK: - The try-on dialog

/// `TryOnDialog` — the ONE place a purchase is decided. The mascot wears the
/// item IN the card (the shop behind stays as-is), the price sits on the buy
/// button, and the meter shows the gap when the wallet is short. Backdrop tap =
/// « non merci ». Nothing here spends; the parent's `confirmBuy` does.
struct TryOnDialogView: View {
    let option: CustomizationOption
    let config: MascotConfig
    let balance: Int
    let sinceBalance: Int
    let buyHandle: LayerHandle
    let reduceMotion: any ReduceMotionSource
    let onBuy: () -> Void
    let onCancel: () -> Void

    @State private var cardHandle = LayerHandle()

    var surface: TryOnSurface { TryOnSurface(option: option, balance: balance) }

    var body: some View {
        let surface = self.surface
        return ZStack {
            // `rgba(74,48,24,0.45)` — tap anywhere outside = cancel.
            Palette.tryOnScrim
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { onCancel() }

            LayerHost(handle: cardHandle) {
                VStack(spacing: ShopMetrics.cardGap) {
                    // The friend wears the item HERE — trying is the whole point.
                    Ollie(
                        config: applyOption(config, option),
                        mood: .idle,
                        size: ShopMetrics.cardMascotSize,
                        reduceMotion: reduceMotion
                    )
                    .shadow(
                        color: .black.opacity(ShopMetrics.mascotShadow.opacity),
                        radius: ShopMetrics.mascotShadow.swiftUIRadius,
                        x: 0,
                        y: ShopMetrics.mascotShadow.y)

                    Text(verbatim: surface.name)
                        .font(Typography.rounded(Typography.Size.xl, Typography.Weight.black))
                        .foregroundStyle(Palette.ink.color)

                    HStack(spacing: ShopMetrics.cardButtonGap) {
                        cancelButton
                        buyButton(surface)
                    }

                    // Saving up: the meter shows how close the wallet is.
                    // `w-4/5` — four fifths of the card's content box, centred.
                    if surface.showsMeter {
                        GeometryReader { proxy in
                            SavingsMeter(
                                cost: option.cost,
                                balance: balance,
                                since: sinceBalance,
                                height: ShopMetrics.cardMeterHeight)
                                .frame(width: proxy.size.width * ShopMetrics.cardMeterFraction)
                                .frame(maxWidth: .infinity)
                        }
                        .frame(height: ShopMetrics.cardMeterHeight)
                    }
                }
                .padding(ShopMetrics.cardPadding)
                .frame(maxWidth: ShopMetrics.cardMaxWidth)
                .background(
                    Palette.stageAdult.gradient,
                    in: RoundedRectangle(cornerRadius: ShopMetrics.cornerRadius))
                .compositingGroup()
                .shadow(
                    color: .black.opacity(ShopMetrics.cardShadow.opacity),
                    radius: ShopMetrics.cardShadow.swiftUIRadius,
                    x: 0,
                    y: ShopMetrics.cardShadow.y)
            }
            .padding(ShopMetrics.dialogPadding)
            // Mount ceremony: the card pops in (gated — `pop` is `anim.ts`'s).
            .onAppear { ShopAnim.pop(cardHandle.layer, reduceMotion: reduceMotion) }
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)  // role="dialog" aria-modal
            .accessibilityLabel(Text(verbatim: surface.title))
        }
    }

    private var cancelButton: some View {
        Button(action: onCancel) {
            Text(verbatim: Copy.Shop.TryOn.cancelGlyph)
                .font(Typography.rounded(Typography.Size.xl, Typography.Weight.black))
                .foregroundStyle(Palette.inkFaint.color)
                .frame(width: ShopMetrics.cancelSide, height: ShopMetrics.cancelSide)
                .background {
                    Circle()
                        .fill(.white.opacity(Palette.White.o90))
                        .shadow(
                            color: .black.opacity(ShopMetrics.chipShadow.opacity),
                            radius: ShopMetrics.chipShadow.swiftUIRadius,
                            x: 0,
                            y: ShopMetrics.chipShadow.y)
                }
        }
        .buttonStyle(PickerActiveScaleStyle(scale: ShopMetrics.backActiveScale))
        .accessibilityLabel(Text(verbatim: Copy.Shop.TryOn.cancel))
    }

    private func buyButton(_ surface: TryOnSurface) -> some View {
        LayerHost(handle: buyHandle) {
            Text(verbatim: surface.buyLabel)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.black))
                .foregroundStyle(
                    surface.affordable ? Palette.goldInk.color : Palette.disabled.color)
                .padding(.horizontal, ShopMetrics.buyPaddingX)
                .padding(.vertical, ShopMetrics.buyPaddingY)
                .background(
                    surface.affordable
                        ? Palette.wallet.color : .white.opacity(Palette.White.o70),
                    in: Capsule())
                .compositingGroup()
                .shadow(
                    color: .black.opacity(surface.affordable ? ShopMetrics.buyLipOpacity : 0),
                    radius: 0,
                    x: 0,
                    y: surface.affordable ? ShopMetrics.buyLipDrop : 0)
        }
        .contentShape(Capsule())
        // `disabled={!affordable}` — no press, no click, but still visible and
        // still labelled, so the "not yet" state is announced, not hidden.
        .shopTilePress(
            enabled: surface.affordable,
            handle: buyHandle,
            reduceMotion: reduceMotion,
            onTap: onBuy)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: surface.buyAccessibilityLabel))
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - The wallet count, on screen

/// `AnimatedNumber` — the count runs OFF the state path: the figure per frame
/// is derived inside a `TimelineView` from the frozen `WalletCount` + start
/// date (the SwiftUI analogue of rAF writing `textContent`), so nothing
/// mutates state per frame (invariant 2). One state flip ends the count.
struct AnimatedNumberText: View {
    let value: Int
    let reduceMotion: any ReduceMotionSource

    @State private var count: WalletCount?
    @State private var started: Date = .distantPast

    var body: some View {
        Group {
            if let count, count.animates {
                TimelineView(.animation) { context in
                    Text(verbatim: String(count.value(at: context.date.timeIntervalSince(started))))
                }
            } else {
                Text(verbatim: String(value))
            }
        }
        .onChange(of: value) { old, new in
            // `if (reducedMotion())` — the figure just jumps.
            guard !reduceMotion.isReduced else {
                count = nil
                return
            }
            // A change landing mid-count rewinds from the figure currently ON
            // SCREEN (`shownRef.current`), not from the old target.
            let from = count.map { $0.value(at: Date().timeIntervalSince(started)) } ?? old
            let next = WalletCount(from: from, to: new)
            guard next.animates else { return }
            count = next
            started = Date()
        }
        .task(id: started) {
            guard let count, count.animates else { return }
            try? await Task.sleep(nanoseconds: UInt64(count.duration * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.count = nil
        }
    }
}
