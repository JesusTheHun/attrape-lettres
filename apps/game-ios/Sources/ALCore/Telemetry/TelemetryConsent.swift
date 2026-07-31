/**
 * Analytics consent, given by the PARENT. France set the GDPR Art. 8 digital
 * consent age at 15, so a six-year-old tapping "Oui" is not consent — which is
 * why the toggle only ever appears on parent-facing screens (Onboarding, and
 * the parental-gated settings so withdrawal is as easy as giving).
 *
 * Device-scoped, never synced: consent belongs to the adult holding this phone,
 * not to the household.
 *
 * Three states, and the distinction is tested: unset (never asked), `"0"`
 * (refused), `"1"` (given). **Unanswered is not consent, and it is not
 * refusal either.**
 *
 * NB: this type reads and writes the key; it deliberately has no public setter.
 * Withdrawal must also **drain the pending queue**, which only `Telemetry` can
 * do, so the public write path is `Telemetry.setConsent(_:)`. Wiring a screen
 * to a setter here instead would make a withdrawal non-retroactive.
 */
public struct TelemetryConsent {
    public static let key = "attrape-lettres:consent:v1"

    private let kv: KVStore

    public init(_ kv: KVStore) { self.kv = kv }

    /// `hasConsent()`.
    public var has: Bool { kv.string(Self.key) == "1" }

    /// `consentAnswered()` — has the parent been asked at all yet? (unset ≠ refused)
    public var answered: Bool { kv.string(Self.key) != nil }

    /// Internal on purpose. See the note above: go through `Telemetry.setConsent`.
    func write(_ on: Bool) {
        kv.set(on ? "1" : "0", for: Self.key)
    }
}
