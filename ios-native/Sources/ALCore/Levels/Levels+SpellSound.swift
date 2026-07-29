// Port of the spell-the-sound ladder from `src/levels.ts`.

/* -------------------------------------------------------------------------- */
/* Spell-the-sound — 5 explicit levels                                        */
/* Each level is a pool (SOUND_TARGETS) + how many intruder letters to add.    */
/* Difficulty is authored in content; only the distractor count lives here.    */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let soundLevels: [SoundLevel] = [
        SoundLevel(distractors: 0),
        SoundLevel(distractors: 2),
        SoundLevel(distractors: 2),
        SoundLevel(distractors: 3),
        SoundLevel(distractors: 3),
    ]

    public static let soundLevelCount: Int = soundLevels.count
    /// Distinct sounds drawn from the pool at the start of a run.
    public static let soundPick: Int = 8
    /// How many of those distinct sounds come back a second time (spaced apart).
    public static let soundRepeats: Int = 4
    /// Rounds in a full run: the 8 picks, plus a replay of 4 of them.
    public static let soundSessionLength: Int = soundPick + soundRepeats

    private static func clampSoundLevel(_ level: Int) -> Int {
        min(max(level, 1), soundLevelCount) - 1
    }

    public static func soundLevel(_ level: Int) -> SoundLevel {
        soundLevels[clampSoundLevel(level)]
    }

    public static func soundPool(_ level: Int) -> [SoundTarget] {
        Content.soundTargets[clampSoundLevel(level)]
    }

    public static func buildSoundSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [SoundTarget] {
        repeatSession(soundPool(level), pick: soundPick, repeats: soundRepeats, rng)
    }

    public static func buildSoundRound(
        target: SoundTarget,
        distractors: Int,
        _ rng: RandomSource = .system(),
        ids: TileIDAllocator = .shared
    ) -> SoundRound {
        let need = Set(target.spelling)
        // NB: a `prefix`, not a precondition — asking for more intruders than the
        // bank holds simply yields fewer, and the round stays valid.
        let intruders = rng.shuffled(Content.soundLetterBank.filter { !need.contains($0) })
            .prefix(distractors)
        return SoundRound(
            target: target,
            slots: target.spelling.map { _ in nil },
            tray: rng.shuffled(target.spelling + Array(intruders))
                .map { SoundTile(id: ids.next(), letter: $0) }
        )
    }

    /// What the child hears: the bare sound, or "sound, comme dans word." on levels with context.
    public static func soundPrompt(_ t: SoundTarget) -> String {
        if let word = t.word { return "\(t.sound), comme dans \(word)." }
        return t.sound
    }

    /// The success line spoken once the letters are all placed.
    public static func soundSuccess(_ t: SoundTarget) -> String {
        "Oui ! \(t.word ?? t.sound)."
    }
}
