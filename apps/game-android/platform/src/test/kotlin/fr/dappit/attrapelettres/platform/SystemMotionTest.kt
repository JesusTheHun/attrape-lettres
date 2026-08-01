package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of the iOS `SystemReduceMotionTests`. The sampler is injected because
 * the real setting cannot be changed from a test — a test that could only
 * read `Settings.Global` would prove the code compiles and nothing else. The
 * ContentObserver wiring in `SystemReduceMotion.from` is the one part that
 * needs a device; it is a three-line bridge onto the same `refresh()` these
 * tests exercise directly.
 */
class SystemMotionTest {

    @Test
    fun `reports the setting as it was at construction`() {
        assertFalse(SystemReduceMotion { false }.isReduced)
        assertTrue(SystemReduceMotion { true }.isReduced)
    }

    @Test
    fun `caches the value instead of sampling on every read`() {
        // isReduced is read on the render path; a Settings round trip per
        // frame is exactly what the cache exists to avoid. So a flipped
        // setting stays invisible until something calls refresh().
        var animationsOff = false
        val source = SystemReduceMotion { animationsOff }

        assertFalse(source.isReduced)
        animationsOff = true
        // Nothing has told it yet.
        assertFalse(source.isReduced)
    }

    @Test
    fun `refresh re-samples, in both directions`() {
        // Invariant 6 is not "read it once at launch": a parent can turn
        // animations off while the child is mid-session, and the mascot must
        // settle down without a relaunch. The observer and the ON_RESUME hook
        // both funnel into refresh().
        var animationsOff = false
        val source = SystemReduceMotion { animationsOff }

        animationsOff = true
        source.refresh()
        assertTrue(source.isReduced)

        animationsOff = false
        source.refresh()
        assertFalse(source.isReduced)
    }

    @Test
    fun `it really is the ReduceMotionSource the app injects`() {
        val source: ReduceMotionSource = SystemReduceMotion { true }
        assertTrue(source.isReduced)
    }
}
