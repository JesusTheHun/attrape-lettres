// Port of the letter half of `src/types.ts` + the level shapes from
// `src/levels.ts` + `faceLabel` from `src/letterForms.ts`.
//
// `SCRIPT_FONT` deliberately does NOT come along: it is a CSS font stack and
// belongs to the view layer (data-core.md §3.4). ALCore keeps `LetterScript`.

/** First-letter exercise ---------------------------------------------------*/
public struct LetterWord: Hashable, Sendable {
    public var letter: String
    public var word: String
    public var emoji: String
    /**
     * Optional dedicated illustration (imported asset URL) shown INSTEAD of `emoji`
     * when the emoji misrepresents the word (e.g. no true "igloo"/"jupe" glyph).
     * `emoji` is kept as the a11y/text fallback. See WordIcon.
     */
    // NB: in TS this is the Vite-imported SVG URL. ALCore must not know about
    // bundles or SwiftUI, so it holds an `ImageKey` and ALArt resolves it.
    public var img: ImageKey?

    public init(letter: String, word: String, emoji: String, img: ImageKey? = nil) {
        self.letter = letter
        self.word = word
        self.emoji = emoji
        self.img = img
    }
}

public struct FirstLetterLevel: Hashable, Sendable {
    /// First-letter catalog for this level. `nil` = full catalog.
    public var letters: [String]?
    /// Distinct words drawn from the pool at the start of a run.
    public var pick: Int
    /// How many of those words come back a second time (spaced apart).
    public var repeats: Int

    public init(letters: [String]?, pick: Int, repeats: Int) {
        self.letters = letters
        self.pick = pick
        self.repeats = repeats
    }
}

public struct FirstLetterRound: Hashable, Sendable {
    public var target: LetterWord
    /// The target letter plus distractors, shuffled.
    public var choices: [String]

    public init(target: LetterWord, choices: [String]) {
        self.target = target
        self.choices = choices
    }
}

/** Letter-form matching exercise -------------------------------------------*/
/**
 * One engine, two skills: pair a letter with its counterpart FORM. `case` pairs a
 * majuscule with its minuscule (both directions in one run); `script` pairs a
 * printed (sans-serif) letter with its cursive "attaché" twin at the SAME case.
 * The underlying letter is the identity; only the rendered form flips.
 */
public enum LetterMatchKind: String, CaseIterable, Hashable, Codable, Sendable {
    case `case`
    case script
}

/// How a letter is drawn on a tile.
public enum LetterScript: String, CaseIterable, Hashable, Codable, Sendable {
    case print
    case cursive
}

/// One rendered letter: the same underlying letter, shown in a given case + script.
public struct LetterFace: Hashable, Sendable {
    /// Canonical UPPERCASE letter — identity (pick match) + the name spoken by VO.
    public var base: String
    /// The exact glyph to render, already cased (e.g. "A" or "a").
    public var glyph: String
    public var script: LetterScript

    public init(base: String, glyph: String, script: LetterScript) {
        self.base = base
        self.glyph = glyph
        self.script = script
    }
}

public struct LetterMatchLevel: Hashable, Sendable {
    /// Letter catalog (uppercase). `nil` = the full alphabet.
    public var letters: [String]?
    /// Distinct letters drawn from the pool at the start of a run.
    public var pick: Int
    /// How many of those come back a second time (spaced apart).
    public var repeats: Int
    /// Wrong-answer tiles added beside the correct counterpart.
    public var distractors: Int

    public init(letters: [String]?, pick: Int, repeats: Int, distractors: Int) {
        self.letters = letters
        self.pick = pick
        self.repeats = repeats
        self.distractors = distractors
    }
}

public struct LetterMatchRound: Hashable, Sendable {
    /// The letter shown big; the child finds its counterpart form below.
    public var prompt: LetterFace
    /// Tiles, shuffled — all in the counterpart form; exactly one shares prompt.base.
    public var choices: [LetterFace]

    public init(prompt: LetterFace, choices: [LetterFace]) {
        self.prompt = prompt
        self.choices = choices
    }
}

/// Screen-reader label: names the letter, its case, and (cursive only) its form.
///
/// Invariant 6 lives here: this is the `aria-label` a letter tile carries, and it
/// is unit-tested in ALCore rather than left to the view layer where nothing
/// would test it.
public func faceLabel(_ face: LetterFace) -> String {
    // NB: non-locale `uppercased()` on purpose. `uppercased(with: Locale.current)`
    // in a Turkish locale maps "i" to "İ", which would flip majuscule/minuscule.
    let caseWord = face.glyph == face.glyph.uppercased() ? "majuscule" : "minuscule"
    let scriptWord = face.script == .cursive ? " attachée" : ""
    return "Lettre \(face.base) \(caseWord)\(scriptWord)"
}
