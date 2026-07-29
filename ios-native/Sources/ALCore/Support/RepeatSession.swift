// Port of `repeatSession` from `src/levels.ts` — the shape of EVERY run in the
// app. Nine ladders seed their session through it.

/**
 * One run's ordered item list: pick `pick` distinct items from `pool`, replay
 * `repeats` of them a second time, and arrange so no item ever lands two rounds
 * in a row — the gap is what turns a repeat into memory practice. Both counts are
 * clamped to the pool, so a short pool just yields a shorter (still valid) run.
 *
 * Construction: group each repeated item as an adjacent pair (doubles first,
 * singles after), then deal that list across even indices, then odd. With no
 * item appearing more than twice and a run of ≥3, this is always collision-free.
 */
// NB: the paragraph above describes an EARLIER construction; the code rerolls.
// It is copied verbatim from the TypeScript because behaviour is frozen and that
// includes not silently editorialising the source — but a reader of the Swift
// would otherwise assume a guarantee that does not exist. What the code actually
// does is shuffle the whole run and reshuffle up to 64 times until no item lands
// twice in a row, GIVING UP with a back-to-back pair still present if it never
// gets there. Keep the bound and keep the give-up.
//
// Ported on INDICES, not on elements, and that is load-bearing: the TS adjacency
// test `x === out[i - 1]` is REFERENCE identity. The pools hold object
// references, so two structurally identical rows would still count as different
// items. Swift structs have no identity, so a naive `==` port would change
// behaviour the day a table grows a value-duplicate row. (Latent today, not
// live: `SOUND_TARGETS[3]` holds four `{sound:"oi", spelling:["O","I"]}` entries
// but each carries a different `word`.) Index identity ≡ TS reference identity,
// exactly, forever — and it lets the tests express "pick distinct items" without
// demanding `Hashable` elements.
public func repeatSessionIndices(
    count: Int,
    pick: Int,
    repeats: Int,
    _ rng: RandomSource = .system()
) -> [Int] {
    // NB: TS `Math.min(pick, pool.length)` with a negative `pick` would produce a
    // negative slice length, which JS quietly reinterprets from the end. Swift's
    // `prefix` traps instead. Nothing in the shipped ladders is negative and the
    // divergence is not worth a clamp that changes behaviour on real inputs.
    let p = min(pick, count)
    let r = min(repeats, p)

    let picks = Array(rng.shuffled(Array(0..<count)).prefix(p))
    if r == 0 { return picks }

    let doubles = Array(rng.shuffled(picks).prefix(r))
    // Random item picked to repeat AND random position: shuffle the whole run,
    // rerolling until no item lands twice in a row. Always solvable for runs of
    // ≥3 rounds; the cap only matters for the degenerate 1-item pool.
    var out = rng.shuffled(picks + doubles)
    var tries = 0
    while tries < 64 && hasAdjacentRepeat(out) {
        out = rng.shuffled(picks + doubles)
        tries += 1
    }
    return out
}

/// `repeatSessionIndices` mapped back onto the pool. This is the TS signature.
///
/// A one-element pool yields `[a, a]` — back-to-back and unavoidable; the TS
/// accepts it and so do we (invariant 3: there is no failure here either).
public func repeatSession<T>(
    _ pool: [T],
    pick: Int,
    repeats: Int,
    _ rng: RandomSource = .system()
) -> [T] {
    repeatSessionIndices(count: pool.count, pick: pick, repeats: repeats, rng).map { pool[$0] }
}

/// The TS `out.some((x, i) => i > 0 && x === out[i - 1])`, on indices.
private func hasAdjacentRepeat(_ out: [Int]) -> Bool {
    guard out.count > 1 else { return false }
    for i in 1..<out.count where out[i] == out[i - 1] { return true }
    return false
}
