import Foundation
import Testing

@testable import ALCore

// New coverage — `src/vo/utterances.ts` had no TypeScript suite; the manifest was
// only ever validated by baking it and listening.
//
// The strongest oracle available is the bake itself. `src/vo/clips/` holds 855
// real `.m4a` files named `<voKey(utterance)>.m4a`, produced from the SHIPPED
// TypeScript manifest. So: hash every utterance this port enumerates and require
// a file on disk. That single assertion transitively proves
//
//   - every French literal in Utterances.swift, byte for byte, apostrophes and
//     accents included (a one-character drift changes the hash);
//   - every `Levels.*` prompt/success function it calls;
//   - the content tables those functions read;
//   - and `voKey` itself, on 723 strings rather than the 20 hand-picked vectors
//     in `VOKeyTests`.
//
// A drifted line does not crash the app — the lookup misses and that ONE line
// speaks in the robot text-to-speech voice mid-narration. This is the test that
// notices.

private let clipsDirectory: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALCoreTests/UtterancesTests.swift
    .deletingLastPathComponent()  // …/Tests/ALCoreTests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/ios-native
    .deletingLastPathComponent()  // …/<repo root>
    .appendingPathComponent("src/vo/clips")

/// `<key>` for every baked clip, preferring nothing — presence is all that matters.
private let bakedKeys: Set<String> = {
    let names = (try? FileManager.default.contentsOfDirectory(atPath: clipsDirectory.path)) ?? []
    return Set(
        names
            .filter { ["m4a", "mp3", "wav"].contains(($0 as NSString).pathExtension) }
            .map { ($0 as NSString).deletingPathExtension }
    )
}()

@Suite("VO manifest — every utterance is a baked clip")
struct UtterancesBakeCoverageTests {

    /// Guards the guard. An empty or unfound clips directory would make every
    /// assertion below vacuously true.
    @Test("the clip bank is where the test thinks it is, and it is full")
    func clipBankResolves() {
        #expect(FileManager.default.fileExists(atPath: clipsDirectory.path), "\(clipsDirectory.path)")
        #expect(bakedKeys.count > 800, "only \(bakedKeys.count) clips found")
    }

    @Test("all 723 enumerated utterances have a clip on disk")
    func everyUtteranceIsBaked() {
        let all = VO.enumerateUtterances()
        #expect(all.count == 723)
        let missing = all.filter { !bakedKeys.contains(voKey($0)) }
        #expect(missing.isEmpty, "not baked: \(missing.prefix(5))")
    }

    @Test("the manifest is deduped and holds no empty or untrimmed line")
    func manifestIsClean() {
        let all = VO.enumerateUtterances()
        #expect(Set(all).count == all.count)
        for t in all {
            #expect(!t.isEmpty)
            #expect(t == t.trimmingCharacters(in: .whitespacesAndNewlines))
            #expect(!t.contains("  "))
        }
    }

    /// Enumeration order is not correctness — the lookup is by hash — but a
    /// stable order keeps a re-bake diffable, so it is pinned at both ends.
    @Test("enumeration order is stable and starts with the celebration lines")
    func orderIsStable() {
        let all = VO.enumerateUtterances()
        #expect(all[0] == "Bravo ! Tu as tout réussi !")
        #expect(all[1] == "Bravo ! Tu as tout trouvé !")
        #expect(all[2] == "Oh non ! On recommence.")
        #expect(all[3] == "Trouve la première lettre de Avion.")
        #expect(all[4] == "Oui ! A. Avion.")
        // The shop block is appended last, in first-appearance order of the
        // catalog's costs — 70 is the dragon's « Petit trésor », the last new
        // price the catalog introduces.
        #expect(all[all.count - 2] == "Ça coûte 70 étoiles.")
        #expect(all[all.count - 1] == "Ça coûte 70 étoiles. Il te manque des étoiles.")
        #expect(VO.enumerateUtterances() == all)
    }
}

@Suite("VO manifest — the shop vocabulary")
struct UtterancesShopTests {

    @Test("the three fixed shop lines are byte-exact and baked")
    func fixedShopLines() {
        #expect(VO.shopBought == "C'est à toi !")  // U+0027, not U+2019
        #expect(VO.shopGrew == "Tu as grandi !")
        #expect(VO.shopNeedMore == "Il te manque des étoiles.")
        // ON DISK, cross-checked against the real filenames.
        #expect(voKey(VO.shopBought) == "1lly6oa")
        #expect(voKey(VO.shopGrew) == "1f8gzb0")
        #expect(bakedKeys.contains(voKey(VO.shopBought)))
        #expect(bakedKeys.contains(voKey(VO.shopGrew)))
    }

    @Test("shopCostLine singularises at 1 and pluralises everywhere else")
    func costLineAgreement() {
        #expect(VO.shopCostLine(1) == "Ça coûte 1 étoile.")
        #expect(VO.shopCostLine(0) == "Ça coûte 0 étoiles.")  // NB: French would
        // write « 0 étoile », but the TS tests `cost === 1` and nothing else. No
        // catalog row costs 0 or 1, so this reading is never spoken; ported as
        // written rather than "fixed".
        #expect(VO.shopCostLine(18) == "Ça coûte 18 étoiles.")
        #expect(VO.shopCostLine(200) == "Ça coûte 200 étoiles.")
        #expect(voKey(VO.shopCostLine(18)) == "1yra4yx")  // ON DISK
        #expect(voKey(VO.shopCostLine(200)) == "1xpd2gi")  // ON DISK
    }

    /// Both readings a try-on can trigger are baked: the plain price, and price +
    /// "not enough yet" as ONE clip, because `say()` is passed the combined
    /// string and lookup is by exact utterance.
    @Test("every distinct catalog cost gets both a plain and a combined line")
    func everyCostIsSpoken() {
        let all = Set(VO.enumerateUtterances())
        let costs = Set(MascotCatalog.catalog.map(\.cost))
        #expect(costs == [16, 18, 20, 22, 24, 32, 40, 45, 48, 50, 55, 60, 65, 70, 75, 95, 200])
        for cost in costs {
            let plain = VO.shopCostLine(cost)
            #expect(all.contains(plain), "missing \(plain)")
            #expect(all.contains("\(plain) \(VO.shopNeedMore)"), "missing combined \(cost)")
            #expect(bakedKeys.contains(voKey(plain)))
            #expect(bakedKeys.contains(voKey("\(plain) \(VO.shopNeedMore)")))
        }
        // 17 costs × 2 readings + the two celebrations.
        #expect(all.filter { $0.hasPrefix("Ça coûte") }.count == costs.count * 2)
    }

    /// `shopNeedMore` alone is deliberately NOT in the manifest — it is only ever
    /// spoken glued to a price, so baking it alone would be a wasted clip. Same
    /// for `shopCostLine(1)`: no row costs 1.
    @Test("the manifest holds no clip nothing can speak")
    func noOrphanShopClips() {
        let all = Set(VO.enumerateUtterances())
        #expect(!all.contains(VO.shopNeedMore))
        #expect(!all.contains(VO.shopCostLine(1)))
    }
}

@Suite("VO manifest — the exercise vocabulary")
struct UtterancesExerciseTests {

    /// Every per-round line comes from the `Levels.*` function the exercise
    /// itself calls, so the manifest cannot describe a sentence the game never
    /// says. Re-authoring any of these inline is the failure mode.
    @Test("prompts and success lines come from the level builders")
    func promptsComeFromTheLevelBuilders() {
        let all = Set(VO.enumerateUtterances())
        #expect(all.contains(Levels.readImagePrompt))
        for p in Levels.letterMatchPrompts.all { #expect(all.contains(p), "\(p)") }
        for base in Content.letterMatchAlphabet {
            #expect(all.contains(Levels.letterMatchSuccess(base)), "\(base)")
        }
        for t in Content.soundTargets.flatMap({ $0 }) {
            #expect(all.contains(Levels.soundPrompt(t)))
            #expect(all.contains(Levels.soundSuccess(t)))
        }
        for s in Content.basicSounds.flatMap({ $0 }) {
            #expect(all.contains(s.sound))
            #expect(all.contains(Levels.findSoundPrompt(s)))
            #expect(all.contains(Levels.findSoundSuccess(s)))
        }
        for f in Content.twinFamilies.flatMap({ $0 }) {
            #expect(all.contains(f.sound))
            #expect(all.contains(Levels.twinPrompt(f)))
            for g in f.graphies { #expect(all.contains(Levels.twinSuccess(g))) }
        }
    }

    /// The syllable grid is enumerated over the WHOLE tableau, not the level
    /// pools, so a baked run covers it exactly once whatever a level draws.
    @Test("the whole consonant × vowel tableau is covered, both drills")
    func wholeGridIsCovered() {
        let all = Set(VO.enumerateUtterances())
        var cells = 0
        for c in Content.gridConsonants {
            for v in Content.gridVowels {
                let s = Levels.gridSyllable(c, v)
                #expect(all.contains(Levels.gridPrompt(s)), "\(s.text)")
                #expect(all.contains(Levels.gridSuccess(s)), "\(s.text)")
                cells += 1
            }
        }
        #expect(cells == Content.gridConsonants.count * Content.gridVowels.count)
        #expect(cells > 60, "only \(cells) grid cells — the tableau shrank?")
    }

    @Test("every word is spoken bare and in its success line, both exercise families")
    func everyWordIsSpoken() {
        let all = Set(VO.enumerateUtterances())
        for w in Content.letterWords {
            #expect(all.contains("Trouve la première lettre de \(w.word)."), "\(w.word)")
            #expect(all.contains("Oui ! \(w.letter). \(w.word)."), "\(w.word)")
            #expect(all.contains(w.word), "\(w.word)")  // auditioned on a picture tile
            #expect(all.contains("Oui ! \(w.word)."), "\(w.word)")
        }
        for w in Content.syllableWords {
            #expect(all.contains(w.word), "\(w.word)")
            #expect(all.contains("Oui ! \(w.word)."), "\(w.word)")
        }
    }

    @Test("the three fixed sentence lines are present, byte for byte")
    func fixedSentenceLines() {
        let all = Set(VO.enumerateUtterances())
        #expect(all.contains("Bravo ! Tu as tout réussi !"))
        #expect(all.contains("Bravo ! Tu as tout trouvé !"))
        #expect(all.contains("Oh non ! On recommence."))
        // ON DISK vectors, shared with VOKeyTests.
        #expect(voKey("Bravo ! Tu as tout réussi !") == "upd4kb")
        #expect(voKey("Oh non ! On recommence.") == "68c1mf")
    }

    /// Invariant 10, adjacent: the manifest is a shipping artefact but it is also
    /// the only place a child's own words could leak into a bake. There are none
    /// — every line is authored content.
    @Test("the manifest holds only authored copy — no name, no device id")
    func manifestIsAuthoredOnly() {
        for t in VO.enumerateUtterances() {
            #expect(!t.contains("undefined"))
            #expect(!t.contains("null"))
            #expect(!t.contains("{"))
        }
    }
}
