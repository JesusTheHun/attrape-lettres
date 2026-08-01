package fr.dappit.attrapelettres.ui.components

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.interaction.MotionSurface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// EarnBadge, EndButtons, Finished and Ollie — the numbers and the decisions,
// asserted against the TypeScript. Sources for every number:
//
//   EarnBadge.tsx    padding clamp(8,2.4vw,14) clamp(18,5vw,30)
//                    fontSize clamp(28,8vw,46), gap-2
//                    boxShadow "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"
//   EndButtons.tsx   mt-1, gap-3; Menu px-7 py-4 text-xl bg-white/80 shadow
//                    Suivant px-9 py-4 text-2xl bg-[#66BB6A]
//                    boxShadow "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"
//   Finished.tsx     gap-5, px-6, z-[41]; cheer clamp(64,20vw,110)
//                    stars fontSize 28, gap-1, grayscale(1)/opacity .45
//                    title clamp(26,7vw,40); pill shown only when earned > 0
//
// Tailwind spacing is n x 4 px: gap-1 = 4, mt-1 = 4, gap-2 = 8, gap-3 = 12,
// py-4 = 16, gap-5 = 20, px-6 = 24, px-7 = 28, px-9 = 36.
//
// The French copy itself is pinned by CopyTest, not restated here.

class EarnBadgeTest {

    @Test
    fun `the clamps are the authored ones`() {
        // 8vw of a 390 dp phone is 31.2, inside [28, 46].
        assertEquals(31.2f, EarnBadgeMetrics.FONT_SIZE.resolve(390f), 1e-4f)
        // …of a 320 dp phone it is 25.6, so the floor wins.
        assertEquals(28f, EarnBadgeMetrics.FONT_SIZE.resolve(320f), 1e-4f)
        // …of a 1024 dp tablet it is 81.9, so the cap wins. `vw` is the WINDOW,
        // not the 480 dp card.
        assertEquals(46f, EarnBadgeMetrics.FONT_SIZE.resolve(1024f), 1e-4f)

        assertEquals(9.36f, EarnBadgeMetrics.PADDING_Y.resolve(390f), 1e-4f)
        assertEquals(19.5f, EarnBadgeMetrics.PADDING_X.resolve(390f), 1e-4f)
        assertEquals(8f, EarnBadgeMetrics.GAP.value) // gap-2
    }

    @Test
    fun `the two box-shadows are the authored ones`() {
        // "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)"
        assertEquals(8f, EarnBadgeMetrics.LIP_DROP.value)
        assertEquals(
            CssShadow(y = 16.dp, blur = 26.dp, opacity = 0.2f),
            EarnBadgeMetrics.SOFT_SHADOW,
        )
        // CSS blur is twice the Gaussian sigma.
        assertEquals(13f, EarnBadgeMetrics.SOFT_SHADOW.blurRadius.value)
        // The lip's colour and the gradient ink come from the shared tokens.
        assertEquals("#E0A800", Palette.goldLip.hex)
        assertEquals("#4A3B00", Palette.goldInk.hex)
    }
}

class EndButtonsTest {

    @Test
    fun `the Tailwind spacing resolves to the authored pixels`() {
        assertEquals(12f, EndButtonsMetrics.GAP.value) // gap-3
        assertEquals(4f, EndButtonsMetrics.TOP_MARGIN.value) // mt-1
        assertEquals(28f, EndButtonsMetrics.MENU_PADDING_X.value) // px-7
        assertEquals(16f, EndButtonsMetrics.MENU_PADDING_Y.value) // py-4
        assertEquals(36f, EndButtonsMetrics.NEXT_PADDING_X.value) // px-9
        assertEquals(16f, EndButtonsMetrics.NEXT_PADDING_Y.value) // py-4
    }

    @Test
    fun `Suivant carries the primary lip, Menu carries Tailwind's shadow`() {
        // "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)"
        assertEquals(8f, EndButtonsMetrics.NEXT_LIP_DROP.value)
        assertEquals(
            CssShadow(y = 14.dp, blur = 24.dp, opacity = 0.2f),
            EndButtonsMetrics.NEXT_SOFT_SHADOW,
        )
        assertEquals("#66BB6A", Palette.green.hex)
        assertEquals("#43A047", Palette.greenLip.hex)
        // The plain Tailwind `shadow`, taken from the shared table rather than
        // re-authored: `0 1px 3px rgba(0,0,0,.1), 0 1px 2px -1px rgba(0,0,0,.1)`.
        assertEquals(Shadows.tailwind, EndButtonsMetrics.MENU_SHADOW)
        assertEquals(CssShadow(y = 1.dp, blur = 3.dp, opacity = 0.1f), EndButtonsMetrics.MENU_SHADOW[0])
        // `bg-white/80`
        assertEquals(0.80f, Palette.White.o80)
    }
}

class FinishedTest {

    @Test
    fun `the earn pill shows exactly the points it was given`() {
        // Invariant 8: Finished does not adjust the reward on the way to the
        // pill. Not a point is added, rounded or clamped.
        for (earned in listOf(1, 2, 3, 5, 8, 13, 46, 999)) {
            assertEquals(earned, FinishedMetrics.display(listOf(true), earned, "t").earned)
        }
    }

    @Test
    fun `a training run pays nothing and hides the pill instead of showing a zero`() {
        // « difficulty 0 » rows earn no accuracy bonus; a « +0 » would read as a
        // punishment, so the pill goes away and the cheer IS the reward.
        assertNull(FinishedMetrics.display(listOf(true), 0, "t").earned)
        // …and one single point is still worth a pill.
        assertEquals(1, assertNotNull(FinishedMetrics.display(listOf(true), 1, "t").earned))
    }

    @Test
    fun `the star array is rendered as handed in — lost rounds stay on screen`() {
        // Invariants 3 and 8: a greyed star is still DRAWN; the round counts as
        // played. Nothing here may compact, sort or drop an entry.
        val stars = listOf(true, false, false, true, false, true)
        val model = FinishedMetrics.display(stars, 4, "Tu as tout lu !")
        assertEquals(stars, model.stars)
        assertEquals(6, model.stars.size)
        assertEquals("Tu as tout lu !", model.title)

        val allLost = listOf(false, false, false)
        assertEquals(allLost, FinishedMetrics.display(allLost, 0, "t").stars)
    }

    @Test
    fun `the layout numbers are the authored ones`() {
        assertEquals(20f, FinishedMetrics.GAP.value) // gap-5
        assertEquals(24f, FinishedMetrics.PADDING_X.value) // px-6
        assertEquals(4f, FinishedMetrics.STAR_GAP.value) // gap-1
        assertEquals(28f, FinishedMetrics.STAR_SIZE.value) // a plain 28, not a clamp
        assertEquals(41f, FinishedMetrics.Z_INDEX) // z-[41], one above the confetti's 40
        // 20vw of 390 = 78; 7vw of 390 = 27.3.
        assertEquals(78f, FinishedMetrics.CHEER_SIZE.resolve(390f), 1e-4f)
        assertEquals(27.3f, FinishedMetrics.TITLE_SIZE.resolve(390f), 1e-4f)
        // …and the caps and floors, which is where a mistyped clamp shows.
        assertEquals(64f, FinishedMetrics.CHEER_SIZE.resolve(300f), 1e-4f)
        assertEquals(110f, FinishedMetrics.CHEER_SIZE.resolve(1024f), 1e-4f)
    }

    @Test
    fun `a lost star is greyed, not hidden`() {
        // `{ filter: "grayscale(1)", opacity: 0.45 }`
        assertEquals(0f, Palette.Lost.saturation)
        assertEquals(0.45f, Palette.Lost.opacity)
        assertTrue(Palette.Lost.opacity > 0f, "the round still counts as played")
    }
}

class OllieTest {

    @Test
    fun `the mascot is gated by reduced motion — invariant 6`() {
        val gate = GatedReduceMotion(FixedReduceMotion(true), MotionSurface.MASCOT)
        assertTrue(gate.isReduced)
        assertFalse(GatedReduceMotion(FixedReduceMotion(false), MotionSurface.MASCOT).isReduced)
        assertEquals(MotionSurface.MASCOT, OllieDefaults.SURFACE)
        assertEquals(88f, OllieDefaults.SIZE.value) // the TSX default
    }

    @Test
    fun `the gate is the table's answer, not a second opinion`() {
        // Press and shake are NOT gated on the web (a bare `el.animate` in
        // Tile.tsx), so the same adapter over those surfaces must let them run
        // even with the setting on. One table, consulted — not re-decided here.
        assertFalse(GatedReduceMotion(FixedReduceMotion(true), MotionSurface.PRESS).isReduced)
        assertFalse(GatedReduceMotion(FixedReduceMotion(true), MotionSurface.SHAKE).isReduced)
        assertTrue(GatedReduceMotion(FixedReduceMotion(true), MotionSurface.CONFETTI).isReduced)
    }

    @Test
    fun `the gate is a live view of the setting, never a snapshot`() {
        // The system setting can change while the app runs. A copy taken at
        // composition time would keep bobbing a mascot the child just asked to
        // hold still.
        var reduced = false
        val source = object : ReduceMotionSource {
            override val isReduced: Boolean get() = reduced
        }
        val gate = GatedReduceMotion(source, MotionSurface.MASCOT)
        assertFalse(gate.isReduced)
        reduced = true
        assertTrue(gate.isReduced)
    }
}

// --- Source scans ------------------------------------------------------------
//
// Two structural claims that no value assertion can make, following the house
// pattern (:core's LicensingSourceScanTest, :ui's TouchDownSourceScanTest).

// `findComponentSources` / `codeLines` used to live here in duplicate; they are
// now the shared `componentSources()` / `codeLines()` in ComponentsTestSupport.kt.

class ComponentSourceScanTest {

    private fun sources(): List<File> {
        val files = componentSources()
        assertTrue(
            files.isNotEmpty(),
            "the scan could not locate ui/components from ${System.getProperty("user.dir")}",
        )
        return files
    }

    @Test
    fun `no component keeps its own table of word illustrations`() {
        // `WordImages.draw` is the ONE exhaustive `when` over ImageKey, `else`-less,
        // so a fifth key is a compile error there rather than a picture that
        // silently renders nothing. A second table here — a map, a `when`, a
        // per-key branch — would let a new key compile and draw nothing, which is
        // exactly the failure that design prevents.
        for (file in sources()) {
            for ((index, line) in codeLines(file).withIndex()) {
                assertFalse(
                    line.contains("ImageKey."),
                    "${file.name}:${index + 1} branches on an ImageKey case — :art owns that table",
                )
            }
        }
    }

    @Test
    fun `nothing computes a reward — invariant 8`() {
        // Points come from `sessionReward` and nowhere else. These two files
        // DISPLAY a number they are handed; the only transformation allowed is
        // the string interpolation in Copy, and the only decision is whether the
        // pill is visible at all. Arithmetic on `earned` anywhere in this
        // package is a second economy.
        val forbidden = listOf("+", "*", "/", "coerce", "roundTo", "abs(", "maxOf", "minOf", "sum")
        for (file in sources()) {
            for ((index, raw) in codeLines(file).withIndex()) {
                if (!raw.contains("earned")) continue
                val line = raw.replace("->", "").replace("?.", "")
                for (token in forbidden) {
                    assertFalse(
                        line.contains(token),
                        "${file.name}:${index + 1} does arithmetic on a reward: $raw",
                    )
                }
            }
        }
    }
}
