// Port of `src/content.ts` — the authored datasets.
//
// CONTENT ONLY, NO LOGIC. This is invariant 4 in file form: content is
// AUTHORED, NOT COMPUTED. There is no runtime French syllabifier and no
// letter→word generator anywhere in ALCore; `SyllableWord.syllables` is stored,
// never derived. `ContentIntegrityTests` asserts `syllables.joined() == word`
// for all 45 words — that is a SHAPE check and must never be turned into a
// generator. Adding one "just for the new words" is the way this invariant dies.
//
// D10 — these are Swift literals, not a bundled JSON resource:
//   - The CLAUDE.md recipe survives. "Append to LETTER_WORDS in content.ts —
//     that's it, pools derive automatically" becomes "append to
//     Content.letterWords", the same single edit, and the compiler still checks
//     the shape, the ImageKey and the enum cases. With JSON a typo in an `img`
//     key turns from a build error into a runtime nil.
//   - Making content decodable makes it LOADABLE, and loadable things fail at
//     runtime. ALCore stays resource-free, which is what makes it instant.
//   - The comments are the point. The most valuable lines in `content.ts` are
//     the French notes explaining WHY — the MAI-SON / POIS-SON /zɔ̃/ vs /sɔ̃/
//     collision, why PAPI-LLON had to go, why « auto » was a bad anchor. JSON
//     cannot hold them. Every one of them is copied across.
//
// Compile-time mitigation (data-core.md §7.8): every array is explicitly
// annotated, every struct carries an explicit memberwise init (see Domain/), and
// each table lives in its own file. If one ever gets slow the fix is splitting
// its array into `static let` parts, not moving to a resource.
//
// Dedicated illustrations for words whose closest emoji is wrong (see WordIcon):
// no glyph is a true igloo/skirt, and 🧁/🛌 read as cupcake/person-in-bed. In
// TypeScript these are Vite-imported SVG URLs; here they are `ImageKey` cases
// that ALArt resolves (data-core.md §3.3).

public enum Content {}

/* -------------------------------------------------------------------------- */
/* Derived tables — the only two computations in the whole of Content, and both */
/* are dedupes, not generators.                                                 */
/* -------------------------------------------------------------------------- */

extension Content {
    /** Every distinct syllable in the corpus — the source for wrong-answer tiles. */
    // NB: the TS is `Array.from(new Set(SYLLABLE_WORDS.flatMap(w => w.syllables)))`.
    // JS `Set` iterates in INSERTION order; Swift `Set` is unordered and
    // randomised per process — so this goes through `orderedUnique`, and it must.
    // The order here is load-bearing twice: `pickDistractorSyllable` picks by
    // index (the order IS the distribution) and its fallback is element 0, today
    // "CHA".
    public static let syllableBank: [String] = orderedUnique(syllableWords.flatMap(\.syllables))

    /** Every consonant the ladder teaches, in teaching order — the null level's pool. */
    public static let gridConsonants: [String] = syllableGridRows.flatMap { $0 ?? [] }

    /// Name → word, for the fill-a-syllable ladder (TS `WORD_BY_NAME`, which
    /// lives in `levels.ts`). A LOOKUP, not a generator: `spellSyllablePool`
    /// resolves authored names through it so the split, the emoji and the baked
    /// VO are reused and never duplicated.
    public static let wordByName: [String: SyllableWord] = {
        var map: [String: SyllableWord] = [:]
        map.reserveCapacity(syllableWords.count)
        for w in syllableWords { map[w.word] = w }
        return map
    }()
}
