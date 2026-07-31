import { z } from "@hono/zod-openapi";

/* -------------------------------------------------------------------------- */
/* The household document, as validation.                                      */
/*                                                                             */
/* This is a FROZEN wire format. It is not designed here — it is described      */
/* here, from two shipped clients that already speak it:                        */
/*   apps/game-web/src/sync/client.ts   `toWire` / `WireRoster`                 */
/*   apps/game-ios/Sources/ALCore/Sync/Wire.swift                              */
/* Field names are byte-identical across all three on purpose. Renaming one     */
/* here does not migrate anything; it orphans every household on disk.         */
/*                                                                             */
/* INVARIANT 10 IS ENFORCED HERE, not just on the clients. `WireChild` has no   */
/* `name` and no `nameRev`, and these objects are STRICT — an unrecognised key  */
/* is rejected rather than stored. A client that one day starts sending a       */
/* child's first name gets a 400, instead of quietly turning this database      */
/* into personal data. That is the difference between "no PII reaches us" and   */
/* "no PII reaches us as long as every client stays well-behaved".              */
/* -------------------------------------------------------------------------- */

/** `Date.now()` — milliseconds. Not a Date: the clients send raw integers. */
const millis = z.number().int().min(0);

/**
 * A grow-only counter, deviceId → count. Values are non-negative integers.
 *
 * DELIBERATELY UNBOUNDED in magnitude. A hostile client can inflate its own
 * child's star count, and that is fine: there is no shared economy, no
 * leaderboard and nothing to win. Their own kid gets free hats. Capping would
 * buy nothing and would risk rejecting a legitimate write, which for a
 * fail-silent client means sync stops working with no visible cause.
 */
const counter = z.record(z.string().min(1).max(128), z.number().int().min(0));

/** LWW stamp. `by` is a device id — opaque, and never sent to anyone else. */
const rev = z.object({ at: millis, by: z.string().max(128) }).strict();

const species = z.enum(["unicorn", "cat", "fox", "rabbit", "dragon"]);

const mascotConfig = z
  .object({
    species,
    stage: z.number().int().min(0).max(9),
    colors: z.record(z.string().max(64), z.string().max(64)),
    styles: z.record(z.string().max(64), z.string().max(64)),
    accessories: z.array(z.string().max(128)).max(200),
  })
  .strict();

const speciesProgress = z
  .object({
    config: mascotConfig,
    owned: z.array(z.string().max(128)).max(500),
    rev,
  })
  .strict();

const persistedProfile = z
  .object({
    chosen: z.boolean(),
    current: species,
    currentRev: rev,
    /**
     * `partialRecord`, not `record`: Zod 4's `z.record` with an enum key is
     * EXHAUSTIVE, and would reject a document missing any of the five mascots.
     * Today every client fills all five (`blankSpeciesMap`), so exhaustive
     * would pass — and would turn any future client that trims an untouched
     * species into a silent sync outage, because both clients swallow a 400.
     * The server has no reason to care: it never reads this.
     *
     * The KEY stays closed, which has an operational consequence worth knowing
     * before it bites: adding a sixth mascot means deploying this service
     * BEFORE shipping the client that sends it.
     */
    species: z.partialRecord(species, speciesProgress),
    stars: z.object({ earned: counter, spent: counter }).strict(),
    /** ledgerKey() → per-device clear counts. The key format is `"<exercise>:<level>"`. */
    clears: z.record(z.string().min(1).max(128), counter),
  })
  .strict();

/** A child as it travels: everything except who they are. */
export const wireChild = z
  .object({
    id: z.string().min(1).max(128),
    touchedAt: millis,
    profile: persistedProfile,
  })
  .strict();

export const wireRoster = z
  .object({
    children: z.array(wireChild).max(64),
    /** childId → when it was deleted. A tombstone is a timestamp, nothing more. */
    removed: z.record(z.string().min(1).max(128), millis),
  })
  .strict();

export type WireRoster = z.infer<typeof wireRoster>;

/**
 * The household id, as minted by the clients.
 *
 * Usually `crypto.randomUUID()`, but the web client has a documented fallback
 * that produces `h_<base36><base36>` where `randomUUID` is unavailable, and the
 * iOS client uppercases nothing. So this is validated as an opaque token, not
 * as a uuid — a stricter rule here would lock out devices that already exist.
 */
export const householdId = z
  .string()
  .min(8)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/, "household id must be an opaque token");
