// Port of the letter-form matching ladder from `src/levels.ts`.

/* -------------------------------------------------------------------------- */
/* Letter-form matching — 4 explicit levels, shared by both kinds (case+script) */
/* Difficulty is one axis: the letter pool grows each level (6 → 13 → 19 → 26)   */
/* + one more distractor tile, ending on the full alphabet.                     */
/* Each run MIXES both directions (upper→lower AND lower→upper; print→cursive    */
/* AND cursive→print), so one session drills a form and "the opposite" together. */
/* -------------------------------------------------------------------------- */

/// The fixed, directional instruction lines — a small, bake-able VO set.
// NB: a STRUCT, not a `[String: String]`, because `Object.values()` order is
// iterated by `enumerateUtterances` and Swift's `Dictionary` is unordered.
// The declaration order below IS that iteration order.
public struct LetterMatchPrompts: Hashable, Sendable {
    public let toLower: String
    public let toUpper: String
    public let toCursive: String
    public let toPrint: String

    public init(toLower: String, toUpper: String, toCursive: String, toPrint: String) {
        self.toLower = toLower
        self.toUpper = toUpper
        self.toCursive = toCursive
        self.toPrint = toPrint
    }

    /// `Object.values(LETTER_MATCH_PROMPTS)` — declaration order, for the VO manifest.
    public var all: [String] { [toLower, toUpper, toCursive, toPrint] }
}

extension Levels {
    public static let letterMatchLevels: [LetterMatchLevel] = [
        // 6
        LetterMatchLevel(letters: ["A", "B", "D", "E", "G", "R"], pick: 5, repeats: 3, distractors: 2),
        // 13
        LetterMatchLevel(
            letters: ["A", "B", "C", "D", "E", "F", "G", "H", "N", "O", "R", "S", "T"],
            pick: 6,
            repeats: 3,
            distractors: 2
        ),
        // 19
        LetterMatchLevel(
            letters: [
                "A", "B", "C", "D", "E", "F", "G", "H", "I", "L",
                "M", "N", "O", "P", "Q", "R", "S", "T", "U",
            ],
            pick: 7,
            repeats: 3,
            distractors: 3
        ),
        // full alphabet (26)
        LetterMatchLevel(letters: nil, pick: 8, repeats: 4, distractors: 3),
    ]

    public static let letterMatchLevelCount: Int = letterMatchLevels.count

    // NB: module-private in the TS; public here so the UI can read a level's
    // distractor count without rebuilding a session. Behaviour is identical.
    public static func letterMatchLevel(_ level: Int) -> LetterMatchLevel {
        letterMatchLevels[min(max(level, 1), letterMatchLevelCount) - 1]
    }

    public static func letterMatchPool(_ level: Int) -> [String] {
        letterMatchLevel(level).letters ?? Content.letterMatchAlphabet
    }

    public static let letterMatchPrompts = LetterMatchPrompts(
        toLower: "Trouve la petite lettre.",
        toUpper: "Trouve la grande lettre.",
        toCursive: "Trouve la lettre attachée.",
        toPrint: "Trouve la lettre en script."
    )

    /// Which line to speak, read off the prompt→answer transform (never names the target).
    // NB: a script change WINS over a case change — the order of these two tests
    // is the behaviour.
    public static func letterMatchPrompt(_ prompt: LetterFace, _ answer: LetterFace) -> String {
        if prompt.script != answer.script {
            return answer.script == .cursive
                ? letterMatchPrompts.toCursive
                : letterMatchPrompts.toPrint
        }
        return answer.glyph == answer.glyph.uppercased()
            ? letterMatchPrompts.toUpper
            : letterMatchPrompts.toLower
    }

    /// The success line — names the letter only AFTER it's been matched by its shape.
    public static func letterMatchSuccess(_ base: String) -> String {
        "Oui ! \(base)."
    }

    /**
     * One run of match rounds. Direction is randomised per round (both `case`
     * directions, both `script` directions), so a single session practises a form
     * and its opposite. The correct tile is always the counterpart of `prompt`.
     */
    public static func buildLetterMatchSession(
        kind: LetterMatchKind,
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [LetterMatchRound] {
        let cfg = letterMatchLevel(level)
        let catalog = letterMatchPool(level)
        return repeatSession(catalog, pick: cfg.pick, repeats: cfg.repeats, rng).map { base in
            let distractors = rng.shuffled(catalog.filter { $0 != base }).prefix(cfg.distractors)
            let pool = rng.shuffled([base] + Array(distractors))
            return kind == .case ? caseRound(base, pool, rng) : scriptRound(base, pool, rng)
        }
    }

    /// upper⇄lower, both plain print: prompt one case, every tile the other.
    private static func caseRound(
        _ base: String,
        _ pool: [String],
        _ rng: RandomSource
    ) -> LetterMatchRound {
        let promptUpper = rng.bool()
        func face(_ l: String, _ upper: Bool) -> LetterFace {
            LetterFace(base: l, glyph: upper ? l : l.lowercased(), script: .print)
        }
        return LetterMatchRound(
            prompt: face(base, promptUpper),
            choices: pool.map { face($0, !promptUpper) }
        )
    }

    /// print⇄cursive at ONE shared case: prompt one script, every tile the other.
    // NB: DRAW ORDER — `upper` is drawn BEFORE `promptCursive`. Under a seed that
    // order is the round; do not reorder them.
    private static func scriptRound(
        _ base: String,
        _ pool: [String],
        _ rng: RandomSource
    ) -> LetterMatchRound {
        let upper = rng.bool()  // prompt + tiles share this case
        let promptCursive = rng.bool()
        func face(_ l: String, _ cursive: Bool) -> LetterFace {
            LetterFace(
                base: l,
                glyph: upper ? l : l.lowercased(),
                script: cursive ? .cursive : .print
            )
        }
        return LetterMatchRound(
            prompt: face(base, promptCursive),
            choices: pool.map { face($0, !promptCursive) }
        )
    }
}
