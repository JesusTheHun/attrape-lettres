// Port of the persisted-profile vocabulary from `src/types.ts` (the
// "Sync-safe counters (v4)" section onward) plus `applyOption` from
// `src/hooks/useProfile.tsx`.
//
// D8: `Species`, `MascotConfig`, `CustomizationOption` live in `Domain/Mascot.swift`
// and are imported, not redeclared. This file owns only the sync-safe storage
// types: Counter, Rev, StarCounters, ClearCounters, SpeciesProgress, SpeciesMap,
// PersistedProfile, ChildProfile, Roster, ProfileView.
//
// INVARIANT 9 — never persist a bare running total — is STRUCTURAL here:
//   • `PersistedProfile` has NO `balance` and NO `ledger` property. A stored
//     total cannot even be expressed in the persisted type; `StarCounters` /
//     `ClearCounters` are the only star/clear storage.
//   • `ProfileView` — the flattened shape the UI reads, which DOES carry
//     `balance` — is deliberately NOT Codable, so persisting or wiring it is a
//     compile error, not a code-review catch.
//
// The rule for any NEW persisted field (from CLAUDE.md, verbatim in spirit):
// decide its merge class FIRST — accumulates on two devices ⇒ `Counter`;
// cosmetic ⇒ a value plus a `Rev` stamp; a set ⇒ grow-only array. Then bump the
// storage KEY to `:vN` in ProfileStorage, add a `loadV(N-1)` reader, migrate
// forward in ProfileStore, and KEEP the old key and reader (a rolled-back
// launch must still read something it understands). Add the field to
// `mergeProfile`/`mergeChild` in the SAME change, with a test that two offline
// devices both writing it lose nothing. Cosmetics (which mascot, its colours, a
// name) are the only legitimate last-write-wins fields — losing one costs
// nothing, losing a star costs trust.

/// Epoch milliseconds — the unit of every stamp JS ever wrote (`Date.now()`).
public typealias Millis = Int64

/* Sync-safe counters (v4) ----------------------------------------------------*/
/* One child plays on Dad's phone, Mum's phone and the iPad. Two devices can    */
/* earn stars for the SAME child while both are offline, so a plain `number`    */
/* cannot merge: last-write-wins silently eats one device's stars. Every value  */
/* that can change on two devices at once is therefore stored per device and    */
/* folded back into the flat number the UI reads (see ProfileView below).       */

/**
 * `deviceId` → a count that only ever grows on that device. Because a device
 * only ever increments its OWN key, merging two replicas is per-key `max` and
 * is lossless, commutative and idempotent — sync in any order, any number of
 * times, same result.
 *
 * `bump` (Sync/Merge.swift) is the ONLY legal counter write, and it only ever
 * touches the caller's own device slot. Nothing in the API takes
 * "set balance to N".
 */
public typealias Counter = [String: Int]

/**
 * Stars as a PN-counter: two grow-only halves, balance = Σearned − Σspent.
 * Never store a running total — a total cannot be merged.
 */
public struct StarCounters: Codable, Hashable, Sendable {
    /// deviceId → stars ever earned on it.
    public var earned: Counter
    /// deviceId → stars ever spent on it.
    public var spent: Counter

    public init(earned: Counter, spent: Counter) {
        self.earned = earned
        self.spent = spent
    }
}

/// ledgerKey() → per-device clear counts. Merged per device, then summed.
public typealias ClearCounters = [String: Counter]

/// Times each (exerciseId, level) has been cleared. Key via ledgerKey().
/// DERIVED ONLY — a fold of `ClearCounters`. Never persisted.
public typealias CompletionLedger = [String: Int]

/**
 * Stamp for a genuinely last-write-wins field (cosmetics only — losing one is
 * harmless). `at` is Date.now(); `by` breaks ties deterministically so two
 * devices merging in opposite orders still agree.
 */
public struct Rev: Codable, Hashable, Sendable {
    public var at: Millis
    public var by: String

    public init(at: Millis, by: String) {
        self.at = at
        self.by = by
    }

    /// The stamp every never-written LWW field starts at — always loses a merge.
    /// (TS: `ZERO_REV = { at: 0, by: "" }`.)
    public static let zero = Rev(at: 0, by: "")
}

/// One mascot's own progress. Kept forever — switching never discards it.
public struct SpeciesProgress: Codable, Hashable, Sendable {
    /// This mascot's current look (stage, colours, styles, accessories).
    public var config: MascotConfig
    /// Option ids bought FOR THIS SPECIES (unlocked, may or may not be equipped).
    /// A grow-only ORDERED array, not a Set — merge preserves local-side order
    /// and the order is observable (shop/inventory ordering).
    public var owned: [String]
    /// LWW stamp for `config`. `owned` needs none — it's a grow-only set.
    public var rev: Rev

    public init(config: MascotConfig, owned: [String], rev: Rev) {
        self.config = config
        self.owned = owned
        self.rev = rev
    }
}

/**
 * TS `Record<Species, SpeciesProgress>` — deliberately a fixed struct with five
 * stored properties, not `[Species: SpeciesProgress]`:
 *   1. totality is compile-time — `normalizeSpecies` in TS exists to guarantee
 *      all five keys are present; the struct guarantees it for free;
 *   2. avoids the `Dictionary` Codable pitfall (enum-keyed dicts need
 *      `CodingKeyRepresentable` to encode as an object, not an array);
 *   3. `mergeProfile` iterates `Object.keys(a.species)` — with the struct it
 *      iterates `Species.allCases`, which is what normalised data always is.
 *
 * Decoding supplies `blankProgress(s)` for a missing key and ignores unknown
 * keys (matching `normalizeSpecies` dropping them). Encoding always writes all
 * five, matching normalised JS output.
 */
public struct SpeciesMap: Hashable, Sendable {
    public var unicorn: SpeciesProgress
    public var cat: SpeciesProgress
    public var fox: SpeciesProgress
    public var rabbit: SpeciesProgress
    public var dragon: SpeciesProgress

    public init(
        unicorn: SpeciesProgress,
        cat: SpeciesProgress,
        fox: SpeciesProgress,
        rabbit: SpeciesProgress,
        dragon: SpeciesProgress
    ) {
        self.unicorn = unicorn
        self.cat = cat
        self.fox = fox
        self.rabbit = rabbit
        self.dragon = dragon
    }

    public subscript(species: Species) -> SpeciesProgress {
        get {
            switch species {
            case .unicorn: return unicorn
            case .cat: return cat
            case .fox: return fox
            case .rabbit: return rabbit
            case .dragon: return dragon
            }
        }
        set {
            switch species {
            case .unicorn: unicorn = newValue
            case .cat: cat = newValue
            case .fox: fox = newValue
            case .rabbit: rabbit = newValue
            case .dragon: dragon = newValue
            }
        }
    }
}

extension SpeciesMap: Codable {
    private enum CodingKeys: String, CodingKey {
        case unicorn, cat, fox, rabbit, dragon
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // A missing slot decodes as its blank; unknown keys in the JSON are
        // ignored by the keyed container — both matching `normalizeSpecies`.
        unicorn = try c.decodeIfPresent(SpeciesProgress.self, forKey: .unicorn) ?? blankProgress(.unicorn)
        cat = try c.decodeIfPresent(SpeciesProgress.self, forKey: .cat) ?? blankProgress(.cat)
        fox = try c.decodeIfPresent(SpeciesProgress.self, forKey: .fox) ?? blankProgress(.fox)
        rabbit = try c.decodeIfPresent(SpeciesProgress.self, forKey: .rabbit) ?? blankProgress(.rabbit)
        dragon = try c.decodeIfPresent(SpeciesProgress.self, forKey: .dragon) ?? blankProgress(.dragon)
    }
    // encode(to:) is synthesised and always writes all five slots.
}

/**
 * The persisted child profile — what ProfileStorage reads/writes.
 *
 * Progress is split by ownership: growth/look/items live PER SPECIES (each
 * mascot remembers itself), while stars and cleared-levels belong to the CHILD
 * and survive every mascot switch.
 *
 * Every field here is mergeable across devices, by construction:
 *   chosen  grow-only boolean (OR)     stars   PN-counter
 *   current LWW via currentRev         clears  per-key counters
 *   species per-species merge (config LWW, owned grow-only set)
 * Nothing is a bare running total. See Sync/Merge.swift.
 */
public struct PersistedProfile: Codable, Hashable, Sendable {
    /// Has a species been picked yet (first-run gate). Never goes back to false.
    public var chosen: Bool
    /// The active mascot.
    public var current: Species
    /// LWW stamp for `current` — which mascot you last picked is cosmetic.
    public var currentRev: Rev
    /// Per-species progress; the child can switch back anytime, nothing is lost.
    public var species: SpeciesMap
    /// Stars — GLOBAL to the child, survives switches. Fold with balanceOf().
    public var stars: StarCounters
    /// Cleared (exercise, level) counts — GLOBAL to the child. Fold with ledgerOf().
    public var clears: ClearCounters

    public init(
        chosen: Bool,
        current: Species,
        currentRev: Rev,
        species: SpeciesMap,
        stars: StarCounters,
        clears: ClearCounters
    ) {
        self.chosen = chosen
        self.current = current
        self.currentRev = currentRev
        self.species = species
        self.stars = stars
        self.clears = clears
    }
}

/**
 * One child on this device. Siblings share the tablet; each keeps their own
 * mascots, stars and progress.
 */
public struct ChildProfile: Codable, Hashable, Sendable {
    /// Minted once and copied, never re-derived — no case normalisation.
    public var id: String
    /**
     * The child's first name. DEVICE-LOCAL: the sync transport strips it, so
     * the server only ever holds opaque ids and integers. A device that joins
     * the household asks the parent « Qui est-ce ? » instead of receiving a
     * name. (Invariant 10 — `WireChild` in Sync/Wire.swift has no such field.)
     */
    public var name: String
    /// LWW stamp for `name`, so a rename still merges between local replicas.
    public var nameRev: Rev
    /**
     * Date.now() of the last write to this child, anywhere. Only the delete
     * rule reads it: a tombstone wins only if nothing happened to the child
     * after it, so tidying the roster on one phone can't erase a week of play
     * on another.
     */
    public var touchedAt: Millis
    public var profile: PersistedProfile

    public init(id: String, name: String, nameRev: Rev, touchedAt: Millis, profile: PersistedProfile) {
        self.id = id
        self.name = name
        self.nameRev = nameRev
        self.touchedAt = touchedAt
        self.profile = profile
    }
}

/// Everyone who plays on this device + who's currently at the wheel.
public struct Roster: Hashable, Sendable {
    public var children: [ChildProfile]
    /**
     * The child now playing; nil shows the "Qui joue ?" welcome screen.
     * DEVICE-LOCAL and never merged — who holds this tablet says nothing about
     * who holds the other one.
     */
    public var activeId: String?
    /**
     * childId → when it was deleted. Without tombstones a delete cannot win:
     * the other device still has the child and would resurrect them on next
     * merge.
     */
    public var removed: [String: Millis]

    public init(children: [ChildProfile], activeId: String?, removed: [String: Millis]) {
        self.children = children
        self.activeId = activeId
        self.removed = removed
    }
}

extension Roster: Codable {
    private enum CodingKeys: String, CodingKey {
        case children, activeId, removed
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        children = try c.decode([ChildProfile].self, forKey: .children)
        activeId = try c.decodeIfPresent(String.self, forKey: .activeId)
        removed = try c.decode([String: Millis].self, forKey: .removed)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(children, forKey: .children)
        // JS `JSON.stringify` writes `"activeId": null` explicitly — encode the
        // optional rather than omitting it, so fixtures stay byte-comparable.
        if let activeId {
            try c.encode(activeId, forKey: .activeId)
        } else {
            try c.encodeNil(forKey: .activeId)
        }
        try c.encode(removed, forKey: .removed)
    }
}

/**
 * Runtime profile the UI reads (TS `Profile extends PersistedProfile`): the
 * persisted shape plus flat mirrors — the CURRENT species' `config`/`owned`,
 * and the two counter folds. Callers keep reading `profile.balance` /
 * `profile.ledger` / `profile.config` / `profile.owned` as plain values; only
 * ProfileStore, ProfileStorage and Sync/Merge ever see the counters underneath.
 *
 * DELIBERATELY NOT Codable (invariant 9): this is the flatten (`expose()` in
 * TS) and must never be persisted or sent. Making it non-Codable makes that a
 * compile error, not a code-review catch. Do not add a conformance.
 */
public struct ProfileView: Hashable, Sendable {
    // The persisted fields.
    public var chosen: Bool
    public var current: Species
    public var currentRev: Rev
    public var species: SpeciesMap
    public var stars: StarCounters
    public var clears: ClearCounters
    // The flat mirrors.
    /// = species[current].config
    public var config: MascotConfig
    /// = species[current].owned
    public var owned: [String]
    /// = balanceOf(stars) — spendable stars, floored at 0.
    public var balance: Int
    /// = ledgerOf(clears) — clears per (exercise, level), summed over devices.
    public var ledger: CompletionLedger

    /**
     * The flatten. The two folds are computed by the caller (ProfileStore's
     * `expose`) with `balanceOf`/`ledgerOf` from Sync/Merge.swift — passed in
     * here so this file does not depend on the merge module.
     */
    public init(of p: PersistedProfile, balance: Int, ledger: CompletionLedger) {
        self.chosen = p.chosen
        self.current = p.current
        self.currentRev = p.currentRev
        self.species = p.species
        self.stars = p.stars
        self.clears = p.clears
        let cur = p.species[p.current]
        self.config = cur.config
        self.owned = cur.owned
        self.balance = balance
        self.ledger = ledger
    }
}

/**
 * What `config` looks like with this option applied. Pure — the shop also uses
 * it to dress the live preview during a try-on, without touching the profile.
 * (Port of `applyOption` in `src/hooks/useProfile.tsx`.)
 */
public func applyOption(_ c: MascotConfig, _ o: CustomizationOption) -> MascotConfig {
    var out = c
    switch o.category {
    case .accessory:
        if !out.accessories.contains(o.id) {
            out.accessories.append(o.id)
        }
    case .color:
        out.colors[o.slot] = o.value
    case .style:
        out.styles[o.slot] = o.value
    }
    return out
}
