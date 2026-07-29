import { describe, it, expect } from "vitest";
import {
  BLANK_LICENSE,
  DAY_MS,
  OFFLINE_GRACE_MS,
  TRIAL_DAYS,
  TRIAL_MS,
  canPlay,
  effectiveNow,
  entitlementOf,
  trialNotice,
  withClock,
  type LicenseState,
} from "./entitlement";
import { applySnapshot } from "./useEntitlement";
import { UNREACHABLE } from "./store";

const T0 = 1_700_000_000_000; // a fixed "now"; nothing here reads a real clock

function license(over: Partial<LicenseState> = {}): LicenseState {
  return { ...BLANK_LICENSE, ...over };
}

describe("trial clock", () => {
  it("gives a full fortnight before the parent has even accepted", () => {
    const e = entitlementOf(license(), T0);
    expect(e).toMatchObject({ status: "trial", daysLeft: TRIAL_DAYS });
  });

  it("counts down from the start date", () => {
    const s = license({ trialStartedAt: T0 });
    expect(entitlementOf(s, T0)).toMatchObject({ status: "trial", daysLeft: 14 });
    expect(entitlementOf(s, T0 + 3 * DAY_MS)).toMatchObject({ daysLeft: 11 });
    expect(entitlementOf(s, T0 + 13.5 * DAY_MS)).toMatchObject({ daysLeft: 1 });
  });

  it("expires the instant the fortnight is up, not a moment before", () => {
    const s = license({ trialStartedAt: T0 });
    expect(entitlementOf(s, T0 + TRIAL_MS - 1).status).toBe("trial");
    expect(entitlementOf(s, T0 + TRIAL_MS).status).toBe("expired");
  });

  it("blocks new rounds only once expired", () => {
    expect(canPlay(entitlementOf(license({ trialStartedAt: T0 }), T0))).toBe(true);
    expect(canPlay(entitlementOf(license({ trialStartedAt: T0 }), T0 + TRIAL_MS))).toBe(false);
    expect(canPlay(entitlementOf(license({ paid: true }), T0))).toBe(true);
  });

  it("ignores a device clock wound backwards", () => {
    // Day 10 of the trial, then someone sets the date back a year.
    const s = withClock(license({ trialStartedAt: T0 }), T0 + 10 * DAY_MS);
    expect(effectiveNow(s, T0 - 365 * DAY_MS)).toBe(T0 + 10 * DAY_MS);
    expect(entitlementOf(s, T0 - 365 * DAY_MS)).toMatchObject({ daysLeft: 4 });
  });

  it("speaks French to the parent, and says the last day plainly", () => {
    expect(trialNotice(entitlementOf(license({ trialStartedAt: T0 }), T0 + 3 * DAY_MS))).toBe(
      "Essai gratuit — 11 jours restants"
    );
    expect(trialNotice(entitlementOf(license({ trialStartedAt: T0 }), T0 + 13.5 * DAY_MS))).toBe(
      "Dernier jour d'essai"
    );
    expect(trialNotice({ status: "paid" })).toBeNull();
  });
});

describe("paid — and failing open", () => {
  it("beats an exhausted trial", () => {
    const s = license({ paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * DAY_MS });
    expect(entitlementOf(s, T0).status).toBe("paid");
  });

  it("stays paid through a long offline stretch", () => {
    const s = license({ paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * DAY_MS });
    // A fortnight in a cottage with no signal is not a reason to lock the app.
    expect(entitlementOf(s, T0 + OFFLINE_GRACE_MS - DAY_MS).status).toBe("paid");
  });

  it("falls back to the trial clock only once the grace window is spent", () => {
    const s = license({ paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * DAY_MS });
    expect(entitlementOf(s, T0 + OFFLINE_GRACE_MS + DAY_MS).status).toBe("expired");
  });

  it("never expires a paid family that has simply never been verified", () => {
    // verifiedAt null = we believe the flag and have nothing to age it against.
    const s = license({ paid: true, verifiedAt: null, trialStartedAt: T0 - 100 * DAY_MS });
    expect(entitlementOf(s, T0 + 10 * 365 * DAY_MS).status).toBe("paid");
  });
});

describe("applySnapshot — what the store is allowed to change", () => {
  it("refuses to downgrade anything when the store is unreachable", () => {
    const s = license({ paid: true, verifiedAt: T0, trialStartedAt: T0 });
    const after = applySnapshot(s, UNREACHABLE, T0 + DAY_MS);
    expect(after.paid).toBe(true);
    expect(after.verifiedAt).toBe(T0); // not re-stamped: it was never confirmed
    expect(entitlementOf(after, T0 + DAY_MS).status).toBe("paid");
  });

  it("grants paid when the store confirms ownership", () => {
    const after = applySnapshot(
      license({ trialStartedAt: T0 - 100 * DAY_MS }),
      { paid: true, trialStartedAt: null, reachable: true },
      T0
    );
    expect(entitlementOf(after, T0).status).toBe("paid");
  });

  it("honours a refund once the store says so out loud", () => {
    const s = license({ paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * DAY_MS });
    const after = applySnapshot(s, { paid: false, trialStartedAt: null, reachable: true }, T0);
    expect(entitlementOf(after, T0).status).toBe("expired");
  });

  it("takes the EARLIEST trial start, so reinstalling buys nothing", () => {
    // Local stamp says today; StoreKit's signed receipt says twelve days ago.
    const s = license({ trialStartedAt: T0 });
    const after = applySnapshot(
      s,
      { paid: false, trialStartedAt: T0 - 12 * DAY_MS, reachable: true },
      T0
    );
    expect(after.trialStartedAt).toBe(T0 - 12 * DAY_MS);
    expect(entitlementOf(after, T0)).toMatchObject({ status: "trial", daysLeft: 2 });
  });

  it("keeps the local stamp when the platform cannot prove one (Android)", () => {
    const s = license({ trialStartedAt: T0 - 5 * DAY_MS });
    const after = applySnapshot(s, { paid: false, trialStartedAt: null, reachable: true }, T0);
    expect(after.trialStartedAt).toBe(T0 - 5 * DAY_MS);
  });

  it("advances the clock high-water mark on every check", () => {
    const after = applySnapshot(license(), UNREACHABLE, T0);
    expect(after.clockHighWater).toBe(T0);
  });
});
