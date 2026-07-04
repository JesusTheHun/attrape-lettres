import type { CompletionLedger, ExerciseId } from "./types";

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

/** What the child will earn next time they clear this (exercise, level). */
export function previewReward(
  ledger: CompletionLedger,
  exercise: ExerciseId,
  level: number
): number {
  return rewardFor(ledger[ledgerKey(exercise, level)] ?? 0);
}
