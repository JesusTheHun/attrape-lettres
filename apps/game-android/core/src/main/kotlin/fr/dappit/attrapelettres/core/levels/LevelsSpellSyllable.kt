package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.content.SPELL_SYLLABLE_WORD_NAMES
import fr.dappit.attrapelettres.core.content.WORD_BY_NAME
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.SpellCell
import fr.dappit.attrapelettres.core.domain.SpellLetterTile
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SpellSyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import fr.dappit.attrapelettres.core.support.orderedUnique
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the fill-a-syllable slice of `src/levels.ts` — the densest builder in
// the app. ONE 4-level word ladder, shared by all three siblings ("écris la
// syllabe" / "…et les intrus" / "…deux syllabes") AND their two "écritures
// mêlées" twins: the word list is level == index (content), so a child meets
// the SAME words as the task gets harder; only the gap count, the tray and —
// for the mixed twins — the writing change per mode. The sharing is structural:
// neither the pool nor the session takes a mode, so the siblings cannot
// diverge on words. The ladder itself (`SPELL_SYLLABLE_LEVELS`, the clamp, the
// level accessor) lives in Levels.kt with the other eight spines.

/**
 * The level's authored word list, resolved to full [SyllableWord] objects so
 * the authored split + emoji + baked VO are reused, never duplicated.
 *
 * NB: the TS throws `Error("spellSyllablePool: unknown word …")`. A content
 * typo must fail loudly at first use, exactly as today — hence [error] rather
 * than a silent skip. A test resolves every name at build time anyway.
 */
fun spellSyllablePool(level: Int): List<SyllableWord> =
    SPELL_SYLLABLE_WORD_NAMES[spellSyllableIdx(level)].map { name ->
        WORD_BY_NAME[name] ?: error("spellSyllablePool: unknown word \"$name\"")
    }

fun buildSpellSyllableSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<SyllableWord> {
    val cfg = spellSyllableLevel(level)
    return repeatSession(spellSyllablePool(level), cfg.pick, cfg.repeats, rng)
}

/** One writing the word can take: a case + a script. */
internal data class SpellForm(val upper: Boolean, val script: LetterScript)

/** Plain siblings always print big uppercase — the letter is never in question. */
internal val PLAIN_FORM: SpellForm = SpellForm(upper = true, script = LetterScript.PRINT)

/** The three writings the "écritures mêlées" twins shuffle between per round. */
// NB: this ORDER is behaviour — `spellIntruders` walks it to build the
// same-letter/wrong-writing traps, and the tests hard-code exactly this set.
internal val MIXED_FORMS: List<SpellForm> = listOf(
    SpellForm(upper = true, script = LetterScript.PRINT), // GRANDE
    SpellForm(upper = false, script = LetterScript.PRINT), // petite
    SpellForm(upper = false, script = LetterScript.CURSIVE), // attachée
)

// NB: the no-arg `lowercase()` is locale-invariant on purpose, like the TS
// `toLowerCase()` — the deprecated locale-sensitive `toLowerCase()` in a
// Turkish locale would map "I" to "ı" and break the base ↔ glyph pairing.
internal fun spellFace(base: String, form: SpellForm): LetterFace = LetterFace(
    base = base,
    glyph = if (form.upper) base else base.lowercase(),
    script = form.script,
)

/** Two faces render identically iff they share this key (glyph already encodes the case). */
internal fun faceKey(f: LetterFace): String = "${f.glyph}|${f.script}"

/**
 * Blank out 1 (or 2, for `letters-two`) whole syllables into per-letter slots and
 * fill the tray. `letters-exact` gives only the gap's own letters (order is the
 * whole task); the other two add intruders. At least one syllable always stays
 * written, so there's a printed anchor to read the word from. Hidden syllables
 * are chosen at random — the gap can sit anywhere in the word.
 *
 * `mixed` adds a second axis: the whole word (anchors + answer) is drawn in ONE
 * random writing (grande / petite / attachée), and the intruders become the SAME
 * gap letters in the OTHER two writings — so a tile with the right letter but the
 * wrong case or script is a trap. The child must match the writing, not just the
 * letter. Plain rounds keep the exact old shape (uppercase print, letter-only).
 */
// NB: `hideCount` clamps to `syl.size - 1`, which is what guarantees a written
// anchor. Every `spellSyllablePool` word has ≥3 syllables, so `letters-two`
// always hides exactly 2.
// NB: `[...s]` in the TS spreads CODE POINTS. Every authored syllable is NFC
// Latin (GÂ, TÉ, HÔ — single BMP chars), so Kotlin's `Char` iteration is
// equivalent — PROVIDED the content stays NFC and in the BMP, which the content
// suite asserts (`every authored string is NFC`).
// NB: `letters-exact` ignores the level's distractor count entirely.
fun buildSpellSyllableRound(
    word: SyllableWord,
    mode: SpellSyllableMode,
    distractors: Int,
    mixed: Boolean = false,
    rng: RandomSource = SystemRandomSource(),
    ids: TileIdAllocator = TileIdAllocator.shared,
): SpellSyllableRound {
    val syl = word.syllables
    val hideCount = minOf(if (mode == SpellSyllableMode.LETTERS_TWO) 2 else 1, syl.size - 1)
    val hidden = rng.shuffled(syl.indices.toList()).take(hideCount).toSet()
    val form = if (mixed) MIXED_FORMS[rng.next(MIXED_FORMS.size)] else PLAIN_FORM

    val cells = mutableListOf<SpellCell>()
    val answer = mutableListOf<String>()
    val answerFaces = mutableListOf<LetterFace>()
    syl.forEachIndexed { si, s ->
        val gap = si in hidden
        s.forEachIndexed { ci, ch ->
            val letter = ch.toString()
            val face = spellFace(letter, form)
            cells.add(
                SpellCell(
                    letter = letter,
                    glyph = face.glyph,
                    script = face.script,
                    fill = gap,
                    slotIndex = if (gap) answer.size else -1,
                    syllableStart = ci == 0,
                )
            )
            if (gap) {
                answer.add(letter)
                answerFaces.add(face)
            }
        }
    }

    val extra = if (mode == SpellSyllableMode.LETTERS_EXACT) 0 else distractors
    val trayFaces = answerFaces + spellIntruders(answer, form, extra, mixed, rng)
    return SpellSyllableRound(
        word = word,
        cells = cells,
        answer = answer,
        answerFaces = answerFaces,
        tray = rng.shuffled(trayFaces).map { f ->
            SpellLetterTile(id = ids.next(), letter = f.base, glyph = f.glyph, script = f.script)
        },
    )
}

/**
 * `extra` distractor faces for the tray. Plain: wrong LETTERS in the same writing
 * (the classic intrus). Mixed: prefer the SAME gap letters in the two OTHER
 * writings (the point of the game), then top up with wrong letters in random
 * writings. Deduped against the answer + each other so no tile is a valid answer.
 */
// NB: three things here are load-bearing under a seed and must not be tidied:
//   - `for (base in orderedUnique(answer))` walks the answer's distinct letters
//     in FIRST-APPEARANCE order (JS `new Set(answer)`); a plain Kotlin `Set`
//     would still happen to preserve it today, but the named helper is the pinned
//     contract (see its own test).
//   - `others` is NOT shuffled again at the end — it is already in shuffled
//     bank order.
//   - the random form for `others` is drawn PER ELEMENT, inside the loop, over
//     the whole filtered bank (up to 22 draws), even though at most `extra` are
//     used. The draw count is part of the reproduction. Note the plain branch
//     shuffles the FILTERED bank while this one shuffles the WHOLE bank and
//     filters after — a different draw count, copied from the TS as-is.
internal fun spellIntruders(
    answer: List<String>,
    form: SpellForm,
    extra: Int,
    mixed: Boolean,
    rng: RandomSource,
): List<LetterFace> {
    if (extra <= 0) return emptyList()
    if (!mixed) {
        val need = answer.toSet()
        return rng.shuffled(SOUND_LETTER_BANK.filter { it !in need })
            .take(extra)
            .map { spellFace(it, form) }
    }

    val seen = answer.mapTo(mutableSetOf()) { faceKey(spellFace(it, form)) }
    fun take(face: LetterFace, into: MutableList<LetterFace>) {
        val k = faceKey(face)
        if (k in seen) return
        seen.add(k)
        into.add(face)
    }

    // Same letters, wrong writings — the traps that make "the right case" matter.
    val wrongForm = mutableListOf<LetterFace>()
    for (base in orderedUnique(answer)) {
        for (f in MIXED_FORMS) take(spellFace(base, f), wrongForm)
    }

    // Wrong letters, random writings — fill any remainder.
    val others = mutableListOf<LetterFace>()
    val answerSet = answer.toSet()
    for (base in rng.shuffled(SOUND_LETTER_BANK).filter { it !in answerSet }) {
        take(spellFace(base, MIXED_FORMS[rng.next(MIXED_FORMS.size)]), others)
    }

    return (rng.shuffled(wrongForm) + others).take(extra)
}
