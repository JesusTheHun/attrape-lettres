package fr.dappit.attrapelettres.art.svg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Colours, gradients and the objectBoundingBox stretch, ported from the paint
// half of the iOS `SVGCanvasTests.swift`.
//
// The one that matters for how the game LOOKS is the bounding-box mapping: no
// gradient in the app declares `gradientUnits`, so every one of them uses SVG's
// default and is stretched by the shape's own aspect ratio. Get that wrong and
// the ground glow under a mascot silently becomes a circle.

class SvgColorTest {

    @Test
    fun `hex parsing covers every form the sources use`() {
        val opaqueWhite = SvgColor(1f, 1f, 1f, 1f)
        assertEquals(opaqueWhite, SvgColor.hex("#FFFFFF"))
        assertEquals(opaqueWhite, SvgColor.hex("FFFFFF"))
        assertEquals(opaqueWhite, SvgColor.hex("#fff"))
        assertEquals(opaqueWhite, SvgColor.hex("  #FFFFFF  "))
        assertEquals(SvgColor(1f, 246f / 255f, 238f / 255f, 1f), SvgColor.hex("#FFF6EE"))
        assertEquals(SvgColor(0f, 0f, 0f, 0f), SvgColor.hex("#00000000"))
        // #RGBA — the fourth digit doubles like the rest.
        assertEquals(SvgColor(1f, 1f, 1f, 0f), SvgColor.hex("#fff0"))
    }

    @Test
    fun `garbage hex yields transparent rather than throwing`() {
        assertEquals(SvgColor.Transparent, SvgColor.hex("not-a-colour"))
        assertEquals(SvgColor.Transparent, SvgColor.hex(""))
        assertEquals(SvgColor.Transparent, SvgColor.hex("#12345"))
    }

    @Test
    fun `a right-length string of wrong digits reads as opaque black`() {
        // Documented rather than desired: a component that fails to parse reads
        // 0 and the alpha stays 1, which is what the iOS port does. Both are
        // unreachable for authored constants — a wrong colour string is caught
        // by looking at the screen, and a nonsense one by this test existing.
        assertEquals(SvgColor(0f, 0f, 0f, 1f), SvgColor.hex("#gggggg"))
    }

    @Test
    fun `opacity multiplies into the alpha rather than replacing it`() {
        val half = SvgColor.hex("#FFFFFF80")
        val quarter = half.withOpacity(0.5)
        assertEquals(half.alpha * 0.5f, quarter.alpha, 1e-6f)
        assertEquals(half.red, quarter.red, 1e-6f)
    }
}

class SvgPaintDefaultsTest {

    @Test
    fun `gradients default to objectBoundingBox, because every gradient in the app does`() {
        // No source declares gradientUnits, so SVG's default applies. If this
        // default ever flips, the ground glow silently becomes a circle.
        assertEquals(
            SvgUnits.OBJECT_BOUNDING_BOX,
            SvgPaint.linear(listOf(SvgStop.hex("#fff", 0f))).units,
        )
        assertEquals(
            SvgUnits.OBJECT_BOUNDING_BOX,
            SvgPaint.radial(listOf(SvgStop.hex("#fff", 0f))).units,
        )
    }

    @Test
    fun `SVG's own gradient coordinate defaults are used`() {
        val linear = assertIs<SvgPaintKind.Linear>(
            SvgPaint.linear(listOf(SvgStop.hex("#fff", 0f))).kind,
        )
        assertEquals(SvgPoint(0f, 0f), linear.start)
        assertEquals(SvgPoint(1f, 0f), linear.end)

        val radial = assertIs<SvgPaintKind.Radial>(
            SvgPaint.radial(listOf(SvgStop.hex("#fff", 0f))).kind,
        )
        assertEquals(SvgPoint(0.5f, 0.5f), radial.center)
        assertEquals(0.5f, radial.radius)
    }

    @Test
    fun `fill none records a node whose paint draws nothing`() {
        val c = SvgCanvas()
        c.fill(SvgShapes.rect(0.0, 0.0, 1.0, 1.0), SvgPaint.None)
        // The node IS recorded — the canvas does not know about paint kinds —
        // but the renderer returns early on it.
        val fill = assertIs<SvgDrawNode.Fill>(c.nodes[0])
        assertEquals(SvgPaintKind.None, fill.paint.kind)
    }

    @Test
    fun `stop opacity multiplies into the stop colour`() {
        val stop = SvgStop.hex("#123456", 0.55f, opacity = 0.22)
        assertEquals(0.55f, stop.offset)
        assertEquals(SvgColor.hex("#123456").withOpacity(0.22), stop.color)
    }

    @Test
    fun `fill opacity rides on the paint, not on the colour`() {
        // `fill-opacity` and the colour's own alpha are two different things in
        // SVG, and both survive to the renderer, which multiplies them.
        val paint = SvgPaint.hex("#FFFFFF", opacity = 0.4)
        assertEquals(0.4, paint.opacity, 1e-12)
        assertEquals(1f, assertIs<SvgPaintKind.Solid>(paint.kind).color.alpha)
    }
}

class ObjectBoundingBoxTest {

    @Test
    fun `the unit square maps onto the box`() {
        val t = objectBoundingBoxTransform(SvgRect(10.0, 40.0, 80.0, 20.0))
        assertEquals(10.0, t.mapX(0.0, 0.0), 1e-9)
        assertEquals(40.0, t.mapY(0.0, 0.0), 1e-9)
        assertEquals(90.0, t.mapX(1.0, 1.0), 1e-9)
        assertEquals(60.0, t.mapY(1.0, 1.0), 1e-9)
        assertEquals(50.0, t.mapX(0.5, 0.5), 1e-9)
        assertEquals(50.0, t.mapY(0.5, 0.5), 1e-9)
    }

    @Test
    fun `the mapping is anisotropic, which is the whole point`() {
        // On an 80x20 box, a circle of radius 0.5 in unit space comes out 80
        // wide and 20 tall. A gradient that ignored this would fade out four
        // times too early on the long axis — the ground glow bug.
        val box = SvgRect(10.0, 40.0, 80.0, 20.0)
        val t = objectBoundingBoxTransform(box)
        val halfWidth = t.mapX(1.0, 0.5) - t.mapX(0.0, 0.5)
        val halfHeight = t.mapY(0.5, 1.0) - t.mapY(0.5, 0.0)
        assertEquals(80.0, halfWidth, 1e-9)
        assertEquals(20.0, halfHeight, 1e-9)
        assertTrue(halfWidth != halfHeight)
    }

    @Test
    fun `a linear gradient's endpoints resolve through the box`() {
        // The unicorn horn's shine is x1=0 y1=1 to x2=1 y2=0 in bounding-box
        // units — a diagonal whose SLOPE depends on the horn's aspect ratio, and
        // mapping the two endpoints reproduces that exactly.
        val box = SvgRect(0.0, 0.0, 4.0, 20.0)
        val kind = assertIs<SvgPaintKind.Linear>(
            SvgPaint.linear(
                listOf(SvgStop.hex("#fff", 0f), SvgStop.hex("#000", 1f)),
                start = SvgPoint(0f, 1f),
                end = SvgPoint(1f, 0f),
            ).kind,
        )
        val (start, end) = kind.endpointsIn(box, SvgUnits.OBJECT_BOUNDING_BOX)
        assertEquals(SvgPoint(0f, 20f), start)
        assertEquals(SvgPoint(4f, 0f), end)
    }

    @Test
    fun `userSpaceOnUse leaves the endpoints exactly as authored`() {
        val kind = SvgPaintKind.Linear(
            stops = listOf(SvgStop.hex("#fff", 0f)),
            start = SvgPoint(3f, 7f),
            end = SvgPoint(11f, 13f),
        )
        val (start, end) = kind.endpointsIn(
            SvgRect(0.0, 0.0, 100.0, 100.0),
            SvgUnits.USER_SPACE_ON_USE,
        )
        assertEquals(SvgPoint(3f, 7f), start)
        assertEquals(SvgPoint(11f, 13f), end)
    }

    @Test
    fun `the box a gradient resolves against is the path's own bounding box`() {
        // The renderer asks `SvgPath.bounds` for it, so the number a host test
        // reasons about is the number the device draws with. An ellipse drawn at
        // (50,20) with radii 30x10 has the box the gradient stretches over.
        val box = SvgPath.bounds(SvgShapes.ellipse(50.0, 20.0, 30.0, 10.0))
        assertEquals(SvgRect(20.0, 10.0, 60.0, 20.0), box?.rounded())
    }
}

/** Round to a sane number of digits so a bounds assertion can be written literally. */
private fun SvgRect.rounded(): SvgRect {
    fun r(v: Double) = kotlin.math.round(v * 1e6) / 1e6
    return SvgRect(r(x), r(y), r(width), r(height))
}
