import type { LetterWord, SyllableWord } from "./types";

/**
 * First-letter dataset: exactly one clean, unambiguous word per letter.
 * Accents that sit mid-word are fine; initials are kept plain so the tile
 * letter is never in question for a beginner.
 */
export const LETTER_WORDS: LetterWord[] = [
  { letter: "A", word: "Avion", emoji: "✈️" },
  { letter: "B", word: "Ballon", emoji: "⚽" },
  { letter: "C", word: "Chat", emoji: "🐱" },
  { letter: "D", word: "Dauphin", emoji: "🐬" },
  { letter: "E", word: "Escargot", emoji: "🐌" },
  { letter: "F", word: "Fleur", emoji: "🌸" },
  { letter: "G", word: "Gâteau", emoji: "🍰" },
  { letter: "H", word: "Hibou", emoji: "🦉" },
  { letter: "I", word: "Igloo", emoji: "🛖" },
  { letter: "J", word: "Jus", emoji: "🧃" },
  { letter: "K", word: "Koala", emoji: "🐨" },
  { letter: "L", word: "Lune", emoji: "🌙" },
  { letter: "M", word: "Maison", emoji: "🏠" },
  { letter: "N", word: "Nuage", emoji: "☁️" },
  { letter: "O", word: "Orange", emoji: "🍊" },
  { letter: "P", word: "Pomme", emoji: "🍎" },
  { letter: "R", word: "Robot", emoji: "🤖" },
  { letter: "S", word: "Soleil", emoji: "☀️" },
  { letter: "T", word: "Tortue", emoji: "🐢" },
  { letter: "V", word: "Vache", emoji: "🐮" },
  { letter: "Z", word: "Zèbre", emoji: "🦓" },
];

/**
 * Syllable dataset: split is authored, not computed. Splits favour simple,
 * open syllables so a child can sound each tile out.
 */
export const SYLLABLE_WORDS: SyllableWord[] = [
  // 2 syllables
  { word: "CHATON", syllables: ["CHA", "TON"], emoji: "🐱" },
  { word: "LAPIN", syllables: ["LA", "PIN"], emoji: "🐰" },
  { word: "SOLEIL", syllables: ["SO", "LEIL"], emoji: "☀️" },
  { word: "BATEAU", syllables: ["BA", "TEAU"], emoji: "⛵" },
  { word: "GÂTEAU", syllables: ["GÂ", "TEAU"], emoji: "🍰" },
  { word: "POISSON", syllables: ["POIS", "SON"], emoji: "🐟" },
  { word: "MAISON", syllables: ["MAI", "SON"], emoji: "🏠" },
  { word: "CANARD", syllables: ["CA", "NARD"], emoji: "🦆" },
  { word: "TORTUE", syllables: ["TOR", "TUE"], emoji: "🐢" },
  { word: "VOITURE", syllables: ["VOI", "TURE"], emoji: "🚗" },
  { word: "SOURIS", syllables: ["SOU", "RIS"], emoji: "🐭" },
  { word: "BANANE", syllables: ["BA", "NANE"], emoji: "🍌" },
  { word: "CAROTTE", syllables: ["CA", "ROTTE"], emoji: "🥕" },
  { word: "DAUPHIN", syllables: ["DAU", "PHIN"], emoji: "🐬" },
  { word: "ROBOT", syllables: ["RO", "BOT"], emoji: "🤖" },
  { word: "MOUTON", syllables: ["MOU", "TON"], emoji: "🐑" },
  { word: "COCHON", syllables: ["CO", "CHON"], emoji: "🐷" },
  { word: "CITRON", syllables: ["CI", "TRON"], emoji: "🍋" },
  { word: "TOMATE", syllables: ["TO", "MATE"], emoji: "🍅" },
  // 3 syllables
  { word: "PAPILLON", syllables: ["PA", "PIL", "LON"], emoji: "🦋" },
  { word: "ÉLÉPHANT", syllables: ["É", "LÉ", "PHANT"], emoji: "🐘" },
  { word: "CROCODILE", syllables: ["CRO", "CO", "DILE"], emoji: "🐊" },
  { word: "CHOCOLAT", syllables: ["CHO", "CO", "LAT"], emoji: "🍫" },
  { word: "TÉLÉPHONE", syllables: ["TÉ", "LÉ", "PHONE"], emoji: "📱" },
  { word: "PARAPLUIE", syllables: ["PA", "RA", "PLUIE"], emoji: "☂️" },
  { word: "BIBERON", syllables: ["BI", "BE", "RON"], emoji: "🍼" },
  { word: "KOALA", syllables: ["KO", "A", "LA"], emoji: "🐨" },
  { word: "DINOSAURE", syllables: ["DI", "NO", "SAURE"], emoji: "🦕" },
  // 4 syllables
  { word: "ORDINATEUR", syllables: ["OR", "DI", "NA", "TEUR"], emoji: "💻" },
  { word: "TÉLÉVISION", syllables: ["TÉ", "LÉ", "VI", "SION"], emoji: "📺" },
  { word: "HÉLICOPTÈRE", syllables: ["HÉ", "LI", "COP", "TÈRE"], emoji: "🚁" },
];

/** Every distinct syllable in the corpus — the source for wrong-answer tiles. */
export const SYLLABLE_BANK: string[] = Array.from(
  new Set(SYLLABLE_WORDS.flatMap((w) => w.syllables))
);
