import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Port of `src/sync/client.test.ts` § "syncOnce", against an in-memory         */
/* household document with ETag semantics, like the real server.                */
/* -------------------------------------------------------------------------- */

private func profile(earned: Counter = [:]) -> PersistedProfile {
    PersistedProfile(
        chosen: true,
        current: .unicorn,
        currentRev: .zero,
        species: blankSpeciesMap(),
        stars: StarCounters(earned: earned, spent: [:]),
        clears: [:]
    )
}

private func kid(_ id: String, _ name: String, earned: Counter = [:]) -> ChildProfile {
    ChildProfile(
        id: id,
        name: name,
        nameRev: Rev(at: 10, by: "dad"),
        touchedAt: 10,
        profile: profile(earned: earned)
    )
}

private func roster(_ children: [ChildProfile], activeId: String? = nil) -> Roster {
    Roster(children: children, activeId: activeId, removed: [:])
}

/// An in-memory household document with ETag semantics, like the real server.
private final class FakeServer: SyncTransport {
    private(set) var doc: WireRoster?
    private var version = 0

    init(_ initial: WireRoster? = nil) {
        self.doc = initial
    }

    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? {
        doc.map { ($0, String(version)) }
    }

    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        let expected = doc != nil ? String(version) : nil
        guard etag == expected else { return .conflict }
        doc = roster
        version += 1
        return .ok(etag: String(version))
    }

    /// Simulate the other phone writing while we were mid-sync.
    func interleave(_ next: WireRoster) {
        doc = next
        version += 1
    }
}

/// Wraps a server so the FIRST push collides (the other phone landed a write
/// between our pull and our push), then behaves normally.
private final class RacyTransport: SyncTransport {
    private let server: FakeServer
    private let interleaved: WireRoster
    private var raced = false

    init(server: FakeServer, interleaved: WireRoster) {
        self.server = server
        self.interleaved = interleaved
    }

    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? {
        try await server.pull(household: household)
    }

    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        if !raced {
            raced = true
            server.interleave(interleaved)
            return .conflict
        }
        return try await server.push(household: household, roster: roster, etag: etag)
    }
}

private func client(
    _ transport: SyncTransport,
    kv: InMemoryKVStore = InMemoryKVStore(),
    endpoint: String? = "https://sync.test"
) -> SyncClient {
    SyncClient(kv: kv, transport: transport, endpoint: { endpoint })
}

@Suite struct SyncClientTests {
    @Test func doesNothingUntilTheDeviceHasJoinedAHousehold() async throws {
        let server = FakeServer()
        let sync = client(server)
        let local = roster([kid("lea", "Léa", earned: ["dad": 10])])
        #expect(try await sync.syncOnce(local: local) == local)
        #expect(server.doc == nil)
        #expect(sync.enabled == false)
    }

    @Test func staysDisabledWithoutAnEndpointEvenInsideAHousehold() async throws {
        let server = FakeServer()
        let sync = client(server, endpoint: nil)
        sync.createHousehold()
        #expect(sync.enabled == false)
        let local = roster([kid("lea", "Léa", earned: ["dad": 10])])
        #expect(try await sync.syncOnce(local: local) == local)
        #expect(server.doc == nil)
    }

    @Test func uploadsTheFirstDevicesRoster() async throws {
        let server = FakeServer()
        let sync = client(server)
        sync.createHousehold()
        _ = try await sync.syncOnce(local: roster([kid("lea", "Léa", earned: ["dad": 10])]))
        #expect(server.doc?.children.count == 1)
    }

    @Test func convergesTwoPhonesWithoutLosingEitherOnesStars() async throws {
        let server = FakeServer()
        let dadPhone = client(server)
        let household = dadPhone.createHousehold()
        let mumPhone = client(server)
        mumPhone.joinHousehold(household)

        // Dad's phone syncs first, then Mum's phone brings its own offline earnings.
        _ = try await dadPhone.syncOnce(local: roster([kid("lea", "Léa", earned: ["dad": 10])]))
        let onMum = try await mumPhone.syncOnce(local: roster([kid("lea", "Léa", earned: ["mum": 3])]))

        #expect(onMum.children.count == 1)
        #expect(balanceOf(onMum.children[0].profile.stars) == 13)
        // And Mum's phone kept calling her Léa, without the server ever knowing it.
        #expect(onMum.children[0].name == "Léa")
        let serverJSON = String(data: try JSONEncoder().encode(server.doc), encoding: .utf8)!
        #expect(!serverJSON.contains("Léa"))
    }

    @Test func bringsHomeASiblingCreatedOnTheOtherPhone() async throws {
        let server = FakeServer()
        let mumPhone = client(server)
        let household = mumPhone.createHousehold()
        let dadPhone = client(server)
        dadPhone.joinHousehold(household)

        _ = try await mumPhone.syncOnce(local: roster([kid("tom", "Tom", earned: ["mum": 4])]))
        let here = try await dadPhone.syncOnce(
            local: roster([kid("lea", "Léa", earned: ["dad": 10])], activeId: "lea")
        )

        #expect(here.children.map(\.id).sorted() == ["lea", "tom"])
        #expect(here.children.first(where: { $0.id == "tom" })?.name == "Enfant")
        #expect(here.activeId == "lea") // still Léa's turn on THIS tablet
    }

    @Test func retriesAConflictingWriteInsteadOfClobberingIt() async throws {
        let server = FakeServer()
        let kv = InMemoryKVStore()
        let sync = client(server, kv: kv)
        sync.createHousehold()
        _ = try await sync.syncOnce(local: roster([kid("lea", "Léa", earned: ["dad": 10])]))

        // The other phone lands a write between our pull and our push.
        let racy = RacyTransport(
            server: server,
            interleaved: toWire(roster([kid("lea", "Léa", earned: ["mum": 5, "dad": 10])]))
        )
        let racySync = SyncClient(kv: kv, transport: racy, endpoint: { "https://sync.test" })

        let merged = try await racySync.syncOnce(
            local: roster([kid("lea", "Léa", earned: ["dad": 10, "ipad": 2])])
        )
        // All three devices' earnings survive the collision.
        #expect(balanceOf(merged.children[0].profile.stars) == 17)
    }

    @Test func isIdempotentResyncingDoesNotDoubleAnything() async throws {
        let server = FakeServer()
        let sync = client(server)
        sync.createHousehold()
        let local = roster([kid("lea", "Léa", earned: ["dad": 10])])
        let once = try await sync.syncOnce(local: local)
        let twice = try await sync.syncOnce(local: once)
        #expect(balanceOf(twice.children[0].profile.stars) == 10)
    }

    /// Oddity ported as-is: the ETag is stored after a successful push — but
    /// never read back by `syncOnce` (each attempt uses the etag from its own
    /// pull). It exists as state; this pins that it keeps existing.
    @Test func storesTheEtagAfterAPushEvenThoughNothingReadsIt() async throws {
        let server = FakeServer()
        let kv = InMemoryKVStore()
        let sync = client(server, kv: kv)
        sync.createHousehold()
        #expect(kv.string(SyncClient.etagKey) == "") // joinHousehold resets it
        _ = try await sync.syncOnce(local: roster([kid("lea", "Léa")]))
        #expect(kv.string(SyncClient.etagKey) == "1")
    }
}
