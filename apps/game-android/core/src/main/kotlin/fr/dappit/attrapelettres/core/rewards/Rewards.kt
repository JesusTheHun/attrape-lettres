package fr.dappit.attrapelettres.core.rewards

import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId

/* -------------------------------------------------------------------------- */
/* Reward math — pure. The economy lives here, and NOWHERE else (invariant 8).  */
/* Port of apps/game-web/src/rewards.ts, value for value.                       */
/* -------------------------------------------------------------------------- */

/**
 * The first clear of an (exercise, level) is the jackpot; repeats decay fast
 * toward a small "keep-playing" trickle so advanced exercises stay the way to
 * earn real points. Curve: 10 → 3 → 2 → 2 → 1 forever.
 */
val REWARD_CURVE = intArrayOf(10, 3, 2, 2)
const val REWARD_FLOOR = 1

/**
 * After a wrong tap, picks are ignored for this long (the shake window). Kills
 * machine-gun tapping and palm-slaps: spamming every tile stops being the
 * fastest way through a round. Feedback still fires on the tap that missed.
 */
const val MISS_COOLDOWN_MS = 800L

/**
 * The stored key for one (exercise, level) pair.
 *
 * A STRING, not a data class, and that is a contract rather than a shortcut:
 * this exact format is a key inside the persisted clear counters, which the web
 * app and the iOS app also write and which crosses the sync wire. Change the
 * separator and a family's history splits in two.
 */
fun ledgerKey(exercise: ExerciseId, level: Int): String = "${exercise.wire}:$level"

/** Points for the *next* clear, given how many times it has already been cleared. */
fun rewardFor(priorClears: Int): Int =
    REWARD_CURVE.getOrElse(priorClears) { REWARD_FLOOR }

/**
 * Points for a finished session — the anti-farming math. A round is "perfect"
 * when it took no wrong tap; a full-perfect run earns exactly `difficulty`
 * bonus points on top of the completion curve, proportionally fewer with
 * misses. Spam-tapping earns zero bonus, so careful play always out-earns
 * farming, and harder exercises out-pay easier ones.
 *
 * EVERY exercise pays the completion curve, training rows included: finishing
 * is the thing being rewarded, and a child who finishes has finished. What
 * difficulty 0 buys is a bonus of exactly zero — on a training row careful play
 * earns precisely what spam earns, so there is nothing to grind for. The
 * gradient still holds: the only way to out-earn the curve is a harder row.
 */
fun sessionReward(
    difficulty: Difficulty,
    priorClears: Int,
    perfectRounds: Int,
    totalRounds: Int,
): Int {
    // `difficulty.weight == 0` needs no branch: it multiplies the bonus to nothing.
    val bonus = if (totalRounds > 0) perfectRounds * difficulty.weight / totalRounds else 0
    return rewardFor(priorClears) + bonus
}

/**
 * What the child will earn next time they clear this (exercise, level) — the
 * guaranteed part only (the accuracy bonus is earned, not promised). Difficulty
 * plays no part: the curve is what every row pays.
 */
fun previewReward(
    ledger: Map<String, Int>,
    exercise: ExerciseId,
    level: Int,
): Int = rewardFor(ledger[ledgerKey(exercise, level)] ?: 0)
