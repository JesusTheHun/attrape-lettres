import Foundation

// Port of the single-flight voice channel in `src/hooks/useAudio.ts`
// (`say` / `stop` / `interruptCurrent` / the watchdogs).
//
// Engine-agnostic on purpose: it talks to `ClipPlayback` and `SpeechPlayback`,
// and schedules its watchdogs through `AudioTimerScheduling`. That is what
// makes the contract host-testable with no AVFoundation, no simulator and — the
// part that matters — no `sleep` in a test.
//
// THE CONTRACT, and it is the whole point of the file:
//
//   `say()` resolves `true` on natural completion and `false` when it was
//   superseded, errored, or a watchdog tripped. It never throws and it never
//   hangs. Callers gate the game's next step on that Bool, so a wrong `false`
//   skips a line and a promise that never settles freezes the round with no way
//   out but « ← Menu ». (Invariant 3: audio failure degrades, never blocks.)

/// A clip that has been resolved and decoded and is ready to be scheduled.
public final class PreparedClip {
    public let url: URL
    /// Seconds. `nil` when the engine cannot say yet — that is the case the
    /// provisional watchdog exists for. Natively `AVAudioFile.length` answers
    /// synchronously, so it is nearly always known.
    public let duration: Double?
    /// The decoded payload, owned by whoever prepared it. Held here so a buffer
    /// that is currently scheduled can never be evicted from the decode cache.
    let payload: AnyObject?

    public init(url: URL, duration: Double?, payload: AnyObject? = nil) {
        self.url = url
        self.duration = duration
        self.payload = payload
    }
}

/// The baked-clip half of the channel — the `HTMLAudioElement` in the TS.
@MainActor
public protocol ClipPlayback: AnyObject {
    /// `clipUrl(text)`. `nil` ⇒ nothing baked ⇒ the TTS path.
    func clipURL(for text: String) -> URL?
    /// Decode. `nil` ⇒ a media error, which the TS surfaces as `el.onerror` ⇒
    /// settle `false`. Deliberately distinct from "not baked".
    func prepare(_ url: URL) -> PreparedClip?
    /// Start playing. `false` ⇒ could not start at all, which is the TS's
    /// `el.play().catch(...)` ⇒ fall back to TTS if still current.
    /// `onEnd(true)` = played to its end; `onEnd(false)` = errored mid-flight.
    func play(_ clip: PreparedClip, onEnd: @escaping (Bool) -> Void) -> Bool
    /// `el.pause()` — cut instantly, no fade.
    func hardStop()
    /// A leave-fade may be mid-ramp; cancel it and restore full volume, or the
    /// next line plays under a decaying gain.
    func cancelFade()
    /// The 200 ms leave-fade: `steps` × `intervalMs`, then stop and restore.
    func fadeOutAndStop(steps: Int, intervalMs: Double)
    /// `!el.paused` — is a clip actually sounding right now?
    var isPlaying: Bool { get }
}

/// The fallback half — `speechSynthesis` in the TS, `AVSpeechSynthesizer` here.
@MainActor
public protocol SpeechPlayback: AnyObject {
    /// `onEnd(true)` = `didFinish`; `onEnd(false)` = `didCancel`/error. May be
    /// called synchronously if the engine is unavailable — that is the TS's
    /// `catch { settle(false) }`.
    func speak(_ text: String, rate: Double, pitch: Double, onEnd: @escaping (Bool) -> Void)
    /// `speechSynthesis.cancel()`.
    func cancel()
}

/// A cancellable one-shot timer.
public protocol AudioTimer: AnyObject {
    func cancel()
}

/// `window.setTimeout` / `clearTimeout`, injectable so the watchdog deadlines
/// can be asserted arithmetically instead of waited out.
@MainActor
public protocol AudioTimerScheduling: AnyObject {
    func schedule(afterMs: Double, _ body: @escaping () -> Void) -> AudioTimer
}

@MainActor
public final class VoiceChannel {

    // Watchdogs are the only thing that resolves `say()` if the engine never
    // reports. Derived from the real clip length — not a magic constant — so
    // they only ever fire on a genuine stall, never mid-line.
    /// Grace past a clip's known duration.
    public static let watchdogMarginMs = 800.0
    /// Held only until a clip reports its duration.
    public static let provisionalWatchdogMs = 8000.0
    /// Floor for the estimate-based TTS watchdog.
    public static let ttsMinMs = 1200.0
    /// Rough French speech pace for the TTS estimate.
    public static let ttsMsPerChar = 90.0

    // NB: `TTS_HEARTBEAT_MS = 5000` is deliberately NOT ported. It is a Chrome
    // workaround (pause/resume so a long utterance is not parked), not
    // behaviour; `AVSpeechSynthesizer` has no such bug. spec §5.2.5.

    private let clip: ClipPlayback
    private let speech: SpeechPlayback
    private let timers: AudioTimerScheduling

    /// Bumped on every `say()`. An engine callback only settles the line it
    /// belongs to — a stale one is dropped.
    private var ticket = 0
    /// The live resolver. `nil` means "already settled": that, not the ticket,
    /// is what makes settling exactly-once, which a Swift continuation requires
    /// and a JS promise gave away for free.
    private var pending: ((Bool) -> Void)?
    private var watchdog: AudioTimer?

    public init(clip: ClipPlayback, speech: SpeechPlayback, timers: AudioTimerScheduling) {
        self.clip = clip
        self.speech = speech
        self.timers = timers
    }

    /// `true` while a line is in flight. Test seam; also what an interruption
    /// handler checks before deciding there is anything to settle.
    public var isSpeaking: Bool { pending != nil }

    /// The watchdog deadline currently armed, in ms. Test seam.
    public private(set) var armedWatchdogMs: Double?

    // MARK: - The API

    /**
     * Speak one line and return when it is DONE.
     *
     * `true` = played to natural completion. `false` = superseded by a later
     * `say()`/`stop()`, errored, or the watchdog tripped. Never throws, never
     * hangs.
     *
     * `rate` and `pitch` reach the TTS path ONLY. The web never sets
     * `HTMLAudioElement.playbackRate`, so a baked clip always plays at 1.0;
     * applying rate to clips would re-pitch every success line in the game.
     */
    @discardableResult
    public func say(_ text: String, rate: Double = 0.94, pitch: Double = 1.1) async -> Bool {
        await withCheckedContinuation { continuation in
            // Runs synchronously, on the caller's beat — same ordering as the TS,
            // where `interruptCurrent` fires before the Promise executor returns.
            start(text, rate: rate, pitch: pitch) { continuation.resume(returning: $0) }
        }
    }

    /**
     * The callback core of `say()`. Public because it is the honest way to test
     * supersession: two `start` calls in one synchronous beat are exactly what
     * two `say()` calls do, with none of the task-scheduling ambiguity.
     *
     * `settleTo` is called exactly once, ever.
     */
    public func start(
        _ text: String,
        rate: Double = 0.94,
        pitch: Double = 1.1,
        settleTo resolver: @escaping (Bool) -> Void
    ) {
        interruptCurrent(hard: true)
        ticket += 1
        let ticket = self.ticket
        pending = resolver

        guard let url = clip.clipURL(for: text) else {
            // No baked clip: stop any in-flight clip so it cannot overlap the
            // TTS line, then speak.
            clip.hardStop()
            speakTts(text, rate: rate, pitch: pitch, ticket: ticket)
            return
        }

        // A leave-fade may be mid-ramp (`fadeRef` + `el.volume = 1` in the TS).
        clip.cancelFade()

        guard let prepared = clip.prepare(url) else {
            settle(false, ticket: ticket)  // `el.onerror` — a decode failure never hangs the round
            return
        }

        arm(VoiceChannel.provisionalWatchdogMs, ticket: ticket)  // until the real duration lands
        if let duration = prepared.duration, duration.isFinite, duration > 0 {
            arm(duration * 1000 + VoiceChannel.watchdogMarginMs, ticket: ticket)
        }

        let started = clip.play(prepared) { [weak self] ok in
            self?.settle(ok, ticket: ticket)
        }
        if !started {
            // `el.play()` rejected. Only fall back if a newer line has not
            // already taken over.
            if ticket == self.ticket {
                speakTts(text, rate: rate, pitch: pitch, ticket: ticket)
            }
        }
    }

    /**
     * Leaving an exercise: settle any pending line `false`, then ramp the clip
     * to silence over 200 ms and cut.
     *
     * 10 steps of 20 ms, reproduced literally. A smooth mixer ramp would be
     * nicer and is therefore out of scope — behaviour is frozen.
     */
    public func stop() {
        interruptCurrent(hard: false)
        guard clip.isPlaying else { return }
        clip.fadeOutAndStop(steps: 10, intervalMs: 20)
    }

    /**
     * An audio-session interruption (a call, an alarm, backgrounding) took the
     * engine away. Same as `stop()` without the fade — there is nothing left to
     * fade. The round stays exactly where it was; nothing is awarded, nothing
     * advances (spec §8).
     */
    public func interrupt() {
        interruptCurrent(hard: true)
    }

    // MARK: - Internals

    /**
     * Settle whatever line is in flight as NOT-completed and quiesce the engine.
     *
     * `hard` cuts the clip instantly (a new line is starting); soft leaves it
     * for `stop()`'s fade.
     *
     * `pending` is cleared BEFORE the engines are cancelled, which is the whole
     * trick: `stopSpeaking` synthesises a `didCancel`, and on the clip side a
     * hard stop can produce a completion callback. Either could otherwise
     * settle this line — and in Swift, unlike JS, a second resume traps rather
     * than being ignored.
     */
    private func interruptCurrent(hard: Bool) {
        clearTimers()
        let resolver = pending
        pending = nil
        if hard { clip.hardStop() }
        speech.cancel()
        resolver?(false)
    }

    private func settle(_ ok: Bool, ticket: Int) {
        guard ticket == self.ticket, let resolver = pending else { return }
        clearTimers()
        pending = nil
        resolver(ok)
    }

    private func arm(_ ms: Double, ticket: Int) {
        watchdog?.cancel()
        armedWatchdogMs = ms
        watchdog = timers.schedule(afterMs: ms) { [weak self] in
            self?.settle(false, ticket: ticket)
        }
    }

    private func clearTimers() {
        watchdog?.cancel()
        watchdog = nil
        armedWatchdogMs = nil
    }

    private func speakTts(_ text: String, rate: Double, pitch: Double, ticket: Int) {
        speech.cancel()
        arm(VoiceChannel.ttsWatchdogMs(for: text, rate: rate), ticket: ticket)
        speech.speak(text, rate: rate, pitch: pitch) { [weak self] ok in
            self?.settle(ok, ticket: ticket)
        }
    }

    /**
     * `Math.max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate + WATCHDOG_MARGIN_MS`.
     *
     * `text.length` in JS counts UTF-16 code units, so `text.utf16.count` — the
     * same trap as `voKey` (D17), and the same answer.
     */
    public static func ttsWatchdogMs(for text: String, rate: Double) -> Double {
        max(ttsMinMs, Double(text.utf16.count) * ttsMsPerChar) / rate + watchdogMarginMs
    }
}
