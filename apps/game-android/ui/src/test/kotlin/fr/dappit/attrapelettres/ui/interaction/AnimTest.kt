package fr.dappit.attrapelettres.ui.interaction

import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The interaction animations, asserted AS DATA against the WAAPI calls in the
// TypeScript — never against the Kotlin. Sources of every expected number:
//
//   press  src/components/Tile.tsx        scale [1, 0.9, 1], 130 ms, ease-out
//   shake  src/components/Tile.tsx        translateX [0, -8, 8, -5, 0], 300 ms,
//                                         ease-in-out
//   pop    src/components/usePopFlourish  scale [0.4, 1.18@0.68, 1] plus
//                                         opacity [0, 1@0.68, 1], 480 ms,
//                                         cubic-bezier(.2,1.35,.4,1)
//   pulse  tailwindcss config.full.js     `pulse 2s cubic-bezier(0.4,0,0.6,1)
//                                         infinite`, keyframes `50% {opacity:.5}`
//                                         — ONLY the 50% frame is defined, so
//                                         the endpoints are the element's own
//                                         opacity (GameFrame.tsx:57 inlines 0.8)
//
// CSS Easing Functions Level 1: ease-out = cubic-bezier(0, 0, 0.58, 1),
// ease-in-out = cubic-bezier(0.42, 0, 0.58, 1). WAAPI's `easing` option applies
// per keyframe INTERVAL, which is why the tracks below are also asserted as
// intervals.
//
// A running `Animatable` needs a `MonotonicFrameClock` and cannot be sampled by
// a host test, so nothing here starts one. What can be got wrong in the port —
// the numbers, the units, the interval split and the gate — is all here.

// `inertScope()` lives in InteractionTestSupport.kt: a scope that accepts a
// `launch` and never runs it, so "did this fire synchronously?" is a
// deterministic assertion rather than a race.

class AnimSpecTest {

    @Test
    fun `press - scale 1 to 0point9 to 1, 130 ms, CSS ease-out`() {
        assertEquals(listOf(1f, 0.9f, 1f), Anim.PRESS.values)
        // A WAAPI keyframe list with no explicit offsets is evenly spaced.
        assertEquals(listOf(0f, 0.5f, 1f), Anim.PRESS.keyTimes)
        assertEquals(130, Anim.PRESS.durationMillis)
        assertEquals(Bezier(0f, 0f, 0.58f, 1f), Anim.PRESS.easing)
        assertEquals(1f, Anim.PRESS.start)
        assertEquals(1f, Anim.PRESS.target)
    }

    @Test
    fun `press splits into two equal 65 ms intervals`() {
        assertEquals(
            listOf(MotionSegment(1f, 0.9f, 65), MotionSegment(0.9f, 1f, 65)),
            Anim.PRESS.segments(),
        )
    }

    @Test
    fun `shake - translateX 0, -8, 8, -5, 0 dp over 300 ms, CSS ease-in-out`() {
        assertEquals(listOf(0f, -8f, 8f, -5f, 0f), Anim.SHAKE.values)
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), Anim.SHAKE.keyTimes)
        assertEquals(300, Anim.SHAKE.durationMillis)
        assertEquals(Bezier(0.42f, 0f, 0.58f, 1f), Anim.SHAKE.easing)
    }

    @Test
    fun `shake splits into four equal 75 ms intervals — the four half-cycles`() {
        assertEquals(
            listOf(
                MotionSegment(0f, -8f, 75),
                MotionSegment(-8f, 8f, 75),
                MotionSegment(8f, -5f, 75),
                MotionSegment(-5f, 0f, 75),
            ),
            Anim.SHAKE.segments(),
        )
    }

    @Test
    fun `pop - scale 0point4 to 1point18 at 0point68 to 1, with alpha 0 to 1, 480 ms`() {
        assertEquals(listOf(0.4f, 1.18f, 1f), Anim.POP_SCALE.values)
        assertEquals(listOf(0f, 0.68f, 1f), Anim.POP_SCALE.keyTimes)
        assertEquals(480, Anim.POP_SCALE.durationMillis)

        assertEquals(listOf(0f, 1f, 1f), Anim.POP_ALPHA.values)
        assertEquals(listOf(0f, 0.68f, 1f), Anim.POP_ALPHA.keyTimes)
        assertEquals(480, Anim.POP_ALPHA.durationMillis)

        // The overshoot, on both channels — it is one WAAPI call in the web.
        assertEquals(Bezier(0.2f, 1.35f, 0.4f, 1f), Anim.POP_SCALE.easing)
        assertEquals(Bezier(0.2f, 1.35f, 0.4f, 1f), Anim.POP_ALPHA.easing)
    }

    @Test
    fun `the pop's 0point68 offset rounds to 326 ms, and the intervals still sum to 480`() {
        val segments = Anim.POP_SCALE.segments()
        assertEquals(listOf(326, 154), segments.map { it.durationMillis })
        assertEquals(480, segments.sumOf { it.durationMillis })
    }

    @Test
    fun `pulse - the endpoints are the element's OWN opacity, not 1`() {
        // Tailwind authors only the 50% frame, so 0% and 100% take the computed
        // style. GameFrame.tsx:57 inlines opacity 0.8 on the live star, so the
        // star breathes 0.8 -> 0.5 -> 0.8.
        assertEquals(0.8f, Anim.PULSE_BASE_ALPHA)
        assertEquals(0.5f, Anim.PULSE_MIN_ALPHA)

        val star = Anim.pulseSpec()
        assertEquals(listOf(0.8f, 0.5f, 0.8f), star.values)
        assertEquals(listOf(0f, 0.5f, 1f), star.keyTimes)
        assertEquals(2000, star.durationMillis)
        assertEquals(Bezier(0.4f, 0f, 0.6f, 1f), star.easing)
        assertEquals(listOf(1000, 1000), star.segments().map { it.durationMillis })

        // CSS semantics: a different underlying opacity gives different
        // endpoints, because only the 50% frame is authored.
        assertEquals(listOf(1f, 0.5f, 1f), Anim.pulseSpec(1f).values)
    }

    @Test
    fun `the easings are the CSS curves, not Material's`() {
        // Material's FastOutSlowIn is (0.4, 0, 0.2, 1) and is not what a
        // browser runs. The web app is the source of truth.
        assertEquals(Bezier(0f, 0f, 0.58f, 1f), Anim.EASE_OUT)
        assertEquals(Bezier(0.42f, 0f, 0.58f, 1f), Anim.EASE_IN_OUT)
        assertEquals(Bezier(0.2f, 1.35f, 0.4f, 1f), Anim.POP_OVERSHOOT)
        assertEquals(Bezier(0.4f, 0f, 0.6f, 1f), Anim.PULSE_CURVE)
    }

    @Test
    fun `a bezier converts to an easing pinned at both ends`() {
        val easing = Anim.EASE_OUT.toEasing()
        assertEquals(0f, easing.transform(0f), 1e-4f)
        assertEquals(1f, easing.transform(1f), 1e-4f)
        // ease-out leaves fast: it is above the diagonal in the first half.
        assertTrue(easing.transform(0.25f) > 0.25f)
    }

    @Test
    fun `every track is well formed - one key time per value, 0 to 1`() {
        val tracks = listOf(Anim.PRESS, Anim.SHAKE, Anim.POP_SCALE, Anim.POP_ALPHA, Anim.pulseSpec())
        for (track in tracks) {
            assertEquals(track.values.size, track.keyTimes.size)
            assertEquals(0f, track.keyTimes.first())
            assertEquals(1f, track.keyTimes.last())
            assertEquals(track.durationMillis, track.segments().sumOf { it.durationMillis })
            assertEquals(track.values.size - 1, track.segments().size)
        }
    }
}

/**
 * Invariant 6's proving test, named in ARCHITECTURE.md section 3 row 6.
 */
class AnimTest {

    private val still: ReduceMotionSource = FixedReduceMotion(true)
    private val moving: ReduceMotionSource = FixedReduceMotion(false)

    @Test
    fun reducedMotionGatesOnlyWhatTheWebGates() {
        // Measured in the PWA rather than assumed (iOS D29):
        //   usePopFlourish.ts:29        reads the media query   -> pop IS gated
        //   GameFrame.tsx:57 motion-safe Tailwind's own gate    -> pulse IS gated
        //   Tile.tsx:59 / :61           bare el.animate(...)    -> NOT gated
        // CLAUDE.md invariant 6 scopes itself identically: "mascot + confetti".
        // A squish and a wobble are how a tap FEELS, not ornament; suppressing
        // them would make the game feel dead for the child who needs the
        // setting most.
        assertFalse(MotionSurface.PRESS.gatedByReduceMotion)
        assertFalse(MotionSurface.SHAKE.gatedByReduceMotion)
        assertTrue(MotionSurface.POP.gatedByReduceMotion)
        assertTrue(MotionSurface.PULSE.gatedByReduceMotion)
        assertTrue(MotionSurface.MASCOT.gatedByReduceMotion)
        assertTrue(MotionSurface.CONFETTI.gatedByReduceMotion)

        // With the setting ON, the tap feedback still runs and the decoration
        // does not.
        assertTrue(Anim.shouldAnimate(MotionSurface.PRESS, still))
        assertTrue(Anim.shouldAnimate(MotionSurface.SHAKE, still))
        assertFalse(Anim.shouldAnimate(MotionSurface.POP, still))
        assertFalse(Anim.shouldAnimate(MotionSurface.PULSE, still))
        assertFalse(Anim.shouldAnimate(MotionSurface.MASCOT, still))
        assertFalse(Anim.shouldAnimate(MotionSurface.CONFETTI, still))

        // With the setting OFF, everything animates.
        for (surface in MotionSurface.entries) {
            assertTrue(Anim.shouldAnimate(surface, moving), "$surface must animate when motion is allowed")
        }
    }

    @Test
    fun `press and shake cannot be gated — there is nowhere to pass the source`() {
        // The absence of the parameter is the mechanism. If someone adds a
        // ReduceMotionSource to TileMotion, this fails before the behaviour
        // can regress.
        val forbidden = ReduceMotionSource::class.java
        for (constructor in TileMotion::class.java.declaredConstructors) {
            assertTrue(
                constructor.parameterTypes.none { forbidden.isAssignableFrom(it) },
                "TileMotion must not take a ReduceMotionSource",
            )
        }
        for (name in listOf("press", "shake")) {
            val method = TileMotion::class.java.declaredMethods.single { it.name == name }
            assertTrue(
                method.parameterTypes.none { forbidden.isAssignableFrom(it) },
                "TileMotion.$name must not take a ReduceMotionSource",
            )
        }
    }

    @Test
    fun `a gated flourish skips the animation but never the caller's completion`() {
        // Gameplay sequenced behind a flourish must not stall for a child with
        // the setting on, so the completion fires synchronously when nothing
        // animates.
        var completed = 0
        val gated = PopMotion(inertScope(), still)
        assertTrue(gated.gated)
        gated.play { completed += 1 }
        assertEquals(1, completed)

        // The ungated one goes down the animation path instead: the inert scope
        // never runs the coroutine, so the completion has NOT fired here. If
        // someone re-gates pop by accident, this count becomes 2.
        val live = PopMotion(inertScope(), moving)
        assertFalse(live.gated)
        live.play { completed += 1 }
        assertEquals(1, completed)
    }

    @Test
    fun `a gated pulse sits at the base opacity — static, not hidden`() {
        // `motion-safe:` drops the class; the inline opacity 0.8 stays. The
        // star is still visible, it just does not breathe.
        val gated = PulseMotion(inertScope(), still)
        assertTrue(gated.gated)
        gated.start()
        assertEquals(Anim.PULSE_BASE_ALPHA, gated.alpha.value)

        val live = PulseMotion(inertScope(), moving)
        assertFalse(live.gated)
    }
}
