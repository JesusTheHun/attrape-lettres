import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import type { HouseholdStore, WriteResult } from "../src/household/store.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import type { WireChild } from "../src/household/wire.js";
import { captureLogs, formatLine } from "../src/log.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The alarms are only as good as the strings they match.                      */
/*                                                                             */
/* `infra/template.yaml` detects every failure in this system by matching       */
/* literal substrings of the log lines `src/log.ts` writes. Nothing in either   */
/* file knows about the other, so renaming an event, reordering a field or      */
/* changing a status code leaves a stack full of alarms that will never fire    */
/* again and a dashboard that reads healthy forever.                            */
/*                                                                             */
/* So this test reads the template, pulls out every FilterPattern, drives the   */
/* real app until it has produced real log lines, and insists that each pattern */
/* still matches one of them. It is the seam between code and infrastructure,   */
/* made executable.                                                            */
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
    await buildApp({ households: broken, telemetry: new InMemoryTelemetrySink() }).fetch(
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
