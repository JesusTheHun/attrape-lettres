package fr.dappit.attrapelettres.core.licensing

import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.KVStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Port of apps/game-web/src/licensing/persist.ts's behaviour, and of           */
/* LicenseStoreTests.swift.                                                    */
/*                                                                             */
/* Every one of these is really the same assertion wearing a different hat:     */
/* whatever the storage layer does wrong, the family still plays.              */
/* -------------------------------------------------------------------------- */

private const val T0 = 1_700_000_000_000L

/**
 * A store that accepts writes and drops them. `SharedPreferences` has no
 * throwing failure mode, so "a write failure" is modelled as a write that does
 * not stick — which is what a full disk or a locked device container amounts
 * to, and it is why `KVStore` has no error channel (A3).
 */
private class WriteFailingKVStore : KVStore {
    private val inner = InMemoryKVStore()
    override fun string(key: String): String? = inner.string(key)
    override fun set(key: String, value: String) = Unit
    override fun remove(key: String) = inner.remove(key)
}

class LicenseStoreTest {

    @Test
    fun `key names are a persistence contract with the shipped PWA and the iOS app`() {
        assertEquals("attrape-lettres:license:v1", LicenseStore.LICENSE_KEY)
        assertEquals("attrape-lettres:onboarded:v1", LicenseStore.ONBOARDED_KEY)
    }

    @Test
    fun `round-trips a populated state`() {
        val store = LicenseStore(InMemoryKVStore())
        val s = LicenseState(
            paid = true,
            verifiedAt = T0,
            trialStartedAt = T0 - 3 * DAY_MS,
            clockHighWater = T0 + 1,
        )
        store.save(s)
        assertEquals(s, store.load())
    }

    @Test
    fun `an absent blob is a blank licence, and a blank licence PLAYS`() {
        val store = LicenseStore(InMemoryKVStore())
        assertEquals(BLANK_LICENSE, store.load())
        assertTrue(canPlay(entitlementOf(store.load(), 0)))
        assertTrue(canPlay(entitlementOf(store.load(), T0)))
        assertTrue(canPlay(entitlementOf(store.load(), T0 * 4)))
    }

    @Test
    fun `writes an explicit null rather than dropping the key`() {
        // `JSON.stringify` emits `"verifiedAt":null`; kotlinx's default
        // `encodeDefaults = false` would omit every field equal to its default
        // and a blank licence would serialise as `{}`, which the PWA and the iOS
        // app would then read as… a blank licence, but only by luck. The blob is
        // a cross-platform contract, so all four keys are written, always.
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(BLANK_LICENSE)
        val raw = assertNotNull(kv.string(LicenseStore.LICENSE_KEY))
        val obj = Json.parseToJsonElement(raw) as JsonObject
        assertEquals(
            listOf("clockHighWater", "paid", "trialStartedAt", "verifiedAt"),
            obj.keys.sorted(),
        )
        assertEquals(JsonNull, obj["verifiedAt"])
        assertEquals(JsonNull, obj["trialStartedAt"])
    }

    @Test
    fun `corrupt bytes degrade to blank rather than throwing`() {
        assertEquals(
            BLANK_LICENSE,
            LicenseStore(InMemoryKVStore(mapOf(LicenseStore.LICENSE_KEY to "{not json at all")))
                .load(),
        )
        assertEquals(
            BLANK_LICENSE,
            LicenseStore(InMemoryKVStore(mapOf(LicenseStore.LICENSE_KEY to ""))).load(),
        )
        // A valid JSON document of the wrong shape is corrupt too.
        assertEquals(
            BLANK_LICENSE,
            LicenseStore(InMemoryKVStore(mapOf(LicenseStore.LICENSE_KEY to "[1,2,3]"))).load(),
        )
    }

    @Test
    fun `a wrong-typed field degrades to its default and the others survive`() {
        // `paid` is a string and `verifiedAt` is a string: both fall back, and
        // the fallbacks mean *full trial*, i.e. the child plays. Fail open all
        // the way down — and note the two GOOD fields are still read, which an
        // all-or-nothing generated decoder would have thrown away.
        val blob =
            """{"paid":"yes","verifiedAt":"soon","trialStartedAt":1700000000000,"clockHighWater":42}"""
        val loaded = LicenseStore(InMemoryKVStore(mapOf(LicenseStore.LICENSE_KEY to blob))).load()
        assertFalse(loaded.paid)
        assertNull(loaded.verifiedAt)
        assertEquals(1_700_000_000_000L, loaded.trialStartedAt)
        assertEquals(42L, loaded.clockHighWater)
    }

    @Test
    fun `missing fields take their defaults`() {
        val kv = InMemoryKVStore(mapOf(LicenseStore.LICENSE_KEY to """{"paid":true}"""))
        assertEquals(LicenseState(paid = true), LicenseStore(kv).load())
    }

    @Test
    fun `a JS float degrades by truncation rather than by being thrown away`() {
        // No blob this app ever wrote holds a fractional stamp, but a JS number
        // is a double and a strict integer read would discard a real trial date.
        val kv = InMemoryKVStore(
            mapOf(LicenseStore.LICENSE_KEY to """{"trialStartedAt":1700000000000.0}"""),
        )
        assertEquals(1_700_000_000_000L, LicenseStore(kv).load().trialStartedAt)
    }

    @Test
    fun `a write that does not stick is swallowed and mutates nothing`() {
        val store = LicenseStore(WriteFailingKVStore())
        store.save(LicenseState(paid = true, verifiedAt = T0))
        assertEquals(BLANK_LICENSE, store.load())
        // And the family still plays.
        assertTrue(canPlay(entitlementOf(store.load(), T0)))
    }

    @Test
    fun `onboarded is a separate, one-way flag`() {
        val kv = InMemoryKVStore()
        val store = LicenseStore(kv)
        assertFalse(store.loadOnboarded())
        store.saveOnboarded()
        assertTrue(store.loadOnboarded())
        assertEquals("1", kv.string(LicenseStore.ONBOARDED_KEY))
        // Anything other than "1" is not onboarded — including "true".
        kv.set(LicenseStore.ONBOARDED_KEY, "true")
        assertFalse(store.loadOnboarded())
    }

    @Test
    fun `the licence blob lives outside the roster and cannot collide with it`() {
        // Separate keys, separate contracts: a roster migration must never be
        // able to take a household's purchase with it, and vice versa.
        val kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(paid = true, verifiedAt = T0))
        LicenseStore(kv).saveOnboarded()
        assertEquals(
            setOf(LicenseStore.LICENSE_KEY, LicenseStore.ONBOARDED_KEY),
            kv.snapshot.keys,
        )
    }
}
