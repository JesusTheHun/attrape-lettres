// The audio + haptics seam. Protocols only — ALCore never imports AVFoundation,
// and the whole feedback layer therefore stays host-testable.
//
// Port of `AudioApi` in `src/hooks/useAudio.ts`.

/**
 * The four SFX and the single-flight voice channel.
 *
 * `say` is the contract that matters: **it returns `Bool`, and it can neither
 * throw nor hang.** That is invariant 3 at the audio layer — a missing clip, a
 * dead engine or a cancelled utterance must never leave a child staring at a
 * round that will not advance. Callers gate the next step on the returned flag
 * (`if (!ok || !mountedRef.current) return;` in the TSX), so `false` simply
 * means "superseded or failed" and the round moves on.
 *
 * `false` = the line was superseded by a later `say()`/`stop()`, or errored.
 * `true` = it played to completion.
 *
 * Every method is safe to call from a pointerdown handler: `pop()` / `nudge()`
 * schedule a pre-rendered buffer synchronously (invariant 1); they never decode.
 */
public protocol AudioEngine: AnyObject {
    /// Warm the engine on the first user gesture. Idempotent — the web original
    /// warms the speech engine ONCE, not on every tap.
    func unlock()

    /// Accepted tap. 660 Hz triangle blip.
    func pop()
    /// Round/session cleared. The four-note rising arpeggio.
    func success()
    /// Wrong tap. A soft low sine — "soft, non-punishing". This is the ENTIRE
    /// wrong-answer penalty (invariant 3), and despite the name it is not a
    /// haptic. NB: during `Rewards.missCooldownMs` a pick is swallowed BEFORE
    /// `pop()`, so the tap still shakes but is silent — deliberate anti-farming
    /// asymmetry (invariant 8).
    func nudge()
    /// The two-note falling "wah-wah" that pairs with « Oh non ! On recommence. »
    func oops()

    /// Speak an utterance. Baked clip if one exists for it, TTS otherwise.
    /// `rate` and `pitch` apply to the TTS path ONLY — the web never sets
    /// `HTMLAudioElement.playbackRate`, so a baked clip always plays at 1.0.
    /// Applying rate to clips would change the pitch of every success line.
    @discardableResult
    func say(_ text: String, rate: Double, pitch: Double) async -> Bool

    /// Interrupt the voice channel: settle the pending `say` `false`, then fade
    /// the voice mixer out over 200 ms (10 steps of 20 ms).
    func stop()
}

extension AudioEngine {
    /// The defaults every call site but one uses (`useAudio.ts`: rate 0.94, pitch 1.1).
    @discardableResult
    public func say(_ text: String) async -> Bool {
        await say(text, rate: 0.94, pitch: 1.1)
    }
}

// NB: ARCHITECTURE.md §5 sketches this protocol as
// `say(_ key: VOKey) async -> Bool; pop(); nudge()`. The three named members are
// here under those names; `say` takes the utterance TEXT (as `useAudio.ts` and
// audio-feedback.md §5.2 both do) because `voKey(_:)` is a `(String) -> String`
// hash applied inside the clip bank, not a distinct type the call sites hold.
// `unlock/success/oops/stop` are the rest of `AudioApi` and are needed by the
// engines; ARCHITECTURE's list was a sketch, not an exhaustive one.

/// Test/preview double. Never plays anything, always settles `true` — so a test
/// that awaits a success line does not stall.
public final class SilentAudioEngine: AudioEngine {
    public private(set) var spoken: [String] = []
    public private(set) var pops = 0
    public private(set) var nudges = 0

    public init() {}

    public func unlock() {}
    public func pop() { pops += 1 }
    public func success() {}
    public func nudge() { nudges += 1 }
    public func oops() {}

    @discardableResult
    public func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
        spoken.append(text)
        return true
    }

    public func stop() {}
}

/**
 * Haptics.
 *
 * There are NONE today: grepping `src/` and `scripts/` for `vibrate` / `Haptic`
 * / the Capacitor haptics plugin returns zero hits, and `nudge()` is an audio
 * blip whose name suggests otherwise. Behaviour is frozen, so the port ships
 * `NoopHaptics`; the seam exists so the decision can be made later without
 * touching a single exercise.
 *
 * When it is made: `light()` on an accepted tap, `soft()` on a wrong tap —
 * `soft`, never `.error`, because invariant 3 says a wrong tap is not a failure.
 */
public protocol Haptics: Sendable {
    func light()
    func soft()
}

/// The shipping implementation, today and until a product decision says otherwise.
public struct NoopHaptics: Haptics {
    public init() {}
    public func light() {}
    public func soft() {}
}
