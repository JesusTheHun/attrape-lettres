package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.SyllableWord

// Port of `src/content.ts` — the authored datasets.
//
// CONTENT ONLY, NO LOGIC. This is invariant 4 in file form: content is
// AUTHORED, NOT COMPUTED. There is no runtime French syllabifier and no
// letter→word generator anywhere in :core; `SyllableWord.syllables` is stored,
// never derived. The integrity tests assert the authored split spells the
// authored word — that is a SHAPE check and must never be turned into a
// generator. Adding one "just for the new words" is the way this invariant dies.
//
// These are Kotlin literals, not a bundled JSON resource, for the same reasons
// the iOS port chose Swift literals (its D10):
//   - The CLAUDE.md recipe survives. "Append to LETTER_WORDS in content.ts —
//     that's it, pools derive automatically" stays the same single edit here,
//     and the compiler still checks every shape. With JSON a typo turns from a
//     build error into a runtime null.
//   - Making content decodable makes it LOADABLE, and loadable things fail at
//     runtime. :core stays resource-free, which is part of what keeps it a
//     plain JVM module (A1) with nothing to stage and nothing to await.
//   - The comments are the point. The most valuable lines in `content.ts` are
//     the notes explaining WHY — the MAI-SON / POIS-SON /zɔ̃/ vs /sɔ̃/
//     collision, why PAPI-LLON had to go, why « auto » was a bad anchor. JSON
//     cannot hold them. Every one of them is copied across.
//
// The authored tables live one per file beside this one (ContentLetterWords,
// ContentSyllableWords, ContentSoundTargets, ContentBasicSounds,
// ContentTwinFamilies, ContentGrid, ContentBanks). This file holds the derived
// tables — the only computations in the whole of content/, and all three are
// dedupes or lookups, not generators.

/** Every distinct syllable in the corpus — the source for wrong-answer tiles. */
// NB: the TS is `Array.from(new Set(SYLLABLE_WORDS.flatMap(w => w.syllables)))`,
// and JS `Set` iterates in INSERTION order. Kotlin's `distinct()` documents the
// same first-appearance order, so this is a faithful port and not a coincidence
// — through an unordered set the order would differ per process. The order is
// load-bearing twice: `pickDistractorSyllable` picks by index (the order IS the
// distribution) and its fallback is element 0, today "CHA".
val SYLLABLE_BANK: List<String> = SYLLABLE_WORDS.flatMap { it.syllables }.distinct()

/** Every consonant the ladder teaches, in teaching order — the null level's pool. */
val GRID_CONSONANTS: List<String> = SYLLABLE_GRID_ROWS.flatMap { it.orEmpty() }

/**
 * Name → word, for the fill-a-syllable ladder (TS `WORD_BY_NAME`, which lives
 * in `levels.ts`; the iOS port keeps it beside the table it indexes, and so
 * does this one). A LOOKUP, not a generator: the spell-syllable pool resolves
 * authored names through it so the split, the emoji and the baked VO are
 * reused and never duplicated.
 */
val WORD_BY_NAME: Map<String, SyllableWord> = SYLLABLE_WORDS.associateBy { it.word }
