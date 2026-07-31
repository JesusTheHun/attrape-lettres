package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.domain.FindSoundLevel
import fr.dappit.attrapelettres.core.domain.FirstLetterLevel
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchLevel
import fr.dappit.attrapelettres.core.domain.ReadImageLevel
import fr.dappit.attrapelettres.core.domain.SoundLevel
import fr.dappit.attrapelettres.core.domain.SpellSyllableLevel
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableGridLevel
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableTier
import fr.dappit.attrapelettres.core.domain.TwinLevel

// Port of the LADDER SPINE and the hub catalog from `src/levels.ts`.
//
// This file holds the nine difficulty ladders, their level-count constants,
// their clamping accessors, and `EXERCISES` — the hub catalog. The pools, the
// round builders and the spoken lines live one file per exercise family beside
// this one (`Levels_FirstLetter.kt`, `Levels_ReadImage.kt`, …), exactly as the
// iOS port splits its `Levels` extensions. The split is only a file split:
// everything is top-level in the SAME package, so a builder file reads
// `SOUND_LEVELS` directly and needs no namespace object.
//
// Naming rule: a TS module-level SCREAMING_SNAKE constant keeps that spelling,
// and a TS module-level camelCase function keeps its name. Kotlin tells
// `GRID_PROMPT` and `gridPrompt` apart by case, so the one rename Swift was
// forced into (a Swift namespace cannot hold both spellings, hence
// `gridConsigne`) is not needed here. The only rename in this file is the
// TS-private `clampLevel`, which is hard-wired to the spell-the-sound ladder
// despite its ladder-agnostic name and becomes `soundIdx` now that it is a
// package-level symbol shared with eight other ladders.
//
// INVARIANT 5 is structural here: this package does not import
// `core.persistence`, no level type has an `unlocked` / `requires` field, and no
// ladder, accessor or pool consults a profile. Every level is reachable from the
// hub, always, and the missing import is the proof. Do not add it.
//
// INVARIANT 8 lives half here and half in `core.rewards`: rewards owns the only
// function that hands out points, and this file owns the `difficulty` each row
// feeds it. A row without one does not compile — `ExerciseMeta.difficulty` has
// no default — which is the same forcing move the icon catalog's exhaustive
// `when` makes for invariant 7.

/**
 * The shared 1-based level → 0-based ladder index clamp, written once because
 * five ladders repeat it verbatim in the TypeScript
 * (`Math.min(Math.max(level, 1), COUNT) - 1`).
 *
 * Clamping rather than trapping is deliberate and is invariant 3 applied to
 * navigation: a level number that is somehow out of range yields the nearest
 * real level instead of a crash. First-letter is the ONE ladder that does not
 * go through here: the TS indexes `FIRST_LETTER_LEVELS[level - 1]` raw, so an
 * out-of-range level throws there and must throw here — adding a clamp to
 * `firstLetterPool` would be a behaviour change, and nothing the router can
 * produce is out of range.
 */
fun levelIndex(level: Int, levelCount: Int): Int = minOf(maxOf(level, 1), levelCount) - 1

/* -------------------------------------------------------------------------- */
/* First-letter — 5 explicit levels                                           */
/* The catalog drives everything: more letters ⇒ a longer word list. Level 1  */
/* stays tiny on purpose so the same handful of words recur and stick.        */
/* -------------------------------------------------------------------------- */

val FIRST_LETTER_LEVELS: List<FirstLetterLevel> = listOf(
    FirstLetterLevel(letters = listOf("A", "B", "C", "M", "P"), pick = 5, repeats = 3),
    FirstLetterLevel(
        letters = listOf("A", "B", "C", "D", "M", "P", "R", "S", "T"),
        pick = 6,
        repeats = 3,
    ),
    FirstLetterLevel(
        letters = listOf("A", "B", "C", "D", "F", "L", "M", "N", "P", "R", "S", "T"),
        pick = 7,
        repeats = 4,
    ),
    FirstLetterLevel(
        letters = listOf(
            "A", "B", "C", "D", "F", "G", "H", "L", "M",
            "N", "O", "P", "R", "S", "T", "V",
        ),
        pick = 8,
        repeats = 4,
    ),
    // full catalog
    FirstLetterLevel(letters = null, pick = 8, repeats = 4),
)

/* -------------------------------------------------------------------------- */
/* Read-the-word — 4 explicit levels                                          */
/* The mirror of first-letter: the WORD is shown, the child reads it and taps */
/* the matching picture. Reading IS the task, so the word is never spoken; the  */
/* only difficulty axis is how many pictures crowd the choice (2 → 4 distractors */
/* around the answer). Draws from the same curated LETTER_WORDS nouns.        */
/* -------------------------------------------------------------------------- */

val READ_IMAGE_LEVELS: List<ReadImageLevel> = listOf(
    ReadImageLevel(pick = 5, repeats = 3, distractors = 2),
    ReadImageLevel(pick = 6, repeats = 3, distractors = 2),
    ReadImageLevel(pick = 7, repeats = 4, distractors = 3),
    ReadImageLevel(pick = 8, repeats = 4, distractors = 4),
)

val READ_IMAGE_LEVEL_COUNT: Int = READ_IMAGE_LEVELS.size

fun readImageLevel(level: Int): ReadImageLevel =
    READ_IMAGE_LEVELS[levelIndex(level, READ_IMAGE_LEVEL_COUNT)]

/* -------------------------------------------------------------------------- */
/* Letter-form matching — 4 explicit levels, shared by both kinds (case+script) */
/* Difficulty is one axis: the letter pool grows each level (6 → 13 → 19 → 26)   */
/* + one more distractor tile, ending on the full alphabet.                   */
/* Each run MIXES both directions (upper→lower AND lower→upper; print→cursive */
/* AND cursive→print), so one session drills a form and "the opposite" together. */
/* -------------------------------------------------------------------------- */

val LETTER_MATCH_LEVELS: List<LetterMatchLevel> = listOf(
    // 6
    LetterMatchLevel(
        letters = listOf("A", "B", "D", "E", "G", "R"),
        pick = 5,
        repeats = 3,
        distractors = 2,
    ),
    // 13
    LetterMatchLevel(
        letters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "N", "O", "R", "S", "T"),
        pick = 6,
        repeats = 3,
        distractors = 2,
    ),
    // 19
    LetterMatchLevel(
        letters = listOf(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "L",
            "M", "N", "O", "P", "Q", "R", "S", "T", "U",
        ),
        pick = 7,
        repeats = 3,
        distractors = 3,
    ),
    // full alphabet (26)
    LetterMatchLevel(letters = null, pick = 8, repeats = 4, distractors = 3),
)

val LETTER_MATCH_LEVEL_COUNT: Int = LETTER_MATCH_LEVELS.size

// NB: module-private in the TS; public here, like the Swift port, so the hub can
// read a level's distractor count without building a session. Behaviour identical.
fun letterMatchLevel(level: Int): LetterMatchLevel =
    LETTER_MATCH_LEVELS[levelIndex(level, LETTER_MATCH_LEVEL_COUNT)]

/* -------------------------------------------------------------------------- */
/* Build-syllables — 12 derived levels                                        */
/* phase cycles every 3; tier (difficulty) rises every 3.                     */
/* -------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------- */
/* Build-syllables — one 4-level difficulty ladder, shared by all 3 modes.    */
/* Level == tier: no phase math, difficulty is the only axis.                 */
/* -------------------------------------------------------------------------- */

// NB: both banners above are in the TypeScript, one immediately after the other
// — the first describes a ladder the file no longer has. Copied as-is, like the
// Swift port did: "behaviour is frozen" includes not editorialising the source.
// The SECOND banner is the one that is true.

val SYLLABLE_TIERS: List<SyllableTier> = listOf(
    SyllableTier(minSyllables = 2, maxSyllables = 2, pick = 6, repeats = 3),
    SyllableTier(minSyllables = 2, maxSyllables = 3, pick = 7, repeats = 3),
    SyllableTier(minSyllables = 3, maxSyllables = 3, pick = 6, repeats = 3),
    SyllableTier(minSyllables = 3, maxSyllables = 4, pick = 8, repeats = 4),
)

val SYLLABLE_LEVEL_COUNT: Int = SYLLABLE_TIERS.size

fun syllableTier(level: Int): SyllableTier =
    SYLLABLE_TIERS[levelIndex(level, SYLLABLE_LEVEL_COUNT)]

/* -------------------------------------------------------------------------- */
/* Spell-the-sound — 5 explicit levels                                        */
/* Each level is a pool (SOUND_TARGETS) + how many intruder letters to add.   */
/* Difficulty is authored in content; only the distractor count lives here.   */
/* -------------------------------------------------------------------------- */

val SOUND_LEVELS: List<SoundLevel> = listOf(
    SoundLevel(distractors = 0),
    SoundLevel(distractors = 2),
    SoundLevel(distractors = 2),
    SoundLevel(distractors = 3),
    SoundLevel(distractors = 3),
)

val SOUND_LEVEL_COUNT: Int = SOUND_LEVELS.size

/** Distinct sounds drawn from the pool at the start of a run. */
const val SOUND_PICK: Int = 8

/** How many of those distinct sounds come back a second time (spaced apart). */
const val SOUND_REPEATS: Int = 4

/** Rounds in a full run: the 8 picks, plus a replay of 4 of them. */
const val SOUND_SESSION_LENGTH: Int = SOUND_PICK + SOUND_REPEATS

/**
 * The spell-the-sound ladder index. The TS spells this `clampLevel`, a name that
 * reads as ladder-agnostic but is hard-wired to `SOUND_LEVEL_COUNT`; renamed
 * here to say which ladder it indexes, because in Kotlin it is a package-level
 * symbol shared with the other eight.
 */
fun soundIdx(level: Int): Int = levelIndex(level, SOUND_LEVEL_COUNT)

fun soundLevel(level: Int): SoundLevel = SOUND_LEVELS[soundIdx(level)]

/* -------------------------------------------------------------------------- */
/* Find-the-sound — 4 explicit levels                                         */
/* The 4yo rung: hear « <sound>, comme dans <mot> », tap the graphy. Pools are */
/* authored per level (BASIC_SOUNDS); only pick/repeats/distractors live here. */
/* -------------------------------------------------------------------------- */

val FIND_SOUND_LEVELS: List<FindSoundLevel> = listOf(
    FindSoundLevel(pick = 5, repeats = 2, distractors = 1),
    FindSoundLevel(pick = 6, repeats = 3, distractors = 2),
    FindSoundLevel(pick = 6, repeats = 3, distractors = 2),
    FindSoundLevel(pick = 7, repeats = 3, distractors = 2),
)

val FIND_SOUND_LEVEL_COUNT: Int = FIND_SOUND_LEVELS.size

fun findSoundIdx(level: Int): Int = levelIndex(level, FIND_SOUND_LEVEL_COUNT)

fun findSoundLevel(level: Int): FindSoundLevel = FIND_SOUND_LEVELS[findSoundIdx(level)]

/* -------------------------------------------------------------------------- */
/* Syllable grid — 8 explicit levels, ONE ladder shared by both drills.       */
/* The « tableau des syllabes »: level = one consonant row × every vowel (the */
/* rows are authored in content), so the ONLY thing that ever changes inside a */
/* level is the vowel. Difficulty is two knobs: how many tiles are on screen, */
/* and how many of them swap the consonant instead of the vowel.              */
/* -------------------------------------------------------------------------- */

val SYLLABLE_GRID_LEVELS: List<SyllableGridLevel> = listOf(
    SyllableGridLevel(pick = 6, repeats = 3, choices = 3, column = 0),
    SyllableGridLevel(pick = 6, repeats = 3, choices = 4, column = 0),
    SyllableGridLevel(pick = 7, repeats = 3, choices = 4, column = 1),
    SyllableGridLevel(pick = 7, repeats = 3, choices = 5, column = 1),
    SyllableGridLevel(pick = 8, repeats = 4, choices = 5, column = 1),
    SyllableGridLevel(pick = 8, repeats = 4, choices = 6, column = 2),
    SyllableGridLevel(pick = 8, repeats = 4, choices = 6, column = 2),
    // révision: tout le tableau
    SyllableGridLevel(pick = 8, repeats = 4, choices = 6, column = 2),
)

val SYLLABLE_GRID_LEVEL_COUNT: Int = SYLLABLE_GRID_LEVELS.size

fun gridIdx(level: Int): Int = levelIndex(level, SYLLABLE_GRID_LEVEL_COUNT)

fun syllableGridLevel(level: Int): SyllableGridLevel = SYLLABLE_GRID_LEVELS[gridIdx(level)]

/* -------------------------------------------------------------------------- */
/* Sound-twins — 4 explicit levels                                            */
/* Hear one sound, find ALL the tiles that write it. Families are authored    */
/* (TWIN_FAMILIES); intruders come from the level's OTHER families, so every  */
/* tile the child can audition has a real sound and a real anchor word.       */
/* -------------------------------------------------------------------------- */

val TWIN_LEVELS: List<TwinLevel> = listOf(
    TwinLevel(pick = 5, repeats = 2, distractors = 2),
    TwinLevel(pick = 5, repeats = 2, distractors = 2),
    TwinLevel(pick = 4, repeats = 2, distractors = 2),
    TwinLevel(pick = 5, repeats = 2, distractors = 3),
)

val TWIN_LEVEL_COUNT: Int = TWIN_LEVELS.size

fun twinIdx(level: Int): Int = levelIndex(level, TWIN_LEVEL_COUNT)

fun twinLevel(level: Int): TwinLevel = TWIN_LEVELS[twinIdx(level)]

/* -------------------------------------------------------------------------- */
/* Fill-a-syllable — one 4-level ladder, shared by all 3 siblings.            */
/* The word list is level == index (content); only the tray/gap count changes */
/* per mode. Level 1 is deliberately 5 words for a quick, repeated win.       */
/* -------------------------------------------------------------------------- */

val SPELL_SYLLABLE_LEVELS: List<SpellSyllableLevel> = listOf(
    SpellSyllableLevel(pick = 5, repeats = 3, distractors = 2),
    SpellSyllableLevel(pick = 6, repeats = 3, distractors = 2),
    SpellSyllableLevel(pick = 7, repeats = 3, distractors = 3),
    SpellSyllableLevel(pick = 8, repeats = 4, distractors = 3),
)

val SPELL_SYLLABLE_LEVEL_COUNT: Int = SPELL_SYLLABLE_LEVELS.size

fun spellSyllableIdx(level: Int): Int = levelIndex(level, SPELL_SYLLABLE_LEVEL_COUNT)

fun spellSyllableLevel(level: Int): SpellSyllableLevel =
    SPELL_SYLLABLE_LEVELS[spellSyllableIdx(level)]

/* -------------------------------------------------------------------------- */
/* Hub catalog                                                                */
/* -------------------------------------------------------------------------- */

// `difficulty` is the reward weight (see rewards.sessionReward): 0 = training,
// which pays the completion curve but never a bonus; 1–4 = bonus points a full
// first-try run earns. It rises with the hub progression so the point-optimal
// strategy is climbing, not farming.
//
// NB: the list ORDER is the hub order and is behaviour — a row's position places
// it in the economy. The only ordering the TypeScript's own suite pins is the
// grid drills' placement (its "hub placement of the grid drills" block), so that
// is the ordering asserted here; a full golden-order test would freeze something
// the source deliberately leaves loose.
//
// NB: the gradient is not strictly monotone, and it is ported exactly as
// shipped. Three rows weigh less than the row above them — `fill-blank` (0)
// after `find-sound` (1), `read-image` (2) after `spell-two-syllables` (3), and
// `match-case` (1) after `read-image` (2). CLAUDE.md's "keep the gradient
// monotone" is the intent; the shipped data is the behaviour, and behaviour is
// frozen. What invariant 8 actually requires still holds exactly, and is tested:
// the best a training row can pay is the worst a paying row can pay. Whoever
// later "fixes" these three numbers changes the economy on one platform only.
//
// NB: `emoji` is data on the row, not what the hub draws. Invariant 7 says every
// exercise gets an original drawn pictogram, so `:art` renders its own icon
// catalog keyed by `ExerciseId` and this field stays a fallback/label.
val EXERCISES: List<ExerciseMeta> = listOf(
    ExerciseMeta(
        id = ExerciseId.FIRST_LETTER,
        name = "La première lettre",
        emoji = "🔤",
        levelCount = FIRST_LETTER_LEVELS.size,
        difficulty = Difficulty(0),
    ),
    // Trouve le son sits this early with difficulty 1 ON PURPOSE (a deliberate
    // bump in the gradient): it's the youngest player's exercise, and 1 is what
    // lets a pre-reader earn shop stars at all. Farming still doesn't pay — the
    // bonus needs first-try rounds, and spam only ever gets the bare curve.
    ExerciseMeta(
        id = ExerciseId.FIND_SOUND,
        name = "Trouve le son",
        emoji = "👂",
        levelCount = FIND_SOUND_LEVEL_COUNT,
        difficulty = Difficulty(1),
        hint = "Écoute le son, tape son écriture",
    ),
    // The combinatoire rungs — consonne + voyelle, drilled row by row. They sit
    // BEFORE every word exercise on purpose: fusing VA / VE / VI / VO / VU / VÉ is
    // the step between knowing letters and reading. Two drills, one grid: hear the
    // syllable and find it written, then find just the vowel that finishes it.
    ExerciseMeta(
        id = ExerciseId.HEAR_SYLLABLE,
        name = "Écoute la syllabe",
        emoji = "🔊",
        levelCount = SYLLABLE_GRID_LEVEL_COUNT,
        difficulty = Difficulty(1),
        hint = "VA, VE, VI… trouve celle que tu entends",
        grid = SyllableGridMode.HEAR,
    ),
    ExerciseMeta(
        id = ExerciseId.PICK_VOWEL,
        name = "La bonne voyelle",
        emoji = "🅰️",
        levelCount = SYLLABLE_GRID_LEVEL_COUNT,
        difficulty = Difficulty(1),
        hint = "La consonne est écrite — pose la voyelle",
        grid = SyllableGridMode.VOWEL,
    ),
    ExerciseMeta(
        id = ExerciseId.FILL_BLANK,
        name = "Complète le mot",
        emoji = "🧩",
        levelCount = SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(0),
        mode = SyllableMode.FILL_BLANK,
    ),
    ExerciseMeta(
        id = ExerciseId.ORDER_SYLLABLES,
        name = "Range les syllabes",
        emoji = "🔀",
        levelCount = SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(1),
        mode = SyllableMode.ORDER,
    ),
    ExerciseMeta(
        id = ExerciseId.FIND_INTRUDER,
        name = "Trouve l’intrus",
        emoji = "🕵️",
        levelCount = SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(1),
        mode = SyllableMode.ORDER_DISTRACTOR,
    ),
    ExerciseMeta(
        id = ExerciseId.SPELL_SOUND,
        name = "Fabrique le son",
        emoji = "🎧",
        levelCount = SOUND_LEVEL_COUNT,
        difficulty = Difficulty(2),
    ),
    ExerciseMeta(
        id = ExerciseId.SPELL_SYLLABLE,
        name = "Écris la syllabe",
        emoji = "✏️",
        levelCount = SPELL_SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(2),
        spell = SpellSyllableMode.LETTERS_EXACT,
    ),
    ExerciseMeta(
        id = ExerciseId.SPELL_SYLLABLE_PLUS,
        name = "La syllabe et les intrus",
        emoji = "🎯",
        levelCount = SPELL_SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(2),
        spell = SpellSyllableMode.LETTERS_EXTRA,
    ),
    ExerciseMeta(
        id = ExerciseId.SPELL_TWO_SYLLABLES,
        name = "Écris deux syllabes",
        emoji = "📝",
        levelCount = SPELL_SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(3),
        spell = SpellSyllableMode.LETTERS_TWO,
    ),
    ExerciseMeta(
        id = ExerciseId.READ_IMAGE,
        name = "Lis le mot",
        emoji = "🖼️",
        levelCount = READ_IMAGE_LEVEL_COUNT,
        difficulty = Difficulty(2),
    ),
    ExerciseMeta(
        id = ExerciseId.MATCH_CASE,
        name = "Grande et petite lettre",
        emoji = "🔠",
        levelCount = LETTER_MATCH_LEVEL_COUNT,
        difficulty = Difficulty(1),
        match = LetterMatchKind.CASE,
    ),
    ExerciseMeta(
        id = ExerciseId.MATCH_SCRIPT,
        name = "Lettres attachées",
        emoji = "✍️",
        levelCount = LETTER_MATCH_LEVEL_COUNT,
        difficulty = Difficulty(2),
        match = LetterMatchKind.SCRIPT,
    ),
    // The mapping capstone of the sound ladder (find-sound = recognition,
    // spell-sound = production): one heard sound, MANY written forms to find.
    ExerciseMeta(
        id = ExerciseId.SOUND_TWINS,
        name = "Les syllabes jumelles",
        emoji = "👯",
        levelCount = TWIN_LEVEL_COUNT,
        difficulty = Difficulty(3),
        hint = "Trouve toutes les écritures du son",
    ),
    // The "écritures mêlées" twins: the two intruder spellers again, but now the
    // word takes one of three writings and the tray mixes forms — same letter in
    // the wrong case/script is a trap. They come LAST, after the child has met
    // majuscule↔minuscule (match-case) and l'attaché (match-script) on their own.
    ExerciseMeta(
        id = ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
        name = "La syllabe et les intrus mêlés",
        emoji = "🎭",
        levelCount = SPELL_SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(4),
        spell = SpellSyllableMode.LETTERS_EXTRA,
        mixed = true,
    ),
    ExerciseMeta(
        id = ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
        name = "Deux syllabes, écritures mêlées",
        emoji = "🖋️",
        levelCount = SPELL_SYLLABLE_LEVEL_COUNT,
        difficulty = Difficulty(4),
        spell = SpellSyllableMode.LETTERS_TWO,
        mixed = true,
    ),
)

/**
 * Reward weight by exercise — [EXERCISES] is the single authority.
 *
 * The [Difficulty.TRAINING] fallback is unreachable while the catalog covers
 * every `ExerciseId` (a test asserts it does), but it is the documented "not in
 * the catalog pays nothing" behaviour of the TS `find(...)` and it stays: the
 * safe way to be missing from the economy is to earn nothing, not to crash a
 * child's session.
 */
fun exerciseDifficulty(id: ExerciseId): Difficulty =
    EXERCISES.firstOrNull { it.id == id }?.difficulty ?: Difficulty.TRAINING

/** Extra hub chip for the "écritures mêlées" twins — names the twist plainly. */
const val MIXED_HINT: String = "GRANDE, petite ou attachée — trouve la bonne"

// The three hint dictionaries below are `Record<K, string>` in the TypeScript,
// where the compiler checks totality for free. A Kotlin `Map` cannot, so the
// tests assert each one covers its enum — that is the recovered guarantee, and a
// new mode must land in the map in the same change or the suite goes red.

val MODE_HINT: Map<SyllableMode, String> = mapOf(
    SyllableMode.FILL_BLANK to "Trouve la syllabe manquante",
    SyllableMode.ORDER to "Remets les syllabes dans l’ordre",
    SyllableMode.ORDER_DISTRACTOR to "Range le mot… et évite l’intrus !",
)

val MATCH_HINT: Map<LetterMatchKind, String> = mapOf(
    LetterMatchKind.CASE to "Associe majuscule et minuscule",
    LetterMatchKind.SCRIPT to "Associe le script et l’attaché",
)

val SPELL_HINT: Map<SpellSyllableMode, String> = mapOf(
    SpellSyllableMode.LETTERS_EXACT to "Range les lettres de la syllabe",
    SpellSyllableMode.LETTERS_EXTRA to "Range les lettres… évite les intrus",
    SpellSyllableMode.LETTERS_TWO to "Complète les deux syllabes",
)
