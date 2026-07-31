package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.GRID_CONSONANTS
import fr.dappit.attrapelettres.core.content.GRID_VOWELS
import fr.dappit.attrapelettres.core.content.SYLLABLE_GRID_ROWS
import fr.dappit.attrapelettres.core.domain.GridRound
import fr.dappit.attrapelettres.core.domain.GridSyllable
import fr.dappit.attrapelettres.core.domain.SyllableGridLevel
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the syllable-grid slice of `src/levels.ts` — the « tableau des
// syllabes », ONE engine for two drills (`hear` / `vowel`) over the same grid.
// Mode changes only the distractor rule and what a tile shows; the assembly is
// mode-agnostic. The ladder itself (`SYLLABLE_GRID_LEVELS`, `gridIdx`,
// `syllableGridLevel`) lives in Levels.kt with the other eight; this file owns
// the pool, the round builder, the session, the spoken lines and the on-screen
// consignes.
//
// `gridSyllable` and the two spoken lines predate the rest of the file: the VO
// manifest enumerates the whole consonant × vowel tableau through them, so they
// landed with the VO work package. The engines call the SAME declarations —
// that is what keeps the manifest and the game speaking identical bytes.

/** One cell of the grid. The spoken form is just the written one, lowercased. */
// NB: `lowercase()` without a locale is Kotlin's locale-invariant form — "CHÉ"
// must lowercase to "ché" on every device, whatever language the phone speaks.
fun gridSyllable(consonant: String, vowel: String): GridSyllable {
    val text = consonant + vowel
    return GridSyllable(text = text, sound = text.lowercase(), consonant = consonant, vowel = vowel)
}

/** The level's rows, expanded to every syllable they hold (row order, then vowel order). */
// NB: the row entry is `null` on the révision level and the `?:` reads it as
// "the whole table" — the TS `SYLLABLE_GRID_ROWS[gridIdx(level)] ?? GRID_CONSONANTS`.
fun syllableGridPool(level: Int): List<GridSyllable> {
    val rows = SYLLABLE_GRID_ROWS[gridIdx(level)] ?: GRID_CONSONANTS
    return rows.flatMap { c -> GRID_VOWELS.map { v -> gridSyllable(c, v) } }
}

/**
 * One round's tiles. `vowel` mode is the pure drill: the same consonant, the
 * other vowels, nothing else. `hear` mode starts there too and, from level 3,
 * swaps `column` of the distractors for the SAME vowel on another consonant
 * (VA vs LA) — so a child who only hears the vowel starts having to read the
 * consonant as well. Tiles are deduped by text; a short pool just yields a
 * smaller (still valid) round.
 */
// NB: in `vowel` mode `cfg.column` is IGNORED, and no dedupe is needed — every
// tile is the same consonant with another vowel, so the row's cells are unique
// by construction.
fun buildGridRound(
    target: GridSyllable,
    pool: List<GridSyllable>,
    cfg: SyllableGridLevel,
    mode: SyllableGridMode,
    rng: RandomSource = SystemRandomSource(),
): GridRound {
    val need = cfg.choices - 1
    val sameRow = rng.shuffled(
        pool.filter { it.consonant == target.consonant && it.vowel != target.vowel }
    )
    if (mode == SyllableGridMode.VOWEL) {
        return GridRound(
            target = target,
            choices = rng.shuffled(listOf(target) + sameRow.take(need)),
        )
    }

    val sameColumn = rng.shuffled(
        pool.filter { it.vowel == target.vowel && it.consonant != target.consonant }
    ).take(minOf(cfg.column, need))
    val picked = mutableListOf<GridSyllable>()
    val seen = mutableSetOf(target.text)
    for (s in sameColumn + sameRow) {
        if (picked.size >= need) break
        if (s.text in seen) continue
        seen.add(s.text)
        picked.add(s)
    }
    return GridRound(target = target, choices = rng.shuffled(listOf(target) + picked))
}

fun buildSyllableGridSession(
    level: Int,
    mode: SyllableGridMode,
    rng: RandomSource = SystemRandomSource(),
): List<GridRound> {
    val cfg = syllableGridLevel(level)
    val pool = syllableGridPool(level)
    return repeatSession(pool, cfg.pick, cfg.repeats, rng)
        .map { buildGridRound(it, pool, cfg, mode, rng) }
}

/** What the child hears: the bare syllable — no word, no letter names. */
fun gridPrompt(s: GridSyllable): String = s.sound

/** The success line: the syllable again, so the last thing heard is the answer. */
fun gridSuccess(s: GridSyllable): String = "Oui ! ${s.sound}."

/**
 * The on-screen consigne, per drill. Kotlin tells `GRID_PROMPT` from
 * `gridPrompt` apart by case, so unlike Swift (whose namespace forced
 * `gridConsigne`) NO rename is needed — keep the TypeScript's spelling.
 */
val GRID_PROMPT: Map<SyllableGridMode, String> = mapOf(
    SyllableGridMode.HEAR to "Écoute la syllabe et trouve son écriture",
    SyllableGridMode.VOWEL to "Écoute la syllabe et trouve la voyelle qui manque",
)
