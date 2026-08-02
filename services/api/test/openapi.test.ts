import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { InMemoryHouseholdStore } from "../src/household/store.js";
import { InMemoryTelemetrySink } from "../src/telemetry/sink.js";
import { InMemoryCodeStore } from "../src/codes/store.js";

/* -------------------------------------------------------------------------- */
/* The generated spec is a deliverable, not a debug page.                      */
/*                                                                             */
/* The two shipped clients are hand-written against the frozen wire format and */
/* do not read this. A future Android port will, which means a schema that     */
/* cannot be rendered into OpenAPI is a defect nobody would otherwise notice   */
/* until the day someone needs it.                                            */
/* -------------------------------------------------------------------------- */

function app(openapi = true) {
  return buildApp({
    households: new InMemoryHouseholdStore(),
    telemetry: new InMemoryTelemetrySink(),
    codes: new InMemoryCodeStore(),
    openapi,
  });
}

describe("openapi", () => {
  it("renders every route into a document", async () => {
    const res = await app().fetch(new Request("http://x/openapi.json"));
    expect(res.status).toBe(200);

    const doc = (await res.json()) as { paths: Record<string, Record<string, unknown>> };
    expect(Object.keys(doc.paths).sort()).toEqual([
      "/entitlement/{id}",
      "/errors",
      "/events",
      "/household/{id}",
      "/redeem",
    ]);
    expect(doc.paths["/household/{id}"]).toHaveProperty("get");
    expect(doc.paths["/household/{id}"]).toHaveProperty("put");
  });

  it("documents the conflict, because a client that ignores it will lose data", async () => {
    const res = await app().fetch(new Request("http://x/openapi.json"));
    const doc = (await res.json()) as {
      paths: { "/household/{id}": { put: { responses: Record<string, unknown> } } };
    };
    expect(Object.keys(doc.paths["/household/{id}"].put.responses)).toContain("412");
  });

  it("is absent unless asked for", async () => {
    const res = await app(false).fetch(new Request("http://x/openapi.json"));
    expect(res.status).toBe(404);
  });
});
