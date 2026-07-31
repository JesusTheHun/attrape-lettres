import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { serve } from "@hono/node-server";
import postgres from "postgres";

import { buildApp } from "./app.js";
import { PostgresHouseholdStore } from "./household/postgres.js";
import { PostgresTelemetrySink } from "./telemetry/postgres.js";
import { nullTelemetrySink } from "./telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The entrypoint: the only file that reads env, opens a pool or listens.      */
/* Everything it wires together is testable without it.                        */
/* -------------------------------------------------------------------------- */

const DATABASE_URL = process.env.DATABASE_URL;
const PORT = Number(process.env.PORT ?? 8787);
/** Set to "0" on a deployment that wants sync without analytics. */
const TELEMETRY = process.env.TELEMETRY !== "0";

if (!DATABASE_URL) {
  console.error("DATABASE_URL is required");
  process.exit(1);
}

const sql = postgres(DATABASE_URL, { max: 10, prepare: true });

// Idempotent (every statement is IF NOT EXISTS), so it is safe on every boot
// and there is no separate migrate step to forget. Revisit when there is a
// second migration — an ordered runner is a twenty-line file, but writing it
// now would be a runner with nothing to run.
const schema = await readFile(
  fileURLToPath(new URL("../migrations/001_init.sql", import.meta.url)),
  "utf8"
);
await sql.unsafe(schema);

const app = buildApp({
  households: new PostgresHouseholdStore(sql),
  telemetry: TELEMETRY ? new PostgresTelemetrySink(sql) : nullTelemetrySink,
  openapi: true,
});

const server = serve({ fetch: app.fetch, port: PORT }, (info) => {
  console.log(`api listening on :${info.port}` + (TELEMETRY ? "" : " (telemetry off)"));
});

// A household PUT that is cut off mid-write costs a device one sync cycle; it
// retries on the next resume and the merge is idempotent. Still: drain first.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.close(() => {
      void sql.end({ timeout: 5 }).then(() => process.exit(0));
    });
  });
}
