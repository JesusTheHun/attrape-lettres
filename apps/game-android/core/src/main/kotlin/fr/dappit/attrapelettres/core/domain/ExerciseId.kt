package fr.dappit.attrapelettres.core.domain

/**
 * The nav/routing key, and half of a persistence contract.
 *
 * `wire` values are FROZEN. They are not display strings and they are not an
 * implementation detail: they are one half of `ledgerKey` ("<exercise>:<level>"),
 * which is a stored key inside every child's clear counters and therefore
 * travels over the sync wire to the family's other phones. Renaming one here
 * would orphan a level's history on this platform only, and the web app and the
 * iOS app would go on writing the old string. Add cases; never rewrite one.
 *
 * Declaration order is the hub order, which the economy leans on: the reward
 * gradient is monotone with it, so climbing always out-pays grinding
 * (invariant 8).
 */
enum class ExerciseId(val wire: String) {
    FIRST_LETTER("first-letter"),
    FIND_SOUND("find-sound"),
    HEAR_SYLLABLE("hear-syllable"),
    PICK_VOWEL("pick-vowel"),
    FILL_BLANK("fill-blank"),
    ORDER_SYLLABLES("order-syllables"),
    FIND_INTRUDER("find-intruder"),
    SPELL_SOUND("spell-sound"),
    SPELL_SYLLABLE("spell-syllable"),
    SPELL_SYLLABLE_PLUS("spell-syllable-plus"),
    SPELL_TWO_SYLLABLES("spell-two-syllables"),
    READ_IMAGE("read-image"),
    MATCH_CASE("match-case"),
    MATCH_SCRIPT("match-script"),
    SOUND_TWINS("sound-twins"),
    SPELL_SYLLABLE_PLUS_MIXED("spell-syllable-plus-mixed"),
    SPELL_TWO_SYLLABLES_MIXED("spell-two-syllables-mixed"),
    ;

    companion object {
        private val byWire = entries.associateBy(ExerciseId::wire)

        /** Null for an unknown string — a stored key from a newer build is data to skip, not a crash. */
        fun fromWire(wire: String): ExerciseId? = byWire[wire]
    }
}

/**
 * Reward weight of an exercise — the anti-farming knob.
 *
 * 0 = training: finishing pays the completion curve like any other row, but no
 * accuracy bonus exists there, so careful play is worth exactly what spam is.
 * 1–4 = how many bonus points a full first-try run earns on top of the curve.
 * See [fr.dappit.attrapelettres.core.rewards.sessionReward].
 */
@JvmInline
value class Difficulty(val weight: Int) {
    init {
        require(weight in 0..MAX) { "difficulty must be 0…$MAX, was $weight" }
    }

    companion object {
        const val MAX = 4

        /** No accuracy bonus exists on this row, so there is nothing to grind for. */
        val TRAINING = Difficulty(0)
    }
}

enum class Mood { IDLE, HAPPY, CHEER }

enum class Verdict { ACCEPT, REJECT }
