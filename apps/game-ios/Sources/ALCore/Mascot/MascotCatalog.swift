// Port of `src/mascot/catalog.ts`.
//
// Invariant 4 (touches) / spec/mascot.md §12: CATALOG and DEFAULT_LOOKS are
// LITERAL DATA. Nothing here is generated, and no gate is derived — a rule that
// computed `minStage` from the species timeline would be shorter and would
// silently change what a child can buy the next time a rig moves a part.
//
// Every `id` string is a persistence contract (see MascotIDs.swift).

/**
 * Shop inventory — the single source of what's buyable. Owned by AGENT A.
 *
 * Every option's slot/value matches exactly what Mascot.tsx reads:
 *  - colours write config.colors[slot] = value (hex),
 *  - styles  write config.styles[slot] = value (variant id),
 *  - accessories are matched verbatim by option id (see ids.ts / ACCESSORY).
 *
 * Cost bands (first exercise clear = 10 pts, repeats decay): colours 15–30,
 * styles 30–60, accessories 40–150, one premium per species = 200.
 */
public enum MascotCatalog {

    private static func color(
        _ species: Species,
        _ slot: String,
        _ token: String,
        _ value: String,
        _ name: String,
        _ emoji: String,
        _ cost: Int,
        _ minStage: Int? = nil
    ) -> CustomizationOption {
        CustomizationOption(
            id: "\(species.rawValue).color.\(slot).\(token)",
            species: species,
            category: .color,
            slot: slot,
            value: value,
            name: name,
            emoji: emoji,
            cost: cost,
            minStage: minStage
        )
    }

    private static func style(
        _ species: Species,
        _ slot: String,
        _ value: String,
        _ name: String,
        _ emoji: String,
        _ cost: Int,
        _ minStage: Int? = nil
    ) -> CustomizationOption {
        CustomizationOption(
            id: "\(species.rawValue).style.\(slot).\(value)",
            species: species,
            category: .style,
            slot: slot,
            value: value,
            name: name,
            emoji: emoji,
            cost: cost,
            minStage: minStage
        )
    }

    private static func accessory(
        _ id: String,
        _ species: Species,
        _ name: String,
        _ emoji: String,
        _ cost: Int,
        _ minStage: Int? = nil
    ) -> CustomizationOption {
        CustomizationOption(
            id: id,
            species: species,
            category: .accessory,
            slot: "accessory",
            value: id,
            name: name,
            emoji: emoji,
            cost: cost,
            minStage: minStage
        )
    }

    // NB: the TS aliases the slot tables to two-letter locals (U, CA, FO, RA, DR,
    // US, CS, FS, RS, DS) purely so the rows fit on one line. Kept, same names.
    private typealias U = ColorSlot.Unicorn
    private typealias CA = ColorSlot.Cat
    private typealias FO = ColorSlot.Fox
    private typealias RA = ColorSlot.Rabbit
    private typealias DR = ColorSlot.Dragon
    private typealias US = StyleSlot.Unicorn
    private typealias CS = StyleSlot.Cat
    private typealias FS = StyleSlot.Fox
    private typealias RS = StyleSlot.Rabbit
    private typealias DS = StyleSlot.Dragon

    public static let catalog: [CustomizationOption] = unicorn + cat + fox + rabbit + dragon

    /* ---- Unicorn ------------------------------------------------------- */
    private static let unicorn: [CustomizationOption] = [
        color(.unicorn, U.body, "rose", "#FFD6E8", "Corps rose", "🌸", 18),
        color(.unicorn, U.body, "ciel", "#DCEFFB", "Corps bleu ciel", "💧", 18),
        color(.unicorn, U.body, "menthe", "#DDF3D8", "Corps menthe", "🌿", 18),
        // Horn only sprouts at stade 2 (nub) → 3 (real horn); gate horn recolours and
        // the twist so nobody buys a look a hornless baby unicorn can't show.
        color(.unicorn, U.horn, "rose", "#FF8FB1", "Corne rose", "🦄", 20, 2),
        color(.unicorn, U.horn, "turquoise", "#7FD1D8", "Corne turquoise", "💠", 20, 2),
        color(.unicorn, U.mane, "corail", "#FF8A65", "Crinière corail", "🔥", 22),
        color(.unicorn, U.mane, "turquoise", "#7FD1D8", "Crinière turquoise", "🌊", 22),
        color(.unicorn, U.tail, "menthe", "#AED581", "Queue menthe", "🍃", 20),
        style(.unicorn, US.tail, "curly", "Queue bouclée", "🌀", 40),
        style(.unicorn, US.horn, "spiral", "Corne torsadée", "🐚", 45, 3),
        accessory(Accessory.Unicorn.ribbon, .unicorn, "Nœud", "🎀", 45),
        accessory(Accessory.Unicorn.flowerCrown, .unicorn, "Couronne de fleurs", "🌸", 95, 2),
        accessory(Accessory.Unicorn.starClip, .unicorn, "Arc-en-ciel magique", "🌈", 200, 4),
        accessory(Accessory.Unicorn.swimsuit, .unicorn, "Maillot de bain", "🩱", 60),
        accessory(Accessory.Unicorn.swimRing, .unicorn, "Bouée", "🛟", 75),
    ]

    /* ---- Cat ----------------------------------------------------------- */
    private static let cat: [CustomizationOption] = [
        color(.cat, CA.body, "gris", "#C9CCD6", "Pelage gris", "🩶", 18),
        color(.cat, CA.body, "blanc", "#FFF3E6", "Pelage blanc", "🤍", 18),
        color(.cat, CA.body, "noir", "#6A6A72", "Pelage noir", "🖤", 20),
        // Stade 0 is a curled sleeping loaf — belly and tail are tucked out of sight,
        // so gate their looks until the kitten sits up at stade 1.
        color(.cat, CA.belly, "rose", "#FFE1EC", "Ventre rose", "🌸", 16, 1),
        color(.cat, CA.tail, "roux", "#E08A4E", "Queue rousse", "🦊", 18, 1),
        color(.cat, CA.body, "creme", "#F3E4D0", "Pelage crème", "🍦", 18),
        color(.cat, CA.body, "lilas", "#E6DDF5", "Pelage lilas", "💜", 20),
        color(.cat, CA.tail, "noire", "#565660", "Queue noire", "🖤", 18, 1),
        style(.cat, CS.hair, "fluffy", "Poil touffu", "☁️", 40),
        style(.cat, CS.tail, "short", "Petite queue", "🐈", 32, 1),
        accessory(Accessory.Cat.bow, .cat, "Nœud", "🎀", 45),
        accessory(Accessory.Cat.bellCollar, .cat, "Collier grelot", "🔔", 55),
        accessory(Accessory.Cat.partyHat, .cat, "Chapeau de fête", "🎉", 200, 4),
        accessory(Accessory.Cat.swimsuit, .cat, "Maillot de bain", "🩱", 60),
        accessory(Accessory.Cat.swimRing, .cat, "Bouée", "🛟", 75),
    ]

    /* ---- Fox ----------------------------------------------------------- */
    private static let fox: [CustomizationOption] = [
        color(.fox, FO.body, "roux", "#E96B4A", "Pelage roux", "🍂", 18),
        color(.fox, FO.body, "miel", "#F4A259", "Pelage miel", "🍯", 18),
        color(.fox, FO.body, "cendre", "#B7A99A", "Pelage cendré", "🌫️", 20),
        color(.fox, FO.belly, "creme", "#FFDCB4", "Ventre crème", "🤍", 16),
        color(.fox, FO.tailTip, "brun", "#5A3A1E", "Bout de queue brun", "🟤", 18),
        color(.fox, FO.tailTip, "dore", "#FFD54F", "Bout de queue doré", "⭐", 22),
        color(.fox, FO.body, "arctique", "#EDE7DE", "Pelage arctique", "❄️", 20),
        style(.fox, FS.fur, "spots", "Taches", "🐆", 48),
        style(.fox, FS.fur, "stripes", "Rayures", "🐯", 48),
        style(.fox, FS.tail, "short", "Petite queue", "🦊", 32),
        accessory(Accessory.Fox.scarf, .fox, "Écharpe", "🧣", 45),
        accessory(Accessory.Fox.beanie, .fox, "Bonnet", "🧢", 60),
        accessory(Accessory.Fox.boots, .fox, "Bottes", "🥾", 200, 4),
        accessory(Accessory.Fox.swimsuit, .fox, "Maillot de bain", "🩱", 60),
        accessory(Accessory.Fox.swimRing, .fox, "Bouée", "🛟", 75),
    ]

    /* ---- Rabbit --------------------------------------------------------- */
    private static let rabbit: [CustomizationOption] = [
        color(.rabbit, RA.body, "souris", "#D6D3DE", "Pelage gris souris", "🐭", 18),
        color(.rabbit, RA.body, "caramel", "#EFC9A0", "Pelage caramel", "🍮", 18),
        color(.rabbit, RA.body, "peche", "#F8D3BC", "Pelage pêche", "🍑", 18),
        color(.rabbit, RA.body, "lilas", "#E4DCF2", "Pelage lilas", "💜", 20),
        // The inner ears only "bloom" their colour at stade 3 — before that the
        // tint is nearly invisible on the pale baby ear.
        color(.rabbit, RA.inner, "rose", "#F5A8C0", "Oreilles rose poudré", "🌸", 20, 3),
        color(.rabbit, RA.inner, "menthe", "#A8DDB8", "Oreilles menthe", "🌿", 20, 3),
        color(.rabbit, RA.belly, "creme", "#FFE8BC", "Ventre crème", "🍦", 16),
        // Ears lie flat on the back until the rabbit stands at stade 2 — the fold
        // wouldn't show on a lying baby.
        style(.rabbit, RS.ear, "pliees", "Oreilles pliées", "🐰", 45, 2),
        style(.rabbit, RS.tail, "etoile", "Queue étoile", "🌟", 50),
        style(.rabbit, RS.fur, "flocons", "Flocons d'étoiles", "❄️", 48),
        accessory(Accessory.Rabbit.bow, .rabbit, "Nœud étoilé", "🎀", 45),
        accessory(Accessory.Rabbit.nightcap, .rabbit, "Bonnet de nuit", "🌙", 60),
        accessory(Accessory.Rabbit.stardust, .rabbit, "Poussière d'étoiles", "🌠", 200, 4),
        // Worn standing only (stade 2+): the lying nappy-culotte read as a backpack.
        accessory(Accessory.Rabbit.swimsuit, .rabbit, "Maillot de bain", "🩱", 60, 2),
        accessory(Accessory.Rabbit.swimRing, .rabbit, "Bouée", "🛟", 75),
    ]

    /* ---- Dragon --------------------------------------------------------- */
    private static let dragon: [CustomizationOption] = [
        color(.dragon, DR.body, "braise", "#D97B6C", "Écailles rouge braise", "🔥", 20),
        color(.dragon, DR.body, "charbon", "#8A8D96", "Écailles charbon", "🪨", 20),
        color(.dragon, DR.body, "nuit", "#7E8FB5", "Écailles bleu nuit", "🌙", 20),
        color(.dragon, DR.body, "terre", "#B08968", "Écailles brun terre", "🤎", 18),
        // The belly is hidden inside the stade-0 egg; wings sprout at 3; horn nubs at 2.
        color(.dragon, DR.belly, "magma", "#FFB27A", "Ventre magma", "🌋", 22, 1),
        color(.dragon, DR.wing, "nuit", "#5F6470", "Ailes nuit", "🦇", 24, 3),
        color(.dragon, DR.wing, "dorees", "#F2C14E", "Ailes dorées", "⭐", 24, 3),
        color(.dragon, DR.horn, "or", "#F2C14E", "Cornes d'or", "✨", 22, 2),
        color(.dragon, DR.horn, "noires", "#4E5560", "Cornes noires", "🖤", 22, 2),
        style(.dragon, DS.horn, "double", "Cornes doubles", "🐉", 45, 3),
        style(.dragon, DS.crest, "lava", "Crête de lave", "🌋", 50, 4),
        style(.dragon, DS.tail, "club", "Queue massue", "🔨", 40, 1),
        style(.dragon, DS.tail, "flame", "Queue de feu", "☄️", 55, 2),
        accessory(Accessory.Dragon.cape, .dragon, "Cape de chevalier", "🦸", 55, 2),
        accessory(Accessory.Dragon.goggles, .dragon, "Lunettes d'aviateur", "🥽", 65, 3),
        accessory(Accessory.Dragon.fang, .dragon, "Collier de croc", "🦷", 45, 2),
        accessory(Accessory.Dragon.treasure, .dragon, "Petit trésor", "🪙", 70),
        accessory(Accessory.Dragon.blueFlame, .dragon, "Flamme bleue", "💙", 200, 4),
    ]
}

/**
 * The mascot's factory look per colour/style slot. Each `value` MUST match the
 * corresponding `pick(..., fallback)` default in the species rig, so selecting
 * it (which just clears the slot) reproduces exactly what a fresh mascot shows.
 * Surfaced in the shop as an already-owned, NAMED tile — no "reset/default"
 * wording — so a child can simply pick it to return to the original look.
 * `minStage` mirrors the slot's part visibility so a default for a not-yet-grown
 * part (unicorn horn, curled-kitten belly/tail) stays gated like its variants.
 */
public struct DefaultLook: Hashable, Sendable {
    // NB: the TS narrows this to `"color" | "style"` — an accessory has no
    // factory look. Swift reuses `CustomizationCategory` so the shop can compare
    // `(category, slot)` against a catalog row without a second mapping;
    // `MascotCatalogTests` asserts no default look is `.accessory`.
    public var category: CustomizationCategory
    public var slot: String
    public var value: String
    public var name: String
    public var emoji: String?
    public var minStage: Int?

    public init(
        category: CustomizationCategory,
        slot: String,
        value: String,
        name: String,
        emoji: String? = nil,
        minStage: Int? = nil
    ) {
        self.category = category
        self.slot = slot
        self.value = value
        self.name = name
        self.emoji = emoji
        self.minStage = minStage
    }
}

extension MascotCatalog {

    public static let defaultLooks: [Species: [DefaultLook]] = [
        .unicorn: [
            DefaultLook(category: .color, slot: U.body, value: "#F5ECFF", name: "Corps lilas"),
            DefaultLook(category: .color, slot: U.horn, value: "#FFD54F", name: "Corne dorée", minStage: 2),
            DefaultLook(category: .color, slot: U.mane, value: "#BA9EE8", name: "Crinière parme"),
            DefaultLook(category: .color, slot: U.tail, value: "#F49AC2", name: "Queue rose"),
            DefaultLook(category: .style, slot: US.tail, value: "straight", name: "Queue lisse", emoji: "〰️"),
            DefaultLook(category: .style, slot: US.horn, value: "smooth", name: "Corne lisse", emoji: "🔺", minStage: 2),
        ],
        .cat: [
            DefaultLook(category: .color, slot: CA.body, value: "#F6A96B", name: "Pelage roux"),
            DefaultLook(category: .color, slot: CA.belly, value: "#FFF3E4", name: "Ventre crème", minStage: 1),
            DefaultLook(category: .color, slot: CA.tail, value: "#F6A96B", name: "Queue assortie", minStage: 1),
            DefaultLook(category: .style, slot: CS.hair, value: "short", name: "Poil court", emoji: "🐱"),
            DefaultLook(category: .style, slot: CS.tail, value: "long", name: "Grande queue", emoji: "🐈", minStage: 1),
        ],
        .fox: [
            DefaultLook(category: .color, slot: FO.body, value: "#FF8A65", name: "Pelage roux"),
            DefaultLook(category: .color, slot: FO.belly, value: "#FFFFFF", name: "Ventre blanc"),
            DefaultLook(category: .color, slot: FO.tailTip, value: "#FFFFFF", name: "Bout blanc"),
            DefaultLook(category: .style, slot: FS.fur, value: "plain", name: "Pelage uni", emoji: "🟠"),
            DefaultLook(category: .style, slot: FS.tail, value: "long", name: "Grande queue", emoji: "🦊"),
        ],
        .rabbit: [
            DefaultLook(category: .color, slot: RA.body, value: "#F6EFE3", name: "Pelage ivoire"),
            DefaultLook(category: .color, slot: RA.inner, value: "#D9CCEE", name: "Oreilles lavande", minStage: 3),
            DefaultLook(category: .color, slot: RA.belly, value: "#FFFFFF", name: "Ventre blanc"),
            DefaultLook(category: .style, slot: RS.ear, value: "hautes", name: "Oreilles hautes", emoji: "🐇", minStage: 2),
            DefaultLook(category: .style, slot: RS.tail, value: "pompon", name: "Queue pompon", emoji: "⚪"),
            DefaultLook(category: .style, slot: RS.fur, value: "uni", name: "Pelage uni", emoji: "🤍"),
        ],
        .dragon: [
            DefaultLook(category: .color, slot: DR.body, value: "#7DB874", name: "Écailles vertes"),
            DefaultLook(category: .color, slot: DR.belly, value: "#E9DFB2", name: "Ventre sable", minStage: 1),
            DefaultLook(category: .color, slot: DR.wing, value: "#E2694F", name: "Ailes braise", minStage: 3),
            DefaultLook(category: .color, slot: DR.horn, value: "#EDE3CE", name: "Cornes ivoire", minStage: 2),
            DefaultLook(category: .style, slot: DS.horn, value: "straight", name: "Cornes droites", emoji: "🔺", minStage: 2),
            DefaultLook(category: .style, slot: DS.crest, value: "charbon", name: "Crête charbon", emoji: "🪨", minStage: 4),
            DefaultLook(category: .style, slot: DS.tail, value: "spade", name: "Queue flèche", emoji: "🏹", minStage: 1),
        ],
    ]
}
