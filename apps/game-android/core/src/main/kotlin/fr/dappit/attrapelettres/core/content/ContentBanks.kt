package fr.dappit.attrapelettres.core.content

// `SOUND_LETTER_BANK` from `src/content.ts`.

/** Letters used to seed wrong-answer tiles (target letters are excluded per round). */
// NB: A–V, 22 letters. W/X/Y/Z are absent on purpose — nothing in the sound
// ladders spells with them, and an intruder a child has never met is noise,
// not difficulty.
val SOUND_LETTER_BANK: List<String> = listOf(
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
    "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
)
