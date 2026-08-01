package fr.dappit.attrapelettres.platform.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import fr.dappit.attrapelettres.core.platform.AudioEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

// `core.platform.AudioEngine`, composed: the SFX channel + the clip bank + the
// clip player + the speech fallback + the single-flight voice channel + audio
// focus. The Android twin of `LiveAudioEngine.swift`, which folds in
// `VoiceChannel.swift`, `AudioSession.swift` and `AudioTimers.swift` because
// Kotlin does not need the last two as separate objects — see below.
//
// ONE instance for the app lifetime, injected from the composition root — not
// one per exercise. `useAudio` creates an `AudioContext` per mount and closes it
// on unmount because "a long session can't leak contexts until the browser caps
// them and the chimes go silent": a browser resource limit, not behaviour.
// Binding a `TextToSpeech` engine costs a service connection, so doing it on
// exercise entry would risk a mute first line. What IS preserved per exercise is
// the teardown: `useEffect(() => () => audio.stop(), [audio])` becomes a
// `DisposableEffect` calling `stop()`.
//
// WHERE THE WATCHDOGS WENT. The web and the Swift port both carry an explicit
// timer object, because a `Promise` and a `CheckedContinuation` are settled from
// the outside and nothing else can un-hang them. `say` here is a `suspend`
// function, so `withTimeoutOrNull` IS the watchdog: same deadlines, same
// arithmetic, no scheduler to inject and no timer to leak. The one thing that
// does NOT change is why they exist —
//
//   `say()` resolves `true` on natural completion and `false` when it was
//   superseded, errored, or a watchdog tripped. It never throws and it never
//   hangs. Callers gate the game's next step on that Boolean, so a wrong `false`
//   skips a line and a call that never settles freezes the round with no way out
//   but « ← Menu ». (Invariant 3: audio failure degrades, never blocks.)
//
// The single exception to "never throws" is a `CancellationException` raised
// because the CALLER's own coroutine was cancelled — an exercise leaving the
// screen mid-line. That is the caller's own decision arriving back at it, not an
// audio failure, and swallowing it would break structured concurrency. The line
// is quiesced on the way out either way.

// THE SFX HALF IS NOT WARMED HERE, AND THAT IS A PLATFORM FACT.
//
// iOS's `SfxPlaying` carries `prewarm()`, `isReady` and `suspend()` because an
// `AVAudioEngine` is a live graph: it has to be started, it can be torn down by
// an interruption, and a tap before it is up makes no sound. `SfxPlayer` here is
// a pool of `AudioTrack`s in `MODE_STATIC`, rendered and primed inside its own
// constructor — so "warm" and "constructed" are the same event, there is no
// readiness to poll, and an audio-focus loss does not invalidate a single track.
// The three lifecycle members therefore have no Android counterpart to call, and
// `SfxPlaying` is exactly `play(Sfx)`. What replaces `prewarm()` is *where* the
// player is built: at startup, before the first tap, which is what [live] does.

/**
 * What the engine needs to know about audio focus. The Android analogue of
 * `AudioSessionEvent` in `AudioSession.swift`.
 *
 * There is deliberately no route-change case. iOS has one — headphones pulled —
 * and spec §8 answers it with **do nothing**, because pausing there would settle
 * a success line `false` mid-celebration and can soft-lock a round (§9.1). An
 * event nobody may act on is not worth an `AudioDeviceCallback`, so Android
 * simply does not listen for it.
 */
enum class AudioFocusEvent {
    /** Another app took focus for good. */
    LOST,

    /** A call, an alarm, a navigation prompt. Ours to give back when it ends. */
    LOST_TRANSIENT,

    /** Somebody wants to talk over us. The system lowers our volume; we do nothing. */
    DUCKED,

    /** A transient loss ended and focus came back. */
    REGAINED,
}

interface AudioFocusControlling {
    /** Called on the main thread. */
    var onEvent: ((AudioFocusEvent) -> Unit)?

    /** Idempotent. `false` means the request was refused — which is not a reason to stay quiet. */
    fun request(): Boolean

    fun abandon()
}

/**
 * `AudioManager` focus, with the two decisions that matter spelled out.
 *
 * **Transient, not exclusive.** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` asks the
 * system to lower whatever else is playing for as long as we are speaking, and
 * to restore it afterwards. A plain `AUDIOFOCUS_GAIN` would STOP the other app —
 * and a six-year-old's tablet is very often also the household music player.
 * Killing somebody's playlist because a reading game opened is not our call to
 * make. This is the same reasoning that put `.duckOthers` on the iOS session.
 *
 * **`willPauseWhenDucked = false`.** We want the SYSTEM to duck us when it needs
 * to; the alternative is being told to pause ourselves, and a paused voice line
 * settles `false` mid-sentence.
 *
 * **`acceptsDelayedFocusGain = false`.** A delayed grant would hand us focus at
 * some later unrelated moment and start audio a child did not ask for.
 */
class AndroidAudioFocus(context: Context) : AudioFocusControlling {

    private val manager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override var onEvent: ((AudioFocusEvent) -> Unit)? = null

    private val held = AtomicBoolean(false)

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                held.set(false)
                onEvent?.invoke(AudioFocusEvent.LOST)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onEvent?.invoke(AudioFocusEvent.LOST_TRANSIENT)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent?.invoke(AudioFocusEvent.DUCKED)
            AudioManager.AUDIOFOCUS_GAIN -> {
                held.set(true)
                onEvent?.invoke(AudioFocusEvent.REGAINED)
            }

            else -> Unit
        }
    }

    // Built once and reused: `abandonAudioFocusRequest` matches on the request
    // object, so a second `Builder` would abandon nothing.
    private val request: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(MediaPlayerVoicePlayer.VOICE_ATTRIBUTES)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }

    override fun request(): Boolean {
        if (held.get()) return true
        val audio = manager ?: return false
        val result = try {
            audio.requestAudioFocus(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "audio focus request failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held.set(granted)
        return granted
    }

    override fun abandon() {
        if (!held.getAndSet(false)) return
        val audio = manager ?: return
        try {
            audio.abandonAudioFocusRequest(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not abandon audio focus", e)
        }
    }

    private companion object {
        const val TAG = "AL.AudioFocus"
    }
}

/** Test and preview double, and the honest answer on a device with no AudioManager. */
class NoAudioFocus : AudioFocusControlling {
    override var onEvent: ((AudioFocusEvent) -> Unit)? = null

    var requests: Int = 0
        private set

    var abandons: Int = 0
        private set

    override fun request(): Boolean {
        requests += 1
        return true
    }

    override fun abandon() {
        abandons += 1
    }
}

/**
 * The shipping [AudioEngine].
 *
 * @param bank resolves an utterance to a staged asset (or to nothing, which is
 *   the text-to-speech contract).
 * @param voice plays one baked clip at a time.
 * @param speech the OS voice, for the one utterance with no clip.
 * @param sfx the pre-built effects player. Already warm by construction — see
 *   the note above on why it has no lifecycle hooks.
 * @param focus audio focus, ducking rather than stopping whatever else plays.
 * @param scope where the fades and the warm-up run. Injected, never global.
 */
class LiveAudioEngine(
    private val bank: ClipLocating,
    private val voice: ClipPlayback,
    private val speech: SpeechPlayback,
    private val sfx: SfxPlaying,
    private val focus: AudioFocusControlling,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AudioEngine {

    private val lock = Any()

    /**
     * The line in flight, or `null` when nothing is. Replacing it IS the
     * supersession: the previous holder is completed `false` and its `say()`
     * returns immediately. `CompletableDeferred.complete` is exactly-once by
     * construction, which is what the web gets for free from a Promise and the
     * Swift port has to reproduce with a nulled resolver.
     */
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Set by [unlock]; cleared by a focus loss. In the steady state [unlock]
     * reads this and returns — no allocation, no syscall, which matters because
     * it sits on the pointer-down path 26 times over.
     */
    @Volatile
    private var unlocked = false

    private val warming = AtomicBoolean(false)

    init {
        focus.onEvent = { event -> handleFocus(event) }
    }

    // ---------------------------------------------------------------- AudioEngine

    /**
     * A web autoplay-policy artifact — "make sure we can make noise now" — kept
     * because its 26 call sites are the natural recovery point after an
     * interruption: a child who taps a tile after taking a phone call gets sound
     * back without knowing why it went.
     *
     * Idempotent, and cheap after the first call. It takes audio focus rather
     * than doing it at launch, so a family listening to music is not ducked
     * until the game actually has something to say.
     */
    override fun unlock() {
        if (unlocked) return
        unlocked = true
        focus.request()
        warm()
    }

    /**
     * Call once at mount so the first tap is not the one that pays for a
     * `MediaPlayer` and a text-to-speech service binding. Deliberately does NOT
     * take audio focus — warming is invisible, ducking is not.
     */
    fun prewarm() {
        warm()
    }

    override fun pop() = sfx.play(Sfx.POP)

    override fun success() = sfx.play(Sfx.SUCCESS)

    override fun nudge() = sfx.play(Sfx.NUDGE)

    override fun oops() = sfx.play(Sfx.OOPS)

    /**
     * Speak one line and return when it is DONE.
     *
     * `rate` and `pitch` reach the text-to-speech path ONLY. The web never sets
     * `HTMLAudioElement.playbackRate`, so a baked clip always plays at 1.0;
     * applying rate to clips would re-pitch every success line in the game.
     */
    override suspend fun say(text: String, rate: Double, pitch: Double): Boolean {
        val line = CompletableDeferred<Boolean>()
        // Runs before anything suspends, on the caller's own beat — the same
        // ordering as the TypeScript, where `interruptCurrent` fires before the
        // Promise executor returns.
        beginLine(line)
        return try {
            runLine(text, rate, pitch, line)
        } catch (ce: CancellationException) {
            // The caller walked away mid-line. Quiesce and let it propagate.
            quiesceLine(line)
            throw ce
        } catch (e: Throwable) {
            // Whatever it was, it is not worth a frozen round.
            Log.w(TAG, "say() failed", e)
            settle(line, false)
            false
        }
    }

    /**
     * Leaving an exercise: settle any pending line `false`, then ramp the clip
     * to silence over 200 ms and cut.
     *
     * 10 steps of 20 ms, reproduced literally. Text-to-speech has no
     * mid-utterance volume, so it is simply cancelled.
     */
    override fun stop() {
        val previous = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        speech.cancel()
        previous?.complete(false)
        // Soft: the clip is left sounding for the fade to take down, which is
        // what makes leaving an exercise a fade rather than a cut.
        if (voice.isPlaying) voice.fadeOutAndStop(FADE_STEPS, FADE_INTERVAL_MS)
    }

    // ------------------------------------------------------------------- Extras

    /**
     * The app is going to the background: cut the line, let other apps' music
     * come back up. No background playback is declared anywhere — the game must
     * not play while backgrounded.
     */
    fun enterBackground() {
        interrupt()
        focus.abandon()
        unlocked = false
    }

    /**
     * An interruption took the engine away. Settle the in-flight line `false`,
     * leave the round exactly where it was, award nothing, advance nothing
     * (spec §8).
     */
    fun interrupt() {
        val previous = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        voice.hardStop()
        speech.cancel()
        previous?.complete(false)
    }

    /**
     * Give every native resource back. The composition root is going away.
     *
     * The `as?` on the SFX player is the same idiom `LiveAudioEngine.swift` uses
     * for `preload`: releasing native tracks is a concrete-implementation
     * concern, and putting it on `SfxPlaying` would force every test double to
     * carry a method that only one implementation means anything by.
     */
    fun release() {
        interrupt()
        focus.abandon()
        focus.onEvent = null
        (sfx as? SfxPlayer)?.release()
        voice.release()
        speech.release()
        scope.coroutineContext[Job]?.cancelChildren()
    }

    // ---------------------------------------------------------------- Internals

    private fun handleFocus(event: AudioFocusEvent) {
        when (event) {
            // The voice line is settled `false` and the round stays exactly
            // where it is. The SFX pool needs nothing: a static `AudioTrack`
            // survives a focus loss, and the system simply stops mixing it.
            AudioFocusEvent.LOST, AudioFocusEvent.LOST_TRANSIENT -> {
                interrupt()
                unlocked = false
            }

            // The system is lowering our volume for somebody else and will put it
            // back. Stopping here would settle a success line `false`
            // mid-celebration; being briefly quiet is strictly better.
            AudioFocusEvent.DUCKED -> Unit

            // We do NOT auto-replay the prompt: every exercise already has an
            // on-screen 🔊 « Écouter » button, and re-speaking unasked would race
            // whatever the child does next.
            AudioFocusEvent.REGAINED -> unlock()
        }
    }

    /**
     * Warm the two engines that need warming, off the main thread. (The SFX
     * player is warm the moment it exists — see the note at the top of the
     * file.)
     *
     * Each `prewarm` is idempotent, so re-entering after an interruption simply
     * rebuilds whatever was torn down. `Dispatchers.IO` and not `Main`:
     * `:platform` has only `kotlinx-coroutines-core` on its classpath, and the
     * `Main` dispatcher is contributed by `kotlinx-coroutines-android`. Nothing
     * here needs a Looper anyway — `MediaPlayer` delivers its callbacks on the
     * main one regardless, and everything else is settled through a
     * `CompletableDeferred`, which is thread-safe.
     */
    private fun warm() {
        if (!warming.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                voice.prewarm()
                speech.prewarm()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.w(TAG, "audio warm-up failed", e)
            } finally {
                warming.set(false)
            }
        }
    }

    /**
     * Install [line] as the one in flight and settle whatever it replaced.
     *
     * The order is load-bearing and is the web's, exactly: `pending` is replaced
     * FIRST, then the engines are cancelled, then the previous line is told it
     * lost. Cancelling an engine synthesises a completion callback, and that
     * callback must find a `pending` that is no longer the line it belongs to —
     * otherwise a cancel could settle a line `true`.
     */
    private fun beginLine(line: CompletableDeferred<Boolean>) {
        val previous = synchronized(lock) {
            val current = pending
            pending = line
            current
        }
        voice.hardStop()
        speech.cancel()
        previous?.complete(false)
    }

    /** Settle [line], if it is still the one in flight. Idempotent. */
    private fun settle(line: CompletableDeferred<Boolean>, ok: Boolean) {
        synchronized(lock) {
            if (pending === line) pending = null
        }
        // A no-op when a superseding call already completed it `false`.
        line.complete(ok)
    }

    /** Settle [line] `false` AND stop the engines, for a caller that gave up. */
    private fun quiesceLine(line: CompletableDeferred<Boolean>) {
        val wasCurrent = synchronized(lock) {
            val mine = pending === line
            if (mine) pending = null
            mine
        }
        if (wasCurrent) {
            voice.hardStop()
            speech.cancel()
        }
        line.complete(false)
    }

    private fun isCurrent(line: CompletableDeferred<Boolean>): Boolean =
        synchronized(lock) { pending === line }

    private suspend fun runLine(
        text: String,
        rate: Double,
        pitch: Double,
        line: CompletableDeferred<Boolean>,
    ): Boolean {
        val assetPath = bank.clipAsset(text)
        if (assetPath == null) {
            // No baked clip: stop any in-flight clip so it cannot overlap the
            // spoken line, then speak.
            voice.hardStop()
            return speakTts(text, rate, pitch, line)
        }

        // A leave-fade may be mid-ramp (`fadeRef` + `el.volume = 1` in the TS).
        voice.cancelFade()

        // The provisional watchdog covers the decode as well as the playback the
        // web uses it for, because natively the decode is where a corrupt file
        // actually stalls. A trip here leaves the IO thread blocked in
        // `MediaPlayer.prepare()` — it cannot be interrupted — but the round is
        // freed, which is the half that matters.
        val prepared = withTimeoutOrNull(PROVISIONAL_WATCHDOG_MS.toLong()) {
            withContext(Dispatchers.IO) { voice.prepare(assetPath) }
        }
        // A later line took over while we were decoding: whatever it settles is
        // this call's answer too.
        if (!isCurrent(line)) return line.await()
        if (prepared == null) {
            // `el.onerror` — a decode failure never hangs the round.
            settle(line, false)
            return false
        }

        val started = voice.play(prepared) { ok -> settle(line, ok) }
        if (!started) {
            // `el.play()` rejected. Only fall back if a newer line has not
            // already taken over.
            if (!isCurrent(line)) return line.await()
            return speakTts(text, rate, pitch, line)
        }

        val deadline = if (prepared.durationMs > 0) {
            prepared.durationMs + WATCHDOG_MARGIN_MS.toLong()
        } else {
            PROVISIONAL_WATCHDOG_MS.toLong()
        }
        return awaitLine(line, deadline)
    }

    private suspend fun speakTts(
        text: String,
        rate: Double,
        pitch: Double,
        line: CompletableDeferred<Boolean>,
    ): Boolean {
        val deadline = ttsWatchdogMs(text, rate).toLong()
        // `speak` may call back synchronously when there is no engine — that is
        // the web's `catch { settle(false) }`, and `awaitLine` finds the line
        // already settled.
        speech.speak(text, rate, pitch) { ok -> settle(line, ok) }
        return awaitLine(line, deadline)
    }

    /**
     * Wait for the line, with the watchdog that is the only thing standing
     * between a stalled engine and a round that never advances.
     */
    private suspend fun awaitLine(line: CompletableDeferred<Boolean>, deadlineMs: Long): Boolean {
        val settled = withTimeoutOrNull(deadlineMs.coerceAtLeast(1L)) { line.await() }
        if (settled != null) return settled
        Log.w(TAG, "voice watchdog tripped after $deadlineMs ms")
        settle(line, false)
        return false
    }

    companion object {
        private const val TAG = "AL.Audio"

        /** Grace past a clip's known duration. */
        const val WATCHDOG_MARGIN_MS: Double = 800.0

        /** Held only until a clip reports its duration. */
        const val PROVISIONAL_WATCHDOG_MS: Double = 8000.0

        /** Floor for the estimate-based text-to-speech watchdog. */
        const val TTS_MIN_MS: Double = 1200.0

        /** Rough French speech pace for the text-to-speech estimate. */
        const val TTS_MS_PER_CHAR: Double = 90.0

        /** `10 × 20 ms = 200 ms`. */
        const val FADE_STEPS: Int = 10
        const val FADE_INTERVAL_MS: Long = 20L

        // NB: `TTS_HEARTBEAT_MS = 5000` is deliberately NOT ported. It is a
        // Chrome workaround — pause/resume so a long utterance is not parked —
        // not behaviour, and `android.speech.tts.TextToSpeech` has no such bug.
        // spec §5.2.5, and the iOS port dropped it for the same reason.

        /**
         * `Math.max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate + WATCHDOG_MARGIN_MS`.
         *
         * `text.length` in JavaScript counts UTF-16 code units, and a Kotlin
         * `String.length` IS that count — the one place this port gets the
         * `voKey` trap for free rather than having to write `utf16.count`.
         *
         * The divisor is floored because the contract says `say()` cannot hang,
         * and a `rate` of zero would arm a watchdog at positive infinity. The
         * app only ever passes 0.94.
         */
        fun ttsWatchdogMs(text: String, rate: Double): Double =
            max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate.coerceAtLeast(0.1) +
                WATCHDOG_MARGIN_MS

        /**
         * The shipping composition: the staged clip bank, one reused
         * `MediaPlayer`, the platform text-to-speech engine, and ducking audio
         * focus.
         *
         * The manifest is read here, at construction, rather than lazily: it is
         * a 10 KB text asset and parsing it costs about a millisecond, and
         * paying that at launch is strictly better than paying it inside the
         * first `say()`.
         *
         * @param sfx the effects player. Its constructor renders and primes
         *   every track, so building it here is what "prewarm the SFX" means;
         *   pass a pre-built one to move that work off the calling thread.
         */
        fun live(
            context: Context,
            sfx: SfxPlaying = SfxPlayer.forDevice(context),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): LiveAudioEngine {
            val app = context.applicationContext
            val assets = app.assets
            return LiveAudioEngine(
                bank = AssetClipBank.shipped(assets),
                voice = MediaPlayerVoicePlayer(assets, scope),
                speech = AndroidSpeechFallback(app),
                sfx = sfx,
                focus = AndroidAudioFocus(app),
                scope = scope,
            )
        }
    }
}
