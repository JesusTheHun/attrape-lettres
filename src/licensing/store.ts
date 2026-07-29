import { Capacitor } from "@capacitor/core";

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

/**
 * Native build.
 *
 * The plugin is not wired yet, so `refresh` reports UNREACHABLE and the app
 * fails open — a native build today behaves exactly like the web one plus a
 * running trial clock. Nothing is charged, nothing is locked. Wiring it means
 * implementing four calls against StoreKit 2 / Play Billing:
 *
 *   refresh()   iOS: iterate `Transaction.currentEntitlements`; the JWS is
 *               signed by Apple and verifies ON DEVICE — no server needed.
 *               paid = PRODUCT_UNLOCK present (this also covers a Family
 *               Sharing grant, which arrives as an ordinary entitlement).
 *               trialStartedAt = PRODUCT_TRIAL's `purchaseDate`.
 *               Android: `queryPurchasesAsync(INAPP)`, verify the signature
 *               against the Play Console RSA key, paid = unlock purchased and
 *               acknowledged. Android reports trialStartedAt: null.
 *   purchase()  StoreKit `product.purchase()` / Play `launchBillingFlow`, then
 *               finish/acknowledge — an unacknowledged Play purchase is
 *               auto-refunded after three days.
 *   restore()   iOS `AppStore.sync()`; Android just re-queries.
 *   beginTrial  iOS only, and it is a purchase() of the price-0 product; on
 *               Android the provider's local stamp is the whole mechanism.
 *
 * Until then this stub is the honest state of the world, and every screen
 * downstream is already written against the finished interface.
 */
const nativeStore: PurchaseStore = {
  available: true,
  async refresh() {
    return UNREACHABLE;
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

export function purchaseStore(): PurchaseStore {
  if (override) return override;
  try {
    return Capacitor.isNativePlatform() ? nativeStore : webStore;
  } catch {
    return webStore;
  }
}

/** Tests only — swap in a fake store. */
export function __setPurchaseStore(s: PurchaseStore | null): void {
  override = s;
}
