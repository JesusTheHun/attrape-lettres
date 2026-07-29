import Testing

@testable import ALCore

// Port of `src/mascot/catalog.test.ts` (4 cases), plus the id-stability suite
// spec/mascot.md §12 asks for.
//
// Provenance of the oracles below: the whole port was checked once, mechanically,
// against a JSON dump produced by running the REAL TypeScript through
// esbuild + node — all 78 rows, every id, slot, value, French name, emoji, cost
// and gate, plus DEFAULT_LOOKS. Zero divergences. The tables here are the part
// of that run worth keeping in the repo; they exist so a future *edit* is caught,
// which is what the TS suite was for too.

private let species: [Species] = [.unicorn, .cat, .fox, .rabbit, .dragon]

/* Growth gates derived from a per-stage visual render of every rig: an option is
 * gated only when the body part it dresses isn't visible yet at that stade
 * (e.g. the unicorn is hornless until stade 2, the kitten's belly/tail are
 * tucked in its stade-0 curl). Anything absent from this map must be ungated —
 * this pins the analysis so a catalog edit can't silently regress it. */
private let expectedGates: [String: Int] = [
    // Unicorn horn: nub at stade 2, real (twistable) horn at stade 3.
    "unicorn.color.hornColor.rose": 2,
    "unicorn.color.hornColor.turquoise": 2,
    "unicorn.style.hornStyle.spiral": 3,
    // Cat belly + tail: hidden in the stade-0 curl, shown once it sits up (1).
    "cat.color.bellyColor.rose": 1,
    "cat.color.tailColor.roux": 1,
    "cat.color.tailColor.noire": 1,
    "cat.style.tailSize.short": 1,
    // Rabbit inner ears: the tint only "blooms" at stade 3; the ear fold can't
    // show while the ears lie on the back (stades 0-1); the swimsuit is worn
    // standing only (the lying nappy read as a backpack in the blind test).
    "rabbit.color.innerEarColor.rose": 3,
    "rabbit.color.innerEarColor.menthe": 3,
    "rabbit.style.earStyle.pliees": 2,
    "rabbit.accessory.swimsuit": 2,
    // Dragon: the belly is hidden inside the stade-0 egg; horn nubs point at 2
    // (real horns at 3 for the double crown); wings sprout at 3; the crest
    // appears at 4; the tail pokes out of the egg but only reads from 1; the
    // flame tail lights when he walks (2); cape/fang need him standing (2);
    // goggles arrive with the wings (3).
    "dragon.color.bellyColor.magma": 1,
    "dragon.color.wingColor.nuit": 3,
    "dragon.color.wingColor.dorees": 3,
    "dragon.color.hornColor.or": 2,
    "dragon.color.hornColor.noires": 2,
    "dragon.style.hornStyle.double": 3,
    "dragon.style.crestStyle.lava": 4,
    "dragon.style.tailStyle.club": 1,
    "dragon.style.tailStyle.flame": 2,
    "dragon.accessory.cape": 2,
    "dragon.accessory.goggles": 3,
    "dragon.accessory.fang-necklace": 2,
    // Premium accessories gated by maturity.
    "unicorn.accessory.flower-crown": 2,
    "unicorn.accessory.star-clip": 4,
    "cat.accessory.party-hat": 4,
    "fox.accessory.boots": 4,
    "rabbit.accessory.stardust": 4,
    "dragon.accessory.blue-flame": 4,
]

@Suite("catalog growth gates")
struct MascotCatalogGateTests {

    @Test("gates exactly the options whose part isn't visible early")
    func gatesExactlyTheOptionsWhosePartIsntVisibleEarly() {
        for o in MascotCatalog.catalog {
            #expect((o.minStage ?? 0) == (expectedGates[o.id] ?? 0), "\(o.id) minStage")
        }
    }

    @Test("only references gates within the growth range")
    func onlyReferencesGatesWithinTheGrowthRange() {
        for o in MascotCatalog.catalog {
            if let minStage = o.minStage {
                #expect(minStage >= 0)
                #expect(minStage < 10)
                // The TS says `< 10`; growthStages is where that 10 comes from.
                #expect(minStage < growthStages)
            }
        }
    }

    // Proves the gate table above is not vacuously satisfied by an empty catalog
    // or by a catalog where nothing is gated: 29 ids carry a gate and the map has
    // no entry for an id that left the catalog.
    //
    // NB: spec/mascot.md §8.5 says "28 gated ids". Counted against the real
    // `catalog.ts` it is 29 — the spec is off by one, not the port. Left as a
    // number here rather than corrected in a doc this package does not own.
    @Test("the gate table describes the catalog that exists")
    func gateTableIsLive() {
        let ids = Set(MascotCatalog.catalog.map(\.id))
        #expect(expectedGates.count == 29)
        #expect(Set(expectedGates.keys).isSubset(of: ids))
        #expect(MascotCatalog.catalog.filter { $0.minStage != nil }.count == 29)
    }
}

@Suite("catalog default looks")
struct MascotCatalogDefaultLookTests {

    @Test("provides exactly one factory look per colour/style slot")
    func oneFactoryLookPerSlot() {
        for sp in species {
            let variantSlots = Set(
                MascotCatalog.catalog
                    .filter { $0.species == sp && ($0.category == .color || $0.category == .style) }
                    .map { "\($0.category.rawValue):\($0.slot)" }
            )
            let defaultSlots = MascotCatalog.defaultLooks[sp]!.map { "\($0.category.rawValue):\($0.slot)" }
            // No duplicates, and the two sets match exactly.
            #expect(Set(defaultSlots).count == defaultSlots.count, "\(sp) duplicate default slot")
            #expect(Set(defaultSlots) == variantSlots, "\(sp) default slots")
        }
    }

    @Test("gates each default no later than its slot's variants")
    func gatesEachDefaultNoLaterThanItsVariants() {
        for sp in species {
            for look in MascotCatalog.defaultLooks[sp]! {
                let variantMins = MascotCatalog.catalog
                    .filter { $0.species == sp && $0.category == look.category && $0.slot == look.slot }
                    .map { $0.minStage ?? 0 }
                let variantMin = variantMins.min()!
                let defMin = look.minStage ?? 0
                #expect(defMin <= variantMin, "\(sp) \(look.slot)")
                // Gated variants ⇒ gated default (never advertise a look for a hidden part).
                #expect((defMin > 0) == (variantMin > 0), "\(sp) \(look.slot)")
            }
        }
    }

    /// The Swift-only half of the type widening: `DefaultLook.category` is
    /// `CustomizationCategory`, which can spell `.accessory`; the TS union
    /// cannot. Nothing may take that third case.
    @Test("no factory look is an accessory")
    func noFactoryLookIsAnAccessory() {
        for sp in species {
            for look in MascotCatalog.defaultLooks[sp]! {
                #expect(look.category != .accessory, "\(sp) \(look.slot)")
            }
        }
        #expect(MascotCatalog.defaultLooks.count == species.count)
    }
}

@Suite("mascot ids are a persistence contract (spec/mascot.md §12)")
struct MascotIDStabilityTests {

    /// Every id the shop can write into `MascotConfig.accessories`, and every
    /// catalog row id, exactly as they are on disk today.
    ///
    /// This list is deliberately hand-written rather than derived from
    /// `MascotCatalog.catalog` — a derived list would agree with any rename and
    /// could not fail. If a row is added, add its id here on purpose.
    private static let frozenCatalogIDs: [String] = [
        "unicorn.color.bodyColor.rose",
        "unicorn.color.bodyColor.ciel",
        "unicorn.color.bodyColor.menthe",
        "unicorn.color.hornColor.rose",
        "unicorn.color.hornColor.turquoise",
        "unicorn.color.maneColor.corail",
        "unicorn.color.maneColor.turquoise",
        "unicorn.color.tailColor.menthe",
        "unicorn.style.tailStyle.curly",
        "unicorn.style.hornStyle.spiral",
        "unicorn.accessory.ribbon",
        "unicorn.accessory.flower-crown",
        "unicorn.accessory.star-clip",
        "unicorn.accessory.swimsuit",
        "unicorn.accessory.swim-ring",
        "cat.color.bodyColor.gris",
        "cat.color.bodyColor.blanc",
        "cat.color.bodyColor.noir",
        "cat.color.bellyColor.rose",
        "cat.color.tailColor.roux",
        "cat.color.bodyColor.creme",
        "cat.color.bodyColor.lilas",
        "cat.color.tailColor.noire",
        "cat.style.hair.fluffy",
        "cat.style.tailSize.short",
        "cat.accessory.bow",
        "cat.accessory.bell-collar",
        "cat.accessory.party-hat",
        "cat.accessory.swimsuit",
        "cat.accessory.swim-ring",
        "fox.color.bodyColor.roux",
        "fox.color.bodyColor.miel",
        "fox.color.bodyColor.cendre",
        "fox.color.bellyColor.creme",
        "fox.color.tailTipColor.brun",
        "fox.color.tailTipColor.dore",
        "fox.color.bodyColor.arctique",
        "fox.style.furPattern.spots",
        "fox.style.furPattern.stripes",
        "fox.style.tailSize.short",
        "fox.accessory.scarf",
        "fox.accessory.beanie",
        "fox.accessory.boots",
        "fox.accessory.swimsuit",
        "fox.accessory.swim-ring",
        "rabbit.color.bodyColor.souris",
        "rabbit.color.bodyColor.caramel",
        "rabbit.color.bodyColor.peche",
        "rabbit.color.bodyColor.lilas",
        "rabbit.color.innerEarColor.rose",
        "rabbit.color.innerEarColor.menthe",
        "rabbit.color.bellyColor.creme",
        "rabbit.style.earStyle.pliees",
        "rabbit.style.tailStyle.etoile",
        "rabbit.style.furPattern.flocons",
        "rabbit.accessory.bow",
        "rabbit.accessory.nightcap",
        "rabbit.accessory.stardust",
        "rabbit.accessory.swimsuit",
        "rabbit.accessory.swim-ring",
        "dragon.color.bodyColor.braise",
        "dragon.color.bodyColor.charbon",
        "dragon.color.bodyColor.nuit",
        "dragon.color.bodyColor.terre",
        "dragon.color.bellyColor.magma",
        "dragon.color.wingColor.nuit",
        "dragon.color.wingColor.dorees",
        "dragon.color.hornColor.or",
        "dragon.color.hornColor.noires",
        "dragon.style.hornStyle.double",
        "dragon.style.crestStyle.lava",
        "dragon.style.tailStyle.club",
        "dragon.style.tailStyle.flame",
        "dragon.accessory.cape",
        "dragon.accessory.goggles",
        "dragon.accessory.fang-necklace",
        "dragon.accessory.treasure",
        "dragon.accessory.blue-flame",
    ]

    @Test("every catalog id is byte-identical to the shipped web build, in order")
    func catalogIDsAreFrozen() {
        #expect(MascotCatalog.catalog.map(\.id) == Self.frozenCatalogIDs)
        #expect(MascotCatalog.catalog.count == 78)
        #expect(Set(MascotCatalog.catalog.map(\.id)).count == 78, "ids must be globally unique")
    }

    /// The config KEYS. These land in `MascotConfig.colors` / `.styles` on disk
    /// and on the sync wire; a rename here orphans a profile exactly the way a
    /// renamed `ExerciseId` raw value would (D11).
    @Test("every colour and style slot key is frozen")
    func slotKeysAreFrozen() {
        #expect(ColorSlot.Unicorn.body == "bodyColor")
        #expect(ColorSlot.Unicorn.horn == "hornColor")
        #expect(ColorSlot.Unicorn.mane == "maneColor")
        #expect(ColorSlot.Unicorn.tail == "tailColor")
        #expect(ColorSlot.Cat.body == "bodyColor")
        #expect(ColorSlot.Cat.belly == "bellyColor")
        #expect(ColorSlot.Cat.tail == "tailColor")
        #expect(ColorSlot.Fox.body == "bodyColor")
        #expect(ColorSlot.Fox.belly == "bellyColor")
        #expect(ColorSlot.Fox.tailTip == "tailTipColor")
        #expect(ColorSlot.Rabbit.body == "bodyColor")
        #expect(ColorSlot.Rabbit.belly == "bellyColor")
        #expect(ColorSlot.Rabbit.inner == "innerEarColor")
        #expect(ColorSlot.Dragon.body == "bodyColor")
        #expect(ColorSlot.Dragon.belly == "bellyColor")
        #expect(ColorSlot.Dragon.wing == "wingColor")
        #expect(ColorSlot.Dragon.horn == "hornColor")

        #expect(StyleSlot.Unicorn.tail == "tailStyle")
        #expect(StyleSlot.Unicorn.horn == "hornStyle")
        #expect(StyleSlot.Cat.hair == "hair")
        #expect(StyleSlot.Cat.tail == "tailSize")
        #expect(StyleSlot.Fox.fur == "furPattern")
        #expect(StyleSlot.Fox.tail == "tailSize")
        #expect(StyleSlot.Rabbit.ear == "earStyle")
        #expect(StyleSlot.Rabbit.tail == "tailStyle")
        #expect(StyleSlot.Rabbit.fur == "furPattern")
        #expect(StyleSlot.Dragon.horn == "hornStyle")
        #expect(StyleSlot.Dragon.crest == "crestStyle")
        #expect(StyleSlot.Dragon.tail == "tailStyle")
    }

    @Test("every accessory id is frozen, and the dragon still has no swim pair")
    func accessoryIDsAreFrozen() {
        #expect(Accessory.Unicorn.ribbon == "unicorn.accessory.ribbon")
        #expect(Accessory.Unicorn.flowerCrown == "unicorn.accessory.flower-crown")
        #expect(Accessory.Unicorn.starClip == "unicorn.accessory.star-clip")
        #expect(Accessory.Unicorn.swimsuit == "unicorn.accessory.swimsuit")
        #expect(Accessory.Unicorn.swimRing == "unicorn.accessory.swim-ring")
        #expect(Accessory.Cat.bow == "cat.accessory.bow")
        #expect(Accessory.Cat.bellCollar == "cat.accessory.bell-collar")
        #expect(Accessory.Cat.partyHat == "cat.accessory.party-hat")
        #expect(Accessory.Cat.swimsuit == "cat.accessory.swimsuit")
        #expect(Accessory.Cat.swimRing == "cat.accessory.swim-ring")
        #expect(Accessory.Fox.scarf == "fox.accessory.scarf")
        #expect(Accessory.Fox.beanie == "fox.accessory.beanie")
        #expect(Accessory.Fox.boots == "fox.accessory.boots")
        #expect(Accessory.Fox.swimsuit == "fox.accessory.swimsuit")
        #expect(Accessory.Fox.swimRing == "fox.accessory.swim-ring")
        #expect(Accessory.Rabbit.bow == "rabbit.accessory.bow")
        #expect(Accessory.Rabbit.nightcap == "rabbit.accessory.nightcap")
        #expect(Accessory.Rabbit.stardust == "rabbit.accessory.stardust")
        #expect(Accessory.Rabbit.swimsuit == "rabbit.accessory.swimsuit")
        #expect(Accessory.Rabbit.swimRing == "rabbit.accessory.swim-ring")
        #expect(Accessory.Dragon.cape == "dragon.accessory.cape")
        #expect(Accessory.Dragon.goggles == "dragon.accessory.goggles")
        #expect(Accessory.Dragon.fang == "dragon.accessory.fang-necklace")
        #expect(Accessory.Dragon.treasure == "dragon.accessory.treasure")
        #expect(Accessory.Dragon.blueFlame == "dragon.accessory.blue-flame")

        // The cross-species swim pair is deliberately absent on the dragon
        // (wardrobe v2, user decision). "Fixing" it would add two buyable ids.
        let dragonIDs = Set(MascotCatalog.catalog.filter { $0.species == .dragon }.map(\.id))
        #expect(!dragonIDs.contains("dragon.accessory.swimsuit"))
        #expect(!dragonIDs.contains("dragon.accessory.swim-ring"))
    }

    /// The shop's whole economy in one line, and the sanity band the file header
    /// documents: colours 15–30, styles 30–60, accessories 40–150, one premium
    /// per species = 200.
    @Test("costs stay in their authored bands, one premium per species")
    func costBands() {
        for o in MascotCatalog.catalog {
            switch o.category {
            case .color: #expect((15...30).contains(o.cost), "\(o.id) \(o.cost)")
            case .style: #expect((30...60).contains(o.cost), "\(o.id) \(o.cost)")
            case .accessory: #expect((40...200).contains(o.cost), "\(o.id) \(o.cost)")
            }
        }
        for sp in species {
            let premium = MascotCatalog.catalog.filter { $0.species == sp && $0.cost == 200 }
            #expect(premium.count == 1, "\(sp) premium count")
            #expect(premium[0].minStage == 4, "\(sp) premium gate")
            #expect(premium[0].category == .accessory, "\(sp) premium category")
        }
    }

    /// The French copy is spoken (`VO.shopCostLine`) and read; the accents and
    /// the ligature are not decoration. Spot-checked against the TS byte for byte.
    @Test("French names keep their accents, ligature and ASCII apostrophes")
    func frenchCopyIsIntact() {
        func name(_ id: String) -> String? { MascotCatalog.catalog.first { $0.id == id }?.name }
        #expect(name("unicorn.accessory.ribbon") == "Nœud")  // U+0153, not "Noeud"
        #expect(name("rabbit.accessory.bow") == "Nœud étoilé")
        #expect(name("rabbit.accessory.stardust") == "Poussière d'étoiles")  // U+0027
        #expect(name("dragon.accessory.goggles") == "Lunettes d'aviateur")  // U+0027
        #expect(name("dragon.color.hornColor.or") == "Cornes d'or")  // U+0027
        #expect(name("dragon.color.bodyColor.braise") == "Écailles rouge braise")
        #expect(name("unicorn.accessory.star-clip") == "Arc-en-ciel magique")
        #expect(name("fox.accessory.scarf") == "Écharpe")
        #expect(name("rabbit.style.furPattern.flocons") == "Flocons d'étoiles")
        // No name may contain the typographic apostrophe — the web build uses the
        // ASCII one throughout the catalog, and these strings are compared, not
        // rendered.
        for o in MascotCatalog.catalog {
            #expect(!o.name.contains("\u{2019}"), "\(o.id) has a curly apostrophe")
        }
    }
}
