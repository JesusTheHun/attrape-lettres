package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The persisted-model structure — the compile-time half of invariant 9 made
 * observable where it can be, plus the JSON details that keep blobs
 * interchangeable with the PWA's. Ported from ProfileModelsTests.swift, the
 * worked iOS port of the same suite; assertions are copied, not re-derived.
 */
private fun sampleProfile(): PersistedProfile {
    val fox = blankProgress(Species.FOX).let {
        it.copy(
            config = it.config.copy(stage = 3, colors = mapOf("furColor" to "#F80")),
            owned = listOf("fox.fur.orange"),
            rev = Rev(1_700_000_000_000, "dad-phone"),
        )
    }
    return DEFAULT_PROFILE.copy(
        chosen = true,
        current = Species.FOX,
        currentRev = Rev(1_700_000_000_000, "dad-phone"),
        species = DEFAULT_PROFILE.species + (Species.FOX to fox),
        stars = StarCounters(earned = mapOf("dad-phone" to 17), spent = mapOf("dad-phone" to 4)),
        clears = mapOf("read-image:1" to mapOf("dad-phone" to 2)),
    )
}

class ProfileModelsTest {

    @Test
    fun `a persisted profile encodes counters, never totals`() {
        // Invariant 9, byte-level: the persisted encoding of a profile carrying
        // stars and clears contains counters only — never a "balance" or
        // "ledger" key. (The type has no such property; this also guards the
        // generated serializer against a future hand-written one.)
        val json = Json.encodeToString(PersistedProfile.serializer(), sampleProfile())
        assertTrue(json.contains("\"earned\""))
        assertTrue(json.contains("\"spent\""))
        assertTrue(json.contains("\"clears\""))
        assertFalse(json.contains("\"balance\""))
        assertFalse(json.contains("\"ledger\""))
    }

    @Test
    fun `ProfileView is deliberately not serializable`() {
        // Invariant 9, structural: the flattened view the UI reads has no
        // serializer, so persisting or wiring it cannot be written by accident.
        // (Swift pins the absent Codable conformance; here the absent
        // serializer surfaces as a runtime lookup failure.)
        assertFails { serializer(typeOf<ProfileView>()) }
    }

    @Test
    fun `ProfileView flattens the current species`() {
        val view = ProfileView(sampleProfile(), balance = 13, ledger = mapOf("read-image:1" to 2))
        assertEquals(Species.FOX, view.config.species)
        assertEquals(3, view.config.stage)
        assertEquals("#F80", view.config.colors["furColor"])
        assertEquals(listOf("fox.fur.orange"), view.owned)
        assertEquals(13, view.balance)
        assertEquals(2, view.ledger["read-image:1"])
        // The persisted fields ride along, as in TS `Profile extends PersistedProfile`.
        assertTrue(view.chosen)
        assertEquals(mapOf("dad-phone" to 17), view.stars.earned)
    }

    @Test
    fun `a normalised profile encodes all five species slots, wire-keyed`() {
        val json = Json.encodeToString(PersistedProfile.serializer(), DEFAULT_PROFILE)
        for (s in Species.entries) {
            assertTrue(json.contains("\"${s.wire}\""), "missing slot ${s.wire} in $json")
        }
    }

    @Test
    fun `normalising fills missing species slots and drops unknown ones`() {
        // Only the fox present, plus a species no version of the app ever wrote.
        val p = normalizeProfile(
            LooseProfile(
                species = mapOf(
                    "fox" to LooseSpeciesProgress(
                        config = LooseMascotConfig(species = "fox", stage = 2.0),
                        owned = listOf("fox.fur.orange"),
                        rev = LooseRev(at = 5.0, by = "d"),
                    ),
                    "griffon" to LooseSpeciesProgress(
                        config = LooseMascotConfig(species = "griffon", stage = 9.0),
                    ),
                ),
            ),
        )
        assertEquals(Species.entries.toList(), p.species.keys.toList())
        assertEquals(2, p.species.getValue(Species.FOX).config.stage)
        assertEquals(listOf("fox.fur.orange"), p.species.getValue(Species.FOX).owned)
        assertEquals(blankProgress(Species.UNICORN), p.species.getValue(Species.UNICORN))
        assertEquals(blankProgress(Species.DRAGON), p.species.getValue(Species.DRAGON))
    }

    @Test
    fun `Roster serialization round-trips`() {
        val roster = Roster(
            children = listOf(
                ChildProfile(
                    id = "lea-uuid",
                    name = "Léa",
                    nameRev = Rev(42, "dad-phone"),
                    touchedAt = 99,
                    profile = sampleProfile(),
                ),
            ),
            activeId = "lea-uuid",
            removed = mapOf("ghost" to 123L),
        )
        val json = Json.encodeToString(Roster.serializer(), roster)
        assertEquals(roster, Json.decodeFromString(Roster.serializer(), json))
    }

    @Test
    fun `the zero rev is the always-losing stamp`() {
        assertEquals(Rev(at = 0, by = ""), Rev.ZERO)
    }

    @Test
    fun `the default profile is a blank, unchosen unicorn`() {
        assertFalse(DEFAULT_PROFILE.chosen)
        assertEquals(Species.UNICORN, DEFAULT_PROFILE.current)
        assertEquals(Rev.ZERO, DEFAULT_PROFILE.currentRev)
        assertEquals(StarCounters(earned = emptyMap(), spent = emptyMap()), DEFAULT_PROFILE.stars)
        assertTrue(DEFAULT_PROFILE.clears.isEmpty())
        for (s in Species.entries) {
            assertEquals(blankProgress(s), DEFAULT_PROFILE.species.getValue(s))
        }
    }
}

class ApplyOptionTest {
    private val base = MascotConfig(
        species = Species.UNICORN,
        stage = 0,
        colors = emptyMap(),
        styles = emptyMap(),
        accessories = emptyList(),
    )

    @Test
    fun `a color sets its slot`() {
        val horn = CustomizationOption(
            id = "unicorn.horn.rainbow",
            species = Species.UNICORN,
            category = CustomizationCategory.COLOR,
            slot = "hornColor",
            value = "#F0A",
            name = "Corne arc-en-ciel",
            cost = 4,
        )
        val next = applyOption(base, horn)
        assertEquals("#F0A", next.colors["hornColor"])
        assertTrue(next.accessories.isEmpty())
    }

    @Test
    fun `a style sets its slot`() {
        val tail = CustomizationOption(
            id = "unicorn.tail.long",
            species = Species.UNICORN,
            category = CustomizationCategory.STYLE,
            slot = "tailSize",
            value = "long",
            name = "Grande queue",
            cost = 3,
        )
        assertEquals("long", applyOption(base, tail).styles["tailSize"])
    }

    @Test
    fun `an accessory appends once, and re-applying is the identity`() {
        val crown = CustomizationOption(
            id = "unicorn.crown",
            species = Species.UNICORN,
            category = CustomizationCategory.ACCESSORY,
            slot = "crown",
            value = "",
            name = "Couronne",
            cost = 6,
        )
        val once = applyOption(base, crown)
        assertEquals(listOf("unicorn.crown"), once.accessories)
        assertEquals(once, applyOption(once, crown))
    }
}
