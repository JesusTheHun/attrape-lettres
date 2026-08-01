package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgPath
import fr.dappit.attrapelettres.art.svg.SvgPathCommand
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.art.svg.SvgTransform
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.layoutFor
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for the « Braise » dragon port (Dragon.kt + DragonParts.kt), ported
// from the iOS `DragonRigTests.swift`.
//
// Strategy: build the recorded draw list for every stage × wardrobe the dragon
// supports and assert (1) it draws something, (2) every leaf path parsed (an
// unparseable `d` yields an empty command list — SvgPath.parseOrEmpty), (3) the
// union bounding box is finite and plausible for the 0–100 viewBox (overflow
// allowed — wings at the top stages), and (4) a leaf-count snapshot per
// configuration, so a refactor that silently drops a part fails loudly.
//
// Not ported from the Swift file: `DragonRasterTests` — reading pixels back
// needs Robolectric or a device, and no new dependency is allowed. What it
// proved (the full stage-9 wardrobe rasterises to visible pixels) stays
// unproven until something draws on a device.

// -- Helpers ----------------------------------------------------------------

private fun dragonLeaves(nodes: List<SvgDrawNode>): List<Pair<List<SvgPathCommand>, SvgTransform>> =
    nodes.flatMap { node ->
        when (node) {
            is SvgDrawNode.Fill -> listOf(node.path to node.transform)
            is SvgDrawNode.Stroke -> listOf(node.path to node.transform)
            is SvgDrawNode.Group -> dragonLeaves(node.children)
            is SvgDrawNode.Mask -> dragonLeaves(node.matte) + dragonLeaves(node.content)
        }
    }

private fun dragonConfig(
    stage: Int,
    styles: Map<String, String> = emptyMap(),
    accessories: List<String> = emptyList(),
): MascotConfig = MascotConfig(
    species = Species.DRAGON,
    stage = stage,
    colors = emptyMap(),
    styles = styles,
    accessories = accessories,
)

private fun dragonList(
    stage: Int,
    styles: Map<String, String> = emptyMap(),
    accessories: List<String> = emptyList(),
    mood: Mood = Mood.IDLE,
    preview: Boolean = false,
): List<SvgDrawNode> {
    val c = SvgCanvas()
    drawDragonRig(
        c,
        dragonConfig(stage, styles, accessories),
        layoutFor(stage.toDouble()),
        stage,
        mood,
        preview,
    )
    return c.nodes
}

private fun leafCount(
    stage: Int,
    styles: Map<String, String> = emptyMap(),
    accessories: List<String> = emptyList(),
    mood: Mood = Mood.IDLE,
    preview: Boolean = false,
): Int = dragonLeaves(dragonList(stage, styles, accessories, mood, preview)).size

private fun unionWith(a: SvgRect?, b: SvgRect): SvgRect {
    if (a == null) return b
    val minX = min(a.minX, b.minX)
    val minY = min(a.minY, b.minY)
    val maxX = max(a.maxX, b.maxX)
    val maxY = max(a.maxY, b.maxY)
    return SvgRect(minX, minY, maxX - minX, maxY - minY)
}

private val ALL_ACCESSORIES = listOf(
    Accessory.Dragon.cape,
    Accessory.Dragon.goggles,
    Accessory.Dragon.fang,
    Accessory.Dragon.treasure,
    Accessory.Dragon.blueFlame,
)

private data class Wardrobe(
    val name: String,
    val styles: Map<String, String>,
    val accessories: List<String>,
)

/**
 * Every wardrobe axis the dragon supports (spec §8.3), exercised one at a time
 * plus the everything-on look. Order matters: the snapshot test reads entries
 * 0 (base), 8 (treasure) and 10 (all) by index, like the Swift original.
 */
private val WARDROBES = listOf(
    Wardrobe("base", emptyMap(), emptyList()),
    Wardrobe("horns-double", mapOf(StyleSlot.Dragon.horn to "double"), emptyList()),
    Wardrobe("crest-lava", mapOf(StyleSlot.Dragon.crest to "lava"), emptyList()),
    Wardrobe("tail-club", mapOf(StyleSlot.Dragon.tail to "club"), emptyList()),
    Wardrobe("tail-flame", mapOf(StyleSlot.Dragon.tail to "flame"), emptyList()),
    Wardrobe("cape", emptyMap(), listOf(Accessory.Dragon.cape)),
    Wardrobe("goggles", emptyMap(), listOf(Accessory.Dragon.goggles)),
    Wardrobe("fang", emptyMap(), listOf(Accessory.Dragon.fang)),
    Wardrobe("treasure", emptyMap(), listOf(Accessory.Dragon.treasure)),
    Wardrobe("blue-flame", emptyMap(), listOf(Accessory.Dragon.blueFlame)),
    Wardrobe(
        "all",
        mapOf(
            StyleSlot.Dragon.horn to "double",
            StyleSlot.Dragon.crest to "lava",
            StyleSlot.Dragon.tail to "flame",
        ),
        ALL_ACCESSORIES,
    ),
)

// -- Matrix smoke + geometry sanity -----------------------------------------

class DragonRigMatrixTests {

    @Test
    fun `every stage x wardrobe x preview draws, parses and stays in plausible bounds`() {
        for (stage in 0..9) {
            for (wardrobe in WARDROBES) {
                for (preview in listOf(false, true)) {
                    val list = dragonList(
                        stage = stage,
                        styles = wardrobe.styles,
                        accessories = wardrobe.accessories,
                        preview = preview,
                    )
                    val ls = dragonLeaves(list)
                    assertTrue(ls.isNotEmpty(), "empty draw list at stage $stage ${wardrobe.name} preview=$preview")

                    var union: SvgRect? = null
                    for ((path, transform) in ls) {
                        // An unparseable `d` string yields an empty command list
                        // (SvgPath.parseOrEmpty) — every leaf must carry real geometry.
                        assertTrue(path.isNotEmpty(), "empty path at stage $stage ${wardrobe.name}")
                        val bounds = SvgPath.bounds(SvgPath.transform(path, transform))
                        if (bounds != null) union = unionWith(union, bounds)
                    }
                    val u = assertNotNull(union, "no bounds at stage $stage ${wardrobe.name}")
                    assertTrue(
                        u.minX.isFinite() && u.minY.isFinite() && u.width.isFinite() && u.height.isFinite(),
                        "non-finite bounds at stage $stage ${wardrobe.name}: $u",
                    )
                    // Authored in the 0–100 viewBox; wings/aura overflow by design
                    // but nothing sane leaves this envelope.
                    assertTrue(u.minX > -40 && u.maxX < 140, "x out of range at stage $stage ${wardrobe.name}: $u")
                    assertTrue(u.minY > -40 && u.maxY < 140, "y out of range at stage $stage ${wardrobe.name}: $u")
                    assertTrue(u.width > 20 && u.height > 20, "implausibly small at stage $stage ${wardrobe.name}: $u")
                }
            }
        }
    }

    @Test
    fun `moods redraw the face without trapping, and change the list`() {
        for (stage in listOf(0, 1, 4, 9)) {
            val idle = leafCount(stage = stage, mood = Mood.IDLE)
            val happy = leafCount(stage = stage, mood = Mood.HAPPY)
            val cheer = leafCount(stage = stage, mood = Mood.CHEER)
            assertTrue(idle > 0 && happy > 0 && cheer > 0)
            // idle eyes are pupil stacks (6 fills), happy/cheer arcs/stars (2) —
            // the counts must differ somewhere across moods.
            assertFalse(idle == happy && happy == cheer, "moods drew identical lists at stage $stage")
        }
    }
}

// -- Behaviour pinned by the TSX --------------------------------------------

class DragonRigBehaviourTests {

    @Test
    fun `the blue-flame breath is gated at stage 4 (ramp clamps below its first stop)`() {
        // Below the gate the premium must change NOTHING (spec.flame is 0 and
        // the guard stops the ramp leak).
        assertEquals(
            leafCount(stage = 3),
            leafCount(stage = 3, accessories = listOf(Accessory.Dragon.blueFlame)),
        )
        // At stage 4 the blue breath appears even though spec.flame is 0.
        assertTrue(
            leafCount(stage = 4, accessories = listOf(Accessory.Dragon.blueFlame)) > leafCount(stage = 4),
        )
        // At stage 7+ the legendary double breath adds a second puff (2 fills)
        // over the single blue breath, plus the ember floor of 4.
        assertTrue(
            leafCount(stage = 7, accessories = listOf(Accessory.Dragon.blueFlame)) > leafCount(stage = 7) + 2,
        )
    }

    @Test
    fun `preview strips the per-stage magic (and the egg)`() {
        // Stage 0 preview: no egg → falls through to the lying branch.
        assertNotEquals(leafCount(stage = 0), leafCount(stage = 0, preview = true))
        // Stage 9 preview: no flame, aura, ember, cracks, smoke, ground, claws or fangs.
        assertTrue(leafCount(stage = 9, preview = true) < leafCount(stage = 9))
        // But sold styles/accessories stay visible in the ghost.
        assertTrue(
            leafCount(stage = 9, accessories = listOf(Accessory.Dragon.cape), preview = true) >
                leafCount(stage = 9, preview = true),
        )
    }

    @Test
    fun `goggles obey the rig's own stage gate at 3, independent of the catalog`() {
        assertEquals(
            leafCount(stage = 2),
            leafCount(stage = 2, accessories = listOf(Accessory.Dragon.goggles)),
        )
        assertTrue(
            leafCount(stage = 3, accessories = listOf(Accessory.Dragon.goggles)) > leafCount(stage = 3),
        )
    }

    @Test
    fun `a SpadeTail without an edge records no zero-width tip stroke`() {
        fun strokes(nodes: List<SvgDrawNode>): Int = nodes.sumOf { n ->
            when (n) {
                is SvgDrawNode.Stroke -> 1
                is SvgDrawNode.Fill -> 0
                is SvgDrawNode.Group -> strokes(n.children)
                is SvgDrawNode.Mask -> strokes(n.matte) + strokes(n.content)
            }
        }

        val edged = SvgCanvas()
        drawSpadeTail(edged, 10.0 to 90.0, 30.0 to 80.0, 30.0 to 60.0, 5.0, "#7DB874", edge = "#5A3A1E")
        // edge underlay + colour curve + tip outline
        assertEquals(3, strokes(edged.nodes))

        val bare = SvgCanvas()
        drawSpadeTail(bare, 10.0 to 90.0, 30.0 to 80.0, 30.0 to 60.0, 5.0, "#7DB874")
        // colour curve only — strokeWidth={edge ? 1 : 0} must record nothing.
        assertEquals(1, strokes(bare.nodes))
    }

    @Test
    fun `an unknown tailStyle draws the curve with no tip, like the TSX cast`() {
        // Same list as club/flame minus the tip: strictly fewer leaves than any
        // known tip, but still a full dragon.
        val unknown = leafCount(stage = 5, styles = mapOf(StyleSlot.Dragon.tail to "mystery"))
        val spade = leafCount(stage = 5)
        assertTrue(unknown in 1 until spade)
    }

    @Test
    fun `the flame tail stays a spade before stage 2, outside preview`() {
        val flame = mapOf(StyleSlot.Dragon.tail to "flame")
        // Stages 0–1: same list as the default spade tail.
        assertEquals(leafCount(stage = 0), leafCount(stage = 0, styles = flame))
        assertEquals(leafCount(stage = 1), leafCount(stage = 1, styles = flame))
        // From stage 2 the tip is a flame (circle + 2-fill puff, not a spade fill).
        assertNotEquals(leafCount(stage = 2), leafCount(stage = 2, styles = flame))
        // In preview the flame tail shows even at stage 1 (it is what the tile sells).
        assertNotEquals(
            leafCount(stage = 1, preview = true),
            leafCount(stage = 1, styles = flame, preview = true),
        )
    }

    @Test
    fun `the treasure hoard only ever grows with the stage`() {
        var previous = 0
        for (stage in 0..9) {
            val c = SvgCanvas()
            drawTreasure(c, stage, 0.0, 95.0)
            val n = dragonLeaves(c.nodes).size
            assertTrue(n >= previous, "treasure shrank at stage $stage: $n < $previous")
            previous = n
        }
    }

    @Test
    fun `T_STAGES - the chest arrives at stage 7 and element size never changes`() {
        T_STAGES.forEachIndexed { stage, spec ->
            assertEquals(stage >= 7, spec.chest)
            assertEquals(stage >= 3, spec.ring)
            assertEquals(stage >= 5, spec.crown)
            assertEquals(stage >= 8, spec.beads)
        }
        assertEquals(listOf(1, 1, 1, 3, 4, 5, 6, 7, 9, 12), T_STAGES.map { it.coins })
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 2, 3, 5), T_STAGES.map { it.gems })
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1, 2, 3), T_STAGES.map { it.spill })
        // seats for the crown
        assertEquals(-1.1, heapTopY(1))
        assertEquals(-2.9, heapTopY(3))
        assertEquals(-4.7, heapTopY(8))
        assertEquals(-6.5, heapTopY(13))
    }

    @Test
    fun `coin rows sort back-to-front with STABLE ties, like JS sort`() {
        // CHEST_SLOTS rows tie on dy; authored order must survive the sort or
        // overlapping coins shingle the wrong way (left drawn first, right on top).
        val sorted = stableSortedByDY(CHEST_SLOTS.take(5))
        assertEquals(listOf(0.0, -2.9, 2.9, -5.4, 5.4), sorted.map { it.first })
        assertEquals(listOf(-0.4, -0.3, -0.3, -0.1, -0.1), sorted.map { it.second })
        // COIN_SLOTS row 1 ties four ways.
        val mound = stableSortedByDY(COIN_SLOTS.take(5))
        assertEquals(listOf(-1.45, 1.45, 0.0, -2.9, 2.9), mound.map { it.first })
    }

    @Test
    fun `the lying and egg branches never touch layout legs`() {
        // Stages 0–1 have legs == [] — the treasure clamp indexes legs[0] and
        // must be unreachable there (guarded by the standing branch).
        for (stage in listOf(0, 1)) {
            val n = leafCount(stage = stage, accessories = listOf(Accessory.Dragon.treasure))
            assertTrue(n > leafCount(stage = stage))
        }
    }
}

// -- Leaf-count snapshot ------------------------------------------------------

// Captured from the iOS port's first green run and frozen — the two ports
// transcribe the same TSX statement for statement, so the recorded draw lists
// must have the same leaf counts. A refactor that silently drops (or
// duplicates) a part changes one of these numbers and fails loudly. The key is
// "s<stage>-<wardrobe>".
private val LEAF_SNAPSHOT: Map<String, Int> = mapOf(
    "s0-all" to 35,
    "s0-base" to 27,
    "s0-preview" to 24,
    "s0-treasure" to 35,
    "s1-all" to 39,
    "s1-base" to 31,
    "s1-preview" to 24,
    "s1-treasure" to 39,
    "s2-all" to 56,
    "s2-base" to 32,
    "s2-preview" to 32,
    "s2-treasure" to 40,
    "s3-all" to 88,
    "s3-base" to 40,
    "s3-preview" to 40,
    "s3-treasure" to 64,
    "s4-all" to 100,
    "s4-base" to 46,
    "s4-preview" to 46,
    "s4-treasure" to 74,
    "s5-all" to 116,
    "s5-base" to 51,
    "s5-preview" to 51,
    "s5-treasure" to 90,
    "s6-all" to 123,
    "s6-base" to 56,
    "s6-preview" to 51,
    "s6-treasure" to 99,
    "s7-all" to 176,
    "s7-base" to 73,
    "s7-preview" to 53,
    "s7-treasure" to 149,
    "s8-all" to 218,
    "s8-base" to 83,
    "s8-preview" to 53,
    "s8-treasure" to 192,
    "s9-all" to 249,
    "s9-base" to 90,
    "s9-preview" to 55,
    "s9-treasure" to 223,
)

class DragonRigSnapshotTests {

    @Test
    fun `leaf counts match the frozen table`() {
        val actual = mutableMapOf<String, Int>()
        for (stage in 0..9) {
            for (wardrobe in listOf(WARDROBES[0], WARDROBES[8], WARDROBES[10])) { // base, treasure, all
                actual["s$stage-${wardrobe.name}"] = leafCount(
                    stage = stage,
                    styles = wardrobe.styles,
                    accessories = wardrobe.accessories,
                )
            }
            actual["s$stage-preview"] = leafCount(stage = stage, preview = true)
        }
        for ((key, expected) in LEAF_SNAPSHOT) {
            assertEquals(expected, actual[key], "$key: got ${actual[key] ?: -1}, expected $expected")
        }
        assertEquals(LEAF_SNAPSHOT.size, actual.size)
    }
}
