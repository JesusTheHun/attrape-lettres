package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgColor
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgLineCap
import fr.dappit.attrapelettres.art.svg.SvgLineJoin
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgPaintKind
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.art.svg.SvgUnits
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.mascot.LegSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The shared part library, asserted as geometry.
//
// Every expectation is computed here from `src/mascot/parts.tsx` — the numbers
// in the TSX, re-derived — and never read back out of the code under test. A
// test that asks the implementation what it drew and then agrees with it proves
// only that the implementation is consistent with itself.
//
// None of this needs an emulator. A part is a command list plus a paint, both
// plain data, so a laptop can check that the cheer eyes really are two
// four-pointed stars at 1.35 times the eye radius.

private const val TEST_DARK = "#4A3222"
private const val TEST_BLUSH = "#FF9AA2"

/** The four-point star, re-derived from the TSX rather than called. */
private fun expectedStarPoints(cx: Double, cy: Double, r: Double): List<Pair<Float, Float>> {
    val i = r * 0.36
    return listOf(
        cx to (cy - r),
        (cx + i) to (cy - i),
        (cx + r) to cy,
        (cx + i) to (cy + i),
        cx to (cy + r),
        (cx - i) to (cy + i),
        (cx - r) to cy,
        (cx - i) to (cy - i),
    ).map { (x, y) -> x.toFloat() to y.toFloat() }
}

private fun starPointsOf(commands: List<SvgPathCommand>): List<Pair<Float, Float>> =
    commands.mapNotNull { command ->
        when (command) {
            is SvgPathCommand.MoveTo -> command.x to command.y
            is SvgPathCommand.LineTo -> command.x to command.y
            else -> null
        }
    }

class FourStarTest {

    @Test
    fun `is eight points alternating outer radius and an inner 0-36 r`() {
        val points = starPointsOf(commands(fourStar(50.0, 40.0, 10.0)))
        assertEquals(expectedStarPoints(50.0, 40.0, 10.0), points)
    }

    @Test
    fun `closes, so it fills as a star and not as a polyline`() {
        assertEquals(SvgPathCommand.Close, commands(fourStar(0.0, 0.0, 3.0)).last())
    }
}

class EyesTest {

    private val cx = 50.0
    private val y = 40.0
    private val dx = 7.0
    private val r = 4.0

    @Test
    fun `the default look is two pupils and four highlights, big one up-left`() {
        val nodes = record { drawEyes(it, cx, y, dx, r, Mood.IDLE) }
        val fills = fillsOf(nodes)
        assertEquals(6, fills.size)
        assertEquals(SvgShapes.circle(cx - dx, y, r), fills[0].path)
        assertEquals(SvgShapes.circle(cx + dx, y, r), fills[1].path)
        // The big highlight sits up and to the LEFT on BOTH eyes — the pet looks
        // at one light source, it is not mirrored.
        assertEquals(SvgShapes.circle(cx - dx - r * 0.32, y - r * 0.36, r * 0.36), fills[2].path)
        assertEquals(SvgShapes.circle(cx + dx - r * 0.32, y - r * 0.36, r * 0.36), fills[3].path)
        // …and the small one down-right, at 80% opacity.
        assertEquals(0.8, fills[4].paint.opacity)
        assertEquals(0.8, fills[5].paint.opacity)
        assertEquals(SvgPaint.hex(TEST_DARK), fills[0].paint)
    }

    @Test
    fun `cheering turns the eyes into sparkle stars at 1-35 times the radius`() {
        val nodes = record { drawEyes(it, cx, y, dx, r, Mood.CHEER) }
        val fills = fillsOf(nodes)
        assertEquals(2, fills.size)
        assertEquals(expectedStarPoints(cx - dx, y, r * 1.35), starPointsOf(fills[0].path))
        assertEquals(expectedStarPoints(cx + dx, y, r * 1.35), starPointsOf(fills[1].path))
    }

    @Test
    fun `happy arcs the eyes upward with a thick round stroke`() {
        val nodes = record { drawEyes(it, cx, y, dx, r, Mood.HAPPY) }
        val strokes = strokesOf(nodes)
        assertEquals(2, strokes.size)
        assertEquals(r * 0.78, strokes[0].style.lineWidth)
        assertEquals(SvgLineCap.ROUND, strokes[0].style.cap)
        // The arc's control point is ABOVE the eye line, which is what makes it
        // a smile and not a frown: y - r against a baseline of y + r * 0.5.
        val quad = strokes[0].path.filterIsInstance<SvgPathCommand.QuadTo>().single()
        assertEquals((y - r).toFloat(), quad.y1)
        assertEquals((y + r * 0.5).toFloat(), quad.y)
        assertTrue(fillsOf(nodes).isEmpty())
    }

    @Test
    fun `sleepy lids belong to the idle newborn only`() {
        // `sleepy && mood === "idle"` — a stade-0 baby that is cheering still
        // opens its eyes. Both halves of that condition are load-bearing.
        val sleeping = record { drawEyes(it, cx, y, dx, r, Mood.IDLE, sleepy = true) }
        assertEquals(2, strokesOf(sleeping).size)
        assertEquals(r * 0.5, strokesOf(sleeping)[0].style.lineWidth)
        assertTrue(fillsOf(sleeping).isEmpty())

        val cheering = record { drawEyes(it, cx, y, dx, r, Mood.CHEER, sleepy = true) }
        assertEquals(2, fillsOf(cheering).size)
        assertTrue(strokesOf(cheering).isEmpty())

        val awake = record { drawEyes(it, cx, y, dx, r, Mood.IDLE, sleepy = false) }
        assertEquals(6, fillsOf(awake).size)
    }
}

class CheeksAndMouthTest {

    @Test
    fun `cheeks are ONE 60 percent group, not two 60 percent fills`() {
        val nodes = record { drawCheeks(it, 50.0, 44.0, 6.0, 2.0) }
        val group = assertIs<SvgDrawNode.Group>(nodes.single())
        assertEquals(0.6, group.opacity)
        assertEquals(2, group.children.size)
        assertEquals(SvgPaint.hex(TEST_BLUSH), fillsOf(group.children)[0].paint)
        // Flattened: rx = r, ry = r * 0.68.
        assertEquals(SvgShapes.ellipse(44.0, 44.0, 2.0, 2.0 * 0.68), fillsOf(group.children)[0].path)
    }

    @Test
    fun `the idle mouth is a stroked curve, every other mood is an open mouth with a tongue`() {
        val idle = record { drawMouth(it, 50.0, 50.0, 3.0, Mood.IDLE) }
        val stroke = strokesOf(idle).single()
        assertEquals(1.5, stroke.style.lineWidth)
        assertEquals(SvgLineCap.ROUND, stroke.style.cap)
        assertTrue(fillsOf(idle).isEmpty())

        for (mood in listOf(Mood.HAPPY, Mood.CHEER)) {
            val open = record { drawMouth(it, 50.0, 50.0, 3.0, mood) }
            val fills = fillsOf(open)
            assertEquals(2, fills.size)
            assertEquals(SvgPaint.hex(TEST_DARK), fills[0].paint)
            assertEquals(SvgPaint.hex("#FF7C93"), fills[1].paint, "the tongue is drawn over the mouth")
            assertTrue(strokesOf(open).isEmpty())
        }
    }
}

class PlumeTest {

    @Test
    fun `each lock is rotated ABOUT ITS OWN CENTRE, which is the anchored rotate form`() {
        val nodes = record { drawPlume(it, x = 30.0, y = 20.0, color = "#fff", len = 10.0, wide = 6.0, rot = 12.0, n = 4) }
        val fills = fillsOf(nodes)
        assertEquals(4, fills.size)
        for (i in 0 until 4) {
            val t = i.toDouble() / 3
            val ox = 30.0 + (t - 0.5) * 6.0
            val oy = 20.0 + kotlin.math.abs(t - 0.5) * 10.0 * 0.18
            val r = 12.0 + (t - 0.5) * 26
            val rad = r * PI / 180
            val mx = (ox + (ox + sin(rad) * 10.0)) / 2
            val my = (oy + (oy + cos(rad) * 10.0)) / 2
            // The centre is a fixed point of an anchored rotation. An unanchored
            // `rotate(r)` would fling the lock across the head, which is the bug
            // the canvas' anchored form exists to prevent.
            assertEquals(mx, fills[i].transform.mapX(mx, my), 1e-9)
            assertEquals(my, fills[i].transform.mapY(mx, my), 1e-9)
            assertEquals(SvgShapes.ellipse(mx, my, 10.0 * 0.26, 10.0 * 0.52), fills[i].path)
        }
    }

    @Test
    fun `a single lock sits in the middle of the fan, not at its left edge`() {
        // `n === 1 ? 0.5 : i / (n - 1)` — with n = 1 the general formula divides
        // by zero, so the midpoint is written in by hand.
        val nodes = record { drawPlume(it, x = 30.0, y = 20.0, color = "#fff", len = 10.0, wide = 6.0, rot = 0.0, n = 1) }
        val fill = fillsOf(nodes).single()
        // t = 0.5 puts ox at x exactly and oy at y exactly.
        val my = (20.0 + (20.0 + cos(0.0) * 10.0)) / 2
        assertEquals(30.0, fill.transform.mapX(30.0, my), 1e-9)
    }

    @Test
    fun `wave swaps the linear fan for a zig-zag`() {
        val fan = fillsOf(record { drawPlume(it, 30.0, 20.0, "#fff", 10.0, 6.0, 0.0, 5, wave = false) })
        val wavy = fillsOf(record { drawPlume(it, 30.0, 20.0, "#fff", 10.0, 6.0, 0.0, 5, wave = true) })
        assertEquals(5, fan.size)
        assertEquals(5, wavy.size)
        // The fan's angles rise monotonically; the wave's `sin(i * 1.9) * 22`
        // does not — that is the whole difference between a mane and a curl.
        assertTrue(fan.map { it.transform.b } != wavy.map { it.transform.b })
    }
}

class LegsTest {

    private val spec = LegSpec(hipX = 44.0, hipY = 60.0, footX = 42.0, footY = 94.0, bend = 3.0, side = -1.0, back = false)

    @Test
    fun `the leg bows AWAY from the body by side times bend`() {
        val nodes = record { drawLeg(it, spec, w = 5.0, color = "#aaa", hoof = "#333") }
        val quad = strokesOf(nodes).single().path.filterIsInstance<SvgPathCommand.QuadTo>().single()
        assertEquals((((44.0 + 42.0) / 2) + (-1.0) * 3.0).toFloat(), quad.x1)
        assertEquals(((60.0 + 94.0) / 2).toFloat(), quad.y1)
        assertEquals(SvgLineCap.ROUND, strokesOf(nodes).single().style.cap)
        // …and the hoof is a squat ellipse at the foot, drawn over the leg.
        assertEquals(SvgShapes.ellipse(42.0, 94.0, 5.0 * 0.62, 5.0 * 0.4), fillsOf(nodes).single().path)
    }

    @Test
    fun `folded legs are two tucked strokes and two hooves`() {
        val nodes = record { drawFoldedLegs(it, bodyCX = 50.0, bodyCY = 80.0, bodyRX = 27.0, color = "#aaa", hoof = "#333") }
        assertEquals(2, strokesOf(nodes).size)
        assertEquals(2, fillsOf(nodes).size)
        assertEquals(7.0, strokesOf(nodes)[0].style.lineWidth)
        // y = bodyCY + 8, x = bodyCX + bodyRX * 0.35 → the first hoof at x + 16.
        val hoof = fillsOf(nodes)[0].path.first()
        assertIs<SvgPathCommand.MoveTo>(hoof)
        assertEquals((50.0 + 27.0 * 0.35 + 16 + 4).toFloat(), hoof.x, 1e-4f)
    }
}

class GlowTest {

    @Test
    fun `the aura is a three-stop radial fading to nothing, inside its own opacity group`() {
        val nodes = record { drawAura(it, cx = 50.0, cy = 40.0, r = 20.0, color = "#FFE29A", opacity = 0.7) }
        val group = assertIs<SvgDrawNode.Group>(nodes.single())
        assertEquals(0.7, group.opacity)
        val fill = fillsOf(group.children).single()
        val radial = assertIs<SvgPaintKind.Radial>(fill.paint.kind)
        assertEquals(listOf(0f, 0.55f, 1f), radial.stops.map { it.offset })
        assertEquals(listOf(0.6f, 0.22f, 0f), radial.stops.map { it.color.alpha })
        assertEquals(SvgShapes.circle(50.0, 40.0, 20.0), fill.path)
    }

    @Test
    fun `the ground glow is a flat 10 to 3 ellipse, so the gradient must be bounding-box`() {
        val nodes = record { drawGroundGlow(it, cx = 50.0, y = 94.0, rx = 30.0, color = "#FFE29A", opacity = 0.85) }
        val group = assertIs<SvgDrawNode.Group>(nodes.single())
        assertEquals(0.85, group.opacity)
        val fill = fillsOf(group.children).single()
        assertEquals(SvgShapes.ellipse(50.0, 94.0, 30.0, 30.0 * 0.3), fill.path)
        // Object-bounding-box units are SVG's default and the reason the glow
        // comes out elliptical rather than as a circle sitting in an ellipse.
        assertEquals(SvgUnits.OBJECT_BOUNDING_BOX, fill.paint.units)
        assertEquals(listOf(0.8f, 0.2f, 0f), assertIs<SvgPaintKind.Radial>(fill.paint.kind).stops.map { it.color.alpha })
    }

    @Test
    fun `the halo is an aura plus a crisp ring at 0-62 r, dimmed to 80 percent`() {
        val nodes = record { drawHalo(it, cx = 50.0, cy = 30.0, r = 25.0, opacity = 0.5) }
        assertIs<SvgDrawNode.Group>(nodes[0])
        val ring = assertIs<SvgDrawNode.Stroke>(nodes[1])
        assertEquals(SvgShapes.circle(50.0, 30.0, 25.0 * 0.62), ring.path)
        assertEquals(1.4, ring.style.lineWidth)
        assertEquals(0.5 * 0.8, ring.paint.opacity)
        // Outlined on purpose: a halo the colour of the body vanishes into the
        // silhouette and the child never sees what the stage bought them.
        assertEquals(SvgColor.hex("#FFF3C4"), assertIs<SvgPaintKind.Solid>(ring.paint.kind).color)
    }

    @Test
    fun `an empty sparkle list draws nothing at all`() {
        assertTrue(record { drawSparkles(it, emptyList()) }.isEmpty())
    }

    @Test
    fun `a burst rings n stars with sizes cycling 1-6, 2-6, 3-6`() {
        val n = 7
        val nodes = record { drawBurst(it, cx = 50.0, cy = 50.0, rx = 20.0, ry = 10.0, n = n) }
        val fills = fillsOf(nodes)
        assertEquals(n, fills.size)
        for (i in 0 until n) {
            val a = i.toDouble() / n * PI * 2
            assertEquals(
                expectedStarPoints(50.0 + cos(a) * 20.0, 50.0 + sin(a) * 10.0, 1.6 + (i % 3)),
                starPointsOf(fills[i].path),
            )
        }
    }
}

class RainbowSheenTest {

    @Test
    fun `the band is one rotated rect filled with the nine-stop spectrum`() {
        val nodes = record { drawRainbowSheen(it) }
        val fill = fillsOf(nodes).single()
        val linear = assertIs<SvgPaintKind.Linear>(fill.paint.kind)
        assertEquals(
            listOf(0f, 0.26f, 0.38f, 0.47f, 0.53f, 0.60f, 0.68f, 0.80f, 1f),
            linear.stops.map { it.offset },
        )
        // Soft at both ends: the first two and last two stops are invisible, so
        // the prism fades in and out instead of arriving as a hard bar.
        assertEquals(0f, linear.stops.first().color.alpha)
        assertEquals(0f, linear.stops.last().color.alpha)
        assertEquals(0.8f, linear.stops[3].color.alpha)
        // A left-to-right gradient across the band, SVG's own default.
        assertEquals(0f, linear.start.x)
        assertEquals(1f, linear.end.x)
    }

    @Test
    fun `the sweep is anchored at the middle of the viewBox, tilted 20 degrees`() {
        val fill = fillsOf(record { drawRainbowSheen(it) }).single()
        assertEquals(50.0, fill.transform.mapX(50.0, 50.0), 1e-9)
        assertEquals(50.0, fill.transform.mapY(50.0, 50.0), 1e-9)
        // rotate(-20): in y-down space a negative angle turns anticlockwise, so
        // a point straight above the anchor drifts LEFT.
        assertTrue(fill.transform.mapX(50.0, 0.0) < 50.0)
    }

    @Test
    fun `the band runs far past the pet at both ends so the linear wrap is never seen`() {
        val fill = fillsOf(record { drawRainbowSheen(it) }).single()
        val ys = coordinates(fill.path).filterIndexed { index, _ -> index % 2 == 1 }
        assertEquals(-120f, ys.min())
        assertEquals(220f, ys.max())
    }
}

class WardrobeTest {

    @Test
    fun `the bow is two loops, a knot and a highlight`() {
        val fills = fillsOf(record { drawBow(it, x = 50.0, y = 60.0, s = 1.0, color = "#FF7EA8") })
        assertEquals(4, fills.size)
        assertEquals(SvgShapes.circle(50.0, 60.0, 2.6), fills[2].path)
        assertEquals(0.4, fills[3].paint.opacity)
    }

    @Test
    fun `the standing swimsuit is CLIPPED to the torso, and its neckline is not`() {
        val nodes = record {
            drawSwimsuit(it, cx = 50.0, cy = 70.0, rx = 20.0, ry = 18.0, color = "#4FC3F7")
        }
        val fills = fillsOf(nodes)
        val strokes = strokesOf(nodes)
        // One colour band, clipped; two stripes, clipped; one neckline, free.
        assertEquals(1, fills.size)
        assertEquals(listOf(SvgShapes.ellipse(50.0, 70.0, 20.0, 18.0)), fills[0].clips)
        assertEquals(3, strokes.size)
        assertEquals(1, strokes[0].clips.size)
        assertEquals(1, strokes[1].clips.size)
        assertTrue(strokes[2].clips.isEmpty(), "the neckline must read on the fur, not be cut by the belly")
        assertEquals(listOf(2.2, 2.2), strokes.take(2).map { it.style.lineWidth })
    }

    @Test
    fun `the champion star only shows when asked for`() {
        val plain = record { drawSwimsuit(it, 50.0, 70.0, 20.0, 18.0, "#4FC3F7") }
        val starred = record { drawSwimsuit(it, 50.0, 70.0, 20.0, 18.0, "#4FC3F7", star = true) }
        assertEquals(fillsOf(plain).size + 1, fillsOf(starred).size)
        assertEquals(
            expectedStarPoints(50.0, 70.0 + 18.0 * 0.38, 3.1),
            starPointsOf(fillsOf(starred).last().path),
        )
    }

    @Test
    fun `the lying culotte covers the rump side and wears vertical stripes`() {
        val nodes = record {
            drawSwimsuit(it, cx = 50.0, cy = 80.0, rx = 27.0, ry = 10.0, color = "#4FC3F7", lying = true)
        }
        val fills = fillsOf(nodes)
        assertEquals(1, fills.size)
        // The rect stops at `edge = cx - rx * 0.16`, i.e. LEFT of centre — the
        // rump, away from the oversized resting head.
        val edge = 50.0 - 27.0 * 0.16
        val xs = coordinates(fills[0].path).filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(edge.toFloat(), xs.max(), 1e-4f)
        assertTrue(edge < 50.0)
        // Two stripes at k = 0.72 then 0.44, in that order.
        val strokes = strokesOf(nodes)
        assertEquals(3, strokes.size)
        assertEquals(2.0, strokes[0].style.lineWidth)
        val firstStripeX = (50.0 - 27.0 * 0.72).toFloat()
        assertEquals(firstStripeX, (strokes[0].path.first() as SvgPathCommand.MoveTo).x, 1e-4f)
    }

    @Test
    fun `the swim ring is eight segments of a Ramanujan perimeter`() {
        val rx = 24.0
        val ry = rx * 0.38
        val w = rx * 0.32
        val perimeter = PI * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))
        val nodes = record { drawSwimRing(it, cx = 50.0, cy = 70.0, rx = rx, color = "#FF6B6B") }
        val strokes = strokesOf(nodes)
        assertEquals(4, strokes.size)
        // Cream underneath, dashed colour on top: that is what makes it read as
        // alternating segments rather than as a two-tone ring.
        assertEquals(SvgPaint.hex("#FFF6EE"), strokes[0].paint)
        assertEquals(w, strokes[0].style.lineWidth)
        assertEquals(listOf(perimeter / 8, perimeter / 8), strokes[1].style.dash)
        assertTrue(strokes.all { it.clips.isEmpty() }, "a full ring is not clipped")
    }

    @Test
    fun `the two halves clip at the tube's midline, and only the front one carries the duck`() {
        val back = record { drawSwimRing(it, 50.0, 70.0, 24.0, "#FF6B6B", duck = true, part = SwimRingPart.BACK) }
        val front = record { drawSwimRing(it, 50.0, 70.0, 24.0, "#FF6B6B", duck = true, part = SwimRingPart.FRONT) }
        // The back half is the four tube strokes and nothing else: the duck is
        // skipped there, because the body would hide it.
        assertEquals(4, strokesOf(back).size)
        assertTrue(strokesOf(back).all { it.clips.size == 1 })
        assertEquals(0, fillsOf(back).size)
        // The front half carries the duck — head, beak and eye, plus its
        // outline — and the duck is drawn BEFORE the clip is pushed, so the
        // midline never cuts it in half.
        assertEquals(3, fillsOf(front).size)
        assertTrue(fillsOf(front).all { it.clips.isEmpty() })
        val frontStrokes = strokesOf(front)
        assertEquals(5, frontStrokes.size)
        assertTrue(frontStrokes.first().clips.isEmpty())
        assertTrue(frontStrokes.drop(1).all { it.clips.size == 1 })
    }

    @Test
    fun `the flower is five petals around a centre, first one straight up`() {
        val fills = fillsOf(record { drawFlower(it, x = 40.0, y = 30.0, r = 4.0, petal = "#FFB", center = "#FFF3C4") })
        assertEquals(6, fills.size)
        assertEquals(SvgShapes.circle(40.0 + cos(-PI / 2) * 4.0, 30.0 + sin(-PI / 2) * 4.0, 4.0 * 0.72), fills[0].path)
        assertEquals(SvgShapes.circle(40.0, 30.0, 4.0 * 0.6), fills[5].path)
    }
}

class CrownTest {

    @Test
    fun `the band is stroked twice, edge under band, and every spike is gem-tipped`() {
        val nodes = record { drawCrown(it, cx = 50.0, cy = 35.0, r = 16.0) }
        val strokes = strokesOf(nodes)
        val fills = fillsOf(nodes)
        // 2 band strokes + 5 spike outlines + 5 gem outlines.
        assertEquals(2 + 5 + 5, strokes.size)
        assertEquals(4.6, strokes[0].style.lineWidth)
        assertEquals(3.2, strokes[1].style.lineWidth)
        assertEquals(SvgPaint.hex("#B07E1E"), strokes[0].paint)
        assertEquals(SvgPaint.hex("#FFD54F"), strokes[1].paint)
        // 5 spike bodies + 5 gems.
        assertEquals(10, fills.size)
        assertEquals(SvgLineJoin.ROUND, strokes[2].style.join)
    }

    @Test
    fun `open drops the CENTRE spike, for wearers whose horn rises exactly there`() {
        val closed = record { drawCrown(it, 50.0, 35.0, 16.0) }
        val open = record { drawCrown(it, 50.0, 35.0, 16.0, open = true) }
        assertEquals(10, fillsOf(closed).size)
        assertEquals(8, fillsOf(open).size)
        // The tall one is the centre, at 8.5 above the ring against 5 for the
        // rest, and it wears the bigger gem.
        val topOfCentre = 35.0 + sin(-PI / 2) * (16.0 + 8.5)
        val gemYs = fillsOf(closed).map { fill -> coordinates(fill.path).filterIndexed { i, _ -> i % 2 == 1 }.min() }
        assertTrue(gemYs.any { kotlin.math.abs(it - (topOfCentre - 2.4)) < 1e-3 })
    }
}
