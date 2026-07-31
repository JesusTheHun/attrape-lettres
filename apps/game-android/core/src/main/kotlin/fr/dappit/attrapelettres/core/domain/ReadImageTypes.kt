package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the read-the-word types from apps/game-web/src/types.ts +           */
/* src/levels.ts, plus the img indirection every word table needs.             */
/* -------------------------------------------------------------------------- */

/**
 * The four dedicated word illustrations under `src/img/`. In TS these are Vite
 * asset URLs (`import jupe from "../img/jupe.svg"`); :core knows nothing about
 * resources or Compose, so it carries a KEY and :art owns the key → drawing
 * table.
 *
 * :art must resolve this with an exhaustive `when` and no `else` branch, never
 * a map — a map lets a new key compile and render nothing.
 */
enum class ImageKey { IGLOO, JUPE, MACARON, PYJAMA }

/* Read-the-word exercise ---------------------------------------------------- */
/* The mirror of first-letter: the WORD is shown, the child reads it and taps   */
/* the matching picture. Reading IS the task, so the word is never spoken; the  */
/* only difficulty axis is how many pictures crowd the choice (2 → 4            */
/* distractors around the answer). Draws from the same curated nouns.           */

data class ReadImageLevel(
    /** Distinct words drawn from the pool at the start of a run. */
    val pick: Int,
    /** How many of those words come back a second time (spaced apart). */
    val repeats: Int,
    /** Wrong-picture tiles shown beside the correct one. */
    val distractors: Int,
)

/** The written word is shown; the child taps the picture that matches it. */
data class ReadImageRound(
    val target: LetterWord,
    /** The target word plus distractor words, shuffled — each rendered as a picture. */
    val choices: List<LetterWord>,
)
