import Foundation

// D6: injectable time, and it is NOT called `Clock` — the stdlib already has one.
//
// The trial clock, the LWW stamps in `sync/merge`, and the reward curve all need
// "now". Any of them reading `Date()` directly is untestable, and for licensing
// that is not a style opinion: a 14-day offline grace whose clock cannot be
// advanced in a test is a 14-day offline grace nobody has ever verified.
//
// Epoch MILLISECONDS as `Int64`, everywhere in ALCore (money.md §2.2). Not
// `Date`, not `TimeInterval`: the arithmetic stays byte-identical to the
// TypeScript's `Date.now()` / `TRIAL_MS` / `DAY_MS`, the persisted blob stays
// interchangeable with the PWA's, and `Int64` cannot accumulate the float drift
// a `TimeInterval` would.

/// Epoch milliseconds. NOT named `Clock` — that collides with the stdlib protocol.
public protocol TimeSource: Sendable {
    var nowMillis: Int64 { get }
}

public struct SystemTimeSource: TimeSource {
    public init() {}
    public var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Tests only. Settable, so a spec can walk a fortnight in four lines.
public final class MutableTimeSource: TimeSource, @unchecked Sendable {
    public var nowMillis: Int64

    public init(_ ms: Int64) { self.nowMillis = ms }

    public func advance(days: Double) { nowMillis += Int64(days * 86_400_000) }

    public func advance(millis: Int64) { nowMillis += millis }
}
