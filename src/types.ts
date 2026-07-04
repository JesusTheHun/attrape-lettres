export type ExerciseId =
  | "first-letter"
  | "fill-blank"
  | "order-syllables"
  | "find-intruder"
  | "spell-sound";

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

/** Spell-the-sound exercise ------------------------------------------------*/
/**
 * One heard sound the child must re-spell by picking letters in order. The point
 * of the ladder: the same `sound` gets several `spelling`s across rounds (o / au
 * / eau, f / ph…), so the child memorises that one sound has many written forms.
 */
export interface SoundTarget {
  /** Spoken syllable / phoneme, lowercase for the TTS (e.g. "lo", "fo", "oi"). */
  sound: string;
  /** Ordered letter tiles that spell it, uppercase (e.g. ["L","O"], ["P","H","O"]). */
  spelling: string[];
  /** Real word this spelling lives in — spoken as "comme dans …" + shown as emoji. */
  word?: string;
  /** Illustration for the context word. */
  emoji?: string;
}

export interface SoundLevel {
  /** Wrong letter-tiles added to the tray (0 = only the needed letters). */
  distractors: number;
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
  | { kind: "play"; exercise: ExerciseId; level: number }
  | { kind: "dashboard" }
  | { kind: "shop" }
  | { kind: "pick" };

/** Mascot + rewards --------------------------------------------------------*/
/* Shared contract for the mascot / points / customization feature. Agents A   */
/* (design), B (earn+dashboard) and C (spend+customize) all build against this. */
/* Do not fork these shapes; add agent-local types in agent-owned files.       */

export type Species = "unicorn" | "cat" | "fox";

/** 0 = baby … 9 = majestic. 10 growth stages. */
export const GROWTH_STAGES = 10;

export type CustomizationCategory = "accessory" | "color" | "style";

/** One buyable item in the shop. `slot` is the config key it writes. */
export interface CustomizationOption {
  /** Globally unique, e.g. "unicorn.horn.rainbow". */
  id: string;
  species: Species;
  category: CustomizationCategory;
  /** Config key this writes: colours/styles set `colors[slot]`/`styles[slot]`. */
  slot: string;
  /** Colour hex or style-variant id. Ignored for pure accessories. */
  value: string;
  /** French shop label. */
  name: string;
  /** Optional shop thumbnail. */
  emoji?: string;
  /** Cost in points. */
  cost: number;
  /** Optional growth gate (default 0). */
  minStage?: number;
}

/** Everything that makes one child's mascot look the way it does. */
export interface MascotConfig {
  species: Species;
  /** 0..GROWTH_STAGES-1 */
  stage: number;
  /** slot -> hex colour, e.g. { hornColor: "#F0A", tailColor: "#8CF" } */
  colors: Record<string, string>;
  /** slot -> variant id, e.g. { tailSize: "long", hair: "curly" } */
  styles: Record<string, string>;
  /** Equipped accessory option ids. */
  accessories: string[];
}

/** Times each (exerciseId, level) has been cleared. Key via ledgerKey(). */
export type CompletionLedger = Record<string, number>;

/** One mascot's own progress. Kept forever — switching never discards it. */
export interface SpeciesProgress {
  /** This mascot's current look (stage, colours, styles, accessories). */
  config: MascotConfig;
  /** Option ids bought FOR THIS SPECIES (unlocked, may or may not be equipped). */
  owned: string[];
}

/**
 * The persisted child profile — what storage.ts reads/writes.
 *
 * Progress is split by ownership: growth/look/items live PER SPECIES (each
 * mascot remembers itself), while stars (`balance`) and cleared-levels
 * (`ledger`) belong to the CHILD and survive every mascot switch.
 */
export interface PersistedProfile {
  /** Has a species been picked yet (first-run gate). */
  chosen: boolean;
  /** The active mascot. */
  current: Species;
  /** Per-species progress; the child can switch back anytime, nothing is lost. */
  species: Record<Species, SpeciesProgress>;
  /** Spendable points — GLOBAL to the child, survives switches. */
  balance: number;
  /** Cleared (exercise, level) counts — GLOBAL to the child. */
  ledger: CompletionLedger;
}

/**
 * Runtime profile exposed by useProfile: the persisted shape plus flat mirrors
 * of the CURRENT species' `config`/`owned`, so callers keep reading
 * `profile.config` / `profile.owned` unchanged.
 */
export interface Profile extends PersistedProfile {
  /** = species[current].config */
  config: MascotConfig;
  /** = species[current].owned */
  owned: string[];
}

/**
 * One child on this device. Siblings share the tablet; each keeps their own
 * mascots, stars and progress. The child's avatar is their current mascot (or
 * the owl, until they've picked a species).
 */
export interface ChildProfile {
  id: string;
  name: string;
  profile: PersistedProfile;
}

/** Everyone who plays on this device + who's currently at the wheel. */
export interface Roster {
  children: ChildProfile[];
  /** The child now playing; null shows the "Qui joue ?" welcome screen. */
  activeId: string | null;
}

/** Drop-in replacement for <Ollie mood>. Agent A implements the SVG rig. */
export interface MascotProps {
  config: MascotConfig;
  mood: Mood;
  /** Rendered pixel size (fluid callers pass a clamp-derived number). */
  size?: number;
}
