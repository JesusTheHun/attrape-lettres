package fr.dappit.attrapelettres.art.images

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgPoint
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgStop
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.art.svg.drawSvg
import fr.dappit.attrapelettres.core.domain.ImageKey

// The four standalone word illustrations — `src/img/{igloo,jupe,macaron,pyjama}.svg`
// — through the same `d`-string parser and the same `SvgCanvas` substrate as
// everything else (iOS: Sources/ALArt/Images/WordImages.swift). Every `d`
// string, every `cx`/`cy`/`rx`, every hex and every gradient stop is copied
// verbatim from the file.
//
// `content.ts` reaches them as Vite asset URLs (`import jupe from "../img/jupe.svg"`);
// :core knows nothing about resources, so it carries `ImageKey` and this file
// owns the key -> drawing table. That table is an EXHAUSTIVE `when` over the
// enum, never a map, for the same reason as the exercise icons: a map lets a
// new key compile and render nothing. (Kotlin checks a `when` statement over an
// enum for exhaustiveness too, so a fifth `ImageKey` is a compile error here.)
//
// Gradients: 12 across the four files, none of them declaring `gradientUnits`,
// so all 12 are `objectBoundingBox` — which is `SvgPaint`'s default. Eleven run
// `(0,0) -> (1,1)` diagonally; the igloo's door is the one vertical
// `(0,0) -> (0,1)`.
//
// NB: the files carry `fill-opacity` / `stroke-opacity` (drop shadows at 0.10,
// jupe's pleat strokes at 0.4, pyjama's dots at 0.8, the highlight ellipses at
// 0.22 / 0.28 / 0.45). Those ride on the paint's `opacity`, not on a group.

object WordImages {

    /** Every one of the four files is `viewBox="0 0 128 128"`. */
    val viewBox = SvgRect(0.0, 0.0, 128.0, 128.0)

    /** The `<title>` element, which is what `aria-labelledby` announces. */
    fun label(key: ImageKey): String = when (key) {
        ImageKey.IGLOO -> "Igloo"
        ImageKey.JUPE -> "Jupe"
        ImageKey.MACARON -> "Macaron"
        ImageKey.PYJAMA -> "Pyjama"
    }

    /**
     * Draw [key] into a canvas whose user space is the 128-unit viewBox.
     *
     * Exhaustive `when`, no `else` — see the file header.
     */
    fun draw(key: ImageKey, canvas: SvgCanvas) {
        when (key) {
            ImageKey.IGLOO -> drawIgloo(canvas)
            ImageKey.JUPE -> drawJupe(canvas)
            ImageKey.MACARON -> drawMacaron(canvas)
            ImageKey.PYJAMA -> drawPyjama(canvas)
        }
    }
}

// --- Shared helpers ----------------------------------------------------------

/**
 * `<linearGradient x1="0" y1="0" x2="1" y2="1">` with two stops — eleven of
 * the twelve gradients in these files.
 */
private fun diagonal(from: String, to: String): SvgPaint = SvgPaint.linear(
    listOf(SvgStop.hex(from, 0f), SvgStop.hex(to, 1f)),
    start = SvgPoint(0f, 0f),
    end = SvgPoint(1f, 1f),
)

private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<SvgPathCommand> =
    SvgShapes.ellipse(cx, cy, rx, ry)

private fun circle(cx: Double, cy: Double, r: Double): List<SvgPathCommand> =
    SvgShapes.circle(cx, cy, r)

private fun stroke(
    width: Double,
    cap: SvgLineCap = SvgLineCap.BUTT,
    join: SvgLineJoin = SvgLineJoin.MITER,
): SvgStrokeStyle = SvgStrokeStyle(lineWidth = width, cap = cap, join = join)

/**
 * `<ellipse ... transform="rotate(a cx cy)">` — the rotation goes on the CTM,
 * never baked into the geometry, exactly as SVG composes it.
 */
private fun rotatedEllipse(
    c: SvgCanvas,
    degrees: Double,
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    paint: SvgPaint,
) {
    c.save()
    c.rotate(degrees, cx, cy)
    c.fill(ellipse(cx, cy, rx, ry), paint)
    c.restore()
}

/** `fill="#000000" fill-opacity="0.10"` — the ground shadow under all four. */
private val dropShadow = SvgPaint.hex("#000000", opacity = 0.10)

// --- Jupe --------------------------------------------------------------------

private fun drawJupe(c: SvgCanvas) {
    // <defs>
    val skirt = diagonal("#FBA895", "#EF7A64") // ju-skirt
    val band = diagonal("#F08B76", "#DB6650") // ju-band
    val limb = diagonal("#CFD4D9", "#AFB5BC") // ju-limb
    val shirt = diagonal("#BEC4CB", "#A2A9B1") // ju-shirt
    val hair = diagonal("#A2A8AF", "#868C93") // ju-hair
    val outline = SvgPaint.hex("#9BA1A8")

    c.fill(ellipse(64.0, 114.0, 27.0, 5.0), dropShadow)

    // legs
    val legL = "M56 70 L55 106 Q55 109 59 109 L61 109 Q63 109 63 106 L63 70 Z"
    c.fill(legL, limb)
    c.stroke(legL, outline, stroke(1.0))
    val legR = "M65 70 L65 106 Q65 109 67 109 L69 109 Q73 109 73 106 L72 70 Z"
    c.fill(legR, limb)
    c.stroke(legR, outline, stroke(1.0))

    // shoes
    c.fill(
        "M52 106 Q52 104 55 104 L63 104 L63 109 Q63 111 60 111 L54 111 Q52 111 52 109 Z",
        SvgPaint.hex("#8B9198"),
    )
    c.fill(
        "M65 104 L73 104 Q76 104 76 106 L76 109 Q76 111 74 111 L68 111 Q65 111 65 109 Z",
        SvgPaint.hex("#8B9198"),
    )

    // torso / shirt
    val torso = "M50 44 Q50 42 53 42 L75 42 Q78 42 78 44 L79 62 Q79 63 77 63 L51 63 Q49 63 49 62 Z"
    c.fill(torso, shirt)
    c.stroke(torso, outline, stroke(1.0))

    // arms
    val armL = "M50 45 Q45 47 44 53 L43 65 Q43 68 46 68 Q49 68 49 65 L50 54 Q50 48 52 46 Z"
    c.fill(armL, limb)
    c.stroke(armL, outline, stroke(1.0))
    val armR = "M78 45 Q83 47 84 53 L85 65 Q85 68 82 68 Q79 68 79 65 L78 54 Q78 48 76 46 Z"
    c.fill(armR, limb)
    c.stroke(armR, outline, stroke(1.0))
    val handL = circle(46.0, 68.0, 3.0)
    c.fill(handL, limb)
    c.stroke(handL, outline, stroke(0.8))
    val handR = circle(82.0, 68.0, 3.0)
    c.fill(handR, limb)
    c.stroke(handR, outline, stroke(0.8))

    // SKIRT (the only colour)
    val skirtPath =
        "M52 64 L42 88 Q46 92 50 88 Q54 92 58 88 Q62 92 66 88 Q70 92 74 88 Q78 92 82 88 Q84 91 86 88 L76 64 Z"
    c.fill(skirtPath, skirt)
    c.stroke(skirtPath, SvgPaint.hex("#D9604B"), stroke(1.4))

    // <g fill="none" stroke="#D9604B" stroke-width="1.4" stroke-opacity="0.4" stroke-linecap="round">
    val pleat = SvgPaint.hex("#D9604B", opacity = 0.4)
    val pleatStyle = stroke(1.4, cap = SvgLineCap.ROUND)
    c.stroke("M53 66 L44 88", pleat, pleatStyle)
    c.stroke("M57 66 L50 88", pleat, pleatStyle)
    c.stroke("M61 66 L58 88", pleat, pleatStyle)
    c.stroke("M65 66 L66 88", pleat, pleatStyle)
    c.stroke("M69 66 L74 88", pleat, pleatStyle)
    c.stroke("M73 66 L84 88", pleat, pleatStyle)

    val waist = "M52 60 Q52 58 55 58 L73 58 Q76 58 76 60 L76 64 L52 64 Z"
    c.fill(waist, band)
    c.stroke(waist, SvgPaint.hex("#D9604B"), stroke(1.4))

    rotatedEllipse(c, degrees = 6.0, cx = 56.0, cy = 74.0, rx = 7.0, ry = 9.0, paint = SvgPaint.hex("#FFFFFF", opacity = 0.22))

    // neck
    c.fill(SvgShapes.roundedRect(60.0, 35.0, 8.0, 8.0, cornerRadius = 2.0), limb)

    // hair back
    c.fill(
        "M64 13 C53 13 51 20 51 29 C51 34 52 37 54 39 L56 31 C56 23 59 19 64 19 C69 19 72 23 72 31 L74 39 C76 37 77 34 77 29 C77 20 75 13 64 13 Z",
        hair,
    )

    // head
    val head = circle(64.0, 27.0, 12.0)
    c.fill(head, limb)
    c.stroke(head, outline, stroke(1.0))

    // fringe
    c.fill("M53 24 Q56 15 64 15 Q72 15 75 24 Q64 19 53 24 Z", hair)

    // face
    c.fill(circle(59.5, 27.0, 1.4), SvgPaint.hex("#6B7178"))
    c.fill(circle(68.5, 27.0, 1.4), SvgPaint.hex("#6B7178"))
    c.stroke("M60 32 Q64 35 68 32", SvgPaint.hex("#6B7178"), stroke(1.3, cap = SvgLineCap.ROUND))
}

// --- Pyjama ------------------------------------------------------------------

private fun drawPyjama(c: SvgCanvas) {
    val fab = diagonal("#B7E8DD", "#83CCBD") // py-fab
    val cuf = diagonal("#6FC2B3", "#4EA494") // py-cuf
    val edge = SvgPaint.hex("#4EA494")
    val dot = SvgPaint.hex("#FFFFFF", opacity = 0.8)

    c.fill(ellipse(64.0, 107.0, 40.0, 6.0), dropShadow)

    val sleeveL = "M50 33 L28 43 Q25 45 25 49 L26 55 Q26 58 30 57 L40 53 L50 47 Z"
    c.fill(sleeveL, fab)
    c.stroke(sleeveL, edge, stroke(1.3))
    val sleeveR = "M78 33 L100 43 Q103 45 103 49 L102 55 Q102 58 98 57 L88 53 L78 47 Z"
    c.fill(sleeveR, fab)
    c.stroke(sleeveR, edge, stroke(1.3))
    val top = "M47 35 Q47 32 50 32 L78 32 Q81 32 81 35 L82 60 Q82 63 79 63 L49 63 Q46 63 46 60 Z"
    c.fill(top, fab)
    c.stroke(top, edge, stroke(1.3))

    val collar = "M58 31 L70 31 L68 38 L60 38 Z"
    c.fill(collar, cuf)
    c.stroke(collar, edge, stroke(1.0))

    c.fill("M24 50 L32 47 L35 55 L27 58 Q24 58 24 55 Z", cuf)
    c.fill("M104 50 L96 47 L93 55 L101 58 Q104 58 104 55 Z", cuf)
    c.fill("M46 58 L82 58 L82 60 Q82 63 79 63 L49 63 Q46 63 46 60 Z", cuf)

    // <line x1="64" y1="39" x2="64" y2="57" stroke="#4EA494" stroke-width="1.2"/>
    c.stroke(SvgShapes.line(64.0, 39.0, 64.0, 57.0), edge, stroke(1.2))

    for (cy in listOf(42.0, 49.0, 56.0)) {
        val button = circle(64.0, cy, 1.8)
        c.fill(button, SvgPaint.hex("#FDF6E7"))
        c.stroke(button, edge, stroke(0.7))
    }

    // <g fill="#FFFFFF" fill-opacity="0.8">
    c.fill(circle(55.0, 45.0, 1.6), dot)
    c.fill(circle(73.0, 47.0, 1.6), dot)
    c.fill(circle(58.0, 54.0, 1.6), dot)
    c.fill(circle(74.0, 55.0, 1.6), dot)

    val waistband = "M48 68 Q48 66 51 66 L77 66 Q80 66 80 68 L80 73 L48 73 Z"
    c.fill(waistband, cuf)
    c.stroke(waistband, edge, stroke(1.2))

    val trousers =
        "M48 73 L45 99 Q45 102 49 102 L59 102 Q62 102 62 99 L64 84 L66 99 Q66 102 69 102 L79 102 Q83 102 82 99 L80 73 Z"
    c.fill(trousers, fab)
    c.stroke(trousers, edge, stroke(1.3))

    c.fill("M45 97 L59 97 L59 99 Q59 102 56 102 L49 102 Q45 102 45 99 Z", cuf)
    c.fill("M66 97 L80 97 L80 99 Q80 102 77 102 L69 102 Q66 102 66 99 Z", cuf)

    // <g fill="#FFFFFF" fill-opacity="0.8">
    c.fill(circle(53.0, 82.0, 1.6), dot)
    c.fill(circle(74.0, 84.0, 1.6), dot)
    c.fill(circle(51.0, 92.0, 1.6), dot)
    c.fill(circle(75.0, 93.0, 1.6), dot)
    c.fill(circle(58.0, 90.0, 1.6), dot)
    c.fill(circle(71.0, 92.0, 1.6), dot)

    rotatedEllipse(c, degrees = -18.0, cx = 56.0, cy = 42.0, rx = 13.0, ry = 7.0, paint = SvgPaint.hex("#FFFFFF", opacity = 0.28))
}

// --- Macaron -----------------------------------------------------------------

private fun drawMacaron(c: SvgCanvas) {
    val side = diagonal("#F2A7C1", "#DE7CA1") // mac-side
    val top = diagonal("#FBD1DF", "#F1A7C2") // mac-top
    val fill = diagonal("#FDEDCE", "#EFCC8E") // mac-fill
    val shellEdge = SvgPaint.hex("#DA82A5")

    c.fill(ellipse(64.0, 93.0, 38.0, 6.0), dropShadow)

    val ganache = "M28 55 A36 7 0 0 0 100 55 L98 70 L30 70 Z"
    c.fill(ganache, fill)
    c.stroke(ganache, SvgPaint.hex("#E3BC7C"), stroke(0.8))

    val bottom =
        "M30 70 Q34.25 74.8 38.5 74.6 Q42.75 77.85 47 76.1 Q51.25 78.95 55.5 76.8 Q59.75 79.4 64 77 Q68.25 79.4 72.5 76.8 Q76.75 78.95 81 76.1 Q85.25 77.85 89.5 74.6 Q93.75 74.8 98 70 C101 82 86 89 64 89 C42 89 28 82 30 70 Z"
    c.fill(bottom, side)
    c.stroke(bottom, shellEdge, stroke(1.3))

    val upper =
        "M29 42 A35 10 0 0 0 99 42 L100 55 Q95.5 59.8 91 59.6 Q86.5 62.85 82 61.1 Q77.5 63.95 73 61.8 Q68.5 64.4 64 62 Q59.5 64.4 55 61.8 Q50.5 63.95 46 61.1 Q41.5 62.85 37 59.6 Q32.5 59.8 28 55 Z"
    c.fill(upper, side)
    c.stroke(upper, shellEdge, stroke(1.3))

    val cap = ellipse(64.0, 42.0, 35.0, 10.0)
    c.fill(cap, top)
    c.stroke(cap, shellEdge, stroke(1.2))

    rotatedEllipse(c, degrees = -16.0, cx = 53.0, cy = 38.0, rx = 15.0, ry = 5.0, paint = SvgPaint.hex("#FFFFFF", opacity = 0.45))
}

// --- Igloo -------------------------------------------------------------------

private fun drawIgloo(c: SvgCanvas) {
    val dome = diagonal("#FFFFFF", "#D2E1EF") // ig-dome
    // ig-door is the one VERTICAL gradient: x1="0" y1="0" x2="0" y2="1".
    val door = SvgPaint.linear(
        listOf(SvgStop.hex("#B4CADC", 0f), SvgStop.hex("#8DA8C0", 1f)),
        start = SvgPoint(0f, 0f),
        end = SvgPoint(0f, 1f),
    )
    val domeEdge = SvgPaint.hex("#A9C6DE")
    val brick = SvgPaint.hex("#B4CFE4")

    c.fill(ellipse(64.0, 98.0, 46.0, 7.0), dropShadow)

    val shell = "M20 92 A44 40 0 0 1 108 92 Z"
    c.fill(shell, dome)
    c.stroke(shell, domeEdge, stroke(2.0))

    rotatedEllipse(c, degrees = -20.0, cx = 46.0, cy = 62.0, rx = 20.0, ry = 12.0, paint = SvgPaint.hex("#FFFFFF", opacity = 0.45))

    // <g fill="none" stroke="#B4CFE4" stroke-width="2" stroke-linecap="round">
    val brickStyle = stroke(2.0, cap = SvgLineCap.ROUND)
    for (d in listOf(
        "M24 80 Q64 72 104 80",
        "M28 66 Q64 59 100 66",
        "M40 56 Q64 51 88 56",
        "M44 90 L45 81",
        "M64 91 L64 80",
        "M84 90 L83 81",
        "M53 78 L54 67",
        "M75 78 L74 67",
        "M54 64 L55 57",
        "M74 64 L73 57",
    )) {
        c.stroke(d, brick, brickStyle)
    }

    val porch = "M47 92 L47 78 A17 17 0 0 1 81 78 L81 92 Z"
    c.fill(porch, dome)
    c.stroke(porch, domeEdge, stroke(2.0))

    c.fill("M53 92 L53 79 A11 11 0 0 1 75 79 L75 92 Z", door)

    c.stroke("M47 78 A17 17 0 0 1 81 78", brick, stroke(1.6))
}

// --- View --------------------------------------------------------------------

/**
 * A word illustration, scaled to fill whatever square it is given.
 *
 * The web renders these as `<img src={word.img}>` and lets the exercise decide
 * the box, so this view carries no intrinsic size either — put it in a frame.
 *
 * The web file is `role="img" aria-labelledby` its `<title>`; the Compose twin
 * is an image-role semantics node whose content description is [WordImages.label].
 */
@Composable
fun WordImage(key: ImageKey, modifier: Modifier = Modifier) {
    val label = WordImages.label(key)
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics {
                role = Role.Image
                contentDescription = label
            },
    ) {
        drawSvg(WordImages.viewBox) { canvas -> WordImages.draw(key, canvas) }
    }
}
