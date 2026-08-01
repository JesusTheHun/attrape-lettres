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
/* Records are emitted as ONE LINE OF JSON, deliberately pre-serialised rather  */
/* than handed to `console.log` as an object. Lambda wraps whatever a handler   */
/* prints, and the wrapping differs by log format: an object may arrive nested  */
/* under `message`, or inspected into `{ event: 'x' }` — single quotes, no      */
/* colon-quote pair, nothing a filter can match. A string survives both, so the */
/* CloudWatch metric filters in `infra/template.yaml` match the literal         */
/* substring `"event":"…"` and stay correct whatever the runtime does around    */
/* them. Those filters are the only thing between a broken deploy and nobody    */
/* noticing; they do not get to depend on a wrapping convention.                */
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

/**
 * The exact bytes that reach CloudWatch.
 *
 * Exported because `test/infra.test.ts` reads every `FilterPattern` out of
 * `infra/template.yaml` and asserts it still matches a line this produces. The
 * alarms match literal substrings of this string, so a reordered field or a
 * renamed event breaks them — and breaks them silently, which is the one
 * failure mode this service must not have twice.
 */
export function formatLine(level: LogLevel, record: LogFields): string {
  return JSON.stringify({ level, ...record });
}

const consoleSink: Sink = (level, record) => {
  // `stderr` for warn and error so Lambda tags the line ERROR, which makes the
  // stream itself a coarse but working backstop if a filter ever stops
  // matching.
  const line = formatLine(level, record);
  if (level === "info") console.log(line);
  else console.error(line);
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
