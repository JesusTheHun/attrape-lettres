// `GRID_VOWELS` + `SYLLABLE_GRID_ROWS` from `src/content.ts`.
//
// `GRID_CONSONANTS` is derived from `syllableGridRows` and lives with the other
// derived tables, in `Content.swift`.

extension Content {
    /**
     * Syllable-grid dataset — the « tableau des syllabes ». The exhaustive
     * consonant × vowel combinatoire (VA VE VI VO VU VÉ) that has to be automatic
     * BEFORE a child can spell a word: one consonant row per pair of levels' worth
     * of drilling, every vowel every time, nothing else ever added.
     *
     * Rows are ordered by how forgiving the consonant is: the continuous ones a
     * child can stretch and hear (L, M, R, V) come first, the plosives (P/T, B/D)
     * next, the hissing ones last. C, G, K, QU are DELIBERATELY absent — CE/CI and
     * GE/GI flip sound, which is a different lesson (Trouve le son / Les syllabes
     * jumelles own it). CH earns its own row: one sound, two letters.
     *
     * Vowels stay the six simple ones. The teams (EU, OU, OI, IN, AN…) are a later
     * exercise's job — here NOTHING changes between levels except the consonant, so
     * a level is short, familiar and repeatable.
     */
    public static let gridVowels: [String] = ["A", "E", "I", "O", "U", "É"]

    /** Consonant rows per level (index = level - 1); `null` = every row (révision). */
    // NB: `(string[] | null)[]` → `[[String]?]`, exactly. The `nil` is the révision
    // level and `syllableGridPool` reads it as "the whole table"; do not flatten it
    // into an empty array, which would mean "no rows".
    public static let syllableGridRows: [[String]?] = [
        ["L", "M"],
        ["R", "V"],
        ["P", "T"],
        ["B", "D"],
        ["F", "S"],
        ["N", "J"],
        ["Z", "CH"],
        nil,  // révision: le tableau entier
    ]
}
