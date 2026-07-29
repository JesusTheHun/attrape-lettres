import Foundation

/**
 * This device's identity — the key every counter in a profile is indexed by.
 * Port of `src/device.ts`.
 *
 * It is NOT an identifier for a person. It is generated locally, never leaves
 * the household record, is not tied to the child, the parent, the OS or the
 * hardware, and a reinstall mints a fresh one. That is deliberate: it exists so
 * two replicas of the same child's progress can be merged without losing stars
 * (see Sync/Merge.swift), and for nothing else. Never send it to an analytics
 * endpoint — that is what makes the telemetry payload genuinely anonymous
 * rather than merely pseudonymous. (It DOES appear inside Counter keys uploaded
 * to the household record — that is by design.)
 *
 * This is also why the backing store is UserDefaults through `KVStore` and
 * NOT the Keychain: Keychain survives an app uninstall, which would silently
 * turn this into a longer-lived identifier — the opposite of the privacy
 * posture.
 *
 * Lazy + memoised, as in TS. The TS laziness existed because the id sat behind
 * kv.ts's boot-time hydration; UserDefaults has no hydration step, but the
 * shape is correct and free, so it stays. Reads are synchronous — the
 * award/spend path runs inside a touch-down handler and cannot await
 * (invariant 1).
 *
 * The key rides the Capacitor prefix (D7 — the injected store is prefixed), so
 * an in-place update from the Capacitor build KEEPS the same device id and
 * existing counters keep accruing under the same key.
 */
public final class DeviceIdentity {
    public static let storageKey = "attrape-lettres:device:v1"

    private let kv: KVStore
    private var cached: String?

    public init(kv: KVStore) {
        self.kv = kv
    }

    /// TS `mint()` — `crypto.randomUUID()`, lowercase like JS emits. The
    /// `d_<ts36>_<rand36>` fallback path (crypto unavailable) has no Swift
    /// equivalent failure mode; `UUID()` cannot fail.
    private func mint() -> String {
        UUID().uuidString.lowercased()
    }

    private func read() -> String {
        // TS `if (saved) return saved` — an empty string is falsy and re-mints.
        if let saved = kv.string(Self.storageKey), !saved.isEmpty {
            return saved
        }
        let fresh = mint()
        // If the write fails this is a per-launch id. That still merges
        // correctly — it just adds a counter key per launch. Stars stay exact,
        // which is the only thing that must not degrade. (On iOS UserDefaults
        // writes do not fail; the comment travels anyway.)
        kv.set(fresh, for: Self.storageKey)
        return fresh
    }

    /// This device's stable id.
    public func deviceId() -> String {
        if let cached { return cached }
        let id = read()
        cached = id
        return id
    }

    /// Tests only — forget the cached id so a spec can act as a different
    /// device. Port of `__resetDeviceId`.
    @discardableResult
    public func _reset(_ id: String? = nil) -> String {
        if let id {
            kv.set(id, for: Self.storageKey)
        } else {
            kv.remove(Self.storageKey)
        }
        cached = nil
        return deviceId()
    }
}
