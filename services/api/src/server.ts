import { DescribeTableCommand } from "@aws-sdk/client-dynamodb";
import { serve } from "@hono/node-server";

import { buildApp } from "./app.js";
import { ConfigError, wireFromEnv } from "./aws.js";
import { log } from "./log.js";

/* -------------------------------------------------------------------------- */
/* The long-running entrypoint: `pnpm dev`, a container, a box.                 */
/*                                                                             */
/* NOT what ships. Production is `lambda.ts` behind an HTTP API — see           */
/* `infra/template.yaml`. This stays because a service that can only run inside */
/* its own deployment is a service nobody can debug, and because `pnpm dev`     */
/* against DynamoDB Local needs no AWS account at all.                          */
/* -------------------------------------------------------------------------- */

const PORT = Number(process.env.PORT ?? 8787);

let wiring;
try {
  wiring = wireFromEnv();
} catch (error) {
  if (error instanceof ConfigError) {
    console.error(error.message);
    process.exit(1);
  }
  throw error;
}

// Fail fast rather than create. Creating the table on boot would need
// CreateTable in the running role's policy, and a service that can create its
// own store will happily create a second, empty one after a typo in
// HOUSEHOLD_TABLE — at which point every family looks brand new and nothing
// errors. The table definition is in `infra/template.yaml`.
//
// `lambda.ts` deliberately skips this: there the name arrives from a
// CloudFormation `Ref` to the table that was just created, so there is no typo
// to catch and no reason to spend a control-plane call on every cold start.
try {
  await wiring.dynamo.send(new DescribeTableCommand({ TableName: wiring.table }));
} catch (error) {
  console.error(`cannot describe DynamoDB table "${wiring.table}":`, error);
  process.exit(1);
}

const server = serve({ fetch: buildApp(wiring.deps).fetch, port: PORT }, (info) => {
  log("info", "boot", { runtime: "node", port: info.port, telemetry: wiring.telemetry });
});

// A household PUT cut off mid-write costs a device one sync cycle; it retries on
// the next resume and the merge is idempotent. Still: drain first.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.close(() => {
      wiring.close();
      process.exit(0);
    });
  });
}
