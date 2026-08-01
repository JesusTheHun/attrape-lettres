package fr.dappit.attrapelettres.art.images

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPaintKind
import fr.dappit.attrapelettres.art.svg.SvgPath
import fr.dappit.attrapelettres.art.svg.SvgPoint
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.SvgUnits
import fr.dappit.attrapelettres.core.domain.ImageKey

// The four `src/img/{igloo,jupe,macaron,pyjama}.svg` word illustrations,
// ported from the iOS `WordImageTests.swift`.
//
// The element counts below were counted BY HAND off the SVG files, element by
// element, with a fill and a stroke on the same element counting as two draws
// (that is what `SvgCanvas` records). They are the check that nothing was
// silently dropped in transcription — the most likely port bug in a 63-line
// file of near-identical `<path>` elements.
//
// The Swift file's rasterisation suite is NOT ported — pixels need Robolectric
// or a device, and no new dependency is allowed.

private fun leafCount(nodes: List<SvgDrawNode>): Int = nodes.sumOf { node ->
    when (node) {
        is SvgDrawNode.Fill, is SvgDrawNode.Stroke -> 1
        is SvgDrawNode.Group -> leafCount(node.children)
        is SvgDrawNode.Mask -> leafCount(node.content)
    }
}

private fun SvgRect.union(other: SvgRect): SvgRect {
    val x0 = min(minX, other.minX)
    val y0 = min(minY, other.minY)
    val x1 = max(maxX, other.maxX)
    val y1 = max(maxY, other.maxY)
    return SvgRect(x0, y0, x1 - x0, y1 - y0)
}

private fun bounds(nodes: List<SvgDrawNode>): SvgRect? {
    var box: SvgRect? = null
    fun add(r: SvgRect?) {
        if (r == null) return
        box = box?.union(r) ?: r
    }
    for (node in nodes) {
        when (node) {
            is SvgDrawNode.Fill -> add(SvgPath.bounds(SvgPath.transform(node.path, node.transform)))
            is SvgDrawNode.Stroke -> add(SvgPath.bounds(SvgPath.transform(node.path, node.transform)))
            is SvgDrawNode.Group -> add(bounds(node.children))
            is SvgDrawNode.Mask -> add(bounds(node.content))
        }
    }
    return box
}

private fun draw(key: ImageKey): List<SvgDrawNode> {
    val canvas = SvgCanvas()
    WordImages.draw(key, canvas)
    return canvas.nodes
}

private fun paints(nodes: List<SvgDrawNode>): List<SvgPaint> = nodes.mapNotNull { node ->
    when (node) {
        is SvgDrawNode.Fill -> node.paint
        is SvgDrawNode.Stroke -> node.paint
        else -> null
    }
}

class WordImagesTest {

    @Test
    fun `the title of each file is its accessibility label`() {
        assertEquals("Igloo", WordImages.label(ImageKey.IGLOO))
        assertEquals("Jupe", WordImages.label(ImageKey.JUPE))
        assertEquals("Macaron", WordImages.label(ImageKey.MACARON))
        assertEquals("Pyjama", WordImages.label(ImageKey.PYJAMA))
        // Exhaustive by construction; this catches a label copied twice.
        val all = ImageKey.entries.map(WordImages::label)
        assertEquals(ImageKey.entries.size, all.toSet().size)
    }

    @Test
    fun `every key draws something`() {
        for (key in ImageKey.entries) {
            assertTrue(leafCount(draw(key)) > 0, "${key.name} renders nothing")
        }
    }

    @Test
    fun `element counts match the SVG files, hand-counted`() {
        // jupe.svg: shadow 1 - 2 legs (fill+stroke) 4 - 2 shoes 2 - torso 2 -
        //           2 arms 4 - 2 hands 4 - skirt 2 - 6 pleats 6 - band 2 -
        //           highlight 1 - neck 1 - hair-back 1 - head 2 - fringe 1 -
        //           2 eyes 2 - mouth 1  = 36
        assertEquals(36, leafCount(draw(ImageKey.JUPE)))
        // pyjama.svg: shadow 1 - 2 sleeves 4 - top 2 - collar 2 - 2 cuffs 2 -
        //             hem 1 - placket 1 - 3 buttons 6 - 4 dots 4 - waistband 2 -
        //             trousers 2 - 2 leg cuffs 2 - 6 dots 6 - highlight 1 = 36
        assertEquals(36, leafCount(draw(ImageKey.PYJAMA)))
        // macaron.svg: shadow 1 - ganache 2 - bottom 2 - top 2 - cap 2 - highlight 1 = 10
        assertEquals(10, leafCount(draw(ImageKey.MACARON)))
        // igloo.svg: shadow 1 - dome 2 - highlight 1 - 10 bricks 10 - porch 2 -
        //            door 1 - lintel arc 1 = 18
        assertEquals(18, leafCount(draw(ImageKey.IGLOO)))
    }

    @Test
    fun `nothing escapes the 128-unit viewBox`() {
        // Control-point hulls, so this is a slightly STRICTER check than the
        // iOS tight-bound version — the hull never underestimates.
        for (key in ImageKey.entries) {
            val box = assertNotNull(bounds(draw(key)), "${key.name} has no geometry")
            assertTrue(box.minX >= 0, "${key.name}: $box")
            assertTrue(box.minY >= 0, "${key.name}: $box")
            assertTrue(box.maxX <= 128, "${key.name}: $box")
            assertTrue(box.maxY <= 128, "${key.name}: $box")
        }
    }

    @Test
    fun `gradients keep their axis - eleven diagonal, the igloo door vertical`() {
        // Every <linearGradient> in these four files is x1=0 y1=0 x2=1 y2=1
        // EXCEPT ig-door, which is x1=0 y1=0 x2=0 y2=1. Swapping the two is
        // invisible in review and obvious on screen.
        fun axes(key: ImageKey): List<Pair<SvgPoint, SvgPoint>> =
            paints(draw(key)).mapNotNull { paint ->
                (paint.kind as? SvgPaintKind.Linear)?.let { it.start to it.end }
            }

        for (key in listOf(ImageKey.JUPE, ImageKey.PYJAMA, ImageKey.MACARON)) {
            val all = axes(key)
            assertTrue(all.isNotEmpty(), "${key.name} lost its gradients")
            assertTrue(
                all.all { it.first == SvgPoint(0f, 0f) && it.second == SvgPoint(1f, 1f) },
                "${key.name} has a non-diagonal gradient",
            )
        }
        val igloo = axes(ImageKey.IGLOO)
        assertTrue(
            igloo.any { it.first == SvgPoint(0f, 0f) && it.second == SvgPoint(0f, 1f) },
            "the igloo door's vertical gradient is missing",
        )
        assertTrue(
            igloo.any { it.first == SvgPoint(0f, 0f) && it.second == SvgPoint(1f, 1f) },
            "the igloo dome's diagonal gradient is missing",
        )
    }

    @Test
    fun `every gradient resolves against the shape's bounding box, SVG's default`() {
        // No file declares gradientUnits, so all twelve are objectBoundingBox.
        // Passing USER_SPACE_ON_USE would smear each gradient across the whole
        // 128-unit canvas instead of the element it fills.
        for (key in ImageKey.entries) {
            for (paint in paints(draw(key))) {
                when (paint.kind) {
                    is SvgPaintKind.Linear, is SvgPaintKind.Radial ->
                        assertEquals(SvgUnits.OBJECT_BOUNDING_BOX, paint.units, key.name)

                    is SvgPaintKind.Solid, SvgPaintKind.None -> Unit
                }
            }
        }
    }

    @Test
    fun `each illustration opens with its 10 percent black ground shadow`() {
        for (key in ImageKey.entries) {
            val first = draw(key).firstOrNull()
            val fill = assertIs<SvgDrawNode.Fill>(first, "${key.name} does not start with a fill")
            assertEquals(0.10, fill.paint.opacity, "${key.name}'s shadow lost its fill-opacity")
        }
    }
}
