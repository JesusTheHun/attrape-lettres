import { OpenAPIHono } from "@hono/zod-openapi";

import { householdRoutes } from "./household/routes.js";
import type { HouseholdStore } from "./household/store.js";
import { telemetryRoutes } from "./telemetry/routes.js";
import type { TelemetrySink } from "./telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The app, as a pure function of its dependencies.                            */
/*                                                                             */
/* No env reads, no pool, no `listen` — `server.ts` does all three. That is    */
/* what lets every route test run against real routing and real validation     */
/* with an in-memory store, in milliseconds, with no database.                 */
/*                                                                             */
/* Household sync and telemetry are separate modules that happen to share a    */
/* deploy. They share nothing else: no types, no store, no middleware. If the  */
/* telemetry write pattern ever justifies its own service, it moves out with   */
/* its directory.                                                             */
/* -------------------------------------------------------------------------- */

export interface Deps {
  households: HouseholdStore;
  telemetry: TelemetrySink;
  /** Serve the generated OpenAPI document. The native clients are hand-written
   *  against the frozen wire format, but an Android port will want this. */
  openapi?: boolean;
}

/** The body cap. A household of 64 children is a few hundred KB; 2 MB is slack. */
export const MAX_BODY_BYTES = 2 * 1024 * 1024;

export function buildApp(deps: Deps): OpenAPIHono {
  const app = new OpenAPIHono({
    // One shape for every validation failure, and — this is the point — a body
    // that never echoes what was sent. Reflecting a rejected payload back is
    // how a server that holds no personal data starts logging some.
    defaultHook: (result, c) => {
      if (!result.success) return c.json({ error: "invalid" }, 400);
    },
  });

  app.use("*", async (c, next) => {
    const declared = Number(c.req.header("content-length") ?? 0);
    if (declared > MAX_BODY_BYTES) return c.json({ error: "too large" }, 413);
    await next();
  });

  // No CORS by default. The clients are native apps and a self-hosted PWA on
  // one origin; a permissive default would hand any page on the internet a
  // write endpoint. Add the one origin explicitly when the backoffice lands.
  app.get("/health", (c) => c.json({ ok: true }));

  app.route("/", householdRoutes(deps.households));
  app.route("/", telemetryRoutes(deps.telemetry));

  if (deps.openapi) {
    app.doc("/openapi.json", {
      openapi: "3.0.0",
      info: { version: "1.0.0", title: "Attrape-Lettres API" },
    });
  }

  return app;
}
