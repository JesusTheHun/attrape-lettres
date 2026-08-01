package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.platform.AppVersionProvider
import fr.dappit.attrapelettres.core.updates.versionAtLeast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Port of the iOS `SystemAppVersionTests`. The seam under test is the
 * constructor: `PackageInfo` cannot be forged on a bare JVM, so the lookup's
 * RESULT — the nullable `versionName` — is what these drive, exactly as the
 * Swift tests drive `infoValue`.
 */
class SystemAppVersionTest {

    @Test
    fun `reads the package versionName`() {
        assertEquals("1.4.2", SystemAppVersion("1.4.2").marketing)
    }

    @Test
    fun `falls back when versionName is absent or empty`() {
        // versionName is nullable in PackageInfo, and an empty version string
        // must not reach telemetry (it stamps v on every event) or the native
        // guard's comparator.
        assertEquals(SystemAppVersion.FALLBACK, SystemAppVersion(null).marketing)
        assertEquals(SystemAppVersion.FALLBACK, SystemAppVersion("").marketing)
    }

    @Test
    fun `the fallback is the lowest version the guard can compare`() {
        // A build that cannot name its own version must gate conservatively,
        // never claim to satisfy a minimum it does not.
        assertFalse(versionAtLeast(SystemAppVersion.FALLBACK, "0.1.0"))
    }

    @Test
    fun `it really is the AppVersionProvider the app injects`() {
        val provider: AppVersionProvider = SystemAppVersion("0.1.0")
        assertEquals("0.1.0", provider.marketing)
    }
}
