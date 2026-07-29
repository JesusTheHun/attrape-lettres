import Foundation
import Testing

@testable import ALCore

// Port of `src/vo/preview.test.ts` (4 cases), plus the same bake-coverage oracle
// `UtterancesTests` uses.

private let previewClipsDirectory: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALCoreTests/PreviewUtterancesTests.swift
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .appendingPathComponent("src/vo/clips")

private let previewBakedKeys: Set<String> = {
    let names = (try? FileManager.default.contentsOfDirectory(atPath: previewClipsDirectory.path)) ?? []
    return Set(
        names
            .filter { ["m4a", "mp3", "wav"].contains(($0 as NSString).pathExtension) }
            .map { ($0 as NSString).deletingPathExtension }
    )
}()

private let items = VO.enumeratePreviewUtterances()
private let texts = Set(items.map(\.text))

@Suite("preview VO catalog covers every audition-able tile")
struct PreviewUtterancesTests {

    @Test("has no duplicate texts and only known kinds")
    func noDuplicatesOnlyKnownKinds() {
        #expect(texts.count == items.count)
        for item in items { #expect([.letter, .syllable].contains(item.kind)) }
    }

    @Test("covers every syllable tile (Assemble draws splits + distractors from SYLLABLE_BANK)")
    func coversEverySyllableTile() {
        for s in Content.syllableBank { #expect(texts.contains(s), "\(s)") }
    }

    @Test("covers every letter tile (first letters, spelling graphemes, intruder bank)")
    func coversEveryLetterTile() {
        for w in Content.letterWords { #expect(texts.contains(w.letter), "\(w.letter)") }
        for l in Content.soundLetterBank { #expect(texts.contains(l), "\(l)") }
        for pool in Content.soundTargets {
            for t in pool {
                for g in t.spelling { #expect(texts.contains(g), "\(g)") }
            }
        }
    }

    @Test("keeps tile case verbatim — uppercase, so voKey matches what audio.speak() gets")
    func keepsTileCaseVerbatim() {
        // Assemble speaks UPPERCASE syllables, SpellSound/FirstLetter UPPERCASE letters.
        #expect(texts.contains("CHA"))  // syllable
        #expect(texts.contains("É"))  // accented grapheme, composed (NFC)
        #expect(texts.contains("B"))  // consonant letter
        for t in texts { #expect(t == t.uppercased(), "\(t)") }
    }
}

@Suite("preview VO catalog — kinds, coverage and the bake")
struct PreviewUtterancesExtraTests {

    /// The split is the point: the letters group is the one the team is prepared
    /// to roll back if lone letters read badly, so nothing a syllable round needs
    /// may be tagged `.letter`. Every SYLLABLE_BANK entry — including the lone
    /// vowels "A" and "É" that could plausibly have been claimed by the letters
    /// pass — must carry `.syllable`.
    @Test("SYLLABLE_BANK entries are all tagged .syllable, lone vowels included")
    func syllableBankWinsTheKind() {
        let kinds = Dictionary(uniqueKeysWithValues: items.map { ($0.text, $0.kind) })
        for s in Content.syllableBank { #expect(kinds[s] == .syllable, "\(s)") }
        // …and the letters-only sources that are NOT in the bank stay `.letter`.
        let bank = Set(Content.syllableBank)
        for l in Content.letterMatchAlphabet where !bank.contains(l) {
            #expect(kinds[l] == .letter, "\(l)")
        }
        // Both kinds are actually populated — a bug that tagged everything one
        // way would pass "no duplicates" and "only known kinds".
        #expect(items.contains { $0.kind == .syllable })
        #expect(items.contains { $0.kind == .letter })
    }

    @Test("letter-match tiles audition by name, so the whole alphabet is in")
    func letterMatchAlphabetIsCovered() {
        for l in Content.letterMatchAlphabet { #expect(texts.contains(l), "\(l)") }
    }

    /// Same oracle as the sentence manifest: the preview clips were baked from
    /// the shipped TypeScript, so every text this port enumerates must hash to a
    /// file that exists.
    @Test("all 122 preview tokens have a clip on disk")
    func everyPreviewTokenIsBaked() {
        #expect(FileManager.default.fileExists(atPath: previewClipsDirectory.path))
        #expect(previewBakedKeys.count > 800, "only \(previewBakedKeys.count) clips found")
        #expect(items.count == 122)
        let missing = items.filter { !previewBakedKeys.contains(voKey($0.text)) }
        #expect(missing.isEmpty, "not baked: \(missing.prefix(5).map(\.text))")
    }

    @Test("enumeration order is stable: the syllable bank first, then the letters")
    func orderIsStable() {
        #expect(items.first?.kind == .syllable)
        #expect(items.last?.kind == .letter)
        let firstLetterIndex = items.firstIndex { $0.kind == .letter }!
        #expect(items[..<firstLetterIndex].allSatisfy { $0.kind == .syllable })
        #expect(items[firstLetterIndex...].allSatisfy { $0.kind == .letter })
        #expect(VO.enumeratePreviewUtterances().map(\.text) == items.map(\.text))
    }
}
