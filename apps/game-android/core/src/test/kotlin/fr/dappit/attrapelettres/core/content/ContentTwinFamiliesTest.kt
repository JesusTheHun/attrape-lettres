package fr.dappit.attrapelettres.core.content

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariant 4 — content is AUTHORED, not computed. The counts and shapes here
 * are copied from `src/content.ts` (via the worked iOS suite,
 * `ContentIntegrityTests.swift`), not estimated: if one of them ever has to
 * change here alone, the ports have drifted and that is the finding.
 */
class ContentTwinFamiliesTest {

    @Test
    fun `pool sizes match the TypeScript`() {
        assertEquals(listOf(5, 5, 4, 5), TWIN_FAMILIES.map { it.size })
        assertEquals(19, TWIN_FAMILIES.flatten().size)
        assertEquals(48, TWIN_FAMILIES.flatten().flatMap { it.graphies }.size)
    }

    /**
     * The builder never mixes homophones, and intruder tiles come from the
     * level's other families — so family sounds must be distinct per level and
     * graphy texts unique across one level's families, and a family needs at
     * least two written forms to be a "twins" round at all.
     */
    @Test
    fun `twin families are well formed`() {
        for ((i, pool) in TWIN_FAMILIES.withIndex()) {
            assertEquals(pool.size, pool.map { it.sound }.toSet().size, "level ${i + 1}: duplicate family sound")
            val texts = pool.flatMap { it.graphies }.map { it.text }
            assertEquals(texts.size, texts.toSet().size, "level ${i + 1}: duplicate graphy text")
            for (f in pool) {
                assertTrue(f.graphies.size >= 2, "level ${i + 1}: family ${f.sound} needs ≥2 graphies")
                assertEquals(f.sound.lowercase(), f.sound, "level ${i + 1}: ${f.sound}")
                for (g in f.graphies) {
                    assertEquals(g.text.uppercase(), g.text, "level ${i + 1}: ${g.text}")
                    assertTrue(g.word.isNotEmpty() && g.emoji.isNotEmpty(), "level ${i + 1}: ${g.text}")
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
        for (f in TWIN_FAMILIES.flatten()) {
            val strings = listOf(f.sound) + f.graphies.flatMap { listOf(it.text, it.word, it.emoji) }
            for (string in strings) {
                assertTrue(Normalizer.isNormalized(string, Normalizer.Form.NFC), "not NFC: \"$string\"")
            }
        }
    }
}
