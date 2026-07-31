package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.domain.TwinFamily
import fr.dappit.attrapelettres.core.domain.TwinGraphy
import fr.dappit.attrapelettres.core.domain.TwinLevel
import fr.dappit.attrapelettres.core.domain.TwinRound
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port of the `twinPool / twinLevel`, `buildTwinRound`, `buildTwinSession` and
 * "sound-twins prompt lines" blocks of `src/levels.test.ts`.
 *
 * The `twinLevel` clamp assertions live in [LaddersTest] with every other
 * ladder's; this suite owns the pool, the rounds and the spoken lines.
 */
class LevelsTwinsTest {

    /* twinPool -------------------------------------------------------------- */

    @Test
    fun `clamps an out-of-range level onto the ladder-end pools`() {
        // `assertSame`, not `assertEquals` — the TS is `toBe` and the clamp must
        // hand back the SAME authored pool, not a value-equal copy.
        assertSame(twinPool(1), twinPool(0))
        assertSame(twinPool(TWIN_LEVEL_COUNT), twinPool(999))
    }

    @Test
    fun `gives every level a pool big enough for its pick`() {
        for ((i, cfg) in TWIN_LEVELS.withIndex()) {
            assertTrue(twinPool(i + 1).size >= cfg.pick, "level ${i + 1}")
        }
    }

    @Test
    fun `every family has at least two same-sound writings — the point of the game`() {
        for (i in TWIN_LEVELS.indices) {
            for (f in twinPool(i + 1)) {
                assertTrue(f.graphies.size >= 2, "${f.sound} has ${f.graphies.size} graphies")
            }
        }
    }

    /**
     * Distinct sounds are what make an intruder WRONG: a duplicated sound would
     * put a "correct-sounding" tile on the intruder side. Unique texts are what
     * keep a round's tiles readable — no two tiles may read the same.
     */
    @Test
    fun `keeps family sounds distinct and graphy texts unique across a level`() {
        for (i in TWIN_LEVELS.indices) {
            val pool = twinPool(i + 1)
            assertEquals(pool.size, pool.map { it.sound }.toSet().size)
            val texts = pool.flatMap { f -> f.graphies.map { it.text } }
            assertEquals(texts.size, texts.toSet().size)
        }
    }

    @Test
    fun `always has enough other-family graphies to fill the intruder quota`() {
        for ((i, cfg) in TWIN_LEVELS.withIndex()) {
            val pool = twinPool(i + 1)
            for (f in pool) {
                val others = pool.filter { it.sound != f.sound }.sumOf { it.graphies.size }
                assertTrue(others >= cfg.distractors, "${f.sound} at level ${i + 1}")
            }
        }
    }

    /* buildTwinRound -------------------------------------------------------- */

    @Test
    fun `deals every family graphy plus exactly distractors intruders`() {
        for (r in runs) {
            assertEquals(r.family.graphies.size + r.cfg.distractors, r.round.tiles.size)
            for (g in r.family.graphies) {
                val tile = r.round.tiles.filter { it.text == g.text }
                assertEquals(1, tile.size, g.text)
                assertTrue(tile.first().correct)
                assertEquals(r.family.sound, tile.first().sound)
            }
            assertEquals(r.family.graphies.size, r.round.tiles.count { it.correct })
        }
    }

    @Test
    fun `intruders never spell the family's sound and never duplicate a text`() {
        for (r in runs) {
            for (t in r.round.tiles) {
                if (t.correct) continue
                assertNotEquals(r.family.sound, t.sound)
            }
            val texts = r.round.tiles.map { it.text }
            assertEquals(texts.size, texts.toSet().size)
        }
    }

    @Test
    fun `gives every tile a unique id`() {
        for (r in runs) {
            val ids = r.round.tiles.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    /* buildTwinSession ------------------------------------------------------ */

    @Test
    fun `seeds pick + repeats rounds, spaced so no family repeats back-to-back`() {
        // The TS adjacency check is reference identity (`not.toBe`); a level's
        // families carry distinct sounds (pinned above), so data-class
        // inequality is exactly equivalent here.
        for ((i, cfg) in TWIN_LEVELS.withIndex()) {
            for (seed in 0 until 40) {
                val run = buildTwinSession(i + 1, SeededGenerator(seed.toLong()))
                assertEquals(cfg.pick + cfg.repeats, run.size, "level ${i + 1} seed $seed")
                for (k in 1 until run.size) {
                    assertNotEquals(
                        run[k - 1].family,
                        run[k].family,
                        "level ${i + 1} seed $seed",
                    )
                }
            }
        }
    }

    /* Prompt lines ----------------------------------------------------------- */

    @Test
    fun `asks for all twins of a sound and celebrates each graphy's own word`() {
        assertEquals(
            "Trouve tous les ko !",
            twinPrompt(TwinFamily(sound = "ko", graphies = emptyList())),
        )
        assertEquals(
            "Oui ! coq.",
            twinSuccess(TwinGraphy(text = "CO", word = "coq", emoji = "🐓")),
        )
    }

    /* Fixture --------------------------------------------------------------- */

    private data class Run(
        val cfg: TwinLevel,
        val family: TwinFamily,
        val round: TwinRound,
    )

    private companion object {
        /**
         * Every level × every family in the level's pool × 12 seeds, mirroring
         * the web fixture (12 unseeded rounds per family) and the iOS one
         * (which derives the seed the same way).
         */
        val runs: List<Run> = TWIN_LEVELS.withIndex().flatMap { (i, cfg) ->
            val pool = twinPool(i + 1)
            pool.withIndex().flatMap { (fi, family) ->
                (0 until 12).map { n ->
                    Run(
                        cfg = cfg,
                        family = family,
                        round = buildTwinRound(
                            family,
                            pool,
                            cfg.distractors,
                            SeededGenerator((fi * 53 + n).toLong()),
                        ),
                    )
                }
            }
        }
    }
}
