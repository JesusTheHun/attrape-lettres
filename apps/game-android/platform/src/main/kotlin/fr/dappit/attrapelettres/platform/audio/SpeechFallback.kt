package fr.dappit.attrapelettres.platform.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// `speechSynthesis` -> `android.speech.tts.TextToSpeech`, via
// `SpeechFallback.swift`.
//
// Exactly ONE product path reaches this in normal play: tapping the score to
// hear the balance read out (`String(profile.balance)`, `App.tsx:44`) — the only
// utterance in the app with no baked clip. Its real job is the safety net: a
// missing or corrupt clip degrades to the OS voice rather than to silence, which
// is what keeps invariant 3 true.
//
// NOTHING here may reach the network. The web scores `localService === false`
// (+2 for network voices) and that is deliberately INVERTED, not ported: any
// server-side synthesis sends the utterance to a third party, and this app posts
// to nobody but its own endpoint (invariant 10, and the Kids Category rule that
// no PII or device information goes to third parties). Android is the one
// platform that answers the question outright —
// `Voice.isNetworkConnectionRequired` — so the picker below drops those voices
// instead of ranking them up.

/**
 * The `Voice.getQuality()` ladder, as a value type so the picker is
 * host-testable without an installed voice inventory.
 */
enum class VoiceQuality {
    STANDARD,
    ENHANCED,
    PREMIUM,
    ;

    companion object {
        /**
         * Android reports quality as an int on a 100..500 scale. `VERY_HIGH` is
         * the downloaded neural voice, `HIGH` the good embedded one, and
         * everything at or below `NORMAL` is the tinny default — which is the
         * same three-way split iOS gets from `AVSpeechSynthesisVoice.Quality`.
         */
        fun ofAndroidQuality(quality: Int): VoiceQuality = when {
            quality >= Voice.QUALITY_VERY_HIGH -> PREMIUM
            quality >= Voice.QUALITY_HIGH -> ENHANCED
            else -> STANDARD
        }
    }
}

/** One installed voice, reduced to the four facts the picker uses. */
data class FrenchVoiceCandidate(
    val name: String,
    /** A BCP-47 tag: `fr`, `fr-FR`, `fr_CA`. */
    val language: String,
    val quality: VoiceQuality,
    /** `Voice.isNetworkConnectionRequired` — a hard exclusion, see the header. */
    val networkRequired: Boolean,
)

/**
 * Port of `voiceScore` / `pickBestFr`, in Android terms.
 *
 * The web scores NAMES because the browser gives it nothing better:
 * `/enhanced|premium|neural|siri/ -> +5`, a `+3` list of French voice names
 * (Amélie, Thomas, Aurélie…), `-5` for `compact|espeak`. Every one of those is a
 * proxy for the same question — "is this the good download or the tinny
 * built-in?" — and `Voice.getQuality()` answers it directly. So the name
 * heuristics disappear, exactly as they did on iOS, and the RANKING is
 * preserved:
 *
 * ```
 * +5  PREMIUM        (web: /enhanced|premium|neural|siri/ -> +5)
 * +4  ENHANCED
 * +1  language is fr-FR  (web: /^fr-FR/ -> +1, France French for a 6yo in fr)
 * ```
 *
 * `localService === false -> +2` is not ported; network voices are excluded
 * outright (see the file header).
 */
object FrenchVoicePicker {

    /**
     * `/fr($|[-_])/i` — `fr`, `fr-FR`, `fr_CA`. Not `fry`, not `frr`.
     *
     * The JavaScript regex is unanchored and would also match `af-fr`; the
     * prefix reading is what iOS shipped and what a BCP-47 tag actually means,
     * since the language subtag comes first by construction.
     */
    fun isFrench(language: String): Boolean {
        val lower = language.lowercase(Locale.ROOT)
        if (!lower.startsWith("fr")) return false
        if (lower.length == 2) return true
        val third = lower[2]
        return third == '-' || third == '_'
    }

    /** `/^fr-FR/i`, with the underscore spelling Android sometimes produces. */
    fun isFranceFrench(language: String): Boolean {
        val lower = language.lowercase(Locale.ROOT)
        return lower.startsWith("fr-fr") || lower.startsWith("fr_fr")
    }

    fun score(candidate: FrenchVoiceCandidate): Int {
        var score = when (candidate.quality) {
            VoiceQuality.PREMIUM -> 5
            VoiceQuality.ENHANCED -> 4
            VoiceQuality.STANDARD -> 0
        }
        if (isFranceFrench(candidate.language)) score += 1
        return score
    }

    /**
     * Highest score wins; `null` when nothing usable and French is installed, in
     * which case the caller leaves the engine on its own default voice and only
     * sets the language.
     *
     * Ties keep the inventory's own order. `Array.prototype.sort` is stable in
     * every engine the PWA runs on, and `maxByOrNull` returns the FIRST maximal
     * element, so the two agree — otherwise the chosen voice could differ
     * between two launches with the same inventory.
     */
    fun pickBest(candidates: List<FrenchVoiceCandidate>): FrenchVoiceCandidate? =
        candidates
            .filter { isFrench(it.language) && !it.networkRequired }
            .maxByOrNull { score(it) }
}

/**
 * `u.rate` / `u.pitch`, and the one place Android is KINDER than iOS.
 *
 * `AVSpeechUtterance.rate` is a 0…1 dial whose default is 0.5, so the iOS port
 * has to scale the web's `0.94` or produce near-maximum gabble — the single most
 * likely way to get that file wrong. `TextToSpeech.setSpeechRate` and
 * `setPitch` are both 1.0-is-normal, the same scale as
 * `SpeechSynthesisUtterance`, so the web's numbers cross unchanged and there is
 * no conversion to get wrong. The clamps below exist only so a future caller
 * cannot hand the engine a zero or a negative, which it answers with `ERROR`.
 */
object SpeechRate {
    /** `setSpeechRate` returns ERROR for anything `<= 0`. */
    fun ttsRate(webRate: Double): Float = webRate.coerceIn(0.1, 4.0).toFloat()

    /** Both scales are 1.0-is-normal; the web itself only ever sends 1.1. */
    fun ttsPitch(webPitch: Double): Float = webPitch.coerceIn(0.5, 2.0).toFloat()
}

/**
 * The fallback half of the voice channel.
 *
 * `onEnd(true)` = the engine reported `onDone`; `onEnd(false)` = it errored, was
 * stopped, or is not there at all. It may be called synchronously — that is the
 * web's `catch { settle(false) }`, and the engine handles it.
 */
interface SpeechPlayback {
    /**
     * Bind the engine, resolve the voice. Idempotent, safe off the main thread,
     * and meant for mount: `TextToSpeech` init is a service binding and is
     * genuinely asynchronous, so an engine warmed at first tap is an engine that
     * is not ready for that tap.
     */
    fun prewarm()

    fun speak(text: String, rate: Double, pitch: Double, onEnd: (Boolean) -> Unit)

    /** `speechSynthesis.cancel()`. */
    fun cancel()

    /** Unbind. The app is going away. */
    fun release()
}

/** [SpeechPlayback] over `android.speech.tts.TextToSpeech`. */
class AndroidSpeechFallback(context: Context) : SpeechPlayback {

    private val appContext = context.applicationContext

    // The instance and its init status are two facts that arrive in an
    // unpredictable ORDER: `onInit` is dispatched from the service connection
    // and can fire before the constructor has even returned, so neither side may
    // assume the other has landed. Both write their half and then call
    // `configureIfReady`, and whichever is second does the configuring. That is
    // race-free without a lock and without assuming which thread we are on.
    private val engineRef = AtomicReference<TextToSpeech?>(null)
    private val initStatus = AtomicInteger(STATUS_PENDING)
    private val creating = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    /** True once the engine is bound AND French is actually available. */
    @Volatile
    private var ready = false

    private val lock = Any()
    private var handler: ((Boolean) -> Unit)? = null
    private var liveId: String? = null
    private val utteranceCounter = AtomicInteger(0)

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Nothing. The web has no analogue and the channel's watchdog is
            // already armed by the time this could fire.
        }

        override fun onDone(utteranceId: String?) {
            finish(utteranceId, true)
        }

        // Abstract in `UtteranceProgressListener` and deprecated in the same
        // breath: it must be implemented, and the engine calls the two-argument
        // form instead on anything this app will ever run on.
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            finish(utteranceId, false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "text-to-speech error $errorCode on $utteranceId")
            finish(utteranceId, false)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // A stop is a supersession, and the channel has already settled that
            // line `false` by the time the engine gets here. Reported anyway so
            // a line stopped by the ENGINE (not by us) cannot hang.
            finish(utteranceId, false)
        }
    }

    override fun prewarm() {
        if (engineRef.get() != null) return
        if (!creating.compareAndSet(false, true)) return
        val created = try {
            TextToSpeech(appContext, TextToSpeech.OnInitListener { status ->
                initStatus.set(status)
                configureIfReady()
            })
        } catch (e: RuntimeException) {
            // No engine installed, or one that refuses to bind. Every line then
            // speaks from its baked clip and the balance read-out is silent —
            // which is quieter, never broken (invariant 3).
            Log.e(TAG, "no usable text-to-speech engine", e)
            null
        }
        engineRef.set(created)
        configureIfReady()
    }

    private fun configureIfReady() {
        val engine = engineRef.get() ?: return
        if (initStatus.get() != TextToSpeech.SUCCESS) return
        if (!configured.compareAndSet(false, true)) return
        try {
            engine.setOnUtteranceProgressListener(progress)
            val language = engine.setLanguage(Locale.FRANCE)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                // A device with no French voice data. The baked bank is the whole
                // game's voice anyway, so this costs exactly one utterance: the
                // score read-out.
                Log.w(TAG, "no French text-to-speech data on this device")
                return
            }
            selectVoice(engine)
            ready = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not configure the text-to-speech engine", e)
        }
    }

    /**
     * Pick the least-robotic LOCAL French voice, and leave the engine alone if
     * there is not one. `voiceschanged` is not ported: the inventory is stable
     * per binding, and the language is fixed `fr`.
     */
    private fun selectVoice(engine: TextToSpeech) {
        val installed: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: RuntimeException) {
            // Some engines throw here rather than answering. `setLanguage` has
            // already picked something reasonable, so this is not fatal.
            Log.w(TAG, "text-to-speech engine would not list its voices", e)
            return
        }
        val byName = installed.associateBy { it.name }
        val candidates = installed.map { voice ->
            FrenchVoiceCandidate(
                name = voice.name,
                language = voice.locale.toLanguageTag(),
                quality = VoiceQuality.ofAndroidQuality(voice.quality),
                networkRequired = voice.isNetworkConnectionRequired,
            )
        }
        val picked = FrenchVoicePicker.pickBest(candidates) ?: return
        byName[picked.name]?.let { engine.setVoice(it) }
    }

    override fun speak(text: String, rate: Double, pitch: Double, onEnd: (Boolean) -> Unit) {
        // Idempotent, and the honest thing to do for a caller that never called
        // `prewarm`: it costs one atomic read in the steady state.
        prewarm()
        val engine = engineRef.get()
        if (engine == null || !ready) {
            // Either no engine at all, or one whose binding has not landed yet.
            // The web resolves its voice synchronously and iOS can too; Android
            // cannot, and the answer is NOT to wait — `say()` may not hang, and
            // the mount-time `prewarm()` is what makes this window empty in
            // practice. Settle `false` and let the round move on (invariant 3).
            onEnd(false)
            return
        }
        val id = "al-" + utteranceCounter.incrementAndGet()
        synchronized(lock) {
            handler = onEnd
            liveId = id
        }
        val result = try {
            engine.setSpeechRate(SpeechRate.ttsRate(rate))
            engine.setPitch(SpeechRate.ttsPitch(pitch))
            // QUEUE_FLUSH IS `speechSynthesis.cancel()` followed by `speak()`:
            // whatever was in flight is dropped, so voice never overlaps voice.
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: RuntimeException) {
            Log.w(TAG, "text-to-speech refused an utterance", e)
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            detach(id)?.invoke(false)
        }
    }

    override fun cancel() {
        // Detach BEFORE stopping: `stop()` synthesises an `onStop`, and the
        // channel has already settled this line. Same ordering the TypeScript
        // gets from nulling `pendingRef` before `speechSynthesis.cancel()`.
        synchronized(lock) {
            handler = null
            liveId = null
        }
        try {
            engineRef.get()?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "text-to-speech would not stop", e)
        }
    }

    override fun release() {
        cancel()
        val engine = engineRef.getAndSet(null) ?: return
        configured.set(false)
        creating.set(false)
        initStatus.set(STATUS_PENDING)
        ready = false
        try {
            engine.shutdown()
        } catch (e: RuntimeException) {
            Log.w(TAG, "text-to-speech would not shut down", e)
        }
    }

    /**
     * Take the pending resolver, but only if it still belongs to [id]. Returns
     * `null` when a later line has already taken over, which is what makes
     * settling exactly-once.
     */
    private fun detach(id: String?): ((Boolean) -> Unit)? = synchronized(lock) {
        if (id == null || liveId != id) return@synchronized null
        val taken = handler
        handler = null
        liveId = null
        taken
    }

    private fun finish(utteranceId: String?, ok: Boolean) {
        detach(utteranceId)?.invoke(ok)
    }

    private companion object {
        const val TAG = "AL.Speech"

        /** Neither `TextToSpeech.SUCCESS` nor `ERROR`: "no answer yet". */
        const val STATUS_PENDING = Int.MIN_VALUE
    }
}

/**
 * Test and preview double: there is no engine, so every line settles `false`
 * immediately. Pure Kotlin, usable from a host test.
 */
class SilentSpeechFallback : SpeechPlayback {
    override fun prewarm() {}

    override fun speak(text: String, rate: Double, pitch: Double, onEnd: (Boolean) -> Unit) {
        onEnd(false)
    }

    override fun cancel() {}

    override fun release() {}
}
