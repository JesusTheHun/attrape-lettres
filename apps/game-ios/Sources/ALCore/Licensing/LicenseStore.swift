import Foundation

/**
 * License persistence. Separate from the profile store on purpose: that one owns
 * children's profiles and its migration contract; this owns one small blob
 * about the household's purchase. Losing a profile is a tragedy, losing this is
 * one `restore()` tap.
 *
 * D18 / invariant 9: the licence accumulates nothing, so it is NOT a `Counter`
 * and it must **never** enter `sync/merge`. If household-level entitlement is
 * ever wanted (the Android Family-Library gap is a real reason to want it), it
 * belongs on the sync backend keyed by `familyId`, not in the merged profile
 * blob.
 *
 * Port of `src/licensing/persist.ts`.
 */
public struct LicenseStore {
    /// Key names are a persistence contract with the shipped PWA (D7: the
    /// `attrape-lettres:*` namespace is preserved byte for byte so a family
    /// updating in place keeps their purchase).
    public static let licenseKey = "attrape-lettres:license:v1"

    /// Has a parent seen the trial terms? Gates Onboarding, per App Review 3.1.1.
    public static let onboardedKey = "attrape-lettres:onboarded:v1"

    private let kv: KVStore

    public init(_ kv: KVStore) { self.kv = kv }

    public func load() -> LicenseState {
        guard let raw = kv.string(Self.licenseKey), !raw.isEmpty else { return .blank }
        guard let data = raw.data(using: .utf8) else { return .blank }
        // `LicenseState.init(from:)` is itself total and lenient, so this only
        // catches syntactically broken JSON. Either way the answer is `.blank`,
        // which means *full trial* — the child plays.
        guard let decoded = try? JSONDecoder().decode(LicenseState.self, from: data) else {
            return .blank
        }
        return decoded
    }

    public func save(_ s: LicenseState) {
        // The store is re-queried every launch; a lost write costs one refresh.
        guard let data = try? JSONEncoder().encode(s) else { return }
        guard let json = String(data: data, encoding: .utf8) else { return }
        kv.set(json, for: Self.licenseKey)
    }

    public func loadOnboarded() -> Bool {
        kv.string(Self.onboardedKey) == "1"
    }

    public func saveOnboarded() {
        kv.set("1", for: Self.onboardedKey)
    }
}
