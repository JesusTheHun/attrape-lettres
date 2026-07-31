/**
 * This device's identity — the key every counter in a profile is indexed by.
 *
 * It is NOT an identifier for a person. It is generated locally, never leaves
 * the household record, is not tied to the child, the parent, the OS or the
 * hardware, and a reinstall mints a fresh one. That is deliberate: it exists so
 * two replicas of the same child's progress can be merged without losing stars
 * (see sync/merge.ts), and for nothing else. Never send it to an analytics
 * endpoint — that is what makes the telemetry payload genuinely anonymous
 * rather than merely pseudonymous.
 *
 * Resolved LAZILY, not at module load: on native the id lives behind kv.ts's
 * hydrated cache, so the first read has to happen after `hydrateKv()` — which
 * it does, since nothing calls this before the roster is built. Reads stay
 * synchronous because useProfile's award/spend path runs inside a pointerdown
 * handler and cannot await.
 */

import { getItem, removeItem, setItem } from "./kv";

const KEY = "attrape-lettres:device:v1";

function mint(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `d_${Date.now().toString(36)}_${Math.floor(Math.random() * 1e9).toString(36)}`;
  }
}

function read(): string {
  const saved = getItem(KEY);
  if (saved) return saved;
  const fresh = mint();
  // If the write fails (private mode, disk full) this is a per-launch id. That
  // still merges correctly — it just adds a counter key per launch. Stars stay
  // exact, which is the only thing that must not degrade.
  setItem(KEY, fresh);
  return fresh;
}

let cached: string | null = null;

/** This device's stable id. */
export function deviceId(): string {
  if (cached === null) cached = read();
  return cached;
}

/** Tests only — forget the cached id so a spec can act as a different device. */
export function __resetDeviceId(id?: string): string {
  if (id) setItem(KEY, id);
  else removeItem(KEY);
  cached = null;
  return deviceId();
}
