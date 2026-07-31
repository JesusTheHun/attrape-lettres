package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.ImageKey
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariant 4 for the letter tables — content is AUTHORED, not computed. These
 * assertions are ported from the iOS `ContentIntegrityTests` (itself counted
 * from `src/content.ts`, not estimated); if one of them ever has to change here
 * alone, the ports have drifted and that is the finding.
 */
class ContentLetterWordsTest {

    /** Diacritic-insensitive fold, test-side only (Éléphant → ELEPHANT). */
    private fun folded(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    @Test
    fun `table sizes match the TypeScript`() {
        assertEquals(42, LETTER_WORDS.size)
        assertEquals(26, LETTER_MATCH_ALPHABET.size)
    }

    @Test
    fun `every letter word has a single uppercase letter`() {
        for (w in LETTER_WORDS) {
            assertEquals(1, w.letter.length, w.word)
            assertEquals(w.letter.uppercase(), w.letter, w.word)
            // NB: folded, not literal. The authored note says "initials are kept
            // plain", but Éléphant and Île ship with an accented initial under
            // the plain E / I tile — that is the data, and the rule is not to
            // write a test the shipped data fails.
            assertTrue(
                folded(w.word).uppercase().startsWith(w.letter),
                "${w.word} does not start with ${w.letter}",
            )
            assertTrue(w.emoji.isNotEmpty(), w.word)
        }
    }

    @Test
    fun `the letter match alphabet is distinct uppercase A to Z`() {
        assertEquals(LETTER_MATCH_ALPHABET.size, LETTER_MATCH_ALPHABET.toSet().size)
        for (l in LETTER_MATCH_ALPHABET) {
            assertEquals(1, l.length)
            assertEquals(l.uppercase(), l)
        }
        assertEquals("A", LETTER_MATCH_ALPHABET.first())
        assertEquals("Z", LETTER_MATCH_ALPHABET.last())
    }

    @Test
    fun `the four dedicated illustrations are the ones authored`() {
        // Spans both word tables on purpose: the img indirection exists for
        // exactly four words, two per table, and every ImageKey constant must be
        // spoken for — an unused key would be a drawing in :art no word can show.
        val letterImgs = LETTER_WORDS.mapNotNull { it.img }.toSet()
        val syllableImgs = SYLLABLE_WORDS.mapNotNull { it.img }.toSet()
        assertEquals(setOf(ImageKey.IGLOO, ImageKey.JUPE), letterImgs)
        assertEquals(setOf(ImageKey.PYJAMA, ImageKey.MACARON), syllableImgs)
        assertEquals(ImageKey.entries.toSet(), letterImgs + syllableImgs)
    }

    @Test
    fun `every authored string is NFC`() {
        // An NFD « É » would change `word.length` (the spell engines count
        // letters), `voKey` (the baked-clip lookup must hash the SAME bytes as
        // the web and iOS) and the rendered glyph — all at once, all silently.
        // `content.ts` is NFC today; this pins the port to it.
        val strings = buildList {
            for (w in LETTER_WORDS) {
                add(w.letter)
                add(w.word)
                add(w.emoji)
            }
            addAll(LETTER_MATCH_ALPHABET)
        }
        for (s in strings) {
            assertTrue(Normalizer.isNormalized(s, Normalizer.Form.NFC), "not NFC: \"$s\"")
        }
    }
}
