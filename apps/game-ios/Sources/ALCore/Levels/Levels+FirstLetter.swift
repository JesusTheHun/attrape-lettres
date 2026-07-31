// Port of the first-letter ladder from `src/levels.ts`, plus `buildSession`
// lifted out of `src/exercises/FirstLetterExercise.tsx` (ARCHITECTURE.md §6.5):
// it is pure, and it is the one session shape a host test could not otherwise
// reach. Behaviour unchanged, hard-coded 2 distractors kept.

/* -------------------------------------------------------------------------- */
/* First-letter — 5 explicit levels                                           */
/* The catalog drives everything: more letters ⇒ a longer word list. Level 1  */
/* stays tiny on purpose so the same handful of words recur and stick.        */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let firstLetterLevels: [FirstLetterLevel] = [
        FirstLetterLevel(letters: ["A", "B", "C", "M", "P"], pick: 5, repeats: 3),
        FirstLetterLevel(letters: ["A", "B", "C", "D", "M", "P", "R", "S", "T"], pick: 6, repeats: 3),
        FirstLetterLevel(
            letters: ["A", "B", "C", "D", "F", "L", "M", "N", "P", "R", "S", "T"],
            pick: 7,
            repeats: 4
        ),
        FirstLetterLevel(
            letters: [
                "A", "B", "C", "D", "F", "G", "H", "L", "M",
                "N", "O", "P", "R", "S", "T", "V",
            ],
            pick: 8,
            repeats: 4
        ),
        FirstLetterLevel(letters: nil, pick: 8, repeats: 4),  // full catalog
    ]

    /// The level's word pool.
    // NB: UNCLAMPED, on purpose. The TS is `FIRST_LETTER_LEVELS[level - 1]`, so
    // `level = 0` or `6` throws a TypeError there and traps here. Every other
    // ladder clamps; this one does not, and adding a clamp would be a behaviour
    // change. Nothing the router can produce is out of range.
    public static func firstLetterPool(_ level: Int) -> [LetterWord] {
        let cfg = firstLetterLevels[level - 1]
        guard let letters = cfg.letters else { return Content.letterWords }
        let allowed = Set(letters)
        return Content.letterWords.filter { allowed.contains($0.letter) }
    }

    /**
     * One run of first-letter rounds. Pick distinct words, replay a few (spaced)
     * to reinforce — never back-to-back.
     */
    // NB: the 2 distractors are HARD-CODED and are not part of `FirstLetterLevel`.
    // That is how the TSX had it; keep it hard-coded.
    // NB: `[...new Set(pool.map(w => w.letter))]` is insertion-ordered in JS, so
    // it goes through `orderedUnique` — a Swift `Set` here would randomise the
    // distractor catalog's order per process, i.e. per-process nondeterminism
    // UNDER A FIXED SEED.
    public static func buildFirstLetterSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [FirstLetterRound] {
        let cfg = firstLetterLevels[level - 1]
        let pool = firstLetterPool(level)
        let catalog = cfg.letters ?? orderedUnique(pool.map(\.letter))
        return repeatSession(pool, pick: cfg.pick, repeats: cfg.repeats, rng).map { target in
            let distractors = rng.shuffled(catalog.filter { $0 != target.letter }).prefix(2)
            return FirstLetterRound(
                target: target,
                choices: rng.shuffled([target.letter] + Array(distractors))
            )
        }
    }
}
