import Testing

@testable import ALCore

/// D11 / invariant 9. These 17 strings are a PERSISTENCE CONTRACT:
/// `Rewards.ledgerKey` builds "\(rawValue):\(level)" and that key lives on disk
/// and on the sync wire. If this test fails, a rename orphaned every existing
/// profile's clear history — fix the code, never the golden array.
@Suite struct ExerciseIDTests {
    static let golden: [String] = [
        "first-letter",
        "find-sound",
        "hear-syllable",
        "pick-vowel",
        "sound-twins",
        "read-image",
        "match-case",
        "match-script",
        "fill-blank",
        "order-syllables",
        "find-intruder",
        "spell-syllable",
        "spell-syllable-plus",
        "spell-two-syllables",
        "spell-syllable-plus-mixed",
        "spell-two-syllables-mixed",
        "spell-sound",
    ]

    @Test func rawValuesAreFrozenAndInDeclarationOrder() {
        #expect(ExerciseId.allCases.map(\.rawValue) == Self.golden)
    }

    @Test func thereAreExactlySeventeen() {
        #expect(ExerciseId.allCases.count == 17)
        #expect(Set(Self.golden).count == 17)  // no duplicate raw value
    }

    @Test func rawValuesRoundTrip() {
        for id in ExerciseId.allCases {
            #expect(ExerciseId(rawValue: id.rawValue) == id)
        }
    }

    @Test func unknownRawValueIsNil() {
        #expect(ExerciseId(rawValue: "first_letter") == nil)
        #expect(ExerciseId(rawValue: "") == nil)
    }
}

@Suite struct DifficultyTests {
    @Test func weightIsTheNumberItself() {
        #expect(Difficulty.d0.weight == 0)
        #expect(Difficulty.d1.weight == 1)
        #expect(Difficulty.d2.weight == 2)
        #expect(Difficulty.d3.weight == 3)
        #expect(Difficulty.d4.weight == 4)
    }

    @Test func theUnionIsExactlyZeroToFour() {
        // TS: `type Difficulty = 0 | 1 | 2 | 3 | 4` — `difficulty: 5` is a
        // compile error there and an unrepresentable value here.
        #expect(Difficulty.allCases.map(\.rawValue) == [0, 1, 2, 3, 4])
        #expect(Difficulty(rawValue: 5) == nil)
        #expect(Difficulty(rawValue: -1) == nil)
    }
}

@Suite struct MoodVerdictTests {
    @Test func moodRawValues() {
        #expect(Mood.allCases.map(\.rawValue) == ["idle", "happy", "cheer"])
    }

    @Test func verdictHasExactlyTwoCasesAndNeitherEndsARun() {
        // Invariant 3: there is no terminal state. A third case would be one.
        #expect(Verdict.allCases.map(\.rawValue) == ["accept", "reject"])
    }
}
