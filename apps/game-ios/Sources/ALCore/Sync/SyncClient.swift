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
