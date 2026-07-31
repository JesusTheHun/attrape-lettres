package fr.dappit.attrapelettres.core.licensing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* A line-for-line port of apps/game-web/src/licensing/entitlement.test.ts —    */
/* same fixed T0, same cases, same assertions, same order — plus the boundary   */
/* cases the Swift port added in EntitlementTests.swift.                       */
/*                                                                             */
/* If any of these needs editing to pass, the port is wrong, not the test.     */
/* This file is the executable half of invariant 11.                           */
/* -------------------------------------------------------------------------- */

/** A fixed "now"; nothing here reads a real clock. */
private const val T0 = 1_700_000_000_000L

private fun license(
    paid: Boolean = false,
    verifiedAt: Long? = null,
    trialStartedAt: Long? = null,
    clockHighWater: Long = 0L,
) = LicenseState(paid, verifiedAt, trialStartedAt, clockHighWater)

/**
 * `expect(e).toMatchObject({ status: "trial", daysLeft: n })` — the vitest
 * matcher ignores `endsAt`, so this does too.
 */
private fun daysLeft(e: Entitlement): Int? = (e as? Entitlement.Trial)?.daysLeft

class TrialClockTest {

    @Test
    fun `gives a full fortnight before the parent has even accepted`() {
        assertEquals(TRIAL_DAYS, daysLeft(entitlementOf(license(), T0)))
    }

    @Test
    fun `counts down from the start date`() {
        val s = license(trialStartedAt = T0)
        assertEquals(14, daysLeft(entitlementOf(s, T0)))
        assertEquals(11, daysLeft(entitlementOf(s, T0 + 3 * DAY_MS)))
        // 13.5 days: the ceil boundary that must round UP to a last day.
        assertEquals(1, daysLeft(entitlementOf(s, T0 + (13.5 * DAY_MS).toLong())))
    }

    @Test
    fun `expires the instant the fortnight is up, not a moment before`() {
        val s = license(trialStartedAt = T0)
        assertEquals(1, daysLeft(entitlementOf(s, T0 + TRIAL_MS - 1)))
        assertEquals(Entitlement.Expired, entitlementOf(s, T0 + TRIAL_MS))
    }

    @Test
    fun `blocks new rounds only once expired`() {
        assertTrue(canPlay(entitlementOf(license(trialStartedAt = T0), T0)))
        assertFalse(canPlay(entitlementOf(license(trialStartedAt = T0), T0 + TRIAL_MS)))
        assertTrue(canPlay(entitlementOf(license(paid = true), T0)))
    }

    @Test
    fun `ignores a device clock wound backwards`() {
        // Day 10 of the trial, then someone sets the date back a year.
        val s = withClock(license(trialStartedAt = T0), T0 + 10 * DAY_MS)
        assertEquals(T0 + 10 * DAY_MS, effectiveNow(s, T0 - 365 * DAY_MS))
        assertEquals(4, daysLeft(entitlementOf(s, T0 - 365 * DAY_MS)))
    }

    @Test
    fun `speaks French to the parent, and says the last day plainly`() {
        assertEquals(
            "Essai gratuit — 11 jours restants",
            trialNotice(entitlementOf(license(trialStartedAt = T0), T0 + 3 * DAY_MS)),
        )
        assertEquals(
            "Dernier jour d'essai",
            trialNotice(
                entitlementOf(license(trialStartedAt = T0), T0 + (13.5 * DAY_MS).toLong()),
            ),
        )
        assertNull(trialNotice(Entitlement.Paid))
        assertNull(trialNotice(Entitlement.Expired))
        assertNull(trialNotice(Entitlement.Unknown))
    }
}

class PaidFailOpenTest {

    @Test
    fun `beats an exhausted trial`() {
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = T0 - 100 * DAY_MS)
        assertEquals(Entitlement.Paid, entitlementOf(s, T0))
    }

    @Test
    fun `stays paid through a long offline stretch`() {
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = T0 - 100 * DAY_MS)
        // A fortnight in a cottage with no signal is not a reason to lock the app.
        assertEquals(Entitlement.Paid, entitlementOf(s, T0 + OFFLINE_GRACE_MS - DAY_MS))
        // The staleness test is `>`, so exactly 14 days is still fresh.
        assertEquals(Entitlement.Paid, entitlementOf(s, T0 + OFFLINE_GRACE_MS))
    }

    @Test
    fun `falls back to the trial clock only once the grace window is spent`() {
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = T0 - 100 * DAY_MS)
        assertEquals(Entitlement.Expired, entitlementOf(s, T0 + OFFLINE_GRACE_MS + DAY_MS))
    }

    @Test
    fun `never expires a paid family that has simply never been verified`() {
        // verifiedAt null = we believe the flag and have nothing to age it against.
        val s = license(paid = true, verifiedAt = null, trialStartedAt = T0 - 100 * DAY_MS)
        assertEquals(Entitlement.Paid, entitlementOf(s, T0 + 10 * 365 * DAY_MS))
    }

    @Test
    fun `a spent grace window falls through to the trial clock, it does not lock`() {
        // The fall-through, stated on its own: a paid family whose grace ran out
        // and whose trial never started gets a trial, not a lockout.
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = null)
        val e = entitlementOf(s, T0 + OFFLINE_GRACE_MS + DAY_MS)
        assertEquals(TRIAL_DAYS, daysLeft(e))
        assertTrue(canPlay(e))
    }
}

class ApplySnapshotTest {

    @Test
    fun `refuses to downgrade anything when the store is unreachable`() {
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = T0)
        val after = applySnapshot(s, StoreSnapshot.UNREACHABLE, T0 + DAY_MS)
        assertTrue(after.paid)
        // Not re-stamped: it was never confirmed.
        assertEquals(T0, after.verifiedAt)
        assertEquals(Entitlement.Paid, entitlementOf(after, T0 + DAY_MS))
    }

    @Test
    fun `grants paid when the store confirms ownership`() {
        val after = applySnapshot(
            license(trialStartedAt = T0 - 100 * DAY_MS),
            StoreSnapshot(paid = true, trialStartedAt = null, reachable = true),
            T0,
        )
        assertEquals(Entitlement.Paid, entitlementOf(after, T0))
    }

    @Test
    fun `honours a refund once the store says so out loud`() {
        val s = license(paid = true, verifiedAt = T0, trialStartedAt = T0 - 100 * DAY_MS)
        val after = applySnapshot(
            s,
            StoreSnapshot(paid = false, trialStartedAt = null, reachable = true),
            T0,
        )
        assertEquals(Entitlement.Expired, entitlementOf(after, T0))
    }

    @Test
    fun `takes the EARLIEST trial start, so reinstalling buys nothing`() {
        // Local stamp says today; a signed receipt says twelve days ago.
        val s = license(trialStartedAt = T0)
        val after = applySnapshot(
            s,
            StoreSnapshot(paid = false, trialStartedAt = T0 - 12 * DAY_MS, reachable = true),
            T0,
        )
        assertEquals(T0 - 12 * DAY_MS, after.trialStartedAt)
        assertEquals(2, daysLeft(entitlementOf(after, T0)))
    }

    @Test
    fun `keeps the local stamp when the platform cannot prove one, which on Android is always`() {
        val s = license(trialStartedAt = T0 - 5 * DAY_MS)
        val after = applySnapshot(
            s,
            StoreSnapshot(paid = false, trialStartedAt = null, reachable = true),
            T0,
        )
        assertEquals(T0 - 5 * DAY_MS, after.trialStartedAt)
    }

    @Test
    fun `an authoritative start that is LATER than the local stamp is ignored`() {
        val s = license(trialStartedAt = T0 - 5 * DAY_MS)
        val after = applySnapshot(
            s,
            StoreSnapshot(paid = false, trialStartedAt = T0, reachable = true),
            T0,
        )
        assertEquals(T0 - 5 * DAY_MS, after.trialStartedAt)
    }

    @Test
    fun `advances the clock high-water mark on every check`() {
        assertEquals(T0, applySnapshot(license(), StoreSnapshot.UNREACHABLE, T0).clockHighWater)
    }
}
