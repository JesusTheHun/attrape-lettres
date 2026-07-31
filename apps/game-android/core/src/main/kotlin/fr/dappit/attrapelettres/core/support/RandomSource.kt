package fr.dappit.attrapelettres.core.support

import kotlin.random.Random

/* -------------------------------------------------------------------------- */
/* The injected, seedable randomness — the Kotlin shape of every                */
/* `Math.random()` in `src/levels.ts`, plus the ordered-dedup helper that keeps */
/* a seeded run reproducible.                                                   */
/*                                                                              */
/* Randomness is INJECTED AND SEEDABLE, and there is deliberately NO shared     */
/* global instance (ARCHITECTURE.md §5: "injected, never global"). A round      */
/* builder that cannot be replayed cannot be tested, and these builders carry   */
/* the whole difficulty curve. The default arguments on the builders are        */
/* `= SystemRandomSource()`, which Kotlin evaluates per call — a FRESH source   */
/* each time, never a shared one.                                               */
/* -------------------------------------------------------------------------- */

/**
 * One run's randomness, threaded explicitly through every builder that
 * consumes it (always as the LAST parameter, so the call sites read like the
 * TS did). `next` is the single requirement; everything the builders actually
 * call — [bool], [shuffled], [elementOf] — is derived from it below, so any
 * implementation is automatically consistent across all four.
 */
interface RandomSource {
    /** `0 until upperBound`, uniform. Returns 0 when `upperBound <= 0` — the TS analogue `(Math.random() * n) | 0` yields 0 there (`NaN | 0`), and the call sites rely on never trapping. */
    fun next(upperBound: Int): Int
}

/**
 * Deterministic, portable, dependency-free. SplitMix64 — a handful of mixing
 * steps per draw, stable across JVMs and OS versions (`java.util.Random`'s
 * algorithm is specified but its seeding conventions invite accidents, and
 * `kotlin.random.Random(seed)`'s algorithm is not documented as frozen). The
 * same generator the iOS port uses, for the same reason: a seeded run must
 * mean the same thing next year.
 */
class SeededGenerator(seed: Long) : RandomSource {
    private var state: ULong = seed.toULong()

    /** The raw 64-bit draw. */
    private fun nextBits(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }

    override fun next(upperBound: Int): Int {
        if (upperBound <= 0) return 0
        val bound = upperBound.toULong()
        // arc4random_uniform-style rejection: `0uL - bound` wraps to
        // 2^64 - bound, whose remainder is 2^64 mod bound — the size of the
        // sliver at the bottom of the range that would make `% bound` uneven.
        // Unbiased with plain 64-bit arithmetic; no 128-bit multiply, which the
        // JVM only grew portably after our Android floor.
        val threshold = (0uL - bound) % bound
        var bits = nextBits()
        while (bits < threshold) {
            bits = nextBits()
        }
        return (bits % bound).toInt()
    }
}

/** Production randomness. Not reproducible, by definition. */
class SystemRandomSource : RandomSource {
    override fun next(upperBound: Int): Int =
        if (upperBound <= 0) 0 else Random.nextInt(upperBound)
}

/** The TS analogue is `Math.random() < 0.5`. */
fun RandomSource.bool(): Boolean = next(2) == 0

/**
 * Exact port of `shuffle<T>` from `src/levels.ts`:
 *
 *     const a = arr.slice();
 *     for (let i = a.length - 1; i > 0; i--) {
 *       const j = (Math.random() * (i + 1)) | 0;
 *       [a[i], a[j]] = [a[j], a[i]];
 *     }
 *
 * Written out rather than delegated to `List.shuffled(Random)` on purpose:
 * descending Fisher–Yates with one draw per step means the NUMBER OF DRAWS PER
 * SHUFFLE matches the TS, which keeps a seeded Kotlin run structurally
 * comparable to a seeded JS run. The stdlib uses the same algorithm but draws
 * through a different interface, so a seed would not survive the substitution.
 */
fun <T> RandomSource.shuffled(list: List<T>): List<T> {
    if (list.size < 2) return list.toList()
    val a = list.toMutableList()
    var i = a.size - 1
    while (i > 0) {
        val j = next(i + 1)
        val tmp = a[i]
        a[i] = a[j]
        a[j] = tmp
        i -= 1
    }
    return a
}

/**
 * `null` on an empty list. Analogue of `a[(Math.random() * a.length) | 0]`,
 * whose `undefined` the TS then `??`-defaults.
 */
fun <T> RandomSource.elementOf(list: List<T>): T? =
    if (list.isEmpty()) null else list[next(list.size)]

/**
 * Insertion-ordered dedupe — the shape of `Array.from(new Set(xs))`.
 *
 * Kotlin's `distinct()` already preserves first-seen order (it walks through a
 * `LinkedHashSet`), so unlike the Swift port this is not dodging a randomised
 * `Set` — but the named helper stays, for two reasons. The three ports share a
 * vocabulary, so the sites that are ORDER-SENSITIVE announce themselves the
 * same way in every language: `SYLLABLE_BANK` (its order IS the
 * distractor distribution and its first element is the fallback, today "CHA"),
 * `spellIntruders` (walks the answer's distinct letters in first-appearance
 * order) and the first-letter catalog. And the property is pinned by our own
 * test rather than by stdlib documentation — a future "optimisation" to a
 * plain `Set` would fail loudly here instead of surfacing as a flaky seeded
 * run.
 */
fun <T> orderedUnique(xs: List<T>): List<T> = xs.distinct()
