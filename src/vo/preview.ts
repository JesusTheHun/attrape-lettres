import { LETTER_WORDS, SYLLABLE_BANK, SOUND_TARGETS, SOUND_LETTER_BANK } from "../content.ts";

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

export type VoKind = "letter" | "syllable";

export interface VoItem {
  text: string;
  kind: VoKind;
}

/** Every letter/syllable a tile can show, tagged by kind, deduped by text. */
export function enumeratePreviewUtterances(): VoItem[] {
  const byText = new Map<string, VoItem>();

  // Syllable tiles (AssembleExercise): both the authored splits and the
  // wrong-answer distractors are drawn from SYLLABLE_BANK. A few entries are
  // lone vowels ("A", "É"); claiming them as syllables here (they read the same
  // either way) keeps them in the syllable group, so a letters-only rollback
  // can't strip audio a syllable round still needs.
  for (const s of SYLLABLE_BANK) if (!byText.has(s)) byText.set(s, { text: s, kind: "syllable" });

  // Letter tiles (FirstLetter + SpellSound): first-letter answers, every
  // spelling grapheme, and the intruder bank. All single uppercase graphemes.
  const letters = new Set<string>();
  for (const w of LETTER_WORDS) letters.add(w.letter);
  for (const l of SOUND_LETTER_BANK) letters.add(l);
  for (const pool of SOUND_TARGETS) for (const t of pool) for (const g of t.spelling) letters.add(g);
  for (const l of letters) if (!byText.has(l)) byText.set(l, { text: l, kind: "letter" });

  return [...byText.values()];
}
