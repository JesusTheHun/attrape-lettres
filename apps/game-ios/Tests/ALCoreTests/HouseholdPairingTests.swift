import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Pairing, through `SyncClient` and the storage it actually writes.            */
/*                                                                             */
/* The claim this file keeps honest is the one a parent would be angriest       */
/* about if it were false: joining a household does not cost you the stars you  */
/* already had.                                                                 */
/* -------------------------------------------------------------------------- */

private let alpha = "1111aaaa-2222-4bbb-8ccc-333344445555"
private let omega = "9999ffff-8888-4eee-8ddd-777766665555"

private func client(_ kv: MemoryKV = MemoryKV()) -> SyncClient {
    SyncClient(kv: kv, transport: RecordingTransport(), endpoint: { "https://s.test" })
}

@Suite("Pairing — a scan is intent")
struct PairingIntentTests {

    @Test("joining stores the id, stamped")
    func joinStamps() {
        let kv = MemoryKV()
        let sync = client(kv)
        #expect(sync.join(alpha, at: 1_700, by: "phone-a"))
        #expect(sync.householdId() == alpha)
        #expect(sync.claim() == HouseholdClaim(id: alpha, rev: Rev(at: 1_700, by: "phone-a")))
    }

    @Test("a link the app would never mint is refused, and changes nothing")
    func refusesRubbish() {
        let sync = client()
        sync.join(alpha, at: 10, by: "phone-a")
        #expect(!sync.join("../../someone-else", at: 99, by: "phone-a"))
        #expect(sync.householdId() == alpha, "a bad link must not unpair a working device")
    }

    @Test("re-pairing blanks the ETag so the next pull is unconditional")
    func joinBlanksEtag() {
        let kv = MemoryKV()
        let sync = client(kv)
        sync.join(alpha, at: 10, by: "phone-a")
        kv.set("\"42\"", for: SyncClient.etagKey)
        sync.join(omega, at: 20, by: "phone-a")
        #expect(kv.string(SyncClient.etagKey) == "")
    }

    @Test("joining does not touch the roster — this is why losing is not a loss")
    func joinKeepsLocalData() {
        // The whole safety argument for a last-write-wins household id. The
        // roster stays put, so the next `syncOnce` merges it INTO the newly
        // joined household rather than replacing it.
        let kv = MemoryKV()
        kv.set("a week of stars", for: "attrape-lettres:roster:v4")
        let sync = client(kv)
        sync.join(omega, at: 20, by: "phone-a")
        #expect(kv.string("attrape-lettres:roster:v4") == "a week of stars")
    }

    @Test("a household stored before the stamp existed reads as zero and loses")
    func legacyHouseholdHasNoStamp() {
        let kv = MemoryKV()
        // What an older build, or the PWA, left behind: an id and no stamp.
        kv.set(omega, for: SyncClient.householdKey)
        let sync = client(kv)
        #expect(sync.claim() == HouseholdClaim(id: omega, rev: .zero))
        let scanned = HouseholdClaim(id: alpha, rev: Rev(at: 5, by: "ipad"))
        #expect(sync.reconcile(with: scanned)?.id == alpha)
    }
}

@Suite("Pairing — iCloud is not intent")
struct PairingDirectoryTests {

    @Test("a device with no household adopts what iCloud offers")
    func adoptsFromDirectory() {
        let sync = client()
        let offered = HouseholdClaim(id: alpha, rev: Rev(at: 3, by: "ipad"))
        #expect(sync.reconcile(with: offered)?.id == alpha)
        #expect(sync.householdId() == alpha)
    }

    @Test("a fresher local claim survives iCloud and is handed back to write out")
    func localWins() {
        let sync = client()
        sync.join(omega, at: 900, by: "phone-a")
        let stale = HouseholdClaim(id: alpha, rev: Rev(at: 3, by: "ipad"))
        #expect(sync.reconcile(with: stale)?.id == omega)
        #expect(sync.householdId() == omega, "iCloud must not undo a deliberate pairing")
    }

    @Test("nothing anywhere stays nothing, without inventing a household")
    func bothEmpty() {
        let sync = client()
        #expect(sync.reconcile(with: nil) == nil)
        #expect(sync.householdId() == nil)
    }

    @Test("reconciling twice changes nothing the second time")
    func idempotent() {
        let sync = client()
        let offered = HouseholdClaim(id: alpha, rev: Rev(at: 3, by: "ipad"))
        let first = sync.reconcile(with: offered)
        let second = sync.reconcile(with: offered)
        #expect(first == second)
    }

    @Test("two devices that each minted a household offline land on the same one")
    func convergesWithoutStamps() {
        // Neither stamp means anything and there is no round trip. Both
        // devices must pick the same household from the same two ids.
        let phone = client()
        phone.joinHousehold(omega)
        let ipad = client()
        ipad.joinHousehold(alpha)

        let phoneResult = phone.reconcile(with: ipad.claim())
        let ipadResult = ipad.reconcile(with: phone.claim())
        #expect(phoneResult?.id == ipadResult?.id)
        #expect(phoneResult?.id == alpha)
    }
}

// MARK: - Doubles

private final class MemoryKV: KVStore, @unchecked Sendable {
    private var storage: [String: String] = [:]
    func string(_ key: String) -> String? { storage[key] }
    func set(_ value: String, for key: String) { storage[key] = value }
    func remove(_ key: String) { storage.removeValue(forKey: key) }
}

private struct RecordingTransport: SyncTransport {
    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? { nil }
    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        .ok(etag: "\"1\"")
    }
}
