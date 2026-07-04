import { describe, it, expect } from "vitest";
import {
  ledgerKey,
  rewardFor,
  previewReward,
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
    expect(previewReward(ledger, "fill-blank", 1)).toBe(REWARD_CURVE[0]);
  });

  it("reflects prior clears from the ledger", () => {
    const ledger: CompletionLedger = { [ledgerKey("fill-blank", 1)]: 2 };
    expect(previewReward(ledger, "fill-blank", 1)).toBe(REWARD_CURVE[2]);
  });
});
