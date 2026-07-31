package fr.dappit.attrapelettres.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariant 6's :core share: `faceLabel` is the `contentDescription` a letter
 * tile carries, ported from `src/letterForms.ts` and tested here rather than
 * left in the view layer where nothing would test it. French copy is byte-exact
 * — note "attachée" carries a LEADING space, so there is no double space and no
 * trailing one.
 */
class FaceLabelTest {

    @Test
    fun `print majuscule`() {
        assertEquals(
            "Lettre A majuscule",
            faceLabel(LetterFace(base = "A", glyph = "A", script = LetterScript.PRINT)),
        )
    }

    @Test
    fun `print minuscule`() {
        assertEquals(
            "Lettre A minuscule",
            faceLabel(LetterFace(base = "A", glyph = "a", script = LetterScript.PRINT)),
        )
    }

    @Test
    fun `cursive minuscule appends attachee`() {
        assertEquals(
            "Lettre E minuscule attachée",
            faceLabel(LetterFace(base = "E", glyph = "e", script = LetterScript.CURSIVE)),
        )
    }

    @Test
    fun `cursive majuscule appends attachee`() {
        assertEquals(
            "Lettre E majuscule attachée",
            faceLabel(LetterFace(base = "E", glyph = "E", script = LetterScript.CURSIVE)),
        )
    }

    /**
     * The case word is decided by the GLYPH, not the base — the base is always
     * uppercase, so reading it would label every tile "majuscule".
     */
    @Test
    fun `the case word comes from the glyph, not the base`() {
        for (letter in 'A'..'Z') {
            val base = letter.toString()
            val lower = base.lowercase()
            assertTrue(
                faceLabel(LetterFace(base = base, glyph = base, script = LetterScript.PRINT))
                    .endsWith("majuscule"),
            )
            assertTrue(
                faceLabel(LetterFace(base = base, glyph = lower, script = LetterScript.PRINT))
                    .endsWith("minuscule"),
            )
        }
    }

    @Test
    fun `no trailing or double space`() {
        for (script in LetterScript.entries) {
            for (glyph in listOf("B", "b")) {
                val label = faceLabel(LetterFace(base = "B", glyph = glyph, script = script))
                assertFalse(label.endsWith(" "), label)
                assertFalse(label.contains("  "), label)
            }
        }
    }
}

class LetterTypeShapeTest {

    /**
     * These enums are not persisted (only ExerciseId's wire strings are frozen —
     * A2), but their lowercase names deliberately mirror the TS union strings so
     * the three ports read identically. Order matters: it is the TS declaration
     * order.
     */
    @Test
    fun `script and kind cases mirror the web strings, in order`() {
        assertEquals(listOf("print", "cursive"), LetterScript.entries.map { it.name.lowercase() })
        assertEquals(listOf("case", "script"), LetterMatchKind.entries.map { it.name.lowercase() })
    }

    @Test
    fun `first-letter level null letters means the full catalog`() {
        val full = FirstLetterLevel(letters = null, pick = 8, repeats = 4)
        assertNull(full.letters)
        val restricted = FirstLetterLevel(letters = listOf("A", "B"), pick = 5, repeats = 3)
        assertEquals(listOf("A", "B"), restricted.letters)
    }

    @Test
    fun `a letter word's img is a key, not a URL`() {
        val jupe = LetterWord(letter = "J", word = "jupe", emoji = "👗", img = ImageKey.JUPE)
        assertEquals(ImageKey.JUPE, jupe.img)
        assertNull(LetterWord(letter = "A", word = "avion", emoji = "✈️").img)
    }
}
