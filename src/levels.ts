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
  Difficulty,
  ExerciseId,
  ExerciseMeta,
  FirstLetterLevel,
  LetterFace,
  LetterMatchKind,
  LetterMatchRound,
  LetterScript,
  LetterWord,
  ReadImageRound,
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
/* Read-the-word — 4 explicit levels                                          */
/* The mirror of first-letter: the WORD is shown, the child reads it and taps  */
/* the matching picture. Reading IS the task, so the word is never spoken; the  */
/* only difficulty axis is how many pictures crowd the choice (2 → 4 distractors */
/* around the answer). Draws from the same curated LETTER_WORDS nouns.          */
/* -------------------------------------------------------------------------- */

export interface ReadImageLevel {
  /** Distinct words drawn from the pool at the start of a run. */
  pick: number;
  /** How many of those words come back a second time (spaced apart). */
  repeats: number;
  /** Wrong-picture tiles shown beside the correct one. */
  distractors: number;
}

export const READ_IMAGE_LEVELS: ReadImageLevel[] = [
  { pick: 5, repeats: 3, distractors: 2 },
  { pick: 6, repeats: 3, distractors: 2 },
  { pick: 7, repeats: 4, distractors: 3 },
  { pick: 8, repeats: 4, distractors: 4 },
];

export const READ_IMAGE_LEVEL_COUNT = READ_IMAGE_LEVELS.length;

export function readImageLevel(level: number): ReadImageLevel {
  return READ_IMAGE_LEVELS[Math.min(Math.max(level, 1), READ_IMAGE_LEVEL_COUNT) - 1];
}

/** The picture pool: every curated noun. Reading practice, so all levels see all. */
export function readImagePool(): LetterWord[] {
  return LETTER_WORDS;
}

/**
 * One read-the-word round: the target plus `distractors` other words, shuffled.
 * Distractors are picked by DISTINCT emoji so no two tiles ever show the same
 * picture (a few nouns share a glyph), which would make the choice ambiguous.
 */
export function buildReadImageRound(target: LetterWord, distractors: number): ReadImageRound {
  const seen = new Set([target.emoji]);
  const pool: LetterWord[] = [];
  for (const w of shuffle(LETTER_WORDS)) {
    if (seen.has(w.emoji)) continue;
    seen.add(w.emoji);
    pool.push(w);
    if (pool.length >= distractors) break;
  }
  return { target, choices: shuffle([target, ...pool]) };
}

export function buildReadImageSession(level: number): ReadImageRound[] {
  const cfg = readImageLevel(level);
  return repeatSession(readImagePool(), cfg.pick, cfg.repeats).map((target) =>
    buildReadImageRound(target, cfg.distractors)
  );
}

/** What the child hears: never the word (that would give the answer away). */
export const READ_IMAGE_PROMPT = "Trouve la bonne image.";

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
  /** The correct letter at this position, canonical UPPERCASE (answer + VO). */
  letter: string;
  /** The exact glyph to render, cased for the round's form (e.g. "A"/"a"/"a"). */
  glyph: string;
  /** print / cursive for the round's form (drives the font). */
  script: LetterScript;
  /** True = a slot the child fills; false = already written (locked). */
  fill: boolean;
  /** Index among fill cells in reading order, or -1 when shown. */
  slotIndex: number;
  /** First letter of its syllable — used to gap-space the written word. */
  syllableStart: boolean;
}

export interface SpellLetterTile {
  id: number;
  /** Canonical UPPERCASE letter — VO name + answer identity. */
  letter: string;
  /** The exact glyph to render (cased). In plain rounds glyph === letter. */
  glyph: string;
  script: LetterScript;
}

export interface SpellSyllableRound {
  word: SyllableWord;
  /** The whole word, letter by letter: shown letters + the gap's slots, in order. */
  cells: SpellCell[];
  /** The gap letters (base, uppercase) in reading order. */
  answer: string[];
  /**
   * The gap FACES in reading order — what a filled slot must equal. In plain
   * rounds a face is just the uppercase print letter; in "mixed" rounds it also
   * pins the case + script, so the child must match the writing, not only the
   * letter.
   */
  answerFaces: LetterFace[];
  /** Shuffled letter tiles the child taps. */
  tray: SpellLetterTile[];
}

/** One writing the word can take: a case + a script. */
interface SpellForm {
  upper: boolean;
  script: LetterScript;
}
/** Plain siblings always print big uppercase — the letter is never in question. */
const PLAIN_FORM: SpellForm = { upper: true, script: "print" };
/** The three writings the "écritures mêlées" twins shuffle between per round. */
const MIXED_FORMS: SpellForm[] = [
  { upper: true, script: "print" }, // GRANDE
  { upper: false, script: "print" }, // petite
  { upper: false, script: "cursive" }, // attachée
];

function spellFace(base: string, form: SpellForm): LetterFace {
  return { base, glyph: form.upper ? base : base.toLowerCase(), script: form.script };
}
/** Two faces render identically iff they share this key (glyph already encodes the case). */
const faceKey = (f: LetterFace) => `${f.glyph}|${f.script}`;

let _spellTileId = 0;
const spellTile = (f: LetterFace): SpellLetterTile => ({
  id: _spellTileId++,
  letter: f.base,
  glyph: f.glyph,
  script: f.script,
});

/**
 * Blank out 1 (or 2, for `letters-two`) whole syllables into per-letter slots and
 * fill the tray. `letters-exact` gives only the gap's own letters (order is the
 * whole task); the other two add intruders. At least one syllable always stays
 * written, so there's a printed anchor to read the word from. Hidden syllables
 * are chosen at random — the gap can sit anywhere in the word.
 *
 * `mixed` adds a second axis: the whole word (anchors + answer) is drawn in ONE
 * random writing (grande / petite / attachée), and the intruders become the SAME
 * gap letters in the OTHER two writings — so a tile with the right letter but the
 * wrong case or script is a trap. The child must match the writing, not just the
 * letter. Plain rounds keep the exact old shape (uppercase print, letter-only).
 */
export function buildSpellSyllableRound(
  word: SyllableWord,
  mode: SpellSyllableMode,
  distractors: number,
  mixed = false
): SpellSyllableRound {
  const syl = word.syllables;
  const hideCount = Math.min(mode === "letters-two" ? 2 : 1, syl.length - 1);
  const hidden = new Set(shuffle(syl.map((_, i) => i)).slice(0, hideCount));
  const form = mixed ? MIXED_FORMS[(Math.random() * MIXED_FORMS.length) | 0] : PLAIN_FORM;

  const cells: SpellCell[] = [];
  const answer: string[] = [];
  const answerFaces: LetterFace[] = [];
  syl.forEach((s, si) => {
    const gap = hidden.has(si);
    [...s].forEach((ch, ci) => {
      const face = spellFace(ch, form);
      cells.push({
        letter: ch,
        glyph: face.glyph,
        script: face.script,
        fill: gap,
        slotIndex: gap ? answer.length : -1,
        syllableStart: ci === 0,
      });
      if (gap) {
        answer.push(ch);
        answerFaces.push(face);
      }
    });
  });

  const extra = mode === "letters-exact" ? 0 : distractors;
  const trayFaces = [...answerFaces, ...spellIntruders(answer, form, extra, mixed)];
  return { word, cells, answer, answerFaces, tray: shuffle(trayFaces).map(spellTile) };
}

/**
 * `extra` distractor faces for the tray. Plain: wrong LETTERS in the same writing
 * (the classic intrus). Mixed: prefer the SAME gap letters in the two OTHER
 * writings (the point of the game), then top up with wrong letters in random
 * writings. Deduped against the answer + each other so no tile is a valid answer.
 */
function spellIntruders(
  answer: string[],
  form: SpellForm,
  extra: number,
  mixed: boolean
): LetterFace[] {
  if (extra <= 0) return [];
  if (!mixed) {
    const need = new Set(answer);
    return shuffle(SOUND_LETTER_BANK.filter((l) => !need.has(l)))
      .slice(0, extra)
      .map((l) => spellFace(l, form));
  }

  const seen = new Set(answer.map((l) => faceKey(spellFace(l, form))));
  const take = (face: LetterFace, into: LetterFace[]) => {
    const k = faceKey(face);
    if (seen.has(k)) return;
    seen.add(k);
    into.push(face);
  };

  // Same letters, wrong writings — the traps that make "the right case" matter.
  const wrongForm: LetterFace[] = [];
  for (const base of new Set(answer))
    for (const f of MIXED_FORMS) take(spellFace(base, f), wrongForm);

  // Wrong letters, random writings — fill any remainder.
  const others: LetterFace[] = [];
  const answerSet = new Set(answer);
  for (const base of shuffle(SOUND_LETTER_BANK).filter((l) => !answerSet.has(l)))
    take(spellFace(base, MIXED_FORMS[(Math.random() * MIXED_FORMS.length) | 0]), others);

  return [...shuffle(wrongForm), ...others].slice(0, extra);
}

/* -------------------------------------------------------------------------- */
/* Hub catalog                                                                */
/* -------------------------------------------------------------------------- */

// `difficulty` is the reward weight (see rewards.sessionReward): 0 = training,
// pays nothing; 1–4 = bonus points a full first-try run earns. It rises with
// the hub progression so the point-optimal strategy is climbing, not farming.
export const EXERCISES: ExerciseMeta[] = [
  { id: "first-letter", name: "La première lettre", emoji: "🔤", levelCount: FIRST_LETTER_LEVELS.length, difficulty: 0 },
  { id: "fill-blank", name: "Complète le mot", emoji: "🧩", levelCount: SYLLABLE_LEVEL_COUNT, mode: "fill-blank", difficulty: 0 },
  { id: "order-syllables", name: "Range les syllabes", emoji: "🔀", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order", difficulty: 1 },
  { id: "find-intruder", name: "Trouve l’intrus", emoji: "🕵️", levelCount: SYLLABLE_LEVEL_COUNT, mode: "order-distractor", difficulty: 1 },
  { id: "spell-sound", name: "Fabrique le son", emoji: "🎧", levelCount: SOUND_LEVEL_COUNT, difficulty: 2 },
  { id: "spell-syllable", name: "Écris la syllabe", emoji: "✏️", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-exact", difficulty: 2 },
  { id: "spell-syllable-plus", name: "La syllabe et les intrus", emoji: "🎯", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-extra", difficulty: 2 },
  { id: "spell-two-syllables", name: "Écris deux syllabes", emoji: "📝", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-two", difficulty: 3 },
  { id: "read-image", name: "Lis le mot", emoji: "🖼️", levelCount: READ_IMAGE_LEVEL_COUNT, difficulty: 2 },
  { id: "match-case", name: "Grande et petite lettre", emoji: "🔠", levelCount: LETTER_MATCH_LEVEL_COUNT, match: "case", difficulty: 1 },
  { id: "match-script", name: "Lettres attachées", emoji: "✍️", levelCount: LETTER_MATCH_LEVEL_COUNT, match: "script", difficulty: 2 },
  // The "écritures mêlées" twins: the two intruder spellers again, but now the
  // word takes one of three writings and the tray mixes forms — same letter in
  // the wrong case/script is a trap. They come LAST, after the child has met
  // majuscule↔minuscule (match-case) and l'attaché (match-script) on their own.
  { id: "spell-syllable-plus-mixed", name: "La syllabe et les intrus mêlés", emoji: "🎭", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-extra", mixed: true, difficulty: 4 },
  { id: "spell-two-syllables-mixed", name: "Deux syllabes, écritures mêlées", emoji: "🖋️", levelCount: SPELL_SYLLABLE_LEVEL_COUNT, spell: "letters-two", mixed: true, difficulty: 4 },
];

/** Reward weight by exercise — EXERCISES is the single authority. */
export function exerciseDifficulty(id: ExerciseId): Difficulty {
  const meta = EXERCISES.find((e) => e.id === id);
  return meta ? meta.difficulty : 0;
}

/** Extra hub chip for the "écritures mêlées" twins — names the twist plainly. */
export const MIXED_HINT = "GRANDE, petite ou attachée — trouve la bonne";

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
