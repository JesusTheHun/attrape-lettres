package fr.dappit.attrapelettres.platform.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import fr.dappit.attrapelettres.core.platform.Haptics

// Haptics: there are NONE today, and shipping none is the port.
//
// Grepping the web app's `src/` and `scripts/` for `vibrate`, `Haptic`, and
// the Capacitor haptics plugin returns zero hits. The PWA ships no haptic
// feedback of any kind; `nudge()` is an audio blip whose name suggests
// otherwise. Behaviour is frozen, so the shipping configuration here is a
// no-op — exactly like `PlatformHaptics.shipping` on iOS and `NoopHaptics` in
// :core — and the seam exists so the product decision can be made later
// without touching a single exercise.
//
// The SYSTEM mode below is that decision, pre-wired and OFF. When it is
// switched on: `light()` on an accepted tap, `soft()` on a wrong tap — soft,
// never an error pattern, because invariant 3 says a wrong tap is not a
// failure.
//
// The Android split mirrors the module's testability rule: unit tests run on
// a bare JVM where `android.os.Vibrator` cannot live, so the DECISIONS —
// mode gating, the event log, "no vibrator is a no-op", "the system haptic
// setting wins" — are pure Kotlin over an injected [HapticEffector], and only
// [AndroidVibratorEffector] touches the platform. Host tests drive a fake
// effector; the Android class is thin enough to have nothing left to test.

/**
 * One buzz, described in neutral terms so the choice of strength is data the
 * host tests can see. `amplitude` is the 1..255 scale
 * `VibrationEffect.createOneShot` takes; hardware without amplitude control
 * plays it at its one strength, which is fine for cues this short.
 */
data class HapticPulse(val durationMs: Long, val amplitude: Int)

/**
 * The device seam. [PlatformHaptics] makes every decision; this only answers
 * three physical questions and, when asked, buzzes.
 */
interface HapticEffector {
    /** False on hardware with no vibrator at all — tablets, some TV boxes. */
    val canVibrate: Boolean

    /**
     * The user's system-wide "touch feedback" switch
     * (`Settings.System.HAPTIC_FEEDBACK_ENABLED`). A user who turned haptics
     * off system-wide has answered the question for every app, this one
     * included.
     */
    val systemHapticsEnabled: Boolean

    fun vibrate(pulse: HapticPulse)
}

class PlatformHaptics(
    val mode: Mode = Mode.OFF,
    private val effector: HapticEffector? = null,
) : Haptics {

    enum class Mode {
        /** What ships. Nothing is emitted, on any device. */
        OFF,

        /** The proposed mapping, live. Not reached until a product decision. */
        SYSTEM,
    }

    enum class Event {
        LIGHT,
        SOFT,
    }

    private val lock = Any()
    private val emitted = mutableListOf<Event>()

    /**
     * Test seam. The only way to assert "the shipping configuration emits
     * nothing" on a host with no vibrator — an absent buzz is otherwise
     * indistinguishable from a buzz nobody can feel.
     */
    val events: List<Event>
        get() = synchronized(lock) { emitted.toList() }

    /** An accepted tap. */
    override fun light() = emit(Event.LIGHT, LIGHT_PULSE)

    /** A WRONG tap — soft, never an error pattern (invariant 3). */
    override fun soft() = emit(Event.SOFT, SOFT_PULSE)

    private fun emit(event: Event, pulse: HapticPulse) {
        // OFF records nothing because it emits nothing; SYSTEM records what it
        // WOULD fire even when the hardware cannot deliver it, so the mapping
        // is proved to exist and proved to be gated — both on a bare JVM.
        if (mode != Mode.SYSTEM) return
        synchronized(lock) { emitted.add(event) }
        val fx = effector ?: return
        // A device with no vibrator is a silent no-op, never a crash; and the
        // user's system-wide haptics switch is respected. Both checks live
        // HERE, in pure Kotlin, so a host test can prove them with a fake.
        if (!fx.canVibrate) return
        if (!fx.systemHapticsEnabled) return
        fx.vibrate(pulse)
    }

    companion object {
        /**
         * A short crisp tick for an accepted tap. Deliberately brief: the
         * audio `pop()` carries the moment, the buzz only underlines it.
         */
        val LIGHT_PULSE = HapticPulse(durationMs = 10, amplitude = 120)

        /**
         * Longer and weaker than [LIGHT_PULSE]: « doucement, non ». The wrong
         * tap must never get the sharper physical cue (invariant 3) — the
         * test asserts the amplitude ordering.
         */
        val SOFT_PULSE = HapticPulse(durationMs = 18, amplitude = 60)

        /** The shipping configuration: silent, everywhere, deliberately. */
        fun shipping(context: Context): PlatformHaptics =
            PlatformHaptics(Mode.OFF, AndroidVibratorEffector(context))
    }
}

/**
 * The one class in this file that touches the platform. Resolution follows
 * the API ladder: `VibratorManager.defaultVibrator` on API 31+, the
 * deprecated `VIBRATOR_SERVICE` below it — same object, older door.
 * `VibrationEffect.createOneShot` needs API 26, which minSdk makes
 * unconditional (A6). Every call is wrapped: haptics may fail to fizz, they
 * may never take the game down (invariant 3).
 */
class AndroidVibratorEffector(private val context: Context) : HapticEffector {

    private val vibrator: Vibrator? = resolveVibrator(context)

    override val canVibrate: Boolean
        get() = try {
            vibrator?.hasVibrator() == true
        } catch (_: Exception) {
            false
        }

    override val systemHapticsEnabled: Boolean
        get() = try {
            // Android's own default for the setting is on; an unreadable
            // setting (restricted profile, odd OEM) reads as the default
            // rather than silently disabling a product decision.
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        } catch (_: Exception) {
            true
        }

    override fun vibrate(pulse: HapticPulse) {
        val target = vibrator ?: return
        try {
            target.vibrate(VibrationEffect.createOneShot(pulse.durationMs, pulse.amplitude))
        } catch (_: Exception) {
            // No permission, a mid-call service death: quiet, never fatal.
        }
    }

    private companion object {
        fun resolveVibrator(context: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }
}
