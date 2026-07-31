import Foundation
import Testing

@testable import ALCore
@testable import ALPlatform

/* -------------------------------------------------------------------------- */
/* INVARIANT 11 — MONEY NEVER FAILS CLOSED, enumerated.                         */
/*                                                                             */
/* Every way StoreKit can fail, and the assertion that none of them locks a     */
/* child out. The expected values come from money.md §5.1's normative rule and  */
/* from `PurchaseStore.swift`'s mapping table — not from reading this adapter   */
/* back to itself.                                                             */
/* -------------------------------------------------------------------------- */

private struct StoreFailure: Error, Equatable {
    var reason: String
}

/// A scripted `AppStoreFacade`: every method's answer is data, including its
/// failure. This is the seam that makes the fail-open logic host-testable — the
/// real facade names StoreKit types and cannot run without a store.
private struct ScriptedFacade: AppStoreFacade {
    var entitlements: Result<[StoreEntitlement], StoreFailure> = .success([])
    var available: Result<Bool, StoreFailure> = .success(true)
    var price: Result<String?, StoreFailure> = .success("9,99 €")
    var purchaseOutcome: Result<PurchaseOutcome, StoreFailure> = .success(.userCancelled)
    var syncOutcome: Result<Void, StoreFailure> = .success(())
    /// Seconds to stall every call — the flat-network case.
    var stall: Double = 0

    private func waitIfStalling() async throws {
        if stall > 0 { try await Task.sleep(nanoseconds: UInt64(stall * 1_000_000_000)) }
    }

    func currentEntitlements() async throws -> [StoreEntitlement] {
        try await waitIfStalling()
        return try entitlements.get()
    }
    func isProductAvailable(_ productID: String) async throws -> Bool {
        try await waitIfStalling()
        return try available.get()
    }
    func displayPrice(_ productID: String) async throws -> String? {
        try await waitIfStalling()
        return try price.get()
    }
    func purchase(_ productID: String) async throws -> PurchaseOutcome {
        try await waitIfStalling()
        return try purchaseOutcome.get()
    }
    func syncWithAppStore() async throws {
        try await waitIfStalling()
        return try syncOutcome.get()
    }
}

private let unlockOwned = StoreEntitlement(
    productID: productUnlock, purchasedAtMillis: 1_700_000_000_000)
private let unlockRevoked = StoreEntitlement(
    productID: productUnlock, purchasedAtMillis: 1_700_000_000_000, isRevoked: true)
private let trialBought = StoreEntitlement(
    productID: productTrial, purchasedAtMillis: 1_699_000_000_000)

private func store(_ facade: ScriptedFacade, timeout: TimeInterval = 10) -> StoreKitPurchaseStore {
    StoreKitPurchaseStore(facade: facade, timeout: timeout)
}

@Suite("StoreKitPurchaseStore — refresh never fails closed")
struct StoreKitRefreshTests {

    @Test("a StoreKit error is unreachable, never a negative answer")
    func entitlementScanThrows() async {
        let snap = await store(
            ScriptedFacade(entitlements: .failure(StoreFailure(reason: "storekit")))
        ).refresh()
        #expect(snap == .unreachable)
        #expect(snap.reachable == false)
    }

    /// The sharpest hazard in the port. `Transaction.currentEntitlements` reads a
    /// LOCAL signed cache: on a fresh install with no network it completes
    /// normally and empty. Reporting that as `reachable: true` locks out a paying
    /// family that reinstalled on a plane.
    @Test("an empty entitlement set with an unanswering catalogue is unreachable")
    func emptyEntitlementsAndProbeThrows() async {
        let snap = await store(
            ScriptedFacade(
                entitlements: .success([]),
                available: .failure(StoreFailure(reason: "offline")))
        ).refresh()
        #expect(snap == .unreachable)
    }

    @Test("an empty catalogue answer is unreachable too")
    func emptyEntitlementsAndEmptyCatalogue() async {
        let snap = await store(
            ScriptedFacade(entitlements: .success([]), available: .success(false))
        ).refresh()
        #expect(snap == .unreachable)
    }

    /// The other half of the rule: a negative MAY downgrade once the store has
    /// answered out loud. Without this, a refund could never be honoured.
    @Test("an empty entitlement set with a live catalogue is a real negative")
    func emptyEntitlementsWithLiveCatalogue() async {
        let snap = await store(
            ScriptedFacade(entitlements: .success([]), available: .success(true))
        ).refresh()
        #expect(snap.paid == false)
        #expect(snap.reachable == true)
    }

    @Test("a signed purchase is trusted even when the catalogue is unreachable")
    func paidNeverNeedsTheProbe() async {
        let snap = await store(
            ScriptedFacade(
                entitlements: .success([unlockOwned]),
                available: .failure(StoreFailure(reason: "offline")))
        ).refresh()
        #expect(snap.paid == true)
        #expect(snap.reachable == true)
    }

    @Test("a revoked unlock is not ownership")
    func revokedIsNotPaid() async {
        let snap = await store(
            ScriptedFacade(entitlements: .success([unlockRevoked]), available: .success(true))
        ).refresh()
        #expect(snap.paid == false)
        #expect(snap.reachable == true)
    }

    @Test("a revoked unlock with an unreachable catalogue still does not downgrade")
    func revokedOfflineIsUnreachable() async {
        let snap = await store(
            ScriptedFacade(
                entitlements: .success([unlockRevoked]),
                available: .failure(StoreFailure(reason: "offline")))
        ).refresh()
        #expect(snap == .unreachable)
    }

    @Test("the trial's signed purchase date is the authoritative start")
    func trialDateIsReported() async {
        let snap = await store(
            ScriptedFacade(entitlements: .success([trialBought]), available: .success(true))
        ).refresh()
        #expect(snap.trialStartedAt == 1_699_000_000_000)
        #expect(snap.reachable == true)
    }

    @Test("the earliest trial purchase wins — a reinstall cannot buy a fresh fortnight")
    func earliestTrialWins() async {
        let later = StoreEntitlement(productID: productTrial, purchasedAtMillis: 1_710_000_000_000)
        let snap = await store(
            ScriptedFacade(entitlements: .success([later, trialBought]), available: .success(true))
        ).refresh()
        #expect(snap.trialStartedAt == 1_699_000_000_000)
    }

    @Test("a store that never answers times out to unreachable")
    func flatNetworkTimesOut() async {
        let started = Date()
        let snap = await store(ScriptedFacade(stall: 30), timeout: 0.05).refresh()
        #expect(snap == .unreachable)
        // It gave up rather than hanging the resume path.
        #expect(Date().timeIntervalSince(started) < 5)
    }
}

@Suite("StoreKitPurchaseStore — the other five calls")
struct StoreKitOtherCallsTests {

    @Test("available is true — there is a purchase path on this build")
    func availableIsTrue() {
        #expect(store(ScriptedFacade()).available == true)
    }

    @Test("beginTrial returns the signed purchase date, and nil on anything else")
    func beginTrialMapping() async {
        let ok = await store(
            ScriptedFacade(purchaseOutcome: .success(.verified(purchasedAtMillis: 42)))
        ).beginTrial()
        #expect(ok == 42)

        for outcome: Result<PurchaseOutcome, StoreFailure> in [
            .success(.userCancelled), .success(.pending), .success(.unverified),
            .success(.unavailable), .failure(StoreFailure(reason: "boom")),
        ] {
            let value = await store(ScriptedFacade(purchaseOutcome: outcome)).beginTrial()
            #expect(value == nil, Comment(rawValue: "\(outcome) must leave the local stamp alone"))
        }
    }

    /// money.md §5.3's table, including `.pending` (Ask to Buy) mapping to false.
    /// Frozen behaviour: the copy says "rien n'a été débité", which is true and
    /// misleading, and it is what the PWA does.
    @Test("purchase is true only for a verified transaction")
    func purchaseMapping() async {
        let ok = await store(
            ScriptedFacade(purchaseOutcome: .success(.verified(purchasedAtMillis: 1)))
        ).purchase()
        #expect(ok == true)

        for outcome: Result<PurchaseOutcome, StoreFailure> in [
            .success(.userCancelled), .success(.pending), .success(.unverified),
            .success(.unavailable), .failure(StoreFailure(reason: "boom")),
        ] {
            let value = await store(ScriptedFacade(purchaseOutcome: outcome)).purchase()
            #expect(value == false, Comment(rawValue: "\(outcome) must not read as bought"))
        }
    }

    @Test("restore re-scans after AppStore.sync and reports what it found")
    func restoreMapping() async {
        let found = await store(
            ScriptedFacade(entitlements: .success([unlockOwned]))
        ).restore()
        #expect(found == true)

        let nothing = await store(ScriptedFacade(entitlements: .success([]))).restore()
        #expect(nothing == false)

        let broken = await store(
            ScriptedFacade(entitlements: .failure(StoreFailure(reason: "boom")))
        ).restore()
        #expect(broken == false)
    }

    @Test("restore still scans when AppStore.sync itself fails")
    func restoreScansDespiteSyncFailure() async {
        // The local signed cache may already hold the answer; a failed sync is
        // not a reason to tell a paying parent their restore did not work.
        let found = await store(
            ScriptedFacade(
                entitlements: .success([unlockOwned]),
                syncOutcome: .failure(StoreFailure(reason: "sync")))
        ).restore()
        #expect(found == true)
    }

    @Test("a revoked unlock does not restore")
    func restoreIgnoresRevoked() async {
        let found = await store(ScriptedFacade(entitlements: .success([unlockRevoked]))).restore()
        #expect(found == false)
    }

    @Test("priceLabel passes the store's own formatting through, nil on failure")
    func priceMapping() async {
        let label = await store(ScriptedFacade(price: .success("9,99 €"))).priceLabel()
        #expect(label == "9,99 €")

        let broken = await store(
            ScriptedFacade(price: .failure(StoreFailure(reason: "boom")))
        ).priceLabel()
        #expect(broken == nil)

        let stalled = await store(ScriptedFacade(stall: 30), timeout: 0.05).priceLabel()
        #expect(stalled == nil)
    }
}

@Suite("StoreKitPurchaseStore — no failure path is a lockout")
struct StoreKitLockoutTests {

    private static let t0: Int64 = 1_700_000_000_000

    private func model(
        _ facade: ScriptedFacade, license: LicenseState, timeout: TimeInterval = 10
    ) async -> EntitlementModel {
        await MainActor.run {
            let kv = InMemoryKVStore()
            let persist = LicenseStore(kv)
            persist.save(license)
            return EntitlementModel(
                store: StoreKitPurchaseStore(facade: facade, timeout: timeout),
                persist: persist,
                time: MutableTimeSource(Self.t0))
        }
    }

    /// Every failure mode the adapter can hit, as data.
    private static let failureModes: [(name: String, facade: ScriptedFacade)] = [
        ("StoreKit threw", ScriptedFacade(entitlements: .failure(StoreFailure(reason: "storekit")))),
        (
            "empty cache, no network",
            ScriptedFacade(
                entitlements: .success([]), available: .failure(StoreFailure(reason: "offline")))
        ),
        (
            "empty cache, empty catalogue",
            ScriptedFacade(entitlements: .success([]), available: .success(false))
        ),
        ("flat network (stalls forever)", ScriptedFacade(stall: 30)),
        (
            "everything fails at once",
            ScriptedFacade(
                entitlements: .failure(StoreFailure(reason: "a")),
                available: .failure(StoreFailure(reason: "b")),
                price: .failure(StoreFailure(reason: "c")),
                purchaseOutcome: .failure(StoreFailure(reason: "d")),
                syncOutcome: .failure(StoreFailure(reason: "e")))
        ),
    ]

    /**
     * The scenario invariant 11 exists for: a family that PAID, reinstalled, and
     * opened the app somewhere with no network. Their trial ran out a month ago,
     * so if the adapter reports a false negative as authoritative the entitlement
     * machine writes `paid = false` and the child meets a paywall for a game the
     * family owns.
     */
    @Test("a paying family is never locked out, whatever the store does")
    func payingFamilySurvivesEveryFailure() async {
        for mode in Self.failureModes {
            let paid = LicenseState(
                paid: true,
                verifiedAt: Self.t0 - dayMs,
                trialStartedAt: Self.t0 - 30 * dayMs,
                clockHighWater: Self.t0 - dayMs)
            let model = await model(mode.facade, license: paid, timeout: 0.05)
            await model.refresh()

            let (entitlement, license) = await MainActor.run { (model.entitlement, model.license) }
            #expect(
                entitlement == .paid,
                Comment(rawValue: "\(mode.name) downgraded a paying family to \(entitlement)"))
            #expect(
                license.paid == true,
                Comment(rawValue: "\(mode.name) wrote paid=false to disk"))
            #expect(canPlay(entitlement), Comment(rawValue: "\(mode.name) locked the child out"))
        }
    }

    @Test("a family mid-trial is never locked out, whatever the store does")
    func trialFamilySurvivesEveryFailure() async {
        for mode in Self.failureModes {
            let mid = LicenseState(
                paid: false,
                verifiedAt: nil,
                trialStartedAt: Self.t0 - 3 * dayMs,
                clockHighWater: Self.t0 - dayMs)
            let model = await model(mode.facade, license: mid, timeout: 0.05)
            await model.refresh()

            let (entitlement, license) = await MainActor.run { (model.entitlement, model.license) }
            #expect(
                entitlement == .trial(daysLeft: 11, endsAt: Self.t0 - 3 * dayMs + trialMs),
                Comment(rawValue: "\(mode.name) moved a running trial to \(entitlement)"))
            #expect(
                license.trialStartedAt == Self.t0 - 3 * dayMs,
                Comment(rawValue: "\(mode.name) rewrote the trial start"))
            #expect(canPlay(entitlement), Comment(rawValue: "\(mode.name) locked the child out"))
        }
    }

    @Test("a brand-new device with no store at all still plays the full trial")
    func blankLicenseSurvivesEveryFailure() async {
        for mode in Self.failureModes {
            let model = await model(mode.facade, license: .blank, timeout: 0.05)
            await model.refresh()
            let entitlement = await MainActor.run { model.entitlement }
            #expect(
                entitlement == .trial(daysLeft: trialDays, endsAt: Self.t0 + trialMs),
                Comment(rawValue: "\(mode.name) gave a fresh install \(entitlement)"))
            #expect(canPlay(entitlement))
        }
    }

    /// The macOS/`swift test` facade, and any build with no store binary path.
    /// It exists so the graph is constructible off-device; it must be fail-open
    /// too, or the host suite would be proving the wrong thing.
    @Test("the no-store facade is fail-open on every call")
    func unavailableFacadeFailsOpen() async {
        let s = StoreKitPurchaseStore(facade: UnavailableAppStoreFacade())
        #expect(await s.refresh() == .unreachable)
        #expect(await s.beginTrial() == nil)
        #expect(await s.purchase() == false)
        #expect(await s.restore() == false)
        #expect(await s.priceLabel() == nil)
        #expect(s.available == true)
    }
}
