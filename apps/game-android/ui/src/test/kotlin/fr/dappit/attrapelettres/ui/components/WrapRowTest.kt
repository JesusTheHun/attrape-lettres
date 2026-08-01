package fr.dappit.attrapelettres.ui.components

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// `flex flex-wrap` — the line breaking, on measured sizes alone.
//
// Ported from the `ComponentsWrapRow` suite in
// apps/game-ios/Tests/ALUITests/Components/SupportingComponentTests.swift.
// Tailwind spacing is n x 4 px: gap-1 = 4, gap-3 = 12.

class WrapRowLinesTest {

    private fun box(w: Int, h: Int = 20) = IntSize(w, h)

    @Test
    fun `a row that fits stays on one line`() {
        val rows = WrapRowLines.lines(listOf(box(120), box(180)), maxWidth = 400, spacing = 12)
        assertEquals(1, rows.size)
        assertEquals(312, rows[0].width) // 120 + 12 + 180
        assertEquals(listOf(0, 1), rows[0].items)
    }

    @Test
    fun `a row that overflows drops to a second line rather than shrinking`() {
        // A Row would squash « 🎉 Suivant »; `flex-wrap` moves it down.
        val rows = WrapRowLines.lines(listOf(box(120), box(180)), maxWidth = 300, spacing = 12)
        assertEquals(2, rows.size)
        assertEquals(listOf(0), rows[0].items)
        assertEquals(listOf(1), rows[1].items)
        assertEquals(180, rows[1].width)
    }

    @Test
    fun `a line takes its tallest child's height`() {
        val rows = WrapRowLines.lines(
            listOf(box(50, 20), box(50, 44), box(50, 30)),
            maxWidth = 400,
            spacing = 4,
        )
        assertEquals(1, rows.size)
        assertEquals(44, rows[0].height)
    }

    @Test
    fun `a run of recap stars wraps only when the card runs out`() {
        // 28 dp stars, gap-1 = 4, inside a 480 dp card less `px-6` on both
        // sides: 432 dp fits 13 before the 14th wraps (13x28 + 12x4 = 412).
        val stars = List(14) { box(28, 28) }
        val rows = WrapRowLines.lines(stars, maxWidth = 432, spacing = 4)
        assertEquals(2, rows.size)
        assertEquals(13, rows[0].items.size)
        assertEquals(1, rows[1].items.size)
    }

    @Test
    fun `an item wider than the container overflows rather than vanishing`() {
        // CSS never breaks a line that holds a single item: an over-wide child
        // hangs out of the box. A break rule that tested the FIRST item too
        // would emit an empty line and silently drop the child.
        val rows = WrapRowLines.lines(listOf(box(600), box(40)), maxWidth = 300, spacing = 8)
        assertEquals(2, rows.size)
        assertEquals(listOf(0), rows[0].items)
        assertEquals(600, rows[0].width)
    }

    @Test
    fun `an empty row produces no lines`() {
        assertTrue(WrapRowLines.lines(emptyList(), maxWidth = 400, spacing = 4).isEmpty())
    }
}
