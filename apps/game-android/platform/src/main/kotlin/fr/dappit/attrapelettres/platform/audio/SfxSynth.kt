package fr.dappit.attrapelettres.platform.audio

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Port of `blip()` and the four SFX in `src/hooks/useAudio.ts`, via the iOS
// port in `ALPlatform/Audio/SfxSynth.swift`. The two native apps share one
// recipe — deviations included — so a child hears the same game on either
// phone.
//
// The web builds each sound from Web Audio oscillators at play time; that
// costs nothing there because scheduling an oscillator is sub-quantum.
// Natively the equivalent is a PRE-RENDERED buffer handed to an already-primed
// AudioTrack: invariant 1 says `pop()` and `nudge()` fire synchronously inside
// the pointer-down handler, so nothing on that path may allocate, decode, or
// touch a file. Everything below happens once, at construction of SfxPlayer.
//
// This file is pure arithmetic on purpose — no `android.*` — so the envelope
// and the frame counts are host-testable on a bare JVM.

/** The oscillator shapes the SFX use. `OscillatorType` in the TS. */
enum class SfxWave {
    SINE,
    TRIANGLE,
}

/** One `blip(freq, dur, type, gain, at)` call. */
data class SfxBlip(
    val freq: Double,
    val dur: Double,
    val wave: SfxWave,
    val gain: Double,
    /** Offset from the start of the sound, in seconds (`at` in the TS). */
    val at: Double,
)

/**
 * The four sounds `AudioEngine` exposes.
 *
 * Three are the web's oscillator recipes verbatim. `NUDGE` is not — see the
 * note on its blips: the authored one is inaudible through a phone's speaker.
 */
enum class Sfx {
    POP,
    SUCCESS,
    NUDGE,
    OOPS,
}

object SfxSynth {

    /**
     * `g.gain.setValueAtTime(0.0001, t0)` — Web Audio's exponential ramps
     * cannot start from or reach zero, hence the 1e-4 floor rather than
     * silence.
     */
    const val FLOOR_GAIN: Double = 0.0001

    /** `exponentialRampToValueAtTime(gain, t0 + 0.008)`. */
    const val ATTACK: Double = 0.008

    /**
     * `osc.stop(t0 + dur + 0.02)` — the oscillator outlives the ramp by 20 ms,
     * during which it emits the 1e-4 floor. Inaudible, but it is part of the
     * buffer length and therefore part of the frame-count assertions.
     */
    const val TAIL: Double = 0.02

    /**
     * The authored decomposition of each sound. Frequencies, durations, waves,
     * gains and offsets copied verbatim from `useAudio.ts` — except `NUDGE`,
     * which carries the iOS deviation, for the same physical reasons.
     */
    fun blips(sfx: Sfx): List<SfxBlip> = when (sfx) {
        Sfx.POP ->
            // blip(660, 0.09, "triangle", 0.16)
            listOf(SfxBlip(freq = 660.0, dur = 0.09, wave = SfxWave.TRIANGLE, gain = 0.16, at = 0.0))

        Sfx.SUCCESS ->
            // [523.25, 659.25, 783.99, 1046.5].forEach((f, i) => blip(f, 0.16, "sine", 0.16, i * 0.075))
            listOf(523.25, 659.25, 783.99, 1046.5).mapIndexed { index, freq ->
                SfxBlip(freq = freq, dur = 0.16, wave = SfxWave.SINE, gain = 0.16, at = index * 0.075)
            }

        Sfx.NUDGE ->
            // [DEVIATION, reported — the same one the iOS port made, kept
            // identical so the two native apps agree with each other.] The web
            // is `blip(196, 0.14, "sine", 0.1)`, fired in the SAME instant as
            // `pop()` — and on a phone a child hears nothing of it. Two
            // mechanisms, both absent on the desktop it was authored against:
            //
            //   - a phone's loudspeaker has no low end. It rolls off hard below
            //     roughly half a kilohertz, so a 196 Hz fundamental arrives
            //     tens of dB down — and the ear is least sensitive there too,
            //     so the two losses compound.
            //   - `pick()` plays `pop()` (660 Hz, gain 0.16) on the same frame.
            //     Whatever survives the speaker is then masked by a tone that
            //     is louder, brighter, and right in the band the speaker likes.
            //
            // So the cue is redesigned rather than transposed: a soft falling
            // pair, C5 → G4, both safely inside the band a phone reproduces,
            // and starting 60 ms in so the pop's decay is out of the way
            // first. Still the quietest sound in the game — invariant 3 says a
            // wrong tap is not a failure, and « doucement, non » is the whole
            // message.
            //
            // Sibling `OOPS` is deliberately NOT changed: it is lower still,
            // but it plays alone and « Oh non ! On recommence. » speaks over
            // it a beat later, so the assembly engines never rely on the tone
            // to carry the meaning. This one had nothing else.
            listOf(
                SfxBlip(freq = 523.25, dur = 0.12, wave = SfxWave.SINE, gain = 0.09, at = 0.06),
                SfxBlip(freq = 392.00, dur = 0.22, wave = SfxWave.SINE, gain = 0.09, at = 0.16),
            )

        Sfx.OOPS ->
            // The two-note falling "wah-wah" that pairs with « Oh non ! On recommence. »
            // blip(392, 0.18, "sine", 0.13, 0) + blip(311.13, 0.28, "sine", 0.13, 0.16)
            listOf(
                SfxBlip(freq = 392.0, dur = 0.18, wave = SfxWave.SINE, gain = 0.13, at = 0.0),
                SfxBlip(freq = 311.13, dur = 0.28, wave = SfxWave.SINE, gain = 0.13, at = 0.16),
            )
    }

    /** Buffer length in seconds: the last blip's `at + dur + 0.02`. */
    fun duration(sfx: Sfx): Double =
        blips(sfx).maxOfOrNull { it.at + it.dur + TAIL } ?: 0.0

    /**
     * Web Audio's exponential ramp, evaluated:
     * `v(t) = v0 · (v1 ÷ v0) ^ ((t − t0) ÷ (t1 − t0))`.
     *
     * Piecewise:
     * - `0 ≤ t < 0.008` — rising from the 1e-4 floor to `gain`.
     * - `0.008 ≤ t < dur` — falling from `gain` back to the floor.
     * - `dur ≤ t` — the 20 ms tail, held at the floor.
     */
    fun envelope(t: Double, dur: Double, gain: Double): Double {
        if (t < 0) return 0.0
        if (t < ATTACK) return FLOOR_GAIN * (gain / FLOOR_GAIN).pow(t / ATTACK)
        if (t < dur) return gain * (FLOOR_GAIN / gain).pow((t - ATTACK) / (dur - ATTACK))
        return FLOOR_GAIN
    }

    /**
     * One period of the requested wave at `phase` radians.
     *
     * Web Audio's `triangle` is band-limited; a naive triangle's odd harmonics
     * fall as one over n squared, so the difference at 660 Hz is inaudible.
     * Generated as `2 ÷ π · asin(sin θ)` rather than a ramp-fold — same call
     * as the iOS port: if the pop ever sounds buzzier than the web, this is
     * the line to look at.
     */
    fun sample(wave: SfxWave, phase: Double): Double = when (wave) {
        SfxWave.SINE -> sin(phase)
        SfxWave.TRIANGLE -> (2 / PI) * asin(sin(phase))
    }

    /**
     * Render one sound to mono float32 at `sampleRate`.
     *
     * Each blip's oscillator starts at phase 0 **at its own offset** (the TS
     * creates a fresh `OscillatorNode` per blip), and the blips are summed —
     * `SUCCESS`'s four notes overlap. No limiter needed: the exponential decay
     * has each note near the floor before the next one peaks, so the sum never
     * approaches clipping (the test asserts it).
     *
     * Deterministic: no RNG, no time source. Two renders are bit-identical.
     */
    fun render(sfx: Sfx, sampleRate: Double): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val total = (duration(sfx) * sampleRate).roundToInt()
        val out = FloatArray(total)
        for (blip in blips(sfx)) {
            val start = (blip.at * sampleRate).roundToInt()
            val count = ((blip.dur + TAIL) * sampleRate).roundToInt()
            val step = 2 * PI * blip.freq / sampleRate
            for (i in 0 until count) {
                val index = start + i
                if (index >= total) break
                val t = i / sampleRate
                val env = envelope(t, blip.dur, blip.gain)
                out[index] += (sample(blip.wave, step * i) * env).toFloat()
            }
        }
        return out
    }
}
