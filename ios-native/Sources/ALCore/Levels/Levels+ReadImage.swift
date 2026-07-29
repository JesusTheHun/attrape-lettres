// Port of the read-the-word ladder from `src/levels.ts`.

/* -------------------------------------------------------------------------- */
/* Read-the-word — 4 explicit levels                                          */
/* The mirror of first-letter: the WORD is shown, the child reads it and taps  */
/* the matching picture. Reading IS the task, so the word is never spoken; the  */
/* only difficulty axis is how many pictures crowd the choice (2 → 4 distractors */
/* around the answer). Draws from the same curated LETTER_WORDS nouns.          */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let readImageLevels: [ReadImageLevel] = [
        ReadImageLevel(pick: 5, repeats: 3, distractors: 2),
        ReadImageLevel(pick: 6, repeats: 3, distractors: 2),
        ReadImageLevel(pick: 7, repeats: 4, distractors: 3),
        ReadImageLevel(pick: 8, repeats: 4, distractors: 4),
    ]

    public static let readImageLevelCount: Int = readImageLevels.count

    public static func readImageLevel(_ level: Int) -> ReadImageLevel {
        readImageLevels[min(max(level, 1), readImageLevelCount) - 1]
    }

    /// The picture pool: every curated noun. Reading practice, so all levels see all.
    public static func readImagePool() -> [LetterWord] {
        Content.letterWords
    }

    /**
     * One read-the-word round: the target plus `distractors` other words, shuffled.
     * Distractors are picked by DISTINCT emoji so no two tiles ever show the same
     * picture (a few nouns share a glyph), which would make the choice ambiguous.
     */
    // NB: it shuffles the WHOLE `Content.letterWords`, not the level pool (there
    // is only one pool). Seeding `seen` with the target's emoji is also what
    // stops the target being picked a second time.
    public static func buildReadImageRound(
        target: LetterWord,
        distractors: Int,
        _ rng: RandomSource = .system()
    ) -> ReadImageRound {
        var seen: Set<String> = [target.emoji]
        var pool: [LetterWord] = []
        for w in rng.shuffled(Content.letterWords) {
            if seen.contains(w.emoji) { continue }
            seen.insert(w.emoji)
            pool.append(w)
            if pool.count >= distractors { break }
        }
        return ReadImageRound(target: target, choices: rng.shuffled([target] + pool))
    }

    public static func buildReadImageSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [ReadImageRound] {
        let cfg = readImageLevel(level)
        return repeatSession(readImagePool(), pick: cfg.pick, repeats: cfg.repeats, rng)
            .map { buildReadImageRound(target: $0, distractors: cfg.distractors, rng) }
    }

    /// What the child hears: never the word (that would give the answer away).
    public static let readImagePrompt: String = "Trouve la bonne image."
}
