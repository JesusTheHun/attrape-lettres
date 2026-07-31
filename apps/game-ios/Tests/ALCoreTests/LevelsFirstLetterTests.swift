import Testing

@testable import ALCore

/// Port of the `firstLetterPool` block of `src/levels.test.ts`, plus the
/// session tests the TypeScript never had — `buildSession` lived inside
/// `FirstLetterExercise.tsx`, where no host test could reach it.
@Suite struct LevelsFirstLetterTests {

    @Test func restrictsWordsToTheLevelsAllowedLetters() {
        let level1 = Levels.firstLetterPool(1)
        let allowed = Set(Levels.firstLetterLevels[0].letters!)
        #expect(!level1.isEmpty)
        #expect(level1.allSatisfy { allowed.contains($0.letter) })
    }

    @Test func returnsTheFullCatalogForTheNullFinalLevel() {
        #expect(Levels.firstLetterPool(Levels.firstLetterLevels.count) == Content.letterWords)
    }

    @Test func everyLevelsPoolIsBigEnoughForItsPick() {
        for (i, cfg) in Levels.firstLetterLevels.enumerated() {
            #expect(Levels.firstLetterPool(i + 1).count >= cfg.pick)
        }
    }

    // MARK: - buildFirstLetterSession

    @Test func sessionIsPickPlusRepeatsSpacedSoNoWordRepeatsBackToBack() {
        for (i, cfg) in Levels.firstLetterLevels.enumerated() {
            for seed in 0..<40 {
                let run = Levels.buildFirstLetterSession(level: i + 1, .seeded(UInt64(seed)))
                #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) seed \(seed)")
                for k in run.indices where k > 0 {
                    #expect(run[k - 1].target.word != run[k].target.word, "level \(i + 1) seed \(seed)")
                }
            }
        }
    }

    /// The hard-coded 2 distractors: three choices, the answer once, all distinct.
    @Test func offersTheTargetLetterPlusExactlyTwoDistractors() {
        for (i, cfg) in Levels.firstLetterLevels.enumerated() {
            let catalog = Set(
                cfg.letters ?? orderedUnique(Levels.firstLetterPool(i + 1).map(\.letter))
            )
            for seed in 0..<20 {
                for round in Levels.buildFirstLetterSession(level: i + 1, .seeded(UInt64(seed))) {
                    #expect(round.choices.count == 3, "level \(i + 1) seed \(seed)")
                    #expect(Set(round.choices).count == round.choices.count)
                    #expect(round.choices.filter { $0 == round.target.letter }.count == 1)
                    #expect(round.choices.allSatisfy { catalog.contains($0) })
                }
            }
        }
    }

    @Test func isReplayableUnderAFixedSeed() {
        for level in 1...Levels.firstLetterLevels.count {
            let a = Levels.buildFirstLetterSession(level: level, .seeded(1234))
            let b = Levels.buildFirstLetterSession(level: level, .seeded(1234))
            #expect(a == b, "level \(level)")
        }
    }

    @Test func differentSeedsProduceDifferentSessions() {
        let a = Levels.buildFirstLetterSession(level: 5, .seeded(1))
        let b = Levels.buildFirstLetterSession(level: 5, .seeded(2))
        #expect(a != b)
    }
}
