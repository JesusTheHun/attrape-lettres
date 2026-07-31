package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// storage.ts behaviour: synchronous and TOTAL — a caller gets a value or a
// safe default, never a throw. Corruption degrades, it never erases. Ported
// from ProfileStorageTests.swift; assertions copied, not re-derived.

private const val DEVICE = "this-phone"
private const val NOW: Millis = 1_700_000_000_000

class ProfileStorageTest {

    @Test
    fun `keys are byte-exact`() {
        // These strings are a persistence contract shared with the web app and
        // the retired Capacitor build. Changing one orphans a family's roster.
        assertEquals("attrape-lettres:roster:v4", ProfileStorage.ROSTER_KEY)
        assertEquals("attrape-lettres:roster:v3", ProfileStorage.V3_KEY)
        assertEquals("attrape-lettres:profile:v2", ProfileStorage.V2_KEY)
        assertEquals("attrape-lettres:profile:v1", ProfileStorage.V1_KEY)
        assertEquals("attrape-lettres:shop-seen:v1", ProfileStorage.SHOP_SEEN_KEY)
    }

    @Test
    fun `loadRoster is null when absent`() {
        assertNull(ProfileStorage.loadRoster(InMemoryKVStore()))
    }

    @Test
    fun `loadRoster is null on unparseable JSON`() {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to "not json {"))
        assertNull(ProfileStorage.loadRoster(kv))
    }

    @Test
    fun `loadRoster is null on a wrong top-level shape`() {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to "[1,2,3]"))
        assertNull(ProfileStorage.loadRoster(kv))
    }

    @Test
    fun `a partially corrupt blob degrades instead of vanishing`() {
        // The point of LooseDecoding: wrong-typed fields read as absent and
        // take their defaults, instead of the whole roster reading as null and
        // a child losing everything.
        val json =
            """{"children":[{"id":42,"name":"Léa","nameRev":"bogus","touchedAt":"soon","profile":{"chosen":"yes","current":"fox","currentRev":{"at":1,"by":"d"},"species":{},"stars":{"earned":{"d":7},"spent":{}},"clears":{}}}],"activeId":null,"removed":{}}"""
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        val c = roster.children[0]
        assertEquals("Léa", c.name) // the good fields survive
        assertTrue(c.id.isNotEmpty()) // id: 42 → absent → freshly minted
        assertEquals(Rev.ZERO, c.nameRev) // "bogus" → absent → zero rev
        assertEquals(0L, c.touchedAt) // "soon" → absent → 0
        assertFalse(c.profile.chosen) // "yes" → absent → false
        assertEquals(Species.FOX, c.profile.current)
        assertEquals(mapOf("d" to 7), c.profile.stars.earned) // the stars survive
    }

    @Test
    fun `save then load round-trips through the loose layer`() {
        val dragon = blankProgress(Species.DRAGON).let {
            it.copy(
                config = it.config.copy(stage = 5),
                owned = listOf("dragon.wings.gold"),
                rev = Rev(NOW, DEVICE),
            )
        }
        val profile = DEFAULT_PROFILE.copy(
            chosen = true,
            current = Species.DRAGON,
            currentRev = Rev(NOW, DEVICE),
            species = DEFAULT_PROFILE.species + (Species.DRAGON to dragon),
            stars = StarCounters(earned = mapOf(DEVICE to 12, "mum-phone" to 6), spent = mapOf(DEVICE to 4)),
            clears = mapOf("read-image:1" to mapOf(DEVICE to 2, "mum-phone" to 1)),
        )
        val roster = Roster(
            children = listOf(
                ChildProfile(id = "c1", name = "Léa", nameRev = Rev(NOW, DEVICE), touchedAt = NOW, profile = profile),
            ),
            activeId = "c1",
            removed = mapOf("old-child" to 123L),
        )

        val kv = InMemoryKVStore()
        ProfileStorage.saveRoster(roster, kv)
        assertEquals(roster, initialRoster(kv, DEVICE, NOW + 1))
    }

    @Test
    fun `a saved empty roster writes activeId null explicitly, like JSON stringify`() {
        // The web app's `JSON.stringify` writes the null; matching it keeps the
        // blobs byte-comparable across the three platforms. (On iOS this lives
        // in a custom `encode(to:)`; here it is ProfileStorage's encoder
        // keeping kotlinx's default `explicitNulls = true`.)
        val kv = InMemoryKVStore()
        ProfileStorage.saveRoster(Roster(children = emptyList(), activeId = null, removed = emptyMap()), kv)
        assertEquals("""{"children":[],"activeId":null,"removed":{}}""", kv.string(ProfileStorage.ROSTER_KEY))
    }

    @Test
    fun `float numbers are tolerated and truncated`() {
        // A hypothetical JS writer emitting floats must not turn the whole
        // blob into "no roster". The loose layer truncates.
        val json =
            """{"children":[{"id":"c1","name":"Léa","nameRev":{"at":1.7e12,"by":"d"},"touchedAt":1.7e12,"profile":{"chosen":true,"current":"cat","currentRev":{"at":0,"by":""},"species":{},"stars":{"earned":{"d":17.0},"spent":{}},"clears":{"a:1":{"d":2.0}}}}],"activeId":"c1","removed":{"x":9.5}}"""
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        val c = roster.children[0]
        assertEquals(1_700_000_000_000, c.touchedAt)
        assertEquals(mapOf("d" to 17), c.profile.stars.earned)
        assertEquals(mapOf("d" to 2), c.profile.clears["a:1"])
        assertEquals(mapOf("x" to 9L), roster.removed)
    }

    @Test
    fun `shop-seen round-trips and defaults empty`() {
        val kv = InMemoryKVStore()
        assertEquals(emptyMap(), ProfileStorage.loadShopSeen(kv))
        ProfileStorage.saveShopSeen(mapOf("c1" to 17, "c2" to 0), kv)
        assertEquals(mapOf("c1" to 17, "c2" to 0), ProfileStorage.loadShopSeen(kv))
        // Corruption degrades to the empty default — the meter just won't
        // animate next visit.
        kv.set(ProfileStorage.SHOP_SEEN_KEY, "###")
        assertEquals(emptyMap(), ProfileStorage.loadShopSeen(kv))
    }

    @Test
    fun `legacy loaders are decode-or-null`() {
        val kv = InMemoryKVStore(
            mapOf(
                ProfileStorage.V3_KEY to "garbage",
                ProfileStorage.V2_KEY to "null",
                ProfileStorage.V1_KEY to "[]",
            ),
        )
        assertNull(ProfileStorage.loadV3Roster(kv))
        assertNull(ProfileStorage.loadV2Profile(kv))
        assertNull(ProfileStorage.loadV1Profile(kv))
        // And absent keys are null too, so initialRoster lands on empty.
        val empty = InMemoryKVStore()
        assertNull(ProfileStorage.loadV3Roster(empty))
        assertNull(ProfileStorage.loadV2Profile(empty))
        assertNull(ProfileStorage.loadV1Profile(empty))
    }

    @Test
    fun `saved roster JSON never contains a total`() {
        // Invariant 9 at the storage boundary: what saveRoster writes carries
        // counters, never a folded total.
        val profile = DEFAULT_PROFILE.copy(
            stars = StarCounters(earned = mapOf(DEVICE to 10), spent = mapOf(DEVICE to 3)),
        )
        val roster = Roster(
            children = listOf(
                ChildProfile(id = "c1", name = "Léa", nameRev = Rev.ZERO, touchedAt = NOW, profile = profile),
            ),
            activeId = "c1",
            removed = emptyMap(),
        )
        val kv = InMemoryKVStore()
        ProfileStorage.saveRoster(roster, kv)
        val json = kv.string(ProfileStorage.ROSTER_KEY)
        assertNotNull(json)
        assertTrue(json.contains("\"earned\""))
        assertFalse(json.contains("\"balance\""))
        assertFalse(json.contains("\"ledger\""))
    }
}
