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
// Not `Sendable`, same v5 reasoning as `RandomSource` (D1).
public final class TileIDAllocator {
    private var counter: Int

    public init(next: Int = 0) {
        self.counter = next
    }

    /// The TS `_tileId++` — returns the current value, then advances.
    public func next() -> Int {
        defer { counter += 1 }
        return counter
    }

    /// The app-wide allocator. The default for every builder.
    public static let shared = TileIDAllocator()
}
