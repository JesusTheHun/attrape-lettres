package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.ColorSlot
import fr.dappit.attrapelettres.core.mascot.INK
import fr.dappit.attrapelettres.core.mascot.Layout
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.accessoryAnchors
import fr.dappit.attrapelettres.core.mascot.mix
import fr.dappit.attrapelettres.core.mascot.pick
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Port of `src/mascot/Rabbit.tsx` (via the finished Swift port,
// Sources/ALArt/Mascot/Rabbit.swift — the web file wins where they could
// disagree; they do not).
//
// Rabbit — « Lune » timeline (tsuki no usagi). Each stade 3→9 adds a clearly
// visible beat on the way to the legendary moon rabbit:
//  0-2 (untouched) curled kit, ears laid back → head up → first hops
//  3 downy chest + inner ears bloom lavender · 4 moonlit gold ear tips
//  5 grand moon pompon + gold star · 6 gold crescent forehead mark + sparkles
//  7 star-tipped ears · 8 a floating crescent moon glows beside the head
//  9 full moon: lavender aura, moonlight pooling on the ground, ring of stars.
// Blind-test lessons baked in: nightcap DROOPS below its brim (a symmetric cone
// between two upright ears reads "crown"), the premium star-dust stays OFF the
// zone right above the head (same crown bug), the star-tail draws OVER the hip
// (half-hidden it read "held object"), the swimsuit has shoulder straps.

private data class RSpec(
    val earH: Double,
    val pompon: Double,
    val chest: Boolean = false,
    val bloom: Boolean = false,
    val dip: Boolean = false,
    val pomStar: Boolean = false,
    val mark: Boolean = false,
    val sparkle: Int? = null,
    val starTips: Boolean = false,
    val halo: Double? = null,
    val aura: Double? = null,
    val ground: Boolean = false,
    val burst: Boolean = false,
)

private val STAGES: List<RSpec> = listOf(
    RSpec(earH = 0.78, pompon = 0.75), // 0
    RSpec(earH = 0.88, pompon = 0.8), // 1
    RSpec(earH = 0.98, pompon = 0.85), // 2
    RSpec(earH = 1.06, pompon = 0.9, chest = true, bloom = true), // 3 chest down + lavender ears
    RSpec(earH = 1.14, pompon = 0.95, chest = true, bloom = true, dip = true), // 4 gold tips
    RSpec(earH = 1.22, pompon = 1.42, chest = true, bloom = true, dip = true, pomStar = true), // 5 moon pompon
    RSpec(earH = 1.3, pompon = 1.46, chest = true, bloom = true, dip = true, pomStar = true, mark = true, sparkle = 2), // 6 crescent mark
    RSpec(earH = 1.4, pompon = 1.5, chest = true, bloom = true, dip = true, pomStar = true, mark = true, sparkle = 3, starTips = true), // 7 star tips
    RSpec(earH = 1.5, pompon = 1.54, chest = true, bloom = true, dip = true, pomStar = true, mark = true, sparkle = 4, starTips = true, halo = 0.6), // 8 crescent halo
    RSpec(earH = 1.66, pompon = 1.62, chest = true, bloom = true, dip = true, pomStar = true, mark = true, sparkle = 6, starTips = true, halo = 0.7, aura = 1.0, ground = true, burst = true), // 9 full moon
)

private const val GOLD = "#FFD54F"
private const val MOONLIGHT = "#EFE7FF"
private const val STAR_SOFT = "#FFE082"
private const val FLECK = "#B8A6E0"
private const val CAP = "#8E9AD6"

// -- Rabbit-specific parts -------------------------------------------------

/**
 * One rabbit ear grown from a base point. `rot` is degrees from vertical:
 * 0 = straight up, ±12 = the classic upright tilt, negative sweeps back for
 * the lying baby.
 */
private fun drawEar(
    c: SvgCanvas,
    bx: Double,
    by: Double,
    len: Double,
    wid: Double,
    rot: Double,
    outer: String,
    inner: String? = null,
    dip: String? = null,
    star: String? = null,
) {
    val rad = rot * PI / 180
    val ux = sin(rad)
    val uy = -cos(rad)
    fun at(t: Double): Pair<Double, Double> = (bx + ux * len * t) to (by + uy * len * t)
    val (cx, cy) = at(0.5)
    val (icx, icy) = at(0.58)
    val (dcx, dcy) = at(0.86)
    val (sx, sy) = at(1.14)
    c.save()
    c.rotate(rot, cx, cy)
    c.fill(SvgShapes.ellipse(cx, cy, wid * 0.5, len * 0.52), SvgPaint.hex(outer))
    c.restore()
    if (inner != null) {
        c.save()
        c.rotate(rot, icx, icy)
        c.fill(SvgShapes.ellipse(icx, icy, wid * 0.27, len * 0.33), SvgPaint.hex(inner))
        c.restore()
    }
    if (dip != null) {
        c.save()
        c.rotate(rot, dcx, dcy)
        c.fill(SvgShapes.ellipse(dcx, dcy, wid * 0.36, len * 0.16), SvgPaint.hex(dip))
        c.restore()
    }
    if (star != null) {
        c.fill(fourStar(sx, sy, 2.3), SvgPaint.hex(star))
    }
}

/** An ear whose top folds over — the bought "Oreilles pliées" look. */
private fun drawKinkEar(
    c: SvgCanvas,
    bx: Double,
    by: Double,
    len: Double,
    wid: Double,
    rot: Double,
    kink: Double,
    outer: String,
    inner: String? = null,
    dip: String? = null,
) {
    val rad = rot * PI / 180
    val ux = sin(rad)
    val uy = -cos(rad)
    val ex = bx + ux * len * 0.6
    val ey = by + uy * len * 0.6
    val rot2 = rot + kink
    val rad2 = rot2 * PI / 180
    val ux2 = sin(rad2)
    val uy2 = -cos(rad2)
    val tl = len * 0.55
    val c1x = bx + ux * len * 0.34
    val c1y = by + uy * len * 0.34
    val c2x = ex + ux2 * tl * 0.45
    val c2y = ey + uy2 * tl * 0.45
    val icx = bx + ux * len * 0.38
    val icy = by + uy * len * 0.38
    val dcx = ex + ux2 * tl * 0.78
    val dcy = ey + uy2 * tl * 0.78
    c.save()
    c.rotate(rot, c1x, c1y)
    c.fill(SvgShapes.ellipse(c1x, c1y, wid * 0.5, len * 0.42), SvgPaint.hex(outer))
    c.restore()
    if (inner != null) {
        c.save()
        c.rotate(rot, icx, icy)
        c.fill(SvgShapes.ellipse(icx, icy, wid * 0.26, len * 0.26), SvgPaint.hex(inner))
        c.restore()
    }
    c.save()
    c.rotate(rot2, c2x, c2y)
    c.fill(SvgShapes.ellipse(c2x, c2y, wid * 0.44, tl * 0.55), SvgPaint.hex(outer))
    c.restore()
    if (dip != null) {
        c.save()
        c.rotate(rot2, dcx, dcy)
        c.fill(SvgShapes.ellipse(dcx, dcy, wid * 0.34, tl * 0.24), SvgPaint.hex(dip))
        c.restore()
    }
}

/**
 * Round fluffy pompon tail — circles with an outline underlay so it separates
 * from a same-tone body.
 */
private fun drawPompon(c: SvgCanvas, cx: Double, cy: Double, s: Double, color: String, edge: String) {
    val puffs = listOf(
        Triple(0.0, 0.0, 4.4),
        Triple(-3.2, -1.5, 3.1),
        Triple(3.0, -1.7, 2.9),
        Triple(-2.4, 2.3, 2.9),
        Triple(2.7, 2.1, 2.8),
        Triple(0.0, -3.2, 3.0),
    )
    for ((dx, dy, r) in puffs) {
        c.fill(SvgShapes.circle(cx + dx * s, cy + dy * s, (r + 0.9) * s), SvgPaint.hex(edge))
    }
    for ((dx, dy, r) in puffs) {
        c.fill(SvgShapes.circle(cx + dx * s, cy + dy * s, r * s), SvgPaint.hex(color))
    }
}

/** Tiny rounded-triangle rabbit nose. */
private fun drawBunnyNose(c: SvgCanvas, cx: Double, y: Double, s: Double) {
    c.fill(
        "M${cx - 1.9 * s} $y Q$cx ${y - 1.7 * s} ${cx + 1.9 * s} $y Q$cx ${y + 2.4 * s} ${cx - 1.9 * s} $y Z",
        SvgPaint.hex("#F0A0AE"),
    )
}

/** Three whisker-freckle dots per cheek — the rabbit face signature. */
private fun drawWhiskerDots(c: SvgCanvas, cx: Double, y: Double, dx: Double, s: Double) {
    c.group(0.3) { g ->
        for (d in listOf(-1.0, 1.0)) {
            for (i in 0 until 3) {
                g.fill(
                    SvgShapes.circle(
                        cx + d * (dx + (i % 2) * 1.6 * s),
                        y + (i - 1) * 1.7 * s,
                        0.58 * s,
                    ),
                    SvgPaint.hex(INK),
                )
            }
        }
    }
}

/** Crescent moon, tips up/down, opening to the left. */
private fun drawCrescent(c: SvgCanvas, cx: Double, cy: Double, r: Double, fill: String, rot: Double = 0.0) {
    val d = "M$cx ${cy - r} A$r $r 0 1 1 $cx ${cy + r} A${r * 1.15} ${r * 1.15} 0 0 0 $cx ${cy - r} Z"
    if (rot != 0.0) {
        c.save()
        c.rotate(rot, cx, cy)
        c.fill(d, SvgPaint.hex(fill))
        c.restore()
    } else {
        c.fill(d, SvgPaint.hex(fill))
    }
}

/**
 * A shooting star: gold four-star head + a trail of shrinking stars (NOT a
 * straight stick — that read as a wand). `rot` = direction the trail streams.
 */
private fun drawComet(c: SvgCanvas, x: Double, y: Double, s: Double, rot: Double) {
    val rad = rot * PI / 180
    val ux = cos(rad)
    val uy = sin(rad)
    val px = -uy
    val py = ux
    c.fill(fourStar(x + (ux * 5.5 + px * 1.3) * s, y + (uy * 5.5 + py * 1.3) * s, 1.7 * s), SvgPaint.hex("#FFE082", 0.9))
    c.fill(fourStar(x + (ux * 9.5 - px * 1.5) * s, y + (uy * 9.5 - py * 1.5) * s, 1.2 * s), SvgPaint.hex("#FFE8A3", 0.8))
    c.fill(SvgShapes.circle(x + (ux * 12.5 + px * 0.9) * s, y + (uy * 12.5 + py * 0.9) * s, 0.65 * s), SvgPaint.hex("#FFE8A3", 0.6))
    c.fill(fourStar(x, y, 2.9 * s), SvgPaint.hex("#FFD54F"))
    c.fill(SvgShapes.circle(x - s * 0.5, y - s * 0.5, 0.65 * s), SvgPaint.hex("#FFF7DC"))
}

/**
 * Sleeping cap seated on the dome: the cone folds over and DROOPS below its
 * own brim on the right, pompom hanging at the tip — the droop is what stops
 * it reading as a crown between two upright ears.
 */
private fun drawNightcap(c: SvgCanvas, hx: Double, hy: Double, headR: Double) {
    val bl = (hx - headR * 0.72) to (hy - headR * 0.52)
    val br = (hx + headR * 0.72) to (hy - headR * 0.52)
    val tipX = hx + headR * 1.28
    val tipY = hy - headR * 0.28
    c.fill(
        "M${bl.first} ${bl.second} Q${hx - headR * 0.25} ${hy - headR * 1.8} ${hx + headR * 0.25} ${hy - headR * 1.5} Q${hx + headR * 0.78} ${hy - headR * 1.34} ${hx + headR * 0.98} ${hy - headR * 0.92} Q${hx + headR * 1.1} ${hy - headR * 0.55} $tipX $tipY Q${hx + headR * 0.92} ${hy - headR * 0.4} ${br.first} ${br.second} Z",
        SvgPaint.hex(CAP),
    )
    c.stroke(
        "M${hx + headR * 0.28} ${hy - headR * 1.44} Q${hx + headR * 0.74} ${hy - headR * 1.12} ${hx + headR * 0.92} ${hy - headR * 0.74}",
        SvgPaint.hex("#7A85C2"),
        SvgStrokeStyle(lineWidth = headR * 0.06, cap = SvgLineCap.ROUND),
    )
    c.fill(fourStar(hx - headR * 0.2, hy - headR * 1.05, headR * 0.09), SvgPaint.hex("#FFF6DE", 0.95))
    c.fill(fourStar(hx + headR * 0.3, hy - headR * 0.95, headR * 0.07), SvgPaint.hex("#FFF6DE", 0.85))
    c.stroke(
        "M${bl.first} ${bl.second} Q$hx ${hy - headR * 1.18} ${br.first} ${br.second}",
        SvgPaint.hex("#FFF3E0"),
        SvgStrokeStyle(lineWidth = headR * 0.22, cap = SvgLineCap.ROUND),
    )
    c.fill(SvgShapes.circle(tipX + headR * 0.06, tipY + headR * 0.1, headR * 0.19), SvgPaint.hex("#FFF3E0"))
    c.stroke(
        SvgShapes.circle(tipX + headR * 0.06, tipY + headR * 0.1, headR * 0.19),
        SvgPaint.hex("#E8D9C0", 0.7),
        SvgStrokeStyle(lineWidth = 0.7),
    )
}

/** Where one ear grows from, and how it leans — the TSX's inline seat tuples. */
private data class EarSeat(val bx: Double, val by: Double, val rot: Double, val k: Double)

// -- The rig ---------------------------------------------------------------

fun drawRabbitRig(
    c: SvgCanvas,
    config: MascotConfig,
    layout: Layout,
    stage: Int,
    mood: Mood,
    preview: Boolean = false,
) {
    val body = pick(config.colors, ColorSlot.Rabbit.body, "#F6EFE3")
    val belly = pick(config.colors, ColorSlot.Rabbit.belly, "#FFFFFF")
    val innerBase = pick(config.colors, ColorSlot.Rabbit.inner, "#D9CCEE")
    val foldEars = pick(config.styles, StyleSlot.Rabbit.ear, "hautes") == "pliees"
    val starTail = pick(config.styles, StyleSlot.Rabbit.tail, "pompon") == "etoile"
    val flecks = pick(config.styles, StyleSlot.Rabbit.fur, "uni") == "flocons"
    fun has(id: String): Boolean = config.accessories.contains(id)
    val stardust = has(Accessory.Rabbit.stardust) && stage >= 4

    var spec = STAGES[stage.coerceIn(0, 9)]
    // Shop thumbnail: strip the free per-stage magic; keep ears + pompon (sold
    // parts) and the inner-ear bloom so colour tiles show their true tint.
    if (preview) spec = RSpec(earH = spec.earH, pompon = spec.pompon, bloom = spec.bloom)

    val bodyCX = layout.bodyCX
    val bodyCY = layout.bodyCY
    val bodyRX = layout.bodyRX
    val bodyRY = layout.bodyRY
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val eyeR = layout.eyeR
    val feetY = layout.feetY
    val anchor = accessoryAnchors(Species.RABBIT, layout)
    val inner = mix(body, innerBase, if (spec.bloom) 1.0 else 0.2)
    val tailEdge = mix(body, INK, 0.18)
    val pom = spec.pompon
    val legW = 7.0
    val earW = headR * 0.4
    val earL = headR * spec.earH
    val hoofC = mix(body, INK, 0.3)

    // Upright tilted ears when standing; swept back over the shoulder when lying.
    val ears = if (layout.standing) {
        listOf(
            EarSeat(headCX - headR * 0.42, headCY - headR * 0.62, -12.0, 1.0),
            EarSeat(headCX + headR * 0.42, headCY - headR * 0.62, 12.0, 1.0),
        )
    } else {
        listOf(
            EarSeat(headCX - headR * 0.55, headCY - headR * 0.48, -66.0, 0.95),
            EarSeat(headCX - headR * 0.2, headCY - headR * 0.6, -30.0, 1.0),
        )
    }

    val pomX = if (layout.standing) bodyCX - bodyRX * 0.98 else bodyCX - bodyRX * 0.9
    val pomY = if (layout.standing) bodyCY + bodyRY * 0.5 else bodyCY - bodyRY * 0.35

    if (spec.ground) {
        drawGroundGlow(c, 50.0, feetY + 2, bodyRX + 18, "#D9CCF2", 0.85)
    }
    if ((spec.aura ?: 0.0) > 0) {
        drawAura(c, bodyCX, bodyCY - 4, bodyRX + 24, MOONLIGHT, spec.aura ?: 0.0)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if (!layout.standing && has(Accessory.Rabbit.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.15, bodyRX * 1.15, "#FFD54F", false, SwimRingPart.FULL)
    }

    // fluffy pompon tail peeking on the rump (star-tail variant drawn OVER
    // the hip later — half-hidden it read as a "held object")
    if (!starTail) {
        drawPompon(c, pomX, pomY, pom, "#FFFFFF", tailEdge)
        if (spec.pomStar) {
            c.fill(fourStar(pomX, pomY - 2 * pom, 2.4), SvgPaint.hex(GOLD))
        }
    }

    // back legs
    for (l in layout.legs.filter { it.back }) {
        drawLeg(c, l, legW, body, hoofC)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if (layout.standing && has(Accessory.Rabbit.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FFD54F", false, SwimRingPart.BACK)
    }

    // body + belly
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), SvgPaint.hex(belly))
    // star-flecks scatter across body AND belly — big enough to read at a glance
    if (flecks) {
        val rows = listOf(
            Triple(-0.55, -0.3, 2.6),
            Triple(0.42, -0.5, 2.2),
            Triple(-0.05, -0.62, 1.9),
            Triple(0.6, 0.12, 2.4),
            Triple(-0.5, 0.38, 2.2),
            Triple(0.18, 0.32, 2.0),
            Triple(-0.16, 0.72, 1.8),
            Triple(0.45, 0.62, 1.6),
        )
        rows.forEachIndexed { i, (dx, dy, r) ->
            c.fill(
                fourStar(bodyCX + dx * bodyRX, bodyCY + dy * bodyRY, r * 1.15),
                SvgPaint.hex(if (i % 2 != 0) "#CFC2EC" else FLECK, 0.95),
            )
        }
    }
    // bought star-tail: a full gold star riding the hip edge, never occluded
    if (starTail) {
        c.save()
        c.rotate(12.0, pomX, pomY)
        c.fill(fourStar(pomX, pomY, 6.8 * pom), SvgPaint.hex(GOLD))
        c.fill(fourStar(pomX, pomY, 4.2 * pom), SvgPaint.hex("#FFF3D6"))
        c.restore()
    }
    if (!layout.standing) {
        drawFoldedLegs(c, bodyCX, bodyCY, bodyRX, body, hoofC)
    }
    // lying nappy-culotte hugs the rump end of the loaf (gated stade 2+ in
    // the catalog, kept for safety)
    if (!layout.standing && has(Accessory.Rabbit.swimsuit)) {
        drawSwimsuit(c, bodyCX - bodyRX * 0.3, bodyCY + bodyRY * 0.1, bodyRX * 0.7, bodyRY * 0.9, "#5AA9E0", "#FFF6EE", true, false)
    }

    // neck
    c.stroke(
        "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
        SvgPaint.hex(body),
        SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
    )

    // front legs
    for (l in layout.legs.filter { !it.back }) {
        drawLeg(c, l, legW, body, hoofC)
    }

    // downy chest
    if (spec.chest) {
        drawPlume(c, headCX, headCY + headR * 0.8, belly, 5.5, headR * 0.65, 0.0, 3)
    }

    // floating crescent moon beside the head (behind the ears)
    if ((spec.halo ?: 0.0) > 0) {
        drawAura(c, headCX + headR * 1.05, headCY - headR * 0.9, headR * 0.95, "#FFF3C4", (spec.halo ?: 0.0) + 0.15)
        drawCrescent(c, headCX + headR * 1.05, headCY - headR * 0.9, headR * 0.4, STAR_SOFT, 24.0)
    }

    // ears (behind the head)
    ears.forEachIndexed { i, e ->
        if (foldEars) {
            drawKinkEar(
                c,
                e.bx, e.by, earL * e.k, earW, e.rot,
                (if (layout.standing) (if (i == 0) -1.0 else 1.0) else -1.0) * 100,
                body,
                inner,
                if (spec.dip) GOLD else null,
            )
        } else {
            drawEar(
                c,
                e.bx, e.by, earL * e.k, earW, e.rot,
                body,
                inner,
                if (spec.dip) GOLD else null,
                if (spec.starTips) STAR_SOFT else null,
            )
        }
    }

    // head
    c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.96), SvgPaint.hex(body))

    // face
    drawEyes(c, headCX, headCY - headR * 0.05, headR * 0.38, eyeR, mood, stage == 0)
    drawCheeks(c, headCX, headCY + headR * 0.32, headR * 0.62, headR * 0.13)
    drawWhiskerDots(c, headCX, headCY + headR * 0.34, headR * 0.8, headR * 0.05)
    drawBunnyNose(c, headCX, headCY + headR * 0.28, headR * 0.055)
    drawMouth(c, headCX, headCY + headR * 0.48, headR * 0.13, mood)
    if (spec.mark) {
        drawCrescent(c, headCX, headCY - headR * 0.55, 2.7, GOLD, 18.0)
    }

    // accessories — placement from the shared anchor resolver
    if (layout.standing && has(Accessory.Rabbit.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY + bodyRY * 0.15, bodyRX * 0.98, bodyRY * 0.88, "#5AA9E0", "#FFF6EE", false, stage >= 6)
        // shoulder straps make it read "maillot une-pièce", not a shirt
        for (d in listOf(-1.0, 1.0)) {
            c.stroke(
                "M${bodyCX + d * bodyRX * 0.36} ${bodyCY + bodyRY * 0.08} Q${bodyCX + d * bodyRX * 0.44} ${bodyCY - bodyRY * 0.45} ${bodyCX + d * bodyRX * 0.4} ${bodyCY - bodyRY * 0.92}",
                SvgPaint.hex("#5AA9E0"),
                SvgStrokeStyle(lineWidth = 2.6, cap = SvgLineCap.ROUND),
            )
        }
    }
    if (layout.standing && has(Accessory.Rabbit.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FFD54F", stage >= 7, SwimRingPart.FRONT)
    }
    if (has(Accessory.Rabbit.bow)) {
        drawBow(c, anchor.neck.x, anchor.neck.y, max(0.75, headR * 0.048), CAP)
        c.fill(fourStar(anchor.neck.x, anchor.neck.y, max(0.75, headR * 0.048) * 1.7), SvgPaint.hex(GOLD))
    }
    if (has(Accessory.Rabbit.nightcap)) {
        drawNightcap(c, headCX, headCY, headR)
    }

    // premium « Poussière d'étoiles » — gold star-dust orbiting the pet.
    // Own beats: denser dust at 7+, a shooting star joins at 9.
    if (stardust) {
        val dust = buildList {
            add(Triple(10.0, 15.0, 2.6))
            add(Triple(60.0, 13.0, 1.8))
            add(Triple(150.0, 16.0, 2.2))
            add(Triple(200.0, 14.0, 1.7))
            add(Triple(335.0, 18.0, 2.8))
            if (stage >= 7) {
                add(Triple(25.0, 21.0, 2.0))
                add(Triple(170.0, 22.0, 2.4))
            }
        }
        drawSparkles(
            c,
            dust.map { (deg, rad, r) ->
                val a = deg * PI / 180
                Triple(bodyCX + cos(a) * (bodyRX + rad), bodyCY + sin(a) * (bodyRY * 0.55 + rad) - 4, r)
            },
            GOLD,
        )
        for ((deg, rad, r) in listOf(Triple(95.0, 10.0, 1.2), Triple(185.0, 12.0, 1.0), Triple(350.0, 9.0, 1.1))) {
            val a = deg * PI / 180
            c.fill(
                SvgShapes.circle(bodyCX + cos(a) * (bodyRX + rad), bodyCY + sin(a) * (bodyRY * 0.55 + rad) - 4, r),
                SvgPaint.hex("#FFE8A3", 0.8),
            )
        }
        if (stage >= 9) {
            drawComet(c, bodyCX - bodyRX - 18, bodyCY - bodyRY - 20, 0.9, 200.0)
        }
    }

    // stade-9 ring of stars + free sparkles
    if (spec.burst) {
        drawBurst(c, bodyCX, bodyCY - 6, bodyRX + 16, bodyRY + 14, 8, STAR_SOFT)
    }
    val sparkleN = spec.sparkle ?: 0
    if (sparkleN > 0) {
        drawSparkles(
            c,
            (0 until sparkleN).map { i ->
                val a = i.toDouble() / sparkleN * PI * 2 + 0.9
                Triple(bodyCX + cos(a) * (bodyRX + 13), bodyCY + sin(a) * (bodyRY + 10) - 3, 1.5 + (i % 3))
            },
            "#FFF1B8",
        )
    }
}
