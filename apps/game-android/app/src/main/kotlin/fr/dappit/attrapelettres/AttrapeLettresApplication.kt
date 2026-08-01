package fr.dappit.attrapelettres

import android.app.Application

/**
 * The process. Builds [AppGraph] once and installs the crash hook; nothing
 * else belongs here.
 *
 * WHY THERE IS AN Application CLASS AT ALL — this app has one activity, and a
 * one-activity app can usually get away without one. Two things make it the
 * right home anyway, both spelled out in `AppGraph`'s header: telemetry's scope
 * has to outlive an activity, and the audio engine holds native handles that
 * must be built once and never rebuilt. Hanging them off the activity would
 * make an activity recreation — a font-scale change, "don't keep activities",
 * a density change — leak native tracks and re-pay the prewarm that invariant 1
 * exists to have already paid.
 *
 * `onCreate` here runs before the first window. That is the point: it is where
 * the SFX tracks get primed, so the first tap of the session is not the one
 * that pays for them.
 */
class AttrapeLettresApplication : Application() {

    /**
     * The live adapters. `lateinit` rather than a lazy: the whole reason this
     * class exists is that the graph is built EAGERLY at process start, and a
     * `by lazy` would quietly move that cost to whoever touched it first —
     * which, for the audio half, could be a child's finger.
     */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // `window.onerror`. Installed after the graph, so the reporter it
        // closes over is ready; installed before any UI, so a crash during the
        // first composition is still seen. Always chains to the platform
        // handler — see CrashReporter.
        Thread.setDefaultUncaughtExceptionHandler(
            CrashReporter(
                previous = Thread.getDefaultUncaughtExceptionHandler(),
                report = { throwable -> graph.reportCrash(throwable, "uncaught") },
            )
        )
    }
}
