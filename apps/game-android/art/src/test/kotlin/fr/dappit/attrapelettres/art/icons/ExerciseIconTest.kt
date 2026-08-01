package fr.dappit.attrapelettres.art.icons

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

import fr.dappit.attrapelettres.art.svg.IconText
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPath
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.SvgStrokeStyle
import fr.dappit.attrapelettres.core.domain.ExerciseId

// Invariant 7's runtime half, ported from the iOS `ExerciseIconTests.swift`.
// The compile-time half is the `when` expression with no `else` in
// `ExerciseIconCatalog.kt`, which catches a MISSING icon; only a test catches a
// branch that compiles because it was copy-pasted from its neighbour.
//
// Every number here is read off `src/components/ExerciseIcon.tsx`.
//
// One deliberate difference from the Swift file: bounding boxes here are
// CONTROL-POINT hulls, because that is what `SvgPath.bounds` computes (it
// matches `android.graphics.Path.computeBounds`, and it never underestimates).
// CoreGraphics reports the tight curve bound, so the two arc expectations were
// recomputed for the hull; everything made of straight lines and full circles
// is identical in both conventions.
//
// The Swift file's rasterisation suite is NOT ported: reading pixels back needs
// Robolectric or a device, and no new dependency is allowed. What it protected
// (the badge fills in its tint, the text glyphs actually render) waits for the
// on-device diff.

// --- Draw-list helpers -------------------------------------------------------

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

private fun shapes(spec: ExerciseIconSpec): List<SvgDrawNode> =
    spec.nodes.flatMap { node -> if (node is IconNode.Shapes) node.nodes else emptyList() }

private fun texts(spec: ExerciseIconSpec): List<IconText> =
    spec.nodes.mapNotNull { node -> (node as? IconNode.Text)?.text }

private fun strokeStyles(nodes: List<SvgDrawNode>): List<SvgStrokeStyle> =
    nodes.mapNotNull { node -> (node as? SvgDrawNode.Stroke)?.style }

// --- Totality ----------------------------------------------------------------

class ExerciseIconTotalityTest {

    @Test
    fun `every exercise has an icon with real geometry in it`() {
        for (id in ExerciseId.entries) {
            val spec = exerciseIconSpec(id)
            assertTrue(spec.nodes.isNotEmpty(), "${id.wire} has no glyph at all")

            val drawn = leafCount(shapes(spec)) + texts(spec).size
            assertTrue(drawn > 0, "${id.wire} draws nothing")

            // A Shapes(emptyList()) node would satisfy "non-empty nodes" and
            // render nothing; forbid it outright.
            for (node in spec.nodes) {
                when (node) {
                    is IconNode.Shapes ->
                        assertTrue(node.nodes.isNotEmpty(), "${id.wire} has an empty shape run")

                    is IconNode.Text -> {
                        assertTrue(node.text.string.isNotEmpty(), "${id.wire} has an empty text node")
                        assertTrue(node.text.size > 0, "${id.wire} has a zero-size glyph")
                    }
                }
            }
        }
    }

    @Test
    fun `all 17 tints are distinct - a copy-pasted branch compiles fine`() {
        val tints = ExerciseId.entries.map { exerciseIconSpec(it).tint }
        assertEquals(17, tints.size)
        assertEquals(tints.size, tints.toSet().size, "two exercises share a badge colour: $tints")
        // The TSX writes every tint as a 6-digit hex.
        assertTrue(tints.all { it.length == 7 && it.startsWith("#") })
    }

    @Test
    fun `no two exercises share the same drawing either`() {
        // Bounding box + leaf count + text content is a cheap fingerprint; two
        // identical branches collide on all three.
        val seen = HashMap<String, ExerciseId>()
        for (id in ExerciseId.entries) {
            val spec = exerciseIconSpec(id)
            val box = bounds(shapes(spec)) ?: SvgRect.Zero
            val key = "${leafCount(shapes(spec))}|${texts(spec).joinToString("") { it.string }}|" +
                "${box.minX},${box.minY},${box.width},${box.height}"
            val other = seen[key]
            if (other != null) {
                fail("${id.wire} draws the same thing as ${other.wire}")
            }
            seen[key] = id
        }
    }

    @Test
    fun `every icon stays inside the 32-unit badge`() {
        // Nothing overflows in the TSX; the widest reach is the magnifier
        // handle at (23.5, 24) and the pleat/pencil marks, all under 28. The
        // hull convention only widens boxes, and even the widened ones fit.
        for (id in ExerciseId.entries) {
            val box = bounds(shapes(exerciseIconSpec(id))) ?: continue
            assertTrue(box.minX >= 0, "${id.wire} draws left of the box: $box")
            assertTrue(box.minY >= 0, "${id.wire} draws above the box: $box")
            assertTrue(box.maxX <= 32, "${id.wire} draws right of the box: $box")
            assertTrue(box.maxY <= 32, "${id.wire} draws below the box: $box")
        }
    }
}

// --- Individual glyphs -------------------------------------------------------

class ExerciseIconGlyphTest {

    @Test
    fun `the shared line style is the TSX's - white, 2 point 4, round, round`() {
        val line = IconStroke.LINE
        assertEquals("#fff", line.color)
        assertEquals(2.4, line.width)
        assertEquals(1.0, line.opacity)
        assertTrue(line.dash.isEmpty())
        assertEquals(SvgLineCap.ROUND, line.cap)
        assertEquals(SvgLineJoin.ROUND, line.join)
    }

    @Test
    fun `there are exactly four text glyphs in the whole file - A, V, A, a`() {
        val all = ExerciseId.entries.flatMap { texts(exerciseIconSpec(it)) }
        assertEquals(4, all.size)
        assertEquals(listOf("A", "V", "A", "a"), all.map { it.string })
        assertTrue(all.all { it.weight == 900 })
        assertTrue(all.all { it.fill == "#fff" })

        // <Glyph x={15} y={17.5} size={17}>A</Glyph>
        assertEquals(IconText("A", x = 15.0, y = 17.5, size = 17.0), all[0])
        // <Glyph x={11} y={16} size={17}>V</Glyph>
        assertEquals(IconText("V", x = 11.0, y = 16.0, size = 17.0), all[1])
        // <Glyph x={12} y={18} size={16}>A</Glyph> / <Glyph x={22} y={19} size={11}>a</Glyph>
        assertEquals(IconText("A", x = 12.0, y = 18.0, size = 16.0), all[2])
        assertEquals(IconText("a", x = 22.0, y = 19.0, size = 11.0), all[3])
    }

    @Test
    fun `first-letter - the sparkle draws AFTER the A, and sits top-right of it`() {
        val spec = exerciseIconSpec(ExerciseId.FIRST_LETTER)
        assertEquals("#FF8A5B", spec.tint)
        // Painter's order: <Glyph> then <path>. Reordering would put the
        // sparkle under the letterform.
        assertEquals(2, spec.nodes.size)
        assertIs<IconNode.Text>(spec.nodes[0], "the A must be drawn first")
        assertIs<IconNode.Shapes>(spec.nodes[1], "the sparkle must be drawn second")
        // d="M24 5.5 ... L20.5 9 ..." - x in 20.5...27.5, y in 5.5...12.5.
        val box = assertNotNull(bounds(shapes(spec)))
        assertTrue(abs(box.minX - 20.5) < 1e-6)
        assertTrue(abs(box.maxX - 27.5) < 1e-6)
        assertTrue(abs(box.minY - 5.5) < 1e-6)
        assertTrue(abs(box.maxY - 12.5) < 1e-6)
    }

    @Test
    fun `pick-vowel - the V is drawn before its dashed box, dash 2,5 2,3, square caps`() {
        val spec = exerciseIconSpec(ExerciseId.PICK_VOWEL)
        assertIs<IconNode.Text>(spec.nodes[0], "the V must be drawn first")
        val styles = strokeStyles(shapes(spec))
        assertEquals(1, styles.size)
        assertEquals(listOf(2.5, 2.3), styles[0].dash)
        assertEquals(1.9, styles[0].lineWidth)
        // This node does NOT spread `line`, so SVG's defaults apply.
        assertEquals(SvgLineCap.BUTT, styles[0].cap)
        assertEquals(SvgLineJoin.MITER, styles[0].join)
    }

    @Test
    fun `fill-blank - only the MIDDLE slot is dashed`() {
        val nodes = shapes(exerciseIconSpec(ExerciseId.FILL_BLANK))
        assertEquals(3, leafCount(nodes))
        val styles = strokeStyles(nodes)
        assertEquals(1, styles.size, "exactly one of the three slots is an outline")
        assertEquals(listOf(2.4, 2.2), styles[0].dash)
    }

    @Test
    fun `the two melees twins wear the shuffle chip - their un-mixed siblings do not`() {
        // The chip is a white circle at (23.5, 23.5) r 6.6 - it pushes the
        // drawing out to x = 30.1. The plain bullseye stops at 24. (A circle's
        // control-point hull equals its tight bounds, so these numbers are the
        // same in both conventions.)
        for (id in listOf(ExerciseId.SPELL_SYLLABLE_PLUS_MIXED, ExerciseId.SPELL_TWO_SYLLABLES_MIXED)) {
            val box = assertNotNull(bounds(shapes(exerciseIconSpec(id))), "${id.wire} draws nothing")
            assertTrue(abs(box.maxX - 30.1) < 1e-5, "${id.wire} is missing the chip: $box")
            assertTrue(abs(box.maxY - 30.1) < 1e-5)
        }
        val plain = assertNotNull(bounds(shapes(exerciseIconSpec(ExerciseId.SPELL_SYLLABLE_PLUS))))
        assertTrue(abs(plain.maxX - 24) < 1e-5, "the un-mixed bullseye must not wear a chip")
    }

    @Test
    fun `find-intruder's little x keeps a round cap but SVG's default miter join`() {
        val styles = strokeStyles(shapes(exerciseIconSpec(ExerciseId.FIND_INTRUDER)))
        assertEquals(3, styles.size)
        // circle + handle use `line`; the x names its own attributes.
        val cross = styles[2]
        assertEquals(1.8, cross.lineWidth)
        assertEquals(SvgLineCap.ROUND, cross.cap)
        assertEquals(SvgLineJoin.MITER, cross.join)
    }

    @Test
    fun `element counts match the TSX, hand-counted per icon`() {
        // Counted off `GLYPHS` element by element (a `<circle {...line}>` is
        // one stroke; the ShuffleChip is 1 fill + 4 strokes). Catches a dropped
        // wave, tile or arrow that every other assertion here would tolerate.
        val expected = mapOf(
            ExerciseId.FIRST_LETTER to 1, // sparkle (+1 text)
            ExerciseId.FIND_SOUND to 5,
            ExerciseId.HEAR_SYLLABLE to 5,
            ExerciseId.PICK_VOWEL to 1, // dashed box (+1 text)
            ExerciseId.FILL_BLANK to 3,
            ExerciseId.ORDER_SYLLABLES to 5,
            ExerciseId.FIND_INTRUDER to 3,
            ExerciseId.SPELL_SOUND to 3,
            ExerciseId.SPELL_SYLLABLE to 2,
            ExerciseId.SPELL_SYLLABLE_PLUS to 3,
            ExerciseId.SPELL_TWO_SYLLABLES to 3,
            ExerciseId.READ_IMAGE to 3,
            ExerciseId.MATCH_CASE to 0, // two texts, no shapes
            ExerciseId.MATCH_SCRIPT to 1,
            ExerciseId.SOUND_TWINS to 3,
            ExerciseId.SPELL_SYLLABLE_PLUS_MIXED to 3 + 5,
            ExerciseId.SPELL_TWO_SYLLABLES_MIXED to 3 + 5,
        )
        for (id in ExerciseId.entries) {
            assertEquals(expected[id], leafCount(shapes(exerciseIconSpec(id))), id.wire)
        }
    }

    @Test
    fun `the arc commands actually produce arcs - an unparsed a would draw nothing`() {
        // find-sound's outer wave is `M15.6 10.4 a5.2 5.2 0 0 1 0 7.2`: a chord
        // of 7.2 on a circle of radius 5.2. A parser that dropped the arc would
        // leave a degenerate zero-width point run.
        //
        // The expected width is the CONTROL-POINT hull, not iOS's sagitta of
        // 1.4477: the arc flattens to one cubic whose control points overshoot
        // the curve, and the hull reaches 15.6 + 1.9302 (computed with the same
        // F.6.5 arithmetic as the implementation, mirrored independently).
        val nodes = shapes(exerciseIconSpec(ExerciseId.FIND_SOUND))
        val wave = nodes[4]
        assertIs<SvgDrawNode.Stroke>(wave, "expected the outer wave last")
        val box = assertNotNull(SvgPath.bounds(SvgPath.transform(wave.path, wave.transform)))
        assertTrue(abs(box.height - 7.2) < 1e-3, "$box")
        assertTrue(abs(box.width - 1.9302) < 5e-3, "$box")
    }

    @Test
    fun `match-case is two letterforms and nothing else`() {
        val spec = exerciseIconSpec(ExerciseId.MATCH_CASE)
        assertEquals(2, spec.nodes.size)
        assertEquals(2, texts(spec).size)
        assertTrue(shapes(spec).isEmpty())
    }
}
