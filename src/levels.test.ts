import { describe, it, expect } from "vitest";
import {
  FIRST_LETTER_LEVELS,
  SYLLABLE_TIERS,
  firstLetterPool,
  syllableTier,
  syllablePool,
  buildSyllableRound,
} from "./levels";
import { LETTER_WORDS } from "./content";
import type { SyllableWord } from "./types";

describe("firstLetterPool", () => {
  it("restricts words to the level's allowed letters", () => {
    const level1 = firstLetterPool(1);
    const allowed = new Set(FIRST_LETTER_LEVELS[0].letters!);
    expect(level1.length).toBeGreaterThan(0);
    expect(level1.every((w) => allowed.has(w.letter))).toBe(true);
  });

  it("returns the full catalog for the null (final) level", () => {
    expect(firstLetterPool(FIRST_LETTER_LEVELS.length)).toEqual(LETTER_WORDS);
  });
});

describe("syllableTier", () => {
  it("clamps out-of-range levels to the ladder ends", () => {
    expect(syllableTier(0)).toBe(SYLLABLE_TIERS[0]);
    expect(syllableTier(999)).toBe(SYLLABLE_TIERS[SYLLABLE_TIERS.length - 1]);
  });

  it("maps level n to tier n", () => {
    expect(syllableTier(2)).toBe(SYLLABLE_TIERS[1]);
  });
});

describe("syllablePool", () => {
  it("keeps only words within the tier's syllable-count window", () => {
    const tier = SYLLABLE_TIERS[0];
    const pool = syllablePool(tier);
    expect(pool.length).toBeGreaterThan(0);
    expect(
      pool.every(
        (w) =>
          w.syllables.length >= tier.minSyllables &&
          w.syllables.length <= tier.maxSyllables
      )
    ).toBe(true);
  });
});

describe("buildSyllableRound", () => {
  const word: SyllableWord = {
    word: "CHATON",
    syllables: ["CHA", "TON"],
    emoji: "🐱",
  };

  it("fill-blank: hides exactly one slot and offers target + one distractor", () => {
    const round = buildSyllableRound(word, "fill-blank");
    const hidden = round.slots.filter((s) => s === null);
    expect(hidden).toHaveLength(1);
    // Every non-hidden slot is locked; the hidden one is not.
    round.slots.forEach((s, i) => expect(round.locked[i]).toBe(s !== null));
    expect(round.tray).toHaveLength(2);
    const missing = word.syllables[round.slots.findIndex((s) => s === null)];
    const trayVals = round.tray.map((t) => t.syllable);
    expect(trayVals).toContain(missing);
  });

  it("order: empty slots, tray is a permutation of all syllables", () => {
    const round = buildSyllableRound(word, "order");
    expect(round.slots).toEqual([null, null]);
    expect(round.locked).toEqual([false, false]);
    expect(round.tray.map((t) => t.syllable).sort()).toEqual(
      [...word.syllables].sort()
    );
  });

  it("order-distractor: tray has all syllables plus one extra", () => {
    const round = buildSyllableRound(word, "order-distractor");
    expect(round.tray).toHaveLength(word.syllables.length + 1);
    word.syllables.forEach((s) =>
      expect(round.tray.map((t) => t.syllable)).toContain(s)
    );
  });

  it("gives every tile a unique id", () => {
    const round = buildSyllableRound(word, "order-distractor");
    const ids = round.tray.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
