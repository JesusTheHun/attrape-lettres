import Foundation

@testable import ALPlatform

// Stubs for the voice channel's two engines and its timer source.
//
// The point of these is that `useAudio.test.ts` drives completion and error by
// hand ("happy-dom won't fire <audio> 'ended' or resolve play() like a browser")
// and so must we — an AVAudioEngine on a headless CI Mac is not a test fixture.
// The state machine is what is under test; AVFoundation is not.

@MainActor
final class FakeClipPlayback: ClipPlayback {

    struct Fade: Equatable {
        let steps: Int
        let intervalMs: Double
    }

    /// Utterances that have a baked clip. Anything else takes the TTS path.
    var baked: Set<String>
    /// What `prepare` reports. `nil` duration ⇒ the provisional watchdog stands.
    var duration: Double?
    /// Set to make `prepare` fail — the TS's `el.onerror`, a media error.
    var prepareFails = false
    /// Set to make `play` refuse to start — the TS's `el.play()` rejection.
    var playFails = false

    private(set) var clipURLCalls = 0
    private(set) var prepareCalls = 0
    private(set) var hardStops = 0
    private(set) var cancelFades = 0
    private(set) var fades: [Fade] = []
    /// The completion handler of the most recent `play`, kept so a test can fire
    /// it late — that is how a STALE engine callback is simulated.
    private(set) var lastOnEnd: ((Bool) -> Void)?

    var isPlaying = false

    init(baked: Set<String> = [], duration: Double? = nil) {
        self.baked = baked
        self.duration = duration
    }

    func clipURL(for text: String) -> URL? {
        clipURLCalls += 1
        guard baked.contains(text) else { return nil }
        return URL(fileURLWithPath: "/fake/\(text).m4a")
    }

    func prepare(_ url: URL) -> PreparedClip? {
        prepareCalls += 1
        if prepareFails { return nil }
        return PreparedClip(url: url, duration: duration)
    }

    func play(_ clip: PreparedClip, onEnd: @escaping (Bool) -> Void) -> Bool {
        lastOnEnd = onEnd
        if playFails { return false }
        isPlaying = true
        return true
    }

    func hardStop() {
        hardStops += 1
        isPlaying = false
    }

    func cancelFade() {
        cancelFades += 1
    }

    func fadeOutAndStop(steps: Int, intervalMs: Double) {
        fades.append(Fade(steps: steps, intervalMs: intervalMs))
        isPlaying = false  // the real player ends the ramp with a hard stop
    }

    /// The clip reached its end (`el.onended`) or errored (`el.onerror`).
    func finish(_ ok: Bool) {
        lastOnEnd?(ok)
    }
}

@MainActor
final class FakeSpeechPlayback: SpeechPlayback {
    private(set) var spoken: [String] = []
    private(set) var lastRate: Double?
    private(set) var lastPitch: Double?
    private(set) var cancels = 0
    private(set) var lastOnEnd: ((Bool) -> Void)?
    /// Make `speak` fail synchronously — the TS's `catch { settle(false) }`.
    var speakFails = false

    func speak(_ text: String, rate: Double, pitch: Double, onEnd: @escaping (Bool) -> Void) {
        spoken.append(text)
        lastRate = rate
        lastPitch = pitch
        lastOnEnd = onEnd
        if speakFails { onEnd(false) }
    }

    func cancel() {
        cancels += 1
    }

    func finish(_ ok: Bool) {
        lastOnEnd?(ok)
    }
}

/// `window.setTimeout`, under the test's control. Nothing sleeps.
@MainActor
final class FakeAudioTimers: AudioTimerScheduling {

    final class Scheduled: AudioTimer {
        let ms: Double
        let body: () -> Void
        private(set) var isCancelled = false
        init(ms: Double, body: @escaping () -> Void) {
            self.ms = ms
            self.body = body
        }
        func cancel() { isCancelled = true }
    }

    private(set) var all: [Scheduled] = []

    func schedule(afterMs: Double, _ body: @escaping () -> Void) -> AudioTimer {
        let timer = Scheduled(ms: afterMs, body: body)
        all.append(timer)
        return timer
    }

    /// Every deadline that was ever armed, in order — including ones later
    /// replaced, which is how the provisional→duration handover is observed.
    var scheduledMs: [Double] { all.map(\.ms) }

    /// The one deadline still live.
    var pending: Scheduled? { all.last { !$0.isCancelled } }

    /// Let the live deadline elapse.
    func fireLatest() {
        pending?.body()
    }
}

@MainActor
final class SpySfx: SfxPlaying {
    private(set) var played: [Sfx] = []
    private(set) var prewarms = 0
    private(set) var suspends = 0
    private(set) var isReady = false

    func prewarm() {
        prewarms += 1
        isReady = true
    }

    func play(_ sfx: Sfx) {
        played.append(sfx)
    }

    func suspend() {
        suspends += 1
        isReady = false
    }
}

/// Bounded spin so an `async` assertion never becomes an infinite hang if the
/// thing under test regresses. `Task.yield`, never `Task.sleep`.
@MainActor
func yieldUntil(_ condition: () -> Bool, limit: Int = 10_000) async -> Bool {
    for _ in 0..<limit {
        if condition() { return true }
        await Task.yield()
    }
    return condition()
}
