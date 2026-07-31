package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.ImageKey
import fr.dappit.attrapelettres.core.domain.LetterWord

// `LETTER_WORDS` + `LETTER_MATCH_ALPHABET` from `src/content.ts`.
//
// Content only, no logic — invariant 4 in file form. In TypeScript the `img`
// values are Vite-imported SVG URLs; `:core` knows nothing about resources, so
// here they are `ImageKey` constants that `:art` resolves with an exhaustive
// `when`. The dedicated illustrations exist because the closest emoji is wrong:
// no glyph is a true igloo/skirt, and 🧁/🛌 read as cupcake/person-in-bed.

/**
 * First-letter dataset: exactly one clean, unambiguous word per letter.
 * Accents that sit mid-word are fine; initials are kept plain so the tile
 * letter is never in question for a beginner.
 */
val LETTER_WORDS: List<LetterWord> = listOf(
    LetterWord(letter = "A", word = "Avion", emoji = "✈️"),
    LetterWord(letter = "B", word = "Ballon", emoji = "⚽"),
    LetterWord(letter = "C", word = "Chat", emoji = "🐱"),
    LetterWord(letter = "D", word = "Dauphin", emoji = "🐬"),
    LetterWord(letter = "E", word = "Escargot", emoji = "🐌"),
    LetterWord(letter = "F", word = "Fleur", emoji = "🌸"),
    LetterWord(letter = "G", word = "Gâteau", emoji = "🍰"),
    LetterWord(letter = "H", word = "Hibou", emoji = "🦉"),
    LetterWord(letter = "I", word = "Igloo", emoji = "🛖", img = ImageKey.IGLOO),
    LetterWord(letter = "J", word = "Jus", emoji = "🧃"),
    LetterWord(letter = "K", word = "Koala", emoji = "🐨"),
    LetterWord(letter = "L", word = "Lune", emoji = "🌙"),
    LetterWord(letter = "M", word = "Maison", emoji = "🏠"),
    LetterWord(letter = "N", word = "Nuage", emoji = "☁️"),
    LetterWord(letter = "O", word = "Orange", emoji = "🍊"),
    LetterWord(letter = "P", word = "Pomme", emoji = "🍎"),
    LetterWord(letter = "R", word = "Robot", emoji = "🤖"),
    LetterWord(letter = "S", word = "Soleil", emoji = "☀️"),
    LetterWord(letter = "T", word = "Tortue", emoji = "🐢"),
    LetterWord(letter = "V", word = "Vache", emoji = "🐮"),
    LetterWord(letter = "Z", word = "Zèbre", emoji = "🦓"),
    // A second clean word per letter — bigger pools ⇒ more variety in a run.
    LetterWord(letter = "A", word = "Abeille", emoji = "🐝"),
    LetterWord(letter = "B", word = "Banane", emoji = "🍌"),
    LetterWord(letter = "C", word = "Carotte", emoji = "🥕"),
    LetterWord(letter = "D", word = "Dé", emoji = "🎲"),
    LetterWord(letter = "E", word = "Éléphant", emoji = "🐘"),
    LetterWord(letter = "F", word = "Fraise", emoji = "🍓"),
    LetterWord(letter = "G", word = "Girafe", emoji = "🦒"),
    LetterWord(letter = "H", word = "Hélicoptère", emoji = "🚁"),
    LetterWord(letter = "I", word = "Île", emoji = "🏝️"),
    LetterWord(letter = "J", word = "Jupe", emoji = "👗", img = ImageKey.JUPE),
    LetterWord(letter = "K", word = "Kiwi", emoji = "🥝"),
    LetterWord(letter = "L", word = "Lion", emoji = "🦁"),
    LetterWord(letter = "M", word = "Moto", emoji = "🏍️"),
    LetterWord(letter = "N", word = "Nid", emoji = "🪺"),
    LetterWord(letter = "O", word = "Ours", emoji = "🐻"),
    LetterWord(letter = "P", word = "Poisson", emoji = "🐟"),
    LetterWord(letter = "R", word = "Renard", emoji = "🦊"),
    LetterWord(letter = "S", word = "Serpent", emoji = "🐍"),
    LetterWord(letter = "T", word = "Tigre", emoji = "🐯"),
    LetterWord(letter = "V", word = "Voiture", emoji = "🚗"),
    LetterWord(letter = "Z", word = "Zéro", emoji = "0️⃣"),
)

/**
 * The full letter set for the letter-form matching games (majuscule ⇄ minuscule,
 * script ⇄ cursive). Uppercase canonical; the round builder cases + styles each
 * face. This is the pool the null (final) match level draws from.
 */
val LETTER_MATCH_ALPHABET: List<String> = listOf(
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
    "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
)
