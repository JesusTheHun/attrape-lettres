import Foundation
import Testing

@testable import ALCore

// money.md §6.7, plus the chaos-store property from §6.2.6.

private let T0: Int64 = 1_700_000_000_000

// MARK: - Test doubles

/// A scriptable store. Every method is non-throwing by protocol, so the fail-open
/// mapping lives here rather than in six catch clauses at the call sites.
private final class ScriptedStore: PurchaseStore, @unchecked Sendable {
    let available: Bool
    private let lock = NSLock()

    private var _snapshot: StoreSnapshot
    private var _trialDate: Int64?
    private var _purchaseResult: Bool
    private var _restoreResult: Bool
    private var _price: String?
    private var _refreshes = 0

    init(
        available: Bool = true,
        snapshot: StoreSnapshot = .unreachable,
        trialDate: Int64? = nil,
        purchaseResult: Bool = false,
        restoreResult: Bool = false,
        price: String? = nil
    ) {
        self.available = available
        self._snapshot = snapshot
        self._trialDate = trialDate
        self._purchaseResult = purchaseResult
        self._restoreResult = restoreResult
        self._price = price
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    var refreshes: Int { sync { _refreshes } }
    var snapshot: StoreSnapshot {
        get { sync { _snapshot } }
        set { sync { _snapshot = newValue } }
    }
    var purchaseResult: Bool {
        get { sync { _purchaseResult } }
        set { sync { _purchaseResult = newValue } }
    }

    func refresh() async -> StoreSnapshot {
        sync {
            _refreshes += 1
            return _snapshot
        }
    }
    func beginTrial() async -> Int64? { sync { _trialDate } }
    func purchase(_ tier: UnlockTier) async -> Bool { sync { _purchaseResult } }
    func restore() async -> Bool { sync { _restoreResult } }
    func priceLabel(_ tier: UnlockTier) async -> String? { sync { _price } }
}

/// A store whose `refresh()` parks until the test opens the gate. Lets the
/// coalescing window be observed deterministically instead of raced for.
private final class GatedStore: PurchaseStore, @unchecked Sendable {
    let available = true
    private let lock = NSLock()
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var opened = false
    private var _entered = 0

    var entered: Int {
        lock.lock()
        defer { lock.unlock() }
        return _entered
    }

    func open() {
        lock.lock()
        opened = true
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        for w in pending { w.resume() }
    }

    private func park(_ c: CheckedContinuation<Void, Never>) {
        lock.lock()
        _entered += 1
        if opened {
            lock.unlock()
            c.resume()
            return
        }
        waiters.append(c)
        lock.unlock()
    }

    func refresh() async -> StoreSnapshot {
        await withCheckedContinuation { park($0) }
        return StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
    }
    func beginTrial() async -> Int64? { nil }
    func purchase(_ tier: UnlockTier) async -> Bool { false }
    func restore() async -> Bool { false }
    func priceLabel(_ tier: UnlockTier) async -> String? { nil }
}

/// Deterministic chaos: mostly unreachable, occasionally a real answer, never a
/// reachable *positive* after the first one. money.md §6.2.6.
private final class ChaosPurchaseStore: PurchaseStore, @unchecked Sendable {
    let available = true
    private let lock = NSLock()
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    private func roll() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    func refresh() async -> StoreSnapshot {
        // Every answer is unreachable. That is the whole point: no sequence of
        // "the store did not answer" may take a confirmed paid family down.
        _ = roll()
        return .unreachable
    }
    func beginTrial() async -> Int64? { nil }
    func purchase(_ tier: UnlockTier) async -> Bool { false }
    func restore() async -> Bool { false }
    func priceLabel(_ tier: UnlockTier) async -> String? { nil }
}

/// Awaits `beginTrial()`'s follow-up task.
///
/// This replaced a fixed `for _ in 0..<20 { await Task.yield() }`, which was
/// quietly flaky from the day it was written and started failing about one run
/// in eight once the suite passed a thousand tests: under parallel load the
/// store call had not been scheduled by the time the yields ran out, so
/// `beginTrialTakesEarliest` read the local stamp instead of the authoritative
/// one.
///
/// `Task.yield()` offers the CURRENT executor a chance to run something else and
/// promises nothing about a task on another thread, so "enough yields" is not a
/// quantity that exists. Polling for the effect instead would fix these three
/// tests and not the fourth, because three of them assert that something did NOT
/// change — and there is no condition that becomes true when nothing happens.
///
/// The only reliable answer was to make the work awaitable, so `beginTrial()`
/// now keeps its task in `trialTask`. A test cannot be made honest against work
/// it has no way to wait for.
@MainActor
private func pump(_ model: EntitlementModel) async {
    await model.trialTask?.value
}

// MARK: - Tests

@Suite("EntitlementModel")
@MainActor
struct EntitlementModelTests {

    private func make(
        store: PurchaseStore,
        kv: InMemoryKVStore = InMemoryKVStore(),
        now: Int64 = T0
    ) -> (EntitlementModel, MutableTimeSource, LicenseStore) {
        let time = MutableTimeSource(now)
        let persist = LicenseStore(kv)
        return (
            EntitlementModel(store: store, persist: persist, time: time), time, persist
        )
    }

    @Test("init reads persisted state and stamps checkedAt from the injected clock")
    func initReadsPersistedState() {
        let kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(paid: true, verifiedAt: T0))
        LicenseStore(kv).saveOnboarded()
        let (model, _, _) = make(store: StubPurchaseStore(), kv: kv, now: T0 + 5)
        #expect(model.license == LicenseState(paid: true, verifiedAt: T0))
        #expect(model.onboarded)
        #expect(model.checkedAt == T0 + 5)
        #expect(model.priceLabel == nil)
        #expect(model.storeAvailable)
        #expect(model.entitlement == .paid)
    }

    /// The frozen-`checkedAt` behaviour, ported deliberately: "a child
    /// mid-exercise when the fortnight runs out gets to finish."
    @Test("does not lapse mid-session when the clock advances — only refresh moves checkedAt")
    func checkedAtIsNotALiveClock() async {
        let kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(trialStartedAt: T0))
        let (model, time, _) = make(store: StubPurchaseStore(), kv: kv, now: T0)
        #expect(model.entitlement != .expired)

        time.nowMillis = T0 + trialMs + dayMs
        #expect(model.entitlement != .expired, "a timer must not tick the entitlement")
        #expect(model.checkedAt == T0)

        await model.refresh()
        #expect(model.entitlement == .expired)
        #expect(model.checkedAt == T0 + trialMs + dayMs)
    }

    @Test("beginTrial persists onboarding and stamps the trial before any await")
    func beginTrialIsSynchronousFirst() async {
        let kv = InMemoryKVStore()
        let store = ScriptedStore(trialDate: nil)
        let (model, _, persist) = make(store: store, kv: kv, now: T0)

        model.beginTrial()
        // Synchronously, before the store task has had a chance to run:
        #expect(model.onboarded)
        #expect(persist.loadOnboarded())
        #expect(model.license.trialStartedAt == T0)
        #expect(persist.load().trialStartedAt == T0)

        await pump(model)
        // nil from the store ⇒ the local stamp stands, silently.
        #expect(model.license.trialStartedAt == T0)
    }

    @Test("beginTrial never overwrites an existing stamp")
    func beginTrialKeepsExistingStamp() async {
        let kv = InMemoryKVStore()
        LicenseStore(kv).save(LicenseState(trialStartedAt: T0 - 5 * dayMs))
        let (model, _, _) = make(store: ScriptedStore(), kv: kv, now: T0)
        model.beginTrial()
        await pump(model)
        #expect(model.license.trialStartedAt == T0 - 5 * dayMs)
    }

    @Test("beginTrial takes the authoritative date when it is earlier")
    func beginTrialTakesEarliest() async {
        let kv = InMemoryKVStore()
        let store = ScriptedStore(trialDate: T0 - 12 * dayMs)
        let (model, _, persist) = make(store: store, kv: kv, now: T0)
        model.beginTrial()
        #expect(model.license.trialStartedAt == T0)
        await pump(model)
        #expect(model.license.trialStartedAt == T0 - 12 * dayMs)
        #expect(persist.load().trialStartedAt == T0 - 12 * dayMs)
    }

    @Test("beginTrial ignores an authoritative date that is later")
    func beginTrialIgnoresLaterAuthoritative() async {
        let store = ScriptedStore(trialDate: T0 + 3 * dayMs)
        let (model, _, _) = make(store: store, now: T0)
        model.beginTrial()
        await pump(model)
        #expect(model.license.trialStartedAt == T0)
    }

    @Test("refresh applies a reachable snapshot and persists it")
    func refreshAppliesAndPersists() async {
        let kv = InMemoryKVStore()
        let store = ScriptedStore(
            snapshot: StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true))
        let (model, _, persist) = make(store: store, kv: kv, now: T0)
        await model.refresh()
        #expect(model.license.paid)
        #expect(model.license.verifiedAt == T0)
        #expect(persist.load().paid)
        #expect(model.entitlement == .paid)
    }

    @Test("an unreachable refresh changes nothing but the clock")
    func unreachableRefreshChangesOnlyTheClock() async {
        let kv = InMemoryKVStore()
        LicenseStore(kv).save(
            LicenseState(paid: true, verifiedAt: T0, trialStartedAt: T0))
        let (model, time, _) = make(store: StubPurchaseStore(), kv: kv, now: T0 + dayMs)
        await model.refresh()
        #expect(model.license.paid)
        #expect(model.license.verifiedAt == T0, "never re-stamped without a confirmation")
        #expect(model.license.clockHighWater == T0 + dayMs)
        #expect(model.entitlement == .paid)
        _ = time
    }

    @Test("purchase refreshes only on success")
    func purchaseRefreshesOnlyOnSuccess() async {
        let store = ScriptedStore(purchaseResult: false)
        let (model, _, _) = make(store: store, now: T0)
        #expect(await model.purchase() == false)
        #expect(store.refreshes == 0)

        store.purchaseResult = true
        store.snapshot = StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
        #expect(await model.purchase() == true)
        #expect(store.refreshes == 1)
        #expect(model.entitlement == .paid)
    }

    /// The asymmetry with `purchase()` is in the TS and is correct:
    /// `AppStore.sync()` can materialise entitlements even when the call reports
    /// nothing restored.
    @Test("restore refreshes unconditionally")
    func restoreRefreshesAlways() async {
        let store = ScriptedStore(restoreResult: false)
        let (model, _, _) = make(store: store, now: T0)
        #expect(await model.restore() == false)
        #expect(store.refreshes == 1)
    }

    @Test("priceLabel is fetched once and never retried")
    func priceLabelFetchedOnce() async {
        let store = ScriptedStore(price: nil)
        let (model, _, _) = make(store: store, now: T0)
        await model.refresh()
        #expect(model.priceLabel == nil)
        await model.refresh()
        await model.refresh()
        #expect(model.priceLabel == nil)
        #expect(store.refreshes == 3)
    }

    @Test("priceLabel lands on the first refresh")
    func priceLabelLands() async {
        let store = ScriptedStore(price: "9,99 €")
        let (model, _, _) = make(store: store, now: T0)
        await model.refresh()
        #expect(model.priceLabel == "9,99 €")
    }

    /// money.md R1 — React's single-threaded read-modify-write has no SwiftUI
    /// equivalent, so a second refresh awaits the first instead of racing it.
    @Test("two concurrent refreshes coalesce into one store call")
    func concurrentRefreshesCoalesce() async {
        let gate = GatedStore()
        let (model, _, _) = make(store: gate, now: T0)

        let first = Task { await model.refresh() }
        // Park inside the store.
        for _ in 0..<200 where gate.entered == 0 { await Task.yield() }
        #expect(gate.entered == 1)

        let second = Task { await model.refresh() }
        // Deliberately a bounded yield loop and NOT a wait-for-condition: what
        // is being checked is an ABSENCE (no second store call), and there is no
        // event that signals "nothing is going to happen". The assertion after
        // `gate.open()` is the real proof; this only widens the window in which
        // a bug could show itself.
        for _ in 0..<200 { await Task.yield() }

        gate.open()
        await first.value
        await second.value

        #expect(gate.entered == 1, "the second refresh started its own store call")
        #expect(model.license.paid)
    }

    @Test("a refresh after an in-flight one has completed is a real refresh")
    func serialisationDoesNotSwallowLaterRefreshes() async {
        let store = ScriptedStore()
        let (model, _, _) = make(store: store, now: T0)
        await model.refresh()
        await model.refresh()
        #expect(store.refreshes == 2)
    }

    /// money.md §6.2.6. Once a reachable answer has confirmed `paid` at `v`, no
    /// sequence of unreachable answers and no clock walk produces `.expired`
    /// before `v + OFFLINE_GRACE_MS`.
    @Test("chaos: no run of unreachable answers can lock a confirmed paid family out")
    func chaosStoreNeverLocksOut() async {
        let kv = InMemoryKVStore()
        // The one reachable positive, at T0.
        LicenseStore(kv).save(
            LicenseState(
                paid: true, verifiedAt: T0, trialStartedAt: T0 - 200 * dayMs,
                clockHighWater: T0))
        let store = ChaosPurchaseStore(seed: 0xBADC_0FFE)
        let time = MutableTimeSource(T0)
        let model = EntitlementModel(store: store, persist: LicenseStore(kv), time: time)

        var rng: UInt64 = 0x5EED
        func next() -> UInt64 {
            rng &+= 0x9E37_79B9_7F4A_7C15
            var z = rng
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }

        for _ in 0..<1000 {
            // A randomly walking clock — forwards and backwards.
            let delta = Int64(next() % UInt64(6 * 3600 * 1000)) - Int64(3 * 3600 * 1000)
            time.nowMillis += delta
            await model.refresh()
            if model.license.clockHighWater <= T0 + offlineGraceMs {
                #expect(model.entitlement == .paid)
                #expect(canPlay(model.entitlement))
            }
        }
    }
}
