package fr.dappit.attrapelettres.core.support

/* -------------------------------------------------------------------------- */
/* Port of `repeatSession` from `src/levels.ts` — the shape of EVERY run in the */
/* app. Nine ladders seed their session through it.                             */
/* -------------------------------------------------------------------------- */

/**
 * One run's ordered item list: pick `pick` distinct items from the pool,
 * replay `repeats` of them a second time, and arrange so no item ever lands
 * two rounds in a row — the gap is what turns a repeat into memory practice.
 * Both counts are clamped to the pool, so a short pool just yields a shorter
 * (still valid) run.
 *
 * What the code actually does: shuffle the whole run and reshuffle up to 64
 * times until no item lands twice in a row, GIVING UP with a back-to-back pair
 * still present if it never gets there. Always solvable for runs of ≥3 rounds;
 * the cap only matters for the degenerate 1-item pool. Keep the bound and keep
 * the give-up — they are copied from the TypeScript, and behaviour is frozen.
 *
 * Ported on INDICES, not on elements, and that is load-bearing: the TS
 * adjacency test `x === out[i - 1]` is REFERENCE identity. The pools hold
 * object references, so two structurally identical rows would still count as
 * different items. Kotlin data classes compare by value, so a naive `==` port
 * would change behaviour the day a table grows a value-duplicate row. (Latent
 * today, not live: `SOUND_TARGETS[3]` holds four `{sound:"oi",
 * spelling:["O","I"]}` entries but each carries a different `word`.) Index
 * identity ≡ TS reference identity, exactly, forever — and it lets the tests
 * express "pick distinct items" without demanding hashable elements.
 */
fun repeatSessionIndices(
    count: Int,
    pick: Int,
    repeats: Int,
    rng: RandomSource = SystemRandomSource(),
): List<Int> {
    // NB: TS `Math.min(pick, pool.length)` with a negative `pick` would produce
    // a negative slice length, which JS quietly reinterprets from the end.
    // Kotlin's `take` throws instead. Nothing in the shipped ladders is
    // negative and the divergence is not worth a clamp that changes behaviour
    // on real inputs.
    val p = minOf(pick, count)
    val r = minOf(repeats, p)

    val picks = rng.shuffled((0 until count).toList()).take(p)
    if (r == 0) return picks

    val doubles = rng.shuffled(picks).take(r)
    // Random item picked to repeat AND random position: shuffle the whole run,
    // rerolling until no item lands twice in a row.
    var out = rng.shuffled(picks + doubles)
    var tries = 0
    while (tries < 64 && hasAdjacentRepeat(out)) {
        out = rng.shuffled(picks + doubles)
        tries += 1
    }
    return out
}

/**
 * [repeatSessionIndices] mapped back onto the pool. This is the TS signature.
 *
 * A one-element pool yields `[a, a]` — back-to-back and unavoidable; the TS
 * accepts it and so do we (invariant 3: there is no failure here either).
 */
fun <T> repeatSession(
    pool: List<T>,
    pick: Int,
    repeats: Int,
    rng: RandomSource = SystemRandomSource(),
): List<T> = repeatSessionIndices(pool.size, pick, repeats, rng).map { pool[it] }

/** The TS `out.some((x, i) => i > 0 && x === out[i - 1])`, on indices. */
private fun hasAdjacentRepeat(out: List<Int>): Boolean {
    for (i in 1 until out.size) {
        if (out[i] == out[i - 1]) return true
    }
    return false
}
