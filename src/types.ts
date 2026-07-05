export type ExerciseId =
  | "first-letter"
  | "read-image"
  | "match-case"
  | "match-script"
  | "fill-blank"
  | "order-syllables"
  | "find-intruder"
  | "spell-syllable"
  | "spell-syllable-plus"
  | "spell-two-syllables"
  | "spell-syllable-plus-mixed"
  | "spell-two-syllables-mixed"
  | "spell-sound";

export type Mood = "idle" | "happy" | "cheer";

export type Verdict = "accept" | "reject";

/** First-letter exercise ---------------------------------------------------*/
export interface LetterWord {
  letter: string;
  word: string;
  emoji: string;
  /**
   * Optional dedicated illustration (imported asset URL) shown INSTEAD of `emoji`
   * when the emoji misrepresents the word (e.g. no true "igloo"/"jupe" glyph).
   * `emoji` is kept as the a11y/text fallback. See WordIcon.
   */
  img?: string;
}

/** Read-the-word exercise ---------------------------------------------------*/
/** The written word is shown; the child taps the picture that matches it. */
export interface ReadImageRound {
  target: LetterWord;
  /** The target word plus distractor words, shuffled — each rendered as a picture. */
  choices: LetterWord[];
}

export interface FirstLetterLevel {
  /** First-letter catalog for this level. `null` = full catalog. */
  letters: string[] | null;
  /** Distinct words drawn from the pool at the start of a run. */
  pick: number;
  /** How many of those words come back a second time (spaced apart). */
  repeats: number;
}

export interface FirstLetterRound {
  target: LetterWord;
  /** The target letter plus distractors, shuffled. */
  choices: string[];
}

/** Letter-form matching exercise -------------------------------------------*/
/**
 * One engine, two skills: pair a letter with its counterpart FORM. `case` pairs a
 * majuscule with its minuscule (both directions in one run); `script` pairs a
 * printed (sans-serif) letter with its cursive "attaché" twin at the SAME case.
 * The underlying letter is the identity; only the rendered form flips.
 */
export type LetterMatchKind = "case" | "script";

/** How a letter is drawn on a tile. */
export type LetterScript = "print" | "cursive";

/** One rendered letter: the same underlying letter, shown in a given case + script. */
export interface LetterFace {
  /** Canonical UPPERCASE letter — identity (pick match) + the name spoken by VO. */
  base: string;
  /** The exact glyph to render, already cased (e.g. "A" or "a"). */
  glyph: string;
  script: LetterScript;
}

export interface LetterMatchRound {
  /** The letter shown big; the child finds its counterpart form below. */
  prompt: LetterFace;
  /** Tiles, shuffled — all in the counterpart form; exactly one shares prompt.base. */
  choices: LetterFace[];
}

/** Build-syllables exercise ------------------------------------------------*/
export interface SyllableWord {
  word: string;
  /** Pre-authored orthographic split, in reading order. */
  syllables: string[];
  emoji: string;
  /** Optional dedicated illustration shown instead of `emoji`. See LetterWord.img / WordIcon. */
  img?: string;
}

export type SyllableMode = "fill-blank" | "order" | "order-distractor";

/** Fill-a-syllable exercise -----------------------------------------------*/
/**
 * ONE engine, three siblings. Part of the word is already written; one (or two)
 * syllable is blanked into per-letter slots the child fills by tapping letters
 * in the right order. Mode only changes what lands in the tray / how many gaps:
 *   - `letters-exact`  one gap, tray = exactly that syllable's letters (order only).
 *   - `letters-extra`  one gap, tray = those letters + intruder letters.
 *   - `letters-two`    two gaps, tray = both syllables' letters + intruders.
 * All three share the SAME word ladder (level per level), so a child meets the
 * same words as the task gets harder.
 */
export type SpellSyllableMode = "letters-exact" | "letters-extra" | "letters-two";

export interface SyllableTier {
  minSyllables: number;
  maxSyllables: number;
  /** Distinct words drawn from the tier pool at the start of a run. */
  pick: number;
  /** How many of those words come back a second time (spaced apart). */
  repeats: number;
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
  /** Fill-a-syllable siblings carry which letter mode they run; others leave it undefined. */
  spell?: SpellSyllableMode;
  /**
   * Fill-a-syllable "écritures mêlées" twins: the word shows in ONE of three
   * writings (grande / petite / attachée) and the tray mixes forms, so the child
   * must pick each letter in the right case AND script. Plain siblings leave it off.
   */
  mixed?: boolean;
  /** Letter-form matching exercises carry which form they flip; others leave it undefined. */
  match?: LetterMatchKind;
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
  /**
   * Static shop-thumbnail mode: no idle/pop animation and the per-stage MAGIC
   * (wings, halo, mane, crown, aura, extra tails, sparkles…) is suppressed, so a
   * ghost silhouette shows ONLY the part a tile is selling. See ItemPreview.
   */
  preview?: boolean;
}
