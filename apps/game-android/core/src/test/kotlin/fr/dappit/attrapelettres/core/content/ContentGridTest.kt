package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariant 4 — content is AUTHORED, not computed. The counts and shapes here
 * are copied from `src/content.ts` (via the worked iOS suite,
 * `ContentIntegrityTests.swift`), not estimated: if one of them ever has to
 * change here alone, the ports have drifted and that is the finding.
 */
class ContentGridTest {

    @Test
    fun `table sizes match the TypeScript`() {
        assertEquals(6, GRID_VOWELS.size)
        assertEquals(8, SYLLABLE_GRID_ROWS.size)
    }

    @Test
    fun `grid consonants are the rows in teaching order`() {
        assertEquals(
            listOf("L", "M", "R", "V", "P", "T", "B", "D", "F", "S", "N", "J", "Z", "CH"),
            GRID_CONSONANTS,
        )
        assertNull(SYLLABLE_GRID_ROWS.last(), "the révision level must stay null")
        assertTrue(SYLLABLE_GRID_ROWS.dropLast(1).all { it != null })
    }

    /**
     * The authored content guard: C, G, K and QU are DELIBERATELY absent from
     * the grid — CE/CI and GE/GI flip sound, which is Trouve le son / Les
     * syllabes jumelles' lesson, not this one.
     */
    @Test
    fun `grid excludes the consonants whose sound flips`() {
        for (banned in listOf("C", "G", "K", "QU")) {
            assertFalse(banned in GRID_CONSONANTS, "$banned must not be a grid row")
        }
    }

    @Test
    fun `grid cells are unique`() {
        assertEquals(GRID_CONSONANTS.size, GRID_CONSONANTS.toSet().size)
        assertEquals(GRID_VOWELS.size, GRID_VOWELS.toSet().size)
        assertEquals(84, GRID_CONSONANTS.size * GRID_VOWELS.size)
    }

    /**
     * An NFD "É" would change the spell exercise's cell count, the baked-clip
     * lookup key and the rendered glyph — all at once, all silently.
     * `content.ts` is NFC today; this pins the port to it.
     */
    @Test
    fun `every authored string is NFC`() {
        for (string in GRID_VOWELS + SYLLABLE_GRID_ROWS.flatMap { it.orEmpty() }) {
            assertTrue(Normalizer.isNormalized(string, Normalizer.Form.NFC), "not NFC: \"$string\"")
        }
    }
}
