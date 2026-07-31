package fr.dappit.attrapelettres.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The string unions of `types.ts`. Every wire value here is either a VO lookup
 * key, a persisted value, or both — they are copied byte for byte, hyphens
 * included, so a "nicer" Kotlin rename would be silent drift from the web app.
 */
class DomainEnumWireValueTest {

    @Test
    fun `syllable modes keep their hyphens`() {
        assertEquals(
            listOf("fill-blank", "order", "order-distractor"),
            SyllableMode.entries.map(SyllableMode::wire),
        )
    }

    @Test
    fun `spell syllable modes`() {
        assertEquals(
            listOf("letters-exact", "letters-extra", "letters-two"),
            SpellSyllableMode.entries.map(SpellSyllableMode::wire),
        )
    }

    @Test
    fun `syllable grid modes`() {
        assertEquals(
            listOf("hear", "vowel"),
            SyllableGridMode.entries.map(SyllableGridMode::wire),
        )
    }
}

/**
 * Shape checks ported from the Swift `RoundShapeTests`: what matters is the
 * exact TYPE each TS field became, because the engines are written against it.
 */
class RoundShapeTest {

    /**
     * `slots: (string | null)[]` → `List<String?>`, exactly: the engines index
     * it and write null back when a child takes a tile out of a slot (through
     * `copy` on this side of the port — the data class is immutable, the state
     * holder is not).
     */
    @Test
    fun `slots are nullable and writable`() {
        var round = SyllableRound(
            word = SyllableWord(word = "CHAPEAU", syllables = listOf("CHA", "PEAU"), emoji = "🎩"),
            slots = listOf("CHA", null),
            locked = listOf(true, false),
            tray = listOf(
                SyllableTile(id = 0, syllable = "PEAU"),
                SyllableTile(id = 1, syllable = "CHA"),
            ),
        )
        assertNull(round.slots[1])
        round = round.copy(slots = listOf("CHA", "PEAU"))
        assertEquals(listOf("CHA", "PEAU"), round.slots)
        round = round.copy(slots = listOf("CHA", null))
        assertNull(round.slots[1])
        assertEquals(round.locked.size, round.slots.size)
    }

    /**
     * Every tile type carries a stable `id` — Compose list keys need it for
     * exactly the reason the TSX used these ids as React keys.
     */
    @Test
    fun `every tile carries a stable id`() {
        assertEquals(7, SyllableTile(id = 7, syllable = "MI").id)
        assertEquals(8, SoundTile(id = 8, letter = "L").id)
        assertEquals(
            9,
            SpellLetterTile(id = 9, letter = "A", glyph = "a", script = LetterScript.CURSIVE).id,
        )
        assertEquals(
            10,
            TwinTile(id = 10, text = "EAU", sound = "o", word = "eau", emoji = "💧", correct = true).id,
        )
    }

    @Test
    fun `optional content fields stay optional`() {
        // A SoundTarget with no anchor word is legal (soundPrompt falls back to
        // the bare sound); a BasicSound always has one, but its traps are optional.
        val bare = SoundTarget(sound = "oi", spelling = listOf("O", "I"))
        assertNull(bare.word)
        assertNull(bare.emoji)
        val trapless = BasicSound(sound = "ou", graphy = "OU", word = "hibou", emoji = "🦉")
        assertNull(trapless.traps)
        val trapped =
            BasicSound(sound = "ou", graphy = "OU", word = "hibou", emoji = "🦉", traps = listOf("ON", "AN"))
        assertEquals(listOf("ON", "AN"), trapped.traps)
    }
}
