package fr.dappit.attrapelettres.platform.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The four SFX, against `blip()` in `src/hooks/useAudio.ts`. Port of the iOS
// suite `AudioSfxTests.swift`, assertion for assertion.
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

class SfxSynthTest {

    @Test
    fun `buffer length is (last offset + duration + tail) seconds`() {
        assertEquals(0 + 0.09 + 0.02, SfxSynth.duration(Sfx.POP))
        assertEquals(3 * 0.075 + 0.16 + 0.02, SfxSynth.duration(Sfx.SUCCESS))
        assertEquals(0.16 + 0.22 + 0.02, SfxSynth.duration(Sfx.NUDGE))
        assertEquals(0.16 + 0.28 + 0.02, SfxSynth.duration(Sfx.OOPS))
    }

    @Test
    fun `frame counts at 48 kHz`() {
        assertEquals(5_280, SfxSynth.render(Sfx.POP, 48_000.0).size) // 0.110 s
        assertEquals(19_440, SfxSynth.render(Sfx.SUCCESS, 48_000.0).size) // 0.405 s
        assertEquals(19_200, SfxSynth.render(Sfx.NUDGE, 48_000.0).size) // 0.400 s
        assertEquals(22_080, SfxSynth.render(Sfx.OOPS, 48_000.0).size) // 0.460 s
    }

    @Test
    fun `frame counts follow the sample rate`() {
        assertEquals(17_640, SfxSynth.render(Sfx.NUDGE, 44_100.0).size) // 0.40 × 44100
    }

    @Test
    fun `the exponential envelope starts at 1e-4, peaks at gain, and decays back`() {
        val gain = 0.16
        val dur = 0.09
        // setValueAtTime(0.0001, t0)
        assertTrue(abs(SfxSynth.envelope(0.0, dur, gain) - 0.0001) < 1e-12)
        // exponentialRampToValueAtTime(gain, t0 + 0.008)
        assertTrue(abs(SfxSynth.envelope(0.008, dur, gain) - gain) < 1e-12)
        // exponentialRampToValueAtTime(0.0001, t0 + dur)
        assertTrue(abs(SfxSynth.envelope(dur, dur, gain) - 0.0001) < 1e-12)
        // v(t) = v0 · (v1 ÷ v0) ^ ((t − t0) ÷ (t1 − t0)) — the midpoint of the
        // attack is the geometric mean, not the arithmetic one. A linear ramp
        // would give 0.08005; the exponential gives sqrt(0.0001 · 0.16).
        assertTrue(abs(SfxSynth.envelope(0.004, dur, gain) - sqrt(0.0001 * gain)) < 1e-12)
        // Monotone rising then monotone falling.
        assertTrue(SfxSynth.envelope(0.002, dur, gain) < SfxSynth.envelope(0.006, dur, gain))
        assertTrue(SfxSynth.envelope(0.02, dur, gain) > SfxSynth.envelope(0.06, dur, gain))
        // The 20 ms tail holds the 1e-4 floor (Web Audio ramps cannot reach 0).
        assertEquals(0.0001, SfxSynth.envelope(dur + 0.01, dur, gain))
    }

    @Test
    fun `peaks match the authored gains and nothing clips`() {
        fun peak(sfx: Sfx): Float {
            var most = 0f
            for (s in SfxSynth.render(sfx, 48_000.0)) {
                if (abs(s) > most) most = abs(s)
            }
            return most
        }
        // A single blip cannot exceed its own gain.
        assertTrue(peak(Sfx.POP) <= 0.16 + 1e-6)
        // …and must actually reach it, or the envelope is not being applied.
        assertTrue(peak(Sfx.POP) > 0.15)
        // nudge: two notes, and the first has decayed to the 1e-4 floor before
        // the second starts — so the sum never exceeds one note.
        assertTrue(peak(Sfx.NUDGE) <= 0.09 + 1e-6)
        assertTrue(peak(Sfx.NUDGE) > 0.089)
        // oops: two 0.13 blips overlapping for 20 ms.
        assertTrue(peak(Sfx.OOPS) <= 0.26 + 1e-6)
        // success: four 0.16 notes 75 ms apart. The exponential decay has the
        // previous note down to a few percent of its gain by the time the next
        // one peaks, so the real sum barely exceeds one note. Either way:
        // nowhere near clipping, no limiter needed — which is the property
        // that actually matters.
        assertTrue(peak(Sfx.SUCCESS) < 1.0)
        assertTrue(peak(Sfx.SUCCESS) > 0.15)
        assertTrue(peak(Sfx.SUCCESS) <= 0.16 + 1e-6)
    }

    @Test
    fun `every sound ends at the inaudible floor — the envelope has fully decayed`() {
        // The last rendered sample sits in the 20 ms tail, where the envelope
        // holds the 1e-4 floor; whatever the oscillator's phase, its magnitude
        // is bounded by that floor. If a refactor ever dropped the decay ramp,
        // the buffer would end at full gain and this would light up.
        for (sfx in Sfx.entries) {
            val rendered = SfxSynth.render(sfx, 48_000.0)
            assertTrue(rendered.isNotEmpty(), sfx.name)
            assertTrue(abs(rendered.last()) <= SfxSynth.FLOOR_GAIN + 1e-7, sfx.name)
        }
    }

    @Test
    fun `the triangle is asin(sin θ)-shaped, not a ramp fold`() {
        // 2 ÷ π · asin(sin θ): peaks at ±1 on the quarter phases, 0 at 0 and π.
        assertTrue(abs(SfxSynth.sample(SfxWave.TRIANGLE, 0.0)) < 1e-12)
        assertTrue(abs(SfxSynth.sample(SfxWave.TRIANGLE, PI / 2) - 1) < 1e-12)
        assertTrue(abs(SfxSynth.sample(SfxWave.TRIANGLE, 3 * PI / 2) + 1) < 1e-12)
        // Linear between them — a sine would give sin(π ÷ 4) ≈ 0.7071 here.
        assertTrue(abs(SfxSynth.sample(SfxWave.TRIANGLE, PI / 4) - 0.5) < 1e-12)
        assertTrue(abs(SfxSynth.sample(SfxWave.SINE, PI / 4) - sqrt(0.5)) < 1e-12)
    }

    @Test
    fun `only pop uses the triangle — success, nudge and oops are sine`() {
        assertTrue(SfxSynth.blips(Sfx.POP).all { it.wave == SfxWave.TRIANGLE })
        for (sfx in listOf(Sfx.SUCCESS, Sfx.NUDGE, Sfx.OOPS)) {
            assertTrue(SfxSynth.blips(sfx).all { it.wave == SfxWave.SINE }, sfx.name)
        }
    }

    @Test
    fun `the success arpeggio is the four rising notes, 75 ms apart`() {
        val blips = SfxSynth.blips(Sfx.SUCCESS)
        assertEquals(listOf(523.25, 659.25, 783.99, 1046.5), blips.map { it.freq })
        // `i * 0.075` in binary floating point: 3 × 0.075 is
        // 0.22499999999999998, not 0.225. Same value the TS computes, so
        // compare it the same way.
        val expectedAts = listOf(0.0, 0.075, 0.15, 0.225)
        assertTrue(blips.map { it.at }.zip(expectedAts).all { (a, b) -> abs(a - b) < 1e-12 })
        assertTrue(blips.all { it.dur == 0.16 && it.gain == 0.16 })
    }

    @Test
    fun `oops is the two-note falling wah-wah`() {
        val blips = SfxSynth.blips(Sfx.OOPS)
        assertEquals(2, blips.size)
        assertTrue(blips[0].freq > blips[1].freq) // 392 → 311.13, falling
        assertEquals(0.16, blips[1].at)
        assertTrue(blips.all { it.gain == 0.13 })
    }

    @Test
    fun `nudge stays the softest sound in the game — invariant 3`() {
        val nudge = SfxSynth.blips(Sfx.NUDGE)
        // A wrong tap is not a failure. Whatever else changes about this cue,
        // it may never be the loudest thing a child hears.
        val others = listOf(Sfx.POP, Sfx.SUCCESS, Sfx.OOPS).flatMap { SfxSynth.blips(it) }
        assertTrue(others.all { blip -> nudge.all { it.gain < blip.gain } })
    }

    @Test
    fun `nudge falls, which is what makes it mean non`() {
        val nudge = SfxSynth.blips(Sfx.NUDGE)
        assertEquals(2, nudge.size)
        assertTrue(nudge[0].freq > nudge[1].freq)
    }

    @Test
    fun `every nudge note is in the band a phone speaker reproduces`() {
        // The reported defect: the web's 196 Hz nudge is below a phone
        // loudspeaker's rolloff knee, so a child hears nothing on a wrong tap.
        // 350 Hz is the floor this cue is allowed to touch — see SfxSynth.
        val low = SfxSynth.blips(Sfx.NUDGE).minOf { it.freq }
        assertTrue(low >= 350, "the nudge's lowest note is $low Hz — a phone will swallow it")
    }

    @Test
    fun `the nudge begins after the pop has died away, so it is not masked`() {
        // `pick()` fires pop() and nudge() in the same instant. The nudge is
        // the quieter of the two by design, so it can only be heard if it
        // starts once the pop's exponential decay has taken it far below.
        val pop = SfxSynth.blips(Sfx.POP)[0]
        val nudge = SfxSynth.blips(Sfx.NUDGE)[0]
        val popStillRinging = SfxSynth.envelope(nudge.at, pop.dur, pop.gain)
        // ≥ 20 dB down: 0.0015 against the nudge's 0.09, i.e. ~35 dB in practice.
        assertTrue(
            popStillRinging * 10 < nudge.gain,
            "at +${nudge.at}s the pop is still at $popStillRinging, against a nudge of ${nudge.gain}",
        )
    }

    @Test
    fun `rendering is deterministic`() {
        for (sfx in Sfx.entries) {
            assertTrue(
                SfxSynth.render(sfx, 48_000.0).contentEquals(SfxSynth.render(sfx, 48_000.0)),
                sfx.name,
            )
        }
    }
}
