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
import fr.dappit.attrapelettres.core.mascot.mix
import fr.dappit.attrapelettres.core.mascot.pick
import fr.dappit.attrapelettres.core.mascot.ramp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of `src/mascot/Dragon.tsx` — the « Braise » rig (via the finished Swift
// port, Sources/ALArt/Mascot/Dragon.swift; the web file wins where they could
// disagree; they do not).
//
// The TSX component becomes `drawDragonRig(c, config, layout, stage, mood,
// preview)` per spec/mascot.md §3: `RigProps.uid` has no port (gradients/clips
// are values, not document-namespace defs) and `layout` is computed by the
// caller (`layoutFor`). Draw order is the TSX statement order, verbatim (D15).

// Dragon — « Braise » timeline. A deep-green fire dragon whose volcano wakes as
// he grows: horn nubs (2) → ember wings (3) → charcoal mohawk crest (4) →
// belly plates (5) → first flame (6) → fangs + claws + gold horn tips (7) →
// lava cracks + nostril smoke (8) → fire storm with ground glow (9).
// Stades 0-1 hatch from a cracked egg, shell kept as souvenirs.
// Wardrobe: colours body ×4 / ventre magma / ailes ×2 / cornes ×2 · styles
// cornes doubles, crête de lave, queue massue, queue de feu · accessories cape
// de chevalier (2+), lunettes d'aviateur (3+), collier de croc (2+), petit
// trésor (dès l'œuf), premium « Flamme bleue » (own beats at 4/7/9).
// No swim pair — the cross-species tradition is deliberately broken here.

private data class DSpec(
    val egg: Egg? = null,
    val horn: Double,
    val wing: Double,
    val crest: Int,
    val plates: Boolean,
    val flame: Double,
    val fierce: Boolean = false,
    val cracks: Boolean = false,
    val smoke: Boolean = false,
    val aura: Double,
    val ember: Int,
    val goldTips: Boolean = false,
    val ground: Boolean = false,
) {
    enum class Egg { FULL, BITS }
}

private val STAGES: List<DSpec> = listOf(
    DSpec(egg = DSpec.Egg.FULL, horn = 0.0, wing = 0.0, crest = 0, plates = false, flame = 0.0, aura = 0.0, ember = 0), // 0 in the egg
    DSpec(egg = DSpec.Egg.BITS, horn = 0.0, wing = 0.0, crest = 0, plates = false, flame = 0.0, aura = 0.0, ember = 0), // 1 shell souvenirs
    DSpec(horn = 3.5, wing = 0.0, crest = 0, plates = false, flame = 0.0, aura = 0.0, ember = 0), // 2 horn nubs
    DSpec(horn = 5.0, wing = 0.9, crest = 0, plates = false, flame = 0.0, aura = 0.0, ember = 0), // 3 ember wings
    DSpec(horn = 6.0, wing = 1.15, crest = 3, plates = false, flame = 0.0, aura = 0.0, ember = 0), // 4 charcoal mohawk
    DSpec(horn = 7.0, wing = 1.3, crest = 4, plates = true, flame = 0.0, aura = 0.0, ember = 0), // 5 belly plates
    DSpec(horn = 8.0, wing = 1.45, crest = 4, plates = true, flame = 0.5, aura = 0.12, ember = 2), // 6 first flame
    DSpec(horn = 10.0, wing = 1.7, crest = 5, plates = true, flame = 0.62, fierce = true, aura = 0.3, ember = 3, goldTips = true), // 7 fangs + claws
    DSpec(horn = 11.0, wing = 1.9, crest = 5, plates = true, flame = 0.72, fierce = true, cracks = true, smoke = true, aura = 0.55, ember = 4, goldTips = true), // 8 volcano wakes
    DSpec(horn = 13.0, wing = 2.2, crest = 6, plates = true, flame = 1.1, fierce = true, cracks = true, smoke = true, aura = 1.0, ember = 8, goldTips = true, ground = true), // 9 fire storm
)

private const val EGG_SPECKLE = "#E8C49A"
private const val HORN_FILL = "#EDE3CE"
private const val HORN_EDGE = "#B8A98C"
private const val CREST = "#5F6470"
private const val CREST_EDGE = "#3E4148"
private const val LAVA_CREST = "#FF8A50"
private const val LAVA_CREST_EDGE = "#C2502E"
private const val WING_EDGE = "#A83E28"
private const val EMBER = "#FF8A50"
private const val BLUE_OUTER = "#5BC8FF"
private const val BLUE_INNER = "#E8F7FF"
private const val BLUE_EMBER = "#7FD1FF"

fun drawDragonRig(
    c: SvgCanvas,
    config: MascotConfig,
    layout: Layout,
    stage: Int,
    mood: Mood,
    preview: Boolean = false,
) {
    val body = pick(config.colors, ColorSlot.Dragon.body, "#7DB874")
    val belly = pick(config.colors, ColorSlot.Dragon.belly, "#E9DFB2")
    val wingCol = pick(config.colors, ColorSlot.Dragon.wing, "#E2694F")
    val hornCol = pick(config.colors, ColorSlot.Dragon.horn, HORN_FILL)
    val hornEdge = if (hornCol == HORN_FILL) HORN_EDGE else mix(hornCol, INK, 0.35)
    val doubleHorns = pick(config.styles, StyleSlot.Dragon.horn, "straight") == "double"
    val lavaCrest = pick(config.styles, StyleSlot.Dragon.crest, "charbon") == "lava"
    val tailPick = pick(config.styles, StyleSlot.Dragon.tail, "spade")
    fun has(id: String): Boolean = config.accessories.contains(id)
    val blue = has(Accessory.Dragon.blueFlame)

    var spec = STAGES[stage.coerceIn(0, 9)]
    // Shop thumbnail: strip the egg + all free per-stage magic so a ghost dragon
    // shows only what a tile sells (sold styles/accessories stay visible).
    if (preview) {
        spec = spec.copy(
            egg = null,
            flame = 0.0,
            aura = 0.0,
            ember = 0,
            fierce = false,
            cracks = false,
            smoke = false,
            goldTips = false,
            ground = false,
        )
    }

    val bodyCX = layout.bodyCX
    val bodyCY = layout.bodyCY
    val bodyRX = layout.bodyRX
    val bodyRY = layout.bodyRY
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val eyeR = layout.eyeR
    val anchor = accessoryAnchors(Species.DRAGON, layout)
    val legW = 7.0
    val tailEdge = mix(body, INK, 0.35)
    val crestCol = if (lavaCrest) LAVA_CREST else CREST
    val crestEdge = if (lavaCrest) LAVA_CREST_EDGE else CREST_EDGE
    // The flame tail lights up with the walking stades; before that it stays a spade.
    val tailTipRaw = if (tailPick == "flame" && stage < 2 && !preview) "spade" else tailPick
    // NB: an unknown tailStyle string maps to null → curve, no tip — same as the
    // TSX cast falling through every `tip === …` branch.
    val tailTip = DragonTailTip.fromWire(tailTipRaw)
    val tailTipColor = when (tailTip) {
        DragonTailTip.CLUB -> mix(body, INK, 0.12)
        DragonTailTip.SPADE -> belly
        else -> null
    }

    // -- Stade 0: hatching in the cracked egg --------------------------------
    if (spec.egg == DSpec.Egg.FULL) {
        if (has(Accessory.Dragon.treasure)) drawTreasure(c, stage, 14.0, layout.feetY + 2)
        c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
        c.stroke(
            "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
            SvgPaint.hex(body),
            SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
        )
        c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.96), SvgPaint.hex(body))
        drawSnout(c, headCX, headCY, headR, belly)
        drawEyes(c, headCX, headCY - headR * 0.05, headR * 0.36, eyeR * 0.85, mood, stage == 0)
        drawCheeks(c, headCX, headCY + headR * 0.32, headR * 0.62, headR * 0.13)
        drawMouth(c, headCX, headCY + headR * 0.42, headR * 0.1, mood)
        drawEggCup(c, EGG_SPECKLE)
        drawShellCap(c, headCX + 2, headCY - headR * 0.86, 1.0, 10.0, EGG_SPECKLE)
        drawSpadeTail(
            c,
            81.0 to 87.0,
            89.0 to 83.0,
            87.5 to 75.0,
            4.5,
            body,
            edge = tailEdge,
            tip = tailTip,
            tipColor = tailTipColor,
            tipS = 0.7,
        )
        return
    }

    // -- Stade 1: hatched, shell souvenirs ------------------------------------
    if (!layout.standing) {
        if (has(Accessory.Dragon.treasure)) drawTreasure(c, stage, bodyCX - bodyRX - 9, layout.feetY + 2)
        drawSpadeTail(
            c,
            (bodyCX + bodyRX * 0.65) to (bodyCY + 2),
            (bodyCX + bodyRX + 9) to bodyCY,
            (bodyCX + bodyRX + 8) to (bodyCY - 9),
            5.5,
            body,
            edge = tailEdge,
            tip = tailTip,
            tipColor = tailTipColor,
            tipS = 0.8,
        )
        c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
        c.fill(SvgShapes.ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), SvgPaint.hex(belly))
        drawFoldedLegs(c, bodyCX, bodyCY, bodyRX, body, INK)
        c.stroke(
            "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
            SvgPaint.hex(body),
            SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
        )
        c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.96), SvgPaint.hex(body))
        drawSnout(c, headCX, headCY, headR, belly)
        drawEyes(c, headCX, headCY - headR * 0.05, headR * 0.4, eyeR, mood, false)
        drawCheeks(c, headCX, headCY + headR * 0.32, headR * 0.62, headR * 0.13)
        drawMouth(c, headCX, headCY + headR * 0.52, headR * 0.13, mood)
        if (spec.egg == DSpec.Egg.BITS) {
            drawShellCap(c, headCX + 3, headCY - headR * 0.88, 0.82, -12.0, EGG_SPECKLE)
            drawShellShard(c, bodyCX - bodyRX * 0.62, bodyCY + bodyRY * 0.42, 0.9, -10.0, EGG_SPECKLE)
        }
        return
    }

    // -- Stades 2-9: on its feet ----------------------------------------------
    val tf = ramp(stage.toDouble(), listOf(RampStop(2.0, 0.0), RampStop(5.0, 0.5), RampStop(9.0, 1.0)))
    val tailRoot = (bodyCX + bodyRX * 0.45) to (bodyCY + bodyRY * 0.5)
    val tailCtrl = (bodyCX + bodyRX * 1.5) to (bodyCY + bodyRY * 0.9)
    val tailEnd = (bodyCX + bodyRX * (1.42 + 0.1 * tf)) to (bodyCY + bodyRY * (0.45 - (0.5 + 0.55 * tf)))
    val mouthY = headCY + headR * 0.52
    val mouthW = headR * 0.13
    // ramp() clamps below its first stop — gate by stage or the "blue from 4"
    // breath would leak down to the wobbly stades.
    val flameS = if (blue && stage >= 4) {
        max(
            spec.flame,
            ramp(
                stage.toDouble(),
                listOf(RampStop(4.0, 0.5), RampStop(6.0, 0.62), RampStop(7.0, 0.85), RampStop(9.0, 1.25)),
            ),
        )
    } else {
        spec.flame
    }
    val emberN = if (blue && stage >= 7) max(spec.ember, 4) else spec.ember
    val emberCol = if (blue) BLUE_EMBER else EMBER

    if (spec.ground) {
        drawGroundGlow(c, 50.0, layout.feetY + 2, bodyRX + 18, if (blue) "#9FD4FF" else "#FF9A66", 0.9)
    }
    if (spec.aura > 0) {
        drawAura(c, bodyCX, bodyCY - 2, bodyRX + 22, if (blue) "#B8E2FF" else "#FFB27A", spec.aura)
    }

    drawBatWings(c, bodyCX, bodyCY - bodyRY * 0.4, spec.wing, wingCol, WING_EDGE)
    if (has(Accessory.Dragon.cape)) {
        drawCapeBack(c, bodyCX, bodyCY - bodyRY * 0.62, bodyRX * 1.3, layout.feetY - 2 - (bodyCY - bodyRY * 0.62))
    }
    drawSpadeTail(
        c,
        tailRoot,
        tailCtrl,
        tailEnd,
        6 + 1.5 * tf,
        body,
        edge = tailEdge,
        tip = tailTip,
        tipColor = tailTipColor,
        tipS = 0.85 + 0.8 * tf,
    )

    for (l in layout.legs.filter { it.back }) {
        drawLeg(c, l, legW, body, INK)
    }

    c.fill(SvgShapes.ellipse(bodyCX, bodyCY, bodyRX, bodyRY), SvgPaint.hex(body))
    if (spec.cracks) drawCracks(c, bodyCX, bodyCY, bodyRX, bodyRY, emberCol)
    c.fill(SvgShapes.ellipse(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6), SvgPaint.hex(belly))
    if (spec.plates) {
        drawBellyPlates(c, bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 0.55, bodyRY * 0.6, "#C4B584")
    }

    c.stroke(
        "M$bodyCX ${bodyCY - bodyRY * 0.5} L$headCX ${headCY + headR * 0.4}",
        SvgPaint.hex(body),
        SvgStrokeStyle(lineWidth = headR * 0.95, cap = SvgLineCap.ROUND),
    )
    for (l in layout.legs.filter { !it.back }) {
        drawLeg(c, l, legW, body, INK)
    }
    if (spec.fierce) {
        for (l in layout.legs.filter { !it.back }) {
            drawClaws(c, l.footX - 1.4, l.footY - 0.6, 4.0)
        }
    }

    drawCrest(c, headCX, headCY, headR, spec.crest, color = crestCol, edge = crestEdge)
    drawHorns(
        c,
        headCX,
        headCY,
        headR,
        spec.horn,
        if (doubleHorns) DragonHornVariant.DOUBLE else DragonHornVariant.STRAIGHT,
        hornCol,
        hornEdge,
        if (spec.goldTips) "#FFD54F" else null,
    )
    c.fill(SvgShapes.ellipse(headCX, headCY, headR, headR * 0.96), SvgPaint.hex(body))

    drawSnout(c, headCX, headCY, headR, belly)
    if (spec.smoke) drawSmokePuffs(c, headCX, headCY, headR)
    drawEyes(c, headCX, headCY - headR * 0.05, headR * 0.4, eyeR, mood, false)
    drawCheeks(c, headCX, headCY + headR * 0.32, headR * 0.62, headR * 0.13)
    drawMouth(c, headCX, mouthY, mouthW, mood)
    if (spec.fierce) drawFangs(c, headCX, mouthY + 0.4, mouthW * 1.15)

    if (flameS > 0) {
        if (blue) {
            drawFlamePuff(c, headCX + headR * 0.3, headCY + headR * 0.6, flameS, 125.0, BLUE_OUTER, BLUE_INNER)
        } else {
            drawFlamePuff(c, headCX + headR * 0.3, headCY + headR * 0.6, flameS, 125.0)
        }
        // legendary double breath — the blue flame's own growth beat
        if (blue && stage >= 7) {
            drawFlamePuff(c, headCX + headR * 0.05, headCY + headR * 0.72, flameS * 0.55, 145.0, BLUE_OUTER, BLUE_INNER)
        }
    }

    // accessories over the body
    if (stage >= 3 && has(Accessory.Dragon.goggles)) {
        drawGoggles(c, headCX, headCY - headR * 0.72, headR)
    }
    // if the cape clasp already sits on the throat, the fang cord drops a touch
    if (has(Accessory.Dragon.fang)) {
        drawFangPendant(c, anchor.neck.x, anchor.neck.y + (if (has(Accessory.Dragon.cape)) 3.0 else 0.0), anchor.neck.w)
    }
    if (has(Accessory.Dragon.cape)) {
        drawCapeClasp(c, anchor.neck.x, anchor.neck.y, anchor.neck.w)
    }
    // fixed-size coins/jewels — the QUANTITY grows with the stage. x is
    // clamped left of the back hoof: the wobbly stades splay their legs and
    // the hoof otherwise catches the gold (DA redline B1).
    if (has(Accessory.Dragon.treasure)) {
        val backFootL = layout.legs[0].footX - 4.6
        val tRight = if (stage >= 7) 17.0 else if (stage >= 6) 8.6 else if (stage >= 3) 5.7 else 2.8
        val tLeft = bodyCX - bodyRX - 5 - 4 * tf
        val tX = min(tLeft, backFootL - tRight - 1)
        drawTreasure(c, stage, tX, layout.feetY + 1)
    }

    if (emberN > 0) {
        for (i in 0 until emberN) {
            val a = i.toDouble() / emberN * PI * 2 + 0.8
            val r = 1.1 + (i % 3) * 0.55
            c.fill(
                SvgShapes.ellipse(bodyCX + cos(a) * (bodyRX + 12), bodyCY + sin(a) * (bodyRY + 9) - 4, r, r),
                SvgPaint.hex(emberCol, 0.55 + 0.15 * (i % 3)),
            )
        }
    }
}
