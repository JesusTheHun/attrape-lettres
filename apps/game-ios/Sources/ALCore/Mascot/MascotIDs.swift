// Port of `src/mascot/ids.ts`.
//
// D8 — this lives in ALCore, not ALArt: the shop (ALUI), the persisted profile
// (Persistence) and the rigs (ALArt) all read these strings.
//
// spec/mascot.md §12 — EVERY STRING IN THIS FILE IS A PERSISTENCE CONTRACT.
// The slot names land in `MascotConfig.colors[slot]` / `.styles[slot]` and the
// accessory ids land in `MascotConfig.accessories`, both of which are written to
// disk and pushed across the sync wire. Renaming one "for Swift style" orphans
// every existing profile at migration time — exactly the rule D11 froze for
// `ExerciseId` raw values. The Swift *identifiers* below may be idiomatic; the
// *string values* may not change. `MascotIDStabilityTests` pins all 54 of them
// — 17 colour slots, 12 style slots, 25 accessory ids — against a hand-written
// oracle so a rename fails loudly.

/**
 * Single source of truth for the config keys + option ids the rig reads.
 * Both Mascot.tsx (reader) and catalog.ts (writer) import from here so the
 * slot / value / accessory-id strings can never drift apart.
 *
 * Owned by AGENT A.
 */
// NB: the TS is one nested `as const` object per kind, keyed by species. Swift
// gets one caseless enum per (kind, species) because the species do not share a
// slot set — the unicorn has a horn and a mane, the fox has a tail tip. A
// `[Species: [String: String]]` dictionary would compile but would turn
// `COLOR_SLOT.unicorn.body`, a typo the TS compiler catches, into a runtime nil.

/// Colour config slots per species — written to config.colors[slot].
public enum ColorSlot {
    public enum Unicorn {
        public static let body = "bodyColor"
        public static let horn = "hornColor"
        public static let mane = "maneColor"
        public static let tail = "tailColor"
    }
    public enum Cat {
        public static let body = "bodyColor"
        public static let belly = "bellyColor"
        public static let tail = "tailColor"
    }
    public enum Fox {
        public static let body = "bodyColor"
        public static let belly = "bellyColor"
        public static let tailTip = "tailTipColor"
    }
    public enum Rabbit {
        public static let body = "bodyColor"
        public static let belly = "bellyColor"
        public static let inner = "innerEarColor"
    }
    public enum Dragon {
        public static let body = "bodyColor"
        public static let belly = "bellyColor"
        public static let wing = "wingColor"
        public static let horn = "hornColor"
    }
}

/// Style config slots per species — written to config.styles[slot].
public enum StyleSlot {
    public enum Unicorn {
        public static let tail = "tailStyle"
        public static let horn = "hornStyle"
    }
    public enum Cat {
        public static let hair = "hair"
        public static let tail = "tailSize"
    }
    public enum Fox {
        public static let fur = "furPattern"
        public static let tail = "tailSize"
    }
    public enum Rabbit {
        public static let ear = "earStyle"
        public static let tail = "tailStyle"
        public static let fur = "furPattern"
    }
    public enum Dragon {
        public static let horn = "hornStyle"
        public static let crest = "crestStyle"
        public static let tail = "tailStyle"
    }
}

/// Accessory option ids — matched verbatim against config.accessories.
public enum Accessory {
    public enum Unicorn {
        public static let ribbon = "unicorn.accessory.ribbon"
        public static let flowerCrown = "unicorn.accessory.flower-crown"
        public static let starClip = "unicorn.accessory.star-clip"
        public static let swimsuit = "unicorn.accessory.swimsuit"
        public static let swimRing = "unicorn.accessory.swim-ring"
    }
    public enum Cat {
        public static let bow = "cat.accessory.bow"
        public static let bellCollar = "cat.accessory.bell-collar"
        public static let partyHat = "cat.accessory.party-hat"
        public static let swimsuit = "cat.accessory.swimsuit"
        public static let swimRing = "cat.accessory.swim-ring"
    }
    public enum Fox {
        public static let scarf = "fox.accessory.scarf"
        public static let beanie = "fox.accessory.beanie"
        public static let boots = "fox.accessory.boots"
        public static let swimsuit = "fox.accessory.swimsuit"
        public static let swimRing = "fox.accessory.swim-ring"
    }
    public enum Rabbit {
        public static let bow = "rabbit.accessory.bow"
        public static let nightcap = "rabbit.accessory.nightcap"
        public static let stardust = "rabbit.accessory.stardust"
        public static let swimsuit = "rabbit.accessory.swimsuit"
        public static let swimRing = "rabbit.accessory.swim-ring"
    }
    // No swim pair on the dragon — the cross-species tradition is deliberately
    // broken here (user decision, wardrobe v2): a fire dragon doesn't bathe.
    public enum Dragon {
        public static let cape = "dragon.accessory.cape"
        public static let goggles = "dragon.accessory.goggles"
        public static let fang = "dragon.accessory.fang-necklace"
        public static let treasure = "dragon.accessory.treasure"
        public static let blueFlame = "dragon.accessory.blue-flame"
    }
}
