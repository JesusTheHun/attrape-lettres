package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPoint
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStop
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.ColorSlot
import fr.dappit.attrapelettres.core.mascot.INK
import fr.dappit.attrapelettres.core.mascot.Layout
import fr.dappit.attrapelettres.core.mascot.Pose
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.accessoryAnchors
import fr.dappit.attrapelettres.core.mascot.pick
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// Port of `src/mascot/Unicorn.tsx` (structure via the finished Swift port,
// Sources/ALArt/Mascot/Unicorn.swift).
//
// Unicorn — "Céleste" timeline. Every stade 3→9 adds ONE clearly visible,
// high-contrast beat so a child sees the reward at each level:
//  0-2 (untouched) folded newborn → lifting head → horn nub
//  3 real horn + outlined sky-blue wings open · 4 wings spread wide
//  5 flowing mane crest + first sparkle · 6 glowing halo
//  7 star crown · 8 rainbow mane + shining horn
//  9 majestic alicorn: huge starry wings, halo, crown, rainbow mane, horn beam,
//    ground glow, sparkle burst.
// Wings are OUTLINED (see drawWings) so they never vanish into the pale body.
//
// Every `d` template from the TSX is copied VERBATIM into a Kotlin string
// template (iOS D2); painter's order is the call order.

// NB on the TS optionals: `rainbow?`/`crown?`/`beam?`/`ground?` collapse to
// non-null `Boolean = false` because every TSX read is truthiness-shaped
// (`spec.crown && …`), so "absent" and "false" were already the same thing.
// `maneFlow`/`halo` stay nullable: "absent" means the FEATURE is off, while a
// number is a length/opacity — no STAGES row ever writes 0 to either, so
// nullability carries the whole distinction. STAGES rows use named arguments
// in the TSX literal order, so a row reads like its source line.
private data class USpec(
    /** horn height in viewBox units; 0 = hornless. */
    val horn: Double,
    val shine: Boolean,
    /** wing scale; 0 = none. */
    val wing: Double,
    /** 0..1 magical glow. */
    val aura: Double,
    val sparkle: Int,
    /** flowing mane crest length (× headR); null = base mane only. */
    val maneFlow: Double? = null,
    /** multi-colour flowing mane (supersedes maneFlow). */
    val rainbow: Boolean = false,
    /** head halo opacity 0..1. */
    val halo: Double? = null,
    /** star crown on the head. */
    val crown: Boolean = false,
    /** light beam + sparkle burst from the horn tip. */
    val beam: Boolean = false,
    /** magical pool of light under the hooves. */
    val ground: Boolean = false,
)

private val RAINBOW = listOf("#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8", "#BA9EE8")

private val STAGES = listOf(
    USpec(horn = 0.0, shine = false, wing = 0.0, aura = 0.0, sparkle = 0), // 0 newborn, hornless
    USpec(horn = 0.0, shine = false, wing = 0.0, aura = 0.0, sparkle = 0), // 1 lifting head, hornless
    USpec(horn = 3.0, shine = false, wing = 0.0, aura = 0.0, sparkle = 0), // 2 nub
    USpec(horn = 9.0, shine = false, wing = 0.8, aura = 0.0, sparkle = 0), // 3 horn + wings open
    USpec(horn = 12.0, shine = false, wing = 1.1, aura = 0.1, sparkle = 0), // 4 wings spread
    USpec(horn = 15.0, shine = false, wing = 1.3, aura = 0.25, sparkle = 1, maneFlow = 1.15), // 5 mane crest
    USpec(horn = 17.0, shine = false, wing = 1.5, aura = 0.4, sparkle = 2, maneFlow = 1.43, halo = 0.5), // 6 halo
    USpec(horn = 19.0, shine = false, wing = 1.7, aura = 0.6, sparkle = 3, maneFlow = 1.7, halo = 0.5, crown = true), // 7 crown
    USpec(horn = 21.0, shine = true, wing = 1.9, aura = 0.85, sparkle = 4, halo = 0.9, crown = true, rainbow = true), // 8 rainbow
    USpec(horn = 26.0, shine = true, wing = 2.2, aura = 1.0, sparkle = 7, halo = 0.9, crown = true, rainbow = true, beam = true, ground = true), // 9 majestic
)

internal fun drawHorn(
    c: SvgCanvas,
    h: Double,
    x: Double,
    baseY: Double,
    color: String,
    spiral: Boolean,
    shine: Boolean,
) {
    if (h <= 0) return
    val apexY = baseY - h
    val hw = 2 + h * 0.15
    // The shine is the TSX's `shineId` linearGradient (x1=0 y1=1 x2=1 y2=0) —
    // objectBoundingBox units over the horn path's own bounding box, which is
    // the SVG default and this canvas's default too.
    val fill = if (shine) {
        SvgPaint.linear(
            stops = listOf(
                SvgStop.hex(color, 0f),
                SvgStop.hex("#FFF6C4", 0.5f),
                SvgStop.hex(color, 1f),
            ),
            start = SvgPoint(0f, 1f),
            end = SvgPoint(1f, 0f),
        )
    } else {
        SvgPaint.hex(color)
    }
    // JS `Math.round` rounds ties toward +∞ and so does `roundToInt()` — the
    // same match Growth.kt's `mix` relies on.
    val rings = max(1, (h / 4).roundToInt())
    c.fill("M$x $apexY L${x - hw} $baseY Q$x ${baseY + hw * 0.6} ${x + hw} $baseY Z", fill)
    if (spiral) {
        for (i in 0 until rings) {
            val t = (i + 0.5) / rings
            val yy = baseY - h * t
            val ww = hw * (1 - t) + 0.4
            c.stroke(
                SvgShapes.line(x - ww, yy + 1.3, x + ww, yy - 1.3),
                SvgPaint.hex(INK, opacity = 0.45),
                SvgStrokeStyle(lineWidth = 0.8, cap = SvgLineCap.ROUND),
            )
        }
    }
}

/**
 * Celestial wings — saturated sky-blue with a real outline + feather lines, so
 * they stay legible against the pale body (a small pale wing is invisible).
 */
internal fun drawWings(c: SvgCanvas, s: Double, cx: Double, cy: Double, starry: Boolean) {
    if (s <= 0) return
    fun wing(dir: Double) {
        c.save()
        // transform="translate(cx + dir*6, cy) scale(dir*s, s)" — the left wing
        // is mirrored by the NEGATIVE x scale, exactly as in SVG.
        c.translate(cx + dir * 6, cy)
        c.scale(dir * s, s)
        val membrane = "M0 0 Q18 -24 34 -13 Q25 -6 30 4 Q19 -2 23 11 Q13 3 15 15 Q7 6 6 17 Q1 8 0 0 Z"
        c.fill(membrane, SvgPaint.hex("#BFE3FF"))
        c.stroke(membrane, SvgPaint.hex("#5AA9E0"), SvgStrokeStyle(lineWidth = 1.3, join = SvgLineJoin.ROUND))
        c.stroke("M4 2 Q15 -14 28 -9", SvgPaint.hex("#5AA9E0", opacity = 0.7), SvgStrokeStyle(lineWidth = 0.9))
        c.stroke("M3 7 Q13 -1 24 2", SvgPaint.hex("#5AA9E0", opacity = 0.55), SvgStrokeStyle(lineWidth = 0.8))
        if (starry) {
            for ((x, y) in listOf(12.0 to -7.0, 18.0 to -1.0, 22.0 to 6.0, 14.0 to 9.0)) {
                c.fill(fourStar(x, y, 1.5), SvgPaint.hex("#fff"))
            }
        }
        c.restore()
    }
    wing(-1.0)
    wing(1.0)
}

/** Flowing tail attached to the rump. Straight = smooth swish, curly = coiled. */
internal fun drawUnicornTail(c: SvgCanvas, x: Double, y: Double, len: Double, color: String, curly: Boolean) {
    // wedge blending into the rump so the tail reads attached
    c.fill(
        "M${x + 8} ${y - 7} Q${x - 3} ${y + 2} ${x - 2} ${y + 11} L${x + 9} ${y + 5} Z",
        SvgPaint.hex(color),
    )
    drawPlume(c, x, y, color, len, 9.0, -24.0, 3, curly)
    if (curly) {
        c.stroke(
            "M${x - len * 0.28} ${y + len * 0.62} q -8 3 -6 10 q 2 6 -4 8 q -6 2 -2 8",
            SvgPaint.hex(color),
            SvgStrokeStyle(lineWidth = 4.2, cap = SvgLineCap.ROUND),
        )
    } else {
        c.stroke(
            "M${x - 2} ${y + 3} q -5 ${len * 0.4} -8 ${len * 0.7}",
            SvgPaint.hex("#ffffff", opacity = 0.45),
            SvgStrokeStyle(lineWidth = 1.0, cap = SvgLineCap.ROUND),
        )
    }
}

fun drawUnicornRig(
    c: SvgCanvas,
    config: MascotConfig,
    layout: Layout,
    stage: Int,
    mood: Mood,
    preview: Boolean = false,
) {
    val body = pick(config.colors, ColorSlot.Unicorn.body, "#F5ECFF")
    val hornCol = pick(config.colors, ColorSlot.Unicorn.horn, "#FFD54F")
    val maneCol = pick(config.colors, ColorSlot.Unicorn.mane, "#BA9EE8")
    val tailCol = pick(config.colors, ColorSlot.Unicorn.tail, "#F49AC2")
    val curlyTail = pick(config.styles, StyleSlot.Unicorn.tail, "straight") == "curly"
    val spiralHorn = pick(config.styles, StyleSlot.Unicorn.horn, "smooth") == "spiral"
    fun has(id: String): Boolean = config.accessories.contains(id)
    val earIn = "#FBE4F1"

    var spec = STAGES[stage.coerceIn(0, 9)]
    // Shop thumbnail: keep the horn (a sold part) but drop every magical extra so a
    // ghost unicorn shows ONLY the colour/style/accessory this tile is about.
    if (preview) {
        spec = spec.copy(
            shine = false,
            wing = 0.0,
            aura = 0.0,
            sparkle = 0,
            maneFlow = null,
            rainbow = false,
            halo = null,
            crown = false,
            beam = false,
            ground = false,
        )
    }
    val hoof = "#EADAC6"
    val bodyCX = layout.bodyCX
    val bodyCY = layout.bodyCY
    val bodyRX = layout.bodyRX
    val bodyRY = layout.bodyRY
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val eyeR = layout.eyeR
    val anchor = accessoryAnchors(Species.UNICORN, layout)
    val hornBaseY = headCY - headR * 0.72
    val apexY = hornBaseY - spec.horn
    val legW = if (layout.pose == Pose.PROUD) 6.5 else 7.0

    if (spec.ground) {
        drawGroundGlow(c, 50.0, layout.feetY + 2, bodyRX + 20, "#FFE29A", 0.9)
    }
    if (spec.aura > 0) {
        drawAura(c, bodyCX, bodyCY - 2, bodyRX + 24, "#FFE29A", spec.aura)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if (!layout.standing && has(Accessory.Unicorn.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.15, bodyRX * 1.15, "#FF8FB1", false, SwimRingPart.FULL)
    }

    drawWings(c, spec.wing, bodyCX, bodyCY - bodyRY * 0.28, spec.wing >= 2)

    // tail attached at rump, behind body
    drawUnicornTail(c, bodyCX - bodyRX * 0.5, bodyCY - bodyRY * 0.12, bodyRY * 1.6, tailCol, curlyTail)

    for (l in layout.legs) {
        if (l.back) drawLeg(c, l, legW, body, hoof)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if (layout.standing && has(Accessory.Unicorn.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FF8FB1", false, SwimRingPart.BACK)
    }

    c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
    if (!layout.standing) {
        drawFoldedLegs(c, bodyCX, bodyCY, bodyRX, body, hoof)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if (!layout.standing && has(Accessory.Unicorn.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#7FD1D8", "#FFF6EE", true, false)
    }
    c.stroke(
        "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
        SvgPaint.hex(body),
        SvgStrokeStyle(lineWidth = headR * 0.9, cap = SvgLineCap.ROUND),
    )

    for (l in layout.legs) {
        if (!l.back) drawLeg(c, l, legW, body, hoof)
    }

    // halo (glowing ring behind the head)
    spec.halo?.let { halo ->
        drawHalo(c, headCX, headCY, headR * 1.7, halo, "#FFF3C4")
    }

    // mane behind head + top crest
    drawPlume(c, headCX + headR * 0.22, headCY - headR * 0.05, maneCol, headR * 1.2, headR * 0.65, 22.0, 4, false)
    if (spec.horn > 0) {
        drawPlume(c, headCX - headR * 0.15, headCY - headR * 0.85, maneCol, headR * 0.55, headR * 0.35, -6.0, 2, false)
    }
    // flowing mane crest → rainbow at the top stages
    if (spec.rainbow) {
        for ((i, col) in RAINBOW.withIndex()) {
            drawPlume(c, headCX + headR * 0.24, headCY - headR * 0.08 + (i / 4.0) * headR, col, headR * 1.5, headR * 0.5, 26 + i * 5.0, 3, false)
        }
    } else {
        spec.maneFlow?.let { maneFlow ->
            drawPlume(c, headCX + headR * 0.24, headCY - headR * 0.08, maneCol, headR * maneFlow, headR * 0.62, 24.0, 4, false)
        }
    }

    // head
    c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.98), SvgPaint.hex(body))

    // rounded ears (never horn-like)
    for (d in listOf(-1.0, 1.0)) {
        val ex = headCX + d * headR * 0.6
        val ey = headCY - headR * 0.58
        c.save()
        c.rotate(d * 22, ex, ey)
        c.fill(SvgShapes.ellipse(ex, ey, headR * 0.16, headR * 0.3), SvgPaint.hex(body))
        c.fill(SvgShapes.ellipse(ex, ey + headR * 0.04, headR * 0.08, headR * 0.18), SvgPaint.hex(earIn))
        c.restore()
    }

    // star crown — UNDER the horn (drawn next) so the horn always pokes
    // through the open band; the flower crown accessory replaces it.
    if (spec.crown && !has(Accessory.Unicorn.flowerCrown)) {
        drawCrown(c, headCX, headCY - headR * 0.5, headR * 0.95, hornCol, "#FF7EA8", "#B07E1E", true)
    }

    // forelock — two symmetric locks framing the face, clear of the eyes
    drawPlume(c, headCX - headR * 0.24, headCY - headR * 0.6, maneCol, headR * 0.42, 4.0, -20.0, 2, false)
    drawPlume(c, headCX + headR * 0.24, headCY - headR * 0.6, maneCol, headR * 0.42, 4.0, 20.0, 2, false)

    // horn (on top, centred between the forelock tufts)
    drawHorn(c, spec.horn, headCX, hornBaseY, hornCol, spiralHorn, spec.shine)
    if (spec.shine) {
        drawSparkles(c, listOf(Triple(headCX, apexY - 1.5, 3.0)))
    }
    // horn light beam (majestic)
    if (spec.beam) {
        c.fill(
            "M${headCX - 5} $apexY L$headCX ${apexY - 26} L${headCX + 5} $apexY Z",
            SvgPaint.hex("#FFF6C4", opacity = 0.5),
        )
        c.fill(fourStar(headCX, apexY - 2, 4.2), SvgPaint.hex("#FFF3C4"))
    }

    // face
    drawEyes(c, headCX, headCY + headR * 0.06, headR * 0.42, eyeR, mood, stage == 0)
    drawCheeks(c, headCX, headCY + headR * 0.36, headR * 0.62, headR * 0.15)
    drawMouth(c, headCX, headCY + headR * 0.5, headR * 0.16, mood)

    // hoof aura (majestic)
    if (spec.aura > 0.4) {
        for (l in layout.legs) {
            drawAura(c, l.footX, l.footY, 8.0, "#FFF0B8", spec.aura)
        }
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if (layout.standing && has(Accessory.Unicorn.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#7FD1D8", "#FFF6EE", false, stage >= 6)
    }
    if (layout.standing && has(Accessory.Unicorn.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FF8FB1", stage >= 7, SwimRingPart.FRONT)
    }
    // ribbon: baby (lying, no neck yet) wears it as a hair bow ON the head;
    // once standing it drops to the throat.
    if (has(Accessory.Unicorn.ribbon)) {
        if (layout.standing) {
            drawBow(c, anchor.neck.x, anchor.neck.y, anchor.head.r * 0.052, "#FF7EA8")
        } else {
            drawBow(c, headCX + headR * 0.34, headCY - headR * 0.5, headR * 0.05, "#FF7EA8")
        }
    }
    // crest left OPEN at the top so the horn (or its stade-2 nub) always pokes
    // through — a crown that swallows the just-earned horn is a heartbreak
    if (has(Accessory.Unicorn.flowerCrown)) {
        val palette = listOf("#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8")
        for ((i, deg) in listOf(-72.0, -38.0, 38.0, 72.0).withIndex()) {
            val rad = (deg - 90) * PI / 180
            val fx = anchor.head.x + cos(rad) * anchor.head.r * 0.98
            val fy = anchor.head.y + sin(rad) * anchor.head.r * 0.98
            drawFlower(c, fx, fy, 3.8, palette[i], "#FFF3C4")
        }
    }
    // "Arc-en-ciel magique" (Accessory.Unicorn.starClip) is a WHOLE-IMAGE
    // overlay drawn by the rig shell (Rig.kt) on top of every rig — wings
    // included — so it isn't handled here.

    // sparkles (proud/majestic)
    if (spec.sparkle > 0) {
        drawSparkles(
            c,
            (0 until spec.sparkle).map { i ->
                val a = i.toDouble() / spec.sparkle * PI * 2
                Triple(bodyCX + cos(a) * (bodyRX + 15), bodyCY - 4 + sin(a) * (bodyRY + 12), 1.8 + (i % 3))
            },
        )
    }
}
