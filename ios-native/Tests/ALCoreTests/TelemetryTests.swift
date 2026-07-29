import Foundation
import Testing

@testable import ALCore

// 1:1 port of `src/telemetry.test.ts` (money.md §6.4).
//
// Two of the 13 TS cases are DROPPED as unrepresentable in Swift, not as
// unimportant:
//   • "silently drops any property not on the allowlist" — it passed
//     `childName: "Léa"` through an `as unknown as TelemetryProps` cast, and
//     there is no Swift cast that would compile against a struct of eight named
//     fields. Replaced by `TelemetryClosureTests`.
//   • "drops non-finite numbers rather than sending null" — there is no `Int`
//     NaN. Replaced by the same file's reflection test.

private struct Wire {
    let url: URL
    let object: [String: Any]

    var events: [[String: Any]] { object["events"] as? [[String: Any]] ?? [] }
}

private func sent(_ transport: RecordingTransport) -> [Wire] {
    transport.sent.map { s in
        Wire(
            url: s.url,
            object: (try? JSONSerialization.jsonObject(with: s.body)) as? [String: Any] ?? [:])
    }
}

@MainActor
private func makeTelemetry(
    endpoint: String? = "https://t.test",
    kv: InMemoryKVStore = InMemoryKVStore(),
    transport: RecordingTransport = RecordingTransport()
) -> (Telemetry, RecordingTransport, InMemoryKVStore) {
    let t = Telemetry(
        endpoint: endpoint, transport: transport, kv: kv,
        appVersion: FixedAppVersion("0.1.0"))
    return (t, transport, kv)
}

@Suite("telemetry — consent")
@MainActor
struct TelemetryConsentTests {

    @Test("starts unanswered, and unanswered is not consent")
    func startsUnanswered() {
        let (t, _, _) = makeTelemetry()
        #expect(t.consentAnswered == false)
        #expect(t.hasConsent == false)
    }

    @Test("records a refusal distinctly from never having asked")
    func refusalIsDistinct() {
        let (t, _, _) = makeTelemetry()
        t.setConsent(false)
        #expect(t.consentAnswered == true)
        #expect(t.hasConsent == false)
    }

    @Test("sends nothing at all without consent")
    func nothingWithoutConsent() async {
        let (t, transport, _) = makeTelemetry()
        t.track(.exerciseStarted, TelemetryProps(exercise: .readImage, level: 1))
        t.flush()
        await t.awaitPendingSends()
        #expect(transport.sent.isEmpty)
    }

    @Test("drops anything already queued the moment consent is withdrawn")
    func withdrawalDrainsTheQueue() async {
        let (t, transport, _) = makeTelemetry()
        t.setConsent(true)
        t.track(.exerciseStarted, TelemetryProps(exercise: .readImage, level: 1))
        t.setConsent(false)
        t.flush()
        await t.awaitPendingSends()
        #expect(transport.sent.isEmpty)
    }
}

@Suite("telemetry — what a tracked event actually contains")
@MainActor
struct TelemetryPayloadTests {

    @Test("sends the event name, allowlisted numbers and the app version — nothing else")
    func payloadShape() async throws {
        let (t, transport, _) = makeTelemetry()
        t.setConsent(true)
        t.track(
            .sessionCompleted,
            TelemetryProps(exercise: .readImage, level: 2, rounds: 8, perfect: 6, points: 12))
        t.flush()
        await t.awaitPendingSends()

        let calls = sent(transport)
        #expect(calls.count == 1)
        let call = try #require(calls.first)
        #expect(call.url.absoluteString == "https://t.test/events")
        #expect(call.object["v"] as? String == "0.1.0")
        #expect(call.events.count == 1)
        let event = try #require(call.events.first)
        #expect(event["event"] as? String == "session_completed")
        let props = try #require(event["props"] as? [String: Any])
        #expect(props.count == 5)
        #expect(props["level"] as? Int == 2)
        #expect(props["rounds"] as? Int == 8)
        #expect(props["perfect"] as? Int == 6)
        #expect(props["points"] as? Int == 12)
        #expect(props["exercise"] as? String == "read-image")

        // The wire ORDER is load-bearing: NUMERIC_KEYS first, then `exercise`,
        // exactly what `sanitize` + `JSON.stringify` produced.
        let json = try #require(transport.sent.first?.json)
        #expect(
            json
                == #"{"v":"0.1.0","events":[{"event":"session_completed","props":{"level":2,"rounds":8,"perfect":6,"points":12,"exercise":"read-image"}}]}"#
        )
    }

    @Test("batches, and a flush with an empty queue is a no-op")
    func batches() async throws {
        let (t, transport, _) = makeTelemetry()
        t.setConsent(true)
        t.track(.shopOpened)
        t.track(.shopOpened)
        t.flush()
        t.flush()
        await t.awaitPendingSends()
        #expect(transport.sent.count == 1)
        #expect(sent(transport)[0].events.count == 2)
    }

    @Test("flushes on its own once the batch fills")
    func autoFlushAtTwenty() async {
        let (t, transport, _) = makeTelemetry()
        t.setConsent(true)
        for _ in 0..<Telemetry.batchSize { t.track(.shopOpened) }
        await t.awaitPendingSends()
        #expect(transport.sent.count == 1)
        #expect(sent(transport)[0].events.count == Telemetry.batchSize)
    }

    /// The Swift analogue of `credentials: "omit"`. The transport signature is
    /// `(Data, URL)` — there is no header bag and no credential to omit — so what
    /// is left to assert is that nothing is smuggled through the URL itself.
    @Test("carries no cookies, no credentials and nothing in the URL")
    func noCredentials() async throws {
        let (t, transport, _) = makeTelemetry()
        t.setConsent(true)
        t.track(.shopOpened)
        t.flush()
        await t.awaitPendingSends()
        let url = try #require(transport.sent.first?.url)
        #expect(url.query == nil)
        #expect(url.user == nil)
        #expect(url.password == nil)
        #expect(url.fragment == nil)
        #expect(url.path == "/events")
    }

    @Test("a transport failure is swallowed, not surfaced")
    func transportFailureSwallowed() async {
        let transport = RecordingTransport(failing: true)
        let (t, _, _) = makeTelemetry(transport: transport)
        t.setConsent(true)
        t.track(.shopOpened)
        t.flush()
        await t.awaitPendingSends()
        #expect(transport.sent.count == 1)  // attempted, threw, nobody noticed
    }
}

@Suite("telemetry — error reports")
@MainActor
struct TelemetryErrorTests {

    private struct Boom: Error, CustomStringConvertible {
        let description: String
    }

    @Test("are sent WITHOUT consent — they carry no identifier, so they are not personal data")
    func sentWithoutConsent() async throws {
        let (t, transport, _) = makeTelemetry()
        #expect(t.hasConsent == false)
        t.reportError(Boom(description: "boom"), where: "test")
        await t.awaitPendingSends()
        let call = try #require(sent(transport).first)
        #expect(call.url.absoluteString == "https://t.test/errors")
        #expect(call.object["message"] as? String == "boom")
        #expect(call.object["where"] as? String == "test")
        #expect(call.object["v"] as? String == "0.1.0")
    }

    @Test("truncate a runaway message instead of shipping it whole")
    func truncatesMessage() async throws {
        let (t, transport, _) = makeTelemetry()
        t.reportError(Boom(description: String(repeating: "x", count: 5000)))
        await t.awaitPendingSends()
        let message = try #require(sent(transport).first?.object["message"] as? String)
        #expect(message.count == 300)
    }

    @Test("truncate the site and the stack too")
    func truncatesSiteAndStack() async throws {
        let (t, transport, _) = makeTelemetry()
        t.reportError(
            "short",
            where: String(repeating: "w", count: 500),
            stack: String(repeating: "s", count: 9000))
        await t.awaitPendingSends()
        let object = try #require(sent(transport).first?.object)
        #expect((object["where"] as? String)?.count == 60)
        #expect((object["stack"] as? String)?.count == 2000)
    }

    @Test("accept a non-Error throw without crashing the game")
    func acceptsANonError() async throws {
        let (t, transport, _) = makeTelemetry()
        t.reportError("plain string")
        await t.awaitPendingSends()
        #expect(sent(transport).first?.object["message"] as? String == "plain string")
        #expect(sent(transport).first?.object["where"] as? String == "unknown")
    }
}

@Suite("telemetry — with no endpoint configured")
@MainActor
struct TelemetryInertTests {

    @Test("is inert — a dev build posts nowhere")
    func inertWithNoEndpoint() async {
        let (t, transport, _) = makeTelemetry(endpoint: nil)
        t.setConsent(true)
        t.track(.shopOpened)
        t.flush()
        t.reportError("boom")
        await t.awaitPendingSends()
        #expect(transport.sent.isEmpty)
    }

    /// The TS guard is `if (!endpoint()) return`, and the test stubs
    /// `VITE_TELEMETRY_URL` to `""` — which is falsy. An empty string must be as
    /// inert as an absent one.
    @Test("an empty endpoint string is as inert as no endpoint")
    func inertWithEmptyEndpoint() async {
        let (t, transport, _) = makeTelemetry(endpoint: "")
        t.setConsent(true)
        t.track(.shopOpened)
        t.flush()
        t.reportError("boom")
        await t.awaitPendingSends()
        #expect(transport.sent.isEmpty)
    }
}
