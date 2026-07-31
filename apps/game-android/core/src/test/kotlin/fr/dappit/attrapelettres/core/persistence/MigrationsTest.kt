package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The upgrade path — port of the migration half of `useProfile.test.tsx`, via
// MigrationTests.swift (the worked iOS port; assertions copied, not
// re-derived). A child who has been playing for months is sitting on a v3
// blob; the v4 counters must reproduce her stars EXACTLY, not approximately.
// Per storage.ts, the old keys and readers stay intact so a rollback still
// finds data it understands.

private const val DEVICE = "this-phone"
private const val NOW: Millis = 1_700_000_000_000

/** The exact V3 fixture from `useProfile.test.tsx`. */
private val v3RosterJson =
    """{"children":[{"id":"lea-v3","name":"Léa","profile":{"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":3,"colors":{"furColor":"#F80"},"styles":{},"accessories":[]},"owned":["fox.fur.orange"]}},"balance":17,"ledger":{"read-image:1":2}}}],"activeId":"lea-v3"}"""

/** The exact V1 fixture from `useProfile.test.tsx`. */
private val v1ProfileJson =
    """{"chosen":true,"config":{"species":"cat","stage":1,"colors":{},"styles":{},"accessories":[]},"balance":5,"ledger":{"first-letter:1":1},"owned":["cat.whiskers.long"]}"""

class MigrationV3Test {
    private fun migrated(): Pair<InMemoryKVStore, Roster> {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V3_KEY to v3RosterJson))
        return kv to initialRoster(kv, DEVICE, NOW)
    }

    @Test
    fun `carries stars, clears, look and items across untouched`() {
        val (_, roster) = migrated()
        assertEquals(1, roster.children.size)
        val lea = roster.children[0]
        assertEquals("Léa", lea.name)
        assertEquals("lea-v3", lea.id)
        assertEquals("lea-v3", roster.activeId)

        // The flat totals became "everything earned on THIS device" — the
        // exact seeding that lets two migrated phones SUM when they meet.
        assertEquals(mapOf(DEVICE to 17), lea.profile.stars.earned)
        assertEquals(emptyMap(), lea.profile.stars.spent)
        assertEquals(mapOf(DEVICE to 2), lea.profile.clears["read-image:1"])

        assertTrue(lea.profile.chosen)
        assertEquals(Species.FOX, lea.profile.current)
        val fox = lea.profile.species.getValue(Species.FOX)
        assertEquals(3, fox.config.stage)
        assertEquals("#F80", fox.config.colors["furColor"])
        assertEquals(listOf("fox.fur.orange"), fox.owned)
        // No stamps existed pre-v4: everything starts at the always-losing rev.
        assertEquals(Rev.ZERO, fox.rev)
        assertEquals(Rev.ZERO, lea.nameRev)
        assertEquals(Rev.ZERO, lea.profile.currentRev)
    }

    @Test
    fun `stamps touchedAt now, and no tombstones`() {
        // "Fresh migration: nothing can have tombstoned these yet, and marking
        // them touched keeps a future stale tombstone from erasing them."
        val (_, roster) = migrated()
        assertEquals(NOW, roster.children[0].touchedAt)
        assertTrue(roster.removed.isEmpty())
    }

    @Test
    fun `the untouched species slots come out blank, not absent`() {
        val (_, roster) = migrated()
        val p = roster.children[0].profile
        assertEquals(Species.entries.toList(), p.species.keys.toList())
        for (s in Species.entries) {
            if (s == Species.FOX) continue
            assertEquals(blankProgress(s), p.species.getValue(s))
        }
    }

    @Test
    fun `leaves the v3 blob in place, and writes nothing`() {
        // "leaves the v3 blob in place so a rollback still reads data" — and
        // initialRoster computes without saving (the first commit persists).
        val (kv, _) = migrated()
        assertEquals(v3RosterJson, kv.string(ProfileStorage.V3_KEY))
        assertNull(kv.string(ProfileStorage.ROSTER_KEY))
    }

    @Test
    fun `a v3 activeId pointing nowhere is dropped`() {
        val json = v3RosterJson.replace("\"activeId\":\"lea-v3\"", "\"activeId\":\"ghost\"")
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V3_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        assertNull(roster.activeId)
    }

    @Test
    fun `an empty string id gets a fresh one`() {
        // TS `c.id || newId()` — an empty string id is replaced too.
        val json = v3RosterJson.replace("\"id\":\"lea-v3\"", "\"id\":\"\"")
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V3_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        assertTrue(roster.children[0].id.isNotEmpty())
        // The stale activeId no longer matches anything.
        assertNull(roster.activeId)
    }

    @Test
    fun `an empty-children v3 falls through to v1`() {
        // TS gate is `v3?.children?.length` — an empty roster falls through.
        val kv = InMemoryKVStore(
            mapOf(
                ProfileStorage.V3_KEY to """{"children":[],"activeId":null}""",
                ProfileStorage.V1_KEY to v1ProfileJson,
            ),
        )
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        assertEquals("Joueur 1", roster.children[0].name)
        assertEquals(Species.CAT, roster.children[0].profile.current)
    }

    @Test
    fun `zero and negative legacy values seed no keys`() {
        // Zero or negative legacy values produce NO key, not a zero key.
        val flat = LegacyFlatProfile(balance = 0.0, ledger = mapOf("a:1" to 0.0, "b:2" to -3.0))
        val p = migrateFlatProfile(flat, DEVICE)
        assertTrue(p.stars.earned.isEmpty())
        assertTrue(p.clears.isEmpty())
    }

    @Test
    fun `a flat profile's chosen defaults false`() {
        val p = migrateFlatProfile(LegacyFlatProfile(), DEVICE)
        assertFalse(p.chosen)
        assertEquals(Species.UNICORN, p.current)
    }
}

class MigrationV1Test {
    @Test
    fun `promotes the single legacy mascot into its species slot`() {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V1_KEY to v1ProfileJson))
        val roster = initialRoster(kv, DEVICE, NOW)

        assertEquals(1, roster.children.size)
        val c = roster.children[0]
        assertEquals("Joueur 1", c.name)
        assertEquals(c.id, roster.activeId)
        assertEquals(Rev(at = NOW, by = DEVICE), c.nameRev)
        assertEquals(NOW, c.touchedAt)

        assertTrue(c.profile.chosen)
        assertEquals(Species.CAT, c.profile.current)
        assertEquals(1, c.profile.species.getValue(Species.CAT).config.stage)
        assertEquals(listOf("cat.whiskers.long"), c.profile.species.getValue(Species.CAT).owned)
        assertEquals(mapOf(DEVICE to 5), c.profile.stars.earned)
        assertEquals(mapOf(DEVICE to 1), c.profile.clears["first-letter:1"])
    }

    @Test
    fun `a v1 profile's chosen defaults true`() {
        // v1 users had necessarily chosen — TS `l.chosen ?? true`.
        val p = migrateV1Profile(LegacyV1Profile(config = LooseMascotConfig(species = "cat")), DEVICE)
        assertTrue(p.chosen)
        assertEquals(Species.CAT, p.current)
    }

    @Test
    fun `a v1 profile missing its config defaults to unicorn`() {
        val p = migrateV1Profile(LegacyV1Profile(balance = 3.0), DEVICE)
        assertEquals(Species.UNICORN, p.current)
        assertEquals(mapOf(DEVICE to 3), p.stars.earned)
    }
}

class MigrationV2Test {
    @Test
    fun `wraps the single child around the flat profile`() {
        val v2Json =
            """{"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":2,"colors":{},"styles":{},"accessories":[]},"owned":[]}},"balance":9,"ledger":{"read-image:1":1}}"""
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V2_KEY to v2Json))
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        val c = roster.children[0]
        assertEquals("Joueur 1", c.name)
        assertEquals(c.id, roster.activeId)
        assertEquals(Species.FOX, c.profile.current)
        assertEquals(2, c.profile.species.getValue(Species.FOX).config.stage)
        assertEquals(mapOf(DEVICE to 9), c.profile.stars.earned)
        assertEquals(mapOf(DEVICE to 1), c.profile.clears["read-image:1"])
        // v2 blob left in place (forward, additive migration).
        assertEquals(v2Json, kv.string(ProfileStorage.V2_KEY))
    }
}

class MigrationV4Test {
    @Test
    fun `v4 wins over every older blob`() {
        val v4 = Roster(
            children = listOf(child("Léa", DEFAULT_PROFILE, DEVICE, NOW)),
            activeId = null,
            removed = emptyMap(),
        )
        val kv = InMemoryKVStore(
            mapOf(ProfileStorage.V3_KEY to v3RosterJson, ProfileStorage.V1_KEY to v1ProfileJson),
        )
        ProfileStorage.saveRoster(v4, kv)
        val roster = initialRoster(kv, DEVICE, NOW)
        assertEquals(1, roster.children.size)
        assertEquals("Léa", roster.children[0].name)
        // The v3 name would have been "Léa" too — pin the v4 provenance by id.
        assertEquals(v4.children[0].id, roster.children[0].id)
    }

    @Test
    fun `normalize keeps tombstones, and drops a stale activeId`() {
        val json = """{"children":[],"activeId":"gone","removed":{"gone":123}}"""
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        assertTrue(roster.children.isEmpty())
        assertNull(roster.activeId)
        assertEquals(mapOf("gone" to 123L), roster.removed)
    }

    @Test
    fun `an unknown species key is dropped, and an unknown current becomes unicorn`() {
        // The two documented deviations from JS truthiness, pinned: unknown
        // species keys are dropped (same as JS), an unknown `current` becomes
        // UNICORN (JS kept the garbage string; the enum cannot).
        val json =
            """{"children":[{"id":"c1","name":"Léa","nameRev":{"at":1,"by":"d"},"touchedAt":2,"profile":{"chosen":true,"current":"dodo","currentRev":{"at":1,"by":"d"},"species":{"dodo":{"config":{"species":"dodo","stage":9,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":1,"by":"d"}}},"stars":{"earned":{},"spent":{}},"clears":{}}}],"activeId":"c1","removed":{}}"""
        val kv = InMemoryKVStore(mapOf(ProfileStorage.ROSTER_KEY to json))
        val roster = initialRoster(kv, DEVICE, NOW)
        val p = roster.children[0].profile
        assertEquals(Species.UNICORN, p.current)
        for (s in Species.entries) {
            assertEquals(0, p.species.getValue(s).config.stage) // the dodo's 9 went nowhere
        }
    }

    @Test
    fun `the species slot key overrides the stored config species`() {
        // TS: `{ ...blankConfig(s), ...src.config, species: s }` — the slot key
        // always wins over whatever species the stored config claims.
        val loose = mapOf("fox" to LooseSpeciesProgress(config = LooseMascotConfig(species = "cat", stage = 4.0)))
        val map = normalizeSpecies(loose)
        assertEquals(Species.FOX, map.getValue(Species.FOX).config.species)
        assertEquals(4, map.getValue(Species.FOX).config.stage)
    }

    @Test
    fun `a partial rev normalises field-wise`() {
        assertEquals(Rev(at = 5, by = ""), normalizeRev(LooseRev(at = 5.0)))
        assertEquals(Rev.ZERO, normalizeRev(null))
    }

    @Test
    fun `empty storage gives the empty roster`() {
        val roster = initialRoster(InMemoryKVStore(), DEVICE, NOW)
        assertEquals(Roster(children = emptyList(), activeId = null, removed = emptyMap()), roster)
    }
}

class ChildHelperTest {
    @Test
    fun `trims, and falls back to Joueur`() {
        val trimmed = child("  Léo  ", DEFAULT_PROFILE, DEVICE, NOW)
        assertEquals("Léo", trimmed.name)
        val blank = child("   ", DEFAULT_PROFILE, DEVICE, NOW)
        assertEquals("Joueur", blank.name)
    }

    @Test
    fun `does not clamp long names`() {
        // NB oddity, ported as-is: `child()` (and so createChild) does NOT
        // clamp to 14 characters — only renameChild does.
        val long = "a".repeat(30)
        assertEquals(long, child(long, DEFAULT_PROFILE, DEVICE, NOW).name)
    }

    @Test
    fun `stamps nameRev and touchedAt with the given clock`() {
        val c = child("Léa", DEFAULT_PROFILE, DEVICE, NOW)
        assertEquals(Rev(at = NOW, by = DEVICE), c.nameRev)
        assertEquals(NOW, c.touchedAt)
        assertTrue(c.id.isNotEmpty())
        assertEquals(c.id.lowercase(), c.id) // ids mint lowercase, like JS uuids
    }

    @Test
    fun `mints distinct ids`() {
        val a = child("Léa", DEFAULT_PROFILE, DEVICE, NOW)
        val b = child("Tom", DEFAULT_PROFILE, DEVICE, NOW)
        assertNotEquals(a.id, b.id)
    }
}
