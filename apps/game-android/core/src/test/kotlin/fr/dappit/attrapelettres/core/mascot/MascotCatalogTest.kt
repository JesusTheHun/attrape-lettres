package fr.dappit.attrapelettres.core.mascot

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A port of `src/mascot/catalog.test.ts` (4 cases), plus the id-stability suite
 * the iOS port added (`MascotCatalogTests.swift`).
 *
 * Provenance of the oracles below: the iOS port was checked once, mechanically,
 * against a JSON dump produced by running the REAL TypeScript through
 * esbuild + node — all 78 rows, every id, slot, value, French name, emoji, cost
 * and gate, plus DEFAULT_LOOKS. Zero divergences. This file carries the same
 * tables so a future *edit* is caught, which is what the TS suite was for too.
 */
class MascotCatalogTest {

    /* Growth gates derived from a per-stage visual render of every rig: an option is
     * gated only when the body part it dresses isn't visible yet at that stade
     * (e.g. the unicorn is hornless until stade 2, the kitten's belly/tail are
     * tucked in its stade-0 curl). Anything absent from this map must be ungated —
     * this pins the analysis so a catalog edit can't silently regress it. */
    private val expectedGates: Map<String, Int> = mapOf(
        // Unicorn horn: nub at stade 2, real (twistable) horn at stade 3.
        "unicorn.color.hornColor.rose" to 2,
        "unicorn.color.hornColor.turquoise" to 2,
        "unicorn.style.hornStyle.spiral" to 3,
        // Cat belly + tail: hidden in the stade-0 curl, shown once it sits up (1).
        "cat.color.bellyColor.rose" to 1,
        "cat.color.tailColor.roux" to 1,
        "cat.color.tailColor.noire" to 1,
        "cat.style.tailSize.short" to 1,
        // Rabbit inner ears: the tint only "blooms" at stade 3; the ear fold can't
        // show while the ears lie on the back (stades 0-1); the swimsuit is worn
        // standing only (the lying nappy read as a backpack in the blind test).
        "rabbit.color.innerEarColor.rose" to 3,
        "rabbit.color.innerEarColor.menthe" to 3,
        "rabbit.style.earStyle.pliees" to 2,
        "rabbit.accessory.swimsuit" to 2,
        // Dragon: the belly is hidden inside the stade-0 egg; horn nubs point at 2
        // (real horns at 3 for the double crown); wings sprout at 3; the crest
        // appears at 4; the tail pokes out of the egg but only reads from 1; the
        // flame tail lights when he walks (2); cape/fang need him standing (2);
        // goggles arrive with the wings (3).
        "dragon.color.bellyColor.magma" to 1,
        "dragon.color.wingColor.nuit" to 3,
        "dragon.color.wingColor.dorees" to 3,
        "dragon.color.hornColor.or" to 2,
        "dragon.color.hornColor.noires" to 2,
        "dragon.style.hornStyle.double" to 3,
        "dragon.style.crestStyle.lava" to 4,
        "dragon.style.tailStyle.club" to 1,
        "dragon.style.tailStyle.flame" to 2,
        "dragon.accessory.cape" to 2,
        "dragon.accessory.goggles" to 3,
        "dragon.accessory.fang-necklace" to 2,
        // Premium accessories gated by maturity.
        "unicorn.accessory.flower-crown" to 2,
        "unicorn.accessory.star-clip" to 4,
        "cat.accessory.party-hat" to 4,
        "fox.accessory.boots" to 4,
        "rabbit.accessory.stardust" to 4,
        "dragon.accessory.blue-flame" to 4,
    )

    @Test
    fun `gates exactly the options whose part isn't visible early`() {
        for (o in CATALOG) {
            assertEquals(expectedGates[o.id] ?: 0, o.minStage ?: 0, "${o.id} minStage")
        }
    }

    @Test
    fun `only references gates within the growth range`() {
        for (o in CATALOG) {
            val minStage = o.minStage ?: continue
            assertTrue(minStage >= 0, o.id)
            // The TS says `< 10`; GROWTH_STAGES is where that 10 comes from.
            assertTrue(minStage < GROWTH_STAGES, o.id)
        }
    }

    @Test
    fun `the gate table describes the catalog that exists`() {
        // Proves the gate table above is not vacuously satisfied by an empty
        // catalog or by a catalog where nothing is gated: 29 ids carry a gate and
        // the map has no entry for an id that left the catalog.
        val ids = CATALOG.map { it.id }.toSet()
        assertEquals(29, expectedGates.size)
        assertTrue(ids.containsAll(expectedGates.keys))
        assertEquals(29, CATALOG.count { it.minStage != null })
    }

    @Test
    fun `provides exactly one factory look per colour or style slot`() {
        for (species in Species.entries) {
            val variantSlots = CATALOG
                .filter { it.species == species && (it.category == CustomizationCategory.COLOR || it.category == CustomizationCategory.STYLE) }
                .map { "${it.category.wire}:${it.slot}" }
                .toSet()
            val defaultSlots = DEFAULT_LOOKS.getValue(species).map { "${it.category.wire}:${it.slot}" }
            // No duplicates, and the two sets match exactly.
            assertEquals(defaultSlots.size, defaultSlots.toSet().size, "$species duplicate default slot")
            assertEquals(variantSlots, defaultSlots.toSet(), "$species default slots")
        }
    }

    @Test
    fun `gates each default no later than its slot's variants`() {
        for (species in Species.entries) {
            for (look in DEFAULT_LOOKS.getValue(species)) {
                val variantMin = CATALOG
                    .filter { it.species == species && it.category == look.category && it.slot == look.slot }
                    .minOf { it.minStage ?: 0 }
                val defMin = look.minStage ?: 0
                assertTrue(defMin <= variantMin, "$species ${look.slot}")
                // Gated variants ⇒ gated default (never advertise a look for a hidden part).
                assertEquals(variantMin > 0, defMin > 0, "$species ${look.slot}")
            }
        }
    }

    @Test
    fun `no factory look is an accessory`() {
        // The Kotlin-only half of the type widening: `DefaultLook.category` is
        // `CustomizationCategory`, which can spell ACCESSORY; the TS union
        // cannot. Nothing may take that third case.
        for (species in Species.entries) {
            for (look in DEFAULT_LOOKS.getValue(species)) {
                assertNotEquals(CustomizationCategory.ACCESSORY, look.category, "$species ${look.slot}")
            }
        }
        assertEquals(Species.entries.size, DEFAULT_LOOKS.size)
    }

    /* Mascot ids are a persistence contract ---------------------------------- */

    /**
     * Every id the shop can write into `MascotConfig.accessories`, and every
     * catalog row id, exactly as they are on disk today.
     *
     * This list is deliberately hand-written rather than derived from `CATALOG` —
     * a derived list would agree with any rename and could not fail. If a row is
     * added, add its id here on purpose.
     */
    private val frozenCatalogIds: List<String> = listOf(
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
    )

    @Test
    fun `every catalog id is byte-identical to the shipped web build, in order`() {
        assertEquals(frozenCatalogIds, CATALOG.map { it.id })
        assertEquals(78, CATALOG.size)
        assertEquals(78, CATALOG.map { it.id }.toSet().size, "ids must be globally unique")
    }

    @Test
    fun `every colour and style slot key is frozen`() {
        // The config KEYS. These land in `MascotConfig.colors` / `.styles` on
        // disk and on the sync wire; a rename here orphans a profile exactly the
        // way a renamed `ExerciseId` wire value would (A2).
        assertEquals("bodyColor", ColorSlot.Unicorn.body)
        assertEquals("hornColor", ColorSlot.Unicorn.horn)
        assertEquals("maneColor", ColorSlot.Unicorn.mane)
        assertEquals("tailColor", ColorSlot.Unicorn.tail)
        assertEquals("bodyColor", ColorSlot.Cat.body)
        assertEquals("bellyColor", ColorSlot.Cat.belly)
        assertEquals("tailColor", ColorSlot.Cat.tail)
        assertEquals("bodyColor", ColorSlot.Fox.body)
        assertEquals("bellyColor", ColorSlot.Fox.belly)
        assertEquals("tailTipColor", ColorSlot.Fox.tailTip)
        assertEquals("bodyColor", ColorSlot.Rabbit.body)
        assertEquals("bellyColor", ColorSlot.Rabbit.belly)
        assertEquals("innerEarColor", ColorSlot.Rabbit.inner)
        assertEquals("bodyColor", ColorSlot.Dragon.body)
        assertEquals("bellyColor", ColorSlot.Dragon.belly)
        assertEquals("wingColor", ColorSlot.Dragon.wing)
        assertEquals("hornColor", ColorSlot.Dragon.horn)

        assertEquals("tailStyle", StyleSlot.Unicorn.tail)
        assertEquals("hornStyle", StyleSlot.Unicorn.horn)
        assertEquals("hair", StyleSlot.Cat.hair)
        assertEquals("tailSize", StyleSlot.Cat.tail)
        assertEquals("furPattern", StyleSlot.Fox.fur)
        assertEquals("tailSize", StyleSlot.Fox.tail)
        assertEquals("earStyle", StyleSlot.Rabbit.ear)
        assertEquals("tailStyle", StyleSlot.Rabbit.tail)
        assertEquals("furPattern", StyleSlot.Rabbit.fur)
        assertEquals("hornStyle", StyleSlot.Dragon.horn)
        assertEquals("crestStyle", StyleSlot.Dragon.crest)
        assertEquals("tailStyle", StyleSlot.Dragon.tail)
    }

    @Test
    fun `every accessory id is frozen, and the dragon still has no swim pair`() {
        assertEquals("unicorn.accessory.ribbon", Accessory.Unicorn.ribbon)
        assertEquals("unicorn.accessory.flower-crown", Accessory.Unicorn.flowerCrown)
        assertEquals("unicorn.accessory.star-clip", Accessory.Unicorn.starClip)
        assertEquals("unicorn.accessory.swimsuit", Accessory.Unicorn.swimsuit)
        assertEquals("unicorn.accessory.swim-ring", Accessory.Unicorn.swimRing)
        assertEquals("cat.accessory.bow", Accessory.Cat.bow)
        assertEquals("cat.accessory.bell-collar", Accessory.Cat.bellCollar)
        assertEquals("cat.accessory.party-hat", Accessory.Cat.partyHat)
        assertEquals("cat.accessory.swimsuit", Accessory.Cat.swimsuit)
        assertEquals("cat.accessory.swim-ring", Accessory.Cat.swimRing)
        assertEquals("fox.accessory.scarf", Accessory.Fox.scarf)
        assertEquals("fox.accessory.beanie", Accessory.Fox.beanie)
        assertEquals("fox.accessory.boots", Accessory.Fox.boots)
        assertEquals("fox.accessory.swimsuit", Accessory.Fox.swimsuit)
        assertEquals("fox.accessory.swim-ring", Accessory.Fox.swimRing)
        assertEquals("rabbit.accessory.bow", Accessory.Rabbit.bow)
        assertEquals("rabbit.accessory.nightcap", Accessory.Rabbit.nightcap)
        assertEquals("rabbit.accessory.stardust", Accessory.Rabbit.stardust)
        assertEquals("rabbit.accessory.swimsuit", Accessory.Rabbit.swimsuit)
        assertEquals("rabbit.accessory.swim-ring", Accessory.Rabbit.swimRing)
        assertEquals("dragon.accessory.cape", Accessory.Dragon.cape)
        assertEquals("dragon.accessory.goggles", Accessory.Dragon.goggles)
        assertEquals("dragon.accessory.fang-necklace", Accessory.Dragon.fang)
        assertEquals("dragon.accessory.treasure", Accessory.Dragon.treasure)
        assertEquals("dragon.accessory.blue-flame", Accessory.Dragon.blueFlame)

        // The cross-species swim pair is deliberately absent on the dragon
        // (wardrobe v2, user decision). "Fixing" it would add two buyable ids.
        val dragonIds = CATALOG.filter { it.species == Species.DRAGON }.map { it.id }.toSet()
        assertFalse("dragon.accessory.swimsuit" in dragonIds)
        assertFalse("dragon.accessory.swim-ring" in dragonIds)
    }

    @Test
    fun `costs stay in their authored bands, one premium per species`() {
        // The shop's whole economy in one loop, and the sanity band the file
        // header documents: colours 15–30, styles 30–60, accessories 40–150, one
        // premium per species = 200.
        for (o in CATALOG) {
            when (o.category) {
                CustomizationCategory.COLOR -> assertTrue(o.cost in 15..30, "${o.id} ${o.cost}")
                CustomizationCategory.STYLE -> assertTrue(o.cost in 30..60, "${o.id} ${o.cost}")
                CustomizationCategory.ACCESSORY -> assertTrue(o.cost in 40..200, "${o.id} ${o.cost}")
            }
        }
        for (species in Species.entries) {
            val premium = CATALOG.filter { it.species == species && it.cost == 200 }
            assertEquals(1, premium.size, "$species premium count")
            assertEquals(4, premium[0].minStage, "$species premium gate")
            assertEquals(CustomizationCategory.ACCESSORY, premium[0].category, "$species premium category")
        }
    }

    @Test
    fun `French names keep their accents, ligature and ASCII apostrophes`() {
        // The French copy is spoken (the shop cost line) and read; the accents
        // and the ligature are not decoration. Spot-checked against the TS byte
        // for byte.
        fun name(id: String): String? = CATALOG.firstOrNull { it.id == id }?.name
        assertEquals("Nœud", name("unicorn.accessory.ribbon")) // U+0153, not "Noeud"
        assertEquals("Nœud étoilé", name("rabbit.accessory.bow"))
        assertEquals("Poussière d'étoiles", name("rabbit.accessory.stardust")) // U+0027
        assertEquals("Lunettes d'aviateur", name("dragon.accessory.goggles")) // U+0027
        assertEquals("Cornes d'or", name("dragon.color.hornColor.or")) // U+0027
        assertEquals("Écailles rouge braise", name("dragon.color.bodyColor.braise"))
        assertEquals("Arc-en-ciel magique", name("unicorn.accessory.star-clip"))
        assertEquals("Écharpe", name("fox.accessory.scarf"))
        assertEquals("Flocons d'étoiles", name("rabbit.style.furPattern.flocons"))
        // No name may contain the typographic apostrophe — the web build uses the
        // ASCII one throughout the catalog, and these strings are compared, not
        // rendered.
        for (o in CATALOG) {
            assertFalse("’" in o.name, "${o.id} has a curly apostrophe")
        }
    }
}
