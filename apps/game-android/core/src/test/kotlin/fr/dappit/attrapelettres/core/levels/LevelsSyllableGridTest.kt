package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.GRID_CONSONANTS
import fr.dappit.attrapelettres.core.content.GRID_VOWELS
import fr.dappit.attrapelettres.core.content.SYLLABLE_GRID_ROWS
import fr.dappit.attrapelettres.core.domain.GridRound
import fr.dappit.attrapelettres.core.domain.SyllableGridLevel
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `syllableGridPool`, `buildGridRound`, `buildSyllableGridSession`
 * and "syllable grid prompt lines" blocks of `src/levels.test.ts`.
 *
 * The clamp assertions ("clamps out-of-range levels to the ladder ends") live
 * in [LaddersTest] with every other ladder's; this suite owns the pool, the
 * rounds, the spoken lines and — per the builder contract — the totality of
 * `GRID_PROMPT`, the fourth hint dictionary, which `HubCatalogTest`
 * deliberately leaves to the file that declares it.
 */
class LevelsSyllableGridTest {

    /* syllableGridPool ------------------------------------------------------ */

    @Test
    fun `expands the level's rows to every vowel — the grid is exhaustive`() {
        for ((i, rows) in SYLLABLE_GRID_ROWS.withIndex()) {
            val pool = syllableGridPool(i + 1)
            val consonants = rows ?: GRID_CONSONANTS
            assertEquals(consonants.size * GRID_VOWELS.size, pool.size, "level ${i + 1}")
            for (c in consonants) {
                for (v in GRID_VOWELS) {
                    assertTrue(pool.any { it.consonant == c && it.vowel == v }, "$c$v")
                }
            }
        }
    }

    @Test
    fun `keeps every cell unique and readable (text = consonne + voyelle)`() {
        val pool = syllableGridPool(SYLLABLE_GRID_LEVEL_COUNT)
        assertEquals(pool.size, pool.map { it.text }.toSet().size)
        for (s in pool) {
            assertEquals(s.consonant + s.vowel, s.text)
            assertEquals(s.text.lowercase(), s.sound)
        }
    }

    /**
     * The content guard: a consonant whose sound flips with the vowel (CE/CI,
     * GE/GI…) belongs to « Trouve le son » / « Les syllabes jumelles », never to
     * the grid — here NOTHING may change inside a level except the vowel.
     */
    @Test
    fun `teaches no consonant whose sound flips with the vowel (C, G, K, QU)`() {
        assertFalse("C" in GRID_CONSONANTS)
        assertFalse("G" in GRID_CONSONANTS)
        assertFalse("K" in GRID_CONSONANTS)
        assertFalse("QU" in GRID_CONSONANTS)
    }

    @Test
    fun `has a pool big enough for a full run at every level`() {
        for ((i, cfg) in SYLLABLE_GRID_LEVELS.withIndex()) {
            assertTrue(syllableGridPool(i + 1).size >= cfg.pick, "level ${i + 1}")
        }
    }

    /* buildGridRound -------------------------------------------------------- */

    @Test
    fun `offers exactly choices tiles, the answer among them, never twice`() {
        for (r in runs) {
            assertEquals(r.cfg.choices, r.round.choices.size)
            assertEquals(1, r.round.choices.count { it.text == r.round.target.text })
            assertEquals(r.round.choices.size, r.round.choices.map { it.text }.toSet().size)
        }
    }

    @Test
    fun `in vowel mode every tile shares the consonant — only the vowel is the task`() {
        for (r in runs) {
            if (r.mode != SyllableGridMode.VOWEL) continue
            for (c in r.round.choices) {
                assertEquals(r.round.target.consonant, c.consonant)
            }
            assertEquals(r.round.choices.size, r.round.choices.map { it.vowel }.toSet().size)
        }
    }

    @Test
    fun `in hear mode swaps at most column tiles onto another consonant`() {
        for (r in runs) {
            if (r.mode != SyllableGridMode.HEAR) continue
            val swapped = r.round.choices.filter { it.consonant != r.round.target.consonant }
            assertTrue(swapped.size <= r.cfg.column)
            // A swapped tile is the SAME vowel on another row (VA vs LA), never
            // a second axis of change at once.
            for (c in swapped) {
                assertEquals(r.round.target.vowel, c.vowel)
            }
        }
    }

    @Test
    fun `never puts a tile that sounds like the answer beside it`() {
        for (r in runs) {
            val twins = r.round.choices.filter {
                it.sound == r.round.target.sound && it.text != r.round.target.text
            }
            assertTrue(twins.isEmpty())
        }
    }

    /* buildSyllableGridSession ---------------------------------------------- */

    @Test
    fun `seeds pick + repeats rounds, spaced so no syllable repeats back-to-back`() {
        for ((i, cfg) in SYLLABLE_GRID_LEVELS.withIndex()) {
            for (mode in SyllableGridMode.entries) {
                for (seed in 0 until 20) {
                    val run = buildSyllableGridSession(
                        i + 1,
                        mode,
                        SeededGenerator(seed.toLong()),
                    )
                    assertEquals(
                        cfg.pick + cfg.repeats,
                        run.size,
                        "level ${i + 1} $mode seed $seed",
                    )
                    for (k in 1 until run.size) {
                        assertNotEquals(
                            run[k - 1].target.text,
                            run[k].target.text,
                            "level ${i + 1} $mode seed $seed",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `is replayable under a fixed seed`() {
        // GridRound is all value types (no tile ids), so whole-session equality
        // is exactly "the same rounds in the same order".
        for (level in 1..SYLLABLE_GRID_LEVEL_COUNT) {
            for (mode in SyllableGridMode.entries) {
                assertEquals(
                    buildSyllableGridSession(level, mode, SeededGenerator(1234L)),
                    buildSyllableGridSession(level, mode, SeededGenerator(1234L)),
                )
            }
        }
    }

    /* Prompt lines ---------------------------------------------------------- */

    @Test
    fun `speaks the bare syllable, and celebrates with it again`() {
        val va = gridSyllable("V", "A")
        assertEquals("va", gridPrompt(va))
        assertEquals("Oui ! va.", gridSuccess(va))
        assertEquals("Oui ! ché.", gridSuccess(gridSyllable("CH", "É")))
    }

    @Test
    fun `the on-screen consigne covers both drills`() {
        assertEquals(SyllableGridMode.entries.size, GRID_PROMPT.size)
        assertEquals(
            "Écoute la syllabe et trouve son écriture",
            GRID_PROMPT[SyllableGridMode.HEAR],
        )
        assertEquals(
            "Écoute la syllabe et trouve la voyelle qui manque",
            GRID_PROMPT[SyllableGridMode.VOWEL],
        )
    }

    /* Fixture --------------------------------------------------------------- */

    private data class Run(
        val cfg: SyllableGridLevel,
        val mode: SyllableGridMode,
        val round: GridRound,
    )

    private companion object {
        /**
         * Every level × both modes × every target in the level's pool × 4 seeds,
         * mirroring the web fixture (which builds 4 unseeded rounds per target)
         * and the iOS one (which derives the seed the same way).
         */
        val runs: List<Run> = SYLLABLE_GRID_LEVELS.withIndex().flatMap { (i, cfg) ->
            val pool = syllableGridPool(i + 1)
            SyllableGridMode.entries.flatMap { mode ->
                pool.withIndex().flatMap { (ti, target) ->
                    (0 until 4).map { n ->
                        Run(
                            cfg = cfg,
                            mode = mode,
                            round = buildGridRound(
                                target,
                                pool,
                                cfg,
                                mode,
                                SeededGenerator((ti * 17 + n).toLong()),
                            ),
                        )
                    }
                }
            }
        }
    }
}
