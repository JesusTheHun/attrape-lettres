package fr.dappit.attrapelettres.core.vo

import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.content.LETTER_WORDS
import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.content.SOUND_TARGETS
import fr.dappit.attrapelettres.core.content.SYLLABLE_BANK

/* -------------------------------------------------------------------------- */
/* Preview voice-over — the "hear it before you tap it" vocabulary.            */
/*                                                                             */
/* Deliberately kept in its OWN file, separate from Utterances.kt, so it can   */
/* be baked — or rolled back — independently of the word/sentence clips. The   */
/* generator bakes it as its own `--group` (letters / syllables), and each     */
/* token carries its KIND so Gemini is prompted for the right thing: a lone    */
/* letter is NAMED (« bé »), a syllable is read as one blended sound           */
/* (« cha », never « cé-ache-a »). We don't yet know if lone letters read      */
/* well, hence the split — bake syllables, keep letters droppable.             */
/*                                                                             */
/* The strings must stay byte-identical (CASE included) to what the exercises  */
/* pass to audio.say(): Assemble speaks the UPPERCASE syllable tile,           */
/* FirstLetter / SpellSound speak the UPPERCASE letter tile. The test pins     */
/* that against the content tables so an edit can't silently drift a tile out  */
/* of the baked set (which would fall back to the robot voice).                */
/* -------------------------------------------------------------------------- */

// Port of `src/vo/preview.ts`. Its own file, mirroring the TS module split —
// which is not cosmetic: the letters group is the one the team is prepared to
// roll back if lone letters read badly, and a rollback has to be able to take
// one group without the other.

enum class VoKind {
    LETTER,
    SYLLABLE,
}

data class VoItem(
    val text: String,
    val kind: VoKind,
)

/** Every letter/syllable a tile can show, tagged by kind, deduped by text. */
fun enumeratePreviewUtterances(): List<VoItem> {
    // NB: a JS `Map` iterates in insertion order and the TS returns
    // `[...byText.values()]` — `LinkedHashMap` + `putIfAbsent` reproduce both
    // the order and the first-writer-wins dedupe.
    val byText = LinkedHashMap<String, VoItem>()

    // Syllable tiles (AssembleExercise): both the authored splits and the
    // wrong-answer distractors are drawn from SYLLABLE_BANK. A few entries are
    // lone vowels ("A", "É"); claiming them as syllables here (they read the same
    // either way) keeps them in the syllable group, so a letters-only rollback
    // can't strip audio a syllable round still needs.
    for (s in SYLLABLE_BANK) byText.putIfAbsent(s, VoItem(text = s, kind = VoKind.SYLLABLE))

    // Letter tiles (FirstLetter + SpellSound): first-letter answers, every
    // spelling grapheme, and the intruder bank. All single uppercase graphemes.
    // NB: the TS builds a `letters` Set BEFORE the loop that inserts them, so
    // the ORDER letters reach `byText` is first-appearance order across these
    // four sources — reproduced with the same ordered dedupe.
    val letters = LinkedHashSet<String>()
    for (w in LETTER_WORDS) letters.add(w.letter)
    for (l in SOUND_LETTER_BANK) letters.add(l)
    for (pool in SOUND_TARGETS) for (t in pool) for (g in t.spelling) letters.add(g)
    // Letter-form matching tiles audition by the letter NAME (both cases/scripts).
    for (l in LETTER_MATCH_ALPHABET) letters.add(l)
    for (l in letters) byText.putIfAbsent(l, VoItem(text = l, kind = VoKind.LETTER))

    return byText.values.toList()
}
