export type ExerciseId =
  | "first-letter"
  | "fill-blank"
  | "order-syllables"
  | "find-intruder";

export type Mood = "idle" | "happy" | "cheer";

export type Verdict = "accept" | "reject";

/** First-letter exercise ---------------------------------------------------*/
export interface LetterWord {
  letter: string;
  word: string;
  emoji: string;
}

export interface FirstLetterLevel {
  /** First-letter catalog for this level. `null` = full catalog. */
  letters: string[] | null;
}

export interface FirstLetterRound {
  target: LetterWord;
  /** The target letter plus distractors, shuffled. */
  choices: string[];
}

/** Build-syllables exercise ------------------------------------------------*/
export interface SyllableWord {
  word: string;
  /** Pre-authored orthographic split, in reading order. */
  syllables: string[];
  emoji: string;
}

export type SyllableMode = "fill-blank" | "order" | "order-distractor";

export interface SyllableTier {
  minSyllables: number;
  maxSyllables: number;
  /** Words available (and session length) at this tier. */
  poolSize: number;
}

/** Hub / navigation --------------------------------------------------------*/
export interface ExerciseMeta {
  id: ExerciseId;
  name: string;
  emoji: string;
  levelCount: number;
  /** Syllable exercises carry the seeding mode; first-letter leaves it undefined. */
  mode?: SyllableMode;
}

export type View =
  | { kind: "hub" }
  | { kind: "play"; exercise: ExerciseId; level: number };
