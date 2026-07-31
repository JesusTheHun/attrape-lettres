import CoreGraphics
import Foundation
import Testing

import ALCore
@testable import ALUI

// The confetti particle system, asserted against `src/hooks/useConfetti.ts` —
// never against the Swift. Every number below was read out of that file:
//
//   COLORS      ["#FF8A65","#FFD54F","#4FC3F7","#AED581","#BA9EE8","#F06292"]
//   burst       for (let i = 0; i < 90; i++)
//   origin      x: cx + (Math.random() - 0.5) * 120 * dpr
//               y: canvas.height * 0.42
//   velocity    a  = Math.random() * Math.PI - Math.PI        // [-π, 0)
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
// explicit in every expectation here.

private let awake = FixedReduceMotion(false)
private let asleep = FixedReduceMotion(true)

@MainActor
private func system(
    seed: UInt64 = 20_260_729,
    reduceMotion: ReduceMotionSource = awake,
    width: CGFloat = 390,
    height: CGFloat = 620,
    scale: CGFloat = 3
) -> ConfettiSystem {
    let made = ConfettiSystem(random: .seeded(seed), reduceMotion: reduceMotion)
    made.report(size: CGSize(width: width, height: height), pixelScale: scale)
    return made
}

@Suite("Confetti — useConfetti.ts")
@MainActor
struct ConfettiTests {

    // MARK: - The authored constants

    @Test("COLORS is the hook's six, in order")
    func palette() {
        #expect(
            ConfettiSystem.colors == [
                "#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8", "#F06292",
            ]
        )
        // Five are the pick-tile faces; the pink is the confetti's own, which is
        // why this list is not `Palette.tileColors`.
        #expect(!Palette.tileColors.map(\.bg.hex).contains("#F06292"))
    }

    @Test("every emission constant matches the TypeScript")
    func constants() {
        #expect(ConfettiSystem.burstCount == 90)
        #expect(ConfettiSystem.gravity == 0.22)
        #expect(ConfettiSystem.lifeDecayPerFrame == 0.008)
        #expect(ConfettiSystem.originYFraction == 0.42)
        #expect(ConfettiSystem.spread == 120)
        #expect(ConfettiSystem.speedFloor == 4)
        #expect(ConfettiSystem.speedSpan == 7)
        #expect(ConfettiSystem.lift == 3)
        #expect(ConfettiSystem.sizeFloor == 5)
        #expect(ConfettiSystem.sizeSpan == 6)
        #expect(ConfettiSystem.rotationSpan == 6.28)
        #expect(ConfettiSystem.spinSpan == 0.4)
        #expect(ConfettiSystem.cullMargin == 40)
        #expect(ConfettiSystem.flakeAspect == 0.6)
        // 1 / 0.008 = 125 frames from full life to nothing.
        #expect(abs(1 / ConfettiSystem.lifeDecayPerFrame - 125) < 1e-9)
    }

    // MARK: - fire()

    @Test("fire emits exactly 90 fully-alive flakes")
    func burstSize() {
        let confetti = system()
        confetti.fire()
        #expect(confetti.particles.count == 90)
        #expect(confetti.particles.allSatisfy { $0.life == 1 })
        #expect(confetti.isRunning)
    }

    @Test("fire is a no-op under reduced motion — invariant 6")
    func reducedMotionGate() {
        let confetti = system(reduceMotion: asleep)
        confetti.fire()
        confetti.fire()
        #expect(confetti.particles.isEmpty)
        #expect(!confetti.isRunning, Comment(rawValue: "no burst means no display link"))
    }

    @Test("fire is a no-op before the canvas has a size — `if (!canvas) return`")
    func noCanvasGate() {
        let confetti = ConfettiSystem(random: .seeded(1), reduceMotion: awake)
        confetti.fire()
        #expect(confetti.particles.isEmpty)
        #expect(!confetti.isRunning)
    }

    @Test("every emitted flake lands inside the authored ranges, in device pixels")
    func emissionRanges() {
        let dpr = 3.0
        let width = 390.0 * dpr
        let height = 620.0 * dpr
        let cx = width / 2
        let confetti = system(scale: CGFloat(dpr))
        confetti.fire()

        for particle in confetti.particles {
            // x: cx + (Math.random() - 0.5) * 120 * dpr
            #expect(abs(particle.x - cx) <= 60 * dpr)
            // y: canvas.height * 0.42
            #expect(particle.y == height * 0.42)
            // g: 0.22 * dpr
            #expect(particle.g == 0.22 * dpr)
            // r: (5 + Math.random() * 6) * dpr
            #expect(particle.r >= 5 * dpr)
            #expect(particle.r < 11 * dpr)
            // rot: Math.random() * 6.28
            #expect(particle.rot >= 0)
            #expect(particle.rot < 6.28)
            // vr: (Math.random() - 0.5) * 0.4
            #expect(abs(particle.vr) <= 0.2)
            #expect(particle.colorIndex >= 0)
            #expect(particle.colorIndex < ConfettiSystem.colors.count)

            // |(vx, vy + 3·dpr)| == sp ∈ [4·dpr, 11·dpr)
            let speed = (particle.vx * particle.vx
                + (particle.vy + 3 * dpr) * (particle.vy + 3 * dpr)).squareRoot()
            #expect(speed >= 4 * dpr - 1e-9)
            #expect(speed < 11 * dpr)
        }
    }

    @Test("every flake is thrown upward — a ∈ [-π, 0) makes sin(a) ≤ 0")
    func upwardBurst() {
        let confetti = system(scale: 2)
        confetti.fire()
        // vy = sin(a)·sp − 3·dpr, and sin(a) ≤ 0 over the whole angle range, so
        // no flake can start by falling.
        #expect(confetti.particles.allSatisfy { $0.vy <= -3 * 2 })
    }

    @Test("a seeded system replays exactly")
    func reproducible() {
        let first = system(seed: 4242)
        let second = system(seed: 4242)
        first.fire()
        second.fire()
        #expect(first.particles == second.particles)

        for _ in 0..<30 {
            first.step(canvasHeightInDevicePixels: 1_860)
            second.step(canvasHeightInDevicePixels: 1_860)
        }
        #expect(first.particles == second.particles)
        #expect(!first.particles.isEmpty)
    }

    @Test("a different seed gives a different burst")
    func seedsDiverge() {
        let first = system(seed: 1)
        let second = system(seed: 2)
        first.fire()
        second.fire()
        #expect(first.particles != second.particles)
    }

    // MARK: - The loop

    @Test("N frames integrate to the closed form of the rAF loop")
    func integration() {
        // Tall enough that nothing is ever culled at the bottom.
        let height = 1_000_000.0
        let confetti = system(height: CGFloat(height), scale: 1)
        confetti.fire()
        let start = confetti.particles
        #expect(start.count == 90)

        let frames = 17
        for _ in 0..<frames { confetti.step(canvasHeightInDevicePixels: height) }
        #expect(confetti.particles.count == 90)

        // Euler with `vy += g` BEFORE `y += vy` (the TS order) gives, after n
        // frames: vy = vy₀ + n·g, x = x₀ + n·vx, rot = rot₀ + n·vr and
        // y = y₀ + n·vy₀ + g·n(n+1)/2.
        let n = Double(frames)
        for (before, after) in zip(start, confetti.particles) {
            #expect(abs(after.vy - (before.vy + n * before.g)) < 1e-9)
            #expect(abs(after.x - (before.x + n * before.vx)) < 1e-9)
            #expect(abs(after.rot - (before.rot + n * before.vr)) < 1e-9)
            let y = before.y + n * before.vy + before.g * n * (n + 1) / 2
            #expect(abs(after.y - y) < 1e-6)
            #expect(abs(after.life - (1 - n * 0.008)) < 1e-12)
            // Untouched by the loop.
            #expect(after.vx == before.vx)
            #expect(after.g == before.g)
            #expect(after.r == before.r)
            #expect(after.colorIndex == before.colorIndex)
        }
    }

    @Test("life runs out after 125 frames at 0.008 a frame")
    func lifetime() {
        let height = 10_000_000.0
        let confetti = system(height: CGFloat(height), scale: 1)
        confetti.fire()

        for _ in 0..<124 { confetti.step(canvasHeightInDevicePixels: height) }
        #expect(
            confetti.particles.count == 90,
            Comment(rawValue: "1 − 124 × 0.008 = 0.008, still alive")
        )

        confetti.step(canvasHeightInDevicePixels: height)
        confetti.step(canvasHeightInDevicePixels: height)
        #expect(confetti.particles.isEmpty)
    }

    @Test("a flake is culled once it passes the bottom edge plus 40 device pixels")
    func bottomCull() {
        let height = 620.0   // dpr 1, so device pixels == points
        let confetti = system(height: 620, scale: 1)
        confetti.fire()

        // Nothing survives below the cull line, at any point in the fall.
        for _ in 0..<80 {
            confetti.step(canvasHeightInDevicePixels: height)
            #expect(confetti.particles.allSatisfy { $0.y <= height + 40 })
        }
        // …and the fall, not the lifetime, is what took them: at frame 80 the
        // life left is 1 − 0.64 = 0.36.
        #expect(confetti.particles.count < 90)
        #expect(confetti.particles.allSatisfy { $0.life > 0.3 })
    }

    @Test("survivors keep their emission order, so they keep their paint order")
    func spliceOrder() {
        let height = 620.0
        let confetti = system(height: 620, scale: 1)
        confetti.fire()
        // `r` is drawn from a continuous range, so it identifies a flake.
        let emitted = confetti.particles.map(\.r)

        for _ in 0..<80 { confetti.step(canvasHeightInDevicePixels: height) }
        let survivors = confetti.particles.map(\.r)
        #expect(survivors.count < emitted.count)
        #expect(!survivors.isEmpty)
        // The survivors must be a SUBSEQUENCE of the emission: descending
        // `splice` preserves the relative order of what is left, and so does
        // `remove(at:)`.
        var cursor = emitted.makeIterator()
        var matched = 0
        for flake in survivors {
            while let next = cursor.next() {
                if next == flake { matched += 1; break }
            }
        }
        #expect(matched == survivors.count)
    }

    @Test("the loop stops itself when the last flake is gone")
    func loopStops() async throws {
        let height = 10_000_000.0
        let confetti = system(height: CGFloat(height), scale: 1)
        confetti.fire()
        #expect(confetti.isRunning)

        let now = Date()
        // Two seconds of wall clock in `maxCatchUp`-sized bites: more than the
        // 125 frames a burst can live.
        var t = now
        for _ in 0..<20 {
            t = t.addingTimeInterval(0.2)
            confetti.advance(to: t, canvasSize: CGSize(width: 390, height: CGFloat(height)))
        }
        #expect(confetti.particles.isEmpty)
        // The flip is deferred one turn (it happens inside the render pass).
        try await Task.sleep(nanoseconds: 20_000_000)
        #expect(!confetti.isRunning, Comment(rawValue: "an idle screen holds no display link"))
    }

    @Test("a second burst joins the first instead of replacing it")
    func refire() {
        let confetti = system()
        confetti.fire()
        confetti.step(canvasHeightInDevicePixels: 1_860)
        confetti.fire()
        #expect(confetti.particles.count == 180)
        // …and the new ones are the fresh ones.
        #expect(confetti.particles.filter { $0.life == 1 }.count == 90)
    }

    @Test("reset drops everything and stops")
    func reset() {
        let confetti = system()
        confetti.fire()
        confetti.reset()
        #expect(confetti.particles.isEmpty)
        #expect(!confetti.isRunning)
    }

    // MARK: - Invariant 2

    @Test("the particle buffer lives in a reference type, not in @State")
    func bufferIsAReferenceType() {
        // The mechanical guard for invariant 2: a value-type buffer could only
        // be driven from `@State`, which re-renders the tree 60 times a second
        // during the reward moment. Two grips on one system see ONE buffer.
        let metatype: Any.Type = ConfettiSystem.self
        #expect(metatype is AnyClass)

        let owner = system()
        let sameBuffer = owner
        owner.fire()
        #expect(sameBuffer.particles.count == 90)

        sameBuffer.step(canvasHeightInDevicePixels: 1_860)
        #expect(owner.particles.count == 90)
        #expect(owner.particles[0].life < 1)
        #expect(owner.particles[0] == sameBuffer.particles[0])
    }

    @Test("the clock steps at a fixed 60 Hz, not once per display frame")
    func fixedTimestep() {
        // The web integrates once per rAF callback. On a 120 Hz iPhone that
        // would run the burst at double speed, so the port pins the rate.
        #expect(abs(ConfettiSystem.frameInterval - 1.0 / 60.0) < 1e-12)

        let height = 10_000_000.0
        let confetti = system(height: CGFloat(height), scale: 1)
        confetti.fire()
        let canvas = CGSize(width: 390, height: CGFloat(height))

        var t = Date()
        confetti.advance(to: t, canvasSize: canvas)          // the first callback: 1 frame
        // Ten frames' worth of wall clock, delivered as twenty 120 Hz ticks.
        for _ in 0..<20 {
            t = t.addingTimeInterval(1.0 / 120.0)
            confetti.advance(to: t, canvasSize: canvas)
        }
        // 1 + 10 frames of decay, not 1 + 20. (Wall-clock rounding can put the
        // count one frame either side; per-callback stepping would be ten out.)
        let life = confetti.particles[0].life
        #expect(life <= 1 - 10 * 0.008 + 1e-9)
        #expect(life >= 1 - 12 * 0.008 - 1e-9)
        #expect(
            life > 1 - 21 * 0.008,
            Comment(rawValue: "one step per callback would have run 21 frames")
        )
    }
}
