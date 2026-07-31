import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/ShopItem.tsx` — one buyable / equippable tile — plus the factory-  */
/* look tile (`DefaultTile` in `Shop.tsx`), which shares its whole visual        */
/* scaffold.                                                                   */
/*                                                                             */
/* Purely presentational: the parent computes every state flag and owns the     */
/* buy()/setConfig() side-effect in `onTap`. State is coded for a PRE-READER —  */
/* colour, shape and fill, never prose:                                        */
/*   gold price tag   = you can buy this now                                   */
/*   grey tag + meter = keep saving                                            */
/*   corner ✓ sticker = yours (green-filled when worn; ✕ = tap takes it off)   */
/*   🌱 chip          = your friend must grow first                            */
/* The art itself is NEVER greyed out — a grey drawing reads as "broken".       */
/*                                                                             */
/* Every flag and string is extracted into `ShopItemSurface` /                  */
/* `DefaultLookSurface` so the host tests assert them against the TSX without   */
/* a renderer.                                                                 */
/* -------------------------------------------------------------------------- */

// MARK: - The buyable tile's state (pure, host-tested)

/// Everything `renderItem` (Shop.tsx) computes + everything `ShopItem.tsx`
/// derives from it, for one option against one profile.
public struct ShopItemSurface: Equatable, Sendable {
    /// The corner "yours" sticker.
    public enum Sticker: Equatable, Sendable {
        /// Not owned — no sticker.
        case none
        /// `✓` — white chip, green ink (owned, not worn) or green chip, white
        /// ink (worn colour/style).
        case check(filled: Bool)
        /// `✕` — a worn accessory comes off on tap. Always green-filled.
        case remove
    }

    /// The price panel under the name — "only until owned; a bought item never
    /// shows a number."
    public enum PricePanel: Equatable, Sendable {
        case none
        /// « 🌱 niv. N · ⭐ C ».
        case locked(badge: String)
        /// « ⭐ C » on gold.
        case affordable(badge: String)
        /// « ⭐ C » greyed + the savings meter.
        case unaffordable(badge: String)
    }

    public let owned: Bool
    public let equipped: Bool
    public let locked: Bool
    public let affordable: Bool
    public let trying: Bool
    /// `equipped && option.category === "accessory"` — tapping takes it off.
    public let removable: Bool
    /// The second half of « <nom>, <état> ».
    public let stateLabel: String
    public let accessibilityLabel: String
    public let sticker: Sticker
    public let pricePanel: PricePanel

    public var showsMeter: Bool {
        if case .unaffordable = pricePanel { return true }
        return false
    }

    public init(option: CustomizationOption, profile: ProfileView, cartId: String?) {
        let config = profile.config
        let owned = profile.owned.contains(option.id)
        let equipped: Bool
        switch option.category {
        case .accessory: equipped = config.accessories.contains(option.id)
        case .color: equipped = config.colors[option.slot] == option.value
        case .style: equipped = config.styles[option.slot] == option.value
        }
        let minStage = option.minStage ?? 0
        let locked = config.stage < minStage
        let affordable = owned || profile.balance >= option.cost
        let trying = cartId == option.id
        let removable = equipped && option.category == .accessory

        self.owned = owned
        self.equipped = equipped
        self.locked = locked
        self.affordable = affordable
        self.trying = trying
        self.removable = removable

        self.stateLabel = equipped
            ? (removable ? Copy.Shop.ItemState.equippedRemovable : Copy.Shop.ItemState.equipped)
            : owned
                ? Copy.Shop.ItemState.owned
                : locked
                    ? Copy.Shop.ItemState.lockedCost(option.cost, stage: minStage + 1)
                    : trying
                        ? Copy.Shop.ItemState.tryingCost(option.cost)
                        : affordable
                            ? Copy.Shop.ItemState.cost(option.cost)
                            : Copy.Shop.ItemState.cannotAfford(option.cost)
        self.accessibilityLabel = Copy.Shop.ItemState.label(option.name, stateLabel)

        self.sticker = !owned ? .none : (removable ? .remove : .check(filled: equipped))

        self.pricePanel = owned
            ? .none
            : locked
                ? .locked(badge: Copy.Shop.ItemState.lockedBadge(stage: minStage + 1, cost: option.cost))
                : affordable
                    ? .affordable(badge: Copy.Shop.ItemState.priceBadge(option.cost))
                    : .unaffordable(badge: Copy.Shop.ItemState.priceBadge(option.cost))
    }
}

// MARK: - The factory-look tile's state (pure, host-tested)

/// `DefaultTile` in `Shop.tsx`: the mascot's factory look for one slot, shown
/// as an ordinary ALREADY-OWNED tile — never "reset/default" jargon. Selecting
/// it clears the slot, so the rig falls back to this exact look.
public struct DefaultLookSurface: Equatable, Sendable {
    /// `config[kind][slot] === undefined` — the slot is unwritten, so the
    /// factory look is what the rig shows.
    public let active: Bool
    public let locked: Bool
    /// « 🌱 niv. N » / « Équipé ✓ » / « À toi ».
    public let badge: String
    public let stateLabel: String
    public let accessibilityLabel: String

    public init(look: DefaultLook, config: MascotConfig) {
        let minStage = look.minStage ?? 0
        let locked = config.stage < minStage
        let active = look.category == .color
            ? config.colors[look.slot] == nil
            : config.styles[look.slot] == nil

        self.active = active
        self.locked = locked
        self.badge = locked
            ? Copy.Shop.DefaultLook.lockedBadge(stage: minStage + 1)
            : active ? Copy.Shop.DefaultLook.equippedBadge : Copy.Shop.DefaultLook.ownedBadge
        self.stateLabel = locked
            ? Copy.Shop.DefaultLook.lockedState(stage: minStage + 1)
            : active ? Copy.Shop.DefaultLook.equippedState : Copy.Shop.DefaultLook.ownedState
        self.accessibilityLabel = Copy.Shop.ItemState.label(look.name, stateLabel)
    }
}

// MARK: - Shared tile metrics (the Tailwind classes, verbatim)

enum ShopTileMetrics {
    /// `min-h-[96px]` — also comfortably past the 44 pt tap floor (invariant 6).
    static let minHeight: CGFloat = 96
    /// `rounded-2xl p-3 gap-1`.
    static let cornerRadius: CGFloat = 16
    static let padding: CGFloat = 12
    static let gap: CGFloat = 4
    /// `opacity: locked ? 0.55 : 1` — on the whole tile, art included.
    static let lockedOpacity: Double = 0.55
    /// `0 0 0 4px` — the equipped / trying ring.
    static let ringWidth: CGFloat = 4
    /// `0 8px 16px rgba(0,0,0,0.12)` under a ringed tile.
    static let ringedShadow = CSSShadow(y: 8, blur: 16, opacity: 0.12)
    /// `0 6px 14px rgba(0,0,0,0.10)` under a plain tile.
    static let plainShadow = CSSShadow(y: 6, blur: 14, opacity: 0.10)

    /// The corner sticker: `h-7 w-7` at `-right-1.5 -top-1.5`.
    static let stickerSide: CGFloat = 28
    static let stickerOverhang: CGFloat = 6
    static let stickerShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)

    /// Price chips: `px-2.5 py-0.5` (gold/grey), `px-2 py-0.5` (locked).
    static let chipPaddingX: CGFloat = 10
    static let chipPaddingXLocked: CGFloat = 8
    static let chipPaddingY: CGFloat = 2
    /// `bg rgba(90,58,30,0.08)` (locked) / `0.10` (unaffordable) — ink brown.
    static let lockedChipOpacity: Double = 0.08
    static let unaffordableChipOpacity: Double = 0.10
}

// MARK: - The buyable tile

/// `<ShopItem option owned equipped locked affordable trying balance
/// sinceBalance onTap />` — flags folded into `ShopItemSurface`.
public struct ShopItemView: View {
    public let option: CustomizationOption
    public let surface: ShopItemSurface
    /// Wallet, for the savings meter on unaffordable items.
    public let balance: Int
    /// Wallet at the previous shop visit — the meter animates from there.
    public let sinceBalance: Int
    public let onTap: () -> Void

    @Environment(\.alReduceMotion) private var reduceMotion
    @State private var handle = LayerHandle()

    public init(
        option: CustomizationOption,
        surface: ShopItemSurface,
        balance: Int,
        sinceBalance: Int,
        onTap: @escaping () -> Void
    ) {
        self.option = option
        self.surface = surface
        self.balance = balance
        self.sinceBalance = sinceBalance
        self.onTap = onTap
    }

    public var body: some View {
        LayerHost(handle: handle) {
            VStack(spacing: ShopTileMetrics.gap) {
                ItemPreview(
                    species: option.species,
                    category: option.category,
                    slot: option.slot,
                    value: option.value)

                Text(verbatim: option.name)
                    .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                    .foregroundStyle(Palette.ink.color)
                    .multilineTextAlignment(.center)

                pricePanel
            }
            .padding(ShopTileMetrics.padding)
            .frame(maxWidth: .infinity, minHeight: ShopTileMetrics.minHeight)
            .background(background, in: RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius))
            .overlay {
                if let ring {
                    RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius)
                        .stroke(ring, lineWidth: ShopTileMetrics.ringWidth)
                }
            }
            .overlay(alignment: .topTrailing) { sticker }
            .compositingGroup()
            .shadow(
                color: .black.opacity(shadow.opacity),
                radius: shadow.swiftUIRadius,
                x: 0,
                y: shadow.y)
        }
        .opacity(surface.locked ? ShopTileMetrics.lockedOpacity : 1)
        .contentShape(RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius))
        // `disabled={locked}` — a disabled button fires neither pointerdown nor
        // click, so a locked tile gets no touch surface at all.
        .shopTilePress(
            enabled: !surface.locked,
            handle: handle,
            reduceMotion: reduceMotion,
            onTap: onTap)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: surface.accessibilityLabel))
        .accessibilityAddTraits(surface.equipped ? [.isButton, .isSelected] : .isButton)
    }

    private var background: Color {
        surface.equipped
            ? Palette.shopEquipped.color
            : surface.trying ? Palette.shopTrying.color : .white.opacity(Palette.White.o90)
    }

    private var ring: Color? {
        surface.equipped ? Palette.green.color : surface.trying ? Palette.tryRing.color : nil
    }

    private var shadow: CSSShadow {
        surface.equipped || surface.trying
            ? ShopTileMetrics.ringedShadow : ShopTileMetrics.plainShadow
    }

    /// The "yours" sticker — icon, not words.
    @ViewBuilder private var sticker: some View {
        switch surface.sticker {
        case .none:
            EmptyView()
        case .remove:
            stickerChip(Copy.Shop.ItemState.removeGlyph, filled: true)
        case .check(let filled):
            stickerChip(Copy.Shop.ItemState.ownedGlyph, filled: filled)
        }
    }

    private func stickerChip(_ glyph: String, filled: Bool) -> some View {
        Text(verbatim: glyph)
            .font(Typography.rounded(Typography.Size.sm, Typography.Weight.black))
            .foregroundStyle(filled ? Color.white : Palette.equippedInk.color)
            .frame(width: ShopTileMetrics.stickerSide, height: ShopTileMetrics.stickerSide)
            .background(filled ? Palette.green.color : Color.white, in: Circle())
            .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
            .shadow(
                color: .black.opacity(ShopTileMetrics.stickerShadow.opacity),
                radius: ShopTileMetrics.stickerShadow.swiftUIRadius,
                x: 0,
                y: ShopTileMetrics.stickerShadow.y)
            .offset(x: ShopTileMetrics.stickerOverhang, y: -ShopTileMetrics.stickerOverhang)
            .accessibilityHidden(true)
    }

    /// Price panel — only until owned.
    @ViewBuilder private var pricePanel: some View {
        switch surface.pricePanel {
        case .none:
            EmptyView()
        case .locked(let badge):
            Text(verbatim: badge)
                .font(Typography.rounded(Typography.Size.xs, Typography.Weight.black))
                .foregroundStyle(Palette.inkFaint.color)
                .padding(.horizontal, ShopTileMetrics.chipPaddingXLocked)
                .padding(.vertical, ShopTileMetrics.chipPaddingY)
                .background(
                    Palette.ink.color.opacity(ShopTileMetrics.lockedChipOpacity),
                    in: Capsule())
                .accessibilityHidden(true)
        case .affordable(let badge):
            Text(verbatim: badge)
                .font(Typography.rounded(Typography.Size.sm, Typography.Weight.black))
                .foregroundStyle(Palette.goldInk.color)
                .padding(.horizontal, ShopTileMetrics.chipPaddingX)
                .padding(.vertical, ShopTileMetrics.chipPaddingY)
                .background(Palette.wallet.color, in: Capsule())
                .compositingGroup()  // D54 — the box casts the shadow, not the glyphs inside it
                .shadow(
                    color: .black.opacity(ShopTileMetrics.stickerShadow.opacity),
                    radius: ShopTileMetrics.stickerShadow.swiftUIRadius,
                    x: 0,
                    y: ShopTileMetrics.stickerShadow.y)
                .accessibilityHidden(true)
        case .unaffordable(let badge):
            Text(verbatim: badge)
                .font(Typography.rounded(Typography.Size.sm, Typography.Weight.black))
                .foregroundStyle(Palette.inkUnaffordable.color)
                .padding(.horizontal, ShopTileMetrics.chipPaddingX)
                .padding(.vertical, ShopTileMetrics.chipPaddingY)
                .background(
                    Palette.ink.color.opacity(ShopTileMetrics.unaffordableChipOpacity),
                    in: Capsule())
                .accessibilityHidden(true)
            SavingsMeter(cost: option.cost, balance: balance, since: sinceBalance)
        }
    }
}

// MARK: - The factory-look tile

/// `<DefaultTile look species active locked onTap />`.
public struct DefaultTileView: View {
    public let look: DefaultLook
    public let species: Species
    public let surface: DefaultLookSurface
    public let onTap: () -> Void

    @Environment(\.alReduceMotion) private var reduceMotion
    @State private var handle = LayerHandle()

    public init(
        look: DefaultLook,
        species: Species,
        surface: DefaultLookSurface,
        onTap: @escaping () -> Void
    ) {
        self.look = look
        self.species = species
        self.surface = surface
        self.onTap = onTap
    }

    public var body: some View {
        LayerHost(handle: handle) {
            VStack(spacing: ShopTileMetrics.gap) {
                ItemPreview(
                    species: species,
                    category: look.category,
                    slot: look.slot,
                    value: look.value)

                Text(verbatim: look.name)
                    .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                    .foregroundStyle(Palette.ink.color)
                    .multilineTextAlignment(.center)

                // `color: active ? "#3E7B3E" : "#9A7A5A"` — keyed on `active`
                // ALONE: a locked slot that is still unwritten shows its 🌱
                // badge in the green. Faithful to the TSX, odd as it looks.
                Text(verbatim: surface.badge)
                    .font(Typography.rounded(Typography.Size.xs, Typography.Weight.black))
                    .foregroundStyle(
                        surface.active ? Palette.equippedInk.color : Palette.inkFaint.color)
            }
            .padding(ShopTileMetrics.padding)
            .frame(maxWidth: .infinity, minHeight: ShopTileMetrics.minHeight)
            .background(
                surface.active ? Palette.shopEquipped.color : .white.opacity(Palette.White.o90),
                in: RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius))
            .overlay {
                if surface.active {
                    RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius)
                        .stroke(Palette.green.color, lineWidth: ShopTileMetrics.ringWidth)
                }
            }
            .compositingGroup()
            .shadow(
                color: .black.opacity(activeShadow.opacity),
                radius: activeShadow.swiftUIRadius,
                x: 0,
                y: activeShadow.y)
        }
        .opacity(surface.locked ? ShopTileMetrics.lockedOpacity : 1)
        .contentShape(RoundedRectangle(cornerRadius: ShopTileMetrics.cornerRadius))
        .shopTilePress(
            enabled: !surface.locked,
            handle: handle,
            reduceMotion: reduceMotion,
            onTap: onTap)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: surface.accessibilityLabel))
        .accessibilityAddTraits(surface.active ? [.isButton, .isSelected] : .isButton)
    }

    private var activeShadow: CSSShadow {
        surface.active ? ShopTileMetrics.ringedShadow : ShopTileMetrics.plainShadow
    }
}

// MARK: - The shared press-then-tap wiring

extension View {
    /// `onPointerDown={() => press(ref.current)}` + `onClick={onTap}` — the
    /// shop's GATED squish at touch-down (D29: `shop/anim.ts` checks reduced
    /// motion itself, unlike `Tile`'s), then the action on touch-UP inside.
    /// `enabled: false` is the web's `disabled` attribute: neither event fires.
    @ViewBuilder
    func shopTilePress(
        enabled: Bool,
        handle: LayerHandle,
        reduceMotion: any ReduceMotionSource,
        onTap: @escaping () -> Void
    ) -> some View {
        if enabled {
            touchDown {
                ShopAnim.press(handle.layer, reduceMotion: reduceMotion)
            } onUp: { inside in
                if inside { onTap() }
            }
        } else {
            self
        }
    }
}
