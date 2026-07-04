import { describe, expect, it } from "vitest";
import { LETTER_WORDS, SYLLABLE_BANK, SOUND_TARGETS, SOUND_LETTER_BANK } from "../content";
import { enumeratePreviewUtterances } from "./preview";

const items = enumeratePreviewUtterances();
const texts = new Set(items.map((i) => i.text));

describe("preview VO catalog covers every audition-able tile", () => {
  it("has no duplicate texts and only known kinds", () => {
    expect(texts.size).toBe(items.length);
    for (const it of items) expect(["letter", "syllable"]).toContain(it.kind);
  });

  it("covers every syllable tile (Assemble draws splits + distractors from SYLLABLE_BANK)", () => {
    for (const s of SYLLABLE_BANK) expect(texts).toContain(s);
  });

  it("covers every letter tile (first letters, spelling graphemes, intruder bank)", () => {
    for (const w of LETTER_WORDS) expect(texts).toContain(w.letter);
    for (const l of SOUND_LETTER_BANK) expect(texts).toContain(l);
    for (const pool of SOUND_TARGETS)
      for (const t of pool) for (const g of t.spelling) expect(texts).toContain(g);
  });

  it("keeps tile case verbatim — uppercase, so voKey matches what audio.speak() gets", () => {
    // Assemble speaks UPPERCASE syllables, SpellSound/FirstLetter UPPERCASE letters.
    expect(texts).toContain("CHA"); // syllable
    expect(texts).toContain("É"); // accented grapheme, composed (NFC)
    expect(texts).toContain("B"); // consonant letter
    for (const t of texts) expect(t).toBe(t.toUpperCase());
  });
});
