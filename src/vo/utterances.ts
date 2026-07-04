import { LETTER_WORDS, SYLLABLE_WORDS, SOUND_TARGETS } from "../content.ts";
import { soundPrompt, soundSuccess } from "../levels.ts";

/* -------------------------------------------------------------------------- */
/* Voice-over vocabulary — the finite, AUTHORED set of things the app speaks.   */
/* Shared by the runtime (clips.ts) and the generator (scripts/generate-vo.ts): */
/* both derive the same filename via voKey(), so a baked clip is found by the    */
/* exact utterance string. Keep these strings byte-identical to what the         */
/* exercises pass to audio.speak(), or the lookup misses and it falls back.      */
/* -------------------------------------------------------------------------- */

export function enumerateUtterances(): string[] {
  const out = new Set<string>();

  // Fixed celebration lines (AssembleExercise / FirstLetterExercise finish).
  out.add("Bravo ! Tu as tout réussi !");
  out.add("Bravo ! Tu as tout trouvé !");

  // First-letter: the spoken prompt + the success line, per word.
  for (const w of LETTER_WORDS) {
    out.add(`Trouve la première lettre de ${w.word}.`);
    out.add(`Oui ! ${w.letter}. ${w.word}.`);
  }

  // Syllable build: the bare word (announce + "Écouter") + the success line.
  for (const w of SYLLABLE_WORDS) {
    out.add(w.word);
    out.add(`Oui ! ${w.word}.`);
  }

  // Spell-the-sound: the heard prompt (announce + "Écouter") + the success line.
  for (const target of SOUND_TARGETS.flat()) {
    out.add(soundPrompt(target));
    out.add(soundSuccess(target));
  }

  return [...out];
}

/**
 * Stable clip key for an utterance: FNV-1a (32-bit) over the NFC-normalised,
 * whitespace-collapsed text, base36. Identical in Node and the browser, so the
 * generator and the runtime always agree on `${voKey(text)}.wav`.
 */
export function voKey(text: string): string {
  const norm = text.normalize("NFC").replace(/\s+/g, " ").trim();
  let h = 0x811c9dc5;
  for (let i = 0; i < norm.length; i++) {
    h ^= norm.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}
