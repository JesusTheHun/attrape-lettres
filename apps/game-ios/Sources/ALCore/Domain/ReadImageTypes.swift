// Port of the read-the-word types from `src/types.ts` + `src/levels.ts`, plus
// the `img` indirection every word table needs.

/**
 * The four dedicated word illustrations under `src/img/`. In TS these are Vite
 * asset URLs (`import jupe from "../img/jupe.svg"`); ALCore knows nothing about
 * bundles or SwiftUI, so it carries a KEY and ALArt owns the key → drawing
 * table.
 *
 * ALArt must resolve this with an exhaustive `switch`, never a dictionary — a
 * dictionary lets a new key compile and render nothing.
 */
public enum ImageKey: String, CaseIterable, Hashable, Codable, Sendable {
    case igloo
    case jupe
    case macaron
    case pyjama
}

/** Read-the-word exercise ---------------------------------------------------*/
/* The mirror of first-letter: the WORD is shown, the child reads it and taps  */
/* the matching picture. Reading IS the task, so the word is never spoken; the  */
/* only difficulty axis is how many pictures crowd the choice (2 → 4 distractors */
/* around the answer). Draws from the same curated LETTER_WORDS nouns.          */
public struct ReadImageLevel: Hashable, Sendable {
    /// Distinct words drawn from the pool at the start of a run.
    public var pick: Int
    /// How many of those words come back a second time (spaced apart).
    public var repeats: Int
    /// Wrong-picture tiles shown beside the correct one.
    public var distractors: Int

    public init(pick: Int, repeats: Int, distractors: Int) {
        self.pick = pick
        self.repeats = repeats
        self.distractors = distractors
    }
}

/// The written word is shown; the child taps the picture that matches it.
public struct ReadImageRound: Hashable, Sendable {
    public var target: LetterWord
    /// The target word plus distractor words, shuffled — each rendered as a picture.
    public var choices: [LetterWord]

    public init(target: LetterWord, choices: [LetterWord]) {
        self.target = target
        self.choices = choices
    }
}
