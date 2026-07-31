import { TransactionCanceledException } from "@aws-sdk/client-dynamodb";
import { QueryCommand, TransactWriteCommand } from "@aws-sdk/lib-dynamodb";

import type { HouseholdStore, StoredHousehold, WriteResult } from "./store.js";
import { etagOf, revisionOf } from "./store.js";
import type { WireChild, WireRoster } from "./wire.js";

/* -------------------------------------------------------------------------- */
/* DynamoDB household store: one root item plus one sidecar per child.         */
/*                                                                             */
/* THE SHARDING IS INVISIBLE ON THE WIRE. Two shipped clients pull one document */
/* with one ETag and push it back with one `If-Match`; that contract is frozen  */
/* and none of what follows may leak into it. Everything here exists to keep    */
/* that promise while not storing a family as a single blob.                    */
/*                                                                             */
/*   pk = <householdId>   sk = "#root"          rev, removed, order            */
/*   pk = <householdId>   sk = "child#<id>"     touchedAt, profile             */
/*                                                                             */
/* One partition per household, so a pull is still ONE round trip: a Query on   */
/* the partition key returns the root and every child together, and "#" sorts   */
/* before "c" so the root arrives first. Sharding across partitions would have  */
/* bought scatter-gather and nothing else.                                      */
/*                                                                             */
/* WHY SHARD AT ALL. DynamoDB caps one item at 400 KB, and the household        */
/* document is the only unbounded thing in this system: children × five mascots */
/* × up to five hundred owned accessories × a per-device clear counter for      */
/* every exercise and level, accumulating for years. As one blob a large family */
/* approaches that ceiling; per child, each item would have to reach 400 KB on  */
/* its own. Crossing it fails the push, and both clients swallow the failure —  */
/* the family would simply stop syncing and nobody would be told.               */
/*                                                                             */
/* WHY THE WRITE IS A TRANSACTION, not a loop of puts. Writing children first   */
/* and the root last looks equivalent and silently loses stars: a push whose    */
/* root CAS fails has already overwritten a sidecar with a merge built on an    */
/* older revision, so the winning revision now points at a child document that  */
/* is missing the other device's newest play. TransactWriteItems makes the      */
/* root's precondition govern every sidecar in the same push. It costs double   */
/* write units, which at this volume is a rounding error on a rounding error.   */
/* -------------------------------------------------------------------------- */

export const ROOT_SK = "#root";
const CHILD_PREFIX = "child#";

/** Sort key for a child sidecar. */
export function childSk(childId: string): string {
  return CHILD_PREFIX + childId;
}

/**
 * A DynamoDB transaction takes at most 100 items, and a push writes one root
 * plus one item per child. `wireRoster` caps `children` at 64, which is what
 * keeps a legal payload under the limit — the two numbers are coupled, and
 * raising the schema cap above 99 would start rejecting valid rosters.
 */
export const MAX_TRANSACT_ITEMS = 100;

export interface RootItem {
  pk: string;
  sk: string;
  /** The integer behind the wire ETag. `etagOf(rev)` is what the client sees. */
  rev: number;
  /** childId → when it was deleted. Kept whole on the root; it is small and it is authoritative. */
  removed: Record<string, number>;
  /**
   * The order the client sent its children in.
   *
   * A Query returns items in sort-key order, which would quietly re-alphabetise
   * a family's roster on the first sync. Order is observable — `mergeRoster`
   * builds its result from an insertion-ordered map — so it is stored rather
   * than recomputed, and a pull returns exactly the array that was pushed.
   */
  order: string[];
}

export interface ChildItem {
  pk: string;
  sk: string;
  touchedAt: number;
  profile: WireChild["profile"];
}

export type HouseholdItem = RootItem | ChildItem;

function isRoot(item: HouseholdItem): item is RootItem {
  return item.sk === ROOT_SK;
}

/** Decompose a pushed roster into the items one transaction will write. */
export function toItems(id: string, roster: WireRoster, rev: number): HouseholdItem[] {
  const root: RootItem = {
    pk: id,
    sk: ROOT_SK,
    rev,
    removed: roster.removed,
    order: roster.children.map((c) => c.id),
  };
  const children: ChildItem[] = roster.children.map((c) => ({
    pk: id,
    sk: childSk(c.id),
    touchedAt: c.touchedAt,
    profile: c.profile,
  }));
  return [root, ...children];
}

/**
 * Compose a pulled roster from whatever the Query returned.
 *
 * TOMBSTONED CHILDREN ARE STILL RETURNED, deliberately. A tombstone does not
 * delete: `mergeRoster` resurrects a child whose `touchedAt` is later than the
 * tombstone, because a parent tidying the roster on one phone must not erase a
 * week of play that happened on the other. Dropping those sidecars here would
 * be a merge rule living on the server — the one thing this design does not do
 * — and it would make the resurrection unrecoverable.
 *
 * A sidecar missing from `order` is appended rather than skipped. That should
 * not happen; if bookkeeping and data ever disagree, the data wins.
 */
export function fromItems(items: HouseholdItem[]): { roster: WireRoster; rev: number } | null {
  const root = items.find(isRoot);
  if (!root) return null;

  const byId = new Map<string, WireChild>();
  for (const item of items) {
    if (isRoot(item)) continue;
    const id = item.sk.slice(CHILD_PREFIX.length);
    byId.set(id, { id, touchedAt: item.touchedAt, profile: item.profile });
  }

  const children: WireChild[] = [];
  for (const id of root.order) {
    const child = byId.get(id);
    if (child) {
      children.push(child);
      byId.delete(id);
    }
  }
  for (const orphan of [...byId.keys()].sort()) children.push(byId.get(orphan)!);

  return { roster: { children, removed: root.removed }, rev: root.rev };
}

/** The slice of the document client this store uses. Narrow on purpose, so tests can stand in. */
export interface DocumentClientLike {
  send(command: QueryCommand | TransactWriteCommand): Promise<unknown>;
}

interface QueryPage {
  Items?: Record<string, unknown>[];
  LastEvaluatedKey?: Record<string, unknown>;
}

/**
 * A cancelled transaction is a 412 when the root's precondition is what failed,
 * and when two transactions collided on the same item — the client's answer to
 * both is "re-pull and retry", which is correct.
 *
 * Everything else is rethrown on purpose. A 400-class fault such as an oversized
 * item or too many items must NOT come back as 412: the clients treat 412 as
 * ordinary traffic and would retry it forever, and the failure would never
 * appear in anything anyone watches.
 */
function isConflict(error: unknown): boolean {
  if (!(error instanceof TransactionCanceledException)) return false;
  const reasons = error.CancellationReasons ?? [];
  return reasons.some(
    (r) => r.Code === "ConditionalCheckFailed" || r.Code === "TransactionConflict"
  );
}

/**
 * The biggest sidecar in a push, and roughly how many bytes it is.
 *
 * Only ever called on the failure path, so the serialisation costs nothing when
 * writes succeed. The byte count is JSON's rather than DynamoDB's own
 * accounting — close enough to point at the culprit, and deliberately not used
 * to reject anything, because rejecting a write DynamoDB would have accepted is
 * worse than the error it would have prevented.
 */
function largestChild(children: HouseholdItem[]): { sk: string; bytes: number } | null {
  let worst: { sk: string; bytes: number } | null = null;
  for (const c of children) {
    if (isRoot(c)) continue;
    const bytes = Buffer.byteLength(JSON.stringify(c), "utf8");
    if (!worst || bytes > worst.bytes) worst = { sk: c.sk, bytes };
  }
  return worst;
}

export class DynamoHouseholdStore implements HouseholdStore {
  constructor(
    private readonly client: DocumentClientLike,
    private readonly table: string
  ) {}

  async read(id: string): Promise<StoredHousehold | null> {
    const items: HouseholdItem[] = [];
    let cursor: Record<string, unknown> | undefined;

    // A Query returns at most 1 MB per page. The request body cap allows a
    // household larger than that, so paging is not optional — stopping at the
    // first page would drop children from the pull, and the device would merge
    // against a roster that is missing a sibling.
    do {
      const page = (await this.client.send(
        new QueryCommand({
          TableName: this.table,
          // Strongly consistent: a pull immediately after a push must see it,
          // or the device merges against its own stale write and pushes a
          // document built on a revision that no longer exists.
          ConsistentRead: true,
          KeyConditionExpression: "#pk = :id",
          ExpressionAttributeNames: { "#pk": "pk" },
          ExpressionAttributeValues: { ":id": id },
          ExclusiveStartKey: cursor,
        })
      )) as QueryPage;
      items.push(...((page.Items ?? []) as unknown as HouseholdItem[]));
      cursor = page.LastEvaluatedKey;
    } while (cursor);

    const found = fromItems(items);
    return found ? { roster: found.roster, etag: etagOf(found.rev) } : null;
  }

  async write(id: string, roster: WireRoster, ifMatch: string | null): Promise<WriteResult> {
    let rev: number;
    let condition: Record<string, unknown>;

    if (ifMatch === null) {
      // "I believe this household does not exist" — which is what a client says
      // after a 404. So it creates, and conflicts if something is already there.
      // Never a blind overwrite of somebody's whole roster.
      rev = 1;
      // Names are scoped to the branch that uses them: DynamoDB rejects an
      // ExpressionAttributeNames entry that no expression references.
      condition = {
        ConditionExpression: "attribute_not_exists(#pk)",
        ExpressionAttributeNames: { "#pk": "pk" },
      };
    } else {
      const expected = revisionOf(ifMatch);
      // A malformed etag is a conflict rather than a 400: "re-pull and retry" is
      // the right client response, and a 400 would be swallowed and retried
      // forever. See `revisionOf`.
      if (expected === null) return { ok: false, conflict: true };
      rev = expected + 1;
      // No `attribute_exists` needed: if the root is gone, `#rev = :expected`
      // cannot hold, so a stale etag against a deleted household is a conflict.
      condition = {
        ConditionExpression: "#rev = :expected",
        ExpressionAttributeNames: { "#rev": "rev" },
        ExpressionAttributeValues: { ":expected": expected },
      };
    }

    const [root, ...children] = toItems(id, roster, rev);

    try {
      await this.client.send(
        new TransactWriteCommand({
          TransactItems: [
            {
              Put: { TableName: this.table, Item: root, ...condition },
            },
            ...children.map((Item) => ({ Put: { TableName: this.table, Item } })),
          ],
        })
      );
    } catch (error) {
      if (isConflict(error)) return { ok: false, conflict: true };
      // This throw becomes a 500, which both clients swallow — so this line may
      // be the only trace that a family stopped syncing. Name the largest child
      // on the way out: an item over DynamoDB's 400 KB cap is the one failure
      // the schema cannot prevent (`colors` and `styles` are records with no
      // bound on how many keys they hold, and `owned` allows 500×128 characters
      // per species), and in a log it is otherwise indistinguishable from a
      // network fault.
      console.error(`household ${id}: write failed`, {
        rev,
        children: children.length,
        largest: largestChild(children),
        error,
      });
      throw error;
    }

    return { ok: true, etag: etagOf(rev) };
  }
}
