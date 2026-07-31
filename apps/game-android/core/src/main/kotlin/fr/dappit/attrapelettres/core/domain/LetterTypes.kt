package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the letter half of apps/game-web/src/types.ts + the level shapes    */
/* from src/levels.ts + faceLabel from src/letterForms.ts.                     */
/*                                                                            */
/* SCRIPT_FONT deliberately does NOT come along: it is a CSS font stack and    */
/* belongs to the view layer. :core keeps LetterScript; :ui owns the fonts.    */
/* -------------------------------------------------------------------------- */

/* First-letter exercise ---------------------------------------------------- */

data class LetterWord(
    val letter: String,
    val word: String,
    val emoji: String,
    /**
     * Optional dedicated illustration shown INSTEAD of [emoji] when the emoji
     * misrepresents the word (e.g. no true "igloo"/"jupe" glyph). [emoji] is
     * kept as the accessibility/text fallback.
     *
     * NB: in TS this is the Vite-imported SVG URL. :core must not know about
     * resources or Compose, so it holds an [ImageKey] and :art resolves it.
     */
    val img: ImageKey? = null,
)

data class FirstLetterLevel(
    /** First-letter catalog for this level. `null` = full catalog. */
    val letters: List<String>?,
    /** Distinct words drawn from the pool at the start of a run. */
    val pick: Int,
    /** How many of those words come back a second time (spaced apart). */
    val repeats: Int,
)

data class FirstLetterRound(
    val target: LetterWord,
    /** The target letter plus distractors, shuffled. */
    val choices: List<String>,
)

/* Letter-form matching exercise -------------------------------------------- */

/**
 * One engine, two skills: pair a letter with its counterpart FORM. [CASE] pairs
 * a majuscule with its minuscule (both directions in one run); [SCRIPT] pairs a
 * printed (sans-serif) letter with its cursive "attaché" twin at the SAME case.
 * The underlying letter is the identity; only the rendered form flips.
 */
enum class LetterMatchKind { CASE, SCRIPT }

/** How a letter is drawn on a tile. */
enum class LetterScript { PRINT, CURSIVE }

/** One rendered letter: the same underlying letter, shown in a given case + script. */
data class LetterFace(
    /** Canonical UPPERCASE letter — identity (pick match) + the name spoken by VO. */
    val base: String,
    /** The exact glyph to render, already cased (e.g. "A" or "a"). */
    val glyph: String,
    val script: LetterScript,
)

data class LetterMatchLevel(
    /** Letter catalog (uppercase). `null` = the full alphabet. */
    val letters: List<String>?,
    /** Distinct letters drawn from the pool at the start of a run. */
    val pick: Int,
    /** How many of those come back a second time (spaced apart). */
    val repeats: Int,
    /** Wrong-answer tiles added beside the correct counterpart. */
    val distractors: Int,
)

data class LetterMatchRound(
    /** The letter shown big; the child finds its counterpart form below. */
    val prompt: LetterFace,
    /** Tiles, shuffled — all in the counterpart form; exactly one shares prompt.base. */
    val choices: List<LetterFace>,
)

/**
 * Screen-reader label: names the letter, its case, and (cursive only) its form.
 *
 * Invariant 6 lives here: this is the `contentDescription` a letter tile
 * carries, and it is unit-tested in :core rather than left to the view layer
 * where nothing would test it.
 */
fun faceLabel(face: LetterFace): String {
    // NB: the no-arg `uppercase()` is locale-invariant on purpose. The deprecated
    // locale-sensitive `toUpperCase()` in a Turkish locale maps "i" to "İ", which
    // would flip majuscule/minuscule.
    val caseWord = if (face.glyph == face.glyph.uppercase()) "majuscule" else "minuscule"
    val scriptWord = if (face.script == LetterScript.CURSIVE) " attachée" else ""
    return "Lettre ${face.base} $caseWord$scriptWord"
}
