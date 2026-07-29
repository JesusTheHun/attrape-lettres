import Testing

@testable import ALCore

/// Port of the `syllableGridPool`, `buildGridRound`, `buildSyllableGridSession`
/// and "syllable grid prompt lines" blocks of `src/levels.test.ts`.
@Suite struct LevelsSyllableGridTests {

    @Test func expandsTheLevelsRowsToEveryVowel() {
        for (i, rows) in Content.syllableGridRows.enumerated() {
            let pool = Levels.syllableGridPool(i + 1)
            let consonants = rows ?? Content.gridConsonants
            #expect(pool.count == consonants.count * Content.gridVowels.count)
            for c in consonants {
                for v in Content.gridVowels {
                    #expect(pool.contains { $0.consonant == c && $0.vowel == v }, "\(c)\(v)")
                }
            }
        }
    }

    @Test func keepsEveryCellUniqueAndReadable() {
        let pool = Levels.syllableGridPool(Levels.syllableGridLevels.count)
        #expect(Set(pool.map(\.text)).count == pool.count)
        for s in pool {
            #expect(s.text == s.consonant + s.vowel)
            #expect(s.sound == s.text.lowercased())
        }
    }

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.syllableGridLevel(0) == Levels.syllableGridLevels[0])
        #expect(
            Levels.syllableGridLevel(999)
                == Levels.syllableGridLevels[Levels.syllableGridLevels.count - 1]
        )
    }

    /// The content guard: a consonant whose sound flips with the vowel belongs to
    /// « Trouve le son » / « Les syllabes jumelles », never to the grid.
    @Test func teachesNoConsonantWhoseSoundFlipsWithTheVowel() {
        #expect(!Content.gridConsonants.contains("C"))
        #expect(!Content.gridConsonants.contains("G"))
        #expect(!Content.gridConsonants.contains("K"))
        #expect(!Content.gridConsonants.contains("QU"))
    }

    @Test func hasAPoolBigEnoughForAFullRunAtEveryLevel() {
        for (i, cfg) in Levels.syllableGridLevels.enumerated() {
            #expect(Levels.syllableGridPool(i + 1).count >= cfg.pick)
        }
    }

    // MARK: - buildGridRound

    private struct Run {
        var cfg: SyllableGridLevel
        var mode: SyllableGridMode
        var round: GridRound
    }

    private static let runs: [Run] = Levels.syllableGridLevels.enumerated().flatMap {
        i, cfg -> [Run] in
        let pool = Levels.syllableGridPool(i + 1)
        return SyllableGridMode.allCases.flatMap { mode in
            pool.enumerated().flatMap { ti, target in
                (0..<4).map { n in
                    Run(
                        cfg: cfg,
                        mode: mode,
                        round: Levels.buildGridRound(
                            target: target, pool: pool, cfg: cfg, mode: mode,
                            .seeded(UInt64(ti &* 17 &+ n))
                        )
                    )
                }
            }
        }
    }

    @Test func offersExactlyChoicesTilesTheAnswerAmongThemNeverTwice() {
        for r in Self.runs {
            #expect(r.round.choices.count == r.cfg.choices)
            #expect(r.round.choices.filter { $0.text == r.round.target.text }.count == 1)
            #expect(Set(r.round.choices.map(\.text)).count == r.round.choices.count)
        }
    }

    @Test func vowelModeSharesTheConsonantAcrossEveryTile() {
        for r in Self.runs where r.mode == .vowel {
            for c in r.round.choices { #expect(c.consonant == r.round.target.consonant) }
            #expect(Set(r.round.choices.map(\.vowel)).count == r.round.choices.count)
        }
    }

    @Test func hearModeSwapsAtMostColumnTilesOntoAnotherConsonant() {
        for r in Self.runs where r.mode == .hear {
            let swapped = r.round.choices.filter { $0.consonant != r.round.target.consonant }
            #expect(swapped.count <= r.cfg.column)
            // a swapped tile is the SAME vowel on another row (VA vs LA), never a
            // second axis of change at once.
            for c in swapped { #expect(c.vowel == r.round.target.vowel) }
        }
    }

    @Test func neverPutsATileThatSoundsLikeTheAnswerBesideIt() {
        for r in Self.runs {
            let twins = r.round.choices.filter {
                $0.sound == r.round.target.sound && $0.text != r.round.target.text
            }
            #expect(twins.isEmpty)
        }
    }

    // MARK: - buildSyllableGridSession

    @Test func seedsPickPlusRepeatsRoundsSpacedSoNoSyllableRepeatsBackToBack() {
        for (i, cfg) in Levels.syllableGridLevels.enumerated() {
            for mode in SyllableGridMode.allCases {
                for seed in 0..<20 {
                    let run = Levels.buildSyllableGridSession(
                        level: i + 1, mode: mode, .seeded(UInt64(seed))
                    )
                    #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) \(mode) seed \(seed)")
                    for k in run.indices where k > 0 {
                        #expect(
                            run[k - 1].target.text != run[k].target.text,
                            "level \(i + 1) \(mode) seed \(seed)"
                        )
                    }
                }
            }
        }
    }

    // MARK: - prompt lines

    @Test func speaksTheBareSyllableAndCelebratesWithItAgain() {
        let va = Levels.gridSyllable("V", "A")
        #expect(Levels.gridPrompt(va) == "va")
        #expect(Levels.gridSuccess(va) == "Oui ! va.")
        #expect(Levels.gridSuccess(Levels.gridSyllable("CH", "É")) == "Oui ! ché.")
    }

    @Test func theOnScreenConsigneCoversBothDrills() {
        #expect(Levels.gridConsigne.count == SyllableGridMode.allCases.count)
        #expect(Levels.gridConsigne[.hear] == "Écoute la syllabe et trouve son écriture")
        #expect(Levels.gridConsigne[.vowel] == "Écoute la syllabe et trouve la voyelle qui manque")
    }

    @Test func isReplayableUnderAFixedSeed() {
        for level in 1...Levels.syllableGridLevelCount {
            for mode in SyllableGridMode.allCases {
                #expect(
                    Levels.buildSyllableGridSession(level: level, mode: mode, .seeded(1234))
                        == Levels.buildSyllableGridSession(level: level, mode: mode, .seeded(1234))
                )
            }
        }
    }
}
