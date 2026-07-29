import Foundation
import Testing

@testable import ALCore

/// Invariant 4 — content is AUTHORED, not computed.
///
/// Read the first test before touching this file: `syllables.joined() == word`
/// is a **shape** check. It asserts that the authored split spells the authored
/// word. It is NOT a specification for a splitter, and the day someone "saves
/// duplication" by deriving `syllables` from `word` this test still passes while
/// the invariant is gone. There is no `(String) -> [String]` in ALCore and there
/// must never be one.
@Suite struct ContentIntegrityTests {

    // MARK: - The shape check (invariant 4)

    @Test func everySyllableSplitSpellsItsWord() {
        for w in Content.syllableWords {
            #expect(w.syllables.joined() == w.word, "bad split for \(w.word): \(w.syllables)")
        }
    }

    @Test func everySyllableIsNonEmptyAndUppercase() {
        for w in Content.syllableWords {
            #expect(!w.syllables.isEmpty, "\(w.word)")
            for s in w.syllables {
                #expect(!s.isEmpty, "\(w.word)")
                #expect(s == s.uppercased(), "\(w.word): \(s)")
            }
        }
    }

    // MARK: - Table sizes (counted from `src/content.ts`, not estimated)

    @Test func tableSizesMatchTheTypeScript() {
        #expect(Content.letterWords.count == 42)
        #expect(Content.letterMatchAlphabet.count == 26)
        #expect(Content.syllableWords.count == 45)
        #expect(Content.soundTargets.map(\.count) == [32, 28, 24, 24, 25])
        #expect(Content.soundTargets.reduce(0) { $0 + $1.count } == 133)
        #expect(Content.basicSounds.map(\.count) == [7, 8, 8, 7])
        #expect(Content.twinFamilies.map(\.count) == [5, 5, 4, 5])
        #expect(Content.twinFamilies.flatMap { $0 }.count == 19)
        #expect(Content.twinFamilies.flatMap { $0 }.flatMap(\.graphies).count == 48)
        #expect(Content.gridVowels.count == 6)
        #expect(Content.syllableGridRows.count == 8)
        #expect(Content.gridConsonants.count == 14)
        #expect(Content.spellSyllableWordNames.map(\.count) == [5, 6, 8, 8])
        #expect(Content.soundLetterBank.count == 22)
    }

    @Test func syllableWordsCoverTheThreeTierWidths() {
        let byWidth = Dictionary(grouping: Content.syllableWords, by: { $0.syllables.count })
        #expect(byWidth[2]?.count == 20)
        #expect(byWidth[3]?.count == 17)
        #expect(byWidth[4]?.count == 8)
    }

    // MARK: - The derived tables, and the Swift-Set trap (data-core.md §4.3)

    /// `SYLLABLE_BANK`'s order is load-bearing twice: `pickDistractorSyllable`
    /// picks by index (so the order IS the distribution) and its fallback is
    /// element 0. Through a Swift `Set` this array would be a different order on
    /// every launch — nondeterministic *under a fixed seed*.
    @Test func syllableBankIsFirstAppearanceOrder() {
        var expected: [String] = []
        var seen = Set<String>()
        for w in Content.syllableWords {
            for s in w.syllables where seen.insert(s).inserted { expected.append(s) }
        }
        #expect(Content.syllableBank == expected)
        #expect(Content.syllableBank.first == "CHA")
    }

    @Test func syllableBankIsDistinctAndCoversEverySyllable() {
        #expect(Set(Content.syllableBank).count == Content.syllableBank.count)
        let all = Set(Content.syllableWords.flatMap(\.syllables))
        #expect(Set(Content.syllableBank) == all)
    }

    @Test func gridConsonantsAreTheRowsInTeachingOrder() {
        #expect(Content.gridConsonants == ["L", "M", "R", "V", "P", "T", "B", "D", "F", "S", "N", "J", "Z", "CH"])
        #expect(Content.syllableGridRows.last! == nil, "the révision level must stay nil")
        #expect(Content.syllableGridRows.dropLast().allSatisfy { $0 != nil })
    }

    /// The authored content guard: C, G, K and QU are DELIBERATELY absent from
    /// the grid — CE/CI and GE/GI flip sound, which is Trouve le son / Les
    /// syllabes jumelles' lesson, not this one.
    @Test func gridExcludesTheConsonantsWhoseSoundFlips() {
        for banned in ["C", "G", "K", "QU"] {
            #expect(!Content.gridConsonants.contains(banned), "\(banned) must not be a grid row")
        }
    }

    @Test func gridCellsAreUnique() {
        #expect(Set(Content.gridConsonants).count == Content.gridConsonants.count)
        #expect(Set(Content.gridVowels).count == Content.gridVowels.count)
        #expect(Content.gridConsonants.count * Content.gridVowels.count == 84)
    }

    @Test func wordByNameResolvesEveryWordAndNothingElse() {
        #expect(Content.wordByName.count == Content.syllableWords.count)
        for w in Content.syllableWords {
            #expect(Content.wordByName[w.word] == w)
        }
        #expect(Content.wordByName["PAPILLON"] == nil)
    }

    // MARK: - The fill-a-syllable ladder resolves against the word table

    @Test func everySpellSyllableNameResolves() {
        for (i, level) in Content.spellSyllableWordNames.enumerated() {
            for name in level {
                #expect(Content.wordByName[name] != nil, "level \(i + 1): unknown word \"\(name)\"")
            }
        }
    }

    /// "Every word has ≥3 syllables so the two-syllable sibling always leaves a
    /// written anchor" — the authored comment, asserted.
    @Test func everySpellSyllableWordHasAtLeastThreeSyllables() {
        for level in Content.spellSyllableWordNames {
            for name in level {
                let w = Content.wordByName[name]
                #expect(w != nil, "\(name)")
                #expect((w?.syllables.count ?? 0) >= 3, "\(name) has \(w?.syllables.count ?? 0) syllables")
            }
        }
    }

    // MARK: - Per-table shape

    @Test func everyLetterWordHasASingleUppercaseLetter() {
        for w in Content.letterWords {
            #expect(w.letter.count == 1, "\(w.word)")
            #expect(w.letter == w.letter.uppercased(), "\(w.word)")
            // NB: folded, not literal. The authored note says "initials are kept
            // plain", but Éléphant and Île ship with an accented initial under
            // the plain E / I tile — that is the data, and §6.4's rule is not to
            // write a test the shipped data fails.
            let folded = w.word.uppercased().folding(options: .diacriticInsensitive, locale: nil)
            #expect(folded.hasPrefix(w.letter), "\(w.word) does not start with \(w.letter)")
            #expect(!w.emoji.isEmpty, "\(w.word)")
        }
    }

    @Test func theFourDedicatedIllustrationsAreTheOnesAuthored() {
        let letterImgs = Content.letterWords.compactMap(\.img)
        let syllableImgs = Content.syllableWords.compactMap(\.img)
        #expect(Set(letterImgs) == [.igloo, .jupe])
        #expect(Set(syllableImgs) == [.pyjama, .macaron])
        #expect(Set(letterImgs).union(syllableImgs) == Set(ImageKey.allCases))
    }

    @Test func everySoundTargetSpellingIsNonEmptyAndUppercase() {
        for (i, pool) in Content.soundTargets.enumerated() {
            for t in pool {
                #expect(!t.spelling.isEmpty, "level \(i + 1): \(t.sound)")
                for letter in t.spelling {
                    #expect(letter == letter.uppercased(), "level \(i + 1): \(t.sound) → \(letter)")
                    #expect(letter.count == 1, "level \(i + 1): \(t.sound) → \(letter)")
                }
                #expect(t.sound == t.sound.lowercased(), "level \(i + 1): \(t.sound)")
            }
        }
    }

    /// Levels 3–5 give every sound a context word + emoji; levels 1–2 do not.
    @Test func soundTargetContextWordsArriveAtLevelThree() {
        #expect(Content.soundTargets[0].allSatisfy { $0.word == nil && $0.emoji == nil })
        #expect(Content.soundTargets[1].allSatisfy { $0.word == nil && $0.emoji == nil })
        for i in 2..<5 {
            #expect(Content.soundTargets[i].allSatisfy { $0.word != nil && $0.emoji != nil }, "level \(i + 1)")
        }
    }

    @Test func everyBasicSoundGraphyIsUppercaseAndTheSoundIsLowercase() {
        for (i, pool) in Content.basicSounds.enumerated() {
            for s in pool {
                #expect(s.graphy == s.graphy.uppercased(), "level \(i + 1): \(s.graphy)")
                #expect(s.sound == s.sound.lowercased(), "level \(i + 1): \(s.sound)")
                #expect(!s.word.isEmpty && !s.emoji.isEmpty, "level \(i + 1): \(s.graphy)")
            }
        }
    }

    /// "Sounds within one level are all DISTINCT, so a distractor can never be a
    /// homophone of the answer" — and the graphies too, since the round builder
    /// dedupes on graphy.
    @Test func findSoundLevelsHaveDistinctSoundsAndGraphies() {
        for (i, pool) in Content.basicSounds.enumerated() {
            #expect(Set(pool.map(\.sound)).count == pool.count, "level \(i + 1): duplicate sound")
            #expect(Set(pool.map(\.graphy)).count == pool.count, "level \(i + 1): duplicate graphy")
        }
    }

    /// Every authored `trap` must resolve to another entry OF THE SAME POOL with
    /// a different sound — otherwise the trap-preferring distractor rule silently
    /// falls back to a random one.
    @Test func everyTrapResolvesWithinItsLevel() {
        for (i, pool) in Content.basicSounds.enumerated() {
            let byGraphy = Dictionary(uniqueKeysWithValues: pool.map { ($0.graphy, $0) })
            for s in pool {
                for trap in s.traps ?? [] {
                    let other = byGraphy[trap]
                    #expect(other != nil, "level \(i + 1): \(s.graphy) traps unknown \(trap)")
                    #expect(other?.sound != s.sound, "level \(i + 1): \(s.graphy) traps a homophone \(trap)")
                }
            }
        }
    }

    @Test func twinFamiliesAreWellFormed() {
        for (i, pool) in Content.twinFamilies.enumerated() {
            #expect(Set(pool.map(\.sound)).count == pool.count, "level \(i + 1): duplicate family sound")
            let texts = pool.flatMap(\.graphies).map(\.text)
            #expect(Set(texts).count == texts.count, "level \(i + 1): duplicate graphy text")
            for f in pool {
                #expect(f.graphies.count >= 2, "level \(i + 1): family \(f.sound) needs ≥2 graphies")
                #expect(f.sound == f.sound.lowercased(), "level \(i + 1): \(f.sound)")
                for g in f.graphies {
                    #expect(g.text == g.text.uppercased(), "level \(i + 1): \(g.text)")
                    #expect(!g.word.isEmpty && !g.emoji.isEmpty, "level \(i + 1): \(g.text)")
                }
            }
        }
    }

    @Test func theLetterBanksAreUppercaseAndDistinct() {
        for bank in [Content.soundLetterBank, Content.letterMatchAlphabet] {
            #expect(Set(bank).count == bank.count)
            for l in bank {
                #expect(l.count == 1)
                #expect(l == l.uppercased())
            }
        }
        // A–V, no W/X/Y/Z: nothing in the sound ladders spells with them.
        #expect(Content.soundLetterBank.first == "A")
        #expect(Content.soundLetterBank.last == "V")
        #expect(Content.letterMatchAlphabet.last == "Z")
    }

    // MARK: - NFC (data-core.md §6.3.4)

    /// An NFD "É" would change `Array(s)` (the spell exercise's cell count),
    /// `voKey` (the baked-clip lookup) and the rendered glyph — all at once, all
    /// silently. `content.ts` is NFC today; this pins it.
    @Test func everyAuthoredStringIsNFC() {
        var strings: [String] = []
        for w in Content.letterWords { strings += [w.letter, w.word, w.emoji] }
        strings += Content.letterMatchAlphabet
        for w in Content.syllableWords { strings += [w.word, w.emoji] + w.syllables }
        strings += Content.spellSyllableWordNames.flatMap { $0 }
        for t in Content.soundTargets.flatMap({ $0 }) {
            strings += [t.sound] + t.spelling + [t.word, t.emoji].compactMap { $0 }
        }
        for s in Content.basicSounds.flatMap({ $0 }) {
            strings += [s.sound, s.graphy, s.word, s.emoji] + (s.traps ?? [])
        }
        for f in Content.twinFamilies.flatMap({ $0 }) {
            strings.append(f.sound)
            for g in f.graphies { strings += [g.text, g.word, g.emoji] }
        }
        strings += Content.gridVowels
        strings += Content.syllableGridRows.flatMap { $0 ?? [] }
        strings += Content.soundLetterBank

        for s in strings {
            #expect(s == s.precomposedStringWithCanonicalMapping, "not NFC: \"\(s)\"")
        }
    }

    // MARK: - The two authoring rules, as regressions

    /// One tile string = one baked clip, so a shared syllable must sound the same
    /// in every word that uses it. MAI-SON (/zɔ̃/) and POIS-SON (/sɔ̃/) cannot
    /// coexist; SAPIN is what shipped instead, reusing PIN from LAPIN at /pɛ̃/.
    @Test func maisonNeverCameBack() {
        let words = Set(Content.syllableWords.map(\.word))
        #expect(words.contains("POISSON"))
        #expect(words.contains("SAPIN"))
        #expect(!words.contains("MAISON"), "MAI-SON makes SON say /zɔ̃/ while POIS-SON says /sɔ̃/")
    }

    /// « ll » alone never says /j/ in French — « ill » does, and it straddles the
    /// split. PAPILLON has no honest 3-way split and had to go.
    @Test func papillonNeverCameBack() {
        let words = Set(Content.syllableWords.map(\.word))
        #expect(!words.contains("PAPILLON"))
        #expect(words.contains("PERROQUET"))
        #expect(!Content.syllableBank.contains("LLON"))
    }

    /// An anchor word must contain its target sound exactly ONCE. « au, comme
    /// dans auto » was wrong: /oto/ has two /o/ and the second is spelled O.
    @Test func autoIsNotAnAuAnchor() {
        let auAnchors = Content.soundTargets.flatMap { $0 }
            .filter { $0.spelling == ["A", "U"] }
            .compactMap(\.word)
        #expect(!auAnchors.contains("auto"))
        #expect(auAnchors.contains("faucon"))
    }
}
