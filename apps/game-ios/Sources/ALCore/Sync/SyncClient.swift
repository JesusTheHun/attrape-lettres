import Foundation

/* -------------------------------------------------------------------------- */
/* Household sync — the transport around Merge.swift. Port of                   */
/* `src/sync/client.ts`.                                                        */
/*                                                                             */
/* No accounts, no e-mail, no login. One device creates a household and shows a */
/* short code; another types it in. That is the entire identity model, and it   */
/* is why the server holds nothing but opaque ids and integers.                 */
/*                                                                             */
/* Sync runs on mount and on resume, NEVER on a write — gameplay stays          */
/* offline-first, and a child mid-round must never wait on the network. Any     */
/* transport error is swallowed by the caller (`ProfileStore.syncNow`) and the  */
/* device keeps playing alone — invariant 11's philosophy applied to sync.      */
/* -------------------------------------------------------------------------- */

/**
 * The transport seam. `URLSessionSyncTransport` (ALPlatform) is the production
 * conformance: GET/PUT `{endpoint}/household/{id}` with ETag optimistic
 * concurrency — the server rejects a write built on a stale read (412 →
 * `.conflict`), so a simultaneous save from the other phone is never clobbered.
 *
 * NB: ARCHITECTURE.md §5 sketches this as a single
 * `exchange(_:) async throws -> WireRoster`. That shape cannot express the
 * pull → merge locally → push-with-ETag loop the TS actually runs (the merge
 * happens on the DEVICE between the two calls, and a 412 must re-pull), so the
 * two-method protocol from persistence.md §3 is kept — the behaviour is frozen
 * and `exchange` cannot reproduce it.
 */
public protocol SyncTransport {
    /// `nil` when the household document does not exist yet (HTTP 404).
    func pull(household: String) async throws -> (roster: WireRoster, etag: String)?
    /// Resolves `.conflict` when `etag` is stale — the caller re-pulls and retries.
    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult
}

public enum PushResult: Equatable, Sendable {
    case ok(etag: String)
    case conflict
}

public final class SyncClient {
    public static let householdKey = "attrape-lettres:household:v1"
    public static let etagKey = "attrape-lettres:household-etag:v1"
    /// The stamp on `householdKey`, split in two because `KVStore` stores
    /// strings and a composite would need an encoding nobody else reads.
    /// ADDITIVE: D7 freezes the two names above as a migration contract with
    /// the shipped PWA, and these are new keys the PWA simply never writes —
    /// absent reads as `Rev.zero`, which is the correct answer for a household
    /// that predates the stamp.
    public static let householdRevAtKey = "attrape-lettres:household-rev-at:v1"
    public static let householdRevByKey = "attrape-lettres:household-rev-by:v1"

    private let kv: KVStore
    private let transport: SyncTransport
    /**
     * The `VITE_SYNC_URL` analogue, injected and read LAZILY (per call, not at
     * construction) so tests can stub it — and so "endpoint absent ⇒ sync
     * disabled, silently" survives the port.
     */
    private let endpoint: () -> String?

    public init(kv: KVStore, transport: SyncTransport, endpoint: @escaping () -> String?) {
        self.kv = kv
        self.transport = transport
        self.endpoint = endpoint
    }

    /* -- household identity -------------------------------------------------*/

    public func householdId() -> String? {
        kv.string(Self.householdKey)
    }

    public func joinHousehold(_ id: String) {
        kv.set(id, for: Self.householdKey)
        kv.set("", for: Self.etagKey)
    }

    /* -- the stamp, and the two ways a household arrives ---------------------*/

    /**
     * What this device currently believes, as a comparable claim.
     *
     * An id stored before this stamp existed — a device that paired on an
     * older build, or a household minted by the PWA — reads as `Rev.zero` and
     * therefore loses every comparison. That is deliberate and it is safe:
     * losing swaps the id, it does not clear the roster, so the local progress
     * merges into the winning household on the next sync (`HouseholdClaim`'s
     * header). A legacy device converging onto the family's household is the
     * outcome we want, not a regression to guard against.
     */
    public func claim() -> HouseholdClaim? {
        guard let id = householdId(), !id.isEmpty else { return nil }
        return HouseholdClaim(id: id, rev: storedRev())
    }

    /**
     * Join because somebody meant to — a scanned QR, a shared link.
     *
     * Stamped with the current time so it outranks whatever was stored and,
     * once written to the directory, outranks what the family's other devices
     * hold too. This is what makes "the scanned id wins" true beyond the phone
     * that did the scanning.
     *
     * Returns false for an id this app would never mint. The caller has
     * nothing to do about that except ignore it (invariant 3: a bad link is
     * not an error state a family has to clear).
     */
    @discardableResult
    public func join(_ id: String, at now: Millis, by device: String) -> Bool {
        guard PairingLink.isWellFormed(id) else { return false }
        adopt(HouseholdClaim(id: id, rev: Rev(at: now, by: device)))
        return true
    }

    /**
     * Reconcile with a household id that arrived without anyone asking —
     * iCloud key-value store, i.e. the same Apple ID on another device.
     *
     * Returns the household this device now belongs to. Writes back whenever
     * the local claim wins, so the two devices converge from both directions
     * rather than one of them silently deferring forever.
     *
     * Every branch here is total. There is no failure mode that leaves the
     * device without a household it can play offline against.
     */
    @discardableResult
    public func reconcile(with directory: HouseholdClaim?) -> HouseholdClaim? {
        let local = claim()
        guard let winner = HouseholdClaim.winner(local, directory) else { return nil }
        if winner != local { adopt(winner) }
        return winner
    }

    /// Swap identity and blank the ETag — the next pull is unconditional, and
    /// the local roster merges into whatever it finds.
    private func adopt(_ claim: HouseholdClaim) {
        kv.set(claim.id, for: Self.householdKey)
        kv.set(String(claim.rev.at), for: Self.householdRevAtKey)
        kv.set(claim.rev.by, for: Self.householdRevByKey)
        kv.set("", for: Self.etagKey)
    }

    private func storedRev() -> Rev {
        let at = kv.string(Self.householdRevAtKey).flatMap(Millis.init) ?? 0
        return Rev(at: at, by: kv.string(Self.householdRevByKey) ?? "")
    }

    @discardableResult
    public func createHousehold() -> String {
        // TS mints `crypto.randomUUID()` (lowercase); the `h_<ts36><rand36>`
        // fallback has no Swift equivalent failure mode — `UUID()` cannot fail.
        let id = UUID().uuidString.lowercased()
        joinHousehold(id)
        return id
    }

    /// Endpoint configured AND household joined. TS `syncEnabled()` — its
    /// truthiness checks make an empty string count as absent; mirrored here.
    public var enabled: Bool {
        hasEndpoint && hasHousehold
    }

    private var hasEndpoint: Bool {
        guard let e = endpoint() else { return false }
        return !e.isEmpty
    }

    private var hasHousehold: Bool {
        guard let h = householdId() else { return false }
        return !h.isEmpty
    }

    /* -- the one operation --------------------------------------------------*/

    /**
     * Pull, merge, push. Returns the roster this device should now hold.
     *
     * Idempotent and safe to call on every resume: Merge.swift guarantees that
     * repeating it changes nothing. On a 412 we re-pull and merge again rather
     * than forcing — the other phone's write is somebody's stars.
     *
     * NB oddity, ported as-is: the ETag stored under `etagKey` is written but
     * never read back by this loop (each attempt uses the etag from its own
     * pull). It exists as state; keep it.
     */
    public func syncOnce(local: Roster) async throws -> Roster {
        guard hasHousehold, hasEndpoint, let household = householdId() else { return local }
        var local = local

        for _ in 0..<3 {
            let remote = try await transport.pull(household: household)
            let merged = remote.map { mergeRoster(local, fromWire($0.roster, local: local)) } ?? local
            let result = try await transport.push(
                household: household,
                roster: toWire(merged),
                etag: remote?.etag
            )
            switch result {
            case .ok(let etag):
                kv.set(etag, for: Self.etagKey)
                return merged
            case .conflict:
                // Someone else wrote between our read and our write. Loop: pull
                // their version, merge on top, try again. Never `force`.
                local = merged
            }
        }
        // Three collisions in a row: keep the merged local state and try next resume.
        return local
    }
}
