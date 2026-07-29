import Testing

@testable import ALCore

/// Invariant 6's ALCore share: `faceLabel` is the `aria-label` a letter tile
/// carries, ported from `src/letterForms.ts` and tested here rather than left in
/// the view layer where nothing would test it. French copy is byte-exact —
/// note "attachée" carries a LEADING space, so there is no double space and no
/// trailing one.
@Suite struct FaceLabelTests {
    @Test func printMajuscule() {
        #expect(faceLabel(LetterFace(base: "A", glyph: "A", script: .print)) == "Lettre A majuscule")
    }

    @Test func printMinuscule() {
        #expect(faceLabel(LetterFace(base: "A", glyph: "a", script: .print)) == "Lettre A minuscule")
    }

    @Test func cursiveMinusculeAppendsAttachee() {
        #expect(
            faceLabel(LetterFace(base: "E", glyph: "e", script: .cursive))
                == "Lettre E minuscule attachée"
        )
    }

    @Test func cursiveMajusculeAppendsAttachee() {
        #expect(
            faceLabel(LetterFace(base: "E", glyph: "E", script: .cursive))
                == "Lettre E majuscule attachée"
        )
    }

    /// The case word is decided by the GLYPH, not the base — the base is always
    /// uppercase, so reading it would label every tile "majuscule".
    @Test func caseComesFromTheGlyphNotTheBase() {
        for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
            let base = String(letter)
            let lower = base.lowercased()
            #expect(faceLabel(LetterFace(base: base, glyph: base, script: .print)).hasSuffix("majuscule"))
            #expect(faceLabel(LetterFace(base: base, glyph: lower, script: .print)).hasSuffix("minuscule"))
        }
    }

    @Test func noTrailingOrDoubleSpace() {
        for script in LetterScript.allCases {
            for glyph in ["B", "b"] {
                let label = faceLabel(LetterFace(base: "B", glyph: glyph, script: script))
                #expect(!label.hasSuffix(" "))
                #expect(!label.contains("  "))
            }
        }
    }
}

@Suite struct LetterTypeShapeTests {
    @Test func scriptAndKindRawValues() {
        #expect(LetterScript.allCases.map(\.rawValue) == ["print", "cursive"])
        #expect(LetterMatchKind.allCases.map(\.rawValue) == ["case", "script"])
    }

    @Test func firstLetterLevelNilLettersMeansFullCatalog() {
        let full = FirstLetterLevel(letters: nil, pick: 8, repeats: 4)
        #expect(full.letters == nil)
        let restricted = FirstLetterLevel(letters: ["A", "B"], pick: 5, repeats: 3)
        #expect(restricted.letters == ["A", "B"])
    }

    @Test func letterWordImgIsAKeyNotAURL() {
        let jupe = LetterWord(letter: "J", word: "jupe", emoji: "👗", img: .jupe)
        #expect(jupe.img == .jupe)
        #expect(LetterWord(letter: "A", word: "avion", emoji: "✈️").img == nil)
    }
}
