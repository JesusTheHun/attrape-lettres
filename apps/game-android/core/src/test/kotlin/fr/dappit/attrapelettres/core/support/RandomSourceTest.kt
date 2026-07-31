package fr.dappit.attrapelettres.core.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The injected RNG is what makes every round builder testable, so the
 * determinism it promises is itself under test — a seeded source that quietly
 * stopped being seeded would turn the whole Levels suite into noise that
 * passes. Port of the iOS `RandomSourceTests.swift`.
 */
class RandomSourceTest {

    // --- Seeded determinism ---------------------------------------------------

    @Test
    fun `same seed produces the same draw sequence`() {
        val a = SeededGenerator(1234)
        val b = SeededGenerator(1234)
        repeat(500) {
            assertEquals(a.next(97), b.next(97))
        }
    }

    @Test
    fun `same seed produces the same shuffle`() {
        val deck = (0 until 40).map { "item-$it" }
        val a = SeededGenerator(99)
        val b = SeededGenerator(99)
        repeat(20) {
            assertEquals(a.shuffled(deck), b.shuffled(deck))
        }
    }

    @Test
    fun `same seed produces the same bools and picks`() {
        val pool = listOf("A", "B", "C", "D", "E")
        val a = SeededGenerator(7)
        val b = SeededGenerator(7)
        repeat(200) {
            assertEquals(a.bool(), b.bool())
            assertEquals(a.elementOf(pool), b.elementOf(pool))
        }
    }

    @Test
    fun `different seeds diverge`() {
        // Catches an RNG accidentally wired to a constant, which would make
        // every determinism test above pass for the wrong reason.
        val deck = (0 until 40).toList()
        val a = SeededGenerator(1).shuffled(deck)
        val b = SeededGenerator(2).shuffled(deck)
        assertNotEquals(a, b)
    }

    @Test
    fun `a seeded source is not frozen`() {
        val rng = SeededGenerator(5)
        val draws = (0 until 200).map { rng.next(1000) }
        assertTrue(draws.toSet().size > 1)
    }

    // --- next(upperBound) -----------------------------------------------------

    @Test
    fun `next stays in range`() {
        val rng = SeededGenerator(42)
        for (n in 1..20) {
            repeat(200) {
                val v = rng.next(n)
                assertTrue(v in 0 until n)
            }
        }
    }

    @Test
    fun `next below zero or less is zero`() {
        // The TS analogue `(Math.random() * n) | 0` yields 0 for n = 0
        // (NaN | 0), and the call sites rely on never trapping.
        val rng = SeededGenerator(3)
        assertEquals(0, rng.next(0))
        assertEquals(0, rng.next(-5))
    }

    @Test
    fun `next covers every value`() {
        val rng = SeededGenerator(11)
        val seen = mutableSetOf<Int>()
        repeat(600) { seen.add(rng.next(6)) }
        assertEquals((0 until 6).toSet(), seen)
    }

    @Test
    fun `bool is not stuck`() {
        val rng = SeededGenerator(17)
        val flips = (0 until 400).map { rng.bool() }
        assertTrue(flips.contains(true))
        assertTrue(flips.contains(false))
    }

    // --- shuffled -------------------------------------------------------------

    @Test
    fun `shuffled is a permutation`() {
        val deck = (0 until 50).toList()
        val rng = SeededGenerator(8)
        repeat(50) {
            val out = rng.shuffled(deck)
            assertEquals(deck.size, out.size)
            assertEquals(deck, out.sorted())
        }
    }

    @Test
    fun `shuffled handles degenerate inputs`() {
        val rng = SeededGenerator(1)
        assertEquals(emptyList(), rng.shuffled(emptyList<Int>()))
        assertEquals(listOf(9), rng.shuffled(listOf(9)))
    }

    @Test
    fun `shuffled actually moves`() {
        val deck = (0 until 20).toList()
        val rng = SeededGenerator(4)
        val outs = (0 until 10).map { rng.shuffled(deck) }
        assertTrue(outs.any { it != deck })
    }

    // --- elementOf ------------------------------------------------------------

    @Test
    fun `elementOf empty is null`() {
        assertNull(SeededGenerator(1).elementOf(emptyList<String>()))
    }

    @Test
    fun `element is always a member`() {
        val pool = listOf("CHA", "TON", "LA", "PIN")
        val rng = SeededGenerator(6)
        repeat(200) {
            val picked = assertNotNull(rng.elementOf(pool))
            assertTrue(picked in pool)
        }
    }

    // --- orderedUnique --------------------------------------------------------

    @Test
    fun `orderedUnique preserves first-seen order`() {
        assertEquals(
            listOf("CHA", "TON", "LA", "PIN"),
            orderedUnique(listOf("CHA", "TON", "LA", "CHA", "PIN", "TON")),
        )
    }

    @Test
    fun `orderedUnique keeps the first occurrence, not the last`() {
        assertEquals(listOf(3, 1, 2), orderedUnique(listOf(3, 1, 3, 2, 1)))
    }

    @Test
    fun `orderedUnique on empty and all-distinct input`() {
        assertEquals(emptyList(), orderedUnique(emptyList<Int>()))
        assertEquals(listOf(1, 2, 3), orderedUnique(listOf(1, 2, 3)))
    }

    @Test
    fun `orderedUnique is stable across repetitions`() {
        // The property the order-sensitive sites lean on (syllable bank order,
        // spell intruders, the first-letter catalog): stable within a process
        // AND equal to the authored order. Pinned by this test rather than by
        // stdlib documentation, so a future "optimisation" through a plain
        // HashSet fails loudly here instead of surfacing as a flaky seeded run.
        val xs = (0 until 200).map { "s${it % 37}" }
        val first = orderedUnique(xs)
        repeat(50) {
            assertEquals(first, orderedUnique(xs))
        }
        assertEquals((0 until 37).map { "s$it" }, first)
    }
}
