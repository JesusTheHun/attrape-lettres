package fr.dappit.attrapelettres.core.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One owner for the mascot vocabulary. `Species` wire values are persisted AND
 * synced (they key `PersistedProfile.species`), so they are frozen for the same
 * reason `ExerciseId`'s are (A2).
 */
class MascotTest {

    @Test
    fun `species wire values are frozen`() {
        assertEquals(
            listOf("unicorn", "cat", "fox", "rabbit", "dragon"),
            Species.entries.map(Species::wire),
        )
    }

    @Test
    fun `species serialises to its wire value, so the two spellings cannot drift`() {
        // `wire` (hand-built keys) and `@SerialName` (kotlinx) are two routes to
        // the same stored string; this weld is what keeps them one string.
        for (species in Species.entries) {
            assertEquals("\"${species.wire}\"", Json.encodeToString(Species.serializer(), species))
            assertEquals(species, Json.decodeFromString(Species.serializer(), "\"${species.wire}\""))
        }
    }

    @Test
    fun `species fromWire skips unknown strings rather than crashing`() {
        assertEquals(Species.DRAGON, Species.fromWire("dragon"))
        assertNull(Species.fromWire("phoenix"))
    }

    @Test
    fun `ten growth stages`() {
        assertEquals(10, GROWTH_STAGES)
    }

    @Test
    fun `customization categories`() {
        assertEquals(
            listOf("accessory", "color", "style"),
            CustomizationCategory.entries.map(CustomizationCategory::wire),
        )
    }

    @Test
    fun `minStage absent is not zero`() {
        // `catalog.ts` only spreads `minStage` in when it was passed, and
        // `catalog.test.ts` distinguishes "absent" from 0 — so it stays nullable.
        val ungated = CustomizationOption(
            id = "unicorn.color.bodyColor.lilas", species = Species.UNICORN,
            category = CustomizationCategory.COLOR, slot = "bodyColor",
            value = "#F5ECFF", name = "Corps lilas", cost = 15,
        )
        assertNull(ungated.minStage)
        assertEquals(0, ungated.minStage ?: 0)

        val gated = CustomizationOption(
            id = "unicorn.color.hornColor.doree", species = Species.UNICORN,
            category = CustomizationCategory.COLOR, slot = "hornColor",
            value = "#FFD54F", name = "Corne dorée", emoji = "🔺", cost = 20, minStage = 2,
        )
        assertEquals(2, gated.minStage)
    }

    @Test
    fun `config round-trips unknown slots`() {
        // An unknown slot key written by a newer build must survive a round trip
        // through an older one — which is why colours/styles are Map<String, String>
        // and not enums.
        val config = MascotConfig(
            species = Species.DRAGON,
            stage = 7,
            colors = mapOf("bodyColor" to "#7DB874", "someFutureSlot" to "#123456"),
            styles = mapOf("hornStyle" to "curved"),
            accessories = listOf("dragon.accessory.treasure"),
        )
        val data = Json.encodeToString(MascotConfig.serializer(), config)
        val back = Json.decodeFromString(MascotConfig.serializer(), data)
        assertEquals(config, back)
        assertEquals("#123456", back.colors["someFutureSlot"])
    }
}
