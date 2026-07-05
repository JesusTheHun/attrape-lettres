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
  LETTER_MATCH_LEVELS,
  LETTER_MATCH_PROMPTS,
  letterMatchPool,
  letterMatchPrompt,
  buildLetterMatchSession,
  SPELL_SYLLABLE_LEVELS,
  spellSyllableLevel,
  spellSyllablePool,
  buildSpellSyllableRound,
  buildSpellSyllableSession,
} from "./levels";
import { LETTER_WORDS, LETTER_MATCH_ALPHABET, SOUND_LETTER_BANK } from "./content";
import type { LetterMatchKind, SpellSyllableMode } from "./types";
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

describe("spellSyllablePool", () => {
  it("clamps out-of-range levels and resolves to real ≥3-syllable words", () => {
    expect(spellSyllablePool(0)).toEqual(spellSyllablePool(1));
    expect(spellSyllablePool(999)).toEqual(spellSyllablePool(SPELL_SYLLABLE_LEVELS.length));
    SPELL_SYLLABLE_LEVELS.forEach((_, i) => {
      const pool = spellSyllablePool(i + 1);
      expect(pool.length).toBeGreaterThan(0);
      // ≥3 syllables so the two-syllable sibling always leaves a written anchor.
      pool.forEach((w) => expect(w.syllables.length).toBeGreaterThanOrEqual(3));
    });
  });

  it("keeps level 1 tiny (≤5 words) and gives every level enough to pick a full run", () => {
    expect(spellSyllablePool(1).length).toBeLessThanOrEqual(5);
    SPELL_SYLLABLE_LEVELS.forEach((cfg, i) =>
      expect(spellSyllablePool(i + 1).length).toBeGreaterThanOrEqual(cfg.pick)
    );
  });
});

describe("buildSpellSyllableRound", () => {
  const MODES: SpellSyllableMode[] = ["letters-exact", "letters-extra", "letters-two"];
  // Every mode × level, many runs — the invariants must hold on every pool word.
  const runs = MODES.flatMap((mode) =>
    SPELL_SYLLABLE_LEVELS.flatMap((_, i) => {
      const cfg = spellSyllableLevel(i + 1);
      return spellSyllablePool(i + 1).flatMap((word) =>
        Array.from({ length: 12 }, () => ({
          mode,
          word,
          round: buildSpellSyllableRound(word, mode, cfg.distractors),
          distractors: cfg.distractors,
        }))
      );
    })
  );

  it("spells the whole word cell-by-cell, in reading order", () => {
    runs.forEach(({ word, round }) => {
      expect(round.cells.map((c) => c.letter).join("")).toBe(word.syllables.join(""));
    });
  });

  it("hides WHOLE syllables and always leaves a written anchor", () => {
    runs.forEach(({ mode, word, round }) => {
      let ci = 0;
      const hidden = word.syllables.filter((s) => {
        const len = [...s].length;
        const group = round.cells.slice(ci, ci + len);
        ci += len;
        const allFill = group.every((c) => c.fill);
        const noneFill = group.every((c) => !c.fill);
        expect(allFill || noneFill).toBe(true); // never a half-hidden syllable
        return allFill;
      });
      expect(hidden.length).toBe(mode === "letters-two" ? 2 : 1);
      expect(round.cells.some((c) => !c.fill)).toBe(true); // ≥1 anchor letter shown
    });
  });

  it("answer is the gap letters in order, numbered 0..n-1", () => {
    runs.forEach(({ round }) => {
      const fills = round.cells.filter((c) => c.fill);
      expect(fills.map((c) => c.slotIndex)).toEqual(fills.map((_, i) => i));
      expect(round.answer).toEqual(fills.map((c) => c.letter));
      round.cells.filter((c) => !c.fill).forEach((c) => expect(c.slotIndex).toBe(-1));
    });
  });

  it("letters-exact: tray is exactly the gap letters, no intruders", () => {
    runs
      .filter((r) => r.mode === "letters-exact")
      .forEach(({ round }) => {
        expect(round.tray.map((t) => t.letter).sort()).toEqual([...round.answer].sort());
      });
  });

  it("letters-extra / -two: adds exactly `distractors` intruder letters, none in the gap", () => {
    runs
      .filter((r) => r.mode !== "letters-exact")
      .forEach(({ round, distractors }) => {
        expect(round.tray).toHaveLength(round.answer.length + distractors);
        const need = new Set(round.answer);
        const intruders = round.tray.map((t) => t.letter).filter((l) => !need.has(l));
        expect(intruders).toHaveLength(distractors);
        intruders.forEach((l) => expect(SOUND_LETTER_BANK).toContain(l));
      });
  });

  it("gives every tray tile a unique id", () => {
    runs.forEach(({ round }) => {
      const ids = round.tray.map((t) => t.id);
      expect(new Set(ids).size).toBe(ids.length);
    });
  });
});

describe("buildSpellSyllableSession", () => {
  it("seeds pick + repeats rounds, spaced so no word repeats back-to-back", () => {
    SPELL_SYLLABLE_LEVELS.forEach((cfg, i) => {
      for (let n = 0; n < 40; n++) {
        const run = buildSpellSyllableSession(i + 1);
        expect(run).toHaveLength(cfg.pick + cfg.repeats);
        run.forEach((w, k) => {
          if (k > 0) expect(run[k - 1].word).not.toBe(w.word);
        });
      }
    });
  });
});

describe("letterMatchPool", () => {
  it("restricts to the level's catalog and returns the full alphabet for the null level", () => {
    const l1 = new Set(LETTER_MATCH_LEVELS[0].letters!);
    expect(letterMatchPool(1).every((l) => l1.has(l))).toBe(true);
    expect(letterMatchPool(LETTER_MATCH_LEVELS.length)).toEqual(LETTER_MATCH_ALPHABET);
  });

  it("gives every level a pool big enough for its pick", () => {
    LETTER_MATCH_LEVELS.forEach((cfg, i) =>
      expect(letterMatchPool(i + 1).length).toBeGreaterThanOrEqual(cfg.pick)
    );
  });
});

describe("buildLetterMatchSession", () => {
  const KINDS: LetterMatchKind[] = ["case", "script"];
  // Every kind × level, many runs — the invariants must hold on all pool shapes.
  const runs = KINDS.flatMap((kind) =>
    LETTER_MATCH_LEVELS.flatMap((_, i) =>
      Array.from({ length: 30 }, () => ({ kind, level: i + 1, session: buildLetterMatchSession(kind, i + 1) }))
    )
  );

  it("is pick + repeats rounds, spaced so no letter repeats back-to-back", () => {
    runs.forEach(({ level, session }) => {
      const cfg = LETTER_MATCH_LEVELS[level - 1];
      expect(session).toHaveLength(cfg.pick + cfg.repeats);
      session.forEach((r, i) => {
        if (i > 0) expect(session[i - 1].prompt.base).not.toBe(r.prompt.base);
      });
    });
  });

  it("offers exactly one correct counterpart among distinct-letter tiles", () => {
    runs.forEach(({ level, session }) => {
      const cfg = LETTER_MATCH_LEVELS[level - 1];
      session.forEach((r) => {
        expect(r.choices).toHaveLength(cfg.distractors + 1);
        const bases = r.choices.map((c) => c.base);
        expect(new Set(bases).size).toBe(bases.length); // distinct letters
        expect(bases.filter((b) => b === r.prompt.base)).toHaveLength(1);
      });
    });
  });

  it("case: both plain print, tiles flip the prompt's case (glyph, not just name)", () => {
    runs
      .filter((r) => r.kind === "case")
      .forEach(({ session }) =>
        session.forEach((r) => {
          expect(r.prompt.script).toBe("print");
          const answer = r.choices.find((c) => c.base === r.prompt.base)!;
          expect(answer.glyph).not.toBe(r.prompt.glyph); // A ⇄ a, never A ⇄ A
          const promptUpper = r.prompt.glyph === r.prompt.glyph.toUpperCase();
          r.choices.forEach((c) => {
            expect(c.script).toBe("print");
            expect(c.glyph === c.glyph.toUpperCase()).toBe(!promptUpper);
          });
        })
      );
  });

  it("script: one shared case, tiles flip print ⇄ cursive", () => {
    runs
      .filter((r) => r.kind === "script")
      .forEach(({ session }) =>
        session.forEach((r) => {
          const otherScript = r.prompt.script === "cursive" ? "print" : "cursive";
          const promptUpper = r.prompt.glyph === r.prompt.glyph.toUpperCase();
          r.choices.forEach((c) => {
            expect(c.script).toBe(otherScript);
            expect(c.glyph === c.glyph.toUpperCase()).toBe(promptUpper); // same case as prompt
          });
        })
      );
  });
});

describe("letterMatchPrompt", () => {
  it("reads the direction off the prompt→answer transform", () => {
    const P = (base: string, glyph: string, script: "print" | "cursive") => ({ base, glyph, script });
    expect(letterMatchPrompt(P("A", "A", "print"), P("A", "a", "print"))).toBe(LETTER_MATCH_PROMPTS.toLower);
    expect(letterMatchPrompt(P("A", "a", "print"), P("A", "A", "print"))).toBe(LETTER_MATCH_PROMPTS.toUpper);
    expect(letterMatchPrompt(P("A", "A", "print"), P("A", "A", "cursive"))).toBe(LETTER_MATCH_PROMPTS.toCursive);
    expect(letterMatchPrompt(P("A", "a", "cursive"), P("A", "a", "print"))).toBe(LETTER_MATCH_PROMPTS.toPrint);
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
