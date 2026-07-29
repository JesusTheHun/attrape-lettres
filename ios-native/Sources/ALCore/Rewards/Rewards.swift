// Port of `src/rewards.ts` — the economy, in full.
//
// INVARIANT 8 LIVES HERE. `Rewards.sessionReward` is the ONLY function in
// ALCore that returns points earned by play. There is no `award(...)` helper
// anywhere else, no round builder returns a number a caller could add to a
// balance, and `Difficulty` is non-optional on `ExerciseMeta` so a new exercise
// cannot compile without placing itself in the economy. `previewReward` is
// display-only (the guaranteed part; the accuracy bonus is earned, not
// promised) and `rewardFor` is a bare curve lookup.
//
// D11 — `ledgerKey` is a PERSISTENCE CONTRACT shared with the storage layer.
// Its format is `"\(exercise.rawValue):\(level)"` and that string is the key of
// `CompletionLedger` / `ClearCounters`, on disk and on the sync wire. Do not
// change the separator and do not "tidy" it into a struct key: every existing
// profile's history is filed under these strings.

/* -------------------------------------------------------------------------- */
/* Reward math — pure. The economy lives here (parallel to levels.ts logic).  */
/* -------------------------------------------------------------------------- */

public enum Rewards {
    /**
     * The first clear of a (exercise, level) is the jackpot; repeats decay fast
     * toward a small "keep-playing" trickle so advanced exercises stay the way to
     * earn real points. Curve: 10 → 3 → 2 → 2 → 1 forever.
     */
    public static let curve: [Int] = [10, 3, 2, 2]
    public static let floor: Int = 1

    public static func ledgerKey(exercise: ExerciseId, level: Int) -> String {
        "\(exercise.rawValue):\(level)"
    }

    /// Points for the *next* clear, given how many times it's already been cleared.
    // NB: the TS is `REWARD_CURVE[priorClears] ?? REWARD_FLOOR` — an out-of-range
    // index (including a negative one) is `undefined` in JS and falls through to
    // the floor. Swift would trap, so the bound check is explicit.
    public static func rewardFor(priorClears: Int) -> Int {
        guard priorClears >= 0, priorClears < curve.count else { return floor }
        return curve[priorClears]
    }

    /**
     * After a wrong tap, picks are ignored for this long (the shake window). Kills
     * machine-gun tapping and palm-slaps: spamming every tile stops being the
     * fastest way through a round. Feedback still fires on the tap that missed.
     */
    // The Int is the source of truth so the number in the spec, the tests and the
    // UI is ONE number; `missCooldown` is the same value for call sites that want
    // a `Duration`. It is a SWALLOW window, never a lock — invariant 3.
    public static let missCooldownMs: Int = 800
    public static let missCooldown: Duration = .milliseconds(missCooldownMs)

    /**
     * Points for a finished session — the anti-farming math. A round is "perfect"
     * when it took no wrong tap; a full-perfect run earns exactly `difficulty`
     * bonus points on top of the completion curve, proportionally fewer with
     * misses. Spam-tapping earns zero bonus, so careful play always out-earns
     * farming, and harder exercises out-pay easier ones. Difficulty 0 = training
     * exercise: pays nothing, ever — the cheer is the reward.
     */
    public static func sessionReward(
        difficulty: Difficulty,
        priorClears: Int,
        perfectRounds: Int,
        totalRounds: Int
    ) -> Int {
        if difficulty == .d0 { return 0 }
        // NB: `Math.floor(a / b)` equals Swift's truncating `Int` division only
        // for non-negative operands. Both are non-negative here by construction
        // (`Difficulty` is 0…4, round counts are counts) — do not add a signed
        // overload, which would silently diverge.
        let bonus = totalRounds > 0 ? (perfectRounds * difficulty.weight) / totalRounds : 0
        return rewardFor(priorClears: priorClears) + bonus
    }

    /// What the child will earn next time they clear this (exercise, level) —
    /// the guaranteed part only (the accuracy bonus is earned, not promised).
    public static func previewReward(
        ledger: CompletionLedger,
        exercise: ExerciseId,
        level: Int,
        difficulty: Difficulty
    ) -> Int {
        if difficulty == .d0 { return 0 }
        return rewardFor(priorClears: ledger[ledgerKey(exercise: exercise, level: level)] ?? 0)
    }
}
