import ALCore
import Foundation

/* -------------------------------------------------------------------------- */
/* The StoreKit 2 adapter — the only place in the app that talks to Apple, and  */
/* the only party in the payment path besides the family. No RevenueCat, no     */
/* Adapty, no Superwall, no analytics SDK, no HTTP call to anything but Apple.  */
/* That is both the product decision and the cheapest possible Kids Category    */
/* 1.3 compliance story: there is no third party to send anything to.          */
/*                                                                             */
/* INVARIANT 11 — MONEY NEVER FAILS CLOSED. Every failure path in this file     */
/* resolves to a snapshot ALCore's entitlement machine reads as "the store did  */
/* not answer", which `canPlay` treats as playable. An unreachable App Store, a */
/* timed-out receipt check, a flat network or a StoreKit error may never lock a */
/* paying family out. The protocol is non-throwing precisely so this adapter,   */
/* and not six hand-written catch clauses at the call sites, owns that mapping  */
/* (PurchaseStore.swift).                                                      */
/* -------------------------------------------------------------------------- */

// MARK: - The seam

/**
 * The slice of StoreKit this adapter needs, expressed without StoreKit types.
 *
 * It exists so the part invariant 11 actually cares about — the fail-open
 * mapping, the `reachable` rule of money.md §5.1, the timeout — is testable on
 * a Mac with no store, no signing and no network. `StoreKitFacade` is the one
 * production conformance and is a thin, logic-free translation layer; a fake
 * conformance in the tests enumerates every way the real one can fail.
 *
 * Every method may throw. None of the throws escape `StoreKitPurchaseStore`.
 */
public protocol AppStoreFacade: Sendable {
    /// VERIFIED entitlements only — an unverified (unsigned) claim is never
    /// trusted, so the facade drops it rather than reporting it.
    func currentEntitlements() async throws -> [StoreEntitlement]

    /// Did the App Store answer out loud? `true` iff the product metadata came
    /// back. This is the probe of money.md §5.1 step 3, and the difference
    /// between "genuinely not purchased" and "local cache not yet populated".
    func isProductAvailable(_ productID: String) async throws -> Bool

    /// Localised, store-formatted price, e.g. "9,99 €".
    func displayPrice(_ productID: String) async throws -> String?

    /// Present the purchase sheet. Finishing a verified transaction is the
    /// facade's job (StoreKit re-delivers an unfinished one forever).
    func purchase(_ productID: String) async throws -> PurchaseOutcome

    /// `AppStore.sync()` — the control Apple requires "Restaurer" to invoke.
    func syncWithAppStore() async throws
}

/// One verified entitlement, flattened out of `StoreKit.Transaction`.
public struct StoreEntitlement: Equatable, Sendable {
    public var productID: String
    /// `Transaction.purchaseDate` in epoch milliseconds (money.md §2.2 — `Int64`
    /// everywhere, never `Date`, so the arithmetic and the persisted blob stay
    /// byte-identical to the PWA's).
    public var purchasedAtMillis: Int64
    /// A refunded / family-revoked entitlement is not ownership.
    public var isRevoked: Bool

    public init(productID: String, purchasedAtMillis: Int64, isRevoked: Bool = false) {
        self.productID = productID
        self.purchasedAtMillis = purchasedAtMillis
        self.isRevoked = isRevoked
    }
}

/// `Product.PurchaseResult`, flattened. (money.md §5.3)
public enum PurchaseOutcome: Equatable, Sendable {
    case verified(purchasedAtMillis: Int64)
    case unverified
    case userCancelled
    /// **Ask to Buy.** In a six-year-old's app this is not an edge case. It maps
    /// to `false` like the TS did — literally true ("rien n'a été débité") and
    /// misleading (it may yet succeed), and the `Transaction.updates` listener
    /// is what eventually picks it up. Behaviour is frozen: ported as-is.
    case pending
    /// The product id is not in the catalogue / the store did not answer.
    case unavailable
}

/// Thrown by the internal timeout. Never escapes this file.
struct StoreTimedOut: Error {}

// MARK: - The adapter

/**
 * `PurchaseStore` over StoreKit 2.
 *
 * Every method here is total: it returns the fail-open value on any error, any
 * cancellation and any timeout. The normative mapping (PurchaseStore.swift):
 *
 * | failure                                                     | returns        |
 * |-------------------------------------------------------------|----------------|
 * | `refresh()` — any error, any timeout                         | `.unreachable` |
 * | `beginTrial()` — any error, cancel                           | `nil`          |
 * | `purchase()` — any error, cancel, `.pending`, `.unverified`  | `false`        |
 * | `restore()` — any error                                      | `false`        |
 * | `priceLabel()` — any error                                   | `nil`          |
 */
public struct StoreKitPurchaseStore: PurchaseStore {
    /// money.md §5.1: "Wrap the whole thing in a timeout … whose expiry returns
    /// `.unreachable`."
    public static let defaultTimeout: TimeInterval = 10

    private let facade: any AppStoreFacade
    private let unlockID: String
    private let trialID: String
    private let timeout: TimeInterval

    public init(
        facade: any AppStoreFacade,
        unlockID: String = productUnlock,
        trialID: String = productTrial,
        timeout: TimeInterval = StoreKitPurchaseStore.defaultTimeout
    ) {
        self.facade = facade
        self.unlockID = unlockID
        self.trialID = trialID
        self.timeout = timeout
    }

    /// There is a purchase path on this build.
    public var available: Bool { true }

    /**
     * Current ownership — and the sharpest hazard in the port (money.md §5.1).
     *
     * `Transaction.currentEntitlements` reads a *local signed cache*. On a fresh
     * install with no network that cache is empty and the loop completes
     * normally, so the naive adapter reports `paid: false, reachable: true`,
     * `applySnapshot` treats it as authoritative, and a paying family that
     * reinstalled on a plane is locked out of an app they own.
     *
     * The rule: **`reachable` means "the store answered out loud", and a
     * negative may only downgrade when it did.**
     *
     *   1. Collect verified entitlements. `.unverified` never counts; a non-nil
     *      `revocationDate` on the unlock is not paid.
     *   2. `paid == true` ⇒ `reachable: true`. A signed positive is trustworthy
     *      online or off. **A paid family is never downgraded by this path.**
     *   3. `paid == false` ⇒ probe the catalogue. Throws or empty ⇒
     *      `.unreachable`. Succeeds ⇒ the device reached the App Store this
     *      session, so an empty entitlement set is a real negative.
     */
    public func refresh() async -> StoreSnapshot {
        do {
            return try await withStoreTimeout(timeout) { try await self.scan() }
        } catch {
            // Timeout, cancellation, StoreKit error, anything at all.
            return .unreachable
        }
    }

    private func scan() async throws -> StoreSnapshot {
        let entitlements = try await facade.currentEntitlements()
        let paid = entitlements.contains { $0.productID == unlockID && !$0.isRevoked }
        // The trial's purchaseDate is signed by Apple and survives a reinstall;
        // a revoked one is still evidence the fortnight was started.
        let trialStartedAt = entitlements
            .filter { $0.productID == trialID }
            .map(\.purchasedAtMillis)
            .min()

        if paid {
            return StoreSnapshot(paid: true, trialStartedAt: trialStartedAt, reachable: true)
        }

        // Cannot tell "not purchased" from "cache not populated" — ask out loud.
        guard try await facade.isProductAvailable(unlockID) else { return .unreachable }
        return StoreSnapshot(paid: false, trialStartedAt: trialStartedAt, reachable: true)
    }

    /**
     * Start the fortnight. Buying the price-0 "14-day Trial" non-consumable IS
     * the mechanism App Review 3.1.1 prescribes — the purchase sheet appearing
     * for a €0 product is the point, not a bug — and its `purchaseDate` is what
     * survives a reinstall.
     *
     * Anything else, including a cancel, returns `nil` and the model's local
     * stamp stands. The child's trial never depends on this call succeeding.
     */
    public func beginTrial() async -> Int64? {
        do {
            // No timeout, for the same reason `purchase()` has none: this
            // presents the purchase sheet and a parent may take minutes.
            let outcome = try await facade.purchase(trialID)
            if case .verified(let millis) = outcome { return millis }
            return nil
        } catch {
            return nil
        }
    }

    /// Buy the unlock. `true` only on a verified transaction.
    public func purchase() async -> Bool {
        do {
            // No timeout: the purchase sheet is modal and a parent may take
            // minutes (Ask to Buy, a password, a re-auth). Timing that out would
            // cancel a purchase in flight, which is worse than waiting.
            let outcome = try await facade.purchase(unlockID)
            if case .verified = outcome { return true }
            return false
        } catch {
            return false
        }
    }

    /**
     * Re-apply prior purchases. `AppStore.sync()` (the control Apple requires to
     * exist), then re-run the entitlement scan; `true` iff a verified, unrevoked
     * unlock is now present.
     *
     * The sync failing is not fatal — the scan runs anyway, because the local
     * signed cache may already hold the answer.
     */
    public func restore() async -> Bool {
        try? await facade.syncWithAppStore()
        do {
            let entitlements = try await withStoreTimeout(timeout) {
                try await self.facade.currentEntitlements()
            }
            return entitlements.contains { $0.productID == unlockID && !$0.isRevoked }
        } catch {
            return false
        }
    }

    /// Localised price. `nil` on any failure; the screens fall back to
    /// `UNLOCK_PRICE_EUR` → "9,99 €".
    public func priceLabel() async -> String? {
        do {
            return try await withStoreTimeout(timeout) { try await self.facade.displayPrice(self.unlockID) }
        } catch {
            return nil
        }
    }
}

/// The whole-operation timeout of money.md §5.1. Kept file-private-ish
/// (internal) so it cannot become a general-purpose utility somebody applies to
/// the purchase sheet.
func withStoreTimeout<T: Sendable>(
    _ seconds: TimeInterval,
    _ operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
            throw StoreTimedOut()
        }
        defer { group.cancelAll() }
        guard let first = try await group.next() else { throw StoreTimedOut() }
        return first
    }
}

// MARK: - The production facade

#if canImport(StoreKit) && os(iOS)
    import StoreKit

    /// The one place StoreKit types are named. Deliberately logic-free: every
    /// decision that invariant 11 depends on lives in `StoreKitPurchaseStore`,
    /// where a host test can reach it.
    @available(iOS 17.0, *)
    public struct StoreKitFacade: AppStoreFacade {
        public init() {}

        private static func millis(_ date: Date) -> Int64 {
            Int64(date.timeIntervalSince1970 * 1000)
        }

        public func currentEntitlements() async throws -> [StoreEntitlement] {
            var out: [StoreEntitlement] = []
            for await result in Transaction.currentEntitlements {
                // `.unverified` is discarded: never trust an unsigned claim.
                guard case .verified(let t) = result else { continue }
                out.append(
                    StoreEntitlement(
                        productID: t.productID,
                        purchasedAtMillis: Self.millis(t.purchaseDate),
                        isRevoked: t.revocationDate != nil))
            }
            return out
        }

        public func isProductAvailable(_ productID: String) async throws -> Bool {
            try await !Product.products(for: [productID]).isEmpty
        }

        public func displayPrice(_ productID: String) async throws -> String? {
            try await Product.products(for: [productID]).first?.displayPrice
        }

        public func purchase(_ productID: String) async throws -> PurchaseOutcome {
            guard let product = try await Product.products(for: [productID]).first else {
                return .unavailable
            }
            switch try await product.purchase() {
            case .success(let verification):
                switch verification {
                case .verified(let t):
                    // An unfinished transaction is re-delivered forever.
                    await t.finish()
                    return .verified(purchasedAtMillis: Self.millis(t.purchaseDate))
                case .unverified:
                    return .unverified
                }
            case .userCancelled:
                return .userCancelled
            case .pending:
                return .pending
            @unknown default:
                return .unverified
            }
        }

        public func syncWithAppStore() async throws {
            try await AppStore.sync()
        }
    }

    /**
     * The lifetime listener StoreKit 2 requires — **an addition, not a feature**
     * (money.md §5.2). Interrupted purchases, Ask-to-Buy approvals, Family
     * Sharing grants and revocations arrive here and nowhere else; without it an
     * Ask-to-Buy approval never lands. The TypeScript had no analogue because
     * Capacitor's stub had no concept of one.
     *
     * Start it once at launch and keep the task for the app's whole lifetime.
     */
    @available(iOS 17.0, *)
    public func observeTransactionUpdates(
        _ onChange: @escaping @Sendable () async -> Void
    ) -> Task<Void, Never> {
        Task.detached {
            for await result in Transaction.updates {
                if case .verified(let t) = result { await t.finish() }
                await onChange()
            }
        }
    }
#endif

/**
 * The facade on a platform with no App Store binary path — i.e. the macOS host
 * that runs `swift test`. Every call reports "the store did not answer", which
 * is the fail-open value; nothing is ever locked because the tests ran on a Mac.
 *
 * This is an explicit no-op, not a silent one: it exists so the composition root
 * is constructible on macOS (rule 4) without `#if` at every call site.
 */
public struct UnavailableAppStoreFacade: AppStoreFacade {
    public init() {}

    public struct NoStore: Error {
        public init() {}
    }

    public func currentEntitlements() async throws -> [StoreEntitlement] { throw NoStore() }
    public func isProductAvailable(_ productID: String) async throws -> Bool { false }
    public func displayPrice(_ productID: String) async throws -> String? { nil }
    public func purchase(_ productID: String) async throws -> PurchaseOutcome { .unavailable }
    public func syncWithAppStore() async throws { throw NoStore() }
}

extension StoreKitPurchaseStore {
    /// The store the app injects at launch: real StoreKit on iOS, an explicit
    /// "no store answered" everywhere else. Fail-open either way.
    public static func system(timeout: TimeInterval = StoreKitPurchaseStore.defaultTimeout)
        -> StoreKitPurchaseStore
    {
        #if canImport(StoreKit) && os(iOS)
            return StoreKitPurchaseStore(facade: StoreKitFacade(), timeout: timeout)
        #else
            return StoreKitPurchaseStore(facade: UnavailableAppStoreFacade(), timeout: timeout)
        #endif
    }
}
