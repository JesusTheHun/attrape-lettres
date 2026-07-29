import type { ChildProfile, Roster } from "../types";
import { getItem, setItem } from "../kv";
import { mergeRoster } from "./merge";

/* -------------------------------------------------------------------------- */
/* Household sync — the transport around merge.ts.                              */
/*                                                                             */
/* No accounts, no e-mail, no login. One device creates a household and shows a */
/* short code; another types it in. That is the entire identity model, and it   */
/* is why the server holds nothing but opaque ids and integers.                 */
/*                                                                             */
/* Names never leave the device (see ChildProfile.name). `toWire` drops both    */
/* the name and its LWW stamp, so a joining phone cannot even be handed one —   */
/* it asks the parent « Qui est-ce ? » instead. What remains on the server is   */
/* genuinely anonymous rather than merely pseudonymous, which is the difference */
/* between a one-paragraph privacy policy and a compliance project.            */
/* -------------------------------------------------------------------------- */

const HOUSEHOLD_KEY = "attrape-lettres:household:v1";
const ETAG_KEY = "attrape-lettres:household-etag:v1";
/** Read lazily, not at module load, so tests can stub the env. */
function endpoint(): string | undefined {
  return import.meta.env.VITE_SYNC_URL as string | undefined;
}

/** A child as it travels: everything except who they are. */
export type WireChild = Omit<ChildProfile, "name" | "nameRev">;
export interface WireRoster {
  children: WireChild[];
  removed: Record<string, number>;
}

const NO_NAME_REV = { at: 0, by: "" };

/** Strip identity. `activeId` goes too — it is about this tablet, not the family. */
export function toWire(r: Roster): WireRoster {
  return {
    children: r.children.map(({ name: _name, nameRev: _nameRev, ...rest }) => rest),
    removed: r.removed,
  };
}

/**
 * Re-attach identity from what THIS device already knows. A child we have never
 * seen gets a placeholder and a zero stamp, so the local name always wins the
 * merge and the parent is prompted to say who it is.
 */
export function fromWire(w: WireRoster, local: Roster): Roster {
  const known = new Map(local.children.map((c) => [c.id, c]));
  return {
    children: w.children.map((c) => {
      const mine = known.get(c.id);
      return {
        ...c,
        name: mine?.name ?? "Enfant",
        nameRev: mine?.nameRev ?? NO_NAME_REV,
      };
    }),
    activeId: null, // never adopted from the wire
    removed: w.removed,
  };
}

export interface SyncTransport {
  pull(household: string): Promise<{ roster: WireRoster; etag: string } | null>;
  /** Resolves "conflict" when `etag` is stale — the caller re-pulls and retries. */
  push(
    household: string,
    roster: WireRoster,
    etag: string | null
  ): Promise<{ etag: string } | "conflict">;
}

const httpTransport: SyncTransport = {
  async pull(household) {
    if (!endpoint()) return null;
    const res = await fetch(`${endpoint()}/household/${household}`, { credentials: "omit" });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`sync pull ${res.status}`);
    return { roster: (await res.json()) as WireRoster, etag: res.headers.get("etag") ?? "" };
  },
  async push(household, roster, etag) {
    if (!endpoint()) throw new Error("sync disabled");
    const res = await fetch(`${endpoint()}/household/${household}`, {
      method: "PUT",
      credentials: "omit",
      headers: {
        "content-type": "application/json",
        // Optimistic concurrency: the server rejects a write built on a stale
        // read, so a simultaneous save from the other phone is never clobbered.
        ...(etag ? { "if-match": etag } : {}),
      },
      body: JSON.stringify(roster),
    });
    if (res.status === 412) return "conflict";
    if (!res.ok) throw new Error(`sync push ${res.status}`);
    return { etag: res.headers.get("etag") ?? "" };
  },
};

let transport: SyncTransport = httpTransport;

/** Tests only. */
export function __setSyncTransport(t: SyncTransport): void {
  transport = t;
}

/* -- household identity -----------------------------------------------------*/

export function householdId(): string | null {
  return getItem(HOUSEHOLD_KEY);
}

export function joinHousehold(id: string): void {
  setItem(HOUSEHOLD_KEY, id);
  setItem(ETAG_KEY, "");
}

export function createHousehold(): string {
  const id =
    typeof crypto?.randomUUID === "function"
      ? crypto.randomUUID()
      : `h_${Date.now().toString(36)}${Math.floor(Math.random() * 1e9).toString(36)}`;
  joinHousehold(id);
  return id;
}

export function syncEnabled(): boolean {
  return Boolean(endpoint()) && householdId() !== null;
}

/* -- the one operation ------------------------------------------------------*/

/**
 * Pull, merge, push. Returns the roster this device should now hold.
 *
 * Idempotent and safe to call on every resume: merge.ts guarantees that
 * repeating it changes nothing. On a 412 we re-pull and merge again rather than
 * forcing — the other phone's write is somebody's stars.
 */
export async function syncOnce(local: Roster): Promise<Roster> {
  const household = householdId();
  if (!household || !endpoint()) return local;

  for (let attempt = 0; attempt < 3; attempt++) {
    const remote = await transport.pull(household);
    const merged = remote ? mergeRoster(local, fromWire(remote.roster, local)) : local;
    const result = await transport.push(household, toWire(merged), remote?.etag ?? null);
    if (result !== "conflict") {
      setItem(ETAG_KEY, result.etag);
      return merged;
    }
    // Someone else wrote between our read and our write. Loop: pull their
    // version, merge on top, try again. Never `force`.
    local = merged;
  }
  // Three collisions in a row: keep the merged local state and try next resume.
  return local;
}
