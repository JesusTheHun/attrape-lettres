package fr.dappit.attrapelettres.art.svg

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The `d`-string parser the whole drawing layer rests on (iOS D2, Sources/ALArt/SVGPath.swift).
//
// The mascots and the exercise icons are not assets: 95 of the app's 189 path
// strings are template literals computed from props at runtime. The port keeps
// them as strings and parses them here, so the geometry is never retyped and a
// port bug cannot silently move a dragon's eye three points left.
//
// The corpus in the repo uses M L Q Z C A l q H a z V, but the full SVG 1.1
// grammar is implemented — a new mascot must not be able to hit an unsupported
// command. Elliptical arcs are converted to cubic Beziers rather than handed to
// a platform arc primitive, which keeps the result independent of any drawing
// backend's sweep convention and makes the maths assertable on the host with
// exact numbers.
//
// WHY THIS FILE HAS NO COMPOSE IN IT AT ALL. A Gradle unit test in an Android
// module runs on a bare JVM with no Android runtime, and
// `androidx.compose.ui.graphics.Path` is a thin wrapper over
// `android.graphics.Path`, which cannot be instantiated there. So parsing
// produces a list of plain-data commands — the thing tests assert on — and
// `SvgRender.kt` converts that list into a Compose `Path` at the last possible
// moment. Parse and compute in neutral types; convert at the boundary.

/**
 * A point in SVG user space.
 *
 * `Float`, like every coordinate that eventually reaches Compose. The affine
 * maths in [SvgTransform] is deliberately `Double` — see the note there — but
 * the geometry itself is stored at the precision it will be drawn at.
 */
data class SvgPoint(val x: Float, val y: Float)

/**
 * One command of a parsed `d` string, with the coordinates already resolved to
 * absolute user space (relative forms are folded away by the parser).
 *
 * [ArcTo] is the one command that survives parsing in its authored form rather
 * than as curves, because its seven parameters are what a test about flags,
 * radii or rotation actually wants to see. [SvgPath.flatten] turns it into the
 * cubic segments everything downstream draws.
 */
sealed interface SvgPathCommand {

    /** `M`/`m` — start a new subpath at (x, y). */
    data class MoveTo(val x: Float, val y: Float) : SvgPathCommand

    /** `L`/`l`/`H`/`h`/`V`/`v` — a straight segment to (x, y). */
    data class LineTo(val x: Float, val y: Float) : SvgPathCommand

    /** `Q`/`q`/`T`/`t` — a quadratic Bezier through the single control point. */
    data class QuadTo(
        val x1: Float,
        val y1: Float,
        val x: Float,
        val y: Float,
    ) : SvgPathCommand

    /** `C`/`c`/`S`/`s` — a cubic Bezier through two control points. */
    data class CubicTo(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : SvgPathCommand

    /** `A`/`a` — an elliptical arc, in its authored endpoint parameterisation. */
    data class ArcTo(
        val rx: Float,
        val ry: Float,
        val rotationDegrees: Float,
        val largeArc: Boolean,
        val sweep: Boolean,
        val x: Float,
        val y: Float,
    ) : SvgPathCommand

    /** `Z`/`z` — close the subpath and return the current point to its start. */
    data object Close : SvgPathCommand
}

/** What went wrong, and where. Mirrors the iOS `SVGPathError` cases one for one. */
enum class SvgPathErrorKind {
    UNEXPECTED_CHARACTER,
    UNKNOWN_COMMAND,
    EXPECTED_NUMBER,
    EXPECTED_FLAG,
    MISSING_INITIAL_MOVE,
}

/**
 * Thrown by [SvgPath.parse] on malformed input.
 *
 * The path strings are authored constants, so anything that throws is a bug a
 * test should catch rather than something to paper over at runtime — which is
 * exactly why the drawing entry points ([SvgPath.parseOrEmpty], `svgPath`) do
 * not throw. Invariant 3, no fail state, applied to the drawing layer.
 */
class SvgPathException(
    val kind: SvgPathErrorKind,
    val offset: Int,
    val character: Char? = null,
) : IllegalArgumentException(describe(kind, offset, character)) {

    private companion object {
        fun describe(kind: SvgPathErrorKind, offset: Int, character: Char?): String = when (kind) {
            SvgPathErrorKind.UNEXPECTED_CHARACTER -> "unexpected character '$character' at $offset"
            SvgPathErrorKind.UNKNOWN_COMMAND -> "unknown path command '$character' at $offset"
            SvgPathErrorKind.EXPECTED_NUMBER -> "expected a number at $offset"
            SvgPathErrorKind.EXPECTED_FLAG -> "expected a 0/1 arc flag at $offset"
            SvgPathErrorKind.MISSING_INITIAL_MOVE ->
                "path data must start with a moveto, at $offset"
        }
    }
}

object SvgPath {

    /**
     * Parse SVG path data into absolute commands, arcs left as [SvgPathCommand.ArcTo].
     *
     * Throws [SvgPathException] on malformed input.
     */
    fun parse(d: String): List<SvgPathCommand> {
        val scanner = Scanner(d)
        val out = ArrayList<SvgPathCommand>()

        var currentX = 0.0 // current point
        var currentY = 0.0
        var subpathStartX = 0.0 // where the active subpath began
        var subpathStartY = 0.0
        var lastControlX = 0.0 // for S/s and T/t reflection
        var lastControlY = 0.0
        var hasLastControl = false
        var lastWasCubic = false
        var lastWasQuad = false
        var previousCommand: Char? = null
        var started = false

        while (true) {
            val command = scanner.nextCommand(previousCommand) ?: break
            val relative = command.isLowerCase()
            val op = command.lowercaseChar()

            if (!started && op != 'm') {
                throw SvgPathException(SvgPathErrorKind.MISSING_INITIAL_MOVE, scanner.offset)
            }

            val originX = if (relative) currentX else 0.0
            val originY = if (relative) currentY else 0.0

            when (op) {
                'm' -> {
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(SvgPathCommand.MoveTo(px.toFloat(), py.toFloat()))
                    currentX = px
                    currentY = py
                    subpathStartX = px
                    subpathStartY = py
                    started = true
                    lastWasCubic = false
                    lastWasQuad = false
                }

                'l' -> {
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(SvgPathCommand.LineTo(px.toFloat(), py.toFloat()))
                    currentX = px
                    currentY = py
                    lastWasCubic = false
                    lastWasQuad = false
                }

                'h' -> {
                    val x = scanner.number()
                    val px = if (relative) currentX + x else x
                    out.add(SvgPathCommand.LineTo(px.toFloat(), currentY.toFloat()))
                    currentX = px
                    lastWasCubic = false
                    lastWasQuad = false
                }

                'v' -> {
                    val y = scanner.number()
                    val py = if (relative) currentY + y else y
                    out.add(SvgPathCommand.LineTo(currentX.toFloat(), py.toFloat()))
                    currentY = py
                    lastWasCubic = false
                    lastWasQuad = false
                }

                'c' -> {
                    val c1x = originX + scanner.number()
                    val c1y = originY + scanner.number()
                    val c2x = originX + scanner.number()
                    val c2y = originY + scanner.number()
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(
                        SvgPathCommand.CubicTo(
                            c1x.toFloat(), c1y.toFloat(),
                            c2x.toFloat(), c2y.toFloat(),
                            px.toFloat(), py.toFloat(),
                        ),
                    )
                    currentX = px
                    currentY = py
                    lastControlX = c2x
                    lastControlY = c2y
                    hasLastControl = true
                    lastWasCubic = true
                    lastWasQuad = false
                }

                's' -> {
                    // The first control point is the reflection of the previous
                    // cubic's second control point; absent one, it coincides with
                    // the current point (SVG 1.1 section 8.3.6).
                    val c1x: Double
                    val c1y: Double
                    if (lastWasCubic) {
                        val rx = if (hasLastControl) lastControlX else currentX
                        val ry = if (hasLastControl) lastControlY else currentY
                        c1x = 2 * currentX - rx
                        c1y = 2 * currentY - ry
                    } else {
                        c1x = currentX
                        c1y = currentY
                    }
                    val c2x = originX + scanner.number()
                    val c2y = originY + scanner.number()
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(
                        SvgPathCommand.CubicTo(
                            c1x.toFloat(), c1y.toFloat(),
                            c2x.toFloat(), c2y.toFloat(),
                            px.toFloat(), py.toFloat(),
                        ),
                    )
                    currentX = px
                    currentY = py
                    lastControlX = c2x
                    lastControlY = c2y
                    hasLastControl = true
                    lastWasCubic = true
                    lastWasQuad = false
                }

                'q' -> {
                    val cx = originX + scanner.number()
                    val cy = originY + scanner.number()
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(
                        SvgPathCommand.QuadTo(
                            cx.toFloat(), cy.toFloat(),
                            px.toFloat(), py.toFloat(),
                        ),
                    )
                    currentX = px
                    currentY = py
                    lastControlX = cx
                    lastControlY = cy
                    hasLastControl = true
                    lastWasQuad = true
                    lastWasCubic = false
                }

                't' -> {
                    val cx: Double
                    val cy: Double
                    if (lastWasQuad) {
                        val rx = if (hasLastControl) lastControlX else currentX
                        val ry = if (hasLastControl) lastControlY else currentY
                        cx = 2 * currentX - rx
                        cy = 2 * currentY - ry
                    } else {
                        cx = currentX
                        cy = currentY
                    }
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(
                        SvgPathCommand.QuadTo(
                            cx.toFloat(), cy.toFloat(),
                            px.toFloat(), py.toFloat(),
                        ),
                    )
                    currentX = px
                    currentY = py
                    lastControlX = cx
                    lastControlY = cy
                    hasLastControl = true
                    lastWasQuad = true
                    lastWasCubic = false
                }

                'a' -> {
                    val rx = scanner.number()
                    val ry = scanner.number()
                    val rotation = scanner.number()
                    val largeArc = scanner.flag()
                    val sweep = scanner.flag()
                    val px = originX + scanner.number()
                    val py = originY + scanner.number()
                    out.add(
                        SvgPathCommand.ArcTo(
                            rx = rx.toFloat(),
                            ry = ry.toFloat(),
                            rotationDegrees = rotation.toFloat(),
                            largeArc = largeArc,
                            sweep = sweep,
                            x = px.toFloat(),
                            y = py.toFloat(),
                        ),
                    )
                    currentX = px
                    currentY = py
                    lastWasCubic = false
                    lastWasQuad = false
                }

                'z' -> {
                    out.add(SvgPathCommand.Close)
                    currentX = subpathStartX
                    currentY = subpathStartY
                    lastWasCubic = false
                    lastWasQuad = false
                }

                else -> throw SvgPathException(
                    SvgPathErrorKind.UNKNOWN_COMMAND,
                    scanner.offset,
                    command,
                )
            }

            previousCommand = command
        }

        return out
    }

    /**
     * Replace every [SvgPathCommand.ArcTo] with the cubic segments that draw it.
     *
     * Everything else passes through untouched, so the result is a list of
     * moves, lines, quadratics, cubics and closes — the five things every
     * drawing backend can express identically.
     */
    fun flatten(commands: List<SvgPathCommand>): List<SvgPathCommand> {
        if (commands.none { it is SvgPathCommand.ArcTo }) return commands

        val out = ArrayList<SvgPathCommand>(commands.size)
        var currentX = 0.0
        var currentY = 0.0
        var subpathStartX = 0.0
        var subpathStartY = 0.0

        for (command in commands) {
            when (command) {
                is SvgPathCommand.ArcTo -> {
                    out.addAll(arcToCubics(currentX, currentY, command))
                    currentX = command.x.toDouble()
                    currentY = command.y.toDouble()
                }

                is SvgPathCommand.MoveTo -> {
                    out.add(command)
                    currentX = command.x.toDouble()
                    currentY = command.y.toDouble()
                    subpathStartX = currentX
                    subpathStartY = currentY
                }

                is SvgPathCommand.LineTo -> {
                    out.add(command)
                    currentX = command.x.toDouble()
                    currentY = command.y.toDouble()
                }

                is SvgPathCommand.QuadTo -> {
                    out.add(command)
                    currentX = command.x.toDouble()
                    currentY = command.y.toDouble()
                }

                is SvgPathCommand.CubicTo -> {
                    out.add(command)
                    currentX = command.x.toDouble()
                    currentY = command.y.toDouble()
                }

                SvgPathCommand.Close -> {
                    out.add(command)
                    currentX = subpathStartX
                    currentY = subpathStartY
                }
            }
        }
        return out
    }

    /** [parse] then [flatten] — what a renderer wants. Throws on malformed input. */
    fun parseFlattened(d: String): List<SvgPathCommand> = flatten(parse(d))

    /**
     * [parseFlattened], but a malformed string yields an empty list instead of
     * throwing.
     *
     * Non-throwing on purpose: every `d` string in the app is an authored
     * constant covered by `SvgPathCorpusTest`, so a malformed one fails the
     * suite long before a child sees it. At runtime a bad string draws nothing
     * rather than taking the screen down — invariant 3, no fail state, applied
     * to the drawing layer.
     */
    fun parseOrEmpty(d: String): List<SvgPathCommand> = try {
        parseFlattened(d)
    } catch (_: SvgPathException) {
        emptyList()
    }

    /**
     * The bounding box of a command list, as the hull of every on-path and
     * control point.
     *
     * This is the CONTROL-POINT hull, not the tight curve bound: it is what
     * `android.graphics.Path.computeBounds` reports, it never underestimates,
     * and — the reason it lives here rather than being read back off a Compose
     * `Path` — it is computable on a bare JVM. `SvgPaint`'s objectBoundingBox
     * mapping and every geometry test go through this one function, so the
     * gradient a host test reasons about is the gradient the device draws.
     *
     * Returns null for a command list that contains no coordinates at all.
     */
    fun bounds(commands: List<SvgPathCommand>): SvgRect? {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var any = false

        fun include(x: Float, y: Float) {
            any = true
            if (x < minX) minX = x.toDouble()
            if (x > maxX) maxX = x.toDouble()
            if (y < minY) minY = y.toDouble()
            if (y > maxY) maxY = y.toDouble()
        }

        for (command in flatten(commands)) {
            when (command) {
                is SvgPathCommand.MoveTo -> include(command.x, command.y)
                is SvgPathCommand.LineTo -> include(command.x, command.y)
                is SvgPathCommand.QuadTo -> {
                    include(command.x1, command.y1)
                    include(command.x, command.y)
                }

                is SvgPathCommand.CubicTo -> {
                    include(command.x1, command.y1)
                    include(command.x2, command.y2)
                    include(command.x, command.y)
                }

                is SvgPathCommand.ArcTo -> include(command.x, command.y) // unreachable after flatten
                SvgPathCommand.Close -> Unit
            }
        }
        if (!any) return null
        return SvgRect(minX, minY, maxX - minX, maxY - minY)
    }

    /** Every point of a command list mapped through [matrix]. */
    fun transform(commands: List<SvgPathCommand>, matrix: SvgTransform): List<SvgPathCommand> {
        if (matrix.isIdentity) return commands
        return flatten(commands).map { command ->
            when (command) {
                is SvgPathCommand.MoveTo -> {
                    val p = matrix.map(command.x, command.y)
                    SvgPathCommand.MoveTo(p.x, p.y)
                }

                is SvgPathCommand.LineTo -> {
                    val p = matrix.map(command.x, command.y)
                    SvgPathCommand.LineTo(p.x, p.y)
                }

                is SvgPathCommand.QuadTo -> {
                    val c = matrix.map(command.x1, command.y1)
                    val p = matrix.map(command.x, command.y)
                    SvgPathCommand.QuadTo(c.x, c.y, p.x, p.y)
                }

                is SvgPathCommand.CubicTo -> {
                    val c1 = matrix.map(command.x1, command.y1)
                    val c2 = matrix.map(command.x2, command.y2)
                    val p = matrix.map(command.x, command.y)
                    SvgPathCommand.CubicTo(c1.x, c1.y, c2.x, c2.y, p.x, p.y)
                }

                // Unreachable: flatten() has already removed every arc. An arc
                // cannot be mapped point-wise anyway — a non-uniform transform
                // changes its radii and its x-axis rotation.
                is SvgPathCommand.ArcTo -> command
                SvgPathCommand.Close -> command
            }
        }
    }

    // --- Elliptical arc -------------------------------------------------------

    /**
     * Endpoint to centre parameterisation (SVG 1.1 appendix F.6.5), then a cubic
     * Bezier approximation in 90-degree-or-smaller sweeps. Out-of-range radii are
     * scaled UP per F.6.6.2 — the spec says grow them until they reach, never
     * clamp.
     *
     * Everything here is `Double`. The output is `Float` because that is what
     * gets drawn, but an arc computed in `Float` accumulates visible error in
     * the trigonometry, and the corpus has 18 of them.
     */
    fun arcToCubics(fromX: Double, fromY: Double, arc: SvgPathCommand.ArcTo): List<SvgPathCommand> {
        val p1x = arc.x.toDouble()
        val p1y = arc.y.toDouble()

        // F.6.2: a zero radius degenerates to a straight line.
        var rx = abs(arc.rx.toDouble())
        var ry = abs(arc.ry.toDouble())
        if (rx <= 0.0 || ry <= 0.0) {
            return listOf(SvgPathCommand.LineTo(arc.x, arc.y))
        }
        // Coincident endpoints: the arc is omitted entirely (F.6.2).
        if (fromX == p1x && fromY == p1y) return emptyList()

        val phi = arc.rotationDegrees.toDouble() * PI / 180.0
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        // F.6.5.1 — midpoint in the rotated frame.
        val dx = (fromX - p1x) / 2
        val dy = (fromY - p1y) / 2
        val x1 = cosPhi * dx + sinPhi * dy
        val y1 = -sinPhi * dx + cosPhi * dy

        // F.6.6.2 — grow radii that cannot span the chord.
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        // F.6.5.2 — centre in the rotated frame.
        val rxSq = rx * rx
        val rySq = ry * ry
        val numerator = max(0.0, rxSq * rySq - rxSq * y1 * y1 - rySq * x1 * x1)
        val denominator = rxSq * y1 * y1 + rySq * x1 * x1
        val coefficient = (if (arc.largeArc == arc.sweep) -1.0 else 1.0) * sqrt(numerator / denominator)
        val cx1 = coefficient * rx * y1 / ry
        val cy1 = -coefficient * ry * x1 / rx

        // F.6.5.3 — back to user space.
        val cx = cosPhi * cx1 - sinPhi * cy1 + (fromX + p1x) / 2
        val cy = sinPhi * cx1 + cosPhi * cy1 + (fromY + p1y) / 2

        // F.6.5.5 / F.6.5.6 — start angle and sweep.
        val ux = (x1 - cx1) / rx
        val uy = (y1 - cy1) / ry
        val vx = (-x1 - cx1) / rx
        val vy = (-y1 - cy1) / ry

        val theta1 = atan2(uy, ux)
        var deltaTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if (!arc.sweep && deltaTheta > 0) deltaTheta -= 2 * PI
        if (arc.sweep && deltaTheta < 0) deltaTheta += 2 * PI

        // Cubic approximation. Error stays under ~1e-4 of the radius at 90 degrees
        // per segment, which is far below a pixel at any size this app draws at.
        val segments = max(1, ceil(abs(deltaTheta) / (PI / 2)).toInt())
        val delta = deltaTheta / segments
        val alpha = 4.0 / 3.0 * tan(delta / 4)

        val out = ArrayList<SvgPathCommand>(segments)
        var theta = theta1
        for (i in 0 until segments) {
            val thetaNext = theta + delta
            val cosT = cos(theta)
            val sinT = sin(theta)
            val cosN = cos(thetaNext)
            val sinN = sin(thetaNext)

            val startX = cx + rx * cosPhi * cosT - ry * sinPhi * sinT
            val startY = cy + rx * sinPhi * cosT + ry * cosPhi * sinT
            val endX = cx + rx * cosPhi * cosN - ry * sinPhi * sinN
            val endY = cy + rx * sinPhi * cosN + ry * cosPhi * sinN

            // Derivative of the parameterised ellipse, used for the tangents.
            val dStartX = -rx * cosPhi * sinT - ry * sinPhi * cosT
            val dStartY = -rx * sinPhi * sinT + ry * cosPhi * cosT
            val dEndX = -rx * cosPhi * sinN - ry * sinPhi * cosN
            val dEndY = -rx * sinPhi * sinN + ry * cosPhi * cosN

            out.add(
                SvgPathCommand.CubicTo(
                    (startX + alpha * dStartX).toFloat(),
                    (startY + alpha * dStartY).toFloat(),
                    (endX - alpha * dEndX).toFloat(),
                    (endY - alpha * dEndY).toFloat(),
                    endX.toFloat(),
                    endY.toFloat(),
                ),
            )
            theta = thetaNext
        }
        return out
    }

    // --- Scanner --------------------------------------------------------------

    /**
     * Tokeniser for path data. SVG separates numbers by whitespace, commas, or
     * nothing at all when the sign or decimal point makes the boundary
     * unambiguous — `10-5` is two numbers and `.5.5` is two numbers.
     */
    private class Scanner(source: String) {
        private val chars: CharArray = source.toCharArray()
        var offset: Int = 0
            private set

        private val atEnd: Boolean get() = offset >= chars.size

        private fun skipSeparators() {
            while (offset < chars.size) {
                when (chars[offset]) {
                    ' ', ',', '\n', '\r', '\t' -> offset += 1
                    else -> return
                }
            }
        }

        /**
         * The next command letter, or — when the data continues with a number —
         * the implicit repeat of the previous one. After a moveto the implicit
         * command is a lineto of matching arity (SVG 1.1 section 8.3.2).
         */
        fun nextCommand(previous: Char?): Char? {
            skipSeparators()
            if (atEnd) return null
            val c = chars[offset]

            if (c.isLetter()) {
                offset += 1
                if (c !in "MmLlHhVvCcSsQqTtAaZz") {
                    throw SvgPathException(SvgPathErrorKind.UNKNOWN_COMMAND, offset - 1, c)
                }
                return c
            }
            if (c.isAsciiDigit() || c == '-' || c == '+' || c == '.') {
                if (previous == null) {
                    throw SvgPathException(SvgPathErrorKind.UNEXPECTED_CHARACTER, offset, c)
                }
                return when (previous) {
                    'M' -> 'L'
                    'm' -> 'l'
                    'Z', 'z' ->
                        throw SvgPathException(SvgPathErrorKind.UNEXPECTED_CHARACTER, offset, c)
                    else -> previous
                }
            }
            throw SvgPathException(SvgPathErrorKind.UNEXPECTED_CHARACTER, offset, c)
        }

        fun number(): Double {
            skipSeparators()
            val start = offset
            if (!atEnd && (chars[offset] == '-' || chars[offset] == '+')) offset += 1
            var sawDigit = false
            while (!atEnd && chars[offset].isAsciiDigit()) {
                offset += 1
                sawDigit = true
            }
            if (!atEnd && chars[offset] == '.') {
                offset += 1
                while (!atEnd && chars[offset].isAsciiDigit()) {
                    offset += 1
                    sawDigit = true
                }
            }
            if (!sawDigit) throw SvgPathException(SvgPathErrorKind.EXPECTED_NUMBER, start)
            // Exponent, but only when it is actually one: `1e-3` yes, `1e` no.
            if (!atEnd && (chars[offset] == 'e' || chars[offset] == 'E')) {
                val mark = offset
                offset += 1
                if (!atEnd && (chars[offset] == '-' || chars[offset] == '+')) offset += 1
                var sawExponentDigit = false
                while (!atEnd && chars[offset].isAsciiDigit()) {
                    offset += 1
                    sawExponentDigit = true
                }
                if (!sawExponentDigit) offset = mark
            }
            // The slice can only be [sign] digits [. digits] [exponent], so
            // `toDouble` cannot see one of the surprising things it accepts —
            // no "NaN", no "Infinity", no `1f` suffix, no hex float.
            return String(chars, start, offset - start).toDoubleOrNull()
                ?: throw SvgPathException(SvgPathErrorKind.EXPECTED_NUMBER, start)
        }

        /**
         * Arc flags are a single character and may be packed against what
         * follows: `a1 1 0 011 1` carries flags 0 and 1 then the x coordinate.
         */
        fun flag(): Boolean {
            skipSeparators()
            if (atEnd) throw SvgPathException(SvgPathErrorKind.EXPECTED_FLAG, offset)
            return when (chars[offset]) {
                '0' -> {
                    offset += 1
                    false
                }

                '1' -> {
                    offset += 1
                    true
                }

                else -> throw SvgPathException(
                    SvgPathErrorKind.EXPECTED_FLAG,
                    offset,
                    chars[offset],
                )
            }
        }

        // Kotlin's Char.isDigit() is Unicode-wide and would accept an
        // Arabic-Indic digit that toDouble() then rejects. Path data is ASCII.
        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    }
}
