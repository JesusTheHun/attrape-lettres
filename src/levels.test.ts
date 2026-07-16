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
  READ_IMAGE_LEVELS,
  readImageLevel,
  readImagePool,
  buildReadImageRound,
  buildReadImageSession,
  FIND_SOUND_LEVELS,
  findSoundLevel,
  findSoundPool,
  buildFindSoundRound,
  buildFindSoundSession,
  findSoundPrompt,
  findSoundSuccess,
  TWIN_LEVELS,
  twinLevel,
  twinPool,
  buildTwinRound,
  buildTwinSession,
  twinPrompt,
  twinSuccess,
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

describe("readImageLevel", () => {
  it("clamps out-of-range levels to the ladder ends", () => {
    expect(readImageLevel(0)).toBe(READ_IMAGE_LEVELS[0]);
    expect(readImageLevel(999)).toBe(READ_IMAGE_LEVELS[READ_IMAGE_LEVELS.length - 1]);
  });

  it("has a pool big enough to pick a full run + distractors at every level", () => {
    READ_IMAGE_LEVELS.forEach((cfg) => {
      expect(readImagePool().length).toBeGreaterThanOrEqual(cfg.pick);
      // one target + its distractors must all fit, by DISTINCT emoji.
      const distinctEmoji = new Set(readImagePool().map((w) => w.emoji)).size;
      expect(distinctEmoji).toBeGreaterThanOrEqual(cfg.distractors + 1);
    });
  });
});

describe("buildReadImageRound", () => {
  // Every level's distractor count, many runs on many targets.
  const runs = READ_IMAGE_LEVELS.flatMap((cfg) =>
    readImagePool().flatMap((target) =>
      Array.from({ length: 8 }, () => ({ cfg, round: buildReadImageRound(target, cfg.distractors) }))
    )
  );

  it("offers the target plus exactly `distractors` choices", () => {
    runs.forEach(({ cfg, round }) => {
      expect(round.choices).toHaveLength(cfg.distractors + 1);
      expect(round.choices.filter((c) => c.word === round.target.word)).toHaveLength(1);
    });
  });

  it("never shows two tiles with the same picture (distinct emoji)", () => {
    runs.forEach(({ round }) => {
      const emojis = round.choices.map((c) => c.emoji);
      expect(new Set(emojis).size).toBe(emojis.length);
    });
  });
});

describe("buildReadImageSession", () => {
  it("seeds pick + repeats rounds, spaced so no word repeats back-to-back", () => {
    READ_IMAGE_LEVELS.forEach((cfg, i) => {
      for (let n = 0; n < 40; n++) {
        const run = buildReadImageSession(i + 1);
        expect(run).toHaveLength(cfg.pick + cfg.repeats);
        run.forEach((r, k) => {
          if (k > 0) expect(run[k - 1].target.word).not.toBe(r.target.word);
        });
      }
    });
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

describe("buildSpellSyllableRound — écritures mêlées (mixed)", () => {
  // Only the two intruder spellers get a mixed twin; -exact never does.
  const MODES: SpellSyllableMode[] = ["letters-extra", "letters-two"];
  const key = (t: { glyph: string; script: string }) => `${t.glyph}|${t.script}`;
  const isUpper = (g: string) => g === g.toUpperCase();
  // Writing signature = case + script; exactly the three MIXED_FORMS are legal.
  const sig = (t: { glyph: string; script: string }) => `${isUpper(t.glyph)}|${t.script}`;
  const LEGAL = new Set(["true|print", "false|print", "false|cursive"]);

  const runs = MODES.flatMap((mode) =>
    SPELL_SYLLABLE_LEVELS.flatMap((_, i) => {
      const cfg = spellSyllableLevel(i + 1);
      return spellSyllablePool(i + 1).flatMap((word) =>
        Array.from({ length: 12 }, () => ({
          mode,
          word,
          round: buildSpellSyllableRound(word, mode, cfg.distractors, true),
          distractors: cfg.distractors,
        }))
      );
    })
  );

  it("draws the WHOLE word in one legal writing (grande / petite / attachée)", () => {
    runs.forEach(({ round }) => {
      const sigs = new Set(round.cells.map(sig));
      expect(sigs.size).toBe(1);
      expect(LEGAL.has([...sigs][0])).toBe(true);
      // Its glyphs are the correct case for that writing.
      round.cells.forEach((c) =>
        expect(c.glyph).toBe(isUpper(c.glyph) ? c.letter : c.letter.toLowerCase())
      );
    });
  });

  it("stays solvable: every gap face has a matching tray tile (case + script)", () => {
    runs.forEach(({ round }) => {
      const trayLeft = new Map<string, number>();
      round.tray.forEach((t) => trayLeft.set(key(t), (trayLeft.get(key(t)) ?? 0) + 1));
      round.answerFaces.forEach((f) => {
        const k = key(f);
        expect(trayLeft.get(k) ?? 0).toBeGreaterThan(0);
        trayLeft.set(k, (trayLeft.get(k) ?? 0) - 1);
      });
    });
  });

  it("adds exactly `distractors` traps, none a valid answer, all distinct forms", () => {
    runs.forEach(({ round, distractors }) => {
      expect(round.tray).toHaveLength(round.answerFaces.length + distractors);
      // Strip one tray tile per answer face; what's left are the distractors.
      const need = new Map<string, number>();
      round.answerFaces.forEach((f) => need.set(key(f), (need.get(key(f)) ?? 0) + 1));
      const extras = round.tray.filter((t) => {
        const n = need.get(key(t)) ?? 0;
        if (n > 0) {
          need.set(key(t), n - 1);
          return false;
        }
        return true;
      });
      expect(extras).toHaveLength(distractors);
      const answerKeys = new Set(round.answerFaces.map(key));
      const extraKeys = extras.map(key);
      expect(new Set(extraKeys).size).toBe(extraKeys.length); // distinct
      extraKeys.forEach((k) => expect(answerKeys.has(k)).toBe(false)); // never a right tile
    });
  });

  it("always plants at least one same-letter, wrong-writing trap", () => {
    runs.forEach(({ round }) => {
      const answerKeys = new Set(round.answerFaces.map(key));
      const answerBases = new Set(round.answerFaces.map((f) => f.base));
      // A tile whose LETTER is needed but whose exact face isn't a valid answer.
      const trap = round.tray.some((t) => answerBases.has(t.letter) && !answerKeys.has(key(t)));
      expect(trap).toBe(true);
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

describe("findSoundPool / findSoundLevel", () => {
  it("clamps out-of-range levels to the ladder ends", () => {
    expect(findSoundLevel(0)).toBe(FIND_SOUND_LEVELS[0]);
    expect(findSoundLevel(999)).toBe(FIND_SOUND_LEVELS[FIND_SOUND_LEVELS.length - 1]);
    expect(findSoundPool(0)).toBe(findSoundPool(1));
  });

  it("gives every level a pool big enough for its pick and its distractors", () => {
    FIND_SOUND_LEVELS.forEach((cfg, i) => {
      const pool = findSoundPool(i + 1);
      expect(pool.length).toBeGreaterThanOrEqual(cfg.pick);
      expect(pool.length).toBeGreaterThanOrEqual(cfg.distractors + 1);
    });
  });

  it("keeps sounds AND graphies distinct within a level (no homophone tiles)", () => {
    FIND_SOUND_LEVELS.forEach((_, i) => {
      const pool = findSoundPool(i + 1);
      expect(new Set(pool.map((e) => e.sound)).size).toBe(pool.length);
      expect(new Set(pool.map((e) => e.graphy)).size).toBe(pool.length);
    });
  });

  it("resolves every authored trap to a different-sound entry of the SAME pool", () => {
    FIND_SOUND_LEVELS.forEach((_, i) => {
      const pool = findSoundPool(i + 1);
      const byGraphy = new Map(pool.map((e) => [e.graphy, e]));
      pool.forEach((e) =>
        (e.traps ?? []).forEach((t) => {
          const hit = byGraphy.get(t);
          expect(hit).toBeDefined();
          expect(hit!.sound).not.toBe(e.sound);
        })
      );
    });
  });
});

describe("buildFindSoundRound", () => {
  const runs = FIND_SOUND_LEVELS.flatMap((cfg, i) => {
    const pool = findSoundPool(i + 1);
    return pool.flatMap((target) =>
      Array.from({ length: 12 }, () => ({
        cfg,
        target,
        round: buildFindSoundRound(target, pool, cfg.distractors),
      }))
    );
  });

  it("offers the target plus exactly `distractors` choices, all distinct graphies", () => {
    runs.forEach(({ cfg, target, round }) => {
      expect(round.choices).toHaveLength(cfg.distractors + 1);
      expect(round.choices.filter((c) => c.graphy === target.graphy)).toHaveLength(1);
      const graphies = round.choices.map((c) => c.graphy);
      expect(new Set(graphies).size).toBe(graphies.length);
    });
  });

  it("never offers a distractor that SOUNDS like the answer", () => {
    runs.forEach(({ target, round }) => {
      round.choices
        .filter((c) => c.graphy !== target.graphy)
        .forEach((c) => expect(c.sound).not.toBe(target.sound));
    });
  });

  it("prefers the authored traps as distractors when they can fill the round", () => {
    runs
      .filter(({ target, cfg }) => (target.traps?.length ?? 0) >= cfg.distractors)
      .forEach(({ target, round }) => {
        round.choices
          .filter((c) => c.graphy !== target.graphy)
          .forEach((c) => expect(target.traps).toContain(c.graphy));
      });
  });
});

describe("buildFindSoundSession", () => {
  it("seeds pick + repeats rounds, spaced so no sound repeats back-to-back", () => {
    FIND_SOUND_LEVELS.forEach((cfg, i) => {
      for (let n = 0; n < 40; n++) {
        const run = buildFindSoundSession(i + 1);
        expect(run).toHaveLength(cfg.pick + cfg.repeats);
        run.forEach((r, k) => {
          if (k > 0) expect(run[k - 1].target).not.toBe(r.target);
        });
      }
    });
  });
});

describe("twinPool / twinLevel", () => {
  it("clamps out-of-range levels to the ladder ends", () => {
    expect(twinLevel(0)).toBe(TWIN_LEVELS[0]);
    expect(twinLevel(999)).toBe(TWIN_LEVELS[TWIN_LEVELS.length - 1]);
    expect(twinPool(0)).toBe(twinPool(1));
  });

  it("gives every level a pool big enough for its pick", () => {
    TWIN_LEVELS.forEach((cfg, i) =>
      expect(twinPool(i + 1).length).toBeGreaterThanOrEqual(cfg.pick)
    );
  });

  it("every family has ≥2 same-sound writings — the point of the game", () => {
    TWIN_LEVELS.forEach((_, i) =>
      twinPool(i + 1).forEach((f) => expect(f.graphies.length).toBeGreaterThanOrEqual(2))
    );
  });

  it("keeps family sounds distinct and graphy texts unique across a level", () => {
    TWIN_LEVELS.forEach((_, i) => {
      const pool = twinPool(i + 1);
      expect(new Set(pool.map((f) => f.sound)).size).toBe(pool.length);
      const texts = pool.flatMap((f) => f.graphies.map((g) => g.text));
      expect(new Set(texts).size).toBe(texts.length);
    });
  });

  it("always has enough other-family graphies to fill the intruder quota", () => {
    TWIN_LEVELS.forEach((cfg, i) => {
      const pool = twinPool(i + 1);
      pool.forEach((f) => {
        const others = pool
          .filter((o) => o.sound !== f.sound)
          .reduce((n, o) => n + o.graphies.length, 0);
        expect(others).toBeGreaterThanOrEqual(cfg.distractors);
      });
    });
  });
});

describe("buildTwinRound", () => {
  const runs = TWIN_LEVELS.flatMap((cfg, i) => {
    const pool = twinPool(i + 1);
    return pool.flatMap((family) =>
      Array.from({ length: 12 }, () => ({
        cfg,
        family,
        round: buildTwinRound(family, pool, cfg.distractors),
      }))
    );
  });

  it("deals every family graphy (correct) + exactly `distractors` intruders", () => {
    runs.forEach(({ cfg, family, round }) => {
      expect(round.tiles).toHaveLength(family.graphies.length + cfg.distractors);
      family.graphies.forEach((g) => {
        const tile = round.tiles.filter((t) => t.text === g.text);
        expect(tile).toHaveLength(1);
        expect(tile[0].correct).toBe(true);
        expect(tile[0].sound).toBe(family.sound);
      });
      expect(round.tiles.filter((t) => t.correct)).toHaveLength(family.graphies.length);
    });
  });

  it("intruders never spell the family's sound and never duplicate a text", () => {
    runs.forEach(({ family, round }) => {
      round.tiles
        .filter((t) => !t.correct)
        .forEach((t) => expect(t.sound).not.toBe(family.sound));
      const texts = round.tiles.map((t) => t.text);
      expect(new Set(texts).size).toBe(texts.length);
    });
  });

  it("gives every tile a unique id", () => {
    runs.forEach(({ round }) => {
      const ids = round.tiles.map((t) => t.id);
      expect(new Set(ids).size).toBe(ids.length);
    });
  });
});

describe("buildTwinSession", () => {
  it("seeds pick + repeats rounds, spaced so no family repeats back-to-back", () => {
    TWIN_LEVELS.forEach((cfg, i) => {
      for (let n = 0; n < 40; n++) {
        const run = buildTwinSession(i + 1);
        expect(run).toHaveLength(cfg.pick + cfg.repeats);
        run.forEach((r, k) => {
          if (k > 0) expect(run[k - 1].family).not.toBe(r.family);
        });
      }
    });
  });
});

describe("find-sound / sound-twins prompt lines", () => {
  it("anchors the find-sound prompt to its word and celebrates with the word", () => {
    const s = { sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉" };
    expect(findSoundPrompt(s)).toBe("ou, comme dans hibou.");
    expect(findSoundSuccess(s)).toBe("Oui ! hibou.");
  });

  it("asks for ALL twins of a sound and celebrates each graphy's own word", () => {
    expect(twinPrompt({ sound: "ko", graphies: [] })).toBe("Trouve tous les ko !");
    expect(twinSuccess({ text: "CO", word: "coq", emoji: "🐓" })).toBe("Oui ! coq.");
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
