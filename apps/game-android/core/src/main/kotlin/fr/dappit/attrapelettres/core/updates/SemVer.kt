package fr.dappit.attrapelettres.core.updates

// Semver-ish compare, enough for `a.b.c` release tags.
//
// Port of `versionAtLeast` in `src/updates.ts` (the iOS twin is SemVer.swift).
// Pure, and the only piece of the old update path that survives the port
// intact: it is still the guard against a payload that needs a shell the
// installed binary lacks, which is still a white screen on a child's tablet
// with no way back.
//
// The TS:
//
//   const h = have.split(".").map(Number);
//   const n = need.split(".").map(Number);
//   for (let i = 0; i < Math.max(h.length, n.length); i++) {
//     const a = h[i] ?? 0;
//     const b = n[i] ?? 0;
//     if (a !== b) return a > b;
//   }
//   return true;
//
// NB — the non-numeric segment. `Number("x")` is `NaN`, and `NaN !== anything`
// is true while `NaN > anything` is false, so the TS returns **false** at the
// first non-numeric segment on *either* side. A naive `toIntOrNull() ?: 0`
// maps it to `0`, which compares equal to a missing segment and lets the loop
// *continue* — the opposite branch. Segments are therefore modelled as `Int?`,
// with `null` standing for `NaN`: not equal to anything, and never greater.
// `Number("")` is `0`, not `NaN`, so an empty segment is `0`. There is no test
// for this in the TS and no real manifest will contain one; it is spelled out
// so an implementer does not silently pick the other branch.
//
// NB — `Number("3.5")` is `3.5` and `Number("1e3")` is `1000`, where
// `toIntOrNull` is `null`. Both are unreachable here: a "." always splits, and
// no release tag carries an exponent.
fun versionAtLeast(have: String, need: String): Boolean {
    val h = segments(have)
    val n = segments(need)
    for (i in 0 until maxOf(h.size, n.size)) {
        // Out of range is 0, exactly like the TS `?? 0` — NOT NaN.
        val a: Int? = if (i < h.size) h[i] else 0
        val b: Int? = if (i < n.size) n[i] else 0
        if (a == null || b == null) return false // NaN: not equal, not greater
        if (a != b) return a > b
    }
    return true
}

// `have.split(".").map(Number)`, with `null` for the `NaN` cases. Kotlin's
// `split` keeps empty pieces, like JS's — and `Number("")` is 0.
private fun segments(v: String): List<Int?> =
    v.split(".").map { piece -> if (piece.isEmpty()) 0 else piece.toIntOrNull() }
