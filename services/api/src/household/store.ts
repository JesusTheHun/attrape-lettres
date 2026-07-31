import type { WireRoster } from "./wire.js";

/* -------------------------------------------------------------------------- */
/* The storage port.                                                           */
/*                                                                             */
/* The server does NOT merge. Merging happens on the device, between the pull   */
/* and the push — `merge.ts` on the web, `Merge.swift` on iOS, both pure,       */
/* commutative and idempotent. This layer's whole job is to refuse a write      */
/* built on a read that has since been superseded, so that one parent's phone   */
/* cannot silently clobber the other's.                                        */
/*                                                                             */
/* That is why there is no `update(fn)` here and no domain knowledge of what a  */
/* roster means. A store that understood the document would be a second place   */
/* the merge rules live, and the two would drift.                              */
/* -------------------------------------------------------------------------- */

export interface StoredHousehold {
  roster: WireRoster;
  /** Strong ETag, quoted, exactly as it goes on the wire: `"7"`. */
  etag: string;
}

/** `conflict` means the caller's precondition failed — the route answers 412. */
export type WriteResult = { ok: true; etag: string } | { ok: false; conflict: true };

export interface HouseholdStore {
  read(id: string): Promise<StoredHousehold | null>;

  /**
   * Conditional write.
   *
   * `ifMatch === null` means "I believe this household does not exist yet" —
   * which is exactly what a client says after a 404 — so it CREATES and
   * conflicts if something is already there. It is never a blind overwrite:
   * a client that lost its etag must re-read before it can write.
   */
  write(id: string, roster: WireRoster, ifMatch: string | null): Promise<WriteResult>;
}

/** `"1"`, `"2"`, … — a revision counter, quoted per RFC 9110 for `If-Match`. */
export function etagOf(revision: number): string {
  return `"${revision}"`;
}

/**
 * `"7"` → 7. Anything else → null, which every caller turns into a 412.
 *
 * A malformed `If-Match` is a conflict rather than a 400 on purpose: the client
 * response to 412 is "re-pull and retry", which is exactly what a client
 * holding a nonsense etag should do. A 400 would be thrown away by both clients
 * (they fail silent) and the device would keep retrying the same bad header
 * forever.
 */
export function revisionOf(etag: string): number | null {
  const match = /^"(\d{1,15})"$/.exec(etag.trim());
  if (!match) return null;
  const n = Number(match[1]);
  return Number.isSafeInteger(n) ? n : null;
}

/**
 * Test and local-dev double. Same semantics as DynamoDB, including the
 * create-only rule, so the route tests exercise real behaviour rather than a
 * simplified stand-in.
 */
export class InMemoryHouseholdStore implements HouseholdStore {
  private readonly rows = new Map<string, { roster: WireRoster; revision: number }>();

  async read(id: string): Promise<StoredHousehold | null> {
    const row = this.rows.get(id);
    return row ? { roster: row.roster, etag: etagOf(row.revision) } : null;
  }

  async write(id: string, roster: WireRoster, ifMatch: string | null): Promise<WriteResult> {
    const row = this.rows.get(id);
    if (row === undefined) {
      // Create. A stale `If-Match` against a household that no longer exists is
      // a conflict, not a create — the client must learn it is gone.
      if (ifMatch !== null) return { ok: false, conflict: true };
      this.rows.set(id, { roster, revision: 1 });
      return { ok: true, etag: etagOf(1) };
    }
    // No `If-Match` on an existing household means the client claimed this did
    // not exist. It does — so this is a conflict, never a blind overwrite of
    // somebody's whole roster.
    if (ifMatch === null) return { ok: false, conflict: true };
    // Parsed, not string-compared, so this double behaves exactly as the
    // DynamoDB store does — including on a whitespace-padded or malformed etag.
    // Keeping the etag an integer revision, rather than a hash of the content,
    // is what lets this double stay faithful. See R9.
    if (revisionOf(ifMatch) !== row.revision) return { ok: false, conflict: true };
    const revision = row.revision + 1;
    this.rows.set(id, { roster, revision });
    return { ok: true, etag: etagOf(revision) };
  }
}
