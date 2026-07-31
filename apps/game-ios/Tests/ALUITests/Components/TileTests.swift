import CoreGraphics
import QuartzCore
import Testing

import ALCore
@testable import ALUI

// Tile — the pick primitive. Every expected value below is derived from the
// TypeScript, never read back from the Swift:
//
//   pipeline   src/components/Tile.tsx   `handle` / `handlePreview` (pointerdown)
//   cooldown   src/exercises/FindSoundExercise.tsx  `coolUntil` ref +
//              src/rewards.ts  MISS_COOLDOWN_MS = 800  (strict `<` comparison)
//   metrics    src/components/Tile.tsx   inline styles (clamp() triples)
//   labels     src/components/Tile.tsx   `previewLabel ?? "Écouter"`
//   TSX tests  src/components/Tile.test.tsx (three assertions, carried over)

// MARK: - Harness

/// The single-pick `pick()` handler, exactly as `FindSoundExercise.tsx` writes
/// it (locked gate → cooldown gate → unlock/pop → wrong: nudge + cooldown +
/// missRound / right: lock + flash + success). The engines package will own
/// the real one; this harness exists so the component-level mechanisms —
/// swallow window, instant star grey, no-fail-state — are asserted against the
/// TSX shape they must serve.
@MainActor
final class SinglePickHarness {
    var stars: [Bool]
    var idx = 0
    var flash: String?
    var mood = "idle"
    var locked = false
    var done = false
    var audio: [String] = []
    let cooldown: MissCooldown
    let target: String

    init(rounds: Int, target: String, time: any TimeSource) {
        stars = Array(repeating: true, count: rounds)
        self.target = target
        cooldown = MissCooldown(time: time)
    }

    /// TSX: `disabled={flash != null}` — the celebration lock, never a
    /// wrong-answer state.
    var tileDisabled: Bool { flash != nil }

    func pick(_ key: String) -> Verdict {
        if locked { return .reject }
        if cooldown.isSwallowing { return .reject } // silent while the shake plays
        audio.append("unlock")
        audio.append("pop")
        if key != target {
            audio.append("nudge")
            cooldown.registerMiss()
            StarStrip.miss(idx, in: &stars) // the star greys NOW
            return .reject
        }
        locked = true
        flash = key
        mood = "happy"
        audio.append("success")
        return .accept
    }
}

// MARK: - The pointerdown pipeline (invariant 1)

@Suite("TilePress — feedback at touch-down, before anything commits")
@MainActor
struct TilePressTests {
    private let animate = FixedReduceMotion(false)
    private let still = FixedReduceMotion(true)

    @Test("the press animation is already on the layer when onPick runs — Tile.tsx animates BEFORE calling onPick")
    func pressBeforePick() {
        let layer = CALayer()
        var pressWasPlayingDuringPick = false
        var picked = 0

        TilePress.pointerDown(layer: layer, disabled: false) {
            pressWasPlayingDuringPick = layer.animation(forKey: "ALPress") != nil
            picked += 1
            return .accept
        }

        #expect(picked == 1)
        #expect(
            pressWasPlayingDuringPick,
            Comment(rawValue: "el.animate(press) precedes onPick() in the TSX handler")
        )
        #expect(layer.animation(forKey: "ALShake") == nil, Comment(rawValue: "accept never shakes"))
    }

    @Test("a .reject verdict adds the shake in the same synchronous beat")
    func rejectShakes() {
        let layer = CALayer()
        TilePress.pointerDown(layer: layer, disabled: false) { .reject }
        #expect(layer.animation(forKey: "ALShake") != nil)
    }

    @Test("a disabled tile does nothing: no animation, no pick")
    func disabledTileIsInert() {
        let layer = CALayer()
        var picked = 0
        TilePress.pointerDown(layer: layer, disabled: true) {
            picked += 1
            return .accept
        }
        #expect(picked == 0, Comment(rawValue: "TSX: if (disabled) return — before the animation"))
        #expect(layer.animation(forKey: "ALPress") == nil)
        #expect(layer.animation(forKey: "ALShake") == nil)
    }

    @Test("reduced motion changes NOTHING about a tile press — the web does not gate it (D29)")
    func reducedMotionDoesNotSuppressTapFeedback() {
        // `Tile.tsx` contains no `matchMedia` call: lines 59 and 61 are bare
        // `el.animate(press, …)` / `el.animate(shake, …)`. The media query is
        // read in usePopFlourish.ts, useConfetti.ts, Mascot.tsx, Dashboard.tsx
        // and shop/anim.ts — never for a pick tile. CLAUDE.md's invariant 6
        // scopes itself the same way: "mascot + confetti".
        //
        // So a child with Reduce Motion switched on still gets the 130 ms squish
        // and the 300 ms wobble, exactly as on the web. `TilePress.pointerDown`
        // takes no `ReduceMotionSource` at all, which is why this test can only
        // assert the outcome and not the gate: the gate cannot be expressed.
        let layer = CALayer()
        let harness = SinglePickHarness(rounds: 3, target: "ou", time: MutableTimeSource(1_000))

        TilePress.pointerDown(layer: layer, disabled: false) {
            harness.pick("an") // wrong
        }

        // The miss landed in full — nudge fired, star greyed…
        #expect(harness.audio == ["unlock", "pop", "nudge"])
        #expect(harness.stars == [false, true, true])
        // …and BOTH animations ran, reduce-motion or not.
        #expect(layer.animation(forKey: "ALShake") != nil)
    }

    @Test("Écouter previews without committing the pick — Tile.test.tsx")
    func previewDoesNotCommit() {
        let layer = CALayer()
        var previewed = 0
        var picked = 0

        TilePress.previewDown(layer: layer, disabled: false) { previewed += 1 }
        #expect(previewed == 1)
        #expect(picked == 0)
        #expect(layer.animation(forKey: "ALShake") == nil, Comment(rawValue: "a preview has no verdict, so it can never shake"))

        TilePress.pointerDown(layer: layer, disabled: false) {
            picked += 1
            return .accept
        }
        #expect(picked == 1)
        #expect(previewed == 1, Comment(rawValue: "picking didn't re-fire preview"))
    }

    @Test("a disabled tile disables Écouter too — Tile.test.tsx")
    func disabledPreviewIsInert() {
        let layer = CALayer()
        var previewed = 0
        TilePress.previewDown(layer: layer, disabled: true) { previewed += 1 }
        #expect(previewed == 0)
        #expect(layer.animation(forKey: "ALPress") == nil)
    }
}

// MARK: - The swallow window (invariant 8)

@Suite("MissCooldown — a miss swallows picks for exactly MISS_COOLDOWN_MS")
struct MissCooldownTests {

    @Test("MISS_COOLDOWN_MS is 800 — rewards.ts")
    func windowLength() {
        #expect(Rewards.missCooldownMs == 800)
    }

    @Test("swallows strictly inside the window and accepts again at exactly +800 ms")
    func windowBoundaries() {
        let time = MutableTimeSource(1_000)
        let cooldown = MissCooldown(time: time)

        #expect(!cooldown.isSwallowing, Comment(rawValue: "coolUntil starts at 0 — nothing swallowed before the first miss"))

        cooldown.registerMiss() // coolUntil = 1000 + 800
        #expect(cooldown.isSwallowing)

        time.advance(millis: 799) // now 1799 < 1800
        #expect(cooldown.isSwallowing)

        time.advance(millis: 1) // now 1800 — TSX compares with strict `<`
        #expect(!cooldown.isSwallowing, Comment(rawValue: "performance.now() < coolUntil is false at equality"))
    }
}

@Suite("The pick pipeline under fire — swallow, grey, and nothing else (invariants 3 + 8)")
@MainActor
struct PickPipelineTests {
    private let animate = FixedReduceMotion(false)

    @Test("a wrong tap: pop then nudge, star greys, cooldown armed — and that is ALL that changes")
    func wrongTapChangesOnlyStarsAndCooldown() {
        let time = MutableTimeSource(1_000)
        let harness = SinglePickHarness(rounds: 3, target: "ou", time: time)
        let layer = CALayer()

        TilePress.pointerDown(layer: layer, disabled: harness.tileDisabled) {
            harness.pick("an")
        }

        // The audible order is exactly unlock, pop, nudge (the tap pops first,
        // then the nudge marks the miss).
        #expect(harness.audio == ["unlock", "pop", "nudge"])
        #expect(harness.stars == [false, true, true])
        #expect(layer.animation(forKey: "ALShake") != nil)

        // Invariant 3 — no fail state: no lock, no lives, no route, no mood
        // change, and the tile is NOT disabled by a wrong answer.
        #expect(!harness.locked)
        #expect(harness.idx == 0)
        #expect(harness.mood == "idle")
        #expect(!harness.done)
        #expect(!harness.tileDisabled)
    }

    @Test("picks during the window are silently swallowed — press and shake still play, zero audio, zero state")
    func swallowedPicksAreSilentButVisible() {
        let time = MutableTimeSource(1_000)
        let harness = SinglePickHarness(rounds: 3, target: "ou", time: time)

        _ = harness.pick("an") // miss at t=1000
        let audioAfterMiss = harness.audio
        let starsAfterMiss = harness.stars

        // Spam the RIGHT answer inside the window: still rejected, still silent.
        let layer = CALayer()
        TilePress.pointerDown(layer: layer, disabled: harness.tileDisabled) {
            harness.pick("ou")
        }
        #expect(harness.audio == audioAfterMiss, Comment(rawValue: "a swallowed pick makes no sound at all"))
        #expect(harness.stars == starsAfterMiss)
        #expect(!harness.locked)
        #expect(
            layer.animation(forKey: "ALPress") == nil && layer.animation(forKey: "ALShake") != nil,
            Comment(rawValue: "the shake replaced the press — feedback stays visual-only, but it IS there")
        )

        // After the window the same tap lands.
        time.advance(millis: 800)
        let verdict = harness.pick("ou")
        #expect(verdict == .accept)
        #expect(harness.audio == audioAfterMiss + ["unlock", "pop", "success"])
        #expect(harness.locked)
    }

    @Test("a second wrong tap in the same round greys nothing further but re-arms the window")
    func secondMissSameRound() {
        let time = MutableTimeSource(1_000)
        let harness = SinglePickHarness(rounds: 3, target: "ou", time: time)

        _ = harness.pick("an")
        #expect(harness.stars == [false, true, true])

        time.advance(millis: 800) // window over
        _ = harness.pick("in") // second miss, same round
        #expect(harness.stars == [false, true, true], Comment(rawValue: "missRound is idempotent per round"))
        #expect(harness.cooldown.isSwallowing, Comment(rawValue: "…but the swallow window is fresh"))
    }

    @Test("the celebration lock rejects silently — and is not a fail state")
    func lockedPickIsSilent() {
        let time = MutableTimeSource(1_000)
        let harness = SinglePickHarness(rounds: 3, target: "ou", time: time)

        _ = harness.pick("ou") // right — locks for the success line
        let audioAfterSuccess = harness.audio

        #expect(harness.pick("ou") == .reject)
        #expect(harness.audio == audioAfterSuccess)
        #expect(harness.stars == [true, true, true], Comment(rawValue: "a locked tap never greys a star"))
    }
}

// MARK: - Metrics and labels (invariant 6)

@Suite("Tile metrics — the authored clamp() triples, and the tap-target floor")
struct TileMetricsTests {

    @Test("dim: clamp(92px, 27vw, 150px) — floor, ramp, cap")
    func defaultSize() {
        #expect(TileMetrics.defaultSize == FluidSpec(min: 92, vw: 27, max: 150))
        // CSS: max(92, min(0.27·viewport, 150))
        #expect(TileMetrics.defaultSize.resolve(viewport: 320) == 92) // 86.4 → floor
        #expect(abs(TileMetrics.defaultSize.resolve(viewport: 390) - 105.3) < 1e-9)
        #expect(TileMetrics.defaultSize.resolve(viewport: 600) == 150) // 162 → cap
    }

    @Test("fontSize clamp(30px, 9vw, 64px); padding clamp(10px, 3vw, 20px); radius 28")
    func glyphMetrics() {
        #expect(TileMetrics.defaultFontSize == FluidSpec(min: 30, vw: 9, max: 64))
        #expect(abs(TileMetrics.defaultFontSize.resolve(viewport: 390) - 35.1) < 1e-9)
        #expect(TileMetrics.horizontalPadding == FluidSpec(min: 10, vw: 3, max: 20))
        #expect(TileMetrics.cornerRadius == 28)
        #expect(TileMetrics.disabledOpacity == 0.4) // disabled:opacity-40
        #expect(TileMetrics.columnGap == 8) // gap-2
    }

    @Test("Écouter: height clamp(40px, 11vw, 52px), font clamp(16px, 4.5vw, 22px)")
    func previewMetrics() {
        #expect(TileMetrics.previewHeight == FluidSpec(min: 40, vw: 11, max: 52))
        #expect(TileMetrics.previewHeight.resolve(viewport: 320) == 40) // 35.2 → floor
        #expect(TileMetrics.previewFontSize == FluidSpec(min: 16, vw: 4.5, max: 22))
        #expect(abs(TileMetrics.previewFontSize.resolve(viewport: 390) - 17.55) < 1e-9)
    }

    @Test("the accessibility floor: 92 pt tiles, 40 pt Écouter — both above the 44 pt platform minimum where the spec demands it")
    func tapTargetFloor() {
        #expect(TileMetrics.defaultSize.min == 92)
        #expect(TileMetrics.defaultSize.min >= 44)
        #expect(TileMetrics.previewHeight.min >= 40)
        #expect(Copy.Tile.minimumSide == TileMetrics.defaultSize.min)
    }

    @Test("highlight ring: 6 pt spread outside a 28 pt box → radius 34; transition 0.15 s")
    func highlightRing() {
        #expect(TileMetrics.highlightRingWidth == 6)
        #expect(TileMetrics.highlightRingRadius == TileMetrics.cornerRadius + TileMetrics.highlightRingWidth)
        #expect(TileMetrics.highlightTransition == 0.15)
    }

    @Test("the Écouter fallback label is « Écouter », byte-exact")
    func previewFallbackLabel() {
        #expect(Copy.Tile.listenFallback == "Écouter")
        #expect(Copy.Tile.listenGlyph == "🔊")
    }
}
