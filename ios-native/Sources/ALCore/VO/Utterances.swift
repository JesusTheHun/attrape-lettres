import Foundation

/* -------------------------------------------------------------------------- */
/* Voice-over vocabulary — the finite, AUTHORED set of things the app speaks.   */
/* Shared by the runtime (clips.ts) and the generator (scripts/generate-vo.ts): */
/* both derive the same filename via voKey(), so a baked clip is found by the    */
/* exact utterance string. Keep these strings byte-identical to what the         */
/* exercises pass to audio.say(), or the lookup misses and it falls back.        */
/* -------------------------------------------------------------------------- */

// Port of `src/vo/utterances.ts` minus `voKey`, which already has its own file
// (VOKey.swift, D17) and its own golden vectors.
//
// This is the manifest the clip bank is baked from. Two things make it fragile
// in a way ordinary ported code is not:
//
//  - A one-character drift in any French line here does not fail, crash or log.
//    The `voKey` lookup simply misses and the app speaks that ONE line in the
//    robot text-to-speech voice, in the middle of otherwise-recorded narration.
//    Every literal is copied byte for byte, apostrophes included ("C'est à toi !"
//    is U+0027, not U+2019 — the mixed apostrophes in `content.ts` are load-
//    bearing for exactly this reason).
//  - The prompts are NOT re-authored here. Every per-round line comes from the
//    same `Levels.*` function the exercise itself calls, so the manifest cannot
//    describe a sentence the game never says.
public enum VO {}

extension VO {

    /* Shop lines — spoken during try-on/purchase so a pre-reader hears what the
     * visuals mean. Fixed strings + a finite cost vocabulary (catalog costs), so
     * they can be baked like everything else; say() falls back to TTS meanwhile. */
    public static let shopBought = "C'est à toi !"
    public static let shopGrew = "Tu as grandi !"
    public static let shopNeedMore = "Il te manque des étoiles."

    /// "Ça coûte N étoiles." — the spoken price of a tile being tried on.
    public static func shopCostLine(_ cost: Int) -> String {
        cost == 1 ? "Ça coûte 1 étoile." : "Ça coûte \(cost) étoiles."
    }

    public static func enumerateUtterances() -> [String] {
        // NB: `new Set<string>()` in JS iterates in INSERTION order, and the
        // generator bakes in the order it is handed. A Swift `Set` does not, so
        // the dedupe is an insertion-ordered one. Order is not correctness — the
        // lookup is by hash — but a stable order keeps a re-bake diffable.
        var seen = Set<String>()
        var out: [String] = []
        func add(_ s: String) {
            if seen.insert(s).inserted { out.append(s) }
        }

        // Fixed celebration lines (AssembleExercise / FirstLetterExercise finish).
        add("Bravo ! Tu as tout réussi !")
        add("Bravo ! Tu as tout trouvé !")

        // Multi-tap miss: the whole row is filled but out of order (Assemble/SpellSound).
        add("Oh non ! On recommence.")

        // First-letter: the spoken prompt + the success line, per word.
        for w in Content.letterWords {
            add("Trouve la première lettre de \(w.word).")
            add("Oui ! \(w.letter). \(w.word).")
        }

        // Read-the-word: the fixed consigne (never names the word), plus — per word —
        // the bare name auditioned on a picture tile and the success line.
        add(Levels.readImagePrompt)
        for w in Content.letterWords {
            add(w.word)
            add("Oui ! \(w.word).")
        }

        // Letter-form matching (case + script): the fixed directional consignes shared
        // by both exercises, plus a per-letter success line (named only after matching).
        for p in Levels.letterMatchPrompts.all { add(p) }
        for base in Content.letterMatchAlphabet { add(Levels.letterMatchSuccess(base)) }

        // Syllable build: the bare word (announce + "Écouter") + the success line.
        for w in Content.syllableWords {
            add(w.word)
            add("Oui ! \(w.word).")
        }

        // Spell-the-sound: the heard prompt (announce + "Écouter") + the success line.
        for target in Content.soundTargets.flatMap({ $0 }) {
            add(Levels.soundPrompt(target))
            add(Levels.soundSuccess(target))
        }

        // Find-the-sound: the bare sound (each tile's "Écouter"), the anchored
        // prompt and the success line. The prompt/success SHAPES match spell-sound's,
        // so overlapping (sound, word) pairs reuse already-baked clips.
        for s in Content.basicSounds.flatMap({ $0 }) {
            add(s.sound)
            add(Levels.findSoundPrompt(s))
            add(Levels.findSoundSuccess(s))
        }

        // Syllable grid: the WHOLE tableau, both drills. The prompt is the bare
        // syllable (also what each tile's "Écouter" speaks, in either drill) and the
        // success line names it again. Enumerated over every consonant × vowel, so a
        // baked run covers the grid exactly once whatever the level draws.
        for c in Content.gridConsonants {
            for v in Content.gridVowels {
                let s = Levels.gridSyllable(c, v)
                add(Levels.gridPrompt(s))
                add(Levels.gridSuccess(s))
            }
        }

        // Sound-twins: the hunt consigne + the bare family sound (tile "Écouter" —
        // every tile, twin or intruder, speaks its OWN family's sound) + one anchor
        // success line per graphy.
        for f in Content.twinFamilies.flatMap({ $0 }) {
            add(f.sound)
            add(Levels.twinPrompt(f))
            for g in f.graphies { add(Levels.twinSuccess(g)) }
        }

        // Shop: celebrations + the spoken price of every tile. Costs are a finite
        // catalog vocabulary; both readings a try-on can trigger are baked (the
        // plain price, and price + "not enough yet" as ONE clip — say() is passed
        // the combined string, and lookup is by exact utterance).
        add(shopBought)
        add(shopGrew)
        for cost in orderedUniqueCosts(MascotCatalog.catalog.map(\.cost)) {
            add(shopCostLine(cost))
            add("\(shopCostLine(cost)) \(shopNeedMore)")
        }

        return out
    }

    /// `new Set(CATALOG.map(o => o.cost))` — first-appearance order, as JS Sets iterate.
    private static func orderedUniqueCosts(_ costs: [Int]) -> [Int] {
        var seen = Set<Int>()
        return costs.filter { seen.insert($0).inserted }
    }
}
