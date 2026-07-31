import Foundation

@testable import ALPlatform

/* -------------------------------------------------------------------------- */
/* The wire tap.                                                                */
/*                                                                             */
/* Intercepts every request the test sessions make, so the transport tests can  */
/* assert on the real `URLRequest` `URLSession` was about to send — its method,  */
/* URL, headers and BODY BYTES. No network, no server, runs on the host.        */
/*                                                                             */
/* Everything is keyed by a UNIQUE HOST per `MockEndpoint`, not held in one     */
/* global bucket: Swift Testing runs suites in parallel, and a shared bucket    */
/* makes one suite's reply answer another suite's request. That failure looks   */
/* exactly like a transport bug, which is the worst kind of flake to chase.     */
/* -------------------------------------------------------------------------- */

final class RecordingURLProtocol: URLProtocol {
    struct Capture: @unchecked Sendable {
        let url: URL
        let method: String
        let headers: [String: String]
        let body: Data
        var text: String { String(decoding: body, as: UTF8.self) }

        func header(_ name: String) -> String? {
            headers.first { $0.key.lowercased() == name.lowercased() }?.value
        }
    }

    struct Reply {
        var status: Int = 200
        var headers: [String: String] = [:]
        var body: Data = Data()

        init(status: Int = 200, headers: [String: String] = [:], body: Data = Data()) {
            self.status = status
            self.headers = headers
            self.body = body
        }
    }

    /// host → HTTP method (or `"*"`) → reply.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var replies: [String: [String: Reply]] = [:]
    nonisolated(unsafe) private static var captures: [String: [Capture]] = [:]
    nonisolated(unsafe) private static var counter = 0

    static func register(_ reply: Reply) -> String {
        lock.lock()
        defer { lock.unlock() }
        counter += 1
        let host = "h\(ProcessInfo.processInfo.processIdentifier)-\(counter).test"
        replies[host] = ["*": reply]
        captures[host] = []
        return host
    }

    static func setReply(_ reply: Reply, for host: String, method: String = "*") {
        lock.lock()
        replies[host, default: [:]][method] = reply
        lock.unlock()
    }

    static func captures(for host: String) -> [Capture] {
        lock.lock()
        defer { lock.unlock() }
        return captures[host] ?? []
    }

    /// `URLSession` moves `httpBody` into a stream before a protocol sees it, so
    /// a tap that only read `httpBody` would silently assert on nothing.
    private static func bodyData(_ request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    override class func canInit(with request: URLRequest) -> Bool {
        guard let host = request.url?.host else { return false }
        lock.lock()
        defer { lock.unlock() }
        return replies[host] != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host else { return }
        let capture = Capture(
            url: url,
            method: request.httpMethod ?? "",
            headers: request.allHTTPHeaderFields ?? [:],
            body: Self.bodyData(request))

        Self.lock.lock()
        Self.captures[host, default: []].append(capture)
        let forHost = Self.replies[host] ?? [:]
        let reply = forHost[capture.method] ?? forHost["*"] ?? Reply()
        Self.lock.unlock()

        let response = HTTPURLResponse(
            url: url, statusCode: reply.status, httpVersion: "HTTP/1.1",
            headerFields: reply.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !reply.body.isEmpty { client?.urlProtocol(self, didLoad: reply.body) }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// A session with the app's real privacy configuration plus the tap, so the
    /// tests exercise the configuration the app ships.
    static func makeSession() -> URLSession {
        let config = PrivateURLSession.makeConfiguration()
        config.protocolClasses = [RecordingURLProtocol.self]
        return URLSession(configuration: config)
    }
}

/// One fake server, owned by one test. Its host is unique, so nothing another
/// suite does can reach it.
final class MockHTTPEndpoint: @unchecked Sendable {
    let host: String

    init(_ reply: RecordingURLProtocol.Reply = RecordingURLProtocol.Reply()) {
        host = RecordingURLProtocol.register(reply)
    }

    var base: String { "https://\(host)" }

    /// Set the reply for one HTTP method, or for everything when `method` is nil.
    func reply(_ reply: RecordingURLProtocol.Reply, method: String? = nil) {
        RecordingURLProtocol.setReply(reply, for: host, method: method ?? "*")
    }

    var captures: [RecordingURLProtocol.Capture] { RecordingURLProtocol.captures(for: host) }

    func session() -> URLSession { RecordingURLProtocol.makeSession() }
}
