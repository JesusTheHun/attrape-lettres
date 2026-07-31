// `SOUND_TARGETS` from `src/content.ts` — 5 pools, 133 rows.
//
// Split into one `static let` per level (data-core.md §7.8's escape hatch): the
// type-checker is happiest with several small explicitly-annotated literals, and
// the ladder reads level by level anyway.

extension Content {
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
    public static let soundTargets: [[SoundTarget]] = [
        soundTargetsL1, soundTargetsL2, soundTargetsL3, soundTargetsL4, soundTargetsL5,
    ]

    // Level 1 — two letters, the right sound. Open CV syllables, no accents.
    static let soundTargetsL1: [SoundTarget] = [
        SoundTarget(sound: "la", spelling: ["L", "A"]),
        SoundTarget(sound: "li", spelling: ["L", "I"]),
        SoundTarget(sound: "lo", spelling: ["L", "O"]),
        SoundTarget(sound: "lu", spelling: ["L", "U"]),
        SoundTarget(sound: "ma", spelling: ["M", "A"]),
        SoundTarget(sound: "mi", spelling: ["M", "I"]),
        SoundTarget(sound: "mo", spelling: ["M", "O"]),
        SoundTarget(sound: "mu", spelling: ["M", "U"]),
        SoundTarget(sound: "na", spelling: ["N", "A"]),
        SoundTarget(sound: "ni", spelling: ["N", "I"]),
        SoundTarget(sound: "no", spelling: ["N", "O"]),
        SoundTarget(sound: "nu", spelling: ["N", "U"]),
        SoundTarget(sound: "ra", spelling: ["R", "A"]),
        SoundTarget(sound: "ri", spelling: ["R", "I"]),
        SoundTarget(sound: "ro", spelling: ["R", "O"]),
        SoundTarget(sound: "ru", spelling: ["R", "U"]),
        SoundTarget(sound: "ta", spelling: ["T", "A"]),
        SoundTarget(sound: "ti", spelling: ["T", "I"]),
        SoundTarget(sound: "to", spelling: ["T", "O"]),
        SoundTarget(sound: "tu", spelling: ["T", "U"]),
        SoundTarget(sound: "pa", spelling: ["P", "A"]),
        SoundTarget(sound: "pi", spelling: ["P", "I"]),
        SoundTarget(sound: "po", spelling: ["P", "O"]),
        SoundTarget(sound: "pu", spelling: ["P", "U"]),
        SoundTarget(sound: "ba", spelling: ["B", "A"]),
        SoundTarget(sound: "bi", spelling: ["B", "I"]),
        SoundTarget(sound: "bo", spelling: ["B", "O"]),
        SoundTarget(sound: "bu", spelling: ["B", "U"]),
        SoundTarget(sound: "da", spelling: ["D", "A"]),
        SoundTarget(sound: "di", spelling: ["D", "I"]),
        SoundTarget(sound: "do", spelling: ["D", "O"]),
        SoundTarget(sound: "du", spelling: ["D", "U"]),
    ]

    // Level 2 — same two-letter shape, fricative consonants + the é vowel; +intruders.
    static let soundTargetsL2: [SoundTarget] = [
        SoundTarget(sound: "fa", spelling: ["F", "A"]),
        SoundTarget(sound: "fi", spelling: ["F", "I"]),
        SoundTarget(sound: "fo", spelling: ["F", "O"]),
        SoundTarget(sound: "fu", spelling: ["F", "U"]),
        SoundTarget(sound: "sa", spelling: ["S", "A"]),
        SoundTarget(sound: "si", spelling: ["S", "I"]),
        SoundTarget(sound: "so", spelling: ["S", "O"]),
        SoundTarget(sound: "su", spelling: ["S", "U"]),
        SoundTarget(sound: "va", spelling: ["V", "A"]),
        SoundTarget(sound: "vi", spelling: ["V", "I"]),
        SoundTarget(sound: "vo", spelling: ["V", "O"]),
        SoundTarget(sound: "vu", spelling: ["V", "U"]),
        SoundTarget(sound: "ja", spelling: ["J", "A"]),
        SoundTarget(sound: "ji", spelling: ["J", "I"]),
        SoundTarget(sound: "jo", spelling: ["J", "O"]),
        SoundTarget(sound: "ju", spelling: ["J", "U"]),
        SoundTarget(sound: "za", spelling: ["Z", "A"]),
        SoundTarget(sound: "zi", spelling: ["Z", "I"]),
        SoundTarget(sound: "zo", spelling: ["Z", "O"]),
        SoundTarget(sound: "zu", spelling: ["Z", "U"]),
        SoundTarget(sound: "fé", spelling: ["F", "É"]),
        SoundTarget(sound: "sé", spelling: ["S", "É"]),
        SoundTarget(sound: "vé", spelling: ["V", "É"]),
        SoundTarget(sound: "lé", spelling: ["L", "É"]),
        SoundTarget(sound: "mé", spelling: ["M", "É"]),
        SoundTarget(sound: "ré", spelling: ["R", "É"]),
        SoundTarget(sound: "té", spelling: ["T", "É"]),
        SoundTarget(sound: "né", spelling: ["N", "É"]),
    ]

    // Level 3 — longer sounds: digraph CH, blends (CR/TR/PL…), OU, each from a real word.
    static let soundTargetsL3: [SoundTarget] = [
        SoundTarget(sound: "cha", spelling: ["C", "H", "A"], word: "chat", emoji: "🐱"),
        SoundTarget(sound: "chi", spelling: ["C", "H", "I"], word: "chien", emoji: "🐕"),
        SoundTarget(sound: "cho", spelling: ["C", "H", "O"], word: "chocolat", emoji: "🍫"),
        SoundTarget(sound: "che", spelling: ["C", "H", "E"], word: "cheval", emoji: "🐴"),
        SoundTarget(sound: "chou", spelling: ["C", "H", "O", "U"], word: "chou", emoji: "🥬"),
        SoundTarget(sound: "cra", spelling: ["C", "R", "A"], word: "crabe", emoji: "🦀"),
        SoundTarget(sound: "cro", spelling: ["C", "R", "O"], word: "crocodile", emoji: "🐊"),
        SoundTarget(sound: "tra", spelling: ["T", "R", "A"], word: "train", emoji: "🚂"),
        SoundTarget(sound: "dra", spelling: ["D", "R", "A"], word: "dragon", emoji: "🐉"),
        SoundTarget(sound: "fra", spelling: ["F", "R", "A"], word: "fraise", emoji: "🍓"),
        SoundTarget(sound: "fro", spelling: ["F", "R", "O"], word: "fromage", emoji: "🧀"),
        SoundTarget(sound: "bra", spelling: ["B", "R", "A"], word: "bras", emoji: "💪"),
        SoundTarget(sound: "bri", spelling: ["B", "R", "I"], word: "brique", emoji: "🧱"),
        SoundTarget(sound: "pri", spelling: ["P", "R", "I"], word: "prince", emoji: "🤴"),
        SoundTarget(sound: "pla", spelling: ["P", "L", "A"], word: "plage", emoji: "🏖️"),
        SoundTarget(sound: "plu", spelling: ["P", "L", "U"], word: "pluie", emoji: "☔"),
        SoundTarget(sound: "gla", spelling: ["G", "L", "A"], word: "glace", emoji: "🍦"),
        SoundTarget(sound: "clé", spelling: ["C", "L", "É"], word: "clé", emoji: "🔑"),
        SoundTarget(sound: "lou", spelling: ["L", "O", "U"], word: "loup", emoji: "🐺"),
        SoundTarget(sound: "rou", spelling: ["R", "O", "U"], word: "roue", emoji: "🛞"),
        SoundTarget(sound: "pou", spelling: ["P", "O", "U"], word: "poule", emoji: "🐔"),
        SoundTarget(sound: "sou", spelling: ["S", "O", "U"], word: "souris", emoji: "🐭"),
        SoundTarget(sound: "bou", spelling: ["B", "O", "U"], word: "bouche", emoji: "👄"),
        SoundTarget(sound: "mou", spelling: ["M", "O", "U"], word: "mouton", emoji: "🐑"),
    ]

    // Level 4 — several letters, one sound (vowel teams & nasals). Same team, many words.
    static let soundTargetsL4: [SoundTarget] = [
        SoundTarget(sound: "oi", spelling: ["O", "I"], word: "roi", emoji: "👑"),
        SoundTarget(sound: "oi", spelling: ["O", "I"], word: "poisson", emoji: "🐟"),
        SoundTarget(sound: "oi", spelling: ["O", "I"], word: "étoile", emoji: "⭐"),
        SoundTarget(sound: "oi", spelling: ["O", "I"], word: "noix", emoji: "🥜"),
        // Not "auto": an anchor must contain its target sound ONCE. /oto/ has two
        // /o/, and the second is spelled O — so « au, comme dans auto » points at
        // the very spelling the child is being asked to tell AU apart from.
        SoundTarget(sound: "au", spelling: ["A", "U"], word: "faucon", emoji: "🦅"),
        SoundTarget(sound: "au", spelling: ["A", "U"], word: "jaune", emoji: "💛"),
        SoundTarget(sound: "au", spelling: ["A", "U"], word: "sauter", emoji: "🦘"),
        SoundTarget(sound: "au", spelling: ["A", "U"], word: "chaud", emoji: "🥵"),
        SoundTarget(sound: "eu", spelling: ["E", "U"], word: "feu", emoji: "🔥"),
        SoundTarget(sound: "eu", spelling: ["E", "U"], word: "jeu", emoji: "🎲"),
        SoundTarget(sound: "eu", spelling: ["E", "U"], word: "deux", emoji: "✌️"),
        SoundTarget(sound: "eu", spelling: ["E", "U"], word: "bleu", emoji: "🔵"),
        SoundTarget(sound: "on", spelling: ["O", "N"], word: "bonbon", emoji: "🍬"),
        SoundTarget(sound: "on", spelling: ["O", "N"], word: "pont", emoji: "🌉"),
        SoundTarget(sound: "on", spelling: ["O", "N"], word: "rond", emoji: "⭕"),
        SoundTarget(sound: "on", spelling: ["O", "N"], word: "mouton", emoji: "🐑"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "gant", emoji: "🧤"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "manteau", emoji: "🧥"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "orange", emoji: "🍊"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "chanter", emoji: "🎤"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "pin", emoji: "🌲"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "lapin", emoji: "🐰"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "sapin", emoji: "🎄"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "requin", emoji: "🦈"),
    ]

    // Level 5 — the SAME sound, written several ways. Pure memory.
    static let soundTargetsL5: [SoundTarget] = [
        SoundTarget(sound: "o", spelling: ["O"], word: "moto", emoji: "🏍️"),
        SoundTarget(sound: "o", spelling: ["O"], word: "vélo", emoji: "🚲"),
        SoundTarget(sound: "o", spelling: ["A", "U"], word: "jaune", emoji: "💛"),
        SoundTarget(sound: "o", spelling: ["E", "A", "U"], word: "eau", emoji: "💧"),
        SoundTarget(sound: "o", spelling: ["E", "A", "U"], word: "bateau", emoji: "⛵"),
        SoundTarget(sound: "o", spelling: ["E", "A", "U"], word: "gâteau", emoji: "🍰"),
        SoundTarget(sound: "fo", spelling: ["F", "O"], word: "forêt", emoji: "🌳"),
        SoundTarget(sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷"),
        SoundTarget(sound: "fa", spelling: ["F", "A"], word: "farine", emoji: "🌾"),
        SoundTarget(sound: "fa", spelling: ["P", "H", "A"], word: "pharmacie", emoji: "💊"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "lapin", emoji: "🐰"),
        SoundTarget(sound: "in", spelling: ["I", "N"], word: "sapin", emoji: "🎄"),
        SoundTarget(sound: "in", spelling: ["A", "I", "N"], word: "main", emoji: "✋"),
        SoundTarget(sound: "in", spelling: ["A", "I", "N"], word: "pain", emoji: "🥖"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "gant", emoji: "🧤"),
        SoundTarget(sound: "an", spelling: ["A", "N"], word: "éléphant", emoji: "🐘"),
        SoundTarget(sound: "an", spelling: ["E", "N"], word: "dent", emoji: "🦷"),
        SoundTarget(sound: "an", spelling: ["E", "N"], word: "content", emoji: "😊"),
        SoundTarget(sound: "ka", spelling: ["C", "A"], word: "cadeau", emoji: "🎁"),
        SoundTarget(sound: "ko", spelling: ["K", "O"], word: "koala", emoji: "🐨"),
        SoundTarget(sound: "ki", spelling: ["Q", "U", "I"], word: "qui", emoji: "❓"),
        SoundTarget(sound: "ko", spelling: ["C", "O"], word: "coq", emoji: "🐓"),
        SoundTarget(sound: "si", spelling: ["C", "I"], word: "citron", emoji: "🍋"),
        SoundTarget(sound: "so", spelling: ["S", "O"], word: "soleil", emoji: "☀️"),
        SoundTarget(sound: "si", spelling: ["S", "I"], word: "singe", emoji: "🐒"),
    ]
}
