package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.art.svg.SvgCanvas
import fr.dappit.attrapelettres.art.svg.SvgDrawNode
import fr.dappit.attrapelettres.art.svg.SvgPaint
import fr.dappit.attrapelettres.art.svg.SvgShapes
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.Layout
import fr.dappit.attrapelettres.core.mascot.layoutFor
import fr.dappit.attrapelettres.core.mascot.stageScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The mascot SHELL: everything `Mascot.tsx` does around the species rigs — the
// ground shadow, the feet-pivot growth scale, the rainbow overlay and the five
// French accessibility labels.
//
// The first suite drives `drawShell` with a stub rig, so it tests the shell and
// only the shell: a bug in the pivot or in the mask is otherwise invisible
// inside a picture full of fur, and would be blamed on whichever species
// happened to be on screen. The second suite goes through the real dispatch.

/** A one-node rig, so a shell assertion is about the shell. */
private class MarkerRig(private val layout: Layout) {
    var calls = 0
        private set

    fun draw(c: SvgCanvas) {
        calls += 1
        c.fill(SvgShapes.circle(50.0, layout.feetY, 1.0), SvgPaint.hex("#FF0000"))
    }
}

private fun config(
    species: Species,
    stage: Int,
    accessories: List<String> = emptyList(),
) = MascotConfig(
    species = species,
    stage = stage,
    colors = emptyMap(),
    styles = emptyMap(),
    accessories = accessories,
)

class MascotShellTest {

    private val stage = 9
    private val layout = layoutFor(stage.toDouble())
    private val scale = stageScale(stage)

    private fun shell(rainbow: Boolean, rig: MarkerRig): List<SvgDrawNode> =
        record { MascotRig.drawShell(it, layout, scale, rainbow, rig::draw) }

    @Test
    fun `the ground shadow is recorded FIRST and lives outside the growth scale`() {
        val nodes = shell(rainbow = false, rig = MarkerRig(layout))
        val shadow = assertIs<SvgDrawNode.Fill>(nodes.first())
        // 10% black, which is the one property the shadow is defined by.
        assertEquals(0.1, shadow.paint.opacity)
        // `rx` is multiplied by k by hand while `cy` is not: the shadow widens
        // with the pet but stays on the same ground line.
        assertEquals(
            SvgShapes.ellipse(50.0, layout.feetY + 3.5, layout.bodyRX * 0.92 * scale, 3.6),
            shadow.path,
        )
        assertTrue(shadow.transform.isIdentity, "the shadow must not be inside the scaled group")
    }

    @Test
    fun `the growth scale is pivoted at the feet, so the pet grows UPWARD off the ground`() {
        val nodes = shell(rainbow = false, rig = MarkerRig(layout))
        val marker = fillsOf(nodes)[1]
        assertNotEquals(1.0, scale, "pick a stage whose scale is not already 1")
        // The pivot is a fixed point.
        assertEquals(50.0, marker.transform.mapX(50.0, layout.feetY), 1e-9)
        assertEquals(layout.feetY, marker.transform.mapY(50.0, layout.feetY), 1e-9)
        // Ten units above the feet becomes ten times k above them — up, not away.
        assertEquals(layout.feetY - 10 * scale, marker.transform.mapY(50.0, layout.feetY - 10), 1e-9)
        assertEquals(50.0 + 10 * scale, marker.transform.mapX(60.0, layout.feetY), 1e-9)
    }

    @Test
    fun `a scale of 1 leaves the rig untransformed, which is what preview pins`() {
        val nodes = record {
            MascotRig.drawShell(c = it, layout = layout, scale = 1.0, rainbow = false, rig = MarkerRig(layout)::draw)
        }
        assertTrue(fillsOf(nodes)[1].transform.isIdentity)
        // …and the shadow narrows with it.
        val shadow = assertIs<SvgDrawNode.Fill>(nodes.first())
        assertEquals(SvgShapes.ellipse(50.0, layout.feetY + 3.5, layout.bodyRX * 0.92, 3.6), shadow.path)
    }

    @Test
    fun `without the premium there is no mask, and the rig is walked once`() {
        val rig = MarkerRig(layout)
        val nodes = shell(rainbow = false, rig = rig)
        assertEquals(1, rig.calls)
        assertFalse(hasMask(nodes))
        assertEquals(2, nodes.size, "shadow, then the rig; nothing else")
    }

    @Test
    fun `the rainbow draws the pet, then masks the sheen with the SAME silhouette`() {
        val rig = MarkerRig(layout)
        val nodes = shell(rainbow = true, rig = rig)
        // Once for the pet, once as the matte: the mask tracks every part at
        // every stade for free because it IS the rig.
        assertEquals(2, rig.calls)
        assertEquals(3, nodes.size)
        val mask = masksOf(nodes).single()
        assertEquals(1, mask.matte.size, "the matte is the rig, not a hand-drawn blob")
        assertEquals(fillsOf(listOf(nodes[1])).single().path, fillsOf(mask.matte).single().path)
        // The content is the band, and the band alone.
        assertEquals(1, mask.content.size)
        assertEquals(9, stopsOf(fillsOf(mask.content).single().paint).size)
    }

    @Test
    fun `the shell leaves the save stack exactly as it found it`() {
        // An unbalanced rig leaks its transform into whatever draws next. The
        // canvas swallows an over-restore at runtime (invariant 3), so nothing
        // but a test can see this.
        val canvas = SvgCanvas()
        canvas.save()
        MascotRig.drawShell(c = canvas, layout = layout, scale = scale, rainbow = true, rig = MarkerRig(layout)::draw)
        assertEquals(1, canvas.saveDepth)
        assertTrue(canvas.currentTransform.isIdentity)
    }
}

class MascotLabelTest {

    // Byte-for-byte from `src/mascot/Mascot.tsx`. Hand-written, not derived from
    // `MascotRig.labels` — a derived oracle agrees with any edit and cannot
    // fail. All five are plain ASCII, so unlike the hub copy there is no
    // apostrophe question to get wrong.
    @Test
    fun `all five labels are byte-identical to Mascot tsx`() {
        assertEquals("Ma licorne", MascotRig.label(Species.UNICORN))
        assertEquals("Mon chat", MascotRig.label(Species.CAT))
        assertEquals("Mon renard", MascotRig.label(Species.FOX))
        assertEquals("Mon lapin", MascotRig.label(Species.RABBIT))
        assertEquals("Mon dragon", MascotRig.label(Species.DRAGON))
    }

    @Test
    fun `every species has a non-empty label and no two share one`() {
        // If a species is ever added without a label this is what goes red — an
        // empty contentDescription is a silent accessibility regression that no
        // rendering test would notice.
        val all = Species.entries.map(MascotRig::label)
        assertTrue(all.all { it.isNotEmpty() })
        assertEquals(Species.entries.size, all.toSet().size)
        assertEquals(Species.entries.size, MascotRig.labels.size)
        assertEquals(all, Species.entries.map { MascotRig.labels.getValue(it) })
    }
}

class MascotRigDispatchTest {

    @Test
    fun `every species and every stage draws real geometry with finite coordinates`() {
        for (species in Species.entries) {
            for (stage in 0 until GROWTH_STAGES) {
                val nodes = MascotRig.drawList(config(species, stage), Mood.IDLE)
                val ls = leaves(nodes)
                assertTrue(ls.isNotEmpty(), "$species stage $stage drew nothing")
                for (leaf in ls) {
                    val path = when (leaf) {
                        is SvgDrawNode.Fill -> leaf.path
                        is SvgDrawNode.Stroke -> leaf.path
                        else -> emptyList()
                    }
                    // A `d` the parser rejected yields an empty command list.
                    assertTrue(path.isNotEmpty(), "$species stage $stage: a leaf has no geometry")
                    assertTrue(
                        coordinates(path).all { it.isFinite() },
                        "$species stage $stage: a coordinate is NaN or infinite",
                    )
                }
            }
        }
    }

    @Test
    fun `the mascot always opens with its own ground shadow`() {
        for (species in Species.entries) {
            val first = MascotRig.drawList(config(species, 5), Mood.IDLE).first()
            val shadow = assertIs<SvgDrawNode.Fill>(first)
            assertEquals(0.1, shadow.paint.opacity, "$species lost its shadow's fill-opacity")
        }
    }

    @Test
    fun `preview pins the growth scale to 1`() {
        // A shop tile's `focus` crop is expressed in raw layout coordinates, so
        // the thumbnail must not be pre-scaled or the crop lands on the wrong
        // part of the pet.
        val stage = GROWTH_STAGES - 1
        assertNotEquals(1.0, stageScale(stage))
        for (species in Species.entries) {
            val normal = MascotRig.drawList(config(species, stage), Mood.IDLE, preview = false)
            val preview = MascotRig.drawList(config(species, stage), Mood.IDLE, preview = true)
            assertNotEquals(
                (normal.first() as SvgDrawNode.Fill).path,
                (preview.first() as SvgDrawNode.Fill).path,
                "$species: preview did not pin the scale",
            )
        }
    }

    @Test
    fun `only the star-clip premium adds the masked rainbow sheen`() {
        assertEquals(listOf(Accessory.Unicorn.starClip), RAINBOW_IDS)
        val plain = MascotRig.drawList(config(Species.UNICORN, 6), Mood.IDLE)
        val premium = MascotRig.drawList(
            config(Species.UNICORN, 6, accessories = listOf(Accessory.Unicorn.starClip)),
            Mood.IDLE,
        )
        assertFalse(hasMask(plain))
        assertTrue(hasMask(premium))
        // An unrelated accessory must not buy it.
        assertFalse(hasMask(MascotRig.drawList(config(Species.UNICORN, 6, listOf(Accessory.Unicorn.ribbon)), Mood.IDLE)))
    }
}
