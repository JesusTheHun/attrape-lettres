import Foundation

// Port of `blip()` and the four SFX in `src/hooks/useAudio.ts`.
//
// The web builds each sound from Web Audio oscillators at play time; that costs
// nothing there because scheduling an oscillator is sub-quantum. Natively the
// equivalent is a PRE-RENDERED buffer handed to an already-running
// `AVAudioPlayerNode`: invariant 1 says `pop()` and `nudge()` fire synchronously
// inside the pointer-down handler, so nothing on that path may allocate,
// decode, or touch a file. Everything below happens once, at `unlock()`.
//
// This file is pure arithmetic on purpose — no AVFoundation — so the envelope
// and the frame counts are host-testable.

/// The oscillator shapes the SFX use. `OscillatorType` in the TS.
public enum SfxWave: Sendable {
    case sine
    case triangle
}

/// One `blip(freq, dur, type, gain, at)` call.
public struct SfxBlip: Sendable {
    public let freq: Double
    public let dur: Double
    public let wave: SfxWave
    public let gain: Double
    /// Offset from the start of the sound, in seconds (`at` in the TS).
    public let at: Double

    public init(freq: Double, dur: Double, wave: SfxWave, gain: Double, at: Double) {
        self.freq = freq
        self.dur = dur
        self.wave = wave
        self.gain = gain
        self.at = at
    }
}

/// The four sounds `AudioEngine` exposes.
///
/// Three are the web's oscillator recipes verbatim. `nudge` is not — see the
/// note on its blips: the authored one is inaudible through a phone's speaker.
public enum Sfx: String, CaseIterable, Sendable {
    case pop
    case success
    case nudge
    case oops
}

public enum SfxSynth {

    /// `g.gain.setValueAtTime(0.0001, t0)` — Web Audio's exponential ramps cannot
    /// start from or reach zero, hence the 1e-4 floor rather than silence.
    public static let floorGain = 0.0001
    /// `exponentialRampToValueAtTime(gain, t0 + 0.008)`.
    public static let attack = 0.008
    /// `osc.stop(t0 + dur + 0.02)` — the oscillator outlives the ramp by 20 ms,
    /// during which it emits the 1e-4 floor. Inaudible, but it is part of the
    /// buffer length and therefore part of the frame-count assertions.
    public static let tail = 0.02

    /// The authored decomposition of each sound. Frequencies, durations, waves,
    /// gains and offsets copied verbatim from `useAudio.ts`.
    public static func blips(for sfx: Sfx) -> [SfxBlip] {
        switch sfx {
        case .pop:
            // blip(660, 0.09, "triangle", 0.16)
            return [SfxBlip(freq: 660, dur: 0.09, wave: .triangle, gain: 0.16, at: 0)]
        case .success:
            // [523.25, 659.25, 783.99, 1046.5].forEach((f, i) => blip(f, 0.16, "sine", 0.16, i * 0.075))
            return [523.25, 659.25, 783.99, 1046.5].enumerated().map { index, freq in
                SfxBlip(freq: freq, dur: 0.16, wave: .sine, gain: 0.16, at: Double(index) * 0.075)
            }
        case .nudge:
            // [DEVIATION, reported] The web is `blip(196, 0.14, "sine", 0.1)`,
            // fired in the SAME instant as `pop()` — and on a phone a child
            // hears nothing of it. Two mechanisms, both absent on the desktop
            // this was authored against:
            //
            //   * a phone's loudspeaker has no low end. It rolls off hard below
            //     roughly half a kilohertz, so a 196 Hz fundamental arrives tens
            //     of dB down — and the ear is least sensitive there too, so the
            //     two losses compound.
            //   * `pick()` plays `pop()` (660 Hz, gain 0.16) on the same frame.
            //     Whatever survives the speaker is then masked by a tone that is
            //     louder, brighter, and right in the band the speaker likes.
            //
            // So the cue is redesigned rather than transposed: a soft falling
            // pair, C5 → G4, both safely inside the band a phone reproduces,
            // and starting 60 ms in so the pop's decay is out of the way first.
            // Still the quietest sound in the game — invariant 3 says a wrong
            // tap is not a failure, and « doucement, non » is the whole message.
            //
            // Sibling `oops` is deliberately NOT changed: it is lower still, but
            // it plays alone and « Oh non ! On recommence. » speaks over it a
            // beat later, so the assembly engines never rely on the tone to
            // carry the meaning. This one had nothing else.
            return [
                SfxBlip(freq: 523.25, dur: 0.12, wave: .sine, gain: 0.09, at: 0.06),
                SfxBlip(freq: 392.00, dur: 0.22, wave: .sine, gain: 0.09, at: 0.16),
            ]
        case .oops:
            // The two-note falling "wah-wah" that pairs with « Oh non ! On recommence. »
            return [
                SfxBlip(freq: 392, dur: 0.18, wave: .sine, gain: 0.13, at: 0),
                SfxBlip(freq: 311.13, dur: 0.28, wave: .sine, gain: 0.13, at: 0.16),
            ]
        }
    }

    /// Buffer length in seconds: the last blip's `at + dur + 0.02`.
    public static func duration(of sfx: Sfx) -> Double {
        blips(for: sfx).map { $0.at + $0.dur + tail }.max() ?? 0
    }

    /**
     * Web Audio's exponential ramp, evaluated: `v(t) = v0 · (v1/v0)^((t−t0)/(t1−t0))`.
     *
     * ```
     * 0     ≤ t < 0.008 : 0.0001 · (gain/0.0001)^(t/0.008)          rising
     * 0.008 ≤ t < dur   : gain   · (0.0001/gain)^((t−0.008)/(dur−0.008))  falling
     * dur   ≤ t         : 0.0001                                    the 20 ms tail
     * ```
     */
    public static func envelope(_ t: Double, dur: Double, gain: Double) -> Double {
        if t < 0 { return 0 }
        if t < attack { return floorGain * pow(gain / floorGain, t / attack) }
        if t < dur { return gain * pow(floorGain / gain, (t - attack) / (dur - attack)) }
        return floorGain
    }

    /// One period of the requested wave at `phase` radians.
    ///
    /// Web Audio's `triangle` is band-limited; a naive triangle's odd harmonics
    /// fall as 1/n², so the difference at 660 Hz is inaudible. Generated as
    /// `2/π · asin(sin θ)` rather than a ramp-fold — see spec §5.3: if the pop
    /// ever sounds buzzier than the web, this is the line to look at.
    public static func sample(_ wave: SfxWave, phase: Double) -> Double {
        switch wave {
        case .sine: return sin(phase)
        case .triangle: return (2 / Double.pi) * asin(sin(phase))
        }
    }

    /**
     * Render one sound to mono float32 at `sampleRate`.
     *
     * Each blip's oscillator starts at phase 0 **at its own offset** (the TS
     * creates a fresh `OscillatorNode` per blip), and the blips are summed —
     * `success`'s four notes overlap, peaking around 0.5. No limiter needed.
     *
     * Deterministic: no RNG, no time source. Two renders are bit-identical.
     */
    public static func render(_ sfx: Sfx, sampleRate: Double) -> [Float] {
        precondition(sampleRate > 0, "sampleRate must be positive")
        let total = Int((duration(of: sfx) * sampleRate).rounded())
        var out = [Float](repeating: 0, count: total)
        for blip in blips(for: sfx) {
            let start = Int((blip.at * sampleRate).rounded())
            let count = Int(((blip.dur + tail) * sampleRate).rounded())
            let step = 2 * Double.pi * blip.freq / sampleRate
            for i in 0..<count {
                let index = start + i
                if index >= total { break }
                let t = Double(i) / sampleRate
                let env = envelope(t, dur: blip.dur, gain: blip.gain)
                out[index] += Float(sample(blip.wave, phase: step * Double(i)) * env)
            }
        }
        return out
    }
}
