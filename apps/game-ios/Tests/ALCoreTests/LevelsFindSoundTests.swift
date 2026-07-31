import Testing

@testable import ALCore

/// Port of the `findSoundPool / findSoundLevel`, `buildFindSoundRound`,
/// `buildFindSoundSession` and "find-sound prompt lines" blocks of
/// `src/levels.test.ts`.
@Suite struct LevelsFindSoundTests {

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.findSoundLevel(0) == Levels.findSoundLevels[0])
        #expect(
            Levels.findSoundLevel(999) == Levels.findSoundLevels[Levels.findSoundLevels.count - 1]
        )
        #expect(Levels.findSoundPool(0) == Levels.findSoundPool(1))
    }

    @Test func givesEveryLevelAPoolBigEnoughForItsPickAndItsDistractors() {
        for (i, cfg) in Levels.findSoundLevels.enumerated() {
            let pool = Levels.findSoundPool(i + 1)
            #expect(pool.count >= cfg.pick)
            #expect(pool.count >= cfg.distractors + 1)
        }
    }

    @Test func keepsSoundsAndGraphiesDistinctWithinALevel() {
        for i in Levels.findSoundLevels.indices {
            let pool = Levels.findSoundPool(i + 1)
            #expect(Set(pool.map(\.sound)).count == pool.count)
            #expect(Set(pool.map(\.graphy)).count == pool.count)
        }
    }

    @Test func resolvesEveryAuthoredTrapToADifferentSoundEntryOfTheSamePool() {
        for i in Levels.findSoundLevels.indices {
            let pool = Levels.findSoundPool(i + 1)
            var byGraphy: [String: BasicSound] = [:]
            for e in pool { byGraphy[e.graphy] = e }
            for e in pool {
                for t in e.traps ?? [] {
                    let hit = byGraphy[t]
                    #expect(hit != nil, "level \(i + 1): trap \(t)")
                    #expect(hit?.sound != e.sound)
                }
            }
        }
    }

    // MARK: - buildFindSoundRound

    private struct Run {
        var cfg: FindSoundLevel
        var target: BasicSound
        var round: FindSoundRound
    }

    private static let runs: [Run] = Levels.findSoundLevels.enumerated().flatMap {
        i, cfg -> [Run] in
        let pool = Levels.findSoundPool(i + 1)
        return pool.enumerated().flatMap { ti, target in
            (0..<12).map { n in
                Run(
                    cfg: cfg,
                    target: target,
                    round: Levels.buildFindSoundRound(
                        target: target, pool: pool, distractors: cfg.distractors,
                        .seeded(UInt64(ti &* 101 &+ n))
                    )
                )
            }
        }
    }

    @Test func offersTheTargetPlusExactlyDistractorsChoicesAllDistinctGraphies() {
        for r in Self.runs {
            #expect(r.round.choices.count == r.cfg.distractors + 1)
            #expect(r.round.choices.filter { $0.graphy == r.target.graphy }.count == 1)
            let graphies = r.round.choices.map(\.graphy)
            #expect(Set(graphies).count == graphies.count)
        }
    }

    @Test func neverOffersADistractorThatSoundsLikeTheAnswer() {
        for r in Self.runs {
            for c in r.round.choices where c.graphy != r.target.graphy {
                #expect(c.sound != r.target.sound)
            }
        }
    }

    @Test func prefersTheAuthoredTrapsAsDistractorsWhenTheyCanFillTheRound() {
        for r in Self.runs where (r.target.traps?.count ?? 0) >= r.cfg.distractors {
            for c in r.round.choices where c.graphy != r.target.graphy {
                #expect(r.target.traps?.contains(c.graphy) == true)
            }
        }
    }

    // MARK: - buildFindSoundSession

    @Test func seedsPickPlusRepeatsRoundsSpacedSoNoSoundRepeatsBackToBack() {
        for (i, cfg) in Levels.findSoundLevels.enumerated() {
            for seed in 0..<40 {
                let run = Levels.buildFindSoundSession(level: i + 1, .seeded(UInt64(seed)))
                #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) seed \(seed)")
                for k in run.indices where k > 0 {
                    #expect(run[k - 1].target != run[k].target, "level \(i + 1) seed \(seed)")
                }
            }
        }
    }

    // MARK: - prompt lines

    @Test func anchorsThePromptToItsWordAndCelebratesWithTheWord() {
        let s = BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉")
        #expect(Levels.findSoundPrompt(s) == "ou, comme dans hibou.")
        #expect(Levels.findSoundSuccess(s) == "Oui ! hibou.")
    }

    @Test func isReplayableUnderAFixedSeed() {
        for level in 1...Levels.findSoundLevelCount {
            #expect(
                Levels.buildFindSoundSession(level: level, .seeded(1234))
                    == Levels.buildFindSoundSession(level: level, .seeded(1234))
            )
        }
    }
}
