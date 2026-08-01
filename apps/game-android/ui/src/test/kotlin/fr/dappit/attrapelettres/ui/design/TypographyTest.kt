package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.faceLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypographyTest {

    // The Tailwind classes the shell actually uses, with their computed px. Read
    // off the scale, not out of Typography.kt.
    @Test
    fun theSizeRampIsTheComputedTailwindScale() {
        assertEquals(11.dp, Typography.Size.xxs) // text-[11px]
        assertEquals(12.dp, Typography.Size.xs) // text-xs
        assertEquals(14.dp, Typography.Size.sm) // text-sm
        assertEquals(16.dp, Typography.Size.base) // text-base
        assertEquals(18.dp, Typography.Size.lg) // text-lg
        assertEquals(20.dp, Typography.Size.xl) // text-xl
        assertEquals(24.dp, Typography.Size.xxl) // text-2xl
    }

    // The ramp is a ladder, and the smallest rung is 11 dp because that is what
    // the hub's repeat-coin badge is authored at. A "tidied" ramp that reorders
    // or drops a rung silently reflows five screens.
    @Test
    fun theRampIsStrictlyIncreasingAndNeverGoesBelow11dp() {
        val ramp = Typography.Size.ramp
        assertEquals(7, ramp.size)
        for (i in 1 until ramp.size) {
            assertTrue(ramp[i] > ramp[i - 1], "${ramp[i]} does not exceed ${ramp[i - 1]}")
        }
        assertEquals(11.dp, ramp.first())
        assertEquals(24.dp, ramp.last())
    }

    // CSS numeric weights, one for one. Compose names weights by NUMBER, so the
    // iOS trap -- SwiftUI calling 800 `.heavy` and 900 `.black`, which makes
    // `font-extrabold` read plausibly as `.black` -- cannot happen here. Pinned
    // anyway: the numbers are what a three-way pixel diff would rest on.
    @Test
    fun theWeightsAreTheCssNumbers() {
        assertEquals(600, Typography.Weight.semibold.weight)
        assertEquals(700, Typography.Weight.bold.weight)
        assertEquals(800, Typography.Weight.extrabold.weight)
        assertEquals(900, Typography.Weight.black.weight)
        assertNotEquals(Typography.Weight.extrabold, Typography.Weight.black)
    }

    @Test
    fun theCssStacksAreRecordedVerbatim() {
        assertEquals("ui-rounded,'SF Pro Rounded',system-ui,sans-serif", Typography.CSS_FONT_STACK)
        assertEquals(
            "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive",
            Typography.CSS_CURSIVE_STACK,
        )
        assertEquals(
            listOf("Snell Roundhand", "Apple Chancery", "Segoe Script", "Bradley Hand"),
            Typography.cursiveFamilies,
        )
    }

    // CSS line-height and Compose's `lineHeight` both mean the whole line box, so
    // the conversion is a multiply. `leading-snug` (1.375) at 16 dp is a 22 dp
    // line box -- not 6 dp, which is what SwiftUI's `lineSpacing` needs and what
    // a port copied from `Typography.swift` would produce.
    @Test
    fun lineHeightRatiosBecomeTheWholeLineBox() {
        assertEquals(1.0f, Typography.LineHeight.none)
        assertEquals(1.25f, Typography.LineHeight.tight)
        assertEquals(1.375f, Typography.LineHeight.snug)

        assertEquals(22f, Typography.lineHeightPx(16f, Typography.LineHeight.snug))
        assertEquals(19.25f, Typography.lineHeightPx(14f, Typography.LineHeight.snug))
        assertEquals(20f, Typography.lineHeightPx(16f, Typography.LineHeight.tight))
        // leading-none is the size itself, never zero: a zero line box collapses
        // every single-line label in the app, which is most of them.
        assertEquals(40f, Typography.lineHeightPx(40f, Typography.LineHeight.none))
    }

    // Sizes are fixed, like CSS px and like iOS: a glyph sized from the same
    // clamp as its tile must not grow past the tile when the system font scale
    // does. Invariant 6 is met by tap targets and content descriptions instead.
    @Test
    fun sizesDoNotMoveWithTheSystemFontScale() {
        assertEquals(24f, Typography.sp(Typography.Size.xxl, 1f).value)
        assertEquals(12f, Typography.sp(Typography.Size.xxl, 2f).value)
        assertEquals(24f, Typography.sp(Typography.Size.xxl, 0f).value)

        val style1x = Typography.style(Typography.Size.base, ratio = Typography.LineHeight.snug, fontScale = 1f)
        val style2x = Typography.style(Typography.Size.base, ratio = Typography.LineHeight.snug, fontScale = 2f)
        assertEquals(16f, style1x.fontSize.value)
        assertEquals(22f, style1x.lineHeight.value)
        // Halved in sp so the RENDERED size is unchanged at a 2x scale.
        assertEquals(8f, style2x.fontSize.value)
        assertEquals(11f, style2x.lineHeight.value)
    }

    @Test
    fun theDefaultStyleIsTheAppsOwnVoice() {
        val style = Typography.style(Typography.Size.xl)
        assertEquals(900, style.fontWeight?.weight)
        assertEquals(FontFamily.SansSerif, style.fontFamily)
        assertEquals(Palette.ink.color, style.color)
    }

    // Letter forms --------------------------------------------------------------

    // A printed letter is drawn in the same face as everything else -- so the
    // letter on a tile is the letter the child sees in the hub. A cursive one
    // must NOT be, or the « attachée » exercises teach the wrong shape.
    @Test
    fun printAndCursiveResolveToDifferentFamilies() {
        assertEquals(Typography.appFamily, Typography.letterFamily(LetterScript.PRINT))
        assertEquals(Typography.cursiveFamily, Typography.letterFamily(LetterScript.CURSIVE))
        assertNotEquals(
            Typography.letterFamily(LetterScript.PRINT),
            Typography.letterFamily(LetterScript.CURSIVE),
        )
        // Both are GENERIC families, so nothing here needs a font file on disk --
        // the same zero-dependency bet `letterForms.ts` makes.
        assertEquals(FontFamily.SansSerif, Typography.appFamily)
        assertEquals(FontFamily.Cursive, Typography.cursiveFamily)
    }

    // `faceLabel` belongs to :core and is NOT re-implemented here. Asserted so a
    // future refactor cannot quietly grow a second copy in the view layer.
    @Test
    fun theLettersScreenReaderLabelStillComesFromCore() {
        assertEquals(
            "Lettre A minuscule attachée",
            faceLabel(LetterFace(base = "A", glyph = "a", script = LetterScript.CURSIVE)),
        )
        assertEquals(
            "Lettre A majuscule",
            faceLabel(LetterFace(base = "A", glyph = "A", script = LetterScript.PRINT)),
        )
    }
}
