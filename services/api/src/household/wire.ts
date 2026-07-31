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

/**
 * The ceiling on one child, in bytes of JSON.
 *
 * DynamoDB refuses an item over 400 KB and the store writes one item per child,
 * so this is that limit expressed somewhere a client can be told about it.
 * Without it the schema is generous in exactly the wrong places — `colors` and
 * `styles` are records with no bound on how many keys they hold, and `owned`
 * allows 500 strings of 128 characters per species across five species — and a
 * child that is entirely legal by the schema can be twenty times larger than a
 * real one. The integration suite writes exactly such a child to prove it.
 *
 * The number counts JSON rather than DynamoDB's own accounting, which counts
 * attribute names and values but not the quotes, braces and colons between
 * them. JSON is therefore always the larger of the two, so a child that passes
 * here cannot be an item DynamoDB refuses. 256 KB against a 400 KB limit leaves
 * the difference as slack for that approximation, and it is still five times
 * the largest child this game can produce: a maxed-out profile measures about
 * 30 KB at five devices and about 53 KB at ten.
 *
 * WHY A BYTE COUNT AND NOT TIGHTER FIELD BOUNDS. Bounding `owned` to the size
 * of today's catalogue would couple this service to the client's content, and
 * every new accessory would mean deploying the server before the app or
 * rejecting valid pushes. A byte ceiling constrains the one thing the store
 * actually cares about and needs no maintenance as content grows.
 *
 * This does not make the failure visible on its own — both clients swallow a
 * 400 exactly as they swallow a 500. What it buys is that the rejection now
 * happens BEFORE the transaction, deterministically, in one place that can name
 * the household and the child index in a log line. See `household.oversized`.
 */
export const MAX_CHILD_BYTES = 256 * 1024;

/**
 * The marker on the oversized-child issue.
 *
 * `app.ts` matches on it to raise a distinct, alarmable log event, because this
 * one validation failure is not a misbehaving client — it is a real family
 * whose sync has just stopped, permanently, with nothing on their phone to say
 * so. Every other 400 from this schema means somebody is posting nonsense.
 */
export const CHILD_TOO_LARGE = "child too large";

/** A child as it travels: everything except who they are. */
export const wireChild = z
  .object({
    id: z.string().min(1).max(128),
    touchedAt: millis,
    profile: persistedProfile,
  })
  .strict()
  // `.length` on the JSON string rather than `Buffer.byteLength`: a multi-byte
  // character counts as one here and as two or three at the store, but every
  // string in this document is already capped and the 144 KB between this
  // ceiling and DynamoDB's covers the difference many times over. Counting
  // UTF-16 units keeps the check cheap on the push path.
  .refine((child) => JSON.stringify(child).length <= MAX_CHILD_BYTES, {
    error: CHILD_TOO_LARGE,
  });

export const wireRoster = z
  .object({
    /**
     * Capped at 64, and the number is load-bearing beyond politeness: a push is
     * written as one DynamoDB transaction of one root item plus one item per
     * child, and a transaction takes at most 100 items. Raising this above 99
     * would start rejecting valid rosters at the store rather than at the
     * schema. See `MAX_TRANSACT_ITEMS` in `dynamo.ts`.
     *
     * A transaction is also capped at 4 MB in total, which 64 children of
     * `MAX_CHILD_BYTES` each would blow through. What keeps that unreachable is
     * `MAX_BODY_BYTES` in `app.ts`: 2 MB of request body cannot become 4 MB of
     * items. The three numbers are one constraint wearing three hats — change
     * any of them and check the other two.
     */
    children: z.array(wireChild).max(64),
    /** childId → when it was deleted. A tombstone is a timestamp, nothing more. */
    removed: z.record(z.string().min(1).max(128), millis),
  })
  .strict();

export type WireChild = z.infer<typeof wireChild>;
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
