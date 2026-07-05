import {
  LETTER_WORDS,
  LETTER_MATCH_ALPHABET,
  SYLLABLE_WORDS,
  SYLLABLE_BANK,
  SPELL_SYLLABLE_WORD_NAMES,
  SOUND_TARGETS,
  SOUND_LETTER_BANK,
} from "./content";
import type {
  ExerciseMeta,
  FirstLetterLevel,
  LetterFace,
  LetterMatchKind,
  LetterMatchRound,
  LetterWord,
  SoundLevel,
  SoundTarget,
  SpellSyllableMode,
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
  { letters: ["A", "B", "C", "M", "P"], pick: 5, repeats: 3 },
  { letters: ["A", "B", "C", "D", "M", "P", "R", "S", "T"], pick: 6, repeats: 3 },
  { letters: ["A", "B", "C", "D", "F", "L", "M", "N", "P", "R", "S", "T"], pick: 7, repeats: 4 },
  {
    letters: [
      "A", "B", "C", "D", "F", "G", "H", "L", "M",
      "N", "O", "P", "R", "S", "T", "V",
    ],
    pick: 8,
    repeats: 4,
  },
  { letters: null, pick: 8, repeats: 4 }, // full catalog
];

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
/* Letter-form matching — 4 explicit levels, shared by both kinds (case+script) */
/* Difficulty is one axis: the letter pool grows each level (6 → 13 → 19 → 26)   */
/* + one more distractor tile, ending on the full alphabet.                     */
/* Each run MIXES both directions (upper→lower AND lower→upper; print→cursive    */
/* AND cursive→print), so one session drills a form and "the opposite" together. */
/* -------------------------------------------------------------------------- */

export interface LetterMatchLevel {
  /** Letter catalog (uppercase). `null` = the full alphabet. */
  letters: string[] | null;
  /** Distinct letters drawn from the pool at the start of a run. */
  pick: number;
  /** How many of those come back a second time (spaced apart). */
  repeats: number;
  /** Wrong-answer tiles added beside the correct counterpart. */
  distractors: number;
}

export const LETTER_MATCH_LEVELS: LetterMatchLevel[] = [
  { letters: ["A", "B", "D", "E", "G", "R"], pick: 5, repeats: 3, distractors: 2 }, // 6
  {
    letters: ["A", "B", "C", "D", "E", "F", "G", "H", "N", "O", "R", "S", "T"],
    pick: 6,
    repeats: 3,
    distractors: 2,
  }, // 13
  {
    letters: [
      "A", "B", "C", "D", "E", "F", "G", "H", "I", "L",
      "M", "N", "O", "P", "Q", "R", "S", "T", "U",
    ],
    pick: 7,
    repeats: 3,
    distractors: 3,
  }, // 19
  { letters: null, pick: 8, repeats: 4, distractors: 3 }, // full alphabet (26)
];

export const LETTER_MATCH_LEVEL_COUNT = LETTER_MATCH_LEVELS.length;

function letterMatchLevel(level: number): LetterMatchLevel {
  return LETTER_MATCH_LEVELS[Math.min(Math.max(level, 1), LETTER_MATCH_LEVEL_COUNT) - 1];
}

export function letterMatchPool(level: number): string[] {
  return letterMatchLevel(level).letters ?? LETTER_MATCH_ALPHABET;
}

/** The fixed, directional instruction lines — a small, bake-able VO set. */
export const LETTER_MATCH_PROMPTS = {
  toLower: "Trouve la petite lettre.",
  toUpper: "Trouve la grande lettre.",
  toCursive: "Trouve la lettre attachée.",
  toPrint: "Trouve la lettre en script.",
} as const;

/** Which line to speak, read off the prompt→answer transform (never names the target). */
export function letterMatchPrompt(prompt: LetterFace, answer: LetterFace): string {
  if (prompt.script !== answer.script)
    return answer.script === "cursive"
      ? LETTER_MATCH_PROMPTS.toCursive
      : LETTER_MATCH_PROMPTS.toPrint;
  return answer.glyph === answer.glyph.toUpperCase()
    ? LETTER_MATCH_PROMPTS.toUpper
    : LETTER_MATCH_PROMPTS.toLower;
}

/** The success line — names the letter only AFTER it's been matched by its shape. */
export function letterMatchSuccess(base: string): string {
  return `Oui ! ${base}.`;
}

/**
 * One run of match rounds. Direction is randomised per round (both `case`
 * directions, both `script` directions), so a single session practises a form
 * and its opposite. The correct tile is always the counterpart of `prompt`.
 */
export function buildLetterMatchSession(
  kind: LetterMatchKind,
  level: number
): LetterMatchRound[] {
  const cfg = letterMatchLevel(level);
  const catalog = letterMatchPool(level);
  return repeatSession(catalog, cfg.pick, cfg.repeats).map((base) => {
    const distractors = shuffle(catalog.filter((l) => l !== base)).slice(0, cfg.distractors);
    const pool = shuffle([base, ...distractors]);
    return kind === "case" ? caseRound(base, pool) : scriptRound(base, pool);
  });
}

/** upper⇄lower, both plain print: prompt one case, every tile the other. */
function caseRound(base: string, pool: string[]): LetterMatchRound {
  const promptUpper = Math.random() < 0.5;
  const face = (l: string, upper: boolean): LetterFace => ({
    base: l,
    glyph: upper ? l : l.toLowerCase(),
    script: "print",
  });
  return {
    prompt: face(base, promptUpper),
    choices: pool.map((l) => face(l, !promptUpper)),
  };
}

/** print⇄cursive at ONE shared case: prompt one script, every tile the other. */
function scriptRound(base: string, pool: string[]): LetterMatchRound {
  const upper = Math.random() < 0.5; // prompt + tiles share this case
  const promptCursive = Math.random() < 0.5;
  const face = (l: string, cursive: boolean): LetterFace => ({
    base: l,
    glyph: upper ? l : l.toLowerCase(),
    script: cursive ? "cursive" : "print",
  });
  return {
    prompt: face(base, promptCursive),
    choices: pool.map((l) => face(l, !promptCursive)),
  };
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
  { minSyllables: 2, maxSyllables: 2, pick: 6, repeats: 3 },
  { minSyllables: 2, maxSyllables: 3, pick: 7, repeats: 3 },
  { minSyllables: 3, maxSyllables: 3, pick: 6, repeats: 3 },
  { minSyllables: 3, maxSyllables: 4, pick: 8, repeats: 4 },
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

export function buildSoundSession(level: number): SoundTarget[] {
  return repeatSession(soundPool(level), SOUND_PICK, SOUND_REPEATS);
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
/* Fill-a-syllable — one 4-level ladder, shared by all 3 siblings.             */
/* The word list is level == index (content); only the tray/gap count changes  */
/* per mode. Level 1 is deliberately 5 words for a quick, repeated win.         */
/* -------------------------------------------------------------------------- */

export interface SpellSyllableLevel {
  /** Distinct words drawn from the level's list at the start of a run. */
  pick: number;
  /** How many of those words come back a second time (spaced apart). */
  repeats: number;
  /** Intruder letters added to the tray for the -extra / -two modes (0 for exact). */
  distractors: number;
}

export const SPELL_SYLLABLE_LEVELS: SpellSyllableLevel[] = [
  { pick: 5, repeats: 3, distractors: 2 },
  { pick: 6, repeats: 3, distractors: 2 },
  { pick: 7, repeats: 3, distractors: 3 },
  { pick: 8, repeats: 4, distractors: 3 },
];

export const SPELL_SYLLABLE_LEVEL_COUNT = SPELL_SYLLABLE_LEVELS.length;

const WORD_BY_NAME = new Map(SYLLABLE_WORDS.map((w) => [w.word, w]));

function spellSyllableIdx(level: number): number {
  return Math.min(Math.max(level, 1), SPELL_SYLLABLE_LEVEL_COUNT) - 1;
}

export function spellSyllableLevel(level: number): SpellSyllableLevel {
  return SPELL_SYLLABLE_LEVELS[spellSyllableIdx(level)];
}

/** The level's authored word list, resolved to full SyllableWord objects. */
export function spellSyllablePool(level: number): SyllableWord[] {
  return SPELL_SYLLABLE_WORD_NAMES[spellSyllableIdx(level)].map((name) => {
    const w = WORD_BY_NAME.get(name);
    if (!w) throw new Error(`spellSyllablePool: unknown word "${name}"`);
    return w;
  });
}

export function buildSpellSyllableSession(level: number): SyllableWord[] {
  const cfg = spellSyllableLevel(level);
  return repeatSession(spellSyllablePool(level), cfg.pick, cfg.repeats);
}

export interface SpellCell {
  /** The correct letter at this position. */
  letter: string;
  /** True = a slot the child fills; false = already written (locked). */
  fill: boolean;
  /** Index among fill cells in reading order, or -1 when shown. */
  slotIndex: number;
  /** First letter of its syllable — used to gap-space the written word. */
  syllableStart: boolean;
}

export interface SpellLetterTile {
  id: number;
  letter: string;
}

export interface SpellSyllableRound {
  word: SyllableWord;
  /** The whole word, letter by letter: shown letters + the gap's slots, in order. */
  cells: SpellCell[];
  /** The gap letters in reading order — what the filled slots must equal. */
  answer: string[];
  /** Shuffled letter tiles the child taps. */
  tray: SpellLetterTile[];
}

let _spellTileId = 0;
const spellTile = (letter: string): SpellLetterTile => ({ id: _spellTileId++, letter });

/**
 * Blank out 1 (or 2, for `letters-two`) whole syllables into per-letter slots and
 * fill the tray. `letters-exact` gives only the gap's own letters (order is the
 * whole task); the other two add intruders. At least one syllable always stays
 * written, so there's a printed anchor to read the word from. Hidden syllables
 * are chosen at random — the gap can sit anywhere in the word.
 */
export function buildSpellSyllableRound(
  word: SyllableWord,
  mode: SpellSyllableMode,
  distractors: number
): SpellSyllableRound {
  const syl = word.syllables;
  const hideCount = Math.min(mode === "letters-two" ? 2 : 1, syl.length - 1);
  const hidden = new Set(shuffle(syl.map((_, i) => i)).slice(0, hideCount));

  const cells: SpellCell[] = [];
  const answer: string[] = [];
  syl.forEach((s, si) => {
    const gap = hidden.has(si);
    [...s].forEach((ch, ci) => {
      cells.push({
        letter: ch,
        fill: gap,
        slotIndex: gap ? answer.length : -1,
        syllableStart: ci === 0,
      });
      if (gap) answer.push(ch);
    });
  });

  const extra = mode === "letters-exact" ? 0 : distractors;
  const need = new Set(answer);
  const intruders = shuffle(SOUND_LETTER_BANK.filter((l) => !need.has(l))).slice(0, extra);
  return { word, cells, answer, tray: shuffle([...answer, ...intruders]).map(spellTile) };
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
  { id: "spell-syllable", name: "Écris la syllabe", emoji: "✏️", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-exact" },
  { id: "spell-syllable-plus", name: "La syllabe et les intrus", emoji: "🎯", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-extra" },
  { id: "spell-two-syllables", name: "Écris deux syllabes", emoji: "📝", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-two" },
  { id: "match-case", name: "Grande et petite lettre", emoji: "🔠", levelCount: LETTER_MATCH_LEVEL_COUNT, match: "case" },
  { id: "match-script", name: "Lettres attachées", emoji: "✍️", levelCount: LETTER_MATCH_LEVEL_COUNT, match: "script" },
];

export const MODE_HINT: Record<SyllableMode, string> = {
  "fill-blank": "Trouve la syllabe manquante",
  order: "Remets les syllabes dans l’ordre",
  "order-distractor": "Range le mot… et évite l’intrus !",
};

export const MATCH_HINT: Record<LetterMatchKind, string> = {
  case: "Associe majuscule et minuscule",
  script: "Associe le script et l’attaché",
};

export const SPELL_HINT: Record<SpellSyllableMode, string> = {
  "letters-exact": "Range les lettres de la syllabe",
  "letters-extra": "Range les lettres… évite les intrus",
  "letters-two": "Complète les deux syllabes",
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

/**
 * One run's ordered item list: pick `pick` distinct items from `pool`, replay
 * `repeats` of them a second time, and arrange so no item ever lands two rounds
 * in a row — the gap is what turns a repeat into memory practice. Both counts are
 * clamped to the pool, so a short pool just yields a shorter (still valid) run.
 *
 * Construction: group each repeated item as an adjacent pair (doubles first,
 * singles after), then deal that list across even indices, then odd. With no
 * item appearing more than twice and a run of ≥3, this is always collision-free.
 */
export function repeatSession<T>(pool: readonly T[], pick: number, repeats: number): T[] {
  const p = Math.min(pick, pool.length);
  const r = Math.min(repeats, p);
  const picks = shuffle(pool).slice(0, p);
  if (r === 0) return picks;

  const doubles = shuffle(picks).slice(0, r);
  const singles = picks.filter((x) => !doubles.includes(x));
  const grouped = [...doubles.flatMap((d) => [d, d]), ...singles];
  const out = new Array<T>(grouped.length);
  let k = 0;
  for (let i = 0; i < out.length; i += 2) out[i] = grouped[k++];
  for (let i = 1; i < out.length; i += 2) out[i] = grouped[k++];
  return out;
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
