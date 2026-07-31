package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.content.SOUND_TARGETS
import fr.dappit.attrapelettres.core.domain.SoundRound
import fr.dappit.attrapelettres.core.domain.SoundTarget
import fr.dappit.attrapelettres.core.domain.SoundTile
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the spell-the-sound slice of `src/levels.ts` (production; find-sound
// is the recognition rung). Each level is a pool (SOUND_TARGETS) + how many
// intruder letters to add — difficulty is authored in content, and only the
// distractor count lives in the ladder, which sits in Levels.kt
// (`SOUND_LEVELS`, `SOUND_PICK` / `SOUND_REPEATS` / `SOUND_SESSION_LENGTH`,
// `soundIdx`, `soundLevel`) with the other eight spines.
//
// The spoken lines landed first (the VO manifest enumerates through them); the
// pool, the session seeder and the tray builder joined them here when the
// levels package landed, exactly as the stub header said they should.

/** The level's authored pool. The point of the ladder: the same `sound` gets several spellings. */
fun soundPool(level: Int): List<SoundTarget> = SOUND_TARGETS[soundIdx(level)]

fun buildSoundSession(
    level: Int,
    rng: RandomSource = SystemRandomSource(),
): List<SoundTarget> = repeatSession(soundPool(level), SOUND_PICK, SOUND_REPEATS, rng)

/**
 * One tray: an empty slot per letter of the spelling, the needed letters plus
 * `distractors` intruders, shuffled.
 *
 * NB: a `take`, not a precondition — asking for more intruders than the bank
 * holds simply yields fewer, and the round stays valid.
 */
fun buildSoundRound(
    target: SoundTarget,
    distractors: Int,
    rng: RandomSource = SystemRandomSource(),
    ids: TileIdAllocator = TileIdAllocator.shared,
): SoundRound {
    val need = target.spelling.toSet()
    val intruders = rng.shuffled(SOUND_LETTER_BANK.filter { it !in need }).take(distractors)
    return SoundRound(
        target = target,
        slots = target.spelling.map { null },
        tray = rng.shuffled(target.spelling + intruders)
            .map { SoundTile(id = ids.next(), letter = it) },
    )
}

/** What the child hears: the bare sound, or "sound, comme dans word." on levels with context. */
fun soundPrompt(t: SoundTarget): String =
    t.word?.let { "${t.sound}, comme dans $it." } ?: t.sound

/** The success line spoken once the letters are all placed. */
fun soundSuccess(t: SoundTarget): String = "Oui ! ${t.word ?: t.sound}."
