// The injected, seedable randomness — the Swift shape of every `Math.random()`
// in `src/levels.ts`, plus the ordered-dedup helper that keeps a seeded run
// reproducible.
//
// D9: randomness is INJECTED AND SEEDABLE, and there is deliberately NO shared
// global instance. Two reasons, both learned from the React original:
//
//   1. A round builder that cannot be replayed cannot be tested, and these
//      builders carry the whole difficulty curve.
//   2. In React the session is built once, in a lazy `useState` initialiser. In
//      SwiftUI an expression in a `@State` default is evaluated EVERY time the
//      view struct is initialised even though only the first value is kept — so
//      building a session there silently builds and discards one on every parent
//      re-render. With a system RNG that is invisible; with a shared seeded one
//      it destroys reproducibility. Hence: never call a `build…Session` from a
//      view's `init` or a `@State` default (build it in `.task {}` or in an
//      `@Observable` model created once), and never reach for a global source.
//
// The convenience defaults below are `= .system()`, which Swift evaluates per
// call — a FRESH source each time, never a shared one.

/// The primitive every source provides: 64 raw bits.
///
/// Trivial on purpose — it exists so `RandomSource` can box any base without
/// caring whether it is the system generator or a deterministic one.
public protocol ALRandomGenerator {
    mutating func nextBits() -> UInt64
}

/// Deterministic, portable, no Foundation. SplitMix64 — a handful of mixing
/// steps per draw, and stable across OS versions (`arc4random` is not, and
/// `SystemRandomNumberGenerator` is not reproducible at all).
public struct SeededGenerator: ALRandomGenerator {
    private var state: UInt64

    public init(seed: UInt64) {
        self.state = seed
    }

    public mutating func nextBits() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Production randomness. Not reproducible, by definition.
public struct SystemGenerator: ALRandomGenerator {
    private var base = SystemRandomNumberGenerator()

    public init() {}

    public mutating func nextBits() -> UInt64 { base.next() }
}

/**
 * One run's randomness, threaded explicitly through every builder that consumes
 * it (always as the LAST parameter, so the call sites read like the TS did).
 */
// NB: ARCHITECTURE.md §5 sketches `RandomSource` as a `protocol` with a single
// `mutating func next(upperBound: Int) -> Int`. It is implemented here as the
// final class that data-core.md §4.1 specifies, for the reasons given there:
// `spellIntruders`, `buildGridRound` and `buildTwinRound` all call helpers and
// closures while holding partial arrays, and threading `inout` through that is
// noise at every level; and "the session's RNG" is naturally a reference. The
// `next(upperBound:)` spelling from ARCHITECTURE.md exists below as a method, so
// a call site written against either document compiles.
//
// Deliberately NOT `Sendable`: it is mutable reference state touched only from
// the UI thread. Fine in language mode v5 (D1); when ALCore flips to v6 this
// becomes `@MainActor`, a two-line change — provided nobody made it a global
// singleton in the meantime.
public final class RandomSource {
    private var draw: () -> UInt64

    public init<G: ALRandomGenerator>(_ base: G) {
        var g = base
        self.draw = { g.nextBits() }
    }

    /// A fresh, non-reproducible source. The default for every builder.
    public static func system() -> RandomSource { RandomSource(SystemGenerator()) }

    /// A reproducible source. The whole point of the injection.
    public static func seeded(_ seed: UInt64) -> RandomSource { RandomSource(SeededGenerator(seed: seed)) }

    /// `0 ..< n`, unbiased (Lemire's multiply-shift with rejection).
    /// Returns 0 when `n <= 0`. The TS analogue is `(Math.random() * n) | 0`.
    public func int(below n: Int) -> Int {
        guard n > 0 else { return 0 }
        let bound = UInt64(n)
        var product = draw().multipliedFullWidth(by: bound)
        if product.low < bound {
            // (2^64 mod bound), computed without 128-bit arithmetic.
            let threshold = (0 &- bound) % bound
            while product.low < threshold {
                product = draw().multipliedFullWidth(by: bound)
            }
        }
        return Int(product.high)
    }

    /// ARCHITECTURE.md §5's spelling of `int(below:)`. Same function.
    public func next(upperBound n: Int) -> Int { int(below: n) }

    /// The TS analogue is `Math.random() < 0.5`.
    public func bool() -> Bool { int(below: 2) == 0 }

    /**
     * Exact port of `shuffle<T>` from `src/levels.ts`:
     *
     *     const a = arr.slice();
     *     for (let i = a.length - 1; i > 0; i--) {
     *       const j = (Math.random() * (i + 1)) | 0;
     *       [a[i], a[j]] = [a[j], a[i]];
     *     }
     *
     * Written out rather than delegated to `Array.shuffled(using:)` on purpose:
     * descending Fisher–Yates with one draw per step means the NUMBER OF DRAWS
     * PER SHUFFLE matches the TS, which keeps a seeded Swift run structurally
     * comparable to a seeded JS run. The stdlib uses the same algorithm but
     * draws differently.
     */
    public func shuffled<T>(_ arr: [T]) -> [T] {
        var a = arr
        guard a.count > 1 else { return a }
        var i = a.count - 1
        while i > 0 {
            let j = int(below: i + 1)
            a.swapAt(i, j)
            i -= 1
        }
        return a
    }

    /// `nil` on an empty array. Analogue of `a[(Math.random() * a.length) | 0]`,
    /// whose `undefined` the TS then `??`-defaults.
    public func element<T>(of a: [T]) -> T? {
        guard !a.isEmpty else { return nil }
        return a[int(below: a.count)]
    }
}

/**
 * Insertion-ordered dedupe — the Swift shape of `Array.from(new Set(xs))`.
 */
// NB: this helper is not a convenience, it is a CORRECTNESS requirement.
// JS `Set` iterates in insertion order; Swift `Set` is unordered AND randomised
// per process. Three sites depend on the order (data-core.md §4.3):
//   - `Content.syllableBank` — its order IS the distractor distribution
//     (`pickDistractorSyllable` picks by index) and its first element is the
//     fallback (today "CHA");
//   - `spellIntruders`, which walks the answer's distinct letters in
//     first-appearance order;
//   - `buildFirstLetterSession`'s letter catalog.
// Missing one makes the result nondeterministic UNDER A FIXED SEED, which reads
// as a flaky test rather than a port bug. A plain `Set` is fine for pure
// membership tests (`need.has`, `seen.has`) — those never iterate.
public func orderedUnique<T: Hashable>(_ xs: [T]) -> [T] {
    var seen = Set<T>()
    var out: [T] = []
    out.reserveCapacity(xs.count)
    for x in xs where seen.insert(x).inserted {
        out.append(x)
    }
    return out
}
