package fr.dappit.attrapelettres.core.licensing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/* -------------------------------------------------------------------------- */
/* Invariant 11 as PROPERTIES: no combination of store state and clock value    */
/* may produce a lockout for a family that has paid.                           */
/*                                                                             */
/* This is the proving test named in ARCHITECTURE.md §3 for row 11. The         */
/* example suite (EntitlementTest) pins the scenarios the web app pinned; this  */
/* one sweeps the space between them, because the failure mode being guarded    */
/* against is not "the fortnight is off by a day" — it is "some corner of the   */
/* state space locks a paying six-year-old out", and corners are exactly what   */
/* examples miss.                                                              */
/*                                                                             */
/* Every sweep is seeded, so a failure reproduces exactly.                     */
/* -------------------------------------------------------------------------- */

private const val T0 = 1_700_000_000_000L
private const val ITERATIONS = 10_000

/**
 * SplitMix64 — deterministic, seedable, four lines.
 *
 * Hand-rolled rather than reaching for the game's injected `RandomSource`
 * (support/RandomSource.kt): that one exists so round builders are testable and
 * it deals in `Int` bounds, while these sweeps need full-width `Long` epoch
 * values. Licensing has no business depending on the round-builder support
 * module either way. Deliberately not `kotlin.random.Random`, per the port's
 * standing rule that randomness is injected and never reached for globally.
 */
private class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    /** Uniform in `low..high`, inclusive. */
    fun long(low: Long, high: Long): Long {
        require(high >= low)
        val span = (high - low).toULong() + 1uL
        if (span == 0uL) return nextLong()
        return low + (nextLong().toULong() % span).toLong()
    }

    fun bool(): Boolean = nextLong() and 1L == 1L

    private companion object {
        const val GOLDEN: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX_A: Long = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX_B: Long = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/**
 * `paid = 3 > trial = 2 > unknown = 1 > expired = 0`.
 *
 * Written as an exhaustive `when` with **no `else`** so a future fifth
 * [Entitlement] case fails to compile here rather than silently ranking as
 * locked. That is a property of the type system doing the work, and it is the
 * same reason `canPlay` is a blacklist of one.
 */
private fun rank(e: Entitlement): Int = when (e) {
    is Entitlement.Paid -> 3
    is Entitlement.Trial -> 2
    is Entitlement.Unknown -> 1
    is Entitlement.Expired -> 0
}

class EntitlementPropertyTest {

    /**
     * P1. An unreachable answer never downgrades: applying it can only raise the
     * clock, never lower the verdict — from ANY starting state at all.
     */
    @Test
    fun `an unreachable store never downgrades any state`() {
        val rng = SplitMix64(0x0000_0000_A11C_E511L)
        repeat(ITERATIONS) {
            val now = rng.long(0, T0 * 2)
            val state = LicenseState(
                paid = rng.bool(),
                verifiedAt = if (rng.bool()) rng.long(0, T0 * 2) else null,
                trialStartedAt = if (rng.bool()) rng.long(0, T0 * 2) else null,
                clockHighWater = rng.long(0, T0 * 2),
            )
            val before = entitlementOf(state, now)
            val after = entitlementOf(applySnapshot(state, StoreSnapshot.UNREACHABLE, now), now)
            assertTrue(rank(after) >= rank(before), "unreachable downgraded $before to $after")
            // And the stronger statement the invariant actually needs:
            if (canPlay(before)) {
                assertTrue(canPlay(after), "unreachable locked out a playable state: $state")
            }
        }
    }

    /**
     * P2. A confirmed paid family plays for a fortnight offline — every single
     * millisecond of it, boundary included.
     */
    @Test
    fun `a confirmed paid family plays through the whole grace window`() {
        val rng = SplitMix64(0x0000_0000_0FF1_11E5L)
        repeat(ITERATIONS) {
            val verifiedAt = rng.long(0, T0 * 2)
            val elapsed = rng.long(0, OFFLINE_GRACE_MS)
            val now = verifiedAt + elapsed
            val state = LicenseState(
                paid = true,
                verifiedAt = verifiedAt,
                // Arbitrary, including a long-exhausted trial: paid beats it.
                trialStartedAt = if (rng.bool()) rng.long(0, now) else null,
                // The high-water mark can be anywhere at or below `now`; a HIGHER
                // one is a clock that already ran past the grace, which is a
                // legitimate expiry and not part of this property.
                clockHighWater = rng.long(0, now),
            )
            assertEquals(Entitlement.Paid, entitlementOf(state, now), "locked out at +$elapsed")
        }
        // The boundary, stated exactly: the staleness test is `>`, not `>=`.
        val s = LicenseState(paid = true, verifiedAt = T0)
        assertEquals(Entitlement.Paid, entitlementOf(s, T0 + OFFLINE_GRACE_MS))
        assertNotEquals(Entitlement.Paid, entitlementOf(s, T0 + OFFLINE_GRACE_MS + 1))
    }

    /**
     * P3. Never-verified paid is permanent. There is nothing to age the flag
     * against, so we believe it — ten years out, a century out.
     */
    @Test
    fun `a never-verified paid flag never expires`() {
        val rng = SplitMix64(0x0000_0000_DEAD_BEEFL)
        repeat(ITERATIONS) {
            val now = rng.long(0, T0 * 4)
            val state = LicenseState(
                paid = true,
                verifiedAt = null,
                trialStartedAt = if (rng.bool()) rng.long(0, T0 * 4) else null,
                clockHighWater = rng.long(0, T0 * 4),
            )
            assertEquals(Entitlement.Paid, entitlementOf(state, now))
        }
    }

    /**
     * P4. Clock rewind is inert: below the high-water mark the answer is the
     * answer AT the high-water mark.
     *
     * This is the one deliberately fail-CLOSED rule in the module — winding the
     * device date back is the free trial extension, and it costs three lines to
     * defeat. Note it can only ever hold an answer STILL, never lower it.
     */
    @Test
    fun `winding the device clock back changes nothing`() {
        val rng = SplitMix64(0x0000_0000_C10C_C10CL)
        repeat(ITERATIONS) {
            val highWater = rng.long(T0, T0 * 2)
            val state = LicenseState(
                paid = rng.bool(),
                verifiedAt = if (rng.bool()) rng.long(0, T0 * 2) else null,
                trialStartedAt = if (rng.bool()) rng.long(0, T0 * 2) else null,
                clockHighWater = highWater,
            )
            val rewound = rng.long(0, highWater)
            assertEquals(entitlementOf(state, highWater), entitlementOf(state, rewound))
        }
    }

    /**
     * P5. `canPlay` is total, and it is a blacklist of one. `Unknown` plays —
     * that is the whole reason the case still exists.
     */
    @Test
    fun `canPlay is total and only expired is blocked`() {
        val cases = listOf(
            Entitlement.Unknown,
            Entitlement.Trial(TRIAL_DAYS, T0),
            Entitlement.Trial(1, T0),
            Entitlement.Expired,
            Entitlement.Paid,
        )
        for (e in cases) {
            assertEquals(rank(e) != 0, canPlay(e), "canPlay disagreed with rank for $e")
            assertEquals(e != Entitlement.Expired, canPlay(e))
        }
        assertTrue(canPlay(Entitlement.Unknown), "the store not having answered must not lock")
        assertFalse(canPlay(Entitlement.Expired))
    }

    /**
     * P6. Monotone countdown: for a fixed started trial `daysLeft` never rises as
     * time advances, `endsAt` is a stable date, and `Expired` is reached exactly
     * once and never left.
     */
    @Test
    fun `the trial countdown is monotone and expiry is absorbing`() {
        val started = T0
        var previous = Int.MAX_VALUE
        var expiredAt: Long? = null
        var t = started
        // Every quarter-day across three weeks.
        while (t <= started + TRIAL_MS + 7 * DAY_MS) {
            when (val e = entitlementOf(LicenseState(trialStartedAt = started), t)) {
                is Entitlement.Trial -> {
                    assertEquals(null, expiredAt, "left Expired after entering it")
                    assertTrue(e.daysLeft <= previous, "daysLeft rose at $t")
                    assertEquals(started + TRIAL_MS, e.endsAt)
                    previous = e.daysLeft
                }

                is Entitlement.Expired -> if (expiredAt == null) expiredAt = t
                is Entitlement.Paid, is Entitlement.Unknown ->
                    fail("a started, unpaid trial produced $e")
            }
            t += DAY_MS / 4
        }
        assertEquals(started + TRIAL_MS, expiredAt)
    }

    /**
     * The umbrella statement, swept: a family that has ever been confirmed paid
     * is never locked out while the grace window is open, whatever the trial
     * clock says and whatever the store does next.
     *
     * This is the property ARCHITECTURE.md row 11 names. It is deliberately
     * stated over a SEQUENCE of store answers rather than one, because the
     * failure that would break it in practice is cumulative: an app that
     * re-stamped `verifiedAt` — or cleared `paid` — on a call that never reached
     * Google would walk a paying household down to a paywall over a weekend
     * without a single individual step looking wrong.
     */
    @Test
    fun `no sequence of unreachable answers can lock a paid family out`() {
        val rng = SplitMix64(0x0000_0000_09A1_D0FFL)
        repeat(ITERATIONS) {
            val verifiedAt = rng.long(T0, T0 * 2)
            var state = LicenseState(
                paid = true,
                verifiedAt = verifiedAt,
                trialStartedAt = verifiedAt - rng.long(0, 400 * DAY_MS),
                clockHighWater = verifiedAt,
            )
            repeat(5) {
                val now = verifiedAt + rng.long(0, OFFLINE_GRACE_MS)
                state = applySnapshot(state, StoreSnapshot.UNREACHABLE, now)
                assertTrue(canPlay(entitlementOf(state, now)))
                assertEquals(Entitlement.Paid, entitlementOf(state, now))
            }
        }
    }

    /**
     * P7. The blank licence plays, at every clock value there is. This is the
     * state a fresh install, a wiped install, a corrupt blob and a failed write
     * all land in, so it is the single most-reached state in the module.
     */
    @Test
    fun `a blank licence plays at any clock value`() {
        val rng = SplitMix64(0x0000_0000_B1A0_C0DEL)
        assertTrue(canPlay(entitlementOf(BLANK_LICENSE, 0)))
        assertTrue(canPlay(entitlementOf(BLANK_LICENSE, T0)))
        assertTrue(canPlay(entitlementOf(BLANK_LICENSE, Long.MAX_VALUE / 2)))
        repeat(ITERATIONS) {
            val now = rng.long(0, T0 * 4)
            assertEquals(TRIAL_DAYS, (entitlementOf(BLANK_LICENSE, now) as Entitlement.Trial).daysLeft)
        }
    }
}
