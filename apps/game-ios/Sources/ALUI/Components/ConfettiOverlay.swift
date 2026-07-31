import Foundation
import Observation
import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* The celebration burst — port of `src/hooks/useConfetti.ts`.                 */
/*                                                                             */
/* INVARIANT 2 — animation stays off the render path. The web runs the whole   */
/* particle system on a canvas `requestAnimationFrame` loop that "never        */
/* touches React state, so celebrations cost nothing on the render path". The  */
/* Swift shape of that is a `TimelineView` whose only child is a LEAF `Canvas`,*/
/* with the particle buffer living in this REFERENCE type. Two things follow   */
/* and neither is optional:                                                    */
/*                                                                             */
/*   - the buffer is never `@State`. A 90-particle `@State` array mutated at   */
/*     60 Hz re-runs `body` (and every sibling's `body`) sixty times a second  */
/*     during the exact moment the child is being rewarded.                    */
/*   - the only OBSERVED property is `isRunning`, which flips twice per burst. */
/*     It gates the `TimelineView` so an idle screen holds no display link.    */
/*     `particles`, the clock and the accumulator are `@ObservationIgnored`;   */
/*     they are written from inside the `Canvas` renderer, where waking the    */
/*     view graph would defeat the purpose.                                    */
/*                                                                             */
/* INVARIANT 6 — `fire()` is a no-op under reduced motion, exactly as the web  */
/* returns early on `reduced.current`. The gate is on the injected             */
/* `ReduceMotionSource` (D14), not on a direct environment read.               */
/*                                                                             */
/* UNITS. The web simulates in DEVICE PIXELS: `canvas.width = offsetWidth *    */
/* dpr` and every authored constant is multiplied by `dpr` at emission —       */
/* speed, spread, gravity, size and the initial lift. This port keeps the      */
/* simulation in those same device pixels and divides by the scale once, when  */
/* drawing, so the numbers below are the TypeScript's numbers unchanged. It is */
/* not a wash: the ONE constant the web does NOT scale is the 40 px cull       */
/* margin below the bottom edge (`p.y > canvas.height + 40`), so a port that   */
/* "simplifies" to point space silently changes when a particle is retired.    */
/* -------------------------------------------------------------------------- */

/// One confetti flake. A value type held inside ``ConfettiSystem`` — the
/// reference type is the buffer's owner, not the flake (see the file header).
///
/// ```ts
/// interface Particle {
///   x: number; y: number; vx: number; vy: number; g: number;
///   r: number; rot: number; vr: number; life: number; color: string;
/// }
/// ```
public struct ConfettiParticle: Equatable, Sendable {
    /// Device pixels.
    public var x: Double
    public var y: Double
    /// Device pixels per frame.
    public var vx: Double
    public var vy: Double
    /// Device pixels per frame².
    public var g: Double
    /// The flake's side, device pixels.
    public var r: Double
    /// Radians.
    public var rot: Double
    /// Radians per frame.
    public var vr: Double
    /// 1 → 0. Also the alpha.
    public var life: Double
    /// Index into ``ConfettiSystem/colors``. The web stores the hex; an index
    /// keeps the particle `Equatable` on cheap scalars and the palette parsed
    /// once.
    public var colorIndex: Int

    public init(
        x: Double,
        y: Double,
        vx: Double,
        vy: Double,
        g: Double,
        r: Double,
        rot: Double,
        vr: Double,
        life: Double,
        colorIndex: Int
    ) {
        self.x = x
        self.y = y
        self.vx = vx
        self.vy = vy
        self.g = g
        self.r = r
        self.rot = rot
        self.vr = vr
        self.life = life
        self.colorIndex = colorIndex
    }
}

/// The particle buffer and the rAF loop, as a reference type.
///
/// Create ONE per exercise run (`useConfetti()` is called once per exercise
/// component), hand it to a ``ConfettiOverlay`` and call ``fire()`` from the
/// same handler that plays the cheer.
@MainActor
@Observable
public final class ConfettiSystem {

    /* ---- The authored constants, all from `useConfetti.ts` ---------------- */

    /// `const COLORS = [...]`. Five of the six are the pick-tile faces; the
    /// sixth (`#F06292`, pink) exists only here, which is why this list is not
    /// `Palette.tileColors`.
    public static let colors: [String] = [
        "#FF8A65",
        "#FFD54F",
        "#4FC3F7",
        "#AED581",
        "#BA9EE8",
        "#F06292",
    ]

    /// `for (let i = 0; i < 90; i++)`.
    public static let burstCount = 90

    /// `p.life -= 0.008` per frame — 125 frames from 1 to 0.
    public static let lifeDecayPerFrame = 0.008

    /// `g: 0.22 * dpr`.
    public static let gravity = 0.22

    /// `y: canvas.height * 0.42` — the burst's origin, a little above centre.
    public static let originYFraction = 0.42

    /// `x: cx + (Math.random() - 0.5) * 120 * dpr` — a 120 px-wide mouth.
    public static let spread = 120.0

    /// `sp = (4 + Math.random() * 7) * dpr`.
    public static let speedFloor = 4.0
    public static let speedSpan = 7.0

    /// `vy: Math.sin(a) * sp - 3 * dpr` — every flake is thrown upward.
    public static let lift = 3.0

    /// `r: (5 + Math.random() * 6) * dpr`.
    public static let sizeFloor = 5.0
    public static let sizeSpan = 6.0

    /// `rot: Math.random() * 6.28` — the TS writes 6.28, not `2 * Math.PI`.
    public static let rotationSpan = 6.28

    /// `vr: (Math.random() - 0.5) * 0.4`.
    public static let spinSpan = 0.4

    /// `p.y > canvas.height + 40` — device pixels, NOT scaled by dpr (see the
    /// file header).
    public static let cullMargin = 40.0

    /// The flake is drawn `fillRect(-r/2, -r/2, r, r * 0.6)` — a wide, short
    /// bar whose top edge sits at `-r/2`, so it is deliberately NOT centred
    /// vertically on its own origin. Copied as authored.
    public static let flakeAspect = 0.6

    /* ---- The clock ------------------------------------------------------- */

    /// The web integrates ONCE PER `requestAnimationFrame` CALLBACK — the
    /// physics is per-frame, not per-second, so on a 120 Hz display a naive
    /// port runs the burst at double speed. This port therefore steps at a
    /// FIXED 60 Hz off the timeline's clock, which is the rate the constants
    /// were authored against.
    public static let frameInterval: Double = 1.0 / 60.0

    /// A stall (backgrounded app, a slow first frame) must not fast-forward the
    /// burst by replaying half a second of physics in one go. The web has the
    /// same clamp for free — rAF simply does not fire while backgrounded.
    public static let maxCatchUp: Double = 0.25

    /* ---- State ----------------------------------------------------------- */

    /// The ONE observed property: it gates the `TimelineView`, and it changes
    /// twice per burst, never per frame.
    public private(set) var isRunning = false

    /// The particle buffer. `@ObservationIgnored` on purpose — see the header.
    @ObservationIgnored public private(set) var particles: [ConfettiParticle] = []

    @ObservationIgnored private let random: RandomSource
    @ObservationIgnored private let reduceMotion: ReduceMotionSource
    @ObservationIgnored private let palette: [Color]

    /// `canvas.offsetWidth/offsetHeight`, in POINTS. Published by the overlay.
    @ObservationIgnored public private(set) var size: CGSize = .zero

    /// `window.devicePixelRatio || 1`.
    @ObservationIgnored public private(set) var pixelScale: Double = 1

    @ObservationIgnored private var lastTick: Date?
    @ObservationIgnored private var carry: Double = 0

    public init(random: RandomSource = .system(), reduceMotion: ReduceMotionSource) {
        self.random = random
        self.reduceMotion = reduceMotion
        self.palette = Self.colors.map { Color(svgHex: $0) }
    }

    /* ---- Geometry, published by the overlay -------------------------------- */

    /// The web's `resize()` plus `devicePixelRatio`. Resizing does NOT disturb
    /// particles already in flight, exactly as re-assigning `canvas.width`
    /// leaves `partsRef` alone.
    public func report(size: CGSize, pixelScale: CGFloat) {
        self.size = size
        self.pixelScale = pixelScale > 0 ? Double(pixelScale) : 1
    }

    /// `canvas.width` — the backing store's width in device pixels.
    public var widthInDevicePixels: Double { Double(size.width) * pixelScale }

    /// `canvas.height`.
    public var heightInDevicePixels: Double { Double(size.height) * pixelScale }

    /* ---- fire ------------------------------------------------------------- */

    /// ```ts
    /// const fire = useCallback(() => {
    ///   const canvas = canvasRef.current;
    ///   if (!canvas || reduced.current) return;
    ///   …90 particles…
    ///   if (!rafRef.current) rafRef.current = requestAnimationFrame(loop);
    /// }, []);
    /// ```
    ///
    /// Note what it does NOT do: it never clears the buffer, so two cheers in
    /// quick succession overlap. Ported as authored.
    public func fire() {
        // `!canvas` — nothing to burst into before the overlay has been laid
        // out; and `reduced.current` (invariant 6).
        guard !reduceMotion.isReduced else { return }
        guard size.width > 0, size.height > 0 else { return }

        let dpr = pixelScale
        let width = widthInDevicePixels
        let height = heightInDevicePixels
        let cx = width / 2

        particles.reserveCapacity(particles.count + Self.burstCount)
        for _ in 0..<Self.burstCount {
            // Draw order matters for reproducibility under a seed: it is the
            // TS's order — a, sp, then the object literal top to bottom.
            let a = unitRandom() * .pi - .pi
            let sp = (Self.speedFloor + unitRandom() * Self.speedSpan) * dpr
            particles.append(
                ConfettiParticle(
                    x: cx + (unitRandom() - 0.5) * Self.spread * dpr,
                    y: height * Self.originYFraction,
                    vx: cos(a) * sp,
                    vy: sin(a) * sp - Self.lift * dpr,
                    g: Self.gravity * dpr,
                    r: (Self.sizeFloor + unitRandom() * Self.sizeSpan) * dpr,
                    rot: unitRandom() * Self.rotationSpan,
                    vr: (unitRandom() - 0.5) * Self.spinSpan,
                    life: 1,
                    colorIndex: Int(unitRandom() * Double(Self.colors.count))
                )
            )
        }

        // `if (!rafRef.current) requestAnimationFrame(loop)` — a burst that
        // lands mid-flight joins the running loop and does not restart its
        // clock.
        if !isRunning {
            lastTick = nil
            carry = 0
            isRunning = true
        }
    }

    /* ---- The loop ---------------------------------------------------------- */

    /// One `requestAnimationFrame` callback's worth of physics.
    ///
    /// ```ts
    /// for (let i = parts.length - 1; i >= 0; i--) {
    ///   const p = parts[i];
    ///   p.vy += p.g; p.x += p.vx; p.y += p.vy; p.rot += p.vr; p.life -= 0.008;
    ///   if (p.life <= 0 || p.y > canvas.height + 40) { parts.splice(i, 1); continue; }
    ///   …draw…
    /// }
    /// ```
    ///
    /// Descending, splicing in place — so the survivors keep their relative
    /// order and therefore their paint order.
    func step(canvasHeightInDevicePixels height: Double) {
        var i = particles.count - 1
        while i >= 0 {
            particles[i].vy += particles[i].g
            particles[i].x += particles[i].vx
            particles[i].y += particles[i].vy
            particles[i].rot += particles[i].vr
            particles[i].life -= Self.lifeDecayPerFrame
            if particles[i].life <= 0 || particles[i].y > height + Self.cullMargin {
                particles.remove(at: i)
            }
            i -= 1
        }
    }

    /// Advance the simulation to `date`, in whole 60 Hz frames.
    ///
    /// Called from inside the `Canvas` renderer, which is why it touches no
    /// observed property (invariant 2).
    func advance(to date: Date, canvasSize: CGSize) {
        let height = Double(canvasSize.height) * pixelScale

        guard let last = lastTick else {
            // The very first callback after `requestAnimationFrame(loop)`:
            // the web integrates one frame before it draws anything.
            lastTick = date
            step(canvasHeightInDevicePixels: height)
            settleIfIdle()
            return
        }

        lastTick = date
        let elapsed = Swift.min(Swift.max(0, date.timeIntervalSince(last)), Self.maxCatchUp) + carry
        let frames = Int(elapsed / Self.frameInterval)
        carry = elapsed - Double(frames) * Self.frameInterval
        for _ in 0..<frames {
            step(canvasHeightInDevicePixels: height)
        }
        settleIfIdle()
    }

    /// `rafRef.current = parts.length ? requestAnimationFrame(loop) : 0` — the
    /// loop stops itself when the last flake is gone.
    ///
    /// The flip is deferred one turn because `advance` runs inside the render
    /// pass and `isRunning` is observed: mutating it there would be a write to
    /// view state during a view update.
    private func settleIfIdle() {
        guard isRunning, particles.isEmpty else { return }
        lastTick = nil
        carry = 0
        Task { @MainActor [weak self] in
            guard let self, self.particles.isEmpty else { return }
            self.isRunning = false
        }
    }

    /// Drop everything and stop, without waiting for the flakes to fall. Used
    /// when a run is abandoned mid-cheer.
    public func reset() {
        particles.removeAll(keepingCapacity: true)
        lastTick = nil
        carry = 0
        isRunning = false
    }

    /* ---- Drawing ------------------------------------------------------------ */

    /// ```ts
    /// ctx.save();
    /// ctx.globalAlpha = Math.max(0, p.life);
    /// ctx.translate(p.x, p.y);
    /// ctx.rotate(p.rot);
    /// ctx.fillStyle = p.color;
    /// ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 0.6);
    /// ctx.restore();
    /// ```
    ///
    /// `ctx.save()/restore()` around each flake is a copy of the context here —
    /// `GraphicsContext` is a value type, so a local copy IS the saved state.
    func draw(into context: inout GraphicsContext) {
        let dpr = pixelScale
        for particle in particles {
            var flake = context
            flake.opacity = Swift.max(0, particle.life)
            flake.translateBy(x: particle.x / dpr, y: particle.y / dpr)
            flake.rotate(by: .radians(particle.rot))
            let side = particle.r / dpr
            flake.fill(
                Path(
                    CGRect(
                        x: -side / 2,
                        y: -side / 2,
                        width: side,
                        height: side * Self.flakeAspect
                    )
                ),
                with: .color(palette[particle.colorIndex])
            )
        }
    }

    /* ---- Randomness ---------------------------------------------------------- */

    /// `Math.random()` — uniform in [0, 1).
    ///
    /// `RandomSource` exposes integers, not doubles, so this draws 53 bits (the
    /// double mantissa) and divides. `int(below:)` with a power-of-two bound
    /// never rejects, so this consumes EXACTLY one 64-bit draw per call and a
    /// seeded run is reproducible.
    private func unitRandom() -> Double {
        Double(random.int(below: 1 << 53)) / Double(1 << 53)
    }
}

/* -------------------------------------------------------------------------- */
/* The view.                                                                   */
/* -------------------------------------------------------------------------- */

/// ```tsx
/// <canvas
///   ref={canvasRef}
///   className="pointer-events-none absolute inset-0 h-full w-full"
///   style={{ zIndex: 40 }}
/// />
/// ```
///
/// The z-order and the full-bleed placement belong to `GameFrame`; this view is
/// only the canvas. It fills whatever it is given, never takes a touch, and is
/// invisible to VoiceOver.
///
/// The `TimelineView` exists ONLY while a burst is in flight. Everything inside
/// it is a leaf `Canvas`: no state, no sibling, nothing to re-evaluate.
public struct ConfettiOverlay: View {
    private let system: ConfettiSystem

    @Environment(\.displayScale) private var displayScale

    public init(system: ConfettiSystem) {
        self.system = system
    }

    public var body: some View {
        GeometryReader { proxy in
            ZStack {
                // Always present, so the system knows the canvas geometry
                // BEFORE the first `fire()` — the web reads `canvas.offsetWidth`
                // on mount, not on demand.
                Color.clear

                if system.isRunning {
                    TimelineView(.animation) { timeline in
                        Canvas { context, canvasSize in
                            system.advance(to: timeline.date, canvasSize: canvasSize)
                            var ctx = context
                            system.draw(into: &ctx)
                        }
                    }
                }
            }
            .onAppear { system.report(size: proxy.size, pixelScale: displayScale) }
            .onChange(of: proxy.size) { _, newSize in
                system.report(size: newSize, pixelScale: displayScale)
            }
            .onChange(of: displayScale) { _, newScale in
                system.report(size: proxy.size, pixelScale: newScale)
            }
        }
        .allowsHitTesting(false)   // pointer-events-none
        .accessibilityHidden(true)
    }
}
