import Testing

@testable import ALPlatform

// `src/hooks/useAudio.test.ts`, ported one for one, plus the cases the TS suite
// leaves to the browser (stale callbacks, the provisional watchdog, the TTS
// deadline arithmetic).
//
// Every expected number below is derived from `useAudio.ts`, not read back out
// of `VoiceChannel`:
//
//   WATCHDOG_MARGIN_MS      800
//   PROVISIONAL_WATCHDOG_MS 8000
//   TTS_MIN_MS              1200
//   TTS_MS_PER_CHAR         90
//   arm(el.duration * 1000 + WATCHDOG_MARGIN_MS)
//   arm(Math.max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate + WATCHDOG_MARGIN_MS)

@MainActor
@Suite("Voice channel — the single-flight contract")
struct AudioVoiceChannelTests {

    private func makeChannel(
        baked: Set<String> = ["chat", "chien"],
        duration: Double? = 2.0
    ) -> (VoiceChannel, FakeClipPlayback, FakeSpeechPlayback, FakeAudioTimers) {
        let clip = FakeClipPlayback(baked: baked, duration: duration)
        let speech = FakeSpeechPlayback()
        let timers = FakeAudioTimers()
        return (VoiceChannel(clip: clip, speech: speech, timers: timers), clip, speech, timers)
    }

    // MARK: - Resolution

    @Test("resolves true when the clip plays to its end")
    func resolvesTrueOnEnd() {
        let (channel, clip, _, _) = makeChannel()
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }
        #expect(settled.isEmpty)
        clip.finish(true)
        #expect(settled == [true])
    }

    @Test("a new say() supersedes the in-flight line, resolving it false, exactly once")
    func supersessionSettlesTheFirstFalseOnce() {
        let (channel, clip, _, _) = makeChannel()
        var first: [Bool] = []
        var second: [Bool] = []

        channel.start("chat") { first.append($0) }
        channel.start("chien") { second.append($0) }

        #expect(first == [false])
        #expect(second.isEmpty)

        clip.finish(true)
        #expect(second == [true])
        // Settled once and once only — in Swift a second resume traps, so this
        // is not a style point.
        #expect(first == [false])
    }

    @Test("a stale engine callback after a supersede cannot settle the new line")
    func staleCallbackIsDropped() {
        let (channel, clip, _, _) = makeChannel()
        var first: [Bool] = []
        var second: [Bool] = []

        channel.start("chat") { first.append($0) }
        let staleEnd = clip.lastOnEnd
        channel.start("chien") { second.append($0) }

        staleEnd?(true)  // the superseded clip reports it finished

        #expect(second.isEmpty, Comment(rawValue: "the OLD clip's 'ended' settled the NEW line"))
        #expect(first == [false])
    }

    @Test("resolves false on a media error, so a decode failure never hangs the round")
    func mediaErrorResolvesFalse() {
        let (channel, clip, speech, _) = makeChannel()
        clip.prepareFails = true
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }
        #expect(settled == [false])
        // A decode failure is NOT the unbaked path: it does not silently swap in
        // a different voice mid-narration.
        #expect(speech.spoken.isEmpty)
    }

    @Test("stop() resolves the in-flight line false and is safe when idle")
    func stopResolvesFalseAndIsIdempotent() {
        let (channel, clip, _, _) = makeChannel()
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }

        channel.stop()
        #expect(settled == [false])
        // 200 ms leave-fade: 10 steps of 20 ms, reproduced literally.
        #expect(clip.fades == [FakeClipPlayback.Fade(steps: 10, intervalMs: 20)])

        channel.stop()  // idle
        #expect(settled == [false])
        #expect(clip.fades.count == 1)
    }

    @Test("play() refusing to start falls back to speech rather than stranding the caller")
    func playStartFailureFallsBackToTts() {
        let (channel, clip, speech, _) = makeChannel()
        clip.playFails = true
        var settled: [Bool] = []
        channel.start("chat", rate: 0.98, pitch: 1.1) { settled.append($0) }
        #expect(speech.spoken == ["chat"])
        #expect(settled.isEmpty)
        speech.finish(true)
        #expect(settled == [true])
    }

    // MARK: - Watchdogs

    @Test("the duration-derived watchdog is armed at duration × 1000 + 800 ms")
    func durationWatchdogArithmetic() {
        let (channel, _, _, timers) = makeChannel(duration: 2.0)
        channel.start("chat") { _ in }
        // Provisional first (8000, until the real duration lands), then the
        // duration-derived one: 2.0 × 1000 + 800.
        #expect(timers.scheduledMs == [8000, 2800])
        #expect(channel.armedWatchdogMs == 2800)
    }

    @Test("a stalled clip is resolved false by its watchdog, never left hanging")
    func stalledClipResolvesFalse() {
        let (channel, _, _, timers) = makeChannel(duration: 2.0)
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }
        #expect(settled.isEmpty)
        timers.fireLatest()
        #expect(settled == [false])
    }

    @Test("the provisional watchdog stands at 8000 ms when no duration is reported")
    func provisionalWatchdog() {
        let (channel, _, _, timers) = makeChannel(duration: nil)
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }
        #expect(timers.scheduledMs == [8000])
        timers.fireLatest()
        #expect(settled == [false])
    }

    @Test("the TTS watchdog is max(1200, utf16 count × 90) / rate + 800")
    func ttsWatchdogArithmetic() {
        // 26 UTF-16 units × 90 = 2340 > the 1200 floor; rate 1 ⇒ 2340 + 800.
        let long = "abcdefghijklmnopqrstuvwxyz"
        #expect(VoiceChannel.ttsWatchdogMs(for: long, rate: 1.0) == 3140)

        // 3 × 90 = 270, so the floor wins: 1200 + 800.
        #expect(VoiceChannel.ttsWatchdogMs(for: "oui", rate: 1.0) == 2000)

        // rate divides the estimate: 2340 / 0.9 + 800.
        #expect(abs(VoiceChannel.ttsWatchdogMs(for: long, rate: 0.9) - (2340 / 0.9 + 800)) < 1e-9)

        // `text.length` in JS is UTF-16 CODE UNITS. 20 accented characters are
        // 20 units; 20 emoji are 40. Counting Characters would give 1800 for
        // both, and the second line's watchdog would fire mid-utterance.
        #expect(VoiceChannel.ttsWatchdogMs(for: String(repeating: "é", count: 20), rate: 1.0) == 2600)
        #expect(VoiceChannel.ttsWatchdogMs(for: String(repeating: "🎉", count: 20), rate: 1.0) == 4400)
    }

    @Test("the TTS path arms its own watchdog and resolves false when it trips")
    func ttsWatchdogIsArmedAndFires() {
        let (channel, _, _, timers) = makeChannel(baked: [])
        var settled: [Bool] = []
        channel.start("quarante-deux", rate: 0.85, pitch: 1.1) { settled.append($0) }
        // 13 UTF-16 units × 90 = 1170 < 1200 floor ⇒ 1200 / 0.85 + 800.
        #expect(abs((timers.pending?.ms ?? 0) - (1200 / 0.85 + 800)) < 1e-9)
        timers.fireLatest()
        #expect(settled == [false])
    }

    // MARK: - The speech fallback

    @Test("falls back to speech when no clip is baked, and still resolves on end")
    func unbakedTakesTheSpeechPath() {
        let (channel, clip, speech, _) = makeChannel(baked: [])
        var settled: [Bool] = []
        channel.start("42") { settled.append($0) }
        #expect(speech.spoken == ["42"])
        // "No baked clip: stop any in-flight clip so it can't overlap the TTS line."
        #expect(clip.hardStops >= 1)
        speech.finish(true)
        #expect(settled == [true])
    }

    @Test("rate and pitch reach the speech path only — a baked clip always plays at 1.0")
    func rateAndPitchNeverReachTheClip() {
        let (channel, _, speech, _) = makeChannel(baked: ["chat"])
        channel.start("chat", rate: 0.98, pitch: 1.1) { _ in }
        // The clip path never received a rate: `ClipPlayback` has no way to
        // express one, and the web never sets `HTMLAudioElement.playbackRate`.
        // Applying it would re-pitch every success line in the game.
        #expect(speech.lastRate == nil)

        let (tts, _, ttsSpeech, _) = makeChannel(baked: [])
        tts.start("42", rate: 0.85, pitch: 1.2) { _ in }
        #expect(ttsSpeech.lastRate == 0.85)
        #expect(ttsSpeech.lastPitch == 1.2)
    }

    @Test("a speech engine that fails synchronously resolves false, not silence forever")
    func speechFailureResolvesFalse() {
        let (channel, _, speech, _) = makeChannel(baked: [])
        speech.speakFails = true
        var settled: [Bool] = []
        channel.start("42") { settled.append($0) }
        #expect(settled == [false])
    }

    @Test("an interruption settles the in-flight line false and cuts the clip")
    func interruptSettlesFalse() {
        let (channel, clip, _, _) = makeChannel()
        var settled: [Bool] = []
        channel.start("chat") { settled.append($0) }
        channel.interrupt()
        #expect(settled == [false])
        // Once when `start` cleared the way, once for the interruption itself.
        #expect(clip.hardStops == 2)
        #expect(clip.fades.isEmpty, Comment(rawValue: "there is nothing left to fade after an interruption"))
    }

    // MARK: - The async surface

    @Test("say() returns true on natural completion")
    func asyncSayResolvesTrue() async {
        let (channel, clip, _, _) = makeChannel()
        let task = Task { await channel.say("chat") }
        #expect(await yieldUntil { channel.isSpeaking })
        clip.finish(true)
        #expect(await task.value == true)
    }

    @Test("say() never hangs: a stalled engine is resolved false by the watchdog")
    func asyncSayNeverHangs() async {
        let (channel, _, _, timers) = makeChannel(duration: 2.0)
        let task = Task { await channel.say("chat") }
        #expect(await yieldUntil { channel.isSpeaking })
        #expect(timers.pending?.ms == 2800)
        timers.fireLatest()  // the deadline elapses; the engine never reported
        #expect(await task.value == false)
    }

    @Test("say() supersession over the async surface: first false, second true")
    func asyncSupersession() async {
        let (channel, clip, _, _) = makeChannel()
        let first = Task { await channel.say("chat") }
        #expect(await yieldUntil { channel.isSpeaking })
        let second = Task { await channel.say("chien") }
        #expect(await first.value == false)
        #expect(await yieldUntil { channel.isSpeaking })
        clip.finish(true)
        #expect(await second.value == true)
    }
}
