import { OpenAPIHono } from "@hono/zod-openapi";

import { codeRoutes } from "./codes/routes.js";
import type { CodeStore } from "./codes/store.js";
import { householdRoutes } from "./household/routes.js";
import type { HouseholdStore } from "./household/store.js";
import { CHILD_TOO_LARGE, MAX_CHILD_BYTES } from "./household/wire.js";
import { householdRef, log, safePath } from "./log.js";
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
  /** Redemption codes and the grants they produce. Its own table and its own
   *  module: entitlement must not touch the sync path (see `codes/dynamo.ts`). */
  codes: CodeStore;
  /** Serve the generated OpenAPI document. The native clients are hand-written
   *  against the frozen wire format, but an Android port will want this. */
  openapi?: boolean;
}

/**
 * The body cap.
 *
 * A household of 64 children is a few hundred KB; 2 MB is slack. It is also
 * what keeps a legal push inside DynamoDB's 4 MB transaction total, since 64
 * children at `MAX_CHILD_BYTES` each would not fit — see `wireRoster`.
 *
 * Enforced from `content-length`, which every client here sets (both `fetch`
 * with a string body and `URLSession` with `httpBody` do). A chunked request
 * declares nothing and slips past this; in production the Lambda Function URL
 * refuses anything over 6 MB before this code runs, so the only place that is
 * genuinely unbounded is `pnpm dev` on a laptop.
 */
export const MAX_BODY_BYTES = 2 * 1024 * 1024;

export function buildApp(deps: Deps): OpenAPIHono {
  const app = new OpenAPIHono({
    // One shape for every validation failure, and — this is the point — a body
    // that never echoes what was sent. Reflecting a rejected payload back is
    // how a server that holds no personal data starts logging some.
    defaultHook: (result, c) => {
      if (result.success) return;

      // One validation failure is not a misbehaving client. A child over
      // `MAX_CHILD_BYTES` is a real family whose sync has just stopped for
      // good, with nothing on their phone to say so — so it gets its own event
      // at error level, and an alarm. Everything else is somebody posting
      // nonsense at a public endpoint, which is expected traffic.
      const oversized = result.error.issues.filter(
        (issue) => issue.message === CHILD_TOO_LARGE
      );
      if (oversized.length > 0) {
        log("error", "household.oversized", {
          household: householdRef(c.req.param("id") ?? ""),
          // Which child, by position. The id would be more useful and is not
          // ours to write down.
          children: oversized.map((issue) => issue.path[1]).filter((i) => typeof i === "number"),
          limit: MAX_CHILD_BYTES,
        });
        return c.json({ error: "invalid" }, 400);
      }

      // The log gets more than the client does, but still no values: a Zod
      // issue carries `received` on some codes, and this service does not write
      // down what it refused to store. Paths are scrubbed by `safePath` because
      // they walk into record keys, and in this schema those keys are device
      // ids. Five issues is enough to see the shape of a broken client.
      log("warn", "request.invalid", {
        route: c.req.routePath,
        issues: result.error.issues
          .slice(0, 5)
          .map((issue) => ({ path: safePath(issue.path), code: issue.code })),
      });
      return c.json({ error: "invalid" }, 400);
    },
  });

  // First, so it wraps everything below including the 413 and any 500.
  app.use("*", async (c, next) => {
    const started = Date.now();
    await next();
    log(c.res.status >= 500 ? "error" : "info", "request", {
      method: c.req.method,
      // The ROUTE, never `c.req.path`: the path of a household request contains
      // the household id, which is the credential. `routePath` is the
      // registered template — `/household/:id` — and unmatched requests report
      // `/*`, so nothing a caller controls reaches the log from here.
      route: c.req.routePath,
      status: c.res.status,
      ms: Date.now() - started,
    });
  });

  // An unhandled throw is a store outage nine times out of ten, and both
  // clients swallow the 500 it produces. Without this it would be Hono's
  // default handler, which prints a stack and nothing alarmable.
  app.onError((error, c) => {
    log("error", "request.failed", {
      route: c.req.routePath,
      method: c.req.method,
      // Name and message only. AWS SDK faults describe the call, not the item,
      // so this says "ProvisionedThroughputExceededException" and not what was
      // in the document.
      name: error.name,
      message: error.message,
    });
    return c.json({ error: "internal" }, 500);
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
  app.route("/", codeRoutes(deps.codes));

  if (deps.openapi) {
    app.doc("/openapi.json", {
      openapi: "3.0.0",
      info: { version: "1.0.0", title: "Attrape-Lettres API" },
    });
  }

  return app;
}
