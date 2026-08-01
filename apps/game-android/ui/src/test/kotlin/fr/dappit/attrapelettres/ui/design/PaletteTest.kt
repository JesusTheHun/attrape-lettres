package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.graphics.toArgb
import fr.dappit.attrapelettres.art.svg.SvgColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Every hex asserted here was read out of the TSX / index.css, not out of
// Palette.kt. The point of the file is that a token drifting from its source
// literal fails loudly instead of shipping a slightly-off brown.
//
// The contrast assertions are the other half: a hex can be copied correctly and
// still be unreadable if it is later "tidied", and a six-year-old reading a
// letter off a tile is the whole product.

class PaletteTest {

    @Test
    fun thePageMat() {
        assertEquals("#efe6da", Palette.page.hex) // index.css, body
    }

    // TWO stage gradients exist and the difference is a single stop position: the
    // play surfaces break the cream at 38 %, the adult/roster/shop ones at 40 %.
    // Unifying them would be a silent redesign of five screens.
    @Test
    fun bothStageGradientsIncludingThe38And40Split() {
        assertEquals(180f, Palette.stage.degrees)
        assertEquals(listOf("#FFE7C9", "#FFEFD6", "#DCEFFB"), Palette.stage.stops.map { it.hex })
        assertEquals(listOf(0.0f, 0.38f, 1.0f), Palette.stage.stops.map { it.location })

        assertEquals(180f, Palette.stageAdult.degrees)
        assertEquals(listOf("#FFE7C9", "#FFEFD6", "#DCEFFB"), Palette.stageAdult.stops.map { it.hex })
        assertEquals(listOf(0.0f, 0.40f, 1.0f), Palette.stageAdult.stops.map { it.location })

        assertNotEquals(Palette.stage, Palette.stageAdult)
    }

    @Test
    fun ink() {
        assertEquals("#5A3A1E", Palette.ink.hex)
        assertEquals("#7A5A3A", Palette.inkSoft.hex)
        assertEquals("#9A7A5A", Palette.inkFaint.hex)
        assertEquals("#6B4A2C", Palette.inkProse.hex)
        assertEquals("#8A6A4A", Palette.inkQuiet.hex)
        assertEquals("#7A5B3C", Palette.inkGate.hex)
        assertEquals("#8A7B69", Palette.inkUnaffordable.hex)
    }

    @Test
    fun goldTheRewardVocabulary() {
        assertEquals("#4A3B00", Palette.goldInk.hex)
        assertEquals(180f, Palette.goldPill.degrees)
        assertEquals(listOf("#FFDE6B", "#FFC107"), Palette.goldPill.stops.map { it.hex })
        assertEquals(listOf(0.0f, 1.0f), Palette.goldPill.stops.map { it.location })
        assertEquals("#E0A800", Palette.goldLip.hex)
        assertEquals("#FFC107", Palette.jackpot.hex)
        assertEquals("#B07A00", Palette.coinInk.hex)
        assertEquals("#FFE08A", Palette.coinRing.hex)
        assertEquals("#FFD54F", Palette.wallet.hex)
        assertEquals(90f, Palette.savingsFill.degrees)
        assertEquals(listOf("#FFC107", "#FFD54F"), Palette.savingsFill.stops.map { it.hex })
        assertEquals("#FFF6E0", Palette.shopEquipped.hex)
        assertEquals("#FFF9EB", Palette.shopTrying.hex)
        assertEquals("#FFB300", Palette.tryRing.hex)
    }

    @Test
    fun greenTheGoVocabulary() {
        assertEquals("#66BB6A", Palette.green.hex)
        assertEquals("#43A047", Palette.greenLip.hex)
        assertEquals(90f, Palette.growthFill.degrees)
        assertEquals(listOf("#AED581", "#66BB6A"), Palette.growthFill.stops.map { it.hex })
        assertEquals("#E9DCC7", Palette.growthTrack.hex)
        assertEquals("#3E7B3E", Palette.equippedInk.hex)
        assertEquals("#E6F4E6", Palette.currentBadge.hex)
        assertEquals("#2E7D32", Palette.currentBadgeInk.hex)
        assertEquals(135f, Palette.growthCard.degrees)
        assertEquals(listOf("#E9F9E0", "#D6F0FB"), Palette.growthCard.stops.map { it.hex })
        assertEquals(160f, Palette.wardrobeZone.degrees)
        assertEquals(listOf("#EAF7E0", "#F4FBEC"), Palette.wardrobeZone.stops.map { it.hex })
        assertEquals(160f, Palette.storeZone.degrees)
        assertEquals(listOf("#E2F0FC", "#EBF5FE"), Palette.storeZone.stops.map { it.hex })
    }

    @Test
    fun slotsBordersDisabled() {
        assertEquals("#E4A15E", Palette.slotDashed.hex)
        assertEquals("#C9A87A", Palette.slotDashedLocked.hex)
        assertEquals("#FFF3E0", Palette.slotRevealed.hex)
        assertEquals("#B8A98E", Palette.disabled.hex)
        assertEquals("#DADCE4", Palette.ghost.hex)
        assertEquals("#F1F0F5", Palette.previewPlate.hex)
    }

    @Test
    fun theAdultSurfaces() {
        assertEquals("#FFFDF8", Palette.gateCard.hex)
        assertEquals("#E6D8C6", Palette.gateField.hex)
        assertEquals("#E5736A", Palette.gateFieldWrong.hex)
        assertEquals("#C4544A", Palette.gateError.hex)
        assertEquals("#F0E6D8", Palette.adultSecondary.hex)
        assertEquals("#EF5350", Palette.destructive.hex)
    }

    // Pick tiles ---------------------------------------------------------------

    @Test
    fun tileColorsInTheOrderTheSinglePickExercisesRotateThrough() {
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8"),
            Palette.tileColors.map { it.bg.hex },
        )
        assertEquals(
            listOf("#4A2317", "#4A3B00", "#062E3D", "#213606", "#2C1846"),
            Palette.tileColors.map { it.ink.hex },
        )
    }

    // The tray ramp starts on blue, not orange. A tray tile and a pick tile at the
    // same index are therefore DIFFERENT colours -- authored that way in four
    // engines, so the two lists must not be collapsed into one.
    @Test
    fun trayColorsIsTheSameFivePaintsInADifferentRotation() {
        assertEquals(
            listOf("#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65"),
            Palette.trayColors.map { it.bg.hex },
        )
        assertEquals(
            listOf("#062E3D", "#213606", "#4A3B00", "#2C1846", "#4A2317"),
            Palette.trayColors.map { it.ink.hex },
        )
        assertEquals(Palette.tileColors.toSet(), Palette.trayColors.toSet())
        assertNotEquals(Palette.tileColors, Palette.trayColors)
        assertNotEquals(Palette.tileColors[0], Palette.trayColors[0])
    }

    // `SyllableGridExercise` is the one engine with a fourth colour of its own: a
    // lighter green on a darker ink than every other exercise's fourth tile.
    @Test
    fun theSyllableGridsFourthTileIsItsOwnGreen() {
        assertEquals(4, Palette.gridTileColors.size)
        assertEquals(Palette.tileColors.take(3), Palette.gridTileColors.take(3))
        assertEquals("#A5D6A7", Palette.gridTileColors[3].bg.hex)
        assertEquals("#123B18", Palette.gridTileColors[3].ink.hex)
        assertNotEquals(Palette.tileColors[3], Palette.gridTileColors[3])
    }

    // Contrast -----------------------------------------------------------------

    // Every pick tile must be readable: the whole point of the (bg, ink) pair. A
    // dark ink on a light face, never the reverse, and never below WCAG AA for
    // body text even though the glyph is far larger than body text.
    @Test
    fun everyTilePaintClearsWcagAa() {
        val all = Palette.tileColors + Palette.trayColors + Palette.gridTileColors
        for (paint in all) {
            val ratio = contrast(paint.bg.hex, paint.ink.hex)
            assertTrue(
                ratio >= 4.5,
                "${paint.ink.hex} on ${paint.bg.hex} is only ${"%.2f".format(ratio)}:1",
            )
            assertTrue(
                luminance(paint.bg.hex) > luminance(paint.ink.hex),
                "${paint.bg.hex} is darker than its ink ${paint.ink.hex}",
            )
        }
    }

    // The headline brown has to read on the mat and on every stop of the wash it
    // is painted over. AAA (7:1), which it clears comfortably -- the assertion is
    // there so a future "warmer" brown cannot land without a failing test.
    @Test
    fun theHeadlineInkReadsOnEveryBackgroundItIsPaintedOn() {
        val backgrounds = buildList {
            add(Palette.page.hex)
            add("#FFFFFF")
            addAll(Palette.stage.stops.map { it.hex })
            addAll(Palette.stageAdult.stops.map { it.hex })
        }
        for (bg in backgrounds) {
            val ratio = contrast(bg, Palette.ink.hex)
            assertTrue(ratio >= 7.0, "ink on $bg is only ${"%.2f".format(ratio)}:1")
        }
        // The wash stays light end to end; a dark stop would strand the brown.
        for (stop in Palette.stage.stops) {
            assertTrue(luminance(stop.hex) > 0.75, "${stop.hex} is too dark for a wash stop")
        }
    }

    // The three ink voices are a deliberate ladder: each is QUIETER than the one
    // before. Swap two and the hub's hint clause shouts over its own title.
    // Only the first two clear AA for small text; `inkFaint` is AA-large only,
    // which is what the web ships, and it is asserted here so the fact is
    // recorded rather than discovered.
    @Test
    fun theThreeInkVoicesGetProgressivelyQuieter() {
        val ladder = listOf(Palette.ink, Palette.inkSoft, Palette.inkFaint)
        for (i in 1 until ladder.size) {
            assertTrue(
                luminance(ladder[i].hex) > luminance(ladder[i - 1].hex),
                "${ladder[i].hex} is not lighter than ${ladder[i - 1].hex}",
            )
        }
        assertTrue(contrast("#FFFFFF", Palette.ink.hex) >= 7.0)
        assertTrue(contrast("#FFFFFF", Palette.inkSoft.hex) >= 4.5)
        assertTrue(contrast("#FFFFFF", Palette.inkFaint.hex) >= 3.0)
        assertTrue(contrast("#FFFFFF", Palette.inkFaint.hex) < 4.5)
    }

    // Gold ink lives inside a gold pill, so it has to clear both ends of the
    // gradient, not just the average.
    @Test
    fun goldInkReadsOnBothEndsOfTheGoldPill() {
        for (stop in Palette.goldPill.stops) {
            val ratio = contrast(stop.hex, Palette.goldInk.hex)
            assertTrue(ratio >= 4.5, "goldInk on ${stop.hex} is only ${"%.2f".format(ratio)}:1")
        }
        assertTrue(contrast(Palette.wallet.hex, Palette.goldInk.hex) >= 4.5)
    }

    // Translucent whites and the greyed star ------------------------------------

    @Test
    fun theWhiteOpacitiesInUse() {
        assertEquals(0.55f, Palette.White.o55)
        assertEquals(0.70f, Palette.White.o70)
        assertEquals(0.80f, Palette.White.o80)
        assertEquals(0.85f, Palette.White.o85)
        assertEquals(0.90f, Palette.White.o90)
        assertEquals(0.92f, Palette.White.o92)
        assertEquals(0.95f, Palette.White.o95)
    }

    // Invariant 8's visible half: a lost star greys but stays VISIBLE -- the round
    // still counts as played. Dropping the opacity to 0 would erase it, and with
    // it the promise of invariant 3 that a wrong tap loses nothing but the star.
    @Test
    fun lostGreysTheStarWithoutHidingIt() {
        assertEquals(0f, Palette.Lost.saturation)
        assertEquals(0.45f, Palette.Lost.opacity)
        assertTrue(Palette.Lost.opacity > 0f)
        assertEquals(0.8f, Palette.liveStarOpacity)
        assertEquals(0.28f, Palette.futureDotOpacity)
        // Live reads brighter than lost, and a future dot fainter than both.
        assertTrue(Palette.liveStarOpacity > Palette.Lost.opacity)
        assertTrue(Palette.futureDotOpacity < Palette.Lost.opacity)
    }

    @Test
    fun theScrimsAreTheAuthoredRgbaValues() {
        assertEquals(0x8C1E140A.toInt(), Palette.gateScrim.toArgb()) // rgba(30,20,10,0.55)
        assertEquals(0x734A3018.toInt(), Palette.tryOnScrim.toArgb()) // rgba(74,48,24,0.45)
        assertEquals(0xD1FFF4E0.toInt(), Palette.shopHeader.toArgb()) // rgba(255,244,224,0.82)
    }

    // Hex parsing and gradient geometry ------------------------------------------

    // One parser, :art's. A second one in the view layer is a second set of edge
    // cases, and `android.graphics.Color.parseColor` cannot even run here.
    @Test
    fun aHexTokenParsesToTheColourItNames() {
        assertEquals(0xFF5A3A1E.toInt(), Palette.ink.color.toArgb())
        assertEquals(0xFFEFE6DA.toInt(), Palette.page.color.toArgb())
        assertEquals(0xFF66BB6A.toInt(), Palette.green.color.toArgb())
        assertEquals(0xFFFF8A65.toInt(), Palette.tileColors[0].bg.color.toArgb())
    }

    // CSS 180 deg runs top -> bottom; 90 deg left -> right. Getting the sign of
    // the y term wrong flips every screen's wash upside down and is invisible in
    // a hex assertion.
    @Test
    fun cssAnglesMapToTheRightUnitFractions() {
        val downStart = HexGradient.startFraction(180f)
        val downEnd = HexGradient.endFraction(180f)
        assertTrue(kotlin.math.abs(downStart.x - 0.5f) < 1e-6f && kotlin.math.abs(downStart.y) < 1e-6f)
        assertTrue(kotlin.math.abs(downEnd.x - 0.5f) < 1e-6f && kotlin.math.abs(downEnd.y - 1f) < 1e-6f)

        val rightStart = HexGradient.startFraction(90f)
        val rightEnd = HexGradient.endFraction(90f)
        assertTrue(kotlin.math.abs(rightStart.x) < 1e-6f && kotlin.math.abs(rightStart.y - 0.5f) < 1e-6f)
        assertTrue(kotlin.math.abs(rightEnd.x - 1f) < 1e-6f && kotlin.math.abs(rightEnd.y - 0.5f) < 1e-6f)

        // 135 deg is down-and-right: the end fraction is past the centre on both
        // axes. Only the signs matter; CSS's corner-fitting is documented as a
        // deviation in Palette.kt.
        val diagonal = HexGradient.endFraction(135f)
        assertTrue(diagonal.x > 0.5f)
        assertTrue(diagonal.y > 0.5f)
    }
}

// Rec. 709 relative luminance of a `#RRGGBB` string, 0..1, with the sRGB
// transfer function applied -- the WCAG definition, not the naive linear one.
private fun luminance(hex: String): Double {
    val c = SvgColor.hex(hex)
    fun lin(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** WCAG contrast ratio between two opaque hexes, 1..21. */
private fun contrast(a: String, b: String): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}
