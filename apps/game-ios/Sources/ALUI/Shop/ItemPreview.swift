import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/ItemPreview.tsx` — the shop tile thumbnail.                       */
/*                                                                             */
/* A GHOST mini-mascot previews the real reward: the whole creature draws in    */
/* neutral grey and ONLY the thing the tile sells is shown for real — the       */
/* recoloured part in its hex, the restyled part in its new shape, or the       */
/* accessory drawn where it lands. A fixed "showcase" stage + the rig's         */
/* `preview` flag (which suppresses the per-stage magic) keep the frame calm.   */
/*                                                                             */
/* `focusFor` decides where to ZOOM: a small part or a worn accessory is        */
/* unreadable inside a whole-body thumbnail, so the drawing is cropped to a     */
/* square around it. The coordinates come from the SAME layout/anchor maths     */
/* the rig draws with (`Growth.layoutFor` / `accessoryAnchors`, ALCore), at     */
/* the fixed showcase stage, so the box lands true. Nothing here authors        */
/* geometry — every number is the TSX's, applied to ALCore's layout.            */
/* -------------------------------------------------------------------------- */

public enum ItemPreviewModel {

    /// `GHOST` — neutral silhouette for every part that ISN'T being sold.
    public static let ghost = Palette.ghost.hex

    /// `SHOWCASE_STAGE` — standing youngster: legs + a real horn/tail visible.
    public static let showcaseStage = 4

    /// `Object.values(COLOR_SLOT[species])` — the slots `ghostColors` greys out,
    /// in the TS object's declaration order.
    public static func colorSlots(_ species: Species) -> [String] {
        switch species {
        case .unicorn:
            return [ColorSlot.Unicorn.body, ColorSlot.Unicorn.horn, ColorSlot.Unicorn.mane, ColorSlot.Unicorn.tail]
        case .cat:
            return [ColorSlot.Cat.body, ColorSlot.Cat.belly, ColorSlot.Cat.tail]
        case .fox:
            return [ColorSlot.Fox.body, ColorSlot.Fox.belly, ColorSlot.Fox.tailTip]
        case .rabbit:
            return [ColorSlot.Rabbit.body, ColorSlot.Rabbit.belly, ColorSlot.Rabbit.inner]
        case .dragon:
            return [ColorSlot.Dragon.body, ColorSlot.Dragon.belly, ColorSlot.Dragon.wing, ColorSlot.Dragon.horn]
        }
    }

    /// `ghostColors(species)` — every colour slot → GHOST.
    public static func ghostColors(_ species: Species) -> [String: String] {
        var out: [String: String] = [:]
        for slot in colorSlots(species) { out[slot] = ghost }
        return out
    }

    /// `STYLE_COLOR_SLOT` — a shape-style lives on ONE part; tinting that part
    /// with its factory colour makes the shape read. Body-wide styles are
    /// omitted (their change draws in its own contrasting colour).
    public static func styleColorSlot(_ species: Species, styleSlot: String) -> String? {
        switch species {
        case .unicorn:
            if styleSlot == StyleSlot.Unicorn.tail { return ColorSlot.Unicorn.tail }
            if styleSlot == StyleSlot.Unicorn.horn { return ColorSlot.Unicorn.horn }
            return nil
        case .cat:
            if styleSlot == StyleSlot.Cat.tail { return ColorSlot.Cat.tail }
            return nil
        case .rabbit:
            // Folded ears read via their lavender inner on the ghost body.
            if styleSlot == StyleSlot.Rabbit.ear { return ColorSlot.Rabbit.inner }
            return nil
        case .dragon:
            // Double horns tinted with the factory ivory.
            if styleSlot == StyleSlot.Dragon.horn { return ColorSlot.Dragon.horn }
            return nil
        case .fox:
            return nil
        }
    }

    /// `defaultColor(species, colorSlot)` — the factory hex for one colour slot.
    public static func defaultColor(_ species: Species, colorSlot: String) -> String? {
        MascotCatalog.defaultLooks[species]?
            .first(where: { $0.category == .color && $0.slot == colorSlot })?
            .value
    }

    /// The thumbnail's whole config: ghost everywhere + the one real thing.
    public static func previewConfig(
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String
    ) -> MascotConfig {
        var colors = ghostColors(species)
        if category == .color {
            colors[slot] = value
        } else if category == .style {
            if let colorSlot = styleColorSlot(species, styleSlot: slot),
               let tint = defaultColor(species, colorSlot: colorSlot) {
                colors[colorSlot] = tint
            }
        }
        return MascotConfig(
            species: species,
            stage: showcaseStage,
            colors: colors,
            // A style preview sets only its own slot; the rig fills every other
            // slot with its plain factory default.
            styles: category == .style ? [slot: value] : [:],
            accessories: category == .accessory ? [value] : [])
    }

    // MARK: - focusFor

    private static func box(_ cx: Double, _ cy: Double, _ r: Double) -> CGRect {
        CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)
    }

    /// The swim set is sold by every species EXCEPT the dragon (tradition
    /// deliberately broken, user decision) — the TS guards `"swimsuit" in AA`.
    static func swimsuitId(_ species: Species) -> String? {
        switch species {
        case .unicorn: return Accessory.Unicorn.swimsuit
        case .cat: return Accessory.Cat.swimsuit
        case .fox: return Accessory.Fox.swimsuit
        case .rabbit: return Accessory.Rabbit.swimsuit
        case .dragon: return nil
        }
    }

    static func swimRingId(_ species: Species) -> String? {
        switch species {
        case .unicorn: return Accessory.Unicorn.swimRing
        case .cat: return Accessory.Cat.swimRing
        case .fox: return Accessory.Fox.swimRing
        case .rabbit: return Accessory.Rabbit.swimRing
        case .dragon: return nil
        }
    }

    /// `focusFor(species, category, slot, value)` — the crop, or nil for the
    /// full body (whole-coat colours, body patterns, whole-image shimmers).
    public static func focus(
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String
    ) -> CGRect? {
        let L = Growth.layoutFor(Double(showcaseStage))
        let A = accessoryAnchors(species, L)
        let headCX = L.headCX
        let headCY = L.headCY
        let headR = L.headR
        let bodyCX = L.bodyCX
        let bodyCY = L.bodyCY
        let bodyRX = L.bodyRX
        let bodyRY = L.bodyRY
        let feetY = L.feetY

        func horn() -> CGRect { box(headCX, headCY - headR * 0.5, headR * 1.25) }
        func mane() -> CGRect { box(headCX, headCY, headR * 1.5) }
        func uniTail() -> CGRect { box(bodyCX - bodyRX * 0.5, bodyCY + bodyRY * 0.5, bodyRY * 1.3) }
        func catTail() -> CGRect { box(bodyCX + bodyRX * 0.9, bodyCY - bodyRY * 0.05, bodyRY * 1.25) }
        func foxTail() -> CGRect { box(bodyCX + bodyRX * 0.85, bodyCY + bodyRY * 0.45, bodyRY * 1.3) }
        func belly() -> CGRect { box(bodyCX, bodyCY + bodyRY * 0.28, bodyRX * 1.15) }
        func rabbitEars() -> CGRect { box(headCX, headCY - headR * 1.1, headR * 1.6) }
        func rabbitTail() -> CGRect { box(bodyCX - bodyRX * 0.95, bodyCY + bodyRY * 0.45, bodyRY * 1.2) }
        func dragonHorns() -> CGRect { box(headCX, headCY - headR * 0.75, headR * 1.35) }

        switch category {
        case .accessory:
            if value == Accessory.Unicorn.starClip { return nil }  // whole-image shimmer
            if value == Accessory.Rabbit.stardust { return nil }  // whole-image star dust
            if value == Accessory.Unicorn.ribbon || value == Accessory.Cat.bellCollar
                || value == Accessory.Fox.scarf || value == Accessory.Rabbit.bow
                || value == Accessory.Dragon.fang {
                return box(A.neck.x, A.neck.y, headR * 1.1)
            }
            if value == Accessory.Cat.bow || value == Accessory.Cat.partyHat
                || value == Accessory.Fox.beanie || value == Accessory.Rabbit.nightcap {
                return box(A.headTop.x, headCY - headR * 0.55, headR * 1.4)
            }
            if value == Accessory.Unicorn.flowerCrown { return box(headCX, headCY - headR * 0.2, headR * 1.4) }
            if value == Accessory.Fox.boots { return box(bodyCX, feetY - 4, bodyRX * 1.25) }
            // Dragon: goggles rest on the forehead; the blue breath curls at
            // the mouth; the treasure heap sits on the ground to his left.
            if value == Accessory.Dragon.goggles { return box(headCX, headCY - headR * 0.6, headR * 1.3) }
            if value == Accessory.Dragon.blueFlame { return box(headCX + headR * 0.35, headCY + headR * 0.7, headR * 1.15) }
            if value == Accessory.Dragon.treasure { return box(18, feetY - 3.5, 11) }
            if let suit = swimsuitId(species), value == suit { return box(bodyCX, bodyCY + bodyRY * 0.25, bodyRX * 1.2) }
            if let ring = swimRingId(species), value == ring { return box(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.55) }
            return nil

        case .color:
            // Every species names its whole coat "bodyColor" — full body.
            switch species {
            case .unicorn:
                if slot == ColorSlot.Unicorn.body { return nil }
                if slot == ColorSlot.Unicorn.horn { return horn() }
                if slot == ColorSlot.Unicorn.mane { return mane() }
                if slot == ColorSlot.Unicorn.tail { return uniTail() }
            case .cat:
                if slot == ColorSlot.Cat.body { return nil }
                if slot == ColorSlot.Cat.belly { return belly() }
                if slot == ColorSlot.Cat.tail { return catTail() }
            case .fox:
                if slot == ColorSlot.Fox.body { return nil }
                if slot == ColorSlot.Fox.belly { return belly() }
                if slot == ColorSlot.Fox.tailTip { return foxTail() }
            case .dragon:
                if slot == ColorSlot.Dragon.body { return nil }
                if slot == ColorSlot.Dragon.belly { return belly() }
                if slot == ColorSlot.Dragon.wing { return box(bodyCX, bodyCY - bodyRY * 0.5, bodyRX * 1.5) }
                if slot == ColorSlot.Dragon.horn { return dragonHorns() }
            case .rabbit:
                if slot == ColorSlot.Rabbit.body { return nil }
                if slot == ColorSlot.Rabbit.belly { return belly() }
                if slot == ColorSlot.Rabbit.inner { return rabbitEars() }
            }
            return nil

        case .style:
            // Zoom part-local shapes; body-wide (fluffy, fur pattern) stay full.
            switch species {
            case .unicorn:
                if slot == StyleSlot.Unicorn.horn { return horn() }
                if slot == StyleSlot.Unicorn.tail { return uniTail() }
            case .cat:
                if slot == StyleSlot.Cat.tail { return catTail() }
            case .fox:
                if slot == StyleSlot.Fox.tail { return foxTail() }
                // Spots/stripes sit on the torso — crop to the trunk.
                if slot == StyleSlot.Fox.fur { return box(bodyCX, bodyCY, bodyRX * 1.15) }
            case .dragon:
                if slot == StyleSlot.Dragon.horn { return dragonHorns() }
                if slot == StyleSlot.Dragon.crest { return box(headCX, headCY - headR * 0.85, headR * 1.35) }
                // Both tail styles change the TIP — crop to the raised tail end.
                if slot == StyleSlot.Dragon.tail { return box(bodyCX + bodyRX * 1.42, bodyCY - bodyRY * 0.2, 11) }
            case .rabbit:
                if slot == StyleSlot.Rabbit.ear { return rabbitEars() }
                if slot == StyleSlot.Rabbit.tail { return rabbitTail() }
                // Star-flecks sit on the torso — crop like the fox pattern.
                if slot == StyleSlot.Rabbit.fur { return box(bodyCX, bodyCY, bodyRX * 1.15) }
            }
            return nil
        }
    }
}

// MARK: - The view

/// `<ItemPreview species category slot value size />`.
public struct ItemPreview: View {
    public let species: Species
    public let category: CustomizationCategory
    /// Config key the item writes (colours/styles). Unused for accessories.
    public let slot: String
    /// Hex (colour), style-variant id (style), or accessory id (accessory).
    public let value: String
    public let size: CGFloat

    @Environment(\.alReduceMotion) private var reduceMotion

    /// `p-[7px]`-equivalent: the plate is `size + 14` square.
    public static let plateInset: CGFloat = 14
    /// `rounded-2xl` on the backing plate.
    public static let plateRadius: CGFloat = 16

    public init(
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String,
        size: CGFloat = 60
    ) {
        self.species = species
        self.category = category
        self.slot = slot
        self.value = value
        self.size = size
    }

    public var body: some View {
        // `preview` clips, pins the growth scale and never animates — the rig's
        // own thumbnail mode, via Ollie's preview branch.
        Ollie(
            config: ItemPreviewModel.previewConfig(
                species: species, category: category, slot: slot, value: value),
            mood: .idle,
            size: size,
            preview: true,
            focus: ItemPreviewModel.focus(
                species: species, category: category, slot: slot, value: value),
            reduceMotion: reduceMotion
        )
        .frame(width: size + Self.plateInset, height: size + Self.plateInset)
        .background(
            Palette.previewPlate.color,
            in: RoundedRectangle(cornerRadius: Self.plateRadius))
        .accessibilityHidden(true)  // aria-hidden — the tile's label names the item
    }
}
