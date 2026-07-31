import Foundation
import Observation

/* -------------------------------------------------------------------------- */
/* First-party telemetry. Our server, no SDK, no vendor.                        */
/*                                                                             */
/* Kids Category guideline 1.3 bans sending personally identifiable information */
/* OR DEVICE INFORMATION to THIRD PARTIES. Both halves matter: "third party" is */
/* why this posts to our own endpoint instead of PostHog or Firebase, and       */
/* "device information" is why there is no SDK — every drop-in analytics client */
/* ships device model, OS, locale, screen size and an install id by default.    */
/*                                                                             */
/* What leaves the device, ever:                                                */
/*   • an event name from a closed list                                         */
/*   • numbers, and exercise ids from a closed list                             */
/*   • the app version                                                          */
/* That is all. No per-install identifier, no child id, no name, no free text,  */
/* no timestamps of the child's day. The payload is genuinely anonymous rather  */
/* than merely pseudonymous, which is what lets the privacy policy say so       */
/* plainly and the Data Safety form stay nearly empty.                          */
/* -------------------------------------------------------------------------- */
//
// Port of `src/telemetry.ts`. Owner of **invariant 10** together with
// `Sync/Wire.swift`.
//
// D12: nothing in this directory references the device-identity module, by
// construction rather than by convention — it is not imported, its function is
// not named, and `MoneySourceScanTests` fails the build's test run if either
// changes.

@MainActor
@Observable
public final class Telemetry {
    /// The batch size at which `track` flushes on its own.
    public static let batchSize = 20

    /// The process-wide instance the screens call. Inert until the app layer
    /// replaces it at boot: a nil endpoint means the whole module posts nowhere,
    /// which is exactly the tested "a dev build posts nowhere" case.
    public static var shared = Telemetry(
        endpoint: nil,
        transport: NullTelemetryTransport(),
        kv: InMemoryKVStore(),
        appVersion: FixedAppVersion("0.0.0"))

    @ObservationIgnored private let endpoint: String?
    @ObservationIgnored private let transport: TelemetryTransport
    @ObservationIgnored private let appVersion: AppVersionProvider
    @ObservationIgnored private let kv: KVStore
    @ObservationIgnored private var queue: [Queued] = []
    @ObservationIgnored private var flushing = false
    @ObservationIgnored private var pending: [Task<Void, Never>] = []

    private struct Queued {
        let event: TelemetryEvent
        let props: TelemetryProps
    }

    /**
     * - Parameter endpoint: the base URL as a string, e.g. `"https://t.test"`.
     *   `nil` **and the empty string** both make the module inert. The empty
     *   string matters: the TS reads `import.meta.env.VITE_TELEMETRY_URL` and
     *   guards with `if (!endpoint()) return`, so `""` is falsy there and the
     *   1:1 test stubs exactly that.
     */
    public init(
        endpoint: String?,
        transport: TelemetryTransport,
        kv: KVStore,
        appVersion: AppVersionProvider
    ) {
        let raw = endpoint ?? ""
        self.endpoint = raw.isEmpty ? nil : raw
        self.transport = transport
        self.kv = kv
        self.appVersion = appVersion
    }

    // MARK: - Consent

    public var consent: TelemetryConsent { TelemetryConsent(kv) }

    /// `hasConsent()`.
    public var hasConsent: Bool { consent.has }

    /// `consentAnswered()` — unset ≠ refused.
    public var consentAnswered: Bool { consent.answered }

    /// The public write path. Withdrawal **drains the queue immediately**:
    /// retroactive for anything not yet sent.
    public func setConsent(_ on: Bool) {
        consent.write(on)
        if !on { queue.removeAll() }
    }

    // MARK: - Events

    /**
     * Record an event, if the parent opted in. Fire-and-forget: never awaited,
     * never blocks a tap, never throws into the game loop. Synchronous in and
     * out — the network hop happens in a detached task, because invariant 1
     * forbids anything on the feedback path behind a hop.
     */
    public func track(_ event: TelemetryEvent, _ props: TelemetryProps = TelemetryProps()) {
        guard hasConsent, endpoint != nil else { return }
        queue.append(Queued(event: event, props: props))
        if queue.count >= Self.batchSize { flush() }
    }

    /// Send whatever is queued. Called on backgrounding, and before a hard exit.
    ///
    /// The `flushing` re-entrancy guard is vestigial in JS but real here once a
    /// task is involved — keep it.
    public func flush() {
        guard !flushing, !queue.isEmpty, endpoint != nil else { return }
        flushing = true
        let batch = queue
        queue.removeAll()
        post("/events", encodeEnvelope(batch))
        flushing = false
    }

    /**
     * Crash/error reporting — deliberately **NOT** consent-gated.
     *
     * The payload carries no identifier of any kind, so it is not personal data
     * and needs no consent. That split is the point: we still hear about the bug
     * that breaks the game for the ~60% of parents who decline analytics.
     *
     * `message` and `stack` are the only free-form values in this file. They are
     * truncated, and NOTHING from app state is ever attached — the roster holds
     * children's first names, and one careless context dump would ship them here.
     *
     * **[DEVIATION] Truncation unit.** JS `slice(0, 300)` counts UTF-16 code
     * units; `String(s.prefix(300))` counts grapheme clusters. Identical for
     * ASCII (the tested case) and strictly safer — it cannot split a surrogate
     * pair or a combining sequence. (money.md R5 — do not "fix" it back.)
     */
    public func reportError(_ message: String, where site: String = "unknown", stack: String = "")
    {
        guard endpoint != nil else { return }
        post(
            "/errors",
            encodeErrorReport(
                message: String(message.prefix(300)),
                site: String(site.prefix(60)),
                stack: String(stack.prefix(2000))))
    }

    /// `err instanceof Error ? err : new Error(String(err))`.
    public func reportError(_ error: Error, where site: String = "unknown", stack: String = "") {
        let message = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
        reportError(message, where: site, stack: stack)
    }

    /**
     * Await every send this instance has started. Test support, and the hook the
     * app layer's background-task flush uses to reproduce `keepalive: true`:
     * `beginBackgroundTask` → `flush()` → `await awaitPendingSends()` → end task.
     */
    public func awaitPendingSends() async {
        let tasks = pending
        pending.removeAll()
        for task in tasks { await task.value }
    }

    // MARK: - Transport

    private func post(_ path: String, _ json: String) {
        // `${endpoint}${path}` — plain concatenation, as the TS does. Appending a
        // path component through URL would percent-escape the leading slash.
        guard let endpoint, let url = URL(string: endpoint + path) else { return }
        let body = Data(json.utf8)
        let sender = transport
        let task = Task<Void, Never> {
            do { try await sender.send(body, to: url) } catch {
                /* telemetry must never surface to a child */
            }
        }
        pending.append(task)
    }

    // MARK: - Wire encoding, hand-written and ordered

    /// `post("/events", { v, events })`.
    private func encodeEnvelope(_ batch: [Queued]) -> String {
        var out = "{\"v\":"
        out += TelemetryJSON.quoted(appVersion.marketing)
        out += ",\"events\":["
        for (i, item) in batch.enumerated() {
            if i > 0 { out += "," }
            out += "{\"event\":"
            out += TelemetryJSON.quoted(item.event.rawValue)
            out += ",\"props\":"
            out += Self.encodeProps(item.props)
            out += "}"
        }
        out += "]}"
        return out
    }

    private func encodeErrorReport(message: String, site: String, stack: String) -> String {
        // Key order matches the TS object literal: v, where, message, stack.
        var out = "{\"v\":"
        out += TelemetryJSON.quoted(appVersion.marketing)
        out += ",\"where\":"
        out += TelemetryJSON.quoted(site)
        out += ",\"message\":"
        out += TelemetryJSON.quoted(message)
        out += ",\"stack\":"
        out += TelemetryJSON.quoted(stack)
        out += "}"
        return out
    }

    /**
     * One `if let` per field, in the TS insertion order, so the JSON is
     * byte-comparable with the PWA's: the `NUMERIC_KEYS` order first
     * (`level, rounds, perfect, points, cost, stage, daysLeft`), then `exercise`
     * — that is exactly what `sanitize` produced and `JSON.stringify` preserved.
     *
     * Hand-written **is the point** (D12). Synthesised `Codable` would emit
     * optionals by `encodeIfPresent` in declaration order — which happens to
     * differ from the wire order here, and would also silently start emitting any
     * field a future edit adds. Adding a field without touching this function
     * produces a field that is never sent, which is the safe direction.
     */
    static func encodeProps(_ p: TelemetryProps) -> String {
        var parts: [String] = []
        if let v = p.level { parts.append("\"level\":\(v)") }
        if let v = p.rounds { parts.append("\"rounds\":\(v)") }
        if let v = p.perfect { parts.append("\"perfect\":\(v)") }
        if let v = p.points { parts.append("\"points\":\(v)") }
        if let v = p.cost { parts.append("\"cost\":\(v)") }
        if let v = p.stage { parts.append("\"stage\":\(v)") }
        if let v = p.daysLeft { parts.append("\"daysLeft\":\(v)") }
        if let v = p.exercise { parts.append("\"exercise\":" + TelemetryJSON.quoted(v.rawValue)) }
        return "{" + parts.joined(separator: ",") + "}"
    }
}

/// Minimal RFC 8259 string escaping. Non-ASCII passes through as UTF-8, exactly
/// as `JSON.stringify` does — which is what makes the byte-level "no child's
/// name in the payload" assertion an honest test rather than an artefact of
/// `\u` escaping.
enum TelemetryJSON {
    static func quoted(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}
