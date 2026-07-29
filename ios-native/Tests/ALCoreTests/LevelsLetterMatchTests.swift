import Testing

@testable import ALCore

/// Port of the `letterMatchPool` / `buildLetterMatchSession` / `letterMatchPrompt`
/// blocks of `src/levels.test.ts`.
@Suite struct LevelsLetterMatchTests {

    @Test func restrictsToTheLevelsCatalogAndReturnsTheFullAlphabetForTheNullLevel() {
        let l1 = Set(Levels.letterMatchLevels[0].letters!)
        #expect(Levels.letterMatchPool(1).allSatisfy { l1.contains($0) })
        #expect(Levels.letterMatchPool(Levels.letterMatchLevels.count) == Content.letterMatchAlphabet)
    }

    @Test func givesEveryLevelAPoolBigEnoughForItsPick() {
        for (i, cfg) in Levels.letterMatchLevels.enumerated() {
            #expect(Levels.letterMatchPool(i + 1).count >= cfg.pick)
        }
    }

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.letterMatchLevel(0) == Levels.letterMatchLevels[0])
        #expect(
            Levels.letterMatchLevel(999) == Levels.letterMatchLevels[Levels.letterMatchLevels.count - 1]
        )
    }

    // MARK: - buildLetterMatchSession

    private struct Run {
        var kind: LetterMatchKind
        var level: Int
        var session: [LetterMatchRound]
    }

    /// Every kind × level, many runs — the invariants must hold on all pool shapes.
    private static let runs: [Run] = LetterMatchKind.allCases.flatMap { kind in
        Levels.letterMatchLevels.indices.flatMap { i in
            (0..<30).map { n in
                Run(
                    kind: kind,
                    level: i + 1,
                    session: Levels.buildLetterMatchSession(
                        kind: kind, level: i + 1, .seeded(UInt64(n))
                    )
                )
            }
        }
    }

    @Test func isPickPlusRepeatsRoundsSpacedSoNoLetterRepeatsBackToBack() {
        for r in Self.runs {
            let cfg = Levels.letterMatchLevels[r.level - 1]
            #expect(r.session.count == cfg.pick + cfg.repeats)
            for i in r.session.indices where i > 0 {
                #expect(r.session[i - 1].prompt.base != r.session[i].prompt.base)
            }
        }
    }

    @Test func offersExactlyOneCorrectCounterpartAmongDistinctLetterTiles() {
        for r in Self.runs {
            let cfg = Levels.letterMatchLevels[r.level - 1]
            for round in r.session {
                #expect(round.choices.count == cfg.distractors + 1)
                let bases = round.choices.map(\.base)
                #expect(Set(bases).count == bases.count)  // distinct letters
                #expect(bases.filter { $0 == round.prompt.base }.count == 1)
            }
        }
    }

    @Test func caseKindIsBothPlainPrintAndTilesFlipThePromptsCase() {
        for r in Self.runs where r.kind == .case {
            for round in r.session {
                #expect(round.prompt.script == .print)
                let answer = round.choices.first { $0.base == round.prompt.base }!
                #expect(answer.glyph != round.prompt.glyph)  // A ⇄ a, never A ⇄ A
                let promptUpper = round.prompt.glyph == round.prompt.glyph.uppercased()
                for c in round.choices {
                    #expect(c.script == .print)
                    #expect((c.glyph == c.glyph.uppercased()) == !promptUpper)
                }
            }
        }
    }

    @Test func scriptKindIsOneSharedCaseAndTilesFlipPrintCursive() {
        for r in Self.runs where r.kind == .script {
            for round in r.session {
                let otherScript: LetterScript = round.prompt.script == .cursive ? .print : .cursive
                let promptUpper = round.prompt.glyph == round.prompt.glyph.uppercased()
                for c in round.choices {
                    #expect(c.script == otherScript)
                    #expect((c.glyph == c.glyph.uppercased()) == promptUpper)  // same case as prompt
                }
            }
        }
    }

    // MARK: - letterMatchPrompt

    @Test func readsTheDirectionOffThePromptToAnswerTransform() {
        func P(_ base: String, _ glyph: String, _ script: LetterScript) -> LetterFace {
            LetterFace(base: base, glyph: glyph, script: script)
        }
        #expect(
            Levels.letterMatchPrompt(P("A", "A", .print), P("A", "a", .print))
                == Levels.letterMatchPrompts.toLower
        )
        #expect(
            Levels.letterMatchPrompt(P("A", "a", .print), P("A", "A", .print))
                == Levels.letterMatchPrompts.toUpper
        )
        #expect(
            Levels.letterMatchPrompt(P("A", "A", .print), P("A", "A", .cursive))
                == Levels.letterMatchPrompts.toCursive
        )
        #expect(
            Levels.letterMatchPrompt(P("A", "a", .cursive), P("A", "a", .print))
                == Levels.letterMatchPrompts.toPrint
        )
    }

    @Test func promptsIterateInDeclarationOrderForTheVOManifest() {
        #expect(
            Levels.letterMatchPrompts.all == [
                "Trouve la petite lettre.",
                "Trouve la grande lettre.",
                "Trouve la lettre attachée.",
                "Trouve la lettre en script.",
            ]
        )
    }

    @Test func successNamesTheLetterOnlyAfterTheMatch() {
        #expect(Levels.letterMatchSuccess("B") == "Oui ! B.")
    }

    @Test func isReplayableUnderAFixedSeed() {
        for kind in LetterMatchKind.allCases {
            for level in 1...Levels.letterMatchLevelCount {
                #expect(
                    Levels.buildLetterMatchSession(kind: kind, level: level, .seeded(1234))
                        == Levels.buildLetterMatchSession(kind: kind, level: level, .seeded(1234))
                )
            }
        }
    }
}
