package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.mascot.CATALOG
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* The shop's behaviour, against `src/shop/Shop.tsx` + `Shop.test.tsx`.         */
/*                                                                             */
/* Everything below drives `ShopModel` — a plain class — and asserts against    */
/* the real `ProfileStore`, so "the shop spent 40 points" means the counters    */
/* moved, not that a view said so. No composition anywhere (A11).              */
/* -------------------------------------------------------------------------- */

class ShopHarnessTest {

    @Test
    fun `six distinct first-clears of a difficulty-1 exercise pay 6 x 10 = 60`() {
        val h = ShopHarness()
        assertEquals(60, h.store.profile.balance)
        assertEquals(60, h.store.profile.stars.earned["test-device"])
        assertNull(h.store.profile.stars.spent["test-device"])
        // The unicorn is the default mascot, which is what every fixture below
        // names its options against.
        assertEquals("unicorn", h.store.profile.config.species.wire)
    }
}

// --- Try-on before buy (Shop.test.tsx, block 1) -----------------------------

class ShopTryOnTest {

    @Test
    fun `trying on opens the dialog but spends nothing`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)

        assertEquals(curly, h.model.cart)
        assertTrue(h.model.cartAffordable)
        assertEquals(60, h.store.profile.balance)
        assertTrue(h.store.profile.config.styles.isEmpty(), "nothing equipped yet")
        assertTrue(h.store.profile.owned.isEmpty())
        assertTrue(h.surface(curly).trying)
        assertEquals(1, h.audio.pops)
        assertEquals(listOf("Ça coûte 40 étoiles."), h.audio.spoken)
    }

    @Test
    fun `the buy button spends, equips, and closes the dialog`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)
        h.model.confirmBuy()

        assertEquals("curly", h.store.profile.config.styles["tailStyle"])
        assertEquals(listOf(curly.id), h.store.profile.owned)
        assertEquals(20, h.store.profile.balance, "60 - 40")
        assertNull(h.model.cart)
        assertEquals(listOf("Ça coûte 40 étoiles.", "C'est à toi !"), h.audio.spoken)
    }

    @Test
    fun `an unaffordable item can be tried on but not bought`() {
        val h = ShopHarness()
        val ring = h.option("Bouée") // 75 pts > 60

        h.model.tapItem(ring)

        assertEquals(ring, h.model.cart)
        assertFalse(h.model.cartAffordable)
        assertEquals(
            listOf("Ça coûte 75 étoiles. Il te manque des étoiles."),
            h.audio.spoken,
        )

        // Fail-open at the model too: a forced confirm is a quiet no-op with
        // the dialog still up — never a wedge, never a spend (invariant 3).
        h.model.confirmBuy()
        assertEquals(ring, h.model.cart)
        assertEquals(60, h.store.profile.balance)
        assertTrue(h.store.profile.owned.isEmpty())
    }

    @Test
    fun `the cross cancels a try-on and leaves the wallet alone`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)
        h.model.cancelTryOn()

        assertNull(h.model.cart)
        assertEquals(60, h.store.profile.balance)
        assertTrue(h.store.profile.owned.isEmpty())
        assertFalse(h.surface(curly).trying)
    }

    @Test
    fun `the spoken price line pluralises like the TSX`() {
        val h = ShopHarness()
        // « Ça coûte N étoile(s). » comes from :core's VO, and the shop only
        // appends the "not enough" clause. Both shapes, once.
        h.model.tapItem(h.option("Nœud")) // 45, affordable at 60
        assertEquals(listOf("Ça coûte 45 étoiles."), h.audio.spoken)
    }
}

// --- The spend path (invariants 8 and 9) ------------------------------------

class ShopSpendPathTest {

    @Test
    fun `a purchase lands in the spent counter — points leave, none appear`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)
        h.model.confirmBuy()

        assertEquals(40, h.store.profile.stars.spent["test-device"])
        assertEquals(60, h.store.profile.stars.earned["test-device"], "earned is untouched by a spend")
        assertEquals(20, h.store.profile.balance)
    }

    @Test
    fun `a purchase is an LWW cosmetic write, stamped — invariant 9`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")
        val before = h.store.roster.children.first().profile.species.getValue(h.store.profile.current).rev

        h.model.tapItem(curly)
        h.model.confirmBuy()

        val after = h.store.roster.children.first().profile.species.getValue(h.store.profile.current).rev
        assertTrue(after != before, "the config write must carry a fresh Rev, not a counter")
        assertEquals("test-device", after.by)
        // And the STARS did not become a bare total on the way through.
        assertEquals(mapOf("test-device" to 40), h.store.profile.stars.spent)
    }

    @Test
    fun `tapping buy twice fast cannot spend twice`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)
        h.model.confirmBuy()
        h.model.confirmBuy() // the cart is already empty — a quiet no-op

        assertEquals(20, h.store.profile.balance)
        assertEquals(listOf(curly.id), h.store.profile.owned, "owned exactly once")
        assertEquals(40, h.store.profile.stars.spent["test-device"])
    }

    @Test
    fun `buying with exactly enough succeeds and leaves zero`() {
        val h = ShopHarness()
        val swim = h.option("Maillot de bain") // 60 = the balance

        h.model.tapItem(swim)
        assertTrue(h.model.cartAffordable, "balance >= cost is affordable, not >")
        assertEquals(
            listOf("Ça coûte 60 étoiles."),
            h.audio.spoken,
            "no « il te manque » clause at exactly enough",
        )

        h.model.confirmBuy()
        assertEquals(0, h.store.profile.balance)
        assertEquals(listOf(swim.id), h.store.profile.owned)
        assertNull(h.model.cart)
    }

    @Test
    fun `buying one short is a quiet, spend-free no-op that never wedges`() {
        val h = ShopHarness()
        h.store.spend(1) // 59 left
        val swim = h.option("Maillot de bain") // costs 60

        h.model.tapItem(swim)
        assertFalse(h.model.cartAffordable)

        h.model.confirmBuy()
        assertEquals(59, h.store.profile.balance)
        assertTrue(h.store.profile.owned.isEmpty())
        assertEquals(swim, h.model.cart, "the dialog stays open — the cross still leads out")

        h.model.cancelTryOn()
        assertNull(h.model.cart, "and the child can always leave")
    }

    @Test
    fun `nothing in the shop ever mints a point`() {
        val h = ShopHarness()
        val bow = h.option("Nœud") // 45-pt accessory
        val lilas = h.defaultLook("Corps lilas")
        val earnedBefore = h.store.profile.stars.earned

        h.model.tapItem(bow)
        h.model.confirmBuy() // spend 45
        h.model.tapItem(bow) // toggle the accessory off (free)
        h.model.tapItem(bow) // and back on (free — already owned)
        h.model.clearSlot(lilas) // factory look (free)

        assertEquals(earnedBefore, h.store.profile.stars.earned, "earned counters are read-only to the shop")
        assertEquals(15, h.store.profile.balance, "60 - 45, nothing else moved")
    }

    @Test
    fun `every price the shop can hand the store comes from the authored catalog`() {
        // Invariant 8's other half, and the guard on `:core`'s known hole: the
        // catalogue is the ONLY source of a cost this package can spend, and
        // every one of them is positive. A negative cost would CREDIT a wallet
        // through `spend`, which neither `useProfile.tsx` nor `ProfileStore`
        // rejects — it stays unreachable because of this table, not because of
        // a check.
        val h = ShopHarness()
        for (option in CATALOG) {
            assertTrue(option.cost > 0, "${option.id} is priced ${option.cost}")
        }
        // And the model has no other way in: buying is `store.buy(option)`,
        // which reads `option.cost` off the row it was handed.
        val ring = h.option("Bouée")
        h.model.tapItem(ring)
        h.model.confirmBuy() // refused, 60 < 75
        assertEquals(60, h.store.profile.balance)
    }
}

// --- Accessory toggle -------------------------------------------------------

class ShopAccessoryToggleTest {

    @Test
    fun `tapping a worn accessory takes it off — ownership and wallet survive`() {
        val h = ShopHarness()
        val bow = h.option("Nœud")

        h.model.tapItem(bow)
        h.model.confirmBuy()
        assertEquals(listOf(bow.id), h.store.profile.config.accessories)
        assertEquals(15, h.store.profile.balance)

        h.model.tapItem(bow) // off
        assertTrue(h.store.profile.config.accessories.isEmpty())
        assertEquals(listOf(bow.id), h.store.profile.owned, "taking it off never un-buys it")
        assertEquals(15, h.store.profile.balance)

        h.model.tapItem(bow) // back on, free
        assertEquals(listOf(bow.id), h.store.profile.config.accessories)
        assertEquals(15, h.store.profile.balance)
    }
}

// --- Factory looks ----------------------------------------------------------

class ShopFactoryLookTest {

    @Test
    fun `switches a style, then the named default look reverts it`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")
        val straight = h.defaultLook("Queue lisse")

        h.model.tapItem(curly)
        h.model.confirmBuy()
        assertTrue(h.surface(curly).equipped)
        assertFalse(DefaultLookSurface(straight, h.store.profile.config).active)

        h.model.clearSlot(straight)
        assertNull(h.store.profile.config.styles["tailStyle"])
        assertTrue(DefaultLookSurface(straight, h.store.profile.config).active)
        assertFalse(h.surface(curly).equipped)
        assertTrue(h.surface(curly).owned, "reverting never un-buys")
        assertEquals(20, h.store.profile.balance, "and it is free")
    }

    @Test
    fun `returns the body colour to its factory look`() {
        val h = ShopHarness()
        val rose = h.option("Corps rose")
        val lilas = h.defaultLook("Corps lilas")

        assertTrue(DefaultLookSurface(lilas, h.store.profile.config).active)

        h.model.tapItem(rose)
        h.model.confirmBuy()
        assertTrue(h.surface(rose).equipped)
        assertFalse(DefaultLookSurface(lilas, h.store.profile.config).active)

        h.model.clearSlot(lilas)
        assertNull(h.store.profile.config.colors["bodyColor"])
        assertTrue(DefaultLookSurface(lilas, h.store.profile.config).active)
        assertFalse(h.surface(rose).equipped)
    }

    @Test
    fun `clearing a slot also closes an open try-on`() {
        val h = ShopHarness()
        h.model.tapItem(h.option("Queue bouclée"))
        assertTrue(h.model.cart != null)

        h.model.clearSlot(h.defaultLook("Corps lilas"))
        assertNull(h.model.cart)
    }
}

// --- Growth (the headline spend) --------------------------------------------

/**
 * `performGrow` and the price curve are `GrowthCard.kt`'s (see the contract in
 * ShopView.kt's header); what is under test HERE is the shop's half — the order
 * the ceremony fires in, and that a refusal is silent.
 */
class ShopGrowthTest {

    @Test
    fun `growing spends the price and bumps the stage, in the web's order`() {
        val h = ShopHarness()

        h.model.grow()

        assertEquals(1, h.store.profile.config.stage)
        assertEquals(30, h.store.profile.balance, "60 - 30")
        assertEquals(30, h.store.profile.stars.spent["test-device"])
        assertEquals(listOf("grewFlight:30", "grewBurst"), h.celebration.events)
        assertEquals(listOf("Tu as grandi !"), h.audio.spoken)
    }

    @Test
    fun `growing one point short is refused and changes nothing`() {
        val h = ShopHarness()
        h.store.spend(31) // 29 left; the price is 30

        h.model.grow()

        assertEquals(0, h.store.profile.config.stage)
        assertEquals(29, h.store.profile.balance)
        assertEquals(emptyList<String>(), h.celebration.events)
        assertEquals(emptyList<String>(), h.audio.spoken)
    }

    @Test
    fun `growth never mints — earned counters are identical after a grow`() {
        val h = ShopHarness()
        val earnedBefore = h.store.profile.stars.earned
        h.model.grow()
        assertEquals(earnedBefore, h.store.profile.stars.earned)
    }
}

// --- The celebration order --------------------------------------------------

class ShopCelebrationTest {

    @Test
    fun `a purchase celebrates once, with the option that was bought`() {
        val h = ShopHarness()
        val curly = h.option("Queue bouclée")

        h.model.tapItem(curly)
        h.model.confirmBuy()

        assertEquals(listOf("purchased:${curly.id}"), h.celebration.events)
    }

    @Test
    fun `a refused purchase celebrates nothing`() {
        val h = ShopHarness()
        h.model.tapItem(h.option("Bouée"))
        h.model.confirmBuy()

        assertEquals(emptyList<String>(), h.celebration.events)
    }

    @Test
    fun `equipping, toggling and reverting all pop the preview`() {
        val h = ShopHarness()
        val bow = h.option("Nœud")

        h.model.tapItem(bow)
        h.model.confirmBuy()
        h.celebration.events.clear()

        h.model.tapItem(bow) // off
        h.model.tapItem(bow) // on
        h.model.clearSlot(h.defaultLook("Corps lilas"))

        assertEquals(listOf("equipped", "equipped", "equipped"), h.celebration.events)
    }

    @Test
    fun `the default celebration is no confetti and never a stall`() {
        val h = ShopHarness()
        val plain = ShopModel(
            store = h.store,
            audio = h.audio,
            kv = h.kv,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val curly = h.option("Queue bouclée")

        plain.tapItem(curly)
        plain.confirmBuy()

        assertEquals(20, h.store.profile.balance, "the purchase still lands with no particles")
        assertNull(plain.cart)
    }
}

// --- Shop-seen (the meters' animate-from point) -----------------------------

class ShopSeenTest {

    @Test
    fun `sinceBalance is captured once, from what this child last saw`() {
        val h = ShopHarness()
        val childId = h.store.activeId!!
        ProfileStorage.saveShopSeen(mapOf(childId to 25, "other-child" to 7), h.kv)

        assertEquals(25, h.newModel().sinceBalance)
    }

    @Test
    fun `a first visit animates from zero`() {
        assertEquals(0, ShopHarness().model.sinceBalance)
    }

    @Test
    fun `recordSeen writes the balance under the active child, preserving siblings`() {
        val h = ShopHarness()
        val childId = h.store.activeId!!
        ProfileStorage.saveShopSeen(mapOf("other-child" to 7), h.kv)

        h.model.recordSeen()

        val seen = ProfileStorage.loadShopSeen(h.kv)
        assertEquals(60, seen[childId])
        assertEquals(7, seen["other-child"], "a sibling's entry survives")
    }

    @Test
    fun `no active child, no write`() {
        val h = ShopHarness()
        h.store.switchChild()
        h.model.recordSeen()
        assertEquals(emptyMap<String, Int>(), ProfileStorage.loadShopSeen(h.kv))
    }

    @Test
    fun `shop-seen is cosmetic — it is never read back as money`() {
        val h = ShopHarness()
        val childId = h.store.activeId!!
        ProfileStorage.saveShopSeen(mapOf(childId to 9_999), h.kv)

        val model = h.newModel()
        assertEquals(9_999, model.sinceBalance)
        // A wildly wrong "seen" value moves the meter's start point and nothing
        // else: the wallet is still the counters' fold.
        assertEquals(60, h.store.profile.balance)
        assertFalse(model.cartAffordable, "and it buys nothing on its own")
    }
}
