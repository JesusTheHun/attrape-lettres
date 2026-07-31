import Foundation
import Testing

@testable import ALPlatform

// The four SFX, against `blip()` in `src/hooks/useAudio.ts`.
//
// Every expected number is arithmetic on the TS's own arguments:
//
//   blip(freq, dur, type, gain, at):
//     g.gain.setValueAtTime(0.0001, t0)
//     g.gain.exponentialRampToValueAtTime(gain,   t0 + 0.008)
//     g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur)
//     osc.start(t0); osc.stop(t0 + dur + 0.02)
//
//   pop     = blip(660,    0.09, triangle, 0.16)
//   success = [523.25, 659.25, 783.99, 1046.5] × blip(f, 0.16, sine, 0.16, i * 0.075)
//   oops    = blip(392,    0.18, sine,     0.13, 0) + blip(311.13, 0.28, sine, 0.13, 0.16)
//
// …except `nudge`, which the web authors as `blip(196, 0.14, sine, 0.10)` and
// which no phone speaker reproduces. Its assertions below are against the
// native recipe and its REASONS (in band, clear of the pop, softest in the
// game), not against the TS.

@Suite("SFX synthesis")
struct AudioSfxTests {

    @Test("buffer length is (last offset + duration + 0.02) seconds")
    func durations() {
        #expect(SfxSynth.duration(of: .pop) == 0 + 0.09 + 0.02)
        #expect(SfxSynth.duration(of: .success) == 3 * 0.075 + 0.16 + 0.02)
        #expect(SfxSynth.duration(of: .nudge) == 0.16 + 0.22 + 0.02)
        #expect(SfxSynth.duration(of: .oops) == 0.16 + 0.28 + 0.02)
    }

    @Test("frame counts at 48 kHz")
    func frameCounts() {
        #expect(SfxSynth.render(.pop, sampleRate: 48_000).count == 5_280)      // 0.110 s
        #expect(SfxSynth.render(.success, sampleRate: 48_000).count == 19_440) // 0.405 s
        #expect(SfxSynth.render(.nudge, sampleRate: 48_000).count == 19_200)   // 0.400 s
        #expect(SfxSynth.render(.oops, sampleRate: 48_000).count == 22_080)    // 0.460 s
    }

    @Test("frame counts follow the sample rate")
    func frameCountsAt44100() {
        #expect(SfxSynth.render(.nudge, sampleRate: 44_100).count == 17_640)  // 0.40 × 44100
    }

    @Test("the exponential envelope starts at 1e-4, peaks at gain, and decays back")
    func envelopeShape() {
        let gain = 0.16
        let dur = 0.09
        // setValueAtTime(0.0001, t0)
        #expect(abs(SfxSynth.envelope(0, dur: dur, gain: gain) - 0.0001) < 1e-12)
        // exponentialRampToValueAtTime(gain, t0 + 0.008)
        #expect(abs(SfxSynth.envelope(0.008, dur: dur, gain: gain) - gain) < 1e-12)
        // exponentialRampToValueAtTime(0.0001, t0 + dur)
        #expect(abs(SfxSynth.envelope(dur, dur: dur, gain: gain) - 0.0001) < 1e-12)
        // v(t) = v0 · (v1/v0)^((t−t0)/(t1−t0)) — the midpoint of the attack is
        // the geometric mean, not the arithmetic one. A linear ramp would give
        // 0.08005; the exponential gives sqrt(0.0001 · 0.16).
        #expect(abs(SfxSynth.envelope(0.004, dur: dur, gain: gain) - (0.0001 * gain).squareRoot()) < 1e-12)
        // Monotone rising then monotone falling.
        #expect(SfxSynth.envelope(0.002, dur: dur, gain: gain) < SfxSynth.envelope(0.006, dur: dur, gain: gain))
        #expect(SfxSynth.envelope(0.02, dur: dur, gain: gain) > SfxSynth.envelope(0.06, dur: dur, gain: gain))
        // The 20 ms tail holds the 1e-4 floor (Web Audio ramps cannot reach 0).
        #expect(SfxSynth.envelope(dur + 0.01, dur: dur, gain: gain) == 0.0001)
    }

    @Test("peaks match the authored gains and nothing clips")
    func peaks() {
        func peak(_ sfx: Sfx) -> Float {
            SfxSynth.render(sfx, sampleRate: 48_000).map { abs($0) }.max() ?? 0
        }
        // A single blip cannot exceed its own gain.
        #expect(peak(.pop) <= 0.16 + 1e-6)
        // …and must actually reach it, or the envelope is not being applied.
        #expect(peak(.pop) > 0.15)
        // nudge: two 0.09 notes, and the first has decayed to the 1e-4 floor
        // before the second starts — so the sum never exceeds one note.
        #expect(peak(.nudge) <= 0.09 + 1e-6)
        #expect(peak(.nudge) > 0.089)
        // oops: two 0.13 blips overlapping for 20 ms.
        #expect(peak(.oops) <= 0.26 + 1e-6)
        // success: four 0.16 notes 75 ms apart. spec §5.3 guesses a summed peak
        // of ≈ 0.5 by assuming they overlap at full gain; they do not. The
        // exponential decay has the previous note down to ~2.6 % of its gain by
        // the time the next one peaks, so the real sum barely exceeds one note.
        // Measured 0.15998 at 48 kHz. Either way: nowhere near clipping, no
        // limiter needed — which is the property that actually matters.
        #expect(peak(.success) < 1.0)
        #expect(peak(.success) > 0.15)
        #expect(peak(.success) <= 0.16 + 1e-6)
    }

    @Test("the triangle is asin(sin θ)-shaped, not a ramp fold")
    func triangleShape() {
        // 2/π · asin(sin θ): peaks at ±1 on the quarter phases, 0 at 0 and π.
        #expect(abs(SfxSynth.sample(.triangle, phase: 0)) < 1e-12)
        #expect(abs(SfxSynth.sample(.triangle, phase: .pi / 2) - 1) < 1e-12)
        #expect(abs(SfxSynth.sample(.triangle, phase: 3 * .pi / 2) + 1) < 1e-12)
        // Linear between them — a sine would give sin(π/4) ≈ 0.7071 here.
        #expect(abs(SfxSynth.sample(.triangle, phase: .pi / 4) - 0.5) < 1e-12)
        #expect(abs(SfxSynth.sample(.sine, phase: .pi / 4) - 0.5.squareRoot()) < 1e-12)
    }

    @Test("only pop uses the triangle; success, nudge and oops are sine")
    func waveAssignment() {
        #expect(SfxSynth.blips(for: .pop).allSatisfy { $0.wave == .triangle })
        for sfx in [Sfx.success, .nudge, .oops] {
            #expect(SfxSynth.blips(for: sfx).allSatisfy { $0.wave == .sine })
        }
    }

    @Test("the success arpeggio is the four rising notes, 75 ms apart")
    func successArpeggio() {
        let blips = SfxSynth.blips(for: .success)
        #expect(blips.map(\.freq) == [523.25, 659.25, 783.99, 1046.5])
        // `i * 0.075` in binary floating point: 3 × 0.075 is 0.22499999999999998,
        // not 0.225. Same value the TS computes, so compare it the same way.
        #expect(zip(blips.map(\.at), [0, 0.075, 0.15, 0.225]).allSatisfy { abs($0 - $1) < 1e-12 })
        #expect(blips.allSatisfy { $0.dur == 0.16 && $0.gain == 0.16 })
    }

    @Test("oops is the two-note falling wah-wah")
    func oopsIsFalling() {
        let blips = SfxSynth.blips(for: .oops)
        #expect(blips.count == 2)
        #expect(blips[0].freq > blips[1].freq)  // 392 → 311.13, falling
        #expect(blips[1].at == 0.16)
        #expect(blips.allSatisfy { $0.gain == 0.13 })
    }

    @Test("nudge stays the softest sound in the game — invariant 3")
    func nudgeIsSoft() {
        let nudge = SfxSynth.blips(for: .nudge)
        // A wrong tap is not a failure. Whatever else changes about this cue,
        // it may never be the loudest thing a child hears.
        let others = [Sfx.pop, .success, .oops].flatMap { SfxSynth.blips(for: $0) }
        #expect(others.allSatisfy { blip in nudge.allSatisfy { $0.gain < blip.gain } })
    }

    @Test("nudge falls, which is what makes it mean « non »")
    func nudgeFalls() {
        let nudge = SfxSynth.blips(for: .nudge)
        #expect(nudge.count == 2)
        #expect(nudge[0].freq > nudge[1].freq)
    }

    @Test("every nudge note is in the band a phone's speaker reproduces")
    func nudgeIsInBand() {
        // The reported defect: the web's 196 Hz nudge is below a phone
        // loudspeaker's rolloff knee, so a child hears nothing on a wrong tap.
        // 350 Hz is the floor this cue is allowed to touch — see `SfxSynth`.
        let low = SfxSynth.blips(for: .nudge).map(\.freq).min() ?? 0
        #expect(
            low >= 350,
            Comment(rawValue: "the nudge's lowest note is \(low) Hz — a phone will swallow it"))
    }

    @Test("the nudge begins after the pop has died away, so it is not masked")
    func nudgeClearsThePop() {
        // `pick()` fires pop() and nudge() in the same instant. The nudge is the
        // quieter of the two by design, so it can only be heard if it starts
        // once the pop's exponential decay has taken it far below.
        let pop = SfxSynth.blips(for: .pop)[0]
        let nudge = SfxSynth.blips(for: .nudge)[0]
        let popStillRinging = SfxSynth.envelope(nudge.at, dur: pop.dur, gain: pop.gain)
        // ≥ 20 dB down: 0.0015 against the nudge's 0.09, i.e. ~35 dB in practice.
        #expect(
            popStillRinging * 10 < nudge.gain,
            Comment(
                rawValue:
                    "at +\(nudge.at)s the pop is still at \(popStillRinging), against a nudge of \(nudge.gain)"
            ))
    }

    @Test("rendering is deterministic")
    func determinism() {
        for sfx in Sfx.allCases {
            #expect(SfxSynth.render(sfx, sampleRate: 48_000) == SfxSynth.render(sfx, sampleRate: 48_000))
        }
    }
}
