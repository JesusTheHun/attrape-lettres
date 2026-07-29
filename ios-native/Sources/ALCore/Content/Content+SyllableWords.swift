// `SYLLABLE_WORDS` + `SPELL_SYLLABLE_WORD_NAMES` from `src/content.ts`.
//
// The two authoring rules for a split, both learned the hard way and both
// visible in the comments below:
//   - A shared syllable must SOUND THE SAME in every word that uses it. One tile
//     string = one baked clip.
//   - A fragment may take its in-word sound only when that is a real French
//     rule. Intervocalic S → /z/ (dino-SAURE) and silent finals (choco-LAT)
//     teach something true; « ll » saying /j/ does not.

extension Content {
    /**
     * Syllable dataset: split is authored, not computed. Splits favour simple,
     * open syllables so a child can sound each tile out.
     */
    public static let syllableWords: [SyllableWord] = [
        // 2 syllables
        SyllableWord(word: "CHATON", syllables: ["CHA", "TON"], emoji: "🐱"),
        SyllableWord(word: "LAPIN", syllables: ["LA", "PIN"], emoji: "🐰"),
        SyllableWord(word: "SOLEIL", syllables: ["SO", "LEIL"], emoji: "☀️"),
        SyllableWord(word: "BATEAU", syllables: ["BA", "TEAU"], emoji: "⛵"),
        SyllableWord(word: "GÂTEAU", syllables: ["GÂ", "TEAU"], emoji: "🍰"),
        SyllableWord(word: "POISSON", syllables: ["POIS", "SON"], emoji: "🐟"),
        // SAPIN, not MAISON: MAI-SON would make SON say /zɔ̃/ (s between vowels),
        // while POIS-SON says /sɔ̃/. One tile string = one baked clip, so a shared
        // syllable must sound the same in every word that uses it — no exceptions,
        // no engine can voice both. SA-PIN reuses PIN from LAPIN at the same /pɛ̃/.
        SyllableWord(word: "SAPIN", syllables: ["SA", "PIN"], emoji: "🎄"),
        SyllableWord(word: "CANARD", syllables: ["CA", "NARD"], emoji: "🦆"),
        SyllableWord(word: "TORTUE", syllables: ["TOR", "TUE"], emoji: "🐢"),
        SyllableWord(word: "VOITURE", syllables: ["VOI", "TURE"], emoji: "🚗"),
        SyllableWord(word: "SOURIS", syllables: ["SOU", "RIS"], emoji: "🐭"),
        SyllableWord(word: "CAROTTE", syllables: ["CA", "ROTTE"], emoji: "🥕"),
        SyllableWord(word: "DAUPHIN", syllables: ["DAU", "PHIN"], emoji: "🐬"),
        SyllableWord(word: "ROBOT", syllables: ["RO", "BOT"], emoji: "🤖"),
        SyllableWord(word: "MOUTON", syllables: ["MOU", "TON"], emoji: "🐑"),
        SyllableWord(word: "COCHON", syllables: ["CO", "CHON"], emoji: "🐷"),
        SyllableWord(word: "CITRON", syllables: ["CI", "TRON"], emoji: "🍋"),
        SyllableWord(word: "TOMATE", syllables: ["TO", "MATE"], emoji: "🍅"),
        // 3 syllables
        SyllableWord(word: "BANANE", syllables: ["BA", "NA", "NE"], emoji: "🍌"),
        // PERROQUET, not PAPILLON: PA-PI-LLON needs LLON to say /jɔ̃/, but "ll" alone
        // never says /j/ in French — "ill" does, and it straddles the split. That
        // teaches a rule that doesn't exist. /papijɔ̃/ has no honest 3-way split.
        SyllableWord(word: "PERROQUET", syllables: ["PER", "RO", "QUET"], emoji: "🦜"),
        SyllableWord(word: "ÉLÉPHANT", syllables: ["É", "LÉ", "PHANT"], emoji: "🐘"),
        SyllableWord(word: "CROCODILE", syllables: ["CRO", "CO", "DILE"], emoji: "🐊"),
        SyllableWord(word: "CHOCOLAT", syllables: ["CHO", "CO", "LAT"], emoji: "🍫"),
        SyllableWord(word: "TÉLÉPHONE", syllables: ["TÉ", "LÉ", "PHONE"], emoji: "📱"),
        SyllableWord(word: "PARAPLUIE", syllables: ["PA", "RA", "PLUIE"], emoji: "☂️"),
        SyllableWord(word: "BIBERON", syllables: ["BI", "BE", "RON"], emoji: "🍼"),
        SyllableWord(word: "KOALA", syllables: ["KO", "A", "LA"], emoji: "🐨"),
        SyllableWord(word: "DINOSAURE", syllables: ["DI", "NO", "SAURE"], emoji: "🦕"),
        // 4 syllables
        SyllableWord(word: "ORDINATEUR", syllables: ["OR", "DI", "NA", "TEUR"], emoji: "💻"),
        SyllableWord(word: "TÉLÉVISION", syllables: ["TÉ", "LÉ", "VI", "SION"], emoji: "📺"),
        SyllableWord(word: "HÉLICOPTÈRE", syllables: ["HÉ", "LI", "COP", "TÈRE"], emoji: "🚁"),
        // --- Bigger pools: more words per tier for variety in a run. ---
        // 2 syllables
        SyllableWord(word: "PANDA", syllables: ["PAN", "DA"], emoji: "🐼"),
        SyllableWord(word: "FUSÉE", syllables: ["FU", "SÉE"], emoji: "🚀"),
        // 3 syllables
        SyllableWord(word: "PYJAMA", syllables: ["PY", "JA", "MA"], emoji: "🛌", img: .pyjama),
        SyllableWord(word: "CINÉMA", syllables: ["CI", "NÉ", "MA"], emoji: "🎬"),
        SyllableWord(word: "KANGOUROU", syllables: ["KAN", "GOU", "ROU"], emoji: "🦘"),
        SyllableWord(word: "PANTALON", syllables: ["PAN", "TA", "LON"], emoji: "👖"),
        SyllableWord(word: "ANANAS", syllables: ["A", "NA", "NAS"], emoji: "🍍"),
        SyllableWord(word: "HÔPITAL", syllables: ["HÔ", "PI", "TAL"], emoji: "🏥"),
        SyllableWord(word: "MACARON", syllables: ["MA", "CA", "RON"], emoji: "🧁", img: .macaron),
        // 4 syllables
        SyllableWord(word: "LOCOMOTIVE", syllables: ["LO", "CO", "MO", "TIVE"], emoji: "🚂"),
        SyllableWord(word: "ANNIVERSAIRE", syllables: ["AN", "NI", "VER", "SAIRE"], emoji: "🎂"),
        SyllableWord(word: "HIPPOPOTAME", syllables: ["HIP", "PO", "PO", "TAME"], emoji: "🦛"),
        SyllableWord(word: "SUPERMARCHÉ", syllables: ["SU", "PER", "MAR", "CHÉ"], emoji: "🛒"),
        SyllableWord(word: "AQUARIUM", syllables: ["A", "QUA", "RI", "UM"], emoji: "🐠"),
    ]

    /**
     * Fill-a-syllable ladder — the word list per level, by name (resolved against
     * SYLLABLE_WORDS in levels.ts so the authored split + emoji + baked VO are
     * reused, never duplicated). The SAME list feeds all three siblings ("écris la
     * syllabe" / "…avec des intrus" / "…deux syllabes"), so the child meets the same
     * words as the task hardens. Every word has ≥3 syllables so the two-syllable
     * sibling always leaves a written anchor. Level 1 stays tiny (5 words) for a fast
     * win → confidence loop. Word count grows and 4-syllable words arrive last.
     */
    public static let spellSyllableWordNames: [[String]] = [
        ["PYJAMA", "PANTALON", "CHOCOLAT", "CINÉMA", "MACARON"],
        ["BIBERON", "PERROQUET", "CROCODILE", "KANGOUROU", "DINOSAURE", "PARAPLUIE"],
        ["TÉLÉPHONE", "ÉLÉPHANT", "ANANAS", "KOALA", "HÔPITAL", "PERROQUET", "CROCODILE", "DINOSAURE"],
        ["ORDINATEUR", "HÉLICOPTÈRE", "HIPPOPOTAME", "LOCOMOTIVE", "ANNIVERSAIRE", "AQUARIUM", "SUPERMARCHÉ", "TÉLÉVISION"],
    ]
}
