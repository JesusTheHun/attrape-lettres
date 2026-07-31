import Testing

@testable import ALCore

/// Port of the `soundLevel / soundPool`, `buildSoundSession`, `buildSoundRound`
/// and "sound prompt lines" blocks of `src/levels.test.ts`.
@Suite struct LevelsSpellSoundTests {

    @Test func clampsOutOfRangeLevelsToTheLadderEnds() {
        #expect(Levels.soundLevel(0) == Levels.soundLevels[0])
        #expect(Levels.soundLevel(999) == Levels.soundLevels[Levels.soundLevels.count - 1])
        #expect(Levels.soundPool(0) == Levels.soundPool(1))
    }

    @Test func givesEveryLevelEnoughTargetsToPickAFullSet() {
        for i in Levels.soundLevels.indices {
            #expect(Levels.soundPool(i + 1).count >= Levels.soundPick)
        }
    }

    // MARK: - buildSoundSession

    private static func key(_ t: SoundTarget) -> String {
        "\(t.sound)|\(t.word ?? "")|\(t.spelling.joined())"
    }

    /// Every level, many runs — the invariants must hold on all pool shapes (L4 has
    /// only 6 distinct sounds across 24 entries, so we count entries, not sounds).
    private static let runs: [[SoundTarget]] = Levels.soundLevels.indices.flatMap { i in
        (0..<60).map { n in Levels.buildSoundSession(level: i + 1, .seeded(UInt64(n))) }
    }

    @Test func isSoundPickDistinctEntriesSoundRepeatsOfThemReplayedOnce() {
        for run in Self.runs {
            #expect(run.count == Levels.soundSessionLength)
            var counts: [String: Int] = [:]
            for t in run { counts[Self.key(t), default: 0] += 1 }
            #expect(counts.count == Levels.soundPick)
            let values = Array(counts.values)
            #expect(values.filter { $0 == 2 }.count == Levels.soundRepeats)
            #expect(values.filter { $0 == 1 }.count == Levels.soundPick - Levels.soundRepeats)
            #expect(values.allSatisfy { $0 <= 2 })
        }
    }

    @Test func neverReplaysTheSameEntryInTwoConsecutiveRounds() {
        for run in Self.runs {
            for i in run.indices where i > 0 {
                #expect(Self.key(run[i - 1]) != Self.key(run[i]))
            }
        }
    }

    @Test func drawsEveryRoundFromTheRequestedLevelsPool() {
        let pool5 = Set(Levels.soundPool(5).map(Self.key))
        for t in Levels.buildSoundSession(level: 5, .seeded(9)) {
            #expect(pool5.contains(Self.key(t)))
        }
    }

    // MARK: - buildSoundRound

    private static let target = SoundTarget(
        sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷"
    )

    @Test func opensOneEmptySlotPerLetterAndATrayOfExactlyTheNeededLetters() {
        let round = Levels.buildSoundRound(target: Self.target, distractors: 0, .seeded(1))
        #expect(round.slots == [nil, nil, nil])
        #expect(round.tray.map(\.letter).sorted() == Self.target.spelling.sorted())
    }

    @Test func addsTheRequestedIntrudersNoneOfWhichAreInTheTarget() {
        for seed in 0..<20 {
            let round = Levels.buildSoundRound(
                target: Self.target, distractors: 3, .seeded(UInt64(seed))
            )
            #expect(round.tray.count == Self.target.spelling.count + 3)
            let need = Set(Self.target.spelling)
            let intruders = round.tray.map(\.letter).filter { !need.contains($0) }
            #expect(intruders.count == 3)
            for l in intruders { #expect(Content.soundLetterBank.contains(l)) }
        }
    }

    @Test func neverAsksForMoreDistractorsThanTheBankCanSupply() {
        // Bank minus a 1-letter target is the worst case; stay within it.
        let round = Levels.buildSoundRound(
            target: SoundTarget(sound: "o", spelling: ["O"]),
            distractors: Content.soundLetterBank.count,
            .seeded(2)
        )
        let letters = round.tray.map(\.letter)
        #expect(Set(letters).count == letters.count)  // no duplicate intruders
        #expect(letters.contains("O"))
    }

    @Test func givesEveryTileAUniqueId() {
        let round = Levels.buildSoundRound(target: Self.target, distractors: 3, .seeded(4))
        let ids = round.tray.map(\.id)
        #expect(Set(ids).count == ids.count)
    }

    // MARK: - prompt lines

    @Test func speaksTheBareSoundWithNoContextWordAndCommeDansWithOne() {
        #expect(Levels.soundPrompt(SoundTarget(sound: "la", spelling: ["L", "A"])) == "la")
        #expect(
            Levels.soundPrompt(
                SoundTarget(sound: "fo", spelling: ["P", "H", "O"], word: "photo")
            ) == "fo, comme dans photo."
        )
    }

    @Test func celebratesWithTheWordWhenPresentTheSoundOtherwise() {
        #expect(Levels.soundSuccess(SoundTarget(sound: "la", spelling: ["L", "A"])) == "Oui ! la.")
        #expect(
            Levels.soundSuccess(
                SoundTarget(sound: "fo", spelling: ["P", "H", "O"], word: "photo")
            ) == "Oui ! photo."
        )
    }

    @Test func isReplayableUnderAFixedSeed() {
        for level in 1...Levels.soundLevelCount {
            #expect(
                Levels.buildSoundSession(level: level, .seeded(1234))
                    == Levels.buildSoundSession(level: level, .seeded(1234))
            )
        }
    }
}
