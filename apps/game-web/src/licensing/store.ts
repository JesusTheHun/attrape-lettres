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

export interface StoreSnapshot {
  /** The unlock is owned by this Apple Account / Google account. */
  paid: boolean;
  /**
   * Authoritative trial start, when the platform can prove one — on iOS the
   * price-0 IAP's StoreKit `purchaseDate`. Overrides the local stamp when it is
   * EARLIER, so reinstalling can never buy a fresh fortnight.
   */
  trialStartedAt: number | null;
  /**
   * False when the store could not be reached at all. Callers MUST fail open on
   * this: never downgrade a family's entitlement because a network call failed.
   */
  reachable: boolean;
}

export const UNREACHABLE: StoreSnapshot = {
  paid: false,
  trialStartedAt: null,
  reachable: false,
};

export interface PurchaseStore {
  /** Current ownership. Called at boot and on every app resume. */
  refresh(): Promise<StoreSnapshot>;
  /**
   * Start the free trial and return the authoritative start date if the
   * platform can mint one. iOS: purchase the price-0 "14-day Trial"
   * non-consumable — that IS the mechanism guideline 3.1.1 prescribes, and its
   * `purchaseDate` is what survives a reinstall. Android: null, the provider's
   * local stamp is all there is.
   */
  beginTrial(): Promise<number | null>;
  /** Buy the unlock. Resolves true once the store confirms. */
  purchase(): Promise<boolean>;
  /** Re-apply prior purchases. Apple requires this control to exist. */
  restore(): Promise<boolean>;
  /** Localised price from the store ("9,99 €"), or null when unreachable. */
  priceLabel(): Promise<string | null>;
  /** Whether a purchase path exists here at all (false on the web build). */
  available: boolean;
}

/**
 * Web / PWA build: no store, therefore no paywall.
 *
 * A deliberate product decision, not an oversight. The existing PWA is
 * self-distributed — there is no payment rail in it, and gating it would break
 * the people already playing while offering them no way to pay. The trial and
 * the €9.99 unlock exist in the native builds, where a store does. Anyone who
 * wants the app on a phone gets it from the App Store or Play.
 */
const webStore: PurchaseStore = {
  available: false,
  async refresh() {
    return { paid: true, trialStartedAt: null, reachable: true };
  },
  async beginTrial() {
    return null;
  },
  async purchase() {
    return false;
  },
  async restore() {
    return false;
  },
  async priceLabel() {
    return null;
  },
};

let override: PurchaseStore | null = null;

/**
 * Always `webStore` — this file has one implementation and no platform branch.
 *
 * It used to have two. An earlier native shell wrapped this same web bundle, so
 * a `nativeStore` stub lived here waiting to be wired to StoreKit 2 and Play
 * Billing. The phones are real native apps now, and the finished iOS
 * implementation is `StoreKitPurchaseStore` in ALPlatform — against the same
 * four calls this interface names, because the interface was ported too. A TS
 * stub describing a purchase path that no TS build can reach would just be a
 * second, staler description of it.
 *
 * The interface itself stays. `UNREACHABLE` and the fail-open rule in
 * `applySnapshot` are what invariant 11 is made of, and the tests that hold
 * them down substitute a store through `__setPurchaseStore`.
 */
export function purchaseStore(): PurchaseStore {
  return override ?? webStore;
}

/** Tests only — swap in a fake store. */
export function __setPurchaseStore(s: PurchaseStore | null): void {
  override = s;
}
