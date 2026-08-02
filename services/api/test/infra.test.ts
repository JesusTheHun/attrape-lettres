import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import type { HouseholdStore, WriteResult } from "../src/household/store.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import type { WireChild } from "../src/household/wire.js";
import { captureLogs, formatLine } from "../src/log.js";
import { S3TelemetrySink } from "../src/telemetry/s3.js";
import { errorReport, eventProps } from "../src/telemetry/schema.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";
import { InMemoryCodeStore } from "../src/codes/store.js";

/* -------------------------------------------------------------------------- */
/* The seam between the code and the stack, made executable.                   */
/*                                                                             */
/* `infra/template.yaml` reaches into this service twice, by copying strings    */
/* out of it. The alarms match literal substrings of the log lines `log.ts`     */
/* writes; the Glue tables restate the shape of `telemetry/schema.ts`. Neither  */
/* side imports the other, so both drift the moment somebody renames an event   */
/* or adds a property — and both drift SILENTLY. A stale alarm simply never     */
/* fires again. A stale column is worse: the query still runs, and answers      */
/* nothing, which reads exactly like an answer.                                 */
/*                                                                             */
/* So these tests read the template as text and hold it against what the code   */
/* actually produces.                                                          */
/* -------------------------------------------------------------------------- */

const TEMPLATE = new URL("../infra/template.yaml", import.meta.url);

/**
 * Every CloudWatch filter pattern in the template, as the list of substrings it
 * requires.
 *
 * A text filter pattern is a set of space-separated terms that must ALL appear
 * in the event, and a quoted term matches its contents literally. In YAML the
 * patterns are single-quoted, so backslashes survive verbatim and the `\"` is
 * CloudWatch's own escape for a double quote inside a term.
 */
function filterPatterns(): { name: string; terms: string[] }[] {
  const yaml = readFileSync(TEMPLATE, "utf8").split("\n");
  const patterns: { name: string; terms: string[] }[] = [];
  let resource = "?";

  for (const line of yaml) {
    const declaration = /^ {2}(\w+):$/.exec(line);
    if (declaration) resource = declaration[1]!;

    const pattern = /^\s*FilterPattern:\s*'(.*)'\s*$/.exec(line);
    if (!pattern) continue;

    const terms = [...pattern[1]!.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) =>
      m[1]!.replace(/\\(.)/g, "$1")
    );
    patterns.push({ name: resource, terms });
  }
  return patterns;
}

/* -- every line the service can write, produced for real --------------------- */

function child(id: string, colors: Record<string, string> = {}): WireChild {
  return {
    id,
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
            colors: { bodyColor: "#8CF", ...colors },
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

const HOUSEHOLD = "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f";
const url = `http://x/household/${HOUSEHOLD}`;

const push = (children: WireChild[], ifMatch?: string) =>
  new Request(url, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      ...(ifMatch ? { "if-match": ifMatch } : {}),
    },
    body: JSON.stringify({ children, removed: {} }),
  });

/** Drive the app through every outcome the template alarms on. */
async function everyLine(): Promise<string[]> {
  const capture = captureLogs();
  try {
    const app = buildApp({
      households: new InMemoryHouseholdStore(),
      telemetry: new InMemoryTelemetrySink(),
      codes: new InMemoryCodeStore(),
    });

    await app.fetch(new Request(url)); // 404
    await app.fetch(push([child("kid")])); // 200, a real push
    await app.fetch(push([child("kid")], '"99"')); // 412, a conflict
    await app.fetch(push([{ ...child("kid"), touchedAt: -1 }])); // 400, nonsense

    const colors: Record<string, string> = {};
    for (let i = 0; i < 4000; i += 1) colors[`c${i}`.padEnd(60, "k")] = "#abcdef";
    await app.fetch(push([child("kid", colors)])); // 400, too big to store

    const broken: HouseholdStore = {
      async read(): Promise<never> {
        throw new Error("ResourceNotFoundException");
      },
      async write(): Promise<WriteResult> {
        throw new Error("ResourceNotFoundException");
      },
    };
    await buildApp({ households: broken, telemetry: new InMemoryTelemetrySink(), codes: new InMemoryCodeStore() }).fetch(
      new Request(url)
    ); // 500

    // The store's own failure line, which no route can produce with a double.
    const { DynamoHouseholdStore } = await import("../src/household/dynamo.js");
    const exploding = new DynamoHouseholdStore(
      {
        async send(): Promise<never> {
          throw new Error("ItemCollectionSizeLimitExceededException");
        },
      },
      "table"
    );
    await exploding.write(HOUSEHOLD, { children: [], removed: {} }, null).catch(() => undefined);

    return capture.records.map(({ level, ...fields }) => formatLine(level, fields));
  } finally {
    capture.restore();
  }
}

describe("every alarm in the template still matches a line the service writes", () => {
  it("finds the patterns at all, so a rewritten template cannot pass vacuously", () => {
    const patterns = filterPatterns();

    expect(patterns.length).toBeGreaterThanOrEqual(6);
    expect(patterns.map((p) => p.name)).toEqual([
      "ServerErrorFilter",
      "WriteFailedFilter",
      "OversizedFilter",
      "PushAttemptFilter",
      "PushConflictFilter",
      "PushSuccessFilter",
    ]);
    // Parsed to substrings, not left as raw YAML.
    expect(patterns[0]!.terms).toEqual(['"event":"request.failed"']);
    expect(patterns[4]!.terms).toEqual([
      '"event":"request"',
      '"method":"PUT"',
      '"status":412',
    ]);
  });

  it.each(filterPatterns())("$name matches", async ({ terms }) => {
    const lines = await everyLine();

    const matched = lines.filter((line) => terms.every((term) => line.includes(term)));

    expect(
      matched,
      `no log line contains all of ${JSON.stringify(terms)}.\n` +
        `The alarm that uses it will never fire again. Lines produced:\n${lines.join("\n")}`
    ).not.toHaveLength(0);
  });

  it("does not match a healthy line by accident", async () => {
    const lines = await everyLine();
    const healthy = lines.filter((l) => l.includes('"status":200'));
    const failure = filterPatterns().filter((p) =>
      ["ServerErrorFilter", "WriteFailedFilter", "OversizedFilter"].includes(p.name)
    );

    expect(healthy.length).toBeGreaterThan(0);
    for (const { name, terms } of failure) {
      for (const line of healthy) {
        expect(terms.every((t) => line.includes(t)), `${name} matched a healthy line`).toBe(false);
      }
    }
  });
});

describe("the Athena tables still describe what the service writes", () => {
  const yaml = readFileSync(TEMPLATE, "utf8");

  /** The column names inside `struct<a:string,b:int>`, in template order. */
  function structFields(struct: string): string[] {
    const inner = /^struct<(.*)>$/.exec(struct.trim())?.[1];
    if (!inner) throw new Error(`not a struct: ${struct}`);
    return inner.split(",").map((field) => field.split(":")[0]!.trim());
  }

  it("has a props column for every telemetry property, and no ghosts", () => {
    const declared = /Type:\s*(struct<[^\n]*>)/.exec(yaml)?.[1];
    expect(declared, "no props struct found in the template").toBeDefined();

    // Hive has no uppercase, and the serde folds JSON keys to match — which is
    // how `daysLeft` on the wire lands in `daysleft` here. Comparing lowercased
    // is not laxness; it is the actual matching rule.
    expect(structFields(declared!).sort()).toEqual(
      Object.keys(eventProps.shape)
        .map((k) => k.toLowerCase())
        .sort()
    );
  });

  it("has a column for every field of an error report, under the name it is queried by", () => {
    // `where` is a SQL keyword, so the serde renames it to `origin` on the way
    // in. That mapping is the reason this test cannot just compare key sets.
    const RENAMED: Record<string, string> = { where: "origin" };

    const errors = yaml.slice(yaml.indexOf("ErrorsTable:"));
    const columns = [...errors.matchAll(/- Name: (\w+)\n\s+Type: string/g)].map((m) => m[1]!);

    expect(columns).toContain("origin");
    expect(columns).not.toContain("where");
    expect(errors).toContain("mapping.origin: where");
    for (const field of Object.keys(errorReport.shape)) {
      expect(columns, `no column for errorReport.${field}`).toContain(
        RENAMED[field] ?? field.toLowerCase()
      );
    }
  });

  it("partitions both tables on the prefix the sink actually writes", async () => {
    // `dayOf` builds `events/dt=YYYY-MM-DD/…`, and partition projection only
    // works if the template's location template agrees character for character.
    const sink = new S3TelemetrySink(
      {
        async send(command: { input: { Key?: string } }) {
          keys.push(command.input.Key!);
          return {};
        },
      } as never,
      "bucket",
      () => new Date("2026-07-31T09:00:00Z")
    );
    const keys: string[] = [];

    await sink.events({ v: "1.0.0", events: [{ event: "shop_opened", props: {} }] });
    await sink.error({ v: "1.0.0", where: "boot", message: "x", stack: "y" });

    expect(keys[0]).toMatch(/^events\/dt=2026-07-31\/[0-9a-f-]{36}\.ndjson$/);
    expect(keys[1]).toMatch(/^errors\/dt=2026-07-31\/[0-9a-f-]{36}\.ndjson$/);
    expect(yaml).toContain("storage.location.template: !Sub \"s3://${TelemetryBucket}/events/dt=${!dt}/\"");
    expect(yaml).toContain("storage.location.template: !Sub \"s3://${TelemetryBucket}/errors/dt=${!dt}/\"");
    expect(yaml).toContain("projection.dt.format: yyyy-MM-dd");
  });

  it("grants the function no way to read telemetry back or delete a family", () => {
    // Append-only and overwrite-under-condition by POLICY, not merely by code.
    // A role that can GetObject is a role that can be made to hand over the
    // analytics; a role that can DeleteItem can erase a child's progress.
    //
    // Read from the policy body with the prose stripped, because the comments
    // above it name the very actions being forbidden.
    const role = yaml.slice(yaml.indexOf("  ApiRole:"), yaml.indexOf("  ApiFunction:"));
    const policy = role
      .split("\n")
      .filter((line) => !/^\s*#/.test(line))
      .join("\n");

    expect(policy).toContain("s3:PutObject");
    expect(policy).toContain("dynamodb:Query");
    expect(policy).toContain("dynamodb:PutItem");

    // Forbidden EVERYWHERE in the role, whatever the resource. Each of these
    // would let a compromised function do something no request path needs:
    // hand over the analytics, erase a child's progress, enumerate families, or
    // log somewhere nobody set a retention on.
    for (const forbidden of [
      "s3:GetObject",
      "s3:DeleteObject",
      "s3:ListBucket",
      "dynamodb:DeleteItem",
      "dynamodb:Scan",
      "logs:CreateLogGroup",
      "*:*",
    ]) {
      expect(policy, `the function's role grants ${forbidden}`).not.toContain(forbidden);
    }

    // GetItem and UpdateItem exist for the CODE table and only for it: the
    // pre-flight read, the entitlement read, and the atomic bump of `redeemed`.
    // Pointed at households they would be a way to read one family's roster a
    // row at a time and to edit a child's counters in place, so the grant is
    // asserted per policy rather than per role.
    const named = (name: string) =>
      policy.slice(policy.indexOf(`- PolicyName: ${name}`), policy.indexOf("PolicyName:", policy.indexOf(`- PolicyName: ${name}`) + 10));

    const households = named("households");
    expect(households).toContain("HouseholdTable.Arn");
    expect(households).not.toContain("dynamodb:GetItem");
    expect(households).not.toContain("dynamodb:UpdateItem");

    const codes = named("codes");
    expect(codes).toContain("CodeTable.Arn");
    expect(codes).not.toContain("HouseholdTable");
    expect(codes).not.toContain("dynamodb:Query");
  });
});

