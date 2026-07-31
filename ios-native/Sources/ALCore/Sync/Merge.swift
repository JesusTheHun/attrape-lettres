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
/*                                                                             */
/* Port of `src/sync/merge.ts`, function for function. The purity is what makes */
/* `MergePropertyTests` runnable on the host — and the property tests are what  */
/* make invariant 9 checkable rather than asserted.                             */
/* -------------------------------------------------------------------------- */

/* -- counters ---------------------------------------------------------------*/

/**
 * Per-device grow-only counter merge. A device only ever increments its OWN
 * key, so a key present on both sides can only differ by one side being stale —
 * `max` takes the fresher without double-counting. This is the whole trick.
 */
public func mergeCounter(_ a: Counter, _ b: Counter) -> Counter {
    var out = a
    for (device, n) in b {
        out[device] = max(out[device] ?? 0, n)
    }
    return out
}

public func sumCounter(_ c: Counter) -> Int {
    var total = 0
    for n in c.values { total += n }
    return total
}

/** Add to this device's own slot. The only legal way to change a counter. */
public func bump(_ c: Counter, device: String, by: Int) -> Counter {
    var out = c
    // NB: a bump of +0 still CREATES the key (TS spreads `[device]: 0` in).
    // No `award` passes 0 any more — every finished run pays the curve — but
    // the key-creating behaviour is the TS's and stays ported as-is.
    out[device] = (c[device] ?? 0) + by
    return out
}

public func emptyStars() -> StarCounters {
    StarCounters(earned: [:], spent: [:])
}

public func mergeStars(_ a: StarCounters, _ b: StarCounters) -> StarCounters {
    StarCounters(
        earned: mergeCounter(a.earned, b.earned),
        spent: mergeCounter(a.spent, b.spent)
    )
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
public func balanceOf(_ stars: StarCounters) -> Int {
    max(0, sumCounter(stars.earned) - sumCounter(stars.spent))
}

public func mergeClears(_ a: ClearCounters, _ b: ClearCounters) -> ClearCounters {
    var out: ClearCounters = [:]
    // TS iterates `new Set([...keys(a), ...keys(b)])`; the output is a dict on
    // both sides, so key order is not observable and no ordered helper is needed.
    for key in Set(a.keys).union(b.keys) {
        out[key] = mergeCounter(a[key] ?? [:], b[key] ?? [:])
    }
    return out
}

/**
 * Clears per (exercise, level), SUMMED across devices — two devices each
 * clearing level 1 once really is two clears, and the reward curve should decay
 * accordingly. Summing (not max-ing) is what keeps `rewardFor` honest and stops
 * "play it on the other phone" being a way to re-farm the 10-star jackpot.
 */
public func ledgerOf(_ clears: ClearCounters) -> CompletionLedger {
    var out: CompletionLedger = [:]
    for (key, byDevice) in clears {
        out[key] = sumCounter(byDevice)
    }
    return out
}

/* -- last-write-wins --------------------------------------------------------*/

public func newRev(device: String, now: Millis) -> Rev {
    Rev(at: now, by: device)
}

/** Later stamp wins; equal stamps break on deviceId so both sides agree. */
public func laterRev(_ a: Rev, _ b: Rev) -> Rev {
    if a.at != b.at { return a.at > b.at ? a : b }
    return a.by >= b.by ? a : b
}

/**
 * True when `a` is the winning stamp — i.e. the side holding it keeps its value.
 *
 * NB: the TS is `laterRev(a, b) === a` — REFERENCE identity, which Swift
 * structs do not have. The predicate below is equivalent: when the stamps are
 * fully equal, both orderings pick the `a`-side by `>=`, and equal stamps carry
 * equal payloads in practice. The tie-break is plain string `>=`: JS compares
 * UTF-16 code units, Swift compares Unicode scalars — identical over ASCII,
 * and device ids are ASCII by construction (keep id minting ASCII-only
 * forever). Do not "fix" this with a locale-aware comparison.
 */
public func revWins(_ a: Rev, _ b: Rev) -> Bool {
    a.at != b.at ? a.at > b.at : a.by >= b.by
}

/* -- sets -------------------------------------------------------------------*/

/** Grow-only set: union, `a`'s order first. Nothing bought is ever un-bought. */
public func mergeOwned(_ a: [String], _ b: [String]) -> [String] {
    // Order-preserving union, NOT a `Set` — the array order is observable
    // (shop/inventory ordering) and Swift's Set is per-process randomised.
    var out = a
    for id in b where !out.contains(id) {
        out.append(id)
    }
    return out
}

/* -- documents --------------------------------------------------------------*/

public func mergeSpecies(_ a: SpeciesProgress, _ b: SpeciesProgress) -> SpeciesProgress {
    let winner = revWins(a.rev, b.rev) ? a : b
    return SpeciesProgress(
        // Look is cosmetic → LWW. Items are earned → union, regardless of who won.
        config: winner.config,
        owned: mergeOwned(a.owned, b.owned),
        rev: winner.rev
    )
}

public func mergeProfile(_ a: PersistedProfile, _ b: PersistedProfile) -> PersistedProfile {
    let currentWinner = revWins(a.currentRev, b.currentRev) ? a : b
    // TS iterates `Object.keys(a.species)`; normalised data always holds all
    // five, which is exactly `Species.allCases` over the fixed struct (D8).
    var species = a.species
    for s in Species.allCases {
        species[s] = mergeSpecies(a.species[s], b.species[s])
    }
    return PersistedProfile(
        // Grow-only: once a child has picked a mascot, no merge un-picks it.
        chosen: a.chosen || b.chosen,
        current: currentWinner.current,
        currentRev: currentWinner.currentRev,
        species: species,
        stars: mergeStars(a.stars, b.stars),
        clears: mergeClears(a.clears, b.clears)
    )
}

public func mergeChild(_ a: ChildProfile, _ b: ChildProfile) -> ChildProfile {
    let nameWinner = revWins(a.nameRev, b.nameRev) ? a : b
    return ChildProfile(
        id: a.id,
        name: nameWinner.name,
        nameRev: nameWinner.nameRev,
        touchedAt: max(a.touchedAt, b.touchedAt),
        profile: mergeProfile(a.profile, b.profile)
    )
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
public func mergeRoster(_ local: Roster, _ remote: Roster) -> Roster {
    var removed = local.removed
    for (id, at) in remote.removed {
        removed[id] = max(removed[id] ?? 0, at)
    }

    // The TS folds through a JS `Map`, which is INSERTION-ORDERED: local
    // children keep their positions, remote-only children append in remote
    // order. The children array order is observable (the roster list), so the
    // fold goes through an explicit order array, never a bare Dictionary.
    var order: [String] = []
    var byId: [String: ChildProfile] = [:]
    for c in local.children {
        if byId.updateValue(c, forKey: c.id) == nil { order.append(c.id) }
    }
    for c in remote.children {
        if let mine = byId[c.id] {
            byId[c.id] = mergeChild(mine, c)
        } else {
            byId[c.id] = c
            order.append(c.id)
        }
    }

    var children: [ChildProfile] = []
    for id in order {
        guard let c = byId[id] else { continue }
        if let tombstone = removed[c.id], tombstone > c.touchedAt { continue }
        children.append(c)
    }

    return Roster(
        children: children,
        activeId: children.contains(where: { $0.id == local.activeId }) ? local.activeId : nil,
        removed: removed
    )
}
