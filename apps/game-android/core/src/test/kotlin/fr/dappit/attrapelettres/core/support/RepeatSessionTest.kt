package fr.dappit.attrapelettres.core.support

import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.content.SYLLABLE_BANK
import fr.dappit.attrapelettres.core.content.SYLLABLE_WORDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `repeatSession` block of `src/levels.test.ts` (via the iOS
 * `RepeatSessionTests.swift`), plus the two port-specific properties the
 * TypeScript could not have: index identity, and the give-up after 64 rerolls.
 *
 * Every case runs under a FRESH seeded source keyed by the loop index, so a
 * failure is reproducible from the printed seed rather than being a flake.
 */
class RepeatSessionTest {

    // --- Shape ----------------------------------------------------------------

    @Test
    fun `length is pick plus repeats`() {
        for (seed in 0 until 40) {
            val out = repeatSessionIndices(count = 12, pick = 6, repeats = 3, rng = SeededGenerator(seed.toLong()))
            assertEquals(9, out.size, "seed $seed")
        }
    }

    @Test
    fun `picks are distinct and repeats appear exactly twice`() {
        for (seed in 0 until 60) {
            val out = repeatSessionIndices(count = 12, pick = 6, repeats = 3, rng = SeededGenerator(seed.toLong()))
            val counts = out.groupingBy { it }.eachCount()
            assertEquals(6, counts.size, "seed $seed: distinct picks")
            assertEquals(3, counts.values.count { it == 2 }, "seed $seed: doubled items")
            assertTrue(counts.values.all { it <= 2 }, "seed $seed: nothing thrice")
        }
    }

    @Test
    fun `every item comes from the pool`() {
        val pool = SYLLABLE_BANK
        for (seed in 0 until 40) {
            val out = repeatSession(pool, pick = 8, repeats = 4, rng = SeededGenerator(seed.toLong()))
            assertEquals(12, out.size, "seed $seed")
            assertTrue(out.all { it in pool }, "seed $seed")
        }
    }

    // --- Clamping (both counts clamp to the pool) -------------------------------

    @Test
    fun `both counts clamp to the pool`() {
        // (["a","b","c"], 8, 4) -> 6, collision-free. Straight from levels.test.ts.
        val pool = listOf("a", "b", "c")
        for (seed in 0 until 60) {
            val out = repeatSession(pool, pick = 8, repeats = 4, rng = SeededGenerator(seed.toLong()))
            assertEquals(6, out.size, "seed $seed")
            assertFalse(hasAdjacentEqual(out), "seed $seed: $out")
        }
    }

    @Test
    fun `repeats zero is a plain shuffle of the picks`() {
        val pool = listOf("a", "b", "c")
        for (seed in 0 until 40) {
            val out = repeatSession(pool, pick = 3, repeats = 0, rng = SeededGenerator(seed.toLong()))
            assertEquals(3, out.size, "seed $seed")
            assertEquals(pool, out.sorted(), "seed $seed")
        }
    }

    @Test
    fun `a pool shorter than pick just yields a shorter run`() {
        for (seed in 0 until 20) {
            val out = repeatSessionIndices(count = 4, pick = 10, repeats = 0, rng = SeededGenerator(seed.toLong()))
            assertEquals(listOf(0, 1, 2, 3), out.sorted(), "seed $seed")
        }
    }

    // --- No two identical rounds in a row ---------------------------------------

    @Test
    fun `never two identical rounds adjacent`() {
        // A sweep over the shapes the shipped ladders actually use.
        val configs = listOf(
            Triple(21, 5, 2), Triple(42, 6, 2), Triple(42, 8, 4), Triple(12, 6, 3), Triple(18, 4, 2),
            Triple(6, 4, 2), Triple(5, 5, 5), Triple(3, 3, 3), Triple(2, 2, 2), Triple(2, 2, 1),
        )
        for ((count, pick, repeats) in configs) {
            for (seed in 0 until 80) {
                val out = repeatSessionIndices(count = count, pick = pick, repeats = repeats, rng = SeededGenerator(seed.toLong()))
                assertFalse(
                    hasAdjacentEqual(out),
                    "cfg ($count, $pick, $repeats) seed $seed: $out",
                )
            }
        }
    }

    @Test
    fun `runs over the real content pools are collision-free`() {
        for (seed in 0 until 40) {
            val letters = repeatSession(LETTER_WORDS, pick = 6, repeats = 2, rng = SeededGenerator(seed.toLong()))
            assertEquals(8, letters.size, "seed $seed")
            val words = repeatSession(SYLLABLE_WORDS, pick = 5, repeats = 2, rng = SeededGenerator(seed.toLong()))
            assertEquals(7, words.size, "seed $seed")
            // Compare by index-free identity: these tables have no duplicate
            // rows, so value adjacency is a valid proxy here.
            assertFalse(hasAdjacentEqual(words.map { it.word }), "seed $seed")
        }
    }

    @Test
    fun `a one-element pool yields the unavoidable pair`() {
        // Back-to-back and unavoidable. The TS accepts it (the 64 rerolls give
        // up) and so do we; there is no failure here (invariant 3).
        for (seed in 0 until 20) {
            val out = repeatSession(listOf("solo"), pick = 3, repeats = 3, rng = SeededGenerator(seed.toLong()))
            assertEquals(listOf("solo", "solo"), out, "seed $seed")
        }
    }

    // --- Index identity ≡ TS reference identity (the port-specific property) ----

    @Test
    fun `adjacency is by index, not by value`() {
        // The TS adjacency test is `x === out[i - 1]` — REFERENCE identity. Two
        // structurally identical pool rows are still two different items there,
        // and must be here too. If this ever fails, someone re-ported the
        // algorithm on `==` and the day a table grows a value-duplicate row
        // (SOUND_TARGETS[3] is one `word` away from having four) the run shape
        // changes silently.
        val pool = listOf("a", "a", "b") // two DISTINCT items that happen to be equal
        var sawEqualNeighbours = false
        for (seed in 0 until 200) {
            val out = repeatSession(pool, pick = 3, repeats = 1, rng = SeededGenerator(seed.toLong()))
            assertEquals(4, out.size, "seed $seed")
            if (hasAdjacentEqual(out)) sawEqualNeighbours = true
        }
        assertTrue(
            sawEqualNeighbours,
            "value-equal neighbours never appeared — the adjacency check is comparing values, not indices",
        )
    }

    @Test
    fun `indices and elements agree`() {
        val pool = listOf("w", "x", "y", "z")
        for (seed in 0 until 30) {
            val idx = repeatSessionIndices(count = pool.size, pick = 3, repeats = 1, rng = SeededGenerator(seed.toLong()))
            val els = repeatSession(pool, pick = 3, repeats = 1, rng = SeededGenerator(seed.toLong()))
            assertEquals(els, idx.map { pool[it] }, "seed $seed")
        }
    }

    // --- Determinism ------------------------------------------------------------

    @Test
    fun `the same seed produces the same run`() {
        for (seed in 0 until 20) {
            val a = repeatSessionIndices(count = 20, pick = 8, repeats = 4, rng = SeededGenerator(seed.toLong()))
            val b = repeatSessionIndices(count = 20, pick = 8, repeats = 4, rng = SeededGenerator(seed.toLong()))
            assertEquals(a, b, "seed $seed")
        }
    }

    @Test
    fun `different seeds produce different runs`() {
        val a = repeatSessionIndices(count = 20, pick = 8, repeats = 4, rng = SeededGenerator(1))
        val b = repeatSessionIndices(count = 20, pick = 8, repeats = 4, rng = SeededGenerator(2))
        assertNotEquals(a, b)
    }
}

private fun <T> hasAdjacentEqual(xs: List<T>): Boolean {
    for (i in 1 until xs.size) {
        if (xs[i] == xs[i - 1]) return true
    }
    return false
}
