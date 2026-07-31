package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.BasicSound

// `BASIC_SOUNDS` from `src/content.ts` — 4 pools, 30 rows.

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
val BASIC_SOUNDS: List<List<BasicSound>> = listOf(
    // Level 1 — single vowels + the two loudest teams.
    listOf(
        BasicSound(sound = "a", graphy = "A", word = "avion", emoji = "✈️"),
        BasicSound(sound = "i", graphy = "I", word = "île", emoji = "🏝️"),
        BasicSound(sound = "o", graphy = "O", word = "orange", emoji = "🍊"),
        BasicSound(sound = "u", graphy = "U", word = "lune", emoji = "🌙"),
        BasicSound(sound = "é", graphy = "É", word = "école", emoji = "🏫"),
        BasicSound(sound = "ou", graphy = "OU", word = "hibou", emoji = "🦉"),
        BasicSound(sound = "oi", graphy = "OI", word = "roi", emoji = "👑"),
    ),
    // Level 2 — the big vowel teams, nasals and CH.
    listOf(
        BasicSound(sound = "ou", graphy = "OU", word = "loup", emoji = "🐺"),
        BasicSound(sound = "oi", graphy = "OI", word = "étoile", emoji = "⭐"),
        BasicSound(sound = "on", graphy = "ON", word = "pont", emoji = "🌉"),
        BasicSound(sound = "an", graphy = "AN", word = "gant", emoji = "🧤"),
        BasicSound(sound = "in", graphy = "IN", word = "lapin", emoji = "🐰"),
        BasicSound(sound = "eu", graphy = "EU", word = "feu", emoji = "🔥"),
        BasicSound(sound = "au", graphy = "AU", word = "jaune", emoji = "💛"),
        BasicSound(sound = "che", graphy = "CH", word = "cheval", emoji = "🐴"),
    ),
    // Level 3 — vowel + R rimes, with two team revisits.
    listOf(
        BasicSound(sound = "or", graphy = "OR", word = "tortue", emoji = "🐢"),
        BasicSound(sound = "ar", graphy = "AR", word = "canard", emoji = "🦆"),
        BasicSound(sound = "our", graphy = "OUR", word = "ours", emoji = "🐻"),
        BasicSound(sound = "oir", graphy = "OIR", word = "soir", emoji = "🌛"),
        BasicSound(sound = "ir", graphy = "IR", word = "rire", emoji = "😄"),
        BasicSound(sound = "ur", graphy = "UR", word = "mur", emoji = "🧱"),
        BasicSound(sound = "ou", graphy = "OU", word = "hibou", emoji = "🦉"),
        BasicSound(sound = "on", graphy = "ON", word = "pont", emoji = "🌉"),
    ),
    // Level 4 — neighbour sounds: the distractors are the authored confusions.
    listOf(
        BasicSound(sound = "ou", graphy = "OU", word = "loup", emoji = "🐺", traps = listOf("ON", "OI")),
        BasicSound(sound = "on", graphy = "ON", word = "mouton", emoji = "🐑", traps = listOf("OU", "AN")),
        BasicSound(sound = "oi", graphy = "OI", word = "poisson", emoji = "🐟", traps = listOf("OU", "ON")),
        BasicSound(sound = "an", graphy = "AN", word = "orange", emoji = "🍊", traps = listOf("IN", "ON")),
        BasicSound(sound = "in", graphy = "IN", word = "sapin", emoji = "🎄", traps = listOf("AN", "ON")),
        BasicSound(sound = "au", graphy = "AU", word = "jaune", emoji = "💛", traps = listOf("OU", "EU")),
        BasicSound(sound = "eu", graphy = "EU", word = "bleu", emoji = "🔵", traps = listOf("AU", "OU")),
    ),
)
