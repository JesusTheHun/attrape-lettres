// `BASIC_SOUNDS` from `src/content.ts` — 4 pools, 30 rows.

extension Content {
    /**
     * Find-the-sound dataset — one authored pool per level (index = level - 1).
     * Recognition, not production: the child hears « <sound>, comme dans <word> »
     * and taps the graphy. Sounds within one level are all DISTINCT (the round
     * builder also enforces it), so a distractor can never be a homophone of the
     * answer. Words are chosen to reuse already-baked "comme dans" clips from
     * SOUND_TARGETS wherever the same (sound, word) pair exists.
     *
     *   1 — Les voyelles + ou/oi : sounds a pre-reader owns; 2 tiles.
     *   2 — Les grandes équipes : nasals, eu/au and CH, each from a real word.
     *   3 — Les sons en R : your or/ar/our closed rimes (fort, canard, ours…).
     *   4 — Les sons voisins : same tiles, but the distractors are the authored
     *       confusable neighbours (`traps`) — OU vs ON, AN vs IN — pure ear work.
     */
    public static let basicSounds: [[BasicSound]] = [
        // Level 1 — single vowels + the two loudest teams.
        [
            BasicSound(sound: "a", graphy: "A", word: "avion", emoji: "✈️"),
            BasicSound(sound: "i", graphy: "I", word: "île", emoji: "🏝️"),
            BasicSound(sound: "o", graphy: "O", word: "orange", emoji: "🍊"),
            BasicSound(sound: "u", graphy: "U", word: "lune", emoji: "🌙"),
            BasicSound(sound: "é", graphy: "É", word: "école", emoji: "🏫"),
            BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉"),
            BasicSound(sound: "oi", graphy: "OI", word: "roi", emoji: "👑"),
        ],
        // Level 2 — the big vowel teams, nasals and CH.
        [
            BasicSound(sound: "ou", graphy: "OU", word: "loup", emoji: "🐺"),
            BasicSound(sound: "oi", graphy: "OI", word: "étoile", emoji: "⭐"),
            BasicSound(sound: "on", graphy: "ON", word: "pont", emoji: "🌉"),
            BasicSound(sound: "an", graphy: "AN", word: "gant", emoji: "🧤"),
            BasicSound(sound: "in", graphy: "IN", word: "lapin", emoji: "🐰"),
            BasicSound(sound: "eu", graphy: "EU", word: "feu", emoji: "🔥"),
            BasicSound(sound: "au", graphy: "AU", word: "jaune", emoji: "💛"),
            BasicSound(sound: "che", graphy: "CH", word: "cheval", emoji: "🐴"),
        ],
        // Level 3 — vowel + R rimes, with two team revisits.
        [
            BasicSound(sound: "or", graphy: "OR", word: "tortue", emoji: "🐢"),
            BasicSound(sound: "ar", graphy: "AR", word: "canard", emoji: "🦆"),
            BasicSound(sound: "our", graphy: "OUR", word: "ours", emoji: "🐻"),
            BasicSound(sound: "oir", graphy: "OIR", word: "soir", emoji: "🌛"),
            BasicSound(sound: "ir", graphy: "IR", word: "rire", emoji: "😄"),
            BasicSound(sound: "ur", graphy: "UR", word: "mur", emoji: "🧱"),
            BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉"),
            BasicSound(sound: "on", graphy: "ON", word: "pont", emoji: "🌉"),
        ],
        // Level 4 — neighbour sounds: the distractors are the authored confusions.
        [
            BasicSound(sound: "ou", graphy: "OU", word: "loup", emoji: "🐺", traps: ["ON", "OI"]),
            BasicSound(sound: "on", graphy: "ON", word: "mouton", emoji: "🐑", traps: ["OU", "AN"]),
            BasicSound(sound: "oi", graphy: "OI", word: "poisson", emoji: "🐟", traps: ["OU", "ON"]),
            BasicSound(sound: "an", graphy: "AN", word: "orange", emoji: "🍊", traps: ["IN", "ON"]),
            BasicSound(sound: "in", graphy: "IN", word: "sapin", emoji: "🎄", traps: ["AN", "ON"]),
            BasicSound(sound: "au", graphy: "AU", word: "jaune", emoji: "💛", traps: ["OU", "EU"]),
            BasicSound(sound: "eu", graphy: "EU", word: "bleu", emoji: "🔵", traps: ["AU", "OU"]),
        ],
    ]
}
