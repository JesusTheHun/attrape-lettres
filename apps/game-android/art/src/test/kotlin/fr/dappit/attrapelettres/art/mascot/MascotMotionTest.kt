package fr.dappit.attrapelettres.art.mascot

import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The mood animation and the sheen sweep, asserted AS DATA. A running
// `Animatable` cannot be sampled on the host, but everything that can be got
// wrong in the port can: the keyframe shape, the timings, the two unit systems
// and the three gates (preview / reduced motion / mood).
//
// Every expectation below is read off `src/mascot/Mascot.tsx` and
// `src/index.css`, never off the Kotlin.

class MascotMotionPlanTest {

    @Test
    fun `idle bobs, happy pops, cheer cheers`() {
        // mood === "idle" ? bob : mood === "cheer" ? cheer : pop
        assertEquals(MascotMotion.bob, MascotMotion.plan(Mood.IDLE, preview = false, reduceMotion = false))
        assertEquals(MascotMotion.cheer, MascotMotion.plan(Mood.CHEER, preview = false, reduceMotion = false))
        assertEquals(MascotMotion.pop, MascotMotion.plan(Mood.HAPPY, preview = false, reduceMotion = false))
    }

    @Test
    fun `preview never animates — a grid of jittering mascots is noise`() {
        for (mood in Mood.entries) {
            assertNull(MascotMotion.plan(mood, preview = true, reduceMotion = false))
        }
    }

    @Test
    fun `reduced motion starts NOTHING — not a shorter bob, nothing`() {
        for (mood in Mood.entries) {
            assertNull(MascotMotion.plan(mood, preview = false, reduceMotion = true))
            assertNull(MascotMotion.plan(mood, preview = true, reduceMotion = true))
        }
    }

    @Test
    fun `the injected reduced-motion source is what silences the mascot`() {
        // D14's Android twin: one source, injected, so a test can force it.
        val reduced = FixedReduceMotion(true)
        val normal = FixedReduceMotion(false)
        assertNull(MascotMotion.plan(Mood.IDLE, preview = false, reduceMotion = reduced.isReduced))
        assertEquals(
            MascotMotion.bob,
            MascotMotion.plan(Mood.IDLE, preview = false, reduceMotion = normal.isReduced),
        )
    }

    @Test
    fun `the bob's keyframe shape - rotate DOWN at rest, UP and rotated at the midpoint`() {
        val bob = MascotMotion.bob
        assertEquals(2.6, bob.duration)
        assertTrue(bob.repeats)
        assertEquals(MascotEasing.EASE_IN_OUT, bob.easing)
        assertEquals(3, bob.keyframes.size)

        assertEquals(0.0, bob.keyframes[0].offset)
        assertEquals(MascotTransform(y = 0.0, rotation = -1.5, scale = 1.0), bob.keyframes[0].transform)
        assertEquals(0.5, bob.keyframes[1].offset)
        assertEquals(MascotTransform(y = -5.0, rotation = 1.5, scale = 1.0), bob.keyframes[1].transform)
        // The loop is seamless only because the last frame equals the first.
        assertEquals(1.0, bob.keyframes[2].offset)
        assertEquals(bob.keyframes[0].transform, bob.keyframes[2].transform)
    }

    @Test
    fun `the pop drops its rotation on the way back — the 0-98 frame has no rotate`() {
        val pop = MascotMotion.pop
        assertEquals(0.48, pop.duration)
        assertFalse(pop.repeats)
        assertEquals(MascotEasing.EASE_OUT, pop.easing)
        assertEquals(listOf(0.0, 0.4, 0.72, 1.0), pop.keyframes.map { it.offset })
        assertEquals(MascotTransform(y = 0.0, rotation = 4.0, scale = 1.16), pop.keyframes[1].transform)
        // `{ transform: "scale(0.98)" }` — no rotate, so CSS reads 0 degrees.
        assertEquals(0.0, pop.keyframes[2].transform.rotation)
        assertEquals(0.98, pop.keyframes[2].transform.scale)
        assertEquals(MascotTransform.Identity, pop.keyframes[3].transform)
    }

    @Test
    fun `the cheer's biggest scale is the THIRD peak, not the first`() {
        val cheer = MascotMotion.cheer
        assertEquals(0.68, cheer.duration)
        assertFalse(cheer.repeats)
        assertEquals(MascotEasing.EASE_OUT, cheer.easing)
        assertEquals(listOf(0.0, 0.25, 0.5, 0.74, 1.0), cheer.keyframes.map { it.offset })
        assertEquals(listOf(1.0, 1.2, 1.1, 1.22, 1.0), cheer.keyframes.map { it.transform.scale })
        assertEquals(listOf(0.0, -6.0, 6.0, -4.0, 0.0), cheer.keyframes.map { it.transform.rotation })
        // Nothing in the cheer translates — only the bob does.
        assertTrue(cheer.keyframes.all { it.transform.y == 0.0 })
    }

    @Test
    fun `no plan translates in y except the bob, and its minus 5 is SCREEN dp`() {
        // Trap 1: this value must NOT be scaled by size/100. It is on the <svg>
        // element, so a 220 dp mascot bobs the same 5 dp as an 88 dp one.
        assertEquals(-5.0, MascotMotion.bob.keyframes[1].transform.y)
        assertTrue(MascotMotion.pop.keyframes.all { it.transform.y == 0.0 })
        assertTrue(MascotMotion.cheer.keyframes.all { it.transform.y == 0.0 })
    }

    @Test
    fun `transform-origin is 50 percent 82 percent`() {
        assertEquals(0.5f, MascotMotion.ORIGIN_X)
        assertEquals(0.82f, MascotMotion.ORIGIN_Y)
        // Not the centre: the pivot is down at the feet so a scaling mascot
        // stays planted on the ground line instead of sinking into it.
        assertNotEquals(0.5f, MascotMotion.ORIGIN_Y)
    }
}

class MascotMotionSegmentTest {

    @Test
    fun `every plan opens with a zero-duration frame, so a restart cannot inherit a mid-flight transform`() {
        for (plan in listOf(MascotMotion.bob, MascotMotion.pop, MascotMotion.cheer)) {
            val segments = plan.segments
            assertEquals(0.0, segments.first().duration)
            assertEquals(plan.keyframes.first().transform, segments.first().transform)
            assertEquals(plan.keyframes.size, segments.size)
        }
    }

    @Test
    fun `segment durations are the offset gaps, and they sum to the whole`() {
        // bob: offsets 0, 0.5, 1 over 2600 ms → 0, 1300, 1300.
        val bob = MascotMotion.bob.segments.map { it.duration }
        assertEquals(3, bob.size)
        assertEquals(1.3, bob[1], 1e-12)
        assertEquals(1.3, bob[2], 1e-12)
        assertEquals(2.6, bob.sum(), 1e-12)

        // cheer: 0.25, 0.25, 0.24, 0.26 of 680 ms.
        val cheer = MascotMotion.cheer.segments.map { it.duration }
        assertEquals(0.17, cheer[1], 1e-12)
        assertEquals(0.68, cheer.sum(), 1e-12)
    }

    @Test
    fun `identity is the un-animated rig, and the bob's first frame is NOT identity`() {
        assertEquals(MascotTransform(y = 0.0, rotation = 0.0, scale = 1.0), MascotTransform.Identity)
        // If it were, the leading zero-duration frame would be redundant; it is
        // not, and dropping it would start every bob square instead of tilted.
        assertNotEquals(MascotTransform.Identity, MascotMotion.bob.keyframes[0].transform)
    }

    @Test
    fun `an empty plan has no segments rather than throwing`() {
        // Not reachable from the three authored plans, but `segments` indexes
        // backwards and a crash in the drawing layer is invariant 3's problem.
        val empty = MascotMotionPlan(emptyList(), duration = 1.0, repeats = false, easing = MascotEasing.EASE_OUT)
        assertTrue(empty.segments.isEmpty())
    }
}

class MascotSheenTest {

    @Test
    fun `the sweep runs minus 150 to plus 150 viewBox units over 3-6 s, and wraps`() {
        assertEquals(-150.0, MascotSheen.FROM_UNITS)
        assertEquals(150.0, MascotSheen.TO_UNITS)
        assertEquals(3.6, MascotSheen.DURATION)

        assertEquals(-150.0, MascotSheen.offsetUnits(0.0, reduceMotion = false))
        assertEquals(0.0, MascotSheen.offsetUnits(1.8, reduceMotion = false), 1e-12)
        // Linear, infinite, no autoreverse: t = duration is t = 0 again.
        assertEquals(-150.0, MascotSheen.offsetUnits(3.6, reduceMotion = false), 1e-12)
        assertEquals(0.0, MascotSheen.offsetUnits(5.4, reduceMotion = false), 1e-12)
    }

    @Test
    fun `a negative clock still lands inside the sweep`() {
        // The double modulo is not decoration: a raw `%` on a negative t would
        // put the band at +150 and beyond, off the pet, invisible for a whole
        // period.
        val v = MascotSheen.offsetUnits(-0.9, reduceMotion = false)
        assertTrue(v >= -150.0 && v <= 150.0, "sweep left its range at t = -0.9: $v")
        assertEquals(75.0, v, 1e-9)
    }

    @Test
    fun `under reduced motion the band FREEZES CENTRED — not hidden, not parked off the pet`() {
        // Invariant 6, explicitly: `animation: none !important` leaves the rect
        // at its base transform, which is translateX(0) — a static rainbow band
        // across the middle of the creature. Parking it at FROM_UNITS would put
        // it 150 units off the pet, where the silhouette mask hides it, and the
        // accessory a family paid 200 stars for would vanish.
        assertEquals(0.0, MascotSheen.PARKED_UNITS)
        assertNotEquals(MascotSheen.FROM_UNITS, MascotSheen.PARKED_UNITS)
        for (t in listOf(0.0, 1.0, 2.5, 9.9)) {
            assertEquals(0.0, MascotSheen.offsetUnits(t, reduceMotion = true))
        }
    }

    @Test
    fun `the recorded band sits exactly where reduced motion parks it`() {
        // `drawRainbowSheen` records the band with no translation at all, and
        // PARKED_UNITS is 0 — the still rig and the reduced rig are the same
        // picture, which is what makes the freeze invisible rather than a jump.
        val nodes = record { drawRainbowSheen(it) }
        val band = fillsOf(nodes).single()
        // The rect is authored at x = 50 - 26 in viewBox units; nothing shifted it.
        val xs = coordinates(band.path).filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(24.0f, xs.min())
        assertEquals(76.0f, xs.max())
        assertTrue(abs(MascotSheen.PARKED_UNITS) < 1e-12)
    }
}

// Invariant 2, as a SOURCE SCAN.
//
// The runtime shape of the invariant is unobservable from inside the process:
// a mascot driven by hoisted state animates perfectly on a fast phone and just
// costs a full recomposition — and a re-recorded draw list, and a re-parsed set
// of `d` strings — sixty times a second. Nothing goes red. What CAN be checked
// is the construction: the animated transform is read inside a `graphicsLayer`
// block and is never put into snapshot state that the mascot itself reads.

private object MotionSource {

    private val CANDIDATES = listOf(
        // Gradle runs a module's unit tests with the MODULE directory as the
        // working directory, so this is Motion.kt seen from `:art`.
        "src/main/kotlin/fr/dappit/attrapelettres/art/mascot/Motion.kt",
        "art/src/main/kotlin/fr/dappit/attrapelettres/art/mascot/Motion.kt",
        "apps/game-android/art/src/main/kotlin/fr/dappit/attrapelettres/art/mascot/Motion.kt",
    )

    /** Motion.kt, found by walking up from the test's working directory. */
    val file: File = run {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in CANDIDATES) {
                val resolved = File(directory, candidate)
                if (resolved.isFile) return@run resolved
            }
            directory = directory.parentFile
        }
        error("Motion.kt not found above ${File("").absoluteFile}")
    }

    /**
     * The source with every comment blanked out.
     *
     * Motion.kt explains at length why `mutableStateOf` would be wrong and
     * quotes the modifier it wants applied outside the sheen — a scan that read
     * that prose would be agreeing with the file's own justification instead of
     * checking its code. Block comments are counted with a DEPTH, because
     * Kotlin's nest, which is also why nothing in this repo writes the opening
     * sequence inside one.
     */
    val code: String = strip(file.readText())

    private fun strip(source: String): String {
        val out = StringBuilder()
        var index = 0
        var depth = 0
        while (index < source.length) {
            if (depth > 0) {
                when {
                    source.startsWith("/*", index) -> { depth++; index += 2 }
                    source.startsWith("*/", index) -> { depth--; index += 2 }
                    else -> {
                        // Newlines survive, so a reported offset still means something.
                        if (source[index] == '\n') out.append('\n')
                        index++
                    }
                }
                continue
            }
            when {
                source.startsWith("/*", index) -> { depth = 1; index += 2 }
                source.startsWith("//", index) ->
                    while (index < source.length && source[index] != '\n') index++

                else -> {
                    out.append(source[index])
                    index++
                }
            }
        }
        return out.toString()
    }
}

class MascotMotionSurfaceTest {

    @Test
    fun `the scan can see its own source`() {
        // Every other assertion here passes trivially against an empty string.
        // This is the one that says there is a file.
        assertTrue(MotionSource.file.isFile)
        assertTrue(MotionSource.code.contains("fun Modifier.mascotMoodMotion"))
    }

    @Test
    fun `the animated transform is never hoisted into snapshot state`() {
        // `remember { mutableStateOf(transform) }` read by the mascot would
        // recompose the whole rig every frame — invariant 2's exact failure.
        assertFalse(MotionSource.code.contains("mutableStateOf"), "the mascot's motion must not be hoisted state")
        assertFalse(MotionSource.code.contains("mutableFloatStateOf"))
        // …and it is not driven off a frame clock into a recomposition either.
        assertFalse(MotionSource.code.contains("withFrameNanos"))
        assertFalse(MotionSource.code.contains("animateFloatAsState"))
    }

    @Test
    fun `both modifiers deliver their value through a graphicsLayer lambda`() {
        // A deferred read: the block re-runs, the composition does not.
        assertEquals(2, Regex("graphicsLayer \\{").findAll(MotionSource.code).count())
        assertTrue(MotionSource.code.contains("Animatable"))
    }

    @Test
    fun `reduced motion is injected, never read from an ambient`() {
        // D14's Android twin: one source of the setting for the whole app, and
        // one a test or a preview harness can force.
        assertTrue(MotionSource.code.contains("reduceMotion: ReduceMotionSource"))
        assertFalse(MotionSource.code.contains("LocalAccessibilityManager"))
        assertFalse(MotionSource.code.contains("ANIMATOR_DURATION_SCALE"))
    }
}
