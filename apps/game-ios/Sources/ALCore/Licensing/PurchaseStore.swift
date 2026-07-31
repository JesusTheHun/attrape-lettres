/* -------------------------------------------------------------------------- */
/* The in-app-purchase seam.                                                    */
/*                                                                             */
/* Vendor-free by design: the only parties in the payment path are Apple and    */
/* Google. No RevenueCat, no analytics SDK, nothing that would put a third      */
/* party between a child and the unlock — which is also what keeps us inside    */
/* Kids Category guideline 1.3 ("may not send personally identifiable           */
/* information or device information to third parties").                        */
/*                                                                             */
/* Two products, both non-consumable:                                           */
/*   trial14  price 0, named "14-day Trial" per App Review 3.1.1. iOS only —    */
/*            Play has no price-0 IAP, so Android keeps a local stamp.          */
/*   unlock   €9.99, FAMILY SHARING ON in App Store Connect (6 people, free,    */
/*            native). Play Family Library does NOT share IAPs, so on Android   */
/*            this restores per Google account only — see CLAUDE.md § Native.   */
/* -------------------------------------------------------------------------- */
//
// Port of `src/licensing/store.ts`.
//
// D4: **ALCore must never import StoreKit.** The whole point of the seam is that
// `swift test` runs the entitlement machine on a Mac with no store, no network
// and no signing. The real adapter is `ALPlatform/StoreKitPurchaseStore` (W17).

public struct StoreSnapshot: Equatable, Sendable {
    /// The unlock is owned by this Apple Account / Google account.
    public var paid: Bool

    /**
     * Authoritative trial start, when the platform can prove one — on iOS the
     * price-0 IAP's StoreKit `purchaseDate`. Overrides the local stamp when it is
     * EARLIER, so reinstalling can never buy a fresh fortnight.
     */
    public var trialStartedAt: Int64?

    /**
     * False when the store could not be reached at all. Callers MUST fail open on
     * this: never downgrade a family's entitlement because a network call failed.
     *
     * `reachable` means *"the store answered out loud"*. A negative may only
     * downgrade when it did — see money.md §5.1, the sharpest hazard in the port:
     * `Transaction.currentEntitlements` succeeds offline against an empty local
     * cache, so a naive adapter reports `paid: false, reachable: true` and locks
     * out a paying family that reinstalled on a plane.
     */
    public var reachable: Bool

    public init(paid: Bool, trialStartedAt: Int64?, reachable: Bool) {
        self.paid = paid
        self.trialStartedAt = trialStartedAt
        self.reachable = reachable
    }

    /// `UNREACHABLE`. The fail-open answer, and the only one the shipping store
    /// gives until StoreKit is wired.
    public static let unreachable = StoreSnapshot(
        paid: false, trialStartedAt: nil, reachable: false)
}

/**
 * The vendor-free IAP protocol.
 *
 * **[DEVIATION — and it is the good kind] None of these throw.** In TypeScript
 * every one of the six is a `Promise` and every call site wraps it in
 * `.catch(() => <fail-open value>)`. That is invariant 11 enforced by six
 * separate hand-written catch clauses; miss one and a paying child sees a
 * paywall because StoreKit blinked. Declaring the protocol non-throwing means
 * the *adapter* is obliged to map every error to the fail-open value, and no
 * caller can forget. The mapping is fixed and normative:
 *
 * | failure                                                  | must return    |
 * |----------------------------------------------------------|----------------|
 * | `refresh()` — any error, any timeout                      | `.unreachable` |
 * | `beginTrial()` — any error, cancel                        | `nil`          |
 * | `purchase()` — any error, cancel, `.pending`, `.unverified`| `false`        |
 * | `restore()` — any error                                   | `false`        |
 * | `priceLabel()` — any error                                | `nil`          |
 */
public protocol PurchaseStore: Sendable {
    /// Whether a purchase path exists here at all. (`false` on the web build,
    /// which no longer exists natively — kept because the screens read it.)
    var available: Bool { get }

    /// Current ownership. Called at boot and on every app resume.
    func refresh() async -> StoreSnapshot

    /**
     * Start the free trial and return the authoritative start date if the
     * platform can mint one. iOS: purchase the price-0 "14-day Trial"
     * non-consumable — that IS the mechanism guideline 3.1.1 prescribes, and its
     * `purchaseDate` is what survives a reinstall. Android: nil, the model's
     * local stamp is all there is.
     */
    func beginTrial() async -> Int64?

    /// Buy the unlock. `true` once the store confirms.
    func purchase() async -> Bool

    /// Re-apply prior purchases. Apple requires this control to exist.
    func restore() async -> Bool

    /// Localised price from the store ("9,99 €"), or nil when unreachable.
    func priceLabel() async -> String?
}

/**
 * Native build, StoreKit not wired yet — **the release default**.
 *
 * A literal port of today's `nativeStore`. `refresh` reports `.unreachable` and
 * the app fails open: a native build today behaves exactly like the web one plus
 * a running trial clock. Nothing is charged, nothing is locked.
 *
 * Note what this is NOT: it fails open **via the trial clock**, not via a fake
 * purchase. The web's `webStore` returned `paid: true` unconditionally *and the
 * provider persisted that `true` to storage* — on the web a deliberate product
 * decision (no payment rail, no gating), in a native build a silent unlock for
 * everyone. That behaviour deliberately does not survive as a default here;
 * see `PreviewPurchaseStore`, which is `#if DEBUG` for exactly this reason
 * (money.md R7).
 */
public struct StubPurchaseStore: PurchaseStore {
    public init() {}
    public var available: Bool { true }
    public func refresh() async -> StoreSnapshot { .unreachable }
    public func beginTrial() async -> Int64? { nil }
    public func purchase() async -> Bool { false }
    public func restore() async -> Bool { false }
    public func priceLabel() async -> String? { nil }
}

#if DEBUG
    /**
     * `#Preview` and tests only. **Never a release default** — an
     * `available: false, paid: true` store reaching a release build would
     * silently unlock everyone (money.md R7), which is precisely what the web's
     * `webStore` did.
     *
     * The `#if DEBUG` fence is load-bearing. Do not remove it.
     */
    public struct PreviewPurchaseStore: PurchaseStore {
        public let available: Bool
        public let paid: Bool
        public let trialStartedAt: Int64?
        public let reachable: Bool
        public let price: String?

        public init(
            available: Bool = true,
            paid: Bool = false,
            trialStartedAt: Int64? = nil,
            reachable: Bool = true,
            price: String? = "9,99 €"
        ) {
            self.available = available
            self.paid = paid
            self.trialStartedAt = trialStartedAt
            self.reachable = reachable
            self.price = price
        }

        public func refresh() async -> StoreSnapshot {
            StoreSnapshot(paid: paid, trialStartedAt: trialStartedAt, reachable: reachable)
        }
        public func beginTrial() async -> Int64? { trialStartedAt }
        public func purchase() async -> Bool { paid }
        public func restore() async -> Bool { paid }
        public func priceLabel() async -> String? { price }
    }
#endif
