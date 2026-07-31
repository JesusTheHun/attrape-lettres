import Foundation

#if canImport(AVFAudio) && os(iOS)
import AVFAudio
#endif

// The audio session is a DECISION, not a port: the PWA inherits WKWebView's
// defaults under Capacitor (effectively `.soloAmbient` — muted by the ring/
// silent switch, and it stops other apps' audio). Nobody chose that; it is what
// a WebView does. spec §8 recommends, and this implements:
//
//     .playback + .duckOthers, preferred IO buffer 5 ms
//
// `.playback` because the voice-over IS the exercise: « Trouve la première
// lettre de Ballon » is the question, and a six-year-old cannot diagnose "the
// ring switch on the side of Mum's phone is flipped" — from their seat the game
// is simply broken, and there is no fail state to explain it. `.duckOthers`
// rather than plain `.playback` (which stops the other app) or `.mixWithOthers`
// (which would have a child distinguishing /ba/ from /da/ under a podcast).
//
// This is flagged in spec §10.1 as needing a `D`-entry above the audio layer;
// it is implemented here because the engine cannot run without SOME category,
// and this is the recommended one. Swap the two lines in `activate()` if the
// decision lands elsewhere.

/// Interruptions and route changes, as the voice channel needs to see them.
public enum AudioSessionEvent: Sendable, Equatable {
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    /// Headphones/AirPods removed. spec §8: **do nothing**. The iOS convention
    /// (pause on unplug) exists for media apps; here pausing would settle the
    /// success line `false` mid-celebration and can soft-lock the round (§9.1).
    case routeLostDevice
}

public protocol AudioSessionControlling: AnyObject {
    func activate()
    func deactivate(notifyOthers: Bool)
    /// Called on the main thread.
    var onEvent: ((AudioSessionEvent) -> Void)? { get set }
}

/// The macOS/host implementation: there is no `AVAudioSession` outside iOS, and
/// `AVAudioEngine` needs no session there. An explicit no-op, not a `#if` hole —
/// `swift test` exercises the same composition the device runs.
public final class NoopAudioSession: AudioSessionControlling {
    public var onEvent: ((AudioSessionEvent) -> Void)?
    public private(set) var activations = 0
    public private(set) var deactivations = 0

    public init() {}

    public func activate() { activations += 1 }
    public func deactivate(notifyOthers: Bool) { deactivations += 1 }
}

#if os(iOS)

public final class LiveAudioSession: AudioSessionControlling {

    public var onEvent: ((AudioSessionEvent) -> Void)?

    private var configured = false
    private var observing = false

    public init() {}

    public func activate() {
        let session = AVAudioSession.sharedInstance()
        if !configured {
            configured = true
            try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
            // ~5 ms is a request, not a promise; the system may grant 11 ms.
            // Either way it is one render quantum between enqueue and first
            // sample, which is the segment invariant 1 cares about.
            try? session.setPreferredIOBufferDuration(0.005)
            observe()
        }
        try? session.setActive(true)
    }

    public func deactivate(notifyOthers: Bool) {
        try? AVAudioSession.sharedInstance().setActive(
            false,
            options: notifyOthers ? [.notifyOthersOnDeactivation] : []
        )
    }

    private func observe() {
        guard !observing else { return }
        observing = true
        let center = NotificationCenter.default
        center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let self,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw)
            else { return }
            switch type {
            case .began:
                self.onEvent?(.interruptionBegan)
            case .ended:
                let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                    .map(AVAudioSession.InterruptionOptions.init(rawValue:)) ?? []
                self.onEvent?(.interruptionEnded(shouldResume: options.contains(.shouldResume)))
            @unknown default:
                self.onEvent?(.interruptionBegan)
            }
        }
        center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                  reason == .oldDeviceUnavailable
            else { return }
            self?.onEvent?(.routeLostDevice)
        }
    }
}

#endif

/// The session this build uses. iOS gets `AVAudioSession`; the host gets the
/// no-op, so `LiveAudioEngine` composes identically in both.
public func makeAudioSession() -> AudioSessionControlling {
    #if os(iOS)
    return LiveAudioSession()
    #else
    return NoopAudioSession()
    #endif
}
