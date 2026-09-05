#!/usr/bin/env node
import { randomInt } from "node:crypto";

import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, PutCommand } from "@aws-sdk/lib-dynamodb";

import { codeHash, format, mint } from "../dist/src/codes/code.js";

/* -------------------------------------------------------------------------- */
/* Mint redemption codes.                                                      */
/*                                                                             */
/*     pnpm build                                                              */
/*     node scripts/mint-codes.mjs --count 20 --label presse                   */
/*     node scripts/mint-codes.mjs --count 1 --uses 30 --days 60 --label ecole */
/*     node scripts/mint-codes.mjs --count 50 --kind discount --label early    */
/*                                                                             */
/* THE CODES ARE PRINTED ONCE, HERE, AND NOWHERE ELSE. Only their sha256 goes  */
/* into DynamoDB, so this output is the only copy that will ever exist — save  */
/* it before closing the terminal. That is deliberate (`codes/code.ts`): a     */
/* database dump is then a list of hashes rather than a list of free unlocks.  */
/*                                                                             */
/* Run by a human with their own AWS credentials, never by the service. The    */
/* Lambda's role has no way to create a code; `infra/template.yaml` grants it  */
/* GetItem, PutItem and UpdateItem on this table and PutItem is conditioned on */
/* the key not existing, so a compromised function cannot mint itself one.     */
/*                                                                             */
/* --dry-run prints codes and writes nothing, which is how you check the       */
/* format before spending real rows.                                           */
/* -------------------------------------------------------------------------- */

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  if (index < 0) return fallback;
  const value = process.argv[index + 1];
  return value === undefined || value.startsWith("--") ? fallback : value;
}

const count = Number(arg("count", "10"));
/** How many households ONE code may unlock. 1 is a personal code. */
const uses = Number(arg("uses", "1"));
/** Days until the code lapses. 0 means it never does. */
const days = Number(arg("days", "0"));
/**
 * What the code buys. `unlock` gives the game away; `discount` gives only the
 * right to buy it at the early-adopter price, through in-app purchase.
 *
 * Spelled out rather than defaulted quietly, because the two are handed to
 * different people and the mistake is one-way: a batch minted `unlock` by
 * accident is a batch of free games, and a spent code cannot be revoked on a
 * device that already holds it (D61).
 */
const kind = arg("kind", "unlock");
const label = arg("label", "");
const table = arg("table", process.env.CODES_TABLE ?? "attrape-api-households-codes");
const dryRun = process.argv.includes("--dry-run");

if (!Number.isInteger(count) || count < 1 || count > 500) {
  console.error("--count must be between 1 and 500");
  process.exit(1);
}
if (!Number.isInteger(uses) || uses < 1) {
  console.error("--uses must be a positive integer");
  process.exit(1);
}
if (kind !== "unlock" && kind !== "discount") {
  console.error(`--kind must be "unlock" or "discount", not "${kind}"`);
  process.exit(1);
}

const now = Date.now();
const expiresAt = days > 0 ? now + days * 24 * 60 * 60 * 1000 : null;

// `randomInt` is the CSPRNG, and it is rejection-sampled — `Math.random()`
// would bias the alphabet and shrink the search space a guesser has to cover.
const codes = Array.from({ length: count }, () => mint((bound) => randomInt(bound)));

const client = dryRun
  ? null
  : DynamoDBDocumentClient.from(new DynamoDBClient({}), {
      marshallOptions: { removeUndefinedValues: true },
    });

for (const code of codes) {
  if (client) {
    await client.send(
      new PutCommand({
        TableName: table,
        Item: {
          pk: `code#${codeHash(code)}`,
          kind,
          maxRedemptions: uses,
          redeemed: 0,
          expiresAt,
          createdAt: now,
          ...(label ? { label } : {}),
        },
        // A hash collision would silently reset an existing code's counter.
        ConditionExpression: "attribute_not_exists(pk)",
      })
    );
  }
  console.log(format(code));
}

console.error(
  `\n${codes.length} ${kind} code(s), ${uses} use(s) each, `
    + `${expiresAt ? new Date(expiresAt).toISOString().slice(0, 10) : "no expiry"}`
    + `${label ? `, label "${label}"` : ""}`
    + `${dryRun ? " — DRY RUN, nothing written" : ` → ${table}`}`
);
console.error("Save the codes above. They cannot be recovered from the table.");

client?.destroy();
