import Foundation

// The JSON tolerance layer — all-optional Decodable mirrors of every shape this
// app has ever written to disk: the current v4 roster plus the legacy v1/v2/v3
// blobs (`LegacyV1Profile`, `LegacyFlatProfile`, `LegacyV3Roster` and the
// `Loose*` types from `src/hooks/useProfile.tsx`).
//
// Why it exists: in TS, `loadRoster` is `JSON.parse` + a cast, and
// `normalizeProfile`/`normalizeRoster` default every missing field. Swift has
// no cast, so the tolerance moves into decoding: every field is optional, every
// field decodes through `try?` so ONE wrong-typed field degrades to "absent"
// (and takes its normalisation default) instead of failing the whole blob and
// reading as "no roster". A partially-corrupt blob degrades; it does not erase
// a child.
//
// Numbers decode as `Double?` because that is what a JS number IS — every blob
// this app ever wrote holds integers (`Date.now()`, integer stars), but a
// strict `Int` decode of a hypothetical `17.0` would throw the whole roster
// away (spec risk #6). Normalisation truncates to `Int`/`Millis`, which is the
// identity on all real data.
//
// These types are DECODE-ONLY (no Encodable) — persisting a loose shape is not
// a thing. The memberwise inits exist for the migrators and the tests.

/// Loose `Rev`. TS keeps a partial rev object as-is; normalisation defaults the
/// missing halves to `0` / `""` — identical for every blob the app ever wrote.
public struct LooseRev: Decodable {
    public var at: Double?
    public var by: String?

    public init(at: Double? = nil, by: String? = nil) {
        self.at = at
        self.by = by
    }

    private enum CodingKeys: String, CodingKey { case at, by }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        at = (try? c.decodeIfPresent(Double.self, forKey: .at)) ?? nil
        by = (try? c.decodeIfPresent(String.self, forKey: .by)) ?? nil
    }
}

/// Loose `MascotConfig`. `species` stays a raw String here — unknown species
/// are dropped (map keys) or defaulted (`current`) during normalisation.
public struct LooseMascotConfig: Decodable {
    public var species: String?
    public var stage: Double?
    public var colors: [String: String]?
    public var styles: [String: String]?
    public var accessories: [String]?

    public init(
        species: String? = nil,
        stage: Double? = nil,
        colors: [String: String]? = nil,
        styles: [String: String]? = nil,
        accessories: [String]? = nil
    ) {
        self.species = species
        self.stage = stage
        self.colors = colors
        self.styles = styles
        self.accessories = accessories
    }

    private enum CodingKeys: String, CodingKey { case species, stage, colors, styles, accessories }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        species = (try? c.decodeIfPresent(String.self, forKey: .species)) ?? nil
        stage = (try? c.decodeIfPresent(Double.self, forKey: .stage)) ?? nil
        colors = (try? c.decodeIfPresent([String: String].self, forKey: .colors)) ?? nil
        styles = (try? c.decodeIfPresent([String: String].self, forKey: .styles)) ?? nil
        accessories = (try? c.decodeIfPresent([String].self, forKey: .accessories)) ?? nil
    }
}

/// TS `LooseProgress` — anything shaped enough to be read as progress: a v4
/// species slot (with `rev`) or a legacy v2/v3 one (without).
public struct LooseSpeciesProgress: Decodable {
    public var config: LooseMascotConfig?
    public var owned: [String]?
    public var rev: LooseRev?

    public init(config: LooseMascotConfig? = nil, owned: [String]? = nil, rev: LooseRev? = nil) {
        self.config = config
        self.owned = owned
        self.rev = rev
    }

    private enum CodingKeys: String, CodingKey { case config, owned, rev }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        config = (try? c.decodeIfPresent(LooseMascotConfig.self, forKey: .config)) ?? nil
        owned = (try? c.decodeIfPresent([String].self, forKey: .owned)) ?? nil
        rev = (try? c.decodeIfPresent(LooseRev.self, forKey: .rev)) ?? nil
    }
}

public struct LooseStarCounters: Decodable {
    public var earned: [String: Double]?
    public var spent: [String: Double]?

    public init(earned: [String: Double]? = nil, spent: [String: Double]? = nil) {
        self.earned = earned
        self.spent = spent
    }

    private enum CodingKeys: String, CodingKey { case earned, spent }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        earned = (try? c.decodeIfPresent([String: Double].self, forKey: .earned)) ?? nil
        spent = (try? c.decodeIfPresent([String: Double].self, forKey: .spent)) ?? nil
    }
}

/// TS `LooseProfile` — a v4 profile blob, or anything shaped enough to pass.
public struct LooseProfile: Decodable {
    public var chosen: Bool?
    public var current: String?
    public var currentRev: LooseRev?
    public var species: [String: LooseSpeciesProgress]?
    public var stars: LooseStarCounters?
    public var clears: [String: [String: Double]]?

    public init(
        chosen: Bool? = nil,
        current: String? = nil,
        currentRev: LooseRev? = nil,
        species: [String: LooseSpeciesProgress]? = nil,
        stars: LooseStarCounters? = nil,
        clears: [String: [String: Double]]? = nil
    ) {
        self.chosen = chosen
        self.current = current
        self.currentRev = currentRev
        self.species = species
        self.stars = stars
        self.clears = clears
    }

    private enum CodingKeys: String, CodingKey { case chosen, current, currentRev, species, stars, clears }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chosen = (try? c.decodeIfPresent(Bool.self, forKey: .chosen)) ?? nil
        current = (try? c.decodeIfPresent(String.self, forKey: .current)) ?? nil
        currentRev = (try? c.decodeIfPresent(LooseRev.self, forKey: .currentRev)) ?? nil
        species = (try? c.decodeIfPresent([String: LooseSpeciesProgress].self, forKey: .species)) ?? nil
        stars = (try? c.decodeIfPresent(LooseStarCounters.self, forKey: .stars)) ?? nil
        clears = (try? c.decodeIfPresent([String: [String: Double]].self, forKey: .clears)) ?? nil
    }
}

public struct LooseChild: Decodable {
    public var id: String?
    public var name: String?
    public var nameRev: LooseRev?
    public var touchedAt: Double?
    public var profile: LooseProfile?

    public init(
        id: String? = nil,
        name: String? = nil,
        nameRev: LooseRev? = nil,
        touchedAt: Double? = nil,
        profile: LooseProfile? = nil
    ) {
        self.id = id
        self.name = name
        self.nameRev = nameRev
        self.touchedAt = touchedAt
        self.profile = profile
    }

    private enum CodingKeys: String, CodingKey { case id, name, nameRev, touchedAt, profile }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil
        nameRev = (try? c.decodeIfPresent(LooseRev.self, forKey: .nameRev)) ?? nil
        touchedAt = (try? c.decodeIfPresent(Double.self, forKey: .touchedAt)) ?? nil
        profile = (try? c.decodeIfPresent(LooseProfile.self, forKey: .profile)) ?? nil
    }
}

/// The loose v4 roster — what `ProfileStorage.loadRoster` actually returns.
/// `Migrations.normalizeRoster` owns the reshape into a strict `Roster`.
public struct LooseRoster: Decodable {
    public var children: [LooseChild]?
    public var activeId: String?
    public var removed: [String: Double]?

    public init(children: [LooseChild]? = nil, activeId: String? = nil, removed: [String: Double]? = nil) {
        self.children = children
        self.activeId = activeId
        self.removed = removed
    }

    private enum CodingKeys: String, CodingKey { case children, activeId, removed }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        children = (try? c.decodeIfPresent([LooseChild].self, forKey: .children)) ?? nil
        activeId = (try? c.decodeIfPresent(String.self, forKey: .activeId)) ?? nil
        removed = (try? c.decodeIfPresent([String: Double].self, forKey: .removed)) ?? nil
    }
}

/* Legacy shapes ------------------------------------------------------------- */

/// Old v1 shape (single mascot), kept only so we can migrate it forward.
public struct LegacyV1Profile: Decodable {
    public var chosen: Bool?
    public var config: LooseMascotConfig?
    public var balance: Double?
    public var ledger: [String: Double]?
    public var owned: [String]?

    public init(
        chosen: Bool? = nil,
        config: LooseMascotConfig? = nil,
        balance: Double? = nil,
        ledger: [String: Double]? = nil,
        owned: [String]? = nil
    ) {
        self.chosen = chosen
        self.config = config
        self.balance = balance
        self.ledger = ledger
        self.owned = owned
    }

    private enum CodingKeys: String, CodingKey { case chosen, config, balance, ledger, owned }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chosen = (try? c.decodeIfPresent(Bool.self, forKey: .chosen)) ?? nil
        config = (try? c.decodeIfPresent(LooseMascotConfig.self, forKey: .config)) ?? nil
        balance = (try? c.decodeIfPresent(Double.self, forKey: .balance)) ?? nil
        ledger = (try? c.decodeIfPresent([String: Double].self, forKey: .ledger)) ?? nil
        owned = (try? c.decodeIfPresent([String].self, forKey: .owned)) ?? nil
    }
}

/**
 * The v2/v3 profile shape — identical to each other, and to v4 except that
 * stars and clears were bare running totals. One migrator serves both.
 */
public struct LegacyFlatProfile: Decodable {
    public var chosen: Bool?
    public var current: String?
    public var species: [String: LooseSpeciesProgress]?
    public var balance: Double?
    public var ledger: [String: Double]?

    public init(
        chosen: Bool? = nil,
        current: String? = nil,
        species: [String: LooseSpeciesProgress]? = nil,
        balance: Double? = nil,
        ledger: [String: Double]? = nil
    ) {
        self.chosen = chosen
        self.current = current
        self.species = species
        self.balance = balance
        self.ledger = ledger
    }

    private enum CodingKeys: String, CodingKey { case chosen, current, species, balance, ledger }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chosen = (try? c.decodeIfPresent(Bool.self, forKey: .chosen)) ?? nil
        current = (try? c.decodeIfPresent(String.self, forKey: .current)) ?? nil
        species = (try? c.decodeIfPresent([String: LooseSpeciesProgress].self, forKey: .species)) ?? nil
        balance = (try? c.decodeIfPresent(Double.self, forKey: .balance)) ?? nil
        ledger = (try? c.decodeIfPresent([String: Double].self, forKey: .ledger)) ?? nil
    }
}

public struct LegacyV3Child: Decodable {
    public var id: String?
    public var name: String?
    public var profile: LegacyFlatProfile?

    public init(id: String? = nil, name: String? = nil, profile: LegacyFlatProfile? = nil) {
        self.id = id
        self.name = name
        self.profile = profile
    }

    private enum CodingKeys: String, CodingKey { case id, name, profile }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil
        profile = (try? c.decodeIfPresent(LegacyFlatProfile.self, forKey: .profile)) ?? nil
    }
}

public struct LegacyV3Roster: Decodable {
    public var children: [LegacyV3Child]?
    public var activeId: String?

    public init(children: [LegacyV3Child]? = nil, activeId: String? = nil) {
        self.children = children
        self.activeId = activeId
    }

    private enum CodingKeys: String, CodingKey { case children, activeId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        children = (try? c.decodeIfPresent([LegacyV3Child].self, forKey: .children)) ?? nil
        activeId = (try? c.decodeIfPresent(String.self, forKey: .activeId)) ?? nil
    }
}

/* JS-number → integer conversion --------------------------------------------- */

extension Double {
    /// JS number → count. Truncates toward zero (the identity on every value
    /// the app ever wrote) and stays total on garbage: NaN → 0, out-of-range
    /// clamps instead of trapping.
    var looseCount: Int {
        if isNaN { return 0 }
        if self >= Double(Int.max) { return Int.max }
        if self <= Double(Int.min) { return Int.min }
        return Int(self)
    }

    /// JS number → epoch milliseconds, same totality rules as `looseCount`.
    var looseMillis: Millis {
        if isNaN { return 0 }
        if self >= Double(Millis.max) { return Millis.max }
        if self <= Double(Millis.min) { return Millis.min }
        return Millis(self)
    }
}

/// `[deviceId: Double]` → `Counter`, truncating each value.
func looseCounter(_ raw: [String: Double]?) -> Counter {
    (raw ?? [:]).mapValues { $0.looseCount }
}

/// Loose clears → `ClearCounters`, truncating each per-device value.
func looseClears(_ raw: [String: [String: Double]]?) -> ClearCounters {
    (raw ?? [:]).mapValues { $0.mapValues { $0.looseCount } }
}

/// Loose tombstones → `[childId: Millis]`.
func looseTombstones(_ raw: [String: Double]?) -> [String: Millis] {
    (raw ?? [:]).mapValues { $0.looseMillis }
}
