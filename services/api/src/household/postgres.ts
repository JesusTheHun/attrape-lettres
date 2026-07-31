import type { Sql } from "postgres";

import type { HouseholdStore, StoredHousehold, WriteResult } from "./store.js";
import { etagOf, revisionOf } from "./store.js";
import type { WireRoster } from "./wire.js";

/* -------------------------------------------------------------------------- */
/* Postgres household store.                                                   */
/*                                                                             */
/* The concurrency control is the WHERE clause, not a transaction and not a    */
/* lock: `UPDATE … WHERE id = $1 AND revision = $2` affecting zero rows IS the */
/* 412. Two phones pushing at the same instant both read revision 7; one       */
/* update matches and moves to 8, the other matches nothing and is told to go  */
/* and merge again. No row is ever locked while a phone thinks about it.       */
/*                                                                             */
/* The document is stored as jsonb but never queried into. The server has no   */
/* domain knowledge of a roster and must not grow any — the merge lives on the */
/* devices, and a second implementation here would drift from it.              */
/* -------------------------------------------------------------------------- */

export class PostgresHouseholdStore implements HouseholdStore {
  constructor(private readonly sql: Sql) {}

  async read(id: string): Promise<StoredHousehold | null> {
    const rows = await this.sql<{ doc: WireRoster; revision: number }[]>`
      SELECT doc, revision FROM household WHERE id = ${id}
    `;
    const row = rows[0];
    return row ? { roster: row.doc, etag: etagOf(row.revision) } : null;
  }

  async write(id: string, roster: WireRoster, ifMatch: string | null): Promise<WriteResult> {
    if (ifMatch === null) {
      // "I believe this does not exist." ON CONFLICT DO NOTHING makes the claim
      // check itself: if a row is already there, zero rows come back and the
      // client is told to re-read. Never a blind overwrite.
      const created = await this.sql<{ revision: number }[]>`
        INSERT INTO household (id, doc, revision)
        VALUES (${id}, ${this.sql.json(roster as never)}, 1)
        ON CONFLICT (id) DO NOTHING
        RETURNING revision
      `;
      return created.length === 1
        ? { ok: true, etag: etagOf(created[0]!.revision) }
        : { ok: false, conflict: true };
    }

    const expected = revisionOf(ifMatch);
    if (expected === null) return { ok: false, conflict: true };

    const updated = await this.sql<{ revision: number }[]>`
      UPDATE household
         SET doc = ${this.sql.json(roster as never)},
             revision = revision + 1,
             updated_at = now()
       WHERE id = ${id} AND revision = ${expected}
      RETURNING revision
    `;
    return updated.length === 1
      ? { ok: true, etag: etagOf(updated[0]!.revision) }
      : { ok: false, conflict: true };
  }
}
