import ALCore
import Foundation

// `ALCore.AudioEngine`, composed: the SFX graph + the clip player + the speech
// fallback + the single-flight voice channel.
//
// ONE instance for the app lifetime, injected through the environment — not one
// per exercise. `useAudio` creates an `AudioContext` per mount and closes it on
// unmount because "a long session can't leak contexts until the browser caps
// them and the chimes go silent": a browser resource limit, not behaviour.
// Starting an `AVAudioEngine` costs 10–30 ms, so doing it on exercise entry
// would risk a silent first tap. What IS preserved per exercise is the teardown:
// `useEffect(() => () => audio.stop(), [audio])` ⇒ `.onDisappear { audio.stop() }`.

@MainActor
public final class LiveAudioEngine: @MainActor AudioEngine {

    private let sfx: SfxPlaying
    private let clip: ClipPlayback
    private let speech: SpeechPlayback
    private let session: AudioSessionControlling
    private let channel: VoiceChannel

    /// Set by `unlock()`; cleared by an interruption. In the steady state
    /// `unlock()` reads this and returns — no allocation, no syscall, which
    /// matters because it sits on the pointer-down path 26 times over.
    private var unlocked = false

    public init(
        sfx: SfxPlaying,
        clip: ClipPlayback,
        speech: SpeechPlayback,
        session: AudioSessionControlling,
        timers: AudioTimerScheduling = MainQueueAudioTimers()
    ) {
        self.sfx = sfx
        self.clip = clip
        self.speech = speech
        self.session = session
        self.channel = VoiceChannel(clip: clip, speech: speech, timers: timers)
        self.session.onEvent = { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
    }

    /// The shipping composition: the bundled clip bank, the AVAudioEngine graph,
    /// `AVSpeechSynthesizer`, and the platform's audio session.
    public static func live(bank: ClipLocating = BundleClipBank()) -> LiveAudioEngine {
        let graph = GameAudioGraph()
        return LiveAudioEngine(
            sfx: graph,
            clip: ClipPlayer(bank: bank, graph: graph),
            speech: SpeechFallback(),
            session: makeAudioSession()
        )
    }

    // MARK: - AudioEngine

    /**
     * A web autoplay-policy artifact — "make sure we can make noise now" — kept
     * because its 26 call sites are the natural recovery point after an
     * interruption: a child who taps a tile after taking a phone call gets sound
     * back without knowing why it went.
     *
     * Idempotent, and cheap after the first call.
     */
    public func unlock() {
        if unlocked, sfx.isReady { return }
        unlocked = true
        session.activate()
        sfx.prewarm()
        (speech as? SpeechFallback)?.prewarm()
    }

    /// Call once at launch so the first tap is not the one that pays the 10–30 ms
    /// engine start. Observably identical to letting `unlock()` do it; the web's
    /// `AudioContext` is created on the first gesture only because it must be.
    public func prewarm() {
        unlock()
    }

    public func pop() { sfx.play(.pop) }
    public func success() { sfx.play(.success) }
    public func nudge() { sfx.play(.nudge) }
    public func oops() { sfx.play(.oops) }

    @discardableResult
    public func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
        await channel.say(text, rate: rate, pitch: pitch)
    }

    public func stop() {
        channel.stop()
    }

    // MARK: - Extras (not on the protocol)

    /// Decode this round's lines ahead of the tap. Purely additive: skipping it
    /// changes nothing but first-play latency. Meant for the 350 ms announce
    /// delay six exercises already have.
    public func preload(_ texts: [String]) {
        (clip as? ClipPlayer)?.preload(texts)
    }

    /// The app is going to the background: cut the line, let other apps' music
    /// resume. No background audio mode is declared — the game must not play
    /// while backgrounded.
    public func enterBackground() {
        channel.interrupt()
        sfx.suspend()
        session.deactivate(notifyOthers: true)
        unlocked = false
    }

    private func handle(_ event: AudioSessionEvent) {
        switch event {
        case .interruptionBegan:
            // The system already stopped the engine. Settle the in-flight line
            // `false`, leave the round exactly where it was, award nothing,
            // advance nothing.
            channel.interrupt()
            sfx.suspend()
            unlocked = false
        case .interruptionEnded(let shouldResume):
            if shouldResume { unlock() }
            // We do NOT auto-replay the prompt: every exercise already has an
            // on-screen 🔊 « Écouter » button, and re-speaking unasked would
            // race whatever the child does next.
        case .routeLostDevice:
            // Deliberately nothing. Pausing here would settle a success line
            // `false` mid-celebration, and the exercises' `locked.current` is
            // never released on a `false` — see spec §9.1.
            break
        }
    }
}
