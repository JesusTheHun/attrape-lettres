import type { CompletionLedger, Difficulty, ExerciseId } from "./types";

/* -------------------------------------------------------------------------- */
/* Reward math — pure. The economy lives here (parallel to levels.ts logic).  */
/* -------------------------------------------------------------------------- */

/**
 * The first clear of a (exercise, level) is the jackpot; repeats decay fast
 * toward a small "keep-playing" trickle so advanced exercises stay the way to
 * earn real points. Curve: 10 → 3 → 2 → 2 → 1 forever.
 */
export const REWARD_CURVE = [10, 3, 2, 2] as const;
export const REWARD_FLOOR = 1;

export function ledgerKey(exercise: ExerciseId, level: number): string {
  return `${exercise}:${level}`;
}

/** Points for the *next* clear, given how many times it's already been cleared. */
export function rewardFor(priorClears: number): number {
  return REWARD_CURVE[priorClears] ?? REWARD_FLOOR;
}

/**
 * After a wrong tap, picks are ignored for this long (the shake window). Kills
 * machine-gun tapping and palm-slaps: spamming every tile stops being the
 * fastest way through a round. Feedback still fires on the tap that missed.
 */
export const MISS_COOLDOWN_MS = 800;

/**
 * Points for a finished session — the anti-farming math. A round is "perfect"
 * when it took no wrong tap; a full-perfect run earns exactly `difficulty`
 * bonus points on top of the completion curve, proportionally fewer with
 * misses. Spam-tapping earns zero bonus, so careful play always out-earns
 * farming, and harder exercises out-pay easier ones. Difficulty 0 = training
 * exercise: pays nothing, ever — the cheer is the reward.
 */
export function sessionReward(
  difficulty: Difficulty,
  priorClears: number,
  perfectRounds: number,
  totalRounds: number
): number {
  if (difficulty === 0) return 0;
  const bonus = totalRounds > 0 ? Math.floor((perfectRounds * difficulty) / totalRounds) : 0;
  return rewardFor(priorClears) + bonus;
}

/** What the child will earn next time they clear this (exercise, level) —
 *  the guaranteed part only (the accuracy bonus is earned, not promised). */
export function previewReward(
  ledger: CompletionLedger,
  exercise: ExerciseId,
  level: number,
  difficulty: Difficulty
): number {
  if (difficulty === 0) return 0;
  return rewardFor(ledger[ledgerKey(exercise, level)] ?? 0);
}
