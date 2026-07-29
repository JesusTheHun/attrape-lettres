import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { App as CapApp } from "@capacitor/app";
import {
  entitlementOf,
  withClock,
  type Entitlement,
  type LicenseState,
} from "./entitlement";
import { loadLicense, loadOnboarded, saveLicense, saveOnboarded } from "./persist";
import { purchaseStore, type StoreSnapshot } from "./store";

/* -------------------------------------------------------------------------- */
/* Who may play, and the two buttons that change it. Wrap the app in            */
/* <EntitlementProvider>; read it with useEntitlement().                        */
/* -------------------------------------------------------------------------- */

/**
 * Fold a store answer into the saved license.
 *
 * Unreachable ⇒ change nothing but the clock. This is the fail-open rule and it
 * is the single most important line in the file: a network blip must never turn
 * a paying family's game off.
 */
export function applySnapshot(
  s: LicenseState,
  snap: StoreSnapshot,
  now: number
): LicenseState {
  if (!snap.reachable) return withClock(s, now);
  const trialStartedAt =
    snap.trialStartedAt !== null
      ? // Earliest wins: a reinstall cannot buy a fresh fortnight.
        Math.min(snap.trialStartedAt, s.trialStartedAt ?? snap.trialStartedAt)
      : s.trialStartedAt;
  return withClock({ ...s, paid: snap.paid, verifiedAt: now, trialStartedAt }, now);
}

export interface EntitlementAPI {
  entitlement: Entitlement;
  /** Has a parent seen and accepted the trial terms? */
  onboarded: boolean;
  /** Localised store price, e.g. "9,99 €" — null until the store answers. */
  priceLabel: string | null;
  /** Is there a purchase path on this build at all? (false on the web PWA) */
  storeAvailable: boolean;
  /** Accept the terms and start the clock. Called from Onboarding only. */
  beginTrial: () => void;
  purchase: () => Promise<boolean>;
  restore: () => Promise<boolean>;
}

const Ctx = createContext<EntitlementAPI | null>(null);

export function EntitlementProvider({ children }: { children: ReactNode }) {
  const [license, setLicense] = useState<LicenseState>(loadLicense);
  const [onboarded, setOnboarded] = useState<boolean>(loadOnboarded);
  const [priceLabel, setPriceLabel] = useState<string | null>(null);
  // The trial can lapse mid-session. We recheck on resume rather than ticking a
  // timer: a child mid-exercise when the fortnight runs out gets to finish.
  const [checkedAt, setCheckedAt] = useState(() => Date.now());

  const ref = useRef(license);
  ref.current = license;

  const store = useMemo(() => purchaseStore(), []);

  const commit = useCallback((next: LicenseState) => {
    ref.current = next;
    setLicense(next);
    saveLicense(next);
  }, []);

  const refresh = useCallback(async () => {
    const now = Date.now();
    setCheckedAt(now);
    try {
      const snap = await store.refresh();
      commit(applySnapshot(ref.current, snap, now));
    } catch {
      // Same rule as an unreachable store: keep what we had.
      commit(withClock(ref.current, now));
    }
  }, [commit, store]);

  useEffect(() => {
    void refresh();
    void store.priceLabel().then(setPriceLabel).catch(() => setPriceLabel(null));
  }, [refresh, store]);

  // Re-check when the app comes back to the foreground: that is when a purchase
  // made in the store UI, a Family Sharing grant, or a refund shows up.
  useEffect(() => {
    let cancel: (() => void) | undefined;
    void CapApp.addListener("appStateChange", ({ isActive }) => {
      if (isActive) void refresh();
    })
      .then((h) => {
        cancel = () => void h.remove();
      })
      .catch(() => {
        /* web build: no native app-state events, the mount refresh is enough */
      });
    return () => cancel?.();
  }, [refresh]);

  const beginTrial = useCallback(() => {
    const now = Date.now();
    saveOnboarded();
    setOnboarded(true);
    // Local stamp first so the clock starts even with no network, then let the
    // store hand back an authoritative (earlier) date if it can.
    commit(withClock({ ...ref.current, trialStartedAt: ref.current.trialStartedAt ?? now }, now));
    void store
      .beginTrial()
      .then((authoritative) => {
        if (authoritative === null) return;
        commit(
          withClock(
            {
              ...ref.current,
              trialStartedAt: Math.min(authoritative, ref.current.trialStartedAt ?? authoritative),
            },
            Date.now()
          )
        );
      })
      .catch(() => {
        /* local stamp stands */
      });
  }, [commit, store]);

  const purchase = useCallback(async () => {
    const ok = await store.purchase().catch(() => false);
    if (ok) await refresh();
    return ok;
  }, [refresh, store]);

  const restore = useCallback(async () => {
    const ok = await store.restore().catch(() => false);
    await refresh();
    return ok;
  }, [refresh, store]);

  const value = useMemo<EntitlementAPI>(
    () => ({
      entitlement: entitlementOf(license, checkedAt),
      onboarded,
      priceLabel,
      storeAvailable: store.available,
      beginTrial,
      purchase,
      restore,
    }),
    [license, checkedAt, onboarded, priceLabel, store, beginTrial, purchase, restore]
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useEntitlement(): EntitlementAPI {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useEntitlement must be used within <EntitlementProvider>");
  return ctx;
}
