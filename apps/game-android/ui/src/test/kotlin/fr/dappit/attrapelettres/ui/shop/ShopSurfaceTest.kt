package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.CATALOG
import fr.dappit.attrapelettres.core.mascot.DEFAULT_LOOKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* The shop's PURE surfaces, against `src/shop/Shop.tsx` + `ShopItem.tsx`.      */
/*                                                                             */
/* Every expected value here is derived FROM THE TYPESCRIPT — the catalog       */
/* prices, the flight-size clamp, the wallet-count easing, the French state     */
/* labels — and never read back out of the Kotlin under test. A test that asks  */
/* the code what it does proves nothing.                                       */
/*                                                                             */
/* Nothing in this file composes (A11): every value below is produced by a      */
/* plain function or a plain class, which is the whole reason the shop's        */
/* decisions live outside its composables.                                     */
/* -------------------------------------------------------------------------- */

private fun unicorn(name: String) =
    CATALOG.first { it.species == Species.UNICORN && it.name == name }

private fun unicornLook(name: String) =
    DEFAULT_LOOKS.getValue(Species.UNICORN).first { it.name == name }

private fun config(
    stage: Int = 0,
    colors: Map<String, String> = emptyMap(),
    styles: Map<String, String> = emptyMap(),
    accessories: List<String> = emptyList(),
) = MascotConfig(
    species = Species.UNICORN,
    stage = stage,
    colors = colors,
    styles = styles,
    accessories = accessories,
)

// --- Flight size ------------------------------------------------------------

class ShopFlightSizeTest {

    @Test
    fun `min(8, max(3, round(cost slash 10))) with JS rounding`() {
        assertEquals(3, shopFlightSize(10))
        // 2.2 rounds to 2, then clamps up to the floor of 3.
        assertEquals(3, shopFlightSize(22))
        assertEquals(3, shopFlightSize(30))
        assertEquals(4, shopFlightSize(40))
        // Math.round(4.5) = 5 — half toward +infinity, not "round half even".
        assertEquals(5, shopFlightSize(45))
        assertEquals(6, shopFlightSize(60))
        assertEquals(7, shopFlightSize(70))
        assertEquals(8, shopFlightSize(75))
        assertEquals(8, shopFlightSize(200), "capped at 8")
    }

    @Test
    fun `every catalog price yields a flock between three and eight`() {
        for (option in CATALOG) {
            val stars = shopFlightSize(option.cost)
            assertTrue(stars in 3..8, "${option.id} costs ${option.cost} and flies $stars stars")
        }
    }
}

// --- The wallet count -------------------------------------------------------

class WalletCountTest {

    @Test
    fun `duration is min(900, 350 + delta times 12) ms`() {
        assertEquals(830L, WalletCount(60, 20).durationMillis)
        assertEquals(362L, WalletCount(60, 59).durationMillis)
        assertEquals(900L, WalletCount(0, 100).durationMillis, "capped at 900 ms")
    }

    @Test
    fun `the count eases with 1 minus (1 minus k) squared and lands exactly`() {
        val count = WalletCount(60, 20)
        assertEquals(60, count.valueAt(0))
        assertEquals(20, count.valueAt(count.durationMillis))
        // k = 0.5 -> eased = 0.75 -> 60 - 40 * 0.75 = 30.
        assertEquals(30, count.valueAt(count.durationMillis / 2))
        // Past the end it clamps, exactly as the rAF loop stops at k = 1.
        assertEquals(20, count.valueAt(count.durationMillis * 3))
    }

    @Test
    fun `an unchanged value does not animate`() {
        assertFalse(WalletCount(42, 42).animates)
        assertTrue(WalletCount(42, 41).animates)
    }

    @Test
    fun `a count that goes UP is the same curve — earning is watched too`() {
        val count = WalletCount(0, 40)
        assertEquals(0, count.valueAt(0))
        assertEquals(30, count.valueAt(count.durationMillis / 2))
        assertEquals(40, count.valueAt(count.durationMillis))
    }
}

// --- Zone grouping ----------------------------------------------------------

class ShopZoneTest {

    private val fresh = shopProfile()

    @Test
    fun `a fresh unicorn's store — body parts first, accessories pooled last`() {
        val groups = shopStoreGroups(fresh)
        assertEquals(listOf("Corps", "Corne", "Crinière", "Queue", "Accessoires"), groups.map { it.label })
        assertEquals(listOf(3, 3, 2, 2, 5), groups.map { it.tiles.size })
    }

    @Test
    fun `a fresh wardrobe holds exactly the factory looks`() {
        val groups = shopArmoireGroups(fresh)
        assertEquals(listOf("Corps", "Corne", "Crinière", "Queue"), groups.map { it.label })
        assertEquals(listOf(1, 2, 1, 2), groups.map { it.tiles.size })
        for (tile in groups.flatMap { it.tiles }) {
            assertTrue(tile is ShopTile.Factory, "a fresh wardrobe listed a bought item: ${tile.id}")
        }
    }

    @Test
    fun `a bought item moves from the store to the wardrobe`() {
        val curly = unicorn("Queue bouclée")
        val owned = shopProfile(owned = listOf(curly.id))

        assertTrue(shopStoreGroups(fresh).flatMap { it.tiles }.any { it.id == curly.id })
        assertFalse(shopArmoireGroups(fresh).flatMap { it.tiles }.any { it.id == curly.id })

        assertFalse(shopStoreGroups(owned).flatMap { it.tiles }.any { it.id == curly.id })
        assertTrue(shopArmoireGroups(owned).flatMap { it.tiles }.any { it.id == curly.id })
        // Only « Queue menthe » is left for sale under that heading.
        assertEquals(1, shopStoreGroups(owned).first { it.label == "Queue" }.tiles.size)
    }

    @Test
    fun `a factory tile's id is the TSX React key`() {
        val straight = unicornLook("Queue lisse")
        assertEquals("default.style.tailStyle", ShopTile.Factory(straight).id)
        assertEquals(unicorn("Nœud").id, ShopTile.Item(unicorn("Nœud")).id)
    }

    @Test
    fun `grouping keeps FIRST-SEEN label order and never reorders within a label`() {
        val a = ShopTile.Item(unicorn("Corps rose"))
        val b = ShopTile.Item(unicorn("Nœud"))
        val c = ShopTile.Item(unicorn("Corps menthe"))
        val groups = shopGrouped(listOf("Corps" to a, "Accessoires" to b, "Corps" to c))
        assertEquals(listOf("Corps", "Accessoires"), groups.map { it.label })
        assertEquals(listOf(a.id, c.id), groups[0].tiles.map { it.id })
    }

    @Test
    fun `slot labels are the authored map, with the raw key as the fallback`() {
        assertEquals("Corps", shopSlotLabel("bodyColor", CustomizationCategory.COLOR))
        assertEquals("Bout de queue", shopSlotLabel("tailTipColor", CustomizationCategory.COLOR))
        assertEquals("Pelage", shopSlotLabel("furPattern", CustomizationCategory.STYLE))
        // An accessory has no slot at all; the label ignores whatever is passed.
        assertEquals("Accessoires", shopSlotLabel("anything", CustomizationCategory.ACCESSORY))
        assertEquals("nopeSlot", shopSlotLabel("nopeSlot", CustomizationCategory.COLOR))
    }
}

// --- Copy coverage ----------------------------------------------------------

/**
 * The test the last wave was missing.
 *
 * `Copy.Shop.groupLabel` falls back to the RAW SLOT KEY when the French is
 * absent, exactly as `SLOT_LABEL[slot] ?? slot` does — so a catalogue row whose
 * slot nobody translated ships a heading like « innerEarColor » above a group of
 * tiles, in English, in a French reading game, and NOTHING fails. This walks
 * `:core`'s two authored tables and closes that hole.
 */
class ShopCopyCoverageTest {

    private val slots: List<Pair<String, String>> =
        CATALOG
            .filter { it.category != CustomizationCategory.ACCESSORY }
            .map { it.slot to it.id } +
            DEFAULT_LOOKS.entries.flatMap { (species, looks) ->
                looks.map { it.slot to "${species.wire}.default.${it.slot}" }
            }

    @Test
    fun `every colour and style slot in core has French in Copy`() {
        val missing = slots
            .filter { (slot, _) -> shopSlotLabel(slot, CustomizationCategory.COLOR) == slot }
            .map { (slot, owner) -> "$slot (first seen on $owner)" }
            .distinct()
        assertEquals(
            emptyList<String>(),
            missing,
            "these slots would head a shop group with their raw config key",
        )
    }

    @Test
    fun `no slot label is empty, and none is the key it labels`() {
        for ((slot, _) in slots) {
            val label = shopSlotLabel(slot, CustomizationCategory.COLOR)
            assertTrue(label.isNotBlank(), "$slot has a blank label")
            assertTrue(label != slot, "$slot fell back to its raw key")
        }
    }

    @Test
    fun `every catalog option can render a full tile label without a placeholder`() {
        // Not a French dictionary — the NAME comes from `:core`'s catalogue and
        // the STATE from Copy; this asserts the join produces one sentence for
        // every row in the app, at every state a tile can be in.
        for (option in CATALOG) {
            val profile = shopProfile(species = option.species)
            for (cart in listOf(null, option.id)) {
                val surface = ShopItemSurface(option, profile, cart)
                assertTrue(
                    surface.contentDescription.startsWith("${option.name},"),
                    "${option.id} announced « ${surface.contentDescription} »",
                )
                assertTrue(surface.stateLabel.isNotBlank(), "${option.id} has no state label")
            }
        }
    }

    @Test
    fun `every factory look can render a full tile label`() {
        for ((species, looks) in DEFAULT_LOOKS) {
            for (look in looks) {
                val surface = DefaultLookSurface(
                    look,
                    MascotConfig(species, 0, emptyMap(), emptyMap(), emptyList()),
                )
                assertTrue(
                    surface.contentDescription.startsWith("${look.name},"),
                    "$species ${look.slot} announced « ${surface.contentDescription} »",
                )
                assertTrue(surface.badge.isNotBlank())
            }
        }
    }

    @Test
    fun `every option's group lands in a labelled bucket, for every species`() {
        for (species in Species.entries) {
            val profile = shopProfile(species = species)
            val groups = shopStoreGroups(profile) + shopArmoireGroups(profile)
            assertTrue(groups.isNotEmpty(), "$species has an empty shop")
            for (group in groups) {
                assertTrue(group.tiles.isNotEmpty(), "$species has an empty group « ${group.label} »")
                // A raw config key is ASCII camelCase; every authored heading is
                // a French word. This is the cheap shape check on top of the
                // exact one above.
                assertTrue(
                    group.label.first().isUpperCase(),
                    "$species group « ${group.label} » looks like a raw config key",
                )
            }
        }
    }
}

// --- The buyable tile's state ----------------------------------------------

class ShopItemSurfaceTest {

    @Test
    fun `an affordable unowned item shows the gold price tag`() {
        val curly = unicorn("Queue bouclée")
        val s = ShopItemSurface(curly, shopProfile(balance = 60), cartId = null)

        assertFalse(s.owned)
        assertFalse(s.equipped)
        assertFalse(s.locked)
        assertFalse(s.trying)
        assertTrue(s.affordable)
        assertEquals("Queue bouclée, coûte 40 points", s.contentDescription)
        assertEquals(ShopPricePanel.Affordable("⭐ 40"), s.pricePanel)
        assertEquals(ShopSticker.None, s.sticker)
        assertFalse(s.showsMeter)
    }

    @Test
    fun `an unaffordable item shows the grey tag and the savings meter`() {
        val ring = unicorn("Bouée") // 75 pts
        val s = ShopItemSurface(ring, shopProfile(balance = 60), cartId = null)

        assertEquals("Bouée, coûte 75 points, pas encore assez", s.contentDescription)
        assertEquals(ShopPricePanel.Unaffordable("⭐ 75"), s.pricePanel)
        assertTrue(s.showsMeter)
    }

    @Test
    fun `exactly enough is affordable — the boundary is greater-or-equal`() {
        val swim = unicorn("Maillot de bain") // 60 pts
        assertTrue(ShopItemSurface(swim, shopProfile(balance = 60), null).affordable)
        assertFalse(ShopItemSurface(swim, shopProfile(balance = 59), null).affordable)
    }

    @Test
    fun `a growth-gated item is locked with the seedling chip — minStage 2 reads level 3`() {
        val horn = unicorn("Corne rose") // minStage 2, cost 20
        val s = ShopItemSurface(horn, shopProfile(balance = 60), cartId = null)

        assertTrue(s.locked)
        assertEquals("Corne rose, coûte 20 points, à débloquer au niveau 3", s.contentDescription)
        assertEquals(ShopPricePanel.Locked("🌱 niv. 3 · ⭐ 20"), s.pricePanel)
    }

    @Test
    fun `the tile in the cart announces the try-on`() {
        val curly = unicorn("Queue bouclée")
        val s = ShopItemSurface(curly, shopProfile(balance = 60), cartId = curly.id)

        assertTrue(s.trying)
        assertEquals("Queue bouclée, coûte 40 points, en train d'essayer", s.contentDescription)
    }

    @Test
    fun `a worn style is equipped with a filled check and no price`() {
        val curly = unicorn("Queue bouclée")
        val profile = shopProfile(
            balance = 20,
            owned = listOf(curly.id),
            config = config(styles = mapOf("tailStyle" to "curly")),
        )
        val s = ShopItemSurface(curly, profile, cartId = null)

        assertEquals("Queue bouclée, équipé", s.contentDescription)
        assertEquals(ShopSticker.Check(filled = true), s.sticker)
        assertEquals(ShopPricePanel.None, s.pricePanel, "a bought item never shows a number")
        assertFalse(s.removable, "a style is changed by picking another, not by tapping it off")
    }

    @Test
    fun `an owned colour that is not worn is « à toi » with a white check`() {
        val rose = unicorn("Corps rose")
        val profile = shopProfile(
            owned = listOf(rose.id),
            config = config(colors = mapOf("bodyColor" to "#DCEFFB")),
        )
        val s = ShopItemSurface(rose, profile, cartId = null)

        assertTrue(s.owned)
        assertFalse(s.equipped)
        assertEquals("Corps rose, à toi", s.contentDescription)
        assertEquals(ShopSticker.Check(filled = false), s.sticker)
    }

    @Test
    fun `a worn accessory warns that a tap takes it off — the cross sticker`() {
        val bow = unicorn("Nœud")
        val profile = shopProfile(
            owned = listOf(bow.id),
            config = config(accessories = listOf(bow.id)),
        )
        val s = ShopItemSurface(bow, profile, cartId = null)

        assertTrue(s.removable)
        assertEquals("Nœud, équipé, appuie pour enlever", s.contentDescription)
        assertEquals(ShopSticker.Remove, s.sticker)
    }

    @Test
    fun `owned beats every price state — an owned item is never a price tag`() {
        val ring = unicorn("Bouée") // 75, dearer than the wallet
        val s = ShopItemSurface(ring, shopProfile(balance = 0, owned = listOf(ring.id)), null)

        assertTrue(s.affordable, "re-equipping what you own is free")
        assertEquals(ShopPricePanel.None, s.pricePanel)
        assertFalse(s.showsMeter)
    }
}

// --- The factory-look tile's state ------------------------------------------

class DefaultLookSurfaceTest {

    @Test
    fun `the factory look starts « en place » and there is no reset jargon`() {
        val s = DefaultLookSurface(unicornLook("Queue lisse"), config())

        assertTrue(s.active)
        assertEquals("Équipé ✓", s.badge)
        assertEquals("Queue lisse, en place", s.contentDescription)
    }

    @Test
    fun `writing the slot makes the factory look « à toi » again`() {
        val s = DefaultLookSurface(
            unicornLook("Queue lisse"),
            config(styles = mapOf("tailStyle" to "curly")),
        )

        assertFalse(s.active)
        assertEquals("À toi", s.badge)
        assertEquals("Queue lisse, à toi", s.contentDescription)
    }

    @Test
    fun `a default for a not-yet-grown part stays gated like its variants`() {
        val horn = unicornLook("Corne dorée") // minStage 2
        val s = DefaultLookSurface(horn, config(stage = 0))

        assertTrue(s.locked)
        assertEquals("🌱 niv. 3", s.badge)
        assertEquals("Corne dorée, à débloquer au niveau 3", s.contentDescription)

        assertFalse(DefaultLookSurface(horn, config(stage = 2)).locked)
    }
}

// --- The try-on dialog ------------------------------------------------------

class TryOnSurfaceTest {

    @Test
    fun `the affordable dialog carries the TSX labels`() {
        val s = TryOnSurface(unicorn("Queue bouclée"), balance = 60)

        assertEquals("Essayer Queue bouclée", s.title)
        assertTrue(s.affordable)
        assertEquals("Acheter · ⭐ 40", s.buyLabel)
        assertEquals("Acheter Queue bouclée pour 40 étoiles", s.buyContentDescription)
        assertFalse(s.showsMeter)
    }

    @Test
    fun `the short dialog says « pas encore » and shows the gap meter`() {
        val s = TryOnSurface(unicorn("Bouée"), balance = 60)

        assertFalse(s.affordable)
        assertEquals("⭐ 75 · pas encore", s.buyLabel)
        assertEquals("Pas encore assez d'étoiles pour Bouée", s.buyContentDescription)
        assertTrue(s.showsMeter, "the meter is the answer to « how much more? », with no maths")
    }

    @Test
    fun `every catalog row can open a dialog, priced and labelled`() {
        for (option in CATALOG) {
            val poor = TryOnSurface(option, balance = 0)
            val rich = TryOnSurface(option, balance = 10_000)
            assertFalse(poor.affordable)
            assertTrue(rich.affordable)
            assertTrue(poor.buyLabel.contains(option.cost.toString()))
            assertTrue(rich.buyLabel.contains(option.cost.toString()))
            assertTrue(rich.title.isNotEmpty())
        }
    }
}
