package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariant 4 — content is AUTHORED, not computed. The counts and shapes here
 * are copied from `src/content.ts` (via the worked iOS suite,
 * `ContentIntegrityTests.swift`), not estimated: if one of them ever has to
 * change here alone, the ports have drifted and that is the finding.
 */
class ContentBasicSoundsTest {

    @Test
    fun `pool sizes match the TypeScript`() {
        assertEquals(listOf(7, 8, 8, 7), BASIC_SOUNDS.map { it.size })
        assertEquals(30, BASIC_SOUNDS.sumOf { it.size })
    }

    @Test
    fun `every graphy is uppercase, the sound lowercase, and the anchor complete`() {
        for ((i, pool) in BASIC_SOUNDS.withIndex()) {
            for (s in pool) {
                assertEquals(s.graphy.uppercase(), s.graphy, "level ${i + 1}: ${s.graphy}")
                assertEquals(s.sound.lowercase(), s.sound, "level ${i + 1}: ${s.sound}")
                assertTrue(s.word.isNotEmpty() && s.emoji.isNotEmpty(), "level ${i + 1}: ${s.graphy}")
            }
        }
    }

    /**
     * "Sounds within one level are all DISTINCT, so a distractor can never be a
     * homophone of the answer" — and the graphies too, since the round builder
     * dedupes on graphy.
     */
    @Test
    fun `find-sound levels have distinct sounds and graphies`() {
        for ((i, pool) in BASIC_SOUNDS.withIndex()) {
            assertEquals(pool.size, pool.map { it.sound }.toSet().size, "level ${i + 1}: duplicate sound")
            assertEquals(pool.size, pool.map { it.graphy }.toSet().size, "level ${i + 1}: duplicate graphy")
        }
    }

    /**
     * Every authored `trap` must resolve to another entry OF THE SAME POOL with
     * a different sound — otherwise the trap-preferring distractor rule silently
     * falls back to a random one.
     */
    @Test
    fun `every trap resolves within its level`() {
        for ((i, pool) in BASIC_SOUNDS.withIndex()) {
            val byGraphy = pool.associateBy { it.graphy }
            for (s in pool) {
                for (trap in s.traps.orEmpty()) {
                    val other = assertNotNull(byGraphy[trap], "level ${i + 1}: ${s.graphy} traps unknown $trap")
                    assertNotEquals(s.sound, other.sound, "level ${i + 1}: ${s.graphy} traps a homophone $trap")
                }
            }
        }
    }

    /**
     * An NFD "É" would change the spell exercise's cell count, the baked-clip
     * lookup key and the rendered glyph — all at once, all silently.
     * `content.ts` is NFC today; this pins the port to it.
     */
    @Test
    fun `every authored string is NFC`() {
        for (s in BASIC_SOUNDS.flatten()) {
            for (string in listOf(s.sound, s.graphy, s.word, s.emoji) + s.traps.orEmpty()) {
                assertTrue(Normalizer.isNormalized(string, Normalizer.Form.NFC), "not NFC: \"$string\"")
            }
        }
    }
}
