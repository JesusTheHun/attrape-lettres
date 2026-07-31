package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.core.support.orderedUnique
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `firstLetterPool` block of `src/levels.test.ts` (via the iOS
 * `LevelsFirstLetterTests.swift`), plus the session tests the TypeScript never
 * had — `buildSession` lived inside `FirstLetterExercise.tsx`, where no host
 * test could reach it.
 *
 * Every seeded case runs under a FRESH `SeededGenerator` keyed by the loop
 * index, so a failure is reproducible from the printed seed, never a flake.
 */
class LevelsFirstLetterTest {

    /* firstLetterPool -------------------------------------------------------- */

    @Test
    fun `restricts words to the level's allowed letters`() {
        val level1 = firstLetterPool(1)
        val allowed = FIRST_LETTER_LEVELS[0].letters!!.toSet()
        assertTrue(level1.isNotEmpty())
        assertTrue(level1.all { it.letter in allowed })
    }

    @Test
    fun `returns the full catalog for the null final level`() {
        assertEquals(LETTER_WORDS, firstLetterPool(FIRST_LETTER_LEVELS.size))
    }

    @Test
    fun `every level's pool is big enough for its pick`() {
        for ((i, cfg) in FIRST_LETTER_LEVELS.withIndex()) {
            assertTrue(firstLetterPool(i + 1).size >= cfg.pick, "level ${i + 1}")
        }
    }

    /* buildFirstLetterSession ------------------------------------------------ */

    @Test
    fun `session is pick plus repeats, spaced so no word repeats back-to-back`() {
        for ((i, cfg) in FIRST_LETTER_LEVELS.withIndex()) {
            for (seed in 0 until 40) {
                val run = buildFirstLetterSession(i + 1, SeededGenerator(seed.toLong()))
                assertEquals(cfg.pick + cfg.repeats, run.size, "level ${i + 1} seed $seed")
                for (k in 1 until run.size) {
                    assertNotEquals(
                        run[k - 1].target.word,
                        run[k].target.word,
                        "level ${i + 1} seed $seed",
                    )
                }
            }
        }
    }

    /** The hard-coded 2 distractors: three choices, the answer once, all distinct. */
    @Test
    fun `offers the target letter plus exactly two distractors`() {
        for ((i, cfg) in FIRST_LETTER_LEVELS.withIndex()) {
            val catalog =
                (cfg.letters ?: orderedUnique(firstLetterPool(i + 1).map { it.letter })).toSet()
            for (seed in 0 until 20) {
                for (round in buildFirstLetterSession(i + 1, SeededGenerator(seed.toLong()))) {
                    val tag = "level ${i + 1} seed $seed"
                    assertEquals(3, round.choices.size, tag)
                    assertEquals(round.choices.size, round.choices.toSet().size, tag)
                    assertEquals(1, round.choices.count { it == round.target.letter }, tag)
                    assertTrue(round.choices.all { it in catalog }, tag)
                }
            }
        }
    }

    @Test
    fun `is replayable under a fixed seed`() {
        for (level in 1..FIRST_LETTER_LEVELS.size) {
            val a = buildFirstLetterSession(level, SeededGenerator(1234))
            val b = buildFirstLetterSession(level, SeededGenerator(1234))
            assertEquals(a, b, "level $level")
        }
    }

    @Test
    fun `different seeds produce different sessions`() {
        val a = buildFirstLetterSession(5, SeededGenerator(1))
        val b = buildFirstLetterSession(5, SeededGenerator(2))
        assertNotEquals(a, b)
    }
}
