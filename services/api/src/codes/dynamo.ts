import { TransactionCanceledException } from "@aws-sdk/client-dynamodb";
import { GetCommand, PutCommand, TransactWriteCommand } from "@aws-sdk/lib-dynamodb";

import { householdRef, log } from "../log.js";
import type { CodeRow, CodeStore, Grant, RedeemResult } from "./store.js";

/* -------------------------------------------------------------------------- */
/* DynamoDB redemption store. One table, two key shapes, no queries.           */
/*                                                                             */
/*   pk = "code#<sha256>"        kind, maxRedemptions, redeemed, expiresAt      */
/*   pk = "grant#<householdId>"  kind, grantedAt                                */
/*                                                                             */
/* A SEPARATE TABLE from households, not a second sort key in that one. The     */
/* household store reads its partition with a Query and treats every non-root   */
/* item as a child sidecar (`fromItems`); a grant row sitting in that partition */
/* would be parsed as a child with a garbage id and pushed back to the family's */
/* other devices. Keeping entitlement out of the sync path entirely costs one   */
/* CloudFormation resource and removes the whole class of problem.              */
/*                                                                             */
/* GRANTS ARE KEYED BY HOUSEHOLD, which is the credential model this service    */
/* already has: whoever holds the household id can read that family's roster,   */
/* and now their grant. Nothing new is exposed. There is still no account, no   */
/* e-mail address and no name anywhere in here — a row is one opaque uuid, one  */
/* word and one integer.                                                       */
/* -------------------------------------------------------------------------- */

export const CODE_PREFIX = "code#";
export const GRANT_PREFIX = "grant#";

export function codeKey(hash: string): string {
  return CODE_PREFIX + hash;
}

export function grantKey(household: string): string {
  return GRANT_PREFIX + household;
}

/** The slice of the document client this store uses. Narrow, so tests stand in. */
export interface DocumentClientLike {
  send(command: GetCommand | PutCommand | TransactWriteCommand): Promise<unknown>;
}

interface GrantItem {
  pk: string;
  kind: Grant["kind"];
  grantedAt: number;
}

/**
 * Which of the transaction's two conditions failed.
 *
 * `CancellationReasons` is positional and matches `TransactItems`, so index 0 is
 * the code's condition and index 1 is the grant's. That is the only way to tell
 * "this code is used up" from "this family is already unlocked" without a second
 * round trip, and the two need different answers.
 */
function cancellation(error: unknown): ("failed" | "ok")[] | null {
  if (!(error instanceof TransactionCanceledException)) return null;
  const reasons = error.CancellationReasons ?? [];
  return reasons.map((r) => (r.Code === "ConditionalCheckFailed" ? "failed" : "ok"));
}

export class DynamoCodeStore implements CodeStore {
  constructor(
    private readonly client: DocumentClientLike,
    private readonly table: string
  ) {}

  async grant(household: string): Promise<Grant | null> {
    const result = (await this.client.send(
      new GetCommand({
        TableName: this.table,
        Key: { pk: grantKey(household) },
        // A device that just redeemed must see its own grant on the next call,
        // or the app decides the redemption failed and shows the paywall again.
        ConsistentRead: true,
      })
    )) as { Item?: GrantItem };
    const item = result.Item;
    return item ? { kind: item.kind, grantedAt: item.grantedAt } : null;
  }

  async put(row: CodeRow): Promise<void> {
    await this.client.send(
      new PutCommand({
        TableName: this.table,
        Item: {
          pk: codeKey(row.hash),
          kind: row.kind,
          maxRedemptions: row.maxRedemptions,
          redeemed: row.redeemed,
          expiresAt: row.expiresAt,
          createdAt: row.createdAt,
          ...(row.label ? { label: row.label } : {}),
        },
        // Minting the same hash twice would reset `redeemed` to zero and hand
        // out a spent code again. Refuse.
        ConditionExpression: "attribute_not_exists(pk)",
      })
    );
  }

  async redeem(hash: string, household: string, now: number): Promise<RedeemResult> {
    // Cheap pre-flight, and the only reason it exists: the transaction below
    // cannot distinguish "no such code" from "code exhausted" — both surface as
    // a failed condition on item 0 — and a parent who mistyped needs to be told
    // that, not that the code is used up. It is a read, so it can be stale; the
    // transaction remains the authority on whether a use is actually spent.
    const row = (await this.client.send(
      new GetCommand({ TableName: this.table, Key: { pk: codeKey(hash) } })
    )) as { Item?: { expiresAt: number | null } };
    if (!row.Item) return { ok: false, reason: "unknown" };
    if (row.Item.expiresAt !== null && now >= row.Item.expiresAt) {
      return { ok: false, reason: "expired" };
    }

    try {
      await this.client.send(
        new TransactWriteCommand({
          TransactItems: [
            {
              Update: {
                TableName: this.table,
                Key: { pk: codeKey(hash) },
                UpdateExpression: "SET redeemed = redeemed + :one",
                // `attribute_exists` as well as the counter check: without it a
                // code deleted between the read above and this write would be
                // CREATED here, with `redeemed = 1` and no other attributes.
                ConditionExpression:
                  "attribute_exists(pk) AND redeemed < maxRedemptions "
                  + "AND (attribute_not_exists(expiresAt) OR expiresAt = :null OR expiresAt > :now)",
                ExpressionAttributeValues: { ":one": 1, ":now": now, ":null": null },
              },
            },
            {
              Put: {
                TableName: this.table,
                Item: { pk: grantKey(household), kind: "unlock", grantedAt: now },
                // One grant per family. This is what makes a second redemption
                // idempotent instead of wasteful.
                ConditionExpression: "attribute_not_exists(pk)",
              },
            },
          ],
        })
      );
    } catch (error) {
      const reasons = cancellation(error);
      if (reasons) {
        // The family already had a grant. Return it — succeeding costs the code
        // nothing, and the parent's answer is "you are already unlocked".
        if (reasons[1] === "failed") {
          const existing = await this.grant(household);
          if (existing) return { ok: true, grant: existing, fresh: false };
        }
        if (reasons[0] === "failed") return { ok: false, reason: "exhausted" };
      }
      // A store fault. Rethrow so it becomes a 500 and the client changes
      // nothing — never a business answer, which the client would persist.
      log("error", "codes.redeem_failed", {
        household: householdRef(household),
        name: error instanceof Error ? error.name : "unknown",
        message: error instanceof Error ? error.message : String(error),
      });
      throw error;
    }

    return { ok: true, grant: { kind: "unlock", grantedAt: now }, fresh: true };
  }
}
