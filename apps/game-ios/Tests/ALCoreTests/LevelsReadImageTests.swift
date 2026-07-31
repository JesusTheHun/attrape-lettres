import Testing

@testable import ALCore

/// Port of the `readImageLevel` / `buildReadImageRound` / `buildReadImageSession`
/// blocks of `src/levels.test.ts`.
@Suite struct LevelsReadImageTests {

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.readImageLevel(0) == Levels.readImageLevels[0])
        #expect(Levels.readImageLevel(999) == Levels.readImageLevels[Levels.readImageLevels.count - 1])
    }

    @Test func hasAPoolBigEnoughToPickAFullRunPlusDistractorsAtEveryLevel() {
        for cfg in Levels.readImageLevels {
            #expect(Levels.readImagePool().count >= cfg.pick)
            // one target + its distractors must all fit, by DISTINCT emoji.
            let distinctEmoji = Set(Levels.readImagePool().map(\.emoji)).count
            #expect(distinctEmoji >= cfg.distractors + 1)
        }
    }

    // MARK: - buildReadImageRound

    private struct Run {
        var cfg: ReadImageLevel
        var round: ReadImageRound
    }

    /// Every level's distractor count, many runs on many targets.
    private static let runs: [Run] = Levels.readImageLevels.flatMap { cfg in
        Levels.readImagePool().enumerated().flatMap { wi, target in
            (0..<8).map { n in
                Run(
                    cfg: cfg,
                    round: Levels.buildReadImageRound(
                        target: target,
                        distractors: cfg.distractors,
                        .seeded(UInt64(wi &* 31 &+ n))
                    )
                )
            }
        }
    }

    @Test func offersTheTargetPlusExactlyDistractorsChoices() {
        for r in Self.runs {
            #expect(r.round.choices.count == r.cfg.distractors + 1)
            #expect(r.round.choices.filter { $0.word == r.round.target.word }.count == 1)
        }
    }

    @Test func neverShowsTwoTilesWithTheSamePicture() {
        for r in Self.runs {
            let emojis = r.round.choices.map(\.emoji)
            #expect(Set(emojis).count == emojis.count)
        }
    }

    // MARK: - buildReadImageSession

    @Test func seedsPickPlusRepeatsRoundsSpacedSoNoWordRepeatsBackToBack() {
        for (i, cfg) in Levels.readImageLevels.enumerated() {
            for seed in 0..<40 {
                let run = Levels.buildReadImageSession(level: i + 1, .seeded(UInt64(seed)))
                #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) seed \(seed)")
                for k in run.indices where k > 0 {
                    #expect(run[k - 1].target.word != run[k].target.word, "level \(i + 1) seed \(seed)")
                }
            }
        }
    }

    @Test func isReplayableUnderAFixedSeed() {
        for level in 1...Levels.readImageLevelCount {
            #expect(
                Levels.buildReadImageSession(level: level, .seeded(1234))
                    == Levels.buildReadImageSession(level: level, .seeded(1234))
            )
        }
    }
}
