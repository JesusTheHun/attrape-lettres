// Port of the two sound ladders from `src/types.ts` + `src/levels.ts`:
// find-the-sound (recognition, the 4yo rung) and spell-the-sound (production).

/** Find-the-sound exercise -------------------------------------------------*/
/**
 * The youngest rung of the sound ladder (recognition; spell-sound is
 * production): the child HEARS a sound with its anchor word (« ou, comme dans
 * hibou ») and taps the tile that writes it. Same one-prompt/one-tile loop as
 * first-letter, so a pre-reader already knows how to play — no reading needed.
 */
public struct BasicSound: Hashable, Sendable {
    /// Spoken sound, lowercase for the TTS/VO (e.g. "ou", "or", "che").
    public var sound: String
    /// The written form shown on the tile, uppercase (e.g. "OU", "CH").
    public var graphy: String
    /// Anchor word the sound lives in — spoken as "comme dans …" + shown as emoji.
    public var word: String
    public var emoji: String
    /**
     * Authored confusable graphies (from the SAME level pool) preferred as
     * distractors — adaptive-by-confusability done as data (OU vs ON, AN vs IN…).
     */
    public var traps: [String]?

    public init(sound: String, graphy: String, word: String, emoji: String, traps: [String]? = nil) {
        self.sound = sound
        self.graphy = graphy
        self.word = word
        self.emoji = emoji
        self.traps = traps
    }
}

public struct FindSoundLevel: Hashable, Sendable {
    /// Distinct sounds drawn from the level pool at the start of a run.
    public var pick: Int
    /// How many of those come back a second time (spaced apart).
    public var repeats: Int
    /// Wrong-graphy tiles shown beside the correct one.
    public var distractors: Int

    public init(pick: Int, repeats: Int, distractors: Int) {
        self.pick = pick
        self.repeats = repeats
        self.distractors = distractors
    }
}

public struct FindSoundRound: Hashable, Sendable {
    public var target: BasicSound
    /// The target graphy plus distractor graphies, shuffled.
    public var choices: [BasicSound]

    public init(target: BasicSound, choices: [BasicSound]) {
        self.target = target
        self.choices = choices
    }
}

/** Spell-the-sound exercise ------------------------------------------------*/
/**
 * One heard sound the child must re-spell by picking letters in order. The point
 * of the ladder: the same `sound` gets several `spelling`s across rounds (o / au
 * / eau, f / ph…), so the child memorises that one sound has many written forms.
 */
public struct SoundTarget: Hashable, Sendable {
    /// Spoken syllable / phoneme, lowercase for the TTS (e.g. "lo", "fo", "oi").
    public var sound: String
    /// Ordered letter tiles that spell it, uppercase (e.g. ["L","O"], ["P","H","O"]).
    public var spelling: [String]
    /// Real word this spelling lives in — spoken as "comme dans …" + shown as emoji.
    public var word: String?
    /// Illustration for the context word.
    public var emoji: String?

    public init(sound: String, spelling: [String], word: String? = nil, emoji: String? = nil) {
        self.sound = sound
        self.spelling = spelling
        self.word = word
        self.emoji = emoji
    }
}

public struct SoundLevel: Hashable, Sendable {
    /// Wrong letter-tiles added to the tray (0 = only the needed letters).
    public var distractors: Int

    public init(distractors: Int) {
        self.distractors = distractors
    }
}

public struct SoundTile: Hashable, Identifiable, Sendable {
    public var id: Int
    public var letter: String

    public init(id: Int, letter: String) {
        self.id = id
        self.letter = letter
    }
}

public struct SoundRound: Hashable, Sendable {
    public var target: SoundTarget
    /// Target order; slots are filled against target.spelling.
    public var slots: [String?]
    public var tray: [SoundTile]

    public init(target: SoundTarget, slots: [String?], tray: [SoundTile]) {
        self.target = target
        self.slots = slots
        self.tray = tray
    }
}
