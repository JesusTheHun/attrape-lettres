// The key/value primitive — the Swift shape of `src/kv.ts`.
//
// D7: ONE protocol, one UserDefaults suite, one namespace, for all five call
// sites (licensing, telemetry consent, device identity, profile storage, sync).
//
// SYNCHRONOUS BY SIGNATURE. This is invariant 1 expressed in the type system:
// an adapter that wants to `await` simply cannot conform. It is the same reason
// `kv.ts` hydrates once at boot and keeps every read synchronous thereafter —
// `award`/`spend` run inside a pointerdown handler, before React (here:
// SwiftUI) commits, and there is nowhere to await on that path.
//
// The web original swallowed every throw (private mode, quota). iOS UserDefaults
// has no equivalent failure, so the protocol has no error channel; an adapter
// that cannot write simply does not.

public protocol KVStore: AnyObject {
    func string(_ key: String) -> String?
    func set(_ value: String, for key: String)
    func remove(_ key: String)
}

extension KVStore {
    // NB: `persistence.md` and `money.md` spell these `get(_:)` / `set(_:_:)`
    // (key first) while ARCHITECTURE.md §5 — which wins — spells them
    // `string(_:)` / `set(_:for:)`. Both spellings compile; there is one
    // requirement to implement.
    public func get(_ key: String) -> String? { string(key) }
    public func set(_ key: String, _ value: String) { set(value, for: key) }
}

/// `money.md` names the protocol `KeyValueStore`. Same type.
public typealias KeyValueStore = KVStore

/// The test and preview backing. Not thread-safe on purpose — everything that
/// touches storage in this app is main-actor bound.
public final class InMemoryKVStore: KVStore {
    private var storage: [String: String]

    public init(_ storage: [String: String] = [:]) {
        self.storage = storage
    }

    public func string(_ key: String) -> String? { storage[key] }

    public func set(_ value: String, for key: String) { storage[key] = value }

    public func remove(_ key: String) { storage.removeValue(forKey: key) }

    /// Everything currently stored. Tests assert over this; production never calls it.
    public var snapshot: [String: String] { storage }

    /// Wipes the store. Tests only.
    public func removeAll() { storage.removeAll() }
}
