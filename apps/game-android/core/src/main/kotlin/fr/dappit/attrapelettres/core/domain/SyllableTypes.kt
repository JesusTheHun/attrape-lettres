package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the syllable half of `src/types.ts` + the round/level shapes from    */
/* `src/levels.ts`. Covers both families that live on syllables: the assemble   */
/* engine (SyllableMode) and the fill-a-syllable engine (SpellSyllableMode).    */
/* -------------------------------------------------------------------------- */

/* Build-syllables exercise ------------------------------------------------- */

data class SyllableWord(
    val word: String,
    /** Pre-authored orthographic split, in reading order. */
    val syllables: List<String>,
    val emoji: String,
    /** Optional dedicated illustration shown instead of [emoji]. See LetterWord.img / WordIcon. */
    val img: ImageKey? = null,
)

/**
 * Seeding mode for the assemble engine. Mode only reaches `buildSyllableRound`;
 * the assembly loop is mode-agnostic. The `wire` strings are the TS union
 * values copied byte for byte, hyphens included — the web app is compared
 * against line by line, so a renamed value here is silent drift, not style.
 */
enum class SyllableMode(val wire: String) {
    FILL_BLANK("fill-blank"),
    ORDER("order"),
    ORDER_DISTRACTOR("order-distractor"),
}

/* Fill-a-syllable exercise ------------------------------------------------- */

/**
 * ONE engine, three siblings. Part of the word is already written; one (or two)
 * syllable is blanked into per-letter slots the child fills by tapping letters
 * in the right order. Mode only changes what lands in the tray / how many gaps:
 *   - `letters-exact`  one gap, tray = exactly that syllable's letters (order only).
 *   - `letters-extra`  one gap, tray = those letters + intruder letters.
 *   - `letters-two`    two gaps, tray = both syllables' letters + intruders.
 * All three share the SAME word ladder (level per level), so a child meets the
 * same words as the task gets harder.
 */
enum class SpellSyllableMode(val wire: String) {
    LETTERS_EXACT("letters-exact"),
    LETTERS_EXTRA("letters-extra"),
    LETTERS_TWO("letters-two"),
}

data class SyllableTier(
    val minSyllables: Int,
    val maxSyllables: Int,
    /** Distinct words drawn from the tier pool at the start of a run. */
    val pick: Int,
    /** How many of those words come back a second time (spaced apart). */
    val repeats: Int,
)

data class SyllableTile(
    val id: Int,
    val syllable: String,
)

data class SyllableRound(
    val word: SyllableWord,
    /**
     * Target order; slots are filled against this.
     *
     * NB: `(string | null)[]` → `List<String?>`, exactly. The engines index it
     * and write null back when a child takes a tile out of a slot (via `copy`
     * on this side of the port), so do not "improve" it into a sealed type.
     */
    val slots: List<String?>,
    /** Which slots are pre-revealed and locked (fill-blank). */
    val locked: List<Boolean>,
    val tray: List<SyllableTile>,
)

/* Fill-a-syllable levels + round ------------------------------------------- */

data class SpellSyllableLevel(
    /** Distinct words drawn from the level's list at the start of a run. */
    val pick: Int,
    /** How many of those words come back a second time (spaced apart). */
    val repeats: Int,
    /** Intruder letters added to the tray for the -extra / -two modes (0 for exact). */
    val distractors: Int,
)

data class SpellCell(
    /** The correct letter at this position, canonical UPPERCASE (answer + VO). */
    val letter: String,
    /** The exact glyph to render, cased for the round's form (e.g. "A"/"a"/"a"). */
    val glyph: String,
    /** print / cursive for the round's form (drives the font). */
    val script: LetterScript,
    /** True = a slot the child fills; false = already written (locked). */
    val fill: Boolean,
    /** Index among fill cells in reading order, or -1 when shown. */
    val slotIndex: Int,
    /** First letter of its syllable — used to gap-space the written word. */
    val syllableStart: Boolean,
)

data class SpellLetterTile(
    val id: Int,
    /** Canonical UPPERCASE letter — VO name + answer identity. */
    val letter: String,
    /** The exact glyph to render (cased). In plain rounds glyph === letter. */
    val glyph: String,
    val script: LetterScript,
)

data class SpellSyllableRound(
    val word: SyllableWord,
    /** The whole word, letter by letter: shown letters + the gap's slots, in order. */
    val cells: List<SpellCell>,
    /** The gap letters (base, uppercase) in reading order. */
    val answer: List<String>,
    /**
     * The gap FACES in reading order — what a filled slot must equal. In plain
     * rounds a face is just the uppercase print letter; in "mixed" rounds it also
     * pins the case + script, so the child must match the writing, not only the
     * letter.
     */
    val answerFaces: List<LetterFace>,
    /** Shuffled letter tiles the child taps. */
    val tray: List<SpellLetterTile>,
)
