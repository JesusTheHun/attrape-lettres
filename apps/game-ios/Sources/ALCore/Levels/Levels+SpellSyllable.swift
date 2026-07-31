// Port of the fill-a-syllable ladder from `src/levels.ts` — the densest builder
// in the app.

/* -------------------------------------------------------------------------- */
/* Fill-a-syllable — one 4-level ladder, shared by all 3 siblings.             */
/* The word list is level == index (content); only the tray/gap count changes  */
/* per mode. Level 1 is deliberately 5 words for a quick, repeated win.         */
/* -------------------------------------------------------------------------- */

/// One writing the word can take: a case + a script.
struct SpellForm: Hashable {
    var upper: Bool
    var script: LetterScript
}

extension Levels {
    public static let spellSyllableLevels: [SpellSyllableLevel] = [
        SpellSyllableLevel(pick: 5, repeats: 3, distractors: 2),
        SpellSyllableLevel(pick: 6, repeats: 3, distractors: 2),
        SpellSyllableLevel(pick: 7, repeats: 3, distractors: 3),
        SpellSyllableLevel(pick: 8, repeats: 4, distractors: 3),
    ]

    public static let spellSyllableLevelCount: Int = spellSyllableLevels.count

    private static func spellSyllableIdx(_ level: Int) -> Int {
        min(max(level, 1), spellSyllableLevelCount) - 1
    }

    public static func spellSyllableLevel(_ level: Int) -> SpellSyllableLevel {
        spellSyllableLevels[spellSyllableIdx(level)]
    }

    /// The level's authored word list, resolved to full SyllableWord objects.
    // NB: the TS throws `Error("spellSyllablePool: unknown word …")`. A content
    // typo must fail loudly at first use, exactly as today — hence the
    // `preconditionFailure` rather than a silent skip. A test resolves every
    // name at build time anyway.
    public static func spellSyllablePool(_ level: Int) -> [SyllableWord] {
        Content.spellSyllableWordNames[spellSyllableIdx(level)].map { name in
            guard let w = Content.wordByName[name] else {
                preconditionFailure("spellSyllablePool: unknown word \"\(name)\"")
            }
            return w
        }
    }

    public static func buildSpellSyllableSession(
        level: Int,
        _ rng: RandomSource = .system()
    ) -> [SyllableWord] {
        let cfg = spellSyllableLevel(level)
        return repeatSession(spellSyllablePool(level), pick: cfg.pick, repeats: cfg.repeats, rng)
    }

    /// Plain siblings always print big uppercase — the letter is never in question.
    static let plainForm = SpellForm(upper: true, script: .print)
    /// The three writings the "écritures mêlées" twins shuffle between per round.
    // NB: this ORDER is behaviour — `spellIntruders` walks it to build the
    // same-letter/wrong-writing traps, and the tests hard-code exactly this set.
    static let mixedForms: [SpellForm] = [
        SpellForm(upper: true, script: .print),  // GRANDE
        SpellForm(upper: false, script: .print),  // petite
        SpellForm(upper: false, script: .cursive),  // attachée
    ]

    static func spellFace(_ base: String, _ form: SpellForm) -> LetterFace {
        LetterFace(
            base: base,
            glyph: form.upper ? base : base.lowercased(),
            script: form.script
        )
    }

    /// Two faces render identically iff they share this key (glyph already encodes the case).
    static func faceKey(_ f: LetterFace) -> String { "\(f.glyph)|\(f.script.rawValue)" }

    /**
     * Blank out 1 (or 2, for `letters-two`) whole syllables into per-letter slots and
     * fill the tray. `letters-exact` gives only the gap's own letters (order is the
     * whole task); the other two add intruders. At least one syllable always stays
     * written, so there's a printed anchor to read the word from. Hidden syllables
     * are chosen at random — the gap can sit anywhere in the word.
     *
     * `mixed` adds a second axis: the whole word (anchors + answer) is drawn in ONE
     * random writing (grande / petite / attachée), and the intruders become the SAME
     * gap letters in the OTHER two writings — so a tile with the right letter but the
     * wrong case or script is a trap. The child must match the writing, not just the
     * letter. Plain rounds keep the exact old shape (uppercase print, letter-only).
     */
    // NB: `hideCount` clamps to `syl.count - 1`, which is what guarantees a
    // written anchor. Every `spellSyllablePool` word has ≥3 syllables, so
    // `letters-two` always hides exactly 2.
    // NB: `[...s]` in the TS spreads CODE POINTS. Every syllable is NFC Latin
    // (GÂ, TÉ, HÔ) so Swift's `Character` iteration is equivalent — PROVIDED the
    // content stays NFC, which `ContentIntegrityTests` asserts.
    // NB: `letters-exact` ignores the level's distractor count entirely.
    public static func buildSpellSyllableRound(
        word: SyllableWord,
        mode: SpellSyllableMode,
        distractors: Int,
        mixed: Bool = false,
        _ rng: RandomSource = .system(),
        ids: TileIDAllocator = .shared
    ) -> SpellSyllableRound {
        let syl = word.syllables
        let hideCount = min(mode == .lettersTwo ? 2 : 1, syl.count - 1)
        let hidden = Set(rng.shuffled(Array(syl.indices)).prefix(hideCount))
        let form = mixed ? mixedForms[rng.int(below: mixedForms.count)] : plainForm

        var cells: [SpellCell] = []
        var answer: [String] = []
        var answerFaces: [LetterFace] = []
        for (si, s) in syl.enumerated() {
            let gap = hidden.contains(si)
            for (ci, ch) in s.enumerated() {
                let letter = String(ch)
                let face = spellFace(letter, form)
                cells.append(
                    SpellCell(
                        letter: letter,
                        glyph: face.glyph,
                        script: face.script,
                        fill: gap,
                        slotIndex: gap ? answer.count : -1,
                        syllableStart: ci == 0
                    )
                )
                if gap {
                    answer.append(letter)
                    answerFaces.append(face)
                }
            }
        }

        let extra = mode == .lettersExact ? 0 : distractors
        let trayFaces =
            answerFaces + spellIntruders(answer: answer, form: form, extra: extra, mixed: mixed, rng)
        return SpellSyllableRound(
            word: word,
            cells: cells,
            answer: answer,
            answerFaces: answerFaces,
            tray: rng.shuffled(trayFaces).map {
                SpellLetterTile(id: ids.next(), letter: $0.base, glyph: $0.glyph, script: $0.script)
            }
        )
    }

    /**
     * `extra` distractor faces for the tray. Plain: wrong LETTERS in the same writing
     * (the classic intrus). Mixed: prefer the SAME gap letters in the two OTHER
     * writings (the point of the game), then top up with wrong letters in random
     * writings. Deduped against the answer + each other so no tile is a valid answer.
     */
    // NB: three things here are load-bearing under a seed and must not be tidied:
    //   - `for base in orderedUnique(answer)` walks the answer's distinct letters
    //     in FIRST-APPEARANCE order (JS `new Set(answer)`); a Swift `Set` would
    //     randomise it per process.
    //   - `others` is NOT shuffled again at the end — it is already in shuffled
    //     bank order.
    //   - the random form for `others` is drawn PER ELEMENT, inside the loop,
    //     over the whole filtered bank (22 draws), even though at most `extra`
    //     are used. The draw count is part of the reproduction.
    static func spellIntruders(
        answer: [String],
        form: SpellForm,
        extra: Int,
        mixed: Bool,
        _ rng: RandomSource
    ) -> [LetterFace] {
        if extra <= 0 { return [] }
        if !mixed {
            let need = Set(answer)
            return rng.shuffled(Content.soundLetterBank.filter { !need.contains($0) })
                .prefix(extra)
                .map { spellFace($0, form) }
        }

        var seen = Set(answer.map { faceKey(spellFace($0, form)) })
        func take(_ face: LetterFace, into: inout [LetterFace]) {
            let k = faceKey(face)
            if seen.contains(k) { return }
            seen.insert(k)
            into.append(face)
        }

        // Same letters, wrong writings — the traps that make "the right case" matter.
        var wrongForm: [LetterFace] = []
        for base in orderedUnique(answer) {
            for f in mixedForms { take(spellFace(base, f), into: &wrongForm) }
        }

        // Wrong letters, random writings — fill any remainder.
        var others: [LetterFace] = []
        let answerSet = Set(answer)
        for base in rng.shuffled(Content.soundLetterBank).filter({ !answerSet.contains($0) }) {
            take(spellFace(base, mixedForms[rng.int(below: mixedForms.count)]), into: &others)
        }

        return Array((rng.shuffled(wrongForm) + others).prefix(extra))
    }
}
