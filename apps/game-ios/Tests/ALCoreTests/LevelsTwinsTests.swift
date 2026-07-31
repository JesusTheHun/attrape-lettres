import Testing

@testable import ALCore

/// Port of the `twinPool / twinLevel`, `buildTwinRound`, `buildTwinSession` and
/// "sound-twins prompt lines" blocks of `src/levels.test.ts`.
@Suite struct LevelsTwinsTests {

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.twinLevel(0) == Levels.twinLevels[0])
        #expect(Levels.twinLevel(999) == Levels.twinLevels[Levels.twinLevels.count - 1])
        #expect(Levels.twinPool(0) == Levels.twinPool(1))
    }

    @Test func givesEveryLevelAPoolBigEnoughForItsPick() {
        for (i, cfg) in Levels.twinLevels.enumerated() {
            #expect(Levels.twinPool(i + 1).count >= cfg.pick)
        }
    }

    @Test func everyFamilyHasAtLeastTwoSameSoundWritings() {
        for i in Levels.twinLevels.indices {
            for f in Levels.twinPool(i + 1) { #expect(f.graphies.count >= 2) }
        }
    }

    @Test func keepsFamilySoundsDistinctAndGraphyTextsUniqueAcrossALevel() {
        for i in Levels.twinLevels.indices {
            let pool = Levels.twinPool(i + 1)
            #expect(Set(pool.map(\.sound)).count == pool.count)
            let texts = pool.flatMap { $0.graphies.map(\.text) }
            #expect(Set(texts).count == texts.count)
        }
    }

    @Test func alwaysHasEnoughOtherFamilyGraphiesToFillTheIntruderQuota() {
        for (i, cfg) in Levels.twinLevels.enumerated() {
            let pool = Levels.twinPool(i + 1)
            for f in pool {
                let others = pool.filter { $0.sound != f.sound }
                    .reduce(0) { $0 + $1.graphies.count }
                #expect(others >= cfg.distractors)
            }
        }
    }

    // MARK: - buildTwinRound

    private struct Run {
        var cfg: TwinLevel
        var family: TwinFamily
        var round: TwinRound
    }

    private static let runs: [Run] = Levels.twinLevels.enumerated().flatMap { i, cfg -> [Run] in
        let pool = Levels.twinPool(i + 1)
        return pool.enumerated().flatMap { fi, family in
            (0..<12).map { n in
                Run(
                    cfg: cfg,
                    family: family,
                    round: Levels.buildTwinRound(
                        family: family, pool: pool, distractors: cfg.distractors,
                        .seeded(UInt64(fi &* 53 &+ n))
                    )
                )
            }
        }
    }

    @Test func dealsEveryFamilyGraphyPlusExactlyDistractorsIntruders() {
        for r in Self.runs {
            #expect(r.round.tiles.count == r.family.graphies.count + r.cfg.distractors)
            for g in r.family.graphies {
                let tile = r.round.tiles.filter { $0.text == g.text }
                #expect(tile.count == 1)
                #expect(tile.first?.correct == true)
                #expect(tile.first?.sound == r.family.sound)
            }
            #expect(r.round.tiles.filter(\.correct).count == r.family.graphies.count)
        }
    }

    @Test func intrudersNeverSpellTheFamilysSoundAndNeverDuplicateAText() {
        for r in Self.runs {
            for t in r.round.tiles where !t.correct {
                #expect(t.sound != r.family.sound)
            }
            let texts = r.round.tiles.map(\.text)
            #expect(Set(texts).count == texts.count)
        }
    }

    @Test func givesEveryTileAUniqueId() {
        for r in Self.runs {
            let ids = r.round.tiles.map(\.id)
            #expect(Set(ids).count == ids.count)
        }
    }

    // MARK: - buildTwinSession

    @Test func seedsPickPlusRepeatsRoundsSpacedSoNoFamilyRepeatsBackToBack() {
        for (i, cfg) in Levels.twinLevels.enumerated() {
            for seed in 0..<40 {
                let run = Levels.buildTwinSession(level: i + 1, .seeded(UInt64(seed)))
                #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) seed \(seed)")
                for k in run.indices where k > 0 {
                    #expect(run[k - 1].family != run[k].family, "level \(i + 1) seed \(seed)")
                }
            }
        }
    }

    // MARK: - prompt lines

    @Test func asksForAllTwinsOfASoundAndCelebratesEachGraphysOwnWord() {
        #expect(Levels.twinPrompt(TwinFamily(sound: "ko", graphies: [])) == "Trouve tous les ko !")
        #expect(
            Levels.twinSuccess(TwinGraphy(text: "CO", word: "coq", emoji: "🐓")) == "Oui ! coq."
        )
    }
}
