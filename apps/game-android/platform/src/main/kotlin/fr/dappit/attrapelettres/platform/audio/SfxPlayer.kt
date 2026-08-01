package fr.dappit.attrapelettres.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

// The Android half of the SFX: SfxSynth's PCM, pushed out the speaker. The
// Kotlin analogue of `GameAudioGraph.swift` (with none of its format-wiring
// subtlety — see the note at the bottom of this header).
//
// WHY STATIC AudioTrack AND NOT SoundPool (invariant 1). The task on the tap
// path is: `pop()` fires synchronously inside the pointer-down handler, before
// anything recomposes, so nothing there may allocate, decode, read a file, or
// launch a coroutine. Both candidates can satisfy that at PLAY time; they
// differ at LOAD time, and the difference decides it:
//
//   - SoundPool's only ingestion paths are a file path, an asset fd, or a
//     resource id — there is no "load from a PCM buffer in memory" API. Our
//     sounds are SYNTHESISED, so SoundPool would force render → wrap in a WAV
//     container → write it to cache → `load()` → wait for the async
//     OnLoadCompleteListener. That adds file I/O, a container round-trip, and
//     a load race (a tap between construction and the callback would be
//     silent), all to feed a decoder whose output we already had.
//   - AudioTrack in MODE_STATIC accepts the FloatArray directly and
//     synchronously: one `write()` at construction hands the samples to the
//     native layer, which keeps them for the track's lifetime. Replaying is
//     `stop()` + `reloadStaticData()` + `play()` — three JNI calls, no
//     allocation, no decode, no filesystem, no await. That is invariant 1's
//     requirement list, verbatim. (MODE_STREAM would need a `write()` per
//     play — a copy on the tap path — which is why it is not used either.)
//
// So: static tracks, built and primed ONCE in the constructor, which is this
// player's `prewarm()`. Construct it at startup (Android has no web-style
// autoplay gate, so there is no reason to wait for a first gesture).
//
// Voices. A wrong tap plays `pop()` AND `nudge()` on the same instant — those
// are different tracks, so that overlap is covered by construction. The pool
// of VOICES_PER_SFX tracks per sound covers a re-trigger of the SAME sound
// before its tail dies. Round-robin restarts the oldest voice, which is the
// Android translation of the iOS graph's `.interrupts` option: a child who
// spam-taps must not build a backlog of pops that keeps sounding after they
// stop.
//
// Why there is no `ensureSfxFormat` here. The iOS graph crashed on the first
// tap because an AVAudioPlayerNode's CONNECTION format and its buffers' format
// are two separate facts that nothing tied together. AudioTrack has no such
// seam: the format is a constructor argument of the same object that holds the
// buffer, so a mono buffer cannot meet a stereo sink. That whole class of bug
// is structurally absent — what remains device-only is latency, which no host
// test can measure (ARCHITECTURE §4, W16).

/**
 * The seam the audio engine plays through, so an engine test can spy on which
 * sounds were requested without an audio device. Mirror of iOS `SfxPlaying`,
 * kept minimal; the engine work package may grow it if it needs lifecycle
 * hooks.
 */
interface SfxPlaying {
    /**
     * Start one pre-rendered sound. Synchronous, allocation-free, never
     * touches the filesystem. A player whose tracks failed to build is a
     * silent no-op — audio may go quiet, it may never take the game down
     * (invariant 3).
     */
    fun play(sfx: Sfx)
}

class SfxPlayer(sampleRate: Int = DEFAULT_SAMPLE_RATE) : SfxPlaying {

    /**
     * `voices[sfx.ordinal]` is that sound's pool of primed tracks. A track
     * that failed to build or prime is simply absent; an empty pool means the
     * sound is quiet, not that the game is broken.
     */
    private val voices: Array<List<AudioTrack>> = Array(Sfx.entries.size) { ordinal ->
        val samples = SfxSynth.render(Sfx.entries[ordinal], sampleRate.toDouble())
        List(VOICES_PER_SFX) { buildTrack(samples, sampleRate) }.filterNotNull()
    }

    /** Next voice to (re)start, per sound. Plain ints — nothing to allocate. */
    private val slots = IntArray(Sfx.entries.size)

    override fun play(sfx: Sfx) {
        val pool = voices[sfx.ordinal]
        if (pool.isEmpty()) return
        val slot = slots[sfx.ordinal]
        slots[sfx.ordinal] = (slot + 1) % pool.size
        val track = pool[slot]
        try {
            // The canonical static-track replay sequence: stop is immediate in
            // MODE_STATIC, reloadStaticData rewinds the head to frame zero,
            // play restarts. All three are cheap native calls on the buffer
            // that has lived on the native side since construction.
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
            // A released or dead track. The sound goes quiet and the game
            // keeps going (invariant 3) — never rethrow from a child's tap.
        }
    }

    /** Release every native track. The player is unusable afterwards. */
    fun release() {
        for (pool in voices) {
            for (track in pool) {
                track.release()
            }
        }
    }

    private fun buildTrack(samples: FloatArray, sampleRate: Int): AudioTrack? {
        if (samples.isEmpty()) return null
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Game SFX, not media: sonification content under game
                        // usage routes correctly and ducks like the iOS
                        // session category does.
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Float.SIZE_BYTES)
                // minSdk 26 makes this unconditional (A6). Low latency asks
                // the mixer for a fast track, which is also why `forDevice`
                // renders at the hardware rate: a fast track requires it.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (written == samples.size && track.state == AudioTrack.STATE_INITIALIZED) {
                track
            } else {
                // A partial write or a track that never became ready would
                // play garbage or throw later; drop it now and stay quiet.
                track.release()
                null
            }
        } catch (_: Exception) {
            // No audio output, an exhausted AudioFlinger, a builder rejection:
            // the game goes quiet and keeps working. Invariant 3.
            null
        }
    }

    companion object {
        /**
         * The rate used when the device's native one is unknown (host
         * previews, tests): 48 kHz is what modern Android hardware runs at.
         */
        const val DEFAULT_SAMPLE_RATE: Int = 48_000

        /** See the header note on voices. */
        private const val VOICES_PER_SFX: Int = 2

        /**
         * Build a player rendered at the device's native output rate, so the
         * mixer never resamples the SFX and the fast (low-latency) path stays
         * eligible.
         */
        fun forDevice(context: Context): SfxPlayer {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val native = manager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
            return SfxPlayer(if (native != null && native > 0) native else DEFAULT_SAMPLE_RATE)
        }
    }
}
