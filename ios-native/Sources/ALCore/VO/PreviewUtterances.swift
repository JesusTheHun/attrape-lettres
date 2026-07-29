/* -------------------------------------------------------------------------- */
/* Preview voice-over — the "hear it before you tap it" vocabulary.             */
/*                                                                            */
/* Deliberately kept in its OWN module, separate from utterances.ts, so it can */
/* be baked — or rolled back — independently of the word/sentence clips. The    */
/* generator bakes it as its own `--group` (letters / syllables), and each       */
/* token carries its KIND so Gemini is prompted for the right thing: a lone      */
/* letter is NAMED (« bé »), a syllable is read as one blended sound             */
/* (« cha », never « cé-ache-a »). We don't yet know if lone letters read well,  */
/* hence the split — bake syllables, keep letters droppable.                     */
/*                                                                            */
/* The strings must stay byte-identical (CASE included) to what the exercises   */
/* pass to audio.say(): Assemble speaks the UPPERCASE syllable tile,             */
/* FirstLetter / SpellSound speak the UPPERCASE letter tile. preview.test.ts     */
/* pins that against the real round builders so a content edit can't silently    */
/* drift a tile out of the baked set (which would fall back to the robot voice). */
/* -------------------------------------------------------------------------- */

// Port of `src/vo/preview.ts`. Its own file, mirroring the TS module split —
// which is not cosmetic: the letters group is the one the team is prepared to
// roll back if lone letters read badly, and a rollback has to be able to take
// one group without the other.

public enum VOKind: String, CaseIterable, Hashable, Codable, Sendable {
    case letter
    case syllable
}

public struct VOItem: Hashable, Sendable {
    public var text: String
    public var kind: VOKind

    public init(text: String, kind: VOKind) {
        self.text = text
        self.kind = kind
    }
}

extension VO {

    /// Every letter/syllable a tile can show, tagged by kind, deduped by text.
    public static func enumeratePreviewUtterances() -> [VOItem] {
        // NB: a JS `Map` iterates in insertion order and the TS returns
        // `[...byText.values()]`, so the Swift keeps an explicit order array
        // alongside the seen-set rather than using a Dictionary.
        var seen = Set<String>()
        var out: [VOItem] = []
        func setIfAbsent(_ item: VOItem) {
            if seen.insert(item.text).inserted { out.append(item) }
        }

        // Syllable tiles (AssembleExercise): both the authored splits and the
        // wrong-answer distractors are drawn from SYLLABLE_BANK. A few entries are
        // lone vowels ("A", "É"); claiming them as syllables here (they read the same
        // either way) keeps them in the syllable group, so a letters-only rollback
        // can't strip audio a syllable round still needs.
        for s in Content.syllableBank { setIfAbsent(VOItem(text: s, kind: .syllable)) }

        // Letter tiles (FirstLetter + SpellSound): first-letter answers, every
        // spelling grapheme, and the intruder bank. All single uppercase graphemes.
        // NB: `letters` is a JS Set built before the loop that inserts them, so the
        // ORDER letters reach `byText` is first-appearance order across these four
        // sources — reproduced with the same ordered dedupe.
        var lettersSeen = Set<String>()
        var letters: [String] = []
        func addLetter(_ l: String) {
            if lettersSeen.insert(l).inserted { letters.append(l) }
        }
        for w in Content.letterWords { addLetter(w.letter) }
        for l in Content.soundLetterBank { addLetter(l) }
        for pool in Content.soundTargets {
            for t in pool {
                for g in t.spelling { addLetter(g) }
            }
        }
        // Letter-form matching tiles audition by the letter NAME (both cases/scripts).
        for l in Content.letterMatchAlphabet { addLetter(l) }
        for l in letters { setIfAbsent(VOItem(text: l, kind: .letter)) }

        return out
    }
}
