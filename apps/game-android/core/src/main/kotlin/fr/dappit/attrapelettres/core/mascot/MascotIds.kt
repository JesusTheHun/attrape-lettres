package fr.dappit.attrapelettres.core.mascot

// Port of `src/mascot/ids.ts`.
//
// This lives in :core, not :art: the shop (:ui), the persisted profile
// (persistence/) and the rigs (:art) all read these strings.
//
// EVERY STRING IN THIS FILE IS A PERSISTENCE CONTRACT. The slot names land in
// `MascotConfig.colors[slot]` / `.styles[slot]` and the accessory ids land in
// `MascotConfig.accessories`, both of which are written to disk and pushed
// across the sync wire. Renaming one "for Kotlin style" orphans every existing
// profile at migration time — exactly the rule A2 froze for `ExerciseId` wire
// values. The Kotlin *identifiers* below may be idiomatic; the *string values*
// may not change. `MascotCatalogTest` pins all 54 of them — 17 colour slots,
// 12 style slots, 25 accessory ids — against a hand-written oracle so a rename
// fails loudly.

/**
 * Single source of truth for the config keys + option ids the rig reads.
 * Both Mascot.tsx (reader) and catalog.ts (writer) import from here so the
 * slot / value / accessory-id strings can never drift apart.
 *
 * Owned by AGENT A.
 */
// NB: the TS is one nested `as const` object per kind, keyed by species. Kotlin
// gets one nested object per (kind, species) because the species do not share a
// slot set — the unicorn has a horn and a mane, the fox has a tail tip. A
// `Map<Species, Map<String, String>>` would compile but would turn
// `COLOR_SLOT.unicorn.body`, a typo the TS compiler catches, into a runtime null.

/** Colour config slots per species — written to config.colors[slot]. */
object ColorSlot {
    object Unicorn {
        const val body = "bodyColor"
        const val horn = "hornColor"
        const val mane = "maneColor"
        const val tail = "tailColor"
    }

    object Cat {
        const val body = "bodyColor"
        const val belly = "bellyColor"
        const val tail = "tailColor"
    }

    object Fox {
        const val body = "bodyColor"
        const val belly = "bellyColor"
        const val tailTip = "tailTipColor"
    }

    object Rabbit {
        const val body = "bodyColor"
        const val belly = "bellyColor"
        const val inner = "innerEarColor"
    }

    object Dragon {
        const val body = "bodyColor"
        const val belly = "bellyColor"
        const val wing = "wingColor"
        const val horn = "hornColor"
    }
}

/** Style config slots per species — written to config.styles[slot]. */
object StyleSlot {
    object Unicorn {
        const val tail = "tailStyle"
        const val horn = "hornStyle"
    }

    object Cat {
        const val hair = "hair"
        const val tail = "tailSize"
    }

    object Fox {
        const val fur = "furPattern"
        const val tail = "tailSize"
    }

    object Rabbit {
        const val ear = "earStyle"
        const val tail = "tailStyle"
        const val fur = "furPattern"
    }

    object Dragon {
        const val horn = "hornStyle"
        const val crest = "crestStyle"
        const val tail = "tailStyle"
    }
}

/** Accessory option ids — matched verbatim against config.accessories. */
object Accessory {
    object Unicorn {
        const val ribbon = "unicorn.accessory.ribbon"
        const val flowerCrown = "unicorn.accessory.flower-crown"
        const val starClip = "unicorn.accessory.star-clip"
        const val swimsuit = "unicorn.accessory.swimsuit"
        const val swimRing = "unicorn.accessory.swim-ring"
    }

    object Cat {
        const val bow = "cat.accessory.bow"
        const val bellCollar = "cat.accessory.bell-collar"
        const val partyHat = "cat.accessory.party-hat"
        const val swimsuit = "cat.accessory.swimsuit"
        const val swimRing = "cat.accessory.swim-ring"
    }

    object Fox {
        const val scarf = "fox.accessory.scarf"
        const val beanie = "fox.accessory.beanie"
        const val boots = "fox.accessory.boots"
        const val swimsuit = "fox.accessory.swimsuit"
        const val swimRing = "fox.accessory.swim-ring"
    }

    object Rabbit {
        const val bow = "rabbit.accessory.bow"
        const val nightcap = "rabbit.accessory.nightcap"
        const val stardust = "rabbit.accessory.stardust"
        const val swimsuit = "rabbit.accessory.swimsuit"
        const val swimRing = "rabbit.accessory.swim-ring"
    }

    // No swim pair on the dragon — the cross-species tradition is deliberately
    // broken here (user decision, wardrobe v2): a fire dragon doesn't bathe.
    object Dragon {
        const val cape = "dragon.accessory.cape"
        const val goggles = "dragon.accessory.goggles"
        const val fang = "dragon.accessory.fang-necklace"
        const val treasure = "dragon.accessory.treasure"
        const val blueFlame = "dragon.accessory.blue-flame"
    }
}
