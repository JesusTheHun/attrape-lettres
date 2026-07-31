package fr.dappit.attrapelettres.core.licensing

/* -------------------------------------------------------------------------- */
/* The in-app-purchase seam.                                                    */
/*                                                                             */
/* Vendor-free by design: the only parties in the payment path are Apple and    */
/* Google. No subscription-management SDK, no analytics SDK, nothing that would */
/* put a third party between a child and the unlock — which is also what keeps  */
/* us inside Kids Category guideline 1.3 ("may not send personally              */
/* identifiable information or device information to third parties").           */
/*                                                                             */
/* Two products, both non-consumable:                                           */
/*   trial14  price 0, named "14-day Trial" per App Review 3.1.1. iOS ONLY —    */
/*            Google Play has no price-0 in-app product, so Android keeps a     */
/*            local stamp instead (LicenseState.trialStartedAt, A5).            */
/*   unlock   €9.99. On iOS, Family Sharing is switched on for it in App Store  */
/*            Connect: six people, free, native. Google Play Family Library     */
/*            explicitly does NOT share in-app purchases, ever, so on Android   */
/*            `restore()` re-applies the purchase for ONE Google account and    */
/*            nothing more.                                                     */
/* -------------------------------------------------------------------------- */
//
// Port of `apps/game-web/src/licensing/store.ts`, cross-checked against
// `apps/game-ios/Sources/ALCore/Licensing/PurchaseStore.swift`.
//
// A1: `:core` is a plain JVM module, so the billing library is not merely
// unwired here — it does not resolve. The real adapter is a `:platform` class
// (W17) and is the only file in the app allowed to name a vendor. Nothing in
// `:core` may, and `LicensingSourceScanTest` turns that from a review note into
// a build failure.
//
// COPY WARNING, and it belongs to this file because this is where the asymmetry
// is: any French copy promising the whole household — the sort of phrase the
// iOS app can honestly write because Family Sharing covers six people — must
// never appear in this app. Android cannot keep that promise. The web build,
// which has no store at all, says « sur vos appareils »; the Android build has
// no better claim to make. The scan test asserts the forbidden phrase is absent
// from `:core`, and the same rule applies to `:ui` when the screens land.
//
// The fix, if household-wide entitlement is ever wanted, is entitlement on the
// sync backend keyed by `familyId` — cheap now that the household record
// exists, and the Android Family-Library gap is the strongest reason to want
// it. It does NOT belong in the merged profile blob: a licence accumulates
// nothing, so it is not a `Counter` and must never enter sync/Merge.kt
// (invariant 9).

/**
 * One answer from the store.
 *
 * @property paid the unlock is owned by this Apple Account / Google account.
 * @property trialStartedAt authoritative trial start, when the platform can
 *   prove one — on iOS the price-0 product's signed `purchaseDate`. Overrides
 *   the local stamp when it is EARLIER, so reinstalling can never buy a fresh
 *   fortnight. **Always null on Android**: there is no price-0 product to date
 *   and no receipt to read, so the local stamp is all there is.
 * @property reachable false when the store could not be reached at all. Callers
 *   MUST fail open on this: never downgrade a family's entitlement because a
 *   network call failed.
 *
 * `reachable` means *"the store answered out loud"*, and getting that wrong in
 * the adapter is the sharpest hazard in this whole subsystem. A billing library
 * that resolves a purchase query from an empty local cache while offline looks
 * like a successful call: a naive adapter then reports "not paid, reachable"
 * and locks out a paying family that reinstalled on a plane. A query that did
 * not reach Google's servers is `reachable = false`, whatever its result code
 * says.
 */
data class StoreSnapshot(
    val paid: Boolean,
    val trialStartedAt: Long?,
    val reachable: Boolean,
) {
    companion object {
        /**
         * `UNREACHABLE`. The fail-open answer, and the only one
         * [StubPurchaseStore] ever gives.
         */
        val UNREACHABLE = StoreSnapshot(paid = false, trialStartedAt = null, reachable = false)
    }
}

/**
 * The vendor-free purchase seam. `:platform` implements it; `:core` and `:ui`
 * only ever see this interface, which is what lets `./gradlew :core:test` run
 * the entire entitlement machine on a laptop with no store, no network and no
 * signing.
 *
 * **None of these throw, and that is the good kind of deviation.** In
 * TypeScript every one is a `Promise` and every call site wraps it in
 * `.catch(() => <fail-open value>)`. That is invariant 11 enforced by six
 * separate hand-written catch clauses; miss one and a paying child sees a
 * paywall because the store blinked. Declaring the interface non-throwing means
 * the *adapter* is obliged to map every failure to the fail-open value, and no
 * caller can forget. The mapping is fixed and normative:
 *
 * | failure                                                     | must return   |
 * |-------------------------------------------------------------|---------------|
 * | `refresh()` — any error, any timeout, any disconnect         | `UNREACHABLE` |
 * | `beginTrial()` — any error, user cancel                      | `null`        |
 * | `purchase()` — any error, cancel, pending, unverified        | `false`       |
 * | `restore()` — any error                                      | `false`       |
 * | `priceLabel()` — any error                                   | `null`        |
 *
 * `suspend`, unlike every other seam in `:core`. Storage is synchronous by
 * signature because invariant 1 has nowhere to await on the tap path (A3); the
 * store is explicitly the thing that never runs on the tap path, so its
 * asynchrony belongs in the type — the same call this module makes for
 * `SyncTransport` (A7).
 */
interface PurchaseStore {
    /** Whether a purchase path exists on this build at all. The screens read it. */
    val available: Boolean

    /** Current ownership. Called at boot and on every resume. */
    suspend fun refresh(): StoreSnapshot

    /**
     * Start the free trial and return the authoritative start date if the
     * platform can mint one.
     *
     * iOS: purchase the price-0 "14-day Trial" non-consumable — that IS the
     * mechanism guideline 3.1.1 prescribes, and its `purchaseDate` is what
     * survives a reinstall.
     *
     * Android: **always null**, and expected to stay null forever. There is no
     * price-0 in-app product on Google Play, so the model's local stamp is all
     * there is. The method exists anyway so the two ports keep one shape and
     * the model keeps one code path.
     */
    suspend fun beginTrial(): Long?

    /** Buy the unlock. True once the store confirms. */
    suspend fun purchase(): Boolean

    /**
     * Re-apply prior purchases. Apple requires this control to exist; on
     * Android it is what a parent taps after switching phone, and it restores
     * for their Google account only.
     */
    suspend fun restore(): Boolean

    /** Localised price from the store ("9,99 €"), or null when unreachable. */
    suspend fun priceLabel(): String?
}

/**
 * Billing not wired yet — **the release default**.
 *
 * `refresh` reports `UNREACHABLE` and the app fails open: a build with this
 * store behaves exactly like the web PWA plus a running trial clock. Nothing is
 * charged, nothing is locked.
 *
 * Note what this is NOT: it fails open **via the trial clock**, not via a
 * pretend purchase. The web's `webStore` reported ownership unconditionally
 * *and the provider persisted that to storage* — on the web a deliberate
 * product decision (no payment rail, so no gating), in a store build a silent
 * unlock for everyone. That behaviour deliberately does not survive as a
 * default here.
 *
 * The iOS port keeps its ownership-granting double behind `#if DEBUG` for
 * exactly that reason. Kotlin has no such fence, so this port does something
 * strictly stronger: the granting doubles live in the TEST source set only
 * (`ScriptedPurchaseStore` in EntitlementModelTest.kt), which is not compiled
 * into the APK at all. `LicensingSourceScanTest` asserts no store in this
 * package's main sources reports ownership.
 */
class StubPurchaseStore : PurchaseStore {
    override val available: Boolean = true
    override suspend fun refresh(): StoreSnapshot = StoreSnapshot.UNREACHABLE
    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = false
    override suspend fun restore(): Boolean = false
    override suspend fun priceLabel(): String? = null
}
