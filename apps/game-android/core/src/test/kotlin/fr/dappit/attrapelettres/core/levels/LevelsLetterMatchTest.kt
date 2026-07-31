package fr.dappit.attrapelettres.core.levels

import fr.dappit.attrapelettres.core.content.LETTER_MATCH_ALPHABET
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchRound
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of the `letterMatchPool`, `buildLetterMatchSession` and
 * `letterMatchPrompt` blocks of `apps/game-web/src/levels.test.ts` (via the
 * Swift `LevelsLetterMatchTests`), plus the prompt-order and success-line
 * assertions.
 *
 * The accessor half of the clamp block (`letterMatchLevel`) lives in
 * [LaddersTest] with its eight siblings.
 */
class LevelsLetterMatchTest {

    @Test
    fun `restricts to the level's catalog and returns the full alphabet for the null level`() {
        val l1 = LETTER_MATCH_LEVELS[0].letters!!.toSet()
        assertTrue(letterMatchPool(1).all { it in l1 })
        assertEquals(LETTER_MATCH_ALPHABET, letterMatchPool(LETTER_MATCH_LEVELS.size))
    }

    @Test
    fun `gives every level a pool big enough for its pick`() {
        LETTER_MATCH_LEVELS.forEachIndexed { i, cfg ->
            assertTrue(letterMatchPool(i + 1).size >= cfg.pick, "level ${i + 1}")
        }
    }

    /* buildLetterMatchSession ------------------------------------------------ */

    private data class Run(
        val kind: LetterMatchKind,
        val level: Int,
        val session: List<LetterMatchRound>,
    )

    /** Every kind × level, many runs — the invariants must hold on all pool shapes. */
    private val runs: List<Run> = LetterMatchKind.entries.flatMap { kind ->
        LETTER_MATCH_LEVELS.indices.flatMap { i ->
            (0 until 30).map { n ->
                Run(
                    kind = kind,
                    level = i + 1,
                    session = buildLetterMatchSession(kind, i + 1, SeededGenerator(n.toLong())),
                )
            }
        }
    }

    @Test
    fun `is pick plus repeats rounds, spaced so no letter repeats back-to-back`() {
        for (r in runs) {
            val cfg = LETTER_MATCH_LEVELS[r.level - 1]
            assertEquals(cfg.pick + cfg.repeats, r.session.size, "${r.kind} level ${r.level}")
            for (i in 1 until r.session.size) {
                assertNotEquals(r.session[i - 1].prompt.base, r.session[i].prompt.base)
            }
        }
    }

    @Test
    fun `offers exactly one correct counterpart among distinct-letter tiles`() {
        for (r in runs) {
            val cfg = LETTER_MATCH_LEVELS[r.level - 1]
            for (round in r.session) {
                assertEquals(cfg.distractors + 1, round.choices.size)
                val bases = round.choices.map { it.base }
                // distinct letters
                assertEquals(bases.size, bases.toSet().size, "$bases has a duplicate")
                assertEquals(1, bases.count { it == round.prompt.base })
            }
        }
    }

    /**
     * The GLYPH flips, not just the name: A ⇄ a, never A ⇄ A. The identity is
     * the base letter; what the child must discriminate is the rendered form.
     */
    @Test
    fun `case kind is both plain print, and tiles flip the prompt's case`() {
        for (r in runs) {
            if (r.kind != LetterMatchKind.CASE) continue
            for (round in r.session) {
                assertEquals(LetterScript.PRINT, round.prompt.script)
                val answer = round.choices.first { it.base == round.prompt.base }
                assertNotEquals(round.prompt.glyph, answer.glyph)
                val promptUpper = round.prompt.glyph == round.prompt.glyph.uppercase()
                for (c in round.choices) {
                    assertEquals(LetterScript.PRINT, c.script)
                    assertEquals(!promptUpper, c.glyph == c.glyph.uppercase(), c.glyph)
                }
            }
        }
    }

    @Test
    fun `script kind is one shared case, and tiles flip print and cursive`() {
        for (r in runs) {
            if (r.kind != LetterMatchKind.SCRIPT) continue
            for (round in r.session) {
                val otherScript =
                    if (round.prompt.script == LetterScript.CURSIVE) LetterScript.PRINT
                    else LetterScript.CURSIVE
                val promptUpper = round.prompt.glyph == round.prompt.glyph.uppercase()
                for (c in round.choices) {
                    assertEquals(otherScript, c.script)
                    // same case as prompt
                    assertEquals(promptUpper, c.glyph == c.glyph.uppercase(), c.glyph)
                }
            }
        }
    }

    /* letterMatchPrompt ------------------------------------------------------ */

    @Test
    fun `reads the direction off the prompt-to-answer transform`() {
        fun p(base: String, glyph: String, script: LetterScript) =
            LetterFace(base = base, glyph = glyph, script = script)

        assertEquals(
            LetterMatchPrompts.TO_LOWER,
            letterMatchPrompt(p("A", "A", LetterScript.PRINT), p("A", "a", LetterScript.PRINT)),
        )
        assertEquals(
            LetterMatchPrompts.TO_UPPER,
            letterMatchPrompt(p("A", "a", LetterScript.PRINT), p("A", "A", LetterScript.PRINT)),
        )
        assertEquals(
            LetterMatchPrompts.TO_CURSIVE,
            letterMatchPrompt(p("A", "A", LetterScript.PRINT), p("A", "A", LetterScript.CURSIVE)),
        )
        assertEquals(
            LetterMatchPrompts.TO_PRINT,
            letterMatchPrompt(p("A", "a", LetterScript.CURSIVE), p("A", "a", LetterScript.PRINT)),
        )
    }

    /** The four instruction lines, byte for byte, in `Object.values` order — the VO manifest iterates this. */
    @Test
    fun `prompts iterate in declaration order for the VO manifest`() {
        assertEquals(
            listOf(
                "Trouve la petite lettre.",
                "Trouve la grande lettre.",
                "Trouve la lettre attachée.",
                "Trouve la lettre en script.",
            ),
            LetterMatchPrompts.all,
        )
    }

    @Test
    fun `success names the letter only after the match`() {
        assertEquals("Oui ! B.", letterMatchSuccess("B"))
    }

    @Test
    fun `is replayable under a fixed seed`() {
        for (kind in LetterMatchKind.entries) {
            for (level in 1..LETTER_MATCH_LEVEL_COUNT) {
                assertEquals(
                    buildLetterMatchSession(kind, level, SeededGenerator(1234)),
                    buildLetterMatchSession(kind, level, SeededGenerator(1234)),
                    "$kind level $level",
                )
            }
        }
    }
}
