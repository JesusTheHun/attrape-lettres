import { createHash } from "node:crypto";

/* -------------------------------------------------------------------------- */
/* Structured logging, because this service fails silently by design.          */
/*                                                                             */
/* Both clients swallow every response that is not a 200, a 404 or a 412 and   */
/* keep playing offline. That is correct — a six-year-old mid-round must never  */
/* wait on a network call — and it means NOTHING that goes wrong here reaches   */
/* a user, a support inbox or a crash reporter. A log line is the only place a  */
/* failure can appear at all, so the lines have to be worth alarming on.        */
/*                                                                             */
/* Records are emitted as OBJECTS, not as strings. Under Lambda's JSON log      */
/* format they land as `{"level":…,"requestId":…,"message":{…our fields…}}`,    */
/* which is what lets the CloudWatch metric filters in `infra/template.yaml`    */
/* match on `$.message.event`. Change the shape here and those filters stop     */
/* matching — silently, which is the failure this whole file exists to prevent. */
/*                                                                             */
/* WHAT MAY NOT BE LOGGED. A household id is the only credential this service   */
/* has: whoever holds one can read and overwrite that family's roster. Writing  */
/* it to CloudWatch would put a stranger's children one log query away, so it   */
/* is never logged in the clear — `householdRef` is. Device ids, child ids and  */
/* anything that arrived in a request body are never logged at all.             */
/* -------------------------------------------------------------------------- */

export type LogLevel = "info" | "warn" | "error";

export type LogFields = Record<string, unknown>;

type Sink = (level: LogLevel, record: LogFields) => void;

const consoleSink: Sink = (level, record) => {
  if (level === "error") console.error(record);
  else if (level === "warn") console.warn(record);
  else console.log(record);
};

let sink: Sink = consoleSink;

/**
 * Tests only. Returns the restore function, so a spec cannot forget to put the
 * console back.
 */
export function captureLogs(): { records: (LogFields & { level: LogLevel })[]; restore: () => void } {
  const records: (LogFields & { level: LogLevel })[] = [];
  const previous = sink;
  sink = (level, record) => records.push({ level, ...record });
  return { records, restore: () => (sink = previous) };
}

export function log(level: LogLevel, event: string, fields: LogFields = {}): void {
  sink(level, { event, ...fields });
}

/**
 * A stable, non-reversible handle for one household.
 *
 * Twelve hex characters is enough to answer the only question worth asking of
 * these logs — "is one family failing or is every family failing" — and it is
 * not enough to reconstruct a 128-bit credential. It is deliberately not a
 * truncation of the id itself, which would leak half of one.
 */
export function householdRef(id: string): string {
  return createHash("sha256").update(id).digest("hex").slice(0, 12);
}

/**
 * A validation-failure path, with anything that came from the payload removed.
 *
 * A Zod issue path walks into record KEYS, and in this schema those keys are
 * device ids (`stars.earned.<deviceId>`) and ledger keys
 * (`clears.<exercise>:<level>`). Device ids never leave the device that minted
 * them and they are not going to start leaving via our own logs.
 *
 * So a segment survives only if it is an array index or looks like a schema
 * field name — short, alphabetic, camelCase. Every id this system mints fails
 * that test: uuids and ledger keys carry `-`, `_`, `:` or digits. Anything else
 * becomes `*`, which still says WHERE the document was wrong without saying
 * what was in it.
 */
const FIELD_NAME = /^[a-z][A-Za-z]{0,15}$/;

export function safePath(path: readonly PropertyKey[]): string {
  return path
    .map((segment) => {
      if (typeof segment === "number") return String(segment);
      const text = String(segment);
      return FIELD_NAME.test(text) ? text : "*";
    })
    .join(".");
}
