import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Property tests — the reason Merge.swift is pure (no clock, no storage, no    */
/* network: values in, values out) is precisely so these can run on the host.   */
/* The example-based suite (MergeTests) pins the scenarios; this suite pins the */
/* ALGEBRA the sync layer relies on: merge in any order, twice, or after a      */
/* rollback, same answer — and two devices that both play offline lose nothing. */
/*                                                                             */
/* One deliberate weakening: `mergeOwned` keeps the LOCAL side's order first,   */
/* and `mergeRoster` keeps local children's positions and the local `activeId`. */
/* Those are observable, wanted asymmetries — so commutativity and              */
/* associativity for the document merges are asserted up to a canonical form    */
/* (owned sorted, children sorted by id, activeId dropped), while idempotence   */
/* — the TS shape `merge(merge(a,b), b) == merge(a,b)` — is asserted STRICTLY.  */
/* -------------------------------------------------------------------------- */

private let devices = ["dad-phone", "mum-phone", "family-ipad"]
private let childIds = ["lea", "tom", "zoe"]
private let itemPool = ["horn", "tail", "hat", "scarf"]
private let ledgerKeys = ["read-image:1", "syllable-grid:2", "first-letter:1"]

/* -- seeded generators ------------------------------------------------------*/

private func genCounter(_ rng: RandomSource) -> Counter {
    var out: Counter = [:]
    for d in devices where rng.bool() {
        out[d] = rng.int(below: 6)
    }
    return out
}

private func genStars(_ rng: RandomSource) -> StarCounters {
    StarCounters(earned: genCounter(rng), spent: genCounter(rng))
}

private func genClears(_ rng: RandomSource) -> ClearCounters {
    var out: ClearCounters = [:]
    for key in ledgerKeys where rng.bool() {
        out[key] = genCounter(rng)
    }
    return out
}

/// Small `at` range on purpose: exact-timestamp ties must actually occur so the
/// deterministic `by` tie-break is exercised.
private func genRev(_ rng: RandomSource) -> Rev {
    Rev(at: Millis(rng.int(below: 4)), by: devices[rng.int(below: devices.count)])
}

private func genOwned(_ rng: RandomSource) -> [String] {
    itemPool.filter { _ in rng.bool() }
}

/**
 * A `Rev` uniquely identifies the write it stamps — a device mints a fresh
 * stamp for every LWW write — so two replicas can only ever hold the SAME
 * stamp for the SAME payload. The generators keep that real-world invariant by
 * deriving every LWW payload from its stamp. Without it, two fully-equal
 * stamps guarding different payloads make the `>=` tie-break side-dependent
 * (`merge(a,b)` keeps a's payload, `merge(b,a)` keeps b's) — a state no
 * sequence of real writes can produce. The TS `laterRev` behaves identically;
 * this is a generator constraint, not a merge fix.
 */
private func stamped(_ rev: Rev) -> Int {
    Int(rev.at) * 7 + (devices.firstIndex(of: rev.by) ?? 5)
}

private func genProgress(_ s: Species, _ rng: RandomSource) -> SpeciesProgress {
    let rev = genRev(rng)
    let n = stamped(rev)
    return SpeciesProgress(
        config: MascotConfig(
            species: s,
            stage: n % 10,
            colors: n % 2 == 0 ? ["hornColor": "#F0A"] : [:],
            styles: [:],
            accessories: []
        ),
        owned: genOwned(rng),
        rev: rev
    )
}

private func genProfile(_ rng: RandomSource) -> PersistedProfile {
    var species = blankSpeciesMap()
    for s in Species.allCases where rng.bool() {
        species[s] = genProgress(s, rng)
    }
    let currentRev = genRev(rng)
    return PersistedProfile(
        chosen: rng.bool(),
        current: Species.allCases[stamped(currentRev) % Species.allCases.count],
        currentRev: currentRev,
        species: species,
        stars: genStars(rng),
        clears: genClears(rng)
    )
}

private func genChild(_ id: String, _ rng: RandomSource) -> ChildProfile {
    ChildProfile(
        id: id,
        name: id,
        nameRev: genRev(rng),
        touchedAt: Millis(rng.int(below: 8)),
        profile: genProfile(rng)
    )
}

private func genRoster(_ rng: RandomSource) -> Roster {
    let children = childIds.filter { _ in rng.bool() }.map { genChild($0, rng) }
    let activeId = children.isEmpty || rng.bool() ? nil : children[rng.int(below: children.count)].id
    var removed: [String: Millis] = [:]
    for id in childIds where rng.int(below: 4) == 0 {
        removed[id] = Millis(rng.int(below: 8))
    }
    return Roster(children: children, activeId: activeId, removed: removed)
}

/* -- canonical form for order-insensitive comparison ------------------------*/

private func canon(_ p: SpeciesProgress) -> SpeciesProgress {
    var p = p
    p.owned.sort()
    return p
}

private func canon(_ p: PersistedProfile) -> PersistedProfile {
    var p = p
    for s in Species.allCases {
        p.species[s] = canon(p.species[s])
    }
    return p
}

private func canon(_ c: ChildProfile) -> ChildProfile {
    var c = c
    c.profile = canon(c.profile)
    return c
}

private func canon(_ r: Roster) -> Roster {
    Roster(
        children: r.children.map(canon).sorted { $0.id < $1.id },
        activeId: nil, // deliberately local; excluded from symmetry claims
        removed: r.removed
    )
}

private let iterations = 500

@Suite struct MergeCounterPropertyTests {
    @Test func commutativeAssociativeIdempotentAndNeverDecreasing() {
        let rng = RandomSource.seeded(0xC0FF_EE01)
        for _ in 0..<iterations {
            let a = genCounter(rng)
            let b = genCounter(rng)
            let c = genCounter(rng)
            let ab = mergeCounter(a, b)

            #expect(ab == mergeCounter(b, a))
            #expect(mergeCounter(ab, c) == mergeCounter(a, mergeCounter(b, c)))
            #expect(mergeCounter(ab, b) == ab)
            #expect(mergeCounter(a, a) == a)
            // A device's slot is only ever advanced by a merge, never rolled back.
            for (d, n) in a { #expect(ab[d]! >= n) }
            for (d, n) in b { #expect(ab[d]! >= n) }
        }
    }
}

@Suite struct MergeProfilePropertyTests {
    @Test func balanceNeverGoesNegative() {
        let rng = RandomSource.seeded(0xC0FF_EE02)
        for _ in 0..<iterations {
            let merged = mergeStars(genStars(rng), genStars(rng))
            #expect(balanceOf(merged) >= 0)
        }
    }

    @Test func commutativeAndAssociativeUpToOwnedOrder() {
        let rng = RandomSource.seeded(0xC0FF_EE03)
        for _ in 0..<iterations {
            let a = genProfile(rng)
            let b = genProfile(rng)
            let c = genProfile(rng)

            #expect(canon(mergeProfile(a, b)) == canon(mergeProfile(b, a)))
            #expect(
                canon(mergeProfile(mergeProfile(a, b), c))
                    == canon(mergeProfile(a, mergeProfile(b, c)))
            )
        }
    }

    @Test func strictlyIdempotent() {
        let rng = RandomSource.seeded(0xC0FF_EE04)
        for _ in 0..<iterations {
            let a = genProfile(rng)
            let b = genProfile(rng)
            let ab = mergeProfile(a, b)
            #expect(mergeProfile(ab, b) == ab)
            #expect(mergeProfile(ab, a) == ab)
        }
    }

    @Test func ownedNeverLosesAnElementAndChosenNeverUnpicks() {
        let rng = RandomSource.seeded(0xC0FF_EE05)
        for _ in 0..<iterations {
            let a = genProfile(rng)
            let b = genProfile(rng)
            let ab = mergeProfile(a, b)
            for s in Species.allCases {
                for item in a.species[s].owned { #expect(ab.species[s].owned.contains(item)) }
                for item in b.species[s].owned { #expect(ab.species[s].owned.contains(item)) }
            }
            #expect(ab.chosen == (a.chosen || b.chosen))
        }
    }

    @Test func perDeviceStarAndClearSlotsNeverDecrease() {
        let rng = RandomSource.seeded(0xC0FF_EE06)
        for _ in 0..<iterations {
            let a = genProfile(rng)
            let b = genProfile(rng)
            let ab = mergeProfile(a, b)
            for (d, n) in a.stars.earned { #expect(ab.stars.earned[d]! >= n) }
            for (d, n) in b.stars.earned { #expect(ab.stars.earned[d]! >= n) }
            for (key, counter) in a.clears {
                for (d, n) in counter { #expect(ab.clears[key]![d]! >= n) }
            }
            for (key, counter) in b.clears {
                for (d, n) in counter { #expect(ab.clears[key]![d]! >= n) }
            }
        }
    }
}

@Suite struct MergeRosterPropertyTests {
    @Test func commutativeUpToOrderAndActiveId() {
        let rng = RandomSource.seeded(0xC0FF_EE07)
        for _ in 0..<iterations {
            let a = genRoster(rng)
            let b = genRoster(rng)
            _ = genRoster(rng)

            #expect(canon(mergeRoster(a, b)) == canon(mergeRoster(b, a)))
        }
    }

    /**
     * NB: `mergeRoster` is deliberately NOT associative in the corner where a
     * tombstone-drop interleaves with a later touch: in `merge(merge(a,b), c)`
     * a child tombstoned against a and b is dropped BEFORE c resurrects them
     * (losing a's and b's contributions to the resurrected child), while in
     * `merge(a, merge(b,c))` the resurrection happens first and b's
     * contribution survives. The TS behaves identically — the delete rule
     * trades algebraic purity for "never erase play". What sync actually
     * relies on is EVENTUAL CONVERGENCE under repetition ("safe to fire as
     * often as we like"): each resume re-runs pull → merge → push, and after
     * every replica has been folded in twice, the household document is a
     * fixed point that no replica — from either side, in any fold order — can
     * perturb. A single pass is not enough for exactly the resurrection corner
     * above; the second pass re-absorbs what the drop discarded.
     */
    @Test func repeatedGossipConvergesToAFixedPointNoFoldOrderMatters() {
        let rng = RandomSource.seeded(0xC0FF_EE0A)
        for _ in 0..<iterations {
            let a = genRoster(rng)
            let b = genRoster(rng)
            let c = genRoster(rng)

            // Two full rounds of pull→merge→push, like two resumes on each device.
            let doc = [b, c, a, b, c].reduce(a, mergeRoster)

            for replica in [a, b, c] {
                #expect(canon(mergeRoster(doc, replica)) == canon(doc))
                #expect(canon(mergeRoster(replica, doc)) == canon(doc))
            }

            // Fold order cannot change the converged document.
            let reversed = [b, a, c, b, a].reduce(c, mergeRoster)
            #expect(canon(reversed) == canon(doc))
        }
    }

    @Test func strictlyIdempotent() {
        let rng = RandomSource.seeded(0xC0FF_EE08)
        for _ in 0..<iterations {
            let a = genRoster(rng)
            let b = genRoster(rng)
            let ab = mergeRoster(a, b)
            #expect(mergeRoster(ab, b) == ab)
        }
    }

    @Test func tombstonesOnlyDropUntouchedChildrenAndNeverRegress() {
        let rng = RandomSource.seeded(0xC0FF_EE09)
        for _ in 0..<iterations {
            let a = genRoster(rng)
            let b = genRoster(rng)
            let ab = mergeRoster(a, b)

            // Tombstones merge by max and are kept forever.
            for (id, at) in a.removed { #expect(ab.removed[id]! >= at) }
            for (id, at) in b.removed { #expect(ab.removed[id]! >= at) }

            // Every survivor beats (or ties) its tombstone; every dropped child
            // was strictly older than one.
            for c in ab.children {
                #expect((ab.removed[c.id] ?? 0) <= c.touchedAt)
            }
            let survivors = Set(ab.children.map(\.id))
            for c in a.children + b.children where !survivors.contains(c.id) {
                let tombstone = ab.removed[c.id]
                #expect(tombstone != nil && tombstone! > c.touchedAt)
            }
        }
    }
}

@Suite struct OfflineConvergenceTests {
    /// The invariant-9 story end to end: one child, two devices, both offline,
    /// both playing — reconciliation loses NOTHING, in either merge order.
    @Test func twoDevicesThatBothPlayOfflineLoseNothing() {
        let base = PersistedProfile(
            chosen: true,
            current: .unicorn,
            currentRev: Rev(at: 1, by: "dad-phone"),
            species: blankSpeciesMap(),
            stars: StarCounters(earned: ["family-ipad": 10], spent: [:]),
            clears: ["read-image:1": ["family-ipad": 1]]
        )

        // Dad's phone, offline: two sessions and a 3-star purchase.
        var dad = base
        dad.stars.earned = bump(dad.stars.earned, device: "dad-phone", by: 5)
        dad.clears["read-image:1"] = bump(dad.clears["read-image:1"] ?? [:], device: "dad-phone", by: 1)
        dad.stars.spent = bump(dad.stars.spent, device: "dad-phone", by: 3)
        var dadUnicorn = dad.species[.unicorn]
        dadUnicorn.owned = dadUnicorn.owned + ["horn"]
        dad.species[.unicorn] = dadUnicorn

        // Mum's phone, offline: one session on another level.
        var mum = base
        mum.stars.earned = bump(mum.stars.earned, device: "mum-phone", by: 2)
        mum.clears["syllable-grid:2"] = bump(mum.clears["syllable-grid:2"] ?? [:], device: "mum-phone", by: 1)
        var mumUnicorn = mum.species[.unicorn]
        mumUnicorn.owned = mumUnicorn.owned + ["tail"]
        mum.species[.unicorn] = mumUnicorn

        for merged in [mergeProfile(dad, mum), mergeProfile(mum, dad)] {
            // 10 + 5 + 2 earned, 3 spent — every star from every device.
            #expect(balanceOf(merged.stars) == 14)
            // Clears SUM: the iPad's and Dad's sessions on the same level are
            // two clears, so the curve keeps decaying.
            let ledger = ledgerOf(merged.clears)
            #expect(ledger["read-image:1"] == 2)
            #expect(ledger["syllable-grid:2"] == 1)
            // Both purchases survive.
            #expect(Set(merged.species[.unicorn].owned) == ["horn", "tail"])
        }
    }
}
