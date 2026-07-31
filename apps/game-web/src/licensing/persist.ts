import { getItem, setItem } from "../kv";
import { BLANK_LICENSE, type LicenseState } from "./entitlement";

/**
 * License persistence. Separate from storage.ts on purpose: that file owns
 * children's profiles and its migration contract; this owns one small blob
 * about the household's purchase. Losing a profile is a tragedy, losing this is
 * one `restore()` tap.
 */

const LICENSE_KEY = "attrape-lettres:license:v1";
const ONBOARDED_KEY = "attrape-lettres:onboarded:v1";

export function loadLicense(): LicenseState {
  try {
    const raw = getItem(LICENSE_KEY);
    if (!raw) return BLANK_LICENSE;
    const parsed = JSON.parse(raw) as Partial<LicenseState>;
    return {
      paid: parsed.paid ?? false,
      verifiedAt: parsed.verifiedAt ?? null,
      trialStartedAt: parsed.trialStartedAt ?? null,
      clockHighWater: parsed.clockHighWater ?? 0,
    };
  } catch {
    return BLANK_LICENSE;
  }
}

export function saveLicense(s: LicenseState): void {
  try {
    setItem(LICENSE_KEY, JSON.stringify(s));
  } catch {
    /* the store is re-queried every launch; a lost write costs one refresh */
  }
}

/** Has a parent seen the trial terms? Gates Onboarding, per App Review 3.1.1. */
export function loadOnboarded(): boolean {
  return getItem(ONBOARDED_KEY) === "1";
}

export function saveOnboarded(): void {
  setItem(ONBOARDED_KEY, "1");
}
