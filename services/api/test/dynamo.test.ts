import { TransactionCanceledException } from "@aws-sdk/client-dynamodb";
import { QueryCommand, TransactWriteCommand } from "@aws-sdk/lib-dynamodb";
import { describe, expect, it } from "vitest";

import {
  DynamoHouseholdStore,
  ROOT_SK,
  childSk,
  fromItems,
  toItems,
  type DocumentClientLike,
  type HouseholdItem,
} from "../src/household/dynamo.js";
import type { WireChild, WireRoster } from "../src/household/wire.js";

/* -------------------------------------------------------------------------- */
/* The sharding is the risky part, so it is tested as pure functions rather    */
/* than through a hand-written fake DynamoDB — a fake would be a second        */
/* implementation of the semantics being checked, and it would be wrong in the */
/* same places.                                                                */
/*                                                                             */
/* What a stub IS used for is the three things no pure function can express:   */
/* paging, the mapping from a cancelled transaction to a 412, and the shape of */
/* the transaction itself.                                                     */
/* -------------------------------------------------------------------------- */

const TABLE = "households";

function child(id: string, touchedAt = 1_700_000_000_000): WireChild {
  return {
    id,
    touchedAt,
    profile: {
      chosen: true,
      current: "cat",
      currentRev: { at: touchedAt, by: "device-a" },
      species: {
        cat: {
          config: { species: "cat", stage: 2, colors: { fur: "#eee" }, styles: {}, accessories: [] },
          owned: ["hat"],
          rev: { at: touchedAt, by: "device-a" },
        },
      },
      stars: { earned: { "device-a": 40 }, spent: { "device-b": 8 } },
      clears: { "first-letter:0": { "device-a": 3 } },
    },
  };
}

describe("decompose and compose", () => {
  it("round-trips a roster byte for byte, children in the order they were sent", () => {
    const roster: WireRoster = {
      // Deliberately NOT alphabetical: a Query returns sort-key order, so this
      // is the case that catches a family's roster being silently re-ordered.
      children: [child("zoe"), child("adam"), child("mia")],
      removed: { gone: 1_699_000_000_000 },
    };

    const back = fromItems(toItems("h_1", roster, 7));

    expect(back).not.toBeNull();
    expect(back!.rev).toBe(7);
    expect(back!.roster).toEqual(roster);
    expect(back!.roster.children.map((c) => c.id)).toEqual(["zoe", "adam", "mia"]);
  });

  it("puts the root first and one sidecar per child, all in one partition", () => {
    const items = toItems("h_1", { children: [child("zoe"), child("adam")], removed: {} }, 1);

    expect(items).toHaveLength(3);
    expect(items.map((i) => i.sk)).toEqual([ROOT_SK, childSk("zoe"), childSk("adam")]);
    expect(new Set(items.map((i) => i.pk))).toEqual(new Set(["h_1"]));
    // "#" sorts before "c", so a Query returns the root ahead of the children.
    expect(ROOT_SK < childSk("adam")).toBe(true);
  });

  it("keeps each child in its own item, so the 400 KB ceiling is per child", () => {
    const items = toItems("h_1", { children: [child("zoe"), child("adam")], removed: {} }, 1);
    const [root, zoe, adam] = items;

    // No sidecar carries a sibling's data, so one child's growth cannot push
    // another child's household over the 400 KB item ceiling.
    expect(JSON.stringify(zoe)).not.toContain("adam");
    expect(JSON.stringify(adam)).not.toContain("zoe");
    // And the root carries bookkeeping only — no profile rides along with it.
    expect(JSON.stringify(root)).not.toContain("stars");
    expect(Object.keys(root!)).toEqual(["pk", "sk", "rev", "removed", "order"]);
  });

  it("returns a tombstoned child rather than filtering it", () => {
    // A tombstone does NOT delete: mergeRoster resurrects a child whose
    // touchedAt is later than the tombstone. Filtering here would be a merge
    // rule living on the server, and it would make that resurrection
    // impossible — the sidecar would already be gone.
    const roster: WireRoster = { children: [child("zoe", 500)], removed: { zoe: 400 } };

    const back = fromItems(toItems("h_1", roster, 3));

    expect(back!.roster.children.map((c) => c.id)).toEqual(["zoe"]);
    expect(back!.roster.removed).toEqual({ zoe: 400 });
  });

  it("keeps a sidecar that the order list has forgotten", () => {
    const items = toItems("h_1", { children: [child("zoe")], removed: {} }, 2);
    const orphaned: HouseholdItem[] = [
      ...items,
      { pk: "h_1", sk: childSk("lost"), touchedAt: 9, profile: child("lost").profile },
    ];

    const back = fromItems(orphaned);

    // If bookkeeping and data ever disagree, the data wins. A child is stars.
    expect(back!.roster.children.map((c) => c.id)).toEqual(["zoe", "lost"]);
  });

  it("is null when there is no root, however many sidecars are lying around", () => {
    expect(fromItems([])).toBeNull();
    expect(fromItems([{ pk: "h_1", sk: childSk("zoe"), touchedAt: 1, profile: child("zoe").profile }])).toBeNull();
  });

  it("survives a roster that is nothing but tombstones", () => {
    const roster: WireRoster = { children: [], removed: { a: 1, b: 2 } };
    const items = toItems("h_1", roster, 4);

    expect(items).toHaveLength(1);
    expect(fromItems(items)!.roster).toEqual(roster);
  });
});

/* -- the parts a pure function cannot express ------------------------------ */

class StubClient implements DocumentClientLike {
  readonly sent: (QueryCommand | TransactWriteCommand)[] = [];
  constructor(private readonly reply: (n: number) => unknown) {}
  async send(command: QueryCommand | TransactWriteCommand): Promise<unknown> {
    this.sent.push(command);
    const result = this.reply(this.sent.length);
    if (result instanceof Error) throw result;
    return result;
  }
}

function cancelled(...codes: string[]): TransactionCanceledException {
  return new TransactionCanceledException({
    message: "cancelled",
    $metadata: {},
    CancellationReasons: codes.map((Code) => ({ Code })),
  });
}

describe("the store against a stubbed client", () => {
  it("follows every page of the query, so a big family does not lose a sibling", async () => {
    const [root, zoe] = toItems("h_1", { children: [child("zoe"), child("adam")], removed: {} }, 5);
    const adam = toItems("h_1", { children: [child("adam")], removed: {} }, 5)[1]!;
    const client = new StubClient((n) =>
      n === 1
        ? { Items: [root, zoe], LastEvaluatedKey: { pk: "h_1", sk: zoe!.sk } }
        : { Items: [adam] }
    );

    const found = await new DynamoHouseholdStore(client, TABLE).read("h_1");

    expect(client.sent).toHaveLength(2);
    expect(found!.etag).toBe('"5"');
    expect(found!.roster.children.map((c) => c.id)).toEqual(["zoe", "adam"]);
  });

  it("reads consistently, or a pull can miss the push that preceded it", async () => {
    const client = new StubClient(() => ({ Items: [] }));
    await new DynamoHouseholdStore(client, TABLE).read("h_1");

    expect((client.sent[0] as QueryCommand).input.ConsistentRead).toBe(true);
  });

  it("writes the root and every sidecar in ONE transaction", async () => {
    const client = new StubClient(() => ({}));
    const store = new DynamoHouseholdStore(client, TABLE);

    const result = await store.write("h_1", { children: [child("zoe"), child("adam")], removed: {} }, '"7"');

    expect(result).toEqual({ ok: true, etag: '"8"' });
    expect(client.sent).toHaveLength(1);
    const items = (client.sent[0] as TransactWriteCommand).input.TransactItems!;
    expect(items).toHaveLength(3);
    // The precondition sits on the root and therefore governs the sidecars too.
    // Writing children outside it would let a losing push overwrite a sidecar
    // with a merge built on a superseded revision: the winning revision would
    // then point at a child missing the other device's newest stars.
    expect(items[0]!.Put!.ConditionExpression).toBe("#rev = :expected");
    expect(items[0]!.Put!.ExpressionAttributeValues).toEqual({ ":expected": 7 });
    expect(items[1]!.Put!.ConditionExpression).toBeUndefined();
  });

  it("creates only when the client claims the household does not exist", async () => {
    const client = new StubClient(() => ({}));

    const result = await new DynamoHouseholdStore(client, TABLE).write(
      "h_1",
      { children: [], removed: {} },
      null
    );

    expect(result).toEqual({ ok: true, etag: '"1"' });
    const put = (client.sent[0] as TransactWriteCommand).input.TransactItems![0]!.Put!;
    expect(put.ConditionExpression).toBe("attribute_not_exists(#pk)");
    // An unused name is a ValidationException, so the two branches carry only
    // the names their own expression references.
    expect(put.ExpressionAttributeNames).toEqual({ "#pk": "pk" });
    expect(put.ExpressionAttributeValues).toBeUndefined();
  });

  it("turns a failed precondition into a conflict, not an error", async () => {
    const client = new StubClient(() => cancelled("ConditionalCheckFailed", "None"));

    const result = await new DynamoHouseholdStore(client, TABLE).write(
      "h_1",
      { children: [child("zoe")], removed: {} },
      '"7"'
    );

    expect(result).toEqual({ ok: false, conflict: true });
  });

  it("treats two colliding transactions as a conflict, because re-pull and retry is right", async () => {
    const client = new StubClient(() => cancelled("TransactionConflict"));

    const result = await new DynamoHouseholdStore(client, TABLE).write(
      "h_1",
      { children: [], removed: {} },
      '"1"'
    );

    expect(result).toEqual({ ok: false, conflict: true });
  });

  it("does NOT hide a validation fault behind a 412", async () => {
    // 412 is ordinary traffic that both clients retry forever. An oversized item
    // or too many items has to stay loud, or the one failure mode this design
    // cannot see would become invisible as well.
    const client = new StubClient(() => cancelled("ValidationError", "ItemSizeTooLarge"));

    await expect(
      new DynamoHouseholdStore(client, TABLE).write("h_1", { children: [], removed: {} }, '"1"')
    ).rejects.toBeInstanceOf(TransactionCanceledException);
  });

  it("refuses a malformed etag without ever reaching DynamoDB", async () => {
    const client = new StubClient(() => ({}));

    const result = await new DynamoHouseholdStore(client, TABLE).write(
      "h_1",
      { children: [], removed: {} },
      "not-an-etag"
    );

    expect(result).toEqual({ ok: false, conflict: true });
    expect(client.sent).toHaveLength(0);
  });
});
