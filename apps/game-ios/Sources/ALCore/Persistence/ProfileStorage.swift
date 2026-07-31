import Foundation

/**
 * Persistence seam — the ONLY place in the app that reads or writes profiles.
 * Port of `src/storage.ts`.
 *
 * Every accessor is synchronous and total: a caller gets a value or a safe
 * default, never a throw. That contract is load-bearing — `ProfileStore`'s
 * roster construction and the award/spend path inside a touch-down handler
 * (invariant 1) have nowhere to await. The `KVStore` protocol (D7) is what
 * keeps it true: synchronous by signature, `UserDefaults` underneath on device.
 *
 * Schema history (Migrations.swift owns the migration logic; storage just
 * fetches the raw blobs so defaults/domain shapes live in one place):
 *   v1  single mascot: { chosen, config, balance, ledger, owned }
 *   v2  per-species progress for ONE child (PersistedProfile)
 *   v3  a Roster of named children (siblings share the device)
 *   v4  sync-safe: balance/ledger become per-device counters, LWW fields get
 *       stamps, deletes get tombstones — so the same child's progress can be
 *       merged across the family's phones without losing stars (Sync/Merge)
 *       ← current
 *
 * A format change MUST be a forward, additive migration, never a rename/reshape
 * in place: bump KEY to :vN, add a loadV(N-1) reader below, migrate old→new in
 * ProfileStore, and KEEP the old-version key + reader intact. Then even a
 * not-yet-updated (or rolled-back) launch reads a blob it still understands.
 * On iOS the rollback scenario is rarer than on the PWA, but the cross-platform
 * household keeps the rule load-bearing: an Android Capacitor device in the
 * same family may still be running v4-writing TypeScript.
 *
 * Difference from the TS, deliberately: `loadRoster` there returns the blob
 * cast to `Roster` and `normalizeRoster` runs later. Swift has no cast, so
 * `loadRoster` returns the loose shape and `Migrations.normalizeRoster` owns
 * the reshape. Same observable behaviour, one honest type.
 */
public enum ProfileStorage {
    // D7: key names byte-identical to storage.ts, forever.
    public static let rosterKey = "attrape-lettres:roster:v4"
    public static let v3Key = "attrape-lettres:roster:v3"
    public static let v2Key = "attrape-lettres:profile:v2"
    public static let v1Key = "attrape-lettres:profile:v1"
    public static let shopSeenKey = "attrape-lettres:shop-seen:v1"

    /// Decode-or-nil, matching the TS try/catch-around-JSON.parse: absent key,
    /// unparseable JSON or a wrong top-level shape all read as `nil`.
    private static func decode<T: Decodable>(_ type: T.Type, key: String, from kv: KVStore) -> T? {
        guard let raw = kv.string(key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: Data(raw.utf8))
    }

    public static func loadRoster(_ kv: KVStore) -> LooseRoster? {
        decode(LooseRoster.self, key: rosterKey, from: kv)
    }

    public static func saveRoster(_ roster: Roster, to kv: KVStore) {
        guard let data = try? JSONEncoder().encode(roster),
              let json = String(data: data, encoding: .utf8)
        else { return }
        kv.set(json, for: rosterKey)
    }

    /**
     * Balance each child LAST SAW in the shop (childId → stars). Purely
     * cosmetic: the savings meters animate from this value on entry, so stars
     * earned since the previous visit read as visible growth. Losing it costs
     * nothing but the animation, hence its own key outside the roster blob (no
     * schema bump).
     */
    public static func loadShopSeen(_ kv: KVStore) -> [String: Int] {
        decode([String: Double].self, key: shopSeenKey, from: kv)?
            .mapValues { $0.looseCount } ?? [:]
    }

    public static func saveShopSeen(_ seen: [String: Int], to kv: KVStore) {
        guard let data = try? JSONEncoder().encode(seen),
              let json = String(data: data, encoding: .utf8)
        else { return }
        kv.set(json, for: shopSeenKey)
    }

    /**
     * The v3 roster, if this device still has one to migrate. Loose: its shape
     * is the OLD `Roster` (flat `balance`/`ledger`, no stamps), which the
     * current types no longer describe. Migrations.swift owns the reshape.
     */
    public static func loadV3Roster(_ kv: KVStore) -> LegacyV3Roster? {
        decode(LegacyV3Roster.self, key: v3Key, from: kv)
    }

    /**
     * The v2 single-child profile, if this device still has one to migrate.
     * Loose for the same reason as v3: v2 and the v3 *profile* share the old
     * flat `balance`/`ledger` shape.
     *
     * NB: TS returns any parseable JSON here and lets truthiness gate the
     * migration, so a corrupt non-object value (e.g. `"garbage-string"`) would
     * still mint a "Joueur 1" child from nothing. Swift's decoder rejects a
     * non-object and falls through to v1/empty instead. Reachable only from a
     * hand-corrupted blob; the honest-data behaviour is identical.
     */
    public static func loadV2Profile(_ kv: KVStore) -> LegacyFlatProfile? {
        decode(LegacyFlatProfile.self, key: v2Key, from: kv)
    }

    /// The raw v1 single-mascot blob, if present, for migration.
    public static func loadV1Profile(_ kv: KVStore) -> LegacyV1Profile? {
        decode(LegacyV1Profile.self, key: v1Key, from: kv)
    }
}

/* Capacitor interop (D7 / persistence spec §8) ------------------------------- */

public enum CapacitorInterop {
    /**
     * `@capacitor/preferences` with no `group` configured uses its default
     * group `CapacitorStorage` and stores every pair in `UserDefaults.standard`
     * under `CapacitorStorage.<key>`. The native port adopts that prefix
     * PERMANENTLY: zero migration step, zero dual-read window, and a rollback
     * to a Capacitor build still finds its data exactly where it left it. A
     * family updating in place keeps their roster, their device id (so
     * counters keep accruing under the same key) and their household.
     */
    public static let keyPrefix = "CapacitorStorage."
}

/**
 * A `KVStore` that maps every logical key `k` to the physical key
 * `prefix + k` on a base store. `UserDefaultsKVStore` in ALPlatform is
 * expected to be exactly `PrefixedKVStore(base: <UserDefaults adapter>)`;
 * the prefixing logic lives here so it is host-testable
 * (`CapacitorInteropTests`) without a device.
 */
public final class PrefixedKVStore: KVStore {
    private let prefix: String
    private let base: KVStore

    public init(prefix: String = CapacitorInterop.keyPrefix, base: KVStore) {
        self.prefix = prefix
        self.base = base
    }

    public func string(_ key: String) -> String? {
        base.string(prefix + key)
    }

    public func set(_ value: String, for key: String) {
        base.set(value, for: prefix + key)
    }

    public func remove(_ key: String) {
        base.remove(prefix + key)
    }
}
