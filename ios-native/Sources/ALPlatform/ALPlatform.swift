import ALCore
import Foundation

// The iOS side of every protocol ALCore declares. See DECISIONS.md D4.
//
// Nothing here is imported by ALUI or ALArt — they only ever see the protocol.
// That is what lets `swift test` run the app's logic with no store, no network
// and no signing.
//
// This file is the composition root: the one place the concrete adapters are
// chosen and wired into the ALCore models the app injects at launch. It is
// deliberately constructible on macOS — a graph that can only be built on a
// device is a graph no host test ever exercises.

/**
 * Build-time configuration, the Swift end of Vite's `import.meta.env.VITE_*`.
 *
 * Both endpoints are OPTIONAL and both fail silent when absent: no telemetry
 * endpoint means the whole telemetry module is inert (the tested "a dev build
 * posts nowhere" case), and no sync endpoint means household sync is disabled
 * without a word to anybody. Absent is the safe default, and it is the default
 * a build that forgot to set them gets.
 *
 * The Info.plist key names are the app-level decision money.md flags as (F) —
 * one xcconfig-driven mechanism for every agent. They live here so there is
 * exactly one typed accessor; change them in one place if the decision lands
 * elsewhere.
 */
public struct PlatformConfiguration: Sendable {
    public static let syncURLKey = "ALSyncURL"
    public static let telemetryURLKey = "ALTelemetryURL"

    /// `VITE_SYNC_URL`. Base URL; the client appends `/household/<id>`.
    public var syncEndpoint: String?
    /// `VITE_TELEMETRY_URL`. Base URL; telemetry appends `/events` / `/errors`.
    public var telemetryEndpoint: String?

    public init(syncEndpoint: String? = nil, telemetryEndpoint: String? = nil) {
        self.syncEndpoint = Self.normalised(syncEndpoint)
        self.telemetryEndpoint = Self.normalised(telemetryEndpoint)
    }

    /// TS truthiness: `""` is falsy, so an empty Info.plist value means absent.
    private static func normalised(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    public static func fromBundle(_ bundle: Bundle = .main) -> PlatformConfiguration {
        PlatformConfiguration(
            syncEndpoint: bundle.object(forInfoDictionaryKey: syncURLKey) as? String,
            telemetryEndpoint: bundle.object(forInfoDictionaryKey: telemetryURLKey) as? String)
    }
}

/**
 * Everything the app needs from the platform, built once at launch.
 *
 * `@MainActor` because three of the models it owns are: `ProfileStore`,
 * `EntitlementModel` and `Telemetry` are all read from views on the touch-down
 * path, where invariant 1 forbids a hop.
 *
 * Audio and haptics are NOT wired here — they are a separate work package with
 * its own seam (`AudioPort.swift`) and its own adapters under `ALPlatform/Audio`.
 * The App layer composes the two.
 */
@MainActor
public final class PlatformEnvironment {
    public let configuration: PlatformConfiguration

    // The primitives.
    public let kv: KVStore
    public let time: TimeSource
    public let appVersion: AppVersionProvider
    public let reduceMotion: SystemReduceMotion

    // The models the screens read.
    public let purchases: any PurchaseStore
    public let licenses: LicenseStore
    public let entitlement: EntitlementModel
    public let telemetry: Telemetry
    public let sync: SyncClient
    public let profiles: ProfileStore

    /**
     * - Parameters:
     *   - configuration: endpoints; `.fromBundle()` in the app, explicit in tests.
     *   - defaults: the `UserDefaults` database. Production uses `.standard`,
     *     where the shipped Capacitor build's data already is (persistence.md §8).
     *     Tests pass a per-process suite — concurrent `swift test` processes
     *     share the real database and will wipe each other's keys.
     *   - purchases: injectable so a test, a preview or a build without a store
     *     can substitute one. Defaults to StoreKit on iOS and to an explicit
     *     "no store answered" (fail-open) elsewhere.
     *   - session: the URL session both transports use. One ephemeral,
     *     cookie-less, credential-less session for the whole app.
     */
    public init(
        configuration: PlatformConfiguration = .fromBundle(),
        defaults: UserDefaults = .standard,
        time: TimeSource = SystemTimeSource(),
        appVersion: AppVersionProvider = SystemAppVersion(),
        reduceMotion: SystemReduceMotion = SystemReduceMotion(),
        purchases: (any PurchaseStore)? = nil,
        session: URLSession = PrivateURLSession.make()
    ) {
        self.configuration = configuration
        let kv = UserDefaultsKVStore(defaults: defaults)
        self.kv = kv
        self.time = time
        self.appVersion = appVersion
        self.reduceMotion = reduceMotion
        self.purchases = purchases ?? StoreKitPurchaseStore.system()

        self.licenses = LicenseStore(kv)
        self.entitlement = EntitlementModel(
            store: self.purchases, persist: licenses, time: time)

        self.telemetry = Telemetry(
            endpoint: configuration.telemetryEndpoint,
            transport: URLSessionTelemetryTransport(session: session),
            kv: kv,
            appVersion: appVersion)

        // Always constructed, exactly like the TS module always exists: it is
        // inert without an endpoint, and `enabled` is what the parent-facing
        // screen reads to say so out loud.
        let syncEndpoint = configuration.syncEndpoint
        let sync = SyncClient(
            kv: kv,
            transport: URLSessionSyncTransport(endpoint: { syncEndpoint }, session: session),
            endpoint: { syncEndpoint })
        self.sync = sync

        // Constructing this migrates v3/v2/v1 forward if needed, synchronously —
        // UserDefaults needs no hydration gate, so there is nothing to await at
        // boot and no splash logic to write.
        self.profiles = ProfileStore(kv: kv, now: { time.nowMillis }, sync: sync)
    }

    /**
     * Publish the telemetry instance the screens reach through
     * `Telemetry.shared`. Explicit rather than done in `init`, so building the
     * graph in a test does not reach out and mutate a process-wide default.
     */
    public func install() {
        Telemetry.shared = telemetry
    }

    /**
     * StoreKit 2 requires a transaction listener for the app's whole lifetime:
     * interrupted purchases, Ask-to-Buy approvals, Family Sharing grants and
     * revocations arrive there and nowhere else (money.md §5.2). The App layer
     * starts this at launch and holds the task forever.
     *
     * A no-op where there is no StoreKit — the graph still builds, and the host
     * suite still runs.
     */
    public func startStoreObservation() -> Task<Void, Never>? {
        #if canImport(StoreKit) && os(iOS)
            let model = entitlement
            return observeTransactionUpdates {
                await model.refresh()
            }
        #else
            return nil
        #endif
    }
}
