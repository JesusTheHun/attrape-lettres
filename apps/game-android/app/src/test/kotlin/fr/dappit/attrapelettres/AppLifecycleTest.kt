package fr.dappit.attrapelettres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ===========================================================================
// The two policies :app owns, driven directly on the host.
//
// Everything ELSE in this module is honestly untestable here and is not faked
// into looking tested: `AttrapeLettresApplication.onCreate` needs a process,
// `MainActivity.onCreate` needs an Activity, `AppGraph`'s constructor needs a
// `Context`, an `AssetManager` and an `AudioTrack`, and `RootView` needs a
// composition (A11). Those are device concerns; what a JVM CAN check is the
// sequencing and the failure behaviour, and both of those are exactly where a
// launch or a resume goes wrong in a way a child notices.
// ===========================================================================

/** Records what was asked for, in order. */
private class RecordingSink(
    private val failOn: String? = null,
) : AppLifecycleSink {

    val calls = mutableListOf<String>()

    override fun enterForeground() = record("enterForeground")

    override fun signalResume() = record("signalResume")

    override fun enterBackground() = record("enterBackground")

    private fun record(name: String) {
        calls.add(name)
        if (name == failOn) throw IllegalStateException("boom in $name")
    }
}

class AppLifecycleTest {

    // --- The resume rule ----------------------------------------------------

    /**
     * The FIRST resume warms the world and says nothing. `RootView`'s effect is
     * keyed on the ticker and its initial value already fires the mount sync;
     * ticking here too would run pull-merge-push twice inside a second of
     * launching.
     */
    @Test
    fun firstResumeWarmsButDoesNotSignal() {
        val sink = RecordingSink()
        val phases = AppLifecycle(sink)

        phases.onResume()

        assertEquals(listOf("enterForeground"), sink.calls)
        assertEquals(1, phases.resumes)
    }

    /** Every LATER resume signals — that is the whole point of the signal. */
    @Test
    fun everyResumeAfterTheFirstSignals() {
        val sink = RecordingSink()
        val phases = AppLifecycle(sink)

        phases.onResume()
        phases.onStop()
        phases.onResume()
        phases.onStop()
        phases.onResume()

        assertEquals(
            listOf(
                "enterForeground",
                "enterBackground",
                "enterForeground", "signalResume",
                "enterBackground",
                "enterForeground", "signalResume",
            ),
            sink.calls,
        )
        assertEquals(3, phases.resumes)
        assertEquals(2, phases.stops)
    }

    /**
     * ORDER, and it is invariant 1's: the audio graph is re-armed BEFORE
     * anything else a resume does. A finger can land on the first frame after a
     * resume, and `unlock()` on the tap path must find a warm graph rather than
     * pay for one.
     */
    @Test
    fun theAudioGraphIsRearmedBeforeTheResumeIsSignalled() {
        val sink = RecordingSink()
        val phases = AppLifecycle(sink)

        phases.onResume()
        phases.onResume()

        val warm = sink.calls.indexOf("enterForeground")
        val signal = sink.calls.indexOf("signalResume")
        assertTrue(warm in 0 until signal, "foreground work must precede the resume signal")
    }

    // --- The failure policy (invariant 11) ----------------------------------

    /**
     * A resume that throws must not reach a child, and must not stop the rest
     * of the resume from happening. A dead audio backend is a quiet game, never
     * a crashed one.
     */
    @Test
    fun aThrowingForegroundStepDoesNotStopTheResumeOrEscape() {
        val sink = RecordingSink(failOn = "enterForeground")
        val failures = mutableListOf<Pair<Throwable, String>>()
        val phases = AppLifecycle(sink) { throwable, site -> failures.add(throwable to site) }

        phases.onResume()
        phases.onResume()

        // Both resumes ran their foreground step, both threw, and the SECOND
        // still went on to signal.
        assertEquals(
            listOf("enterForeground", "enterForeground", "signalResume"),
            sink.calls,
        )
        assertEquals(2, failures.size)
        assertEquals(listOf("resume.foreground", "resume.foreground"), failures.map { it.second })
    }

    /** Same contract on the way out. Backgrounding is not allowed to crash either. */
    @Test
    fun aThrowingBackgroundStepIsSwallowedAndReported() {
        val sink = RecordingSink(failOn = "enterBackground")
        val failures = mutableListOf<String>()
        val phases = AppLifecycle(sink) { _, site -> failures.add(site) }

        phases.onStop()

        assertEquals(listOf("enterBackground"), sink.calls)
        assertEquals(listOf("stop.background"), failures)
        assertEquals(1, phases.stops)
    }

    /**
     * `Throwable`, not `Exception`. What this guards against is not a tidy
     * `IOException` — it is a `NoClassDefFoundError` from an audio backend that
     * is not on this device, or an assertion inside a vendor settings provider.
     */
    @Test
    fun evenAnErrorIsAbsorbed() {
        val sink = object : AppLifecycleSink {
            override fun enterForeground(): Unit = throw NoClassDefFoundError("android.media.Nope")
            override fun signalResume() = Unit
            override fun enterBackground() = Unit
        }
        val seen = mutableListOf<Throwable>()
        val phases = AppLifecycle(sink) { throwable, _ -> seen.add(throwable) }

        phases.onResume()

        assertEquals(1, seen.size)
        assertTrue(seen.single() is NoClassDefFoundError)
    }

    /** The default reporter is a drop, so a test that does not care need not say so. */
    @Test
    fun failuresAreSwallowedWithNoReporterWired() {
        val phases = AppLifecycle(RecordingSink(failOn = "enterBackground"))
        phases.onStop()
        assertEquals(1, phases.stops)
    }

    // --- The crash hook -----------------------------------------------------

    /**
     * The one property that matters: the platform handler ALWAYS runs. It is
     * what produces the tombstone, the crash dialog and the Play Console
     * report, and swallowing the exception here would make the app vanish
     * silently and delete the crash from the only place anyone will look.
     */
    @Test
    fun theCrashHookReportsThenDelegates() {
        val order = mutableListOf<String>()
        val boom = RuntimeException("kaboom")
        var delegated: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, throwable ->
            order.add("previous")
            delegated = throwable
        }

        val reporter = CrashReporter(previous) { order.add("report") }
        reporter.uncaughtException(Thread.currentThread(), boom)

        assertEquals(listOf("report", "previous"), order)
        assertSame(boom, delegated)
    }

    /** A reporter that breaks must not turn a crash into a different crash. */
    @Test
    fun aThrowingReporterStillDelegates() {
        var delegated = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }

        val reporter = CrashReporter(previous) { error("the reporter is broken too") }
        reporter.uncaughtException(Thread.currentThread(), RuntimeException("kaboom"))

        assertTrue(delegated, "a failed report must not swallow the crash")
    }

    /**
     * No previous handler is a no-op, not an NPE. Android always has one; a
     * host test does not, and the type says so.
     */
    @Test
    fun aMissingPreviousHandlerIsTolerated() {
        var reported = 0
        val reporter = CrashReporter(previous = null) { reported += 1 }
        reporter.uncaughtException(Thread.currentThread(), RuntimeException("kaboom"))
        assertEquals(1, reported)
    }

    /**
     * The delegate is called with the ORIGINAL throwable, unwrapped and
     * un-rethrown by us — and a delegate that itself throws is the platform's
     * business, not ours, so it propagates.
     */
    @Test
    fun theDelegateOwnsWhatHappensNext() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> throw IllegalStateException("os") }
        val reporter = CrashReporter(previous) { }
        assertFailsWith<IllegalStateException> {
            reporter.uncaughtException(Thread.currentThread(), RuntimeException("kaboom"))
        }
    }
}
