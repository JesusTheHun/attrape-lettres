import Foundation

/* -------------------------------------------------------------------------- */
/* Which household this device belongs to, when more than one answer arrives.   */
/*                                                                             */
/* Two mechanisms can now name a household, and they have different authority:  */
/*                                                                             */
/*   • A SCAN or a shared link is a parent doing something on purpose. It wins. */
/*   • iCloud key-value store is two devices on one Apple ID discovering each   */
/*     other with nobody watching. There is no intent to honour, so the rule    */
/*     has to be deterministic: both devices must reach the SAME answer without */
/*     talking to each other, or they ping-pong forever.                       */
/*                                                                             */
/* Both collapse into one comparison by stamping every claim. A scan stamps     */
/* `at: now`, so it beats anything already stored and then propagates through   */
/* iCloud to the family's own other devices — which is what makes "the scanned  */
/* id wins" true across a household rather than only on the phone that          */
/* scanned.                                                                    */
/*                                                                             */
/* ── Why losing is not a loss ───────────────────────────────────────────────── */
/* This is the field invariant 9 would normally forbid: a bare last-write-wins  */
/* value. It is allowed here for the same reason cosmetics are — losing it      */
/* costs nothing that cannot be recovered.                                     */
/*                                                                             */
/* Joining does not clear the roster. `joinHousehold` swaps the id and blanks   */
/* the ETag; the next `syncOnce` pulls the NEW document, merges the LOCAL       */
/* roster into it, and pushes. The week of stars on the losing device flows     */
/* into the winning household on the first sync after pairing. Nothing is       */
/* dropped, because the merge is the same per-device counter fold it always     */
/* was — see `Merge.swift`.                                                    */
/*                                                                             */
/* THE ONE REAL HAZARD, and it is not data loss: a THIRD device still pointing  */
/* at the abandoned household. It keeps its stars and keeps working, but it     */
/* stops converging with the others until it is paired too. `SyncClient` cannot */
/* detect this — the abandoned document is on a server it no longer asks about. */
/* The pairing screen says so in French rather than leaving a parent to notice  */
/* over a fortnight.                                                           */
/* -------------------------------------------------------------------------- */

/**
 * Where a household id can arrive from without anyone asking.
 *
 * The production conformance is `UbiquitousHouseholdDirectory` (ALPlatform),
 * over `NSUbiquitousKeyValueStore` — one Apple ID, every device signed into
 * it. That is the honest limit of what Apple exposes: there is NO API that
 * reveals an Apple *family*, deliberately, because a family identifier would
 * be a cross-user identifier. `Transaction.ownershipType == .familyShared`
 * says an entitlement reached you through a family and carries no key you
 * could pair on.
 *
 * So this covers one parent's phone and the family iPad, automatically and
 * with no screen. Two parents are two Apple IDs, and no amount of Apple
 * plumbing will join them — that is what the scanned link is for.
 *
 * A seam rather than a direct dependency because `swift test` runs on the
 * host, where there is no iCloud account and `NSUbiquitousKeyValueStore`
 * silently does nothing.
 */
public protocol HouseholdDirectory: AnyObject {
    /// nil when nothing has been published, or when iCloud is unavailable —
    /// which is not an error. A device with no iCloud account keeps its own
    /// household and plays exactly as before.
    func read() -> HouseholdClaim?
    func write(_ claim: HouseholdClaim)
    /// Fires when another device publishes. Replaces any previous observer.
    func observe(_ onChange: @escaping (HouseholdClaim?) -> Void)
}

/// A household id and when this device came to believe in it.
public struct HouseholdClaim: Equatable, Sendable {
    public let id: String
    public let rev: Rev

    public init(id: String, rev: Rev) {
        self.id = id
        self.rev = rev
    }
}

extension HouseholdClaim {

    /**
     * The winner of two claims, or nil when there is nothing to pick from.
     *
     * A TOTAL ORDER, deliberately, and in this order:
     *
     *  1. `rev.at` — a deliberate join is stamped now and outranks history.
     *  2. `rev.by` — the device id, so two devices stamping in the same
     *     millisecond still agree. Same tie-break `mergeRev` already uses.
     *  3. `id` — the last resort, and the rule that carries the whole
     *     iCloud case: two devices that each minted a household offline have
     *     no meaningful stamps to compare, and both must independently reach
     *     the same answer with no round trip. Lowest id wins. Which one is
     *     arbitrary; that it is the SAME one on both devices is not.
     *
     * Order-independent by construction, which is what stops two devices from
     * overwriting each other forever. `HouseholdClaimTests` asserts it over
     * every permutation rather than trusting the reading.
     */
    public static func winner(_ a: HouseholdClaim?, _ b: HouseholdClaim?) -> HouseholdClaim? {
        guard let a else { return b }
        guard let b else { return a }
        if a.rev.at != b.rev.at { return a.rev.at > b.rev.at ? a : b }
        if a.rev.by != b.rev.by { return a.rev.by > b.rev.by ? a : b }
        if a.id != b.id { return a.id < b.id ? a : b }
        return a
    }
}
