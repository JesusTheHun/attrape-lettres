import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import {
  ALPHABET,
  CODE_LENGTH,
  checkSymbol,
  codeHash,
  format,
  isWellFormed,
  mint,
  normalise,
} from "../src/codes/code.js";
import { InMemoryCodeStore } from "../src/codes/store.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import { captureLogs } from "../src/log.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* Redemption, from the server's side.                                         */
/*                                                                             */
/* Two things are asserted here that nothing else can catch.                   */
/*                                                                             */
/*  1. THE CODE FORMAT, which is implemented TWICE — here and in Swift, in     */
/*     `apps/game-ios/Sources/ALCore/Licensing/RedemptionCode.swift`. The       */
/*     vectors below are duplicated verbatim in `RedemptionCodeTests.swift`.    */
/*     If the two checksums drift, every code we mint is rejected on the device */
/*     before it is ever sent, which looks exactly like "the codes don't work"  */
/*     and appears in no log on either side.                                    */
/*                                                                             */
/*  2. THAT ONE CODE UNLOCKS ONE FAMILY. Redemption is a conditional            */
/*     transaction precisely because the two failure directions are a burnt     */
/*     code and an infinite one, and neither is visible from a device.          */
/* -------------------------------------------------------------------------- */

const HOUSEHOLD = "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f";
const OTHER = "9a0e9b1a-2c3d-4e5f-8a7b-6c5d4e3f2a1b";

/**
 * SHARED WITH SWIFT. `apps/game-ios/…/Tests/ALCoreTests/RedemptionCodeTests.swift`
 * asserts the same eleven payloads produce the same check symbols. Changing one
 * side without the other is the drift this exists to catch.
 */
const VECTORS: [string, string][] = [
  ["00000000000", "0"],
  ["00000000001", "B"],
  ["10000000000", "1"],
  ["ZZZZZZZZZZZ", "Y"],
  ["7FQ4M2XB9KD", "B"],
  ["ABCDEFGHJKM", "C"],
  ["0123456789A", "R"],
];

function app(codes = new InMemoryCodeStore(), now = () => 1_700_000_000_000) {
  void now;
  return buildApp({
    households: new InMemoryHouseholdStore(),
    telemetry: new InMemoryTelemetrySink(),
    codes,
  });
}

function post(body: unknown) {
  return new Request("http://x/redeem", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

/**
 * Put one code in the store and return it.
 *
 * `symbol` picks WHICH code: `mint` is deterministic given its random source, so
 * two seeds with the same symbol are the same code — which is a trap worth
 * naming, because a test meaning "two different codes" and getting one twice
 * fails in a way that looks like a redemption bug.
 */
async function seed(
  store: InMemoryCodeStore,
  over: Partial<Parameters<InMemoryCodeStore["put"]>[0]> = {},
  symbol = 7
) {
  const code = mint(() => symbol);
  await store.put({
    hash: codeHash(code),
    kind: "unlock",
    maxRedemptions: 1,
    redeemed: 0,
    expiresAt: null,
    createdAt: 0,
    ...over,
  });
  return code;
}

describe("the code format", () => {
  it("uses Crockford base32 — 32 symbols, no I, L, O or U", () => {
    expect(ALPHABET).toHaveLength(32);
    expect(new Set(ALPHABET).size).toBe(32);
    for (const banned of ["I", "L", "O", "U"]) expect(ALPHABET).not.toContain(banned);
  });

  it("produces the check symbols Swift produces", () => {
    for (const [payload, expected] of VECTORS) {
      expect(payload).toHaveLength(CODE_LENGTH - 1);
      expect(checkSymbol(payload)).toBe(expected);
    }
  });

  it("maps the confusable characters rather than rejecting them", () => {
    const code = mint(() => 3);
    // Someone reading « O » where we printed « 0 », and « I »/« l » for « 1 ».
    const misread = code.replace(/0/g, "O").replace(/1/g, "I");
    expect(normalise(misread)).toBe(code);
    expect(isWellFormed(normalise(misread)!)).toBe(true);
  });

  it("treats separators as decoration", () => {
    const code = mint(() => 11);
    expect(normalise(format(code))).toBe(code);
    expect(normalise(code.toLowerCase())).toBe(code);
    expect(normalise(` ${code.slice(0, 4)} ${code.slice(4, 8)}\t${code.slice(8)} `)).toBe(code);
  });

  it("prints as XXXX-XXXX-XXXX", () => {
    expect(format("7FQ4M2XB9KDH")).toBe("7FQ4-M2XB-9KDH");
  });

  it("rejects a single-symbol typo and an adjacent transposition", () => {
    const code = mint(() => 5);
    for (let i = 0; i < code.length; i++) {
      // Advance one symbol at position i.
      const wrong = ALPHABET[(ALPHABET.indexOf(code[i]!) + 1) % 32]!;
      const typo = code.slice(0, i) + wrong + code.slice(i + 1);
      expect(isWellFormed(typo)).toBe(false);
    }
    const swapped = "7FQ4M2XB9KDH";
    if (isWellFormed(swapped)) {
      const transposed = "F7Q4M2XB9KDH";
      expect(isWellFormed(transposed)).toBe(false);
    }
  });

  it("mints codes that validate", () => {
    for (let seed = 0; seed < 32; seed++) {
      const code = mint(() => seed);
      expect(code).toHaveLength(CODE_LENGTH);
      expect(isWellFormed(code)).toBe(true);
    }
  });

  it("hashes the normalised form, so both spellings are one row", () => {
    const code = mint(() => 9);
    expect(codeHash(normalise(format(code))!)).toBe(codeHash(code));
    expect(codeHash(code)).toHaveLength(64);
  });
});

describe("POST /redeem", () => {
  it("unlocks a household", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes);

    const res = await app(codes).fetch(post({ code: format(code), household: HOUSEHOLD }));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ kind: "unlock" });
  });

  it("refuses a malformed code without touching the store", async () => {
    // A store that throws on any access: if the checksum gate leaks, this 500s.
    const exploding = {
      grant: async () => {
        throw new Error("must not be reached");
      },
      redeem: async () => {
        throw new Error("must not be reached");
      },
      put: async () => {
        throw new Error("must not be reached");
      },
    };
    const { restore } = captureLogs();
    const res = await buildApp({
      households: new InMemoryHouseholdStore(),
      telemetry: new InMemoryTelemetrySink(),
      codes: exploding,
    }).fetch(post({ code: "AAAA-AAAA-AAAA", household: HOUSEHOLD }));
    restore();
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "invalid" });
  });

  it("404s an unknown code", async () => {
    const codes = new InMemoryCodeStore();
    const unknown = mint(() => 13);
    const res = await app(codes).fetch(post({ code: unknown, household: HOUSEHOLD }));
    expect(res.status).toBe(404);
  });

  it("409s once every use is spent", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes, { maxRedemptions: 1 });
    const server = app(codes);

    expect((await server.fetch(post({ code, household: HOUSEHOLD }))).status).toBe(200);
    const second = await server.fetch(post({ code, household: OTHER }));
    expect(second.status).toBe(409);
    expect(await second.json()).toEqual({ error: "exhausted" });
  });

  it("410s a lapsed code", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes, { expiresAt: 1 });
    const res = await app(codes).fetch(post({ code, household: HOUSEHOLD }));
    expect(res.status).toBe(410);
  });

  it("a multi-use code unlocks several families", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes, { maxRedemptions: 2 });
    const server = app(codes);
    expect((await server.fetch(post({ code, household: HOUSEHOLD }))).status).toBe(200);
    expect((await server.fetch(post({ code, household: OTHER }))).status).toBe(200);
    expect((await server.fetch(post({ code, household: "third-household-id" }))).status).toBe(409);
  });

  /// A parent tapping « Valider » twice, or reinstalling and trying again.
  it("redeeming twice is idempotent and spends only one use", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes, { maxRedemptions: 2 });
    const server = app(codes);

    const first = await server.fetch(post({ code, household: HOUSEHOLD }));
    const again = await server.fetch(post({ code, household: HOUSEHOLD }));
    expect(first.status).toBe(200);
    expect(again.status).toBe(200);
    // The same grant, not a second one with a later stamp.
    expect(await again.json()).toEqual(await first.json());

    // Two taps spent ONE use: the second family still gets the other.
    expect((await server.fetch(post({ code, household: OTHER }))).status).toBe(200);
  });

  it("a second, different code on an unlocked family changes nothing", async () => {
    const codes = new InMemoryCodeStore();
    const first = await seed(codes, { maxRedemptions: 1 }, 7);
    const second = await seed(codes, { maxRedemptions: 1 }, 11);
    expect(first).not.toBe(second);
    const server = app(codes);

    await server.fetch(post({ code: first, household: HOUSEHOLD }));
    expect((await server.fetch(post({ code: second, household: HOUSEHOLD }))).status).toBe(200);
    // The second code was NOT spent — another family can still use it.
    expect((await server.fetch(post({ code: second, household: OTHER }))).status).toBe(200);
  });

  it("rejects a body with anything extra in it", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes);
    const res = await app(codes).fetch(
      post({ code, household: HOUSEHOLD, email: "parent@example.com" })
    );
    expect(res.status).toBe(400);
  });

  it("never writes the code or the household id to the log", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes);
    const { records, restore } = captureLogs();
    await app(codes).fetch(post({ code, household: HOUSEHOLD }));
    restore();

    const text = JSON.stringify(records);
    expect(text).not.toContain(code);
    expect(text).not.toContain(HOUSEHOLD);
    expect(text).toContain("codes.redeemed");
  });
});

describe("GET /entitlement/{id}", () => {
  it("404s a household that has redeemed nothing", async () => {
    const res = await app().fetch(new Request(`http://x/entitlement/${HOUSEHOLD}`));
    expect(res.status).toBe(404);
  });

  it("returns the grant after a redemption", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes);
    const server = app(codes);
    await server.fetch(post({ code, household: HOUSEHOLD }));

    const res = await server.fetch(new Request(`http://x/entitlement/${HOUSEHOLD}`));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ kind: "unlock" });
  });

  it("one family's grant is not another's", async () => {
    const codes = new InMemoryCodeStore();
    const code = await seed(codes);
    const server = app(codes);
    await server.fetch(post({ code, household: HOUSEHOLD }));

    expect((await server.fetch(new Request(`http://x/entitlement/${OTHER}`))).status).toBe(404);
  });
});
