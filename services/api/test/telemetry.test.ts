import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The allowlist, tested where it actually protects the database.              */
/*                                                                             */
/* The client has the same closed lists, and `apps/game-web/src/telemetry.ts`  */
/* has its own tests for them. Those prove OUR client is well-behaved. These   */
/* prove it does not matter whether it is: the endpoint is public and          */
/* unauthenticated, so the copy that counts is this one.                       */
/* -------------------------------------------------------------------------- */

function world() {
  const telemetry = new InMemoryTelemetrySink();
  const app = buildApp({ households: new InMemoryHouseholdStore(), telemetry });
  return { app, telemetry };
}

const post = (path: string, body: unknown) =>
  new Request(`http://x${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });

describe("telemetry — events", () => {
  it("accepts a well-formed batch and answers 204", async () => {
    const { app, telemetry } = world();
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        events: [
          { event: "session_completed", props: { exercise: "first-letter", level: 1, points: 10 } },
        ],
      })
    );
    expect(res.status).toBe(204);
    expect(telemetry.batches).toHaveLength(1);
  });

  it("rejects an event that is not on the list", async () => {
    const { app, telemetry } = world();
    const res = await app.fetch(
      post("/events", { v: "0.1.0", events: [{ event: "name_entered", props: {} }] })
    );
    expect(res.status).toBe(400);
    expect(telemetry.batches).toHaveLength(0);
  });

  it("rejects a property that is not on the list — there is no free-text escape hatch", async () => {
    const { app, telemetry } = world();
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        events: [{ event: "session_completed", props: { childName: "Léa" } }],
      })
    );
    expect(res.status).toBe(400);
    expect(telemetry.batches).toHaveLength(0);
  });

  it("rejects a string where a number belongs, so `level` can never carry a name", async () => {
    const { app } = world();
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        events: [{ event: "session_completed", props: { level: "Léa" } }],
      })
    );
    expect(res.status).toBe(400);
  });

  it("rejects an exercise id that is not in the catalog", async () => {
    const { app } = world();
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        events: [{ event: "exercise_started", props: { exercise: "whatever" } }],
      })
    );
    expect(res.status).toBe(400);
  });

  it("never lets a rejected payload back out in the response", async () => {
    const { app } = world();
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        events: [{ event: "session_completed", props: { childName: "Léa" } }],
      })
    );
    expect(await res.text()).not.toContain("Léa");
  });

  it("stores only what the schema named, even when extra keys ride along", async () => {
    const { app, telemetry } = world();
    // A batch whose events are individually valid must not carry unknown
    // top-level keys either — `.strict()` all the way down.
    const res = await app.fetch(
      post("/events", {
        v: "0.1.0",
        deviceId: "d-1",
        events: [{ event: "shop_opened", props: {} }],
      })
    );
    expect(res.status).toBe(400);
    expect(telemetry.batches).toHaveLength(0);
  });
});

describe("telemetry — error reports", () => {
  it("accepts a report and answers 204", async () => {
    const { app, telemetry } = world();
    const res = await app.fetch(
      post("/errors", {
        v: "0.1.0",
        where: "window.onerror",
        message: "boom",
        stack: "at x (y.ts:1:1)",
      })
    );
    expect(res.status).toBe(204);
    expect(telemetry.errors).toHaveLength(1);
  });

  it("refuses anything beyond the four fields — no context dump", async () => {
    const { app } = world();
    const res = await app.fetch(
      post("/errors", {
        v: "0.1.0",
        where: "window.onerror",
        message: "boom",
        stack: "at x",
        roster: { children: [{ name: "Léa" }] },
      })
    );
    expect(res.status).toBe(400);
  });

  it("refuses an oversized stack rather than truncating it silently", async () => {
    const { app } = world();
    const res = await app.fetch(
      post("/errors", {
        v: "0.1.0",
        where: "w",
        message: "m",
        stack: "x".repeat(2001),
      })
    );
    expect(res.status).toBe(400);
  });
});

describe("telemetry — turned off", () => {
  it("still answers 204, because a client treats a failure as a reason to retry", async () => {
    const { nullTelemetrySink } = await import("../src/telemetry/sink.js");
    const app = buildApp({
      households: new InMemoryHouseholdStore(),
      telemetry: nullTelemetrySink,
    });
    const res = await app.fetch(
      post("/events", { v: "0.1.0", events: [{ event: "shop_opened", props: {} }] })
    );
    expect(res.status).toBe(204);
  });
});
