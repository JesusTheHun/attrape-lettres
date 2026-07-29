// Port of the syllable half of `src/types.ts` + the round/level shapes from
// `src/levels.ts`. Covers both families that live on syllables: the assemble
// engine (`SyllableMode`) and the fill-a-syllable engine (`SpellSyllableMode`).

/** Build-syllables exercise ------------------------------------------------*/
public struct SyllableWord: Hashable, Sendable {
    public var word: String
    /// Pre-authored orthographic split, in reading order.
    public var syllables: [String]
    public var emoji: String
    /// Optional dedicated illustration shown instead of `emoji`. See LetterWord.img / WordIcon.
    public var img: ImageKey?

    public init(word: String, syllables: [String], emoji: String, img: ImageKey? = nil) {
        self.word = word
        self.syllables = syllables
        self.emoji = emoji
        self.img = img
    }
}

public enum SyllableMode: String, CaseIterable, Hashable, Codable, Sendable {
    case fillBlank = "fill-blank"
    case order
    case orderDistractor = "order-distractor"
}

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
public enum SpellSyllableMode: String, CaseIterable, Hashable, Codable, Sendable {
    case lettersExact = "letters-exact"
    case lettersExtra = "letters-extra"
    case lettersTwo = "letters-two"
}

public struct SyllableTier: Hashable, Sendable {
    public var minSyllables: Int
    public var maxSyllables: Int
    /// Distinct words drawn from the tier pool at the start of a run.
    public var pick: Int
    /// How many of those words come back a second time (spaced apart).
    public var repeats: Int

    public init(minSyllables: Int, maxSyllables: Int, pick: Int, repeats: Int) {
        self.minSyllables = minSyllables
        self.maxSyllables = maxSyllables
        self.pick = pick
        self.repeats = repeats
    }
}

public struct SyllableTile: Hashable, Identifiable, Sendable {
    public var id: Int
    public var syllable: String

    public init(id: Int, syllable: String) {
        self.id = id
        self.syllable = syllable
    }
}

public struct SyllableRound: Hashable, Sendable {
    public var word: SyllableWord
    /// Target order; slots are filled against this.
    // NB: `(string | null)[]` → `[String?]`, exactly. The engines index it and
    // write `nil` back, so do not "improve" it into an enum.
    public var slots: [String?]
    /// Which slots are pre-revealed and locked (fill-blank).
    public var locked: [Bool]
    public var tray: [SyllableTile]

    public init(word: SyllableWord, slots: [String?], locked: [Bool], tray: [SyllableTile]) {
        self.word = word
        self.slots = slots
        self.locked = locked
        self.tray = tray
    }
}

/** Fill-a-syllable levels + round ------------------------------------------*/
public struct SpellSyllableLevel: Hashable, Sendable {
    /// Distinct words drawn from the level's list at the start of a run.
    public var pick: Int
    /// How many of those words come back a second time (spaced apart).
    public var repeats: Int
    /// Intruder letters added to the tray for the -extra / -two modes (0 for exact).
    public var distractors: Int

    public init(pick: Int, repeats: Int, distractors: Int) {
        self.pick = pick
        self.repeats = repeats
        self.distractors = distractors
    }
}

public struct SpellCell: Hashable, Sendable {
    /// The correct letter at this position, canonical UPPERCASE (answer + VO).
    public var letter: String
    /// The exact glyph to render, cased for the round's form (e.g. "A"/"a"/"a").
    public var glyph: String
    /// print / cursive for the round's form (drives the font).
    public var script: LetterScript
    /// True = a slot the child fills; false = already written (locked).
    public var fill: Bool
    /// Index among fill cells in reading order, or -1 when shown.
    public var slotIndex: Int
    /// First letter of its syllable — used to gap-space the written word.
    public var syllableStart: Bool

    public init(
        letter: String,
        glyph: String,
        script: LetterScript,
        fill: Bool,
        slotIndex: Int,
        syllableStart: Bool
    ) {
        self.letter = letter
        self.glyph = glyph
        self.script = script
        self.fill = fill
        self.slotIndex = slotIndex
        self.syllableStart = syllableStart
    }
}

public struct SpellLetterTile: Hashable, Identifiable, Sendable {
    public var id: Int
    /// Canonical UPPERCASE letter — VO name + answer identity.
    public var letter: String
    /// The exact glyph to render (cased). In plain rounds glyph === letter.
    public var glyph: String
    public var script: LetterScript

    public init(id: Int, letter: String, glyph: String, script: LetterScript) {
        self.id = id
        self.letter = letter
        self.glyph = glyph
        self.script = script
    }
}

public struct SpellSyllableRound: Hashable, Sendable {
    public var word: SyllableWord
    /// The whole word, letter by letter: shown letters + the gap's slots, in order.
    public var cells: [SpellCell]
    /// The gap letters (base, uppercase) in reading order.
    public var answer: [String]
    /**
     * The gap FACES in reading order — what a filled slot must equal. In plain
     * rounds a face is just the uppercase print letter; in "mixed" rounds it also
     * pins the case + script, so the child must match the writing, not only the
     * letter.
     */
    public var answerFaces: [LetterFace]
    /// Shuffled letter tiles the child taps.
    public var tray: [SpellLetterTile]

    public init(
        word: SyllableWord,
        cells: [SpellCell],
        answer: [String],
        answerFaces: [LetterFace],
        tray: [SpellLetterTile]
    ) {
        self.word = word
        self.cells = cells
        self.answer = answer
        self.answerFaces = answerFaces
        self.tray = tray
    }
}
