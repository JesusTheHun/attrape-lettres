// Port of the sound-twins ladder from `src/levels.ts`.

/* -------------------------------------------------------------------------- */
/* Sound-twins — 4 explicit levels                                            */
/* Hear one sound, find ALL the tiles that write it. Families are authored     */
/* (TWIN_FAMILIES); intruders come from the level's OTHER families, so every   */
/* tile the child can audition has a real sound and a real anchor word.        */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let twinLevels: [TwinLevel] = [
        TwinLevel(pick: 5, repeats: 2, distractors: 2),
        TwinLevel(pick: 5, repeats: 2, distractors: 2),
        TwinLevel(pick: 4, repeats: 2, distractors: 2),
        TwinLevel(pick: 5, repeats: 2, distractors: 3),
    ]

    public static let twinLevelCount: Int = twinLevels.count

    private static func twinIdx(_ level: Int) -> Int {
        min(max(level, 1), twinLevelCount) - 1
    }

    public static func twinLevel(_ level: Int) -> TwinLevel {
        twinLevels[twinIdx(level)]
    }

    public static func twinPool(_ level: Int) -> [TwinFamily] {
        Content.twinFamilies[twinIdx(level)]
    }

    /// A graphy carrying the sound of the family it came from — the TS
    /// `{ ...g, sound: f.sound }` anonymous shape, which Swift needs a name for.
    private struct SoundedGraphy {
        var text: String
        var word: String
        var emoji: String
        var sound: String
        var correct: Bool
    }

    /**
     * One round: every graphy of `family` (all must be found) + `distractors`
     * graphies from the level's other families. Intruders never spell the target
     * sound (families within a level have distinct sounds), and tile texts are
     * deduped so no two tiles read the same.
     */
    // NB: the tiles are shuffled FIRST and given their ids AFTER, so the ids
    // follow the shuffled order. That is what the TS does.
    public static func buildTwinRound(
        family: TwinFamily,
        pool: [TwinFamily],
        distractors: Int,
        _ rng: RandomSource = .system(),
        ids: TileIDAllocator = .shared
    ) -> TwinRound {
        let others = rng.shuffled(
            pool.filter { $0.sound != family.sound }.flatMap { f in
                f.graphies.map {
                    SoundedGraphy(
                        text: $0.text, word: $0.word, emoji: $0.emoji,
                        sound: f.sound, correct: false
                    )
                }
            }
        )
        var picked: [SoundedGraphy] = []
        var seen = Set(family.graphies.map(\.text))
        for g in others {
            if picked.count >= distractors { break }
            if seen.contains(g.text) { continue }
            seen.insert(g.text)
            picked.append(g)
        }
        let correct = family.graphies.map {
            SoundedGraphy(
                text: $0.text, word: $0.word, emoji: $0.emoji,
                sound: family.sound, correct: true
            )
        }
        let tiles = rng.shuffled(correct + picked).map { t in
            TwinTile(
                id: ids.next(),
                text: t.text,
                sound: t.sound,
                word: t.word,
                emoji: t.emoji,
                correct: t.correct
            )
        }
        return TwinRound(family: family, tiles: tiles)
    }

    public static func buildTwinSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [TwinRound] {
        let cfg = twinLevel(level)
        let pool = twinPool(level)
        return repeatSession(pool, pick: cfg.pick, repeats: cfg.repeats, rng)
            .map { buildTwinRound(family: $0, pool: pool, distractors: cfg.distractors, rng) }
    }

    /// What the child hears at round start — the sound to hunt, never a spelling.
    public static func twinPrompt(_ f: TwinFamily) -> String {
        "Trouve tous les \(f.sound) !"
    }

    /// Success line per found tile — anchors THIS spelling to its own word.
    public static func twinSuccess(_ g: TwinGraphy) -> String {
        "Oui ! \(g.word)."
    }
}
