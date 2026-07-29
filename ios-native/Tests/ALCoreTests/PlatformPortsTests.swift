import Testing

@testable import ALCore

@Suite struct KVStoreTests {
    @Test func roundTrip() {
        let kv = InMemoryKVStore()
        #expect(kv.string("attrape-lettres:roster:v4") == nil)
        kv.set("{}", for: "attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == "{}")
        kv.set("{\"a\":1}", for: "attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == "{\"a\":1}")
        kv.remove("attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == nil)
    }

    @Test func seededFromADictionary() {
        let kv = InMemoryKVStore(["k": "v"])
        #expect(kv.string("k") == "v")
        #expect(kv.snapshot == ["k": "v"])
        kv.removeAll()
        #expect(kv.snapshot.isEmpty)
    }

    /// Both spellings in the specs resolve to the same single requirement.
    @Test func theConvenienceSpellingsAgree() {
        let kv = InMemoryKVStore()
        kv.set("device-1", "abc")
        #expect(kv.get("device-1") == "abc")
        #expect(kv.string("device-1") == "abc")
    }

    /// Invariant 1 in the type system (D7): a value written is readable by the
    /// very next SYNCHRONOUS statement, with nothing to await. If `KVStore` ever
    /// grows an `async` requirement this file stops compiling — which is the
    /// point.
    @Test func writesAreVisibleToTheNextSynchronousRead() {
        let kv: KVStore = InMemoryKVStore()
        kv.set("42", for: "balance")
        #expect(kv.string("balance") == "42")
    }
}

@Suite struct TimeSourceTests {
    @Test func mutableTimeStartsWhereItIsPut() {
        let time = MutableTimeSource(1_700_000_000_000)
        #expect(time.nowMillis == 1_700_000_000_000)
    }

    @Test func advancingDaysUsesExactMilliseconds() {
        let time = MutableTimeSource(0)
        time.advance(days: 14)
        #expect(time.nowMillis == 14 * 86_400_000)
        time.advance(days: 0.5)
        #expect(time.nowMillis == 14 * 86_400_000 + 43_200_000)
    }

    @Test func advancingMillisIsExact() {
        let time = MutableTimeSource(1_000)
        time.advance(millis: 800)
        #expect(time.nowMillis == 1_800)
    }

    @Test func systemTimeIsEpochMillisNotSeconds() {
        // A seconds-based bug would land three orders of magnitude low; this
        // bound is 2023-11-14 and the app did not ship before it.
        let now = SystemTimeSource().nowMillis
        #expect(now > 1_700_000_000_000)
        #expect(now < 100_000_000_000_000)
    }

    @Test func injectingATimeSourceIsEnoughToWalkAFortnight() {
        let time: TimeSource = MutableTimeSource(0)
        let start = time.nowMillis
        (time as! MutableTimeSource).advance(days: 14)
        #expect(time.nowMillis - start == 1_209_600_000)
    }
}

@Suite struct AppVersionTests {
    @Test func fixedProviderReportsWhatItWasGiven() {
        let provider: AppVersionProvider = FixedAppVersion("0.1.0")
        #expect(provider.marketing == "0.1.0")
    }
}

@Suite struct ReduceMotionTests {
    @Test func canBeForcedBothWays() {
        #expect(FixedReduceMotion(true).isReduced)
        #expect(!FixedReduceMotion(false).isReduced)
    }
}

@Suite struct AudioPortTests {
    /// Invariant 3 at the audio layer: `say` returns a `Bool`, cannot throw, and
    /// must settle. A stub that never returned would hang this test.
    @Test func sayReturnsAndCannotThrow() async {
        let audio = SilentAudioEngine()
        let ok = await audio.say("Bravo ! Tu as tout réussi !")
        #expect(ok)
        #expect(audio.spoken == ["Bravo ! Tu as tout réussi !"])
    }

    @Test func theDefaultRateAndPitchAreTheWebOnes() async {
        // 0.94 / 1.1, the defaults in useAudio.ts. The convenience overload must
        // pass those through rather than 1.0/1.0.
        final class Recorder: AudioEngine {
            var rate = 0.0
            var pitch = 0.0
            func unlock() {}
            func pop() {}
            func success() {}
            func nudge() {}
            func oops() {}
            func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
                self.rate = rate
                self.pitch = pitch
                return true
            }
            func stop() {}
        }
        let recorder = Recorder()
        _ = await recorder.say("coucou")
        #expect(recorder.rate == 0.94)
        #expect(recorder.pitch == 1.1)
    }

    @Test func sfxAreSynchronousAndCountable() {
        let audio = SilentAudioEngine()
        audio.pop()
        audio.pop()
        audio.nudge()
        #expect(audio.pops == 2)
        #expect(audio.nudges == 1)
    }

    /// There are no haptics in the PWA (zero hits for `vibrate`/`Haptic`), so
    /// the port ships the no-op. This test exists to make a future wiring a
    /// deliberate act.
    @Test func hapticsAreNoOpToday() {
        let haptics: Haptics = NoopHaptics()
        haptics.light()
        haptics.soft()
        #expect(haptics is NoopHaptics)
    }
}
