package fr.dappit.attrapelettres.core.licensing

import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Port of EntitlementModelTests.swift, which is itself the behaviour of        */
/* useEntitlement.tsx made testable. Plus the chaos-store property: once a      */
/* reachable answer has confirmed the purchase, NO run of unreachable answers   */
/* and no clock walk may lock the family out before the grace window is spent.  */
/* -------------------------------------------------------------------------- */

private const val T0 = 1_700_000_000_000L

/* Test doubles ---------------------------------------------------------------*/
/* These live here and not in main sources ON PURPOSE. The iOS port fences its  */
/* ownership-granting double behind `#if DEBUG` because a store that reports a  */
/* purchase nobody made would silently unlock everyone if it ever reached a     */
/* release build. Kotlin has no such fence, so the doubles live in the test     */
/* source set, which is not compiled into the APK at all — strictly stronger,   */
/* and LicensingSourceScanTest asserts that main sources stay clean.            */

/** A scriptable store. Every method is non-throwing by interface. */
private class ScriptedPurchaseStore(
    override val available: Boolean = true,
    var snapshot: StoreSnapshot = StoreSnapshot.UNREACHABLE,
    var trialDate: Long? = null,
    var purchaseResult: Boolean = false,
    var restoreResult: Boolean = false,
    var price: String? = null,
) : PurchaseStore {
    var refreshes: Int = 0
        private set

    override suspend fun refresh(): StoreSnapshot {
        refreshes++
        return snapshot
    }

    override suspend fun beginTrial(): Long? = trialDate
    override suspend fun purchase(): Boolean = purchaseResult
    override suspend fun restore(): Boolean = restoreResult
    override suspend fun priceLabel(): String? = price
}

/**
 * A store whose `refresh()` parks until the test opens the gate. Lets the
 * coalescing window be observed deterministically instead of raced for.
 */
private class GatedPurchaseStore : PurchaseStore {
    override val available: Boolean = true
    private val opened = CompletableDeferred<Unit>()

    var entered: Int = 0
        private set

    fun open() {
        opened.complete(Unit)
    }

    override suspend fun refresh(): StoreSnapshot {
        entered++
        opened.await()
        return StoreSnapshot(paid = true, trialStartedAt = null, reachable = true)
    }

    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = false
    override suspend fun restore(): Boolean = false
    override suspend fun priceLabel(): String? = null
}

/**
 * Every answer is unreachable — which is the whole point. No sequence of "the
 * store did not answer" may take a confirmed paid family down, and the run
 * counter proves the sequence really happened.
 */
private class SilentPurchaseStore : PurchaseStore {
    override val available: Boolean = true
    var calls: Int = 0
        private set

    override suspend fun refresh(): StoreSnapshot {
        calls++
        return StoreSnapshot.UNREACHABLE
    }

    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = false
    override suspend fun restore(): Boolean = false
    override suspend fun priceLabel(): String? = null
}

class EntitlementModelTest {

    private fun newModel(
        store: PurchaseStore,
        kv: InMemoryKVStore = InMemoryKVStore(),
        now: Long = T0,
    ) = EntitlementModel(store, LicenseStore(kv), MutableTimeSource(now))

    @Test
    fun `init reads persisted state and stamps checkedAt from the injected clock`() {
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(paid = true, verifiedAt = T0))
        LicenseStore(kv).saveOnboarded()
        val model = newModel(StubPurchaseStore(), kv, now = T0 + 5)
        assertEquals(LicenseState(paid = true, verifiedAt = T0), model.license)
        assertTrue(model.onboarded)
        assertEquals(T0 + 5, model.checkedAt)
        assertNull(model.priceLabel)
        assertTrue(model.storeAvailable)
        assertEquals(Entitlement.Paid, model.entitlement)
    }

    /**
     * The frozen-`checkedAt` behaviour, ported deliberately: "a child
     * mid-exercise when the fortnight runs out gets to finish."
     */
    @Test
    fun `the entitlement does not lapse mid-session, only a refresh moves checkedAt`() = runTest {
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(trialStartedAt = T0))
        val time = MutableTimeSource(T0)
        val model = EntitlementModel(StubPurchaseStore(), LicenseStore(kv), time)
        assertNotEquals(Entitlement.Expired, model.entitlement)

        time.nowMillis = T0 + TRIAL_MS + DAY_MS
        assertNotEquals(Entitlement.Expired, model.entitlement, "a timer must not tick this")
        assertEquals(T0, model.checkedAt)

        model.refresh()
        assertEquals(Entitlement.Expired, model.entitlement)
        assertEquals(T0 + TRIAL_MS + DAY_MS, model.checkedAt)
    }

    @Test
    fun `beginTrial persists onboarding and stamps the trial, synchronously`() {
        val kv = InMemoryKVStore()
        val persist = LicenseStore(kv)
        val model = newModel(ScriptedPurchaseStore(), kv)

        model.beginTrial()
        // No suspension anywhere in that call: on Android the local stamp IS the
        // trial clock, so it has to land before anything can go wrong.
        assertTrue(model.onboarded)
        assertTrue(persist.loadOnboarded())
        assertEquals(T0, model.license.trialStartedAt)
        assertEquals(T0, persist.load().trialStartedAt)
    }

    @Test
    fun `beginTrial never overwrites an existing stamp`() {
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(trialStartedAt = T0 - 5 * DAY_MS))
        val model = newModel(ScriptedPurchaseStore(), kv)
        model.beginTrial()
        assertEquals(T0 - 5 * DAY_MS, model.license.trialStartedAt)
    }

    @Test
    fun `confirmTrialStart takes the authoritative date when it is earlier`() = runTest {
        val kv = InMemoryKVStore()
        val persist = LicenseStore(kv)
        val model = newModel(ScriptedPurchaseStore(trialDate = T0 - 12 * DAY_MS), kv)
        model.beginTrial()
        assertEquals(T0, model.license.trialStartedAt)
        model.confirmTrialStart()
        assertEquals(T0 - 12 * DAY_MS, model.license.trialStartedAt)
        assertEquals(T0 - 12 * DAY_MS, persist.load().trialStartedAt)
    }

    @Test
    fun `confirmTrialStart ignores an authoritative date that is later`() = runTest {
        val model = newModel(ScriptedPurchaseStore(trialDate = T0 + 3 * DAY_MS))
        model.beginTrial()
        model.confirmTrialStart()
        assertEquals(T0, model.license.trialStartedAt)
    }

    /**
     * The Android case, and the only one that will ever run on this platform:
     * Google Play has no price-0 in-app product, so the store can never mint a
     * date and the local stamp stands, silently.
     */
    @Test
    fun `confirmTrialStart is a no-op when the platform cannot mint a date`() = runTest {
        val model = newModel(ScriptedPurchaseStore(trialDate = null))
        model.beginTrial()
        val before = model.license
        model.confirmTrialStart()
        assertEquals(before, model.license)
        assertEquals(T0, model.license.trialStartedAt)
    }

    @Test
    fun `refresh applies a reachable snapshot and persists it`() = runTest {
        val kv = InMemoryKVStore()
        val persist = LicenseStore(kv)
        val store = ScriptedPurchaseStore(
            snapshot = StoreSnapshot(paid = true, trialStartedAt = null, reachable = true),
        )
        val model = newModel(store, kv)
        model.refresh()
        assertTrue(model.license.paid)
        assertEquals(T0, model.license.verifiedAt)
        assertTrue(persist.load().paid)
        assertEquals(Entitlement.Paid, model.entitlement)
    }

    @Test
    fun `an unreachable refresh changes nothing but the clock`() = runTest {
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(
            LicenseState(paid = true, verifiedAt = T0, trialStartedAt = T0),
        )
        val model = newModel(StubPurchaseStore(), kv, now = T0 + DAY_MS)
        model.refresh()
        assertTrue(model.license.paid)
        assertEquals(T0, model.license.verifiedAt, "never re-stamped without a confirmation")
        assertEquals(T0 + DAY_MS, model.license.clockHighWater)
        assertEquals(Entitlement.Paid, model.entitlement)
    }

    @Test
    fun `purchase refreshes only on success`() = runTest {
        val store = ScriptedPurchaseStore(purchaseResult = false)
        val model = newModel(store)
        assertFalse(model.purchase())
        assertEquals(0, store.refreshes)

        store.purchaseResult = true
        store.snapshot = StoreSnapshot(paid = true, trialStartedAt = null, reachable = true)
        assertTrue(model.purchase())
        assertEquals(1, store.refreshes)
        assertEquals(Entitlement.Paid, model.entitlement)
    }

    /**
     * The asymmetry with `purchase` is in the TypeScript and is correct: a
     * restore can materialise an entitlement even when it reports nothing
     * restored.
     */
    @Test
    fun `restore refreshes unconditionally`() = runTest {
        val store = ScriptedPurchaseStore(restoreResult = false)
        val model = newModel(store)
        assertFalse(model.restore())
        assertEquals(1, store.refreshes)
    }

    @Test
    fun `priceLabel is fetched once and never retried`() = runTest {
        val store = ScriptedPurchaseStore(price = null)
        val model = newModel(store)
        model.refresh()
        assertNull(model.priceLabel)
        // A store that could not price the product will not price it on resume
        // either, and retrying forever is a battery bug, not a fix.
        store.price = "9,99 €"
        model.refresh()
        model.refresh()
        assertNull(model.priceLabel)
        assertEquals(3, store.refreshes)
    }

    @Test
    fun `priceLabel lands on the first refresh`() = runTest {
        val model = newModel(ScriptedPurchaseStore(price = "9,99 €"))
        model.refresh()
        assertEquals("9,99 €", model.priceLabel)
    }

    /**
     * React's single-threaded read-modify-write has no Kotlin equivalent, so a
     * second refresh waits for the first instead of racing it — otherwise two
     * resume triggers could each read the licence, suspend in the store, and
     * write back, losing one update.
     */
    // `runCurrent` — drain everything the scheduler can run right now and no
    // more — is the only way to observe the coalescing window deterministically,
    // and it is still opt-in API. A yield loop would be guessing at a quantity
    // that does not exist, which is the flakiness the iOS suite had to unpick.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `two concurrent refreshes coalesce into one store call`() = runTest {
        val store = GatedPurchaseStore()
        val model = newModel(store)

        val first = launch { model.refresh() }
        runCurrent() // park inside the store
        assertEquals(1, store.entered)

        val second = launch { model.refresh() }
        runCurrent()
        assertEquals(1, store.entered, "the second refresh started its own store call")

        store.open()
        first.join()
        second.join()

        assertEquals(1, store.entered)
        assertTrue(model.license.paid)
    }

    @Test
    fun `a refresh after an in-flight one has completed is a real refresh`() = runTest {
        val store = ScriptedPurchaseStore()
        val model = newModel(store)
        model.refresh()
        model.refresh()
        assertEquals(2, store.refreshes)
    }

    /**
     * The invariant-11 end-to-end statement, through the model rather than the
     * pure function: once a reachable answer has confirmed the purchase at T0,
     * no run of unreachable answers and no clock walk — forwards OR backwards —
     * produces a lockout before the grace window is spent.
     */
    @Test
    fun `no run of unreachable answers can lock a confirmed paid family out`() = runTest {
        val kv = InMemoryKVStore()
        // The one reachable positive, at T0.
        LicenseStore(kv).save(
            LicenseState(
                paid = true,
                verifiedAt = T0,
                trialStartedAt = T0 - 200 * DAY_MS,
                clockHighWater = T0,
            ),
        )
        val store = SilentPurchaseStore()
        val time = MutableTimeSource(T0)
        val model = EntitlementModel(store, LicenseStore(kv), time)

        // A deterministic random walk, ±3 hours a step, over a thousand resumes.
        var rng = 0x0000_0000_0000_5EEDL
        repeat(1_000) {
            rng += -0x61c8864680b583ebL
            var z = rng
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            val roll = (z xor (z ushr 31)).toULong() % (6UL * 3_600_000UL)
            time.nowMillis += roll.toLong() - 3 * 3_600_000L

            model.refresh()
            if (model.license.clockHighWater <= T0 + OFFLINE_GRACE_MS) {
                assertEquals(Entitlement.Paid, model.entitlement)
                assertTrue(canPlay(model.entitlement))
            }
            // Whatever the walk did, the confirmation stamp is untouched: an
            // unreachable answer is not evidence of anything.
            assertEquals(T0, model.license.verifiedAt)
            assertTrue(model.license.paid)
        }
        assertEquals(1_000, store.calls)
    }
}
