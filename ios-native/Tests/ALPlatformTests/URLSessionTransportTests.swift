import Foundation
import Testing

@testable import ALCore
@testable import ALPlatform

/* -------------------------------------------------------------------------- */
/* INVARIANT 10 — nothing identifying leaves the device, asserted on the BYTES  */
/* that actually go out, not on the model that produced them.                   */
/* -------------------------------------------------------------------------- */

/// The device id every counter in the fixtures is keyed by. It rides the SYNC
/// payload by design (merging two phones' stars is impossible without it) and
/// must never ride telemetry.
private let deviceId = "b3f0e2c1-1111-4aaa-9bbb-cccccccccccc"

@MainActor
private func rosterWithLéa() -> Roster {
    let kv = InMemoryKVStore()
    let store = ProfileStore(kv: kv, device: { deviceId }, now: { 1_719_400_000_000 })
    store.createChild(name: "Léa")
    _ = store.award(exercise: .readImage, level: 1, perfectRounds: 6, totalRounds: 6)
    return store.roster
}

/// Both spellings a JSON encoder could emit for « Léa ». A test that looked for
/// only one would pass against an encoder configured the other way.
private let nameSpellings = ["Léa", "L\\u00e9a", "L\\u00E9a"]

@Suite("URLSession transports — privacy")
struct URLSessionTransportPrivacyTests {

    @Test("the sync payload carries no child's name")
    func syncPushStripsNames() async throws {
        let server = MockHTTPEndpoint(.init(status: 200, headers: ["ETag": "w/2"]))
        let roster = await rosterWithLéa()
        // It really is in the local model — otherwise this proves nothing.
        #expect(roster.children.first?.name == "Léa")

        let transport = URLSessionSyncTransport(endpoint: { server.base }, session: server.session())
        let result = try await transport.push(household: "h-1", roster: toWire(roster), etag: "w/1")
        #expect(result == .ok(etag: "w/2"))

        let sent = try #require(server.captures.first)
        for spelling in nameSpellings {
            #expect(
                !sent.text.contains(spelling),
                Comment(rawValue: "the wire carried the child's name as \(spelling)"))
        }
        // The stamp that would let a server infer a rename, and the field that
        // says which child this tablet is on, are gone with it.
        #expect(!sent.text.contains("nameRev"))
        #expect(!sent.text.contains("activeId"))
        // What DOES travel: opaque ids and integers.
        #expect(sent.text.contains("\"children\""))
        #expect(sent.text.contains("\"earned\""))
        // The device id rides the counter KEYS, by design: it identifies an
        // install, never a person, and the merge cannot work without it.
        // Asserted so the boundary is stated rather than assumed.
        #expect(sent.text.contains(deviceId))
    }

    @Test("the telemetry payload carries neither the name nor the device id")
    func telemetryCarriesNothingIdentifying() async throws {
        let server = MockHTTPEndpoint()
        let session = server.session()
        let endpoint = server.base

        let telemetry = await MainActor.run { () -> Telemetry in
            // The same store the roster and the device id live in: if telemetry
            // could reach them, this is where it would reach.
            let kv = InMemoryKVStore()
            kv.set(deviceId, for: DeviceIdentity.storageKey)
            let store = ProfileStore(kv: kv, device: { deviceId }, now: { 1_719_400_000_000 })
            store.createChild(name: "Léa")

            let t = Telemetry(
                endpoint: endpoint,
                transport: URLSessionTelemetryTransport(session: session),
                kv: kv,
                appVersion: FixedAppVersion("0.1.0"))
            t.setConsent(true)
            t.track(
                .sessionCompleted,
                TelemetryProps(exercise: .readImage, level: 1, rounds: 6, perfect: 6, points: 9))
            t.track(.itemBought, TelemetryProps(cost: 4))
            t.flush()
            return t
        }
        await telemetry.awaitPendingSends()

        let sent = try #require(server.captures.first)
        #expect(sent.url.absoluteString == endpoint + "/events")
        #expect(sent.method == "POST")

        for spelling in nameSpellings {
            #expect(
                !sent.text.contains(spelling),
                Comment(rawValue: "telemetry carried a child's name as \(spelling)"))
        }
        #expect(
            !sent.text.contains(deviceId),
            Comment(rawValue: "telemetry carried the per-install identifier"))

        // What it does carry: the app version, event names from a closed list,
        // numbers, and an exercise id from a closed list.
        #expect(sent.text.contains("\"v\":\"0.1.0\""))
        #expect(sent.text.contains("session_completed"))
        #expect(sent.text.contains("read-image"))
    }

    @Test("no request carries a credential, a cookie or a device header")
    func requestsCarryOnlyContentType() async throws {
        let server = MockHTTPEndpoint()
        try await URLSessionTelemetryTransport(session: server.session())
            .send(Data("{}".utf8), to: URL(string: server.base + "/errors")!)

        let sent = try #require(server.captures.first)
        let names = Set(sent.headers.keys.map { $0.lowercased() })
        for banned in ["authorization", "cookie", "x-device-id", "x-install-id"] {
            #expect(!names.contains(banned), Comment(rawValue: "\(banned) was sent"))
        }
        #expect(sent.header("content-type") == "application/json")
        // No query string smuggling an id past the body assertions either.
        #expect(URLComponents(url: sent.url, resolvingAgainstBaseURL: false)?.query == nil)
    }

    @Test("the session keeps no cookies and no credentials")
    func sessionIsAnonymous() {
        // `credentials: "omit"`, in the only form URLSession understands.
        let config = PrivateURLSession.makeConfiguration()
        #expect(config.httpCookieStorage == nil)
        #expect(config.urlCredentialStorage == nil)
        #expect(config.httpCookieAcceptPolicy == .never)
        #expect(
            URLSessionTelemetryTransport.request(Data(), URL(string: "https://x.test")!)
                .httpShouldHandleCookies == false)
    }

    @Test("a telemetry failure is visible to the caller, and swallowed above it")
    func telemetryErrorsSurfaceToTheCaller() async {
        // `Telemetry.post` catches; the transport itself must not pretend a 500
        // was a success in some future version that inspects the response.
        let server = MockHTTPEndpoint(.init(status: 500))
        let transport = URLSessionTelemetryTransport(session: server.session())
        // Today the response is ignored entirely — no throw, and one request made.
        try? await transport.send(Data("{}".utf8), to: URL(string: server.base + "/events")!)
        #expect(server.captures.count == 1)
    }
}

@Suite("URLSessionSyncTransport — HTTP semantics")
struct URLSessionSyncTransportTests {

    private static let emptyRoster = WireRoster(children: [], removed: [:])

    private func transport(_ server: MockHTTPEndpoint) -> URLSessionSyncTransport {
        URLSessionSyncTransport(endpoint: { server.base }, session: server.session())
    }

    @Test("pull GETs {endpoint}/household/{id} and reads the ETag")
    func pullShape() async throws {
        let body = try JSONEncoder().encode(Self.emptyRoster)
        let server = MockHTTPEndpoint(.init(status: 200, headers: ["ETag": "w/7"], body: body))

        let result = try await transport(server).pull(household: "h-1")
        let unwrapped = try #require(result)
        #expect(unwrapped.etag == "w/7")
        #expect(unwrapped.roster.children.isEmpty)

        let sent = try #require(server.captures.first)
        #expect(sent.method == "GET")
        #expect(sent.url.absoluteString == server.base + "/household/h-1")
    }

    @Test("pull returns nil on 404 — there is no household document yet")
    func pullNotFound() async throws {
        let server = MockHTTPEndpoint(.init(status: 404))
        #expect(try await transport(server).pull(household: "h-1") == nil)
    }

    @Test("pull throws on any other non-2xx")
    func pullServerError() async {
        let server = MockHTTPEndpoint(.init(status: 500))
        await #expect(throws: SyncTransportError.http(status: 500)) {
            _ = try await transport(server).pull(household: "h-1")
        }
    }

    @Test("a missing ETag header reads as the empty string, never a failure")
    func pullWithoutETag() async throws {
        let body = try JSONEncoder().encode(Self.emptyRoster)
        let server = MockHTTPEndpoint(.init(status: 200, body: body))
        let result = try await transport(server).pull(household: "h-1")
        #expect(result?.etag == "")
    }

    @Test("pull is silent when no endpoint is configured")
    func pullDisabled() async throws {
        let server = MockHTTPEndpoint()
        for endpoint in [nil, ""] as [String?] {
            let t = URLSessionSyncTransport(endpoint: { endpoint }, session: server.session())
            #expect(try await t.pull(household: "h-1") == nil)
        }
        #expect(server.captures.isEmpty)
    }

    @Test("push PUTs with if-match and returns the new ETag")
    func pushShape() async throws {
        let server = MockHTTPEndpoint(.init(status: 200, headers: ["ETag": "w/8"]))
        let result = try await transport(server).push(
            household: "h-1", roster: Self.emptyRoster, etag: "w/7")
        #expect(result == .ok(etag: "w/8"))

        let sent = try #require(server.captures.first)
        #expect(sent.method == "PUT")
        #expect(sent.url.absoluteString == server.base + "/household/h-1")
        #expect(sent.header("if-match") == "w/7")
        #expect(sent.header("content-type") == "application/json")
        #expect(sent.text.contains("\"children\":[]"))
    }

    /// Optimistic concurrency: the server rejects a write built on a stale read,
    /// so the other phone's stars are never clobbered. `SyncClient` re-pulls.
    @Test("push resolves conflict on 412 rather than throwing")
    func pushConflict() async throws {
        let server = MockHTTPEndpoint(.init(status: 412))
        let result = try await transport(server).push(
            household: "h-1", roster: Self.emptyRoster, etag: "stale")
        #expect(result == .conflict)
    }

    @Test("push throws on any other non-2xx")
    func pushServerError() async {
        let server = MockHTTPEndpoint(.init(status: 503))
        await #expect(throws: SyncTransportError.http(status: 503)) {
            _ = try await transport(server).push(
                household: "h-1", roster: Self.emptyRoster, etag: nil)
        }
    }

    @Test("no etag, or an empty one, sends no if-match header")
    func pushWithoutETag() async throws {
        for etag in [nil, ""] as [String?] {
            let server = MockHTTPEndpoint(.init(status: 200))
            _ = try await transport(server).push(
                household: "h-1", roster: Self.emptyRoster, etag: etag)
            let sent = try #require(server.captures.first)
            #expect(sent.header("if-match") == nil)
        }
    }

    @Test("push is loud when no endpoint is configured — sync disabled")
    func pushDisabled() async {
        let server = MockHTTPEndpoint()
        let t = URLSessionSyncTransport(endpoint: { nil }, session: server.session())
        await #expect(throws: SyncTransportError.disabled) {
            _ = try await t.push(household: "h-1", roster: Self.emptyRoster, etag: nil)
        }
        #expect(server.captures.isEmpty)
    }

    /// The endpoint is read per call, not captured at construction: that is what
    /// keeps "configured later, or not at all" working, and it mirrors the TS
    /// reading `import.meta.env` lazily.
    @Test("the endpoint is read lazily, on every call")
    func endpointIsLazy() async throws {
        let server = MockHTTPEndpoint(.init(status: 404))
        let box = EndpointBox()
        let transport = URLSessionSyncTransport(
            endpoint: { box.value }, session: server.session())

        #expect(try await transport.pull(household: "h-1") == nil)
        #expect(server.captures.isEmpty)

        box.value = server.base
        _ = try await transport.pull(household: "h-1")
        #expect(server.captures.count == 1)
        #expect(server.captures.first?.url.absoluteString == server.base + "/household/h-1")
    }

    /// `SyncClient` on top of this transport: the loop that actually runs on
    /// resume, with a real 412 in the middle of it.
    @Test("a conflicting push is re-pulled and merged, never forced")
    func clientRetriesOnConflict() async throws {
        let server = MockHTTPEndpoint()
        server.reply(
            .init(
                status: 200, headers: ["ETag": "w/1"],
                body: try JSONEncoder().encode(Self.emptyRoster)),
            method: "GET")
        // The other phone writes between every read and every write of ours.
        server.reply(.init(status: 412), method: "PUT")
        let kv = InMemoryKVStore()
        let client = SyncClient(
            kv: kv, transport: transport(server), endpoint: { server.base })
        client.joinHousehold("h-1")

        let local = await MainActor.run { () -> Roster in
            let store = ProfileStore(kv: InMemoryKVStore(), device: { deviceId }, now: { 1 })
            store.createChild(name: "Léa")
            return store.roster
        }
        _ = try await client.syncOnce(local: local)

        // Three attempts and then it gives up until the next resume, rather than
        // forcing anybody's stars away.
        let pushes = server.captures.filter { $0.method == "PUT" }
        #expect(pushes.count == 3)
        #expect(server.captures.filter { $0.method == "GET" }.count == 3)
        for push in pushes {
            for spelling in nameSpellings {
                #expect(!push.text.contains(spelling))
            }
        }
    }
}

/// A mutable endpoint the lazily-read closure can see change.
private final class EndpointBox: @unchecked Sendable {
    var value: String?
}
