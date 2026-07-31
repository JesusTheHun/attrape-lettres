package fr.dappit.attrapelettres.core.vo

import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.content.SOUND_TARGETS
import fr.dappit.attrapelettres.core.content.SYLLABLE_BANK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Port of `src/vo/preview.test.ts` (4 cases), plus the extra suites the Swift
// port added: the kind split, and the same bake-coverage oracle
// UtterancesTest uses (see VoManifest.kt).

private val items = enumeratePreviewUtterances()
private val texts = items.map { it.text }.toSet()

class PreviewUtterancesTest {

    @Test
    fun `has no duplicate texts`() {
        // (The TS also asserts every kind is a known one — in Kotlin the enum
        // makes that a compile-time fact, so only the dedupe is left to test.)
        assertEquals(items.size, texts.size)
    }

    @Test
    fun `covers every syllable tile — Assemble draws splits and distractors from SYLLABLE_BANK`() {
        for (s in SYLLABLE_BANK) assertTrue(s in texts, s)
    }

    @Test
    fun `covers every letter tile — first letters, spelling graphemes, intruder bank`() {
        for (w in LETTER_WORDS) assertTrue(w.letter in texts, w.letter)
        for (l in SOUND_LETTER_BANK) assertTrue(l in texts, l)
        for (pool in SOUND_TARGETS) {
            for (t in pool) {
                for (g in t.spelling) assertTrue(g in texts, g)
            }
        }
    }

    @Test
    fun `keeps tile case verbatim — uppercase, so voKey matches what audio speak gets`() {
        // Assemble speaks UPPERCASE syllables, SpellSound/FirstLetter UPPERCASE letters.
        assertTrue("CHA" in texts) // syllable
        assertTrue("É" in texts) // accented grapheme, composed (NFC)
        assertTrue("B" in texts) // consonant letter
        for (t in texts) assertEquals(t.uppercase(), t)
    }
}

class PreviewUtterancesKindAndBakeTest {

    /**
     * The split is the point: the letters group is the one the team is
     * prepared to roll back if lone letters read badly, so nothing a syllable
     * round needs may be tagged LETTER. Every SYLLABLE_BANK entry — including
     * the lone vowels "A" and "É" that could plausibly have been claimed by
     * the letters pass — must carry SYLLABLE.
     */
    @Test
    fun `SYLLABLE_BANK entries are all tagged syllable, lone vowels included`() {
        val kinds = items.associate { it.text to it.kind }
        for (s in SYLLABLE_BANK) assertEquals(VoKind.SYLLABLE, kinds[s], s)
        // …and the letters-only sources that are NOT in the bank stay LETTER.
        val bank = SYLLABLE_BANK.toSet()
        for (l in LETTER_MATCH_ALPHABET) {
            if (l !in bank) assertEquals(VoKind.LETTER, kinds[l], l)
        }
        // Both kinds are actually populated — a bug that tagged everything one
        // way would pass the dedupe test above.
        assertTrue(items.any { it.kind == VoKind.SYLLABLE })
        assertTrue(items.any { it.kind == VoKind.LETTER })
    }

    @Test
    fun `letter-match tiles audition by name, so the whole alphabet is in`() {
        for (l in LETTER_MATCH_ALPHABET) assertTrue(l in texts, l)
    }

    /**
     * Same oracle as the sentence manifest: the preview clips were baked from
     * the shipped TypeScript, so every text this port enumerates must hash to
     * a staged filename.
     */
    @Test
    fun `all 122 preview tokens have a staged clip`() {
        assertEquals(122, items.size)
        val missing = items.filter { voKey(it.text) !in VoManifest.keys }
        assertTrue(missing.isEmpty(), "not baked: ${missing.take(5).map { it.text }}")
    }

    @Test
    fun `enumeration order is stable — the syllable bank first, then the letters`() {
        assertEquals(VoKind.SYLLABLE, items.first().kind)
        assertEquals(VoKind.LETTER, items.last().kind)
        val firstLetterIndex = items.indexOfFirst { it.kind == VoKind.LETTER }
        assertTrue(items.take(firstLetterIndex).all { it.kind == VoKind.SYLLABLE })
        assertTrue(items.drop(firstLetterIndex).all { it.kind == VoKind.LETTER })
        assertEquals(items.map { it.text }, enumeratePreviewUtterances().map { it.text })
    }
}
