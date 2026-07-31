import type {
  ChildProfile,
  ClearCounters,
  CompletionLedger,
  Counter,
  PersistedProfile,
  Rev,
  Roster,
  SpeciesProgress,
  StarCounters,
} from "../types";

/* -------------------------------------------------------------------------- */
/* Cross-device merge — PURE. No storage, no network, no clock reads except     */
/* through the `now` arguments callers pass in.                                 */
/*                                                                             */
/* The problem this solves: one child plays on Dad's phone and Mum's phone, and */
/* both can be offline at once. Whatever arrives from the other device must be  */
/* folded in WITHOUT losing anything the child earned here. Every merge below   */
/* is commutative, associative and idempotent, so sync can run in any order,    */
/* twice, or after a rollback, and still land on the same answer.               */
/*                                                                             */
/* Two classes of field, and the whole design is picking the right one:         */
/*   • counters  — anything a child earns or accumulates. Merged per device,    */
/*                 never overwritten. Lossless.                                 */
/*   • LWW       — cosmetics only (which mascot, its colours, a name). Losing   */
/*                 one of these costs nothing; losing a star costs trust.       */
/* -------------------------------------------------------------------------- */

/* -- counters ---------------------------------------------------------------*/

/**
 * Per-device grow-only counter merge. A device only ever increments its OWN
 * key, so a key present on both sides can only differ by one side being stale —
 * `max` takes the fresher without double-counting. This is the whole trick.
 */
export function mergeCounter(a: Counter, b: Counter): Counter {
  const out: Counter = { ...a };
  for (const [device, n] of Object.entries(b)) {
    out[device] = Math.max(out[device] ?? 0, n);
  }
  return out;
}

export function sumCounter(c: Counter): number {
  let total = 0;
  for (const n of Object.values(c)) total += n;
  return total;
}

/** Add to this device's own slot. The only legal way to change a counter. */
export function bump(c: Counter, device: string, by: number): Counter {
  return { ...c, [device]: (c[device] ?? 0) + by };
}

export function emptyStars(): StarCounters {
  return { earned: {}, spent: {} };
}

export function mergeStars(a: StarCounters, b: StarCounters): StarCounters {
  return {
    earned: mergeCounter(a.earned, b.earned),
    spent: mergeCounter(a.spent, b.spent),
  };
}

/**
 * Spendable stars = everything ever earned minus everything ever spent.
 *
 * The floor is not paranoia. Two devices offline, both see 10 stars, both buy
 * an 8-star item: after merge Σearned=10, Σspent=16. The child keeps BOTH items
 * and the balance floors at 0 — we never claw a purchase back from a six-year-
 * old to satisfy arithmetic. Overdraw costs us a few stars; a mascot vanishing
 * from the shelf costs us the child.
 */
export function balanceOf(stars: StarCounters): number {
  return Math.max(0, sumCounter(stars.earned) - sumCounter(stars.spent));
}

export function mergeClears(a: ClearCounters, b: ClearCounters): ClearCounters {
  const out: ClearCounters = {};
  for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
    out[key] = mergeCounter(a[key] ?? {}, b[key] ?? {});
  }
  return out;
}

/**
 * Clears per (exercise, level), SUMMED across devices — two devices each
 * clearing level 1 once really is two clears, and the reward curve should decay
 * accordingly. Summing (not max-ing) is what keeps `rewardFor` honest and stops
 * "play it on the other phone" being a way to re-farm the 10-star jackpot.
 */
export function ledgerOf(clears: ClearCounters): CompletionLedger {
  const out: CompletionLedger = {};
  for (const [key, byDevice] of Object.entries(clears)) {
    out[key] = sumCounter(byDevice);
  }
  return out;
}

/* -- last-write-wins --------------------------------------------------------*/

export function newRev(device: string, now: number): Rev {
  return { at: now, by: device };
}

/** Later stamp wins; equal stamps break on deviceId so both sides agree. */
export function laterRev(a: Rev, b: Rev): Rev {
  if (a.at !== b.at) return a.at > b.at ? a : b;
  return a.by >= b.by ? a : b;
}

/** True when `a` is the winning stamp — i.e. the side holding it keeps its value. */
export function revWins(a: Rev, b: Rev): boolean {
  return laterRev(a, b) === a;
}

/* -- sets -------------------------------------------------------------------*/

/** Grow-only set: union, `a`'s order first. Nothing bought is ever un-bought. */
export function mergeOwned(a: string[], b: string[]): string[] {
  const out = [...a];
  for (const id of b) if (!out.includes(id)) out.push(id);
  return out;
}

/* -- documents --------------------------------------------------------------*/

export function mergeSpecies(a: SpeciesProgress, b: SpeciesProgress): SpeciesProgress {
  const winner = revWins(a.rev, b.rev) ? a : b;
  return {
    // Look is cosmetic → LWW. Items are earned → union, regardless of who won.
    config: winner.config,
    rev: winner.rev,
    owned: mergeOwned(a.owned, b.owned),
  };
}

export function mergeProfile(a: PersistedProfile, b: PersistedProfile): PersistedProfile {
  const currentWinner = revWins(a.currentRev, b.currentRev) ? a : b;
  const species = {} as PersistedProfile["species"];
  for (const key of Object.keys(a.species) as (keyof PersistedProfile["species"])[]) {
    species[key] = mergeSpecies(a.species[key], b.species[key]);
  }
  return {
    // Grow-only: once a child has picked a mascot, no merge un-picks it.
    chosen: a.chosen || b.chosen,
    current: currentWinner.current,
    currentRev: currentWinner.currentRev,
    species,
    stars: mergeStars(a.stars, b.stars),
    clears: mergeClears(a.clears, b.clears),
  };
}

export function mergeChild(a: ChildProfile, b: ChildProfile): ChildProfile {
  const nameWinner = revWins(a.nameRev, b.nameRev) ? a : b;
  return {
    id: a.id,
    name: nameWinner.name,
    nameRev: nameWinner.nameRev,
    touchedAt: Math.max(a.touchedAt, b.touchedAt),
    profile: mergeProfile(a.profile, b.profile),
  };
}

/**
 * Fold a remote roster into the local one.
 *
 * Two rules worth stating out loud:
 *
 * `activeId` is NOT merged. Who is holding this tablet says nothing about who
 * is holding the other one; the local value always survives.
 *
 * A delete only wins if nothing happened to that child afterwards
 * (`tombstone > touchedAt`). Delete-always-wins is the textbook rule and it is
 * wrong here: a parent tidying up the roster on one phone would silently erase
 * a week of play that happened on the other. A resurrected child is an
 * annoyance the parent fixes in two taps; a vanished child is unrecoverable.
 * So the tie goes to keeping the data.
 */
export function mergeRoster(local: Roster, remote: Roster): Roster {
  const removed: Record<string, number> = { ...local.removed };
  for (const [id, at] of Object.entries(remote.removed)) {
    removed[id] = Math.max(removed[id] ?? 0, at);
  }

  const byId = new Map<string, ChildProfile>();
  for (const c of local.children) byId.set(c.id, c);
  for (const c of remote.children) {
    const mine = byId.get(c.id);
    byId.set(c.id, mine ? mergeChild(mine, c) : c);
  }

  const children: ChildProfile[] = [];
  for (const c of byId.values()) {
    const tombstone = removed[c.id];
    if (tombstone !== undefined && tombstone > c.touchedAt) continue;
    children.push(c);
  }

  return {
    children,
    activeId: children.some((c) => c.id === local.activeId) ? local.activeId : null,
    removed,
  };
}
