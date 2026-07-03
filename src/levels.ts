import { LETTER_WORDS, SYLLABLE_WORDS, SYLLABLE_BANK } from "./content";
import type {
  ExerciseMeta,
  FirstLetterLevel,
  LetterWord,
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
/* Hub catalog                                                                */
/* -------------------------------------------------------------------------- */

export const EXERCISES: ExerciseMeta[] = [
  { id: "first-letter", name: "La première lettre", emoji: "🔤", levelCount: FIRST_LETTER_LEVELS.length },
  { id: "fill-blank", name: "Complète le mot", emoji: "🧩", levelCount: SYLLABLE_LEVEL_COUNT, mode: "fill-blank" },
  { id: "order-syllables", name: "Range les syllabes", emoji: "🔀", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order" },
  { id: "find-intruder", name: "Trouve l’intrus", emoji: "🕵️", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order-distractor" },
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
