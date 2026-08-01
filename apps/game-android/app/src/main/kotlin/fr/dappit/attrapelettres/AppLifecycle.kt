package fr.dappit.attrapelettres

// ===========================================================================
// The two POLICIES :app owns, as plain Kotlin — no Android type, no Compose,
// no coroutine — so `./gradlew :app:test` can drive them on the host.
//
// This is the same split :ui made for the same reason (A11): a host test cannot
// start an Activity any more than it can invoke a @Composable, so the decision
// lives in a plain class and the Activity is a thin renderer over it. What is
// left in MainActivity after this file is four `super` calls and one
// `setContent`, and there is nothing in those four calls a test could learn
// anything from.
//
// Both policies exist because of the same worry: the moments handled here — a
// resume, a backgrounding, a crash — are the moments where the app is least
// able to absorb a new failure, and where a failure is most likely to reach a
// child as a frozen or vanished game.
// ===========================================================================

/**
 * Everything the foreground/background fan-out can ask for.
 *
 * An interface rather than five lambdas so a test can assert on ORDER as
 * cheaply as on occurrence — "the audio graph is re-armed before the sync is
 * signalled" is a claim about sequence, and a recording double makes it one
 * assertion.
 */
interface AppLifecycleSink {

    /**
     * Re-sample the reduced-motion setting and re-arm the audio graph. Both are
     * idempotent; both must happen before a finger can arrive (invariant 1).
     */
    fun enterForeground()

    /**
     * Tell the UI a resume happened. `RootView` owns what that MEANS — pull,
     * merge, push, then re-check the licence — and this signal is the only
     * thing the activity contributes to it, which is what keeps `:ui` free of
     * the Android lifecycle and therefore host-testable.
     */
    fun signalResume()

    /** Cut the voice line, hand audio focus back, push queued telemetry. */
    fun enterBackground()
}

/**
 * The activity lifecycle, as a state machine with one interesting rule.
 *
 * THE RULE: the FIRST `onResume` does not signal a resume; every later one
 * does. `RootView`'s effect is keyed on the ticker's value and its initial
 * value already fires the mount sync — "launch and resume share one code path",
 * as its own doc puts it. Ticking on the first resume too would run
 * pull-merge-push twice within a second of launch. That is harmless (the merge
 * is idempotent by construction, which is invariant 9's other half) but it is
 * two round trips and two licence refreshes for nothing, and "harmless
 * duplicate" is exactly the kind of thing that stops being harmless when
 * somebody later hangs a counter off it.
 *
 * The foreground work is NOT subject to that rule. It runs on every resume,
 * including the first: a launch is also a moment when the audio graph may not
 * be armed and the motion setting has not been read since the process started.
 *
 * FAILURE POLICY (invariant 11, and invariant 3's spirit). Every call out is
 * individually guarded. A resume that throws — a dead audio graph, a settings
 * provider that refuses, a transport that breaks its non-throwing contract —
 * must not take the app down in a child's hands, and must not stop the REST of
 * the resume from happening. A swallowed failure is routed to [onFailure],
 * which the app points at crash reporting; the default drops it, so a test
 * that does not care about failures does not have to say so.
 */
class AppLifecycle(
    private val sink: AppLifecycleSink,
    private val onFailure: (Throwable, String) -> Unit = { _, _ -> },
) {

    /** How many times [onResume] has been called. The first is the launch. */
    var resumes: Int = 0
        private set

    /** How many times [onStop] has been called. */
    var stops: Int = 0
        private set

    fun onResume() {
        resumes += 1
        guarded("resume.foreground") { sink.enterForeground() }
        if (resumes > 1) {
            guarded("resume.signal") { sink.signalResume() }
        }
    }

    fun onStop() {
        stops += 1
        guarded("stop.background") { sink.enterBackground() }
    }

    private fun guarded(site: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            // Deliberately Throwable and not Exception. What we are protecting
            // against here is not a tidy IOException; it is a
            // NoClassDefFoundError from an audio backend that is not on this
            // device, or an assertion inside a vendor settings provider.
            onFailure(throwable, site)
        }
    }
}

/**
 * `installErrorReporting()`'s `window.onerror` half — the last thing that runs
 * before the process dies.
 *
 * Two properties, and they are the only two that matter:
 *
 *  1. It ALWAYS delegates to [previous]. Android's own default handler is what
 *     produces the crash dialog, the logcat tombstone and the Play Console
 *     report; swallowing the exception here would make the app disappear
 *     silently and would delete the crash from the one place a release engineer
 *     will actually look.
 *  2. A failure inside [report] cannot change (1). Reporting is best-effort by
 *     nature — see `AppGraph.reportCrash` for what "best-effort" honestly means
 *     on a platform that kills the process — and a bug in the reporter must not
 *     turn a crash into a hang.
 *
 * [previous] is nullable because `Thread.getDefaultUncaughtExceptionHandler()`
 * is; in an Android process it never actually is, but a host test constructs
 * this class with nothing behind it and the type should say so.
 */
class CrashReporter(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val report: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            report(throwable)
        } catch (_: Throwable) {
            // See (2) above.
        }
        previous?.uncaughtException(thread, throwable)
    }
}
