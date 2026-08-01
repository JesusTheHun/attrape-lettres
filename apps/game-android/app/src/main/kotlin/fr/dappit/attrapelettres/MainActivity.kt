package fr.dappit.attrapelettres

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.dappit.attrapelettres.ui.ChangeTicker
import fr.dappit.attrapelettres.ui.RootView
import fr.dappit.attrapelettres.ui.screens.DevScreen

/**
 * The one activity, and close to nothing else — the twin of
 * `AttrapeLettresApp.swift`.
 *
 * Everything of substance is elsewhere: the object graph in [AppGraph] (built
 * by [AttrapeLettresApplication] at process start), the policies in
 * [AppLifecycle], every screen in `:ui`. What is left here is three lifecycle
 * callbacks and one `setContent` — deliberately, because none of them can be
 * reached by a host test and everything they decide has been moved somewhere
 * that can (A11's split, applied to an Activity instead of a @Composable).
 *
 * DELIBERATELY NOT HERE:
 *
 *  - No `ViewModel`, no `rememberSaveable` route. The route is one
 *    `mutableStateOf` inside `RootView`, and `android:configChanges` covers the
 *    configuration changes a phone in a child's hands actually produces, so a
 *    round survives them without a save/restore path to get wrong.
 *  - No `BackHandler`. `RootView` has no back stack on purpose — a nav library
 *    would put its own gesture detector above the exercise tiles, which is
 *    invariant 1's territory, and would give a six-year-old a stack to fall
 *    down. The consequence is real and is recorded as a gap: system back leaves
 *    the app from anywhere, including mid-round. Fixing it properly means
 *    hoisting the route out of `RootView`, which is a `:ui` change and not one
 *    to smuggle in here.
 *  - No `audio.release()` in `onDestroy`. The engine is process-scoped by
 *    design; releasing it when this activity goes away would mute a recreated
 *    one. Its native handles are reclaimed when the process ends, which is the
 *    only end it has.
 */
class MainActivity : ComponentActivity() {

    /**
     * The resume signal `RootView` expects. Held by the activity because it is
     * the activity that knows when a resume happened, and created as a field —
     * not inside the composition — so a tick that arrives before the first
     * composition is still there when the composition reads it.
     */
    private val resume = ChangeTicker()

    private lateinit var graph: AppGraph

    /**
     * Not named `lifecycle`: `ComponentActivity` already owns that name for the
     * AndroidX `Lifecycle`, and a same-named property here would be an
     * accidental override rather than a shadow.
     */
    private lateinit var phases: AppLifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the system bars, on every API level. Not decoration:
        // `RootView`'s `ShellFrame` computes its gutter from
        // `WindowInsets.safeDrawing`, and those insets are only reported once
        // the window stops fitting system windows. Without this the shell would
        // see zero insets below API 35 (where the platform forces edge-to-edge
        // anyway) and behave differently across two halves of the supported
        // range — including for the IME inset that keeps the « Ton prénom »
        // field above the keyboard.
        enableEdgeToEdge()

        graph = (application as AttrapeLettresApplication).graph
        phases = AppLifecycle(
            sink = object : AppLifecycleSink {
                override fun enterForeground() = graph.enterForeground()
                override fun signalResume() = resume.tick()
                override fun enterBackground() = graph.enterBackground()
            },
            onFailure = { throwable, site -> graph.reportCrash(throwable, site) },
        )

        // The web's `#stages` / `#vo`. Android has no URL hash, so the stand-in
        // is an Intent extra:
        //
        //     adb shell am start -n fr.dappit.attrapelettres/.MainActivity \
        //         -e alDevScreen stages
        //
        // Gated on the build being debuggable, so both benches are unreachable
        // in anything a child can install. Read from `ApplicationInfo` rather
        // than `BuildConfig.DEBUG` because it is the same fact without turning
        // `buildFeatures.buildConfig` on for one boolean — and because it is
        // exactly the flag `DevScreen.parse`'s parameter is named after.
        val dev = DevScreen.parse(
            value = intent?.getStringExtra(DevScreen.EXTRA),
            debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )

        // Every parameter is a `:core` interface or a `:core` model. `:ui` never
        // sees `:platform` — this call is the only place the two halves of the
        // module graph meet (ARCHITECTURE §1), which is what lets the compiler
        // guarantee no screen reached for a SharedPreference or an AudioTrack
        // behind our back.
        setContent {
            RootView(
                profiles = graph.platform.profiles,
                entitlement = graph.platform.entitlement,
                telemetry = graph.platform.telemetry,
                audio = graph.audio,
                kv = graph.platform.kv,
                time = graph.platform.time,
                reduceMotion = graph.platform.reduceMotion,
                resume = resume,
                dev = dev,
            )
        }
    }

    /**
     * Foreground. [AppLifecycle] decides what that means, including the rule
     * that the FIRST resume does not signal `RootView` — its mount effect
     * already covers launch.
     */
    override fun onResume() {
        super.onResume()
        phases.onResume()
    }

    /**
     * No longer visible. `onStop`, not `onPause`: a dialog or a permission
     * sheet over the game leaves it visible and must not cut the voice line,
     * while a home press, a screen off or a task switch must — the game
     * declares no background playback and must never be heard from behind
     * another app.
     *
     * Before `super`, so the queued telemetry starts leaving while the process
     * is still unambiguously alive.
     */
    override fun onStop() {
        phases.onStop()
        super.onStop()
    }
}
