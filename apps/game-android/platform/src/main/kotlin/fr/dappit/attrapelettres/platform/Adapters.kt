package fr.dappit.attrapelettres.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.licensing.LicenseStore
import fr.dappit.attrapelettres.core.licensing.PurchaseStore
import fr.dappit.attrapelettres.core.licensing.StubPurchaseStore
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.platform.TimeSource
import fr.dappit.attrapelettres.core.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope

/* -------------------------------------------------------------------------- */
/* The one place the concrete Android adapters are chosen and composed into    */
/* the :core models the app injects at launch — the twin of ALPlatform.swift's */
/* PlatformEnvironment, so :app's onCreate stays three lines and not thirty.   */
/*                                                                             */
/* Nothing in this file is imported by :ui or :art — they only ever see the    */
/* :core interface, injected at the root. That is what keeps                   */
/* `./gradlew :core:test` able to run the entire app's logic with no store,    */
/* no network and no signing (A1).                                             */
/*                                                                             */
/* The adapters themselves live in their own files (SharedPreferencesKVStore,  */
/* SystemAppVersion, SystemReduceMotion, the transports, audio/); this file    */
/* only picks and wires them. Audio and haptics are deliberately NOT wired     */
/* here — LiveAudioEngine and PlatformHaptics are their own work package with  */
/* their own seam (core/platform/AudioPort.kt), and the app layer composes the */
/* two, exactly as iOS does.                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Build-time configuration, the Android end of Vite's `import.meta.env.VITE_*`
 * and the twin of the iOS `PlatformConfiguration` — same key names as its
 * Info.plist keys (`ALSyncURL`, `ALTelemetryURL`), read here from the
 * application's manifest `meta-data`, so the release runbook is one page for
 * both stores.
 *
 * Both endpoints are OPTIONAL and both fail silent when absent: no telemetry
 * endpoint means the whole telemetry module is inert (the tested "a dev build
 * posts nowhere" case), and no sync endpoint means household sync is disabled
 * without a word to anybody. Absent is the safe default, and it is the default
 * a build that forgot to set them gets.
 */
class PlatformConfiguration(
    syncEndpoint: String? = null,
    telemetryEndpoint: String? = null,
) {
    /** `VITE_SYNC_URL`. Base URL; the client appends `/household/{id}`. */
    val syncEndpoint: String? = normalised(syncEndpoint)

    /** `VITE_TELEMETRY_URL`. Base URL; telemetry appends `/events` and `/errors`. */
    val telemetryEndpoint: String? = normalised(telemetryEndpoint)

    companion object {
        const val SYNC_URL_KEY = "ALSyncURL"
        const val TELEMETRY_URL_KEY = "ALTelemetryURL"

        /** TS truthiness: `""` is falsy, so an empty manifest value means absent. */
        internal fun normalised(raw: String?): String? =
            raw?.trim()?.takeUnless { trimmed -> trimmed.isEmpty() }

        /** The production reader. Any failure to read is "not configured". */
        fun fromMetaData(context: Context): PlatformConfiguration {
            val meta = try {
                val packageManager = context.packageManager
                val info =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getApplicationInfo(
                            context.packageName,
                            PackageManager.ApplicationInfoFlags.of(
                                PackageManager.GET_META_DATA.toLong()
                            ),
                        )
                    } else {
                        // The flags-object overload only exists from API 33;
                        // below that the deprecated int overload is the only
                        // way to ask, so the suppression is scoped to this
                        // branch and nothing else.
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(
                            context.packageName,
                            PackageManager.GET_META_DATA,
                        )
                    }
                info.metaData
            } catch (_: Exception) {
                null
            }
            return PlatformConfiguration(
                syncEndpoint = meta?.getString(SYNC_URL_KEY),
                telemetryEndpoint = meta?.getString(TELEMETRY_URL_KEY),
            )
        }
    }
}

private val DIFFICULTY_BY_EXERCISE: Map<ExerciseId, Difficulty> =
    EXERCISES.associate { meta -> meta.id to meta.difficulty }

/**
 * `ProfileStore`'s `difficultyOf` — `EXERCISES` is the single authority for
 * reward weights (invariant 8), and this is its one production binding.
 * `getValue` throws for an id without a hub row; the totality test in this
 * module pins that no such id exists, so a new `ExerciseId` without an
 * `EXERCISES` row fails a host test rather than a child's session.
 */
internal fun exerciseDifficulty(id: ExerciseId): Difficulty =
    DIFFICULTY_BY_EXERCISE.getValue(id)

/**
 * Everything the app needs from the platform (audio aside — see the file
 * header), built once at launch:
 *
 *     val env = PlatformEnvironment(context = this, scope = processScope)
 *     setContent { AppRoot(env) }
 *
 * MAIN-THREAD BOUND, like the `:core` models it owns: `ProfileStore`,
 * `EntitlementModel` and `Telemetry` are all read from composables on the
 * touch-down path, where invariant 1 forbids a hop. Construct it in `onCreate`
 * and nowhere else.
 *
 * @param context any context; only the application context is retained.
 * @param scope where telemetry's fire-and-forget sends run. Pass a scope that
 *   lives as long as the PROCESS, not an activity — a rotation must not cancel
 *   an in-flight send. The app layer's backgrounding observer is also expected
 *   to call `telemetry.flush()` then `telemetry.awaitPendingSends()` inside a
 *   non-cancellable block — that pair is this port's `keepalive: true`.
 * @param purchases injectable so a test or a preview can substitute one.
 */
class PlatformEnvironment(
    context: Context,
    scope: CoroutineScope,
    val configuration: PlatformConfiguration =
        PlatformConfiguration.fromMetaData(context.applicationContext),
    val purchases: PurchaseStore = StubPurchaseStore(),
    val time: TimeSource = SystemTimeSource(),
) {
    // PURCHASES ARE A DELIBERATE GAP, wired to core's StubPurchaseStore above —
    // recorded here rather than quietly filled. A real PlayBillingPurchaseStore
    // needs the Play Billing library (a dependency this port has not taken) and
    // a Play Console product that does not exist yet. When both do, it has to:
    //   - own a BillingClient connection lifecycle: connect, retry on
    //     disconnect, and a PurchasesUpdatedListener held for the process's
    //     life — the Play twin of iOS's transaction-updates task;
    //   - implement refresh() as queryPurchasesAsync for the `unlock`
    //     non-consumable, mapping "the query never reached Play" to
    //     StoreSnapshot.UNREACHABLE — NOT to "not paid". Billing answers
    //     purchase queries from a local cache while offline, and a naive
    //     adapter reading that empty answer as "reachable, unowned" locks out
    //     a paying family that reinstalled on a plane (the hazard
    //     PurchaseStore's own KDoc calls the sharpest in the subsystem);
    //   - implement purchase() as launchBillingFlow plus acknowledgement,
    //     restore() as the same query re-applied (per Google account only —
    //     Play Family Library never shares in-app purchases, so no copy may
    //     promise the household), priceLabel() from queryProductDetails, and
    //     beginTrial() as a permanent null (no price-0 product on Play; the
    //     local stamp carried by Auto Backup is the trial clock, A5);
    //   - and above all MAP EVERY FAILURE to the interface's fail-open table
    //     instead of throwing. The one real hazard: core's
    //     EntitlementModel.refresh() has no try/catch because PurchaseStore is
    //     documented as non-throwing, so a billing adapter that throws would
    //     surface as an unhandled exception in :ui. It could not lock a child
    //     out — invariant 11 holds structurally, because paid state is never
    //     committed on a failed refresh — but a crash in the adult's hands is
    //     still a crash. Non-throwing is the adapter's obligation, enforced by
    //     review and its tests, not by the compiler.

    /**
     * One logical namespace, two physical prefs files — the device id lives in
     * the file the backup rules exclude, everything else in the backed-up main
     * file (A5). The routing is [DeviceIsolatingKVStore]'s and is host-tested.
     */
    val kv: KVStore = SharedPreferencesKVStore.from(context.applicationContext)

    val appVersion: SystemAppVersion = SystemAppVersion.from(context.applicationContext)

    /**
     * Exposed concretely so the app layer's ON_RESUME hook can call
     * [SystemReduceMotion.refresh] — the setting can change while backgrounded,
     * and a parent expects the mascot to settle down without a relaunch.
     */
    val reduceMotion: SystemReduceMotion = SystemReduceMotion.from(context.applicationContext)

    val licenses: LicenseStore = LicenseStore(kv)

    val entitlement: EntitlementModel =
        EntitlementModel(store = purchases, persist = licenses, time = time)

    /**
     * Always constructed, exactly like the TS module always exists: inert
     * without an endpoint. The transport is real — telemetry's contract is
     * stable, unlike sync's.
     */
    val telemetry: Telemetry = Telemetry(
        endpoint = configuration.telemetryEndpoint,
        transport = HttpTelemetryTransport(),
        kv = kv,
        appVersion = appVersion,
        scope = scope,
    )

    /**
     * Constructing this migrates v3/v2/v1 blobs forward if needed,
     * synchronously — SharedPreferences needs no hydration gate, so there is
     * nothing to await at boot and no splash logic to write.
     *
     * `sync` is null ON PURPOSE (A7): the `services/api` contract is being
     * reworked and [HttpSyncTransport] is a documented sketch with no document
     * codec behind it. When the contract lands, the wiring is one line here —
     * a `SyncClient` over an `HttpSyncTransport` reading
     * `configuration.syncEndpoint` and the household id — plus the identity
     * flow that is deferred with it.
     */
    val profiles: ProfileStore = ProfileStore(
        kv = kv,
        difficultyOf = ::exerciseDifficulty,
        time = time,
        sync = null,
    )
}
