import ALCore
import Foundation

#if canImport(UIKit)
import UIKit
#endif

// Haptics: there are NONE today, and shipping none is the port.
//
// Grepping `src/` and `scripts/` for `vibrate`, `Haptic`, and the Capacitor
// haptics plugin returns zero hits. The PWA ships no haptic feedback of any
// kind; `nudge()` is an audio blip whose name suggests otherwise. Behaviour is
// frozen, so `PlatformHaptics.shipping` is a no-op and that is what `App/`
// injects. The seam exists so the product decision (spec §10.2) can be made
// later without touching a single exercise.
//
// The `.system` mode below is that decision, pre-wired and OFF. When it is
// switched on: `light()` on an accepted tap, `soft()` on a wrong tap — `soft`,
// never `.error`, because invariant 3 says a wrong tap is not a failure.
//
// `UIFeedbackGenerator`, not CoreHaptics: CoreHaptics buys custom envelopes we
// have no design for, needs a `CHHapticEngine` lifecycle alongside the audio
// engine, and is unavailable on every iPad. `UIFeedbackGenerator` degrades to
// nothing on hardware without a Taptic Engine, which is exactly right here.
// CoreHaptics is also iOS-only, which would put the whole file behind a `#if`
// and out of `swift test`; `UIFeedbackGenerator` at least has a host no-op.

public final class PlatformHaptics: Haptics, @unchecked Sendable {

    public enum Mode: Sendable {
        /// What ships. Nothing is emitted, on any platform.
        case off
        /// The proposed mapping, live. Not reached until a product decision.
        case system
    }

    public enum Event: String, Sendable, Equatable {
        case light
        case soft
    }

    public let mode: Mode

    private let lock = NSLock()
    private var emitted: [Event] = []
    /// Test seam. The only way to assert "the shipping configuration emits
    /// nothing" on a host with no Taptic Engine — an absent buzz is otherwise
    /// indistinguishable from a buzz nobody can feel.
    public var events: [Event] {
        lock.lock()
        defer { lock.unlock() }
        return emitted
    }

    #if canImport(UIKit)
    private let lightGenerator = UIImpactFeedbackGenerator(style: .light)
    private let softGenerator = UIImpactFeedbackGenerator(style: .soft)
    #endif

    public init(mode: Mode = .off) {
        self.mode = mode
    }

    /// The shipping configuration: silent, everywhere, deliberately.
    public static let shipping = PlatformHaptics(mode: .off)

    /// `prepare()` on round load, so the first tap is not the one that warms the
    /// Taptic Engine. A no-op in `.off` and on the host.
    public func prepare() {
        guard case .system = mode else { return }
        #if canImport(UIKit)
        lightGenerator.prepare()
        softGenerator.prepare()
        #endif
    }

    public func light() { emit(.light) }
    public func soft() { emit(.soft) }

    private func emit(_ event: Event) {
        guard case .system = mode else { return }
        lock.lock()
        emitted.append(event)
        lock.unlock()
        #if canImport(UIKit)
        switch event {
        case .light: lightGenerator.impactOccurred()
        case .soft: softGenerator.impactOccurred()
        }
        #endif
        // No UIKit (macOS host): recorded and otherwise a no-op. The composition
        // is identical on both platforms, which is what keeps this testable.
    }
}
