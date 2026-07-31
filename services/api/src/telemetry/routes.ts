import { OpenAPIHono, createRoute } from "@hono/zod-openapi";

import { errorReport, eventBatch } from "./schema.js";
import type { TelemetrySink } from "./sink.js";

/* -------------------------------------------------------------------------- */
/* Telemetry — two write-only routes that must never make the app wait.        */
/*                                                                             */
/* The client posts with `keepalive: true` during backgrounding and ignores    */
/* the response entirely, including errors: "telemetry must never surface to a */
/* child". So the status code here is for US, in a log, not for the client.    */
/* 204 and get out.                                                            */
/*                                                                             */
/* Kids Category guideline 1.3 is why this exists at all rather than an SDK:   */
/* no third-party analytics, no PII or device information to third parties.    */
/* The only party in this path is us. `deviceId()` is never sent — see         */
/* apps/game-web/src/device.ts — so a batch cannot be tied to a device, let    */
/* alone a person.                                                             */
/* -------------------------------------------------------------------------- */

const events = createRoute({
  method: "post",
  path: "/events",
  summary: "Record a batch of gameplay events",
  request: { body: { content: { "application/json": { schema: eventBatch } } } },
  responses: {
    204: { description: "Accepted." },
    400: { description: "Unknown event, unknown property, or a bad value." },
  },
});

const errors = createRoute({
  method: "post",
  path: "/errors",
  summary: "Record a JS error report",
  request: { body: { content: { "application/json": { schema: errorReport } } } },
  responses: {
    204: { description: "Accepted." },
    400: { description: "Malformed report." },
  },
});

export function telemetryRoutes(sink: TelemetrySink): OpenAPIHono {
  const app = new OpenAPIHono();

  app.openapi(events, async (c) => {
    // `c.req.valid("json")` is the PARSED value, not the body that arrived:
    // `.strict()` has already rejected any key that is not on the allowlist,
    // so nothing reaches the sink that the schema did not name.
    await sink.events(c.req.valid("json"));
    return c.body(null, 204);
  });

  app.openapi(errors, async (c) => {
    await sink.error(c.req.valid("json"));
    return c.body(null, 204);
  });

  return app;
}
