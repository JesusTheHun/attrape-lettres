package fr.dappit.attrapelettres.core.updates

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A line-for-line port of `src/updates.test.ts`.
 *
 * The native-version guard is the one piece of the update path that can brick
 * a tablet: content calling a feature the installed binary lacks is a white
 * screen with no way back. It is also the only piece testable without a
 * device.
 */
class SemVerTest {

    @Test
    fun `accepts an exact match`() {
        assertTrue(versionAtLeast("1.3.0", "1.3.0"))
    }

    @Test
    fun `accepts a newer shell`() {
        assertTrue(versionAtLeast("1.4.0", "1.3.0"))
        assertTrue(versionAtLeast("2.0.0", "1.9.9"))
        assertTrue(versionAtLeast("1.3.1", "1.3.0"))
    }

    @Test
    fun `refuses an older shell`() {
        assertFalse(versionAtLeast("1.2.9", "1.3.0"))
        assertFalse(versionAtLeast("0.9.0", "1.0.0"))
    }

    @Test
    fun `compares numerically, not as strings`() {
        // The classic bug: "1.10.0" < "1.9.0" under lexical comparison.
        assertTrue(versionAtLeast("1.10.0", "1.9.0"))
        assertFalse(versionAtLeast("1.9.0", "1.10.0"))
    }

    @Test
    fun `treats missing segments as zero`() {
        assertTrue(versionAtLeast("1.3", "1.3.0"))
        assertTrue(versionAtLeast("2", "1.9.9"))
        assertFalse(versionAtLeast("1.3", "1.3.1"))
    }

    /**
     * `Number("x")` is `NaN`; `NaN !== a` is true and `NaN > a` is false, so
     * the TS bails out **false** at the first non-numeric segment on either
     * side. `toIntOrNull() ?: 0` would map it to zero and let the loop
     * continue — the other branch. There is no TS test for this and no real
     * manifest will contain one; it is pinned so nobody silently flips it.
     */
    @Test
    fun `a non-numeric segment is neither equal nor greater — it loses, on either side`() {
        assertFalse(versionAtLeast("1.x.0", "1.0.0"))
        assertFalse(versionAtLeast("1.0.0", "1.x.0"))
        assertFalse(versionAtLeast("1.x.0", "1.x.0"))
        // …but only from the segment where it appears: earlier segments still decide.
        assertTrue(versionAtLeast("2.x.0", "1.0.0"))
        assertFalse(versionAtLeast("0.x.0", "1.0.0"))
    }

    /** `Number("")` is `0`, not `NaN`, so an empty segment is a zero. */
    @Test
    fun `an empty segment is zero, as Number of empty string is`() {
        assertTrue(versionAtLeast("1..0", "1.0.0"))
        assertTrue(versionAtLeast("", "0"))
        assertFalse(versionAtLeast("", "0.0.1"))
    }
}
