// Port of the find-the-sound ladder from `src/levels.ts`.

/* -------------------------------------------------------------------------- */
/* Find-the-sound — 4 explicit levels                                         */
/* The 4yo rung: hear « <sound>, comme dans <mot> », tap the graphy. Pools are */
/* authored per level (BASIC_SOUNDS); only pick/repeats/distractors live here. */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let findSoundLevels: [FindSoundLevel] = [
        FindSoundLevel(pick: 5, repeats: 2, distractors: 1),
        FindSoundLevel(pick: 6, repeats: 3, distractors: 2),
        FindSoundLevel(pick: 6, repeats: 3, distractors: 2),
        FindSoundLevel(pick: 7, repeats: 3, distractors: 2),
    ]

    public static let findSoundLevelCount: Int = findSoundLevels.count

    private static func findSoundIdx(_ level: Int) -> Int {
        min(max(level, 1), findSoundLevelCount) - 1
    }

    public static func findSoundLevel(_ level: Int) -> FindSoundLevel {
        findSoundLevels[findSoundIdx(level)]
    }

    public static func findSoundPool(_ level: Int) -> [BasicSound] {
        Content.basicSounds[findSoundIdx(level)]
    }

    /**
     * One round: the target + `distractors` other pool entries. A distractor is
     * never a homophone of the answer (same `sound`), or a correct ear would be
     * told "wrong". Authored `traps` (confusable neighbours) are preferred; the
     * rest of the pool fills any remainder.
     */
    // NB: the TS `rest` filter is `!traps.includes(e)` — REFERENCE identity.
    // Filtering on `graphy` is exactly equivalent here (`byGraphy` is keyed by
    // graphy, and a level's graphies are unique — `levels.test.ts` asserts it)
    // and it does not depend on `BasicSound` value equality.
    // NB: the trap LIST itself is shuffled before it is resolved.
    public static func buildFindSoundRound(
        target: BasicSound,
        pool: [BasicSound],
        distractors: Int,
        _ rng: RandomSource = .system()
    ) -> FindSoundRound {
        let candidates = pool.filter { $0.sound != target.sound && $0.graphy != target.graphy }
        var byGraphy: [String: BasicSound] = [:]
        for e in candidates { byGraphy[e.graphy] = e }
        let traps = rng.shuffled(target.traps ?? []).compactMap { byGraphy[$0] }
        let trapGraphies = Set(traps.map(\.graphy))
        let rest = rng.shuffled(candidates.filter { !trapGraphies.contains($0.graphy) })
        var picked: [BasicSound] = []
        var seen: Set<String> = [target.graphy]
        for e in traps + rest {
            if picked.count >= distractors { break }
            if seen.contains(e.graphy) { continue }
            seen.insert(e.graphy)
            picked.append(e)
        }
        return FindSoundRound(target: target, choices: rng.shuffled([target] + picked))
    }

    public static func buildFindSoundSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [FindSoundRound] {
        let cfg = findSoundLevel(level)
        let pool = findSoundPool(level)
        return repeatSession(pool, pick: cfg.pick, repeats: cfg.repeats, rng)
            .map { buildFindSoundRound(target: $0, pool: pool, distractors: cfg.distractors, rng) }
    }

    /// What the child hears: the sound anchored to its word — never the spelling.
    public static func findSoundPrompt(_ t: BasicSound) -> String {
        "\(t.sound), comme dans \(t.word)."
    }

    /// The success line once the graphy is tapped.
    public static func findSoundSuccess(_ t: BasicSound) -> String {
        "Oui ! \(t.word)."
    }
}
