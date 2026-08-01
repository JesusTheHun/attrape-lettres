package fr.dappit.attrapelettres.platform.audio

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

// The baked-clip half of the voice channel: `ClipPlayer.swift`, and before it
// the single reused `HTMLAudioElement` in `src/hooks/useAudio.ts`.
//
// WHY MediaPlayer AND NOT SoundPool. The SFX channel is a `SoundPool` because
// invariant 1 requires `pop()` to make a sound synchronously inside the
// pointer-down handler. The voice channel has the opposite requirement:
// `say()` must resolve WHEN THE LINE ENDS, because every exercise gates its
// next step on that boolean. `SoundPool` has no completion callback at all —
// there is no event to resolve on — so it cannot implement `say` however fast
// it starts. `MediaPlayer` has `setOnCompletionListener`, `setOnErrorListener`,
// `getDuration`, `setVolume` and `pause`, which is very nearly the
// `HTMLAudioElement` surface the web is written against, one for one.
//
// The cost is that `prepare()` decodes, and decoding must not happen on the tap
// path. It does not: `say()` is the only suspend function in the audio seam,
// the engine calls `prepare` on `Dispatchers.IO`, and the tap itself is served
// by the pre-warmed SFX pool. What IS warmed at mount is the `MediaPlayer`
// instance, so the first line does not also pay object construction.
//
// The iOS port keeps a 120-second LRU of decoded PCM buffers and can therefore
// `preload()` a round's lines during the 350 ms announce delay. That is NOT
// ported: reproducing it on Android means MediaCodec into AudioTrack and a
// hand-written mixer, which is a large amount of machinery for latency that
// `MediaPlayer.prepare()` on a local, already-uncompressed-in-the-APK asset
// measures in single-digit milliseconds. If it ever matters, the seam to widen
// is [ClipPlayback], not the engine.

/** A clip that has been resolved and prepared and is ready to be started. */
class PreparedClip(
    /** The asset path it came from, e.g. `vo/10121dz.m4a`. */
    val assetPath: String,
    /**
     * Milliseconds, or `<= 0` when the player cannot say. That is the case the
     * provisional watchdog exists for; `MediaPlayer.getDuration()` answers
     * synchronously after `prepare()`, so it is nearly always known.
     */
    val durationMs: Long,
)

/**
 * One clip at a time. A new `prepare` / `play` supersedes whatever was
 * sounding, which is the whole contract: voice never overlaps voice.
 *
 * Nothing here is `suspend`. [hardStop], [cancelFade] and [fadeOutAndStop] are
 * reachable from `AudioEngine.stop()`, which an exercise calls as it unmounts,
 * and that is not a place with anywhere to await (invariant 1, A3).
 */
interface ClipPlayback {
    /**
     * Build the player. Idempotent, safe to call off the main thread, and meant
     * to be called at mount so the first `say()` does not pay for it.
     */
    fun prewarm()

    /**
     * Decode and buffer. BLOCKING — the caller is responsible for keeping it
     * off the main thread. `null` is a media error, which the web surfaces as
     * `el.onerror` and settles `false`; it is deliberately distinct from
     * "not baked", which never reaches here at all.
     */
    fun prepare(assetPath: String): PreparedClip?

    /**
     * Start playing. `false` means it could not start at all — the web's
     * `el.play().catch(...)` — and the engine falls back to text-to-speech.
     * `onEnd(true)` = played to its end, `onEnd(false)` = errored mid-flight.
     * `onEnd` may be invoked from any thread.
     */
    fun play(clip: PreparedClip, onEnd: (Boolean) -> Unit): Boolean

    /** `el.pause()` — cut instantly, no fade. */
    fun hardStop()

    /**
     * A leave-fade may be mid-ramp; cancel it and restore full volume, or the
     * next line plays under a decaying gain.
     */
    fun cancelFade()

    /** The 200 ms leave-fade: `steps` x `intervalMs`, then stop and restore. */
    fun fadeOutAndStop(steps: Int, intervalMs: Long)

    /** `!el.paused` — is a clip actually sounding right now? */
    val isPlaying: Boolean

    /** Give the codec back. The app is going away, or the engine is. */
    fun release()
}

/**
 * [ClipPlayback] over one reused `MediaPlayer`, fed straight from the APK's
 * assets.
 *
 * @param assets the APK's asset table; clip paths come from [AssetClipBank].
 * @param scope where the 200 ms leave-fade ticks. Injected, never global, so a
 *   test can drive it deterministically and so the engine can cancel it.
 */
class MediaPlayerVoicePlayer(
    private val assets: AssetManager,
    private val scope: CoroutineScope,
) : ClipPlayback {

    private val lock = Any()
    private var player: MediaPlayer? = null

    // Bumped on every hard stop and every new start, so a completion callback
    // that was already in flight on the main looper cannot report a line that
    // is over. The engine's own single-flight guard would drop it anyway; this
    // one keeps the player's own `playing` flag honest.
    private var generation = 0

    // Bumped on every fade start and every cancel, for the same reason.
    private var fadeToken = 0

    // The web reads `start = el.volume` when a fade begins, so a fade that
    // interrupts a fade ramps from where it actually is rather than from 1.
    private var volume = 1f

    @Volatile
    private var playing = false

    override val isPlaying: Boolean get() = playing

    override fun prewarm() {
        ensurePlayer()
    }

    private fun ensurePlayer(): MediaPlayer? = synchronized(lock) {
        player ?: try {
            MediaPlayer().also {
                it.setAudioAttributes(VOICE_ATTRIBUTES)
                player = it
            }
        } catch (e: RuntimeException) {
            // A device that cannot give us a MediaPlayer at all is a device
            // where every line speaks in the OS voice instead. Quieter, never
            // broken (invariant 3).
            Log.e(TAG, "could not create the voice MediaPlayer", e)
            null
        }
    }

    override fun prepare(assetPath: String): PreparedClip? {
        val mp = ensurePlayer() ?: return null
        synchronized(lock) {
            return try {
                mp.reset()
                // `reset()` returns the player to Idle and DROPS the audio
                // attributes with it, so they are re-applied on every line
                // rather than once at construction. Missing this is how a game
                // ends up routed as media and ducked by its own music.
                mp.setAudioAttributes(VOICE_ATTRIBUTES)
                val fd = assets.openFd(assetPath)
                try {
                    mp.setDataSource(fd)
                } finally {
                    // Documented as safe the moment `setDataSource` returns —
                    // the player dups what it needs.
                    fd.close()
                }
                mp.prepare()
                volume = 1f
                mp.setVolume(1f, 1f)
                PreparedClip(assetPath, mp.duration.toLong())
            } catch (e: IOException) {
                Log.w(TAG, "could not prepare $assetPath", e)
                null
            } catch (e: IllegalStateException) {
                Log.w(TAG, "voice player was in the wrong state for $assetPath", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "voice player rejected $assetPath", e)
                null
            }
        }
    }

    override fun play(clip: PreparedClip, onEnd: (Boolean) -> Unit): Boolean {
        val mp = synchronized(lock) { player } ?: return false
        val generationForThisLine = synchronized(lock) {
            generation += 1
            generation
        }
        return try {
            mp.setOnCompletionListener { finish(generationForThisLine, true, onEnd) }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "voice playback error on ${clip.assetPath} ($what/$extra)")
                finish(generationForThisLine, false, onEnd)
                // `true` = handled. Returning false would ALSO fire the
                // completion listener, which would settle the same line twice.
                true
            }
            mp.seekTo(0) // `el.currentTime = 0`
            mp.start()
            playing = true
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "voice player could not start ${clip.assetPath}", e)
            false
        }
    }

    /**
     * Deliver an end exactly once, and only for the line that is still current.
     *
     * MediaPlayer callbacks arrive on the Looper of whichever thread built the
     * player — and this one is built on an IO thread with no Looper, so they
     * arrive on the main one. In a host test with no main Looper they never
     * arrive at all, and the engine's watchdog settles the line `false`. That is
     * the correct degradation, not a bug to design around.
     */
    private fun finish(generationForThisLine: Int, ok: Boolean, onEnd: (Boolean) -> Unit) {
        val current = synchronized(lock) { generation == generationForThisLine }
        if (!current) return
        playing = false
        onEnd(ok)
    }

    override fun hardStop() {
        synchronized(lock) {
            generation += 1
            fadeToken += 1
            playing = false
            volume = 1f
            val mp = player ?: return
            try {
                mp.setVolume(1f, 1f)
                if (mp.isPlaying) mp.pause()
                mp.seekTo(0)
            } catch (e: IllegalStateException) {
                // Released or never prepared. There is nothing to stop, which is
                // the outcome we wanted anyway.
                Log.w(TAG, "hardStop on an unusable voice player", e)
            }
        }
    }

    override fun cancelFade() {
        synchronized(lock) {
            fadeToken += 1
            volume = 1f
            try {
                player?.setVolume(1f, 1f)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cancelFade on an unusable voice player", e)
            }
        }
    }

    /**
     * `10 x 20 ms = 200 ms`, reproduced literally including the arithmetic:
     * `volume = max(0, start * (1 - ++i / steps))`. A smooth hardware ramp
     * would be nicer and is therefore out of scope — the behaviour is frozen.
     */
    override fun fadeOutAndStop(steps: Int, intervalMs: Long) {
        if (steps <= 0) {
            hardStop()
            return
        }
        val (token, start) = synchronized(lock) {
            fadeToken += 1
            fadeToken to volume
        }
        scope.launch {
            for (i in 1..steps) {
                delay(intervalMs)
                val level = (start * (1f - i.toFloat() / steps)).coerceAtLeast(0f)
                val stillOurs = synchronized(lock) {
                    if (fadeToken != token) return@synchronized false
                    volume = level
                    try {
                        player?.setVolume(level, level)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "fade on an unusable voice player", e)
                    }
                    true
                }
                if (!stillOurs) return@launch
            }
            // `hardStop` clears the token and restores the volume to 1, so the
            // next exercise's first line does not start under a dead gain.
            val stillOurs = synchronized(lock) { fadeToken == token }
            if (stillOurs) hardStop()
        }
    }

    override fun release() {
        synchronized(lock) {
            generation += 1
            fadeToken += 1
            playing = false
            val mp = player ?: return
            player = null
            try {
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.reset()
                mp.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "release on an unusable voice player", e)
            }
        }
    }

    companion object {
        private const val TAG = "AL.VoicePlayer"

        /**
         * `USAGE_GAME` with `CONTENT_TYPE_SPEECH`.
         *
         * The pair is deliberate and the two halves say different things.
         * `USAGE_GAME` is what the app IS, and it keeps the voice on the same
         * stream as the SFX, so a parent turning the game down turns all of it
         * down together. `CONTENT_TYPE_SPEECH` is what the buffer HOLDS, and it
         * is the flag accessibility services and in-car systems read to decide
         * whether this is something a listener needs to hear — which the
         * voice-over is: « Trouve la première lettre de Ballon » is the
         * question, not decoration.
         *
         * Not `USAGE_ASSISTANCE_ACCESSIBILITY`, which would let the game talk
         * over a phone call, and not `USAGE_MEDIA`, which invites the system to
         * treat a six-year-old's exercise as background music.
         */
        val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
