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
import fr.dappit.attrapelettres.core.mascot.Layout
import fr.dappit.attrapelettres.core.mascot.RampStop
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.accessoryAnchors
import fr.dappit.attrapelettres.core.mascot.mix
import fr.dappit.attrapelettres.core.mascot.pick
import fr.dappit.attrapelettres.core.mascot.ramp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of `src/mascot/Cat.tsx` (structure via the finished Swift port,
// Sources/ALArt/Mascot/Cat.swift).
//
// Cat — "Lynx Royal" timeline. Each stade 3→9 adds a clear, high-contrast beat
// on the way to a majestic big-cat:
//  0-2 (untouched) curled newborn → lifting head → first steps
//  3 whiskers sprout · 4 lynx ear-tufts · 5 neck ruff starts
//  6 growing mane + sparkle · 7 big mane · 8 aura + XL mane + halo
//  9 lion mane, gold crown, ground glow, sparkle burst.
// The mane is a soft two-tone scallop ruff (see drawMane) so the cat stays kawaii
// while still reading "lion" against the same-coloured head.

private enum class Tuft { NONE, SMALL, BIG }

// NB on the TS optionals: `whiskers?`/`crown?`/`ground?` collapse to non-null
// `Boolean = false` because every TSX read is truthiness-shaped. `mane`/`halo`
// stay nullable: "absent" means the feature is off, and no STAGES row writes 0
// to either, so nullability carries the whole distinction. Rows use named
// arguments in the TSX literal order, so a row reads like its source line.
private data class CSpec(
    /** tail length/bushiness multiplier. */
    val tail: Double,
    val tuft: Tuft,
    val aura: Double,
    val sparkle: Int,
    /** whiskers — sprout at stade 3 (a newborn's bare face is its own beat). */
    val whiskers: Boolean = false,
    /** lion-mane plume length; null = none. */
    val mane: Double? = null,
    /** head halo opacity 0..1. */
    val halo: Double? = null,
    /** royal crown. */
    val crown: Boolean = false,
    /** pool of light under the paws. */
    val ground: Boolean = false,
)

private const val MANE = "#FFC98A"
private const val MANE_EDGE = "#E08A3C"

private val STAGES = listOf(
    CSpec(tail = 0.5, tuft = Tuft.NONE, aura = 0.0, sparkle = 0), // 0
    CSpec(tail = 0.6, tuft = Tuft.NONE, aura = 0.0, sparkle = 0), // 1
    CSpec(tail = 0.8, tuft = Tuft.NONE, aura = 0.0, sparkle = 0), // 2
    CSpec(tail = 1.05, tuft = Tuft.NONE, aura = 0.0, sparkle = 0, whiskers = true), // 3 whiskers
    CSpec(tail = 1.2, tuft = Tuft.SMALL, aura = 0.0, sparkle = 0, whiskers = true), // 4 lynx ear-tufts
    CSpec(tail = 1.4, tuft = Tuft.SMALL, aura = 0.0, sparkle = 0, whiskers = true, mane = 5.0), // 5 ruff
    CSpec(tail = 1.55, tuft = Tuft.SMALL, aura = 0.15, sparkle = 1, whiskers = true, mane = 6.5), // 6 mane + sparkle
    CSpec(tail = 1.6, tuft = Tuft.BIG, aura = 0.4, sparkle = 2, whiskers = true, mane = 8.0), // 7 big mane
    CSpec(tail = 1.75, tuft = Tuft.BIG, aura = 0.7, sparkle = 3, whiskers = true, mane = 9.5, halo = 0.6), // 8 XL mane + halo
    CSpec(tail = 2.0, tuft = Tuft.BIG, aura = 1.0, sparkle = 6, whiskers = true, mane = 11.0, halo = 0.6, crown = true, ground = true), // 9 lion king
)

// The TS `Record<Tuft, number>` as an exhaustive `when` — the compiler checks
// totality, so a missed lookup cannot silently read as "no tuft".
private fun tuftLen(tuft: Tuft): Double = when (tuft) {
    Tuft.NONE -> 0.0
    Tuft.SMALL -> 4.0
    Tuft.BIG -> 7.0
}

/**
 * Lion mane, kawaii — an ARC of soft round fur scallops in warm two-tone
 * (spiky dark plumes read "angry"; round pastel clumps read "plush"). The
 * chest is left clear so the head keeps its silhouette instead of melting
 * into a blob.
 */
internal fun drawMane(c: SvgCanvas, cx: Double, cy: Double, r: Double, size: Double) {
    val n = 12
    fun ring(rad: Double, cr: Double, col: String) {
        for (i in 0 until n) {
            val a = i.toDouble() / n * PI * 2
            if (sin(a) > 0.62) continue // skip the chest wedge
            val x = cx + cos(a) * rad
            val y = cy + sin(a) * rad + r * 0.1
            c.fill(SvgShapes.circle(x, y, cr), SvgPaint.hex(col))
        }
    }
    ring(r + size * 0.5, size * 0.92, MANE_EDGE)
    ring(r + size * 0.28, size * 0.72, MANE)
}

/** One fur clump along the tail curve. */
private data class TailClump(val px: Double, val py: Double, val r: Double)

fun drawCatRig(
    c: SvgCanvas,
    config: MascotConfig,
    layout: Layout,
    stage: Int,
    mood: Mood,
    preview: Boolean = false,
) {
    val body = pick(config.colors, ColorSlot.Cat.body, "#F6A96B")
    val belly = pick(config.colors, ColorSlot.Cat.belly, "#FFF3E4")
    val tailCol = pick(config.colors, ColorSlot.Cat.tail, body)
    val fluffy = pick(config.styles, StyleSlot.Cat.hair, "short") == "fluffy"
    val longTail = pick(config.styles, StyleSlot.Cat.tail, "long") == "long"
    fun has(id: String): Boolean = config.accessories.contains(id)
    val furEdge = mix(body, "#FFFFFF", 0.34) // lighter fluff so scallops are visible

    var spec = STAGES[stage.coerceIn(0, 9)]
    // Shop thumbnail: keep the tail (a sold part) but drop mane/tufts/halo/crown/
    // glow so a ghost cat shows ONLY what this tile changes.
    if (preview) {
        spec = spec.copy(
            tuft = Tuft.NONE,
            aura = 0.0,
            sparkle = 0,
            mane = null,
            whiskers = false,
            halo = null,
            crown = false,
            ground = false,
        )
    }
    val tailF = spec.tail * (if (longTail) 1.0 else 0.62)
    val raise = ramp(stage.toDouble(), listOf(RampStop(0.0, 0.0), RampStop(2.0, 0.1), RampStop(4.0, 0.4), RampStop(6.0, 0.75), RampStop(9.0, 1.0)))
    val earScale = ramp(stage.toDouble(), listOf(RampStop(0.0, 0.44), RampStop(2.0, 0.5), RampStop(4.0, 0.58), RampStop(6.0, 0.64), RampStop(9.0, 0.68)))
    val earPoint = ramp(stage.toDouble(), listOf(RampStop(0.0, 0.6), RampStop(3.0, 0.85), RampStop(6.0, 1.0), RampStop(9.0, 1.0)))
    val bodyCX = layout.bodyCX
    val bodyCY = layout.bodyCY
    val bodyRX = layout.bodyRX
    val bodyRY = layout.bodyRY
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val eyeR = layout.eyeR
    val anchor = accessoryAnchors(Species.CAT, layout)
    val legW = 7.0

    // Lush fur-clump tail: BUSHINESS (rB) scales with age; reach stays bounded so
    // the tail curls beside the body instead of flying off-canvas.
    val tx = bodyCX + bodyRX * 0.72
    val ty = bodyCY + bodyRY * 0.02
    val cx1 = tx + 13 + 4 * raise
    val cy1 = ty - (4 + 11 * raise)
    val tipX = tx + 8 + 6 * raise
    val tipY = ty - (10 + 24 * raise)
    val rB = 3.2 + 4.9 * tailF
    fun bez(t: Double): Pair<Double, Double> = Pair(
        (1 - t) * (1 - t) * tx + 2 * (1 - t) * t * cx1 + t * t * tipX,
        (1 - t) * (1 - t) * ty + 2 * (1 - t) * t * cy1 + t * t * tipY,
    )
    val clumps = (0 until 7).map { i ->
        val t = i / 6.0
        val (px, py) = bez(t)
        TailClump(px, py, rB * (0.62 + 0.5 * sin(PI * (0.16 + 0.8 * t))))
    }

    if (spec.ground) {
        drawGroundGlow(c, 50.0, layout.feetY + 2, bodyRX + 18, "#FFE29A", 0.85)
    }
    if (spec.aura > 0) {
        drawAura(c, tipX, tipY, rB * 1.5, "#FFE6B0", spec.aura)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if (!layout.standing && has(Accessory.Cat.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.15, bodyRX * 1.15, "#FF6B6B", false, SwimRingPart.FULL)
    }

    // tail behind body — core + fur clumps + fluffy tip
    c.stroke(
        "M$tx $ty Q$cx1 $cy1 $tipX $tipY",
        SvgPaint.hex(tailCol),
        SvgStrokeStyle(lineWidth = rB * 0.7, cap = SvgLineCap.ROUND),
    )
    for (b in clumps) {
        c.fill(SvgShapes.circle(b.px, b.py, b.r), SvgPaint.hex(tailCol))
    }
    if (fluffy) {
        for ((i, b) in clumps.withIndex()) {
            if (i % 2 == 1) {
                c.fill(SvgShapes.circle(b.px - b.r * 0.4, b.py - b.r * 0.4, b.r * 0.5), SvgPaint.hex(furEdge))
            }
        }
    }
    drawPlume(c, tipX, tipY, tailCol, 5 + 5 * tailF, 4 + 4 * tailF, -12.0, 4, false)

    // back legs
    for (l in layout.legs) {
        if (l.back) drawLeg(c, l, legW, body, body)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if (layout.standing && has(Accessory.Cat.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FF6B6B", false, SwimRingPart.BACK)
    }

    // fluffy: lighter scalloped fur halo breaking the body outline
    if (fluffy) {
        for (i in 0 until 16) {
            val a = i / 16.0 * PI * 2
            c.fill(SvgShapes.circle(bodyCX + cos(a) * bodyRX * 1.02, bodyCY + sin(a) * bodyRY * 1.02, 5.4), SvgPaint.hex(furEdge))
        }
    }

    // body
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY + bodyRY * 0.28, bodyRX * 0.6, bodyRY * 0.62), SvgPaint.hex(belly))
    if (!layout.standing) {
        drawFoldedLegs(c, bodyCX, bodyCY, bodyRX, body, belly)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if (!layout.standing && has(Accessory.Cat.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#4FC3F7", "#FFF6EE", true, false)
    }
    c.stroke(
        "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
        SvgPaint.hex(body),
        SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
    )

    // front legs
    for (l in layout.legs) {
        if (!l.back) drawLeg(c, l, legW, body, body)
    }

    // halo + lion mane (behind the head)
    spec.halo?.let { halo ->
        drawHalo(c, headCX, headCY, headR * 1.7, halo, "#FFF3C4")
    }
    spec.mane?.let { mane ->
        drawMane(c, headCX, headCY, headR * 1.02, mane)
    }

    // fluffy: lighter fur halo around the head
    if (fluffy) {
        for (i in 0 until 12) {
            val a = i / 12.0 * PI * 2
            c.fill(SvgShapes.circle(headCX + cos(a) * headR * 1.0, headCY + sin(a) * headR * 1.0, 4.6), SvgPaint.hex(furEdge))
        }
    }

    // head
    c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR), SvgPaint.hex(body))

    // ears — grow & sharpen with age; rounded tip for babies
    for (d in listOf(-1.0, 1.0)) {
        val ex = headCX + d * headR * 0.62
        val ey = headCY - headR * 0.58
        val th = headR * earScale
        val tipYe = ey - th
        val tipXe = ex + d * th * 0.35
        c.fill(
            "M${ex - d * headR * 0.02} $ey Q${tipXe - d * th * 0.1} $tipYe $tipXe ${tipYe + th * (1 - earPoint) * 0.2} Q${tipXe + d * th * 0.05} ${tipYe + 2} ${ex - d * headR * 0.6} ${ey - headR * 0.02} Z",
            SvgPaint.hex(body),
        )
        c.fill(
            "M$ex ${ey - 1} L${tipXe - d * th * 0.05} ${tipYe + th * 0.28} L${ex - d * headR * 0.34} ${ey - headR * 0.02} Z",
            SvgPaint.hex("#FF9AA2"),
        )
        if (tuftLen(spec.tuft) > 0) {
            drawPlume(c, tipXe, tipYe + 1, body, tuftLen(spec.tuft), 2.0, d * 12, 2, false)
        }
    }

    // fluffy cheeks + head tuft (lighter, unmistakable)
    if (fluffy) {
        for (d in listOf(-1.0, 1.0)) {
            drawPlume(c, headCX + d * headR * 0.92, headCY + headR * 0.2, furEdge, 11.0, 7.0, d * 74, 4, false)
        }
        drawPlume(c, headCX, headCY - headR * 0.92, furEdge, 8.0, 7.0, 0.0, 3, false)
    }

    // face
    drawEyes(c, headCX, headCY, headR * 0.4, eyeR, mood, stage == 0)
    drawCheeks(c, headCX, headCY + headR * 0.36, headR * 0.58, headR * 0.14)
    c.fill(
        "M${headCX - 2.4} ${headCY + headR * 0.3} L${headCX + 2.4} ${headCY + headR * 0.3} L$headCX ${headCY + headR * 0.44} Z",
        SvgPaint.hex("#FF7C93"),
    )
    drawMouth(c, headCX, headCY + headR * 0.52, headR * 0.15, mood)
    // whiskers — the stade-3 beat
    if (spec.whiskers) {
        for (d in listOf(-1.0, 1.0)) {
            val style = SvgStrokeStyle(lineWidth = 0.8, cap = SvgLineCap.ROUND)
            c.stroke(
                SvgShapes.line(headCX + d * headR * 0.35, headCY + headR * 0.4, headCX + d * headR, headCY + headR * 0.3),
                SvgPaint.hex("#B79A82"),
                style,
            )
            c.stroke(
                SvgShapes.line(headCX + d * headR * 0.35, headCY + headR * 0.48, headCX + d * headR, headCY + headR * 0.5),
                SvgPaint.hex("#B79A82"),
                style,
            )
        }
    }

    // royal crown (majestic)
    if (spec.crown) {
        drawCrown(c, headCX, headCY - headR * 0.5, headR * 0.95, "#FFD54F", "#E0533B", "#B07E1E", false)
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if (layout.standing && has(Accessory.Cat.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#4FC3F7", "#FFF6EE", false, stage >= 6)
    }
    if (layout.standing && has(Accessory.Cat.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FF6B6B", stage >= 7, SwimRingPart.FRONT)
    }
    // collar grows up with the cat: kitten bell → studded leather + gold medal
    // from stade 5 (a baby bell on a lion reads wrong)
    if (has(Accessory.Cat.bellCollar)) {
        if (stage >= 5) {
            c.stroke(
                "M${anchor.neck.x - anchor.neck.w} ${anchor.neck.y} Q${anchor.neck.x} ${anchor.neck.y + 6} ${anchor.neck.x + anchor.neck.w} ${anchor.neck.y}",
                SvgPaint.hex("#6B4226"),
                SvgStrokeStyle(lineWidth = 3.6, cap = SvgLineCap.ROUND),
            )
            for (t in listOf(0.18, 0.38, 0.62, 0.82)) {
                val sx = (1 - t) * (1 - t) * (anchor.neck.x - anchor.neck.w) + 2 * (1 - t) * t * anchor.neck.x + t * t * (anchor.neck.x + anchor.neck.w)
                val sy = (1 - t) * (1 - t) * anchor.neck.y + 2 * (1 - t) * t * (anchor.neck.y + 6) + t * t * anchor.neck.y
                c.fill(SvgShapes.circle(sx, sy, 0.9), SvgPaint.hex("#FFD54F"))
            }
            c.fill(SvgShapes.circle(anchor.neck.x, anchor.neck.y + 5.4, 2.9), SvgPaint.hex("#FFD54F"))
            c.stroke(SvgShapes.circle(anchor.neck.x, anchor.neck.y + 5.4, 2.9), SvgPaint.hex("#B98A22"), SvgStrokeStyle(lineWidth = 0.8))
            c.stroke(SvgShapes.circle(anchor.neck.x, anchor.neck.y + 5.4, 1.1), SvgPaint.hex("#B98A22"), SvgStrokeStyle(lineWidth = 0.7))
        } else {
            c.stroke(
                "M${anchor.neck.x - anchor.neck.w} ${anchor.neck.y} Q${anchor.neck.x} ${anchor.neck.y + 6} ${anchor.neck.x + anchor.neck.w} ${anchor.neck.y}",
                SvgPaint.hex("#EF6F6C"),
                SvgStrokeStyle(lineWidth = 3.4, cap = SvgLineCap.ROUND),
            )
            c.fill(SvgShapes.circle(anchor.neck.x, anchor.neck.y + 4, 3.0), SvgPaint.hex("#FFD54F"))
            c.fill(SvgShapes.circle(anchor.neck.x, anchor.neck.y + 4, 0.9), SvgPaint.hex("#B98A22"))
        }
    }
    if (has(Accessory.Cat.bow)) {
        drawBow(c, anchor.headTop.x - headR * 0.5, anchor.headTop.y + headR * 0.18, 0.95, "#FF7EA8")
    }
    if (has(Accessory.Cat.partyHat)) {
        c.fill(
            "M$headCX ${headCY - headR * 1.7} L${headCX - headR * 0.5} ${headCY - headR * 0.8} L${headCX + headR * 0.5} ${headCY - headR * 0.8} Z",
            SvgPaint.hex("#4FC3F7"),
        )
        c.fill(
            "M${headCX - headR * 0.18} ${headCY - headR * 1.2} L${headCX + headR * 0.26} ${headCY - headR * 1.1} L${headCX + headR * 0.2} ${headCY - headR * 0.87} L${headCX - headR * 0.24} ${headCY - headR * 0.95} Z",
            SvgPaint.hex("#FFD54F"),
        )
        c.fill(SvgShapes.circle(headCX, headCY - headR * 1.73, 2.6), SvgPaint.hex("#FF8A65"))
    }

    if (spec.sparkle > 0) {
        drawSparkles(
            c,
            (0 until spec.sparkle).map { i ->
                val a = i.toDouble() / spec.sparkle * PI * 2 + 0.5
                Triple(bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 8), 1.6 + (i % 3))
            },
        )
    }
}
