package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.domain.FirstLetterRound
import fr.dappit.attrapelettres.core.domain.LetterWord
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.orderedUnique
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the first-letter slice of `src/levels.ts`, plus `buildSession` lifted
// out of `src/exercises/FirstLetterExercise.tsx` — the same adjudication the
// iOS port made: it is pure, and it is the one session shape a host test could
// not otherwise reach while it lived in the view. Behaviour unchanged,
// hard-coded 2 distractors kept. The ladder itself (`FIRST_LETTER_LEVELS`)
// lives in `Levels.kt`, the spine.

/**
 * The level's word pool.
 *
 * NB: UNCLAMPED, on purpose. The TS is `FIRST_LETTER_LEVELS[level - 1]`, so
 * `level = 0` or `6` throws a TypeError there and an
 * `IndexOutOfBoundsException` here. Every other ladder clamps; this one does
 * not, and adding a clamp would be a behaviour change. Nothing the router can
 * produce is out of range.
 */
fun firstLetterPool(level: Int): List<LetterWord> {
    val cfg = FIRST_LETTER_LEVELS[level - 1]
    val letters = cfg.letters ?: return LETTER_WORDS
    val allowed = letters.toSet()
    return LETTER_WORDS.filter { it.letter in allowed }
}

/**
 * One run of first-letter rounds. Pick distinct words, replay a few (spaced)
 * to reinforce — never back-to-back.
 */
// NB: the 2 distractors are HARD-CODED and are not part of `FirstLetterLevel`.
// That is how the TSX had it; keep it hard-coded.
// NB: `[...new Set(pool.map(w => w.letter))]` is insertion-ordered in JS, so
// the null catalog goes through `orderedUnique` — the named helper that marks
// an ORDER-SENSITIVE dedupe site (see its doc): the catalog's order feeds the
// seeded distractor shuffle, so losing it would change a run under a fixed
// seed.
fun buildFirstLetterSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<FirstLetterRound> {
    val cfg = FIRST_LETTER_LEVELS[level - 1]
    val pool = firstLetterPool(level)
    val catalog = cfg.letters ?: orderedUnique(pool.map { it.letter })
    return repeatSession(pool, pick = cfg.pick, repeats = cfg.repeats, rng = rng).map { target ->
        val distractors = rng.shuffled(catalog.filter { it != target.letter }).take(2)
        FirstLetterRound(
            target = target,
            choices = rng.shuffled(listOf(target.letter) + distractors),
        )
    }
}
