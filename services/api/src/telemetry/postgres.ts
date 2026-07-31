import type { Sql } from "postgres";

import type { ErrorReport, EventBatch } from "./schema.js";
import type { TelemetrySink } from "./sink.js";

/* -------------------------------------------------------------------------- */
/* Postgres telemetry sink.                                                    */
/*                                                                             */
/* Append-only, no reads, no joins, no foreign key to anything. There is       */
/* nothing to join to: a batch carries no device id, no household id and no    */
/* session id, so two batches from the same phone are indistinguishable from   */
/* two batches from two phones. That is not an oversight to fix later — it is  */
/* what makes the data genuinely anonymous rather than pseudonymous, and it is */
/* why this table can be kept indefinitely without a retention policy.         */
/*                                                                             */
/* `received_at` is the ONLY thing the server adds. Deliberately not a client  */
/* timestamp: a client clock is a fingerprinting surface and we do not need    */
/* one.                                                                        */
/* -------------------------------------------------------------------------- */

export class PostgresTelemetrySink implements TelemetrySink {
  constructor(private readonly sql: Sql) {}

  async events(batch: EventBatch): Promise<void> {
    const rows = batch.events.map((e) => ({
      app_version: batch.v,
      event: e.event,
      props: this.sql.json(e.props as never),
    }));
    await this.sql`INSERT INTO telemetry_event ${this.sql(rows)}`;
  }

  async error(report: ErrorReport): Promise<void> {
    await this.sql`
      INSERT INTO telemetry_error (app_version, where_, message, stack)
      VALUES (${report.v}, ${report.where}, ${report.message}, ${report.stack})
    `;
  }
}
