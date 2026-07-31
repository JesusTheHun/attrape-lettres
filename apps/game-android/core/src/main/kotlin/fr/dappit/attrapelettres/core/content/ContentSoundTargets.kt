package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.SoundTarget

// `SOUND_TARGETS` from `src/content.ts` — 5 pools, 133 rows.
//
// One literal with the level comments inline, exactly the TS layout. (The Swift
// port splits this table into five `static let`s to appease its type-checker;
// Kotlin needs no such escape hatch, so the source of truth's shape survives.)

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
val SOUND_TARGETS: List<List<SoundTarget>> = listOf(
    // Level 1 — two letters, the right sound. Open CV syllables, no accents.
    listOf(
        SoundTarget(sound = "la", spelling = listOf("L", "A")),
        SoundTarget(sound = "li", spelling = listOf("L", "I")),
        SoundTarget(sound = "lo", spelling = listOf("L", "O")),
        SoundTarget(sound = "lu", spelling = listOf("L", "U")),
        SoundTarget(sound = "ma", spelling = listOf("M", "A")),
        SoundTarget(sound = "mi", spelling = listOf("M", "I")),
        SoundTarget(sound = "mo", spelling = listOf("M", "O")),
        SoundTarget(sound = "mu", spelling = listOf("M", "U")),
        SoundTarget(sound = "na", spelling = listOf("N", "A")),
        SoundTarget(sound = "ni", spelling = listOf("N", "I")),
        SoundTarget(sound = "no", spelling = listOf("N", "O")),
        SoundTarget(sound = "nu", spelling = listOf("N", "U")),
        SoundTarget(sound = "ra", spelling = listOf("R", "A")),
        SoundTarget(sound = "ri", spelling = listOf("R", "I")),
        SoundTarget(sound = "ro", spelling = listOf("R", "O")),
        SoundTarget(sound = "ru", spelling = listOf("R", "U")),
        SoundTarget(sound = "ta", spelling = listOf("T", "A")),
        SoundTarget(sound = "ti", spelling = listOf("T", "I")),
        SoundTarget(sound = "to", spelling = listOf("T", "O")),
        SoundTarget(sound = "tu", spelling = listOf("T", "U")),
        SoundTarget(sound = "pa", spelling = listOf("P", "A")),
        SoundTarget(sound = "pi", spelling = listOf("P", "I")),
        SoundTarget(sound = "po", spelling = listOf("P", "O")),
        SoundTarget(sound = "pu", spelling = listOf("P", "U")),
        SoundTarget(sound = "ba", spelling = listOf("B", "A")),
        SoundTarget(sound = "bi", spelling = listOf("B", "I")),
        SoundTarget(sound = "bo", spelling = listOf("B", "O")),
        SoundTarget(sound = "bu", spelling = listOf("B", "U")),
        SoundTarget(sound = "da", spelling = listOf("D", "A")),
        SoundTarget(sound = "di", spelling = listOf("D", "I")),
        SoundTarget(sound = "do", spelling = listOf("D", "O")),
        SoundTarget(sound = "du", spelling = listOf("D", "U")),
    ),
    // Level 2 — same two-letter shape, fricative consonants + the é vowel; +intruders.
    listOf(
        SoundTarget(sound = "fa", spelling = listOf("F", "A")),
        SoundTarget(sound = "fi", spelling = listOf("F", "I")),
        SoundTarget(sound = "fo", spelling = listOf("F", "O")),
        SoundTarget(sound = "fu", spelling = listOf("F", "U")),
        SoundTarget(sound = "sa", spelling = listOf("S", "A")),
        SoundTarget(sound = "si", spelling = listOf("S", "I")),
        SoundTarget(sound = "so", spelling = listOf("S", "O")),
        SoundTarget(sound = "su", spelling = listOf("S", "U")),
        SoundTarget(sound = "va", spelling = listOf("V", "A")),
        SoundTarget(sound = "vi", spelling = listOf("V", "I")),
        SoundTarget(sound = "vo", spelling = listOf("V", "O")),
        SoundTarget(sound = "vu", spelling = listOf("V", "U")),
        SoundTarget(sound = "ja", spelling = listOf("J", "A")),
        SoundTarget(sound = "ji", spelling = listOf("J", "I")),
        SoundTarget(sound = "jo", spelling = listOf("J", "O")),
        SoundTarget(sound = "ju", spelling = listOf("J", "U")),
        SoundTarget(sound = "za", spelling = listOf("Z", "A")),
        SoundTarget(sound = "zi", spelling = listOf("Z", "I")),
        SoundTarget(sound = "zo", spelling = listOf("Z", "O")),
        SoundTarget(sound = "zu", spelling = listOf("Z", "U")),
        SoundTarget(sound = "fé", spelling = listOf("F", "É")),
        SoundTarget(sound = "sé", spelling = listOf("S", "É")),
        SoundTarget(sound = "vé", spelling = listOf("V", "É")),
        SoundTarget(sound = "lé", spelling = listOf("L", "É")),
        SoundTarget(sound = "mé", spelling = listOf("M", "É")),
        SoundTarget(sound = "ré", spelling = listOf("R", "É")),
        SoundTarget(sound = "té", spelling = listOf("T", "É")),
        SoundTarget(sound = "né", spelling = listOf("N", "É")),
    ),
    // Level 3 — longer sounds: digraph CH, blends (CR/TR/PL…), OU, each from a real word.
    listOf(
        SoundTarget(sound = "cha", spelling = listOf("C", "H", "A"), word = "chat", emoji = "🐱"),
        SoundTarget(sound = "chi", spelling = listOf("C", "H", "I"), word = "chien", emoji = "🐕"),
        SoundTarget(sound = "cho", spelling = listOf("C", "H", "O"), word = "chocolat", emoji = "🍫"),
        SoundTarget(sound = "che", spelling = listOf("C", "H", "E"), word = "cheval", emoji = "🐴"),
        SoundTarget(sound = "chou", spelling = listOf("C", "H", "O", "U"), word = "chou", emoji = "🥬"),
        SoundTarget(sound = "cra", spelling = listOf("C", "R", "A"), word = "crabe", emoji = "🦀"),
        SoundTarget(sound = "cro", spelling = listOf("C", "R", "O"), word = "crocodile", emoji = "🐊"),
        SoundTarget(sound = "tra", spelling = listOf("T", "R", "A"), word = "train", emoji = "🚂"),
        SoundTarget(sound = "dra", spelling = listOf("D", "R", "A"), word = "dragon", emoji = "🐉"),
        SoundTarget(sound = "fra", spelling = listOf("F", "R", "A"), word = "fraise", emoji = "🍓"),
        SoundTarget(sound = "fro", spelling = listOf("F", "R", "O"), word = "fromage", emoji = "🧀"),
        SoundTarget(sound = "bra", spelling = listOf("B", "R", "A"), word = "bras", emoji = "💪"),
        SoundTarget(sound = "bri", spelling = listOf("B", "R", "I"), word = "brique", emoji = "🧱"),
        SoundTarget(sound = "pri", spelling = listOf("P", "R", "I"), word = "prince", emoji = "🤴"),
        SoundTarget(sound = "pla", spelling = listOf("P", "L", "A"), word = "plage", emoji = "🏖️"),
        SoundTarget(sound = "plu", spelling = listOf("P", "L", "U"), word = "pluie", emoji = "☔"),
        SoundTarget(sound = "gla", spelling = listOf("G", "L", "A"), word = "glace", emoji = "🍦"),
        SoundTarget(sound = "clé", spelling = listOf("C", "L", "É"), word = "clé", emoji = "🔑"),
        SoundTarget(sound = "lou", spelling = listOf("L", "O", "U"), word = "loup", emoji = "🐺"),
        SoundTarget(sound = "rou", spelling = listOf("R", "O", "U"), word = "roue", emoji = "🛞"),
        SoundTarget(sound = "pou", spelling = listOf("P", "O", "U"), word = "poule", emoji = "🐔"),
        SoundTarget(sound = "sou", spelling = listOf("S", "O", "U"), word = "souris", emoji = "🐭"),
        SoundTarget(sound = "bou", spelling = listOf("B", "O", "U"), word = "bouche", emoji = "👄"),
        SoundTarget(sound = "mou", spelling = listOf("M", "O", "U"), word = "mouton", emoji = "🐑"),
    ),
    // Level 4 — several letters, one sound (vowel teams & nasals). Same team, many words.
    listOf(
        SoundTarget(sound = "oi", spelling = listOf("O", "I"), word = "roi", emoji = "👑"),
        SoundTarget(sound = "oi", spelling = listOf("O", "I"), word = "poisson", emoji = "🐟"),
        SoundTarget(sound = "oi", spelling = listOf("O", "I"), word = "étoile", emoji = "⭐"),
        SoundTarget(sound = "oi", spelling = listOf("O", "I"), word = "noix", emoji = "🥜"),
        // Not "auto": an anchor must contain its target sound ONCE. /oto/ has two
        // /o/, and the second is spelled O — so « au, comme dans auto » points at
        // the very spelling the child is being asked to tell AU apart from.
        SoundTarget(sound = "au", spelling = listOf("A", "U"), word = "faucon", emoji = "🦅"),
        SoundTarget(sound = "au", spelling = listOf("A", "U"), word = "jaune", emoji = "💛"),
        SoundTarget(sound = "au", spelling = listOf("A", "U"), word = "sauter", emoji = "🦘"),
        SoundTarget(sound = "au", spelling = listOf("A", "U"), word = "chaud", emoji = "🥵"),
        SoundTarget(sound = "eu", spelling = listOf("E", "U"), word = "feu", emoji = "🔥"),
        SoundTarget(sound = "eu", spelling = listOf("E", "U"), word = "jeu", emoji = "🎲"),
        SoundTarget(sound = "eu", spelling = listOf("E", "U"), word = "deux", emoji = "✌️"),
        SoundTarget(sound = "eu", spelling = listOf("E", "U"), word = "bleu", emoji = "🔵"),
        SoundTarget(sound = "on", spelling = listOf("O", "N"), word = "bonbon", emoji = "🍬"),
        SoundTarget(sound = "on", spelling = listOf("O", "N"), word = "pont", emoji = "🌉"),
        SoundTarget(sound = "on", spelling = listOf("O", "N"), word = "rond", emoji = "⭕"),
        SoundTarget(sound = "on", spelling = listOf("O", "N"), word = "mouton", emoji = "🐑"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "gant", emoji = "🧤"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "manteau", emoji = "🧥"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "orange", emoji = "🍊"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "chanter", emoji = "🎤"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "pin", emoji = "🌲"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "lapin", emoji = "🐰"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "sapin", emoji = "🎄"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "requin", emoji = "🦈"),
    ),
    // Level 5 — the SAME sound, written several ways. Pure memory.
    listOf(
        SoundTarget(sound = "o", spelling = listOf("O"), word = "moto", emoji = "🏍️"),
        SoundTarget(sound = "o", spelling = listOf("O"), word = "vélo", emoji = "🚲"),
        SoundTarget(sound = "o", spelling = listOf("A", "U"), word = "jaune", emoji = "💛"),
        SoundTarget(sound = "o", spelling = listOf("E", "A", "U"), word = "eau", emoji = "💧"),
        SoundTarget(sound = "o", spelling = listOf("E", "A", "U"), word = "bateau", emoji = "⛵"),
        SoundTarget(sound = "o", spelling = listOf("E", "A", "U"), word = "gâteau", emoji = "🍰"),
        SoundTarget(sound = "fo", spelling = listOf("F", "O"), word = "forêt", emoji = "🌳"),
        SoundTarget(sound = "fo", spelling = listOf("P", "H", "O"), word = "photo", emoji = "📷"),
        SoundTarget(sound = "fa", spelling = listOf("F", "A"), word = "farine", emoji = "🌾"),
        SoundTarget(sound = "fa", spelling = listOf("P", "H", "A"), word = "pharmacie", emoji = "💊"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "lapin", emoji = "🐰"),
        SoundTarget(sound = "in", spelling = listOf("I", "N"), word = "sapin", emoji = "🎄"),
        SoundTarget(sound = "in", spelling = listOf("A", "I", "N"), word = "main", emoji = "✋"),
        SoundTarget(sound = "in", spelling = listOf("A", "I", "N"), word = "pain", emoji = "🥖"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "gant", emoji = "🧤"),
        SoundTarget(sound = "an", spelling = listOf("A", "N"), word = "éléphant", emoji = "🐘"),
        SoundTarget(sound = "an", spelling = listOf("E", "N"), word = "dent", emoji = "🦷"),
        SoundTarget(sound = "an", spelling = listOf("E", "N"), word = "content", emoji = "😊"),
        SoundTarget(sound = "ka", spelling = listOf("C", "A"), word = "cadeau", emoji = "🎁"),
        SoundTarget(sound = "ko", spelling = listOf("K", "O"), word = "koala", emoji = "🐨"),
        SoundTarget(sound = "ki", spelling = listOf("Q", "U", "I"), word = "qui", emoji = "❓"),
        SoundTarget(sound = "ko", spelling = listOf("C", "O"), word = "coq", emoji = "🐓"),
        SoundTarget(sound = "si", spelling = listOf("C", "I"), word = "citron", emoji = "🍋"),
        SoundTarget(sound = "so", spelling = listOf("S", "O"), word = "soleil", emoji = "☀️"),
        SoundTarget(sound = "si", spelling = listOf("S", "I"), word = "singe", emoji = "🐒"),
    ),
)
