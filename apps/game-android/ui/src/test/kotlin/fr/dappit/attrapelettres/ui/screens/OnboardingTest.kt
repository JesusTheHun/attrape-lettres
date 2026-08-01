package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.licensing.LicenseStore
import fr.dappit.attrapelettres.core.licensing.TRIAL_DAYS
import fr.dappit.attrapelettres.core.licensing.UNLOCK_PRICE_EUR
import fr.dappit.attrapelettres.core.licensing.canPlay
import fr.dappit.attrapelettres.ui.design.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// First launch, tested as data. Nothing here composes (A11).
//
// The claims are legal before they are behavioural:
//   - App Review 3.1.1 / Play's trial disclosure: duration, what stops, and the
//     charge, all BEFORE the trial starts;
//   - CJEU *Planet49*: the consent control starts OFF;
//   - GDPR Art. 7: consent is recorded before anything is tracked;
//   - Play Family Library shares no in-app purchase, so the scope promise is
//     « sur vos appareils », never a family;
//   - invariant 11: nothing on this screen can resolve to "locked".

class EmphasisRunsTest {

    private val sentence = Copy.Onboarding.trialParagraph(TRIAL_DAYS, "9,99 €")
    private val bold = listOf("$TRIAL_DAYS jours", "9,99 €")

    @Test
    fun `the runs concatenate back to the sentence, unchanged`() {
        assertEquals(sentence, emphasisRuns(sentence, bold).joinToString("") { it.text })
    }

    @Test
    fun `exactly the TSX's two spans are bold, and nothing else is`() {
        val emphasised = emphasisRuns(sentence, bold).filter { it.bold }.map { it.text }
        assertEquals(bold, emphasised)
    }

    @Test
    fun `runs come back in document order even when the needles are listed backwards`() {
        val emphasised = emphasisRuns(sentence, bold.reversed()).filter { it.bold }.map { it.text }
        assertEquals(bold, emphasised)
    }

    @Test
    fun `each emphasised span occurs exactly once in the sentence it emphasises`() {
        // If a needle appeared twice, the FIRST occurrence would be bolded and the
        // second would silently not be — a defect nobody would see in review.
        for (needle in bold) {
            assertEquals(
                1,
                Regex(Regex.escape(needle)).findAll(sentence).count(),
                "« $needle » is not unique in the sentence",
            )
        }
    }

    @Test
    fun `a sentence with no emphasis is one plain run`() {
        val runs = emphasisRuns(Copy.Onboarding.NO_STORE_PARAGRAPH, emptyList())
        assertEquals(1, runs.size)
        assertFalse(runs[0].bold)
        assertEquals(Copy.Onboarding.NO_STORE_PARAGRAPH, runs[0].text)
    }

    @Test
    fun `a needle that is not there changes nothing`() {
        val runs = emphasisRuns("abc", listOf("zzz"))
        assertEquals(listOf(EmphasisRun("abc", false)), runs)
    }

    @Test
    fun `emphasis at the very start emits no empty leading run`() {
        assertEquals(
            listOf(EmphasisRun("ab", true), EmphasisRun("c", false)),
            emphasisRuns("abc", listOf("ab")),
        )
    }

    @Test
    fun `emphasis at the very end emits no empty trailing run`() {
        assertEquals(
            listOf(EmphasisRun("a", false), EmphasisRun("bc", true)),
            emphasisRuns("abc", listOf("bc")),
        )
    }

    @Test
    fun `an empty needle is ignored rather than looping forever`() {
        assertEquals(listOf(EmphasisRun("abc", false)), emphasisRuns("abc", listOf("")))
    }
}

class OnboardingPlanTest {

    @Test
    fun `with a store there are two paragraphs, the trial terms then the pause note`() {
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = null)
        assertEquals(2, plan.paragraphs.size)
        assertEquals(
            Copy.Onboarding.trialParagraph(TRIAL_DAYS, plan.price),
            plan.paragraphs[0].joinToString("") { it.text },
        )
        assertEquals(
            Copy.Onboarding.pauseParagraph(TRIAL_DAYS),
            plan.paragraphs[1].joinToString("") { it.text },
        )
    }

    @Test
    fun `the terms disclose the duration, what stops, and the charge`() {
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = null)
        val all = plan.paragraphs.joinToString(" ") { runs -> runs.joinToString("") { it.text } }
        assertTrue(all.contains("$TRIAL_DAYS jours"), "the duration is not disclosed")
        assertTrue(all.contains(plan.price), "the charge is not disclosed")
        assertTrue(all.contains("pause"), "what stops working is not disclosed")
        assertTrue(all.contains("Pas d'abonnement"), "the no-subscription term is missing")
    }

    @Test
    fun `the store's own label wins over the computed fallback`() {
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = "8,99 €")
        assertEquals("8,99 €", plan.price)
        assertTrue(plan.paragraphs[0].any { it.bold && it.text == "8,99 €" })
    }

    @Test
    fun `the fallback price is UNLOCK_PRICE_EUR with a French comma`() {
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = null)
        assertEquals("9,99 €", plan.price)
        assertEquals(Copy.fallbackPriceLabel(UNLOCK_PRICE_EUR), plan.price)
        assertFalse(plan.price.contains("."))
    }

    @Test
    fun `without a store there is one paragraph and a bare « Commencer »`() {
        val plan = OnboardingPlan(storeAvailable = false, priceLabel = null)
        assertEquals(1, plan.paragraphs.size)
        assertEquals(Copy.Onboarding.START, plan.buttonTitle)
        assertEquals(
            Copy.Onboarding.NO_STORE_PARAGRAPH,
            plan.paragraphs[0].joinToString("") { it.text },
        )
    }

    @Test
    fun `the no-store branch promises nothing about money`() {
        val plan = OnboardingPlan(storeAvailable = false, priceLabel = "9,99 €")
        val all = plan.paragraphs.joinToString(" ") { runs -> runs.joinToString("") { it.text } }
        assertFalse(all.contains("9,99"))
        assertFalse(all.contains("€"))
        assertFalse(all.contains("achat unique"))
    }

    @Test
    fun `the copy is built from the constant, not from a hard-coded 14`() {
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = null, days = 7)
        val all = plan.paragraphs.joinToString(" ") { runs -> runs.joinToString("") { it.text } }
        assertTrue(all.contains("7 jours"))
        assertFalse(all.contains("14 jours"))
        assertEquals("Commencer les 7 jours", plan.buttonTitle)
    }

    @Test
    fun `the default day count is TRIAL_DAYS, which is 14`() {
        assertEquals(14, TRIAL_DAYS)
        assertEquals(
            Copy.Onboarding.startTrial(TRIAL_DAYS),
            OnboardingPlan(storeAvailable = true, priceLabel = null).buttonTitle,
        )
    }

    /**
     * Google Play Family Library explicitly does NOT share in-app purchases,
     * ever. iOS's `Copy.swift` may promise « pour toute la famille » because
     * Family Sharing keeps that promise; this app must not, and the wording it
     * uses instead is exactly true — a Play restore covers the devices signed
     * into that Google account.
     */
    @Test
    fun `the scope is per-device, never a family`() {
        assertEquals("sur vos appareils", Copy.Onboarding.SCOPE)
        val plan = OnboardingPlan(storeAvailable = true, priceLabel = null)
        val all = plan.paragraphs.joinToString(" ") { runs -> runs.joinToString("") { it.text } }
        assertTrue(all.contains("sur vos appareils"))
        assertFalse(all.lowercase().contains("famille"))
    }

    /**
     * The trial on Android is a LOCAL stamp carried by Auto Backup (A5): Play has
     * no price-0 in-app product, so there is no signed receipt to restore. No
     * onboarding copy may imply one.
     */
    @Test
    fun `no onboarding copy implies a receipt or a restore flow`() {
        val all = listOf(
            Copy.Onboarding.TITLE,
            Copy.Onboarding.trialParagraph(TRIAL_DAYS, "9,99 €"),
            Copy.Onboarding.pauseParagraph(TRIAL_DAYS),
            Copy.Onboarding.NO_STORE_PARAGRAPH,
            Copy.Onboarding.startTrial(TRIAL_DAYS),
            Copy.Onboarding.START,
            Copy.Onboarding.CONSENT_TITLE,
            Copy.Onboarding.CONSENT_BODY,
        ).joinToString(" ").lowercase()
        for (word in listOf("restaur", "reçu", "facture", "compte google")) {
            assertFalse(all.contains(word), "onboarding copy implies « $word »")
        }
    }
}

class OnboardingConsentTest {

    /** CJEU *Planet49*. The box starts empty, and it is a constant so it stays so. */
    @Test
    fun `the consent control starts OFF`() {
        assertFalse(OnboardingDefaults.INITIAL_CONSENT)
        assertFalse(Copy.Onboarding.CONSENT_INITIALLY_CHECKED)
        assertEquals(Copy.Onboarding.CONSENT_INITIALLY_CHECKED, OnboardingDefaults.INITIAL_CONSENT)
    }

    /**
     * The order in `start()` is `setConsent` -> `track` -> `beginTrial`, and this
     * pins the first arrow: consent is stored BEFORE the first event is
     * considered, so a parent who ticks the box has their `trial_started`
     * recorded rather than dropped by a stale "no".
     */
    @Test
    fun `setConsent runs BEFORE track, so the very first event respects the answer`() {
        val world = AdultWorld(consent = false)
        startOnboarding(analytics = true, world.telemetry, world.entitlement)
        val wire = world.probe.drain()
        assertTrue(wire.contains("trial_started"), "the accepted event never left: $wire")
        assertTrue(wire.contains("daysLeft"))
        assertTrue(world.telemetry.hasConsent)
    }

    @Test
    fun `a refusal is recorded and the event never leaves the device`() {
        val world = AdultWorld(consent = true)
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        assertFalse(world.telemetry.hasConsent)
        assertEquals("", world.probe.drain(), "a refused event was still sent")
    }

    /**
     * Invariant 10: the only property on the wire is a number. The roster is
     * never read here, so a child's first name has no path from this screen to a
     * transport.
     */
    @Test
    fun `the only property on the wire is daysLeft, and it is a number`() {
        val world = AdultWorld(consent = true)
        startOnboarding(analytics = true, world.telemetry, world.entitlement)
        val wire = world.probe.drain()
        assertTrue(wire.contains("\"daysLeft\":14"), wire)
        assertFalse(wire.contains("Léa"))
        assertFalse(wire.contains("name"))
    }

    /** Declining costs the parent nothing: the trial starts either way. */
    @Test
    fun `declining costs the parent nothing`() {
        for (answer in listOf(true, false)) {
            val world = AdultWorld()
            startOnboarding(answer, world.telemetry, world.entitlement)
            assertTrue(world.entitlement.onboarded)
            assertNotNull(world.entitlement.license.trialStartedAt)
            assertTrue(canPlay(world.entitlement.entitlement))
        }
    }
}

class OnboardingTrialTest {

    @Test
    fun `the onboarded flag is persisted under the PWA's own key`() {
        val world = AdultWorld()
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        assertEquals("1", world.probe.kv.string(LicenseStore.ONBOARDED_KEY))
        assertEquals("attrape-lettres:onboarded:v1", LicenseStore.ONBOARDED_KEY)
    }

    @Test
    fun `a second « Commencer » cannot re-start the clock`() {
        val world = AdultWorld()
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        val first = world.entitlement.license.trialStartedAt
        world.time.advance(3.0)
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        assertEquals(first, world.entitlement.license.trialStartedAt)
    }

    /** Invariant 11: an unreachable store still leaves the family playing. */
    @Test
    fun `an unreachable store still leaves the family playing`() {
        val world = AdultWorld(store = SilentStore(available = true, reachable = false))
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        assertTrue(canPlay(world.entitlement.entitlement))
        // And a plan is still renderable: the fallback price is always there.
        val plan = OnboardingPlan(
            storeAvailable = world.entitlement.storeAvailable,
            priceLabel = world.entitlement.priceLabel,
        )
        assertEquals("9,99 €", plan.price)
    }

    /** No store at all — this build's shipping shape. Still playing. */
    @Test
    fun `no store at all still leaves the family playing`() {
        val world = AdultWorld(store = SilentStore(available = false))
        startOnboarding(analytics = false, world.telemetry, world.entitlement)
        assertTrue(canPlay(world.entitlement.entitlement))
        val plan = OnboardingPlan(storeAvailable = false, priceLabel = null)
        assertEquals(Copy.Onboarding.START, plan.buttonTitle)
    }
}

class OnboardingSourceTest {

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertNotNull(adultScreenSource("Onboarding.kt"))
    }

    /** A12 — `Modifier.clickable` is banned module-wide. */
    @Test
    fun `every tap on the onboarding screen goes through touchDown`() {
        val file = assertNotNull(adultScreenSource("Onboarding.kt"))
        for ((number, line) in codeLines(file)) {
            assertFalse(line.contains("clickable"), "Onboarding.kt:$number uses clickable")
        }
        assertTrue(codeLines(file).any { it.second.contains("touchDown") })
    }

    /**
     * The consent default must not become a function of anything. A
     * `hasConsent` read in this file would be exactly that edit, and it would
     * pre-tick the box.
     */
    @Test
    fun `the onboarding screen never seeds consent from storage`() {
        val file = assertNotNull(adultScreenSource("Onboarding.kt"))
        for ((number, line) in codeLines(file)) {
            assertFalse(
                line.contains("hasConsent"),
                "Onboarding.kt:$number reads a stored consent answer: ${line.trim()}",
            )
        }
    }
}
