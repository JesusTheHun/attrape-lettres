package fr.dappit.attrapelettres

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// ===========================================================================
// The manifest and the composition root, as a contract.
//
// Nothing in this file needs a device, and every claim it makes is one that
// ONLY fails on a device — quietly, in a release, in somebody's hands. That is
// the whole reason it is a source scan rather than prose in a review:
//
//   * a telemetry endpoint added without the INTERNET permission does not throw
//     anywhere a developer will see. `HttpURLConnection` raises SecurityException,
//     `Telemetry.post` swallows it by design ("telemetry must never surface to a
//     child"), and the result is an app that looks instrumented and reports
//     nothing, forever;
//   * a permission added and never used is invisible in testing and expensive
//     exactly once, in a Kids Category review or on the Play Data safety form;
//   * :ui growing a :platform dependency compiles fine and silently deletes the
//     structural guarantee behind invariant 1 (an async adapter cannot implement
//     a synchronous interface — but only while :ui cannot reach the adapters at
//     all);
//   * `sync` or billing being wired here compiles fine too, and binds the app to
//     a contract that is being reworked (A7) or to a Play Console product that
//     does not exist.
//
// XML comments are stripped before every scan. This manifest documents the
// endpoint keys and the permission it does NOT declare, and a naive `contains`
// would read the documentation as the declaration — which is the failure mode
// where a test agrees with itself.
// ===========================================================================
class ManifestContractTest {

    // --- Permissions and endpoints move together ----------------------------

    /** The two configuration keys `PlatformConfiguration` reads from meta-data. */
    private val endpointKeys = listOf("ALTelemetryURL", "ALSyncURL")

    private val internet = "android.permission.INTERNET"

    /**
     * TODAY: no endpoint, therefore no permission. This is the shipping posture
     * and it is what makes the Play Data safety form nearly empty — the app
     * genuinely cannot open a socket.
     */
    @Test
    fun theShippingBuildDeclaresNoPermissionAndNoEndpoint() {
        val xml = manifestBody()

        assertFalse(
            xml.contains("<uses-permission"),
            "the game needs no permission: sync is not wired (A7) and telemetry has no endpoint",
        )
        for (key in endpointKeys) {
            assertFalse(xml.contains(key), "$key is declared but nothing in this build may reach the network")
        }
    }

    /**
     * THE RULE, in both directions, so the day telemetry is switched on the
     * failure is a red test and not a silent one.
     */
    @Test
    fun anEndpointAndTheInternetPermissionArriveTogether() {
        val xml = manifestBody()
        val declaresEndpoint = endpointKeys.any { xml.contains(it) }
        val declaresInternet = xml.contains(internet)

        if (declaresEndpoint) {
            assertTrue(
                declaresInternet,
                "an endpoint is declared without $internet — every send would throw " +
                    "SecurityException and Telemetry would swallow it, forever and silently",
            )
        } else {
            assertFalse(
                declaresInternet,
                "$internet is declared but no endpoint is — a permission this app cannot " +
                    "use is one more line to justify in a Kids Category review",
            )
        }
    }

    /** No cleartext escape hatch, ever. Both endpoints are documented as https. */
    @Test
    fun cleartextTrafficIsNeverEnabled() {
        val xml = manifestBody()
        assertFalse(xml.contains("usesCleartextTraffic=\"true\""))
        assertFalse(xml.contains("networkSecurityConfig"))
    }

    // --- Backup (A5, invariant 9) -------------------------------------------

    /**
     * Auto Backup is the trial clock's only vehicle on this platform: Play has
     * no price-0 in-app product, so the 14-day stamp is a local value and a
     * reinstall that lost it would re-roll the trial indefinitely.
     *
     * (:platform's SharedPreferencesKVStoreTest owns the other half — that the
     * two rules files exclude the device-id file under exactly the name the
     * code opens it by. This test only pins that the manifest still POINTS at
     * them, which is the part that lives here.)
     */
    @Test
    fun backupIsOnAndBothRulesFilesAreReferenced() {
        val xml = manifestBody()
        assertTrue(xml.contains("android:allowBackup=\"true\""))
        assertTrue(xml.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(xml.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        assertTrue(androidFile("app/src/main/res/xml/backup_rules.xml").isFile)
        assertTrue(androidFile("app/src/main/res/xml/data_extraction_rules.xml").isFile)
    }

    // --- The activity -------------------------------------------------------

    @Test
    fun oneExportedLauncherActivity() {
        val xml = manifestBody()
        assertEquals(1, xml.split("<activity").size - 1, "the app has exactly one activity")
        assertTrue(xml.contains("android:name=\".MainActivity\""))
        assertTrue(xml.contains("android:exported=\"true\""))
        assertTrue(xml.contains("android.intent.category.LAUNCHER"))
    }

    /** Portrait: the card is 480 dp wide and every fluid size is a fraction of the WIDTH. */
    @Test
    fun theGameIsPortrait() {
        assertTrue(manifestBody().contains("android:screenOrientation=\"portrait\""))
    }

    /**
     * The route is a plain `mutableStateOf` inside `RootView`, so an activity
     * recreation throws a child out of the round they were in. These are the
     * configuration changes a phone in a child's hands actually produces, and
     * every one of them is something Compose re-reads by itself.
     */
    @Test
    fun theRoundSurvivesTheConfigurationChangesAPhoneProduces() {
        val xml = manifestBody()
        val declared = Regex("android:configChanges=\"([^\"]+)\"")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.split("|")
            ?.toSet()
            ?: fail("MainActivity declares no configChanges — a font-size change would end the round")

        for (change in listOf(
            "orientation", "screenSize", "smallestScreenSize", "screenLayout",
            "density", "fontScale", "keyboardHidden", "uiMode",
        )) {
            assertTrue(change in declared, "configChanges must handle $change")
        }
    }

    /**
     * `adjustResize`, because `RootView`'s gutter comes from
     * `WindowInsets.safeDrawing` and two screens have a text field in them
     * (« Ton prénom » and the parental gate's answer box). Without it the
     * keyboard covers the thing the adult is being asked to type into.
     */
    @Test
    fun theKeyboardResizesRatherThanCovers() {
        assertTrue(manifestBody().contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    // --- The process --------------------------------------------------------

    /**
     * The manifest names a class that exists. A rename that misses the manifest
     * is an `ActivityThread` crash on the FIRST launch of a release build, and
     * nothing before install-time can otherwise see it.
     */
    @Test
    fun theApplicationClassIsDeclaredAndPresent() {
        assertTrue(
            manifestBody().contains("android:name=\".AttrapeLettresApplication\""),
            "the graph must be process-scoped: telemetry's scope outlives an activity and " +
                "the audio engine's native handles must be built once",
        )
        assertTrue(source("AttrapeLettresApplication.kt").isNotEmpty())
    }

    // --- The composition root's four promises -------------------------------

    /**
     * INVARIANT 1. `SfxPlayer`'s constructor renders the PCM and primes the
     * static `AudioTrack`s — it IS the prewarm — so it is built at process
     * start and handed to the engine, never left to be created lazily by the
     * first `pop()` on the pointer-down path.
     */
    @Test
    fun theSfxPlayerIsBuiltAtStartupAndInjected() {
        val graph = code("AppGraph.kt")
        assertTrue(graph.contains("SfxPlayer.forDevice("), "the SFX tracks must be primed eagerly")
        assertTrue(
            Regex("LiveAudioEngine\\.live\\([^)]*sfx\\s*=").containsMatchIn(graph),
            "the pre-built player must be passed in, or the engine builds its own on first use",
        )
        assertFalse(
            graph.contains("by lazy"),
            "a lazy graph moves the prewarm to whoever touches it first — which is a finger",
        )
    }

    /**
     * A7. `PlatformEnvironment` builds `ProfileStore(sync = null)`; the
     * composition root must not quietly override it. `HttpSyncTransport` is a
     * labelled sketch whose document codec throws, and the household contract is
     * being reworked.
     */
    @Test
    fun noSyncClientIsWired() {
        for ((name, text) in appCode()) {
            assertFalse(
                text.contains("HttpSyncTransport") || text.contains("SyncClient"),
                "$name wires a sync transport — A7 says this app ships none yet",
            )
        }
    }

    /**
     * Billing stays a stub, and invariant 11 is why the stub is safe: with no
     * store, the entitlement machine can never conclude "not paid" from a failed
     * query, so no child is ever locked out by a store that blinked.
     */
    @Test
    fun noBillingDependencyAndNoBillingCode() {
        val build = stripKotlinComments(androidFile("app/build.gradle.kts").readText()).lowercase()
        assertFalse(build.contains("billingclient"), "no Play Billing dependency")

        val catalog = androidFile("gradle/libs.versions.toml").readText().lowercase()
        assertFalse(catalog.contains("billingclient"), "no Play Billing entry in the version catalog")

        for ((name, text) in appCode()) {
            assertFalse(
                text.contains("BillingClient"),
                "$name reaches for Play Billing — the seam is StubPurchaseStore until " +
                    "a Play Console product exists",
            )
        }
    }

    /**
     * ARCHITECTURE §1, and the structural half of invariant 1: `:ui` reaches the
     * device only through `:core` interfaces injected here. The day it can see
     * `:platform` directly, "an async adapter cannot implement this interface"
     * stops being a compiler guarantee and becomes a review note.
     */
    @Test
    fun uiStillCannotSeePlatform() {
        val ui = stripKotlinComments(androidFile("ui/build.gradle.kts").readText())
        assertFalse(
            ui.contains("\":platform\""),
            ":ui must not depend on :platform — the seams are injected in :app",
        )
        val art = stripKotlinComments(androidFile("art/build.gradle.kts").readText())
        assertFalse(art.contains("\":platform\""), ":art must not depend on :platform either")
    }

    /**
     * The activity's one contribution to sync-on-resume: it hands `RootView` the
     * ticker. `:ui` deliberately does not observe the Android lifecycle (that is
     * what keeps it host-testable), so if this parameter is dropped the app
     * simply stops re-syncing on resume — with no error anywhere.
     */
    @Test
    fun theActivityHandsRootViewItsResumeSignal() {
        val activity = code("MainActivity.kt")
        assertTrue(activity.contains("ChangeTicker()"), "the activity owns the ticker")
        assertTrue(
            Regex("RootView\\(.*?resume\\s*=\\s*resume", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(activity),
            "RootView must be given the activity's ticker",
        )
        assertTrue(activity.contains("phases.onResume()"), "onResume must reach the policy")
        assertTrue(activity.contains("phases.onStop()"), "onStop must reach the policy")
        assertTrue(code("AppLifecycle.kt").contains("signalResume"))
    }

    // --- helpers ------------------------------------------------------------

    /** The manifest with every XML comment removed. See the file header. */
    private fun manifestBody(): String =
        androidFile("app/src/main/AndroidManifest.xml")
            .readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun source(name: String): String =
        androidFile("app/src/main/kotlin/fr/dappit/attrapelettres/$name").readText()

    /**
     * Source with its comments removed.
     *
     * Every "must NOT appear" scan below runs on this and not on the raw text,
     * because the composition root DOCUMENTS what it deliberately does not wire
     * — `HttpSyncTransport`, `BillingClient`, a `by lazy` graph — and a scan
     * that read those notes as declarations would fail on the very files whose
     * comments explain why they are absent. "Must appear" scans use the raw
     * source, where a comment cannot satisfy them by accident either way.
     */
    private fun code(name: String): String = stripKotlinComments(source(name))

    /** Every `:app` source, comment-free, as (file name, code). */
    private fun appCode(): List<Pair<String, String>> =
        androidFile("app/src/main/kotlin/fr/dappit/attrapelettres")
            .listFiles()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.map { it.name to stripKotlinComments(it.readText()) }
            .orEmpty()

    /**
     * Block comments first, then line comments. Good enough for a source scan
     * and deliberately not a Kotlin lexer: the only thing it can get wrong is a
     * `//` inside a string literal, and nothing here asserts on one.
     */
    private fun stripKotlinComments(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }

    /**
     * Resolves a path relative to `apps/game-android/`, from wherever Gradle put
     * this JVM's working directory. Walks upward so the test survives being run
     * from the module, the Android root or the repo root; not finding the root
     * FAILS, because a contract test that silently skips is not a contract.
     */
    private fun androidFile(relative: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        for (base in generateSequence(start) { it.parentFile }.take(8)) {
            for (root in listOf(base, File(base, "apps/game-android"))) {
                if (File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory) {
                    return File(root, relative)
                }
            }
        }
        fail("could not locate apps/game-android walking up from $start")
    }
}
