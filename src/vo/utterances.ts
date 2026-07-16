import { LETTER_WORDS, LETTER_MATCH_ALPHABET, SYLLABLE_WORDS, SOUND_TARGETS } from "../content.ts";
import {
  LETTER_MATCH_PROMPTS,
  READ_IMAGE_PROMPT,
  letterMatchSuccess,
  soundPrompt,
  soundSuccess,
} from "../levels.ts";

/* -------------------------------------------------------------------------- */
/* Voice-over vocabulary — the finite, AUTHORED set of things the app speaks.   */
/* Shared by the runtime (clips.ts) and the generator (scripts/generate-vo.ts): */
/* both derive the same filename via voKey(), so a baked clip is found by the    */
/* exact utterance string. Keep these strings byte-identical to what the         */
/* exercises pass to audio.say(), or the lookup misses and it falls back.        */
/* -------------------------------------------------------------------------- */

/* Shop lines — spoken during try-on/purchase so a pre-reader hears what the
 * visuals mean. Fixed strings + a finite cost vocabulary (catalog costs), so
 * they can be baked like everything else; say() falls back to TTS meanwhile. */
export const SHOP_BOUGHT = "C'est à toi !";
export const SHOP_GREW = "Tu as grandi !";
export const SHOP_NEED_MORE = "Il te manque des étoiles.";

/** "Ça coûte N étoiles." — the spoken price of a tile being tried on. */
export function shopCostLine(cost: number): string {
  return cost === 1 ? "Ça coûte 1 étoile." : `Ça coûte ${cost} étoiles.`;
}

export function enumerateUtterances(): string[] {
  const out = new Set<string>();

  // Fixed celebration lines (AssembleExercise / FirstLetterExercise finish).
  out.add("Bravo ! Tu as tout réussi !");
  out.add("Bravo ! Tu as tout trouvé !");

  // Multi-tap miss: the whole row is filled but out of order (Assemble/SpellSound).
  out.add("Oh non ! On recommence.");

  // First-letter: the spoken prompt + the success line, per word.
  for (const w of LETTER_WORDS) {
    out.add(`Trouve la première lettre de ${w.word}.`);
    out.add(`Oui ! ${w.letter}. ${w.word}.`);
  }

  // Read-the-word: the fixed consigne (never names the word), plus — per word —
  // the bare name auditioned on a picture tile and the success line.
  out.add(READ_IMAGE_PROMPT);
  for (const w of LETTER_WORDS) {
    out.add(w.word);
    out.add(`Oui ! ${w.word}.`);
  }

  // Letter-form matching (case + script): the fixed directional consignes shared
  // by both exercises, plus a per-letter success line (named only after matching).
  for (const p of Object.values(LETTER_MATCH_PROMPTS)) out.add(p);
  for (const base of LETTER_MATCH_ALPHABET) out.add(letterMatchSuccess(base));

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
