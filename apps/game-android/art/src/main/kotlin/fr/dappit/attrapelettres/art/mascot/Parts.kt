package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStop
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.mascot.LegSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Port of `src/mascot/parts.tsx` (via `Sources/ALArt/Mascot/Parts.swift`).
//
// Generic flat-kawaii part library. Species files compose these plus their own
// species-specific parts. Everything is pure SVG driven by numeric props.
//
// Each TSX component becomes ONE `draw…(c, …)` function over the `SvgCanvas`
// substrate, transcribed statement by statement — painter's order is the call
// order, and re-ordering two statements is a visible bug, not a style choice.
// Every `d` template is copied VERBATIM into a Kotlin string template; the
// `circle`/`ellipse`/`rect` attributes go through the helpers below with their
// numbers untouched. Nothing here re-authors geometry as builder calls.
//
// WHY THERE IS NO COMPOSE IN THIS FILE. A part is geometry plus paint, and both
// are plain data here: `SvgShapes.ellipse` returns a command list and
// `SvgPaint.hex` returns four floats. That is what lets a host test assert that
// the cheer eyes really are two four-pointed stars at 1.35x the eye radius,
// on a laptop, with no emulator. Compose enters exactly once, in
// `SvgRender.drawSvg`, when the recorded list is replayed.
//
// The TSX `id` props (Aura, Swimsuit, SwimRing, Halo, GroundGlow) existed only
// because SVG `defs` share one document namespace. Here a gradient is a value
// and a clipPath is `save()` / `clip()` / `restore()`, so the ids have no port
// and nothing may re-introduce a uid.

/** The ink colour every face feature is drawn in. */
const val DARK = "#4A3222"

/** The cheek blush. */
const val BLUSH = "#FF9AA2"

// --- Shape helpers (verbatim attribute to command list) ----------------------
//
// These exist so a species rig transcribed from the TSX can keep writing
// `ellipsePath(cx, cy, rx, ry)` where the JSX wrote `<ellipse cx cy rx ry>`,
// instead of mentally reordering the arguments into a rect. SwiftUI hands the
// iOS port `Path(ellipseIn:)` for free; Compose's equivalent is a member of
// `Path`, which a host test cannot construct, so `SvgShapes` builds the same
// four-cubic approximation in neutral data and these are its SVG-shaped names.

/** `<ellipse cx cy rx ry>`. */
fun ellipsePath(cx: Double, cy: Double, rx: Double, ry: Double): List<SvgPathCommand> =
    SvgShapes.ellipse(cx, cy, rx, ry)

/** `<circle cx cy r>`. */
fun circlePath(cx: Double, cy: Double, r: Double): List<SvgPathCommand> =
    SvgShapes.circle(cx, cy, r)

/** `<line x1 y1 x2 y2>`. */
fun linePath(x1: Double, y1: Double, x2: Double, y2: Double): List<SvgPathCommand> =
    SvgShapes.line(x1, y1, x2, y2)

/** `<rect x y width height>`. */
fun rectPath(x: Double, y: Double, width: Double, height: Double): List<SvgPathCommand> =
    SvgShapes.rect(x, y, width, height)

/** A crisp 4-point sparkle star centred at (cx,cy). */
fun fourStar(cx: Double, cy: Double, r: Double): String {
    val i = r * 0.36
    return "M$cx ${cy - r} L${cx + i} ${cy - i} L${cx + r} $cy L${cx + i} ${cy + i} " +
        "L$cx ${cy + r} L${cx - i} ${cy + i} L${cx - r} $cy L${cx - i} ${cy - i} Z"
}

// --- Face --------------------------------------------------------------------

/**
 * The eyes, in four mutually exclusive looks.
 *
 * [sleepy] closes them for the helpless stade-0 baby, and only while idle — a
 * newborn that is cheering opens its eyes like everyone else.
 */
fun drawEyes(
    c: SvgCanvas,
    cx: Double,
    y: Double,
    dx: Double,
    r: Double,
    mood: Mood,
    sleepy: Boolean = false,
) {
    val lx = cx - dx
    val rx = cx + dx
    if (sleepy && mood == Mood.IDLE) {
        val w = r * 1.1
        val style = SvgStrokeStyle(lineWidth = r * 0.5, cap = SvgLineCap.ROUND)
        c.stroke("M${lx - w} $y Q$lx ${y + r * 0.9} ${lx + w} $y", SvgPaint.hex(DARK), style)
        c.stroke("M${rx - w} $y Q$rx ${y + r * 0.9} ${rx + w} $y", SvgPaint.hex(DARK), style)
        return
    }
    if (mood == Mood.CHEER) {
        c.fill(fourStar(lx, y, r * 1.35), SvgPaint.hex(DARK))
        c.fill(fourStar(rx, y, r * 1.35), SvgPaint.hex(DARK))
        return
    }
    if (mood == Mood.HAPPY) {
        val w = r * 1.25
        val style = SvgStrokeStyle(lineWidth = r * 0.78, cap = SvgLineCap.ROUND)
        c.stroke("M${lx - w} ${y + r * 0.5} Q$lx ${y - r} ${lx + w} ${y + r * 0.5}", SvgPaint.hex(DARK), style)
        c.stroke("M${rx - w} ${y + r * 0.5} Q$rx ${y - r} ${rx + w} ${y + r * 0.5}", SvgPaint.hex(DARK), style)
        return
    }
    c.fill(circlePath(lx, y, r), SvgPaint.hex(DARK))
    c.fill(circlePath(rx, y, r), SvgPaint.hex(DARK))
    c.fill(circlePath(lx - r * 0.32, y - r * 0.36, r * 0.36), SvgPaint.hex("#fff"))
    c.fill(circlePath(rx - r * 0.32, y - r * 0.36, r * 0.36), SvgPaint.hex("#fff"))
    c.fill(circlePath(lx + r * 0.34, y + r * 0.34, r * 0.16), SvgPaint.hex("#fff", opacity = 0.8))
    c.fill(circlePath(rx + r * 0.34, y + r * 0.34, r * 0.16), SvgPaint.hex("#fff", opacity = 0.8))
}

fun drawCheeks(c: SvgCanvas, cx: Double, y: Double, dx: Double, r: Double) {
    // `<g fill={BLUSH} opacity={0.6}>` — a GROUP opacity, not two 0.6 fills. The
    // two blushes never overlap today, but the group is what the web wrote and
    // it is the difference between one 60% wash and a double-darkened seam if a
    // species ever moves them together.
    c.group(opacity = 0.6) { g ->
        g.fill(ellipsePath(cx - dx, y, r, r * 0.68), SvgPaint.hex(BLUSH))
        g.fill(ellipsePath(cx + dx, y, r, r * 0.68), SvgPaint.hex(BLUSH))
    }
}

fun drawMouth(c: SvgCanvas, cx: Double, y: Double, w: Double, mood: Mood) {
    if (mood == Mood.IDLE) {
        c.stroke(
            "M${cx - w} $y Q$cx ${y + w * 0.95} ${cx + w} $y",
            SvgPaint.hex(DARK),
            SvgStrokeStyle(lineWidth = 1.5, cap = SvgLineCap.ROUND),
        )
        return
    }
    c.fill("M${cx - w} ${y - w * 0.2} Q$cx ${y + w * 1.5} ${cx + w} ${y - w * 0.2} Z", SvgPaint.hex(DARK))
    c.fill(
        "M${cx - w * 0.5} ${y + w * 0.5} Q$cx ${y + w * 1.1} ${cx + w * 0.5} ${y + w * 0.5} Z",
        SvgPaint.hex("#FF7C93"),
    )
}

// --- Fur / hair plume (tails, manes, tufts) ----------------------------------

/** A fan of rotated lock-ellipses. [wave] toggles a curly zig-zag. */
fun drawPlume(
    c: SvgCanvas,
    x: Double,
    y: Double,
    color: String,
    len: Double,
    wide: Double,
    rot: Double,
    n: Int,
    wave: Boolean = false,
) {
    for (i in 0 until n) {
        // A single lock sits in the middle of the fan rather than at t = 0.
        val t = if (n == 1) 0.5 else i.toDouble() / (n - 1)
        val ox = x + (t - 0.5) * wide
        val oy = y + abs(t - 0.5) * len * 0.18
        val r = if (wave) rot + sin(i * 1.9) * 22 else rot + (t - 0.5) * 26
        val rad = r * PI / 180
        val tipX = ox + sin(rad) * len
        val tipY = oy + cos(rad) * len
        val mx = (ox + tipX) / 2
        val my = (oy + tipY) / 2
        // `transform={rotate(r mx my)}` — the ANCHORED form. Rotating about the
        // origin instead would fling every lock off the head; the anchor is the
        // whole reason `SvgCanvas.rotate` takes a centre.
        c.save()
        c.rotate(degrees = r, centerX = mx, centerY = my)
        c.fill(ellipsePath(mx, my, len * 0.26, len * 0.52), SvgPaint.hex(color))
        c.restore()
    }
}

// --- Legs --------------------------------------------------------------------

fun drawLeg(c: SvgCanvas, spec: LegSpec, w: Double, color: String, hoof: String) {
    val mx = (spec.hipX + spec.footX) / 2 + spec.side * spec.bend
    val my = (spec.hipY + spec.footY) / 2
    c.stroke(
        "M${spec.hipX} ${spec.hipY} Q$mx $my ${spec.footX} ${spec.footY}",
        SvgPaint.hex(color),
        SvgStrokeStyle(lineWidth = w, cap = SvgLineCap.ROUND),
    )
    c.fill(ellipsePath(spec.footX, spec.footY, w * 0.62, w * 0.4), SvgPaint.hex(hoof))
}

/** Tucked folded legs for the lying (can't-stand) baby pose. */
fun drawFoldedLegs(
    c: SvgCanvas,
    bodyCX: Double,
    bodyCY: Double,
    bodyRX: Double,
    color: String,
    hoof: String,
) {
    val y = bodyCY + 8
    val x = bodyCX + bodyRX * 0.35
    val style = SvgStrokeStyle(lineWidth = 7.0, cap = SvgLineCap.ROUND)
    c.stroke("M${x - 4} ${y - 5} Q${x + 10} ${y + 2} ${x + 16} $y", SvgPaint.hex(color), style)
    c.stroke("M${x - 10} ${y - 2} Q${x + 4} ${y + 6} ${x + 12} ${y + 4}", SvgPaint.hex(color), style)
    c.fill(ellipsePath(x + 16, y, 4.0, 2.6), SvgPaint.hex(hoof))
    c.fill(ellipsePath(x + 12, y + 4, 4.0, 2.6), SvgPaint.hex(hoof))
}

// --- Shine / glow ------------------------------------------------------------

/** Soft radial glow. */
fun drawAura(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    color: String,
    opacity: Double = 1.0,
) {
    c.group(opacity = opacity) { g ->
        g.fill(
            circlePath(cx, cy, r),
            SvgPaint.radial(
                listOf(
                    SvgStop.hex(color, 0f, opacity = 0.6),
                    SvgStop.hex(color, 0.55f, opacity = 0.22),
                    SvgStop.hex(color, 1f, opacity = 0.0),
                ),
            ),
        )
    }
}

fun drawSparkles(
    c: SvgCanvas,
    // The TSX takes `Array<[number, number, number]>` — (x, y, r) triples. Kept
    // as `Triple` rather than a named type so a rig transcribed from the TSX
    // keeps the same shape at the call site.
    points: List<Triple<Double, Double, Double>>,
    color: String = "#FFF7D6",
) {
    if (points.isEmpty()) return
    for ((x, y, r) in points) {
        c.fill(fourStar(x, y, r), SvgPaint.hex(color))
    }
}

// --- "Arc-en-ciel magique" premium overlay -----------------------------------

/**
 * Premium "Arc-en-ciel magique" overlay — the ultimate reward. A single rainbow
 * prism of light that sweeps diagonally across the WHOLE image (wings included)
 * on a slow loop, like tilting a holographic card. No stars (the free growth
 * timeline already owns those) and NO colour film or glow over the pet — just
 * the travelling spectrum glint. Confined by the silhouette mask so it never
 * spills onto surrounding UI.
 *
 * PORT NOTE. The sweep itself (`--al-from: -150px` to `--al-to: 150px`, in
 * viewBox units) is VIEW-LEVEL motion and lives in Motion.kt, because invariant
 * 2 says an animation may not reach the code that builds geometry. What this
 * function records is the band at its base transform, `translateX(0)` — which is
 * exactly the static, centred rainbow band that reduced motion must keep
 * showing (`MascotSheen.PARKED_UNITS`). It is a still frame, not a hidden band.
 */
fun drawRainbowSheen(c: SvgCanvas) {
    val sheenW = 26.0 // half-width of the prism band, in viewBox units
    // Sweep range comfortably exceeds the pet's on-screen extent (wings overflow
    // the 0..100 box at the top stades). The band lives off the pet at both ends
    // — where the silhouette mask hides it — so the linear wrap is never seen.

    // the sweeping glint IS a rainbow prism — a spectrum band, soft at both edges
    val sheen = listOf(
        SvgStop.hex("#FF6F91", 0f, opacity = 0.0),
        SvgStop.hex("#FF6F91", 0.26f, opacity = 0.0),
        SvgStop.hex("#FF6F91", 0.38f, opacity = 0.75),
        SvgStop.hex("#FFD24C", 0.47f, opacity = 0.8),
        SvgStop.hex("#7CE8B0", 0.53f, opacity = 0.8),
        SvgStop.hex("#5BC8FF", 0.60f, opacity = 0.8),
        SvgStop.hex("#B98CFF", 0.68f, opacity = 0.75),
        SvgStop.hex("#B98CFF", 0.80f, opacity = 0.0),
        SvgStop.hex("#B98CFF", 1f, opacity = 0.0),
    )

    // rainbow prism sweeping across the pet (confined by the silhouette mask)
    c.save()
    c.rotate(degrees = -20.0, centerX = 50.0, centerY = 50.0)
    c.fill(rectPath(50 - sheenW, -120.0, sheenW * 2, 340.0), SvgPaint.linear(sheen))
    c.restore()
}

// --- Accessories, shared shapes ----------------------------------------------

fun drawBow(c: SvgCanvas, x: Double, y: Double, s: Double, color: String) {
    c.fill("M$x $y L${x - 7 * s} ${y - 5 * s} Q${x - 9.5 * s} $y ${x - 7 * s} ${y + 5 * s} Z", SvgPaint.hex(color))
    c.fill("M$x $y L${x + 7 * s} ${y - 5 * s} Q${x + 9.5 * s} $y ${x + 7 * s} ${y + 5 * s} Z", SvgPaint.hex(color))
    c.fill(circlePath(x, y, 2.6 * s), SvgPaint.hex(color))
    c.fill(circlePath(x - 0.6 * s, y - 0.6 * s, 1.1 * s), SvgPaint.hex("#fff", opacity = 0.4))
}

/**
 * One-piece striped bathing suit — a colour band CLIPPED to the torso ellipse so
 * it hugs the belly. Standing pets wear a waist band (champions, stade 6+, earn
 * a white [star] badge). [lying] newborns wear a little nappy-culotte on the
 * RUMP (away from the oversized resting head) with vertical stripes — the rig
 * draws it UNDER the head/neck so the face always stays on top.
 */
fun drawSwimsuit(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    color: String,
    stripe: String = "#FFF6EE",
    lying: Boolean = false,
    star: Boolean = false,
) {
    if (lying) {
        val edge = cx - rx * 0.16 // culotte covers the left (rump) side of the loaf
        c.save()
        c.clip(ellipsePath(cx, cy, rx, ry))
        c.fill(
            rectPath(cx - rx - 1, cy - ry - 1, edge - (cx - rx - 1), ry * 2 + 2),
            SvgPaint.hex(color),
        )
        for (k in listOf(0.72, 0.44)) {
            c.stroke(
                "M${cx - rx * k} ${cy - ry} q 2.6 ${ry * 0.5} 0 $ry q -2.6 ${ry * 0.5} 0 $ry",
                SvgPaint.hex(stripe, opacity = 0.9),
                SvgStrokeStyle(lineWidth = 2.0),
            )
        }
        c.restore()
        // The waistband edge is OUTSIDE the clip on purpose — it is the seam of
        // the culotte and must read as a line on the fur, not be cut by the belly.
        c.stroke(
            "M$edge ${cy - ry * 0.9} Q${edge + 2} $cy $edge ${cy + ry * 0.9}",
            SvgPaint.hex(DARK, opacity = 0.28),
            SvgStrokeStyle(lineWidth = 1.0),
        )
        return
    }
    val top = cy - ry * 0.12
    c.save()
    c.clip(ellipsePath(cx, cy, rx, ry))
    c.fill(rectPath(cx - rx - 1, top, rx * 2 + 2, ry * 2), SvgPaint.hex(color))
    for (k in listOf(0.34, 0.68)) {
        c.stroke(
            "M${cx - rx} ${top + ry * k} q ${rx * 0.5} -3 $rx 0 q ${rx * 0.5} 3 $rx 0",
            SvgPaint.hex(stripe, opacity = 0.9),
            SvgStrokeStyle(lineWidth = 2.2),
        )
    }
    c.restore()
    c.stroke(
        "M${cx - rx * 0.97} ${top + 1.5} Q$cx ${top - 2} ${cx + rx * 0.97} ${top + 1.5}",
        SvgPaint.hex(DARK, opacity = 0.28),
        SvgStrokeStyle(lineWidth = 1.0),
    )
    if (star) {
        c.fill(fourStar(cx, cy + ry * 0.38, 3.1), SvgPaint.hex("#fff", opacity = 0.95))
    }
}

/** Which half of the swim ring to draw — see [drawSwimRing]. */
enum class SwimRingPart { FULL, BACK, FRONT }

/**
 * Classic segmented swim ring — a thick ellipse torus with alternating
 * colour/cream segments and faint rim outlines so it reads as a puffy
 * inflatable. To make the pet sit INSIDE the tube, rigs draw it twice: the
 * [SwimRingPart.BACK] half behind the body, the [SwimRingPart.FRONT] half over
 * it (each half is the same geometry clipped at the tube's midline).
 * [SwimRingPart.FULL] is for lying babies who rest ON the ring. [duck]
 * (stade 7+) perches an inflatable duck head on the tube — drawn with the front
 * half so the body never hides it.
 */
fun drawSwimRing(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    color: String,
    duck: Boolean = false,
    part: SwimRingPart = SwimRingPart.FULL,
) {
    val ry = rx * 0.38
    val w = rx * 0.32
    // ~1/8 of the ellipse perimeter (Ramanujan) → 4 colour + 4 cream segments.
    val per = PI * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))
    val dash = per / 8
    val pad = w + 2
    val hx = cx + rx * 0.95
    val hy = cy - w * 0.55
    val hr = w * 0.8
    if (duck && part != SwimRingPart.BACK) {
        c.fill(circlePath(hx, hy, hr), SvgPaint.hex("#FFE082"))
        c.stroke(circlePath(hx, hy, hr), SvgPaint.hex(DARK, opacity = 0.25), SvgStrokeStyle(lineWidth = 0.7))
        c.fill(
            "M${hx + hr * 0.6} ${hy - 1.4} L${hx + hr + 3.6} ${hy + 0.4} L${hx + hr * 0.6} ${hy + 1.8} Z",
            SvgPaint.hex("#FF8A50"),
        )
        c.fill(circlePath(hx + hr * 0.25, hy - hr * 0.25, 0.9), SvgPaint.hex(DARK))
    }
    c.save()
    when (part) {
        SwimRingPart.FULL -> Unit
        SwimRingPart.BACK -> c.clip(rectPath(cx - rx - pad, cy - ry - pad, (rx + pad) * 2, ry + pad))
        SwimRingPart.FRONT -> c.clip(rectPath(cx - rx - pad, cy, (rx + pad) * 2, ry + pad))
    }
    c.stroke(ellipsePath(cx, cy, rx, ry), SvgPaint.hex("#FFF6EE"), SvgStrokeStyle(lineWidth = w))
    c.stroke(
        ellipsePath(cx, cy, rx, ry),
        SvgPaint.hex(color),
        SvgStrokeStyle(lineWidth = w, dash = listOf(dash, dash)),
    )
    c.stroke(
        ellipsePath(cx, cy, rx + w / 2, ry + w / 2),
        SvgPaint.hex(DARK, opacity = 0.22),
        SvgStrokeStyle(lineWidth = 0.8),
    )
    c.stroke(
        ellipsePath(cx, cy, rx - w / 2, ry - w / 2),
        SvgPaint.hex(DARK, opacity = 0.22),
        SvgStrokeStyle(lineWidth = 0.8),
    )
    c.restore()
}

fun drawFlower(
    c: SvgCanvas,
    x: Double,
    y: Double,
    r: Double,
    petal: String,
    center: String,
) {
    for (i in 0 until 5) {
        val a = i.toDouble() / 5 * PI * 2 - PI / 2
        c.fill(circlePath(x + cos(a) * r, y + sin(a) * r, r * 0.72), SvgPaint.hex(petal))
    }
    c.fill(circlePath(x, y, r * 0.6), SvgPaint.hex(center))
}

// --- Majestic-stage shared parts ---------------------------------------------
//
// These carry the late-stage "wow". Every one is OUTLINED / non-body-coloured on
// purpose: features that share the body's colour vanish into the silhouette and
// a child never sees the reward. Keep the edges.

/** Glowing head halo (soft aura plus a crisp ring). */
fun drawHalo(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    opacity: Double,
    color: String = "#FFF3C4",
) {
    drawAura(c, cx = cx, cy = cy, r = r, color = color, opacity = opacity)
    c.stroke(
        circlePath(cx, cy, r * 0.62),
        SvgPaint.hex(color, opacity = opacity * 0.8),
        SvgStrokeStyle(lineWidth = 1.4),
    )
}

/** Flat radial glow pooled under the feet. */
fun drawGroundGlow(
    c: SvgCanvas,
    cx: Double,
    y: Double,
    rx: Double,
    color: String,
    opacity: Double,
) {
    c.group(opacity = opacity) { g ->
        // objectBoundingBox units: on this 10:3 ellipse the browser stretches the
        // radial gradient into a 10:3 ellipse too. `SvgPaint.radial` defaults to
        // those units for exactly this shape — a circular glow here would be the
        // classic port bug.
        g.fill(
            ellipsePath(cx, y, rx, rx * 0.3),
            SvgPaint.radial(
                listOf(
                    SvgStop.hex(color, 0f, opacity = 0.8),
                    SvgStop.hex(color, 0.55f, opacity = 0.2),
                    SvgStop.hex(color, 1f, opacity = 0.0),
                ),
            ),
        )
    }
}

private data class CrownSpike(
    val bx: Double,
    val by: Double,
    val tx: Double,
    val ty: Double,
    val big: Boolean,
)

/**
 * Outlined tiara/crown arcing over the head, gem-tipped spikes.
 *
 * [open] drops the tall CENTRE spike — for wearers whose horn rises exactly
 * there (the rig also draws the horn OVER the band so it always pokes through).
 */
fun drawCrown(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    r: Double,
    band: String = "#FFD54F",
    gem: String = "#FF7EA8",
    edge: String = "#B07E1E",
    open: Boolean = false,
) {
    val spikes = ArrayList<CrownSpike>(5)
    for ((i, f) in listOf(-0.62, -0.31, 0.0, 0.31, 0.62).withIndex()) {
        if (open && i == 2) continue
        val a = (-90 + f * 78) * (PI / 180)
        val bx = cx + cos(a) * r
        val by = cy + sin(a) * r
        val h = if (i == 2) 8.5 else 5.0
        spikes.add(
            CrownSpike(
                bx = bx,
                by = by,
                tx = cx + cos(a) * (r + h),
                ty = cy + sin(a) * (r + h),
                big = i == 2,
            ),
        )
    }
    val bandPath = "M${cx - r * 0.72} ${cy - r * 0.55} Q$cx ${cy - r * 1.02} ${cx + r * 0.72} ${cy - r * 0.55}"
    // Drawn twice, edge first then band: that is how the TSX gets an outlined
    // arc out of two strokes with no stroke-alignment support in SVG.
    c.stroke(bandPath, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 4.6, cap = SvgLineCap.ROUND))
    c.stroke(bandPath, SvgPaint.hex(band), SvgStrokeStyle(lineWidth = 3.2, cap = SvgLineCap.ROUND))
    for (s in spikes) {
        val spike = "M${s.bx - 2} ${s.by} L${s.tx} ${s.ty} L${s.bx + 2} ${s.by} Z"
        c.fill(spike, SvgPaint.hex(band))
        c.stroke(spike, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.9, join = SvgLineJoin.ROUND))
        val gemR = if (s.big) 2.4 else 1.7
        c.fill(circlePath(s.tx, s.ty, gemR), SvgPaint.hex(gem))
        c.stroke(circlePath(s.tx, s.ty, gemR), SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.6))
    }
}

/** A ring of sparkle-stars around a centre (a celebratory burst). */
fun drawBurst(
    c: SvgCanvas,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    n: Int,
    color: String? = null,
) {
    drawSparkles(
        c,
        points = (0 until n).map { i ->
            val a = i.toDouble() / n * PI * 2
            Triple(cx + cos(a) * rx, cy + sin(a) * ry, 1.6 + (i % 3))
        },
        color = color ?: "#FFF7D6",
    )
}
