package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.licensing.LicenseStore
import fr.dappit.attrapelettres.core.licensing.PurchaseStore
import fr.dappit.attrapelettres.core.licensing.StoreSnapshot
import fr.dappit.attrapelettres.core.platform.FixedAppVersion
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.telemetry.Telemetry
import fr.dappit.attrapelettres.core.telemetry.TelemetryTransport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// Shared fixtures for the three adult screens. Nothing here composes (A11):
// every screen decision lives in a plain class or function, and these are the
// collaborators those functions take.

/** Every byte this "device" tried to send, decoded. */
class RecordingTransport : TelemetryTransport {
    val sent = mutableListOf<String>()

    override suspend fun send(body: ByteArray, url: String) {
        sent.add(body.decodeToString())
    }
}

/**
 * A telemetry instance with a real endpoint, so `track` actually queues and
 * `flush` actually hands bytes to [transport]. `Dispatchers.Unconfined` makes
 * the send synchronous, which is what turns "did anything leave the device?"
 * into a plain assertion.
 */
class TelemetryProbe(consent: Boolean? = null) {
    val kv = InMemoryKVStore()
    val transport = RecordingTransport()
    val telemetry = Telemetry(
        endpoint = "https://t.test",
        transport = transport,
        kv = kv,
        appVersion = FixedAppVersion("0.1.0"),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    init {
        if (consent != null) telemetry.setConsent(consent)
    }

    /** Everything queued so far, as one wire string. */
    fun drain(): String {
        telemetry.flush()
        return transport.sent.joinToString("\n")
    }
}

/** Billing not wired — the release default, and what `:app` ships today. */
class SilentStore(
    override val available: Boolean = true,
    private val paid: Boolean = false,
    private val reachable: Boolean = false,
    private val price: String? = null,
    private val buys: Boolean = false,
    private val restores: Boolean = false,
) : PurchaseStore {
    var purchases = 0
        private set
    var restorations = 0
        private set

    override suspend fun refresh(): StoreSnapshot =
        StoreSnapshot(paid = paid, trialStartedAt = null, reachable = reachable)

    override suspend fun beginTrial(): Long? = null

    override suspend fun purchase(): Boolean {
        purchases += 1
        return buys
    }

    override suspend fun restore(): Boolean {
        restorations += 1
        return restores
    }

    override suspend fun priceLabel(): String? = price
}

/**
 * An adapter that breaks `PurchaseStore`'s non-throwing contract — the failure
 * a real Play Billing wrapper commits by letting a `BillingClient` exception
 * escape. Invariant 11 says it may not reach a child.
 */
class ExplodingStore : PurchaseStore {
    override val available: Boolean = true
    override suspend fun refresh(): StoreSnapshot = error("billing exploded")
    override suspend fun beginTrial(): Long? = error("billing exploded")
    override suspend fun purchase(): Boolean = error("billing exploded")
    override suspend fun restore(): Boolean = error("billing exploded")
    override suspend fun priceLabel(): String? = error("billing exploded")
}

/** A world just big enough to drive one adult screen. */
class AdultWorld(
    store: PurchaseStore = SilentStore(),
    consent: Boolean? = null,
    start: Long = 1_700_000_000_000,
) {
    val probe = TelemetryProbe(consent)
    val telemetry: Telemetry get() = probe.telemetry
    val time = MutableTimeSource(start)
    val licenses = LicenseStore(probe.kv)
    val entitlement = EntitlementModel(store = store, persist = licenses, time = time)
}

/**
 * Gradle runs tests with the module directory as the working directory; the
 * walk upwards is belt and braces for an IDE runner rooted at the repo or at
 * `apps/game-android`. Named distinctly from `CopyTest`'s own private finder so
 * the two files cannot collide.
 */
fun adultScreenSource(name: String): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/screens/$name"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        for (candidate in listOf(suffix, "ui/$suffix", "apps/game-android/ui/$suffix")) {
            val here = File(dir, candidate)
            if (here.isFile) return here
        }
        dir = dir.parentFile
    }
    return null
}

/** Source lines that are not comments — what a "this file must not mention X" scan reads. */
fun codeLines(file: File): List<Pair<Int, String>> =
    file.readLines().mapIndexed { i, line -> (i + 1) to line }.filter { (_, line) ->
        val t = line.trim()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
    }
