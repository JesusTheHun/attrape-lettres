import type { PersistedProfile, Roster } from "./types";

/**
 * Persistence seam — the ONLY place in the app that talks to device storage.
 *
 * Web browsers AND Capacitor iOS/Android WebViews all support localStorage, so
 * it backs every target (phone, tablet, desktop) and works fully offline. For a
 * hardened native build where the OS may evict WebView storage, swap this file's
 * body for `@capacitor/preferences` — keep the signatures and the "no network,
 * offline-first" contract. Nothing else imports storage APIs.
 *
 * Schema history (useProfile owns the migration logic; storage just fetches the
 * raw blobs so defaults/domain shapes live in one place):
 *   v1  single mascot: { chosen, config, balance, ledger, owned }
 *   v2  per-species progress for ONE child (PersistedProfile)
 *   v3  a Roster of named children (siblings share the device)  ← current
 */

const KEY = "attrape-lettres:roster:v3";
const V2_KEY = "attrape-lettres:profile:v2";
const V1_KEY = "attrape-lettres:profile:v1";

export function loadRoster(): Roster | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Roster) : null;
  } catch {
    return null;
  }
}

export function saveRoster(roster: Roster): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(roster));
  } catch {
    /* private mode / quota — profiles just won't persist this session. */
  }
}

/** The v2 single-child profile, if this device still has one to migrate. */
export function loadV2Profile(): PersistedProfile | null {
  try {
    const raw = localStorage.getItem(V2_KEY);
    return raw ? (JSON.parse(raw) as PersistedProfile) : null;
  } catch {
    return null;
  }
}

/** The raw v1 single-mascot blob, if present, for migration. */
export function loadV1Profile(): unknown | null {
  try {
    const raw = localStorage.getItem(V1_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
