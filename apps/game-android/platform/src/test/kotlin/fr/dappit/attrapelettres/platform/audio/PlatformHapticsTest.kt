package fr.dappit.attrapelettres.platform.audio

import fr.dappit.attrapelettres.core.platform.Haptics
import fr.dappit.attrapelettres.core.platform.NoopHaptics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Haptics: the PWA has NONE, so the port ships none. Port of the iOS suite
// `AudioHapticsTests.swift`, plus the Android-only gating properties (no
// vibrator, the system haptic setting) that iOS delegates to UIKit.
//
// "No haptics" is the awkward thing to test — an absent buzz on a JVM with no
// vibrator is indistinguishable from a buzz nobody can feel. Hence the event
// log: OFF records nothing because it emits nothing, and SYSTEM records what
// it would fire, so the seam is proved to exist AND proved not to be wired.
// The fake effector answers the two physical questions on demand, which is
// what lets a bare JVM prove the device rules.

private class FakeEffector(
    override var canVibrate: Boolean = true,
    override var systemHapticsEnabled: Boolean = true,
) : HapticEffector {
    val pulses = mutableListOf<HapticPulse>()

    override fun vibrate(pulse: HapticPulse) {
        pulses.add(pulse)
    }
}

class PlatformHapticsTest {

    @Test
    fun `the shipping configuration emits nothing, on any device`() {
        val effector = FakeEffector()
        val haptics = PlatformHaptics(PlatformHaptics.Mode.OFF, effector)
        haptics.light()
        haptics.soft()
        haptics.light()
        assertTrue(haptics.events.isEmpty())
        assertTrue(effector.pulses.isEmpty())
    }

    @Test
    fun `the default mode is off — a new call site cannot accidentally buzz`() {
        val haptics = PlatformHaptics()
        haptics.light()
        haptics.soft()
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun `the system mapping exists behind the flag, ready for a product decision`() {
        val effector = FakeEffector()
        val haptics = PlatformHaptics(PlatformHaptics.Mode.SYSTEM, effector)
        haptics.light() // an accepted tap
        haptics.soft() // a WRONG tap — soft, never an error pattern (invariant 3)
        assertEquals(listOf(PlatformHaptics.Event.LIGHT, PlatformHaptics.Event.SOFT), haptics.events)
        assertEquals(listOf(PlatformHaptics.LIGHT_PULSE, PlatformHaptics.SOFT_PULSE), effector.pulses)
    }

    @Test
    fun `a device with no vibrator is a silent no-op, never a crash`() {
        val effector = FakeEffector(canVibrate = false)
        val haptics = PlatformHaptics(PlatformHaptics.Mode.SYSTEM, effector)
        haptics.light()
        haptics.soft()
        // The mapping still logs what it WOULD fire — the seam exists — but
        // nothing reaches hardware that is not there.
        assertEquals(2, haptics.events.size)
        assertTrue(effector.pulses.isEmpty())
    }

    @Test
    fun `the system-wide haptic setting is respected`() {
        val effector = FakeEffector(systemHapticsEnabled = false)
        val haptics = PlatformHaptics(PlatformHaptics.Mode.SYSTEM, effector)
        haptics.light()
        haptics.soft()
        assertTrue(effector.pulses.isEmpty())

        // …and the setting is read per emit, so flipping it back mid-session
        // is honoured without rebuilding anything.
        effector.systemHapticsEnabled = true
        haptics.light()
        assertEquals(listOf(PlatformHaptics.LIGHT_PULSE), effector.pulses)
    }

    @Test
    fun `no effector at all cannot crash — the pure half stands alone`() {
        val haptics = PlatformHaptics(PlatformHaptics.Mode.SYSTEM, effector = null)
        haptics.light()
        haptics.soft()
        assertEquals(2, haptics.events.size)
    }

    @Test
    fun `soft is gentler than light — a wrong tap is not a failure`() {
        // Invariant 3 in physical form: the wrong-tap cue must never be the
        // sharper one. Both pulses stay inside createOneShot's legal ranges,
        // and both stay short — an underline, not a rumble.
        assertTrue(PlatformHaptics.SOFT_PULSE.amplitude < PlatformHaptics.LIGHT_PULSE.amplitude)
        for (pulse in listOf(PlatformHaptics.LIGHT_PULSE, PlatformHaptics.SOFT_PULSE)) {
            assertTrue(pulse.amplitude in 1..255)
            assertTrue(pulse.durationMs in 1..50)
        }
    }

    @Test
    fun `it is injectable as core's Haptics, and still emits nothing through the interface`() {
        // The exercises only ever see core's Haptics. Going through the
        // interface is what a call site does, and it must not re-enable
        // anything the concrete type turned off.
        val effector = FakeEffector()
        val concrete = PlatformHaptics(PlatformHaptics.Mode.OFF, effector)
        val shipping: Haptics = concrete
        shipping.light()
        shipping.soft()
        assertTrue(concrete.events.isEmpty())
        assertTrue(effector.pulses.isEmpty())

        // core's own default must be interchangeable with it.
        val fallback: Haptics = NoopHaptics()
        fallback.light()
        fallback.soft()

        val both: List<Haptics> = listOf(shipping, fallback)
        assertEquals(2, both.size)
    }
}
