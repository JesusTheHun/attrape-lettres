// Port of the « tableau des syllabes » ladder from `src/levels.ts` — ONE engine,
// two drills (`hear` / `vowel`) over the same grid.

/* -------------------------------------------------------------------------- */
/* Syllable grid — 8 explicit levels, ONE ladder shared by both drills.        */
/* The « tableau des syllabes »: level = one consonant row × every vowel (the  */
/* rows are authored in content), so the ONLY thing that ever changes inside a */
/* level is the vowel. Difficulty is two knobs: how many tiles are on screen,  */
/* and how many of them swap the consonant instead of the vowel.               */
/* -------------------------------------------------------------------------- */

extension Levels {
    public static let syllableGridLevels: [SyllableGridLevel] = [
        SyllableGridLevel(pick: 6, repeats: 3, choices: 3, column: 0),
        SyllableGridLevel(pick: 6, repeats: 3, choices: 4, column: 0),
        SyllableGridLevel(pick: 7, repeats: 3, choices: 4, column: 1),
        SyllableGridLevel(pick: 7, repeats: 3, choices: 5, column: 1),
        SyllableGridLevel(pick: 8, repeats: 4, choices: 5, column: 1),
        SyllableGridLevel(pick: 8, repeats: 4, choices: 6, column: 2),
        SyllableGridLevel(pick: 8, repeats: 4, choices: 6, column: 2),
        SyllableGridLevel(pick: 8, repeats: 4, choices: 6, column: 2),  // révision: tout le tableau
    ]

    public static let syllableGridLevelCount: Int = syllableGridLevels.count

    private static func gridIdx(_ level: Int) -> Int {
        min(max(level, 1), syllableGridLevelCount) - 1
    }

    public static func syllableGridLevel(_ level: Int) -> SyllableGridLevel {
        syllableGridLevels[gridIdx(level)]
    }

    /// One cell of the grid. The spoken form is just the written one, lowercased.
    // NB: non-locale `lowercased()`. "CHÉ" must lowercase to "ché" everywhere.
    public static func gridSyllable(_ consonant: String, _ vowel: String) -> GridSyllable {
        let text = consonant + vowel
        return GridSyllable(
            text: text,
            sound: text.lowercased(),
            consonant: consonant,
            vowel: vowel
        )
    }

    /// The level's rows, expanded to every syllable they hold (row order, then vowel order).
    public static func syllableGridPool(_ level: Int) -> [GridSyllable] {
        let rows = Content.syllableGridRows[gridIdx(level)] ?? Content.gridConsonants
        return rows.flatMap { c in Content.gridVowels.map { v in gridSyllable(c, v) } }
    }

    /**
     * One round's tiles. `vowel` mode is the pure drill: the same consonant, the
     * other vowels, nothing else. `hear` mode starts there too and, from level 3,
     * swaps `column` of the distractors for the SAME vowel on another consonant
     * (VA vs LA) — so a child who only hears the vowel starts having to read the
     * consonant as well. Tiles are deduped by text; a short pool just yields a
     * smaller (still valid) round.
     */
    // NB: in `vowel` mode `cfg.column` is IGNORED, and no dedupe is needed — the
    // row's cells are unique by construction.
    public static func buildGridRound(
        target: GridSyllable,
        pool: [GridSyllable],
        cfg: SyllableGridLevel,
        mode: SyllableGridMode,
        _ rng: RandomSource = .system()
    ) -> GridRound {
        let need = cfg.choices - 1
        let sameRow = rng.shuffled(
            pool.filter { $0.consonant == target.consonant && $0.vowel != target.vowel }
        )
        if mode == .vowel {
            return GridRound(
                target: target,
                choices: rng.shuffled([target] + Array(sameRow.prefix(need)))
            )
        }

        let sameColumn = Array(
            rng.shuffled(
                pool.filter { $0.vowel == target.vowel && $0.consonant != target.consonant }
            ).prefix(min(cfg.column, need))
        )
        var picked: [GridSyllable] = []
        var seen: Set<String> = [target.text]
        for s in sameColumn + sameRow {
            if picked.count >= need { break }
            if seen.contains(s.text) { continue }
            seen.insert(s.text)
            picked.append(s)
        }
        return GridRound(target: target, choices: rng.shuffled([target] + picked))
    }

    public static func buildSyllableGridSession(
        level: Int,
        mode: SyllableGridMode,
        _ rng: RandomSource = .system()
    ) -> [GridRound] {
        let cfg = syllableGridLevel(level)
        let pool = syllableGridPool(level)
        return repeatSession(pool, pick: cfg.pick, repeats: cfg.repeats, rng)
            .map { buildGridRound(target: $0, pool: pool, cfg: cfg, mode: mode, rng) }
    }

    /// What the child hears: the bare syllable — no word, no letter names.
    public static func gridPrompt(_ s: GridSyllable) -> String {
        s.sound
    }

    /// The success line: the syllable again, so the last thing heard is the answer.
    public static func gridSuccess(_ s: GridSyllable) -> String {
        "Oui ! \(s.sound)."
    }

    /// The on-screen consigne, per drill.
    // NB: the TS name is `GRID_PROMPT`. Renamed because `gridPrompt(_:)` above
    // already occupies that spelling on this namespace — the one forced rename in
    // the whole port (data-core.md §2.1).
    public static let gridConsigne: [SyllableGridMode: String] = [
        .hear: "Écoute la syllabe et trouve son écriture",
        .vowel: "Écoute la syllabe et trouve la voyelle qui manque",
    ]
}
