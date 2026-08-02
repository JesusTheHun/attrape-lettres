import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";
import { InMemoryCodeStore } from "../src/codes/store.js";

/* -------------------------------------------------------------------------- */
/* The pull → merge → push loop, from the server's side.                       */
/*                                                                             */
/* These tests speak HTTP, not TypeScript: they build the same requests the    */
/* shipped clients build, because the clients are Swift and TypeScript and     */
/* neither can be imported here. What is being pinned is the wire behaviour    */
/* both of them already depend on.                                            */
/*                                                                            */
/* Both clients FAIL SILENT — anything that is not 200/404/412 is swallowed    */
/* and the device keeps playing offline. So a wrong status here does not throw */
/* anywhere; it shows up months later as two phones quietly disagreeing. That  */
/* is why the statuses are asserted this pedantically.                         */
/* -------------------------------------------------------------------------- */

const HOUSEHOLD = "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f";

function app() {
  return buildApp({
    households: new InMemoryHouseholdStore(),
    telemetry: new InMemoryTelemetrySink(),
    codes: new InMemoryCodeStore(),
  });
}

/** A minimal but COMPLETE household document — exactly what `toWire` emits. */
function roster(stars = 10, childId = "child-1") {
  return {
    children: [
      {
        id: childId,
        touchedAt: 1_700_000_000_000,
        profile: {
          chosen: true,
          current: "dragon",
          currentRev: { at: 1_700_000_000_000, by: "device-a" },
          species: {
            dragon: {
              config: {
                species: "dragon",
                stage: 3,
                colors: { bodyColor: "#8CF" },
                styles: { tailSize: "long" },
                accessories: ["dragon.hat.crown"],
              },
              owned: ["dragon.hat.crown"],
              rev: { at: 1_700_000_000_000, by: "device-a" },
            },
          },
          stars: { earned: { "device-a": stars }, spent: {} },
          clears: { "first-letter:1": { "device-a": 2 } },
        },
      },
    ],
    removed: {},
  };
}

const put = (body: unknown, ifMatch?: string) =>
  new Request(`http://x/household/${HOUSEHOLD}`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      ...(ifMatch ? { "if-match": ifMatch } : {}),
    },
    body: JSON.stringify(body),
  });

const get = () => new Request(`http://x/household/${HOUSEHOLD}`);

describe("household — the first write", () => {
  it("answers 404 before anything exists, which is how a client knows to create", async () => {
    const res = await app().fetch(get());
    expect(res.status).toBe(404);
  });

  it("creates on a push with no If-Match, and hands back an ETag", async () => {
    const a = app();
    const res = await a.fetch(put(roster()));
    expect(res.status).toBe(200);
    expect(res.headers.get("etag")).toBe('"1"');
  });

  it("returns the document byte for byte, so the other device's merge sees the same stamps", async () => {
    const a = app();
    const sent = roster();
    await a.fetch(put(sent));

    const res = await a.fetch(get());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(sent);
  });
});

describe("household — optimistic concurrency", () => {
  it("moves the ETag on every accepted write", async () => {
    const a = app();
    await a.fetch(put(roster(10)));
    const second = await a.fetch(put(roster(20), '"1"'));
    expect(second.status).toBe(200);
    expect(second.headers.get("etag")).toBe('"2"');
  });

  it("refuses a write built on a superseded read — this is the whole design", async () => {
    const a = app();
    await a.fetch(put(roster(10))); // etag "1"

    // Mum's phone and Dad's phone both pulled at revision 1.
    const mum = await a.fetch(put(roster(20), '"1"'));
    const dad = await a.fetch(put(roster(30), '"1"'));

    expect(mum.status).toBe(200);
    expect(dad.status).toBe(412);
  });

  it("keeps the winner's document when a write is refused — nobody's stars vanish", async () => {
    const a = app();
    await a.fetch(put(roster(10)));
    await a.fetch(put(roster(20), '"1"'));
    await a.fetch(put(roster(30), '"1"')); // conflicts

    const res = await a.fetch(get());
    const doc = (await res.json()) as ReturnType<typeof roster>;
    expect(doc.children[0]!.profile.stars.earned["device-a"]).toBe(20);
  });

  it("lets the loser through once it re-pulls and retries, which is what the client does", async () => {
    const a = app();
    await a.fetch(put(roster(10)));
    await a.fetch(put(roster(20), '"1"'));
    expect((await a.fetch(put(roster(30), '"1"'))).status).toBe(412);

    // syncOnce: pull again, merge on top, push with the fresh etag.
    const fresh = (await a.fetch(get())).headers.get("etag")!;
    const retry = await a.fetch(put(roster(50), fresh));
    expect(retry.status).toBe(200);
    expect(retry.headers.get("etag")).toBe('"3"');
  });

  it("refuses a create against a household that already exists", async () => {
    const a = app();
    await a.fetch(put(roster()));
    // No If-Match means "I believe this does not exist". It does. A blind
    // overwrite here would silently destroy the other phone's whole roster.
    expect((await a.fetch(put(roster(999)))).status).toBe(412);
  });

  it("treats a malformed If-Match as a conflict, not an error, so the client re-pulls", async () => {
    const a = app();
    await a.fetch(put(roster()));
    for (const bad of ['"nope"', "7", "", "W/\"1\"", '"999"']) {
      expect((await a.fetch(put(roster(1), bad))).status).toBe(412);
    }
  });
});

describe("household — the document clients really send", () => {
  /**
   * `blankSpeciesMap()` fills all five mascots on the web, and the Swift port
   * does the same, so every real push carries five species whether or not the
   * child has ever picked them. The other tests use one species deliberately —
   * that is the tolerance — but at least one has to be the real thing.
   */
  const ALL_FIVE = ["unicorn", "cat", "fox", "rabbit", "dragon"] as const;

  function fullRoster() {
    const r = roster();
    const progress = (s: string) => ({
      config: { species: s, stage: 0, colors: {}, styles: {}, accessories: [] },
      owned: [],
      rev: { at: 0, by: "" },
    });
    r.children[0]!.profile.species = Object.fromEntries(
      ALL_FIVE.map((s) => [s, progress(s)])
    ) as never;
    return r;
  }

  it("accepts and round-trips a five-species document", async () => {
    const a = app();
    const sent = fullRoster();
    expect((await a.fetch(put(sent))).status).toBe(200);
    expect(await (await a.fetch(get())).json()).toEqual(sent);
  });

  it("accepts a tombstone-only roster — a family that deleted its last child", async () => {
    const a = app();
    const res = await a.fetch(
      put({ children: [], removed: { "child-1": 1_700_000_000_000 } })
    );
    expect(res.status).toBe(200);
  });
});

describe("household — what the server refuses to store", () => {
  it("rejects a child's name, even though no shipped client sends one", async () => {
    const withName = roster() as unknown as { children: Record<string, unknown>[] };
    withName.children[0]!.name = "Léa";
    withName.children[0]!.nameRev = { at: 1, by: "device-a" };

    const res = await app().fetch(put(withName));
    expect(res.status).toBe(400);
  });

  it("does not echo the rejected payload back", async () => {
    const withName = roster() as unknown as { children: Record<string, unknown>[] };
    withName.children[0]!.name = "Léa";

    const res = await app().fetch(put(withName));
    expect(await res.text()).not.toContain("Léa");
  });

  it("rejects any unrecognised key rather than storing it", async () => {
    const extra = roster() as unknown as Record<string, unknown>;
    extra.note = "anything at all";
    expect((await app().fetch(put(extra))).status).toBe(400);
  });

  it("rejects a negative counter — counters are grow-only by construction", async () => {
    const bad = roster();
    bad.children[0]!.profile.stars.earned["device-a"] = -5;
    expect((await app().fetch(put(bad))).status).toBe(400);
  });

  it("rejects a household id that is not an opaque token", async () => {
    const res = await app().fetch(
      new Request("http://x/household/..%2Fetc%2Fpasswd", {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(roster()),
      })
    );
    expect(res.status).toBe(400);
  });

  it("accepts the web client's non-uuid fallback id", async () => {
    // `h_<base36><base36>`, minted where crypto.randomUUID is unavailable.
    const res = await app().fetch(
      new Request("http://x/household/h_m1x2y3z4abcdefg", {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(roster()),
      })
    );
    expect(res.status).toBe(200);
  });
});
