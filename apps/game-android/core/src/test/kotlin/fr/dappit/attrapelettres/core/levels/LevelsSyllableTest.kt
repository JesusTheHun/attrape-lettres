package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SYLLABLE_BANK
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import fr.dappit.attrapelettres.core.support.repeatSessionIndices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `syllablePool` / `buildSyllableRound` blocks of
 * `src/levels.test.ts` (via the iOS `LevelsSyllableTests.swift`), plus the
 * syllable half of the shipped-config `repeatSession` block — the exact runs
 * the assemble engine seeds.
 *
 * Deliberately absent, so each assertion has ONE owning suite:
 *   - the `syllableTier` clamp/mapping tests live in `LaddersTest` with the
 *     other eight accessors;
 *   - `repeatSession`'s generic behaviour (clamping, zero repeats, the 64-try
 *     give-up) lives in `support/RepeatSessionTest`;
 *   - the first-letter half of the shipped-config block belongs to the
 *     first-letter suite, which owns `firstLetterPool`.
 *
 * Every seeded case runs under a FRESH `SeededGenerator` keyed by the loop
 * index, so a failure reproduces from the printed seed instead of flaking.
 */
class LevelsSyllableTest {

    private val word = SyllableWord(word = "CHATON", syllables = listOf("CHA", "TON"), emoji = "🐱")

    /* syllablePool ----------------------------------------------------------- */

    @Test
    fun `syllablePool keeps only words within the tier's syllable-count window`() {
        val tier = SYLLABLE_TIERS[0]
        val pool = syllablePool(tier)
        assertTrue(pool.isNotEmpty())
        assertTrue(
            pool.all {
                it.syllables.size >= tier.minSyllables && it.syllables.size <= tier.maxSyllables
            },
        )
    }

    /* buildSyllableRound ----------------------------------------------------- */

    @Test
    fun `fill-blank hides exactly one slot and offers target plus one distractor`() {
        for (seed in 0 until 40) {
            val round = buildSyllableRound(word, SyllableMode.FILL_BLANK, SeededGenerator(seed.toLong()))
            assertEquals(1, round.slots.count { it == null }, "seed $seed")
            // Every non-hidden slot is locked; the hidden one is not.
            round.slots.forEachIndexed { i, s ->
                assertEquals(s != null, round.locked[i], "seed $seed slot $i")
            }
            assertEquals(2, round.tray.size, "seed $seed")
            val missing = word.syllables[round.slots.indexOfFirst { it == null }]
            assertTrue(round.tray.map { it.syllable }.contains(missing), "seed $seed")
            // The distractor is never one of the word's own syllables.
            val other = round.tray.map { it.syllable }.filter { it != missing }
            assertEquals(1, other.size, "seed $seed")
            assertFalse(word.syllables.contains(other[0]), "seed $seed")
        }
    }

    @Test
    fun `order has empty slots and a tray that permutes all syllables`() {
        for (seed in 0 until 20) {
            val round = buildSyllableRound(word, SyllableMode.ORDER, SeededGenerator(seed.toLong()))
            assertEquals(listOf<String?>(null, null), round.slots, "seed $seed")
            assertEquals(listOf(false, false), round.locked, "seed $seed")
            assertEquals(
                word.syllables.sorted(),
                round.tray.map { it.syllable }.sorted(),
                "seed $seed",
            )
        }
    }

    @Test
    fun `order-distractor tray has all syllables plus one extra`() {
        for (seed in 0 until 20) {
            val round =
                buildSyllableRound(word, SyllableMode.ORDER_DISTRACTOR, SeededGenerator(seed.toLong()))
            assertEquals(word.syllables.size + 1, round.tray.size, "seed $seed")
            for (s in word.syllables) {
                assertTrue(round.tray.map { it.syllable }.contains(s), "seed $seed: $s")
            }
        }
    }

    @Test
    fun `gives every tile a unique id`() {
        val round = buildSyllableRound(word, SyllableMode.ORDER_DISTRACTOR, SeededGenerator(7))
        val ids = round.tray.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `tile ids come from the injected allocator`() {
        val ids = TileIdAllocator(next = 100)
        val round = buildSyllableRound(word, SyllableMode.ORDER, SeededGenerator(3), ids = ids)
        assertEquals(listOf(100, 101), round.tray.map { it.id })
    }

    /* pickDistractorSyllable ------------------------------------------------- */

    @Test
    fun `distractor is never an excluded syllable and falls back to the bank's first entry`() {
        for (seed in 0 until 50) {
            val picked = pickDistractorSyllable(word.syllables.toSet(), SeededGenerator(seed.toLong()))
            assertFalse(word.syllables.contains(picked), "seed $seed")
            assertTrue(picked in SYLLABLE_BANK, "seed $seed")
        }
        // Excluding the whole bank leaves nothing, and the TS falls through to
        // `SYLLABLE_BANK[0]` — which is only stable because the bank is ordered.
        assertEquals(
            SYLLABLE_BANK[0],
            pickDistractorSyllable(SYLLABLE_BANK.toSet(), SeededGenerator(1)),
        )
    }

    /* repeatSession over the exact shipped tiers ------------------------------ */

    @Test
    fun `every shipped tier pool is big enough for a full pick`() {
        for (tier in SYLLABLE_TIERS) {
            assertTrue(syllablePool(tier).size >= tier.pick, "tier $tier")
        }
    }

    @Test
    fun `a shipped tier run picks distinct words and replays repeats of them never back-to-back`() {
        for (tier in SYLLABLE_TIERS) {
            val pool = syllablePool(tier)
            for (seed in 0 until 40) {
                val run = repeatSessionIndices(
                    count = pool.size,
                    pick = tier.pick,
                    repeats = tier.repeats,
                    rng = SeededGenerator(seed.toLong()),
                )
                assertEquals(tier.pick + tier.repeats, run.size, "tier $tier seed $seed")
                val counts = run.groupingBy { it }.eachCount()
                assertEquals(tier.pick, counts.size, "tier $tier seed $seed")
                assertEquals(
                    tier.repeats,
                    counts.values.count { it == 2 },
                    "tier $tier seed $seed",
                )
                assertTrue(counts.values.all { it <= 2 }, "tier $tier seed $seed")
                for (i in 1 until run.size) {
                    assertNotEquals(run[i - 1], run[i], "tier $tier seed $seed")
                }
            }
        }
    }
}
