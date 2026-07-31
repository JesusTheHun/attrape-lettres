import type { ErrorReport, EventBatch } from "./schema.js";

/* -------------------------------------------------------------------------- */
/* Where accepted telemetry goes.                                              */
/*                                                                             */
/* Separate port from the household store even though both land in the same    */
/* Postgres and the same deploy, because they have opposite requirements:      */
/* household sync has to be CORRECT (a lost write is a child's stars), and     */
/* telemetry has to be CHEAP (it must never make the app wait). Keeping them   */
/* apart in the code is what makes splitting the deploy later a config change  */
/* rather than a rewrite.                                                       */
/* -------------------------------------------------------------------------- */

export interface TelemetrySink {
  events(batch: EventBatch): Promise<void>;
  error(report: ErrorReport): Promise<void>;
}

/** Test and local-dev double: keeps what it was given, in order. */
export class InMemoryTelemetrySink implements TelemetrySink {
  readonly batches: EventBatch[] = [];
  readonly errors: ErrorReport[] = [];

  async events(batch: EventBatch): Promise<void> {
    this.batches.push(batch);
  }

  async error(report: ErrorReport): Promise<void> {
    this.errors.push(report);
  }
}

/**
 * A sink that drops everything, for a deployment that wants sync without
 * analytics. The endpoints still answer 204, because the clients treat any
 * non-2xx as a reason to retry and we would rather they didn't.
 */
export const nullTelemetrySink: TelemetrySink = {
  async events() {},
  async error() {},
};
