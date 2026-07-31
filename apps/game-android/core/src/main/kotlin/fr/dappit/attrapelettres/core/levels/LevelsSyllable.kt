package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SYLLABLE_BANK
import fr.dappit.attrapelettres.core.content.SYLLABLE_WORDS
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableTier
import fr.dappit.attrapelettres.core.domain.SyllableTile
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import fr.dappit.attrapelettres.core.support.elementOf
import fr.dappit.attrapelettres.core.support.shuffled

// Port of the build-syllables slice of `src/levels.ts` — ONE engine behind all
// three assemble modes. The MODE ONLY REACHES `buildSyllableRound` and stops
// there: it changes how a round is SEEDED (which slots start hidden, what lands
// in the tray) and nothing else. The assembly loop — slot filling, the undo
// tap, the whole-row judgement, the celebration — is mode-agnostic and belongs
// to the engine screen, exactly as `AssembleExercise.tsx` keeps it. Resist any
// urge to branch on mode downstream of the builder; that is how one engine
// quietly becomes three.
//
// The ladder itself (`SYLLABLE_TIERS`, `syllableTier`) is spine and lives in
// `Levels.kt`. This file owns what the TypeScript keeps beside the tiers and
// the tile factory: the tier pool, the wrong-answer picker and the round
// builder.
//
// Deliberately NOT here, matching the iOS port:
//   - No session builder. The run is seeded by the engine screen as
//     `repeatSession(syllablePool(tier), tier.pick, tier.repeats)` — the TS
//     keeps that call in component state, so the core keeps no wrapper for it.
//   - No spoken lines. The assemble engine speaks the WORD and its syllables
//     (both already content) plus the screen's own retry/success framing —
//     nothing here has a per-round line the way the sound and twin builders do.

/** The tier's word list: every authored word whose split falls inside the window. */
fun syllablePool(tier: SyllableTier): List<SyllableWord> =
    SYLLABLE_WORDS.filter {
        it.syllables.size >= tier.minSyllables && it.syllables.size <= tier.maxSyllables
    }

/** A wrong-answer syllable that is not one of the word's own. */
// NB: the fallback is `SYLLABLE_BANK[0]` (today "CHA"), which is only stable
// because the bank goes through the ordered dedupe. The bank's ORDER is also
// the distribution, since the pick is by index.
fun pickDistractorSyllable(
    exclude: Set<String>,
    rng: RandomSource = SystemRandomSource(),
): String {
    val options = SYLLABLE_BANK.filter { it !in exclude }
    return rng.elementOf(options) ?: SYLLABLE_BANK[0]
}

/**
 * Seed one assemble round.
 *
 * Fill-blank hides exactly ONE syllable — every other slot is pre-revealed and
 * locked — and trays the missing syllable plus one distractor. Order empties
 * every slot and trays a permutation of the whole word. Order-distractor adds
 * one wrong syllable to that tray. The draw order — hidden slot, then
 * distractor, then the tray shuffle — is the round under a seed; keep it.
 */
fun buildSyllableRound(
    word: SyllableWord,
    mode: SyllableMode,
    rng: RandomSource = SystemRandomSource(),
    ids: TileIdAllocator = TileIdAllocator.shared,
): SyllableRound {
    val syl = word.syllables

    if (mode == SyllableMode.FILL_BLANK) {
        val missing = rng.next(syl.size)
        val slots = syl.mapIndexed { i, s -> if (i == missing) null else s }
        val locked = syl.indices.map { it != missing }
        val distractor = pickDistractorSyllable(syl.toSet(), rng)
        val tray = rng.shuffled(listOf(syl[missing], distractor))
            .map { SyllableTile(id = ids.next(), syllable = it) }
        return SyllableRound(word = word, slots = slots, locked = locked, tray = tray)
    }

    val slots: List<String?> = List(syl.size) { null }
    val locked = List(syl.size) { false }
    val trayValues =
        if (mode == SyllableMode.ORDER_DISTRACTOR) {
            syl + pickDistractorSyllable(syl.toSet(), rng)
        } else {
            syl
        }
    return SyllableRound(
        word = word,
        slots = slots,
        locked = locked,
        tray = rng.shuffled(trayValues).map { SyllableTile(id = ids.next(), syllable = it) },
    )
}
