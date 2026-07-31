// Port of the build-syllables ladder from `src/levels.ts` — ONE engine, three
// modes; the mode only reaches `buildSyllableRound`.

/* -------------------------------------------------------------------------- */
/* Build-syllables — 12 derived levels                                        */
/* phase cycles every 3; tier (difficulty) rises every 3.                     */
/* -------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------- */
/* Build-syllables — one 4-level difficulty ladder, shared by all 3 modes.     */
/* Level == tier: no phase math, difficulty is the only axis.                  */
/* -------------------------------------------------------------------------- */

// NB: both comment blocks above are in the TypeScript, one immediately after the
// other — the first describes the ladder this file no longer has. Copied as-is
// (behaviour frozen includes not editorialising the source); the SECOND one is
// the one that is true.

extension Levels {
    public static let syllableTiers: [SyllableTier] = [
        SyllableTier(minSyllables: 2, maxSyllables: 2, pick: 6, repeats: 3),
        SyllableTier(minSyllables: 2, maxSyllables: 3, pick: 7, repeats: 3),
        SyllableTier(minSyllables: 3, maxSyllables: 3, pick: 6, repeats: 3),
        SyllableTier(minSyllables: 3, maxSyllables: 4, pick: 8, repeats: 4),
    ]

    public static let syllableLevelCount: Int = syllableTiers.count

    public static func syllableTier(_ level: Int) -> SyllableTier {
        syllableTiers[min(max(level, 1), syllableTiers.count) - 1]
    }

    public static func syllablePool(_ tier: SyllableTier) -> [SyllableWord] {
        Content.syllableWords.filter {
            $0.syllables.count >= tier.minSyllables && $0.syllables.count <= tier.maxSyllables
        }
    }

    /// A wrong-answer syllable that is not one of the word's own.
    // NB: the fallback is `Content.syllableBank[0]` (today "CHA"), which is only
    // stable because the bank goes through `orderedUnique`. The bank's ORDER is
    // also the distribution, since the pick is by index.
    public static func pickDistractorSyllable(
        exclude: Set<String>,
        _ rng: RandomSource = .system()
    ) -> String {
        let options = Content.syllableBank.filter { !exclude.contains($0) }
        return rng.element(of: options) ?? Content.syllableBank[0]
    }

    public static func buildSyllableRound(
        word: SyllableWord,
        mode: SyllableMode,
        _ rng: RandomSource = .system(),
        ids: TileIDAllocator = .shared
    ) -> SyllableRound {
        let syl = word.syllables

        if mode == .fillBlank {
            let missing = rng.int(below: syl.count)
            let slots: [String?] = syl.enumerated().map { i, s in i == missing ? nil : s }
            let locked: [Bool] = syl.indices.map { $0 != missing }
            let distractor = pickDistractorSyllable(exclude: Set(syl), rng)
            let tray = rng.shuffled([syl[missing], distractor])
                .map { SyllableTile(id: ids.next(), syllable: $0) }
            return SyllableRound(word: word, slots: slots, locked: locked, tray: tray)
        }

        let slots: [String?] = syl.map { _ in nil }
        let locked: [Bool] = syl.map { _ in false }
        let trayValues =
            mode == .orderDistractor
            ? syl + [pickDistractorSyllable(exclude: Set(syl), rng)]
            : syl
        return SyllableRound(
            word: word,
            slots: slots,
            locked: locked,
            tray: rng.shuffled(trayValues).map { SyllableTile(id: ids.next(), syllable: $0) }
        )
    }
}
