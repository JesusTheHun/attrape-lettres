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
import fr.dappit.attrapelettres.core.mascot.RampStop
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.accessoryAnchors
import fr.dappit.attrapelettres.core.mascot.pick
import fr.dappit.attrapelettres.core.mascot.ramp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of `src/mascot/Fox.tsx` (structure via the finished Swift port,
// Sources/ALArt/Mascot/Fox.swift).
//
// Fox — "Kitsune" timeline. Each stade 3→9 sprouts a clearly visible beat on
// the way to a many-tailed fire-spirit:
//  0-2 (untouched) curled kit → lifting head → first steps
//  3 ear-tufts + ruff · 4 a 2nd tail · 5 a 3rd tail
//  6 sparkle + flame-tipped tails · 7 five tails · 8 forehead mark + halo
//  9 seven glowing tails, flame tips, halo, ground glow, sparkle burst.
// The extra tails are a DEEPER orange with a dark outline + cream tips, so they
// separate from the same-coloured body instead of merging into one mass.

// NB on the TS optionals: `flames?`/`mark?`/`ground?` collapse to non-null
// `Boolean = false` (every TSX read is truthiness-shaped) and `extraTails?`
// to `Int = 0` (the TSX reads it `spec.extraTails ?? 0`). `halo` stays
// nullable: "absent" means no halo, and no STAGES row writes 0, so nullability
// carries the whole distinction. Rows use named arguments in the TSX literal
// order, so a row reads like its source line.
private data class FSpec(
    val tail: Double,
    val ruff: Double,
    val tuft: Boolean,
    val aura: Double,
    val sparkle: Int,
    /** number of extra fanned kitsune tails (on top of the base tail). */
    val extraTails: Int = 0,
    /** flame tips on the fanned tails. */
    val flames: Boolean = false,
    /** forehead spirit-mark. */
    val mark: Boolean = false,
    /** head halo opacity 0..1. */
    val halo: Double? = null,
    /** pool of light under the paws. */
    val ground: Boolean = false,
)

private const val TAIL_DEEP = "#F26B3C"
private const val TAIL_EDGE = "#B8431C"
private const val TAIL_TIP = "#FFF6EE"

private val STAGES = listOf(
    FSpec(tail = 0.55, ruff = 0.4, tuft = false, aura = 0.0, sparkle = 0), // 0
    FSpec(tail = 0.65, ruff = 0.45, tuft = false, aura = 0.0, sparkle = 0), // 1
    FSpec(tail = 0.8, ruff = 0.55, tuft = false, aura = 0.0, sparkle = 0), // 2
    FSpec(tail = 0.95, ruff = 0.7, tuft = true, aura = 0.0, sparkle = 0), // 3 ear tufts + ruff
    FSpec(tail = 1.05, ruff = 0.8, tuft = true, aura = 0.0, sparkle = 0, extraTails = 1), // 4 2nd tail
    FSpec(tail = 1.15, ruff = 0.9, tuft = true, aura = 0.0, sparkle = 0, extraTails = 2), // 5 3 tails
    FSpec(tail = 1.25, ruff = 1.0, tuft = true, aura = 0.2, sparkle = 1, extraTails = 2, flames = true), // 6 flame tips
    FSpec(tail = 1.4, ruff = 1.1, tuft = true, aura = 0.4, sparkle = 2, extraTails = 4, flames = true), // 7 5 tails
    FSpec(tail = 1.55, ruff = 1.2, tuft = true, aura = 0.7, sparkle = 3, extraTails = 4, flames = true, mark = true, halo = 0.55), // 8
    FSpec(tail = 1.8, ruff = 1.3, tuft = true, aura = 1.0, sparkle = 6, extraTails = 6, flames = true, mark = true, halo = 0.55, ground = true), // 9 kitsune
)

/** Small two-tone flame. */
internal fun drawFlame(c: SvgCanvas, x: Double, y: Double, s: Double) {
    c.save()
    c.translate(x, y)
    c.scale(s)
    c.fill("M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z", SvgPaint.hex("#FF7043"))
    c.fill("M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z", SvgPaint.hex("#FFE082"))
    c.restore()
}

/** One fur clump along a fanned-tail curve. */
private data class FanClump(val px: Double, val py: Double, val r: Double, val tip: Boolean)

/**
 * One fanned kitsune tail — a bushy fur-clump curve with an outline underlay
 * + a deeper fill + cream tip, so it reads apart from the body and its siblings.
 * NB: the TSX takes a `ki` prop that is only a React key; it has no port.
 */
internal fun drawFanTail(
    c: SvgCanvas,
    rootX: Double,
    rootY: Double,
    angleDeg: Double,
    length: Double,
    rB: Double,
    flame: Boolean,
) {
    val a = angleDeg * PI / 180
    val perp = a + PI / 2
    val ex = rootX + cos(a) * length
    val ey = rootY + sin(a) * length
    val cx = rootX + cos(a) * length * 0.5 + cos(perp) * length * 0.16
    val cy = rootY + sin(a) * length * 0.5 + sin(perp) * length * 0.16
    fun bez(t: Double): Pair<Double, Double> = Pair(
        (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * cx + t * t * ex,
        (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * cy + t * t * ey,
    )
    val clumps = (0 until 6).map { i ->
        val t = i / 5.0
        val (px, py) = bez(t)
        FanClump(px, py, rB * (0.55 + 0.5 * sin(PI * (0.12 + 0.8 * t))), t > 0.72)
    }
    val (tx, ty) = bez(1.0)
    for (b in clumps) {
        c.fill(SvgShapes.circle(b.px, b.py, b.r + 1.1), SvgPaint.hex(TAIL_EDGE))
    }
    for (b in clumps) {
        c.fill(SvgShapes.circle(b.px, b.py, b.r), SvgPaint.hex(if (b.tip) TAIL_TIP else TAIL_DEEP))
    }
    if (flame) {
        drawFlame(c, tx, ty, 0.6)
    }
}

/** One fur clump along the base tail curve. */
private data class Clump(val px: Double, val py: Double, val r: Double)

fun drawFoxRig(
    c: SvgCanvas,
    config: MascotConfig,
    layout: Layout,
    stage: Int,
    mood: Mood,
    preview: Boolean = false,
) {
    val body = pick(config.colors, ColorSlot.Fox.body, "#FF8A65")
    // Default belly is pure white so the warm "Ventre crème" option reads clearly.
    val belly = pick(config.colors, ColorSlot.Fox.belly, "#FFFFFF")
    val tailTip = pick(config.colors, ColorSlot.Fox.tailTip, "#FFFFFF")
    val pattern = pick(config.styles, StyleSlot.Fox.fur, "plain")
    val longTail = pick(config.styles, StyleSlot.Fox.tail, "long") == "long"
    fun has(id: String): Boolean = config.accessories.contains(id)

    var spec = STAGES[stage.coerceIn(0, 9)]
    // Shop thumbnail: keep the base tail + ruff (sold parts) but drop the extra
    // kitsune tails, flames, spirit-mark, halo and glow so a ghost fox shows ONLY
    // what this tile changes.
    if (preview) {
        spec = spec.copy(
            tuft = false,
            aura = 0.0,
            sparkle = 0,
            extraTails = 0,
            flames = false,
            mark = false,
            halo = null,
            ground = false,
        )
    }
    val tailF = spec.tail * (if (longTail) 1.0 else 0.66)
    val bodyCX = layout.bodyCX
    val bodyCY = layout.bodyCY
    val bodyRX = layout.bodyRX
    val bodyRY = layout.bodyRY
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val eyeR = layout.eyeR
    val anchor = accessoryAnchors(Species.FOX, layout)
    val legW = 7.0

    // Bushy tail = fur clumps along a curve; it tapers to a pointed TIP whose fur
    // clumps carry the recolour, so the tint follows the tail silhouette to a point.
    val rootX = bodyCX + bodyRX * 0.55
    val rootY = bodyCY - bodyRY * 0.22
    val ctrlX = bodyCX + bodyRX * 1.28
    val ctrlY = bodyCY + bodyRY * 0.18
    val endX = bodyCX + bodyRX * 0.92
    val endY = bodyCY + bodyRY * 0.98
    val rB = 5 + 6 * tailF
    fun bez(t: Double): Pair<Double, Double> = Pair(
        (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * ctrlX + t * t * endX,
        (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * ctrlY + t * t * endY,
    )
    val bodyClumps = (0 until 6).map { i ->
        val t = i / 5.0 * 0.72
        val (px, py) = bez(t)
        Clump(px, py, rB * (0.75 + 0.35 * sin(PI * (t / 0.72))))
    }
    val tipClumps = (0 until 4).map { i ->
        val t = 0.76 + i / 3.0 * 0.24
        val (px, py) = bez(t)
        Clump(px, py, rB * (0.62 - 0.36 * (i / 3.0)))
    }

    val extra = spec.extraTails

    if (spec.ground) {
        drawGroundGlow(c, 50.0, layout.feetY + 2, bodyRX + 18, "#FFD59A", 0.85)
    }

    // lying baby rests ON its swim ring (drawn behind), face clear of the tube
    if (!layout.standing && has(Accessory.Fox.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.15, bodyRX * 1.15, "#FFD54F", false, SwimRingPart.FULL)
    }

    // extra kitsune tails, fanned symmetrically behind the body
    for (i in 0 until extra) {
        val spread = if (extra == 1) 0.0 else i.toDouble() / (extra - 1) - 0.5
        drawFanTail(
            c,
            bodyCX,
            bodyCY + bodyRY * 0.02,
            270 + spread * 132,
            bodyRX * (1.12 + 0.42 * tailF),
            4 + 3 * tailF,
            spec.flames,
        )
    }

    // bushy tail behind body; recolourable tapering tip
    if (spec.aura > 0) {
        drawAura(c, endX, endY, rB * 1.3, "#FFF1C4", spec.aura)
    }
    for (b in bodyClumps) {
        c.fill(SvgShapes.circle(b.px, b.py, b.r), SvgPaint.hex(body))
    }
    for (b in tipClumps) {
        c.fill(SvgShapes.circle(b.px, b.py, b.r), SvgPaint.hex(tailTip))
    }

    // back legs
    for (l in layout.legs) {
        if (l.back) drawLeg(c, l, legW, body, INK)
    }

    // swim ring, back half — behind the body so the pet sits IN the tube
    if (layout.standing && has(Accessory.Fox.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FFD54F", false, SwimRingPart.BACK)
    }

    // body
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))

    // fur pattern (clearly visible)
    if (pattern == "spots") {
        for ((dx, dy) in listOf(-0.42 to -0.28, 0.28 to -0.4, 0.02 to -0.05, -0.15 to 0.28, 0.36 to 0.05)) {
            c.fill(SvgShapes.ellipse(bodyCX + dx * bodyRX, bodyCY + dy * bodyRY, 3.2, 3.2), SvgPaint.hex("#A24A2C", opacity = 0.85))
        }
    }
    if (pattern == "stripes") {
        for (dy in listOf(-0.4, -0.05, 0.3)) {
            c.stroke(
                "M${bodyCX - bodyRX * 0.72} ${bodyCY + dy * bodyRY} Q$bodyCX ${bodyCY + dy * bodyRY - 5} ${bodyCX + bodyRX * 0.72} ${bodyCY + dy * bodyRY}",
                SvgPaint.hex("#8A4326", opacity = 0.4),
                SvgStrokeStyle(lineWidth = 2.6, cap = SvgLineCap.ROUND),
            )
        }
    }

    c.fill(SvgShapes.ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), SvgPaint.hex(belly))
    if (!layout.standing) {
        drawFoldedLegs(c, bodyCX, bodyCY, bodyRX, body, INK)
    }
    // lying culotte sits on the rump, UNDER the resting head/neck
    if (!layout.standing && has(Accessory.Fox.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#5AA9E0", "#FFF6EE", true, false)
    }

    // neck
    c.stroke(
        "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
        SvgPaint.hex(body),
        SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
    )

    // front legs
    for (l in layout.legs) {
        if (!l.back) drawLeg(c, l, legW, body, INK)
    }

    // chest ruff
    c.fill(
        "M${headCX - headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Q$headCX ${headCY + headR * 1.5 * spec.ruff} ${headCX + headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Z",
        SvgPaint.hex(belly),
    )

    // halo (behind the head)
    spec.halo?.let { halo ->
        drawHalo(c, headCX, headCY, headR * 1.7, halo, "#FFD59A")
    }

    // head
    c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.96), SvgPaint.hex(body))

    // ears — tall but ROUNDED tips, soft dark tip
    for (d in listOf(-1.0, 1.0)) {
        val ex = headCX + d * headR * 0.58
        val ey = headCY - headR * 0.5
        val th = headR * ramp(stage.toDouble(), listOf(RampStop(0.0, 0.55), RampStop(4.0, 0.72), RampStop(9.0, 0.78)))
        val tipX = ex + d * th * 0.32
        val tipY = ey - th
        c.fill(
            "M${ex - d * headR * 0.5} $ey Q${tipX - d * th * 0.15} ${tipY - 2} ${tipX + d * 1.5} ${tipY + th * 0.14} Q${tipX + d * th * 0.12} ${tipY + th * 0.1} ${ex + d * headR * 0.05} ${ey - headR * 0.05} Z",
            SvgPaint.hex(body),
        )
        c.fill(
            "M$tipX ${tipY + th * 0.05} Q${tipX + d * th * 0.14} ${tipY + th * 0.12} ${tipX + d * 1} ${tipY + th * 0.3} Q${tipX - d * th * 0.06} ${tipY + th * 0.24} $tipX ${tipY + th * 0.05} Z",
            SvgPaint.hex(INK),
        )
        if (spec.tuft) {
            drawPlume(c, ex - d * headR * 0.02, ey - headR * 0.02, belly, 5.0, 3.0, d * 10, 2, false)
        }
    }

    // white cheek ruff (grows)
    for (d in listOf(-1.0, 1.0)) {
        drawPlume(c, headCX + d * headR * 0.72, headCY + headR * 0.3, belly, 6 + 5 * spec.ruff, 5.0, d * 60, 3, false)
    }

    // snout
    c.fill(SvgShapes.ellipse(headCX, headCY + headR * 0.42, headR * 0.42, headR * 0.32), SvgPaint.hex(belly))
    c.fill(SvgShapes.ellipse(headCX, headCY + headR * 0.3, 2.4, 2.0), SvgPaint.hex(INK))

    // face — softened, rounder eyes
    drawEyes(c, headCX, headCY - headR * 0.02, headR * 0.4, eyeR * 1.05, mood, stage == 0)
    drawCheeks(c, headCX, headCY + headR * 0.32, headR * 0.62, headR * 0.13)
    drawMouth(c, headCX, headCY + headR * 0.5, headR * 0.14, mood)

    // forehead spirit-mark (kitsune)
    if (spec.mark) {
        c.fill(fourStar(headCX, headCY - headR * 0.55, 2.6), SvgPaint.hex("#FFF3C4"))
    }

    // accessories — placement from the per-species/per-stage anchor resolver
    // swim set — grows with its wearer: rump culotte on the newborn (drawn
    // earlier, under the head) → striped suit → star badge (6+); the ring's
    // front half closes the tube around the waist, duck head at 7+.
    if (layout.standing && has(Accessory.Fox.swimsuit)) {
        drawSwimsuit(c, bodyCX, bodyCY, bodyRX, bodyRY, "#5AA9E0", "#FFF6EE", false, stage >= 6)
    }
    if (layout.standing && has(Accessory.Fox.swimRing)) {
        drawSwimRing(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.22, "#FFD54F", stage >= 7, SwimRingPart.FRONT)
    }
    if (has(Accessory.Fox.scarf)) {
        c.stroke(
            "M${anchor.neck.x - anchor.neck.w} ${anchor.neck.y} Q${anchor.neck.x} ${anchor.neck.y + 7} ${anchor.neck.x + anchor.neck.w} ${anchor.neck.y}",
            SvgPaint.hex("#66BB6A"),
            SvgStrokeStyle(lineWidth = 5.0, cap = SvgLineCap.ROUND),
        )
        c.fill(
            "M${anchor.neck.x + anchor.neck.w * 0.5} ${anchor.neck.y + 2} l3 10 l-6 1 Z",
            SvgPaint.hex("#4FA84E"),
        )
    }
    if (has(Accessory.Fox.beanie)) {
        c.fill(
            "M${headCX - headR} ${headCY - headR * 0.5} Q$headCX ${headCY - headR * 1.7} ${headCX + headR} ${headCY - headR * 0.5} Z",
            SvgPaint.hex("#4FC3F7"),
        )
        c.fill(
            SvgShapes.roundedRect(headCX - headR, headCY - headR * 0.62, headR * 2, headR * 0.3, headR * 0.15),
            SvgPaint.hex("#2E9BD6"),
        )
        c.fill(SvgShapes.circle(headCX, headCY - headR * 1.55, 3.4), SvgPaint.hex("#FFF3E6"))
    }
    if (has(Accessory.Fox.boots)) {
        for (l in anchor.feet) {
            c.fill(SvgShapes.roundedRect(l.footX - 4, l.footY - 6, 8.0, 8.0, 3.0), SvgPaint.hex("#8D5A3B"))
            c.fill(SvgShapes.roundedRect(l.footX - 5, l.footY - 7, 10.0, 3.0, 1.5), SvgPaint.hex("#C98A5B"))
        }
    }

    if (spec.sparkle > 0) {
        drawSparkles(
            c,
            (0 until spec.sparkle).map { i ->
                val a = i.toDouble() / spec.sparkle * PI * 2 + 1
                Triple(bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 8), 1.6 + (i % 3))
            },
        )
    }
}
