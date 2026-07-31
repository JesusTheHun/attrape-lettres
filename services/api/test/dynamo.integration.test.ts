import {
  CreateTableCommand,
  DeleteTableCommand,
  DynamoDBClient,
  TransactionCanceledException,
} from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { DynamoHouseholdStore } from "../src/household/dynamo.js";
import type { WireChild, WireRoster } from "../src/household/wire.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The ONLY tests here that touch a real DynamoDB.                            */
/*                                                                             */
/* Everything else in this suite stubs the client, which proves the commands   */
/* we build are the ones we meant to build — and proves nothing about whether  */
/* DynamoDB accepts them, or about what happens when two phones genuinely race.*/
/* A stub cannot lose a race: the in-memory double is single-threaded, so its  */
/* "concurrency" tests are two sequential writes wearing the same etag.        */
/*                                                                             */
/* So this file exists for the handful of properties only a real engine can    */
/* answer: does the condition expression parse, does the transaction actually  */
/* serialise, does the marshaller keep an empty map, does the paging loop ever */
/* run. Run it with:                                                           */
/*                                                                             */
/*   docker run -d -p 8123:8000 amazon/dynamodb-local                          */
/*   pnpm test:integration                                                     */
/*                                                                             */
/* Without DYNAMO_ENDPOINT the whole file skips, loudly, so `pnpm test` stays  */
/* fast and offline and nobody mistakes a skip for coverage.                   */
/* -------------------------------------------------------------------------- */

const ENDPOINT = process.env.DYNAMO_ENDPOINT;
const TABLE = "attrape-households-test";

const client = new DynamoDBClient({
  endpoint: ENDPOINT,
  region: "eu-west-3",
  credentials: { accessKeyId: "local", secretAccessKey: "local" },
});
const documents = DynamoDBDocumentClient.from(client, {
  marshallOptions: { removeUndefinedValues: true },
});
const store = new DynamoHouseholdStore(documents, TABLE);

/** Each test gets its own household, so nothing leaks between them. */
let counter = 0;
function household(): string {
  counter += 1;
  return `h_integration_${counter}_${process.pid}`;
}

function child(id: string, earned: number, touchedAt = 1_700_000_000_000): WireChild {
  return {
    id,
    touchedAt,
    profile: {
      chosen: true,
      current: "dragon",
      currentRev: { at: touchedAt, by: "device-a" },
      species: {
        dragon: {
          config: {
            species: "dragon",
            stage: 3,
            colors: { scales: "#3a7", belly: "#fe9" },
            // An empty map is legal in the wire schema and has historically been
            // a marshalling trap. If it comes back as null the client's merge
            // reads a missing field.
            styles: {},
            accessories: [],
          },
          owned: ["cape", "goggles"],
          rev: { at: touchedAt, by: "device-a" },
        },
      },
      stars: { earned: { "device-a": earned }, spent: {} },
      // A ledger key contains a colon; a device key is a uuid. Neither is a
      // legal DynamoDB expression name, which is exactly why they only ever
      // appear as map keys and never in an expression.
      clears: { "spell-syllable-plus:2": { "device-a": 4 } },
    },
  };
}

// File-level, not per-describe: an afterAll inside the first block would drop
// the table before the second block ran.
beforeAll(async () => {
  if (!ENDPOINT) return;
  await client.send(new DeleteTableCommand({ TableName: TABLE })).catch(() => undefined);
  await client.send(
    new CreateTableCommand({
      TableName: TABLE,
      AttributeDefinitions: [
        { AttributeName: "pk", AttributeType: "S" },
        { AttributeName: "sk", AttributeType: "S" },
      ],
      KeySchema: [
        { AttributeName: "pk", KeyType: "HASH" },
        { AttributeName: "sk", KeyType: "RANGE" },
      ],
      BillingMode: "PAY_PER_REQUEST",
    })
  );
});

afterAll(async () => {
  if (!ENDPOINT) return;
  await client.send(new DeleteTableCommand({ TableName: TABLE })).catch(() => undefined);
  client.destroy();
});

describe.skipIf(!ENDPOINT)("DynamoDB, for real", () => {
  it("accepts the commands we build at all", async () => {
    // The cheapest thing this file buys: every condition expression, attribute
    // name and marshalled value in the store is parsed by a real engine. A
    // stub would accept a ConditionExpression with a typo in it forever.
    const id = household();

    expect(await store.read(id)).toBeNull();
    expect(await store.write(id, { children: [child("zoe", 10)], removed: {} }, null)).toEqual({
      ok: true,
      etag: '"1"',
    });
    expect((await store.read(id))!.etag).toBe('"1"');
  });

  it("round-trips a roster through real marshalling, empty map and all", async () => {
    const id = household();
    const roster: WireRoster = {
      children: [child("zoe", 10), child("adam", 3)],
      removed: { gone: 1_699_000_000_000 },
    };

    await store.write(id, roster, null);
    const back = await store.read(id);

    expect(back!.roster).toEqual(roster);
    // Empty maps survive as empty maps. A null here would reach the device's
    // merge as a missing field.
    expect(back!.roster.children[0]!.profile.species.dragon!.config.styles).toEqual({});
  });

  it("keeps the pushed child order through a real Query", async () => {
    const id = household();
    // Reverse-alphabetical on purpose: a Query returns sort-key order, so if
    // the root's `order` were ignored this comes back adam, mia, zoe.
    const roster: WireRoster = {
      children: [child("zoe", 1), child("mia", 2), child("adam", 3)],
      removed: {},
    };

    await store.write(id, roster, null);

    expect((await store.read(id))!.roster.children.map((c) => c.id)).toEqual([
      "zoe",
      "mia",
      "adam",
    ]);
  });

  it("still returns a tombstoned child, so the device can resurrect it", async () => {
    const id = household();
    // touchedAt AFTER the tombstone: mergeRoster keeps this child. If the store
    // filtered tombstones the sidecar would be unreachable and the week of play
    // that happened on the other phone would be gone.
    await store.write(
      id,
      { children: [child("zoe", 40, 900)], removed: { zoe: 400 } },
      null
    );

    const back = await store.read(id);

    expect(back!.roster.children.map((c) => c.id)).toEqual(["zoe"]);
    expect(back!.roster.removed).toEqual({ zoe: 400 });
  });

  it("lets exactly ONE of eight genuinely concurrent writers win", async () => {
    // THE test. Everything else about this design is downstream of it, and no
    // stub and no single-threaded double can produce it: eight real requests,
    // in flight at once, all claiming to have read revision 1.
    const id = household();
    await store.write(id, { children: [child("zoe", 1)], removed: {} }, null);

    const results = await Promise.all(
      Array.from({ length: 8 }, (_, i) =>
        store.write(id, { children: [child("zoe", 100 + i)], removed: {} }, '"1"')
      )
    );

    expect(results.filter((r) => r.ok)).toHaveLength(1);
    expect(results.filter((r) => !r.ok)).toHaveLength(7);
    expect((await store.read(id))!.etag).toBe('"2"');
  });

  it("lets exactly ONE of five concurrent creators win", async () => {
    const id = household();

    const results = await Promise.all(
      Array.from({ length: 5 }, (_, i) =>
        store.write(id, { children: [child(`kid-${i}`, i)], removed: {} }, null)
      )
    );

    // `attribute_not_exists` is the create-only rule, and it has to hold when
    // two phones are handed the same join code at the same moment.
    expect(results.filter((r) => r.ok)).toHaveLength(1);
  });

  it("never loses a sidecar to a losing push", async () => {
    // The reason the write is a transaction rather than a loop of puts. Racer B
    // loses on the root; if its child put had landed anyway, the winning
    // revision would point at a sidecar built on a superseded merge.
    const id = household();
    await store.write(id, { children: [child("zoe", 5)], removed: {} }, null);

    const [a, b] = await Promise.all([
      store.write(id, { children: [child("zoe", 50)], removed: {} }, '"1"'),
      store.write(id, { children: [child("zoe", 60)], removed: {} }, '"1"'),
    ]);

    const winner = a.ok ? 50 : 60;
    expect(a.ok !== b.ok).toBe(true);
    const back = await store.read(id);
    expect(back!.roster.children[0]!.profile.stars.earned["device-a"]).toBe(winner);
  });

  it("pages a household too big for one Query response", async () => {
    // A Query returns at most 1 MB. Stopping at the first page would drop
    // children from the pull and the device would merge against a roster
    // missing a sibling — so the loop has to actually run at least once.
    const id = household();
    const fat = (i: number): WireChild => {
      const c = child(`kid-${String(i).padStart(2, "0")}`, i);
      c.profile.species.dragon!.owned = Array.from({ length: 200 }, (_, n) =>
        `accessory-${n}`.padEnd(120, "x")
      );
      return c;
    };
    const children = Array.from({ length: 60 }, (_, i) => fat(i));

    await store.write(id, { children, removed: {} }, null);
    const back = await store.read(id);

    expect(back!.roster.children).toHaveLength(60);
    expect(back!.roster.children.map((c) => c.id)).toEqual(children.map((c) => c.id));
  });

  it("refuses a stale etag against a household that was deleted", async () => {
    const id = household();
    const result = await store.write(id, { children: [], removed: {} }, '"3"');
    expect(result).toEqual({ ok: false, conflict: true });
  });

  it("accepts a schema-legal child that real DynamoDB would REJECT", async () => {
    // Not a happy result. `colors` and `styles` are records with no bound on
    // how many keys they hold, and `owned` allows 500 strings of 128 characters
    // per species across five species — so a child can be schema-legal at well
    // over 400 KB, which real DynamoDB refuses and this engine does not.
    //
    // Recorded as a test rather than a comment because it says exactly what is
    // NOT covered here: item-size enforcement cannot be exercised locally, so
    // the guard against it is the size alarm in production, and the diagnostic
    // is the log line in the store's failure path.
    const id = household();
    const huge = child("zoe", 1);
    huge.profile.species.dragon!.owned = Array.from({ length: 500 }, (_, n) =>
      `owned-${n}`.padEnd(128, "y")
    );
    huge.profile.species.dragon!.config.colors = Object.fromEntries(
      Array.from({ length: 5000 }, (_, n) => [`c${n}`.padEnd(60, "k"), "#abcdef"])
    );
    const bytes = Buffer.byteLength(JSON.stringify(huge), "utf8");

    expect(bytes).toBeGreaterThan(400 * 1024);
    await expect(store.write(id, { children: [huge], removed: {} }, null)).resolves.toEqual({
      ok: true,
      etag: '"1"',
    });
  });
});

describe.skipIf(!ENDPOINT)("the whole app, on a real store", () => {
  const app = () =>
    buildApp({ households: store, telemetry: new InMemoryTelemetrySink() });

  it("404 → create → pull → stale push is 412, end to end", async () => {
    const id = household();
    const url = `http://api/household/${id}`;
    const body = (earned: number) =>
      JSON.stringify({ children: [child("zoe", earned)], removed: {} });
    const a = app();

    expect((await a.fetch(new Request(url))).status).toBe(404);

    const created = await a.fetch(
      new Request(url, {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: body(10),
      })
    );
    expect(created.status).toBe(200);
    const etag = created.headers.get("etag");
    expect(etag).toBe('"1"');

    const pulled = await a.fetch(new Request(url));
    expect(pulled.status).toBe(200);
    expect(pulled.headers.get("etag")).toBe('"1"');

    const stale = await a.fetch(
      new Request(url, {
        method: "PUT",
        headers: { "content-type": "application/json", "if-match": '"999"' },
        body: body(20),
      })
    );
    expect(stale.status).toBe(412);
  });
});

/** Exported so a reader can see the exception type is real, not constructed. */
export const REAL_CANCELLATION = TransactionCanceledException;
