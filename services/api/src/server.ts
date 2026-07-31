import { serve } from "@hono/node-server";
import { DescribeTableCommand, DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";
import { S3Client } from "@aws-sdk/client-s3";

import { buildApp } from "./app.js";
import { DynamoHouseholdStore } from "./household/dynamo.js";
import { S3TelemetrySink } from "./telemetry/s3.js";
import { nullTelemetrySink } from "./telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The entrypoint: the only file that reads env, opens a client or listens.    */
/* Everything it wires together is testable without it.                        */
/* -------------------------------------------------------------------------- */

const TABLE = process.env.HOUSEHOLD_TABLE;
const BUCKET = process.env.TELEMETRY_BUCKET;
const PORT = Number(process.env.PORT ?? 8787);
/** Set to "0" on a deployment that wants sync without analytics. */
const TELEMETRY = process.env.TELEMETRY !== "0";
/** Point at DynamoDB Local for development. Unset in production. */
const DYNAMO_ENDPOINT = process.env.DYNAMO_ENDPOINT;

if (!TABLE) {
  console.error("HOUSEHOLD_TABLE is required");
  process.exit(1);
}
if (TELEMETRY && !BUCKET) {
  console.error("TELEMETRY_BUCKET is required unless TELEMETRY=0");
  process.exit(1);
}

const dynamo = new DynamoDBClient(DYNAMO_ENDPOINT ? { endpoint: DYNAMO_ENDPOINT } : {});
const documents = DynamoDBDocumentClient.from(dynamo, {
  // The clients send JSON that Zod has already validated, so there should be
  // nothing undefined to strip — but a marshalling error at 3am is a silent
  // sync outage, and this costs nothing.
  marshallOptions: { removeUndefinedValues: true },
});

// Fail fast rather than create. Creating the table on boot would need
// CreateTable in the running role's policy, and a service that can create its
// own store will happily create a second, empty one after a typo in
// HOUSEHOLD_TABLE — at which point every family looks brand new and nothing
// errors. The table definition is in the README.
try {
  await dynamo.send(new DescribeTableCommand({ TableName: TABLE }));
} catch (error) {
  console.error(`cannot describe DynamoDB table "${TABLE}":`, error);
  process.exit(1);
}

const app = buildApp({
  households: new DynamoHouseholdStore(documents, TABLE),
  telemetry: TELEMETRY ? new S3TelemetrySink(new S3Client({}), BUCKET!) : nullTelemetrySink,
  openapi: true,
});

const server = serve({ fetch: app.fetch, port: PORT }, (info) => {
  console.log(`api listening on :${info.port}` + (TELEMETRY ? "" : " (telemetry off)"));
});

// A household PUT cut off mid-write costs a device one sync cycle; it retries on
// the next resume and the merge is idempotent. Still: drain first.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.close(() => {
      documents.destroy();
      process.exit(0);
    });
  });
}
