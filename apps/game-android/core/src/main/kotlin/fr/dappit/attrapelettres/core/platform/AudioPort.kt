package fr.dappit.attrapelettres.core.platform

/* -------------------------------------------------------------------------- */
/* The audio + haptics seam. Interfaces only — :core never sees SoundPool, and  */
/* the whole feedback layer therefore stays host-testable.                      */
/* Port of `AudioApi` in `src/hooks/useAudio.ts`.                               */
/* -------------------------------------------------------------------------- */

/**
 * The four SFX and the single-flight voice channel.
 *
 * `say` is the contract that matters: **it returns `Boolean`, and it can
 * neither throw nor hang.** That is invariant 3 at the audio layer — a missing
 * clip, a dead engine or a cancelled utterance must never leave a child staring
 * at a round that will not advance. Callers gate the next step on the returned
 * flag (`if (!ok || !mountedRef.current) return;` in the TSX), so `false`
 * simply means "superseded or failed" and the round moves on.
 *
 * `false` = the line was superseded by a later `say()`/`stop()`, or errored.
 * `true` = it played to completion.
 *
 * Every non-suspend method is safe to call from a pointer-down handler:
 * `pop()` / `nudge()` schedule a pre-rendered buffer synchronously
 * (invariant 1); they never decode.
 */
// NB: ARCHITECTURE.md §5 sketches this interface as
// `suspend fun say(key: VoKey): Boolean; fun pop(); fun nudge()`. The three
// named members are here under those names; `say` takes the utterance TEXT (as
// `useAudio.ts` does, and as the iOS port already resolved once) because
// `voKey()` is a `(String) -> String` hash applied inside the clip bank, not a
// distinct type the call sites hold. `unlock/success/oops/stop` are the rest of
// `AudioApi` and are needed by the engines; ARCHITECTURE's list was a sketch,
// not an exhaustive one. `say` is the ONLY suspend function in this seam —
// everything else runs on the tap path (invariant 1, A3).
interface AudioEngine {
    /**
     * Warm the engine on the first user gesture. Idempotent — the web original
     * warms the speech engine ONCE, not on every tap.
     */
    fun unlock()

    /** Accepted tap. 660 Hz triangle blip. */
    fun pop()

    /** Round/session cleared. The four-note rising arpeggio. */
    fun success()

    /**
     * Wrong tap. A soft low sine — "soft, non-punishing". This is the ENTIRE
     * wrong-answer penalty (invariant 3), and despite the name it is not a
     * haptic. NB: during `MISS_COOLDOWN_MS` a pick is swallowed BEFORE `pop()`,
     * so the tap still shakes but is silent — deliberate anti-farming asymmetry
     * (invariant 8).
     */
    fun nudge()

    /** The two-note falling "wah-wah" that pairs with « Oh non ! On recommence. » */
    fun oops()

    /**
     * Speak an utterance. Baked clip if one exists for it, TTS otherwise.
     * `rate` and `pitch` apply to the TTS path ONLY — the web never sets
     * `HTMLAudioElement.playbackRate`, so a baked clip always plays at 1.0.
     * Applying rate to clips would change the pitch of every success line.
     *
     * The defaults are the ones every call site but one uses (`useAudio.ts`:
     * rate 0.94, pitch 1.1). They live on the interface, so an implementation
     * cannot quietly substitute its own.
     */
    suspend fun say(text: String, rate: Double = 0.94, pitch: Double = 1.1): Boolean

    /**
     * Interrupt the voice channel: settle the pending `say` `false`, then fade
     * the voice mixer out over 200 ms (10 steps of 20 ms).
     */
    fun stop()
}

/**
 * Test/preview double. Never plays anything, always settles `true` — so a test
 * that awaits a success line does not stall.
 */
class SilentAudioEngine : AudioEngine {
    private val spokenLines = mutableListOf<String>()

    /** Every utterance `say` received, in order. */
    val spoken: List<String>
        get() = spokenLines

    var pops: Int = 0
        private set

    var nudges: Int = 0
        private set

    override fun unlock() {}

    override fun pop() {
        pops += 1
    }

    override fun success() {}

    override fun nudge() {
        nudges += 1
    }

    override fun oops() {}

    override suspend fun say(text: String, rate: Double, pitch: Double): Boolean {
        spokenLines.add(text)
        return true
    }

    override fun stop() {}
}

/**
 * Haptics.
 *
 * There are NONE today: grepping the web app's `src/` and `scripts/` for
 * `vibrate` / `Haptic` returns zero hits, and `nudge()` is an audio blip whose
 * name suggests otherwise. Behaviour is frozen, so the port ships
 * [NoopHaptics]; the seam exists so the decision can be made later without
 * touching a single exercise.
 *
 * When it is made: `light()` on an accepted tap, `soft()` on a wrong tap —
 * `soft`, never an error pattern, because invariant 3 says a wrong tap is not
 * a failure.
 */
interface Haptics {
    fun light()
    fun soft()
}

/** The shipping implementation, today and until a product decision says otherwise. */
class NoopHaptics : Haptics {
    override fun light() {}
    override fun soft() {}
}
