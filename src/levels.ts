import {
  LETTER_WORDS,
  SYLLABLE_WORDS,
  SYLLABLE_BANK,
  SOUND_TARGETS,
  SOUND_LETTER_BANK,
} from "./content";
import type {
  ExerciseMeta,
  FirstLetterLevel,
  LetterWord,
  SoundLevel,
  SoundTarget,
  SyllableMode,
  SyllableTier,
  SyllableWord,
} from "./types";

/* -------------------------------------------------------------------------- */
/* First-letter — 5 explicit levels                                           */
/* The catalog drives everything: more letters ⇒ a longer word list. Level 1  */
/* stays tiny on purpose so the same handful of words recur and stick.        */
/* -------------------------------------------------------------------------- */

export const FIRST_LETTER_LEVELS: FirstLetterLevel[] = [
  { letters: ["A", "B", "C", "M", "P"] },
  { letters: ["A", "B", "C", "D", "M", "P", "R", "S", "T"] },
  { letters: ["A", "B", "C", "D", "F", "L", "M", "N", "P", "R", "S", "T"] },
  {
    letters: [
      "A", "B", "C", "D", "F", "G", "H", "L", "M",
      "N", "O", "P", "R", "S", "T", "V",
    ],
  },
  { letters: null }, // full catalog
];

export const FIRST_LETTER_SESSION = 6;

export interface FirstLetterRound {
  target: LetterWord;
  choices: string[]; // letters, shuffled, always includes target.letter
}

export function firstLetterPool(level: number): LetterWord[] {
  const cfg = FIRST_LETTER_LEVELS[level - 1];
  if (!cfg.letters) return LETTER_WORDS;
  const allowed = new Set(cfg.letters);
  return LETTER_WORDS.filter((w) => allowed.has(w.letter));
}

/* -------------------------------------------------------------------------- */
/* Build-syllables — 12 derived levels                                        */
/* phase cycles every 3; tier (difficulty) rises every 3.                     */
/* -------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------- */
/* Build-syllables — one 4-level difficulty ladder, shared by all 3 modes.     */
/* Level == tier: no phase math, difficulty is the only axis.                  */
/* -------------------------------------------------------------------------- */

export const SYLLABLE_TIERS: SyllableTier[] = [
  { minSyllables: 2, maxSyllables: 2, poolSize: 4 },
  { minSyllables: 2, maxSyllables: 3, poolSize: 6 },
  { minSyllables: 3, maxSyllables: 3, poolSize: 8 },
  { minSyllables: 3, maxSyllables: 4, poolSize: 10 },
];

export const SYLLABLE_LEVEL_COUNT = SYLLABLE_TIERS.length;

export function syllableTier(level: number): SyllableTier {
  return SYLLABLE_TIERS[Math.min(Math.max(level, 1), SYLLABLE_TIERS.length) - 1];
}

export function syllablePool(tier: SyllableTier): SyllableWord[] {
  return SYLLABLE_WORDS.filter(
    (w) => w.syllables.length >= tier.minSyllables && w.syllables.length <= tier.maxSyllables
  );
}

export interface SyllableTile {
  id: number;
  syllable: string;
}

export interface SyllableRound {
  word: SyllableWord;
  /** Target order; slots are filled against this. */
  slots: (string | null)[];
  /** Which slots are pre-revealed and locked (fill-blank). */
  locked: boolean[];
  tray: SyllableTile[];
}

/* -------------------------------------------------------------------------- */
/* Spell-the-sound — 5 explicit levels                                        */
/* Each level is a pool (SOUND_TARGETS) + how many intruder letters to add.    */
/* Difficulty is authored in content; only the distractor count lives here.    */
/* -------------------------------------------------------------------------- */

export const SOUND_LEVELS: SoundLevel[] = [
  { distractors: 0 },
  { distractors: 2 },
  { distractors: 2 },
  { distractors: 3 },
  { distractors: 3 },
];

export const SOUND_LEVEL_COUNT = SOUND_LEVELS.length;
/** Distinct sounds drawn from the pool at the start of a run. */
export const SOUND_PICK = 8;
/** How many of those distinct sounds come back a second time (spaced apart). */
export const SOUND_REPEATS = 4;
/** Rounds in a full run: the 8 picks, plus a replay of 4 of them. */
export const SOUND_SESSION_LENGTH = SOUND_PICK + SOUND_REPEATS;

export interface SoundTile {
  id: number;
  letter: string;
}

export interface SoundRound {
  target: SoundTarget;
  /** Target order; slots are filled against target.spelling. */
  slots: (string | null)[];
  tray: SoundTile[];
}

function clampLevel(level: number): number {
  return Math.min(Math.max(level, 1), SOUND_LEVEL_COUNT) - 1;
}

export function soundLevel(level: number): SoundLevel {
  return SOUND_LEVELS[clampLevel(level)];
}

export function soundPool(level: number): SoundTarget[] {
  return SOUND_TARGETS[clampLevel(level)];
}

/**
 * One run's ordered sound list: pick SOUND_PICK distinct sounds from the level
 * pool, show SOUND_REPEATS of them a second time (so 12 rounds from 8 sounds),
 * and arrange them so the same sound is never two rounds in a row — the gap is
 * what turns a repeat into memory practice. Falls back to a plain shuffle if the
 * pool is somehow too small to pick a full set.
 */
export function buildSoundSession(level: number): SoundTarget[] {
  const pool = soundPool(level);
  if (pool.length < SOUND_PICK) return shuffle(pool);

  const picks = shuffle(pool).slice(0, SOUND_PICK);
  const doubles = shuffle(picks).slice(0, SOUND_REPEATS);
  const singles = picks.filter((p) => !doubles.includes(p));

  // Group each repeated sound as an adjacent pair, singles after. Distributing
  // this list across even indices then odd indices keeps every pair ≥2 apart —
  // guaranteed collision-free because no sound appears more than twice.
  const grouped = [...doubles.flatMap((d) => [d, d]), ...singles];
  const out: SoundTarget[] = new Array(grouped.length);
  let k = 0;
  for (let i = 0; i < out.length; i += 2) out[i] = grouped[k++];
  for (let i = 1; i < out.length; i += 2) out[i] = grouped[k++];
  return out;
}

/** What the child hears: the bare sound, or "sound, comme dans word." on levels with context. */
export function soundPrompt(t: SoundTarget): string {
  return t.word ? `${t.sound}, comme dans ${t.word}.` : t.sound;
}

/** The success line spoken once the letters are all placed. */
export function soundSuccess(t: SoundTarget): string {
  return `Oui ! ${t.word ?? t.sound}.`;
}

/* -------------------------------------------------------------------------- */
/* Hub catalog                                                                */
/* -------------------------------------------------------------------------- */

export const EXERCISES: ExerciseMeta[] = [
  { id: "first-letter", name: "La première lettre", emoji: "🔤", levelCount: FIRST_LETTER_LEVELS.length },
  { id: "fill-blank", name: "Complète le mot", emoji: "🧩", levelCount: SYLLABLE_LEVEL_COUNT, mode: "fill-blank" },
  { id: "order-syllables", name: "Range les syllabes", emoji: "🔀", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order" },
  { id: "find-intruder", name: "Trouve l’intrus", emoji: "🕵️", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order-distractor" },
  { id: "spell-sound", name: "Fabrique le son", emoji: "🎧", levelCount: SOUND_LEVEL_COUNT },
];

export const MODE_HINT: Record<SyllableMode, string> = {
  "fill-blank": "Trouve la syllabe manquante",
  order: "Remets les syllabes dans l’ordre",
  "order-distractor": "Range le mot… et évite l’intrus !",
};

/* -------------------------------------------------------------------------- */
/* Shared helpers                                                             */
/* -------------------------------------------------------------------------- */

export function shuffle<T>(arr: readonly T[]): T[] {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = (Math.random() * (i + 1)) | 0;
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export function pickDistractorSyllable(exclude: Set<string>): string {
  const options = SYLLABLE_BANK.filter((s) => !exclude.has(s));
  return options[(Math.random() * options.length) | 0] ?? SYLLABLE_BANK[0];
}

let _tileId = 0;
const tile = (syllable: string): SyllableTile => ({ id: _tileId++, syllable });

export function buildSyllableRound(word: SyllableWord, mode: SyllableMode): SyllableRound {
  const syl = word.syllables;

  if (mode === "fill-blank") {
    const missing = (Math.random() * syl.length) | 0;
    const slots = syl.map((s, i) => (i === missing ? null : s));
    const locked = syl.map((_, i) => i !== missing);
    const distractor = pickDistractorSyllable(new Set(syl));
    const tray = shuffle([syl[missing], distractor]).map(tile);
    return { word, slots, locked, tray };
  }

  const slots = syl.map(() => null);
  const locked = syl.map(() => false);
  const trayValues =
    mode === "order-distractor"
      ? [...syl, pickDistractorSyllable(new Set(syl))]
      : [...syl];
  return { word, slots, locked, tray: shuffle(trayValues).map(tile) };
}

let _letterTileId = 0;
const letterTile = (letter: string): SoundTile => ({ id: _letterTileId++, letter });

export function buildSoundRound(target: SoundTarget, distractors: number): SoundRound {
  const need = new Set(target.spelling);
  const intruders = shuffle(SOUND_LETTER_BANK.filter((l) => !need.has(l))).slice(0, distractors);
  return {
    target,
    slots: target.spelling.map(() => null),
    tray: shuffle([...target.spelling, ...intruders]).map(letterTile),
  };
}
