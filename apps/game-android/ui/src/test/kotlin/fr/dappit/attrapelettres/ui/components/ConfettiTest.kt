package fr.dappit.attrapelettres.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.MotionSurface
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The confetti particle system, asserted against `src/hooks/useConfetti.ts` —
// never against the Kotlin, and never against the Swift. Every number below was
// read out of that file:
//
//   COLORS      ["#FF8A65","#FFD54F","#4FC3F7","#AED581","#BA9EE8","#F06292"]
//   burst       for (let i = 0; i < 90; i++)
//   origin      x: cx + (Math.random() - 0.5) * 120 * dpr
//               y: canvas.height * 0.42
//   velocity    a  = Math.random() * Math.PI - Math.PI        // [-pi, 0)
//               sp = (4 + Math.random() * 7) * dpr
//               vx = cos(a) * sp
//               vy = sin(a) * sp - 3 * dpr
//   gravity     g: 0.22 * dpr
//   size        r: (5 + Math.random() * 6) * dpr
//   spin        rot: Math.random() * 6.28, vr: (Math.random() - 0.5) * 0.4
//   integration p.vy += p.g; p.x += p.vx; p.y += p.vy; p.rot += p.vr
//   lifetime    p.life -= 0.008                               // 125 frames
//   cull        p.life <= 0 || p.y > canvas.height + 40
//   flake       fillRect(-r/2, -r/2, r, r * 0.6)
//   gate        if (!canvas || reduced.current) return;
//
// The simulation runs in DEVICE PIXELS, as the TypeScript does, so `dpr` is
// explicit in every expectation here. No composition is built anywhere in this
// file: the physics is a plain class and the drawing math is a plain function,
// which is the whole point of the split.

private val awake = FixedReduceMotion(false)
private val asleep = FixedReduceMotion(true)

/** A system whose canvas is already laid out. Sizes are RAW PIXELS. */
private fun system(
    seed: Long = 20_260_729L,
    reduceMotion: ReduceMotionSource = awake,
    widthPx: Double = 1170.0,
    heightPx: Double = 1860.0,
    scale: Double = 3.0,
): ConfettiSystem {
    val made = ConfettiSystem(random = SeededGenerator(seed), reduceMotion = reduceMotion)
    made.report(widthPx.toFloat(), heightPx.toFloat(), scale.toFloat())
    return made
}

private fun instanceFields(type: Class<*>) =
    type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }

class ConfettiTest {

    // --- The authored constants ---------------------------------------------

    @Test
    fun `COLORS is the hook's six, in order`() {
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8", "#F06292"),
            ConfettiSystem.COLORS,
        )
        // Five are the pick-tile faces; the pink is the confetti's own, which is
        // why this list is not `Palette.tileColors`.
        assertFalse(Palette.tileColors.map { it.bg.hex }.contains("#F06292"))
    }

    @Test
    fun `every emission constant matches the TypeScript`() {
        assertEquals(90, ConfettiSystem.BURST_COUNT)
        assertEquals(0.22, ConfettiSystem.GRAVITY)
        assertEquals(0.008, ConfettiSystem.LIFE_DECAY_PER_FRAME)
        assertEquals(0.42, ConfettiSystem.ORIGIN_Y_FRACTION)
        assertEquals(120.0, ConfettiSystem.SPREAD)
        assertEquals(4.0, ConfettiSystem.SPEED_FLOOR)
        assertEquals(7.0, ConfettiSystem.SPEED_SPAN)
        assertEquals(3.0, ConfettiSystem.LIFT)
        assertEquals(5.0, ConfettiSystem.SIZE_FLOOR)
        assertEquals(6.0, ConfettiSystem.SIZE_SPAN)
        assertEquals(6.28, ConfettiSystem.ROTATION_SPAN)
        assertEquals(0.4, ConfettiSystem.SPIN_SPAN)
        assertEquals(40.0, ConfettiSystem.CULL_MARGIN)
        assertEquals(0.6, ConfettiSystem.FLAKE_ASPECT)
        // 1 / 0.008 = 125 frames from full life to nothing.
        assertTrue(abs(1 / ConfettiSystem.LIFE_DECAY_PER_FRAME - 125) < 1e-9)
    }

    // --- fire ----------------------------------------------------------------

    @Test
    fun `fire emits exactly 90 fully-alive flakes`() {
        val confetti = system()
        confetti.fire()
        assertEquals(90, confetti.particles.size)
        assertTrue(confetti.particles.all { it.life == 1.0 })
        assertTrue(confetti.isRunning)
    }

    @Test
    fun `fire is a no-op under reduced motion - invariant 6`() {
        val confetti = system(reduceMotion = asleep)
        confetti.fire()
        confetti.fire()
        assertTrue(confetti.particles.isEmpty())
        assertFalse(confetti.isRunning, "no burst means no frame callback")
    }

    @Test
    fun `the reduce-motion gate is the one table, and it gates confetti`() {
        assertTrue(MotionSurface.CONFETTI.gatedByReduceMotion)
        assertTrue(Anim.shouldAnimate(MotionSurface.CONFETTI, awake))
        assertFalse(Anim.shouldAnimate(MotionSurface.CONFETTI, asleep))
    }

    @Test
    fun `fire is a no-op before the canvas has a size`() {
        // `if (!canvas) return` — the overlay has not been laid out yet.
        val confetti = ConfettiSystem(random = SeededGenerator(1L), reduceMotion = awake)
        confetti.fire()
        assertTrue(confetti.particles.isEmpty())
        assertFalse(confetti.isRunning)
    }

    @Test
    fun `every emitted flake lands inside the authored ranges, in device pixels`() {
        val dpr = 3.0
        val width = 390.0 * dpr
        val height = 620.0 * dpr
        val cx = width / 2
        val confetti = system(widthPx = width, heightPx = height, scale = dpr)
        confetti.fire()

        for (p in confetti.particles) {
            // x: cx + (Math.random() - 0.5) * 120 * dpr
            assertTrue(abs(p.x - cx) <= 60 * dpr)
            // y: canvas.height * 0.42
            assertEquals(height * 0.42, p.y)
            // g: 0.22 * dpr
            assertEquals(0.22 * dpr, p.g)
            // r: (5 + Math.random() * 6) * dpr
            assertTrue(p.r >= 5 * dpr)
            assertTrue(p.r < 11 * dpr)
            // rot: Math.random() * 6.28
            assertTrue(p.rot >= 0.0)
            assertTrue(p.rot < 6.28)
            // vr: (Math.random() - 0.5) * 0.4
            assertTrue(abs(p.vr) <= 0.2)
            assertTrue(p.colorIndex >= 0)
            assertTrue(p.colorIndex < ConfettiSystem.COLORS.size)

            // |(vx, vy + 3 dpr)| == sp, in [4 dpr, 11 dpr)
            val speed = sqrt(p.vx * p.vx + (p.vy + 3 * dpr) * (p.vy + 3 * dpr))
            assertTrue(speed >= 4 * dpr - 1e-9)
            assertTrue(speed < 11 * dpr)
        }
    }

    @Test
    fun `every flake is thrown upward`() {
        // a in [-pi, 0) makes sin(a) <= 0, and vy = sin(a) sp - 3 dpr, so no
        // flake can start by falling.
        val confetti = system(widthPx = 780.0, heightPx = 1240.0, scale = 2.0)
        confetti.fire()
        assertTrue(confetti.particles.all { it.vy <= -3 * 2.0 })
    }

    @Test
    fun `a seeded system replays exactly`() {
        val first = system(seed = 4242L)
        val second = system(seed = 4242L)
        first.fire()
        second.fire()
        assertEquals(first.particles, second.particles)

        repeat(30) {
            first.step(1860.0)
            second.step(1860.0)
        }
        assertEquals(first.particles, second.particles)
        assertTrue(first.particles.isNotEmpty())
    }

    @Test
    fun `a different seed gives a different burst`() {
        val first = system(seed = 1L)
        val second = system(seed = 2L)
        first.fire()
        second.fire()
        assertTrue(first.particles != second.particles)
    }

    // --- The loop -------------------------------------------------------------

    @Test
    fun `N frames integrate to the closed form of the rAF loop`() {
        // Tall enough that nothing is ever culled at the bottom.
        val height = 1_000_000.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        val start = confetti.particles.map { it.copy() }
        assertEquals(90, start.size)

        val frames = 17
        repeat(frames) { confetti.step(height) }
        assertEquals(90, confetti.particles.size)

        // Euler with `vy += g` BEFORE `y += vy` (the TS order) gives, after n
        // frames: vy = vy0 + n g, x = x0 + n vx, rot = rot0 + n vr and
        // y = y0 + n vy0 + g n (n + 1) / 2.
        val n = frames.toDouble()
        for ((before, after) in start.zip(confetti.particles)) {
            assertTrue(abs(after.vy - (before.vy + n * before.g)) < 1e-9)
            assertTrue(abs(after.x - (before.x + n * before.vx)) < 1e-9)
            assertTrue(abs(after.rot - (before.rot + n * before.vr)) < 1e-9)
            val y = before.y + n * before.vy + before.g * n * (n + 1) / 2
            assertTrue(abs(after.y - y) < 1e-6)
            assertTrue(abs(after.life - (1 - n * 0.008)) < 1e-12)
            // Untouched by the loop.
            assertEquals(before.vx, after.vx)
            assertEquals(before.g, after.g)
            assertEquals(before.r, after.r)
            assertEquals(before.colorIndex, after.colorIndex)
        }
    }

    @Test
    fun `life runs out after 125 frames at 0_008 a frame`() {
        val height = 10_000_000.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()

        repeat(124) { confetti.step(height) }
        // 1 - 124 x 0.008 = 0.008, still alive.
        assertEquals(90, confetti.particles.size)

        confetti.step(height)
        confetti.step(height)
        assertTrue(confetti.particles.isEmpty())
    }

    @Test
    fun `no survivor is ever below the bottom edge plus 40 device pixels`() {
        val height = 620.0   // dpr 1, so device pixels are points
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        repeat(124) {
            confetti.step(height)
            assertTrue(confetti.particles.all { it.y <= height + ConfettiSystem.CULL_MARGIN })
        }
    }

    @Test
    fun `the fall, not the lifetime, retires a flake on a short canvas`() {
        // 200 px tall: the burst clears the bottom edge long before the 125th
        // frame, so the population shrinks while every survivor is still alive.
        val height = 200.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        repeat(100) { confetti.step(height) }

        assertTrue(confetti.particles.size < 90, "the fall culled nobody")
        assertTrue(confetti.particles.isNotEmpty(), "the fall culled everybody")
        // 1 - 100 x 0.008 = 0.2: nothing here died of old age.
        assertTrue(confetti.particles.all { it.life > 0.19 })
    }

    @Test
    fun `survivors keep their emission order, so they keep their paint order`() {
        val height = 200.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        // `r` is drawn from a continuous range, so it identifies a flake.
        val emitted = confetti.particles.map { it.r }

        repeat(100) { confetti.step(height) }
        val survivors = confetti.particles.map { it.r }
        assertTrue(survivors.size < emitted.size)
        assertTrue(survivors.isNotEmpty())

        // The survivors must be a SUBSEQUENCE of the emission: descending
        // `splice` preserves the relative order of what is left, and so does
        // `removeAt`.
        var cursor = 0
        var matched = 0
        for (flake in survivors) {
            while (cursor < emitted.size) {
                val next = emitted[cursor]
                cursor += 1
                if (next == flake) {
                    matched += 1
                    break
                }
            }
        }
        assertEquals(survivors.size, matched)
    }

    @Test
    fun `the loop stops itself when the last flake is gone`() {
        val height = 10_000_000.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        assertTrue(confetti.isRunning)

        // Two seconds of wall clock in MAX_CATCH_UP-sized bites: far more than
        // the 125 frames a burst can live.
        var t = 0L
        confetti.advance(t, height)
        repeat(20) {
            t += 200_000_000L
            confetti.advance(t, height)
        }
        assertTrue(confetti.particles.isEmpty())
        assertFalse(confetti.isRunning, "an idle screen holds no frame callback")
    }

    @Test
    fun `a stall does not fast-forward the burst`() {
        val height = 10_000_000.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()
        confetti.advance(0L, height)                     // the first callback: 1 frame
        confetti.advance(60_000_000_000L, height)        // a minute in the background
        // The clamp is 0.25 s, so at most 15 more frames ran, not 3600.
        assertTrue(confetti.particles[0].life > 1 - 17 * 0.008)
    }

    @Test
    fun `a second burst joins the first instead of replacing it`() {
        val confetti = system()
        confetti.fire()
        confetti.step(1860.0)
        confetti.fire()
        assertEquals(180, confetti.particles.size)
        // ...and the new ones are the fresh ones.
        assertEquals(90, confetti.particles.count { it.life == 1.0 })
    }

    @Test
    fun `reset drops everything and stops`() {
        val confetti = system()
        confetti.fire()
        confetti.reset()
        assertTrue(confetti.particles.isEmpty())
        assertFalse(confetti.isRunning)
    }

    @Test
    fun `the clock steps at a fixed 60 Hz, not once per display frame`() {
        // The web integrates once per rAF callback. On a 120 Hz phone that
        // would run the burst at double speed, so the port pins the rate.
        assertTrue(abs(ConfettiSystem.FRAME_INTERVAL_SECONDS - 1.0 / 60.0) < 1e-12)

        val height = 10_000_000.0
        val confetti = system(heightPx = height, scale = 1.0)
        confetti.fire()

        var t = 0L
        confetti.advance(t, height)                  // the first callback: 1 frame
        // Ten frames' worth of wall clock, delivered as twenty 120 Hz ticks.
        repeat(20) {
            t += 8_333_333L
            confetti.advance(t, height)
        }
        // 1 + 10 frames of decay, not 1 + 20. (Nanosecond rounding can put the
        // count one frame either side; per-callback stepping would be ten out.)
        val life = confetti.particles[0].life
        assertTrue(life <= 1 - 10 * 0.008 + 1e-9)
        assertTrue(life >= 1 - 12 * 0.008 - 1e-9)
        assertTrue(life > 1 - 21 * 0.008, "one step per callback would have run 21 frames")
    }

    // --- Drawing math ----------------------------------------------------------

    @Test
    fun `a flake draws as the canvas fillRect, in pixels`() {
        val confetti = system(heightPx = 1860.0, scale = 3.0)
        confetti.fire()
        val p = confetti.particles[0]
        val flake = confetti.flake(p)

        // ctx.translate(p.x, p.y) — no division by the scale: a DrawScope is
        // already in the space the simulation runs in.
        assertEquals(p.x.toFloat(), flake.translateX)
        assertEquals(p.y.toFloat(), flake.translateY)
        // ctx.rotate(p.rot) — radians on the canvas, degrees in Compose.
        assertTrue(abs(flake.degrees - Math.toDegrees(p.rot).toFloat()) < 1e-3)
        // fillRect(-r/2, -r/2, r, r * 0.6): a wide, short bar, NOT centred
        // vertically on its own origin.
        assertEquals((-p.r / 2).toFloat(), flake.left)
        assertEquals((-p.r / 2).toFloat(), flake.top)
        assertEquals(p.r.toFloat(), flake.width)
        assertEquals((p.r * 0.6).toFloat(), flake.height)
        assertTrue(flake.height < flake.width)
        // globalAlpha = Math.max(0, p.life)
        assertEquals(1f, flake.alpha)
    }

    @Test
    fun `alpha is clamped at zero, never negative`() {
        val confetti = system(heightPx = 10_000_000.0, scale = 1.0)
        confetti.fire()
        val p = confetti.particles[0].copy(life = -0.5)
        assertEquals(0f, confetti.flake(p).alpha)
    }

    @Test
    fun `every colour index resolves to a parsed palette entry`() {
        val confetti = system()
        confetti.fire()
        val used = confetti.particles.map { confetti.colorOf(it) }.toSet()
        assertTrue(used.isNotEmpty())
        // The palette is parsed once, not per frame: the same index gives the
        // identical value object.
        for (p in confetti.particles) {
            assertEquals(confetti.colorOf(p), confetti.colorOf(p))
        }
    }

    // --- Invariant 2 ------------------------------------------------------------

    @Test
    fun `the particle buffer is not a snapshot object`() {
        // ARCHITECTURE.md section 3 row 2 names this test. A SnapshotStateList
        // or a mutableStateOf buffer would invalidate every reader sixty times
        // a second during the reward moment — the exact bug the file exists to
        // prevent. Structural, not a comment: the FIELD's type is asserted.
        val buffer = instanceFields(ConfettiSystem::class.java).single { it.name == "buffer" }
        assertEquals(ArrayList::class.java, buffer.type)
        assertFalse(State::class.java.isAssignableFrom(buffer.type))
        assertFalse(SnapshotStateList::class.java.isAssignableFrom(buffer.type))

        // ...and the live instance, in case the field is ever widened.
        val confetti = system()
        confetti.fire()
        val live: Any = confetti.particles
        assertTrue(live is ArrayList<*>, "the buffer became ${live.javaClass.name}")
        assertFalse(live is SnapshotStateList<*>)
    }

    @Test
    fun `the only hoisted state in the system is the run flag`() {
        val stateful = instanceFields(ConfettiSystem::class.java)
            .filter { State::class.java.isAssignableFrom(it.type) }
        assertEquals(
            listOf("runningState"),
            stateful.map { it.name },
            "a per-frame value became Compose state (invariant 2)",
        )
        assertTrue(MutableState::class.java.isAssignableFrom(stateful.single().type))
    }

    @Test
    fun `a particle is plain scalars, with nothing observable on it`() {
        for (field in instanceFields(ConfettiParticle::class.java)) {
            assertTrue(
                field.type.isPrimitive,
                "ConfettiParticle.${field.name} is ${field.type.simpleName}, not a scalar",
            )
        }
    }

    @Test
    fun `two grips on one system see one buffer`() {
        // The system is a reference type, so `remember`ing it and handing it to
        // the overlay shares the buffer instead of copying it per frame.
        val owner = system()
        val same = owner
        owner.fire()
        assertEquals(90, same.particles.size)

        same.step(1860.0)
        assertEquals(90, owner.particles.size)
        assertTrue(owner.particles[0].life < 1.0)
        assertEquals(same.particles[0], owner.particles[0])
    }
}
