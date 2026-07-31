import AVFoundation
import Foundation

// The `AVAudioEngine` graph. Port of the Web Audio oscillator SFX in
// `src/hooks/useAudio.ts`, with the one structural change the platform forces.
//
//   6 × AVAudioPlayerNode (SFX pool) ─┐
//                                     ├─→ mainMixerNode → outputNode
//   1 × AVAudioPlayerNode (voice) ─→ voiceMixer ────────┘
//
// WHY NOT `AVAudioPlayer` (invariant 1). `AVAudioPlayer.play()` without a prior
// `prepareToPlay()` does file open, container parse, decoder instantiation and
// first-buffer decode ON THE CALLING THREAD. On the pointer-down path that is a
// variable multi-millisecond stall and a main-thread hazard, and `prepareToPlay`
// only moves the cost — a re-`play()` after completion re-arms lazily. So:
// pre-rendered PCM buffers scheduled onto player nodes that were attached,
// connected and `play()`-ed once at `unlock()` and are left running forever. A
// running node with nothing scheduled renders silence; `scheduleBuffer` on it is
// an enqueue, not a start. That makes `pop()` a memcpy-free enqueue — no decode,
// no allocation, no filesystem, no await, which is exactly what invariant 1 asks
// and what a spy-on-the-loader test can prove.
//
// AVAudioEngine is available on macOS, so this file builds and links on the
// host; `swift test` simply never starts it.

/// The SFX half of the engine, behind a protocol so the "no I/O on the tap
/// path" test can watch it without an audio device.
@MainActor
public protocol SfxPlaying: AnyObject {
    /// Build the graph and render the four buffers. Idempotent.
    func prewarm()
    /// Schedule a pre-rendered buffer. Synchronous, allocation-free, never
    /// touches the filesystem. A no-op before `prewarm()` — exactly like the
    /// web's `if (!ctx) return`.
    func play(_ sfx: Sfx)
    /// Whether the graph is up and audible.
    var isReady: Bool { get }
    /// Tear down on an interruption so the next `unlock()` rebuilds.
    func suspend()
}

@MainActor
public final class GameAudioGraph: SfxPlaying {

    private let engine = AVAudioEngine()
    private let sfxNodes: [AVAudioPlayerNode]
    /// Six slots is comfortably more than the worst observed overlap: a wrong
    /// tap plays `pop()` AND `nudge()` on the same instant, plus a tail from the
    /// previous tap.
    private var nextSfxSlot = 0

    let voiceNode = AVAudioPlayerNode()
    let voiceMixer = AVAudioMixerNode()

    private var sfxBuffers: [Sfx: AVAudioPCMBuffer] = [:]
    private var renderedAt: Double = 0
    private var built = false
    private var voiceFormat: AVAudioFormat?

    /// The format the SFX nodes are CONNECTED with, which must equal the format
    /// every rendered buffer carries. See `ensureSfxFormat`.
    private var sfxFormat: AVAudioFormat?

    public private(set) var isReady = false

    public init(sfxVoices: Int = 6) {
        sfxNodes = (0..<max(1, sfxVoices)).map { _ in AVAudioPlayerNode() }
    }

    // MARK: - Lifecycle

    public func prewarm() {
        if isReady { return }  // steady state: one Bool, no syscall, no allocation
        build()

        let outputFormat = engine.outputNode.outputFormat(forBus: 0)
        let rate = outputFormat.sampleRate > 0 ? outputFormat.sampleRate : 48_000
        if renderedAt != rate {
            renderBuffers(sampleRate: rate)
        }
        // BEFORE `engine.start()`, and before any tap can reach `play`.
        if let format = sfxFormat { ensureSfxFormat(format) }

        do {
            try engine.start()
        } catch {
            // No audio device, a session we could not activate, a simulator
            // hiccup: the game goes quiet and keeps working. Invariant 3.
            isReady = false
            return
        }
        guard engine.isRunning else {
            isReady = false
            return
        }
        for node in sfxNodes where !node.isPlaying { node.play() }
        if !voiceNode.isPlaying { voiceNode.play() }
        isReady = true
    }

    public func suspend() {
        isReady = false
        for node in sfxNodes { node.stop() }
        voiceNode.stop()
        engine.stop()
    }

    private func build() {
        guard !built else { return }
        built = true
        // Attached, NOT connected: the connection format is not known until the
        // buffers have been rendered, and connecting with `nil` here is what
        // crashed the app on the very first tap. See `ensureSfxFormat`.
        for node in sfxNodes {
            engine.attach(node)
        }
        engine.attach(voiceNode)
        engine.attach(voiceMixer)
        engine.connect(voiceMixer, to: engine.mainMixerNode, format: nil)
        // The voice node's format is settled the first time a clip is prepared —
        // the bank is 24 kHz mono throughout, so this happens once.
        engine.connect(voiceNode, to: voiceMixer, format: nil)
        voiceMixer.outputVolume = 1
    }

    private func renderBuffers(sampleRate: Double) {
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ) else { return }
        var built: [Sfx: AVAudioPCMBuffer] = [:]
        for sfx in Sfx.allCases {
            let samples = SfxSynth.render(sfx, sampleRate: sampleRate)
            guard !samples.isEmpty,
                  let buffer = AVAudioPCMBuffer(
                      pcmFormat: format,
                      frameCapacity: AVAudioFrameCount(samples.count)
                  ),
                  let channel = buffer.floatChannelData?[0]
            else { continue }
            samples.withUnsafeBufferPointer { source in
                channel.update(from: source.baseAddress!, count: samples.count)
            }
            buffer.frameLength = AVAudioFrameCount(samples.count)
            built[sfx] = buffer
        }
        sfxBuffers = built
        renderedAt = sampleRate
        sfxFormat = format
    }

    // MARK: - The tap path

    public func play(_ sfx: Sfx) {
        guard isReady, let buffer = sfxBuffers[sfx] else { return }
        // `scheduleBuffer` does not fail politely: handed a buffer whose format
        // differs from the one its node was CONNECTED with, it raises an ObjC
        // exception, which in Swift is an uncatchable abort. From a child's tap.
        // Never schedule into a format we did not connect (invariant 3: audio
        // may go quiet, it may never take the game down).
        let node = sfxNodes[nextSfxSlot]
        // Asked of the NODE, not of our own bookkeeping: `outputFormat(forBus:)`
        // is the value `scheduleBuffer` validates against, so a graph that
        // recorded one format and wired another still goes quiet instead of
        // aborting. One property read on the tap path, against an abort.
        guard Self.canSchedule(connected: node.outputFormat(forBus: 0), buffer: buffer) else {
            return
        }
        nextSfxSlot = (nextSfxSlot + 1) % sfxNodes.count
        // `.interrupts` rather than queueing: round-robin means the slot we are
        // reusing is the oldest, and a child who spam-taps must not build a
        // backlog of pops that keeps sounding after they stop.
        node.scheduleBuffer(buffer, at: nil, options: [.interrupts], completionHandler: nil)
        if !node.isPlaying { node.play() }
    }

    /// Connect every SFX node with the EXACT format its buffers carry.
    ///
    /// The sibling of `ensureVoiceFormat`, and its absence is what crashed the
    /// app on the first tap of any tile:
    ///
    ///     -[AVAudioPlayerNode scheduleBuffer:atTime:options:completionHandler:]
    ///     → +[NSException raise:format:] → abort()
    ///
    /// `build()` used to connect with `format: nil`, which does NOT mean "adapt
    /// to whatever arrives" — it means "use the source node's current output
    /// format", and for a player node that has never been given a buffer that is
    /// the engine's standard format: **stereo**, at the hardware rate.
    /// `renderBuffers` produces **mono**. Scheduling a 1-channel buffer into a
    /// 2-channel connection raises, and an ObjC exception in Swift is an abort,
    /// not an error — so the game died on a six-year-old's first tap, on the one
    /// code path invariant 1 puts in front of everything else.
    ///
    /// The mixer converts, so this format need not match the hardware at all —
    /// only the buffers. That is also why a route change cannot resurrect the
    /// bug: the connection stays consistent with what we schedule into it.
    ///
    /// Nothing on the host suite could see this. `AVAudioEngine` never runs
    /// there, `swift test` has no audio device, and the whole graph is behind
    /// `isReady`, which is false on a Mac. Only a real tap on a real device
    /// reaches line one of it.
    func ensureSfxFormat(_ format: AVAudioFormat) {
        guard built else { return }
        if let current = connectedSfxFormat, current == format { return }
        connectedSfxFormat = format
        for node in sfxNodes {
            engine.disconnectNodeOutput(node)
            engine.connect(node, to: engine.mainMixerNode, format: format)
        }
        if isReady {
            for node in sfxNodes where !node.isPlaying { node.play() }
        }
    }

    /// What `ensureSfxFormat` last wired up. Distinct from `sfxFormat`, which is
    /// what the BUFFERS carry: `play` refuses to schedule unless they agree.
    private var connectedSfxFormat: AVAudioFormat?

    /// Test seam: our bookkeeping — what `ensureSfxFormat` believes it wired.
    var connectedSfxFormatForTesting: AVAudioFormat? { connectedSfxFormat }

    /// Test seam: what the ENGINE actually reports for a node's output bus.
    ///
    /// This, not `connectedSfxFormat`, is what `scheduleBuffer` is checked
    /// against at runtime. Asserting on the bookkeeping alone would pass a graph
    /// that records one format and connects another — which is a restatement of
    /// the original bug, not a guard against it.
    var actualSfxNodeFormatForTesting: AVAudioFormat? {
        sfxNodes.first?.outputFormat(forBus: 0)
    }

    /// Test seam: a rendered buffer, to compare against the connection.
    func bufferForTesting(_ sfx: Sfx) -> AVAudioPCMBuffer? { sfxBuffers[sfx] }

    /// The decision `play` makes before it schedules — the ONE thing standing
    /// between a format drift and an abort. A static predicate rather than a
    /// duplicated `guard` so a test exercises the real rule and not a copy of
    /// it: an audio device is needed to call `play`, none is needed for this.
    static func canSchedule(connected: AVAudioFormat?, buffer: AVAudioPCMBuffer) -> Bool {
        guard let connected else { return false }
        return connected == buffer.format
    }

    // MARK: - Voice plumbing (used by ClipPlayer)

    /// The voice node is connected lazily to the first clip's format. Every clip
    /// in the bank is 24 kHz mono, so in practice this reconnects exactly once,
    /// off the tap path, and the mixer resamples to the hardware rate.
    func ensureVoiceFormat(_ format: AVAudioFormat) {
        guard built else { return }
        if let current = voiceFormat, current == format { return }
        voiceFormat = format
        engine.disconnectNodeOutput(voiceNode)
        engine.connect(voiceNode, to: voiceMixer, format: format)
        if isReady, !voiceNode.isPlaying { voiceNode.play() }
    }
}
