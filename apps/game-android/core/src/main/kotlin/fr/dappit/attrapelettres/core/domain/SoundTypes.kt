package fr.dappit.attrapelettres.core.domain

/* -------------------------------------------------------------------------- */
/* Port of the two sound ladders from `src/types.ts` + `src/levels.ts`:         */
/* find-the-sound (recognition, the 4yo rung) and spell-the-sound (production). */
/* -------------------------------------------------------------------------- */

/* Find-the-sound exercise --------------------------------------------------- */

/**
 * The youngest rung of the sound ladder (recognition; spell-sound is
 * production): the child HEARS a sound with its anchor word (« ou, comme dans
 * hibou ») and taps the tile that writes it. Same one-prompt/one-tile loop as
 * first-letter, so a pre-reader already knows how to play — no reading needed.
 */
data class BasicSound(
    /** Spoken sound, lowercase for the TTS/VO (e.g. "ou", "or", "che"). */
    val sound: String,
    /** The written form shown on the tile, uppercase (e.g. "OU", "CH"). */
    val graphy: String,
    /** Anchor word the sound lives in — spoken as "comme dans …" + shown as emoji. */
    val word: String,
    val emoji: String,
    /**
     * Authored confusable graphies (from the SAME level pool) preferred as
     * distractors — adaptive-by-confusability done as data (OU vs ON, AN vs IN…).
     */
    val traps: List<String>? = null,
)

data class FindSoundLevel(
    /** Distinct sounds drawn from the level pool at the start of a run. */
    val pick: Int,
    /** How many of those come back a second time (spaced apart). */
    val repeats: Int,
    /** Wrong-graphy tiles shown beside the correct one. */
    val distractors: Int,
)

data class FindSoundRound(
    val target: BasicSound,
    /** The target graphy plus distractor graphies, shuffled. */
    val choices: List<BasicSound>,
)

/* Spell-the-sound exercise -------------------------------------------------- */

/**
 * One heard sound the child must re-spell by picking letters in order. The point
 * of the ladder: the same `sound` gets several `spelling`s across rounds (o / au
 * / eau, f / ph…), so the child memorises that one sound has many written forms.
 */
data class SoundTarget(
    /** Spoken syllable / phoneme, lowercase for the TTS (e.g. "lo", "fo", "oi"). */
    val sound: String,
    /** Ordered letter tiles that spell it, uppercase (e.g. ["L","O"], ["P","H","O"]). */
    val spelling: List<String>,
    /** Real word this spelling lives in — spoken as "comme dans …" + shown as emoji. */
    val word: String? = null,
    /** Illustration for the context word. */
    val emoji: String? = null,
)

data class SoundLevel(
    /** Wrong letter-tiles added to the tray (0 = only the needed letters). */
    val distractors: Int,
)

data class SoundTile(
    val id: Int,
    val letter: String,
)

data class SoundRound(
    val target: SoundTarget,
    /** Target order; slots are filled against target.spelling. */
    val slots: List<String?>,
    val tray: List<SoundTile>,
)
