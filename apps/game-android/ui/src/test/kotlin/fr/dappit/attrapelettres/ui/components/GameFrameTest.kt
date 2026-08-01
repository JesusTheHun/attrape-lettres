package fr.dappit.attrapelettres.ui.components

import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// GameFrame — the frame's authored numbers, the strip's flex-wrap arithmetic,
// and the confetti stacking order.
//
//   metrics   src/components/GameFrame.tsx, the Tailwind classes
//             rounded-3xl=24  min-h-[620px]  gap-3=12  px-4 pt-4=16
//             gap-x-1=4  gap-y-0.5=2  w-[84px]  sm: = 640px
//   wrap      `flex-wrap … gap-x-1`, greedy, centred
//   z-order   canvas zIndex 40  <  header z-[41]  =  children z-[41]

class GameFrameMetricsTest {

    @Test
    fun `rounded-3xl 24, min-h 620, gap-3 12, px-4 and pt-4 16`() {
        assertEquals(24f, GameFrameMetrics.cornerRadius.value)
        assertEquals(620f, GameFrameMetrics.minHeight.value)
        assertEquals(Shell.minimumScreenHeight, GameFrameMetrics.minHeight)
        assertEquals(12f, GameFrameMetrics.headerSpacing.value)
        assertEquals(16f, GameFrameMetrics.headerPadding.value)
    }

    @Test
    fun `strip gaps 4 and 2 — the sm-only 84 dp spacer appears at viewport 640`() {
        assertEquals(4f, GameFrameMetrics.stripGapX.value) // gap-x-1
        assertEquals(2f, GameFrameMetrics.stripGapY.value) // gap-y-0.5
        assertEquals(84f, GameFrameMetrics.spacerWidth.value) // w-[84px]
        assertEquals(640f, GameFrameMetrics.spacerMinViewport.value) // Tailwind sm:
    }

    @Test
    fun `the back button wears px-4 py-2 and the pill's own 44 dp line box`() {
        assertEquals(16f, GameFrameMetrics.backPaddingX.value)
        assertEquals(8f, GameFrameMetrics.backPaddingY.value)
        // text-lg's 28 px line box + py-2 twice = 44 dp of button. The web's own
        // arithmetic, and the only reason « ← Menu » clears the tap floor.
        assertEquals(28f, 18f * TEXT_LG_LEADING, 0.001f)
        assertEquals(44f, 18f * TEXT_LG_LEADING + 2f * GameFrameMetrics.backPaddingY.value, 0.001f)
    }

    @Test
    fun `the lost-star treatment and the strip glyphs, byte-exact`() {
        // LOST = { filter: grayscale(1), opacity: 0.45 }; live 0.8; dots 0.28.
        assertEquals(0f, Palette.Lost.saturation)
        assertEquals(0.45f, Palette.Lost.opacity)
        assertEquals(0.8f, Palette.liveStarOpacity)
        assertEquals(0.28f, Palette.futureDotOpacity)
        assertEquals("⭐", Copy.Frame.STAR)
        assertEquals("•", Copy.Frame.FUTURE_ROUND)
        assertEquals("← Menu", Copy.Frame.BACK_TO_MENU)
    }
}

class StripFlowTest {

    @Test
    fun `12 stars of 16 px wrap 9 plus 3 in a 180 px slot at gap 4`() {
        // 9 items: 9*16 + 8*4 = 176 <= 180; a 10th would need 196.
        val widths = List(12) { 16 }
        assertEquals(listOf(9, 3), StripFlow.wrapLines(widths, maxWidth = 180, gapX = 4).map { it.size })
    }

    @Test
    fun `an unbounded width keeps one line`() {
        val widths = List(12) { 20 }
        assertEquals(
            listOf(12),
            StripFlow.wrapLines(widths, maxWidth = Int.MAX_VALUE, gapX = 4).map { it.size },
        )
    }

    @Test
    fun `an exact fit stays on the line — one pixel less wraps`() {
        val widths = List(3) { 20 } // 3*20 + 2*4 = 68
        assertEquals(listOf(3), StripFlow.wrapLines(widths, maxWidth = 68, gapX = 4).map { it.size })
        assertEquals(listOf(2, 1), StripFlow.wrapLines(widths, maxWidth = 67, gapX = 4).map { it.size })
    }

    @Test
    fun `an item wider than the slot still gets a line of its own`() {
        // Flexbox does not drop an item it cannot fit, and neither may we: a
        // strip that silently loses a round is a strip a child cannot count.
        val lines = StripFlow.wrapLines(listOf(20, 500, 20), maxWidth = 40, gapX = 4)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), lines)
    }

    @Test
    fun `every item lands on exactly one line, in order`() {
        val widths = List(23) { 16 }
        val lines = StripFlow.wrapLines(widths, maxWidth = 100, gapX = 4)
        assertEquals(widths.indices.toList(), lines.flatten())
    }

    @Test
    fun `a line's width is its items plus the gaps between them`() {
        val widths = listOf(16, 16, 16)
        assertEquals(56, StripFlow.lineWidth(listOf(0, 1, 2), widths, gapX = 4))
        assertEquals(16, StripFlow.lineWidth(listOf(0), widths, gapX = 4))
        assertEquals(0, StripFlow.lineWidth(emptyList(), widths, gapX = 4))
    }
}

class GameFrameZOrderTest {

    // Two iOS engine agents independently reported that `GameFrame` painted the
    // confetti ON TOP of the exercise content, where the web paints it behind,
    // and it had survived a full phase: the draw list, the view type, the
    // modifier chain and 1115 other tests were equally happy either way. Only
    // pixels could tell, and iOS could rasterise on the host. Android cannot —
    // no Robolectric, no compose-ui-test, by decision.
    //
    // So the defence is split in two, and both halves are here: the ORDER is a
    // pure enum a test can read, and the fact that the composable actually
    // applies it is a source scan. What neither can catch is a Compose semantic
    // that makes `zIndex` not mean what it says; that needs a device, and it is
    // recorded as such.

    @Test
    fun `the confetti canvas paints under the header and the children`() {
        // Web, measured in the TSX: canvas zIndex 40, header z-[41], children
        // z-[41]. The burst passes BEHIND the tiles, the mascot and the word
        // picture, so at the reward moment the child's eye stays on the word.
        assertTrue(FrameLayer.OVERLAY.z < FrameLayer.FLOW.z)
        assertEquals(40f, FrameLayer.OVERLAY.z)
        assertEquals(41f, FrameLayer.FLOW.z)
    }

    @Test
    fun `the overlay still paints over the stage wash — it is not behind everything`() {
        // The complementary half. Pushing the canvas below the wash would hide
        // the burst entirely, which the ordering test above would not notice.
        // The wash is drawn by the frame box itself, so it is behind every
        // child by construction: what this asserts is that it is a `drawBehind`
        // on the frame and not a sibling with a z of its own.
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val source = code(frame)
        assertTrue(
            source.contains("drawBehind { drawRect(Palette.stage.brush(size)) }"),
            "the stage wash must be drawn by the frame box, behind every child",
        )
        assertFalse(
            source.contains("FrameLayer.STAGE"),
            "the wash is not a sibling layer; giving it a z would let it be reordered",
        )
    }

    @Test
    fun `both z values come from FrameLayer, and nothing else spells one`() {
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val lines = codeLines(frame)
        val zLines = lines.filter { it.contains(".zIndex(") }
        assertEquals(2, zLines.size, "expected exactly two z-index call sites, got: $zLines")
        assertTrue(
            zLines.any { it.contains("FrameLayer.OVERLAY.z") },
            "the confetti slot must carry FrameLayer.OVERLAY",
        )
        assertTrue(
            zLines.any { it.contains("FrameLayer.FLOW.z") },
            "the header + children column must carry FrameLayer.FLOW",
        )
        for (line in zLines) {
            assertTrue(
                line.contains("FrameLayer."),
                "a bare z value drifts from the web's: $line",
            )
        }
    }

    @Test
    fun `the confetti slot is full-bleed and out of the accessibility tree`() {
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val source = code(frame)
        // `absolute inset-0` — takes the parent's size without driving it.
        assertTrue(source.contains("matchParentSize()"), "the canvas is inset-0, not a flow child")
        // `pointer-events-none` + aria-hidden.
        assertTrue(source.contains("clearAndSetSemantics"), "the canvas must be hidden from TalkBack")
    }
}

class GameFrameStructureTest {

    @Test
    fun `the frame never puts a scroller over an exercise`() {
        // ARCHITECTURE section 3 row 1: a scrollable ancestor competes for the
        // same down event, and every exercise tile grid lives under this frame.
        // `PageScroll` is for the two adult-ish screens and nothing else.
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val source = code(frame)
        assertFalse(source.contains("verticalScroll"), "no scroll container may wrap an exercise")
        assertFalse(source.contains("pageScroll"), "no scroll container may wrap an exercise")
    }

    @Test
    fun `the frame takes its touch path from the one primitive`() {
        // « ← Menu » is navigation and fires on the LIFT (the TSX uses onClick
        // there deliberately) — but through `touchDown`'s onUp, not through a
        // second gesture API. `Modifier.clickable` fires after gesture
        // arbitration and behind Material's ripple; it is banned app-wide.
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val source = code(frame)
        assertTrue(source.contains("touchDown("), "the frame must route through touchDown")
        assertTrue(source.contains("onUp ="), "« ← Menu » confirms on the lift, inside the button")
        for (file in componentSources()) {
            for ((index, line) in codeLines(file).withIndex()) {
                assertFalse(
                    line.contains("clickable("),
                    "${file.name}:${index + 1} uses clickable — invariant 1 forbids it",
                )
                assertFalse(
                    line.contains("detectTapGestures"),
                    "${file.name}:${index + 1} uses detectTapGestures — use touchDown",
                )
            }
        }
    }

    @Test
    fun `the strip greys through StarStrip and nowhere else`() {
        // The one edit that would silently break invariant 8 is an engine that
        // writes `stars[i] = false` itself and forgets the already-grey guard,
        // or a frame that recomputes the flags at render time. The frame renders
        // the flags it is given; the mutation has exactly one entry point.
        val frame = assertNotNull(componentSource("GameFrame.kt"), "GameFrame.kt not found")
        val body = code(frame).substringAfter("fun GameFrame(")
        assertFalse(body.contains("stars["), "the frame must not index the flags itself")
        assertFalse(body.contains("miss("), "the frame must not grey a star; the engine does, at down")
    }
}
