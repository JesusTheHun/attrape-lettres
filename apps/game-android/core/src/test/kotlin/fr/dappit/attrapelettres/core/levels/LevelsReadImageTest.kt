package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.domain.ReadImageLevel
import fr.dappit.attrapelettres.core.domain.ReadImageRound
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `readImageLevel` / `buildReadImageRound` / `buildReadImageSession`
 * blocks of `src/levels.test.ts` (via the iOS `LevelsReadImageTests.swift`).
 *
 * The ladder-accessor clamp assertion of the `readImageLevel` block lives in
 * `LaddersTest`, which gathers that half for every ladder; this suite owns the
 * pool and the builders.
 */
class LevelsReadImageTest {

    /* readImagePool ---------------------------------------------------------- */

    @Test
    fun `has a pool big enough to pick a full run plus distractors at every level`() {
        for (cfg in READ_IMAGE_LEVELS) {
            assertTrue(readImagePool().size >= cfg.pick)
            // one target + its distractors must all fit, by DISTINCT emoji.
            val distinctEmoji = readImagePool().map { it.emoji }.toSet().size
            assertTrue(distinctEmoji >= cfg.distractors + 1)
        }
    }

    /* buildReadImageRound ---------------------------------------------------- */

    @Test
    fun `offers the target plus exactly distractors choices`() {
        for (r in RUNS) {
            assertEquals(r.cfg.distractors + 1, r.round.choices.size)
            assertEquals(1, r.round.choices.count { it.word == r.round.target.word })
        }
    }

    @Test
    fun `never shows two tiles with the same picture (distinct emoji)`() {
        for (r in RUNS) {
            val emojis = r.round.choices.map { it.emoji }
            assertEquals(emojis.size, emojis.toSet().size)
        }
    }

    /* buildReadImageSession -------------------------------------------------- */

    @Test
    fun `seeds pick plus repeats rounds, spaced so no word repeats back-to-back`() {
        for ((i, cfg) in READ_IMAGE_LEVELS.withIndex()) {
            for (seed in 0 until 40) {
                val run = buildReadImageSession(i + 1, SeededGenerator(seed.toLong()))
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

    @Test
    fun `is replayable under a fixed seed`() {
        for (level in 1..READ_IMAGE_LEVEL_COUNT) {
            assertEquals(
                buildReadImageSession(level, SeededGenerator(1234)),
                buildReadImageSession(level, SeededGenerator(1234)),
                "level $level",
            )
        }
    }
}

private data class Run(val cfg: ReadImageLevel, val round: ReadImageRound)

/** Every level's distractor count, many seeded runs on every target in the pool. */
private val RUNS: List<Run> = READ_IMAGE_LEVELS.flatMap { cfg ->
    readImagePool().withIndex().flatMap { (wi, target) ->
        (0 until 8).map { n ->
            Run(
                cfg = cfg,
                round = buildReadImageRound(
                    target,
                    cfg.distractors,
                    SeededGenerator((wi * 31 + n).toLong()),
                ),
            )
        }
    }
}
