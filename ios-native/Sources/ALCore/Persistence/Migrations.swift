import Foundation

// The blanks, the loose-blob normalisation and the v1/v2/v3 → v4 migrations —
// port of the top half of `src/hooks/useProfile.tsx` (lines 57–263).
//
// All pure functions, all host-testable. Inputs are loose shapes plus
// `device: String` and `now: Millis` PASSED IN — no clock reads and no
// device-id reads inside, the same discipline as Sync/Merge. The only
// non-determinism is `newChildId()`, which mints ids for brand-new children
// and for damaged blobs whose child has no id.
//
// The migration never deletes or rewrites the old key: the v3/v2/v1 blobs stay
// on disk untouched, and the migrated result is only persisted when the first
// mutation commits — `initialRoster` computes, it does not save. (TS: nothing
// writes until `commit`; the test "leaves the v3 blob in place" pins it.)

/* Blanks --------------------------------------------------------------------- */

public func blankConfig(_ species: Species) -> MascotConfig {
    MascotConfig(species: species, stage: 0, colors: [:], styles: [:], accessories: [])
}

public func blankProgress(_ species: Species) -> SpeciesProgress {
    SpeciesProgress(config: blankConfig(species), owned: [], rev: .zero)
}

public func blankSpeciesMap() -> SpeciesMap {
    SpeciesMap(
        unicorn: blankProgress(.unicorn),
        cat: blankProgress(.cat),
        fox: blankProgress(.fox),
        rabbit: blankProgress(.rabbit),
        dragon: blankProgress(.dragon)
    )
}

/// TS `DEFAULT_PROFILE` — what a child who has never played looks like.
public let defaultProfile = PersistedProfile(
    chosen: false,
    current: .unicorn,
    currentRev: .zero,
    species: blankSpeciesMap(),
    stars: StarCounters(earned: [:], spent: [:]),
    clears: [:]
)

/// TS `newId()` — `crypto.randomUUID()`. JS emits lowercase; Swift's `UUID()`
/// emits uppercase, so lowercase it purely so mixed-fleet households produce
/// homogeneous-looking documents. The `c_<ts36>_<rand36>` fallback path
/// (crypto unavailable) has no Swift equivalent failure mode; `UUID()` cannot
/// fail.
func newChildId() -> String {
    UUID().uuidString.lowercased()
}

/* Normalisation --------------------------------------------------------------- */

func normalizeRev(_ r: LooseRev?) -> Rev {
    // TS: `rev ?? ZERO_REV` — a *partial* rev object would be kept as-is
    // there; here the missing halves default field-wise. Identical for every
    // blob the app ever wrote (revs are always written whole).
    guard let r else { return .zero }
    return Rev(at: (r.at ?? 0).looseMillis, by: r.by ?? "")
}

/// TS: `{ ...blankConfig(s), ...src.config, species: s }` — every present
/// field replaces the default, and `species` is FORCE-SET to the slot key
/// (a loose config claiming another species is overridden).
func normalizeConfig(_ s: Species, _ src: LooseMascotConfig?) -> MascotConfig {
    var out = blankConfig(s)
    if let src {
        if let stage = src.stage { out.stage = stage.looseCount }
        if let colors = src.colors { out.colors = colors }
        if let styles = src.styles { out.styles = styles }
        if let accessories = src.accessories { out.accessories = accessories }
    }
    out.species = s
    return out
}

public func normalizeSpecies(_ partial: [String: LooseSpeciesProgress]?) -> SpeciesMap {
    var out = blankSpeciesMap()
    // Iterating `Species.allCases` drops unknown species keys, same as the TS
    // loop over ALL_SPECIES.
    for s in Species.allCases {
        guard let src = partial?[s.rawValue] else { continue }
        out[s] = SpeciesProgress(
            config: normalizeConfig(s, src.config),
            owned: src.owned ?? [],
            rev: normalizeRev(src.rev)
        )
    }
    return out
}

public func normalizeProfile(_ p: LooseProfile?) -> PersistedProfile {
    // NB: TS types the parameter non-optional and would throw on a corrupted
    // v4 child with no profile object; the Swift port stays total and reads it
    // as a blank profile.
    let p = p ?? LooseProfile()
    return PersistedProfile(
        chosen: p.chosen ?? false,
        // NB: documented deviation (spec risk #1) — JS would KEEP an unknown
        // `current` string; the enum cannot represent garbage, so unknown →
        // `.unicorn` here. Only reachable from a hand-corrupted blob;
        // behaviourally invisible for all data the app has ever written. Do
        // not widen `current` to String over this.
        current: p.current.flatMap(Species.init(rawValue:)) ?? .unicorn,
        currentRev: normalizeRev(p.currentRev),
        species: normalizeSpecies(p.species),
        stars: StarCounters(
            earned: looseCounter(p.stars?.earned),
            spent: looseCounter(p.stars?.spent)
        ),
        clears: looseClears(p.clears)
    )
}

/* Migrations ------------------------------------------------------------------ */

/**
 * v2/v3 → v4. The flat totals become "everything earned on THIS device", which
 * is the only honest seeding: before v4 there was no sync, so whatever a device
 * holds is exactly what that device produced. Two phones that migrate their own
 * blobs and meet later therefore SUM — correct, they really were two separate
 * progressions. `balance` was already net of spending, so it seeds `earned`
 * with `spent` empty and the spendable total comes out unchanged.
 */
public func migrateFlatProfile(_ l: LegacyFlatProfile, device: String) -> PersistedProfile {
    var clears: [String: [String: Double]] = [:]
    // Guard `n > 0`: a zero or negative legacy value produces NO key, not a
    // zero key (same below for balance).
    for (key, n) in l.ledger ?? [:] where n > 0 {
        clears[key] = [device: n]
    }
    let balance = l.balance ?? 0
    return normalizeProfile(LooseProfile(
        chosen: l.chosen,
        current: l.current,
        currentRev: nil,
        species: l.species,
        stars: LooseStarCounters(
            earned: balance > 0 ? [device: balance] : [:],
            spent: [:]
        ),
        clears: clears
    ))
}

/// v1 (single mascot) → v4: that mascot fills its species slot, then as above.
public func migrateV1Profile(_ l: LegacyV1Profile, device: String) -> PersistedProfile {
    // NB: same deviation as `normalizeProfile` — an unknown species string on
    // the legacy config reads as `.unicorn`.
    let current = (l.config?.species).flatMap(Species.init(rawValue:)) ?? .unicorn
    return migrateFlatProfile(
        LegacyFlatProfile(
            // v1 users had necessarily chosen (TS: `l.chosen ?? true`).
            chosen: l.chosen ?? true,
            current: current.rawValue,
            species: [current.rawValue: LooseSpeciesProgress(config: l.config, owned: l.owned ?? [], rev: nil)],
            balance: l.balance,
            ledger: l.ledger
        ),
        device: device
    )
}

/// TS `child()` — a brand-new roster entry.
public func child(name: String, profile: PersistedProfile, device: String, now: Millis) -> ChildProfile {
    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
    return ChildProfile(
        id: newChildId(),
        // NB oddity, port as-is (spec §7.8): the trimmed-or-"Joueur" fallback
        // does NOT clamp to 14 characters — only `renameChild` clamps.
        name: trimmed.isEmpty ? "Joueur" : trimmed,
        // TS `newRev(device, now)` — inlined; Sync/Merge owns the function.
        nameRev: Rev(at: now, by: device),
        touchedAt: now,
        profile: profile
    )
}

public func normalizeRoster(_ r: LooseRoster) -> Roster {
    let children = (r.children ?? []).map { c in
        ChildProfile(
            // TS uses `c.id || newId()` — an EMPTY string id is replaced too.
            id: (c.id?.isEmpty == false) ? c.id! : newChildId(),
            name: c.name ?? "Joueur",
            nameRev: normalizeRev(c.nameRev),
            touchedAt: (c.touchedAt ?? 0).looseMillis,
            profile: normalizeProfile(c.profile)
        )
    }
    let activeId = children.contains(where: { $0.id == r.activeId }) ? r.activeId : nil
    return Roster(children: children, activeId: activeId, removed: looseTombstones(r.removed))
}

/**
 * Migrate the best available saved data into a roster (v4 → v3 → v2 → v1).
 * Port of `initialRoster()` — with `device` and `now` injected instead of read
 * (TS calls `deviceId()` and `Date.now()` inside).
 *
 * Computes only; never writes. The migrated result is persisted by the first
 * mutation's commit, exactly like `useState(initialRoster)` which saves
 * nothing until a write.
 */
public func initialRoster(kv: KVStore, device: String, now: Millis) -> Roster {
    if let v4 = ProfileStorage.loadRoster(kv) {
        return normalizeRoster(v4)
    }

    // TS gate: `v3?.children?.length` — present AND non-empty.
    if let v3 = ProfileStorage.loadV3Roster(kv), let kids = v3.children, !kids.isEmpty {
        let children = kids.map { c in
            ChildProfile(
                id: (c.id?.isEmpty == false) ? c.id! : newChildId(),
                name: c.name ?? "Joueur",
                nameRev: .zero,
                // Fresh migration: nothing can have tombstoned these yet, and
                // marking them touched keeps a future stale tombstone from
                // erasing them.
                touchedAt: now,
                profile: migrateFlatProfile(c.profile ?? LegacyFlatProfile(), device: device)
            )
        }
        let activeId = children.contains(where: { $0.id == v3.activeId }) ? v3.activeId : nil
        return Roster(children: children, activeId: activeId, removed: [:])
    }

    if let v2 = ProfileStorage.loadV2Profile(kv) {
        let c = child(name: "Joueur 1", profile: migrateFlatProfile(v2, device: device), device: device, now: now)
        return Roster(children: [c], activeId: c.id, removed: [:])
    }

    if let v1 = ProfileStorage.loadV1Profile(kv) {
        let c = child(name: "Joueur 1", profile: migrateV1Profile(v1, device: device), device: device, now: now)
        return Roster(children: [c], activeId: c.id, removed: [:])
    }

    return Roster(children: [], activeId: nil, removed: [:])
}
