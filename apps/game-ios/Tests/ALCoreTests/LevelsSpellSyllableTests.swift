import Testing

@testable import ALCore

/// Port of the `spellSyllablePool`, `buildSpellSyllableRound`,
/// `buildSpellSyllableRound — écritures mêlées (mixed)` and
/// `buildSpellSyllableSession` blocks of `src/levels.test.ts`.
@Suite struct LevelsSpellSyllableTests {

    // MARK: - spellSyllablePool

    @Test func clampsOutOfRangeLevelsAndResolvesToRealThreeSyllableWords() {
        #expect(Levels.spellSyllablePool(0) == Levels.spellSyllablePool(1))
        #expect(
            Levels.spellSyllablePool(999)
                == Levels.spellSyllablePool(Levels.spellSyllableLevels.count)
        )
        for i in Levels.spellSyllableLevels.indices {
            let pool = Levels.spellSyllablePool(i + 1)
            #expect(!pool.isEmpty)
            // ≥3 syllables so the two-syllable sibling always leaves a written anchor.
            for w in pool { #expect(w.syllables.count >= 3) }
        }
    }

    @Test func keepsLevelOneTinyAndGivesEveryLevelEnoughToPickAFullRun() {
        #expect(Levels.spellSyllablePool(1).count <= 5)
        for (i, cfg) in Levels.spellSyllableLevels.enumerated() {
            #expect(Levels.spellSyllablePool(i + 1).count >= cfg.pick)
        }
    }

    // MARK: - buildSpellSyllableRound (plain)

    private struct Run {
        var mode: SpellSyllableMode
        var word: SyllableWord
        var round: SpellSyllableRound
        var distractors: Int
    }

    /// Every mode × level, many runs — the invariants must hold on every pool word.
    private static let runs: [Run] = SpellSyllableMode.allCases.flatMap { mode in
        Levels.spellSyllableLevels.indices.flatMap { i -> [Run] in
            let cfg = Levels.spellSyllableLevel(i + 1)
            return Levels.spellSyllablePool(i + 1).enumerated().flatMap { wi, word in
                (0..<12).map { n in
                    Run(
                        mode: mode,
                        word: word,
                        round: Levels.buildSpellSyllableRound(
                            word: word, mode: mode, distractors: cfg.distractors,
                            .seeded(UInt64(wi &* 197 &+ n))
                        ),
                        distractors: cfg.distractors
                    )
                }
            }
        }
    }

    @Test func spellsTheWholeWordCellByCellInReadingOrder() {
        for r in Self.runs {
            #expect(r.round.cells.map(\.letter).joined() == r.word.syllables.joined())
        }
    }

    @Test func hidesWholeSyllablesAndAlwaysLeavesAWrittenAnchor() {
        for r in Self.runs {
            var ci = 0
            var hiddenCount = 0
            for s in r.word.syllables {
                let len = s.count
                let group = Array(r.round.cells[ci..<(ci + len)])
                ci += len
                let allFill = group.allSatisfy(\.fill)
                let noneFill = group.allSatisfy { !$0.fill }
                #expect(allFill || noneFill)  // never a half-hidden syllable
                if allFill { hiddenCount += 1 }
            }
            #expect(hiddenCount == (r.mode == .lettersTwo ? 2 : 1))
            #expect(r.round.cells.contains { !$0.fill })  // ≥1 anchor letter shown
        }
    }

    @Test func answerIsTheGapLettersInOrderNumberedZeroToNMinusOne() {
        for r in Self.runs {
            let fills = r.round.cells.filter(\.fill)
            #expect(fills.map(\.slotIndex) == Array(fills.indices))
            #expect(r.round.answer == fills.map(\.letter))
            for c in r.round.cells where !c.fill { #expect(c.slotIndex == -1) }
        }
    }

    @Test func lettersExactTrayIsExactlyTheGapLettersNoIntruders() {
        for r in Self.runs where r.mode == .lettersExact {
            #expect(r.round.tray.map(\.letter).sorted() == r.round.answer.sorted())
        }
    }

    @Test func lettersExtraAndTwoAddExactlyDistractorsIntrudersNoneInTheGap() {
        for r in Self.runs where r.mode != .lettersExact {
            #expect(r.round.tray.count == r.round.answer.count + r.distractors)
            let need = Set(r.round.answer)
            let intruders = r.round.tray.map(\.letter).filter { !need.contains($0) }
            #expect(intruders.count == r.distractors)
            for l in intruders { #expect(Content.soundLetterBank.contains(l)) }
        }
    }

    @Test func givesEveryTrayTileAUniqueId() {
        for r in Self.runs {
            let ids = r.round.tray.map(\.id)
            #expect(Set(ids).count == ids.count)
        }
    }

    // MARK: - buildSpellSyllableRound — écritures mêlées (mixed)

    private static func key(glyph: String, script: LetterScript) -> String {
        "\(glyph)|\(script.rawValue)"
    }
    private static func isUpper(_ g: String) -> Bool { g == g.uppercased() }
    /// Writing signature = case + script; exactly the three MIXED_FORMS are legal.
    private static func sig(glyph: String, script: LetterScript) -> String {
        "\(isUpper(glyph))|\(script.rawValue)"
    }
    private static let legal: Set<String> = ["true|print", "false|print", "false|cursive"]

    /// Only the two intruder spellers get a mixed twin; -exact never does.
    private static let mixedRuns: [Run] = [SpellSyllableMode.lettersExtra, .lettersTwo].flatMap {
        mode in
        Levels.spellSyllableLevels.indices.flatMap { i -> [Run] in
            let cfg = Levels.spellSyllableLevel(i + 1)
            return Levels.spellSyllablePool(i + 1).enumerated().flatMap { wi, word in
                (0..<12).map { n in
                    Run(
                        mode: mode,
                        word: word,
                        round: Levels.buildSpellSyllableRound(
                            word: word, mode: mode, distractors: cfg.distractors, mixed: true,
                            .seeded(UInt64(wi &* 389 &+ n))
                        ),
                        distractors: cfg.distractors
                    )
                }
            }
        }
    }

    @Test func drawsTheWholeWordInOneLegalWriting() {
        for r in Self.mixedRuns {
            let sigs = Set(r.round.cells.map { Self.sig(glyph: $0.glyph, script: $0.script) })
            #expect(sigs.count == 1)
            #expect(Self.legal.contains(sigs.first!))
            // Its glyphs are the correct case for that writing.
            for c in r.round.cells {
                #expect(c.glyph == (Self.isUpper(c.glyph) ? c.letter : c.letter.lowercased()))
            }
        }
    }

    @Test func staysSolvableEveryGapFaceHasAMatchingTrayTile() {
        for r in Self.mixedRuns {
            var trayLeft: [String: Int] = [:]
            for t in r.round.tray {
                trayLeft[Self.key(glyph: t.glyph, script: t.script), default: 0] += 1
            }
            for f in r.round.answerFaces {
                let k = Self.key(glyph: f.glyph, script: f.script)
                #expect((trayLeft[k] ?? 0) > 0)
                trayLeft[k] = (trayLeft[k] ?? 0) - 1
            }
        }
    }

    @Test func addsExactlyDistractorsTrapsNoneAValidAnswerAllDistinctForms() {
        for r in Self.mixedRuns {
            #expect(r.round.tray.count == r.round.answerFaces.count + r.distractors)
            // Strip one tray tile per answer face; what's left are the distractors.
            var need: [String: Int] = [:]
            for f in r.round.answerFaces {
                need[Self.key(glyph: f.glyph, script: f.script), default: 0] += 1
            }
            var extras: [SpellLetterTile] = []
            for t in r.round.tray {
                let k = Self.key(glyph: t.glyph, script: t.script)
                let n = need[k] ?? 0
                if n > 0 {
                    need[k] = n - 1
                } else {
                    extras.append(t)
                }
            }
            #expect(extras.count == r.distractors)
            let answerKeys = Set(r.round.answerFaces.map { Self.key(glyph: $0.glyph, script: $0.script) })
            let extraKeys = extras.map { Self.key(glyph: $0.glyph, script: $0.script) }
            #expect(Set(extraKeys).count == extraKeys.count)  // distinct
            for k in extraKeys { #expect(!answerKeys.contains(k)) }  // never a right tile
        }
    }

    @Test func alwaysPlantsAtLeastOneSameLetterWrongWritingTrap() {
        for r in Self.mixedRuns {
            let answerKeys = Set(r.round.answerFaces.map { Self.key(glyph: $0.glyph, script: $0.script) })
            let answerBases = Set(r.round.answerFaces.map(\.base))
            // A tile whose LETTER is needed but whose exact face isn't a valid answer.
            let trap = r.round.tray.contains {
                answerBases.contains($0.letter)
                    && !answerKeys.contains(Self.key(glyph: $0.glyph, script: $0.script))
            }
            #expect(trap)
        }
    }

    // MARK: - buildSpellSyllableSession

    @Test func seedsPickPlusRepeatsRoundsSpacedSoNoWordRepeatsBackToBack() {
        for (i, cfg) in Levels.spellSyllableLevels.enumerated() {
            for seed in 0..<40 {
                let run = Levels.buildSpellSyllableSession(level: i + 1, .seeded(UInt64(seed)))
                #expect(run.count == cfg.pick + cfg.repeats, "level \(i + 1) seed \(seed)")
                for k in run.indices where k > 0 {
                    #expect(run[k - 1].word != run[k].word, "level \(i + 1) seed \(seed)")
                }
            }
        }
    }

    // MARK: - port-specific

    /// `spellIntruders` walks the answer's DISTINCT letters in first-appearance
    /// order (JS `new Set`). A Swift `Set` there would randomise the trap order
    /// per process — nondeterminism under a fixed seed.
    @Test func mixedTrapsFollowTheAnswersFirstAppearanceOrder() {
        let faces = Levels.spellIntruders(
            answer: ["B", "A", "B", "C"],
            form: Levels.plainForm,
            extra: 9,
            mixed: true,
            .seeded(1)
        )
        // The wrong-writing traps come first and cover B, A, C in that order,
        // three writings each minus the ones the answer already uses.
        #expect(Set(faces.prefix(6).map(\.base)) == ["B", "A", "C"])
        #expect(faces.count == 9)
    }

    @Test func plainIntrudersAreAlwaysWrongLettersInTheRoundsForm() {
        let faces = Levels.spellIntruders(
            answer: ["B", "A"], form: Levels.plainForm, extra: 3, mixed: false, .seeded(2)
        )
        #expect(faces.count == 3)
        for f in faces {
            #expect(!["B", "A"].contains(f.base))
            #expect(f.script == .print)
            #expect(f.glyph == f.base)
            #expect(Content.soundLetterBank.contains(f.base))
        }
    }

    @Test func noIntrudersWhenExtraIsZeroOrLess() {
        #expect(
            Levels.spellIntruders(
                answer: ["A"], form: Levels.plainForm, extra: 0, mixed: true, .seeded(1)
            ).isEmpty
        )
        #expect(
            Levels.spellIntruders(
                answer: ["A"], form: Levels.plainForm, extra: -1, mixed: false, .seeded(1)
            ).isEmpty
        )
    }

    @Test func isReplayableUnderAFixedSeed() {
        let word = Levels.spellSyllablePool(4)[0]
        for mode in SpellSyllableMode.allCases {
            for mixed in [false, true] {
                let ids = TileIDAllocator(next: 0)
                let a = Levels.buildSpellSyllableRound(
                    word: word, mode: mode, distractors: 3, mixed: mixed, .seeded(1234), ids: ids
                )
                let ids2 = TileIDAllocator(next: 0)
                let b = Levels.buildSpellSyllableRound(
                    word: word, mode: mode, distractors: 3, mixed: mixed, .seeded(1234), ids: ids2
                )
                #expect(a == b, "\(mode) mixed=\(mixed)")
            }
        }
    }
}
