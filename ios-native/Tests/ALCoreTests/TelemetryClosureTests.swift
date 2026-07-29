import Foundation
import Testing

@testable import ALCore

// Invariant 10 as STRUCTURE, not as discipline (D12 / money.md §6.3).
//
// The TS maintained its allowlist with a `sanitize()` pass and a test that cast
// `{ childName: "Léa" }` through `as unknown as TelemetryProps`. In Swift that
// cast does not exist, so the guarantee moves into the type — and these tests
// document the guarantee and fail the moment somebody widens it.

private let allowlist: Set<String> = [
    "exercise", "level", "rounds", "perfect", "points", "cost", "stage", "daysLeft",
]

/// A fully-populated props bag: every field set, so nothing can hide behind a nil.
private let fullProps = TelemetryProps(
    exercise: .spellTwoSyllablesMixed,
    level: 3, rounds: 9, perfect: 7, points: 21, cost: 40, stage: 6, daysLeft: 11)

@MainActor
private func emitEveryEvent(
    kv: InMemoryKVStore
) async -> RecordingTransport {
    let transport = RecordingTransport()
    let t = Telemetry(
        endpoint: "https://t.test", transport: transport, kv: kv,
        appVersion: FixedAppVersion("0.1.0"))
    t.setConsent(true)
    for event in TelemetryEvent.allCases {
        t.track(event, fullProps)
        t.flush()
    }
    await t.awaitPendingSends()
    return transport
}

@Suite("telemetry is closed by construction")
@MainActor
struct TelemetryClosureTests {

    /// The Swift replacement for the TS `childName: "Léa"` test. Adding a field
    /// to `TelemetryProps` without touching this test and the encoder fails the
    /// build's test run.
    @Test("the reflected field set IS the allowlist")
    func reflectionAllowlist() {
        let labels = Set(Mirror(reflecting: TelemetryProps()).children.compactMap { $0.label })
        #expect(labels == allowlist)
        #expect(Set(TelemetryProps.allowedKeys) == allowlist)
        #expect(labels.count == 8)
    }

    @Test("the encoded key set is a subset of the allowlist, for every event")
    func encodedKeysAreASubset() async throws {
        let transport = await emitEveryEvent(kv: InMemoryKVStore())
        #expect(transport.sent.count == TelemetryEvent.allCases.count)

        for record in transport.sent {
            let envelope = try #require(
                try JSONSerialization.jsonObject(with: record.body) as? [String: Any])
            #expect(Set(envelope.keys) == ["v", "events"])
            let events = try #require(envelope["events"] as? [[String: Any]])
            for event in events {
                #expect(Set(event.keys) == ["event", "props"])
                let props = try #require(event["props"] as? [String: Any])
                #expect(Set(props.keys).isSubset(of: allowlist))
                // The event name is itself from the closed enum.
                let name = try #require(event["event"] as? String)
                #expect(TelemetryEvent.allCases.map(\.rawValue).contains(name))
            }
        }
    }

    /// D12's byte-level test. The roster holds six-year-olds' first names; a
    /// per-install identifier would make the payload pseudonymous rather than
    /// anonymous. Neither may appear in a single byte that leaves the device.
    ///
    /// This is structurally guaranteed — `Telemetry` never reads the roster key
    /// and never names the device-identity function — and the test documents the
    /// guarantee.
    @Test("no child's name and no per-install id can appear in any payload")
    func noNamesNoIdentifiers() async {
        let kv = InMemoryKVStore([
            // A roster shaped exactly like the one on disk, sitting in the very
            // store telemetry was handed.
            "attrape-lettres:roster:v4":
                #"{"children":[{"id":"c1","name":"Léa","nameRev":3},{"id":"c2","name":"Tom","nameRev":1}],"activeId":"c1"}"#,
            "attrape-lettres:device:v1": "dad-phone",
        ])
        let transport = await emitEveryEvent(kv: kv)

        for record in transport.sent {
            let json = record.json
            #expect(!json.contains("Léa"))
            #expect(!json.contains("Tom"))
            #expect(!json.contains("nameRev"))
            #expect(!json.contains("activeId"))
            #expect(!json.contains("dad-phone"))
            #expect(!json.contains("name"))
            #expect(!json.contains("roster"))
        }
    }

    /// The event path carries numbers and one closed-enum raw value. Nothing
    /// else. `reportError` is the ONLY producer of free-form strings in the
    /// module, and it is on a different path (`/errors`) and a different API.
    @Test("no free text on the event path")
    func noFreeTextOnTheEventPath() async throws {
        let transport = await emitEveryEvent(kv: InMemoryKVStore())
        let exerciseIds = Set(ExerciseId.allCases.map(\.rawValue))

        for record in transport.sent {
            let envelope = try #require(
                try JSONSerialization.jsonObject(with: record.body) as? [String: Any])
            let events = try #require(envelope["events"] as? [[String: Any]])
            for event in events {
                let props = try #require(event["props"] as? [String: Any])
                for (key, value) in props {
                    if key == "exercise" {
                        let raw = try #require(value as? String)
                        #expect(exerciseIds.contains(raw))
                    } else {
                        #expect(
                            value is NSNumber,
                            "prop \(key) is not a number — a string escape hatch has opened")
                    }
                }
            }
        }
    }

    /// `TelemetryEvent` is a closed list of eleven wire names, six of which have
    /// no producer today (money.md R10 — aspirational, ported whole).
    @Test("the event list is exactly the eleven wire names")
    func eventListIsClosed() {
        #expect(
            TelemetryEvent.allCases.map(\.rawValue) == [
                "exercise_started", "session_completed", "shop_opened", "item_bought",
                "mascot_grown", "trial_started", "trial_expired", "paywall_shown",
                "purchase_completed", "purchase_failed", "purchase_restored",
            ])
    }

    /// Empty props encode as an empty object, not as nulls: a field that is not
    /// set is a field that is not sent.
    @Test("unset fields are absent, never null")
    func unsetFieldsAreAbsent() {
        #expect(Telemetry.encodeProps(TelemetryProps()) == "{}")
        #expect(Telemetry.encodeProps(TelemetryProps(level: 1)) == #"{"level":1}"#)
        #expect(
            Telemetry.encodeProps(fullProps)
                == #"{"level":3,"rounds":9,"perfect":7,"points":21,"cost":40,"stage":6,"daysLeft":11,"exercise":"spell-two-syllables-mixed"}"#
        )
    }
}
