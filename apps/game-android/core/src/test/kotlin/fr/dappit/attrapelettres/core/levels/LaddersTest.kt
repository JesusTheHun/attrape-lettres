package fr.dappit.attrapelettres.core.levels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ladder spine: the nine authored difficulty ladders, their level-count
 * constants and their clamping accessors.
 *
 * Ports the accessor half of every `describe("… / …Level")` block in
 * apps/game-web/src/levels.test.ts — `readImageLevel`, `syllableTier`,
 * `soundLevel`, `findSoundLevel`, `twinLevel` and their siblings all assert the
 * same two things there ("clamps out-of-range levels to the ladder ends", "maps
 * level n to entry n"), so they are gathered here rather than repeated in each
 * exercise's suite. The pool assertions that go with them belong to the builder
 * files, which own the pools.
 */
class LaddersTest {

    /* Level counts ---------------------------------------------------------- */

    /**
     * The shipped ladder lengths. They are what the hub renders as a row of
     * level buttons, and `ledgerKey` embeds the level number in stored, synced
     * data — so shortening a ladder strands a child's cleared levels rather than
     * merely changing the UI.
     */
    @Test
    fun `the ladders ship the lengths the hub advertises`() {
        assertEquals(5, FIRST_LETTER_LEVELS.size)
        assertEquals(4, READ_IMAGE_LEVEL_COUNT)
        assertEquals(4, LETTER_MATCH_LEVEL_COUNT)
        assertEquals(4, SYLLABLE_LEVEL_COUNT)
        assertEquals(5, SOUND_LEVEL_COUNT)
        assertEquals(4, FIND_SOUND_LEVEL_COUNT)
        assertEquals(8, SYLLABLE_GRID_LEVEL_COUNT)
        assertEquals(4, TWIN_LEVEL_COUNT)
        assertEquals(4, SPELL_SYLLABLE_LEVEL_COUNT)
    }

    @Test
    fun `every level-count constant is derived from its ladder, never inlined`() {
        assertEquals(READ_IMAGE_LEVELS.size, READ_IMAGE_LEVEL_COUNT)
        assertEquals(LETTER_MATCH_LEVELS.size, LETTER_MATCH_LEVEL_COUNT)
        assertEquals(SYLLABLE_TIERS.size, SYLLABLE_LEVEL_COUNT)
        assertEquals(SOUND_LEVELS.size, SOUND_LEVEL_COUNT)
        assertEquals(FIND_SOUND_LEVELS.size, FIND_SOUND_LEVEL_COUNT)
        assertEquals(SYLLABLE_GRID_LEVELS.size, SYLLABLE_GRID_LEVEL_COUNT)
        assertEquals(TWIN_LEVELS.size, TWIN_LEVEL_COUNT)
        assertEquals(SPELL_SYLLABLE_LEVELS.size, SPELL_SYLLABLE_LEVEL_COUNT)
    }

    /* The shared clamp ------------------------------------------------------ */

    @Test
    fun `levelIndex turns a 1-based level into a 0-based index`() {
        assertEquals(0, levelIndex(1, 4))
        assertEquals(3, levelIndex(4, 4))
    }

    @Test
    fun `levelIndex clamps both ends instead of trapping`() {
        // Invariant 3 applied to navigation: a level number that is somehow out
        // of range lands on the nearest real level, never on a crash.
        assertEquals(0, levelIndex(0, 4))
        assertEquals(0, levelIndex(-999, 4))
        assertEquals(3, levelIndex(999, 4))
    }

    /* Per-ladder accessors -------------------------------------------------- */

    // `assertSame` throughout, not `assertEquals`: the TS tests are `toBe`
    // (reference identity), and these ladders hold value-equal entries — the two
    // `SoundLevel(distractors = 2)` rows, `FIND_SOUND_LEVELS[1]` and `[2]`, the
    // last three grid levels, the first two twin levels. Value equality would
    // pass on the wrong entry.

    @Test
    fun `readImageLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(READ_IMAGE_LEVELS[0], readImageLevel(0))
        assertSame(READ_IMAGE_LEVELS[READ_IMAGE_LEVEL_COUNT - 1], readImageLevel(999))
    }

    @Test
    fun `letterMatchLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(LETTER_MATCH_LEVELS[0], letterMatchLevel(0))
        assertSame(LETTER_MATCH_LEVELS[LETTER_MATCH_LEVEL_COUNT - 1], letterMatchLevel(999))
    }

    @Test
    fun `syllableTier clamps out-of-range levels to the ladder ends`() {
        assertSame(SYLLABLE_TIERS[0], syllableTier(0))
        assertSame(SYLLABLE_TIERS[SYLLABLE_TIERS.size - 1], syllableTier(999))
    }

    @Test
    fun `syllableTier maps level n to tier n`() {
        assertSame(SYLLABLE_TIERS[1], syllableTier(2))
    }

    @Test
    fun `soundLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(SOUND_LEVELS[0], soundLevel(0))
        assertSame(SOUND_LEVELS[SOUND_LEVEL_COUNT - 1], soundLevel(999))
    }

    @Test
    fun `findSoundLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(FIND_SOUND_LEVELS[0], findSoundLevel(0))
        assertSame(FIND_SOUND_LEVELS[FIND_SOUND_LEVEL_COUNT - 1], findSoundLevel(999))
    }

    @Test
    fun `syllableGridLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(SYLLABLE_GRID_LEVELS[0], syllableGridLevel(0))
        assertSame(
            SYLLABLE_GRID_LEVELS[SYLLABLE_GRID_LEVEL_COUNT - 1],
            syllableGridLevel(999),
        )
    }

    @Test
    fun `twinLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(TWIN_LEVELS[0], twinLevel(0))
        assertSame(TWIN_LEVELS[TWIN_LEVEL_COUNT - 1], twinLevel(999))
    }

    @Test
    fun `spellSyllableLevel clamps out-of-range levels to the ladder ends`() {
        assertSame(SPELL_SYLLABLE_LEVELS[0], spellSyllableLevel(0))
        assertSame(
            SPELL_SYLLABLE_LEVELS[SPELL_SYLLABLE_LEVEL_COUNT - 1],
            spellSyllableLevel(999),
        )
    }

    /**
     * The index helpers the pools share with their accessors. They must agree,
     * or a level would draw its config from one rung and its content from
     * another — the silent kind of drift, since both would still be valid.
     */
    @Test
    fun `each index helper agrees with the accessor built on it`() {
        for (level in -1..12) {
            assertSame(SOUND_LEVELS[soundIdx(level)], soundLevel(level), "sound $level")
            assertSame(
                FIND_SOUND_LEVELS[findSoundIdx(level)],
                findSoundLevel(level),
                "find-sound $level",
            )
            assertSame(
                SYLLABLE_GRID_LEVELS[gridIdx(level)],
                syllableGridLevel(level),
                "grid $level",
            )
            assertSame(TWIN_LEVELS[twinIdx(level)], twinLevel(level), "twins $level")
            assertSame(
                SPELL_SYLLABLE_LEVELS[spellSyllableIdx(level)],
                spellSyllableLevel(level),
                "spell-syllable $level",
            )
        }
    }

    /* Ladder shape ---------------------------------------------------------- */

    /**
     * `repeatSession` clamps `repeats` to `pick`, so authoring more repeats than
     * picks would silently shorten a run instead of failing. Nothing shipped
     * relies on that clamp; assert it stays that way.
     */
    @Test
    fun `no ladder authors more repeats than it picks`() {
        val runs = FIRST_LETTER_LEVELS.map { it.pick to it.repeats } +
            READ_IMAGE_LEVELS.map { it.pick to it.repeats } +
            LETTER_MATCH_LEVELS.map { it.pick to it.repeats } +
            SYLLABLE_TIERS.map { it.pick to it.repeats } +
            FIND_SOUND_LEVELS.map { it.pick to it.repeats } +
            SYLLABLE_GRID_LEVELS.map { it.pick to it.repeats } +
            TWIN_LEVELS.map { it.pick to it.repeats } +
            SPELL_SYLLABLE_LEVELS.map { it.pick to it.repeats } +
            listOf(SOUND_PICK to SOUND_REPEATS)
        for ((pick, repeats) in runs) {
            assertTrue(pick > 0, "a run of $pick rounds")
            assertTrue(repeats in 1..pick, "$repeats repeats over $pick picks")
        }
    }

    /**
     * A ladder never gets EASIER as the level rises: the number of wrong tiles
     * crowding the answer is non-decreasing on every ladder that has one. This
     * is the difficulty axis the CLAUDE.md "Tune progression" recipe edits, and
     * it is the one direction the recipe must not be allowed to invert by
     * accident.
     */
    @Test
    fun `distractor counts never fall as the level rises`() {
        assertNonDecreasing("read-image", READ_IMAGE_LEVELS.map { it.distractors })
        assertNonDecreasing("letter-match", LETTER_MATCH_LEVELS.map { it.distractors })
        assertNonDecreasing("spell-sound", SOUND_LEVELS.map { it.distractors })
        assertNonDecreasing("find-sound", FIND_SOUND_LEVELS.map { it.distractors })
        assertNonDecreasing("twins", TWIN_LEVELS.map { it.distractors })
        assertNonDecreasing("spell-syllable", SPELL_SYLLABLE_LEVELS.map { it.distractors })
        // The grid's two knobs: tiles on screen, and how many of them swap the
        // consonant instead of the vowel.
        assertNonDecreasing("grid choices", SYLLABLE_GRID_LEVELS.map { it.choices })
        assertNonDecreasing("grid column", SYLLABLE_GRID_LEVELS.map { it.column })
    }

    /**
     * The first-letter and letter-match catalogs grow strictly, and both end on
     * `null` — the "full catalog" sentinel. A level that shrank the catalog
     * would teach fewer letters than the level before it.
     */
    @Test
    fun `the letter catalogs grow and end on the full catalog`() {
        val firstLetterSizes = FIRST_LETTER_LEVELS.dropLast(1).map { it.letters!!.size }
        assertNonDecreasing("first-letter catalog", firstLetterSizes)
        assertNull(FIRST_LETTER_LEVELS.last().letters)

        val matchSizes = LETTER_MATCH_LEVELS.dropLast(1).map { it.letters!!.size }
        assertEquals(listOf(6, 13, 19), matchSizes)
        assertNull(LETTER_MATCH_LEVELS.last().letters)
    }

    /**
     * Every authored catalog is a set of distinct uppercase letters. A duplicate
     * would skew the distractor draw toward one letter for no authored reason.
     */
    @Test
    fun `letter catalogs hold distinct uppercase letters`() {
        val catalogs = FIRST_LETTER_LEVELS.mapNotNull { it.letters } +
            LETTER_MATCH_LEVELS.mapNotNull { it.letters }
        for (letters in catalogs) {
            assertEquals(letters.size, letters.toSet().size, "$letters has a duplicate")
            for (l in letters) {
                assertEquals(l, l.uppercase(), "$l is not uppercase")
                assertEquals(1, l.length, "$l is not a single letter")
            }
        }
    }

    @Test
    fun `the syllable tiers widen from two syllables to four`() {
        for (tier in SYLLABLE_TIERS) {
            assertTrue(
                tier.minSyllables <= tier.maxSyllables,
                "${tier.minSyllables}..${tier.maxSyllables} is empty",
            )
        }
        assertEquals(2, SYLLABLE_TIERS.first().minSyllables)
        assertEquals(4, SYLLABLE_TIERS.last().maxSyllables)
        assertNonDecreasing("tier floor", SYLLABLE_TIERS.map { it.minSyllables })
        assertNonDecreasing("tier ceiling", SYLLABLE_TIERS.map { it.maxSyllables })
    }

    /** Level 1 of spell-the-sound is deliberately intruder-free — the tutorial rung. */
    @Test
    fun `spell-the-sound level 1 has no intruders`() {
        assertEquals(0, soundLevel(1).distractors)
        assertTrue(soundLevel(2).distractors > 0)
    }

    @Test
    fun `a full spell-the-sound run is its picks plus its replays`() {
        assertEquals(8, SOUND_PICK)
        assertEquals(4, SOUND_REPEATS)
        assertEquals(SOUND_PICK + SOUND_REPEATS, SOUND_SESSION_LENGTH)
        assertEquals(12, SOUND_SESSION_LENGTH)
    }

    private fun assertNonDecreasing(what: String, values: List<Int>) {
        for ((before, after) in values.zipWithNext()) {
            assertTrue(after >= before, "$what falls: $values")
        }
    }
}
