import { randomUUID } from "node:crypto";

import { PutObjectCommand, type S3Client } from "@aws-sdk/client-s3";

import type { ErrorReport, EventBatch } from "./schema.js";
import type { TelemetrySink } from "./sink.js";

/* -------------------------------------------------------------------------- */
/* S3 telemetry sink: newline-delimited JSON, one object per accepted batch.   */
/*                                                                             */
/* Telemetry is append-many and read-rarely, and the reads are aggregations —  */
/* counts grouped by event and by day. That is the one thing a key-value store */
/* is bad at, which is why this does NOT go in the same DynamoDB table as the  */
/* households. Under a date prefix it costs one request per batch to write and */
/* can be read with Athena or by downloading a day and counting locally, with  */
/* nothing running in between.                                                 */
/*                                                                             */
/* It also keeps family data and analytics in physically separate stores, which */
/* is the isolation this split was asked for.                                  */
/*                                                                             */
/* NOTHING JOINS TWO ROWS. A batch carries no device id, no household id and    */
/* no session id, so two batches from one phone are indistinguishable from two */
/* batches from two phones — and the object key is random rather than derived  */
/* from anything, so it cannot become one either. That is what makes this data */
/* anonymous rather than pseudonymous, and why it needs no retention policy.   */
/*                                                                             */
/* `receivedAt` is the only thing the server adds. Deliberately not a client    */
/* clock: a client clock is a fingerprinting surface and nothing here needs one.*/
/* -------------------------------------------------------------------------- */

/** `2026-07-31`, in UTC. The partition a day's objects live under. */
function dayOf(at: Date): string {
  return at.toISOString().slice(0, 10);
}

function ndjson(rows: unknown[]): string {
  return rows.map((r) => JSON.stringify(r)).join("\n") + "\n";
}

export class S3TelemetrySink implements TelemetrySink {
  constructor(
    private readonly client: Pick<S3Client, "send">,
    private readonly bucket: string,
    /** Injected so a test can pin the partition; production passes nothing. */
    private readonly now: () => Date = () => new Date()
  ) {}

  private async put(prefix: string, rows: unknown[]): Promise<void> {
    const at = this.now();
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        // Random, not derived: a key built from anything in the payload would
        // be a grouping handle, and there must not be one.
        Key: `${prefix}/dt=${dayOf(at)}/${randomUUID()}.ndjson`,
        Body: ndjson(rows),
        ContentType: "application/x-ndjson",
      }) as never
    );
  }

  async events(batch: EventBatch): Promise<void> {
    const receivedAt = this.now().toISOString();
    await this.put(
      "events",
      batch.events.map((e) => ({ v: batch.v, event: e.event, props: e.props, receivedAt }))
    );
  }

  async error(report: ErrorReport): Promise<void> {
    await this.put("errors", [{ ...report, receivedAt: this.now().toISOString() }]);
  }
}
