package fr.dappit.attrapelettres.core.support

import java.util.concurrent.atomic.AtomicInteger

/* -------------------------------------------------------------------------- */
/* Port of the four module-level tile-id counters in `src/levels.ts`:           */
/* `_tileId` (syllable tiles), `_letterTileId` (sound tiles), `_twinTileId`     */
/* (twin tiles), `_spellTileId` (spell-syllable tiles).                         */
/* -------------------------------------------------------------------------- */

/**
 * Monotonic tile ids, for the process lifetime.
 *
 * The ids are React keys in the original and Compose `key()`s here, and the
 * monotonicity is the point: if ids restarted at 0 each round, Compose would
 * structurally identify round *n*'s tile 0 with round *n+1*'s tile 0 and reuse
 * the node — carrying the press/shake layer state across rounds (invariants 1
 * and 2). They must also stay STABLE across a reshuffle of the same round,
 * because the undo logic matches a slot back to its tray tile by id. Never
 * substitute an array index.
 *
 * The TypeScript has four counters only because it has four `let`
 * declarations; ids are only ever compared within one tray, so one allocator
 * is enough. Builders take `ids: TileIdAllocator = TileIdAllocator.shared` so
 * a test can assert exact ids without the rest of the suite perturbing them.
 *
 * The counter is an [AtomicInteger], and that is not ceremony: `shared` is a
 * process-wide mutable global, and an unsynchronised `counter++` on it is a
 * data race by definition. It was one — the iOS suite handed out the same id
 * twice at roughly one run in six once it was large enough to build rounds
 * from several threads at once, and only the allocator could have produced the
 * duplicate in a fully seeded fixture. In the shipped app the builders are all
 * reached from the main thread, so the race is currently unreachable there —
 * but "unreachable by inspection of today's call sites" is not a property
 * anything enforces, and the cost of the guarantee is one uncontended atomic
 * per tile, of which a round allocates a handful. A wrong id is not cosmetic:
 * ids are Compose identities, so a collision reuses a node and carries
 * press/shake state onto the wrong tile, and the undo logic matches BY ID.
 */
class TileIdAllocator(next: Int = 0) {
    private val counter = AtomicInteger(next)

    /** The TS `_tileId++` — returns the current value, then advances. */
    fun next(): Int = counter.getAndIncrement()

    companion object {
        /** The app-wide allocator. The default for every builder. */
        val shared = TileIdAllocator()
    }
}
