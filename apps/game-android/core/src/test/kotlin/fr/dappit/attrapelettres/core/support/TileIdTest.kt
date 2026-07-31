package fr.dappit.attrapelettres.core.support

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tile-id counters from `src/levels.ts`. Monotonicity is not cosmetic: it
 * is what stops Compose structurally identifying round *n*'s tile 0 with round
 * *n+1*'s tile 0 and reusing the node, which would carry press/shake state
 * across rounds (invariants 1 and 2).
 */
class TileIdTest {

    @Test
    fun `ids are monotonic and start at the given value`() {
        val ids = TileIdAllocator()
        assertEquals(listOf(0, 1, 2, 3, 4), (0 until 5).map { ids.next() })

        val seeded = TileIdAllocator(next = 100)
        assertEquals(listOf(100, 101, 102), (0 until 3).map { seeded.next() })
    }

    @Test
    fun `ids never repeat within an allocator`() {
        val ids = TileIdAllocator()
        val drawn = (0 until 1000).map { ids.next() }
        assertEquals(1000, drawn.toSet().size)
    }

    @Test
    fun `allocators are independent`() {
        val a = TileIdAllocator()
        val b = TileIdAllocator()
        a.next()
        a.next()
        assertEquals(0, b.next())
        assertEquals(2, a.next())
    }

    @Test
    fun `the shared allocator is one counter`() {
        // The shared allocator is a reference: two handles advance the same
        // counter. (Asserted relatively — the suite order must not decide the
        // absolute value.)
        val first = TileIdAllocator.shared.next()
        val second = TileIdAllocator.shared.next()
        assertEquals(first + 1, second)
    }
}
