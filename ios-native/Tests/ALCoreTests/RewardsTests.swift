import Testing

@testable import ALCore

/// 1:1 port of `src/rewards.test.ts`, plus the invariant-8 properties the port
/// is asked to prove (careful full-perfect vs spam, difficulty 0 pays nothing).
@Suite struct RewardsTests {

    // MARK: - ledgerKey (a persistence contract — D11)

    @Test func ledgerKeyJoinsExerciseAndLevel() {
        #expect(Rewards.ledgerKey(exercise: .firstLetter, level: 3) == "first-letter:3")
    }

    @Test func ledgerKeyUsesTheFrozenRawValueForEveryExercise() {
        for id in ExerciseId.allCases {
            #expect(Rewards.ledgerKey(exercise: id, level: 1) == "\(id.rawValue):1")
        }
    }

    // MARK: - rewardFor

    @Test func rewardForFollowsTheCurveForTheFirstClears() {
        #expect(Rewards.curve.indices.map { Rewards.rewardFor(priorClears: $0) } == Rewards.curve)
    }

    @Test func rewardForDecaysToTheFloorPastTheCurve() {
        #expect(Rewards.rewardFor(priorClears: Rewards.curve.count) == Rewards.floor)
        #expect(Rewards.rewardFor(priorClears: 999) == Rewards.floor)
    }

    // MARK: - previewReward

    @Test func previewReturnsTheFirstClearJackpotForAnUntouchedLevel() {
        let ledger: CompletionLedger = [:]
        #expect(
            Rewards.previewReward(ledger: ledger, exercise: .readImage, level: 1, difficulty: .d2)
                == Rewards.curve[0]
        )
    }

    @Test func previewReflectsPriorClearsFromTheLedger() {
        let ledger: CompletionLedger = [Rewards.ledgerKey(exercise: .readImage, level: 1): 2]
        #expect(
            Rewards.previewReward(ledger: ledger, exercise: .readImage, level: 1, difficulty: .d2)
                == Rewards.curve[2]
        )
    }

    @Test func previewPromisesNothingForATrainingExercise() {
        #expect(
            Rewards.previewReward(ledger: [:], exercise: .firstLetter, level: 1, difficulty: .d0)
                == 0
        )
    }

    // MARK: - sessionReward — the anti-farming math

    @Test func trainingExercisesPayNothingEvenFullPerfect() {
        #expect(
            Rewards.sessionReward(difficulty: .d0, priorClears: 0, perfectRounds: 10, totalRounds: 10) == 0
        )
        #expect(
            Rewards.sessionReward(difficulty: .d0, priorClears: 999, perfectRounds: 0, totalRounds: 10) == 0
        )
    }

    /// Difficulty 0 pays nothing EVER — no clear count, no accuracy, no round
    /// count can make it pay.
    @Test func difficultyZeroPaysNothingOverTheWholeInputSpace() {
        for priorClears in 0...6 {
            for total in 0...8 {
                for perfect in 0...total {
                    #expect(
                        Rewards.sessionReward(
                            difficulty: .d0,
                            priorClears: priorClears,
                            perfectRounds: perfect,
                            totalRounds: total
                        ) == 0
                    )
                }
            }
        }
    }

    @Test func aFullPerfectRunEarnsExactlyDifficultyBonusPoints() {
        #expect(
            Rewards.sessionReward(difficulty: .d1, priorClears: 0, perfectRounds: 10, totalRounds: 10)
                == Rewards.curve[0] + 1
        )
        #expect(
            Rewards.sessionReward(difficulty: .d4, priorClears: 0, perfectRounds: 8, totalRounds: 8)
                == Rewards.curve[0] + 4
        )
    }

    /// The careful-vs-spam pair, on the same (exercise, level), at every weight.
    @Test func carefulEarnsCurvePlusDifficultyWhileSpamEarnsTheBareCurve() {
        for d in Difficulty.allCases where d != .d0 {
            for priorClears in 0...5 {
                let curve = Rewards.rewardFor(priorClears: priorClears)
                let careful = Rewards.sessionReward(
                    difficulty: d, priorClears: priorClears, perfectRounds: 12, totalRounds: 12
                )
                let spam = Rewards.sessionReward(
                    difficulty: d, priorClears: priorClears, perfectRounds: 0, totalRounds: 12
                )
                #expect(careful == curve + d.weight)
                #expect(spam == curve)
                #expect(careful > spam)
            }
        }
    }

    @Test func spamTappingEarnsTheBareCurveNoBonus() {
        #expect(
            Rewards.sessionReward(difficulty: .d4, priorClears: 0, perfectRounds: 0, totalRounds: 10)
                == Rewards.curve[0]
        )
        #expect(
            Rewards.sessionReward(difficulty: .d4, priorClears: 999, perfectRounds: 0, totalRounds: 10)
                == Rewards.floor
        )
    }

    @Test func partialAccuracyScalesTheBonusDownFloored() {
        // difficulty 2, 5/10 perfect -> floor(5*2/10) = 1 bonus point.
        #expect(
            Rewards.sessionReward(difficulty: .d2, priorClears: 999, perfectRounds: 5, totalRounds: 10)
                == Rewards.floor + 1
        )
        // difficulty 1, 5/10 perfect -> floor(5/10) = 0: half-careful play on an
        // easy exercise is worth no more than spam.
        #expect(
            Rewards.sessionReward(difficulty: .d1, priorClears: 999, perfectRounds: 5, totalRounds: 10)
                == Rewards.floor
        )
    }

    @Test func carefulPlayOnAHardExerciseBeatsFarmingAnEasyOne() {
        let farmEasy = Rewards.sessionReward(
            difficulty: .d1, priorClears: 999, perfectRounds: 0, totalRounds: 10
        )  // spam a cheap level forever
        let playHard = Rewards.sessionReward(
            difficulty: .d4, priorClears: 999, perfectRounds: 10, totalRounds: 10
        )  // first-try a mêlées run
        #expect(playHard > farmEasy * 4)
    }

    @Test func toleratesAnEmptySessionWithoutDividingByZero() {
        #expect(
            Rewards.sessionReward(difficulty: .d3, priorClears: 0, perfectRounds: 0, totalRounds: 0)
                == Rewards.curve[0]
        )
    }

    // MARK: - the cooldown is one number

    @Test func missCooldownIsOneNumberInTwoShapes() {
        #expect(Rewards.missCooldownMs == 800)
        #expect(Rewards.missCooldown == .milliseconds(800))
    }
}
