package fr.dappit.attrapelettres.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// FitLine — the pure fit rule, asserted against `src/components/FitLine.tsx`:
//
//   const k = natural > 0 && natural > available ? available / natural : 1;
//   inner.style.transform = k < 1 ? `scale(${k})` : "";
//   outer.style.height    = k < 1 ? `${naturalHeight * k}px` : "";
//
// The composable itself needs a composition; what the host CAN prove is the
// derivation — including every boundary where the TSX changes behaviour — and
// the shape of the « collapse onto the tray » fix: the fit is always derived
// from the NATURAL size, so re-fitting never compounds.
//
// Ported from apps/game-ios/Tests/ALUITests/Components/FitLineTests.swift.

class FitLineScaleTest {

    @Test
    fun `a row that fits is left at natural size`() {
        assertEquals(1f, FitLineFit.scale(natural = 200f, available = 320f))
    }

    @Test
    fun `natural equal to available is NOT shrunk — the TSX comparison is strict`() {
        assertEquals(1f, FitLineFit.scale(natural = 320f, available = 320f))
    }

    @Test
    fun `one point of overflow starts shrinking`() {
        // k = available / natural, computed here independently of the code.
        assertEquals(320f / 321f, FitLineFit.scale(natural = 321f, available = 320f))
        assertEquals(0.64f, FitLineFit.scale(natural = 500f, available = 320f), 1e-6f)
    }

    @Test
    fun `nothing measured yet gives 1 — the natural greater than 0 guard`() {
        assertEquals(1f, FitLineFit.scale(natural = 0f, available = 320f))
    }

    @Test
    fun `a zero-width slot scales to zero — the web's scale(0), kept identical`() {
        assertEquals(0f, FitLineFit.scale(natural = 100f, available = 0f))
    }

    @Test
    fun `the row is never ENLARGED, only shrunk`() {
        assertEquals(1f, FitLineFit.scale(natural = 100f, available = 500f))
    }
}

class FitLinePinnedHeightTest {

    @Test
    fun `shrunk — the wrapper is pinned to naturalHeight times k, snug against the row`() {
        val pinned = assertNotNull(FitLineFit.pinnedHeight(naturalHeight = 100f, scale = 0.64f))
        assertTrue(kotlin.math.abs(pinned - 64f) < 1e-4f)
        assertEquals(40f, FitLineFit.pinnedHeight(naturalHeight = 80f, scale = 0.5f))
    }

    @Test
    fun `unshrunk — no height override, the TSX writes an empty style`() {
        assertNull(FitLineFit.pinnedHeight(naturalHeight = 100f, scale = 1f))
    }

    @Test
    fun `re-fitting from the same natural size is stable — the anti-collapse property`() {
        // The bug this rule exists for: the wrapper's pinned height fed back
        // into the next measurement, so each re-fit shrank the row again until
        // it collapsed onto the tray. The fix measures the NATURAL
        // (untransformed) size every time — in this port, an unbounded
        // `Constraints()` — so fitting twice from the same inputs must be the
        // fixed point, not a second shrink.
        val natural = 500f
        val naturalHeight = 100f
        val available = 320f

        val k1 = FitLineFit.scale(natural, available)
        val h1 = FitLineFit.pinnedHeight(naturalHeight, k1)
        val k2 = FitLineFit.scale(natural, available)
        val h2 = FitLineFit.pinnedHeight(naturalHeight, k2)

        assertEquals(k1, k2)
        assertEquals(h1, h2)
        val pinned = assertNotNull(h1)
        assertTrue(kotlin.math.abs(pinned - 64f) < 1e-4f)

        // And the failure mode, for contrast: were the row allowed to stretch to
        // the pinned height, the next measurement would read 64 instead of 100
        // and pin 64 x 0.64 = 40.96 — the second step of the collapse the
        // natural-size measurement forbids.
        val compounded = assertNotNull(FitLineFit.pinnedHeight(pinned, k1))
        assertTrue(kotlin.math.abs(compounded - 40.96f) < 1e-3f)
    }
}
