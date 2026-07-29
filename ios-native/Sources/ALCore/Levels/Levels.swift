// Port of `src/levels.ts` — the difficulty ladders, the pools, the round
// builders and the hub catalog. This file holds the namespace, the catalog and
// the four hint dictionaries; each ladder lives in its own `Levels+…` file.
//
// Naming rule, applied without exception (data-core.md §2.1): a TS module-level
// `SCREAMING_SNAKE` constant becomes a `lowerCamel` `static let` on this
// namespace; a TS module-level `camelCase` function keeps its name as a
// `static func`. `GRID_PROMPT` is the ONE forced rename — TS has both the
// dictionary and `gridPrompt()`, and Swift cannot hold both on one namespace,
// so the dictionary is `Levels.gridConsigne`.
//
// INVARIANT 5 is structural here: `Levels` does not import the persistence
// module, no level struct has an `unlocked`/`requires` field, and no pool or
// builder consults a profile. Every level is reachable, always.
//
// D9: every builder that consumes randomness takes a `RandomSource` as its last
// positional parameter, defaulted to a FRESH `.system()` — never a shared
// global. See `Support/RandomSource.swift` for why, and for the SwiftUI
// `@State` trap that makes a global source actively harmful.

public enum Levels {}

/* -------------------------------------------------------------------------- */
/* Hub catalog                                                                */
/* -------------------------------------------------------------------------- */

extension Levels {
    // `difficulty` is the reward weight (see rewards.sessionReward): 0 = training,
    // pays nothing; 1–4 = bonus points a full first-try run earns. It rises with
    // the hub progression so the point-optimal strategy is climbing, not farming.
    //
    // NB: the array ORDER is the hub order and is behaviour. The only ordering
    // the TypeScript itself pins is the grid drills' placement (its
    // "hub placement of the grid drills" block), so that is the only ordering
    // asserted here — a full golden order test would freeze something the source
    // deliberately leaves loose.
    //
    // NB: the gradient is NOT actually monotone, and it is ported as-is.
    // `fill-blank` (d0) sits after `find-sound` (d1); `read-image` (d2) sits
    // before `match-case` (d1). CLAUDE.md's "keep the gradient monotone" is the
    // intent, the shipped data is the behaviour, and behaviour is frozen.
    // Whoever later "fixes" the data changes the economy.
    public static let exercises: [ExerciseMeta] = [
        ExerciseMeta(
            id: .firstLetter, name: "La première lettre", emoji: "🔤",
            levelCount: firstLetterLevels.count, difficulty: .d0
        ),
        // Trouve le son sits this early with difficulty 1 ON PURPOSE (a deliberate
        // bump in the gradient): it's the youngest player's exercise, and 1 is what
        // lets a pre-reader earn shop stars at all. Farming still doesn't pay — the
        // bonus needs first-try rounds, and spam only ever gets the bare curve.
        ExerciseMeta(
            id: .findSound, name: "Trouve le son", emoji: "👂",
            levelCount: findSoundLevelCount, difficulty: .d1,
            hint: "Écoute le son, tape son écriture"
        ),
        // The combinatoire rungs — consonne + voyelle, drilled row by row. They sit
        // BEFORE every word exercise on purpose: fusing VA / VE / VI / VO / VU / VÉ is
        // the step between knowing letters and reading. Two drills, one grid: hear the
        // syllable and find it written, then find just the vowel that finishes it.
        ExerciseMeta(
            id: .hearSyllable, name: "Écoute la syllabe", emoji: "🔊",
            levelCount: syllableGridLevelCount, difficulty: .d1,
            hint: "VA, VE, VI… trouve celle que tu entends", grid: .hear
        ),
        ExerciseMeta(
            id: .pickVowel, name: "La bonne voyelle", emoji: "🅰️",
            levelCount: syllableGridLevelCount, difficulty: .d1,
            hint: "La consonne est écrite — pose la voyelle", grid: .vowel
        ),
        ExerciseMeta(
            id: .fillBlank, name: "Complète le mot", emoji: "🧩",
            levelCount: syllableLevelCount, difficulty: .d0, mode: .fillBlank
        ),
        ExerciseMeta(
            id: .orderSyllables, name: "Range les syllabes", emoji: "🔀",
            levelCount: syllableLevelCount, difficulty: .d1, mode: .order
        ),
        ExerciseMeta(
            id: .findIntruder, name: "Trouve l’intrus", emoji: "🕵️",
            levelCount: syllableLevelCount, difficulty: .d1, mode: .orderDistractor
        ),
        ExerciseMeta(
            id: .spellSound, name: "Fabrique le son", emoji: "🎧",
            levelCount: soundLevelCount, difficulty: .d2
        ),
        ExerciseMeta(
            id: .spellSyllable, name: "Écris la syllabe", emoji: "✏️",
            levelCount: spellSyllableLevelCount, difficulty: .d2, spell: .lettersExact
        ),
        ExerciseMeta(
            id: .spellSyllablePlus, name: "La syllabe et les intrus", emoji: "🎯",
            levelCount: spellSyllableLevelCount, difficulty: .d2, spell: .lettersExtra
        ),
        ExerciseMeta(
            id: .spellTwoSyllables, name: "Écris deux syllabes", emoji: "📝",
            levelCount: spellSyllableLevelCount, difficulty: .d3, spell: .lettersTwo
        ),
        ExerciseMeta(
            id: .readImage, name: "Lis le mot", emoji: "🖼️",
            levelCount: readImageLevelCount, difficulty: .d2
        ),
        ExerciseMeta(
            id: .matchCase, name: "Grande et petite lettre", emoji: "🔠",
            levelCount: letterMatchLevelCount, difficulty: .d1, match: .case
        ),
        ExerciseMeta(
            id: .matchScript, name: "Lettres attachées", emoji: "✍️",
            levelCount: letterMatchLevelCount, difficulty: .d2, match: .script
        ),
        // The mapping capstone of the sound ladder (find-sound = recognition,
        // spell-sound = production): one heard sound, MANY written forms to find.
        ExerciseMeta(
            id: .soundTwins, name: "Les syllabes jumelles", emoji: "👯",
            levelCount: twinLevelCount, difficulty: .d3,
            hint: "Trouve toutes les écritures du son"
        ),
        // The "écritures mêlées" twins: the two intruder spellers again, but now the
        // word takes one of three writings and the tray mixes forms — same letter in
        // the wrong case/script is a trap. They come LAST, after the child has met
        // majuscule↔minuscule (match-case) and l'attaché (match-script) on their own.
        ExerciseMeta(
            id: .spellSyllablePlusMixed, name: "La syllabe et les intrus mêlés",
            emoji: "🎭",
            levelCount: spellSyllableLevelCount, difficulty: .d4,
            spell: .lettersExtra, mixed: true
        ),
        ExerciseMeta(
            id: .spellTwoSyllablesMixed, name: "Deux syllabes, écritures mêlées",
            emoji: "🖋️",
            levelCount: spellSyllableLevelCount, difficulty: .d4,
            spell: .lettersTwo, mixed: true
        ),
    ]

    /// Reward weight by exercise — `exercises` is the single authority.
    // NB: the `.d0` fallback is unreachable once the catalog covers every case
    // (a test asserts it does), but it is the documented "not in the catalog pays
    // nothing" behaviour and stays.
    public static func exerciseDifficulty(_ id: ExerciseId) -> Difficulty {
        exercises.first(where: { $0.id == id })?.difficulty ?? .d0
    }

    /// Extra hub chip for the "écritures mêlées" twins — names the twist plainly.
    public static let mixedHint: String = "GRANDE, petite ou attachée — trouve la bonne"

    public static let modeHint: [SyllableMode: String] = [
        .fillBlank: "Trouve la syllabe manquante",
        .order: "Remets les syllabes dans l’ordre",
        .orderDistractor: "Range le mot… et évite l’intrus !",
    ]

    public static let matchHint: [LetterMatchKind: String] = [
        .case: "Associe majuscule et minuscule",
        .script: "Associe le script et l’attaché",
    ]

    public static let spellHint: [SpellSyllableMode: String] = [
        .lettersExact: "Range les lettres de la syllabe",
        .lettersExtra: "Range les lettres… évite les intrus",
        .lettersTwo: "Complète les deux syllabes",
    ]
}
