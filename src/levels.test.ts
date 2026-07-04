import { describe, it, expect } from "vitest";
import {
  FIRST_LETTER_LEVELS,
  SYLLABLE_TIERS,
  SOUND_LEVELS,
  firstLetterPool,
  syllableTier,
  syllablePool,
  buildSyllableRound,
  repeatSession,
  soundLevel,
  soundPool,
  buildSoundRound,
  buildSoundSession,
  soundPrompt,
  soundSuccess,
  SOUND_PICK,
  SOUND_REPEATS,
  SOUND_SESSION_LENGTH,
} from "./levels";
import { LETTER_WORDS, SOUND_LETTER_BANK } from "./content";
import type { SoundTarget, SyllableWord } from "./types";

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

describe("soundLevel / soundPool", () => {
  it("clamps out-of-range levels to the ladder ends", () => {
    expect(soundLevel(0)).toBe(SOUND_LEVELS[0]);
    expect(soundLevel(999)).toBe(SOUND_LEVELS[SOUND_LEVELS.length - 1]);
    expect(soundPool(0)).toBe(soundPool(1));
  });

  it("gives every level enough targets to pick a full set", () => {
    SOUND_LEVELS.forEach((_, i) =>
      expect(soundPool(i + 1).length).toBeGreaterThanOrEqual(SOUND_PICK)
    );
  });
});

describe("repeatSession", () => {
  // Exactly the configs the app ships: every first-letter level + syllable tier.
  const CONFIGS: { pool: readonly unknown[]; pick: number; repeats: number }[] = [
    ...FIRST_LETTER_LEVELS.map((l, i) => ({
      pool: firstLetterPool(i + 1),
      pick: l.pick,
      repeats: l.repeats,
    })),
    ...SYLLABLE_TIERS.map((t) => ({ pool: syllablePool(t), pick: t.pick, repeats: t.repeats })),
  ];

  it("every shipped pool is big enough for a full pick", () => {
    CONFIGS.forEach(({ pool, pick }) => expect(pool.length).toBeGreaterThanOrEqual(pick));
  });

  it("picks distinct items, replays `repeats` of them, never back-to-back", () => {
    CONFIGS.forEach(({ pool, pick, repeats }) => {
      for (let n = 0; n < 40; n++) {
        const run = repeatSession(pool, pick, repeats);
        expect(run).toHaveLength(pick + repeats);
        const counts = new Map<unknown, number>();
        run.forEach((x) => counts.set(x, (counts.get(x) ?? 0) + 1));
        expect(counts.size).toBe(pick);
        expect([...counts.values()].filter((c) => c === 2)).toHaveLength(repeats);
        expect([...counts.values()].every((c) => c <= 2)).toBe(true);
        run.forEach((x, i) => {
          if (i > 0) expect(run[i - 1]).not.toBe(x);
        });
      }
    });
  });

  it("clamps pick/repeats to a short pool and stays collision-free", () => {
    const run = repeatSession(["a", "b", "c"], 8, 4); // pick→3, repeats→3
    expect(run).toHaveLength(6);
    run.forEach((x, i) => {
      if (i > 0) expect(run[i - 1]).not.toBe(x);
    });
  });

  it("returns a plain shuffle when repeats is 0", () => {
    const run = repeatSession(["a", "b", "c", "d"], 3, 0);
    expect(run).toHaveLength(3);
    expect(new Set(run).size).toBe(3);
  });
});

describe("buildSoundSession", () => {
  const key = (t: SoundTarget) => `${t.sound}|${t.word ?? ""}|${t.spelling.join("")}`;
  // Every level, many runs — the invariants must hold on all pool shapes (L4 has
  // only 6 distinct sounds across 24 entries, so we count entries, not sounds).
  const runs = SOUND_LEVELS.flatMap((_, i) =>
    Array.from({ length: 60 }, () => buildSoundSession(i + 1))
  );

  it("is SOUND_PICK distinct entries, SOUND_REPEATS of them replayed once", () => {
    runs.forEach((run) => {
      expect(run).toHaveLength(SOUND_SESSION_LENGTH);
      const counts = new Map<string, number>();
      run.forEach((t) => counts.set(key(t), (counts.get(key(t)) ?? 0) + 1));
      expect(counts.size).toBe(SOUND_PICK);
      const values = [...counts.values()];
      expect(values.filter((n) => n === 2)).toHaveLength(SOUND_REPEATS);
      expect(values.filter((n) => n === 1)).toHaveLength(SOUND_PICK - SOUND_REPEATS);
      expect(values.every((n) => n <= 2)).toBe(true);
    });
  });

  it("never replays the same entry in two consecutive rounds", () => {
    runs.forEach((run) =>
      run.forEach((t, i) => {
        if (i > 0) expect(key(run[i - 1])).not.toBe(key(t));
      })
    );
  });

  it("draws every round from the requested level's pool", () => {
    const pool5 = new Set(soundPool(5).map(key));
    buildSoundSession(5).forEach((t) => expect(pool5.has(key(t))).toBe(true));
  });
});

describe("buildSoundRound", () => {
  const target: SoundTarget = { sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷" };

  it("opens one empty slot per letter and a tray of exactly the needed letters when distractors=0", () => {
    const round = buildSoundRound(target, 0);
    expect(round.slots).toEqual([null, null, null]);
    expect(round.tray.map((t) => t.letter).sort()).toEqual([...target.spelling].sort());
  });

  it("adds the requested number of intruder letters, none of which are in the target", () => {
    const round = buildSoundRound(target, 3);
    expect(round.tray).toHaveLength(target.spelling.length + 3);
    const need = new Set(target.spelling);
    const intruders = round.tray.map((t) => t.letter).filter((l) => !need.has(l));
    expect(intruders).toHaveLength(3);
    intruders.forEach((l) => expect(SOUND_LETTER_BANK).toContain(l));
  });

  it("never asks for more distractors than the bank can supply", () => {
    // Bank minus a 1-letter target is the worst case; stay within it.
    const round = buildSoundRound({ sound: "o", spelling: ["O"] }, SOUND_LETTER_BANK.length);
    const letters = round.tray.map((t) => t.letter);
    expect(new Set(letters).size).toBe(letters.length); // no duplicate intruders
    expect(letters).toContain("O");
  });

  it("gives every tile a unique id", () => {
    const round = buildSoundRound(target, 3);
    const ids = round.tray.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe("sound prompt lines", () => {
  it("speaks the bare sound with no context word, and 'comme dans' with one", () => {
    expect(soundPrompt({ sound: "la", spelling: ["L", "A"] })).toBe("la");
    expect(soundPrompt({ sound: "fo", spelling: ["P", "H", "O"], word: "photo" })).toBe(
      "fo, comme dans photo."
    );
  });

  it("celebrates with the word when present, the sound otherwise", () => {
    expect(soundSuccess({ sound: "la", spelling: ["L", "A"] })).toBe("Oui ! la.");
    expect(soundSuccess({ sound: "fo", spelling: ["P", "H", "O"], word: "photo" })).toBe(
      "Oui ! photo."
    );
  });
});
