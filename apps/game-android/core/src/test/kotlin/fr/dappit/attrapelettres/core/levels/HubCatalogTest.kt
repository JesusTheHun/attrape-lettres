package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.rewards.sessionReward
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of the "hub placement of the grid drills" block of
 * apps/game-web/src/levels.test.ts and of the iOS `HubCatalogTests`, plus the
 * catalog properties the TypeScript got from `Record<ExerciseId, …>` for free
 * and Kotlin has to assert.
 *
 * The web app is the source of truth, so these assertions are copied, not
 * re-derived — if one of them ever has to change here alone, the two apps have
 * drifted and that is the finding.
 */
class HubCatalogTest {

    private fun at(id: ExerciseId): Int = EXERCISES.indexOfFirst { it.id == id }

    /**
     * The ONLY ordering the TypeScript itself pins. Deliberately no full-order
     * golden test: that would freeze something the source leaves loose.
     */
    @Test
    fun `puts both combinatoire drills before every word exercise`() {
        for (id in listOf(ExerciseId.HEAR_SYLLABLE, ExerciseId.PICK_VOWEL)) {
            assertTrue(at(id) > at(ExerciseId.FIND_SOUND), "$id after find-sound")
            assertTrue(at(id) < at(ExerciseId.SPELL_SYLLABLE), "$id before spell-syllable")
            assertTrue(at(id) < at(ExerciseId.SPELL_SOUND), "$id before spell-sound")
        }
    }

    @Test
    fun `catalog covers every exercise exactly once`() {
        assertEquals(ExerciseId.entries.toSet(), EXERCISES.map { it.id }.toSet())
        assertEquals(ExerciseId.entries.size, EXERCISES.size)
    }

    /**
     * `levelCount` is DERIVED from the ladders, never inlined — adding a level
     * must update the hub for free. The `when` is exhaustive over the enum with
     * no `else`, so a new exercise cannot be added without deciding which ladder
     * it rides.
     */
    @Test
    fun `levelCount is derived from the ladder the row claims`() {
        for (e in EXERCISES) {
            val expected = when (e.id) {
                ExerciseId.FIRST_LETTER -> FIRST_LETTER_LEVELS.size
                ExerciseId.FIND_SOUND -> FIND_SOUND_LEVEL_COUNT
                ExerciseId.HEAR_SYLLABLE, ExerciseId.PICK_VOWEL -> SYLLABLE_GRID_LEVEL_COUNT
                ExerciseId.FILL_BLANK,
                ExerciseId.ORDER_SYLLABLES,
                ExerciseId.FIND_INTRUDER,
                -> SYLLABLE_LEVEL_COUNT
                ExerciseId.SPELL_SOUND -> SOUND_LEVEL_COUNT
                ExerciseId.SPELL_SYLLABLE,
                ExerciseId.SPELL_SYLLABLE_PLUS,
                ExerciseId.SPELL_TWO_SYLLABLES,
                ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
                ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
                -> SPELL_SYLLABLE_LEVEL_COUNT
                ExerciseId.READ_IMAGE -> READ_IMAGE_LEVEL_COUNT
                ExerciseId.MATCH_CASE, ExerciseId.MATCH_SCRIPT -> LETTER_MATCH_LEVEL_COUNT
                ExerciseId.SOUND_TWINS -> TWIN_LEVEL_COUNT
            }
            assertEquals(expected, e.levelCount, e.id.wire)
            assertTrue(e.levelCount > 0, "${e.id.wire} has no levels")
        }
    }

    @Test
    fun `exerciseDifficulty reads the catalog`() {
        for (e in EXERCISES) {
            assertEquals(e.difficulty, exerciseDifficulty(e.id))
        }
    }

    /** The training exercises — the only rows that pay no accuracy bonus. */
    @Test
    fun `only first-letter and fill-blank are training`() {
        val training = EXERCISES.filter { it.difficulty == Difficulty.TRAINING }.map { it.id }
        assertEquals(setOf(ExerciseId.FIRST_LETTER, ExerciseId.FILL_BLANK), training.toSet())
    }

    /**
     * INVARIANT 8, stated exactly as CLAUDE.md states it: "the best a training
     * row can pay is the worst a paying row can pay". A perfect run on a
     * difficulty-0 row earns the bare completion curve — precisely what
     * spam-tapping earns on the cheapest paying row — so there is never a reason
     * to farm the bottom of the hub. Asserted through [sessionReward] because
     * that is the only function in the app that hands out points.
     */
    @Test
    fun `the best a training row can pay is the worst a paying row can pay`() {
        val training = EXERCISES.filter { it.difficulty.weight == 0 }
        val paying = EXERCISES.filter { it.difficulty.weight > 0 }
        assertTrue(training.isNotEmpty(), "there must be a training row to compare")
        assertTrue(paying.isNotEmpty(), "there must be a paying row to compare")
        // Across the whole reward curve, not just the first clear: the property
        // has to hold for a child who has cleared a level ten times already.
        for (priorClears in 0..6) {
            val bestTraining = training.maxOf { sessionReward(it.difficulty, priorClears, 10, 10) }
            val worstPaying = paying.minOf { sessionReward(it.difficulty, priorClears, 0, 10) }
            assertEquals(bestTraining, worstPaying, "prior clears = $priorClears")
        }
    }

    /**
     * The gradient rises with the hub order everywhere except three rows, and
     * those three are the shipped behaviour, ported deliberately (see the note
     * above `EXERCISES`). Pinning them by NAME rather than by index catches a new
     * break in the gradient without freezing the hub order the source leaves
     * loose.
     */
    @Test
    fun `the only dips in the gradient are the three shipped ones`() {
        val dips = EXERCISES.zipWithNext()
            .filter { (before, after) -> after.difficulty.weight < before.difficulty.weight }
            .map { (_, after) -> after.id }
        assertEquals(
            listOf(ExerciseId.FILL_BLANK, ExerciseId.READ_IMAGE, ExerciseId.MATCH_CASE),
            dips,
        )
    }

    /**
     * The top of the economy sits at the bottom of the hub: only the two
     * « écritures mêlées » capstones carry the maximum weight, and they are the
     * last two rows. Climbing is the point-optimal strategy because the biggest
     * bonus is the furthest away.
     */
    @Test
    fun `only the mêlées capstones carry the top weight, and they come last`() {
        val top = EXERCISES.filter { it.difficulty.weight == Difficulty.MAX }.map { it.id }
        assertEquals(
            listOf(
                ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
                ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
            ),
            top,
        )
        assertEquals(top, EXERCISES.takeLast(2).map { it.id })
    }

    @Test
    fun `every row carries an authored difficulty inside the legal range`() {
        for (e in EXERCISES) {
            assertTrue(e.difficulty.weight in 0..Difficulty.MAX, e.id.wire)
        }
    }

    /**
     * Recovers the exhaustiveness `Record<K, V>` gave the TypeScript for free.
     *
     * `GRID_PROMPT` is the fourth such dictionary in the web app; it lives with
     * the syllable-grid ladder rather than with the hub catalog, so its totality
     * is asserted by that ladder's own suite.
     */
    @Test
    fun `hint dictionaries are total`() {
        assertEquals(SyllableMode.entries.size, MODE_HINT.size)
        assertEquals(LetterMatchKind.entries.size, MATCH_HINT.size)
        assertEquals(SpellSyllableMode.entries.size, SPELL_HINT.size)
        for (m in SyllableMode.entries) assertNotNull(MODE_HINT[m], "$m")
        for (m in LetterMatchKind.entries) assertNotNull(MATCH_HINT[m], "$m")
        for (m in SpellSyllableMode.entries) assertNotNull(SPELL_HINT[m], "$m")
    }

    /**
     * French copy is a VO lookup key — the typographic apostrophe, the ellipsis
     * character and the em dash are load-bearing bytes and must never be
     * normalised to their ASCII lookalikes.
     */
    @Test
    fun `French copy keeps its typographic punctuation`() {
        assertEquals("Trouve l’intrus", EXERCISES[at(ExerciseId.FIND_INTRUDER)].name)
        assertEquals("Remets les syllabes dans l’ordre", MODE_HINT[SyllableMode.ORDER])
        assertEquals(
            "Range le mot… et évite l’intrus !",
            MODE_HINT[SyllableMode.ORDER_DISTRACTOR],
        )
        assertEquals(
            "Range les lettres… évite les intrus",
            SPELL_HINT[SpellSyllableMode.LETTERS_EXTRA],
        )
        assertEquals("Associe le script et l’attaché", MATCH_HINT[LetterMatchKind.SCRIPT])
        assertEquals("GRANDE, petite ou attachée — trouve la bonne", MIXED_HINT)
        assertEquals(
            "La consonne est écrite — pose la voyelle",
            EXERCISES[at(ExerciseId.PICK_VOWEL)].hint,
        )
        assertEquals(
            "VA, VE, VI… trouve celle que tu entends",
            EXERCISES[at(ExerciseId.HEAR_SYLLABLE)].hint,
        )
    }

    /** The mode / grid / spell / match columns are what the router switches on. */
    @Test
    fun `mode columns match the engine each row runs`() {
        assertEquals(SyllableMode.FILL_BLANK, EXERCISES[at(ExerciseId.FILL_BLANK)].mode)
        assertEquals(SyllableMode.ORDER, EXERCISES[at(ExerciseId.ORDER_SYLLABLES)].mode)
        assertEquals(
            SyllableMode.ORDER_DISTRACTOR,
            EXERCISES[at(ExerciseId.FIND_INTRUDER)].mode,
        )
        assertEquals(SyllableGridMode.HEAR, EXERCISES[at(ExerciseId.HEAR_SYLLABLE)].grid)
        assertEquals(SyllableGridMode.VOWEL, EXERCISES[at(ExerciseId.PICK_VOWEL)].grid)
        assertEquals(LetterMatchKind.CASE, EXERCISES[at(ExerciseId.MATCH_CASE)].match)
        assertEquals(LetterMatchKind.SCRIPT, EXERCISES[at(ExerciseId.MATCH_SCRIPT)].match)
        assertEquals(
            SpellSyllableMode.LETTERS_EXACT,
            EXERCISES[at(ExerciseId.SPELL_SYLLABLE)].spell,
        )
        assertEquals(
            SpellSyllableMode.LETTERS_EXTRA,
            EXERCISES[at(ExerciseId.SPELL_SYLLABLE_PLUS)].spell,
        )
        assertEquals(
            SpellSyllableMode.LETTERS_TWO,
            EXERCISES[at(ExerciseId.SPELL_TWO_SYLLABLES)].spell,
        )
        assertTrue(EXERCISES[at(ExerciseId.SPELL_SYLLABLE_PLUS_MIXED)].mixed)
        assertTrue(EXERCISES[at(ExerciseId.SPELL_TWO_SYLLABLES_MIXED)].mixed)
        // Only the two « écritures mêlées » twins carry `mixed`.
        assertEquals(2, EXERCISES.count { it.mixed })
    }

    /**
     * Every mode discriminator is claimed by at least one row, so no engine ships
     * unreachable from the hub.
     */
    @Test
    fun `every engine discriminator is claimed by a row`() {
        assertEquals(SyllableMode.entries.toSet(), EXERCISES.mapNotNull { it.mode }.toSet())
        assertEquals(SyllableGridMode.entries.toSet(), EXERCISES.mapNotNull { it.grid }.toSet())
        assertEquals(SpellSyllableMode.entries.toSet(), EXERCISES.mapNotNull { it.spell }.toSet())
        assertEquals(LetterMatchKind.entries.toSet(), EXERCISES.mapNotNull { it.match }.toSet())
    }

    /**
     * INVARIANT 5, as far as a runtime test can reach it: every level of every
     * row is addressable with nothing but its number — no profile, no ledger, no
     * unlock check anywhere in the catalog. The structural half of the proof is
     * the missing `core.persistence` import in `Levels.kt`.
     */
    @Test
    fun `every level of every row resolves to a real run without a profile`() {
        for (e in EXERCISES) {
            for (level in 1..e.levelCount) {
                // The run length the ladder promises for this level. Reaching it
                // takes the level number and nothing else — no roster, no ledger,
                // no unlock check exists to consult.
                val pick = when (e.id) {
                    ExerciseId.FIRST_LETTER -> FIRST_LETTER_LEVELS[level - 1].pick
                    ExerciseId.FIND_SOUND -> findSoundLevel(level).pick
                    ExerciseId.HEAR_SYLLABLE, ExerciseId.PICK_VOWEL ->
                        syllableGridLevel(level).pick
                    ExerciseId.FILL_BLANK,
                    ExerciseId.ORDER_SYLLABLES,
                    ExerciseId.FIND_INTRUDER,
                    -> syllableTier(level).pick
                    ExerciseId.SPELL_SOUND -> SOUND_PICK
                    ExerciseId.SPELL_SYLLABLE,
                    ExerciseId.SPELL_SYLLABLE_PLUS,
                    ExerciseId.SPELL_TWO_SYLLABLES,
                    ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
                    ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
                    -> spellSyllableLevel(level).pick
                    ExerciseId.READ_IMAGE -> readImageLevel(level).pick
                    ExerciseId.MATCH_CASE, ExerciseId.MATCH_SCRIPT ->
                        letterMatchLevel(level).pick
                    ExerciseId.SOUND_TWINS -> twinLevel(level).pick
                }
                assertTrue(pick > 0, "${e.id.wire} level $level has an empty run")
            }
        }
    }
}
