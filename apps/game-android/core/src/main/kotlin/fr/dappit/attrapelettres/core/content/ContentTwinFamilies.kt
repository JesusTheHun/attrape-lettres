package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.TwinFamily
import fr.dappit.attrapelettres.core.domain.TwinGraphy

// `TWIN_FAMILIES` from `src/content.ts` — 4 pools, 19 families, 48 graphies.

/**
 * Sound-twins dataset — one authored pool of families per level. A family is
 * one SOUND and every written form the level teaches for it; intruder tiles
 * come from the level's other families (the builder never mixes homophones,
 * and graphy texts are unique across one level's families).
 *
 *   1 — c/k/qu et f/ph : the sound is trivially the same, the forms are loud.
 *   2 — o/au/eau et é/er/ez : the classic vowel-team spellings.
 *   3 — Les nasales + è : an/en/am/em, on/om, in/ain/ein, è/ai/ei/et.
 *   4 — Les lettres qui changent : ç, c-doux, qu — the letter alone lies.
 */
val TWIN_FAMILIES: List<List<TwinFamily>> = listOf(
    // Level 1 — /k/ and /f/ written several ways.
    listOf(
        TwinFamily(
            sound = "ka",
            graphies = listOf(
                TwinGraphy(text = "CA", word = "cadeau", emoji = "🎁"),
                TwinGraphy(text = "KA", word = "kayak", emoji = "🛶"),
                TwinGraphy(text = "QUA", word = "quatre", emoji = "4️⃣"),
            ),
        ),
        TwinFamily(
            sound = "ko",
            graphies = listOf(
                TwinGraphy(text = "CO", word = "coq", emoji = "🐓"),
                TwinGraphy(text = "KO", word = "koala", emoji = "🐨"),
            ),
        ),
        TwinFamily(
            sound = "ki",
            graphies = listOf(
                TwinGraphy(text = "QUI", word = "qui", emoji = "❓"),
                TwinGraphy(text = "KI", word = "kiwi", emoji = "🥝"),
            ),
        ),
        TwinFamily(
            sound = "fa",
            graphies = listOf(
                TwinGraphy(text = "FA", word = "farine", emoji = "🌾"),
                TwinGraphy(text = "PHA", word = "pharmacie", emoji = "💊"),
            ),
        ),
        TwinFamily(
            sound = "fo",
            graphies = listOf(
                TwinGraphy(text = "FO", word = "forêt", emoji = "🌳"),
                TwinGraphy(text = "PHO", word = "photo", emoji = "📷"),
            ),
        ),
    ),
    // Level 2 — the /o/ and /é/ vowel teams, bare and inside syllables.
    listOf(
        TwinFamily(
            sound = "o",
            graphies = listOf(
                TwinGraphy(text = "O", word = "moto", emoji = "🏍️"),
                TwinGraphy(text = "AU", word = "auto", emoji = "🚗"),
                TwinGraphy(text = "EAU", word = "bateau", emoji = "⛵"),
            ),
        ),
        TwinFamily(
            sound = "é",
            graphies = listOf(
                TwinGraphy(text = "É", word = "vélo", emoji = "🚲"),
                TwinGraphy(text = "ER", word = "goûter", emoji = "🍪"),
                TwinGraphy(text = "EZ", word = "nez", emoji = "👃"),
            ),
        ),
        TwinFamily(
            sound = "so",
            graphies = listOf(
                TwinGraphy(text = "SO", word = "soleil", emoji = "☀️"),
                TwinGraphy(text = "SEAU", word = "seau", emoji = "🪣"),
                TwinGraphy(text = "SAU", word = "saut", emoji = "🤸"),
            ),
        ),
        TwinFamily(
            sound = "to",
            graphies = listOf(
                TwinGraphy(text = "TO", word = "tomate", emoji = "🍅"),
                TwinGraphy(text = "TEAU", word = "gâteau", emoji = "🍰"),
            ),
        ),
        TwinFamily(
            sound = "bo",
            graphies = listOf(
                TwinGraphy(text = "BO", word = "robot", emoji = "🤖"),
                TwinGraphy(text = "BEAU", word = "corbeau", emoji = "🐦‍⬛"),
            ),
        ),
    ),
    // Level 3 — nasals and è: several letters, one sound, many spellings.
    listOf(
        TwinFamily(
            sound = "an",
            graphies = listOf(
                TwinGraphy(text = "AN", word = "gant", emoji = "🧤"),
                TwinGraphy(text = "EN", word = "dent", emoji = "🦷"),
                TwinGraphy(text = "AM", word = "chambre", emoji = "🛏️"),
                TwinGraphy(text = "EM", word = "tempête", emoji = "🌪️"),
            ),
        ),
        TwinFamily(
            sound = "on",
            graphies = listOf(
                TwinGraphy(text = "ON", word = "pont", emoji = "🌉"),
                TwinGraphy(text = "OM", word = "pompier", emoji = "🚒"),
            ),
        ),
        TwinFamily(
            sound = "in",
            graphies = listOf(
                TwinGraphy(text = "IN", word = "lapin", emoji = "🐰"),
                TwinGraphy(text = "AIN", word = "pain", emoji = "🥖"),
                TwinGraphy(text = "EIN", word = "peinture", emoji = "🎨"),
            ),
        ),
        TwinFamily(
            sound = "è",
            graphies = listOf(
                TwinGraphy(text = "È", word = "chèvre", emoji = "🐐"),
                TwinGraphy(text = "AI", word = "fraise", emoji = "🍓"),
                TwinGraphy(text = "EI", word = "neige", emoji = "❄️"),
                TwinGraphy(text = "ET", word = "jouet", emoji = "🧸"),
            ),
        ),
    ),
    // Level 4 — the softeners: the same letter reads differently, the sound wins.
    listOf(
        TwinFamily(
            sound = "si",
            graphies = listOf(
                TwinGraphy(text = "SI", word = "singe", emoji = "🐒"),
                TwinGraphy(text = "CI", word = "citron", emoji = "🍋"),
            ),
        ),
        TwinFamily(
            sound = "sa",
            graphies = listOf(
                TwinGraphy(text = "SA", word = "sac", emoji = "🎒"),
                TwinGraphy(text = "ÇA", word = "ça", emoji = "👉"),
            ),
        ),
        TwinFamily(
            sound = "se",
            graphies = listOf(
                TwinGraphy(text = "SE", word = "semaine", emoji = "📅"),
                TwinGraphy(text = "CE", word = "cerise", emoji = "🍒"),
            ),
        ),
        TwinFamily(
            sound = "ké",
            graphies = listOf(
                TwinGraphy(text = "KÉ", word = "képi", emoji = "🧢"),
                TwinGraphy(text = "QUAI", word = "quai", emoji = "🚉"),
            ),
        ),
        TwinFamily(
            sound = "ka",
            graphies = listOf(
                TwinGraphy(text = "CA", word = "cadeau", emoji = "🎁"),
                TwinGraphy(text = "KA", word = "kayak", emoji = "🛶"),
                TwinGraphy(text = "QUA", word = "quatre", emoji = "4️⃣"),
            ),
        ),
    ),
)
