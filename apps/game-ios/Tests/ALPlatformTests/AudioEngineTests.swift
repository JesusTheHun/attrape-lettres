import ALCore
import Testing

@testable import ALPlatform

// Invariant 1 at the audio layer.
//
// `pop()` and `nudge()` are called SYNCHRONOUSLY inside the pointer-down
// handler, before any state commit — and on a wrong tap both fire on the same
// instant. If either one decodes, allocates a player or touches the filesystem,
// a child feels the delay and no functional test notices. So it is proved
// structurally: the loader is a spy, and after `unlock()` it must record zero
// calls no matter how many sounds are played.

@MainActor
@Suite("LiveAudioEngine — the tap path")
struct AudioEngineTests {

    private func makeEngine() -> (LiveAudioEngine, SpySfx, FakeClipPlayback, FakeSpeechPlayback, NoopAudioSession) {
        let sfx = SpySfx()
        let clip = FakeClipPlayback(baked: ["chat"], duration: 1.5)
        let speech = FakeSpeechPlayback()
        let session = NoopAudioSession()
        let engine = LiveAudioEngine(
            sfx: sfx,
            clip: clip,
            speech: speech,
            session: session,
            timers: FakeAudioTimers()
        )
        return (engine, sfx, clip, speech, session)
    }

    @Test("pop() and nudge() never reach the clip loader")
    func tapPathDoesNoIO() {
        let (engine, sfx, clip, speech, _) = makeEngine()
        engine.unlock()

        // A wrong tap: pop() AND nudge() on the same synchronous beat.
        engine.pop()
        engine.nudge()
        // …then an accepted tap and a completed round.
        engine.pop()
        engine.success()
        engine.oops()

        #expect(clip.clipURLCalls == 0, Comment(rawValue: "the tap path hit the clip bank"))
        #expect(clip.prepareCalls == 0, Comment(rawValue: "the tap path decoded a clip"))
        #expect(speech.spoken.isEmpty)
        #expect(sfx.played == [.pop, .nudge, .pop, .success, .oops])
    }

    @Test("unlock() is idempotent and cheap after the first call")
    func unlockIsIdempotent() {
        let (engine, sfx, _, _, session) = makeEngine()
        // 26 call sites, every one of them on a pointer-down path.
        for _ in 0..<26 { engine.unlock() }
        #expect(sfx.prewarms == 1)
        #expect(session.activations == 1)
    }

    @Test("the SFX are silent before unlock(), exactly like `if (!ctx) return`")
    func sfxBeforeUnlockAreSwallowed() {
        let (engine, sfx, _, _, _) = makeEngine()
        engine.pop()
        // The spy records the call; the real graph drops it because `isReady`
        // is false. What matters is that it does not crash and does not decode.
        #expect(sfx.prewarms == 0)
        #expect(sfx.isReady == false)
    }

    @Test("say() goes through the voice channel and honours the clip path")
    func sayUsesTheChannel() async {
        let (engine, _, clip, speech, _) = makeEngine()
        engine.unlock()
        let task = Task { await engine.say("chat", rate: 0.98, pitch: 1.1) }
        #expect(await yieldUntil { clip.lastOnEnd != nil })
        #expect(speech.spoken.isEmpty, Comment(rawValue: "a baked line went to TTS"))
        clip.finish(true)
        #expect(await task.value == true)
    }

    @Test("stop() settles the in-flight line false — the `onDisappear` contract")
    func stopSettlesFalse() async {
        let (engine, _, clip, _, _) = makeEngine()
        engine.unlock()
        let task = Task { await engine.say("chat") }
        #expect(await yieldUntil { clip.lastOnEnd != nil })
        engine.stop()
        #expect(await task.value == false)
        #expect(clip.fades == [FakeClipPlayback.Fade(steps: 10, intervalMs: 20)])
    }

    @Test("backgrounding cuts the line, suspends the graph and releases the session")
    func backgroundingReleasesEverything() async {
        let (engine, sfx, clip, _, session) = makeEngine()
        engine.unlock()
        let task = Task { await engine.say("chat") }
        #expect(await yieldUntil { clip.lastOnEnd != nil })

        engine.enterBackground()
        #expect(await task.value == false)
        #expect(sfx.suspends == 1)
        #expect(session.deactivations == 1)

        // The next tap brings the engine back — that is why `unlock()` stays on
        // 26 call sites rather than being called once at launch.
        engine.unlock()
        #expect(sfx.prewarms == 2)
        #expect(session.activations == 2)
    }

    @Test("the default rate and pitch are the web's 0.94 / 1.1")
    func defaultRateAndPitch() async {
        let (engine, _, _, speech, _) = makeEngine()
        engine.unlock()
        // "42" is unbaked, so it takes the TTS path where rate/pitch are real.
        let task = Task { await (engine as AudioEngine).say("42") }
        #expect(await yieldUntil { speech.lastOnEnd != nil })
        #expect(speech.lastRate == 0.94)
        #expect(speech.lastPitch == 1.1)
        speech.finish(true)
        #expect(await task.value == true)
    }
}
