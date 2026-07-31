package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.BASIC_SOUNDS
import fr.dappit.attrapelettres.core.domain.BasicSound
import fr.dappit.attrapelettres.core.domain.FindSoundRound
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the find-the-sound slice of `src/levels.ts` (the 4yo rung): hear
// « <sound>, comme dans <mot> », tap the graphy. Pools are authored per level
// (BASIC_SOUNDS); the ladder itself (`FIND_SOUND_LEVELS`, `findSoundIdx`,
// `findSoundLevel`) lives in Levels.kt with the other eight spines.
//
// The spoken lines landed first (the VO manifest enumerates through them); the
// pool and the round builders joined them here when the levels package landed,
// exactly as the stub header said they should.
//
// NB: the two spoken-line SHAPES deliberately match spell-the-sound's
// (`soundPrompt` / `soundSuccess` with a context word), so a (sound, word) pair
// that appears in both exercises resolves to the SAME clip and is baked once.

/** The level's authored pool — recognition content, one list per rung. */
fun findSoundPool(level: Int): List<BasicSound> = BASIC_SOUNDS[findSoundIdx(level)]

/**
 * One round: the target + `distractors` other pool entries. A distractor is
 * never a homophone of the answer (same `sound`), or a correct ear would be
 * told "wrong". Authored `traps` (confusable neighbours) are preferred; the
 * rest of the pool fills any remainder.
 *
 * The traps are DATA, and that is the design: adaptive-by-confusability is
 * authored per entry in content (OU vs ON, AN vs IN…), never computed from a
 * similarity metric.
 *
 * NB: the TS `rest` filter is `!traps.includes(e)` — REFERENCE identity, which
 * Kotlin data classes do not have. Filtering on `graphy` is exactly equivalent
 * here (`byGraphy` is keyed by graphy, and a level's graphies are unique — the
 * suite asserts it) and does not depend on `BasicSound` value equality.
 * NB: the trap LIST itself is shuffled before it is resolved.
 */
fun buildFindSoundRound(
    target: BasicSound,
    pool: List<BasicSound>,
    distractors: Int,
    rng: RandomSource = SystemRandomSource(),
): FindSoundRound {
    val candidates = pool.filter { it.sound != target.sound && it.graphy != target.graphy }
    // Later entries overwrite earlier ones, like the TS `new Map(...)`.
    val byGraphy = candidates.associateBy { it.graphy }
    val traps = rng.shuffled(target.traps.orEmpty()).mapNotNull { byGraphy[it] }
    val trapGraphies = traps.map { it.graphy }.toSet()
    val rest = rng.shuffled(candidates.filter { it.graphy !in trapGraphies })
    val picked = mutableListOf<BasicSound>()
    val seen = mutableSetOf(target.graphy)
    for (e in traps + rest) {
        if (picked.size >= distractors) break
        if (e.graphy in seen) continue
        seen.add(e.graphy)
        picked.add(e)
    }
    return FindSoundRound(target = target, choices = rng.shuffled(listOf(target) + picked))
}

fun buildFindSoundSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<FindSoundRound> {
    val cfg = findSoundLevel(level)
    val pool = findSoundPool(level)
    return repeatSession(pool, cfg.pick, cfg.repeats, rng)
        .map { buildFindSoundRound(it, pool, cfg.distractors, rng) }
}

/** What the child hears: the sound anchored to its word — never the spelling. */
fun findSoundPrompt(t: BasicSound): String = "${t.sound}, comme dans ${t.word}."

/** The success line once the graphy is tapped. */
fun findSoundSuccess(t: BasicSound): String = "Oui ! ${t.word}."
