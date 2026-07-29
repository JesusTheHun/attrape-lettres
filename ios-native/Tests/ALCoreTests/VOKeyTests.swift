import Testing

@testable import ALCore

// Golden vectors for D17. EVERY expected value here was produced by running the
// TypeScript `voKey` in Node, and the six marked `ON DISK` were additionally
// checked against real filenames in `src/vo/clips/` (845 baked clips). They are
// the oracle: if the Swift changes and a vector fails, the Swift is wrong.
//
// A test that recomputed these from the Swift implementation could not fail, so
// none of them are.

@Suite("voKey — the clip-bank hash (D17)")
struct VOKeyTests {

    @Test func matchesTheTypeScriptOnPlainAsciiAndTheEmptyString() {
        #expect(voKey("") == "ztntfp")
        #expect(voKey("A") == "1ie980c")
        #expect(voKey("B") == "1j88139")  // ON DISK: 1j88139.m4a
        #expect(voKey("CHA") == "hv0eu5")  // ON DISK: hv0eu5.m4a
        #expect(voKey("MAI") == "c10hli")
        #expect(voKey("SON") == "9q8pln")
    }

    @Test func matchesTheTypeScriptOnAccentedFrench() {
        #expect(voKey("É") == "l3kwro")  // ON DISK: l3kwro.m4a
        #expect(voKey("é") == "tz86ys")
        #expect(voKey("Bravo ! Tu as tout réussi !") == "upd4kb")  // ON DISK
        #expect(voKey("Bravo ! Tu as tout trouvé !") == "1sh6e1p")
        #expect(voKey("Oh non ! On recommence.") == "68c1mf")  // ON DISK
        #expect(voKey("Trouve la bonne image.") == "19582p8")  // ON DISK
        #expect(voKey("Oui ! B. BALLON.") == "18ufc9r")
    }

    @Test func matchesTheTypeScriptOnTheShopLines() {
        #expect(voKey("C'est à toi !") == "1lly6oa")
        #expect(voKey("Tu as grandi !") == "1f8gzb0")
        #expect(voKey("Il te manque des étoiles.") == "14y05kb")
        #expect(voKey("Ça coûte 1 étoile.") == "ty037e")
        #expect(voKey("Ça coûte 12 étoiles.") == "jnmioj")
    }

    /// The reason the hash iterates `utf16` and not `unicodeScalars`: outside the
    /// BMP the two disagree, and the failure is silent (lookup misses, the app
    /// falls back to TTS — a different voice, no error).
    @Test func hashesAstralCharactersAsSurrogatePairs() {
        #expect(voKey("👂 emoji") == "1mkhw5w")

        // Prove the vector actually discriminates: the scalar-wise hash of the
        // same string is a DIFFERENT value, so this test fails if someone
        // "simplifies" the loop.
        var scalarwise: UInt32 = 0x811c_9dc5
        for s in "👂 emoji".unicodeScalars {
            scalarwise ^= UInt32(truncatingIfNeeded: s.value)
            scalarwise = scalarwise &* 0x0100_0193
        }
        #expect(String(scalarwise, radix: 36) != "1mkhw5w")
    }

    @Test func collapsesRunsOfWhitespaceAndTrimsTheEnds() {
        #expect(voKey("  espaces   multiples  ") == "5wgrym")
        #expect(voKey("\ttab\nnewline\t") == "1u7m45s")

        // The collapse is what makes those equal to their tidy forms.
        #expect(voKey("  espaces   multiples  ") == voKey("espaces multiples"))
        #expect(voKey("\ttab\nnewline\t") == voKey("tab newline"))
    }

    /// NFC normalisation: the composed and decomposed spellings of É are one clip.
    /// A Swift source file can hold either, so this is a real hazard, not a
    /// theoretical one.
    ///
    /// NB: Swift's `String ==` is canonical-equivalence based, so these two ARE
    /// `==` to each other — unlike JS, where `"\u{00C9}" === "E\u{0301}"` is
    /// false. That difference is exactly why the normalisation call still has to
    /// be here: `==` hides the distinction but `.utf16` does not, and the hash
    /// reads `.utf16`.
    @Test func normalisesToNFCSoDecomposedAccentsFindTheSameClip() {
        let composed = "\u{00C9}"  // É
        let decomposed = "E\u{0301}"  // E + combining acute

        // Distinct code units — one unit vs two — which is what the hash sees.
        #expect(Array(composed.utf16) == [0x00C9])
        #expect(Array(decomposed.utf16) == [0x0045, 0x0301])

        #expect(voKey(composed) == "l3kwro")
        #expect(voKey(decomposed) == "l3kwro")

        // And prove the normalisation is load-bearing rather than incidental:
        // hashing the decomposed code units directly gives a different key.
        var unnormalised: UInt32 = 0x811c_9dc5
        for unit in decomposed.utf16 {
            unnormalised ^= UInt32(unit)
            unnormalised = unnormalised &* 0x0100_0193
        }
        #expect(String(unnormalised, radix: 36) != "l3kwro")
    }

    @Test func theStringPropertyAndTheFreeFunctionAgree() {
        for s in ["", "A", "É", "CHA", "Ça coûte 1 étoile.", "👂 emoji"] {
            #expect(s.voKey == voKey(s))
        }
    }

    /// Base36 of a UInt32 is at most 7 characters, and the generator writes the
    /// key straight into a filename.
    @Test func producesAFilenameSafeLowercaseBase36Key() {
        let samples = [
            "", "A", "É", "CHA", "Bravo ! Tu as tout réussi !", "👂 emoji",
            "Ça coûte 12 étoiles.", "Oui ! B. BALLON.",
        ]
        for s in samples {
            let k = voKey(s)
            #expect(!k.isEmpty)
            #expect(k.count <= 7)
            #expect(k.allSatisfy { $0.isLowercase || $0.isNumber })
        }
    }
}
