package fr.dappit.attrapelettres.art.svg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Geometry tests for the drawing substrate, ported from the iOS
// `SVGCanvasTests.swift`. These assert coordinates computed by hand from the SVG
// spec, never values read back out of the implementation — a test that derives
// its expectation from the code under test proves only that the code is
// consistent with itself.
//
// None of this needs an emulator, and that is the design: the draw list is
// plain data, so the transforms, the clip stack and the node structure are
// assertable on a laptop. What is NOT assertable here is whether Compose
// composes matrices in the same direction, which is why `SvgTransform.toMatrix`
// carries the column-major layout in prose next to the code.

private fun square(size: Double = 1.0) = SvgShapes.rect(0.0, 0.0, size, size)

class SvgCanvasTransformTest {

    @Test
    fun `a fresh canvas is the identity`() {
        val c = SvgCanvas()
        assertEquals(SvgTransform.Identity, c.currentTransform)
        assertTrue(c.currentClips.isEmpty())
    }

    @Test
    fun `save and restore round-trip the transform and the clip stack`() {
        val c = SvgCanvas()
        c.translate(10.0, 20.0)
        val saved = c.currentTransform
        c.save()
        c.scale(3.0)
        c.clip(square())
        assertNotEquals(saved, c.currentTransform)
        assertEquals(1, c.currentClips.size)
        c.restore()
        assertEquals(saved, c.currentTransform)
        assertTrue(c.currentClips.isEmpty())
    }

    @Test
    fun `transforms compose left-to-right, as an SVG transform list does`() {
        // transform="translate(10 0) scale(2)" maps the point (1,0) to (12,0):
        // scale applies to the geometry first, translate second.
        val c = SvgCanvas()
        c.translate(10.0, 0.0)
        c.scale(2.0)
        assertEquals(12.0, c.currentTransform.mapX(1.0, 0.0), 1e-12)
        assertEquals(0.0, c.currentTransform.mapY(1.0, 0.0), 1e-12)
    }

    @Test
    fun `an unanchored rotation turns about the origin, clockwise on screen`() {
        val c = SvgCanvas()
        c.rotate(90.0)
        // y grows downward, so +90 degrees takes (1,0) to (0,1).
        assertEquals(0.0, c.currentTransform.mapX(1.0, 0.0), 1e-12)
        assertEquals(1.0, c.currentTransform.mapY(1.0, 0.0), 1e-12)
    }

    @Test
    fun `rotate about an anchor turns about the anchor — the silent bug D15 exists to kill`() {
        // SVG: rotate(90 50 50) leaves (50,50) fixed and takes (60,50) to (50,60).
        val c = SvgCanvas()
        c.rotate(90.0, 50.0, 50.0)
        val t = c.currentTransform
        assertEquals(50.0, t.mapX(50.0, 50.0), 1e-9)
        assertEquals(50.0, t.mapY(50.0, 50.0), 1e-9)
        assertEquals(50.0, t.mapX(60.0, 50.0), 1e-9)
        assertEquals(60.0, t.mapY(60.0, 50.0), 1e-9)
        // And it is NOT the same as the unanchored rotation, which is the bug.
        val plain = SvgCanvas()
        plain.rotate(90.0)
        assertNotEquals(plain.currentTransform, t)
    }

    @Test
    fun `a negative scale mirrors`() {
        val c = SvgCanvas()
        c.scale(-1.0, 1.0)
        assertEquals(-5.0, c.currentTransform.mapX(5.0, 7.0), 1e-12)
        assertEquals(7.0, c.currentTransform.mapY(5.0, 7.0), 1e-12)
    }

    @Test
    fun `viewBox maps like preserveAspectRatio xMidYMid meet`() {
        // A 100x100 box into a 200x400 rect: uniform scale 2, centred vertically.
        val t = SvgCanvas.viewBoxTransform(
            SvgRect(0.0, 0.0, 100.0, 100.0),
            SvgRect(0.0, 0.0, 200.0, 400.0),
        )
        assertEquals(0.0, t.mapX(0.0, 0.0), 1e-9)
        assertEquals(100.0, t.mapY(0.0, 0.0), 1e-9)
        assertEquals(200.0, t.mapX(100.0, 100.0), 1e-9)
        assertEquals(300.0, t.mapY(100.0, 100.0), 1e-9)
        // Uniform: a square stays square.
        val dx = t.mapX(10.0, 10.0) - t.mapX(0.0, 0.0)
        val dy = t.mapY(10.0, 10.0) - t.mapY(0.0, 0.0)
        assertEquals(dx, dy, 1e-9)
    }

    @Test
    fun `the viewBox never clips — overflow survives`() {
        // Top-stage wings and haloes deliberately leave the 100-unit box.
        val c = SvgCanvas(SvgRect(0.0, 0.0, 100.0, 100.0), SvgRect(0.0, 0.0, 100.0, 100.0))
        assertTrue(c.currentClips.isEmpty())
        assertEquals(SvgTransform.Identity, c.currentTransform)
    }

    @Test
    fun `a degenerate viewBox falls back to the identity rather than dividing by zero`() {
        val t = SvgCanvas.viewBoxTransform(SvgRect.Zero, SvgRect(0.0, 0.0, 50.0, 50.0))
        assertEquals(SvgTransform.Identity, t)
    }

    @Test
    fun `clips are recorded in root space so replay order is the only ordering`() {
        val c = SvgCanvas()
        c.translate(100.0, 0.0)
        c.clip(SvgShapes.rect(0.0, 0.0, 10.0, 10.0))
        val clip = assertNotNull(c.currentClips.firstOrNull())
        val box = assertNotNull(SvgPath.bounds(clip))
        assertEquals(100.0, box.minX, 1e-9)
        assertEquals(110.0, box.maxX, 1e-9)
    }

    @Test
    fun `then reads as this first, then the outer transform`() {
        // The whole CTM stack is built on this one operation, so it is pinned
        // directly: scaling by 2 and THEN translating by 10 takes 1 to 12; doing
        // it the other way round takes 1 to 22.
        val scaleThenTranslate = SvgTransform.scale(2.0, 2.0).then(SvgTransform.translate(10.0, 0.0))
        val translateThenScale = SvgTransform.translate(10.0, 0.0).then(SvgTransform.scale(2.0, 2.0))
        assertEquals(12.0, scaleThenTranslate.mapX(1.0, 0.0), 1e-12)
        assertEquals(22.0, translateThenScale.mapX(1.0, 0.0), 1e-12)
    }
}

class SvgCanvasDrawListTest {

    @Test
    fun `fills record in painter's order with the CTM of the moment`() {
        val c = SvgCanvas()
        c.fill(square(), SvgPaint.hex("#111111"))
        c.translate(5.0, 5.0)
        c.fill(square(), SvgPaint.hex("#222222"))
        assertEquals(2, c.nodes.size)
        val first = assertIs<SvgDrawNode.Fill>(c.nodes[0])
        val second = assertIs<SvgDrawNode.Fill>(c.nodes[1])
        assertEquals(SvgTransform.Identity, first.transform)
        assertEquals(SvgTransform.translate(5.0, 5.0), second.transform)
    }

    @Test
    fun `a zero-width stroke records nothing`() {
        // SVG treats stroke-width="0" as no stroke; a backend would draw a
        // hairline. The dragon's spade tail passes `strokeWidth={edge ? 1 : 0}`
        // and depends on this.
        val c = SvgCanvas()
        c.stroke(square(), SvgPaint.hex("#000000"), SvgStrokeStyle(lineWidth = 0.0))
        assertTrue(c.nodes.isEmpty())
        c.stroke(square(), SvgPaint.hex("#000000"), SvgStrokeStyle(lineWidth = -1.0))
        assertTrue(c.nodes.isEmpty())
        c.stroke(square(), SvgPaint.hex("#000000"), SvgStrokeStyle(lineWidth = 1.0))
        assertEquals(1, c.nodes.size)
    }

    @Test
    fun `a group nests its children and inherits the transform`() {
        val c = SvgCanvas()
        c.translate(3.0, 4.0)
        c.group(opacity = 0.5) { g -> g.fill(square(), SvgPaint.hex("#fff")) }
        assertEquals(1, c.nodes.size)
        val group = assertIs<SvgDrawNode.Group>(c.nodes[0])
        assertEquals(0.5, group.opacity, 1e-12)
        assertEquals(1, group.children.size)
        val fill = assertIs<SvgDrawNode.Fill>(group.children[0])
        assertEquals(SvgTransform.translate(3.0, 4.0), fill.transform)
    }

    @Test
    fun `a group does not leak its own transform back to the parent`() {
        val c = SvgCanvas()
        c.group { g ->
            g.scale(9.0)
            g.fill(square(), SvgPaint.hex("#fff"))
        }
        assertEquals(SvgTransform.Identity, c.currentTransform)
    }

    @Test
    fun `a mask keeps matte and content separate, both inheriting the state`() {
        val c = SvgCanvas()
        c.scale(2.0)
        c.mask(
            matte = { m -> m.fill(square(), SvgPaint.hex("#fff")) },
            content = { body ->
                body.fill(square(2.0), SvgPaint.hex("#f00"))
                body.fill(square(3.0), SvgPaint.hex("#0f0"))
            },
        )
        val mask = assertIs<SvgDrawNode.Mask>(c.nodes[0])
        assertEquals(1, mask.matte.size)
        assertEquals(2, mask.content.size)
        val matteFill = assertIs<SvgDrawNode.Fill>(mask.matte[0])
        assertEquals(SvgTransform.scale(2.0, 2.0), matteFill.transform)
    }

    @Test
    fun `restore does not trap on an unbalanced stack`() {
        // Invariant 3 applied to drawing: nothing about a malformed rig may take
        // the app down in front of a child. An unbalanced restore leaves the
        // state alone instead.
        val c = SvgCanvas()
        c.translate(1.0, 1.0)
        c.restore()
        c.restore()
        assertEquals(SvgTransform.translate(1.0, 1.0), c.currentTransform)
        assertEquals(0, c.saveDepth)
    }

    @Test
    fun `a clip is scoped by save and restore, and clips accumulate`() {
        val c = SvgCanvas()
        c.clip(SvgShapes.rect(0.0, 0.0, 50.0, 50.0))
        c.save()
        c.clip(SvgShapes.rect(0.0, 0.0, 10.0, 10.0))
        c.fill(square(), SvgPaint.hex("#fff"))
        c.restore()
        c.fill(square(), SvgPaint.hex("#fff"))
        val inner = assertIs<SvgDrawNode.Fill>(c.nodes[0])
        val outer = assertIs<SvgDrawNode.Fill>(c.nodes[1])
        assertEquals(2, inner.clips.size)
        assertEquals(1, outer.clips.size)
    }

    @Test
    fun `the d-string overloads parse into the same commands the list overloads take`() {
        val c = SvgCanvas()
        c.fill("M 0 0 L 10 0 L 10 10 Z", SvgPaint.hex("#fff"))
        val fill = assertIs<SvgDrawNode.Fill>(c.nodes[0])
        assertEquals(SvgPath.parseFlattened("M 0 0 L 10 0 L 10 10 Z"), fill.path)
    }

    @Test
    fun `a malformed d string records an empty path instead of throwing`() {
        val c = SvgCanvas()
        c.fill("L 10 10", SvgPaint.hex("#fff"))
        val fill = assertIs<SvgDrawNode.Fill>(c.nodes[0])
        assertTrue(fill.path.isEmpty())
    }
}

class SvgShapesTest {

    @Test
    fun `an ellipse spans exactly its radii`() {
        val box = assertNotNull(SvgPath.bounds(SvgShapes.ellipse(50.0, 20.0, 30.0, 10.0)))
        assertEquals(20.0, box.minX, 1e-4)
        assertEquals(80.0, box.maxX, 1e-4)
        assertEquals(10.0, box.minY, 1e-4)
        assertEquals(30.0, box.maxY, 1e-4)
    }

    @Test
    fun `an ellipse is four cubics and a close, in the four-arc approximation`() {
        val commands = SvgShapes.ellipse(0.0, 0.0, 10.0, 10.0)
        assertEquals(4, commands.count { it is SvgPathCommand.CubicTo })
        assertEquals(SvgPathCommand.Close, commands.last())
        // Every on-path point of a circle is exactly r from the centre. The
        // control points are NOT — they sit KAPPA of the way along the tangent —
        // so this is what says the approximation was built the right way round.
        for (command in commands.filterIsInstance<SvgPathCommand.CubicTo>()) {
            val r = kotlin.math.sqrt(
                command.x.toDouble() * command.x + command.y.toDouble() * command.y,
            )
            assertEquals(10.0, r, 1e-4)
        }
    }

    @Test
    fun `a circle is an ellipse with equal radii`() {
        assertEquals(SvgShapes.ellipse(1.0, 2.0, 3.0, 3.0), SvgShapes.circle(1.0, 2.0, 3.0))
    }

    @Test
    fun `a rect is four corners wound clockwise and closed`() {
        assertEquals(
            listOf(
                SvgPathCommand.MoveTo(1f, 2f),
                SvgPathCommand.LineTo(11f, 2f),
                SvgPathCommand.LineTo(11f, 7f),
                SvgPathCommand.LineTo(1f, 7f),
                SvgPathCommand.Close,
            ),
            SvgShapes.rect(1.0, 2.0, 10.0, 5.0),
        )
    }

    @Test
    fun `a rounded rect stays inside its rect and clamps an over-large radius`() {
        val box = assertNotNull(SvgPath.bounds(SvgShapes.roundedRect(0.0, 0.0, 20.0, 10.0, 3.0)))
        assertEquals(0.0, box.minX, 1e-6)
        assertEquals(20.0, box.maxX, 1e-6)
        assertEquals(0.0, box.minY, 1e-6)
        assertEquals(10.0, box.maxY, 1e-6)
        // A radius past half the short side would turn the rect inside out; SVG
        // clamps, and so does this. At exactly half, the short side is a full
        // half-circle and the box is unchanged.
        val clamped = assertNotNull(SvgPath.bounds(SvgShapes.roundedRect(0.0, 0.0, 20.0, 10.0, 99.0)))
        assertEquals(0.0, clamped.minX, 1e-6)
        assertEquals(20.0, clamped.maxX, 1e-6)
        assertEquals(10.0, clamped.height, 1e-6)
    }

    @Test
    fun `a zero radius rounded rect is the plain rect`() {
        assertEquals(
            SvgShapes.rect(0.0, 0.0, 4.0, 4.0),
            SvgShapes.roundedRect(0.0, 0.0, 4.0, 4.0, 0.0),
        )
    }

    @Test
    fun `a line is an open two-command path`() {
        assertEquals(
            listOf(SvgPathCommand.MoveTo(0f, 1f), SvgPathCommand.LineTo(2f, 3f)),
            SvgShapes.line(0.0, 1.0, 2.0, 3.0),
        )
    }

    @Test
    fun `the kappa constant is the quarter-circle one`() {
        // 4 / 3 * tan(PI / 8). Written out as a literal in the source so it can
        // be read at a glance; checked here so it cannot drift into a typo.
        assertTrue(
            abs(SvgShapes.KAPPA - 4.0 / 3.0 * kotlin.math.tan(kotlin.math.PI / 8)) < 1e-15,
        )
    }
}
