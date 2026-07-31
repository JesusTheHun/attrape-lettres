package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The derived tables and the letter bank. `SYLLABLE_BANK` and `WORD_BY_NAME`
 * are the only computations in the whole of content/ — dedupes and lookups,
 * never generators (invariant 4) — and these tests pin the properties the round
 * builders lean on.
 */
class ContentBanksTest {

    @Test
    fun `the letter bank is 22 distinct uppercase letters, A to V`() {
        assertEquals(22, SOUND_LETTER_BANK.size)
        assertEquals(SOUND_LETTER_BANK.size, SOUND_LETTER_BANK.toSet().size)
        for (l in SOUND_LETTER_BANK) {
            assertEquals(1, l.length)
            assertEquals(l.uppercase(), l)
        }
        // A–V, no W/X/Y/Z: nothing in the sound ladders spells with them.
        assertEquals("A", SOUND_LETTER_BANK.first())
        assertEquals("V", SOUND_LETTER_BANK.last())
    }

    /**
     * `SYLLABLE_BANK`'s order is load-bearing twice: `pickDistractorSyllable`
     * picks by index (so the order IS the distribution) and its fallback is
     * element 0. JS `Set` iterates in insertion order and Kotlin's `distinct()`
     * keeps first appearances, so the two apps must agree — this asserts it.
     */
    @Test
    fun `syllable bank is first-appearance order`() {
        val expected = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (w in SYLLABLE_WORDS) {
            for (s in w.syllables) if (seen.add(s)) expected.add(s)
        }
        assertEquals(expected, SYLLABLE_BANK)
        assertEquals("CHA", SYLLABLE_BANK.first())
    }

    @Test
    fun `syllable bank is distinct and covers every syllable`() {
        assertEquals(SYLLABLE_BANK.size, SYLLABLE_BANK.toSet().size)
        assertEquals(SYLLABLE_WORDS.flatMap { it.syllables }.toSet(), SYLLABLE_BANK.toSet())
    }

    @Test
    fun `word by name resolves every word and nothing else`() {
        assertEquals(SYLLABLE_WORDS.size, WORD_BY_NAME.size)
        for (w in SYLLABLE_WORDS) {
            assertEquals(w, WORD_BY_NAME[w.word])
        }
        assertNull(WORD_BY_NAME["PAPILLON"])
    }

    /**
     * An NFD "É" would change the spell exercise's cell count, the baked-clip
     * lookup key and the rendered glyph — all at once, all silently.
     * `content.ts` is NFC today; this pins the port to it.
     */
    @Test
    fun `every authored string is NFC`() {
        for (string in SOUND_LETTER_BANK) {
            assertTrue(Normalizer.isNormalized(string, Normalizer.Form.NFC), "not NFC: \"$string\"")
        }
    }
}
