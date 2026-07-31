import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { S3Client } from "@aws-sdk/client-s3";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";

import type { Deps } from "./app.js";
import { DynamoHouseholdStore } from "./household/dynamo.js";
import { S3TelemetrySink } from "./telemetry/s3.js";
import { nullTelemetrySink } from "./telemetry/sink.js";

/* -------------------------------------------------------------------------- */
/* The env → clients → dependencies wiring, shared by both entrypoints.        */
/*                                                                             */
/* `server.ts` (a laptop, a container) and `lambda.ts` (what actually ships)    */
/* differ in how they start and stop and in nothing else, so the part that      */
/* reads configuration and opens clients lives here rather than twice.          */
/*                                                                             */
/* Still the only place that touches `process.env`, alongside those two files.  */
/* Everything below `buildApp` is a pure function of what this returns, which   */
/* is why the whole suite runs with no AWS account and no network.              */
/* -------------------------------------------------------------------------- */

export interface Wiring {
  deps: Deps;
  /** The raw client, for the one caller that wants to probe the table on boot. */
  dynamo: DynamoDBClient;
  table: string;
  /** False when `TELEMETRY=0` swapped the sink for the one that drops. */
  telemetry: boolean;
  close(): void;
}

export class ConfigError extends Error {}

export function wireFromEnv(env: NodeJS.ProcessEnv = process.env): Wiring {
  const table = env.HOUSEHOLD_TABLE;
  const bucket = env.TELEMETRY_BUCKET;
  /** Set to "0" for a deployment that wants sync without analytics. */
  const telemetry = env.TELEMETRY !== "0";
  /** Point at DynamoDB Local for development. Unset in production. */
  const endpoint = env.DYNAMO_ENDPOINT;

  if (!table) throw new ConfigError("HOUSEHOLD_TABLE is required");
  if (telemetry && !bucket) {
    throw new ConfigError("TELEMETRY_BUCKET is required unless TELEMETRY=0");
  }

  const dynamo = new DynamoDBClient(endpoint ? { endpoint } : {});
  const documents = DynamoDBDocumentClient.from(dynamo, {
    // The clients send JSON that Zod has already validated, so there should be
    // nothing undefined to strip — but a marshalling error at 3am is a silent
    // sync outage, and this costs nothing.
    marshallOptions: { removeUndefinedValues: true },
  });
  const s3 = telemetry ? new S3Client({}) : null;

  return {
    table,
    dynamo,
    telemetry,
    deps: {
      households: new DynamoHouseholdStore(documents, table),
      telemetry: s3 ? new S3TelemetrySink(s3, bucket!) : nullTelemetrySink,
      openapi: true,
    },
    close: () => {
      documents.destroy();
      s3?.destroy();
    },
  };
}
