package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.domain.LetterWord
import fr.dappit.attrapelettres.core.domain.ReadImageRound
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the read-the-word slice of `src/levels.ts`. The ladder
// (`READ_IMAGE_LEVELS`) and its clamping accessor live in `Levels.kt`, the
// spine; this file holds the pool, the round builders and the spoken line.
//
// NB: `READ_IMAGE_PROMPT` landed here before the rest of the file, because the
// VO manifest (vo/Utterances.kt) must speak through the very same declarations
// the exercise engine calls — re-authoring the French inline in the manifest is
// the failure mode the whole VO design exists to prevent. The builders now
// join it, as that earlier header promised.

/** The picture pool: every curated noun. Reading practice, so all levels see all. */
fun readImagePool(): List<LetterWord> = LETTER_WORDS

/**
 * One read-the-word round: the target plus [distractors] other words, shuffled.
 * Distractors are picked by DISTINCT emoji so no two tiles ever show the same
 * picture (a few nouns share a glyph), which would make the choice ambiguous.
 */
// NB: it shuffles the WHOLE `LETTER_WORDS`, not the level pool (there is only
// one pool). Seeding `seen` with the target's emoji is also what stops the
// target being picked a second time.
fun buildReadImageRound(
    target: LetterWord,
    distractors: Int,
    rng: RandomSource = SystemRandomSource(),
): ReadImageRound {
    val seen = mutableSetOf(target.emoji)
    val pool = mutableListOf<LetterWord>()
    for (w in rng.shuffled(LETTER_WORDS)) {
        if (w.emoji in seen) continue
        seen.add(w.emoji)
        pool.add(w)
        if (pool.size >= distractors) break
    }
    return ReadImageRound(target = target, choices = rng.shuffled(listOf(target) + pool))
}

fun buildReadImageSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<ReadImageRound> {
    val cfg = readImageLevel(level)
    return repeatSession(readImagePool(), pick = cfg.pick, repeats = cfg.repeats, rng = rng)
        .map { buildReadImageRound(it, cfg.distractors, rng) }
}

/** What the child hears: never the word (that would give the answer away). */
const val READ_IMAGE_PROMPT: String = "Trouve la bonne image."
