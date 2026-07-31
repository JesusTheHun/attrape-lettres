/**
 * The one key/value primitive under storage.ts and device.ts.
 *
 * It is localStorage, wrapped. Every read in this app happens on a synchronous
 * path that cannot await — `useState(initialRoster)` at mount, and
 * `award`/`spend` inside a pointerdown handler (invariant 1: feedback fires
 * before React commits) — and localStorage is synchronous, so there is nothing
 * to hydrate and no boot gate.
 *
 * Why it is still a module and not a bare `localStorage` call at each site:
 * every access is inside try/catch. Private mode and a full quota both throw,
 * and none of the callers has anywhere useful to report that — a failed read is
 * an absent value, a failed write is a value this session still holds in the
 * React state. Total, never a throw.
 *
 * NB: the native apps do NOT go through here. They are real native apps with
 * their own storage (iOS: `UserDefaultsKVStore` in ALPlatform), which is why
 * this file has no platform branch. It used to: an earlier native shell wrapped
 * this same web bundle and needed an async device store behind a memory cache.
 * See DECISIONS.md § "Capacitor removed".
 */

export function getItem(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function setItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode / quota — see storage.ts callers, all tolerate this */
  }
}

export function removeItem(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}
