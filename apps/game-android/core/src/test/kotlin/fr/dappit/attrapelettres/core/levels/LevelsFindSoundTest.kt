package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.domain.BasicSound
import fr.dappit.attrapelettres.core.domain.FindSoundLevel
import fr.dappit.attrapelettres.core.domain.FindSoundRound
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port of the `findSoundPool / findSoundLevel`, `buildFindSoundRound` and
 * `buildFindSoundSession` blocks of `apps/game-web/src/levels.test.ts` (via the
 * Swift `LevelsFindSoundTests`), plus the find-sound prompt lines.
 *
 * The accessor half of the clamp block (`findSoundLevel`) lives in
 * [LaddersTest] with its eight siblings; the POOL half is here, because this
 * suite owns the pool.
 */
class LevelsFindSoundTest {

    @Test
    fun `clamps an out-of-range level to the same pool as the ladder ends`() {
        // `assertSame`, like the TS `toBe`: the clamp must land on the same
        // authored list, not a value-equal copy.
        assertSame(findSoundPool(1), findSoundPool(0))
        assertSame(findSoundPool(FIND_SOUND_LEVEL_COUNT), findSoundPool(999))
    }

    @Test
    fun `gives every level a pool big enough for its pick and its distractors`() {
        FIND_SOUND_LEVELS.forEachIndexed { i, cfg ->
            val pool = findSoundPool(i + 1)
            assertTrue(pool.size >= cfg.pick, "level ${i + 1}: pool of ${pool.size}")
            assertTrue(pool.size >= cfg.distractors + 1, "level ${i + 1}: pool of ${pool.size}")
        }
    }

    /**
     * No homophone tiles: within a level both the sounds and the graphies are
     * distinct, so a correct ear can never be told "wrong" by a duplicate.
     */
    @Test
    fun `keeps sounds and graphies distinct within a level`() {
        for (i in FIND_SOUND_LEVELS.indices) {
            val pool = findSoundPool(i + 1)
            assertEquals(pool.size, pool.map { it.sound }.toSet().size, "level ${i + 1} sounds")
            assertEquals(pool.size, pool.map { it.graphy }.toSet().size, "level ${i + 1} graphies")
        }
    }

    /**
     * The authored traps are the whole "adaptive by confusability" design: they
     * must point at a real entry of the SAME pool, and never at a homophone —
     * a trap that spelled the target's own sound would punish a correct answer.
     */
    @Test
    fun `resolves every authored trap to a different-sound entry of the same pool`() {
        for (i in FIND_SOUND_LEVELS.indices) {
            val pool = findSoundPool(i + 1)
            val byGraphy = pool.associateBy { it.graphy }
            for (e in pool) {
                for (t in e.traps.orEmpty()) {
                    val hit = byGraphy[t]
                    assertNotNull(hit, "level ${i + 1}: trap $t of ${e.graphy} resolves to nothing")
                    assertNotEquals(e.sound, hit.sound, "level ${i + 1}: trap $t")
                }
            }
        }
    }

    /* buildFindSoundRound ---------------------------------------------------- */

    private data class Run(
        val cfg: FindSoundLevel,
        val target: BasicSound,
        val round: FindSoundRound,
    )

    /** Every level × every target × 12 seeds — the invariants must hold on all pool shapes. */
    private val runs: List<Run> = FIND_SOUND_LEVELS.flatMapIndexed { i, cfg ->
        val pool = findSoundPool(i + 1)
        pool.flatMapIndexed { ti, target ->
            (0 until 12).map { n ->
                Run(
                    cfg = cfg,
                    target = target,
                    round = buildFindSoundRound(
                        target,
                        pool,
                        cfg.distractors,
                        SeededGenerator((ti * 101 + n).toLong()),
                    ),
                )
            }
        }
    }

    @Test
    fun `offers the target plus exactly distractors choices, all distinct graphies`() {
        for (r in runs) {
            assertEquals(r.cfg.distractors + 1, r.round.choices.size)
            assertEquals(1, r.round.choices.count { it.graphy == r.target.graphy })
            val graphies = r.round.choices.map { it.graphy }
            assertEquals(graphies.size, graphies.toSet().size, "$graphies has a duplicate")
        }
    }

    @Test
    fun `never offers a distractor that sounds like the answer`() {
        for (r in runs) {
            for (c in r.round.choices) {
                if (c.graphy == r.target.graphy) continue
                assertNotEquals(r.target.sound, c.sound, "homophone ${c.graphy} beside ${r.target.graphy}")
            }
        }
    }

    @Test
    fun `prefers the authored traps as distractors when they can fill the round`() {
        for (r in runs) {
            if (r.target.traps.orEmpty().size < r.cfg.distractors) continue
            for (c in r.round.choices) {
                if (c.graphy == r.target.graphy) continue
                assertTrue(
                    c.graphy in r.target.traps.orEmpty(),
                    "${r.target.graphy}: ${c.graphy} is not an authored trap",
                )
            }
        }
    }

    /* buildFindSoundSession -------------------------------------------------- */

    @Test
    fun `seeds pick plus repeats rounds, spaced so no sound repeats back-to-back`() {
        FIND_SOUND_LEVELS.forEachIndexed { i, cfg ->
            for (seed in 0 until 40) {
                val run = buildFindSoundSession(i + 1, SeededGenerator(seed.toLong()))
                assertEquals(cfg.pick + cfg.repeats, run.size, "level ${i + 1} seed $seed")
                for (k in 1 until run.size) {
                    assertNotEquals(run[k - 1].target, run[k].target, "level ${i + 1} seed $seed")
                }
            }
        }
    }

    /* Prompt lines ----------------------------------------------------------- */

    @Test
    fun `anchors the prompt to its word and celebrates with the word`() {
        val s = BasicSound(sound = "ou", graphy = "OU", word = "hibou", emoji = "🦉")
        assertEquals("ou, comme dans hibou.", findSoundPrompt(s))
        assertEquals("Oui ! hibou.", findSoundSuccess(s))
    }

    @Test
    fun `is replayable under a fixed seed`() {
        for (level in 1..FIND_SOUND_LEVEL_COUNT) {
            assertEquals(
                buildFindSoundSession(level, SeededGenerator(1234)),
                buildFindSoundSession(level, SeededGenerator(1234)),
                "level $level",
            )
        }
    }
}
