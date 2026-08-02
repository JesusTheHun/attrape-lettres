import ALCore
import Foundation

/* -------------------------------------------------------------------------- */
/* The two network seams: household sync and first-party telemetry. Ports of    */
/* the `fetch` calls in `src/sync/client.ts` and `src/telemetry.ts`.           */
/*                                                                             */
/* INVARIANT 10 — NOTHING IDENTIFYING LEAVES THE DEVICE.                       */
/*                                                                             */
/* Upstream of here it is already structural: `WireChild` has no `name` and no  */
/* `nameRev` property at all, so stripping identity is a property of the TYPE,  */
/* not a runtime `Omit` somebody can forget, and `TelemetryProps` has no        */
/* free-text field. This file's job is to not reopen the hole at the wire:      */
/*                                                                             */
/*   • `credentials: "omit"` ⇒ an EPHEMERAL configuration with nil cookie       */
/*     storage, nil credential storage, `.never` cookie policy, and             */
/*     `httpShouldHandleCookies = false` on every request. There is no session  */
/*     to carry, and no way to accidentally start one.                         */
/*   • No `Authorization`, no custom header beyond `content-type:              */
/*     application/json`, no query parameter, no user-agent string we compose.  */
/*   • The device id is never read here — it is not imported, not named, and    */
/*     `URLSessionTransportPrivacyTests` asserts on the transmitted BYTES.      */
/*     (It rides the SYNC payload as a counter KEY, by design: that is what     */
/*     makes two phones' stars mergeable, and the household document holds      */
/*     nothing but opaque ids and integers. It never rides telemetry.)         */
/*                                                                             */
/* This is a Kids Category app: no third-party analytics, no PII, no device     */
/* information to third parties. There is no third party at all.               */
/* -------------------------------------------------------------------------- */

public enum PrivateURLSession {
    /**
     * The one session configuration both transports use.
     *
     * `URLSessionConfiguration.ephemeral` already keeps nothing on disk; the
     * three explicit nils are belt and braces, and they are what money.md §4.5
     * spells out as the Swift form of `credentials: "omit"`.
     */
    public static func makeConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.httpCookieAcceptPolicy = .never
        config.httpShouldSetCookies = false
        return config
    }

    public static func make() -> URLSession {
        URLSession(configuration: makeConfiguration())
    }
}

// MARK: - Telemetry

/**
 * `TelemetryTransport` over `URLSession`. The signature it conforms to is the
 * privacy boundary: `(Data, URL)` and nothing else — there is no header bag, no
 * credential and no cookie jar for a caller to fill in.
 *
 * Responses are ignored and errors are swallowed by `Telemetry` itself
 * ("telemetry must never surface to a child"); this throws so a test can see the
 * failure, exactly like `RecordingTransport` does.
 */
public struct URLSessionTelemetryTransport: TelemetryTransport {
    private let session: URLSession

    public init(session: URLSession = PrivateURLSession.make()) {
        self.session = session
    }

    public func send(_ body: Data, to url: URL) async throws {
        _ = try await session.data(for: Self.request(body, url))
    }

    /// The exact bytes and headers that go out. Internal so the privacy test can
    /// assert on the request itself as well as on what the wire carried.
    static func request(_ body: Data, _ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // The ONLY header. Anything else here is a privacy review.
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = body
        request.httpShouldHandleCookies = false
        return request
    }
}

// MARK: - Sync

public enum SyncTransportError: Error, Equatable {
    /// No endpoint configured — TS `throw new Error("sync disabled")`.
    case disabled
    /// The endpoint string plus the household id did not make a URL.
    case badURL
    /// TS `throw new Error("sync pull ${status}")` / `sync push ${status}`.
    case http(status: Int)
}

/**
 * `SyncTransport` over `URLSession`: `GET`/`PUT {endpoint}/household/{id}` with
 * ETag optimistic concurrency, so the server rejects a write built on a stale
 * read (412 → `.conflict`) and a simultaneous save from the other phone is never
 * clobbered.
 *
 * The endpoint is read LAZILY, per call — the `VITE_SYNC_URL` analogue. That
 * keeps "endpoint absent ⇒ sync disabled, silently" true, and it is what lets a
 * test vary it.
 *
 * Errors are thrown, not swallowed: `ProfileStore.syncNow` swallows them and the
 * device keeps playing alone. Gameplay is offline-first and a child mid-round
 * never waits on the network.
 */
public struct URLSessionSyncTransport: SyncTransport {
    private let endpoint: () -> String?
    private let session: URLSession

    public init(endpoint: @escaping () -> String?, session: URLSession = PrivateURLSession.make()) {
        self.endpoint = endpoint
        self.session = session
    }

    /// `${endpoint}/household/${household}` — plain concatenation, as the TS
    /// template does. Appending a path component through `URL` would percent-
    /// escape the separator and change the path the server sees.
    static func url(endpoint: String, household: String) -> URL? {
        URL(string: endpoint + "/household/" + household)
    }

    private func resolvedEndpoint() -> String? {
        guard let raw = endpoint(), !raw.isEmpty else { return nil }
        return raw
    }

    public func pull(household: String) async throws -> (roster: WireRoster, etag: String)? {
        // TS: `if (!endpoint()) return null` — pull is silent when disabled.
        guard let endpoint = resolvedEndpoint() else { return nil }
        guard let url = Self.url(endpoint: endpoint, household: household) else {
            throw SyncTransportError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.httpShouldHandleCookies = false

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncTransportError.badURL }
        if http.statusCode == 404 { return nil }  // no household document yet
        guard (200..<300).contains(http.statusCode) else {
            throw SyncTransportError.http(status: http.statusCode)
        }
        let roster = try JSONDecoder().decode(WireRoster.self, from: data)
        return (roster, http.value(forHTTPHeaderField: "ETag") ?? "")
    }

    public func push(household: String, roster: WireRoster, etag: String?) async throws
        -> PushResult
    {
        // TS: `if (!endpoint()) throw new Error("sync disabled")` — push is loud.
        guard let endpoint = resolvedEndpoint() else { throw SyncTransportError.disabled }
        guard let url = Self.url(endpoint: endpoint, household: household) else {
            throw SyncTransportError.badURL
        }
        let body = try JSONEncoder().encode(roster)
        let request = Self.pushRequest(url: url, body: body, etag: etag)

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncTransportError.badURL }
        // Optimistic concurrency: the other phone's write is somebody's stars.
        if http.statusCode == 412 { return .conflict }
        guard (200..<300).contains(http.statusCode) else {
            throw SyncTransportError.http(status: http.statusCode)
        }
        return .ok(etag: http.value(forHTTPHeaderField: "ETag") ?? "")
    }

    /// Internal so the privacy test can inspect exactly what would be sent.
    static func pushRequest(url: URL, body: Data, etag: String?) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        // TS spreads `...(etag ? { "if-match": etag } : {})` — an empty string is
        // falsy there, so it sends no header. Mirrored.
        if let etag, !etag.isEmpty {
            request.setValue(etag, forHTTPHeaderField: "if-match")
        }
        request.httpBody = body
        request.httpShouldHandleCookies = false
        return request
    }
}

// MARK: - Redemption

/**
 * `RedemptionTransport` over `URLSession`: `POST {endpoint}/redeem`.
 *
 * Same session, same absence of headers, same absence of credentials as the two
 * transports above. The body is two strings — the normalised code and the
 * family's opaque household id — and nothing else. No account, no e-mail
 * address, no device id, no child.
 *
 * **NOTHING IS THROWN.** `RedemptionTransport` is non-throwing on purpose
 * (invariant 11): every network failure, timeout, 5xx and unrecognised status
 * collapses to `.unreachable`, which the model reads as "nothing was learnt"
 * and which changes nothing on the device. A store outage may not tell a family
 * they are not unlocked.
 *
 * The four statuses that DO mean something are exactly the four the API
 * documents. They are mapped positionally rather than by parsing the error body,
 * so a change to the refusal shape cannot silently turn a refusal into a grant.
 */
public struct URLSessionRedemptionTransport: RedemptionTransport {
    /// Long enough for a cold Lambda, short enough that a parent does not stare
    /// at a spinner. A timeout is `.unreachable`, so overrunning costs nothing
    /// but the wait.
    public static let defaultTimeout: TimeInterval = 15

    // `@Sendable`, unlike `URLSessionSyncTransport`'s: `RedemptionTransport`
    // is a `Sendable` protocol, so the stored closure has to be too.
    private let endpoint: @Sendable () -> String?
    private let session: URLSession
    private let timeout: TimeInterval

    public init(
        endpoint: @escaping @Sendable () -> String?,
        session: URLSession = PrivateURLSession.make(),
        timeout: TimeInterval = URLSessionRedemptionTransport.defaultTimeout
    ) {
        self.endpoint = endpoint
        self.session = session
        self.timeout = timeout
    }

    /// `${endpoint}/redeem` — plain concatenation, as `URLSessionSyncTransport`
    /// does, so the path the server sees is the path we wrote.
    static func url(endpoint: String) -> URL? {
        URL(string: endpoint + "/redeem")
    }

    /// Internal so a test can assert on the bytes that would go out.
    static func request(url: URL, code: String, household: String, timeout: TimeInterval)
        -> URLRequest?
    {
        // Hand-encoded rather than through an encodable struct so that the
        // payload is visible, in full, at the one place it is built. Two keys,
        // both values already constrained: a code is twelve symbols from a
        // 32-character alphabet, a household id is an opaque token.
        let payload = ["code": code, "household": household]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = body
        request.httpShouldHandleCookies = false
        request.timeoutInterval = timeout
        return request
    }

    public func redeem(code: String, household: String) async -> RedemptionAnswer {
        guard let raw = endpoint(), !raw.isEmpty else { return .unreachable }
        guard let url = Self.url(endpoint: raw),
            let request = Self.request(
                url: url, code: code, household: household, timeout: timeout)
        else { return .unreachable }

        guard
            let (data, response) = try? await session.data(for: request),
            let http = response as? HTTPURLResponse
        else { return .unreachable }

        switch http.statusCode {
        case 200:
            // `grantedAt` is the server's clock. A response we cannot read is
            // NOT a refusal — the family may well have been granted, so the
            // honest answer is "nothing was learnt" and the parent retries.
            guard
                let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let at = object["grantedAt"] as? Int64 ?? (object["grantedAt"] as? NSNumber)?.int64Value
            else { return .unreachable }
            return .granted(at: at)
        case 400, 404:
            // 400 is a code this device thought was well-formed and the server
            // did not; from a parent's chair that is the same as "no such code".
            return .unknown
        case 409:
            return .exhausted
        case 410:
            return .expired
        default:
            return .unreachable
        }
    }
}
