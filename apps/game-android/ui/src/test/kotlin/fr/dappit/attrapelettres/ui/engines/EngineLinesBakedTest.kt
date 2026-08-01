package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.vo.enumerateUtterances
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The four French strings the engines speak are written twice: once in
 * [EngineLines] / [SinglePickModel]'s firstLetter factory, and once in :core's
 * `enumerateUtterances()`, which is the list `generate-vo.mjs` bakes a clip for.
 *
 * Nothing made those two agree. iOS has the same split (`EngineStringsTests`
 * pins `EngineLines` against literals; nothing pins it against the enumerator),
 * so this is not a regression — but a one-character drift on either side is
 * invisible to every existing test and its only symptom is a clip that fails to
 * resolve at runtime, silently, on a child's device. The clip bank is keyed by
 * the exact utterance, so "is this string in the bank?" is the whole contract.
 *
 * Deliberately asymmetric: the enumerator is allowed to contain lines no engine
 * speaks (the shop, the hub, the mascot). Only the reverse is a defect.
 */
class EngineLinesBakedTest {

    private val baked = enumerateUtterances().toSet()

    @Test
    fun `every fixed engine line has a baked clip`() {
        for (line in listOf(
            EngineLines.OH_NON,
            EngineLines.BRAVO_FOUND,
            EngineLines.BRAVO_SUCCEEDED,
        )) {
            assertTrue(line in baked, "no baked VO clip for the spoken line « $line »")
        }
    }

    @Test
    fun `the first-letter prompt template matches the baked one for every word`() {
        // The engine builds it per round as "Trouve la première lettre de <word>.";
        // the enumerator bakes the same shape per LETTER_WORDS entry. If either
        // side's punctuation moves, every level of the first exercise goes mute.
        for (word in LETTER_WORDS) {
            val line = "Trouve la première lettre de ${word.word}."
            assertTrue(line in baked, "no baked VO clip for « $line »")
        }
    }
}
