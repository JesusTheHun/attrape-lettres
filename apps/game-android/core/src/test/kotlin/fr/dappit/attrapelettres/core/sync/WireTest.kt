package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/* -------------------------------------------------------------------------- */
/* Port of apps/game-web/src/sync/client.test.ts § "the wire format — what the  */
/* server is allowed to know".                                                  */
/*                                                                             */
/* `WireChild` has no `name`/`nameRev` property and `WireRoster` no `activeId`, */
/* so the TYPE already guarantees most of this — the serialised-bytes           */
/* assertions are KEPT anyway (invariant 10): they are what guards the          */
/* generated serialiser against a future hand-written one quietly re-adding a   */
/* field. "Léa" and "Tom" are both asserted absent on purpose: one accented,    */
/* one pure ASCII, so an encoder that escaped non-ASCII could not sneak a name  */
/* past the test.                                                              */
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

class WireTest {

    @Test
    fun `strips names and their stamps`() {
        val wire = toWire(roster(listOf(kid("lea", "Léa"), kid("tom", "Tom"))))
        val encoded = Json.encodeToString(WireRoster.serializer(), wire)
        assertFalse(encoded.contains("Léa"), encoded)
        assertFalse(encoded.contains("Tom"), encoded)
        assertFalse(encoded.contains("nameRev"), encoded)
        // What DOES travel: opaque ids and integers.
        assertEquals(listOf("lea", "tom"), wire.children.map { it.id })
    }

    @Test
    fun `never sends who is holding this tablet`() {
        val wire = toWire(roster(listOf(kid("lea", "Léa")), activeId = "lea"))
        val encoded = Json.encodeToString(WireRoster.serializer(), wire)
        assertFalse(encoded.contains("activeId"), encoded)
    }

    @Test
    fun `re-attaches names this device already knows`() {
        val local = roster(listOf(kid("lea", "Léa")))
        val back = fromWire(toWire(local), local)
        assertEquals("Léa", back.children[0].name)
        assertEquals(Rev(10L, "dad"), back.children[0].nameRev)
    }

    @Test
    fun `gives a never-seen child a placeholder that always loses to a local name`() {
        val fromOtherPhone = toWire(roster(listOf(kid("tom", "Tom"))))
        val joined = fromWire(fromOtherPhone, roster(emptyList()))
        assertEquals("Enfant", joined.children[0].name)
        // Zero stamp => the moment this parent names them, that name wins forever.
        assertEquals(Rev(0L, ""), joined.children[0].nameRev)
    }

    @Test
    fun `fromWire never adopts an active player`() {
        val local = roster(listOf(kid("lea", "Léa")), activeId = "lea")
        assertNull(fromWire(toWire(local), local).activeId)
    }

    @Test
    fun `removed tombstones travel both ways`() {
        val r = roster(listOf(kid("lea", "Léa"))).copy(removed = mapOf("tom" to 99L))
        val wire = toWire(r)
        assertEquals(mapOf("tom" to 99L), wire.removed)
        assertEquals(mapOf("tom" to 99L), fromWire(wire, r).removed)
    }

    /**
     * Self-interop: what Android pushes, Android (and `JSON.parse` on the web,
     * and `JSONDecoder` on the iPhone) can pull back unchanged — the field names
     * are byte-identical to the TS property names, and the object has exactly
     * the keys the household document is allowed to have.
     */
    @Test
    fun `wire roster survives an encode-decode round trip`() {
        val wire = toWire(roster(listOf(kid("lea", "Léa", earned = mapOf("dad-phone" to 10)))))
        val encoded = Json.encodeToString(WireRoster.serializer(), wire)
        assertEquals(wire, Json.decodeFromString(WireRoster.serializer(), encoded))

        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("children", "removed"), root.keys)
        val child = root.getValue("children").jsonArray[0].jsonObject
        assertEquals(setOf("id", "touchedAt", "profile"), child.keys)
    }

    /**
     * The stub server folds two household documents without ever holding a name
     * — the placeholder `fromWire` invents is discarded by `toWire` on the way
     * back out. Nothing identifying is invented, nothing that travels is lost.
     */
    @Test
    fun `mergeWire folds two documents without materialising a name`() {
        val here = toWire(roster(listOf(kid("lea", "Léa", earned = mapOf("dad" to 10)))))
        val there = toWire(roster(listOf(kid("lea", "Léa", earned = mapOf("mum" to 3)))))
        val folded = mergeWire(here, there)

        assertEquals(1, folded.children.size)
        assertEquals(13, balanceOf(folded.children[0].profile.stars))
        val encoded = Json.encodeToString(WireRoster.serializer(), folded)
        assertFalse(encoded.contains("Léa"), encoded)
        assertFalse(encoded.contains("Enfant"), encoded)
    }
}
