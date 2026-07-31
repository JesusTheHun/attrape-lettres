import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";

import type { HouseholdStore } from "./store.js";
import { householdId, wireRoster } from "./wire.js";

/* -------------------------------------------------------------------------- */
/* Household sync — two routes, and the 412 that is the whole design.          */
/*                                                                             */
/* The client loop (`syncOnce`, identical on web and iOS):                     */
/*                                                                             */
/*     pull  →  merge LOCALLY  →  push with the etag the pull returned         */
/*     412   →  pull again, merge again, retry (three times, then give up      */
/*              and try on the next resume)                                     */
/*                                                                             */
/* Never `force`. The other phone's write is somebody's stars.                 */
/*                                                                             */
/* Both clients fail SILENT: any non-2xx that is not a 404 or a 412 is thrown  */
/* away and the device keeps playing offline. That is deliberate — a child      */
/* mid-round must never wait on a network call — but it means a bug here is    */
/* invisible from the app. It surfaces as two phones quietly disagreeing.      */
/* -------------------------------------------------------------------------- */

const idParam = z.object({
  id: householdId.openapi({
    param: { name: "id", in: "path" },
    example: "5c1f0c8e-2b2a-4a1e-9a0e-9b1a2c3d4e5f",
  }),
});

const etagHeader = z.object({
  etag: z.string().openapi({ example: '"7"' }),
});

const pull = createRoute({
  method: "get",
  path: "/household/{id}",
  summary: "Read the household document",
  request: { params: idParam },
  responses: {
    200: {
      description: "The document, with the ETag to send back on the next write.",
      headers: etagHeader,
      content: { "application/json": { schema: wireRoster } },
    },
    404: {
      description:
        "No document yet. The client pushes without If-Match to create one.",
    },
  },
});

const push = createRoute({
  method: "put",
  path: "/household/{id}",
  summary: "Write the household document, conditionally",
  request: {
    params: idParam,
    headers: z.object({
      "if-match": z
        .string()
        .optional()
        .openapi({
          description:
            "The ETag from the pull this write is based on. ABSENT means " +
            "'I believe this household does not exist' — the write then " +
            "creates, and conflicts if something is already there.",
        }),
    }),
    body: { content: { "application/json": { schema: wireRoster } } },
  },
  responses: {
    200: { description: "Written.", headers: etagHeader },
    412: {
      description:
        "Someone wrote between your read and your write, or you claimed the " +
        "household did not exist and it does. Re-pull, re-merge, retry.",
    },
    400: { description: "The document is not a valid household." },
  },
});

export function householdRoutes(store: HouseholdStore): OpenAPIHono {
  const app = new OpenAPIHono();

  app.openapi(pull, async (c) => {
    const { id } = c.req.valid("param");
    const found = await store.read(id);
    if (!found) return c.body(null, 404);
    c.header("etag", found.etag);
    // `c.json` would re-serialise through the schema; the stored document is
    // already exactly what a client sent and validated, so it goes back byte
    // for byte. Round-tripping matters: `touchedAt` and the LWW `at` stamps are
    // compared for equality by the merge on the other side.
    return c.json(found.roster, 200);
  });

  app.openapi(push, async (c) => {
    const { id } = c.req.valid("param");
    const roster = c.req.valid("json");
    // Hono lower-cases header names; the web client sends `if-match` and the
    // Swift client sends `If-Match`, and both arrive here the same.
    const ifMatch = c.req.header("if-match") ?? null;

    const result = await store.write(id, roster, ifMatch);
    if (!result.ok) return c.body(null, 412);

    c.header("etag", result.etag);
    return c.body(null, 200);
  });

  return app;
}
