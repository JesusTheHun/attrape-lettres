export type ExerciseId =
  | "first-letter"
  | "find-sound"
  | "hear-syllable"
  | "pick-vowel"
  | "sound-twins"
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

/** Find-the-sound exercise -------------------------------------------------*/
/**
 * The youngest rung of the sound ladder (recognition; spell-sound is
 * production): the child HEARS a sound with its anchor word (« ou, comme dans
 * hibou ») and taps the tile that writes it. Same one-prompt/one-tile loop as
 * first-letter, so a pre-reader already knows how to play — no reading needed.
 */
export interface BasicSound {
  /** Spoken sound, lowercase for the TTS/VO (e.g. "ou", "or", "che"). */
  sound: string;
  /** The written form shown on the tile, uppercase (e.g. "OU", "CH"). */
  graphy: string;
  /** Anchor word the sound lives in — spoken as "comme dans …" + shown as emoji. */
  word: string;
  emoji: string;
  /**
   * Authored confusable graphies (from the SAME level pool) preferred as
   * distractors — adaptive-by-confusability done as data (OU vs ON, AN vs IN…).
   */
  traps?: string[];
}

/** Syllable-grid exercises --------------------------------------------------*/
/**
 * The « tableau des syllabes »: the exhaustive consonant × vowel combinatoire
 * (VA VE VI VO VU VÉ) a child must fuse before any word work. ONE engine, two
 * drills over the SAME grid:
 *   - `hear`   hear « va », tap the tile that writes it (the neighbours are the
 *              same consonant with the other vowels, so the VOWEL is the task).
 *   - `vowel`  hear « vi », the consonant is already written, tap the vowel
 *              that finishes it — the same contrast, from the other side.
 */
export type SyllableGridMode = "hear" | "vowel";

/** One cell of the grid: a consonant row × a vowel column. */
export interface GridSyllable {
  /** Written form shown on the tile, uppercase (e.g. "VA", "CHÉ"). */
  text: string;
  /** Spoken form, lowercase for the TTS/VO (e.g. "va", "ché"). */
  sound: string;
  /** Its consonant row, uppercase ("V", "CH"). */
  consonant: string;
  /** Its vowel column, uppercase ("A" … "É"). */
  vowel: string;
}

export interface SyllableGridLevel {
  /** Distinct syllables drawn from the level's rows at the start of a run. */
  pick: number;
  /** How many of those come back a second time (spaced apart). */
  repeats: number;
  /** Tiles in a round: the answer + its distractors. */
  choices: number;
  /**
   * How many distractors come from the same VOWEL column (another consonant,
   * e.g. VA vs LA) instead of the same consonant row (VA vs VI). 0 on the first
   * levels — the vowel alone is the whole task — then the consonant joins in.
   * Ignored in `vowel` mode, where every tile is a vowel by construction.
   */
  column: number;
}

/** Sound-twins exercise ----------------------------------------------------*/
/** One written form of a sound family + the anchor word that owns it. */
export interface TwinGraphy {
  /** Uppercase tile text (e.g. "CO", "KO", "EAU"). */
  text: string;
  /** Spoken on this tile's success line (« Oui ! coq. ») — what tells twins apart. */
  word: string;
  emoji: string;
}

/**
 * A family = ONE spoken sound and every way the level writes it. The child
 * hears the sound and must find ALL the family's tiles among intruder graphies
 * drawn from the level's other families.
 */
export interface TwinFamily {
  /** Spoken sound, lowercase for the TTS/VO (e.g. "ko", "o", "an"). */
  sound: string;
  /** 2–4 same-sound written forms, each anchored to its own word. */
  graphies: TwinGraphy[];
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
/**
 * Reward weight of an exercise — the anti-farming knob. 0 = training exercise:
 * finishing pays the completion curve like any other row, but no accuracy
 * bonus exists there, so careful play is worth exactly what spam is.
 * 1–4 = how many bonus points a full first-try run earns on top of the
 * completion curve. See rewards.sessionReward.
 */
export type Difficulty = 0 | 1 | 2 | 3 | 4;

export interface ExerciseMeta {
  id: ExerciseId;
  name: string;
  emoji: string;
  levelCount: number;
  /** Reward weight (0 = training: the curve, never a bonus). Required: every new exercise
   *  must place itself in the economy, same deal as its ExerciseIcon. */
  difficulty: Difficulty;
  /** Extra hub chip when the name alone doesn't say what to do (parent-facing). */
  hint?: string;
  /** Syllable exercises carry the seeding mode; first-letter leaves it undefined. */
  mode?: SyllableMode;
  /** Syllable-grid drills carry which side of the grid they ask; others leave it undefined. */
  grid?: SyllableGridMode;
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
  | { kind: "pick" }
  /** Trial over. Reached only by tapping an exercise — never a startup wall. */
  | { kind: "paywall" };

/** Mascot + rewards --------------------------------------------------------*/
/* Shared contract for the mascot / points / customization feature. Agents A   */
/* (design), B (earn+dashboard) and C (spend+customize) all build against this. */
/* Do not fork these shapes; add agent-local types in agent-owned files.       */

export type Species = "unicorn" | "cat" | "fox" | "rabbit" | "dragon";

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

/* Sync-safe counters (v4) ----------------------------------------------------*/
/* One child plays on Dad's phone, Mum's phone and the iPad. Two devices can    */
/* earn stars for the SAME child while both are offline, so a plain `number`    */
/* cannot merge: last-write-wins silently eats one device's stars. Every value  */
/* that can change on two devices at once is therefore stored per device and    */
/* folded back into the flat number the UI reads (see Profile below).          */

/**
 * `deviceId` → a count that only ever grows on that device. Because a device
 * only ever increments its OWN key, merging two replicas is per-key `max` and
 * is lossless, commutative and idempotent — sync in any order, any number of
 * times, same result.
 */
export type Counter = Record<string, number>;

/**
 * Stars as a PN-counter: two grow-only halves, balance = Σearned − Σspent.
 * Never store a running total — a total cannot be merged.
 */
export interface StarCounters {
  /** deviceId → stars ever earned on it. */
  earned: Counter;
  /** deviceId → stars ever spent on it. */
  spent: Counter;
}

/** ledgerKey() → per-device clear counts. Merged per device, then summed. */
export type ClearCounters = Record<string, Counter>;

/**
 * Stamp for a genuinely last-write-wins field (cosmetics only — losing one is
 * harmless). `at` is Date.now(); `by` breaks ties deterministically so two
 * devices merging in opposite orders still agree.
 */
export interface Rev {
  at: number;
  by: string;
}

/** One mascot's own progress. Kept forever — switching never discards it. */
export interface SpeciesProgress {
  /** This mascot's current look (stage, colours, styles, accessories). */
  config: MascotConfig;
  /** Option ids bought FOR THIS SPECIES (unlocked, may or may not be equipped). */
  owned: string[];
  /** LWW stamp for `config`. `owned` needs none — it's a grow-only set. */
  rev: Rev;
}

/**
 * The persisted child profile — what storage.ts reads/writes.
 *
 * Progress is split by ownership: growth/look/items live PER SPECIES (each
 * mascot remembers itself), while stars and cleared-levels belong to the CHILD
 * and survive every mascot switch.
 *
 * Every field here is mergeable across devices, by construction:
 *   chosen  grow-only boolean (OR)     stars   PN-counter
 *   current LWW via currentRev         clears  per-key counters
 *   species per-species merge (config LWW, owned grow-only set)
 * Nothing is a bare running total. See sync/merge.ts.
 */
export interface PersistedProfile {
  /** Has a species been picked yet (first-run gate). Never goes back to false. */
  chosen: boolean;
  /** The active mascot. */
  current: Species;
  /** LWW stamp for `current` — which mascot you last picked is cosmetic. */
  currentRev: Rev;
  /** Per-species progress; the child can switch back anytime, nothing is lost. */
  species: Record<Species, SpeciesProgress>;
  /** Stars — GLOBAL to the child, survives switches. Fold with balanceOf(). */
  stars: StarCounters;
  /** Cleared (exercise, level) counts — GLOBAL to the child. Fold with ledgerOf(). */
  clears: ClearCounters;
}

/**
 * Runtime profile exposed by useProfile: the persisted shape plus flat mirrors
 * the UI reads directly — the CURRENT species' `config`/`owned`, and the two
 * counter folds. Callers keep reading `profile.balance` / `profile.ledger` /
 * `profile.config` / `profile.owned` as plain values; only this hook, storage
 * and sync/merge ever see the counters underneath.
 */
export interface Profile extends PersistedProfile {
  /** = species[current].config */
  config: MascotConfig;
  /** = species[current].owned */
  owned: string[];
  /** = balanceOf(stars) — spendable stars, floored at 0. */
  balance: number;
  /** = ledgerOf(clears) — clears per (exercise, level), summed over devices. */
  ledger: CompletionLedger;
}

/**
 * One child on this device. Siblings share the tablet; each keeps their own
 * mascots, stars and progress. The child's avatar is their current mascot (or
 * the owl, until they've picked a species).
 */
export interface ChildProfile {
  id: string;
  /**
   * The child's first name. DEVICE-LOCAL: the sync transport strips it, so the
   * server only ever holds opaque ids and integers. A device that joins the
   * household asks the parent « Qui est-ce ? » instead of receiving a name.
   */
  name: string;
  /** LWW stamp for `name`, so a rename still merges between local replicas. */
  nameRev: Rev;
  /**
   * Date.now() of the last write to this child, anywhere. Only the delete rule
   * reads it: a tombstone wins only if nothing happened to the child after it,
   * so tidying the roster on one phone can't erase a week of play on another.
   */
  touchedAt: number;
  profile: PersistedProfile;
}

/** Everyone who plays on this device + who's currently at the wheel. */
export interface Roster {
  children: ChildProfile[];
  /**
   * The child now playing; null shows the "Qui joue ?" welcome screen.
   * DEVICE-LOCAL and never merged — who holds this tablet says nothing about
   * who holds the other one.
   */
  activeId: string | null;
  /**
   * childId → when it was deleted. Without tombstones a delete cannot win: the
   * other device still has the child and would resurrect them on next merge.
   */
  removed: Record<string, number>;
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
  /**
   * Crop the drawing to this viewBox rect (in the 0–100 mascot space) instead of
   * the full body — used to ZOOM a preview onto a small part/accessory so it fills
   * the tile. Requires `preview` (clips overflow). Square rects avoid distortion.
   */
  focus?: { x: number; y: number; w: number; h: number };
}
