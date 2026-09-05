/* -------------------------------------------------------------------------- */
/* What the family is allowed to play right now. PURE — no storage, no store,   */
/* no clock except the `now` passed in.                                         */
/*                                                                             */
/* The model Apple wrote a rule for (App Review 3.1.1): a non-subscription app  */
/* may run a free time-based trial before a full unlock, via a price-0          */
/* non-consumable named "14-day Trial", provided the duration, what stops       */
/* working, and the eventual charge are all disclosed BEFORE the trial starts.  */
/* Onboarding.tsx is that disclosure; this file is the clock.                   */
/* -------------------------------------------------------------------------- */

export const TRIAL_DAYS = 7;
export const DAY_MS = 24 * 60 * 60 * 1000;
export const TRIAL_MS = TRIAL_DAYS * DAY_MS;

/** Price in euros, TTC. Displayed copy lives in the screens; this is the truth. */
export const UNLOCK_PRICE_EUR = 11.99;

export const PRODUCT_TRIAL = "fr.dappit.attrapelettres.trial7";
export const PRODUCT_UNLOCK = "fr.dappit.attrapelettres.unlock";

/**
 * How long a "paid" verdict survives without the store confirming it again.
 *
 * This is a fail-OPEN window and it is deliberate. A child on a plane, or in a
 * kitchen with no wifi, must never be told the game they own is locked. We
 * would rather give away a fortnight of play to someone who genuinely refunded
 * than show one paying six-year-old a paywall because StoreKit timed out.
 */
export const OFFLINE_GRACE_MS = 14 * DAY_MS;

export interface LicenseState {
  /** The store says the unlock is owned by this Apple Account / Google account. */
  paid: boolean;
  /** When the store last CONFIRMED `paid`. Drives the offline grace window. */
  verifiedAt: number | null;
  /**
   * When the trial began.
   *
   * iOS: the price-0 IAP's StoreKit `purchaseDate` — signed by Apple, survives
   * a reinstall, and follows the Apple Account across devices. Never our own
   * timestamp, which a reinstall would reset.
   *
   * Android: Play has no price-0 IAP, so this is a local stamp persisted
   * through Auto Backup. Clearing app data resets it. Accepted: parents of
   * six-year-olds do not farm fortnightly trials, and the alternative is an
   * account system this app deliberately does not have.
   */
  trialStartedAt: number | null;
  /**
   * Highest `now` ever observed. Winding the device clock back is the one
   * trial-extension trick that costs nothing to try, and three lines to defeat.
   */
  clockHighWater: number;
}

export const BLANK_LICENSE: LicenseState = {
  paid: false,
  verifiedAt: null,
  trialStartedAt: null,
  clockHighWater: 0,
};

export type Entitlement =
  /** Nothing decided yet — the store has not answered. Never render a paywall on this. */
  | { status: "unknown" }
  | { status: "trial"; daysLeft: number; endsAt: number }
  | { status: "expired" }
  | { status: "paid" };

/** Monotonic clock: a device clock that moved backwards is ignored. */
export function effectiveNow(state: LicenseState, now: number): number {
  return Math.max(now, state.clockHighWater);
}

/**
 * Fold the license into what the app should do.
 *
 * Order matters: paid beats everything, and an expired trial only bites once we
 * are sure the unlock is NOT owned. A merely unverified paid flag stays paid
 * until the grace window runs out.
 */
export function entitlementOf(state: LicenseState, now: number): Entitlement {
  const t = effectiveNow(state, now);

  if (state.paid) {
    const stale =
      state.verifiedAt !== null && t - state.verifiedAt > OFFLINE_GRACE_MS;
    if (!stale) return { status: "paid" };
    // Grace exhausted: fall through and let the trial clock decide, rather than
    // hard-locking. Worst case the family sees the paywall and taps "Restaurer".
  }

  // Trial not started yet (pre-onboarding): treat as a full trial so nothing is
  // ever gated before the parent has even seen the terms.
  if (state.trialStartedAt === null) {
    return { status: "trial", daysLeft: TRIAL_DAYS, endsAt: t + TRIAL_MS };
  }

  const endsAt = state.trialStartedAt + TRIAL_MS;
  const daysLeft = Math.ceil((endsAt - t) / DAY_MS);
  return daysLeft > 0 ? { status: "trial", daysLeft, endsAt } : { status: "expired" };
}

/** Can a new exercise round be started? The only gate the child ever feels. */
export function canPlay(e: Entitlement): boolean {
  return e.status !== "expired";
}

/** Advance the high-water mark. Call whenever the license is touched. */
export function withClock(state: LicenseState, now: number): LicenseState {
  return now > state.clockHighWater ? { ...state, clockHighWater: now } : state;
}

/** French, parent-facing, for the trial countdown chip. */
export function trialNotice(e: Entitlement): string | null {
  if (e.status !== "trial") return null;
  if (e.daysLeft === 1) return "Dernier jour d'essai";
  return `Essai gratuit — ${e.daysLeft} jours restants`;
}
