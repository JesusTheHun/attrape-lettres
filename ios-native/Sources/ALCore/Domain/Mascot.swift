// Port of the mascot vocabulary from `src/types.ts` (with `src/mascot/catalog.ts`
// for the catalog row's shape).
//
// D8: these types have exactly ONE owner and it is here. Content, levels,
// persistence, the mascot art and the shop all need them; written twice they
// drift. `ALCore/Mascot/*` (growth math, anchors, ids, catalog data) and
// `ALArt/Mascot/*` (the rigs) import this file — they do not redeclare it.
//
// `Mood` is not here: it is shared with the exercises and lives in
// `Domain/ExerciseID.swift`.

/** Mascot + rewards --------------------------------------------------------*/
/* Shared contract for the mascot / points / customization feature. Agents A   */
/* (design), B (earn+dashboard) and C (spend+customize) all build against this. */
/* Do not fork these shapes; add agent-local types in agent-owned files.       */

public enum Species: String, CaseIterable, Hashable, Codable, Sendable {
    case unicorn
    case cat
    case fox
    case rabbit
    case dragon
}

/// 0 = baby … 9 = majestic. 10 growth stages.
public let growthStages = 10

public enum CustomizationCategory: String, CaseIterable, Hashable, Codable, Sendable {
    case accessory
    case color
    case style
}

/// One buyable item in the shop. `slot` is the config key it writes.
public struct CustomizationOption: Hashable, Codable, Identifiable, Sendable {
    /// Globally unique, e.g. "unicorn.horn.rainbow".
    public var id: String
    public var species: Species
    public var category: CustomizationCategory
    /// Config key this writes: colours/styles set `colors[slot]`/`styles[slot]`.
    public var slot: String
    /// Colour hex or style-variant id. Ignored for pure accessories.
    public var value: String
    /// French shop label.
    public var name: String
    /// Optional shop thumbnail.
    public var emoji: String?
    /// Cost in points.
    public var cost: Int
    /// Optional growth gate (default 0).
    // NB: stays Optional. The catalog test distinguishes "absent" from "0" —
    // `catalog.ts` only spreads `minStage` in when it was passed.
    public var minStage: Int?

    public init(
        id: String,
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String,
        name: String,
        emoji: String? = nil,
        cost: Int,
        minStage: Int? = nil
    ) {
        self.id = id
        self.species = species
        self.category = category
        self.slot = slot
        self.value = value
        self.name = name
        self.emoji = emoji
        self.cost = cost
        self.minStage = minStage
    }
}

/// Everything that makes one child's mascot look the way it does.
public struct MascotConfig: Hashable, Codable, Sendable {
    public var species: Species
    /// 0..growthStages-1
    public var stage: Int
    /// slot -> hex colour, e.g. { hornColor: "#F0A", tailColor: "#8CF" }
    // NB: index signatures stay `[String: String]`. The rigs `pick()` with
    // fallbacks, so an unknown slot key written by an older build must round-trip
    // untouched — modelling slots as enums would silently drop it.
    public var colors: [String: String]
    /// slot -> variant id, e.g. { tailSize: "long", hair: "curly" }
    public var styles: [String: String]
    /// Equipped accessory option ids.
    public var accessories: [String]

    public init(
        species: Species,
        stage: Int,
        colors: [String: String],
        styles: [String: String],
        accessories: [String]
    ) {
        self.species = species
        self.stage = stage
        self.colors = colors
        self.styles = styles
        self.accessories = accessories
    }
}
