package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariant 4 — content is AUTHORED, not computed.
 *
 * Read the first test before touching this file: `syllables.joinToString("") ==
 * word` is a SHAPE check. It asserts that the authored split spells the
 * authored word. It is NOT a specification for a splitter, and the day someone
 * "saves duplication" by deriving `syllables` from `word` this test still
 * passes while the invariant is gone. There is no `(String) -> List<String>` in
 * `:core` and there must never be one.
 */
class ContentSyllableWordsTest {

    // The shape check (invariant 4) -------------------------------------------

    @Test
    fun `every syllable split spells its word`() {
        for (w in SYLLABLE_WORDS) {
            assertEquals(w.word, w.syllables.joinToString(""), "bad split for ${w.word}: ${w.syllables}")
        }
    }

    @Test
    fun `every syllable is non-empty and uppercase`() {
        for (w in SYLLABLE_WORDS) {
            assertTrue(w.syllables.isNotEmpty(), w.word)
            for (s in w.syllables) {
                assertTrue(s.isNotEmpty(), w.word)
                assertEquals(s.uppercase(), s, "${w.word}: $s")
            }
        }
    }

    // Table sizes (counted from `src/content.ts`, not estimated) --------------

    @Test
    fun `table sizes match the TypeScript`() {
        assertEquals(45, SYLLABLE_WORDS.size)
        assertEquals(listOf(5, 6, 8, 8), SPELL_SYLLABLE_WORD_NAMES.map { it.size })
    }

    @Test
    fun `syllable words cover the three tier widths`() {
        val byWidth = SYLLABLE_WORDS.groupBy { it.syllables.size }
        assertEquals(20, byWidth[2]?.size)
        assertEquals(17, byWidth[3]?.size)
        assertEquals(8, byWidth[4]?.size)
    }

    // The fill-a-syllable ladder resolves against the word table --------------

    @Test
    fun `every spell-syllable name resolves`() {
        // A test-local index, not the app's lookup: the runtime resolver is the
        // WORD_BY_NAME dedupe next to the levels wiring, and this test must keep
        // passing without it. An index is a dedupe, never a generator.
        val byName = SYLLABLE_WORDS.associateBy { it.word }
        SPELL_SYLLABLE_WORD_NAMES.forEachIndexed { i, level ->
            for (name in level) {
                assertTrue(name in byName, "level ${i + 1}: unknown word \"$name\"")
            }
        }
    }

    /**
     * "Every word has ≥3 syllables so the two-syllable sibling always leaves a
     * written anchor" — the authored comment, asserted.
     */
    @Test
    fun `every spell-syllable word has at least three syllables`() {
        val byName = SYLLABLE_WORDS.associateBy { it.word }
        for (level in SPELL_SYLLABLE_WORD_NAMES) {
            for (name in level) {
                val w = assertNotNull(byName[name], name)
                assertTrue(w.syllables.size >= 3, "$name has ${w.syllables.size} syllables")
            }
        }
    }

    // The two authoring rules, as regressions ---------------------------------

    /**
     * One tile string = one baked clip, so a shared syllable must sound the same
     * in every word that uses it. MAI-SON (/zɔ̃/) and POIS-SON (/sɔ̃/) cannot
     * coexist; SAPIN is what shipped instead, reusing PIN from LAPIN at /pɛ̃/.
     */
    @Test
    fun `maison never came back`() {
        val words = SYLLABLE_WORDS.map { it.word }.toSet()
        assertTrue("POISSON" in words)
        assertTrue("SAPIN" in words)
        assertFalse("MAISON" in words, "MAI-SON makes SON say /zɔ̃/ while POIS-SON says /sɔ̃/")
    }

    /**
     * « ll » alone never says /j/ in French — « ill » does, and it straddles the
     * split. PAPILLON has no honest 3-way split and had to go.
     */
    @Test
    fun `papillon never came back`() {
        val words = SYLLABLE_WORDS.map { it.word }.toSet()
        assertFalse("PAPILLON" in words)
        assertTrue("PERROQUET" in words)
        // The iOS suite also pins "LLON" out of the derived SYLLABLE_BANK; the
        // bank is a dedupe that lives with Content.kt, so the same regression is
        // asserted here at the source: no authored split contains the dishonest
        // tile.
        assertTrue(SYLLABLE_WORDS.none { "LLON" in it.syllables })
    }

    // NFC ---------------------------------------------------------------------

    @Test
    fun `every authored string is NFC`() {
        // An NFD « É » would change `word.length` (the spell exercises' cell
        // count), `voKey` (the baked-clip lookup must hash the SAME bytes as the
        // web and iOS) and the rendered glyph — all at once, all silently.
        // `content.ts` is NFC today; this pins the port to it.
        val strings = buildList {
            for (w in SYLLABLE_WORDS) {
                add(w.word)
                add(w.emoji)
                addAll(w.syllables)
            }
            SPELL_SYLLABLE_WORD_NAMES.forEach(::addAll)
        }
        for (s in strings) {
            assertTrue(Normalizer.isNormalized(s, Normalizer.Form.NFC), "not NFC: \"$s\"")
        }
    }
}
