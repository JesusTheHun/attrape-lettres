package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/* -------------------------------------------------------------------------- */
/* Port of apps/game-web/src/sync/client.test.ts § "syncOnce", against an       */
/* in-memory household document.                                               */
/*                                                                             */
/* NOT ported, deliberately (A7): the household id, the join code, the ETag     */
/* and the 412-retry loop. All four are about how the document is ADDRESSED,    */
/* the half of the contract `services/api` is currently reworking, and there is */
/* no HTTP in `:core` to hang them on. What is ported is the half that can lose */
/* a child's stars: the round trip and the fold around it.                     */
/* -------------------------------------------------------------------------- */

private fun blankProgress(species: Species) = SpeciesProgress(
    config = MascotConfig(
        species = species,
        stage = 0,
        colors = emptyMap(),
        styles = emptyMap(),
        accessories = emptyList(),
    ),
    owned = emptyList(),
    rev = Rev(0L, ""),
)

private fun profile(earned: Map<String, Int> = emptyMap()) = PersistedProfile(
    chosen = true,
    current = Species.UNICORN,
    currentRev = Rev(0L, ""),
    species = Species.entries.associateWith { blankProgress(it) },
    stars = StarCounters(earned = earned, spent = emptyMap()),
    clears = emptyMap(),
)

private fun kid(id: String, name: String, earned: Map<String, Int> = emptyMap()) = ChildProfile(
    id = id,
    name = name,
    nameRev = Rev(10L, "dad"),
    touchedAt = 10L,
    profile = profile(earned),
)

private fun roster(children: List<ChildProfile>, activeId: String? = null) =
    Roster(children = children, activeId = activeId, removed = emptyMap())

class SyncClientTest {

    @Test
    fun `uploads the first device's roster`() = runTest {
        val server = StubSyncTransport()
        SyncClient(server).syncOnce(roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10)))))
        assertEquals(1, assertNotNull(server.document).children.size)
    }

    @Test
    fun `converges two phones without losing either one's stars`() = runTest {
        val server = StubSyncTransport()
        val dadPhone = SyncClient(server)
        val mumPhone = SyncClient(server)

        // Dad's phone syncs first, then Mum's phone brings its own offline earnings.
        dadPhone.syncOnce(roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10)))))
        val onMum = mumPhone.syncOnce(roster(listOf(kid("lea", "Léa", earned = mapOf("mum" to 3)))))

        assertEquals(1, onMum.children.size)
        assertEquals(13, balanceOf(onMum.children[0].profile.stars))
        // And Mum's phone kept calling her Léa, without the server ever knowing it.
        assertEquals("Léa", onMum.children[0].name)
        val serverJson = Json.encodeToString(WireRoster.serializer(), assertNotNull(server.document))
        assertFalse(serverJson.contains("Léa"), serverJson)
    }

    @Test
    fun `brings home a sibling created on the other phone`() = runTest {
        val server = StubSyncTransport()
        val mumPhone = SyncClient(server)
        val dadPhone = SyncClient(server)

        mumPhone.syncOnce(roster(listOf(kid("tom", "Tom", earned = mapOf("mum" to 4)))))
        val here = dadPhone.syncOnce(
            roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10))), activeId = "lea"),
        )

        assertEquals(listOf("lea", "tom"), here.children.map { it.id }.sorted())
        assertEquals("Enfant", here.children.first { it.id == "tom" }.name)
        assertEquals("lea", here.activeId) // still Léa's turn on THIS tablet
    }

    @Test
    fun `a write that lands between two exchanges is merged, never clobbered`() = runTest {
        val server = StubSyncTransport()
        val sync = SyncClient(server)
        sync.syncOnce(roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10)))))

        // The other phone lands a write while this one is between resumes.
        server.interleave(toWire(roster(listOf(kid("lea", "Léa", earned = mapOf("mum" to 5))))))

        val merged = sync.syncOnce(
            roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10, "ipad" to 2)))),
        )
        // All three devices' earnings survive the collision. This is what the
        // web's ETag/412 retry buys and what a store-the-last-write server would
        // silently break — hence a stub that folds rather than overwrites.
        assertEquals(17, balanceOf(merged.children[0].profile.stars))
    }

    @Test
    fun `is idempotent — re-syncing does not double anything`() = runTest {
        val server = StubSyncTransport()
        val sync = SyncClient(server)
        val local = roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10))))
        val once = sync.syncOnce(local)
        val twice = sync.syncOnce(once)
        assertEquals(10, balanceOf(twice.children[0].profile.stars))
        assertEquals(2, server.exchanges)
    }

    @Test
    fun `a delete made here propagates and does not come back on the next resume`() = runTest {
        val server = StubSyncTransport()
        val sync = SyncClient(server)
        sync.syncOnce(roster(listOf(kid("lea", "Léa"), kid("tom", "Tom"))))

        // The parent removes Tom at t=99, after his last recorded touch at t=10.
        val afterDelete = Roster(
            children = listOf(kid("lea", "Léa")),
            activeId = null,
            removed = mapOf("tom" to 99L),
        )
        val here = sync.syncOnce(afterDelete)
        assertEquals(listOf("lea"), here.children.map { it.id })
        // A second resume must not resurrect him from the household document.
        assertEquals(listOf("lea"), sync.syncOnce(here).children.map { it.id })
        assertNull(here.children.firstOrNull { it.id == "tom" })
    }
}
