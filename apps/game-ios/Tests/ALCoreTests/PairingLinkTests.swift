import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* A pairing link is untrusted input that decides which household this device   */
/* writes into. Everything here is about what it REFUSES.                       */
/* -------------------------------------------------------------------------- */

private let uuid = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

@Suite("PairingLink — minting")
struct PairingLinkMintingTests {

    @Test("a household id round-trips through the link it is handed out in")
    func roundTrip() throws {
        let url = try #require(PairingLink.url(household: uuid))
        #expect(PairingLink.household(from: url) == uuid)
    }

    @Test("the link is the scheme the Info.plist registers")
    func shape() throws {
        let url = try #require(PairingLink.url(household: uuid))
        #expect(url.scheme == "attrape-lettres")
        #expect(url.host == "pair")
        #expect(url.absoluteString == "attrape-lettres://pair?h=\(uuid)")
    }

    @Test("an id this module would not accept is never handed out")
    func refusesToMintRubbish() {
        #expect(PairingLink.url(household: "") == nil)
        #expect(PairingLink.url(household: "../../etc/passwd") == nil)
        // The one that would actually happen: an uppercase UUID from some
        // other Foundation call site.
        #expect(PairingLink.url(household: uuid.uppercased()) == nil)
    }

    @Test("what SyncClient mints is what PairingLink accepts")
    func agreesWithCreateHousehold() {
        // The contract that keeps the two halves from drifting: if
        // `createHousehold` ever changes shape, this fails rather than pairing
        // silently refusing every new household.
        let client = SyncClient(
            kv: MemoryKV(), transport: NeverCalledTransport(), endpoint: { "https://s.test" })
        for _ in 0..<32 {
            #expect(PairingLink.isWellFormed(client.createHousehold()))
        }
    }
}

@Suite("PairingLink — accepting")
struct PairingLinkAcceptingTests {

    @Test("the PWA's crypto-less fallback id is honoured")
    func webFallbackId() throws {
        // `h_<ts36><rand36>` — a phone can be handed a household minted by the
        // web app, so this shape is not optional.
        let id = "h_m4x9q2z0abc123"
        let url = try #require(PairingLink.url(household: id))
        #expect(PairingLink.household(from: url) == id)
    }

    @Test("another app's scheme is not our pairing link")
    func wrongScheme() throws {
        let url = try #require(URL(string: "attrape-lettres-evil://pair?h=\(uuid)"))
        #expect(PairingLink.household(from: url) == nil)
    }

    @Test("a link for some other action is not a join")
    func wrongHost() throws {
        let url = try #require(URL(string: "attrape-lettres://buy?h=\(uuid)"))
        #expect(PairingLink.household(from: url) == nil)
    }

    @Test("two household parameters are refused rather than resolved")
    func duplicateParameters() throws {
        // Whoever writes the parser and whoever writes the attack would pick
        // different winners. There is no winner.
        let url = try #require(URL(string: "attrape-lettres://pair?h=\(uuid)&h=\(uuid)"))
        #expect(PairingLink.household(from: url) == nil)
    }

    @Test("a link with no household is not a join")
    func missingParameter() throws {
        #expect(PairingLink.household(from: try #require(URL(string: "attrape-lettres://pair"))) == nil)
        #expect(
            PairingLink.household(from: try #require(URL(string: "attrape-lettres://pair?h="))) == nil
        )
    }

    @Test(
        "an id the app never mints is refused",
        arguments: [
            "",
            "not-a-uuid",
            // Right length, wrong alphabet.
            "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5g",
            // Right alphabet, wrong grouping.
            "3f2b1c4d5e6f4a7b8c9d0e1f2a3b4c5d",
            // Uppercase: Foundation's UUID(uuidString:) takes it, we do not.
            "3F2B1C4D-5E6F-4A7B-8C9D-0E1F2A3B4C5D",
            // Path traversal, which is what strictness is actually for: the id
            // is interpolated into `{endpoint}/household/{id}`.
            "../../household/someone-else",
            "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/../other",
            // The fallback prefix without a legal body.
            "h_",
            "h_UPPER",
            "h_with-a-dash",
        ]
    )
    func rejectsForeignIds(_ id: String) {
        #expect(!PairingLink.isWellFormed(id))
        // And it cannot sneak in through a hand-built URL either.
        var components = URLComponents()
        components.scheme = PairingLink.scheme
        components.host = PairingLink.host
        components.queryItems = [URLQueryItem(name: "h", value: id)]
        if let url = components.url {
            #expect(PairingLink.household(from: url) == nil)
        }
    }
}

// MARK: - Doubles

private final class MemoryKV: KVStore, @unchecked Sendable {
    private var storage: [String: String] = [:]
    func string(_ key: String) -> String? { storage[key] }
    func set(_ value: String, for key: String) { storage[key] = value }
    func remove(_ key: String) { storage.removeValue(forKey: key) }
}

private struct NeverCalledTransport: SyncTransport {
    func pull(household: String) async throws -> (roster: WireRoster, etag: String)? {
        Issue.record("pairing must not touch the network")
        return nil
    }
    func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult {
        Issue.record("pairing must not touch the network")
        return .conflict
    }
}
