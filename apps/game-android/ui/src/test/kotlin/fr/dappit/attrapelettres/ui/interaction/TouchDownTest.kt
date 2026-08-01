package fr.dappit.attrapelettres.ui.interaction

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Invariant 1's proving test. Everything here drives the press state machine
// directly; no pointer event is simulated, because a host JUnit run cannot
// dispatch one — see the honest limits at the bottom of the file.
//
// The contract under test comes from the PWA (`src/components/Tile.tsx`:
// `onPointerDown` fires the feedback synchronously; a click is a down plus an
// up on the element) and from the iOS port's device findings (D49: a flick that
// lifts over a card must not tap it). It does not come from the Kotlin.

private val BOUNDS = IntSize(100, 100)
private val INSIDE = Offset(50f, 50f)
private val OUTSIDE = Offset(400f, 50f)

class TouchDownCoreTest {

    @Test
    fun `onDown fires at touch-DOWN, not at the lift`() {
        var downs = 0
        var ups = 0
        val core = TouchDownCore(onDown = { downs += 1 }, onUp = { ups += 1 })

        core.began()
        // The finger is still down and the feedback has already fired. An
        // implementation that acts on pointer-up fails on this line, and
        // `Modifier.clickable` is exactly such an implementation.
        assertEquals(1, downs)
        assertEquals(0, ups)

        core.ended(INSIDE, BOUNDS)
        assertEquals(1, downs)
        assertEquals(1, ups)
    }

    @Test
    fun `onDown is synchronous — the effect is observable on the next line`() {
        val log = mutableListOf<String>()
        val core = TouchDownCore(onDown = { log.add("down") }, onUp = {})

        log.add("pre")
        core.began()
        log.add("post")

        // Routed through a state write, a LaunchedEffect, a dispatched
        // coroutine or an animation callback, "down" would land after "post"
        // (or not yet at all) and this exact ordering breaks.
        assertEquals(listOf("pre", "down", "post"), log)
    }

    @Test
    fun `onUp distinguishes a lift inside from a lift outside`() {
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.began()
        core.ended(Offset(10f, 10f), BOUNDS)

        core.began()
        core.ended(OUTSIDE, BOUNDS) // slid off the tile, then lifted

        assertEquals(listOf(true, false), insides)
    }

    @Test
    fun `sliding off does not cancel — only the lift decides`() {
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.began()
        // Wandering outside and back in, with nobody consuming: still one press.
        core.handle(PointerPhase.MOVE, inside = false, stolenByAncestor = false)
        core.handle(PointerPhase.MOVE, inside = true, stolenByAncestor = false)
        assertTrue(core.isTracking)
        core.handle(PointerPhase.UP, inside = true, stolenByAncestor = false)

        // The web is the same: `Tile.tsx` has no pointerleave handling at all,
        // and a six-year-old's press wanders.
        assertEquals(listOf(true), insides)
    }

    @Test
    fun `a cancellation is a lift-outside — the web's pointercancel`() {
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.began()
        core.cancelled()

        assertEquals(listOf(false), insides)
    }

    @Test
    fun `no phantom events — an up without a down, or a second down, is dropped`() {
        var downs = 0
        var ups = 0
        val core = TouchDownCore(onDown = { downs += 1 }, onUp = { ups += 1 })

        core.ended(INSIDE, BOUNDS) // up with no down
        core.cancelled() // cancel with no down
        assertEquals(0, ups)

        core.began()
        core.began() // duplicate down while the finger is already down
        assertEquals(1, downs)

        core.ended(INSIDE, BOUNDS)
        core.ended(INSIDE, BOUNDS) // duplicate up
        assertEquals(1, ups)
    }

    @Test
    fun `a cancel mid-touch swallows the lift that follows it`() {
        // The shape the scroll fix relies on. Once a drag has been ruled a
        // scroll, the lift that follows must not become a tap — the caller sees
        // exactly ONE onUp, reporting false. Without this, the species picker
        // would leave a card toggled and the shop would open a try-on dialog at
        // the end of a flick (iOS D49).
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.began()
        core.cancelled() // the scrollable consumed the drag: this is a scroll
        core.ended(INSIDE, BOUNDS) // the system still delivers the lift
        assertEquals(listOf(false), insides)

        // and the surface still works for the NEXT, genuine tap.
        core.began()
        core.ended(INSIDE, BOUNDS)
        assertEquals(listOf(false, true), insides)
    }

    @Test
    fun `the core is reusable across taps`() {
        var downs = 0
        val core = TouchDownCore(onDown = { downs += 1 }, onUp = {})

        repeat(3) {
            core.began()
            core.ended(INSIDE, BOUNDS)
        }
        assertEquals(3, downs)
        assertFalse(core.isTracking)
    }
}

class TouchDownPhaseTest {

    @Test
    fun `a MOVE consumed upstream is a scroll, not a tap`() {
        // The Compose twin of iOS asking UIScrollView.isDragging: a scrollable
        // announces the takeover by CONSUMING the position change, which we see
        // on PointerEventPass.Final.
        var downs = 0
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = { downs += 1 }, onUp = { insides.add(it) })

        core.handle(PointerPhase.DOWN, inside = true, stolenByAncestor = false)
        assertEquals(1, downs) // the press feedback still fired at touch-down

        core.handle(PointerPhase.MOVE, inside = true, stolenByAncestor = true)
        // The finger IS on the tile when it lifts, and it still must not tap.
        core.handle(PointerPhase.UP, inside = true, stolenByAncestor = false)

        assertEquals(listOf(false), insides)
    }

    @Test
    fun `the same down-move-up with nobody consuming IS a tap`() {
        // The other half, and the reason the rule is not simply "never tap
        // inside a scroller": the shop and the species picker must stay usable.
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.handle(PointerPhase.DOWN, inside = true, stolenByAncestor = false)
        core.handle(PointerPhase.MOVE, inside = true, stolenByAncestor = false)
        core.handle(PointerPhase.UP, inside = true, stolenByAncestor = false)

        assertEquals(listOf(true), insides)
    }

    @Test
    fun `a lift consumed in the same event is not a tap either`() {
        // Belt to the braces: the takeover and the lift can arrive together.
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.handle(PointerPhase.DOWN, inside = true, stolenByAncestor = false)
        core.handle(PointerPhase.UP, inside = true, stolenByAncestor = true)

        assertEquals(listOf(false), insides)
    }

    @Test
    fun `an explicit CANCEL phase ends the press with inside false`() {
        val insides = mutableListOf<Boolean>()
        val core = TouchDownCore(onDown = {}, onUp = { insides.add(it) })

        core.handle(PointerPhase.DOWN, inside = true, stolenByAncestor = false)
        core.handle(PointerPhase.CANCEL, inside = true, stolenByAncestor = false)

        assertEquals(listOf(false), insides)
        assertFalse(core.isTracking)
    }
}

class TouchDownGeometryTest {

    @Test
    fun `isInside is half-open — the far edge belongs to the next pixel`() {
        assertTrue(isInside(Offset(0f, 0f), BOUNDS))
        assertTrue(isInside(Offset(99.9f, 99.9f), BOUNDS))
        assertFalse(isInside(Offset(100f, 50f), BOUNDS))
        assertFalse(isInside(Offset(50f, 100f), BOUNDS))
        assertFalse(isInside(Offset(-0.1f, 50f), BOUNDS))
        assertFalse(isInside(Offset(50f, -0.1f), BOUNDS))
    }

    @Test
    fun `a zero-sized element contains nothing`() {
        assertFalse(isInside(Offset.Zero, IntSize(0, 0)))
    }
}

class TouchDownModifierTest {

    private fun count(modifier: Modifier): Int {
        var n = 0
        modifier.foldIn(Unit) { _, _ -> n += 1 }
        return n
    }

    @Test
    fun `touchDownRaw installs exactly one pointer-input element`() {
        // One element, and it is a pointer-input node — not a clickable, whose
        // node comes with an Indication and a semantics action behind it.
        val chain = Modifier.touchDownRaw {}
        assertEquals(1, count(chain))
        val name = chain.foldIn("") { _, element -> element.javaClass.simpleName }
        assertTrue(
            name.contains("PointerInput", ignoreCase = true),
            "expected a pointer-input modifier element, got $name",
        )
    }

    @Test
    fun `a disabled surface still builds, and adds nothing else`() {
        // `enabled` is the web's `disabled` prop: the handler returns early.
        // It must not switch the modifier out, or the node would be recreated
        // in the middle of a press.
        assertEquals(1, count(Modifier.touchDownRaw(enabled = false) {}))
    }
}

// --- Review-time rules turned into build-time ones ----------------------------
//
// The same mechanism `LicensingSourceScanTest` uses in :core. One grep with a
// paragraph of reason behind it, and the reason is in the test name.

private fun findInteractionSources(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/interaction"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val here = File(dir, suffix)
        if (here.isDirectory) return here
        val nested = File(dir, "ui/$suffix")
        if (nested.isDirectory) return nested
        val underApp = File(dir, "apps/game-android/ui/$suffix")
        if (underApp.isDirectory) return underApp
        dir = dir.parentFile
    }
    return null
}

private val interactionSourcesOrNull: File? = findInteractionSources()

/**
 * Lines with comments removed, so a scan matches CODE and not the paragraph
 * that explains why the code does not do that.
 */
private fun codeLines(file: File): List<String> {
    val out = mutableListOf<String>()
    var inBlock = false
    for (raw in file.readLines()) {
        val line = raw.trim()
        if (inBlock) {
            if (line.endsWith("*/")) inBlock = false
            continue
        }
        if (line.startsWith("/*")) {
            if (!line.endsWith("*/")) inBlock = true
            continue
        }
        if (line.startsWith("//")) continue
        out.add(raw.substringBefore("//"))
    }
    return out
}

class TouchDownSourceScanTest {

    private fun sources(): List<File> {
        val dir = assertNotNull(
            interactionSourcesOrNull,
            "the scan could not locate ui/interaction from ${System.getProperty("user.dir")}",
        )
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue(files.isNotEmpty(), "no Kotlin sources found in $dir")
        return files
    }

    @Test
    fun `no interaction file reaches for Modifier clickable — it fires on UP`() {
        // The single most likely regression in this package, and the one a
        // future editor reaches for first. clickable fires on pointer-up, after
        // gesture arbitration, behind Material's ripple: three commits between
        // a six-year-old's finger and the sound.
        for (file in sources()) {
            for ((index, line) in codeLines(file).withIndex()) {
                assertFalse(
                    line.contains("clickable("),
                    "${file.name}:${index + 1} uses clickable — invariant 1 forbids it",
                )
                assertFalse(
                    line.contains("detectTapGestures"),
                    "${file.name}:${index + 1} uses detectTapGestures — use awaitFirstDown",
                )
            }
        }
    }

    @Test
    fun `the down is taken unconsumed, on the Initial pass`() {
        // Both halves of the scroll bargain, asserted structurally: we are ahead
        // of every ancestor (Initial) and we do not care what they wanted
        // (requireUnconsumed = false). Losing either one means a scrollable
        // ancestor can delay the feedback by the touch-slop timeout.
        val touchDown = sources().single { it.name == "TouchDown.kt" }
        val code = codeLines(touchDown).joinToString("\n")
        assertTrue(code.contains("requireUnconsumed = false"), "the down must not require an unconsumed event")
        assertTrue(code.contains("PointerEventPass.Initial"), "the down must be taken on the Initial pass")
        assertTrue(code.contains("PointerEventPass.Final"), "the takeover must be observed on the Final pass")
    }

    @Test
    fun `onDown is a plain callback, never a suspend one`() {
        // A suspend onDown is the design error this file exists to prevent: it
        // invites awaitRelease() and delay() onto the tap path, and AudioEngine
        // pop/nudge are non-suspend precisely so nothing needs it (A3).
        val touchDown = sources().single { it.name == "TouchDown.kt" }
        val code = codeLines(touchDown).joinToString("\n")
        assertTrue(code.contains("onDown: () -> Unit"))
        assertFalse(code.contains("onDown: suspend"))
    }
}
