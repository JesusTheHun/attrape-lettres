import Foundation

// Port of the four module-level tile-id counters in `src/levels.ts`:
// `_tileId` (syllable tiles), `_letterTileId` (sound tiles), `_twinTileId`
// (twin tiles), `_spellTileId` (spell-syllable tiles).

/**
 * Monotonic tile ids, for the process lifetime.
 *
 * The ids are React keys in the original and `Identifiable` ids here, and the
 * monotonicity is the point: if ids restarted at 0 each round, SwiftUI would
 * structurally identify round *n*'s tile 0 with round *n+1*'s tile 0 and reuse
 * the view — carrying the press/shake layer state across rounds (invariants 1
 * and 2). They must also stay STABLE across a reshuffle of the same round,
 * because the undo logic matches a slot back to its tray tile by id. Never
 * substitute an array index.
 */
// NB: the TypeScript has four counters only because it has four `let`
// declarations; ids are only ever compared within one tray, so one allocator is
// enough. Builders take `ids: TileIDAllocator = .shared` so a test can assert
// exact ids without the rest of the suite perturbing them.
//
// The counter is lock-protected, and that is not ceremony: `shared` is a
// process-wide mutable global, and an unsynchronised `counter += 1` on it is a
// data race by definition. It was one — `LevelsSpellSyllableTests` handed out
// the same id twice at roughly one run in six once the suite was large enough to
// build rounds from several threads at once. The fixture is fully seeded and
// deterministic, so the duplicate could only have come from the allocator.
//
// In the shipped app the builders are all reached from `@MainActor` models, so
// the race is currently unreachable there — but "unreachable by inspection of
// today's call sites" is not a property anything enforces, and the cost of the
// guarantee is one uncontended lock per tile, of which a round allocates a
// handful. A wrong id is not cosmetic: ids are SwiftUI identities, so a
// collision reuses a view and carries press/shake layer state onto the wrong
// tile (invariants 1 and 2), and the undo logic matches a slot back to its tray
// tile BY ID.
//
// `@unchecked Sendable` because the lock is the checker.
public final class TileIDAllocator: @unchecked Sendable {
    private let lock = NSLock()
    private var counter: Int

    public init(next: Int = 0) {
        self.counter = next
    }

    /// The TS `_tileId++` — returns the current value, then advances.
    public func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        defer { counter += 1 }
        return counter
    }

    /// The app-wide allocator. The default for every builder.
    public static let shared = TileIDAllocator()
}
