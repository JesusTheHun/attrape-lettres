import Foundation
import Testing

@testable import ALCore
@testable import ALPlatform

/* -------------------------------------------------------------------------- */
/* The two small adapters — reduced motion (D14, invariant 6) and the app       */
/* version — plus the composition root, which must be constructible on a Mac or */
/* nothing here runs at all.                                                    */
/* -------------------------------------------------------------------------- */

/// A settable stand-in for the system accessibility setting. The real one cannot
/// be changed from a test, so a test that could only read it would prove that
/// the code compiles and nothing else.
private final class MotionFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Bool
    init(_ value: Bool) { self.value = value }
    var isOn: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return value
        }
        set {
            lock.lock()
            value = newValue
            lock.unlock()
        }
    }
}

@Suite("SystemReduceMotion")
struct SystemReduceMotionTests {

    private static let notification = Notification.Name("test.reduceMotionChanged")

    @Test("reports the setting as it was at construction")
    func readsAtConstruction() {
        let off = SystemReduceMotion(
            center: NotificationCenter(), notification: nil, read: { false })
        #expect(off.isReduced == false)

        let on = SystemReduceMotion(
            center: NotificationCenter(), notification: nil, read: { true })
        #expect(on.isReduced == true)
    }

    /// Invariant 6 is not "read it once at launch": a parent can turn Reduce
    /// Motion on while the child is mid-session, and the mascot must settle down
    /// without a relaunch.
    @Test("re-samples when the system posts its change notification")
    func updatesOnNotification() {
        let center = NotificationCenter()
        let flag = MotionFlag(false)
        let source = SystemReduceMotion(
            center: center, notification: Self.notification, read: { flag.isOn })

        #expect(source.isReduced == false)

        flag.isOn = true
        // Nothing has told it yet.
        #expect(source.isReduced == false)

        center.post(name: Self.notification, object: nil)
        #expect(source.isReduced == true)

        flag.isOn = false
        center.post(name: Self.notification, object: nil)
        #expect(source.isReduced == false)
    }

    @Test("re-samples on demand, for a scene that was backgrounded when it changed")
    func refreshOnDemand() {
        let flag = MotionFlag(false)
        let source = SystemReduceMotion(
            center: NotificationCenter(), notification: nil, read: { flag.isOn })
        flag.isOn = true
        #expect(source.isReduced == false)
        source.refresh()
        #expect(source.isReduced == true)
    }

    @Test("it really is the ReduceMotionSource the app injects")
    func conformsToThePort() {
        let source: any ReduceMotionSource = SystemReduceMotion(
            center: NotificationCenter(), notification: nil, read: { true })
        #expect(source.isReduced == true)
    }

    /// Smoke test of the real platform wiring: reading the live setting must not
    /// trap, and the notification name must exist. Its VALUE depends on the host
    /// machine, so nothing asserts on it.
    @Test("the system wiring resolves on this platform")
    @MainActor
    func systemWiringResolves() {
        _ = SystemReduceMotion.readSystem()
        #if canImport(UIKit) || canImport(AppKit)
            #expect(SystemReduceMotion.systemNotification != nil)
        #endif
    }
}

@Suite("SystemAppVersion")
struct SystemAppVersionTests {

    @Test("reads CFBundleShortVersionString")
    func readsMarketingVersion() {
        #expect(SystemAppVersion(infoValue: "1.4.2").marketing == "1.4.2")
    }

    /// An absent or wrongly-typed key must not produce an empty version string:
    /// telemetry stamps `v` on every event, and `versionAtLeast` compares it.
    @Test("falls back when the key is absent, empty or not a string")
    func fallsBack() {
        #expect(SystemAppVersion(infoValue: nil).marketing == "0.0.0")
        #expect(SystemAppVersion(infoValue: "").marketing == "0.0.0")
        #expect(SystemAppVersion(infoValue: 3 as Int).marketing == "0.0.0")
    }

    @Test("the fallback is the lowest version the guard can compare")
    func fallbackComparesLow() {
        // D13's native guard: a build that cannot name its own version must gate
        // conservatively, never claim to satisfy a minimum it does not.
        #expect(versionAtLeast(SystemAppVersion.fallback, "0.1.0") == false)
    }

    @Test("the bundle path yields a usable version on the host")
    func bundlePathIsTotal() {
        // The test runner's bundle has no marketing version; the point is that it
        // degrades to the fallback rather than to "".
        #expect(!SystemAppVersion(bundle: .main).marketing.isEmpty)
    }
}

@Suite("PlatformConfiguration")
struct PlatformConfigurationTests {

    @Test("absent and empty endpoints both read as absent")
    func emptyIsAbsent() {
        #expect(PlatformConfiguration().syncEndpoint == nil)
        #expect(PlatformConfiguration(syncEndpoint: "", telemetryEndpoint: "  ").syncEndpoint == nil)
        #expect(
            PlatformConfiguration(syncEndpoint: "", telemetryEndpoint: "  ").telemetryEndpoint
                == nil)
    }

    @Test("a configured endpoint survives untouched")
    func endpointsSurvive() {
        let config = PlatformConfiguration(
            syncEndpoint: "https://sync.example", telemetryEndpoint: "https://t.example")
        #expect(config.syncEndpoint == "https://sync.example")
        #expect(config.telemetryEndpoint == "https://t.example")
    }

    @Test("a bundle with no keys configures nothing, silently")
    func bundleWithoutKeys() {
        let config = PlatformConfiguration.fromBundle(.main)
        #expect(config.syncEndpoint == nil)
        #expect(config.telemetryEndpoint == nil)
    }
}

@Suite("PlatformEnvironment — the composition root")
@MainActor
struct PlatformEnvironmentTests {

    private func makeEnvironment(
        configuration: PlatformConfiguration = PlatformConfiguration(),
        defaults: UserDefaults? = nil,
        session: URLSession = RecordingURLProtocol.makeSession()
    ) -> PlatformEnvironment {
        PlatformEnvironment(
            configuration: configuration,
            defaults: defaults ?? AdapterTestDefaults.make("env"),
            time: MutableTimeSource(1_700_000_000_000),
            appVersion: FixedAppVersion("0.1.0"),
            reduceMotion: SystemReduceMotion(
                center: NotificationCenter(), notification: nil, read: { false }),
            // The host has no store. Fail-open all the way down (invariant 11).
            purchases: StoreKitPurchaseStore(facade: UnavailableAppStoreFacade()),
            session: session)
    }

    /// Rule 4: the whole graph builds on the host. A graph that only builds on a
    /// device is a graph no `swift test` ever exercises.
    @Test("builds every adapter with no store, no network and no signing")
    func buildsOnTheHost() {
        let env = makeEnvironment()
        #expect(env.kv is UserDefaultsKVStore)
        #expect(env.purchases.available == true)
        #expect(env.entitlement.storeAvailable == true)
        #expect(env.profiles.children.isEmpty)
        #expect(env.telemetry.hasConsent == false)
        #expect(env.reduceMotion.isReduced == false)
        #expect(env.appVersion.marketing == "0.1.0")
    }

    /// "Endpoint absent ⇒ the module is inert, silently" — the tested "a dev
    /// build posts nowhere" case, asserted against the same graph that DOES post
    /// when an endpoint is configured, so the test cannot pass by wiring nothing.
    @Test("telemetry posts only when an endpoint is configured")
    func inertWithoutEndpoints() async {
        let server = MockHTTPEndpoint()

        let configured = makeEnvironment(
            configuration: PlatformConfiguration(telemetryEndpoint: server.base),
            session: server.session())
        configured.telemetry.setConsent(true)
        configured.telemetry.track(.shopOpened)
        configured.telemetry.flush()
        await configured.telemetry.awaitPendingSends()
        #expect(server.captures.count == 1)

        let inert = makeEnvironment(session: server.session())
        #expect(inert.sync.enabled == false)
        inert.telemetry.setConsent(true)
        inert.telemetry.track(.shopOpened)
        inert.telemetry.flush()
        await inert.telemetry.awaitPendingSends()
        #expect(server.captures.count == 1)
    }

    @Test("sync stays disabled until a household is joined")
    func syncNeedsBothHalves() {
        let env = makeEnvironment(
            configuration: PlatformConfiguration(syncEndpoint: "https://sync.example"))
        #expect(env.sync.enabled == false)
        env.sync.joinHousehold("h-1")
        #expect(env.sync.enabled == true)
        #expect(env.sync.householdId() == "h-1")
    }

    @Test("every store shares one KV, so the household id lands at the Capacitor key")
    func oneStoreForEveryone() {
        let defaults = AdapterTestDefaults.make("shared-kv")
        let env = PlatformEnvironment(
            configuration: PlatformConfiguration(),
            defaults: defaults,
            time: MutableTimeSource(1_700_000_000_000),
            appVersion: FixedAppVersion("0.1.0"),
            reduceMotion: SystemReduceMotion(
                center: NotificationCenter(), notification: nil, read: { false }),
            purchases: StoreKitPurchaseStore(facade: UnavailableAppStoreFacade()),
            session: RecordingURLProtocol.makeSession())

        env.sync.joinHousehold("h-42")
        env.telemetry.setConsent(true)
        env.licenses.saveOnboarded()

        #expect(
            defaults.string(forKey: "CapacitorStorage.attrape-lettres:household:v1") == "h-42")
        #expect(defaults.string(forKey: "CapacitorStorage.attrape-lettres:consent:v1") == "1")
        #expect(defaults.string(forKey: "CapacitorStorage.attrape-lettres:onboarded:v1") == "1")
    }

    /// Invariant 11 through the whole graph, not just the adapter: a Mac (or any
    /// build with no store) still plays.
    @Test("a graph with no store at all is still playable")
    func failsOpenEndToEnd() async {
        let env = makeEnvironment()
        await env.entitlement.refresh()
        #expect(canPlay(env.entitlement.entitlement))
        #expect(env.entitlement.entitlement == .trial(daysLeft: trialDays, endsAt: 1_700_000_000_000 + trialMs))
    }

    @Test("there is no store observation to start off-device")
    func noStoreObservationOnTheHost() {
        let env = makeEnvironment()
        let task = env.startStoreObservation()
        #if os(iOS)
            #expect(task != nil)
            task?.cancel()
        #else
            #expect(task == nil)
        #endif
    }
}
