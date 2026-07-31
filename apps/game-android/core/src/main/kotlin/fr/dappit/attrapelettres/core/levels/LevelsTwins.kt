package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.TWIN_FAMILIES
import fr.dappit.attrapelettres.core.domain.TwinFamily
import fr.dappit.attrapelettres.core.domain.TwinGraphy
import fr.dappit.attrapelettres.core.domain.TwinRound
import fr.dappit.attrapelettres.core.domain.TwinTile
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the sound-twins slice of `src/levels.ts` — hear one sound, find ALL
// the tiles that write it. Families are authored (TWIN_FAMILIES); intruders
// come from the level's OTHER families, so every tile the child can audition
// has a real sound and a real anchor word. The ladder itself (`TWIN_LEVELS`,
// `twinIdx`, `twinLevel`) lives in Levels.kt with the other eight; this file
// owns the pool, the round builder, the session and the spoken lines.
//
// The two spoken lines predate the rest of the file: the VO manifest must
// enumerate through the exact declarations the engine calls, so they landed
// with the VO work package.

fun twinPool(level: Int): List<TwinFamily> = TWIN_FAMILIES[twinIdx(level)]

/**
 * A graphy carrying the sound of the family it came from — the TS
 * `{ ...g, sound: f.sound }` anonymous shape, which Kotlin needs a name for.
 * Private and top-level: file-scoped, so it cannot clash with another builder.
 */
private data class SoundedGraphy(
    val text: String,
    val word: String,
    val emoji: String,
    val sound: String,
    val correct: Boolean,
)

/**
 * One round: every graphy of `family` (all must be found) + `distractors`
 * graphies from the level's other families. Intruders never spell the target
 * sound (families within a level have distinct sounds), and tile texts are
 * deduped so no two tiles read the same.
 */
// NB: the tiles are shuffled FIRST and given their ids AFTER, so the ids
// follow the shuffled order. That is what the TS does.
fun buildTwinRound(
    family: TwinFamily,
    pool: List<TwinFamily>,
    distractors: Int,
    rng: RandomSource = SystemRandomSource(),
    ids: TileIdAllocator = TileIdAllocator.shared,
): TwinRound {
    val others = rng.shuffled(
        pool.filter { it.sound != family.sound }.flatMap { f ->
            f.graphies.map {
                SoundedGraphy(
                    text = it.text,
                    word = it.word,
                    emoji = it.emoji,
                    sound = f.sound,
                    correct = false,
                )
            }
        }
    )
    val picked = mutableListOf<SoundedGraphy>()
    val seen = family.graphies.map { it.text }.toMutableSet()
    for (g in others) {
        if (picked.size >= distractors) break
        if (g.text in seen) continue
        seen.add(g.text)
        picked.add(g)
    }
    val correct = family.graphies.map {
        SoundedGraphy(
            text = it.text,
            word = it.word,
            emoji = it.emoji,
            sound = family.sound,
            correct = true,
        )
    }
    val tiles = rng.shuffled(correct + picked).map { t ->
        TwinTile(
            id = ids.next(),
            text = t.text,
            sound = t.sound,
            word = t.word,
            emoji = t.emoji,
            correct = t.correct,
        )
    }
    return TwinRound(family = family, tiles = tiles)
}

fun buildTwinSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<TwinRound> {
    val cfg = twinLevel(level)
    val pool = twinPool(level)
    return repeatSession(pool, cfg.pick, cfg.repeats, rng)
        .map { buildTwinRound(it, pool, cfg.distractors, rng) }
}

/** What the child hears at round start — the sound to hunt, never a spelling. */
fun twinPrompt(f: TwinFamily): String = "Trouve tous les ${f.sound} !"

/** Success line per found tile — anchors THIS spelling to its own word. */
fun twinSuccess(g: TwinGraphy): String = "Oui ! ${g.word}."
