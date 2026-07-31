// Port of `src/types.ts` — the nav/routing key, the reward weight, and the two
// tiny UI vocabularies (mood, verdict).
//
// D11: `ExerciseId`'s RAW VALUES ARE FROZEN. `Rewards.ledgerKey` builds
// "\(rawValue):\(level)" and that string is the key of the per-device clear
// counters — on disk and on the sync wire. Renaming a raw value orphans every
// existing profile's history. Swift case names may be idiomatic; raw values may
// not change, ever.

/// Every exercise in the hub. Declaration order is `allCases` order and is
/// pinned by a golden test; the raw values are a persistence contract (D11).
public enum ExerciseId: String, CaseIterable, Hashable, Codable, Sendable {
    case firstLetter = "first-letter"
    case findSound = "find-sound"
    case hearSyllable = "hear-syllable"
    case pickVowel = "pick-vowel"
    case soundTwins = "sound-twins"
    case readImage = "read-image"
    case matchCase = "match-case"
    case matchScript = "match-script"
    case fillBlank = "fill-blank"
    case orderSyllables = "order-syllables"
    case findIntruder = "find-intruder"
    case spellSyllable = "spell-syllable"
    case spellSyllablePlus = "spell-syllable-plus"
    case spellTwoSyllables = "spell-two-syllables"
    case spellSyllablePlusMixed = "spell-syllable-plus-mixed"
    case spellTwoSyllablesMixed = "spell-two-syllables-mixed"
    case spellSound = "spell-sound"
}

public enum Mood: String, CaseIterable, Hashable, Codable, Sendable {
    case idle
    case happy
    case cheer
}

public enum Verdict: String, CaseIterable, Hashable, Codable, Sendable {
    case accept
    case reject
}

/**
 * Reward weight of an exercise — the anti-farming knob. 0 = training exercise:
 * finishing pays the completion curve like any other row, but no accuracy
 * bonus exists there, so careful play is worth exactly what spam is.
 * 1–4 = how many bonus points a full first-try run earns on top of the
 * completion curve. See rewards.sessionReward.
 */
// NB: TS models this as `0 | 1 | 2 | 3 | 4`, a literal union — `difficulty: 5`
// is a compile error there. An Int-raw-value enum is the only Swift shape that
// keeps that error, so the case names carry no invented semantics: `d0` is NOT
// named "training" even though the comment above says that is what it means.
public enum Difficulty: Int, CaseIterable, Hashable, Codable, Sendable {
    case d0 = 0
    case d1
    case d2
    case d3
    case d4

    /// The number itself. `sessionReward` multiplies by this.
    public var weight: Int { rawValue }
}
