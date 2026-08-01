package fr.dappit.attrapelettres

import android.content.Context
import android.os.Looper
import fr.dappit.attrapelettres.platform.PlatformEnvironment
import fr.dappit.attrapelettres.platform.audio.LiveAudioEngine
import fr.dappit.attrapelettres.platform.audio.PlatformHaptics
import fr.dappit.attrapelettres.platform.audio.SfxPlayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ===========================================================================
// THE COMPOSITION ROOT — the twin of apps/game-ios/App/AttrapeLettres/
// AppEnvironment.swift, and the ONE place in the whole build that may see both
// :ui and :platform.
//
// Everything of substance is already assembled by :platform's
// PlatformEnvironment (kv -> licences -> entitlement -> telemetry -> profiles,
// all host-tested there). This class adds exactly the three things that need an
// app process to hang from:
//
//   1. the AUDIO engine, which :platform deliberately does not build, because
//      one instance must live for the app's lifetime and only the app knows
//      what that lifetime is;
//   2. the PROCESS-lifetime coroutine scope telemetry sends run on;
//   3. the crash-reporting hook — src/telemetry.ts's installErrorReporting(),
//      whose three web listeners are platform wiring and therefore ours (the
//      note is written out in core/telemetry/Telemetry.kt's header).
//
// WHY THIS HANGS OFF Application AND NOT MainActivity. Two reasons, both real:
//
//   - Telemetry's scope must outlive an activity ("a rotation must not cancel
//     an in-flight send" — PlatformEnvironment's own KDoc). An activity-scoped
//     CoroutineScope cannot promise that.
//   - The audio engine owns native AudioTrack and MediaPlayer handles and a
//     TextToSpeech service binding. Rebuilding it on every activity recreation
//     would leak them and would re-pay the prewarm, which is invariant 1's
//     whole point. MainActivity declares android:configChanges for the common
//     cases, but "don't keep activities", a density change or a font-scale
//     change still recreate it, and none of those may cost a child their sound.
//
// ── INVARIANT 11: NOTHING HERE IS A PRECONDITION FOR LAUNCHING ──────────────
// Every construction below is non-throwing by contract and degrades to quiet
// rather than to a locked door: SharedPreferencesKVStore swallows a locked or
// full profile, a corrupt roster blob migrates to a fresh one (loose decoding),
// StubPurchaseStore answers "no store" and canPlay(UNKNOWN) is true, the SFX
// tracks are individually droppable, and both HTTP endpoints are absent by
// default. A device with no store, no network and no working audio still
// launches into a playable game.
//
// ── WHAT IS DELIBERATELY NOT WIRED ─────────────────────────────────────────
//   - SYNC. PlatformEnvironment passes ProfileStore(sync = null) and this file
//     does not override it (A7): services/api's household contract is being
//     reworked and HttpSyncTransport is a labelled sketch whose document codec
//     throws. Merge.kt is pure and fully tested; only the last few hundred
//     bytes of wire plumbing wait for the contract. Wiring it now would be
//     binding to a shape that is about to move.
//   - BILLING. StubPurchaseStore, from :core. No Play Billing dependency, no
//     Play Console product, no BillingClient lifecycle — the obligations a real
//     adapter takes on are enumerated in Adapters.kt and are not small. The
//     consequence is benign in exactly the direction invariant 11 requires:
//     with no store, the entitlement machine can never conclude "not paid" from
//     a failed query, and a child keeps playing.
// ===========================================================================

/**
 * The live object graph, built once per process.
 *
 * MAIN-THREAD BOUND, like the `:core` models it holds — `ProfileStore`,
 * `EntitlementModel` and `Telemetry` are all read from composables on the
 * touch-down path, where invariant 1 forbids a thread hop. Construct it from
 * [AttrapeLettresApplication.onCreate] and nowhere else.
 */
class AppGraph(context: Context) {

    /**
     * Where telemetry's fire-and-forget sends run.
     *
     * `Dispatchers.Main.immediate`, not `Default`, and that is not a mistake:
     * `Telemetry` keeps its queue and its pending-job list in plain unlocked
     * `ArrayList`s because it is documented main-thread bound, so the bookkeeping
     * either side of the network hop has to stay on the main thread. The hop
     * itself does not — `HttpTelemetryTransport.send` opens with
     * `withContext(Dispatchers.IO)`, so no socket is ever touched from here.
     *
     * `SupervisorJob` so one failed send cannot take the scope down with it, and
     * NEVER cancelled: the process ending is its only end.
     *
     * NB `Dispatchers.Main` is resolved through a `ServiceLoader`, so it is a
     * RUNTIME dependency that no compiler checks — which is why
     * `kotlinx-coroutines-android` is named explicitly in `app/build.gradle.kts`
     * rather than left to arrive transitively with AndroidX. Getting that wrong
     * does not fail the build; it kills the app on the line below.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, throwable -> reportCrash(throwable, "coroutine") },
    )

    /** kv, app version, reduce motion, licences, entitlement, telemetry, profiles. */
    val platform: PlatformEnvironment = PlatformEnvironment(context = context, scope = scope)

    /**
     * INVARIANT 1, AND THE REASON THIS LINE IS SEPARATE FROM THE ONE BELOW.
     *
     * `SfxPlayer`'s constructor IS its prewarm: it renders all four sounds to
     * PCM and hands each buffer to a static `AudioTrack`, so `pop()` on the
     * pointer-down path is three JNI calls with no allocation, no decode and no
     * file I/O. Constructing it here means that work is paid during process
     * start-up — before a window exists, let alone a finger. `LiveAudioEngine`
     * would build one lazily on first use, which is precisely the thing
     * invariant 1 forbids, so it is built eagerly and passed in.
     *
     * It stays on the main thread on purpose: it is eight `AudioTrack`
     * allocations over a few thousand synthesised samples, and moving it to a
     * background thread would buy a millisecond of start-up at the price of a
     * window in which a tap is silent — the exact defect the eager build exists
     * to remove.
     */
    private val sfx: SfxPlayer = SfxPlayer.forDevice(context)

    /**
     * The SFX channel + the 845-clip bank + the clip player + the speech
     * fallback + the single-flight voice channel + audio focus.
     *
     * ONE instance for the app's lifetime, never one per exercise: binding
     * `TextToSpeech` costs a service connection, and paying it on exercise entry
     * would risk a mute first line. What IS per-exercise is the teardown — the
     * engine screens call `stop()` on dispose.
     *
     * It keeps its OWN internal scope (the `live` default: `Dispatchers.Default`)
     * rather than borrowing [scope]. The fades and the warm-up are background
     * work by nature and have no business queueing behind the main looper.
     */
    val audio: LiveAudioEngine = LiveAudioEngine.live(context = context, sfx = sfx)

    /**
     * The haptics seam, in its shipping configuration: `Mode.OFF`, on every
     * device. The PWA has zero haptics — grepping `apps/game-web/src` for
     * `vibrate` returns nothing — and behaviour is frozen, so nothing consumes
     * this yet and `RootView` does not take it. It is held here anyway, exactly
     * as `AppEnvironment.swift` holds it, so that the day a product decision
     * lands only this line and its one consumer move.
     */
    val haptics: PlatformHaptics = PlatformHaptics.shipping(context)

    /**
     * `window.onerror` / `unhandledrejection`, as far as this platform honestly
     * allows.
     *
     * Deliberately NOT consent-gated, matching `Telemetry.reportError`: the
     * payload is a truncated message and a stack and nothing else — no roster,
     * no name, no device information — so it is not personal data and we still
     * hear about the bug that breaks the game for a parent who declined
     * analytics (invariant 10, Kids Category 1.3).
     *
     * Two honest limits, stated rather than implied:
     *
     *  - A crash on a NON-main thread is dropped. `Telemetry`'s queue is
     *    unlocked and main-bound by contract, and racing it from a dying thread
     *    would be a data race for no gain, so this returns early instead.
     *  - Even on the main thread, delivery is best-effort. An uncaught exception
     *    kills the Android process; the POST is a coroutine that will usually
     *    never be dispatched. The fix, if crash telemetry ever has to be
     *    reliable, is an on-disk spool written synchronously here and replayed
     *    at the next launch — not a longer wait in a dying process.
     *
     * All of which is moot today: `ALTelemetryURL` is not declared in the
     * manifest, so `Telemetry` is inert and this path posts nowhere.
     */
    fun reportCrash(throwable: Throwable, site: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            platform.telemetry.reportError(throwable, site)
        } catch (_: Throwable) {
            // A reporter that throws while reporting must not change what the
            // app was already doing — which, at the two call sites, is dying.
        }
    }

    /**
     * The app is no longer visible: cut the voice line, give audio focus back so
     * the family's music comes up, and push whatever telemetry is queued.
     *
     * This is the web's `visibilitychange` + `pagehide` pair. `keepalive: true`
     * needs no analogue here — [scope] outlives every activity by construction,
     * so a send already in flight simply keeps going; `awaitPendingSends()` is
     * called to drain the bookkeeping list rather than to hold anything open,
     * and `HttpTelemetryTransport`'s 15 s timeouts bound it.
     */
    fun enterBackground() {
        audio.enterBackground()
        platform.telemetry.flush()
        scope.launch { platform.telemetry.awaitPendingSends() }
    }

    /**
     * The app is in front of a child again.
     *
     *  - Re-sample reduced motion: the setting can change while backgrounded and
     *    a parent expects the mascot to settle down without a relaunch.
     *  - Re-arm the audio graph BEFORE a finger can arrive. `unlock()` on the
     *    tap path already heals a suspended graph, but invariant 1 says the tap
     *    must not PAY for it. Idempotent and cheap once warm.
     *
     * The sync + licence half of a resume is not here: it belongs to `RootView`,
     * which owns the mount/resume effect. The activity only signals.
     */
    fun enterForeground() {
        platform.reduceMotion.refresh()
        audio.prewarm()
    }
}
