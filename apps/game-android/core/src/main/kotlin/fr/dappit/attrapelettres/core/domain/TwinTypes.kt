package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the sound-twins types from `src/types.ts` + `src/levels.ts`.         */
/* -------------------------------------------------------------------------- */

/* Sound-twins exercise ------------------------------------------------------ */

/** One written form of a sound family + the anchor word that owns it. */
data class TwinGraphy(
    /** Uppercase tile text (e.g. "CO", "KO", "EAU"). */
    val text: String,
    /** Spoken on this tile's success line (« Oui ! coq. ») — what tells twins apart. */
    val word: String,
    val emoji: String,
)

/**
 * A family = ONE spoken sound and every way the level writes it. The child
 * hears the sound and must find ALL the family's tiles among intruder graphies
 * drawn from the level's other families.
 */
data class TwinFamily(
    /** Spoken sound, lowercase for the TTS/VO (e.g. "ko", "o", "an"). */
    val sound: String,
    /** 2–4 same-sound written forms, each anchored to its own word. */
    val graphies: List<TwinGraphy>,
)

data class TwinLevel(
    /** Distinct families drawn from the level pool at the start of a run. */
    val pick: Int,
    /** How many of those come back a second time (spaced apart). */
    val repeats: Int,
    /** Wrong-family graphy tiles mixed into the round. */
    val distractors: Int,
)

data class TwinTile(
    val id: Int,
    /** Uppercase graphy shown on the tile. */
    val text: String,
    /** The sound THIS tile's own family spells — what "Écouter" speaks. */
    val sound: String,
    /** This graphy's anchor word (success line for correct tiles). */
    val word: String,
    val emoji: String,
    /** True = belongs to the round's family; false = intruder. */
    val correct: Boolean,
)

data class TwinRound(
    val family: TwinFamily,
    /** Family graphies + intruders, shuffled. */
    val tiles: List<TwinTile>,
)
