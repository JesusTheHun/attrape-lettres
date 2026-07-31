package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.SOUND_LETTER_BANK
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.SpellLetterTile
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SpellSyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.core.support.TileIdAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of the `spellSyllablePool`, `buildSpellSyllableRound`,
 * `buildSpellSyllableRound — écritures mêlées (mixed)` and
 * `buildSpellSyllableSession` blocks of apps/game-web/src/levels.test.ts, via
 * the iOS `LevelsSpellSyllableTests`, plus the port-specific seams (seeded
 * replayability, the first-appearance trap order) the TypeScript gets from JS
 * semantics for free.
 */
class LevelsSpellSyllableTest {

    /* spellSyllablePool ------------------------------------------------------ */

    @Test
    fun `clamps out-of-range levels and resolves to real three-syllable words`() {
        assertEquals(spellSyllablePool(1), spellSyllablePool(0))
        assertEquals(spellSyllablePool(SPELL_SYLLABLE_LEVELS.size), spellSyllablePool(999))
        for (i in SPELL_SYLLABLE_LEVELS.indices) {
            val pool = spellSyllablePool(i + 1)
            assertTrue(pool.isNotEmpty())
            // ≥3 syllables so the two-syllable sibling always leaves a written anchor.
            for (w in pool) assertTrue(w.syllables.size >= 3, w.word)
        }
    }

    @Test
    fun `keeps level 1 tiny and gives every level enough to pick a full run`() {
        assertTrue(spellSyllablePool(1).size <= 5)
        SPELL_SYLLABLE_LEVELS.forEachIndexed { i, cfg ->
            assertTrue(spellSyllablePool(i + 1).size >= cfg.pick, "level ${i + 1}")
        }
    }

    /**
     * The three siblings and their two "écritures mêlées" twins share ONE word
     * ladder, level for level, so a child meets the SAME words as the task gets
     * harder. The sharing is structural — neither `spellSyllablePool` nor
     * `buildSpellSyllableSession` takes a mode, so there is no parameter for a
     * sibling to diverge on; the mixed twins change which writing the word shows
     * and what the tray mixes, never the ladder. The behavioural half is pinned
     * here: at every level, every seeded session draws only from that level's
     * shared pool, and over seeds the whole pool is met.
     */
    @Test
    fun `all siblings ride one shared word ladder, level for level`() {
        for (i in SPELL_SYLLABLE_LEVELS.indices) {
            val poolWords = spellSyllablePool(i + 1).map { it.word }.toSet()
            val met = mutableSetOf<String>()
            for (seed in 0 until 40) {
                val run = buildSpellSyllableSession(i + 1, SeededGenerator(seed.toLong()))
                for (w in run) {
                    assertTrue(w.word in poolWords, "level ${i + 1}: ${w.word} not in its pool")
                    met.add(w.word)
                }
            }
            assertEquals(poolWords, met, "level ${i + 1}: pool never fully met")
        }
    }

    /* buildSpellSyllableRound (plain) ---------------------------------------- */

    @Test
    fun `spells the whole word cell by cell in reading order`() {
        for (r in runs) {
            assertEquals(
                r.word.syllables.joinToString(""),
                r.round.cells.joinToString("") { it.letter },
            )
        }
    }

    @Test
    fun `hides whole syllables and always leaves a written anchor`() {
        for (r in runs) {
            var ci = 0
            var hiddenCount = 0
            for (s in r.word.syllables) {
                val group = r.round.cells.subList(ci, ci + s.length)
                ci += s.length
                val allFill = group.all { it.fill }
                val noneFill = group.none { it.fill }
                // Never a half-hidden syllable.
                assertTrue(allFill || noneFill, "${r.word.word}: half-hidden $s")
                if (allFill) hiddenCount += 1
            }
            assertEquals(
                if (r.mode == SpellSyllableMode.LETTERS_TWO) 2 else 1,
                hiddenCount,
                r.word.word,
            )
            // ≥1 anchor letter shown.
            assertTrue(r.round.cells.any { !it.fill }, r.word.word)
        }
    }

    @Test
    fun `answer is the gap letters in order, numbered zero to n minus one`() {
        for (r in runs) {
            val fills = r.round.cells.filter { it.fill }
            assertEquals(fills.indices.toList(), fills.map { it.slotIndex })
            assertEquals(fills.map { it.letter }, r.round.answer)
            for (c in r.round.cells) {
                if (!c.fill) assertEquals(-1, c.slotIndex)
            }
        }
    }

    @Test
    fun `letters-exact tray is exactly the gap letters, no intruders`() {
        for (r in runs) {
            if (r.mode != SpellSyllableMode.LETTERS_EXACT) continue
            assertEquals(r.round.answer.sorted(), r.round.tray.map { it.letter }.sorted())
        }
    }

    @Test
    fun `letters-extra and -two add exactly distractors intruders, none in the gap`() {
        for (r in runs) {
            if (r.mode == SpellSyllableMode.LETTERS_EXACT) continue
            assertEquals(r.round.answer.size + r.distractors, r.round.tray.size)
            val need = r.round.answer.toSet()
            val intruders = r.round.tray.map { it.letter }.filter { it !in need }
            assertEquals(r.distractors, intruders.size, r.word.word)
            for (l in intruders) assertTrue(l in SOUND_LETTER_BANK, l)
        }
    }

    @Test
    fun `gives every tray tile a unique id`() {
        for (r in runs) {
            val ids = r.round.tray.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    /* buildSpellSyllableRound — écritures mêlées (mixed) --------------------- */

    @Test
    fun `draws the whole word in one legal writing`() {
        for (r in mixedRuns) {
            val sigs = r.round.cells.map { sig(it.glyph, it.script) }.toSet()
            assertEquals(1, sigs.size, r.word.word)
            assertTrue(sigs.first() in LEGAL, sigs.first())
            // Its glyphs are the correct case for that writing.
            for (c in r.round.cells) {
                assertEquals(if (isUpper(c.glyph)) c.letter else c.letter.lowercase(), c.glyph)
            }
        }
    }

    @Test
    fun `stays solvable - every gap face has a matching tray tile`() {
        for (r in mixedRuns) {
            val trayLeft = mutableMapOf<String, Int>()
            for (t in r.round.tray) {
                val k = key(t.glyph, t.script)
                trayLeft[k] = (trayLeft[k] ?: 0) + 1
            }
            for (f in r.round.answerFaces) {
                val k = key(f.glyph, f.script)
                assertTrue((trayLeft[k] ?: 0) > 0, "${r.word.word}: no tray tile for $k")
                trayLeft[k] = (trayLeft[k] ?: 0) - 1
            }
        }
    }

    @Test
    fun `adds exactly distractors traps, none a valid answer, all distinct forms`() {
        for (r in mixedRuns) {
            assertEquals(r.round.answerFaces.size + r.distractors, r.round.tray.size)
            // Strip one tray tile per answer face; what's left are the distractors.
            val need = mutableMapOf<String, Int>()
            for (f in r.round.answerFaces) {
                val k = key(f.glyph, f.script)
                need[k] = (need[k] ?: 0) + 1
            }
            val extras = mutableListOf<SpellLetterTile>()
            for (t in r.round.tray) {
                val k = key(t.glyph, t.script)
                val n = need[k] ?: 0
                if (n > 0) need[k] = n - 1 else extras.add(t)
            }
            assertEquals(r.distractors, extras.size, r.word.word)
            val answerKeys = r.round.answerFaces.map { key(it.glyph, it.script) }.toSet()
            val extraKeys = extras.map { key(it.glyph, it.script) }
            // Distinct…
            assertEquals(extraKeys.size, extraKeys.toSet().size)
            // …and never a right tile.
            for (k in extraKeys) assertTrue(k !in answerKeys, k)
        }
    }

    @Test
    fun `always plants at least one same-letter wrong-writing trap`() {
        for (r in mixedRuns) {
            val answerKeys = r.round.answerFaces.map { key(it.glyph, it.script) }.toSet()
            val answerBases = r.round.answerFaces.map { it.base }.toSet()
            // A tile whose LETTER is needed but whose exact face isn't a valid answer.
            val trap = r.round.tray.any {
                it.letter in answerBases && key(it.glyph, it.script) !in answerKeys
            }
            assertTrue(trap, r.word.word)
        }
    }

    /* buildSpellSyllableSession ---------------------------------------------- */

    @Test
    fun `seeds pick plus repeats rounds, spaced so no word repeats back to back`() {
        SPELL_SYLLABLE_LEVELS.forEachIndexed { i, cfg ->
            for (seed in 0 until 40) {
                val run = buildSpellSyllableSession(i + 1, SeededGenerator(seed.toLong()))
                assertEquals(cfg.pick + cfg.repeats, run.size, "level ${i + 1} seed $seed")
                for (k in 1 until run.size) {
                    assertTrue(run[k - 1].word != run[k].word, "level ${i + 1} seed $seed")
                }
            }
        }
    }

    /* Port-specific ---------------------------------------------------------- */

    /**
     * `spellIntruders` walks the answer's DISTINCT letters in first-appearance
     * order (JS `new Set`). `orderedUnique` is the pinned contract for that; a
     * plain iteration-order accident there would surface as a flaky seeded run.
     */
    @Test
    fun `mixed traps follow the answer's first-appearance order`() {
        val faces = spellIntruders(
            answer = listOf("B", "A", "B", "C"),
            form = PLAIN_FORM,
            extra = 9,
            mixed = true,
            rng = SeededGenerator(1),
        )
        // The wrong-writing traps come first and cover B, A, C in that order,
        // three writings each minus the ones the answer already uses.
        assertEquals(setOf("B", "A", "C"), faces.take(6).map { it.base }.toSet())
        assertEquals(9, faces.size)
    }

    @Test
    fun `plain intruders are always wrong letters in the round's form`() {
        val faces = spellIntruders(
            answer = listOf("B", "A"),
            form = PLAIN_FORM,
            extra = 3,
            mixed = false,
            rng = SeededGenerator(2),
        )
        assertEquals(3, faces.size)
        for (f in faces) {
            assertTrue(f.base !in listOf("B", "A"), f.base)
            assertEquals(LetterScript.PRINT, f.script)
            assertEquals(f.base, f.glyph)
            assertTrue(f.base in SOUND_LETTER_BANK, f.base)
        }
    }

    @Test
    fun `no intruders when extra is zero or less`() {
        assertTrue(
            spellIntruders(
                answer = listOf("A"),
                form = PLAIN_FORM,
                extra = 0,
                mixed = true,
                rng = SeededGenerator(1),
            ).isEmpty()
        )
        assertTrue(
            spellIntruders(
                answer = listOf("A"),
                form = PLAIN_FORM,
                extra = -1,
                mixed = false,
                rng = SeededGenerator(1),
            ).isEmpty()
        )
    }

    @Test
    fun `is replayable under a fixed seed`() {
        val word = spellSyllablePool(4)[0]
        for (mode in SpellSyllableMode.entries) {
            for (mixed in listOf(false, true)) {
                val a = buildSpellSyllableRound(
                    word, mode, 3, mixed, SeededGenerator(1234), TileIdAllocator(0)
                )
                val b = buildSpellSyllableRound(
                    word, mode, 3, mixed, SeededGenerator(1234), TileIdAllocator(0)
                )
                assertEquals(a, b, "$mode mixed=$mixed")
            }
        }
    }

    /** One built fixture: which sibling, which word, and the level's distractor count. */
    private data class Run(
        val mode: SpellSyllableMode,
        val word: SyllableWord,
        val round: SpellSyllableRound,
        val distractors: Int,
    )

    private companion object {
        /**
         * Every mode × level, many seeded rounds — the invariants must hold on
         * every pool word. Seeds copied from the iOS suite (`wi * 197 + n`,
         * `wi * 389 + n` for the mixed set) so the three ports exercise
         * comparable fixtures.
         */
        val runs: List<Run> = SpellSyllableMode.entries.flatMap { mode ->
            SPELL_SYLLABLE_LEVELS.indices.flatMap { i ->
                val cfg = spellSyllableLevel(i + 1)
                spellSyllablePool(i + 1).withIndex().flatMap { (wi, word) ->
                    (0 until 12).map { n ->
                        Run(
                            mode = mode,
                            word = word,
                            round = buildSpellSyllableRound(
                                word,
                                mode,
                                cfg.distractors,
                                rng = SeededGenerator((wi * 197 + n).toLong()),
                            ),
                            distractors = cfg.distractors,
                        )
                    }
                }
            }
        }

        /** Only the two intruder spellers get a mixed twin; -exact never does. */
        val mixedRuns: List<Run> =
            listOf(SpellSyllableMode.LETTERS_EXTRA, SpellSyllableMode.LETTERS_TWO).flatMap { mode ->
                SPELL_SYLLABLE_LEVELS.indices.flatMap { i ->
                    val cfg = spellSyllableLevel(i + 1)
                    spellSyllablePool(i + 1).withIndex().flatMap { (wi, word) ->
                        (0 until 12).map { n ->
                            Run(
                                mode = mode,
                                word = word,
                                round = buildSpellSyllableRound(
                                    word,
                                    mode,
                                    cfg.distractors,
                                    mixed = true,
                                    rng = SeededGenerator((wi * 389 + n).toLong()),
                                ),
                                distractors = cfg.distractors,
                            )
                        }
                    }
                }
            }
    }
}

/* Writing helpers for the mixed assertions — the TS test's `key`/`sig`/`LEGAL`.
   Used only in test bodies, never in a signature, so top-level private is safe. */

private fun key(glyph: String, script: LetterScript): String = "$glyph|$script"

private fun isUpper(g: String): Boolean = g == g.uppercase()

/** Writing signature = case + script; exactly the three MIXED_FORMS are legal. */
private fun sig(glyph: String, script: LetterScript): String = "${isUpper(glyph)}|$script"

private val LEGAL: Set<String> = setOf("true|PRINT", "false|PRINT", "false|CURSIVE")
