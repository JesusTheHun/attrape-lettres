package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchRound
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.bool
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the letter-form-matching slice of `src/levels.ts` (case + script).
// The underlying letter is the IDENTITY and only the rendered form flips: the
// `case` kind runs both directions in one run (upper→lower AND lower→upper),
// the `script` kind pairs print with cursive at the SAME case. The ladder
// (`LETTER_MATCH_LEVELS`, `letterMatchLevel`) lives in Levels.kt with the
// other eight spines.
//
// The spoken lines landed first (the VO manifest enumerates through them); the
// pool, the directional prompt selector and the session builder joined them
// here when the levels package landed, exactly as the stub header said they
// should.

/** The fixed, directional instruction lines — a small, bake-able VO set. */
// The TS is a plain object (`LETTER_MATCH_PROMPTS`) and the manifest enumerates
// `Object.values(...)`, which iterates in declaration order — so `all` repeats
// that order explicitly rather than trusting anything implicit.
object LetterMatchPrompts {
    const val TO_LOWER = "Trouve la petite lettre."
    const val TO_UPPER = "Trouve la grande lettre."
    const val TO_CURSIVE = "Trouve la lettre attachée."
    const val TO_PRINT = "Trouve la lettre en script."

    /** `Object.values(LETTER_MATCH_PROMPTS)` — declaration order. */
    val all: List<String> = listOf(TO_LOWER, TO_UPPER, TO_CURSIVE, TO_PRINT)
}

fun letterMatchPool(level: Int): List<String> =
    letterMatchLevel(level).letters ?: LETTER_MATCH_ALPHABET

/**
 * Which line to speak, read off the prompt→answer transform (never names the
 * target).
 *
 * NB: a script change WINS over a case change — the order of these two tests is
 * the behaviour.
 */
fun letterMatchPrompt(prompt: LetterFace, answer: LetterFace): String {
    if (prompt.script != answer.script) {
        return if (answer.script == LetterScript.CURSIVE) {
            LetterMatchPrompts.TO_CURSIVE
        } else {
            LetterMatchPrompts.TO_PRINT
        }
    }
    // The no-arg `uppercase()` is locale-invariant on purpose, like `faceLabel`:
    // the locale-sensitive variant in a Turkish locale maps "i" to "İ", which
    // would flip majuscule/minuscule.
    return if (answer.glyph == answer.glyph.uppercase()) {
        LetterMatchPrompts.TO_UPPER
    } else {
        LetterMatchPrompts.TO_LOWER
    }
}

/** The success line — names the letter only AFTER it's been matched by its shape. */
fun letterMatchSuccess(base: String): String = "Oui ! $base."

/**
 * One run of match rounds. Direction is randomised per round (both `case`
 * directions, both `script` directions), so a single session practises a form
 * and its opposite. The correct tile is always the counterpart of `prompt`.
 */
fun buildLetterMatchSession(
    kind: LetterMatchKind,
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<LetterMatchRound> {
    val cfg = letterMatchLevel(level)
    val catalog = letterMatchPool(level)
    return repeatSession(catalog, cfg.pick, cfg.repeats, rng).map { base ->
        val distractors = rng.shuffled(catalog.filter { it != base }).take(cfg.distractors)
        val pool = rng.shuffled(listOf(base) + distractors)
        if (kind == LetterMatchKind.CASE) caseRound(base, pool, rng) else scriptRound(base, pool, rng)
    }
}

/** upper⇄lower, both plain print: prompt one case, every tile the other. */
private fun caseRound(base: String, pool: List<String>, rng: RandomSource): LetterMatchRound {
    val promptUpper = rng.bool()
    fun face(l: String, upper: Boolean) = LetterFace(
        base = l,
        glyph = if (upper) l else l.lowercase(),
        script = LetterScript.PRINT,
    )
    return LetterMatchRound(
        prompt = face(base, promptUpper),
        choices = pool.map { face(it, !promptUpper) },
    )
}

/**
 * print⇄cursive at ONE shared case: prompt one script, every tile the other.
 *
 * NB: DRAW ORDER — `upper` is drawn BEFORE `promptCursive`. Under a seed that
 * order is the round; do not reorder them.
 */
private fun scriptRound(base: String, pool: List<String>, rng: RandomSource): LetterMatchRound {
    // prompt + tiles share this case
    val upper = rng.bool()
    val promptCursive = rng.bool()
    fun face(l: String, cursive: Boolean) = LetterFace(
        base = l,
        glyph = if (upper) l else l.lowercase(),
        script = if (cursive) LetterScript.CURSIVE else LetterScript.PRINT,
    )
    return LetterMatchRound(
        prompt = face(base, promptCursive),
        choices = pool.map { face(it, !promptCursive) },
    )
}
