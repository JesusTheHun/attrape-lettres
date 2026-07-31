package fr.dappit.attrapelettres.core.vo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Golden vectors for the clip-bank hash. EVERY expected value here was produced
 * by running the TypeScript `voKey` in Node, and the ones marked `ON DISK` were
 * additionally checked against real staged filenames in
 * `platform/src/main/assets/vo/`. They are the oracle: if the Kotlin changes
 * and a vector fails, the Kotlin is wrong.
 *
 * A test that recomputed these from the Kotlin implementation could not fail,
 * so none of them are. (UtterancesTest then runs the same proof over all 845
 * real utterances via the staged manifest.)
 */
class VoKeyTest {

    @Test
    fun `matches the TypeScript on plain ascii and the empty string`() {
        assertEquals("ztntfp", voKey(""))
        assertEquals("1ie980c", voKey("A"))
        assertEquals("1j88139", voKey("B")) // ON DISK: 1j88139.m4a
        assertEquals("hv0eu5", voKey("CHA")) // ON DISK: hv0eu5.m4a
        assertEquals("c10hli", voKey("MAI"))
        assertEquals("9q8pln", voKey("SON"))
    }

    @Test
    fun `matches the TypeScript on accented French`() {
        assertEquals("l3kwro", voKey("É")) // ON DISK: l3kwro.m4a
        assertEquals("tz86ys", voKey("é"))
        assertEquals("upd4kb", voKey("Bravo ! Tu as tout réussi !")) // ON DISK
        assertEquals("1sh6e1p", voKey("Bravo ! Tu as tout trouvé !"))
        assertEquals("68c1mf", voKey("Oh non ! On recommence.")) // ON DISK
        assertEquals("19582p8", voKey("Trouve la bonne image.")) // ON DISK
        assertEquals("18ufc9r", voKey("Oui ! B. BALLON."))
    }

    @Test
    fun `matches the TypeScript on the shop lines`() {
        assertEquals("1lly6oa", voKey("C'est à toi !"))
        assertEquals("1f8gzb0", voKey("Tu as grandi !"))
        assertEquals("14y05kb", voKey("Il te manque des étoiles."))
        assertEquals("ty037e", voKey("Ça coûte 1 étoile."))
        assertEquals("jnmioj", voKey("Ça coûte 12 étoiles."))
    }

    /**
     * On the JVM a String IS UTF-16 and `Char.code` IS `charCodeAt`, so unlike
     * Swift there is no wrong scalar type to reach for by accident — but the
     * failure would still be silent (lookup misses, the app falls back to TTS,
     * a different voice, no error), so the astral vector is pinned anyway
     * against a future "simplification" into a code-point walk.
     */
    @Test
    fun `hashes astral characters as surrogate pairs`() {
        assertEquals("1mkhw5w", voKey("👂 emoji"))

        // Prove the vector actually discriminates: the code-POINT-wise hash of
        // the same string is a DIFFERENT value, so this test fails if someone
        // rewrites the loop over code points.
        val s = "👂 emoji"
        var pointwise = 0x811c9dc5.toInt()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val cp: Int
            if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cp = Character.toCodePoint(ch, s[i + 1])
                i += 2
            } else {
                cp = ch.code
                i += 1
            }
            pointwise = pointwise xor cp
            pointwise *= 0x01000193
        }
        assertNotEquals("1mkhw5w", pointwise.toUInt().toString(36))
    }

    @Test
    fun `collapses runs of whitespace and trims the ends`() {
        assertEquals("5wgrym", voKey("  espaces   multiples  "))
        assertEquals("1u7m45s", voKey("\ttab\nnewline\t"))

        // The collapse is what makes those equal to their tidy forms.
        assertEquals(voKey("espaces multiples"), voKey("  espaces   multiples  "))
        assertEquals(voKey("tab newline"), voKey("\ttab\nnewline\t"))
    }

    /**
     * The collapse must use JS's `\s`, not Java's. Java's default `\s` is
     * ASCII-only (NBSP would survive and change the hash), and Java's Unicode
     * `\s` disagrees with ECMAScript on U+0085 and U+FEFF. Every vector here
     * was computed in Node; the U+FEFF case is the one divergence the Swift
     * port documented and kept — an explicit class lets Kotlin close it.
     */
    @Test
    fun `treats exactly ECMAScript's whitespace as whitespace`() {
        assertEquals("4em8e0", voKey("insecable\u00A0ici")) // NBSP
        assertEquals(voKey("insecable ici"), voKey("insecable\u00A0ici"))
        assertEquals("4m7u2a", voKey("a\u202Fb")) // narrow NBSP
        assertEquals("o01yh2", voKey("ideo\u3000space")) // ideographic space
        assertEquals("gb0u3q", voKey("l\u2028s")) // line separator
        assertEquals("1fc1l2r", voKey("bom\uFEFFinside")) // ZWNBSP, the Swift divergence
        assertEquals(voKey("bom inside"), voKey("bom\uFEFFinside"))
    }

    /**
     * NFC normalisation: the composed and decomposed spellings of É are one
     * clip. A Kotlin source file can hold either, so this is a real hazard,
     * not a theoretical one. Unlike Swift, Kotlin's `==` on String compares
     * code units, so here the two literals are not even equal — which makes
     * the need for the normalisation call visible rather than hidden.
     */
    @Test
    fun `normalises to NFC so decomposed accents find the same clip`() {
        val composed = "\u00C9" // É, one code unit
        val decomposed = "E\u0301" // E + combining acute

        // Distinct code units — one unit vs two — which is what the hash sees.
        assertContentEquals(listOf(0x00C9), composed.map { it.code })
        assertContentEquals(listOf(0x0045, 0x0301), decomposed.map { it.code })

        assertEquals("l3kwro", voKey(composed))
        assertEquals("l3kwro", voKey(decomposed))

        // And prove the normalisation is load-bearing rather than incidental:
        // hashing the decomposed code units directly gives a different key.
        var unnormalised = 0x811c9dc5.toInt()
        for (unit in decomposed) {
            unnormalised = unnormalised xor unit.code
            unnormalised *= 0x01000193
        }
        assertNotEquals("l3kwro", unnormalised.toUInt().toString(36))
    }

    /**
     * Base36 of a UInt is at most 7 characters, and the generator writes the
     * key straight into a filename.
     */
    @Test
    fun `produces a filename-safe lowercase base36 key`() {
        val samples = listOf(
            "", "A", "É", "CHA", "Bravo ! Tu as tout réussi !", "👂 emoji",
            "Ça coûte 12 étoiles.", "Oui ! B. BALLON.",
        )
        for (s in samples) {
            val k = voKey(s)
            assertTrue(k.isNotEmpty())
            assertTrue(k.length <= 7)
            assertTrue(k.all { it in '0'..'9' || it in 'a'..'z' }, k)
        }
    }
}
