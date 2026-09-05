package fr.dappit.attrapelettres.core.licensing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Review-time rules turned into build-time ones.                               */
/*                                                                             */
/* Port of MoneySourceScanTests.swift, restricted to the money half (telemetry  */
/* brings its own scans). Cheap, and the only mechanism that survives a         */
/* refactor by somebody who has not read A1, A5 or invariant 11. Each scan is   */
/* one grep with a paragraph of reason behind it, and the reason is in the      */
/* test name.                                                                  */
/* -------------------------------------------------------------------------- */

/**
 * Gradle runs tests with the module directory as the working directory, so
 * `src/main/kotlin/...` resolves from there. The walk upwards is belt and
 * braces for an IDE runner rooted at the repo or the Android app instead.
 */
private fun findCoreMain(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/core"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val here = File(dir, suffix)
        if (here.isDirectory) return here
        val nested = File(dir, "core/$suffix")
        if (nested.isDirectory) return nested
        val underApp = File(dir, "apps/game-android/core/$suffix")
        if (underApp.isDirectory) return underApp
        dir = dir.parentFile
    }
    return null
}

private val coreMainOrNull: File? = findCoreMain()

class LicensingSourceScanTest {

    private fun coreMain(): File = assertNotNull(
        coreMainOrNull,
        "the scan could not locate :core's main sources from ${System.getProperty("user.dir")}",
    )

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    @Test
    fun `the scan can find the sources it is meant to scan`() {
        val licensing = File(coreMain(), "licensing")
        assertTrue(licensing.isDirectory, "no licensing package at ${licensing.path}")
        assertTrue(kotlinFiles(licensing).size >= 4, "the licensing package lost a file")
        assertTrue(kotlinFiles(coreMain()).size > 20, "the scan is looking at the wrong tree")
    }

    /**
     * The trial clock takes an injected `TimeSource`. This is not a style
     * opinion: a 14-day offline grace whose clock cannot be advanced in a test
     * is a 14-day offline grace nobody has ever verified, and on Android the
     * trial start is a local stamp (A5) with no signed receipt behind it — so
     * the clock is the only evidence there is.
     */
    @Test
    fun `licensing reads no system clock directly`() {
        val banned = listOf(
            "System.currentTimeMillis",
            "System.nanoTime",
            "Instant.now",
            "LocalDate",
            "SystemTimeSource",
        )
        for (file in kotlinFiles(File(coreMain(), "licensing"))) {
            val source = file.readText()
            for (token in banned) {
                assertTrue(
                    !source.contains(token),
                    "${file.name} reads the system clock via `$token`; inject TimeSource",
                )
            }
        }
    }

    /**
     * A1. `:core` is a plain JVM module, so these would not resolve anyway —
     * but the failure would arrive as a puzzling unresolved reference in a
     * module somebody was about to "fix" by applying the Android plugin. Say it
     * out loud instead.
     */
    @Test
    fun `core imports nothing from the Android platform`() {
        val banned = listOf("import android.", "import androidx.", "import com.android.")
        for (file in kotlinFiles(coreMain())) {
            for (line in file.readLines()) {
                val trimmed = line.trim()
                for (token in banned) {
                    assertTrue(
                        !trimmed.startsWith(token),
                        "${file.name} has `$trimmed` — that belongs in :platform",
                    )
                }
            }
        }
    }

    /**
     * The purchase seam is vendor-free by design: the only parties in the
     * payment path are Apple and Google, and Kids Category guideline 1.3 says
     * no personally identifiable information or device information may go to a
     * third party. `PurchaseStore` is an interface; the billing library is named
     * exactly once in this app, in the `:platform` adapter that implements it.
     */
    @Test
    fun `core names no billing vendor anywhere`() {
        val banned = listOf(
            "BillingClient",
            "com.android.billingclient",
            "Play Billing",
            "RevenueCat",
            "com.revenuecat",
        )
        for (file in kotlinFiles(coreMain())) {
            val source = file.readText()
            for (token in banned) {
                assertTrue(
                    !source.contains(token),
                    "${file.name} names `$token`; :core sees the PurchaseStore interface only",
                )
            }
        }
    }

    /**
     * The two platforms are NOT symmetric on family, and this is the scan that
     * keeps the difference honest.
     *
     * iOS switches Family Sharing on for the €11.99 non-consumable in App Store
     * Connect: six people, free, native — so the iOS app's copy can promise the
     * whole household and keep the promise. Google Play Family Library
     * explicitly does not share in-app purchases, ever; Android restores per
     * Google account only. Copy that promises what this build cannot deliver is
     * a refund request and a one-star review, so the phrase is banned outright
     * rather than left to whoever writes the paywall screen.
     */
    @Test
    fun `core never promises the whole family what Android cannot deliver`() {
        val banned = listOf("toute la famille", "toute la fratrie", "Partage familial")
        for (file in kotlinFiles(coreMain())) {
            val source = file.readText()
            for (token in banned) {
                assertTrue(
                    !source.contains(token),
                    "${file.name} promises « $token »; Play does not share in-app purchases",
                )
            }
        }
    }

    /**
     * The web's `webStore` reported ownership unconditionally *and the provider
     * persisted that answer* — on the web a deliberate product decision (no
     * payment rail, so no gating), in a store build a silent unlock for
     * everyone. iOS fences its granting double behind `#if DEBUG`; Kotlin has no
     * such fence, so no shipped store may report a purchase nobody made. The
     * granting doubles live in the test source set, which is not in the APK.
     */
    @Test
    fun `no shipped store reports a purchase nobody made`() {
        for (file in kotlinFiles(File(coreMain(), "licensing"))) {
            val source = file.readText()
            assertTrue(
                !source.contains("paid = true"),
                "${file.name} builds an ownership-granting snapshot; keep those in test sources",
            )
        }
    }
}
