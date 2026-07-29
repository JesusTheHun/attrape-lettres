/// Semver-ish compare, enough for `a.b.c` release tags.
///
/// Port of `versionAtLeast` in `src/updates.ts`. Pure, and the only piece of the
/// old update path that survives the port intact (D13): it is still the guard
/// against a payload that needs a shell the installed binary lacks, which is
/// still a white screen on a child's tablet with no way back.
///
/// ```ts
/// const h = have.split(".").map(Number);
/// const n = need.split(".").map(Number);
/// for (let i = 0; i < Math.max(h.length, n.length); i++) {
///   const a = h[i] ?? 0;
///   const b = n[i] ?? 0;
///   if (a !== b) return a > b;
/// }
/// return true;
/// ```
///
/// NB — the non-numeric segment (money.md §3.6 / R12). `Number("x")` is `NaN`,
/// and `NaN !== anything` is true while `NaN > anything` is false, so the TS
/// returns **false** at the first non-numeric segment on *either* side. A naive
/// `Int($0) ?? 0` maps it to `0`, which compares equal to a missing segment and
/// lets the loop *continue* — the opposite branch. Segments are therefore
/// modelled as `Int?`, with `nil` standing for `NaN`: not equal to anything, and
/// never greater. `Number("")` is `0`, not `NaN`, so an empty segment is `0`.
/// There is no test for this in the TS and no real manifest will contain one;
/// it is spelled out so an implementer does not silently pick the other branch.
///
/// NB — `Number("3.5")` is `3.5` and `Number("1e3")` is `1000`, where `Int` is
/// `nil`. Both are unreachable here: a "." always splits, and no release tag
/// carries an exponent.
public func versionAtLeast(_ have: String, _ need: String) -> Bool {
    let h = segments(have)
    let n = segments(need)
    for i in 0..<max(h.count, n.count) {
        // Out of range is 0, exactly like the TS `?? 0` — NOT NaN.
        let a: Int? = i < h.count ? h[i] : 0
        let b: Int? = i < n.count ? n[i] : 0
        guard let a, let b else { return false }  // NaN: not equal, not greater
        if a != b { return a > b }
    }
    return true
}

/// `have.split(".").map(Number)`, with `nil` for the `NaN` cases.
private func segments(_ v: String) -> [Int?] {
    // `split(separator:omittingEmptySubsequences: false)` because JS's split
    // keeps empty pieces and `Number("")` is 0.
    v.split(separator: ".", omittingEmptySubsequences: false).map { piece in
        piece.isEmpty ? 0 : Int(piece)
    }
}
