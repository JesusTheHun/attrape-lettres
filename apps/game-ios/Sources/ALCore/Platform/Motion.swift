// D14: ONE source of truth for reduced motion.
//
// Invariant 6 requires `prefers-reduced-motion` to be honoured by the mascot,
// the confetti and the sheen. Three independent
// `@Environment(\.accessibilityReduceMotion)` reads are three chances to forget
// one — and the render harness and the tests could not force it on. So: one
// protocol here, injected at the root, read everywhere.

public protocol ReduceMotionSource: Sendable {
    var isReduced: Bool { get }
}

/// Tests, previews and the D19 render harness.
public struct FixedReduceMotion: ReduceMotionSource {
    public let isReduced: Bool
    public init(_ isReduced: Bool) { self.isReduced = isReduced }
}
