package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.domain.SoundTarget
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port of the `soundLevel / soundPool`, `buildSoundSession`, `buildSoundRound`
 * and sound-prompt-line blocks of `apps/game-web/src/levels.test.ts` (via the
 * Swift `LevelsSpellSoundTests`).
 *
 * The accessor half of the clamp block (`soundLevel`) lives in [LaddersTest]
 * with its eight siblings; the POOL half is here, because this suite owns the
 * pool.
 */
class LevelsSpellSoundTest {

    @Test
    fun `clamps an out-of-range level to the same pool as the ladder ends`() {
        // `assertSame`, like the TS `toBe`: the clamp must land on the same
        // authored list, not a value-equal copy.
        assertSame(soundPool(1), soundPool(0))
        assertSame(soundPool(SOUND_LEVEL_COUNT), soundPool(999))
    }

    @Test
    fun `gives every level enough targets to pick a full set`() {
        for (i in SOUND_LEVELS.indices) {
            assertTrue(soundPool(i + 1).size >= SOUND_PICK, "level ${i + 1}")
        }
    }

    /* buildSoundSession ------------------------------------------------------ */

    /**
     * The TS keys entries by value because `repeatSession` repeats REFERENCES;
     * the Kotlin port repeats pool indices, which is the same identity. The key
     * mirrors the TS one so "distinct entry" means the same thing in both suites
     * — level 4 holds the same `sound` under several spellings/words, so a key
     * of `sound` alone would under-count.
     */
    private fun key(t: SoundTarget): String =
        "${t.sound}|${t.word ?: ""}|${t.spelling.joinToString("")}"

    /**
     * Every level, many runs — the invariants must hold on all pool shapes (L4
     * has only 6 distinct sounds across 24 entries, so we count entries, not
     * sounds).
     */
    private val runs: List<List<SoundTarget>> = SOUND_LEVELS.indices.flatMap { i ->
        (0 until 60).map { n -> buildSoundSession(i + 1, SeededGenerator(n.toLong())) }
    }

    @Test
    fun `is SOUND_PICK distinct entries, SOUND_REPEATS of them replayed once`() {
        for (run in runs) {
            assertEquals(SOUND_SESSION_LENGTH, run.size)
            val counts = mutableMapOf<String, Int>()
            for (t in run) counts[key(t)] = (counts[key(t)] ?: 0) + 1
            assertEquals(SOUND_PICK, counts.size)
            val values = counts.values.toList()
            assertEquals(SOUND_REPEATS, values.count { it == 2 })
            assertEquals(SOUND_PICK - SOUND_REPEATS, values.count { it == 1 })
            assertTrue(values.all { it <= 2 })
        }
    }

    @Test
    fun `never replays the same entry in two consecutive rounds`() {
        for (run in runs) {
            for (i in 1 until run.size) {
                assertNotEquals(key(run[i - 1]), key(run[i]))
            }
        }
    }

    @Test
    fun `draws every round from the requested level's pool`() {
        val pool5 = soundPool(5).map { key(it) }.toSet()
        for (t in buildSoundSession(5, SeededGenerator(9))) {
            assertTrue(key(t) in pool5, key(t))
        }
    }

    /* buildSoundRound -------------------------------------------------------- */

    private val target = SoundTarget(
        sound = "fo",
        spelling = listOf("P", "H", "O"),
        word = "photo",
        emoji = "📷",
    )

    @Test
    fun `opens one empty slot per letter and a tray of exactly the needed letters`() {
        val round = buildSoundRound(target, 0, SeededGenerator(1))
        assertEquals(listOf<String?>(null, null, null), round.slots)
        assertEquals(target.spelling.sorted(), round.tray.map { it.letter }.sorted())
    }

    @Test
    fun `adds the requested intruders, none of which are in the target`() {
        for (seed in 0 until 20) {
            val round = buildSoundRound(target, 3, SeededGenerator(seed.toLong()))
            assertEquals(target.spelling.size + 3, round.tray.size)
            val need = target.spelling.toSet()
            val intruders = round.tray.map { it.letter }.filter { it !in need }
            assertEquals(3, intruders.size, "seed $seed")
            for (l in intruders) assertTrue(l in SOUND_LETTER_BANK, "seed $seed: $l")
        }
    }

    @Test
    fun `never asks for more distractors than the bank can supply`() {
        // Bank minus a 1-letter target is the worst case; stay within it.
        val round = buildSoundRound(
            SoundTarget(sound = "o", spelling = listOf("O")),
            SOUND_LETTER_BANK.size,
            SeededGenerator(2),
        )
        val letters = round.tray.map { it.letter }
        // no duplicate intruders
        assertEquals(letters.size, letters.toSet().size)
        assertTrue("O" in letters)
    }

    @Test
    fun `gives every tile a unique id`() {
        val round = buildSoundRound(target, 3, SeededGenerator(4))
        val ids = round.tray.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /* Prompt lines ----------------------------------------------------------- */

    @Test
    fun `speaks the bare sound with no context word, and comme dans with one`() {
        assertEquals("la", soundPrompt(SoundTarget(sound = "la", spelling = listOf("L", "A"))))
        assertEquals(
            "fo, comme dans photo.",
            soundPrompt(SoundTarget(sound = "fo", spelling = listOf("P", "H", "O"), word = "photo")),
        )
    }

    @Test
    fun `celebrates with the word when present, the sound otherwise`() {
        assertEquals("Oui ! la.", soundSuccess(SoundTarget(sound = "la", spelling = listOf("L", "A"))))
        assertEquals(
            "Oui ! photo.",
            soundSuccess(SoundTarget(sound = "fo", spelling = listOf("P", "H", "O"), word = "photo")),
        )
    }

    @Test
    fun `is replayable under a fixed seed`() {
        for (level in 1..SOUND_LEVEL_COUNT) {
            assertEquals(
                buildSoundSession(level, SeededGenerator(1234)),
                buildSoundSession(level, SeededGenerator(1234)),
                "level $level",
            )
        }
    }
}
