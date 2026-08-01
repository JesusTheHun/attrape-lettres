package fr.dappit.attrapelettres.art.svg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The drawing substrate every mascot, icon and word image goes through
// (iOS D15, Sources/ALArt/Canvas/SVGCanvas.swift).
//
// One model, two halves:
//
//   1. `SvgCanvas` — an imperative builder with an explicit CTM stack that
//      mirrors SVG's own transform semantics. A rig draw function transcribes
//      its TSX statement by statement (painter's order) into `fill` / `stroke` /
//      `clip` / `group` / `mask` calls and gets back a draw list.
//   2. `DrawScope.drawSvg` in SvgRender.kt — replays that list into Compose.
//
// The split is what makes the geometry host-testable: a test builds the list
// and asserts on transforms, clip regions and node structure without ever
// touching `androidx.compose.ui.graphics.Path`, which is a wrapper over
// `android.graphics.Path` and cannot be instantiated on a bare JVM. NOTHING in
// this file imports Compose, and nothing in it may start to.
//
// Transform semantics — the part D15 exists for:
//
//   `canvas.translate(50, y); canvas.scale(k); canvas.translate(-50, -y)`
//   is exactly `transform="translate(50 y) scale(k) translate(-50 -y)"`:
//   the FIRST call is the OUTERMOST transform, later calls apply to the
//   drawn geometry first — the same left-to-right reading as an SVG
//   transform list, and the same arithmetic a browser does. Because the
//   whole CTM (including the viewBox mapping and the growth `scale(k)`)
//   reaches the renderer as ONE matrix, stroke widths scale with the
//   geometry exactly as SVG scales them.
//
// What this file deliberately does NOT do:
//
//   - Clip at the viewBox. Wings and haloes at the top growth stages
//     deliberately overflow the 100-unit box (`overflow: visible` on the
//     web). Only the container decides to clip (preview mode does; nothing
//     else does). `SvgCanvas.viewBoxTransform` maps, it never clips.
//     NB: a Compose `Canvas` clips to its OWN bounds — the mascot view must
//     size the canvas larger than the mascot and offset inside it; that is
//     the container's job, not this file's.
//   - Even-odd fills. The whole app has zero `fill-rule="evenodd"`; every
//     fill here is nonzero, which is Compose's `PathFillType` default. There
//     is no API to request even-odd on purpose.
//   - `<use>`. SVG's def/use indirection exists because SVG defs share a
//     document namespace; here a reused subtree is just the draw lambda
//     called twice (the rainbow overlay does exactly that: once for the
//     rig, once inside `mask`).

/**
 * An axis-aligned rectangle in some coordinate space, in `Double`.
 *
 * Deliberately not `androidx.compose.ui.geometry.Rect`: viewBoxes and bounding
 * boxes are arithmetic, and arithmetic has to run in a host test.
 */
data class SvgRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2

    companion object {
        val Zero = SvgRect(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * A 2D affine transform, in the same component layout as CoreGraphics'
 * `CGAffineTransform` so the iOS reasoning transfers unchanged:
 *
 * ```
 * x' = a * x + c * y + tx
 * y' = b * x + d * y + ty
 * ```
 *
 * WHY `Double` WHEN EVERY COORDINATE AROUND IT IS `Float`. A CTM is a product:
 * the viewBox mapping times a growth scale times an anchored rotation times a
 * part-local translate, four or five deep on a mascot. `Float` carries about
 * seven significant digits, so composing five of them at a scale of ~300 leaves
 * error in the third decimal of a unit — visible as a seam between two parts
 * that are supposed to touch. The matrix is computed in `Double` and narrowed
 * once, at the point where geometry is handed to Compose.
 */
data class SvgTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double,
) {
    /** Map a point from this transform's source space to its target space. */
    fun map(x: Float, y: Float): SvgPoint = map(x.toDouble(), y.toDouble())

    fun map(x: Double, y: Double): SvgPoint =
        SvgPoint((a * x + c * y + tx).toFloat(), (b * x + d * y + ty).toFloat())

    /** Map a point without narrowing to `Float` — for assertions and for chaining. */
    fun mapX(x: Double, y: Double): Double = a * x + c * y + tx

    fun mapY(x: Double, y: Double): Double = b * x + d * y + ty

    /**
     * `this` first, then [outer] — CoreGraphics' `concatenating(_:)`.
     *
     * Reading it as "geometry passes through `this`, and the result passes
     * through `outer`" is what makes an SVG transform list translate directly:
     * `transform="A B"` is `B.then(A)`.
     */
    fun then(outer: SvgTransform): SvgTransform = SvgTransform(
        a = a * outer.a + b * outer.c,
        b = a * outer.b + b * outer.d,
        c = c * outer.a + d * outer.c,
        d = c * outer.b + d * outer.d,
        tx = tx * outer.a + ty * outer.c + outer.tx,
        ty = tx * outer.b + ty * outer.d + outer.ty,
    )

    val isIdentity: Boolean
        get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && tx == 0.0 && ty == 0.0

    companion object {
        val Identity = SvgTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translate(tx: Double, ty: Double) = SvgTransform(1.0, 0.0, 0.0, 1.0, tx, ty)

        fun scale(sx: Double, sy: Double) = SvgTransform(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Positive angles turn CLOCKWISE on screen, because y grows downward. */
        fun rotation(radians: Double): SvgTransform {
            val c = cos(radians)
            val s = sin(radians)
            return SvgTransform(c, s, -s, c, 0.0, 0.0)
        }

        fun rotationDegrees(degrees: Double): SvgTransform = rotation(degrees * PI / 180.0)
    }
}

/** `stroke-linecap`. SVG's default is `butt`. */
enum class SvgLineCap { BUTT, ROUND, SQUARE }

/** `stroke-linejoin`. SVG's default is `miter`. */
enum class SvgLineJoin { MITER, ROUND, BEVEL }

/**
 * `stroke-width` / `-linecap` / `-linejoin` / `-miterlimit` / `-dasharray`.
 *
 * The miter limit defaults to 4, which is SVG's default — NOT SwiftUI's 10, and
 * the iOS port inherited SwiftUI's. The web app is the source of truth and a
 * browser uses 4; nothing in the art draws a spike sharp enough to tell the
 * difference, but the number that is right is the one that is written down.
 *
 * `dash` is in user-space units, like the width, so the CTM scales it too. The
 * dashed boxes on the "complete the word" icons are the users.
 */
data class SvgStrokeStyle(
    val lineWidth: Double,
    val cap: SvgLineCap = SvgLineCap.BUTT,
    val join: SvgLineJoin = SvgLineJoin.MITER,
    val miterLimit: Double = 4.0,
    val dash: List<Double> = emptyList(),
    val dashPhase: Double = 0.0,
)

/**
 * One node of the recorded draw list.
 *
 * Leaves ([Fill] / [Stroke]) are self-contained: they carry the full CTM at the
 * time they were recorded and the clip stack resolved into ROOT space, so
 * replay order is the only ordering that matters — painter's order, the order
 * the builder was called in.
 *
 * `path` and `clips` are neutral command lists, never Compose paths, so the
 * whole list is inspectable from a host test.
 */
sealed interface SvgDrawNode {

    /**
     * Fill `path` (nonzero) with `paint`. `transform` maps the path's user space
     * to the canvas root space; `clips` are root-space paths whose intersection
     * bounds the drawing.
     */
    data class Fill(
        val path: List<SvgPathCommand>,
        val transform: SvgTransform,
        val clips: List<List<SvgPathCommand>>,
        val paint: SvgPaint,
    ) : SvgDrawNode

    /**
     * Stroke `path` with `paint`. The stroke is centred on the path and its
     * width lives in the path's user space — the CTM scales it, as SVG does.
     */
    data class Stroke(
        val path: List<SvgPathCommand>,
        val transform: SvgTransform,
        val clips: List<List<SvgPathCommand>>,
        val paint: SvgPaint,
        val style: SvgStrokeStyle,
    ) : SvgDrawNode

    /**
     * `<g opacity=…>`: children composite into one layer first, then the layer
     * blends at `opacity` — overlapping children must NOT double-darken.
     */
    data class Group(
        val opacity: Double,
        val children: List<SvgDrawNode>,
    ) : SvgDrawNode

    /**
     * `<mask>`: `matte`'s rendered ALPHA modulates `content`, pixel by pixel.
     *
     * NB: the web's one mask (the "Arc-en-ciel magique" silhouette) is a
     * luminance mask made equivalent to an alpha mask by CSS
     * `filter: brightness(0) invert(1)` on the `<use>` — after that filter
     * luminance is 1 everywhere, so mask value = source alpha. This port
     * implements the alpha mask directly and drops the filter; implementing
     * luminance masking would re-introduce a web workaround for nothing.
     * Semi-transparent parts keep their exact web behaviour: a sparkle at
     * opacity 0.8 passes 0.8 of the sheen.
     */
    data class Mask(
        val matte: List<SvgDrawNode>,
        val content: List<SvgDrawNode>,
    ) : SvgDrawNode
}

/**
 * The builder: an SVG-semantics drawing surface with an explicit CTM stack.
 *
 * A class rather than Swift's value-type struct, so `group` and `mask` hand a
 * CHILD canvas to their lambda instead of an `inout` copy. The child inherits
 * the parent's state and its nodes are collected into one node; it does not
 * write into the parent.
 */
class SvgCanvas private constructor(
    private var ctm: SvgTransform,
    private var clips: List<List<SvgPathCommand>>,
) {

    private class State(val ctm: SvgTransform, val clips: List<List<SvgPathCommand>>)

    private val stack = ArrayDeque<State>()
    private val recorded = ArrayList<SvgDrawNode>()

    /** The recorded draw list, in painter's order. */
    val nodes: List<SvgDrawNode> get() = recorded

    /** The CTM as currently accumulated: user space to canvas root space. */
    val currentTransform: SvgTransform get() = ctm

    /** The active clip stack, resolved into canvas root space. */
    val currentClips: List<List<SvgPathCommand>> get() = clips

    constructor() : this(SvgTransform.Identity, emptyList())

    /**
     * A canvas whose root CTM maps [viewBox] into [rect] the way
     * `preserveAspectRatio="xMidYMid meet"` does: uniform scale to fit, centred
     * on both axes. Content outside the viewBox is NOT clipped.
     */
    constructor(viewBox: SvgRect, rect: SvgRect) : this(viewBoxTransform(viewBox, rect), emptyList())

    // --- CTM stack ------------------------------------------------------------

    /** Push the current transform and clip; [restore] pops back to it. */
    fun save() {
        stack.addLast(State(ctm, clips))
    }

    /**
     * Pop to the most recent [save]. An unbalanced restore is an authored-code
     * bug the tests catch; at runtime it is a no-op rather than a crash —
     * invariant 3 applied to the drawing layer.
     */
    fun restore() {
        val previous = stack.removeLastOrNull() ?: return
        ctm = previous.ctm
        clips = previous.clips
    }

    /** How deep the save stack is. For tests that assert a rig is balanced. */
    val saveDepth: Int get() = stack.size

    /**
     * Concatenate [t] the way appending it to an SVG transform list does: [t]
     * applies to subsequently drawn geometry BEFORE everything already on the
     * stack.
     */
    fun concatenate(t: SvgTransform) {
        ctm = t.then(ctm)
    }

    /** `translate(tx ty)`. */
    fun translate(tx: Double, ty: Double) {
        concatenate(SvgTransform.translate(tx, ty))
    }

    /** `scale(s)` — uniform. */
    fun scale(s: Double) {
        scale(s, s)
    }

    /**
     * `scale(sx sy)`. Negative values mirror, exactly as in SVG — the unicorn's
     * left wing is `scale(-s s)` and must stay that way.
     */
    fun scale(sx: Double, sy: Double) {
        concatenate(SvgTransform.scale(sx, sy))
    }

    /**
     * `rotate(a)` or `rotate(a cx cy)`. The optional anchor is the silent bug
     * D15 exists to kill, so it is implemented exactly as SVG defines it:
     * `translate(cx cy) rotate(a) translate(-cx -cy)`. Positive angles turn
     * clockwise on screen (y-down space), same as SVG.
     */
    fun rotate(degrees: Double, centerX: Double, centerY: Double) {
        concatenate(SvgTransform.translate(centerX, centerY))
        concatenate(SvgTransform.rotationDegrees(degrees))
        concatenate(SvgTransform.translate(-centerX, -centerY))
    }

    /** `rotate(a)` about the current origin. */
    fun rotate(degrees: Double) {
        concatenate(SvgTransform.rotationDegrees(degrees))
    }

    // --- Clip -----------------------------------------------------------------

    /**
     * `<clipPath>`: intersect the active clip with `path` (interpreted in the
     * current user space, nonzero rule). Scoped by [save]/[restore] like
     * everything else on the stack.
     */
    fun clip(path: List<SvgPathCommand>) {
        clips = clips + listOf(SvgPath.transform(path, ctm))
    }

    /** [clip] from a `d` string. */
    fun clip(d: String) {
        clip(SvgPath.parseOrEmpty(d))
    }

    // --- Drawing --------------------------------------------------------------

    /** Fill `path` (nonzero) with `paint` under the current CTM and clip. */
    fun fill(path: List<SvgPathCommand>, paint: SvgPaint) {
        recorded.add(SvgDrawNode.Fill(path, ctm, clips, paint))
    }

    /** [fill] from a `d` string. */
    fun fill(d: String, paint: SvgPaint) {
        fill(SvgPath.parseOrEmpty(d), paint)
    }

    /**
     * Stroke `path` with `paint` under the current CTM and clip. A zero or
     * negative line width records NOTHING: SVG treats `stroke-width="0"` as "no
     * stroke" while a drawing backend may still put down a hairline — the
     * dragon's spade tail passes `strokeWidth={edge ? 1 : 0}` and relies on this.
     */
    fun stroke(path: List<SvgPathCommand>, paint: SvgPaint, style: SvgStrokeStyle) {
        if (style.lineWidth <= 0) return
        recorded.add(SvgDrawNode.Stroke(path, ctm, clips, paint, style))
    }

    /** [stroke] from a `d` string. */
    fun stroke(d: String, paint: SvgPaint, style: SvgStrokeStyle) {
        stroke(SvgPath.parseOrEmpty(d), paint, style)
    }

    // --- Structure ------------------------------------------------------------

    /**
     * `<g opacity=…>` — [body] draws into a child canvas that inherits the
     * current CTM and clip; the result composites as ONE layer at [opacity].
     */
    fun group(opacity: Double = 1.0, body: (SvgCanvas) -> Unit) {
        val child = SvgCanvas(ctm, clips)
        body(child)
        recorded.add(SvgDrawNode.Group(opacity, child.recorded))
    }

    /**
     * `<mask>` — [matte]'s rendered alpha modulates what [content] draws. Both
     * lambdas inherit the current CTM and clip. See [SvgDrawNode.Mask] for why
     * this is an alpha mask and not a luminance one.
     */
    fun mask(matte: (SvgCanvas) -> Unit, content: (SvgCanvas) -> Unit) {
        val matteCanvas = SvgCanvas(ctm, clips)
        matte(matteCanvas)
        val contentCanvas = SvgCanvas(ctm, clips)
        content(contentCanvas)
        recorded.add(SvgDrawNode.Mask(matteCanvas.recorded, contentCanvas.recorded))
    }

    companion object {
        /**
         * The `xMidYMid meet` mapping on its own — for containers that manage
         * their own canvas (the mascot view sizes its canvas larger than the
         * mascot so overflow survives, then applies this to an inner rect).
         *
         * A degenerate viewBox yields the identity rather than dividing by zero.
         */
        fun viewBoxTransform(viewBox: SvgRect, rect: SvgRect): SvgTransform {
            if (viewBox.width <= 0 || viewBox.height <= 0) return SvgTransform.Identity
            val s = min(rect.width / viewBox.width, rect.height / viewBox.height)
            val dx = rect.midX - viewBox.midX * s
            val dy = rect.midY - viewBox.midY * s
            return SvgTransform.scale(s, s).then(SvgTransform.translate(dx, dy))
        }
    }
}

/**
 * The primitive shapes the rigs draw with, as command lists.
 *
 * SwiftUI hands ALArt `Path(ellipseIn:)` and `Path(roundedRect:)` for free;
 * Compose's equivalents live on `Path`, which a host test cannot build. So the
 * primitives are constructed here in neutral data, out of the same four-cubic
 * approximation every vector backend uses, and a rig's ellipse is as assertable
 * as its parsed `d` strings.
 */
object SvgShapes {

    /**
     * The circular-arc magic number: the control-point offset that makes a cubic
     * Bezier match a quarter circle to within 2e-4 of the radius.
     * `4 / 3 * tan(PI / 8)`.
     */
    const val KAPPA: Double = 0.5522847498307933

    /** A rectangle, wound clockwise from its top-left corner. */
    fun rect(x: Double, y: Double, width: Double, height: Double): List<SvgPathCommand> = listOf(
        SvgPathCommand.MoveTo(x.toFloat(), y.toFloat()),
        SvgPathCommand.LineTo((x + width).toFloat(), y.toFloat()),
        SvgPathCommand.LineTo((x + width).toFloat(), (y + height).toFloat()),
        SvgPathCommand.LineTo(x.toFloat(), (y + height).toFloat()),
        SvgPathCommand.Close,
    )

    /** A rectangle with uniform corner rounding, clamped to half the short side. */
    fun roundedRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        cornerRadius: Double,
    ): List<SvgPathCommand> {
        val r = cornerRadius.coerceIn(0.0, min(abs(width), abs(height)) / 2)
        if (r <= 0.0) return rect(x, y, width, height)
        val k = r * KAPPA
        val x0 = x
        val x1 = x + width
        val y0 = y
        val y1 = y + height
        return listOf(
            SvgPathCommand.MoveTo((x0 + r).toFloat(), y0.toFloat()),
            SvgPathCommand.LineTo((x1 - r).toFloat(), y0.toFloat()),
            SvgPathCommand.CubicTo(
                (x1 - r + k).toFloat(), y0.toFloat(),
                x1.toFloat(), (y0 + r - k).toFloat(),
                x1.toFloat(), (y0 + r).toFloat(),
            ),
            SvgPathCommand.LineTo(x1.toFloat(), (y1 - r).toFloat()),
            SvgPathCommand.CubicTo(
                x1.toFloat(), (y1 - r + k).toFloat(),
                (x1 - r + k).toFloat(), y1.toFloat(),
                (x1 - r).toFloat(), y1.toFloat(),
            ),
            SvgPathCommand.LineTo((x0 + r).toFloat(), y1.toFloat()),
            SvgPathCommand.CubicTo(
                (x0 + r - k).toFloat(), y1.toFloat(),
                x0.toFloat(), (y1 - r + k).toFloat(),
                x0.toFloat(), (y1 - r).toFloat(),
            ),
            SvgPathCommand.LineTo(x0.toFloat(), (y0 + r).toFloat()),
            SvgPathCommand.CubicTo(
                x0.toFloat(), (y0 + r - k).toFloat(),
                (x0 + r - k).toFloat(), y0.toFloat(),
                (x0 + r).toFloat(), y0.toFloat(),
            ),
            SvgPathCommand.Close,
        )
    }

    /** An axis-aligned ellipse centred on (cx, cy). */
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<SvgPathCommand> {
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        return listOf(
            SvgPathCommand.MoveTo((cx + rx).toFloat(), cy.toFloat()),
            SvgPathCommand.CubicTo(
                (cx + rx).toFloat(), (cy + ky).toFloat(),
                (cx + kx).toFloat(), (cy + ry).toFloat(),
                cx.toFloat(), (cy + ry).toFloat(),
            ),
            SvgPathCommand.CubicTo(
                (cx - kx).toFloat(), (cy + ry).toFloat(),
                (cx - rx).toFloat(), (cy + ky).toFloat(),
                (cx - rx).toFloat(), cy.toFloat(),
            ),
            SvgPathCommand.CubicTo(
                (cx - rx).toFloat(), (cy - ky).toFloat(),
                (cx - kx).toFloat(), (cy - ry).toFloat(),
                cx.toFloat(), (cy - ry).toFloat(),
            ),
            SvgPathCommand.CubicTo(
                (cx + kx).toFloat(), (cy - ry).toFloat(),
                (cx + rx).toFloat(), (cy - ky).toFloat(),
                (cx + rx).toFloat(), cy.toFloat(),
            ),
            SvgPathCommand.Close,
        )
    }

    /** A circle centred on (cx, cy). */
    fun circle(cx: Double, cy: Double, r: Double): List<SvgPathCommand> = ellipse(cx, cy, r, r)

    /** A single open segment — what most of the rigs' strokes are. */
    fun line(x1: Double, y1: Double, x2: Double, y2: Double): List<SvgPathCommand> = listOf(
        SvgPathCommand.MoveTo(x1.toFloat(), y1.toFloat()),
        SvgPathCommand.LineTo(x2.toFloat(), y2.toFloat()),
    )
}
