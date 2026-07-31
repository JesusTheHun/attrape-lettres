import type { Roster } from "./types";
import { getItem, setItem } from "./kv";

/**
 * Persistence seam — the ONLY place in the app that reads or writes profiles.
 *
 * Every accessor is synchronous and total: a caller gets a value or a safe
 * default, never a promise and never a throw. That contract is load-bearing —
 * `useState(initialRoster)` and the award/spend path inside a pointerdown
 * handler (invariant 1) have nowhere to await. kv.ts is what keeps it true on
 * native, where the underlying store is async.
 *
 * Backends live in kv.ts: localStorage on the web, `@capacitor/preferences`
 * on device (not evictable under disk pressure, and the thing iCloud KVS and
 * Android Auto Backup actually back up).
 *
 * Schema history (useProfile owns the migration logic; storage just fetches the
 * raw blobs so defaults/domain shapes live in one place):
 *   v1  single mascot: { chosen, config, balance, ledger, owned }
 *   v2  per-species progress for ONE child (PersistedProfile)
 *   v3  a Roster of named children (siblings share the device)
 *   v4  sync-safe: balance/ledger become per-device counters, LWW fields get
 *       stamps, deletes get tombstones — so the same child's progress can be
 *       merged across the family's phones without losing stars (sync/merge.ts)
 *       ← current
 *
 * PWA / service-worker note (see scripts/gen-sw.mjs): the SW caches *code*, not
 * data — localStorage is a separate store the cache wipe on update never touches,
 * so deploys never lose profiles. But the SW auto-updates silently: new code
 * lands on the *next* launch, and a device can keep running the previous app
 * version until it relaunches (and a rollback re-runs old code against new data).
 * So a format change MUST be a forward, additive migration, never a rename/reshape
 * in place: bump KEY to :vN, add a loadV(N-1) reader below, migrate old→new in
 * useProfile, and KEEP the old-version key + reader intact. Then even a not-yet-
 * updated (or rolled-back) launch reads a blob it still understands.
 */

const KEY = "attrape-lettres:roster:v4";
const V3_KEY = "attrape-lettres:roster:v3";
const V2_KEY = "attrape-lettres:profile:v2";
const V1_KEY = "attrape-lettres:profile:v1";
const SHOP_SEEN_KEY = "attrape-lettres:shop-seen:v1";

export function loadRoster(): Roster | null {
  try {
    const raw = getItem(KEY);
    return raw ? (JSON.parse(raw) as Roster) : null;
  } catch {
    return null;
  }
}

export function saveRoster(roster: Roster): void {
  try {
    setItem(KEY, JSON.stringify(roster));
  } catch {
    /* private mode / quota — profiles just won't persist this session. */
  }
}

/**
 * Balance each child LAST SAW in the shop (childId → stars). Purely cosmetic:
 * the savings meters animate from this value on entry, so stars earned since
 * the previous visit read as visible growth. Losing it costs nothing but the
 * animation, hence its own key outside the roster blob (no schema bump).
 */
export function loadShopSeen(): Record<string, number> {
  try {
    const raw = getItem(SHOP_SEEN_KEY);
    return raw ? (JSON.parse(raw) as Record<string, number>) : {};
  } catch {
    return {};
  }
}

export function saveShopSeen(seen: Record<string, number>): void {
  try {
    setItem(SHOP_SEEN_KEY, JSON.stringify(seen));
  } catch {
    /* private mode / quota — the meter just won't animate next visit. */
  }
}

/**
 * The v3 roster, if this device still has one to migrate. Raw `unknown`: its
 * shape is the OLD `Roster` (flat `balance`/`ledger`, no stamps), which the
 * current types no longer describe. useProfile owns the reshape.
 */
export function loadV3Roster(): unknown | null {
  try {
    const raw = getItem(V3_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * The v2 single-child profile, if this device still has one to migrate. Raw
 * `unknown` for the same reason as v3: v2 and the v3 *profile* share the old
 * flat `balance`/`ledger` shape, which the current types no longer describe.
 */
export function loadV2Profile(): unknown | null {
  try {
    const raw = getItem(V2_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/** The raw v1 single-mascot blob, if present, for migration. */
export function loadV1Profile(): unknown | null {
  try {
    const raw = getItem(V1_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
