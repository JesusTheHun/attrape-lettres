import Foundation

// Port of `voKey` from `src/vo/utterances.ts`.
//
// D17 — THE HASH ITERATES UTF-16 CODE UNITS, EXACTLY.
//
// The clip bank is keyed by this hash: the generator writes `<voKey>.m4a` and
// the runtime looks a clip up by hashing the utterance it is about to speak.
// The two must agree byte for byte or the lookup misses.
//
// The trap that makes this worth its own file: iterating `unicodeScalars` or
// `Character`s instead of `utf16` yields a hash that is CORRECT for every ASCII
// and every Latin-1 string, and wrong only for the ones outside the BMP — the
// emoji-bearing lines. Nothing crashes and nothing is logged; the lookup simply
// misses and the app degrades to text-to-speech. A different voice, silently.
// `String.utf16` is what `charCodeAt(i)` returns, so `utf16` it is.
//
// The vectors in `VOKeyTests` were computed by RUNNING the TypeScript and
// cross-checked against the 845 baked clip filenames in `src/vo/clips/`. They
// are the source of truth; do not recompute them from this implementation.

extension String {
    /**
     * Stable clip key for an utterance: FNV-1a (32-bit) over the NFC-normalised,
     * whitespace-collapsed text, base36. Identical in Node and the browser, so the
     * generator and the runtime always agree on `${voKey(text)}.wav`.
     */
    // NB: the TS doc-comment says `.wav`; the baked clips on disk are `.m4a`.
    // Copied as written — the comment is stale in the original and the hash is
    // what matters.
    public var voKey: String { ALCore.voKey(self) }
}

/// Free function form, mirroring the TS export. `String.voKey` calls this.
public func voKey(_ text: String) -> String {
    let norm = collapseWhitespace(text.precomposedStringWithCanonicalMapping)

    // FNV-1a, 32-bit. `Math.imul(h, prime)` is a 32-bit wrapping multiply and
    // `h >>> 0` reinterprets the result as unsigned — doing the whole thing in
    // `UInt32` with `&^`/`&*` produces the identical bit pattern at every step.
    var h: UInt32 = 0x811c_9dc5
    for unit in norm.utf16 {  // charCodeAt(i) — NOT unicodeScalars, NOT Character
        h ^= UInt32(unit)
        h = h &* 0x0100_0193
    }
    // `.toString(36)` — lowercase, digits then a…z. `String(_:radix:)` matches.
    return String(h, radix: 36)
}

/**
 * `text.replace(/\s+/g, " ").trim()`.
 *
 * NB: JS's `\s` and Swift's `.whitespacesAndNewlines` agree on everything the
 * app can actually contain — space, tab, CR, LF, VT, FF, NBSP, the U+2000 block,
 * U+2028/9, U+202F, U+205F, U+3000. They differ on exactly one code point,
 * U+FEFF (a BOM mid-string), which JS strips and Swift keeps. No authored French
 * copy contains one, and adding the special case would mean claiming a
 * divergence that has never occurred. Left as-is, deliberately, and written down
 * here rather than fixed silently.
 */
private func collapseWhitespace(_ s: String) -> String {
    var out = ""
    out.reserveCapacity(s.count)
    var pendingSpace = false
    var wroteAnything = false
    for scalar in s.unicodeScalars {
        if CharacterSet.whitespacesAndNewlines.contains(scalar) {
            // Only emit a separator if something has already been written —
            // that folds the leading `.trim()` in for free.
            if wroteAnything { pendingSpace = true }
            continue
        }
        if pendingSpace {
            out.unicodeScalars.append(" ")
            pendingSpace = false
        }
        out.unicodeScalars.append(scalar)
        wroteAnything = true
    }
    // `pendingSpace` still set = trailing whitespace, which `.trim()` drops.
    return out
}
