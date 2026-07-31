import Foundation

/**
 * The network seam. ALCore makes no network call; it hands finished bytes and a
 * finished URL to an adapter that lives in `ALPlatform` (W17).
 *
 * The signature is the privacy boundary: `(Data, URL)` and nothing else. There
 * is no header bag, no credential, no cookie jar, no session — so
 * `credentials: "omit"` is not a flag a caller can forget to pass, it is the
 * absence of any way to attach one. The adapter's own obligations (money.md
 * §4.5): `URLSessionConfiguration.ephemeral` with a nil cookie storage, a nil
 * credential storage, `httpCookieAcceptPolicy = .never`, and
 * `request.httpShouldHandleCookies = false`. No `Authorization`, no custom
 * header beyond `content-type: application/json`. Responses ignored; errors
 * swallowed — telemetry must never surface to a child.
 *
 * NB: ARCHITECTURE.md §5 sketches this as `func send(_ body: Data) async throws`.
 * The URL parameter is added because the module posts to two paths (`/events`
 * and `/errors`) and the tests assert which; the sketch was not exhaustive
 * (the same latitude `AudioPort.swift` took with `AudioEngine`).
 */
public protocol TelemetryTransport: Sendable {
    func send(_ body: Data, to url: URL) async throws
}

/// A transport that goes nowhere. The shipping default when no endpoint is
/// configured, and the reason a dev build posts nowhere.
public struct NullTelemetryTransport: TelemetryTransport {
    public init() {}
    public func send(_ body: Data, to url: URL) async throws {}
}

/// Tests. Records every send in order.
public final class RecordingTransport: TelemetryTransport, @unchecked Sendable {
    public struct Sent: Sendable {
        public let url: URL
        public let body: Data
        public var json: String { String(decoding: body, as: UTF8.self) }
    }

    private let lock = NSLock()
    private var storage: [Sent] = []

    /// When true, every `send` throws — proving the swallow path.
    public var failing: Bool

    public init(failing: Bool = false) { self.failing = failing }

    public var sent: [Sent] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    public func clear() {
        lock.lock()
        storage.removeAll()
        lock.unlock()
    }

    public func send(_ body: Data, to url: URL) async throws {
        // Locking lives in a synchronous helper: `NSLock` is unavailable from an
        // async context (an error in Swift 6 mode).
        record(Sent(url: url, body: body))
        if failing { throw TransportFailure() }
    }

    private func record(_ s: Sent) {
        lock.lock()
        defer { lock.unlock() }
        storage.append(s)
    }

    public struct TransportFailure: Error {
        public init() {}
    }
}
