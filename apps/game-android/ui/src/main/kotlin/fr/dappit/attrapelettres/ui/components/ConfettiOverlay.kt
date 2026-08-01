package fr.dappit.attrapelettres.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.design.HexColor
import fr.dappit.attrapelettres.ui.interaction.Anim
import fr.dappit.attrapelettres.ui.interaction.MotionSurface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// The celebration burst — port of `src/hooks/useConfetti.ts`, by way of the
// iOS `Components/ConfettiOverlay.swift`.
//
// INVARIANT 2, IN ITS STRICTEST FORM. The web runs the whole particle system on
// a canvas `requestAnimationFrame` loop that "never touches React state, so
// celebrations cost nothing on the render path". The Compose shape of that
// sentence is three rules, none of them optional:
//
//   - the particle buffer is a PLAIN `ArrayList` on a plain class. Never a
//     `SnapshotStateList`, never `mutableStateOf`. Ninety particles mutated at
//     60 Hz through a snapshot object would invalidate every reader sixty times
//     a second, during the exact moment the child is being rewarded. That is
//     the bug this file exists to make impossible, and `ConfettiTest` asserts
//     the field's type rather than trusting this comment.
//   - the frame loop is `withFrameNanos` inside a `LaunchedEffect`, and the
//     only snapshot value it writes is a frame counter that is READ INSIDE THE
//     `Canvas` DRAW LAMBDA. A draw-phase read invalidates drawing only — the
//     same deferred-read trick `Modifier.graphicsLayer` uses for the tile
//     press. Nothing recomposes, nothing re-measures.
//   - the ONE piece of hoisted state is `isRunning`, which flips twice per
//     burst and never per frame. It gates the `LaunchedEffect`, so an idle
//     screen holds no frame callback at all.
//
// INVARIANT 6 — `fire()` is a no-op under reduced motion, exactly as the web
// returns early on `reduced.current`. The gate goes through the one table,
// `MotionSurface.CONFETTI`, not through a second opinion.
//
// UNITS. The web simulates in DEVICE PIXELS: `canvas.width = offsetWidth * dpr`
// and every authored constant is multiplied by `dpr` at emission — speed,
// spread, gravity, size and the initial lift. A Compose `DrawScope` is ALREADY
// in raw pixels, so the simulation and the drawing share one space and the
// numbers below are the TypeScript's numbers unchanged. `pixelScale` is still
// carried because it is a real term in the emission formulas, not a unit
// conversion: it is what makes the burst as wide and as fast on a 3x phone as
// it looks in a browser at dpr 3. The one constant the web does NOT scale is
// the 40 px cull margin below the bottom edge (`p.y > canvas.height + 40`),
// which is why a port that "simplifies" the scale away silently changes when a
// particle is retired.
// ---------------------------------------------------------------------------

/**
 * One confetti flake.
 *
 * ```ts
 * interface Particle {
 *   x: number; y: number; vx: number; vy: number; g: number;
 *   r: number; rot: number; vr: number; life: number; color: string;
 * }
 * ```
 *
 * `Double`, not `Float`: a JS number is a double, the closed-form integration
 * test compares 125 frames of Euler accumulation against it, and the values are
 * narrowed to `Float` once, at the draw call. Mutable `var`s on purpose — the
 * loop rewrites the same ninety objects in place, allocating nothing per frame.
 */
data class ConfettiParticle(
    /** Device pixels. */
    var x: Double,
    var y: Double,
    /** Device pixels per frame. */
    var vx: Double,
    var vy: Double,
    /** Device pixels per frame squared. */
    var g: Double,
    /** The flake's side, device pixels. */
    var r: Double,
    /** Radians. */
    var rot: Double,
    /** Radians per frame. */
    var vr: Double,
    /** 1 to 0. Also the alpha. */
    var life: Double,
    /**
     * Index into [ConfettiSystem.COLORS]. The web stores the hex string; an
     * index keeps the particle comparable on cheap scalars and the palette
     * parsed exactly once.
     */
    var colorIndex: Int,
)

/**
 * Everything the draw call needs for one flake, as plain numbers.
 *
 * Split out of the `DrawScope` extension so the drawing MATH is host-testable:
 * a `DrawScope` cannot be built without Android, but this can.
 *
 * ```ts
 * ctx.globalAlpha = Math.max(0, p.life);
 * ctx.translate(p.x, p.y);
 * ctx.rotate(p.rot);
 * ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 0.6);
 * ```
 */
data class FlakeDraw(
    val translateX: Float,
    val translateY: Float,
    val degrees: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val colorIndex: Int,
)

/**
 * The particle buffer and the rAF loop, as a plain class.
 *
 * Create ONE per exercise run (`useConfetti()` is called once per exercise
 * component) with [rememberConfettiSystem], hand it to a [ConfettiOverlay] and
 * call [fire] from the same handler that plays the cheer.
 *
 * `@Stable` is honest here, and it is what lets the overlay skip: the only
 * property a COMPOSITION ever reads is [isRunning], and that one is backed by
 * snapshot state. Everything else — the buffer, the geometry, the clock — is
 * read from the frame loop and the draw lambda, which is precisely the point.
 */
@Stable
class ConfettiSystem(
    private val random: RandomSource = SystemRandomSource(),
    private val reduceMotion: ReduceMotionSource,
) {

    companion object {

        // ---- The authored constants, all from `useConfetti.ts` -------------

        /**
         * `const COLORS = [...]`. Five of the six are the pick-tile faces; the
         * sixth (`#F06292`, pink) exists only here, which is why this list is
         * not `Palette.tileColors`.
         */
        val COLORS: List<String> = listOf(
            "#FF8A65",
            "#FFD54F",
            "#4FC3F7",
            "#AED581",
            "#BA9EE8",
            "#F06292",
        )

        /** `for (let i = 0; i < 90; i++)`. */
        const val BURST_COUNT = 90

        /** `p.life -= 0.008` per frame — 125 frames from 1 to nothing. */
        const val LIFE_DECAY_PER_FRAME = 0.008

        /** `g: 0.22 * dpr`. */
        const val GRAVITY = 0.22

        /** `y: canvas.height * 0.42` — the origin, a little above centre. */
        const val ORIGIN_Y_FRACTION = 0.42

        /** `x: cx + (Math.random() - 0.5) * 120 * dpr` — a 120 px-wide mouth. */
        const val SPREAD = 120.0

        /** `sp = (4 + Math.random() * 7) * dpr`. */
        const val SPEED_FLOOR = 4.0
        const val SPEED_SPAN = 7.0

        /** `vy: Math.sin(a) * sp - 3 * dpr` — every flake is thrown upward. */
        const val LIFT = 3.0

        /** `r: (5 + Math.random() * 6) * dpr`. */
        const val SIZE_FLOOR = 5.0
        const val SIZE_SPAN = 6.0

        /** `rot: Math.random() * 6.28` — the TS writes 6.28, not `2 * PI`. */
        const val ROTATION_SPAN = 6.28

        /** `vr: (Math.random() - 0.5) * 0.4`. */
        const val SPIN_SPAN = 0.4

        /**
         * `p.y > canvas.height + 40` — device pixels, NOT scaled by dpr (see
         * the UNITS note in the file header).
         */
        const val CULL_MARGIN = 40.0

        /**
         * The flake is drawn `fillRect(-r/2, -r/2, r, r * 0.6)` — a wide, short
         * bar whose top edge sits at `-r/2`, so it is deliberately NOT centred
         * vertically on its own origin. Copied as authored.
         */
        const val FLAKE_ASPECT = 0.6

        // ---- The clock -----------------------------------------------------

        /**
         * The web integrates ONCE PER `requestAnimationFrame` CALLBACK — the
         * physics is per-frame, not per-second, so on a 120 Hz phone a naive
         * port runs the whole burst at double speed. This port therefore steps
         * at a FIXED 60 Hz off the frame clock, the rate the constants above
         * were authored against.
         */
        const val FRAME_INTERVAL_SECONDS = 1.0 / 60.0

        /**
         * A stall (a backgrounded app, a slow first frame) must not fast-forward
         * the burst by replaying half a second of physics in one go. The web
         * gets the same clamp for free: rAF does not fire while backgrounded.
         */
        const val MAX_CATCH_UP_SECONDS = 0.25

        /**
         * `Math.random()`'s mantissa, as many bits as one [RandomSource] draw
         * can portably carry. A power of two, so `SeededGenerator`'s rejection
         * threshold is exactly zero and a call consumes EXACTLY ONE 64-bit draw
         * — which is what makes a seeded burst replay flake for flake.
         */
        const val UNIT_BITS = 1 shl 30
    }

    // ---- State -------------------------------------------------------------

    /**
     * The particle buffer. A plain [ArrayList], declared as one so that the
     * proving test can read the field's TYPE. See the invariant 2 note in the
     * header; this is the field it is about.
     */
    private val buffer: ArrayList<ConfettiParticle> = ArrayList(BURST_COUNT * 2)

    /** Read-only view for the overlay and the tests. Never copies. */
    val particles: List<ConfettiParticle> get() = buffer

    /**
     * The ONE hoisted value in the file. It gates the frame loop, it changes
     * twice per burst, and it is never written per frame.
     */
    private val runningState = mutableStateOf(false)

    val isRunning: Boolean get() = runningState.value

    /** The parsed palette, once. */
    private val palette: List<Color> = COLORS.map { HexColor(it).color }

    /** `canvas.width` — the backing store's width, device pixels. */
    var widthInPixels: Double = 0.0
        private set

    /** `canvas.height`. */
    var heightInPixels: Double = 0.0
        private set

    /** `window.devicePixelRatio || 1`. */
    var pixelScale: Double = 1.0
        private set

    private var lastTickNanos: Long? = null
    private var carrySeconds: Double = 0.0

    // ---- Geometry, published by the overlay ---------------------------------

    /**
     * The web's `resize()` plus `devicePixelRatio`, in one call. Resizing does
     * NOT disturb particles already in flight, exactly as re-assigning
     * `canvas.width` leaves `partsRef` alone.
     *
     * [width] and [height] are RAW PIXELS, which is what `onSizeChanged` and
     * `DrawScope.size` both already speak.
     */
    fun report(width: Float, height: Float, pixelScale: Float) {
        widthInPixels = width.toDouble()
        heightInPixels = height.toDouble()
        this.pixelScale = if (pixelScale > 0f) pixelScale.toDouble() else 1.0
    }

    // ---- fire ---------------------------------------------------------------

    /**
     * ```ts
     * const fire = useCallback(() => {
     *   const canvas = canvasRef.current;
     *   if (!canvas || reduced.current) return;
     *   ...90 particles...
     *   if (!rafRef.current) rafRef.current = requestAnimationFrame(loop);
     * }, []);
     * ```
     *
     * Note what it does NOT do: it never clears the buffer, so two cheers in
     * quick succession overlap. Ported as authored.
     */
    fun fire() {
        // `reduced.current` — invariant 6, through the one gate table.
        if (!Anim.shouldAnimate(MotionSurface.CONFETTI, reduceMotion)) return
        // `!canvas` — nothing to burst into before the overlay is laid out.
        if (widthInPixels <= 0.0 || heightInPixels <= 0.0) return

        val dpr = pixelScale
        val cx = widthInPixels / 2
        buffer.ensureCapacity(buffer.size + BURST_COUNT)
        repeat(BURST_COUNT) {
            // Draw order matters for reproducibility under a seed, so it is the
            // TS's order: a, sp, then the object literal top to bottom.
            val a = unitRandom() * Math.PI - Math.PI
            val sp = (SPEED_FLOOR + unitRandom() * SPEED_SPAN) * dpr
            buffer.add(
                ConfettiParticle(
                    x = cx + (unitRandom() - 0.5) * SPREAD * dpr,
                    y = heightInPixels * ORIGIN_Y_FRACTION,
                    vx = cos(a) * sp,
                    vy = sin(a) * sp - LIFT * dpr,
                    g = GRAVITY * dpr,
                    r = (SIZE_FLOOR + unitRandom() * SIZE_SPAN) * dpr,
                    rot = unitRandom() * ROTATION_SPAN,
                    vr = (unitRandom() - 0.5) * SPIN_SPAN,
                    life = 1.0,
                    colorIndex = (unitRandom() * COLORS.size).toInt(),
                )
            )
        }

        // `if (!rafRef.current) requestAnimationFrame(loop)` — a burst that
        // lands mid-flight joins the running loop and does not restart its
        // clock.
        if (!runningState.value) {
            lastTickNanos = null
            carrySeconds = 0.0
            runningState.value = true
        }
    }

    // ---- The loop ------------------------------------------------------------

    /**
     * One `requestAnimationFrame` callback's worth of physics.
     *
     * ```ts
     * for (let i = parts.length - 1; i >= 0; i--) {
     *   const p = parts[i];
     *   p.vy += p.g; p.x += p.vx; p.y += p.vy; p.rot += p.vr; p.life -= 0.008;
     *   if (p.life <= 0 || p.y > canvas.height + 40) { parts.splice(i, 1); continue; }
     *   ...draw...
     * }
     * ```
     *
     * Descending, splicing in place — so the survivors keep their relative
     * order and therefore their paint order.
     *
     * Public because a host test drives it directly; the overlay only ever
     * calls [advance].
     */
    fun step(heightPx: Double) {
        var i = buffer.size - 1
        while (i >= 0) {
            val p = buffer[i]
            p.vy += p.g
            p.x += p.vx
            p.y += p.vy
            p.rot += p.vr
            p.life -= LIFE_DECAY_PER_FRAME
            if (p.life <= 0.0 || p.y > heightPx + CULL_MARGIN) {
                buffer.removeAt(i)
            }
            i -= 1
        }
    }

    /**
     * Advance the simulation to [frameTimeNanos], in whole 60 Hz frames.
     *
     * Called from inside `withFrameNanos`, which is why it writes no snapshot
     * value except the twice-per-burst [isRunning] flip (invariant 2). The
     * frame callback runs before composition, so that write is legal there —
     * unlike iOS, which had to defer the same flip off the render pass.
     */
    fun advance(frameTimeNanos: Long, heightPx: Double) {
        val last = lastTickNanos
        if (last == null) {
            // The very first callback after `requestAnimationFrame(loop)`: the
            // web integrates one frame before it draws anything.
            lastTickNanos = frameTimeNanos
            step(heightPx)
            settleIfIdle()
            return
        }

        lastTickNanos = frameTimeNanos
        val delta = (frameTimeNanos - last).toDouble() / 1_000_000_000.0
        val elapsed = min(max(0.0, delta), MAX_CATCH_UP_SECONDS) + carrySeconds
        val frames = (elapsed / FRAME_INTERVAL_SECONDS).toInt()
        carrySeconds = elapsed - frames * FRAME_INTERVAL_SECONDS
        repeat(frames) { step(heightPx) }
        settleIfIdle()
    }

    /**
     * `rafRef.current = parts.length ? requestAnimationFrame(loop) : 0` — the
     * loop stops itself when the last flake is gone.
     */
    private fun settleIfIdle() {
        if (!runningState.value || buffer.isNotEmpty()) return
        lastTickNanos = null
        carrySeconds = 0.0
        runningState.value = false
    }

    /**
     * Drop everything and stop, without waiting for the flakes to fall. Used
     * when a run is abandoned mid-cheer.
     */
    fun reset() {
        buffer.clear()
        lastTickNanos = null
        carrySeconds = 0.0
        runningState.value = false
    }

    // ---- Drawing --------------------------------------------------------------

    /** The colour a flake paints with. */
    fun colorOf(particle: ConfettiParticle): Color = palette[particle.colorIndex]

    /**
     * The pure geometry of one flake, in pixels. No division by the scale: a
     * `DrawScope` is already in the space the simulation runs in (see UNITS).
     */
    fun flake(particle: ConfettiParticle): FlakeDraw {
        val side = particle.r
        return FlakeDraw(
            translateX = particle.x.toFloat(),
            translateY = particle.y.toFloat(),
            degrees = Math.toDegrees(particle.rot).toFloat(),
            left = (-side / 2).toFloat(),
            top = (-side / 2).toFloat(),
            width = side.toFloat(),
            height = (side * FLAKE_ASPECT).toFloat(),
            alpha = max(0.0, particle.life).toFloat(),
            colorIndex = particle.colorIndex,
        )
    }

    // ---- Randomness ------------------------------------------------------------

    /**
     * `Math.random()` — uniform in [0, 1).
     *
     * [RandomSource] exposes integers, so this draws [UNIT_BITS] and divides.
     * The bound is a power of two, so `SeededGenerator` never rejects and a
     * call costs exactly one 64-bit draw: a seeded run is reproducible.
     */
    private fun unitRandom(): Double = random.next(UNIT_BITS).toDouble() / UNIT_BITS.toDouble()
}

// ---------------------------------------------------------------------------
// The view.
// ---------------------------------------------------------------------------

/**
 * One system per screen, surviving recomposition. [key] lets a caller start a
 * fresh system per run.
 */
@Composable
fun rememberConfettiSystem(
    reduceMotion: ReduceMotionSource,
    random: RandomSource = SystemRandomSource(),
    key: Any? = Unit,
): ConfettiSystem = remember(key) { ConfettiSystem(random = random, reduceMotion = reduceMotion) }

/**
 * ```tsx
 * <canvas
 *   ref={canvasRef}
 *   className="pointer-events-none absolute inset-0 h-full w-full"
 *   style={{ zIndex: 40 }}
 * />
 * ```
 *
 * The z-order and the full-bleed placement belong to `GameFrame`; this is only
 * the canvas, so the caller passes the sizing modifier (a `matchParentSize` in
 * the frame's `Box`). It never takes a touch — there is no `pointerInput` on it
 * at all, which is `pointer-events-none` without needing a flag — and it is
 * invisible to TalkBack.
 *
 * The frame loop exists ONLY while a burst is in flight, and its only snapshot
 * write is a counter read inside the draw lambda: a draw-phase read, so a frame
 * costs one re-draw of this leaf and zero recompositions (invariant 2).
 */
@Composable
fun ConfettiOverlay(system: ConfettiSystem, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val tick = remember(system) { mutableIntStateOf(0) }
    val running = system.isRunning

    LaunchedEffect(system, running) {
        if (!running) return@LaunchedEffect
        while (system.isRunning) {
            withFrameNanos { now ->
                system.advance(now, system.heightInPixels)
                tick.intValue += 1
            }
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { system.report(it.width.toFloat(), it.height.toFloat(), density) }
            .clearAndSetSemantics { },
    ) {
        // The deferred read: taken in the DRAW phase, so a frame costs one
        // re-draw of this leaf and zero recompositions. Written as a guard
        // rather than a bare statement so the read is unmistakably load-bearing
        // and no "unused expression" cleanup can ever delete it.
        if (tick.intValue >= 0) {
            drawConfetti(system)
        }
    }
}

/**
 * ```ts
 * ctx.save();
 * ctx.globalAlpha = Math.max(0, p.life);
 * ctx.translate(p.x, p.y);
 * ctx.rotate(p.rot);
 * ctx.fillStyle = p.color;
 * ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 0.6);
 * ctx.restore();
 * ```
 *
 * `withTransform` is the `save()`/`restore()` pair; the rotation pivots on the
 * ALREADY-TRANSLATED origin, which is what `translate` then `rotate` means in a
 * canvas context.
 */
fun DrawScope.drawConfetti(system: ConfettiSystem) {
    for (particle in system.particles) {
        val flake = system.flake(particle)
        withTransform({
            translate(flake.translateX, flake.translateY)
            rotate(flake.degrees, pivot = Offset.Zero)
        }) {
            drawRect(
                color = system.colorOf(particle),
                topLeft = Offset(flake.left, flake.top),
                size = Size(flake.width, flake.height),
                alpha = flake.alpha,
            )
        }
    }
}
