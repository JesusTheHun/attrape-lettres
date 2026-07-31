package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Invariant 4 — content is AUTHORED, not computed. The counts and shapes here
 * are copied from `src/content.ts` (via the worked iOS suite,
 * `ContentIntegrityTests.swift`), not estimated: if one of them ever has to
 * change here alone, the ports have drifted and that is the finding.
 */
class ContentSoundTargetsTest {

    @Test
    fun `pool sizes match the TypeScript`() {
        assertEquals(listOf(32, 28, 24, 24, 25), SOUND_TARGETS.map { it.size })
        assertEquals(133, SOUND_TARGETS.sumOf { it.size })
    }

    @Test
    fun `every spelling is single uppercase letters and the sound lowercase`() {
        for ((i, pool) in SOUND_TARGETS.withIndex()) {
            for (t in pool) {
                assertTrue(t.spelling.isNotEmpty(), "level ${i + 1}: ${t.sound}")
                for (letter in t.spelling) {
                    assertEquals(letter.uppercase(), letter, "level ${i + 1}: ${t.sound} → $letter")
                    assertEquals(1, letter.length, "level ${i + 1}: ${t.sound} → $letter")
                }
                assertEquals(t.sound.lowercase(), t.sound, "level ${i + 1}: ${t.sound}")
            }
        }
    }

    /** Levels 3–5 give every sound a context word + emoji; levels 1–2 do not. */
    @Test
    fun `context words arrive at level three`() {
        assertTrue(SOUND_TARGETS[0].all { it.word == null && it.emoji == null })
        assertTrue(SOUND_TARGETS[1].all { it.word == null && it.emoji == null })
        for (i in 2..4) {
            assertTrue(SOUND_TARGETS[i].all { it.word != null && it.emoji != null }, "level ${i + 1}")
        }
    }

    /**
     * An anchor word must contain its target sound exactly ONCE. « au, comme
     * dans auto » was wrong: /oto/ has two /o/ and the second is spelled O.
     * The once-only rule needs an ear, not a phonetics engine (which invariant 4
     * forbids anyway), so it is pinned here as the regression it produced.
     */
    @Test
    fun `auto is not an au anchor`() {
        val auAnchors = SOUND_TARGETS.flatten()
            .filter { it.spelling == listOf("A", "U") }
            .mapNotNull { it.word }
        assertFalse("auto" in auAnchors)
        assertTrue("faucon" in auAnchors)
    }

    /**
     * An NFD "É" would change the spell exercise's cell count, the baked-clip
     * lookup key and the rendered glyph — all at once, all silently.
     * `content.ts` is NFC today; this pins the port to it.
     */
    @Test
    fun `every authored string is NFC`() {
        for (t in SOUND_TARGETS.flatten()) {
            val strings = listOf(t.sound) + t.spelling + listOfNotNull(t.word, t.emoji)
            for (string in strings) {
                assertTrue(Normalizer.isNormalized(string, Normalizer.Form.NFC), "not NFC: \"$string\"")
            }
        }
    }
}
