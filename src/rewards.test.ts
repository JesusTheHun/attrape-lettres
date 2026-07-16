import { describe, it, expect } from "vitest";
import {
  ledgerKey,
  rewardFor,
  previewReward,
  sessionReward,
  REWARD_CURVE,
  REWARD_FLOOR,
} from "./rewards";
import type { CompletionLedger } from "./types";

describe("ledgerKey", () => {
  it("joins exercise and level", () => {
    expect(ledgerKey("first-letter", 3)).toBe("first-letter:3");
  });
});

describe("rewardFor", () => {
  it("follows the curve for the first clears", () => {
    expect(REWARD_CURVE.map((_, i) => rewardFor(i))).toEqual([...REWARD_CURVE]);
  });

  it("decays to the floor past the curve", () => {
    expect(rewardFor(REWARD_CURVE.length)).toBe(REWARD_FLOOR);
    expect(rewardFor(999)).toBe(REWARD_FLOOR);
  });
});

describe("previewReward", () => {
  it("returns the first-clear jackpot for an untouched level", () => {
    const ledger: CompletionLedger = {};
    expect(previewReward(ledger, "read-image", 1, 2)).toBe(REWARD_CURVE[0]);
  });

  it("reflects prior clears from the ledger", () => {
    const ledger: CompletionLedger = { [ledgerKey("read-image", 1)]: 2 };
    expect(previewReward(ledger, "read-image", 1, 2)).toBe(REWARD_CURVE[2]);
  });

  it("promises nothing for a training exercise", () => {
    expect(previewReward({}, "first-letter", 1, 0)).toBe(0);
  });
});

describe("sessionReward — the anti-farming math", () => {
  it("training exercises (difficulty 0) pay nothing, even full-perfect", () => {
    expect(sessionReward(0, 0, 10, 10)).toBe(0);
    expect(sessionReward(0, 999, 0, 10)).toBe(0);
  });

  it("a full-perfect run earns exactly `difficulty` bonus points", () => {
    expect(sessionReward(1, 0, 10, 10)).toBe(REWARD_CURVE[0] + 1);
    expect(sessionReward(4, 0, 8, 8)).toBe(REWARD_CURVE[0] + 4);
  });

  it("spam-tapping (zero perfect rounds) earns the bare curve, no bonus", () => {
    expect(sessionReward(4, 0, 0, 10)).toBe(REWARD_CURVE[0]);
    expect(sessionReward(4, 999, 0, 10)).toBe(REWARD_FLOOR);
  });

  it("partial accuracy scales the bonus down, floored", () => {
    // difficulty 2, 5/10 perfect -> floor(5*2/10) = 1 bonus point.
    expect(sessionReward(2, 999, 5, 10)).toBe(REWARD_FLOOR + 1);
    // difficulty 1, 5/10 perfect -> floor(5/10) = 0: half-careful play on an
    // easy exercise is worth no more than spam.
    expect(sessionReward(1, 999, 5, 10)).toBe(REWARD_FLOOR);
  });

  it("careful play on a hard exercise beats farming an easy one", () => {
    const farmEasy = sessionReward(1, 999, 0, 10); // spam a cheap level forever
    const playHard = sessionReward(4, 999, 10, 10); // first-try a mêlées run
    expect(playHard).toBeGreaterThan(farmEasy * 4);
  });

  it("tolerates an empty session without dividing by zero", () => {
    expect(sessionReward(3, 0, 0, 0)).toBe(REWARD_CURVE[0]);
  });
});
