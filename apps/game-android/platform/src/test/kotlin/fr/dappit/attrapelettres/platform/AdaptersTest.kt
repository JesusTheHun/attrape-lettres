package fr.dappit.attrapelettres.platform

import fr.dappit.attrapelettres.core.domain.ExerciseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* The host-testable slices of the composition file. The adapters themselves    */
/* are tested in their own suites (SharedPreferencesKVStoreTest,               */
/* SystemMotionTest, SystemAppVersionTest, the transport tests); what lives     */
/* here is only what Adapters.kt itself declares.                              */
/* -------------------------------------------------------------------------- */

class AdaptersTest {

    @Test
    fun `endpoint configuration keeps TS truthiness — blank means absent`() {
        // The web reads import.meta.env.VITE_* and guards with falsiness, so
        // "" must behave exactly like unset: both telemetry and sync stay
        // inert. A build that forgot to configure them posts nowhere.
        val blank = PlatformConfiguration(syncEndpoint = "", telemetryEndpoint = "   ")
        assertNull(blank.syncEndpoint)
        assertNull(blank.telemetryEndpoint)

        val padded = PlatformConfiguration(
            syncEndpoint = " https://s.test ",
            telemetryEndpoint = "https://t.test",
        )
        assertEquals("https://s.test", padded.syncEndpoint)
        assertEquals("https://t.test", padded.telemetryEndpoint)

        val absent = PlatformConfiguration()
        assertNull(absent.syncEndpoint)
        assertNull(absent.telemetryEndpoint)
    }

    @Test
    fun `the manifest keys match the iOS Info plist keys`() {
        // One release runbook for both stores: the value that lands in the
        // iOS Info.plist under these names lands in the Android manifest's
        // meta-data under the same ones.
        assertEquals("ALSyncURL", PlatformConfiguration.SYNC_URL_KEY)
        assertEquals("ALTelemetryURL", PlatformConfiguration.TELEMETRY_URL_KEY)
    }

    @Test
    fun `every exercise has a reward weight in the hub catalog`() {
        // exerciseDifficulty uses getValue, which throws for an id without an
        // EXERCISES row. This test is what turns that throw into a host-test
        // failure instead of a child's crashed session — the :platform half of
        // "EXERCISES is the single authority for reward weights" (invariant 8).
        for (id in ExerciseId.entries) {
            assertTrue(exerciseDifficulty(id).weight >= 0)
        }
    }
}
