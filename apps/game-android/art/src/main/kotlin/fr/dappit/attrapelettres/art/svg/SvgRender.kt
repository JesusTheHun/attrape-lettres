package fr.dappit.attrapelettres.art.svg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

// The ONE file in the SVG runtime that knows Compose exists.
//
// Everything upstream of here — the parser, the command list, the CTM stack,
// the draw list, the paints — is plain Kotlin over plain data, because a Gradle
// unit test runs on a bare JVM where `androidx.compose.ui.graphics.Path` (a
// wrapper over `android.graphics.Path`) cannot be constructed. This file is the
// adapter: command list to `Path`, `SvgTransform` to `Matrix`, `SvgDrawNode` to
// `DrawScope` calls. It is deliberately thin and deliberately not unit-tested;
// what it can get wrong is direction and composition order, which only a real
// canvas can show, and the geometry it is handed is already proven.
//
// Fill rule: Compose's `Path.fillType` defaults to `PathFillType.NonZero`, and
// nothing here changes it. The whole app has zero `fill-rule="evenodd"`.

// --- Types -------------------------------------------------------------------

/** Build a Compose `Path` from a parsed command list. Arcs are flattened first. */
fun List<SvgPathCommand>.toComposePath(): Path {
    val path = Path()
    for (command in SvgPath.flatten(this)) {
        when (command) {
            is SvgPathCommand.MoveTo -> path.moveTo(command.x, command.y)
            is SvgPathCommand.LineTo -> path.lineTo(command.x, command.y)
            is SvgPathCommand.QuadTo ->
                path.quadraticTo(command.x1, command.y1, command.x, command.y)

            is SvgPathCommand.CubicTo -> path.cubicTo(
                command.x1, command.y1,
                command.x2, command.y2,
                command.x, command.y,
            )

            // Unreachable: `flatten` above removed every arc. Drawing the chord
            // rather than throwing is invariant 3 applied to the drawing layer.
            is SvgPathCommand.ArcTo -> path.lineTo(command.x, command.y)
            SvgPathCommand.Close -> path.close()
        }
    }
    return path
}

/**
 * Build a Compose `Path` from SVG path data.
 *
 * Non-throwing on purpose — see [SvgPath.parseOrEmpty]. A malformed string
 * draws nothing instead of taking a screen down in front of a child.
 */
fun svgPath(d: String): Path = SvgPath.parseOrEmpty(d).toComposePath()

/**
 * The CTM as a Compose `Matrix`.
 *
 * Compose's matrix is a 4x4 in COLUMN-major order, so column 0 holds (a, b) and
 * column 3 holds the translation: `map(x, y)` computes
 * `values[0] * x + values[4] * y + values[12]`. That is the same arithmetic
 * [SvgTransform.map] does, which is the property the rasterisation of a
 * translated square would catch if it ever stopped being true.
 */
fun SvgTransform.toMatrix(): Matrix = Matrix(
    floatArrayOf(
        a.toFloat(), b.toFloat(), 0f, 0f,
        c.toFloat(), d.toFloat(), 0f, 0f,
        0f, 0f, 1f, 0f,
        tx.toFloat(), ty.toFloat(), 0f, 1f,
    ),
)

fun SvgColor.toComposeColor(): Color = Color(red, green, blue, alpha)

/**
 * The stroke style as Compose's.
 *
 * An odd-length dash array is doubled, which is what SVG says to do with an odd
 * `stroke-dasharray`, and is also what Android's dash effect requires (it
 * rejects an odd interval count outright).
 */
fun SvgStrokeStyle.toComposeStroke(): Stroke {
    val intervals = if (dash.size % 2 == 0) dash else dash + dash
    return Stroke(
        width = lineWidth.toFloat(),
        miter = miterLimit.toFloat(),
        cap = when (cap) {
            SvgLineCap.BUTT -> StrokeCap.Butt
            SvgLineCap.ROUND -> StrokeCap.Round
            SvgLineCap.SQUARE -> StrokeCap.Square
        },
        join = when (join) {
            SvgLineJoin.MITER -> StrokeJoin.Miter
            SvgLineJoin.ROUND -> StrokeJoin.Round
            SvgLineJoin.BEVEL -> StrokeJoin.Bevel
        },
        pathEffect = if (intervals.size < 2) {
            null
        } else {
            PathEffect.dashPathEffect(
                FloatArray(intervals.size) { intervals[it].toFloat() },
                dashPhase.toFloat(),
            )
        },
    )
}

/** A `Shape` wrapping SVG path data authored in a viewBox, scaled to fit.
 *
 * `viewBox` is the SVG coordinate space the `d` string was authored in; the
 * shape maps it into whatever rect Compose hands it, preserving aspect ratio
 * the way `preserveAspectRatio="xMidYMid meet"` does in the browser.
 *
 * The mapping is [SvgCanvas.viewBoxTransform], not a second copy of it: one
 * drawing model, and two implementations of `xMidYMid meet` is how one of them
 * quietly stops agreeing with the other. A degenerate viewBox yields the
 * identity there, which is the unmapped path here.
 */
class SvgShape(private val d: String, private val viewBox: SvgRect) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val transform = SvgCanvas.viewBoxTransform(
            viewBox,
            SvgRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble()),
        )
        return Outline.Generic(
            SvgPath.transform(SvgPath.parseOrEmpty(d), transform).toComposePath(),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SvgShape && other.d == d && other.viewBox == viewBox

    override fun hashCode(): Int = 31 * d.hashCode() + viewBox.hashCode()

    override fun toString(): String = "SvgShape(viewBox=$viewBox)"
}

// --- Replay ------------------------------------------------------------------

/**
 * Build a draw list mapped from [viewBox] into this scope's whole size, then
 * replay it. The convenience every rig call site wants.
 *
 * NB it does NOT clip at the viewBox — a Compose `Canvas` already clips to its
 * own bounds, so a mascot whose wings overflow the 100-unit box needs the
 * CALLER to draw into a scope bigger than the box. That is the container's job.
 */
fun DrawScope.drawSvg(viewBox: SvgRect, build: (SvgCanvas) -> Unit) {
    val canvas = SvgCanvas(viewBox, SvgRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble()))
    build(canvas)
    drawSvg(canvas.nodes)
}

/** Replay a recorded draw list, in painter's order. */
fun DrawScope.drawSvg(nodes: List<SvgDrawNode>) {
    for (node in nodes) {
        when (node) {
            is SvgDrawNode.Fill -> withClips(node.clips, 0) {
                paintPath(node.paint, node.path, node.transform, Fill)
            }

            is SvgDrawNode.Stroke -> withClips(node.clips, 0) {
                paintPath(node.paint, node.path, node.transform, node.style.toComposeStroke())
            }

            is SvgDrawNode.Group -> {
                // Composite the children as ONE layer, then blend the layer at
                // the group's opacity, so overlapping children darken once and
                // not twice. This is `<g opacity>`, not per-child alpha.
                val canvas = drawContext.canvas
                canvas.saveLayer(
                    Rect(Offset.Zero, size),
                    Paint().apply { alpha = node.opacity.toFloat() },
                )
                drawSvg(node.children)
                canvas.restore()
            }

            is SvgDrawNode.Mask -> {
                // Alpha masking, in the one construction that expresses it with
                // no offscreen bitmap of our own: draw the content into a layer,
                // then punch it with the matte through DST_IN, which keeps the
                // destination colour and multiplies the destination alpha by the
                // source's. A sparkle at opacity 0.8 therefore passes 0.8 of the
                // sheen, exactly as the web's mask does.
                val canvas = drawContext.canvas
                val bounds = Rect(Offset.Zero, size)
                canvas.saveLayer(bounds, Paint())
                drawSvg(node.content)
                canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
                drawSvg(node.matte)
                canvas.restore()
                canvas.restore()
            }
        }
    }
}

/** Apply the recorded root-space clips, outermost first, then draw. */
private fun DrawScope.withClips(
    clips: List<List<SvgPathCommand>>,
    index: Int,
    body: DrawScope.() -> Unit,
) {
    if (index >= clips.size) {
        body()
        return
    }
    clipPath(clips[index].toComposePath()) {
        withClips(clips, index + 1, body)
    }
}

private fun DrawScope.paintPath(
    paint: SvgPaint,
    commands: List<SvgPathCommand>,
    ctm: SvgTransform,
    style: DrawStyle,
) {
    when (val kind = paint.kind) {
        SvgPaintKind.None -> return

        is SvgPaintKind.Solid -> {
            // fill-opacity is folded into the colour rather than passed as
            // `alpha`: Compose's alpha parameter REPLACES the paint's alpha
            // instead of multiplying it, which would throw away the alpha of a
            // `#RRGGBBAA` colour.
            val color = kind.color.withOpacity(paint.opacity).toComposeColor()
            withTransform({ transform(ctm.toMatrix()) }) {
                drawPath(commands.toComposePath(), color, style = style)
            }
        }

        is SvgPaintKind.Linear -> {
            if (kind.stops.isEmpty()) return
            val box = boundingBoxOrNull(paint.units, commands) ?: return
            val (start, end) = kind.endpointsIn(box, paint.units)
            val brush = Brush.linearGradient(
                colorStops = kind.stops.toColorStops(),
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
            )
            withTransform({ transform(ctm.toMatrix()) }) {
                drawPath(
                    commands.toComposePath(),
                    brush,
                    alpha = paint.opacity.toFloat(),
                    style = style,
                )
            }
        }

        is SvgPaintKind.Radial -> {
            if (kind.stops.isEmpty() || kind.radius <= 0f) return
            if (paint.units == SvgUnits.USER_SPACE_ON_USE) {
                val brush = Brush.radialGradient(
                    colorStops = kind.stops.toColorStops(),
                    center = Offset(kind.center.x, kind.center.y),
                    radius = kind.radius,
                )
                withTransform({ transform(ctm.toMatrix()) }) {
                    drawPath(
                        commands.toComposePath(),
                        brush,
                        alpha = paint.opacity.toFloat(),
                        style = style,
                    )
                }
                return
            }
            val box = SvgPath.bounds(commands) ?: return
            // SVG: an element whose bounding box is degenerate on either axis is
            // not rendered at all with objectBoundingBox units.
            if (box.width <= 0 || box.height <= 0) return
            drawBoundingBoxRadial(kind, paint, commands, ctm, box, style)
        }
    }
}

/**
 * The objectBoundingBox radial gradient — the one case the bounding box really
 * does stretch, and the reason this whole units business exists.
 *
 * The shape is drawn into a layer as pure coverage, then the gradient — a plain
 * circle in the 0…1 unit square — is painted over it through SRC_IN under the
 * bounding box's own transform. The CTM does the stretching, exactly as it does
 * in a browser: on an 80x20 ellipse the gradient is an 80x20 ellipse too.
 *
 * The iOS port clips to the shape instead of compositing a layer. Compose's
 * `clipPath` is a hard-edged clip on Android, and the users here are the ground
 * glow and the aura — soft ellipses whose whole point is that they have no
 * edge — so this port composites, which keeps the antialiasing.
 */
private fun DrawScope.drawBoundingBoxRadial(
    kind: SvgPaintKind.Radial,
    paint: SvgPaint,
    commands: List<SvgPathCommand>,
    ctm: SvgTransform,
    box: SvgRect,
    style: DrawStyle,
) {
    val brush = Brush.radialGradient(
        colorStops = kind.stops.toColorStops(),
        center = Offset(kind.center.x, kind.center.y),
        radius = kind.radius,
    )
    val boxMatrix = objectBoundingBoxTransform(box).then(ctm).toMatrix()
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    withTransform({ transform(ctm.toMatrix()) }) {
        drawPath(commands.toComposePath(), Color.Black, style = style)
    }
    withTransform({ transform(boxMatrix) }) {
        // In unit space the shape lives inside 0…1; the gradient may run past
        // it, so cover generously and let SRC_IN do the bounding.
        drawRect(
            brush = brush,
            topLeft = Offset(-1f, -1f),
            size = Size(3f, 3f),
            alpha = paint.opacity.toFloat(),
            blendMode = BlendMode.SrcIn,
        )
    }
    canvas.restore()
}

/**
 * The bounding box a gradient resolves against, or null when there is nothing
 * to render.
 *
 * Per SVG, objectBoundingBox units against a box that is degenerate on either
 * axis mean the element is not rendered. `userSpaceOnUse` needs no box at all,
 * and says so by answering a zero rect that [SvgPaintKind.Linear.endpointsIn]
 * ignores.
 */
private fun boundingBoxOrNull(units: SvgUnits, commands: List<SvgPathCommand>): SvgRect? {
    if (units == SvgUnits.USER_SPACE_ON_USE) return SvgRect.Zero
    val box = SvgPath.bounds(commands) ?: return null
    return if (box.width > 0 && box.height > 0) box else null
}

private fun List<SvgStop>.toColorStops(): Array<Pair<Float, Color>> =
    Array(size) { this[it].offset to this[it].color.toComposeColor() }
