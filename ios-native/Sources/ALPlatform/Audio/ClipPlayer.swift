import AVFoundation
import Foundation

// `ClipPlayback` over the graph's voice node. The `HTMLAudioElement` of the TS,
// with the decode made explicit because natively it is: `AVAudioFile.length`
// gives the duration synchronously, which is why the provisional 8 s watchdog is
// nearly vacuous here and kept anyway (an unreadable file still has to settle).

@MainActor
public final class ClipPlayer: ClipPlayback {

    /// Decoded seconds held in memory. The whole bank is 2 434 s ≈ 234 MB at
    /// 24 kHz float32 — preloading it is not an option. 120 s ≈ 41 clips ≈ three
    /// rounds' working set, which is the right granularity.
    public static let cacheSecondsCap = 120.0

    private let bank: ClipLocating
    private let graph: GameAudioGraph

    private struct Cached {
        let clip: PreparedClip
        let seconds: Double
        var lastUsed: UInt64
    }

    private var cache: [String: Cached] = [:]
    private var cachedSeconds: Double = 0
    private var useCounter: UInt64 = 0

    /// Bumped on every hard stop and every new schedule, so a completion
    /// callback that was already in flight cannot report a line that is over.
    private var generation = 0
    /// `!el.paused`. An `AVAudioPlayerNode` left permanently running reports
    /// `isPlaying == true` with nothing scheduled, so it cannot answer this.
    public private(set) var isPlaying = false

    private var fadeToken = 0

    public init(bank: ClipLocating, graph: GameAudioGraph) {
        self.bank = bank
        self.graph = graph
    }

    // MARK: - ClipPlayback

    public func clipURL(for text: String) -> URL? {
        bank.clipURL(for: text)
    }

    public func prepare(_ url: URL) -> PreparedClip? {
        let key = url.deletingPathExtension().lastPathComponent
        if var hit = cache[key] {
            useCounter += 1
            hit.lastUsed = useCounter
            cache[key] = hit
            return hit.clip
        }
        guard let decoded = ClipPlayer.decode(url) else { return nil }
        insert(decoded, key: key)
        return decoded.clip
    }

    public func play(_ clip: PreparedClip, onEnd: @escaping (Bool) -> Void) -> Bool {
        guard graph.isReady, let buffer = clip.payload as? AVAudioPCMBuffer else { return false }
        graph.ensureVoiceFormat(buffer.format)
        generation += 1
        let generation = self.generation
        isPlaying = true
        graph.voiceNode.scheduleBuffer(
            buffer,
            at: nil,
            options: [.interrupts],
            completionCallbackType: .dataPlayedBack
        ) { _ in
            DispatchQueue.main.async { [weak self] in
                guard let self, generation == self.generation else { return }
                self.isPlaying = false
                onEnd(true)
            }
        }
        if !graph.voiceNode.isPlaying { graph.voiceNode.play() }
        return true
    }

    public func hardStop() {
        generation += 1
        isPlaying = false
        cancelFadeTimer()
        graph.voiceMixer.outputVolume = 1
        guard graph.isReady else { return }
        graph.voiceNode.stop()
        graph.voiceNode.play()  // keep it running: a stopped node would have to be restarted on the tap path
    }

    public func cancelFade() {
        cancelFadeTimer()
        graph.voiceMixer.outputVolume = 1  // `el.volume = 1` — or the next line plays under a decaying gain
    }

    /// `10 × 20 ms = 200 ms`, reproduced literally including the arithmetic:
    /// `volume = max(0, start * (1 - ++i / steps))`.
    public func fadeOutAndStop(steps: Int, intervalMs: Double) {
        guard steps > 0 else {
            hardStop()
            return
        }
        cancelFadeTimer()
        fadeToken += 1
        let token = fadeToken
        let start = Double(graph.voiceMixer.outputVolume)
        step(1, of: steps, from: start, intervalMs: intervalMs, token: token)
    }

    private func step(_ i: Int, of steps: Int, from start: Double, intervalMs: Double, token: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + intervalMs / 1000) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.fadeToken == token else { return }
                self.graph.voiceMixer.outputVolume =
                    Float(max(0, start * (1 - Double(i) / Double(steps))))
                if i >= steps {
                    self.hardStop()  // clears the token, restores the volume to 1
                } else {
                    self.step(i + 1, of: steps, from: start, intervalMs: intervalMs, token: token)
                }
            }
        }
    }

    private func cancelFadeTimer() {
        fadeToken += 1
    }

    // MARK: - Decode cache

    /**
     * Decode ahead of time. Additive only: it fills the LRU on a background
     * queue and is a no-op for anything already cached or not baked. Callers may
     * skip it entirely and nothing changes but first-play latency.
     *
     * Meant for the 350 ms announce delay six exercises already have.
     */
    public func preload(_ texts: [String]) {
        let urls: [URL] = texts.compactMap { text in
            guard let url = bank.clipURL(for: text) else { return nil }
            let key = url.deletingPathExtension().lastPathComponent
            return cache[key] == nil ? url : nil
        }
        guard !urls.isEmpty else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let decoded = urls.compactMap { ClipPlayer.decode($0) }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    for item in decoded {
                        let key = item.clip.url.deletingPathExtension().lastPathComponent
                        if self.cache[key] == nil { self.insert(item, key: key) }
                    }
                }
            }
        }
    }

    private struct Decoded {
        let clip: PreparedClip
        let seconds: Double
    }

    private nonisolated static func decode(_ url: URL) -> Decoded? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let format = file.processingFormat
        let frames = AVAudioFrameCount(file.length)
        guard frames > 0, format.sampleRate > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)
        else { return nil }
        do {
            try file.read(into: buffer)
        } catch {
            return nil
        }
        let seconds = Double(file.length) / format.sampleRate
        return Decoded(
            clip: PreparedClip(url: url, duration: seconds, payload: buffer),
            seconds: seconds
        )
    }

    private func insert(_ decoded: Decoded, key: String) {
        useCounter += 1
        cache[key] = Cached(clip: decoded.clip, seconds: decoded.seconds, lastUsed: useCounter)
        cachedSeconds += decoded.seconds
        evictIfNeeded()
    }

    private func evictIfNeeded() {
        guard cachedSeconds > ClipPlayer.cacheSecondsCap else { return }
        // Least-recently-PLAYED first. Never touches whatever the voice channel
        // is holding: `PreparedClip` keeps a strong reference to its buffer, so
        // an evicted-but-scheduled clip stays alive until it finishes.
        let order = cache.sorted { $0.value.lastUsed < $1.value.lastUsed }
        for (key, entry) in order {
            if cachedSeconds <= ClipPlayer.cacheSecondsCap { break }
            cache.removeValue(forKey: key)
            cachedSeconds -= entry.seconds
        }
    }
}
