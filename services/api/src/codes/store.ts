/* -------------------------------------------------------------------------- */
/* The redemption port.                                                        */
/*                                                                             */
/* Two things live behind it, and they are one table because they are always   */
/* written together:                                                           */
/*                                                                             */
/*   CODE   a hash, how many redemptions it allows, how many it has had, and   */
/*          when it stops working. Minted by us, never by a client.            */
/*   GRANT  one row per household: this family is unlocked, since when.        */
/*                                                                             */
/* ── The redemption is ONE atomic step, and that is the whole design. ─────────*/
/*                                                                             */
/* Two writes — bump the counter, then write the grant — lose in both           */
/* directions. Counter first and the grant write fails: a code is burnt and a   */
/* family paid nothing for it, with no way to tell which. Grant first and the   */
/* counter fails: one code unlocks the world. So `redeem` is a single           */
/* conditional transaction, and the conditions ARE the business rules:          */
/*                                                                             */
/*   redeemed < maxRedemptions      the code has uses left                     */
/*   now < expiresAt                the code has not lapsed                     */
/*   the household has no grant     one unlock per family, so a code cannot be  */
/*                                  spent on a family that is already unlocked  */
/*                                                                             */
/* The last one also makes redeeming twice IDEMPOTENT rather than wasteful: a   */
/* parent who taps « Valider » twice, or reinstalls and tries the same code,    */
/* gets `already` and the code keeps its remaining uses.                        */
/* -------------------------------------------------------------------------- */

/** What a code buys. One kind today; the column exists so a second is a value,
 *  not a migration. */
export type GrantKind = "unlock";

export interface CodeRow {
  /** `codeHash(normalised)` — never the code. */
  hash: string;
  kind: GrantKind;
  /** How many households this code may unlock. 1 for a personal code. */
  maxRedemptions: number;
  redeemed: number;
  /** Epoch ms, or null for a code that never lapses. */
  expiresAt: number | null;
  createdAt: number;
  /** Free text for us, never sent to a client: "presse", "école Jules Ferry". */
  label?: string;
}

export interface Grant {
  kind: GrantKind;
  grantedAt: number;
}

/**
 * Every way a redemption can end.
 *
 * `unreachable` is not here on purpose: a store either answers or throws, and a
 * throw becomes a 500 that the client treats as "say nothing, change nothing".
 * Inventing a fail-open value at this layer would let a store outage look like
 * a business answer.
 */
export type RedeemResult =
  | { ok: true; grant: Grant; fresh: boolean }
  | { ok: false; reason: "unknown" | "exhausted" | "expired" };

export interface CodeStore {
  /** The family's current grant, or null. Cheap: one point read. */
  grant(household: string): Promise<Grant | null>;

  /**
   * Spend one use of `hash` on `household`, atomically.
   *
   * `fresh: false` means the family already had a grant and no use was spent.
   */
  redeem(hash: string, household: string, now: number): Promise<RedeemResult>;

  /** Minting. Not reachable from any route — only `scripts/mint-codes.mjs`. */
  put(row: CodeRow): Promise<void>;
}

/**
 * Test and local-dev double. Same semantics as the DynamoDB store, including
 * the three conditions and the idempotent second redemption, so the route tests
 * exercise real behaviour rather than a simplified stand-in.
 */
export class InMemoryCodeStore implements CodeStore {
  private readonly codes = new Map<string, CodeRow>();
  private readonly grants = new Map<string, Grant>();

  async grant(household: string): Promise<Grant | null> {
    return this.grants.get(household) ?? null;
  }

  async put(row: CodeRow): Promise<void> {
    this.codes.set(row.hash, { ...row });
  }

  async redeem(hash: string, household: string, now: number): Promise<RedeemResult> {
    const existing = this.grants.get(household);
    const row = this.codes.get(hash);

    // The code is checked BEFORE the short-circuit below, so a family that is
    // already unlocked still learns that the code they typed is nonsense. The
    // alternative silently accepts an invalid code and teaches nobody anything.
    if (!row) return { ok: false, reason: "unknown" };
    if (row.expiresAt !== null && now >= row.expiresAt) {
      return { ok: false, reason: "expired" };
    }

    // Already unlocked: succeed, spend nothing.
    if (existing) return { ok: true, grant: existing, fresh: false };

    if (row.redeemed >= row.maxRedemptions) return { ok: false, reason: "exhausted" };

    row.redeemed += 1;
    const grant: Grant = { kind: row.kind, grantedAt: now };
    this.grants.set(household, grant);
    return { ok: true, grant, fresh: true };
  }
}
