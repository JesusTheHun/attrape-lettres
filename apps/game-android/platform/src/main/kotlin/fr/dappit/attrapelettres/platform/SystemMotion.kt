package fr.dappit.attrapelettres.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource

// ---------------------------------------------------------------------------
// Reduced motion — ONE source of truth (invariant 6).
//
// The mascot and the confetti honour it; independent per-screen reads would be
// that many chances to forget one, and tests could not force the setting on.
// So: this samples the system once, keeps listening, and everything downstream
// reads the injected ReduceMotionSource.
//
// ANDROID HAS NO prefers-reduced-motion. The honest analogue is
// Settings.Global.ANIMATOR_DURATION_SCALE == 0f, which is what a user gets
// from "Remove animations" in Accessibility or from turning animation scales
// off in Developer options. This is a WEAKER signal than the web's media
// query, and we accept it knowingly:
//
//  - It is an all-animations-off switch, not a motion-sensitivity preference.
//    A vestibular user who wants LESS motion but has not flipped the global
//    kill switch reads as 1f, where iOS's Reduce Motion toggle would have
//    caught them. There is simply no finer-grained signal on this platform.
//  - It conflates intents: a developer benchmarking with scales at zero is
//    indistinguishable from an accessibility need. Both read as "reduced".
//
// Both errors land on the calm side — at worst the mascot settles down for
// someone who did not ask — which is the right way for a children's app to be
// wrong. And it deliberately gates only what the web gates: mascot and
// confetti, never press/shake feedback (ARCHITECTURE §3 row 6).
// ---------------------------------------------------------------------------

/**
 * [ReduceMotionSource] over the platform animation setting.
 *
 * The value is CACHED, not read through on every access: `isReduced` is read
 * on the render path, and `Settings.Global.getFloat` is a ContentProvider
 * round trip that has no business inside a frame. A `@Volatile` Boolean is
 * the whole cost; the [ContentObserver] and an explicit [refresh] are what
 * keep it fresh.
 *
 * The sampler is injected so a host test can prove the caching and the
 * re-sampling actually work — a test that could only read the real setting
 * could never change it, and would prove nothing. The same seam is why this
 * class is constructible on a bare JVM at all; only [from] and [readSystem]
 * touch the Android runtime.
 */
class SystemReduceMotion(private val read: () -> Boolean) : ReduceMotionSource {

    @Volatile
    private var value: Boolean = read()

    override val isReduced: Boolean
        get() = value

    /**
     * Re-sample now. Called by the settings observer; also the hook for an
     * ON_RESUME re-check, because the setting can change while the app is
     * backgrounded and a parent expects the mascot to settle down without a
     * relaunch.
     */
    fun refresh() {
        value = read()
    }

    companion object {
        /**
         * The production source: sampled from [readSystem], re-sampled
         * whenever the animator-duration-scale setting row changes.
         *
         * The observer is registered on the application context and never
         * unregistered — the source is a process-lifetime singleton wired at
         * the composition root, so "as long as the process" is exactly the
         * right lifetime, and there is no teardown moment at which
         * unregistering would be anything but dead code.
         */
        fun from(context: Context): SystemReduceMotion {
            val app = context.applicationContext
            val source = SystemReduceMotion { readSystem(app) }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    source.refresh()
                }
            }
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
            return source
        }

        /**
         * The current system setting — the ONLY place the platform API is
         * named. Exactly 0f means animations are off; any positive scale
         * (0.5x, 1x, 5x) is a speed preference, not a motion aversion, and
         * reads as not-reduced. An unreadable setting defaults to 1f and
         * therefore to full motion, matching the web where an unsupported
         * media query simply never matches.
         */
        fun readSystem(context: Context): Boolean =
            try {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            } catch (_: Exception) {
                false
            }
    }
}
