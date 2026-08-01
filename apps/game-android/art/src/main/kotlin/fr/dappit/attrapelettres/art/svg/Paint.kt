package fr.dappit.attrapelettres.art.svg

// Fills and strokes for the SVG canvas (iOS D15, Sources/ALArt/Canvas/Paint.swift).
//
// The whole app declares gradients WITHOUT a `gradientUnits` attribute, which
// means every single one uses SVG's default, `objectBoundingBox`: the gradient
// coordinates are fractions of the element's own bounding box, and the gradient
// is therefore stretched by that box's aspect ratio. On a 2:1 ellipse an SVG
// radial gradient is an ELLIPSE; a naive circular gradient draws a circle and
// nobody notices until the ground glow looks wrong. That is not an edge case
// here, it is the only case — so the bounding-box mapping is the default in
// this file too, and `USER_SPACE_ON_USE` is the opt-out.
//
// Everything in this file is plain data and arithmetic: a colour is four
// floats, a gradient is stops plus unit-square coordinates, and the
// bounding-box mapping is an `SvgTransform`. Turning any of it into a Compose
// `Brush` happens in SvgRender.kt, at the boundary, so the mapping a host test
// reasons about is the mapping the device draws.

/** Which coordinate space a gradient's numbers live in. */
enum class SvgUnits {
    /**
     * Fractions of the filled element's bounding box. **SVG's default**, and
     * what every gradient in this app uses.
     */
    OBJECT_BOUNDING_BOX,

    /** The current user space, i.e. the same coordinates as the path data. */
    USER_SPACE_ON_USE,
}

/**
 * A straight sRGB colour, components in 0…1.
 *
 * Not `androidx.compose.ui.graphics.Color`: that is a `ULong` value class whose
 * colour-space plumbing belongs to the Compose runtime, and the parsing below
 * has to be host-testable.
 */
data class SvgColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
) {
    /** `stop-opacity` / `fill-opacity` folded into the colour, as CSS does. */
    fun withOpacity(opacity: Double): SvgColor =
        copy(alpha = (alpha * opacity).toFloat().coerceIn(0f, 1f))

    companion object {
        val Transparent = SvgColor(0f, 0f, 0f, 0f)

        /**
         * Parse an SVG/CSS hex colour. Accepts `#RGB`, `#RGBA`, `#RRGGBB` and
         * `#RRGGBBAA`, with or without the leading `#`, which is every form the
         * sources use.
         *
         * Unparseable input yields transparent rather than throwing — invariant
         * 3 applied to the drawing layer. Every colour string in the app is an
         * authored constant, so a bad one fails a test long before a child sees
         * it. NB a string of the RIGHT LENGTH with wrong digits yields opaque
         * BLACK, not transparent, exactly as the iOS port does: each component
         * that fails to parse reads 0 and the alpha stays 1.
         */
        fun hex(string: String): SvgColor {
            val trimmed = string.trim()
            val body = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
            // #RGB / #RGBA — each digit doubled, per CSS.
            val digits = if (body.length == 3 || body.length == 4) {
                body.map { "$it$it" }.joinToString("")
            } else {
                body
            }
            if (digits.length != 6 && digits.length != 8) return Transparent

            fun component(at: Int): Float {
                val value = digits.substring(at, at + 2).toIntOrNull(16)
                return if (value == null || value !in 0..255) 0f else value / 255f
            }
            return SvgColor(
                red = component(0),
                green = component(2),
                blue = component(4),
                alpha = if (digits.length == 8) component(6) else 1f,
            )
        }
    }
}

/** `<stop offset="55%" stopColor="#fff" stopOpacity="0.22" />` — offset is 0…1. */
data class SvgStop(val color: SvgColor, val offset: Float) {
    companion object {
        /** `offset` is 0…1; pass 0.55 for "55%". */
        fun hex(hex: String, offset: Float, opacity: Double = 1.0): SvgStop =
            SvgStop(SvgColor.hex(hex).withOpacity(opacity), offset)

        fun color(color: SvgColor, offset: Float, opacity: Double = 1.0): SvgStop =
            SvgStop(color.withOpacity(opacity), offset)
    }
}

/** What to fill or stroke with. */
sealed interface SvgPaintKind {

    /** `fill="none"` / `stroke="none"` — draws nothing at all. */
    data object None : SvgPaintKind

    data class Solid(val color: SvgColor) : SvgPaintKind

    /** `<linearGradient x1 y1 x2 y2>`; SVG's defaults are 0,0 to 1,0. */
    data class Linear(
        val stops: List<SvgStop>,
        val start: SvgPoint,
        val end: SvgPoint,
    ) : SvgPaintKind

    /**
     * `<radialGradient cx cy r>`; SVG's defaults are 0.5, 0.5, 0.5.
     *
     * No `fx`/`fy` — the app uses no focal offsets, and neither Compose nor
     * SwiftUI can express one, so an unsupported focus would have to be
     * silently dropped. It is absent from the type rather than ignored.
     */
    data class Radial(
        val stops: List<SvgStop>,
        val center: SvgPoint,
        val radius: Float,
    ) : SvgPaintKind
}

/**
 * `fill="#FFF6EE"`, `fill="url(#someGradient)"`, or `fill="none"`, plus the
 * `fill-opacity` that rides with it.
 */
data class SvgPaint(
    val kind: SvgPaintKind,
    /** `fill-opacity` / `stroke-opacity`, multiplied into the drawing. */
    val opacity: Double = 1.0,
    val units: SvgUnits = SvgUnits.OBJECT_BOUNDING_BOX,
) {
    companion object {
        val None = SvgPaint(SvgPaintKind.None)

        fun color(color: SvgColor, opacity: Double = 1.0): SvgPaint =
            SvgPaint(SvgPaintKind.Solid(color), opacity)

        /** `fill="#FFF6EE"`. */
        fun hex(hex: String, opacity: Double = 1.0): SvgPaint =
            SvgPaint(SvgPaintKind.Solid(SvgColor.hex(hex)), opacity)

        fun linear(
            stops: List<SvgStop>,
            start: SvgPoint = SvgPoint(0f, 0f),
            end: SvgPoint = SvgPoint(1f, 0f),
            opacity: Double = 1.0,
            units: SvgUnits = SvgUnits.OBJECT_BOUNDING_BOX,
        ): SvgPaint = SvgPaint(SvgPaintKind.Linear(stops, start, end), opacity, units)

        fun radial(
            stops: List<SvgStop>,
            center: SvgPoint = SvgPoint(0.5f, 0.5f),
            radius: Float = 0.5f,
            opacity: Double = 1.0,
            units: SvgUnits = SvgUnits.OBJECT_BOUNDING_BOX,
        ): SvgPaint = SvgPaint(SvgPaintKind.Radial(stops, center, radius), opacity, units)
    }
}

/**
 * The `objectBoundingBox` mapping: the 0…1 unit square onto [box].
 *
 * This is the whole of the bounding-box rule, and it is one line: translate to
 * the box's origin, scale by its size. Applied to the CTM it stretches whatever
 * is drawn in unit space by the box's aspect ratio — which is exactly what a
 * browser does, and why a radial gradient over a 4:1 ellipse comes out as an
 * ellipse rather than a circle.
 */
fun objectBoundingBoxTransform(box: SvgRect): SvgTransform =
    SvgTransform.scale(box.width, box.height).then(SvgTransform.translate(box.minX, box.minY))

/**
 * A linear gradient's two endpoints resolved into user space.
 *
 * For a linear gradient the bounding-box stretch needs no layer trickery at
 * all: mapping the two endpoints through [objectBoundingBoxTransform] gives the
 * same skewed axis the browser produces, and the result can be handed straight
 * to a Compose `Brush.linearGradient`.
 */
fun SvgPaintKind.Linear.endpointsIn(box: SvgRect?, units: SvgUnits): Pair<SvgPoint, SvgPoint> =
    when {
        units == SvgUnits.USER_SPACE_ON_USE || box == null -> start to end
        else -> {
            val t = objectBoundingBoxTransform(box)
            t.map(start.x, start.y) to t.map(end.x, end.y)
        }
    }
