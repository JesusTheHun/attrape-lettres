// Port of the sound-twins types from `src/types.ts` + `src/levels.ts`.

/** Sound-twins exercise ----------------------------------------------------*/
/// One written form of a sound family + the anchor word that owns it.
public struct TwinGraphy: Hashable, Sendable {
    /// Uppercase tile text (e.g. "CO", "KO", "EAU").
    public var text: String
    /// Spoken on this tile's success line (« Oui ! coq. ») — what tells twins apart.
    public var word: String
    public var emoji: String

    public init(text: String, word: String, emoji: String) {
        self.text = text
        self.word = word
        self.emoji = emoji
    }
}

/**
 * A family = ONE spoken sound and every way the level writes it. The child
 * hears the sound and must find ALL the family's tiles among intruder graphies
 * drawn from the level's other families.
 */
public struct TwinFamily: Hashable, Sendable {
    /// Spoken sound, lowercase for the TTS/VO (e.g. "ko", "o", "an").
    public var sound: String
    /// 2–4 same-sound written forms, each anchored to its own word.
    public var graphies: [TwinGraphy]

    public init(sound: String, graphies: [TwinGraphy]) {
        self.sound = sound
        self.graphies = graphies
    }
}

public struct TwinLevel: Hashable, Sendable {
    /// Distinct families drawn from the level pool at the start of a run.
    public var pick: Int
    /// How many of those come back a second time (spaced apart).
    public var repeats: Int
    /// Wrong-family graphy tiles mixed into the round.
    public var distractors: Int

    public init(pick: Int, repeats: Int, distractors: Int) {
        self.pick = pick
        self.repeats = repeats
        self.distractors = distractors
    }
}

public struct TwinTile: Hashable, Identifiable, Sendable {
    public var id: Int
    /// Uppercase graphy shown on the tile.
    public var text: String
    /// The sound THIS tile's own family spells — what "Écouter" speaks.
    public var sound: String
    /// This graphy's anchor word (success line for correct tiles).
    public var word: String
    public var emoji: String
    /// True = belongs to the round's family; false = intruder.
    public var correct: Bool

    public init(id: Int, text: String, sound: String, word: String, emoji: String, correct: Bool) {
        self.id = id
        self.text = text
        self.sound = sound
        self.word = word
        self.emoji = emoji
        self.correct = correct
    }
}

public struct TwinRound: Hashable, Sendable {
    public var family: TwinFamily
    /// Family graphies + intruders, shuffled.
    public var tiles: [TwinTile]

    public init(family: TwinFamily, tiles: [TwinTile]) {
        self.family = family
        self.tiles = tiles
    }
}
