package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.mascot.INK
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Port of `src/mascot/dragonParts.tsx` (via the finished Swift port,
// Sources/ALArt/Mascot/DragonParts.swift — the web file wins where they could
// disagree; they do not).
//
// Kept as ONE file mirroring the TSX so the two can be read side by side; every
// `d` string is the TSX template copied verbatim into a Kotlin string template
// (D2) and drawn in TSX statement order (D15) — painter's order is the call
// order on the SvgCanvas.
//
// Not ported — design-run candidates unreachable from Dragon.tsx (spec §8.6,
// recorded as a deviation, same list as iOS): `CloudWings`, `DomeFin`, `Bolt`,
// `RainCloud`, `Gem`, `AngularWings`, `FrillBand`, `DustPuffs`, `KnightHelmet`,
// `Shield`, `ChestArmor`, `EggShield`, the `Horns` "curly" variant, the
// `SpadeTail` "bolt" tip and its `gem` inset, and the `Crest` `gems` option
// (`Gem` and the METAL/METAL_EDGE constants are reachable only from those).
// `TailClub` IS reachable (tailStyle "club") and is here.

// Dragon part library — everything Braise wears and breathes, kept out of
// Dragon.kt so the rig file stays a readable STAGE_SPEC timeline.
// Pure SVG, numeric props, kawaii house style.

internal const val EGG_FILL = "#FFF9EE"
internal const val EGG_EDGE = "#E3D2BA"

// -- The stade-0 egg ------------------------------------------------------

/**
 * Cracked egg cup, fixed to the stade-0 lying layout: jagged rim rises at the
 * sides and dips in FRONT of the resting face, so the newborn peeks out of the
 * broken shell. Drawn OVER the body/chin (the dragon is IN the egg).
 */
internal val CUP_PATH =
    "M24 68 L28 63 L31.5 69 L35.5 64 L39 70 L43 65 " +
        "L45.5 84 L49 88.5 L52.5 84.5 L56 88.5 L59.5 84.5 L63 88.5 L66.5 84.5 L70 88.5 L73.5 84.5 L76.5 88 " +
        "L79 70 L82.5 74.5 L86 68.5 " +
        "C90 80 89 90 78 94.5 C68 98 42 98 32 94 C23.5 90.5 21 78 24 68 Z"

// NB: `uid` from the TSX prop list has no port — the clipPath is a value here,
// not a document-namespace def (spec §4).
fun drawEggCup(c: SvgCanvas, speckle: String, suitColor: String? = null) {
    c.fill(CUP_PATH, SvgPaint.hex(EGG_FILL))
    c.stroke(CUP_PATH, SvgPaint.hex(EGG_EDGE), SvgStrokeStyle(lineWidth = 1.2, join = SvgLineJoin.ROUND))
    if (suitColor != null) {
        c.save()
        c.clip(CUP_PATH)
        c.fill(SvgShapes.rect(20.0, 83.0, 70.0, 16.0), SvgPaint.hex(suitColor))
        for (x in listOf(40.0, 56.0)) {
            c.stroke(
                "M$x 82 q 2.6 6 0 14",
                SvgPaint.hex("#FFF6EE", 0.9),
                SvgStrokeStyle(lineWidth = 2.0),
            )
        }
        c.restore()
    }
    // crack lines + speckles
    c.stroke("M39 92 L42.5 84.5 L40 78.5", SvgPaint.hex(EGG_EDGE, 0.7), SvgStrokeStyle(lineWidth = 1.0))
    c.stroke("M78 92.5 L75 86.5", SvgPaint.hex(EGG_EDGE, 0.6), SvgStrokeStyle(lineWidth = 1.0))
    c.fill(dpCircle(32.0, 84.0, 1.3), SvgPaint.hex(speckle))
    c.fill(dpCircle(71.0, 92.0, 1.2), SvgPaint.hex(speckle))
    c.fill(dpCircle(47.0, 93.5, 1.1), SvgPaint.hex(speckle))
    c.fill(dpCircle(82.0, 81.0, 1.1), SvgPaint.hex(speckle))
}

/** Half-shell cap worn on the head (the classic hatchling hat). */
fun drawShellCap(c: SvgCanvas, x: Double, y: Double, s: Double, tilt: Double, speckle: String) {
    c.save()
    c.translate(x, y)
    c.rotate(tilt)
    c.scale(s)
    val d = "M-12 1 C-11 -8 -5 -12.5 0 -12.5 C5 -12.5 11 -8 12 1 L8 5.5 L4 1.5 L0 5.8 L-4 1.5 L-8 5.5 Z"
    c.fill(d, SvgPaint.hex(EGG_FILL))
    c.stroke(d, SvgPaint.hex(EGG_EDGE), SvgStrokeStyle(lineWidth = 1.1, join = SvgLineJoin.ROUND))
    c.fill(dpCircle(3.0, -6.0, 1.2), SvgPaint.hex(speckle))
    c.fill(dpCircle(-4.5, -3.5, 0.9), SvgPaint.hex(speckle))
    c.restore()
}

/** Small shard of shell resting on the back/rump at stade 1. */
fun drawShellShard(c: SvgCanvas, x: Double, y: Double, s: Double, tilt: Double, speckle: String) {
    c.save()
    c.translate(x, y)
    c.rotate(tilt)
    c.scale(s)
    val d = "M0 0 L3 -6 L6.5 -1 L10 -5 L12 1 C8 4 3 4 0 0 Z"
    c.fill(d, SvgPaint.hex(EGG_FILL))
    c.stroke(d, SvgPaint.hex(EGG_EDGE), SvgStrokeStyle(lineWidth = 1.0, join = SvgLineJoin.ROUND))
    c.fill(dpCircle(6.0, -1.0, 0.9), SvgPaint.hex(speckle))
    c.restore()
}

// -- Wings ----------------------------------------------------------------

/**
 * Scalloped bat-style dragon wings, drawn BEHIND the body. Outlined + finger
 * ridges so they stay legible against any body colour.
 */
fun drawBatWings(c: SvgCanvas, cx: Double, cy: Double, s: Double, membrane: String, edge: String) {
    if (s <= 0) return
    fun wing(d: Double) {
        val sx = cx + d * 9
        val sy = cy
        val tx = sx + d * 21 * s
        val ty = sy - 17 * s
        val p1x = sx + d * 16 * s
        val p1y = sy - 4 * s
        val p2x = sx + d * 8.5 * s
        val p2y = sy - 0.5 * s
        val path =
            "M$sx $sy C${sx + d * 3 * s} ${sy - 12 * s} ${sx + d * 11 * s} ${sy - 18 * s} $tx $ty " +
                "Q${(tx + p1x) / 2 - d * 3 * s} ${(ty + p1y) / 2} $p1x $p1y " +
                "Q${(p1x + p2x) / 2 - d * 2 * s} ${(p1y + p2y) / 2 + 1.5 * s} $p2x $p2y " +
                "Q${(p2x + sx) / 2} ${sy + 1.5 * s} $sx $sy Z"
        c.fill(path, SvgPaint.hex(membrane))
        c.stroke(path, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 1.1, join = SvgLineJoin.ROUND))
        c.group(0.5) { g ->
            g.stroke(
                "M${sx + d * 2 * s} ${sy - 2 * s} L${tx - d * 2 * s} ${ty + 2 * s}",
                SvgPaint.hex(edge),
                SvgStrokeStyle(lineWidth = 0.8),
            )
            g.stroke(
                "M${sx + d * 2 * s} ${sy - s} L${p1x - d * 1.5 * s} ${p1y - s}",
                SvgPaint.hex(edge),
                SvgStrokeStyle(lineWidth = 0.8),
            )
        }
    }
    wing(-1.0)
    wing(1.0)
}

// -- Tail -----------------------------------------------------------------

/**
 * The reachable arms of the TSX `tip` union: `"spade" | "club" | "flame"`.
 * `"bolt"` and `"none"` have no runtime path from Dragon.tsx (spec §8.6); an
 * unknown `tailStyle` string from an old profile maps to null, which draws the
 * curve with no tip — exactly what the TSX conditionals do when no arm matches.
 */
enum class DragonTailTip(val wire: String) {
    SPADE("spade"),
    CLUB("club"),
    FLAME("flame"),
    ;

    companion object {
        /** Null for an unknown string — old-profile data to fall through, not a crash. */
        fun fromWire(wire: String): DragonTailTip? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Quadratic tail stroke ending in a spade / mace-club / flame tip that follows
 * the curve's tangent.
 */
// NB: the `gem` inset and the "bolt" arm are not ported (spec §8.6).
fun drawSpadeTail(
    c: SvgCanvas,
    p0: Pair<Double, Double>,
    p1: Pair<Double, Double>,
    p2: Pair<Double, Double>,
    w: Double,
    color: String,
    /** Outline colour — a same-as-body tail vanishes into the silhouette without it. */
    edge: String? = null,
    tip: DragonTailTip? = DragonTailTip.SPADE,
    tipColor: String? = null,
    tipS: Double = 1.0,
) {
    val ang = atan2(p2.second - p1.second, p2.first - p1.first) * 180 / PI
    val curve = "M${p0.first} ${p0.second} Q${p1.first} ${p1.second} ${p2.first} ${p2.second}"
    if (edge != null) {
        c.stroke(curve, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = w + 2.4, cap = SvgLineCap.ROUND))
    }
    c.stroke(curve, SvgPaint.hex(color), SvgStrokeStyle(lineWidth = w, cap = SvgLineCap.ROUND))
    when (tip) {
        DragonTailTip.SPADE -> {
            c.save()
            c.translate(p2.first, p2.second)
            c.rotate(ang)
            c.scale(tipS)
            val d = "M7 0 L-2.5 -5 Q-0.5 0 -2.5 5 Z"
            c.fill(d, SvgPaint.hex(tipColor ?: color))
            // strokeWidth={edge ? 1 : 0} — width 0 records nothing (SvgCanvas.stroke).
            c.stroke(
                d,
                if (edge != null) SvgPaint.hex(edge) else SvgPaint.None,
                SvgStrokeStyle(lineWidth = if (edge != null) 1.0 else 0.0, join = SvgLineJoin.ROUND),
            )
            c.restore()
        }

        DragonTailTip.CLUB ->
            drawTailClub(c, p2.first, p2.second, tipS, tipColor ?: color, edge ?: INK)

        DragonTailTip.FLAME -> {
            c.fill(dpCircle(p2.first, p2.second, w * 0.55), SvgPaint.hex(color))
            c.stroke(
                dpCircle(p2.first, p2.second, w * 0.55),
                if (edge != null) SvgPaint.hex(edge) else SvgPaint.None,
                SvgStrokeStyle(lineWidth = if (edge != null) 1.0 else 0.0),
            )
            drawFlamePuff(c, p2.first, p2.second, tipS * 0.42, ang + 90)
        }

        null -> Unit
    }
}

// -- Head gear ------------------------------------------------------------

/**
 * The reachable arms of the TSX `variant` union — "curly" is not ported
 * (spec §8.6; Dragon.tsx only ever selects straight or double).
 */
enum class DragonHornVariant(val wire: String) {
    STRAIGHT("straight"),
    DOUBLE("double"),
}

/**
 * Two little horns on the dome. Straight cones or the double pair (a big cone
 * + a smaller one in front — the classic dragon crown of four horns).
 */
fun drawHorns(
    c: SvgCanvas,
    hx: Double,
    hy: Double,
    headR: Double,
    h: Double,
    variant: DragonHornVariant = DragonHornVariant.STRAIGHT,
    color: String,
    edge: String,
    tipDot: String? = null,
) {
    if (h <= 0) return
    fun horn(d: Double) {
        val bx = hx + d * headR * 0.52
        val by = hy - headR * 0.72
        if (variant == DragonHornVariant.DOUBLE) {
            // Small pair OUTSIDE and below the big one, at the silhouette edge —
            // tucked between the horns it drowns in the head fill and the crest.
            fun cone(cx: Double, cy: Double, hh: Double, ww: Double, lean: Double): String =
                "M${cx - d * ww} ${cy + 1.5} Q${cx + d * hh * 0.02} ${cy - hh * 0.6} ${cx + d * hh * lean} ${cy - hh} Q${cx + d * (ww + hh * 0.18)} ${cy - hh * 0.4} ${cx + d * ww} ${cy + 1.5} Z"

            val w = headR * 0.12 + h * 0.055
            val small = cone(hx + d * headR * 0.82, hy - headR * 0.42, h * 0.62, w * 0.72, 0.85)
            c.fill(small, SvgPaint.hex(color))
            c.stroke(small, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.8, join = SvgLineJoin.ROUND))
            val big = cone(bx, by, h, w, 0.5)
            c.fill(big, SvgPaint.hex(color))
            c.stroke(big, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.8, join = SvgLineJoin.ROUND))
            if (tipDot != null) {
                c.fill(dpCircle(bx + d * h * 0.5, by - h, 1.7), SvgPaint.hex(tipDot))
                c.stroke(dpCircle(bx + d * h * 0.5, by - h, 1.7), SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.5))
            }
            return
        }
        val w = headR * 0.13 + h * 0.06
        val tx = bx + d * h * 0.45
        val ty = by - h
        val d2 =
            "M${bx - d * w} ${by + 1.5} Q${bx + d * h * 0.02} ${by - h * 0.6} $tx $ty Q${bx + d * (w + h * 0.16)} ${by - h * 0.45} ${bx + d * w} ${by + 1.5} Z"
        c.fill(d2, SvgPaint.hex(color))
        c.stroke(d2, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.8, join = SvgLineJoin.ROUND))
        if (tipDot != null) {
            c.fill(dpCircle(tx, ty, 1.7), SvgPaint.hex(tipDot))
            c.stroke(dpCircle(tx, ty, 1.7), SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.5))
        }
    }
    horn(-1.0)
    horn(1.0)
}

/**
 * Row of crest bumps fanned along the head dome, between the horns.
 * `round` swaps pointy triangles for soft bubbles; `scale` grows the bumps
 * (bone back-plates are big rounded ones).
 */
// NB: the `gems` option is not ported — unreachable from Dragon.tsx and the
// only in-rig consumer of `Gem` (spec §8.6).
fun drawCrest(
    c: SvgCanvas,
    hx: Double,
    hy: Double,
    headR: Double,
    n: Int,
    round: Boolean = false,
    color: String,
    edge: String,
    scale: Double = 1.0,
) {
    if (n <= 0) return
    for (i in 0 until n) {
        val t = if (n == 1) 0.5 else i.toDouble() / (n - 1)
        val a = (t - 0.5) * 76 * PI / 180
        val rx = sin(a)
        val ry = -cos(a)
        val bx = hx + rx * headR * 0.9
        val by = hy + ry * headR * 0.88
        val hgt = headR * (0.36 - 0.08 * abs(t - 0.5) * 2) * scale
        val px = -ry
        val py = rx
        val wHalf = headR * 0.12 * scale
        if (round) {
            c.fill(dpCircle(bx + rx * hgt * 0.42, by + ry * hgt * 0.42, headR * 0.14 * scale), SvgPaint.hex(color))
            c.stroke(
                dpCircle(bx + rx * hgt * 0.42, by + ry * hgt * 0.42, headR * 0.14 * scale),
                SvgPaint.hex(edge),
                SvgStrokeStyle(lineWidth = 0.8),
            )
            continue
        }
        val tx = bx + rx * hgt
        val ty = by + ry * hgt
        val d = "M${bx - px * wHalf} ${by - py * wHalf} L$tx $ty L${bx + px * wHalf} ${by + py * wHalf} Z"
        c.fill(d, SvgPaint.hex(color))
        c.stroke(d, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.8, join = SvgLineJoin.ROUND))
    }
}

// -- Belly / scales ---------------------------------------------------------

/** Reptile plate lines across the belly ellipse (chord-width, no clip needed). */
fun drawBellyPlates(c: SvgCanvas, cx: Double, cy: Double, rx: Double, ry: Double, line: String) {
    c.group(0.6) { g ->
        for (k in listOf(-0.25, 0.12, 0.48)) {
            val halfW = rx * sqrt(1 - k * k) * 0.9
            g.stroke(
                "M${cx - halfW} ${cy + ry * k} Q$cx ${cy + ry * k + 2.6} ${cx + halfW} ${cy + ry * k}",
                SvgPaint.hex(line),
                SvgStrokeStyle(lineWidth = 1.3, cap = SvgLineCap.ROUND),
            )
        }
    }
}

// -- Fire / storm / treasure bits ------------------------------------------

/**
 * Small two-tone kawaii flame (points up at rot=0). Recolourable for the
 * legendary blue breath.
 */
fun drawFlamePuff(
    c: SvgCanvas,
    x: Double,
    y: Double,
    s: Double,
    rot: Double = 0.0,
    outer: String = "#FF7043",
    inner: String = "#FFE082",
) {
    c.save()
    c.translate(x, y)
    c.rotate(rot)
    c.scale(s)
    c.fill("M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z", SvgPaint.hex(outer))
    c.fill("M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z", SvgPaint.hex(inner))
    c.restore()
}

// -- Boy-coded parts (reboot) -----------------------------------------------

/** Spiky mace-ball tail tip (drawn at p2 of a SpadeTail with tip="none"). */
fun drawTailClub(c: SvgCanvas, x: Double, y: Double, s: Double, color: String, edge: String) {
    for (i in 0 until 7) {
        val a = i.toDouble() / 7 * PI * 2 - PI / 2
        val bx = x + cos(a) * 3.1 * s
        val by = y + sin(a) * 3.1 * s
        val tx = x + cos(a) * 5.4 * s
        val ty = y + sin(a) * 5.4 * s
        val px = -sin(a) * 1.15 * s
        val py = cos(a) * 1.15 * s
        val d = "M${bx - px} ${by - py} L$tx $ty L${bx + px} ${by + py} Z"
        c.fill(d, SvgPaint.hex(color))
        c.stroke(d, SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.7, join = SvgLineJoin.ROUND))
    }
    c.fill(dpCircle(x, y, 3.4 * s), SvgPaint.hex(color))
    c.stroke(dpCircle(x, y, 3.4 * s), SvgPaint.hex(edge), SvgStrokeStyle(lineWidth = 0.9))
    c.fill(dpCircle(x - s, y - s, 0.9 * s), SvgPaint.hex("#FFFFFF", 0.45))
}

/** Glowing lava/charge cracks — short zigzags on the body flanks. */
fun drawCracks(c: SvgCanvas, cx: Double, cy: Double, rx: Double, ry: Double, color: String) {
    fun zig(x: Double, y: Double, d: Double): String =
        "M$x $y l${2.4 * d} -2.6 l${2.4 * d} 2.6 l${2.4 * d} -2.6"

    val style = SvgStrokeStyle(lineWidth = 1.7, cap = SvgLineCap.ROUND, join = SvgLineJoin.ROUND)
    c.stroke(zig(cx - rx * 0.88, cy - ry * 0.25, 1.0), SvgPaint.hex(color), style)
    c.stroke(zig(cx + rx * 0.35, cy - ry * 0.55, 1.0), SvgPaint.hex(color), style)
    c.stroke(zig(cx - rx * 0.5, cy + ry * 0.42, 1.0), SvgPaint.hex(color, 0.85), style)
}

/**
 * Two little smoke curls drifting OUTWARD from the nostrils — mostly sideways
 * so they clear the eyes (rising straight up read as tears next to them).
 */
fun drawSmokePuffs(c: SvgCanvas, hx: Double, hy: Double, headR: Double) {
    fun puff(d: Double) {
        val x = hx + d * headR * 0.24
        val y = hy + headR * 0.3
        c.group(0.85) { g ->
            g.fill(dpCircle(x + d * headR * 0.28, y - 1, 1.4), SvgPaint.hex("#C3C7CE"))
            g.fill(dpCircle(x + d * headR * 0.52, y - 3, 1.9), SvgPaint.hex("#C3C7CE"))
            g.fill(dpCircle(x + d * headR * 0.82, y - 5.6, 2.5), SvgPaint.hex("#C3C7CE", 0.8))
        }
    }
    puff(-1.0)
    puff(1.0)
}

/** White claw nicks on a foot (drawn over the dark hoof). */
fun drawClaws(c: SvgCanvas, x: Double, y: Double, w: Double) {
    c.group(0.95) { g ->
        for (f in listOf(-0.3, 0.12, 0.54)) {
            g.fill("M${x + f * w} ${y - 1} l1.1 2.6 l1.1 -2.6 Z", SvgPaint.hex("#FFFFFF"))
        }
    }
}

/** Two tiny fangs peeking from the mouth corners. */
fun drawFangs(c: SvgCanvas, cx: Double, y: Double, w: Double) {
    val style = SvgStrokeStyle(lineWidth = 0.35)
    val left = "M${cx - w} ${y - 0.6} l0.9 3 l1.5 -2.4 Z"
    val right = "M${cx + w} ${y - 0.6} l-0.9 3 l-1.5 -2.4 Z"
    c.fill(left, SvgPaint.hex("#FFFFFF"))
    c.stroke(left, SvgPaint.hex(INK), style)
    c.fill(right, SvgPaint.hex("#FFFFFF"))
    c.stroke(right, SvgPaint.hex(INK), style)
}

// -- Knight accessories (items run, candidate A) ------------------------------

internal const val CAPE = "#C64F4F"
internal const val CAPE_EDGE = "#8F3535"
// NB: METAL / METAL_EDGE not ported — only the skipped KnightHelmet used them.
internal const val GOLD_TRIM = "#F2C14E"
internal const val GOLD_TRIM_EDGE = "#B07E1E"

/** Hero cape hanging from the shoulders, drawn BEHIND the body. */
fun drawCapeBack(c: SvgCanvas, cx: Double, topY: Double, w: Double, h: Double) {
    val d =
        "M${cx - w * 0.62} $topY Q$cx ${topY - 3} ${cx + w * 0.62} $topY C${cx + w * 0.9} ${topY + h * 0.55} ${cx + w * 0.82} ${topY + h * 0.9} ${cx + w * 0.7} ${topY + h} L${cx + w * 0.28} ${topY + h - 2} L$cx ${topY + h} L${cx - w * 0.28} ${topY + h - 2} L${cx - w * 0.7} ${topY + h} C${cx - w * 0.82} ${topY + h * 0.9} ${cx - w * 0.9} ${topY + h * 0.55} ${cx - w * 0.62} $topY Z"
    c.fill(d, SvgPaint.hex(CAPE))
    c.stroke(d, SvgPaint.hex(CAPE_EDGE), SvgStrokeStyle(lineWidth = 1.1, join = SvgLineJoin.ROUND))
}

/** The cape's gold clasp + throat cord, drawn OVER the chest. */
fun drawCapeClasp(c: SvgCanvas, x: Double, y: Double, w: Double) {
    c.stroke(
        "M${x - w} ${y - 1} Q$x ${y + 2.5} ${x + w} ${y - 1}",
        SvgPaint.hex(GOLD_TRIM),
        SvgStrokeStyle(lineWidth = 1.8, cap = SvgLineCap.ROUND),
    )
    c.fill(dpCircle(x, y + 1.6, 2.5), SvgPaint.hex(GOLD_TRIM))
    c.stroke(dpCircle(x, y + 1.6, 2.5), SvgPaint.hex(GOLD_TRIM_EDGE), SvgStrokeStyle(lineWidth = 0.8))
    c.fill(dpCircle(x - 0.7, y + 0.9, 0.8), SvgPaint.hex("#FFFDF4", 0.8))
}

// -- Accessories ------------------------------------------------------------

// -- The treasure hoard ------------------------------------------------------
// Design rule (user): a coin or a jewel NEVER changes size with growth — only
// the QUANTITY does: 0-2 a single gold coin · 3-6 gold + jewellery · 7-9 the
// full hoard, jewels set with precious stones.
// Art direction (DA jeunesse redlines): three golds and NO olive outlines —
// model with flat tones like the dragon itself; coins are cylinders (edge
// offset), the upright coin wears an engraved star; the heap is a tight 3:2
// shingled mound (never a ribbon); one shared ground shadow anchors the hoard
// in the dragon's world; gems are two-tone with tone-on-tone dark rims (white
// halos die on the cream background); sparkles are GOLD, white only on gold.
// At 7-9 the hoard gets its wooden chest: same three-tone modelling in wood,
// mound brimming over the rim, and everything that overflows (upright coin,
// spilled coins, ring) tumbles out on the DRAGON's side — he guards it.

internal const val OR_LIGHT = "#FFE08A"
internal const val OR = "#FFC94D"
internal const val OR_DEEP = "#E09B3A"
internal const val OR_EDGE = "#C9822E"
internal const val OR_PALE = "#FFDB6E"
internal const val RUBY = "#E0533B"
internal const val SAPPHIRE = "#5E7EB5"
internal const val EMERALD = "#4CAF7D"
internal const val AMETHYST = "#9575CD"

internal val GEM_DARK: Map<String, String> = mapOf(
    RUBY to "#B23B28",
    SAPPHIRE to "#46618F",
    EMERALD to "#37835C",
    AMETHYST to "#74569F",
)

internal const val COIN_R = 2.8
internal const val COIN_RY = 1.15

/** Lying coin = a CYLINDER (edge slice under the face), not an outlined pill. */
internal fun drawTCoin(c: SvgCanvas, cx: Double, cy: Double) {
    c.fill(dpEllipse(cx, cy + 0.7, COIN_R, COIN_RY), SvgPaint.hex(OR_DEEP))
    c.fill(dpEllipse(cx, cy, COIN_R, COIN_RY), SvgPaint.hex(OR))
    c.stroke(dpEllipse(cx, cy, 1.64, 0.67), SvgPaint.hex(OR_DEEP), SvgStrokeStyle(lineWidth = 0.45))
    c.fill(dpEllipse(cx - 0.95, cy - 0.35, 0.7, 0.3), SvgPaint.hex(OR_LIGHT))
}

/**
 * The storybook coin: upright, engraved star, shaded rim — leans on the heap
 * once there is one (`lean` in degrees, pivot at its ground contact).
 */
internal fun drawTUprightCoin(c: SvgCanvas, cx: Double, cy: Double, lean: Double = 0.0) {
    c.save()
    c.rotate(lean, cx, cy + COIN_R)
    c.fill(dpCircle(cx, cy, COIN_R), SvgPaint.hex(OR))
    c.stroke(dpCircle(cx, cy, COIN_R), SvgPaint.hex(OR_EDGE), SvgStrokeStyle(lineWidth = 0.35))
    c.fill(dpCircle(cx, cy, 2.18), SvgPaint.hex(OR_PALE))
    // bottom-right shading crescent between rim and inner disc
    c.fill(
        "M${cx + 2.63} ${cy + 0.96} A2.8 2.8 0 0 1 ${cx - 0.49} ${cy + 2.76} L${cx - 0.38} ${cy + 2.15} A2.18 2.18 0 0 0 ${cx + 2.05} ${cy + 0.75} Z",
        SvgPaint.hex(OR_DEEP, 0.5),
    )
    c.fill(
        "M$cx ${cy - 1.58} L${cx + 0.49} ${cy - 0.49} L${cx + 1.58} $cy L${cx + 0.49} ${cy + 0.49} L$cx ${cy + 1.58} L${cx - 0.49} ${cy + 0.49} L${cx - 1.58} $cy L${cx - 0.49} ${cy - 0.49} Z",
        SvgPaint.hex(OR_DEEP),
    )
    c.fill(dpCircle(cx - 0.92, cy - 0.92, 0.48), SvgPaint.hex("#FFF6D8"))
    c.restore()
}

/** Two-tone faceted gem, tone-on-tone dark rim (no white halo on cream bg). */
// NB: `GEM_DARK[color]` can miss for a colour outside the four gems — the TSX
// then renders the facet with SVG's default fill (black) and no rim stroke.
// Unreachable from the slot tables, but mirrored rather than "fixed".
internal fun drawTGem(c: SvgCanvas, x: Double, y: Double, s: Double, color: String) {
    val dark = GEM_DARK[color]
    val body = "M$x ${y - s} L${x + s * 0.85} $y L$x ${y + s} L${x - s * 0.85} $y Z"
    c.fill(body, SvgPaint.hex(color))
    c.stroke(
        body,
        if (dark != null) SvgPaint.hex(dark) else SvgPaint.None,
        SvgStrokeStyle(lineWidth = 0.3, join = SvgLineJoin.ROUND),
    )
    c.fill("M${x - s * 0.85} $y L$x ${y + s} L${x + s * 0.85} $y Z", SvgPaint.hex(dark ?: "#000000", 0.35))
    c.fill(dpCircle(x - s * 0.2, y - s * 0.3, s * 0.2), SvgPaint.hex("#FFFFFF", 0.85))
}

/** Gold ring: golden band with rim light + contact shade, mounted stone at 7+. */
internal fun drawTRing(c: SvgCanvas, cx: Double, cy: Double, gem: Boolean = false) {
    c.stroke(dpCircle(cx, cy, 1.9), SvgPaint.hex(OR), SvgStrokeStyle(lineWidth = 1.4))
    c.stroke(
        "M${cx - 1.9} $cy A1.9 1.9 0 0 0 ${cx + 1.9} $cy",
        SvgPaint.hex(OR_DEEP),
        SvgStrokeStyle(lineWidth = 0.5),
    )
    c.stroke(
        "M${cx - 1.9} $cy A1.9 1.9 0 0 1 $cx ${cy - 1.9}",
        SvgPaint.hex(OR_LIGHT),
        SvgStrokeStyle(lineWidth = 0.4),
    )
    if (gem) drawTGem(c, cx, cy - 2.6, 1.0, RUBY)
}

/**
 * Gold crown: base band + three peaks with light-gold balls + one set ruby.
 * `y` is the band's bottom centre; tilted a touch (a crooked crown is cuter).
 */
internal fun drawTCrown(c: SvgCanvas, x: Double, y: Double) {
    val s = 1.15
    val bandTop = y - 1.0
    c.save()
    c.rotate(-6.0, x, y - 1.7)
    c.fill(
        "M${x - 2.8 * s} ${bandTop + 0.1} L${x - 1.87 * s} ${bandTop - 1.9 * s} L${x - 0.93 * s} ${bandTop - 0.4 * s} L$x ${bandTop - 2.55 * s} L${x + 0.93 * s} ${bandTop - 0.4 * s} L${x + 1.87 * s} ${bandTop - 1.9 * s} L${x + 2.8 * s} ${bandTop + 0.1} Z",
        SvgPaint.hex(OR),
    )
    c.fill(dpCircle(x - 1.87 * s, bandTop - 1.9 * s, 0.5), SvgPaint.hex(OR_PALE))
    c.fill(dpCircle(x, bandTop - 2.55 * s, 0.5), SvgPaint.hex(OR_PALE))
    c.fill(dpCircle(x + 1.87 * s, bandTop - 1.9 * s, 0.5), SvgPaint.hex(OR_PALE))
    c.fill(SvgShapes.roundedRect(x - 2.8 * s, bandTop, 5.6 * s, 1.0, 0.4), SvgPaint.hex(OR))
    c.stroke(
        "M${x - 2.4 * s} ${y - 0.12} L${x + 2.4 * s} ${y - 0.12}",
        SvgPaint.hex(OR_DEEP),
        SvgStrokeStyle(lineWidth = 0.4, cap = SvgLineCap.ROUND),
    )
    c.fill(dpCircle(x, y - 0.5, 0.6), SvgPaint.hex(RUBY))
    c.restore()
}

// Wooden chest — three wood tones, mirroring the three golds (plus the named
// base-shade token so no blended fourth tone hides in an opacity).
internal const val WOOD_LT = "#C9985F"
internal const val WOOD = "#AF7A45"
internal const val WOOD_DK = "#8A5C33"
internal const val WOOD_SHADE = "#9A6A3E"

/**
 * Open lid, tipped back a touch (crooked like the crown — cuter). We see its
 * wooden edge and the darker inside face. Drawn BEHIND the coin mound.
 */
internal fun drawTChestLid(c: SvgCanvas, x: Double, rimY: Double) {
    // Tall enough that the lid + dark inside stay readable ABOVE the full s9
    // mound (rows top out at rimY-4.95) — the open-lid read must survive the
    // climax stade. The low corner sinks behind the front wall, no overhang.
    c.save()
    c.rotate(-9.0, x, rimY)
    c.fill(
        "M${x - 7.2} ${rimY - 0.2} L${x - 7.2} ${rimY - 6.2} Q${x - 7.2} ${rimY - 8.4} ${x - 4.6} ${rimY - 8.4} L${x + 4.6} ${rimY - 8.4} Q${x + 7.2} ${rimY - 8.4} ${x + 7.2} ${rimY - 6.2} L${x + 7.2} ${rimY - 0.2} Z",
        SvgPaint.hex(WOOD),
    )
    c.fill(
        "M${x - 6.3} ${rimY - 0.2} L${x - 6.3} ${rimY - 5.9} Q${x - 6.3} ${rimY - 7.3} ${x - 4.6} ${rimY - 7.3} L${x + 4.6} ${rimY - 7.3} Q${x + 6.3} ${rimY - 7.3} ${x + 6.3} ${rimY - 5.9} L${x + 6.3} ${rimY - 0.2} Z",
        SvgPaint.hex(WOOD_DK),
    )
    c.restore()
}

/**
 * Chest front wall + gold straps + lock. Drawn AFTER the mound so the bottom
 * coin row sinks behind the rim — the chest reads as FULL, not decorated.
 */
internal fun drawTChestBody(c: SvgCanvas, x: Double, rimY: Double, groundY: Double) {
    val h = groundY - rimY
    c.fill(SvgShapes.roundedRect(x - 7.5, rimY, 15.0, h, 1.1), SvgPaint.hex(WOOD))
    c.fill(SvgShapes.roundedRect(x - 7.3, groundY - 1.4, 14.6, 1.4, 0.7), SvgPaint.hex(WOOD_SHADE))
    c.fill(SvgShapes.roundedRect(x - 7.5, rimY, 15.0, 1.1, 0.55), SvgPaint.hex(WOOD_LT))
    c.fill(SvgShapes.rect(x - 5.0, rimY, 1.7, h), SvgPaint.hex(OR))
    c.fill(SvgShapes.rect(x + 3.3, rimY, 1.7, h), SvgPaint.hex(OR))
    c.fill(SvgShapes.rect(x - 5.0, rimY, 1.7, 1.1), SvgPaint.hex(OR_DEEP, 0.5))
    c.fill(SvgShapes.rect(x + 3.3, rimY, 1.7, 1.1), SvgPaint.hex(OR_DEEP, 0.5))
    c.fill(SvgShapes.roundedRect(x - 1.5, rimY + 1.9, 3.0, 3.4, 1.0), SvgPaint.hex(OR))
    c.fill(dpCircle(x, rimY + 3.1, 0.55), SvgPaint.hex(OR_EDGE))
    c.fill("M$x ${rimY + 3.3} L${x - 0.45} ${rimY + 4.5} L${x + 0.45} ${rimY + 4.5} Z", SvgPaint.hex(OR_EDGE))
}

/**
 * Strand of little gold pearls draped over the chest's LEFT front corner —
 * the "it overflows" storytelling beat, joins at stade 8.
 */
internal fun drawTBeads(c: SvgCanvas, x: Double, rimY: Double) {
    // A strand, not rivets: the thread ties the pearls together, the first two
    // sit on the rim (it climbs OUT of the chest) and the last one touches the
    // ground — that contact is the "it overflows" read.
    val pts = listOf(
        -4.6 to -0.9, -5.2 to -0.5, -5.7 to -0.2, -6.3 to 0.9, -6.7 to 2.1, -6.9 to 3.3, -6.8 to 4.5, -6.6 to 5.9,
    )
    c.stroke(
        "M${x + pts[0].first} ${rimY + pts[0].second} " +
            pts.drop(1).joinToString(" ") { (dx, dy) -> "L${x + dx} ${rimY + dy}" },
        SvgPaint.hex(OR_EDGE),
        SvgStrokeStyle(lineWidth = 0.35, join = SvgLineJoin.ROUND),
    )
    for ((dx, dy) in pts) {
        c.fill(dpCircle(x + dx, rimY + dy, 0.85), SvgPaint.hex(OR_PALE))
        c.stroke(dpCircle(x + dx, rimY + dy, 0.85), SvgPaint.hex(OR_EDGE), SvgStrokeStyle(lineWidth = 0.25))
    }
}

/**
 * Mound slots INSIDE the chest, dy relative to the rim. Base row fills first —
 * a treasure chest always reads full; growth adds the rows that overflow.
 */
internal val CHEST_SLOTS: List<Pair<Double, Double>> = listOf(
    0.0 to -0.4, -2.9 to -0.3, 2.9 to -0.3, -5.4 to -0.1, 5.4 to -0.1,
    -1.45 to -2.1, 1.45 to -2.1, -4.2 to -1.9, 4.2 to -1.9,
    0.0 to -3.8, -2.8 to -3.6, 2.8 to -3.6,
)

/**
 * Coins tumbled out on the ground, dragon's side, dy relative to ground.
 * Each stays ≥40% visible beside the hero coin — a spill nobody can see is
 * a spill that does not exist: s7 against the wall, s8 behind the ring,
 * s9 perched on the wall corner.
 */
internal val SPILL_SLOTS: List<Pair<Double, Double>> = listOf(
    7.4 to -1.1, 12.8 to -1.8, 6.8 to -2.9,
)

/**
 * Gems for the chest stages — on the mound, then one on the spill pile.
 * dy relative to groundY (rim sits at -6.6).
 */
internal val CHEST_GEM_SLOTS: List<Triple<Double, Double, String>> = listOf(
    Triple(1.5, -9.9, RUBY),
    Triple(-3.0, -9.7, SAPPHIRE),
    Triple(4.6, -9.2, EMERALD),
    Triple(-1.4, -11.5, AMETHYST),
    Triple(6.2, -7.2, SAPPHIRE),
)

/**
 * Heap slots — tight 2.9/1.8 shingle so any N reads as a 3:2 mound, never a
 * ribbon. Fill order grows a balanced pyramid (centre out, up before wide).
 */
internal val COIN_SLOTS: List<Pair<Double, Double>> = listOf(
    0.0 to -1.1, -2.9 to -1.1, -1.45 to -2.9,
    2.9 to -1.1, 1.45 to -2.9,
    -5.8 to -1.1, 5.8 to -1.1,
    0.0 to -4.7,
    -4.35 to -2.9, 4.35 to -2.9,
    -2.9 to -4.7, 2.9 to -4.7,
    0.0 to -6.5,
)

/**
 * Loose gems, in appearance order — every one seated on a coin that is filled
 * by the stade it appears at, or nestled against the mound's base.
 */
internal val GEM_SLOTS: List<Triple<Double, Double, String>> = listOf(
    Triple(3.1, -3.3, RUBY),
    Triple(-3.1, -3.3, SAPPHIRE),
    Triple(8.0, -1.2, EMERALD),
    Triple(-6.6, -1.0, AMETHYST),
    Triple(6.0, -3.2, SAPPHIRE),
)

internal data class TSpec(
    val coins: Int,
    val ring: Boolean = false,
    val crown: Boolean = false,
    val gems: Int,
    val chest: Boolean = false,
    val spill: Int = 0,
    val beads: Boolean = false,
)

internal val T_STAGES: List<TSpec> = listOf(
    TSpec(coins = 1, gems = 0), // 0 une simple pièce d'or
    TSpec(coins = 1, gems = 0),
    TSpec(coins = 1, gems = 0),
    TSpec(coins = 3, ring = true, gems = 0), // 3 l'or + le premier bijou
    TSpec(coins = 4, ring = true, gems = 0),
    TSpec(coins = 5, ring = true, crown = true, gems = 0), // 5 la couronne rejoint le butin
    TSpec(coins = 6, ring = true, crown = true, gems = 0),
    TSpec(coins = 7, ring = true, crown = true, gems = 2, chest = true, spill = 1), // 7 le coffre !
    TSpec(coins = 9, ring = true, crown = true, gems = 3, chest = true, spill = 2, beads = true),
    TSpec(coins = 12, ring = true, crown = true, gems = 5, chest = true, spill = 3, beads = true), // 9 il déborde
)

/** y of the highest filled coin slot, for seating the crown on the heap. */
internal fun heapTopY(coins: Int): Double {
    if (coins >= 13) return -6.5
    if (coins >= 8) return -4.7
    if (coins >= 3) return -2.9
    return -1.1
}

/**
 * `[...slots.slice(0, n)].sort((a, b) => a[1] - b[1])` — JS `sort` is stable
 * (ES2019) and adjacent coins in a row OVERLAP by design, so ties must keep
 * authored order or the shingling flips. Kotlin's `sortedBy` is documented
 * stable ("the sort is stable"), so unlike Swift no index tie-break is needed —
 * but the function stays named for what it guarantees, and the test pins it.
 */
internal fun stableSortedByDY(slots: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
    slots.sortedBy { it.second }

/** The treasure accessory. Element size is constant; stage drives quantity. */
fun drawTreasure(c: SvgCanvas, stage: Int, x: Double, groundY: Double) {
    val spec = T_STAGES[stage.coerceIn(0, 9)]
    val set = stage >= 7 // jewels get their precious stones
    if (spec.chest) {
        val rimY = groundY - 6.6
        val top = if (spec.coins >= 10) -3.8 else -2.1 // highest filled mound row
        // Everything that overflows goes on the DRAGON's side: standing coin
        // leaning on the right wall, spilled coins around it, ring past them.
        val upX = 9.7
        val ringX = 13.4
        val left = -8.2
        val right = ringX + 2.6
        c.fill(
            dpEllipse(x + (left + right) / 2, groundY + 0.5, (right - left) / 2 + 1.5, 1.3),
            SvgPaint.hex("#000", 0.1),
        )
        drawTChestLid(c, x, rimY)
        for ((dx, dy) in stableSortedByDY(CHEST_SLOTS.take(spec.coins))) {
            drawTCoin(c, x + dx, rimY + dy)
        }
        if (spec.crown) drawTCrown(c, x + 0.6, rimY + top - 1.2)
        drawTChestBody(c, x, rimY, groundY)
        if (spec.beads) drawTBeads(c, x, rimY)
        for ((dx, dy) in stableSortedByDY(SPILL_SLOTS.take(spec.spill))) {
            drawTCoin(c, x + dx, groundY + dy)
        }
        // the hero coin stays IN FRONT of the spill — its star face is the read
        drawTUprightCoin(c, x + upX, groundY - COIN_R, -10.0)
        drawTRing(c, x + ringX, groundY - 1.9, gem = true)
        for ((dx, dy, color) in CHEST_GEM_SLOTS.take(spec.gems)) {
            drawTGem(c, x + dx, groundY + dy, 1.3, color)
        }
        drawSparkles(
            c,
            listOf(
                Triple(x - 4.8, rimY + top - 4.3, 1.1),
                Triple(x + 4.2, rimY + top - 5.7, 0.85),
            ),
            OR,
        )
        // white glint allowed ONLY on gold, never on the bare background
        drawSparkles(c, listOf(Triple(x + 0.9, rimY - 1.6, 0.7)), "#FFFFFF")
        return
    }
    if (spec.coins == 1) {
        c.fill(dpEllipse(x, groundY + 0.6, 2.9, 0.7), SvgPaint.hex("#000", 0.1))
        drawTUprightCoin(c, x, groundY - COIN_R)
        drawSparkles(c, listOf(Triple(x + 3.0, groundY - 7.0, 0.85)), OR)
        return
    }
    // Side pieces hug the mound: the upright coin leans on row 1's left edge,
    // the ring sits just past it, AWAY from the dragon (no more donut-on-toe).
    val leftRow = if (spec.coins >= 6) -5.8 else -2.9
    val upX = leftRow - COIN_R * 2 + 1
    val ringX = if (spec.ring) upX - COIN_R - 1.9 else upX
    val left = ringX - 1.9
    val right = (if (spec.coins >= 6) 5.8 else if (spec.coins >= 4) 2.9 else 0.0) + COIN_R + (if (set) 1.4 else 0.0)
    // one shared ground shadow — same token as the pet's, glues it to the floor
    c.fill(
        dpEllipse(x + (left + right) / 2, groundY + 0.5, (right - left) / 2 + 2, 1.2),
        SvgPaint.hex("#000", 0.1),
    )
    for ((dx, dy) in stableSortedByDY(COIN_SLOTS.take(spec.coins))) {
        drawTCoin(c, x + dx, groundY + dy)
    }
    drawTUprightCoin(c, x + upX, groundY - COIN_R, -12.0)
    if (spec.ring) drawTRing(c, x + ringX, groundY - 1.9, gem = set)
    for ((dx, dy, color) in GEM_SLOTS.take(spec.gems)) {
        drawTGem(c, x + dx, groundY + dy, 1.3, color)
    }
    if (spec.crown) drawTCrown(c, x, groundY + heapTopY(spec.coins) - 1.2)
    drawSparkles(
        c,
        listOf(
            Triple(x - 4.8, groundY + heapTopY(spec.coins) - 4.4, 1.1),
            Triple(x + 3.6, groundY + heapTopY(spec.coins) - 6.0, 0.85),
        ),
        OR,
    )
    // white glint allowed ONLY on gold, never on the bare background
    if (set) drawSparkles(c, listOf(Triple(x + 1.5, groundY - 3.1, 0.7)), "#FFFFFF")
}

/**
 * Cord necklace with a little white fang pendant, hung at the throat anchor
 * (his first baby fang, kept as a trophy).
 */
fun drawFangPendant(c: SvgCanvas, x: Double, y: Double, w: Double) {
    c.stroke(
        "M${x - w} ${y - 1.2} Q$x ${y + 2.2} ${x + w} ${y - 1.2}",
        SvgPaint.hex("#8D5A3B"),
        SvgStrokeStyle(lineWidth = 1.6, cap = SvgLineCap.ROUND),
    )
    val fang = "M${x - 1.9} ${y + 1.2} Q${x - 1.7} ${y + 6.2} ${x + 0.6} ${y + 7.6} Q${x + 2} ${y + 4} ${x + 1.5} ${y + 1}"
    c.fill(fang, SvgPaint.hex("#FFFDF4"))
    c.stroke(fang, SvgPaint.hex("#B8A98C"), SvgStrokeStyle(lineWidth = 0.8, join = SvgLineJoin.ROUND))
    c.stroke(
        "M${x - 2.3} ${y + 1.4} q2.2 1.7 4.2 0",
        SvgPaint.hex("#8D5A3B"),
        SvgStrokeStyle(lineWidth = 1.4, cap = SvgLineCap.ROUND),
    )
}

/** Aviator goggles resting on the upper dome (never over the eyes). */
fun drawGoggles(c: SvgCanvas, x: Double, y: Double, headR: Double) {
    val ly = y + headR * 0.22
    val r = headR * 0.21
    val dx = headR * 0.3
    c.stroke(
        "M${x - headR * 0.72} ${ly + r * 0.3} Q$x ${y - headR * 0.14} ${x + headR * 0.72} ${ly + r * 0.3}",
        SvgPaint.hex("#8D5A3B"),
        SvgStrokeStyle(lineWidth = 2.2),
    )
    c.fill(dpCircle(x - dx, ly, r), SvgPaint.hex("#CDEBF7"))
    c.stroke(dpCircle(x - dx, ly, r), SvgPaint.hex("#C98A5B"), SvgStrokeStyle(lineWidth = 1.8))
    c.fill(dpCircle(x + dx, ly, r), SvgPaint.hex("#CDEBF7"))
    c.stroke(dpCircle(x + dx, ly, r), SvgPaint.hex("#C98A5B"), SvgStrokeStyle(lineWidth = 1.8))
    c.stroke(
        "M${x - dx + r} $ly Q$x ${ly - r * 0.5} ${x + dx - r} $ly",
        SvgPaint.hex("#C98A5B"),
        SvgStrokeStyle(lineWidth = 1.5),
    )
    c.fill(dpCircle(x - dx - r * 0.3, ly - r * 0.35, r * 0.28), SvgPaint.hex("#FFFFFF", 0.8))
    c.fill(dpCircle(x + dx - r * 0.3, ly - r * 0.35, r * 0.28), SvgPaint.hex("#FFFFFF", 0.8))
}

/** Dragon snout: wide muzzle + nostrils (the nose dots make it a dragon, not a cat). */
fun drawSnout(c: SvgCanvas, hx: Double, hy: Double, headR: Double, color: String) {
    c.fill(dpEllipse(hx, hy + headR * 0.45, headR * 0.46, headR * 0.32), SvgPaint.hex(color))
    c.group(0.75) { g ->
        g.fill(dpEllipse(hx - headR * 0.13, hy + headR * 0.32, 1.15, 1.5), SvgPaint.hex(INK))
        g.fill(dpEllipse(hx + headR * 0.13, hy + headR * 0.32, 1.15, 1.5), SvgPaint.hex(INK))
    }
}

// -- Shape helpers (`<circle>` / `<ellipse>` are not path data — D2) ---------

private fun dpCircle(cx: Double, cy: Double, r: Double): List<SvgPathCommand> =
    SvgShapes.circle(cx, cy, r)

private fun dpEllipse(cx: Double, cy: Double, rx: Double, ry: Double): List<SvgPathCommand> =
    SvgShapes.ellipse(cx, cy, rx, ry)
