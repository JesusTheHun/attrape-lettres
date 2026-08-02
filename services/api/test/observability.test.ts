import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import type { HouseholdStore, WriteResult } from "../src/household/store.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import { MAX_CHILD_BYTES, type WireChild } from "../src/household/wire.js";
import { captureLogs, householdRef, log, safePath } from "../src/log.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";
import { InMemoryCodeStore } from "../src/codes/store.js";

/* -------------------------------------------------------------------------- */
/* What the logs may and may not say.                                          */
/*                                                                             */
/* This service fails silently by design: both clients swallow everything that */
/* is not 200/404/412 and keep playing offline. A log line is therefore the    */
/* ONLY place a failure can appear, which makes these lines load-bearing —     */
/* `infra/template.yaml` turns them into metric filters and alarms, and a      */
/* renamed event or a changed field breaks that quietly.                       */
/*                                                                             */
/* And they are the newest way to leak. A household id is the only credential  */
/* this service has, and a request path contains one. Half these tests exist   */
/* to prove that logging a request does not write a family's credential, a     */
/* device id or anything from a body into CloudWatch.                          */
/* -------------------------------------------------------------------------- */

const HOUSEHOLD = "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f";

let restore: (() => void) | null = null;
afterEach(() => {
  restore?.();
  restore = null;
});

/** Run `fn` with the sink captured, and hand back everything it logged. */
async function logged(fn: () => unknown) {
  const capture = captureLogs();
  restore = capture.restore;
  await fn();
  return capture.records;
}

function app(households: HouseholdStore = new InMemoryHouseholdStore()) {
  return buildApp({ households, telemetry: new InMemoryTelemetrySink(), codes: new InMemoryCodeStore() });
}

function child(id: string, extra: Record<string, string> = {}): WireChild {
  return {
    id,
    touchedAt: 1_700_000_000_000,
    profile: {
      chosen: true,
      current: "dragon" as const,
      currentRev: { at: 1_700_000_000_000, by: "device-a" },
      species: {
        dragon: {
          config: {
            species: "dragon" as const,
            stage: 3,
            colors: { bodyColor: "#8CF", ...extra },
            styles: {},
            accessories: [],
          },
          owned: [],
          rev: { at: 1_700_000_000_000, by: "device-a" },
        },
      },
      stars: { earned: { "device-a": 10 }, spent: {} },
      clears: { "first-letter:1": { "device-a": 2 } },
    },
  };
}

const put = (body: unknown, id = HOUSEHOLD) =>
  new Request(`http://x/household/${id}`, {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });

describe("the request log", () => {
  it("records the route template, never the path that carries the credential", async () => {
    const records = await logged(() => app().fetch(new Request(`http://x/household/${HOUSEHOLD}`)));

    const request = records.find((r) => r.event === "request")!;
    expect(request).toBeDefined();
    expect(request.route).toBe("/household/:id");
    expect(request.status).toBe(404);
    // THE assertion. Whoever holds a household id can read and overwrite that
    // family's roster, so it must not be sitting in CloudWatch in the clear.
    expect(JSON.stringify(records)).not.toContain(HOUSEHOLD);
  });

  it("says nothing a caller controls when no route matches", async () => {
    const records = await logged(() =>
      app().fetch(new Request(`http://x/${HOUSEHOLD}/../secrets`))
    );

    const request = records.find((r) => r.event === "request")!;
    expect(request.route).toBe("/*");
    expect(JSON.stringify(records)).not.toContain(HOUSEHOLD);
  });

  it("logs a store outage at error level, without the document that triggered it", async () => {
    const broken: HouseholdStore = {
      async read() {
        throw new Error("ProvisionedThroughputExceededException");
      },
      async write(): Promise<WriteResult> {
        throw new Error("nope");
      },
    };

    const records = await logged(async () => {
      const response = await app(broken).fetch(new Request(`http://x/household/${HOUSEHOLD}`));
      // Both clients swallow this, so the log line below is the only trace.
      expect(response.status).toBe(500);
      expect(await response.json()).toEqual({ error: "internal" });
    });

    const failure = records.find((r) => r.event === "request.failed")!;
    expect(failure.level).toBe("error");
    expect(failure.message).toBe("ProvisionedThroughputExceededException");
    // The wrapping request line escalates too, so one metric filter on
    // `level = error` catches both.
    expect(records.find((r) => r.event === "request")!.level).toBe("error");
  });

  it("scrubs a validation path before it can carry a device id", async () => {
    // The path of a bad counter walks into a record KEY, and in this schema
    // those keys are device ids. `stars.earned.<uuid>` must not be logged.
    const bad = child("kid");
    bad.profile.stars.earned["8f14e45f-ceea-467a-9f8c-8a6d1e3c9b21"] = -5;

    const records = await logged(() => app().fetch(put({ children: [bad], removed: {} })));

    const invalid = records.find((r) => r.event === "request.invalid")!;
    expect(invalid.level).toBe("warn");
    expect(invalid.issues).toEqual([
      { path: "children.0.profile.stars.earned.*", code: "too_small" },
    ]);
    expect(JSON.stringify(records)).not.toContain("8f14e45f");
  });

  it("never echoes a rejected value, only where the document was wrong", async () => {
    const records = await logged(() =>
      app().fetch(put({ children: [child("kid")], removed: {}, smuggled: "Camille" }))
    );

    expect(JSON.stringify(records)).not.toContain("Camille");
    expect((records.find((r) => r.event === "request.invalid")!.issues as unknown[])[0]).toEqual({
      path: "",
      code: "unrecognized_keys",
    });
  });
});

describe("safePath", () => {
  it("keeps field names and indices, and drops everything a payload could name", () => {
    expect(safePath(["children", 0, "profile", "stars", "earned"])).toBe(
      "children.0.profile.stars.earned"
    );
    // Every id this system mints fails the field-name test: uuids and the web
    // client's `d_<base36>` fallback carry `-` or `_`, and a ledger key carries
    // both a `-` and a `:`.
    expect(safePath(["earned", "8f14e45f-ceea-467a-9f8c-8a6d1e3c9b21"])).toBe("earned.*");
    expect(safePath(["earned", "d_lz9k2_3f8a1"])).toBe("earned.*");
    expect(safePath(["clears", "spell-syllable-plus:2"])).toBe("clears.*");
  });
});

describe("householdRef", () => {
  it("is stable, short, and not the id", () => {
    const ref = householdRef(HOUSEHOLD);

    expect(ref).toHaveLength(12);
    expect(ref).toMatch(/^[0-9a-f]{12}$/);
    expect(ref).toBe(householdRef(HOUSEHOLD));
    // Not a truncation — that would leak half a credential.
    expect(HOUSEHOLD).not.toContain(ref);
    expect(householdRef(HOUSEHOLD + "x")).not.toBe(ref);
  });
});

describe("the per-child ceiling", () => {
  /** Legal by every other rule in the schema, and far too big to store. */
  function oversized(id: string) {
    const colors: Record<string, string> = {};
    for (let i = 0; i < 4000; i += 1) colors[`c${i}`.padEnd(60, "k")] = "#abcdef";
    return child(id, colors);
  }

  it("refuses a child DynamoDB would refuse, as a 400 rather than a 500", async () => {
    const body = { children: [oversized("kid")], removed: {} };
    expect(JSON.stringify(body.children[0]).length).toBeGreaterThan(MAX_CHILD_BYTES);

    const response = await app().fetch(put(body));

    // A 400 is swallowed by both clients exactly as a 500 is — what this buys
    // is that it happens before the transaction, deterministically, in one
    // place that knows which household and which child.
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid" });
  });

  it("raises its own alarmable event, naming the family by hash and the child by index", async () => {
    const records = await logged(() =>
      app().fetch(put({ children: [child("small"), oversized("big")], removed: {} }))
    );

    const alarm = records.find((r) => r.event === "household.oversized")!;
    expect(alarm.level).toBe("error");
    expect(alarm.household).toBe(householdRef(HOUSEHOLD));
    expect(alarm.children).toEqual([1]);
    expect(alarm.limit).toBe(MAX_CHILD_BYTES);
    // A family that cannot sync is not the same event as somebody posting
    // nonsense at a public endpoint, or the alarm would fire on both.
    expect(records.find((r) => r.event === "request.invalid")).toBeUndefined();
    expect(JSON.stringify(records)).not.toContain(HOUSEHOLD);
  });

  it("still accepts a maxed-out real child, with room to spare", async () => {
    // What the game can actually produce: five species, every slot filled, ten
    // devices' worth of counters. The ceiling has to sit far above this or it
    // becomes the outage it was meant to prevent.
    const kid = child("kid");
    const devices = Array.from({ length: 10 }, (_, i) => `device-${i}-${"x".repeat(28)}`);
    for (const species of ["unicorn", "cat", "fox", "rabbit", "dragon"] as const) {
      (kid.profile.species as Record<string, unknown>)[species] = {
        config: {
          species,
          stage: 9,
          colors: Object.fromEntries(
            ["bodyColor", "bellyColor", "eyeColor", "hornColor", "maneColor"].map((k) => [
              k,
              "#8CF012",
            ])
          ),
          styles: Object.fromEntries(
            ["tailSize", "earShape", "pattern", "pose"].map((k) => [k, "variant-three"])
          ),
          accessories: Array.from({ length: 8 }, (_, i) => `${species}.accessory.${i}`),
        },
        owned: Array.from({ length: 78 }, (_, i) => `${species}.accessory.long-name-${i}`),
        rev: { at: 1_700_000_000_000, by: devices[0] },
      };
    }
    kid.profile.stars = {
      earned: Object.fromEntries(devices.map((d) => [d, 99_999])),
      spent: Object.fromEntries(devices.map((d) => [d, 4_321])),
    };
    // Seventeen exercises across every level they have, per device — the part
    // that actually dominates a grown-up profile.
    const clears: Record<string, Record<string, number>> = {};
    for (let exercise = 0; exercise < 17; exercise += 1) {
      for (let level = 0; level < 5; level += 1) {
        clears[`exercise-name-${exercise}:${level}`] = Object.fromEntries(
          devices.map((d) => [d, 40])
        );
      }
    }
    kid.profile.clears = clears;

    const size = JSON.stringify(kid).length;
    expect(size).toBeLessThan(MAX_CHILD_BYTES / 4);

    const response = await app().fetch(put({ children: [kid], removed: {} }));
    expect(response.status).toBe(200);
  });
});

describe("the sink", () => {
  it("restores the previous one, so a spec cannot leak into the next", () => {
    const outer = captureLogs();
    const inner = captureLogs();
    log("info", "inner");
    inner.restore();
    log("info", "outer");
    outer.restore();

    expect(inner.records.map((r) => r.event)).toEqual(["inner"]);
    expect(outer.records.map((r) => r.event)).toEqual(["outer"]);
  });
});
