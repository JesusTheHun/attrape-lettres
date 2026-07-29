import ALCore
import Foundation

/* -------------------------------------------------------------------------- */
/* The one key/value primitive under every persistence call site — the iOS side */
/* of `src/kv.ts`.                                                             */
/*                                                                             */
/* SYNCHRONOUS, because `KVStore` is synchronous by signature (D7 / invariant   */
/* 1): `award`/`spend` run inside a touch-down handler and there is nowhere to  */
/* await on that path. `UserDefaults` is already synchronous, so the whole      */
/* `hydrateKv()` dance the TypeScript needs on native (Capacitor Preferences is */
/* async) simply disappears — there is nothing to await at boot.                */
/*                                                                             */
/* KEYS KEEP THE `CapacitorStorage.` PREFIX. That is a persistence contract,    */
/* not a legacy wart: `@capacitor/preferences` with no `group` configured       */
/* writes `UserDefaults.standard` under `CapacitorStorage.<key>`, so an         */
/* in-place App Store update over the shipped Capacitor build finds the         */
/* family's roster, their stars, their device id (counters keep accruing under  */
/* the same key) and their household membership exactly where they were.       */
/* Getting this wrong orphans real children's saved progress, and to a          */
/* six-year-old that is indistinguishable from the app deleting them.          */
/* It is also bidirectional: a rollback to a Capacitor build still reads its    */
/* own data. (persistence.md §8, D7.)                                          */
/* -------------------------------------------------------------------------- */

/**
 * The unprefixed adapter: one logical key, one `UserDefaults` key, no
 * namespacing. Production never uses this directly — `UserDefaultsKVStore`
 * wraps it in ALCore's `PrefixedKVStore` — but it is the seam a test (or a
 * future migration that has to read a raw key) needs.
 */
public final class UserDefaultsBackingStore: KVStore {
    public let defaults: UserDefaults

    public init(_ defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func string(_ key: String) -> String? {
        defaults.string(forKey: key)
    }

    public func set(_ value: String, for key: String) {
        defaults.set(value, forKey: key)
    }

    public func remove(_ key: String) {
        defaults.removeObject(forKey: key)
    }
}

/**
 * The production store. Every logical key `k` resolves to the physical
 * `UserDefaults` key `"CapacitorStorage." + k`.
 *
 * The prefixing itself is ALCore's `PrefixedKVStore` (host-tested by
 * `CapacitorInteropTests` with no device); this type is the composition of that
 * with `UserDefaults`, which is all persistence.md §8 asks for:
 * "`UserDefaultsKVStore` … is expected to be exactly
 * `PrefixedKVStore(base: <UserDefaults adapter>)`".
 *
 * Note what is deliberately absent: no `synchronize()`. `cfprefsd` flushes on
 * its own and even on a kill; calling it chases a phantom (persistence.md §7
 * R7). And no error channel — `kv.ts` swallowed private-mode/quota throws,
 * `UserDefaults` has no equivalent failure, so a write that cannot happen
 * simply does not.
 */
public final class UserDefaultsKVStore: KVStore {
    /// `CapacitorStorage.` — re-exported so a call site can name it without
    /// importing the interop enum.
    public static let keyPrefix = CapacitorInterop.keyPrefix

    /// The raw defaults database behind the prefix. Tests use it to assert on
    /// the physical keys; production does not touch it.
    public let defaults: UserDefaults

    private let prefix: String
    private let namespaced: KVStore

    public init(defaults: UserDefaults = .standard, prefix: String = CapacitorInterop.keyPrefix) {
        self.defaults = defaults
        self.prefix = prefix
        self.namespaced = PrefixedKVStore(prefix: prefix, base: UserDefaultsBackingStore(defaults))
    }

    /**
     * Convenience for the app layer: the store backed by a named suite. Passing
     * `nil` (or a suite that fails to open) falls back to `.standard`, which is
     * where the Capacitor build's data actually lives — an app that silently
     * opened an empty suite would look exactly like an app that lost the roster.
     */
    public convenience init(suiteName: String?) {
        if let suiteName, let suite = UserDefaults(suiteName: suiteName) {
            self.init(defaults: suite)
        } else {
            self.init(defaults: .standard)
        }
    }

    /// The physical `UserDefaults` key a logical key resolves to. Public because
    /// it is the migration contract, and a contract nobody can read is a
    /// contract nobody can check.
    public func physicalKey(_ key: String) -> String { prefix + key }

    public func string(_ key: String) -> String? { namespaced.string(key) }

    public func set(_ value: String, for key: String) { namespaced.set(value, for: key) }

    public func remove(_ key: String) { namespaced.remove(key) }
}
