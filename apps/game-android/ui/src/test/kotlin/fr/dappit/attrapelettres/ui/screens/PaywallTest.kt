package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.domain.AppRoute
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.licensing.Entitlement
import fr.dappit.attrapelettres.core.licensing.UNLOCK_PRICE_EUR
import fr.dappit.attrapelettres.core.licensing.canPlay
import fr.dappit.attrapelettres.ui.design.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

// End of the trial, tested as data. Nothing here composes (A11).
//
// Two claims dominate, and both are legal:
//
//   KIDS CATEGORY 1.3 — no purchase may sit in front of a child. `PaywallLayer`
//   makes that assertable: the composable renders from it field by field, so a
//   test over `texts` is a test over the screen.
//
//   INVARIANT 11 — money never fails closed. This screen SELLS and never GATES,
//   which is checked twice: against every entitlement state through the router's
//   own `openOutcome`, and structurally by scanning this file for the reads it
//   must not contain.

private val PRICE = Copy.fallbackPriceLabel(UNLOCK_PRICE_EUR)

class PaywallLayerTest {

    @Test
    fun `the child layer names no price, no currency and no purchase control`() {
        val layer = paywallLayer(PaywallStep.CHILD, PRICE, storeAvailable = true)
        for (text in layer.texts) {
            assertFalse(text.contains(PRICE), "the child sees a price: « $text »")
            assertFalse(text.contains("€"), "the child sees a currency: « $text »")
            assertFalse(text.contains("9,99"), "the child sees the amount: « $text »")
            assertFalse(text.contains("9.99"), "the child sees the amount: « $text »")
            assertFalse(
                text.contains(Copy.Paywall.Parent.RESTORE),
                "the child sees a store control: « $text »",
            )
        }
        assertFalse(layer.texts.contains(Copy.Paywall.Parent.TITLE))
    }

    @Test
    fun `the gate layer names no price either — the door itself is priceless`() {
        val layer = paywallLayer(PaywallStep.GATE, PRICE, storeAvailable = true)
        for (text in layer.texts) {
            assertFalse(text.contains(PRICE), "the gate shows a price: « $text »")
            assertFalse(text.contains("€"), "the gate shows a currency: « $text »")
        }
    }

    /**
     * The whole Kids-Category claim in one loop: over every step, and both with
     * and without a store, the price appears if and only if the adult has passed
     * the gate.
     */
    @Test
    fun `no price string is reachable without passing the gate`() {
        for (step in PaywallStep.entries) {
            for (storeAvailable in listOf(true, false)) {
                for (busy in listOf(true, false)) {
                    val layer = paywallLayer(step, PRICE, storeAvailable, busy)
                    val shows = layer.texts.any { it.contains(PRICE) }
                    // Behind the gate, with a store, and not mid-purchase (the
                    // busy label is « … »): the price is on screen. Everywhere
                    // else it is not.
                    val expected = step == PaywallStep.PARENT
                    assertEquals(
                        expected,
                        shows,
                        "step=$step store=$storeAvailable busy=$busy showed=$shows",
                    )
                }
            }
        }
    }

    @Test
    fun `the parent layer carries the price in the body and on the button`() {
        val layer = paywallLayer(PaywallStep.PARENT, PRICE, storeAvailable = true)
            as PaywallLayer.Parent
        assertEquals(Copy.Paywall.Parent.body(PRICE), layer.body)
        assertEquals(Copy.Paywall.Parent.buy(PRICE), layer.primary)
        assertEquals(Copy.Paywall.Parent.RESTORE, layer.restore)
        assertNull(layer.noStore)
    }

    @Test
    fun `busy swaps the button for the ellipsis and nothing else`() {
        val layer = paywallLayer(PaywallStep.PARENT, PRICE, storeAvailable = true, busy = true)
            as PaywallLayer.Parent
        assertEquals(Copy.Paywall.Parent.BUSY, layer.primary)
        assertEquals("…", layer.primary, "the busy label must be ONE character, U+2026")
        assertEquals(Copy.Paywall.Parent.body(PRICE), layer.body)
    }

    @Test
    fun `with no store the two controls disappear together and an explanation appears`() {
        val layer = paywallLayer(PaywallStep.PARENT, PRICE, storeAvailable = false)
            as PaywallLayer.Parent
        assertNull(layer.restore)
        assertEquals(Copy.Paywall.Parent.NO_STORE, layer.noStore)
    }

    @Test
    fun `every layer offers a way out — a child never meets a dead end`() {
        val child = paywallLayer(PaywallStep.CHILD, PRICE, true)
        assertTrue(child.texts.contains(Copy.Paywall.Child.SEE_COMPANION))
        val gate = paywallLayer(PaywallStep.GATE, PRICE, true)
        assertTrue(gate.texts.contains(Copy.ParentalGate.CANCEL))
        val parent = paywallLayer(PaywallStep.PARENT, PRICE, true)
        assertTrue(parent.texts.contains(Copy.Paywall.Parent.BACK_TO_GAME))
        // Even when the store is dead: the exit does not depend on the store.
        val stranded = paywallLayer(PaywallStep.PARENT, PRICE, storeAvailable = false)
        assertTrue(stranded.texts.contains(Copy.Paywall.Parent.BACK_TO_GAME))
    }

    @Test
    fun `the copy is the authored French, character for character`() {
        assertEquals("Les jeux font une pause", Copy.Paywall.Child.TITLE)
        // U+00A0 before the "!" — the French typographic space, and the `&nbsp;`
        // of the TSX.
        assertEquals(
            "Demande à un grand ! Tes étoiles et ta mascotte t'attendent.",
            Copy.Paywall.Child.BODY,
        )
        assertTrue(
            Copy.Paywall.Child.BODY.contains('\u00A0'),
            "the &nbsp; was normalised to an ordinary space",
        )
        assertEquals("Voir ma mascotte", Copy.Paywall.Child.SEE_COMPANION)
        assertEquals("Je suis un adulte", Copy.Paywall.Child.I_AM_AN_ADULT)
        assertEquals("Débloquer Attrape-Lettres", Copy.Paywall.Parent.TITLE)
        // U+2014 em dash on the buy button.
        assertEquals("Débloquer — 11,99 €", Copy.Paywall.Parent.buy(PRICE))
        assertEquals(
            "L'achat n'a pas abouti. Rien n'a été débité.",
            Copy.Paywall.Note.PURCHASE_FAILED,
        )
        assertEquals("Achat restauré.", Copy.Paywall.Note.RESTORED)
        // « sur ce compte », because a Play restore is per Google account.
        assertEquals("Aucun achat trouvé sur ce compte.", Copy.Paywall.Note.NOTHING_TO_RESTORE)
    }

    @Test
    fun `only the price is emphasised in the parent paragraph`() {
        val body = Copy.Paywall.Parent.body(PRICE)
        val runs = emphasisRuns(body, listOf(PRICE))
        assertEquals(body, runs.joinToString("") { it.text })
        assertEquals(listOf(PRICE), runs.filter { it.bold }.map { it.text })
    }

    @Test
    fun `the fallback price is built from the licensing constant`() {
        assertEquals("11,99 €", paywallPrice(null))
        assertEquals(Copy.fallbackPriceLabel(UNLOCK_PRICE_EUR), paywallPrice(null))
        assertEquals("4,99 €", paywallPrice(null, fallbackEur = 4.99))
    }

    @Test
    fun `the store label wins when it has answered`() {
        assertEquals("8,49 €", paywallPrice("8,49 €"))
    }
}

class PaywallStepMachineTest {

    @Test
    fun `it starts on the child layer`() {
        assertEquals(PaywallStep.CHILD, PaywallModel().step)
    }

    @Test
    fun `asking for an adult opens the gate and tracks NOTHING`() {
        val world = AdultWorld(consent = true)
        val m = PaywallModel()
        m.askForAnAdult()
        assertEquals(PaywallStep.GATE, m.step)
        // A child tapping around is not a paywall impression.
        assertEquals("", world.probe.drain())
    }

    @Test
    fun `passing the gate shows the price and tracks the impression`() {
        val world = AdultWorld(consent = true)
        val m = PaywallModel(step = PaywallStep.GATE)
        m.gatePassed(world.telemetry)
        assertEquals(PaywallStep.PARENT, m.step)
        assertTrue(world.probe.drain().contains("paywall_shown"))
    }

    @Test
    fun `cancelling the gate returns to the calm screen, not out of the app`() {
        val m = PaywallModel(step = PaywallStep.GATE)
        m.gateCancelled()
        assertEquals(PaywallStep.CHILD, m.step)
    }

    @Test
    fun `the layers form a closed loop`() {
        val world = AdultWorld(consent = true)
        val m = PaywallModel()
        m.askForAnAdult()
        m.gateCancelled()
        assertEquals(PaywallStep.CHILD, m.step)
        m.askForAnAdult()
        m.gatePassed(world.telemetry)
        assertEquals(PaywallStep.PARENT, m.step)
    }

    @Test
    fun `nothing else moves the step`() = runBlocking {
        val world = AdultWorld(consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.buy(world.entitlement, world.telemetry)
        m.redo(world.entitlement, world.telemetry)
        m.setAnalytics(true, world.telemetry)
        m.seedAnalytics(false)
        assertEquals(PaywallStep.PARENT, m.step)
    }
}

class PaywallStoreTest {

    @Test
    fun `a successful purchase tracks completion and leaves no note`() = runBlocking {
        val world = AdultWorld(SilentStore(buys = true, reachable = true, paid = true), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.buy(world.entitlement, world.telemetry)
        assertNull(m.note)
        assertFalse(m.busy)
        assertTrue(world.probe.drain().contains("purchase_completed"))
    }

    @Test
    fun `a failed purchase says nothing was charged, and blames nobody`() = runBlocking {
        val world = AdultWorld(SilentStore(buys = false), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.buy(world.entitlement, world.telemetry)
        assertEquals(Copy.Paywall.Note.PURCHASE_FAILED, m.note)
        assertFalse(m.busy)
        assertTrue(world.probe.drain().contains("purchase_failed"))
    }

    /**
     * INVARIANT 11. `PurchaseStore`'s contract says an adapter never throws; a
     * Play Billing wrapper that lets a `BillingClient` exception escape breaks
     * it. That must land exactly where a refusal lands — a note, `busy` back to
     * false, and a family whose game is as playable as it was a second earlier.
     */
    @Test
    fun `a store that THROWS is a refusal, not a lockout`() = runBlocking {
        val world = AdultWorld(ExplodingStore(), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.buy(world.entitlement, world.telemetry)
        assertEquals(Copy.Paywall.Note.PURCHASE_FAILED, m.note)
        assertFalse(m.busy)
        assertTrue(canPlay(world.entitlement.entitlement))

        m.redo(world.entitlement, world.telemetry)
        assertEquals(Copy.Paywall.Note.NOTHING_TO_RESTORE, m.note)
        assertFalse(m.busy)
        assertTrue(canPlay(world.entitlement.entitlement))
    }

    @Test
    fun `no failure mode downgrades the entitlement`() = runBlocking {
        for (store in listOf(SilentStore(), SilentStore(available = false), ExplodingStore())) {
            val world = AdultWorld(store)
            world.entitlement.beginTrial()
            val before = world.entitlement.entitlement
            val m = PaywallModel(step = PaywallStep.PARENT)
            m.buy(world.entitlement, world.telemetry)
            m.redo(world.entitlement, world.telemetry)
            assertEquals(before, world.entitlement.entitlement, "downgraded by $store")
            assertTrue(canPlay(world.entitlement.entitlement))
        }
    }

    @Test
    fun `a retry clears the previous note`() = runBlocking {
        val world = AdultWorld(SilentStore(buys = false), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.buy(world.entitlement, world.telemetry)
        assertNotNull(m.note)
        m.redo(world.entitlement, world.telemetry)
        // `setNote(null)` happens first inside redo; the note we see is redo's.
        assertEquals(Copy.Paywall.Note.NOTHING_TO_RESTORE, m.note)
    }

    @Test
    fun `a restore that finds something says so`() = runBlocking {
        val world = AdultWorld(SilentStore(restores = true, reachable = true, paid = true), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.redo(world.entitlement, world.telemetry)
        assertEquals(Copy.Paywall.Note.RESTORED, m.note)
        assertTrue(world.probe.drain().contains("purchase_restored"))
    }

    @Test
    fun `a restore that finds nothing still tracks the attempt`() = runBlocking {
        val world = AdultWorld(SilentStore(restores = false), consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.redo(world.entitlement, world.telemetry)
        assertEquals(Copy.Paywall.Note.NOTHING_TO_RESTORE, m.note)
        assertTrue(world.probe.drain().contains("purchase_restored"))
    }

    @Test
    fun `restoring against an unreachable store keeps a paid family paid`() = runBlocking {
        // A family who paid, on a device that then loses the store.
        val world = AdultWorld(SilentStore(paid = true, reachable = true))
        world.entitlement.refresh()
        assertEquals(Entitlement.Paid, world.entitlement.entitlement)

        val gone = AdultWorld(SilentStore(reachable = false))
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.redo(world.entitlement, world.telemetry)
        assertEquals(Entitlement.Paid, world.entitlement.entitlement)
        assertTrue(canPlay(gone.entitlement.entitlement))
    }
}

class PaywallConsentTest {

    @Test
    fun `the box starts at the answer already stored`() {
        val world = AdultWorld(consent = true)
        val m = PaywallModel()
        m.seedAnalytics(world.telemetry.hasConsent)
        assertTrue(m.analytics)
    }

    /**
     * The contrast with Onboarding is deliberate and must not be unified: that
     * screen ASKS for the first time and starts OFF (CJEU *Planet49*); this one
     * SHOWS the answer already given so it can be withdrawn (GDPR Art. 7(3)).
     */
    @Test
    fun `this screen seeds from storage while Onboarding never does`() {
        assertFalse(OnboardingDefaults.INITIAL_CONSENT)
        val world = AdultWorld(consent = true)
        val m = PaywallModel()
        m.seedAnalytics(world.telemetry.hasConsent)
        assertTrue(m.analytics)
    }

    @Test
    fun `the seed does not clobber a toggle the parent moved`() {
        val world = AdultWorld(consent = false)
        val m = PaywallModel()
        m.seedAnalytics(false)
        m.setAnalytics(true, world.telemetry)
        m.seedAnalytics(false)
        assertTrue(m.analytics)
    }

    @Test
    fun `toggling writes the consent through`() {
        val world = AdultWorld(consent = false)
        val m = PaywallModel()
        m.setAnalytics(true, world.telemetry)
        assertTrue(world.telemetry.hasConsent)
        m.setAnalytics(false, world.telemetry)
        assertFalse(world.telemetry.hasConsent)
    }

    @Test
    fun `withdrawing consent drops what was not sent yet`() {
        val world = AdultWorld(consent = true)
        val m = PaywallModel(step = PaywallStep.PARENT)
        m.gatePassed(world.telemetry) // queues paywall_shown
        m.setAnalytics(false, world.telemetry)
        assertEquals("", world.probe.drain(), "a withdrawn event was still sent")
    }
}

class PaywallNeverGatesTest {

    private val everyState: List<Entitlement> = listOf(
        Entitlement.Unknown,
        Entitlement.Trial(14, 0L),
        Entitlement.Trial(1, 0L),
        Entitlement.Trial(0, 0L),
        Entitlement.Paid,
        Entitlement.Expired,
    )

    /**
     * The paywall must never appear for a family `:core` says can play. The
     * router owns that decision, so this asserts against `openOutcome` over the
     * whole catalog and every state — the same claim `RootShellTest` makes,
     * restated here because this is the screen the claim protects.
     */
    @Test
    fun `no entitlement that can play ever reaches this screen`() {
        for (state in everyState) {
            for (row in EXERCISES) {
                val outcome = openOutcome(row.id, 1, state)
                if (canPlay(state)) {
                    assertEquals(
                        OpenOutcome.Play(row.id, 1),
                        outcome,
                        "${row.id.wire} was sent to the paywall under $state",
                    )
                } else {
                    assertEquals(OpenOutcome.Paywall, outcome)
                    assertEquals(Entitlement.Expired, state)
                }
            }
        }
    }

    @Test
    fun `unknown plays — canPlay is a blacklist of one`() {
        assertTrue(canPlay(Entitlement.Unknown))
        assertEquals(
            OpenOutcome.Play(EXERCISES.first().id, 1),
            openOutcome(EXERCISES.first().id, 1, Entitlement.Unknown),
        )
    }

    /**
     * The route stays REACHABLE in every licence state — a parent may open the
     * paywall from the hub whenever they like. What must never happen is a child
     * being sent there, which is the test above. No gate consults the licence at
     * all, which is why `shellGate` has no entitlement parameter to hand these
     * six states to.
     */
    @Test
    fun `the paywall is reachable as a route in every licence state`() {
        for (state in everyState) {
            assertEquals(
                ShellGate.Screen(AppRoute.Paywall),
                shellGate(
                    dev = null,
                    onboarded = true,
                    activeId = "c1",
                    chosen = true,
                    route = AppRoute.Paywall,
                ),
            )
            assertEquals(state == Entitlement.Expired, !canPlay(state))
        }
    }
}

class PaywallSourceTest {

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertNotNull(adultScreenSource("Paywall.kt"))
    }

    /**
     * INVARIANT 11, structurally: this screen SELLS and never GATES. A branch of
     * its own — "if the store did not answer, hide the way back" — is the way the
     * fail-open chain gets broken, and the branch would have to start with one of
     * these reads.
     */
    @Test
    fun `the paywall reads no verdict about who may play`() {
        val file = assertNotNull(adultScreenSource("Paywall.kt"))
        val banned = listOf("canPlay", "entitlementOf", "Entitlement.", ".entitlement", "TimeSource")
        for ((number, line) in codeLines(file)) {
            for (word in banned) {
                assertFalse(
                    line.contains(word),
                    "Paywall.kt:$number decides who may play: ${line.trim()}",
                )
            }
        }
    }

    /** A12 — `Modifier.clickable` is banned module-wide. */
    @Test
    fun `every tap on the paywall goes through touchDown`() {
        val file = assertNotNull(adultScreenSource("Paywall.kt"))
        for ((number, line) in codeLines(file)) {
            assertFalse(line.contains("clickable"), "Paywall.kt:$number uses clickable")
        }
        assertTrue(codeLines(file).any { it.second.contains("touchDown") })
    }

    /** Play never shares a purchase with a family, so no string here may say it does. */
    @Test
    fun `no paywall string promises a family`() {
        val strings = listOf(
            Copy.Paywall.Child.TITLE,
            Copy.Paywall.Child.BODY,
            Copy.Paywall.Child.SEE_COMPANION,
            Copy.Paywall.Child.I_AM_AN_ADULT,
            Copy.Paywall.Parent.TITLE,
            Copy.Paywall.Parent.body(PRICE),
            Copy.Paywall.Parent.buy(PRICE),
            Copy.Paywall.Parent.RESTORE,
            Copy.Paywall.Parent.NO_STORE,
            Copy.Paywall.Parent.CONSENT_BODY,
            Copy.Paywall.Parent.BACK_TO_GAME,
            Copy.Paywall.Note.PURCHASE_FAILED,
            Copy.Paywall.Note.RESTORED,
            Copy.Paywall.Note.NOTHING_TO_RESTORE,
        )
        for (s in strings) {
            assertFalse(s.lowercase().contains("famille"), "« $s » promises a family")
        }
    }
}
