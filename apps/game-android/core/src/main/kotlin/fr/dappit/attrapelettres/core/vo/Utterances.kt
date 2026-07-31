package fr.dappit.attrapelettres.core.vo

import fr.dappit.attrapelettres.core.content.BASIC_SOUNDS
import fr.dappit.attrapelettres.core.content.GRID_CONSONANTS
import fr.dappit.attrapelettres.core.content.GRID_VOWELS
import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.content.SOUND_TARGETS
import fr.dappit.attrapelettres.core.content.SYLLABLE_WORDS
import fr.dappit.attrapelettres.core.content.TWIN_FAMILIES
import fr.dappit.attrapelettres.core.levels.LetterMatchPrompts
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.levels.findSoundPrompt
import fr.dappit.attrapelettres.core.levels.findSoundSuccess
import fr.dappit.attrapelettres.core.levels.gridPrompt
import fr.dappit.attrapelettres.core.levels.gridSuccess
import fr.dappit.attrapelettres.core.levels.gridSyllable
import fr.dappit.attrapelettres.core.levels.letterMatchSuccess
import fr.dappit.attrapelettres.core.levels.soundPrompt
import fr.dappit.attrapelettres.core.levels.soundSuccess
import fr.dappit.attrapelettres.core.levels.twinPrompt
import fr.dappit.attrapelettres.core.levels.twinSuccess
import fr.dappit.attrapelettres.core.mascot.CATALOG

/* -------------------------------------------------------------------------- */
/* Voice-over vocabulary — the finite, AUTHORED set of things the app speaks.  */
/* Shared by the runtime (clips.ts) and the generator (scripts/generate-vo.ts) */
/* on the web: both derive the same filename via voKey(), so a baked clip is   */
/* found by the exact utterance string. Keep these strings byte-identical to   */
/* what the exercises pass to audio.say(), or the lookup misses and it falls   */
/* back.                                                                       */
/* -------------------------------------------------------------------------- */

// Port of `src/vo/utterances.ts` minus `voKey`, which already has its own file
// (VoKey.kt) and its own golden vectors.
//
// This is the manifest the clip bank was baked from. Two things make it
// fragile in a way ordinary ported code is not:
//
//  - A one-character drift in any French line here does not fail, crash or
//    log. The `voKey` lookup simply misses and the app speaks that ONE line in
//    the robot text-to-speech voice, in the middle of otherwise-recorded
//    narration. Every literal is copied byte for byte, apostrophes included
//    ("C'est à toi !" is U+0027, not U+2019 — the mixed apostrophes in
//    `content.ts` are load-bearing for exactly this reason).
//  - The prompts are NOT re-authored here. Every per-round line comes from the
//    same `levels` declaration the exercise itself calls, so the manifest
//    cannot describe a sentence the game never says.

/* Shop lines — spoken during try-on/purchase so a pre-reader hears what the
 * visuals mean. Fixed strings + a finite cost vocabulary (catalog costs), so
 * they can be baked like everything else; say() falls back to TTS meanwhile. */
const val SHOP_BOUGHT = "C'est à toi !"
const val SHOP_GREW = "Tu as grandi !"
const val SHOP_NEED_MORE = "Il te manque des étoiles."

/** "Ça coûte N étoiles." — the spoken price of a tile being tried on. */
fun shopCostLine(cost: Int): String =
    if (cost == 1) "Ça coûte 1 étoile." else "Ça coûte $cost étoiles."

fun enumerateUtterances(): List<String> {
    // NB: `new Set<string>()` in JS iterates in INSERTION order, and the
    // generator bakes in the order it is handed. `LinkedHashSet` has the same
    // contract (re-adding an element does not move it). Order is not
    // correctness — the lookup is by hash — but a stable order keeps a re-bake
    // diffable.
    val out = LinkedHashSet<String>()

    // Fixed celebration lines (AssembleExercise / FirstLetterExercise finish).
    out.add("Bravo ! Tu as tout réussi !")
    out.add("Bravo ! Tu as tout trouvé !")

    // Multi-tap miss: the whole row is filled but out of order (Assemble/SpellSound).
    out.add("Oh non ! On recommence.")

    // First-letter: the spoken prompt + the success line, per word.
    for (w in LETTER_WORDS) {
        out.add("Trouve la première lettre de ${w.word}.")
        out.add("Oui ! ${w.letter}. ${w.word}.")
    }

    // Read-the-word: the fixed consigne (never names the word), plus — per word —
    // the bare name auditioned on a picture tile and the success line.
    out.add(READ_IMAGE_PROMPT)
    for (w in LETTER_WORDS) {
        out.add(w.word)
        out.add("Oui ! ${w.word}.")
    }

    // Letter-form matching (case + script): the fixed directional consignes shared
    // by both exercises, plus a per-letter success line (named only after matching).
    for (p in LetterMatchPrompts.all) out.add(p)
    for (base in LETTER_MATCH_ALPHABET) out.add(letterMatchSuccess(base))

    // Syllable build: the bare word (announce + "Écouter") + the success line.
    for (w in SYLLABLE_WORDS) {
        out.add(w.word)
        out.add("Oui ! ${w.word}.")
    }

    // Spell-the-sound: the heard prompt (announce + "Écouter") + the success line.
    for (target in SOUND_TARGETS.flatten()) {
        out.add(soundPrompt(target))
        out.add(soundSuccess(target))
    }

    // Find-the-sound: the bare sound (each tile's "Écouter"), the anchored
    // prompt and the success line. The prompt/success SHAPES match spell-sound's,
    // so overlapping (sound, word) pairs reuse already-baked clips.
    for (s in BASIC_SOUNDS.flatten()) {
        out.add(s.sound)
        out.add(findSoundPrompt(s))
        out.add(findSoundSuccess(s))
    }

    // Syllable grid: the WHOLE tableau, both drills. The prompt is the bare
    // syllable (also what each tile's "Écouter" speaks, in either drill) and the
    // success line names it again. Enumerated over every consonant × vowel, so a
    // baked run covers the grid exactly once whatever the level draws.
    for (c in GRID_CONSONANTS) {
        for (v in GRID_VOWELS) {
            val s = gridSyllable(c, v)
            out.add(gridPrompt(s))
            out.add(gridSuccess(s))
        }
    }

    // Sound-twins: the hunt consigne + the bare family sound (tile "Écouter" —
    // every tile, twin or intruder, speaks its OWN family's sound) + one anchor
    // success line per graphy.
    for (f in TWIN_FAMILIES.flatten()) {
        out.add(f.sound)
        out.add(twinPrompt(f))
        for (g in f.graphies) out.add(twinSuccess(g))
    }

    // Shop: celebrations + the spoken price of every tile. Costs are a finite
    // catalog vocabulary; both readings a try-on can trigger are baked (the
    // plain price, and price + "not enough yet" as ONE clip — say() is passed
    // the combined string, and lookup is by exact utterance).
    out.add(SHOP_BOUGHT)
    out.add(SHOP_GREW)
    // `new Set(CATALOG.map(o => o.cost))` iterates in first-appearance order,
    // which is exactly Kotlin's documented `distinct()` order.
    for (cost in CATALOG.map { it.cost }.distinct()) {
        out.add(shopCostLine(cost))
        out.add("${shopCostLine(cost)} $SHOP_NEED_MORE")
    }

    return out.toList()
}
