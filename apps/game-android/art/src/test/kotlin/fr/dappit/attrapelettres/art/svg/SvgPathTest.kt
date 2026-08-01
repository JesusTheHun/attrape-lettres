package fr.dappit.attrapelettres.art.svg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Exact-geometry tests for the `d` parser, ported from the iOS
// `SVGPathTests.swift`. These assert numbers, not "it didn't crash" — the whole
// point of parsing the strings instead of retyping the geometry is that the
// result is PROVABLY the same as the web app's, and a parser that silently
// dropped a control point would still produce a plausible-looking dragon.
//
// Everything here runs on a bare JVM: the parser answers plain data, so no
// Android runtime and no Compose `Path` is involved.

private fun parse(d: String): List<SvgPathCommand> = SvgPath.parseFlattened(d)

private fun move(x: Float, y: Float) = SvgPathCommand.MoveTo(x, y)

private fun line(x: Float, y: Float) = SvgPathCommand.LineTo(x, y)

private fun quad(x1: Float, y1: Float, x: Float, y: Float) = SvgPathCommand.QuadTo(x1, y1, x, y)

private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
    SvgPathCommand.CubicTo(x1, y1, x2, y2, x, y)

class SvgPathGrammarTest {

    @Test
    fun `absolute moveto and lineto`() {
        assertEquals(listOf(move(10f, 20f), line(30f, 40f)), parse("M 10 20 L 30 40"))
    }

    @Test
    fun `relative commands accumulate from the current point`() {
        assertEquals(
            listOf(move(10f, 10f), line(15f, 15f), line(12f, 15f)),
            parse("M 10 10 l 5 5 l -3 0"),
        )
    }

    @Test
    fun `a repeated coordinate pair after moveto is an implicit lineto`() {
        assertEquals(
            listOf(move(0f, 0f), line(1f, 1f), line(2f, 2f)),
            parse("M 0 0 1 1 2 2"),
        )
    }

    @Test
    fun `relative moveto makes the implicit command a relative lineto`() {
        assertEquals(
            listOf(move(10f, 10f), line(15f, 10f), line(20f, 10f)),
            parse("m 10 10 5 0 5 0"),
        )
    }

    @Test
    fun `a command letter is not repeated for its own arguments`() {
        assertEquals(
            listOf(move(0f, 0f), line(1f, 1f), line(2f, 2f), line(3f, 3f)),
            parse("M0 0 L1 1 2 2 3 3"),
        )
    }

    @Test
    fun `horizontal and vertical linetos keep the other axis`() {
        assertEquals(
            listOf(
                move(5f, 5f),
                line(20f, 5f),
                line(20f, 30f),
                line(15f, 30f),
                line(15f, 20f),
            ),
            parse("M 5 5 H 20 V 30 h -5 v -10"),
        )
    }

    @Test
    fun `quadratic and cubic curves keep every control point`() {
        assertEquals(
            listOf(
                move(0f, 0f),
                quad(10f, -10f, 20f, 0f),
                cubic(25f, 5f, 35f, 5f, 40f, 0f),
            ),
            parse("M0 0 Q 10 -10 20 0 C 25 5 35 5 40 0"),
        )
    }

    @Test
    fun `S reflects the previous cubic's second control point`() {
        // Previous control2 is (20,10) about the current point (30,0) -> (40,-10).
        val parsed = parse("M0 0 C 10 10 20 10 30 0 S 50 -10 60 0")
        assertEquals(cubic(40f, -10f, 50f, -10f, 60f, 0f), parsed[2])
    }

    @Test
    fun `S with no preceding cubic uses the current point as its first control`() {
        val parsed = parse("M 10 10 S 20 20 30 10")
        assertEquals(cubic(10f, 10f, 20f, 20f, 30f, 10f), parsed[1])
    }

    @Test
    fun `T reflects the previous quadratic's control point`() {
        // Reflection of (10,10) about (20,0) is (30,-10).
        val parsed = parse("M0 0 Q 10 10 20 0 T 40 0")
        assertEquals(quad(30f, -10f, 40f, 0f), parsed[2])
    }

    @Test
    fun `closepath returns the current point to the subpath start`() {
        // The lineto after Z must start from (0,0), not from (10,10).
        val parsed = parse("M 0 0 L 10 0 L 10 10 Z l 5 5")
        assertEquals(line(5f, 5f), parsed.last())
    }

    @Test
    fun `numbers separate on a sign or a second decimal point with no delimiter`() {
        assertEquals(listOf(move(0f, 0f), line(10f, -5f)), parse("M0 0L10-5"))
        assertEquals(listOf(move(0.5f, 0.5f), line(1.5f, 0.5f)), parse("M.5.5L1.5.5"))
    }

    @Test
    fun `exponent notation parses, and a bare trailing e does not swallow a command`() {
        assertEquals(listOf(move(100f, -0.15f)), parse("M 1e2 -1.5e-1"))
    }

    @Test
    fun `commas and newlines are separators`() {
        assertEquals(listOf(move(0f, 0f), line(10f, 10f)), parse("M0,0\n  L10,10"))
    }

    @Test
    fun `malformed input throws rather than silently truncating`() {
        assertEquals(
            SvgPathErrorKind.MISSING_INITIAL_MOVE,
            assertFailsWith<SvgPathException> { SvgPath.parse("L 10 10") }.kind,
        )
        assertEquals(
            SvgPathErrorKind.UNKNOWN_COMMAND,
            assertFailsWith<SvgPathException> { SvgPath.parse("M 0 0 X 1 1") }.kind,
        )
        assertEquals(
            SvgPathErrorKind.EXPECTED_NUMBER,
            assertFailsWith<SvgPathException> { SvgPath.parse("M 0 0 L 10") }.kind,
        )
        assertEquals(
            SvgPathErrorKind.EXPECTED_FLAG,
            assertFailsWith<SvgPathException> { SvgPath.parse("M 0 0 A 5 5 0 2 1 10 10") }.kind,
        )
        // A number where a command is expected, with nothing to repeat.
        assertEquals(
            SvgPathErrorKind.UNEXPECTED_CHARACTER,
            assertFailsWith<SvgPathException> { SvgPath.parse("5 5") }.kind,
        )
        // Z takes no arguments, so its implicit repeat is not a thing.
        assertEquals(
            SvgPathErrorKind.UNEXPECTED_CHARACTER,
            assertFailsWith<SvgPathException> { SvgPath.parse("M 0 0 Z 5 5") }.kind,
        )
    }

    @Test
    fun `a malformed string draws nothing rather than throwing at the drawing entry point`() {
        // Invariant 3 applied to the drawing layer: a bad `d` string is a bug a
        // test catches, never a screen a child loses.
        assertEquals(emptyList<SvgPathCommand>(), SvgPath.parseOrEmpty("L 10 10"))
        assertEquals(listOf(move(1f, 2f)), SvgPath.parseOrEmpty("M 1 2"))
    }

    @Test
    fun `parse keeps arcs in their authored form and flatten is what turns them into curves`() {
        // The two-step shape is the point: a test about flags, radii or rotation
        // asserts on the SEVEN NUMBERS the author wrote, and only the renderer
        // needs them as curves.
        val raw = SvgPath.parse("M 0 0 A 30 15 45 1 0 40 20")
        assertEquals(
            SvgPathCommand.ArcTo(
                rx = 30f,
                ry = 15f,
                rotationDegrees = 45f,
                largeArc = true,
                sweep = false,
                x = 40f,
                y = 20f,
            ),
            raw[1],
        )
        assertTrue(SvgPath.flatten(raw).none { it is SvgPathCommand.ArcTo })
        assertTrue(SvgPath.flatten(raw).drop(1).all { it is SvgPathCommand.CubicTo })
    }

    @Test
    fun `parsing is deterministic`() {
        val d = "M 12 8 q 4 -6 9 0 t 8 3 A 6 6 0 0 1 30 20 Z"
        assertEquals(parse(d), parse(d))
    }
}

class SvgArcTest {

    private fun endpointOf(d: String): SvgPoint {
        val last = assertIs<SvgPathCommand.CubicTo>(parse(d).last(), "arc did not emit curves for $d")
        return SvgPoint(last.x, last.y)
    }

    private fun boundsOf(d: String): SvgRect = assertNotNull(SvgPath.bounds(SvgPath.parse(d)))

    @Test
    fun `an arc terminates exactly on its stated endpoint`() {
        for (d in listOf(
            "M 0 0 A 50 50 0 0 1 100 0",
            "M 0 0 A 50 50 0 1 1 100 0",
            "M 0 0 A 50 50 0 0 0 100 0",
            "M 0 0 A 50 50 0 1 0 100 0",
        )) {
            val end = endpointOf(d)
            // The tolerance is a `Float` one, not the iOS suite's 1e-6: the
            // command list stores what will be drawn, and a float carries about
            // seven digits. 1e-3 of a unit is a thousandth of a pixel here.
            assertTrue(abs(end.x - 100f) < 1e-3f, "$d ended at $end")
            assertTrue(abs(end.y) < 1e-3f, "$d ended at $end")
        }
        val tilted = endpointOf("M 10 20 A 30 15 45 1 1 70 60")
        assertTrue(abs(tilted.x - 70f) < 1e-3f, "ended at $tilted")
        assertTrue(abs(tilted.y - 60f) < 1e-3f, "ended at $tilted")
    }

    @Test
    fun `two half arcs make a circle of the right bounding box`() {
        val box = boundsOf("M 50 0 A 50 50 0 1 0 -50 0 A 50 50 0 1 0 50 0 Z")
        assertTrue(abs(box.minX - -50) < 0.01, "$box")
        assertTrue(abs(box.maxX - 50) < 0.01, "$box")
        assertTrue(abs(box.minY - -50) < 0.01, "$box")
        assertTrue(abs(box.maxY - 50) < 0.01, "$box")
    }

    @Test
    fun `the sweep flag chooses which side the arc bulges to`() {
        val sweepOne = boundsOf("M 0 0 A 50 50 0 0 1 100 0")
        val sweepZero = boundsOf("M 0 0 A 50 50 0 0 0 100 0")
        // One bulges to positive y, the other to negative; they cannot agree.
        assertTrue(sweepOne.midY * sweepZero.midY < 0, "$sweepOne vs $sweepZero")
    }

    @Test
    fun `the large-arc flag chooses the longer sweep`() {
        val small = boundsOf("M 0 0 A 50 50 0 0 1 50 50")
        val large = boundsOf("M 0 0 A 50 50 0 1 1 50 50")
        assertTrue(large.width > small.width, "$large vs $small")
        assertTrue(large.height > small.height, "$large vs $small")
    }

    @Test
    fun `radii too small to span the chord are grown, per F 6 6 2`() {
        // Endpoints 100 apart with r=10: the spec grows r to 50 rather than
        // clamping or failing. The result is the exact semicircle.
        val box = boundsOf("M 0 0 A 10 10 0 0 1 100 0")
        assertTrue(abs(box.width - 100) < 0.01, "$box")
        assertTrue(abs(box.height - 50) < 0.01, "$box")
    }

    @Test
    fun `a zero radius degenerates to a straight line`() {
        assertEquals(listOf(move(0f, 0f), line(100f, 0f)), parse("M 0 0 A 0 50 0 0 1 100 0"))
    }

    @Test
    fun `coincident endpoints omit the arc entirely`() {
        assertEquals(listOf(move(10f, 10f)), parse("M 10 10 A 50 50 0 1 1 10 10"))
    }

    @Test
    fun `x-axis rotation tilts the ellipse`() {
        val upright = boundsOf("M 0 0 A 50 20 0 1 1 60 0")
        val tilted = boundsOf("M 0 0 A 50 20 45 1 1 60 0")
        assertTrue(abs(upright.height - tilted.height) > 1, "$upright vs $tilted")
    }

    @Test
    fun `arc flags may be packed with no separator`() {
        // `011 100` reads as largeArc=0, sweep=1, then the endpoint pair (1,100).
        // Flags are single characters, so nothing separates them from the x that
        // follows — a tokeniser that grabbed a whole number here would consume
        // 011 and then be one argument short for the rest of the path.
        val packed = parse("M0 0a50 50 0 011 100")
        val spaced = parse("M0 0 a 50 50 0 0 1 1 100")
        assertEquals(spaced, packed)
        assertEquals(3, packed.size)
    }

    @Test
    fun `an arc is split into at most ninety degrees per cubic`() {
        // The error bound quoted in the file only holds for quarter sweeps, so
        // the segment count is part of the contract, not an implementation
        // detail: a full circle is four cubics, a semicircle two.
        assertEquals(2, parse("M 0 0 A 50 50 0 0 1 100 0").count { it is SvgPathCommand.CubicTo })
        assertEquals(
            4,
            parse("M 50 0 A 50 50 0 1 1 49.999 0").count { it is SvgPathCommand.CubicTo },
        )
    }

    @Test
    fun `every point of an arc lies on its ellipse`() {
        // The endpoint test only pins the last point. This one says the curve in
        // between is the right ellipse and not, say, a mirrored one that happens
        // to land in the same place: every on-path point of a circular arc is
        // exactly r from the centre.
        val commands = parse("M 100 50 A 50 50 0 1 1 0 50")
        for (command in commands.filterIsInstance<SvgPathCommand.CubicTo>()) {
            val dx = command.x - 50.0
            val dy = command.y - 50.0
            assertTrue(
                abs(kotlin.math.sqrt(dx * dx + dy * dy) - 50.0) < 1e-3,
                "point (${command.x}, ${command.y}) is not on the circle",
            )
        }
    }
}

class SvgPathBoundsTest {

    @Test
    fun `bounds are the hull of every on-path and control point`() {
        // Deliberately the CONTROL-POINT hull, matching what
        // android.graphics.Path.computeBounds reports: it never underestimates,
        // and it is the box the objectBoundingBox gradient mapping uses, so the
        // gradient a host test reasons about is the gradient the device draws.
        val box = assertNotNull(SvgPath.bounds(parse("M 0 0 Q 10 -20 20 0")))
        assertEquals(SvgRect(0.0, -20.0, 20.0, 20.0), box)
    }

    @Test
    fun `bounds of an empty command list are null`() {
        assertEquals(null, SvgPath.bounds(emptyList()))
        assertEquals(null, SvgPath.bounds(listOf(SvgPathCommand.Close)))
    }

    @Test
    fun `a single moveto has a degenerate but non-null box`() {
        assertEquals(SvgRect(3.0, 4.0, 0.0, 0.0), SvgPath.bounds(parse("M 3 4")))
    }
}
