import type { LetterWord, SoundTarget, SyllableWord } from "./types";

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
  // A second clean word per letter — bigger pools ⇒ more variety in a run.
  { letter: "A", word: "Abeille", emoji: "🐝" },
  { letter: "B", word: "Banane", emoji: "🍌" },
  { letter: "C", word: "Carotte", emoji: "🥕" },
  { letter: "D", word: "Dé", emoji: "🎲" },
  { letter: "E", word: "Éléphant", emoji: "🐘" },
  { letter: "F", word: "Fraise", emoji: "🍓" },
  { letter: "G", word: "Girafe", emoji: "🦒" },
  { letter: "H", word: "Hélicoptère", emoji: "🚁" },
  { letter: "I", word: "Île", emoji: "🏝️" },
  { letter: "J", word: "Jupe", emoji: "👗" },
  { letter: "K", word: "Kiwi", emoji: "🥝" },
  { letter: "L", word: "Lion", emoji: "🦁" },
  { letter: "M", word: "Moto", emoji: "🏍️" },
  { letter: "N", word: "Nid", emoji: "🪺" },
  { letter: "O", word: "Ours", emoji: "🐻" },
  { letter: "P", word: "Poisson", emoji: "🐟" },
  { letter: "R", word: "Renard", emoji: "🦊" },
  { letter: "S", word: "Serpent", emoji: "🐍" },
  { letter: "T", word: "Tigre", emoji: "🐯" },
  { letter: "V", word: "Voiture", emoji: "🚗" },
  { letter: "Z", word: "Zéro", emoji: "0️⃣" },
];

/**
 * The full letter set for the letter-form matching games (majuscule ⇄ minuscule,
 * script ⇄ cursive). Uppercase canonical; the round builder cases + styles each
 * face. This is the pool the null (final) match level draws from.
 */
export const LETTER_MATCH_ALPHABET: string[] = [
  "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
  "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
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
  // --- Bigger pools: more words per tier for variety in a run. ---
  // 2 syllables
  { word: "PANDA", syllables: ["PAN", "DA"], emoji: "🐼" },
  { word: "FUSÉE", syllables: ["FU", "SÉE"], emoji: "🚀" },
  // 3 syllables
  { word: "PYJAMA", syllables: ["PY", "JA", "MA"], emoji: "🛌" },
  { word: "CINÉMA", syllables: ["CI", "NÉ", "MA"], emoji: "🎬" },
  { word: "KANGOUROU", syllables: ["KAN", "GOU", "ROU"], emoji: "🦘" },
  { word: "PANTALON", syllables: ["PAN", "TA", "LON"], emoji: "👖" },
  { word: "ANANAS", syllables: ["A", "NA", "NAS"], emoji: "🍍" },
  { word: "HÔPITAL", syllables: ["HÔ", "PI", "TAL"], emoji: "🏥" },
  { word: "MACARON", syllables: ["MA", "CA", "RON"], emoji: "🧁" },
  // 4 syllables
  { word: "LOCOMOTIVE", syllables: ["LO", "CO", "MO", "TIVE"], emoji: "🚂" },
  { word: "ANNIVERSAIRE", syllables: ["AN", "NI", "VER", "SAIRE"], emoji: "🎂" },
  { word: "HIPPOPOTAME", syllables: ["HIP", "PO", "PO", "TAME"], emoji: "🦛" },
  { word: "SUPERMARCHÉ", syllables: ["SU", "PER", "MAR", "CHÉ"], emoji: "🛒" },
  { word: "AQUARIUM", syllables: ["A", "QUA", "RI", "UM"], emoji: "🐠" },
];

/** Every distinct syllable in the corpus — the source for wrong-answer tiles. */
export const SYLLABLE_BANK: string[] = Array.from(
  new Set(SYLLABLE_WORDS.flatMap((w) => w.syllables))
);

/**
 * Fill-a-syllable ladder — the word list per level, by name (resolved against
 * SYLLABLE_WORDS in levels.ts so the authored split + emoji + baked VO are
 * reused, never duplicated). The SAME list feeds all three siblings ("écris la
 * syllabe" / "…avec des intrus" / "…deux syllabes"), so the child meets the same
 * words as the task hardens. Every word has ≥3 syllables so the two-syllable
 * sibling always leaves a written anchor. Level 1 stays tiny (5 words) for a fast
 * win → confidence loop. Word count grows and 4-syllable words arrive last.
 */
export const SPELL_SYLLABLE_WORD_NAMES: string[][] = [
  ["PYJAMA", "PANTALON", "CHOCOLAT", "CINÉMA", "MACARON"],
  ["BIBERON", "PAPILLON", "CROCODILE", "KANGOUROU", "DINOSAURE", "PARAPLUIE"],
  ["TÉLÉPHONE", "ÉLÉPHANT", "ANANAS", "KOALA", "HÔPITAL", "PAPILLON", "CROCODILE", "DINOSAURE"],
  ["ORDINATEUR", "HÉLICOPTÈRE", "HIPPOPOTAME", "LOCOMOTIVE", "ANNIVERSAIRE", "AQUARIUM", "SUPERMARCHÉ", "TÉLÉVISION"],
];

/**
 * Spell-the-sound dataset — one authored pool per level (index = level - 1).
 * Everything is content: the sound spoken, the letters that spell it, and (from
 * level 3) the word + emoji giving the sound a context.
 *
 *   1 — Deux lettres : simple open syllables, exact letters only.
 *   2 — Trouve les bonnes lettres : same, but the tray hides some intruders.
 *   3 — Des sons plus longs : blends & digraphs, each drawn from a real word.
 *   4 — Sons compliqués : vowel teams where several letters make ONE sound.
 *   5 — Le même son, plusieurs façons : the same sound, spelled many ways
 *       (o / au / eau, fo / pho, in / ain, an / en) — pure memory training.
 *
 * `sound` is lowercase so the fallback TTS reads it as a syllable, not letters;
 * `spelling` is uppercase to match the letter tiles the child taps.
 */
export const SOUND_TARGETS: SoundTarget[][] = [
  // Level 1 — two letters, the right sound. Open CV syllables, no accents.
  [
    { sound: "la", spelling: ["L", "A"] },
    { sound: "li", spelling: ["L", "I"] },
    { sound: "lo", spelling: ["L", "O"] },
    { sound: "lu", spelling: ["L", "U"] },
    { sound: "ma", spelling: ["M", "A"] },
    { sound: "mi", spelling: ["M", "I"] },
    { sound: "mo", spelling: ["M", "O"] },
    { sound: "mu", spelling: ["M", "U"] },
    { sound: "na", spelling: ["N", "A"] },
    { sound: "ni", spelling: ["N", "I"] },
    { sound: "no", spelling: ["N", "O"] },
    { sound: "nu", spelling: ["N", "U"] },
    { sound: "ra", spelling: ["R", "A"] },
    { sound: "ri", spelling: ["R", "I"] },
    { sound: "ro", spelling: ["R", "O"] },
    { sound: "ru", spelling: ["R", "U"] },
    { sound: "ta", spelling: ["T", "A"] },
    { sound: "ti", spelling: ["T", "I"] },
    { sound: "to", spelling: ["T", "O"] },
    { sound: "tu", spelling: ["T", "U"] },
    { sound: "pa", spelling: ["P", "A"] },
    { sound: "pi", spelling: ["P", "I"] },
    { sound: "po", spelling: ["P", "O"] },
    { sound: "pu", spelling: ["P", "U"] },
    { sound: "ba", spelling: ["B", "A"] },
    { sound: "bi", spelling: ["B", "I"] },
    { sound: "bo", spelling: ["B", "O"] },
    { sound: "bu", spelling: ["B", "U"] },
    { sound: "da", spelling: ["D", "A"] },
    { sound: "di", spelling: ["D", "I"] },
    { sound: "do", spelling: ["D", "O"] },
    { sound: "du", spelling: ["D", "U"] },
  ],
  // Level 2 — same two-letter shape, fricative consonants + the é vowel; +intruders.
  [
    { sound: "fa", spelling: ["F", "A"] },
    { sound: "fi", spelling: ["F", "I"] },
    { sound: "fo", spelling: ["F", "O"] },
    { sound: "fu", spelling: ["F", "U"] },
    { sound: "sa", spelling: ["S", "A"] },
    { sound: "si", spelling: ["S", "I"] },
    { sound: "so", spelling: ["S", "O"] },
    { sound: "su", spelling: ["S", "U"] },
    { sound: "va", spelling: ["V", "A"] },
    { sound: "vi", spelling: ["V", "I"] },
    { sound: "vo", spelling: ["V", "O"] },
    { sound: "vu", spelling: ["V", "U"] },
    { sound: "ja", spelling: ["J", "A"] },
    { sound: "ji", spelling: ["J", "I"] },
    { sound: "jo", spelling: ["J", "O"] },
    { sound: "ju", spelling: ["J", "U"] },
    { sound: "za", spelling: ["Z", "A"] },
    { sound: "zi", spelling: ["Z", "I"] },
    { sound: "zo", spelling: ["Z", "O"] },
    { sound: "zu", spelling: ["Z", "U"] },
    { sound: "fé", spelling: ["F", "É"] },
    { sound: "sé", spelling: ["S", "É"] },
    { sound: "vé", spelling: ["V", "É"] },
    { sound: "lé", spelling: ["L", "É"] },
    { sound: "mé", spelling: ["M", "É"] },
    { sound: "ré", spelling: ["R", "É"] },
    { sound: "té", spelling: ["T", "É"] },
    { sound: "né", spelling: ["N", "É"] },
  ],
  // Level 3 — longer sounds: digraph CH, blends (CR/TR/PL…), OU, each from a real word.
  [
    { sound: "cha", spelling: ["C", "H", "A"], word: "chat", emoji: "🐱" },
    { sound: "chi", spelling: ["C", "H", "I"], word: "chien", emoji: "🐕" },
    { sound: "cho", spelling: ["C", "H", "O"], word: "chocolat", emoji: "🍫" },
    { sound: "che", spelling: ["C", "H", "E"], word: "cheval", emoji: "🐴" },
    { sound: "chou", spelling: ["C", "H", "O", "U"], word: "chou", emoji: "🥬" },
    { sound: "cra", spelling: ["C", "R", "A"], word: "crabe", emoji: "🦀" },
    { sound: "cro", spelling: ["C", "R", "O"], word: "crocodile", emoji: "🐊" },
    { sound: "tra", spelling: ["T", "R", "A"], word: "train", emoji: "🚂" },
    { sound: "dra", spelling: ["D", "R", "A"], word: "dragon", emoji: "🐉" },
    { sound: "fra", spelling: ["F", "R", "A"], word: "fraise", emoji: "🍓" },
    { sound: "fro", spelling: ["F", "R", "O"], word: "fromage", emoji: "🧀" },
    { sound: "bra", spelling: ["B", "R", "A"], word: "bras", emoji: "💪" },
    { sound: "bri", spelling: ["B", "R", "I"], word: "brique", emoji: "🧱" },
    { sound: "pri", spelling: ["P", "R", "I"], word: "prince", emoji: "🤴" },
    { sound: "pla", spelling: ["P", "L", "A"], word: "plage", emoji: "🏖️" },
    { sound: "plu", spelling: ["P", "L", "U"], word: "pluie", emoji: "☔" },
    { sound: "gla", spelling: ["G", "L", "A"], word: "glace", emoji: "🍦" },
    { sound: "clé", spelling: ["C", "L", "É"], word: "clé", emoji: "🔑" },
    { sound: "lou", spelling: ["L", "O", "U"], word: "loup", emoji: "🐺" },
    { sound: "rou", spelling: ["R", "O", "U"], word: "roue", emoji: "🛞" },
    { sound: "pou", spelling: ["P", "O", "U"], word: "poule", emoji: "🐔" },
    { sound: "sou", spelling: ["S", "O", "U"], word: "souris", emoji: "🐭" },
    { sound: "bou", spelling: ["B", "O", "U"], word: "bouche", emoji: "👄" },
    { sound: "mou", spelling: ["M", "O", "U"], word: "mouton", emoji: "🐑" },
  ],
  // Level 4 — several letters, one sound (vowel teams & nasals). Same team, many words.
  [
    { sound: "oi", spelling: ["O", "I"], word: "roi", emoji: "👑" },
    { sound: "oi", spelling: ["O", "I"], word: "poisson", emoji: "🐟" },
    { sound: "oi", spelling: ["O", "I"], word: "étoile", emoji: "⭐" },
    { sound: "oi", spelling: ["O", "I"], word: "noix", emoji: "🥜" },
    { sound: "au", spelling: ["A", "U"], word: "auto", emoji: "🚗" },
    { sound: "au", spelling: ["A", "U"], word: "jaune", emoji: "💛" },
    { sound: "au", spelling: ["A", "U"], word: "sauter", emoji: "🦘" },
    { sound: "au", spelling: ["A", "U"], word: "chaud", emoji: "🥵" },
    { sound: "eu", spelling: ["E", "U"], word: "feu", emoji: "🔥" },
    { sound: "eu", spelling: ["E", "U"], word: "jeu", emoji: "🎲" },
    { sound: "eu", spelling: ["E", "U"], word: "deux", emoji: "✌️" },
    { sound: "eu", spelling: ["E", "U"], word: "bleu", emoji: "🔵" },
    { sound: "on", spelling: ["O", "N"], word: "bonbon", emoji: "🍬" },
    { sound: "on", spelling: ["O", "N"], word: "pont", emoji: "🌉" },
    { sound: "on", spelling: ["O", "N"], word: "rond", emoji: "⭕" },
    { sound: "on", spelling: ["O", "N"], word: "mouton", emoji: "🐑" },
    { sound: "an", spelling: ["A", "N"], word: "gant", emoji: "🧤" },
    { sound: "an", spelling: ["A", "N"], word: "manteau", emoji: "🧥" },
    { sound: "an", spelling: ["A", "N"], word: "orange", emoji: "🍊" },
    { sound: "an", spelling: ["A", "N"], word: "chanter", emoji: "🎤" },
    { sound: "in", spelling: ["I", "N"], word: "pin", emoji: "🌲" },
    { sound: "in", spelling: ["I", "N"], word: "lapin", emoji: "🐰" },
    { sound: "in", spelling: ["I", "N"], word: "sapin", emoji: "🎄" },
    { sound: "in", spelling: ["I", "N"], word: "requin", emoji: "🦈" },
  ],
  // Level 5 — the SAME sound, written several ways. Pure memory.
  [
    { sound: "o", spelling: ["O"], word: "moto", emoji: "🏍️" },
    { sound: "o", spelling: ["O"], word: "vélo", emoji: "🚲" },
    { sound: "o", spelling: ["A", "U"], word: "auto", emoji: "🚗" },
    { sound: "o", spelling: ["E", "A", "U"], word: "eau", emoji: "💧" },
    { sound: "o", spelling: ["E", "A", "U"], word: "bateau", emoji: "⛵" },
    { sound: "o", spelling: ["E", "A", "U"], word: "gâteau", emoji: "🍰" },
    { sound: "fo", spelling: ["F", "O"], word: "forêt", emoji: "🌳" },
    { sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷" },
    { sound: "fa", spelling: ["F", "A"], word: "farine", emoji: "🌾" },
    { sound: "fa", spelling: ["P", "H", "A"], word: "pharmacie", emoji: "💊" },
    { sound: "in", spelling: ["I", "N"], word: "lapin", emoji: "🐰" },
    { sound: "in", spelling: ["I", "N"], word: "sapin", emoji: "🎄" },
    { sound: "in", spelling: ["A", "I", "N"], word: "main", emoji: "✋" },
    { sound: "in", spelling: ["A", "I", "N"], word: "pain", emoji: "🥖" },
    { sound: "an", spelling: ["A", "N"], word: "gant", emoji: "🧤" },
    { sound: "an", spelling: ["A", "N"], word: "éléphant", emoji: "🐘" },
    { sound: "an", spelling: ["E", "N"], word: "dent", emoji: "🦷" },
    { sound: "an", spelling: ["E", "N"], word: "content", emoji: "😊" },
    { sound: "ka", spelling: ["C", "A"], word: "cadeau", emoji: "🎁" },
    { sound: "ko", spelling: ["K", "O"], word: "koala", emoji: "🐨" },
    { sound: "ki", spelling: ["Q", "U", "I"], word: "qui", emoji: "❓" },
    { sound: "ko", spelling: ["C", "O"], word: "coq", emoji: "🐓" },
    { sound: "si", spelling: ["C", "I"], word: "citron", emoji: "🍋" },
    { sound: "so", spelling: ["S", "O"], word: "soleil", emoji: "☀️" },
    { sound: "si", spelling: ["S", "I"], word: "singe", emoji: "🐒" },
  ],
];

/** Letters used to seed wrong-answer tiles (target letters are excluded per round). */
export const SOUND_LETTER_BANK: string[] = [
  "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
  "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
];
