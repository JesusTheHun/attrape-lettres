package fr.dappit.attrapelettres.core.platform

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the iOS `PlatformPortsTests.swift` suites. These interfaces are what
 * every module is implemented against, so their contracts — synchronous reads,
 * exact millisecond arithmetic, a `say` that always settles — are themselves
 * under test.
 */
class KVStoreTest {

    @Test
    fun `round trip`() {
        val kv = InMemoryKVStore()
        assertNull(kv.string("attrape-lettres:roster:v4"))
        kv.set("attrape-lettres:roster:v4", "{}")
        assertEquals("{}", kv.string("attrape-lettres:roster:v4"))
        kv.set("attrape-lettres:roster:v4", "{\"a\":1}")
        assertEquals("{\"a\":1}", kv.string("attrape-lettres:roster:v4"))
        kv.remove("attrape-lettres:roster:v4")
        assertNull(kv.string("attrape-lettres:roster:v4"))
    }

    @Test
    fun `seeded from a map`() {
        val kv = InMemoryKVStore(mapOf("k" to "v"))
        assertEquals("v", kv.string("k"))
        assertEquals(mapOf("k" to "v"), kv.snapshot)
        kv.removeAll()
        assertTrue(kv.snapshot.isEmpty())
    }

    @Test
    fun `writes are visible to the next synchronous read`() {
        // Invariant 1 in the type system (A3): a value written is readable by
        // the very next SYNCHRONOUS statement, with nothing to await. If
        // KVStore ever grows a suspend requirement this file stops compiling —
        // which is the point.
        val kv: KVStore = InMemoryKVStore()
        kv.set("balance", "42")
        assertEquals("42", kv.string("balance"))
    }
}

class TimeSourceTest {

    @Test
    fun `mutable time starts where it is put`() {
        val time = MutableTimeSource(1_700_000_000_000)
        assertEquals(1_700_000_000_000, time.nowMillis)
    }

    @Test
    fun `advancing days uses exact milliseconds`() {
        val time = MutableTimeSource(0)
        time.advance(days = 14.0)
        assertEquals(14 * 86_400_000L, time.nowMillis)
        time.advance(days = 0.5)
        assertEquals(14 * 86_400_000L + 43_200_000L, time.nowMillis)
    }

    @Test
    fun `advancing millis is exact`() {
        val time = MutableTimeSource(1_000)
        time.advance(millis = 800L)
        assertEquals(1_800L, time.nowMillis)
    }

    @Test
    fun `system time is epoch millis, not seconds`() {
        // A seconds-based bug would land three orders of magnitude low; this
        // bound is 2023-11-14 and the app did not ship before it.
        val now = SystemTimeSource().nowMillis
        assertTrue(now > 1_700_000_000_000)
        assertTrue(now < 100_000_000_000_000)
    }

    @Test
    fun `injecting a time source is enough to walk a fortnight`() {
        val mutable = MutableTimeSource(0)
        val time: TimeSource = mutable
        val start = time.nowMillis
        mutable.advance(days = 14.0)
        assertEquals(1_209_600_000L, time.nowMillis - start)
    }
}

class AppVersionTest {

    @Test
    fun `fixed provider reports what it was given`() {
        val provider: AppVersionProvider = FixedAppVersion("0.1.0")
        assertEquals("0.1.0", provider.marketing)
    }
}

class ReduceMotionTest {

    @Test
    fun `can be forced both ways`() {
        assertTrue(FixedReduceMotion(true).isReduced)
        assertFalse(FixedReduceMotion(false).isReduced)
    }
}

class AudioPortTest {

    @Test
    fun `say returns and cannot throw`() {
        // Invariant 3 at the audio layer: say returns a Boolean, cannot throw,
        // and must settle. A stub that never returned would hang this test.
        val audio = SilentAudioEngine()
        val ok = runBlocking { audio.say("Bravo ! Tu as tout réussi !") }
        assertTrue(ok)
        assertEquals(listOf("Bravo ! Tu as tout réussi !"), audio.spoken)
    }

    @Test
    fun `the default rate and pitch are the web ones`() {
        // 0.94 / 1.1, the defaults in useAudio.ts. The interface defaults must
        // pass those through rather than 1.0/1.0.
        class Recorder : AudioEngine {
            var rate = 0.0
            var pitch = 0.0

            override fun unlock() {}
            override fun pop() {}
            override fun success() {}
            override fun nudge() {}
            override fun oops() {}
            override suspend fun say(text: String, rate: Double, pitch: Double): Boolean {
                this.rate = rate
                this.pitch = pitch
                return true
            }
            override fun stop() {}
        }

        val recorder = Recorder()
        runBlocking { recorder.say("coucou") }
        assertEquals(0.94, recorder.rate, absoluteTolerance = 0.0)
        assertEquals(1.1, recorder.pitch, absoluteTolerance = 0.0)
    }

    @Test
    fun `sfx are synchronous and countable`() {
        val audio = SilentAudioEngine()
        audio.pop()
        audio.pop()
        audio.nudge()
        assertEquals(2, audio.pops)
        assertEquals(1, audio.nudges)
    }

    @Test
    fun `haptics are no-op today`() {
        // There are no haptics in the PWA (zero hits for `vibrate`/`Haptic`),
        // so the port ships the no-op. This test exists to make a future
        // wiring a deliberate act: both calls are the entire contract —
        // nothing to observe, nothing thrown.
        val haptics: Haptics = NoopHaptics()
        haptics.light()
        haptics.soft()
    }
}
