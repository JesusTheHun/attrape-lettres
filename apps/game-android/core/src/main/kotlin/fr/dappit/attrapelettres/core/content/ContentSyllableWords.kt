package fr.dappit.attrapelettres.core.content

import fr.dappit.attrapelettres.core.domain.ImageKey
import fr.dappit.attrapelettres.core.domain.SyllableWord

// `SYLLABLE_WORDS` + `SPELL_SYLLABLE_WORD_NAMES` from `src/content.ts`.
//
// The two authoring rules for a split, both learned the hard way and both
// visible in the comments below:
//   - A shared syllable must SOUND THE SAME in every word that uses it. One tile
//     string = one baked clip.
//   - A fragment may take its in-word sound only when that is a real French
//     rule. Intervocalic S → /z/ (dino-SAURE) and silent finals (choco-LAT)
//     teach something true; « ll » saying /j/ does not.

/**
 * Syllable dataset: split is authored, not computed. Splits favour simple,
 * open syllables so a child can sound each tile out.
 */
val SYLLABLE_WORDS: List<SyllableWord> = listOf(
    // 2 syllables
    SyllableWord(word = "CHATON", syllables = listOf("CHA", "TON"), emoji = "🐱"),
    SyllableWord(word = "LAPIN", syllables = listOf("LA", "PIN"), emoji = "🐰"),
    SyllableWord(word = "SOLEIL", syllables = listOf("SO", "LEIL"), emoji = "☀️"),
    SyllableWord(word = "BATEAU", syllables = listOf("BA", "TEAU"), emoji = "⛵"),
    SyllableWord(word = "GÂTEAU", syllables = listOf("GÂ", "TEAU"), emoji = "🍰"),
    SyllableWord(word = "POISSON", syllables = listOf("POIS", "SON"), emoji = "🐟"),
    // SAPIN, not MAISON: MAI-SON would make SON say /zɔ̃/ (s between vowels),
    // while POIS-SON says /sɔ̃/. One tile string = one baked clip, so a shared
    // syllable must sound the same in every word that uses it — no exceptions,
    // no engine can voice both. SA-PIN reuses PIN from LAPIN at the same /pɛ̃/.
    SyllableWord(word = "SAPIN", syllables = listOf("SA", "PIN"), emoji = "🎄"),
    SyllableWord(word = "CANARD", syllables = listOf("CA", "NARD"), emoji = "🦆"),
    SyllableWord(word = "TORTUE", syllables = listOf("TOR", "TUE"), emoji = "🐢"),
    SyllableWord(word = "VOITURE", syllables = listOf("VOI", "TURE"), emoji = "🚗"),
    SyllableWord(word = "SOURIS", syllables = listOf("SOU", "RIS"), emoji = "🐭"),
    SyllableWord(word = "CAROTTE", syllables = listOf("CA", "ROTTE"), emoji = "🥕"),
    SyllableWord(word = "DAUPHIN", syllables = listOf("DAU", "PHIN"), emoji = "🐬"),
    SyllableWord(word = "ROBOT", syllables = listOf("RO", "BOT"), emoji = "🤖"),
    SyllableWord(word = "MOUTON", syllables = listOf("MOU", "TON"), emoji = "🐑"),
    SyllableWord(word = "COCHON", syllables = listOf("CO", "CHON"), emoji = "🐷"),
    SyllableWord(word = "CITRON", syllables = listOf("CI", "TRON"), emoji = "🍋"),
    SyllableWord(word = "TOMATE", syllables = listOf("TO", "MATE"), emoji = "🍅"),
    // 3 syllables
    SyllableWord(word = "BANANE", syllables = listOf("BA", "NA", "NE"), emoji = "🍌"),
    // PERROQUET, not PAPILLON: PA-PI-LLON needs LLON to say /jɔ̃/, but "ll" alone
    // never says /j/ in French — "ill" does, and it straddles the split. That
    // teaches a rule that doesn't exist. /papijɔ̃/ has no honest 3-way split.
    SyllableWord(word = "PERROQUET", syllables = listOf("PER", "RO", "QUET"), emoji = "🦜"),
    SyllableWord(word = "ÉLÉPHANT", syllables = listOf("É", "LÉ", "PHANT"), emoji = "🐘"),
    SyllableWord(word = "CROCODILE", syllables = listOf("CRO", "CO", "DILE"), emoji = "🐊"),
    SyllableWord(word = "CHOCOLAT", syllables = listOf("CHO", "CO", "LAT"), emoji = "🍫"),
    SyllableWord(word = "TÉLÉPHONE", syllables = listOf("TÉ", "LÉ", "PHONE"), emoji = "📱"),
    SyllableWord(word = "PARAPLUIE", syllables = listOf("PA", "RA", "PLUIE"), emoji = "☂️"),
    SyllableWord(word = "BIBERON", syllables = listOf("BI", "BE", "RON"), emoji = "🍼"),
    SyllableWord(word = "KOALA", syllables = listOf("KO", "A", "LA"), emoji = "🐨"),
    SyllableWord(word = "DINOSAURE", syllables = listOf("DI", "NO", "SAURE"), emoji = "🦕"),
    // 4 syllables
    SyllableWord(word = "ORDINATEUR", syllables = listOf("OR", "DI", "NA", "TEUR"), emoji = "💻"),
    SyllableWord(word = "TÉLÉVISION", syllables = listOf("TÉ", "LÉ", "VI", "SION"), emoji = "📺"),
    SyllableWord(word = "HÉLICOPTÈRE", syllables = listOf("HÉ", "LI", "COP", "TÈRE"), emoji = "🚁"),
    // --- Bigger pools: more words per tier for variety in a run. ---
    // 2 syllables
    SyllableWord(word = "PANDA", syllables = listOf("PAN", "DA"), emoji = "🐼"),
    SyllableWord(word = "FUSÉE", syllables = listOf("FU", "SÉE"), emoji = "🚀"),
    // 3 syllables
    SyllableWord(word = "PYJAMA", syllables = listOf("PY", "JA", "MA"), emoji = "🛌", img = ImageKey.PYJAMA),
    SyllableWord(word = "CINÉMA", syllables = listOf("CI", "NÉ", "MA"), emoji = "🎬"),
    SyllableWord(word = "KANGOUROU", syllables = listOf("KAN", "GOU", "ROU"), emoji = "🦘"),
    SyllableWord(word = "PANTALON", syllables = listOf("PAN", "TA", "LON"), emoji = "👖"),
    SyllableWord(word = "ANANAS", syllables = listOf("A", "NA", "NAS"), emoji = "🍍"),
    SyllableWord(word = "HÔPITAL", syllables = listOf("HÔ", "PI", "TAL"), emoji = "🏥"),
    SyllableWord(word = "MACARON", syllables = listOf("MA", "CA", "RON"), emoji = "🧁", img = ImageKey.MACARON),
    // 4 syllables
    SyllableWord(word = "LOCOMOTIVE", syllables = listOf("LO", "CO", "MO", "TIVE"), emoji = "🚂"),
    SyllableWord(word = "ANNIVERSAIRE", syllables = listOf("AN", "NI", "VER", "SAIRE"), emoji = "🎂"),
    SyllableWord(word = "HIPPOPOTAME", syllables = listOf("HIP", "PO", "PO", "TAME"), emoji = "🦛"),
    SyllableWord(word = "SUPERMARCHÉ", syllables = listOf("SU", "PER", "MAR", "CHÉ"), emoji = "🛒"),
    SyllableWord(word = "AQUARIUM", syllables = listOf("A", "QUA", "RI", "UM"), emoji = "🐠"),
)

/**
 * Fill-a-syllable ladder — the word list per level, by name (resolved against
 * SYLLABLE_WORDS in the levels wiring so the authored split + emoji + baked VO
 * are reused, never duplicated). The SAME list feeds all three siblings ("écris
 * la syllabe" / "…avec des intrus" / "…deux syllabes"), so the child meets the
 * same words as the task hardens. Every word has ≥3 syllables so the
 * two-syllable sibling always leaves a written anchor. Level 1 stays tiny
 * (5 words) for a fast win → confidence loop. Word count grows and 4-syllable
 * words arrive last.
 */
val SPELL_SYLLABLE_WORD_NAMES: List<List<String>> = listOf(
    listOf("PYJAMA", "PANTALON", "CHOCOLAT", "CINÉMA", "MACARON"),
    listOf("BIBERON", "PERROQUET", "CROCODILE", "KANGOUROU", "DINOSAURE", "PARAPLUIE"),
    listOf("TÉLÉPHONE", "ÉLÉPHANT", "ANANAS", "KOALA", "HÔPITAL", "PERROQUET", "CROCODILE", "DINOSAURE"),
    listOf("ORDINATEUR", "HÉLICOPTÈRE", "HIPPOPOTAME", "LOCOMOTIVE", "ANNIVERSAIRE", "AQUARIUM", "SUPERMARCHÉ", "TÉLÉVISION"),
)
