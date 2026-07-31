package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the « tableau des syllabes » types from `src/types.ts` +             */
/* `src/levels.ts`.                                                             */
/* -------------------------------------------------------------------------- */

/* Syllable-grid exercises --------------------------------------------------- */

/**
 * The « tableau des syllabes »: the exhaustive consonant × vowel combinatoire
 * (VA VE VI VO VU VÉ) a child must fuse before any word work. ONE engine, two
 * drills over the SAME grid:
 *   - `hear`   hear « va », tap the tile that writes it (the neighbours are the
 *              same consonant with the other vowels, so the VOWEL is the task).
 *   - `vowel`  hear « vi », the consonant is already written, tap the vowel
 *              that finishes it — the same contrast, from the other side.
 */
enum class SyllableGridMode(val wire: String) {
    HEAR("hear"),
    VOWEL("vowel"),
}

/** One cell of the grid: a consonant row × a vowel column. */
data class GridSyllable(
    /** Written form shown on the tile, uppercase (e.g. "VA", "CHÉ"). */
    val text: String,
    /** Spoken form, lowercase for the TTS/VO (e.g. "va", "ché"). */
    val sound: String,
    /** Its consonant row, uppercase ("V", "CH"). */
    val consonant: String,
    /** Its vowel column, uppercase ("A" … "É"). */
    val vowel: String,
)

data class SyllableGridLevel(
    /** Distinct syllables drawn from the level's rows at the start of a run. */
    val pick: Int,
    /** How many of those come back a second time (spaced apart). */
    val repeats: Int,
    /** Tiles in a round: the answer + its distractors. */
    val choices: Int,
    /**
     * How many distractors come from the same VOWEL column (another consonant,
     * e.g. VA vs LA) instead of the same consonant row (VA vs VI). 0 on the first
     * levels — the vowel alone is the whole task — then the consonant joins in.
     * Ignored in `vowel` mode, where every tile is a vowel by construction.
     */
    val column: Int,
)

data class GridRound(
    val target: GridSyllable,
    /**
     * The tiles, shuffled, target included. In `hear` mode a tile shows its
     * `text` (the whole syllable); in `vowel` mode its `vowel` — every tile then
     * shares the target's consonant, so the tiles ARE the vowel column.
     */
    val choices: List<GridSyllable>,
)
