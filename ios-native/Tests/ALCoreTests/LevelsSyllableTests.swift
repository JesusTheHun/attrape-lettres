import Testing

@testable import ALCore

/// Port of the `syllableTier` / `syllablePool` / `buildSyllableRound` blocks of
/// `src/levels.test.ts`, plus the shipped-config `repeatSession` block (which
/// exercises exactly the pools the app ships).
@Suite struct LevelsSyllableTests {

    @Test func syllableTierClampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.syllableTier(0) == Levels.syllableTiers[0])
        #expect(Levels.syllableTier(999) == Levels.syllableTiers[Levels.syllableTiers.count - 1])
    }

    @Test func syllableTierMapsLevelNToTierN() {
        #expect(Levels.syllableTier(2) == Levels.syllableTiers[1])
    }

    @Test func syllablePoolKeepsOnlyWordsWithinTheTiersWindow() {
        let tier = Levels.syllableTiers[0]
        let pool = Levels.syllablePool(tier)
        #expect(!pool.isEmpty)
        #expect(
            pool.allSatisfy {
                $0.syllables.count >= tier.minSyllables && $0.syllables.count <= tier.maxSyllables
            }
        )
    }

    // MARK: - buildSyllableRound

    private static let word = SyllableWord(word: "CHATON", syllables: ["CHA", "TON"], emoji: "🐱")

    @Test func fillBlankHidesExactlyOneSlotAndOffersTargetPlusOneDistractor() {
        for seed in 0..<40 {
            let round = Levels.buildSyllableRound(
                word: Self.word, mode: .fillBlank, .seeded(UInt64(seed))
            )
            #expect(round.slots.filter { $0 == nil }.count == 1, "seed \(seed)")
            // Every non-hidden slot is locked; the hidden one is not.
            for (i, s) in round.slots.enumerated() {
                #expect(round.locked[i] == (s != nil), "seed \(seed)")
            }
            #expect(round.tray.count == 2, "seed \(seed)")
            let missing = Self.word.syllables[round.slots.firstIndex(where: { $0 == nil })!]
            #expect(round.tray.map(\.syllable).contains(missing), "seed \(seed)")
            // The distractor is never one of the word's own syllables.
            let other = round.tray.map(\.syllable).filter { $0 != missing }
            #expect(other.count == 1)
            #expect(!Self.word.syllables.contains(other[0]), "seed \(seed)")
        }
    }

    @Test func orderHasEmptySlotsAndATrayThatPermutesAllSyllables() {
        for seed in 0..<20 {
            let round = Levels.buildSyllableRound(
                word: Self.word, mode: .order, .seeded(UInt64(seed))
            )
            #expect(round.slots == [nil, nil])
            #expect(round.locked == [false, false])
            #expect(round.tray.map(\.syllable).sorted() == Self.word.syllables.sorted())
        }
    }

    @Test func orderDistractorTrayHasAllSyllablesPlusOneExtra() {
        for seed in 0..<20 {
            let round = Levels.buildSyllableRound(
                word: Self.word, mode: .orderDistractor, .seeded(UInt64(seed))
            )
            #expect(round.tray.count == Self.word.syllables.count + 1)
            for s in Self.word.syllables {
                #expect(round.tray.map(\.syllable).contains(s), "seed \(seed)")
            }
        }
    }

    @Test func givesEveryTileAUniqueId() {
        let round = Levels.buildSyllableRound(
            word: Self.word, mode: .orderDistractor, .seeded(7)
        )
        let ids = round.tray.map(\.id)
        #expect(Set(ids).count == ids.count)
    }

    @Test func tileIdsComeFromTheInjectedAllocator() {
        let ids = TileIDAllocator(next: 100)
        let round = Levels.buildSyllableRound(
            word: Self.word, mode: .order, .seeded(3), ids: ids
        )
        #expect(round.tray.map(\.id) == [100, 101])
    }

    // MARK: - pickDistractorSyllable

    @Test func distractorIsNeverAnExcludedSyllableAndFallsBackToTheBanksFirstEntry() {
        for seed in 0..<50 {
            let picked = Levels.pickDistractorSyllable(
                exclude: Set(Self.word.syllables), .seeded(UInt64(seed))
            )
            #expect(!Self.word.syllables.contains(picked))
            #expect(Content.syllableBank.contains(picked))
        }
        // Excluding the whole bank leaves nothing, and the TS falls through to
        // `SYLLABLE_BANK[0]` — which is only stable because the bank is ordered.
        #expect(
            Levels.pickDistractorSyllable(exclude: Set(Content.syllableBank), .seeded(1))
                == Content.syllableBank[0]
        )
    }

    // MARK: - repeatSession over the exact shipped configs

    private struct Config {
        var count: Int
        var pick: Int
        var repeats: Int
    }

    private static let configs: [Config] =
        Levels.firstLetterLevels.enumerated().map { i, l in
            Config(count: Levels.firstLetterPool(i + 1).count, pick: l.pick, repeats: l.repeats)
        }
        + Levels.syllableTiers.map { t in
            Config(count: Levels.syllablePool(t).count, pick: t.pick, repeats: t.repeats)
        }

    @Test func everyShippedPoolIsBigEnoughForAFullPick() {
        for c in Self.configs { #expect(c.count >= c.pick) }
    }

    @Test func picksDistinctItemsReplaysRepeatsOfThemNeverBackToBack() {
        for c in Self.configs {
            for seed in 0..<40 {
                let run = repeatSessionIndices(
                    count: c.count, pick: c.pick, repeats: c.repeats, .seeded(UInt64(seed))
                )
                #expect(run.count == c.pick + c.repeats)
                var counts: [Int: Int] = [:]
                for x in run { counts[x, default: 0] += 1 }
                #expect(counts.count == c.pick)
                #expect(counts.values.filter { $0 == 2 }.count == c.repeats)
                #expect(counts.values.allSatisfy { $0 <= 2 })
                for i in run.indices where i > 0 {
                    #expect(run[i - 1] != run[i], "seed \(seed)")
                }
            }
        }
    }

    @Test func clampsPickAndRepeatsToAShortPoolAndStaysCollisionFree() {
        for seed in 0..<40 {
            let run = repeatSession(["a", "b", "c"], pick: 8, repeats: 4, .seeded(UInt64(seed)))
            #expect(run.count == 6)  // pick→3, repeats→3
            for i in run.indices where i > 0 {
                #expect(run[i - 1] != run[i], "seed \(seed)")
            }
        }
    }

    @Test func returnsAPlainShuffleWhenRepeatsIsZero() {
        let run = repeatSession(["a", "b", "c", "d"], pick: 3, repeats: 0, .seeded(5))
        #expect(run.count == 3)
        #expect(Set(run).count == 3)
    }
}
