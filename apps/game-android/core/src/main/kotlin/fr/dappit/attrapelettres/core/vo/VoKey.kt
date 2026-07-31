package fr.dappit.attrapelettres.core.vo

import java.text.Normalizer

// Port of `voKey` from `src/vo/utterances.ts` (the iOS twin is `VOKey.swift`,
// its D17).
//
// THE HASH ITERATES UTF-16 CODE UNITS, EXACTLY.
//
// The clip bank is keyed by this hash: the generator writes `<voKey>.m4a` and
// the runtime looks a clip up by hashing the utterance it is about to speak.
// The two must agree byte for byte or the lookup misses — nothing crashes and
// nothing is logged; the app simply degrades to text-to-speech for that one
// line. A different voice, silently.
//
// On the JVM this is the one place the port gets a trap for free: a Kotlin
// `String` IS a UTF-16 sequence and `Char.code` IS `charCodeAt(i)`, surrogate
// halves included, so the plain `for (unit in norm)` loop below cannot be
// wrong the way iterating Swift `Character`s or `unicodeScalars` could (the
// iOS port needed a whole file header about it). The emoji vector in
// `VoKeyTest` pins it anyway, against the day someone "simplifies" the loop
// into a code-point walk.
//
// The vectors in `VoKeyTest` were computed by RUNNING the TypeScript in Node
// and cross-checked against the real baked clip filenames staged into
// `platform/src/main/assets/vo/`. They are the source of truth; do not
// recompute them from this implementation.

// `text.replace(/\s+/g, " ")` — with JS's exact idea of `\s`. Java's regex
// `\s` is ASCII-only by default and its UNICODE_CHARACTER_CLASS variant is
// Unicode White_Space, which disagrees with ECMAScript on two code points
// (U+0085 in, U+FEFF out). So the class is spelled out: ECMA-262 WhiteSpace
// (TAB VT FF SP NBSP ZWNBSP + category Zs) plus LineTerminator (LF CR LS PS).
// The Swift port lives with a one-code-point divergence here (U+FEFF, which
// its CharacterSet keeps) and documents it; an explicit class costs Kotlin
// nothing, so this port closes even that gap — `VoKeyTest` pins U+FEFF, NBSP,
// U+202F, U+3000 and U+2028 against Node-computed keys.
private val JS_WHITESPACE =
    Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

// FNV-1a, 32-bit. `0x811c9dc5` does not fit a signed Int, so the literal is a
// Long narrowed once here; the bit pattern is the same.
private val FNV_OFFSET_BASIS: Int = 0x811c9dc5.toInt()
private const val FNV_PRIME: Int = 0x01000193

/**
 * Stable clip key for an utterance: FNV-1a (32-bit) over the NFC-normalised,
 * whitespace-collapsed text, base36. Identical in Node and the browser, so the
 * generator and the runtime always agree on `${voKey(text)}.wav`.
 */
// NB: the TS doc-comment says `.wav`; the staged clips are `.m4a`. Copied as
// written — the comment is stale in the original and the hash is what matters.
fun voKey(text: String): String {
    // Normalise FIRST, collapse second, exactly the TS order. NFC matters even
    // though Kotlin's `==` would call the composed and decomposed spellings of
    // « É » different strings either way: one clip must serve both, and the
    // hash reads code units, where they differ.
    //
    // After the collapse every leading/trailing run is a single U+0020, so
    // `trim(' ')` is `.trim()` exactly (JS trims the same set it collapsed).
    val norm = JS_WHITESPACE
        .replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ")
        .trim(' ')

    // `Math.imul(h, prime)` is a 32-bit wrapping multiply and `h >>> 0`
    // reinterprets the result as unsigned — Kotlin's `Int` multiply wraps the
    // same way, and `toUInt()` is the reinterpretation, so every intermediate
    // bit pattern is identical.
    var h = FNV_OFFSET_BASIS
    for (unit in norm) { // charCodeAt(i): UTF-16 code units, surrogates split
        h = h xor unit.code
        h *= FNV_PRIME
    }
    // `.toString(36)` — lowercase, digits then a…z. UInt.toString(36) matches.
    return h.toUInt().toString(36)
}
