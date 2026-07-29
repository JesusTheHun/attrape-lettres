import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Port of `src/sync/client.test.ts` § "the wire format — what the server is    */
/* allowed to know".                                                            */
/*                                                                             */
/* `WireChild` has no `name`/`nameRev` property and `WireRoster` no `activeId`, */
/* so the type already guarantees most of this — the serialised-bytes           */
/* assertions are KEPT anyway (invariant 10): they guard the Codable            */
/* conformance against a future custom encoder quietly re-adding a field.       */
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

private func json<T: Encodable>(_ value: T) -> String {
    String(data: try! JSONEncoder().encode(value), encoding: .utf8)!
}

@Suite struct WireTests {
    @Test func stripsNamesAndTheirStamps() {
        let wire = toWire(roster([kid("lea", "Léa"), kid("tom", "Tom")]))
        let encoded = json(wire)
        #expect(!encoded.contains("Léa"))
        #expect(!encoded.contains("Tom"))
        #expect(!encoded.contains("nameRev"))
        // What DOES travel: opaque ids and integers.
        #expect(wire.children.map(\.id) == ["lea", "tom"])
    }

    @Test func neverSendsWhoIsHoldingThisTablet() {
        let wire = toWire(roster([kid("lea", "Léa")], activeId: "lea"))
        #expect(!json(wire).contains("activeId"))
    }

    @Test func reattachesNamesThisDeviceAlreadyKnows() {
        let local = roster([kid("lea", "Léa")])
        let back = fromWire(toWire(local), local: local)
        #expect(back.children[0].name == "Léa")
        #expect(back.children[0].nameRev == Rev(at: 10, by: "dad"))
    }

    @Test func givesANeverSeenChildAPlaceholderThatAlwaysLosesToALocalName() {
        let fromOtherPhone = toWire(roster([kid("tom", "Tom")]))
        let joined = fromWire(fromOtherPhone, local: roster([]))
        #expect(joined.children[0].name == "Enfant")
        // Zero stamp ⇒ the moment this parent names them, that name wins forever.
        #expect(joined.children[0].nameRev == Rev.zero)
    }

    @Test func fromWireNeverAdoptsAnActivePlayer() {
        let local = roster([kid("lea", "Léa")], activeId: "lea")
        let back = fromWire(toWire(local), local: local)
        #expect(back.activeId == nil)
    }

    @Test func removedTombstonesTravelBothWays() {
        var r = roster([kid("lea", "Léa")])
        r.removed = ["tom": 99]
        let wire = toWire(r)
        #expect(wire.removed == ["tom": 99])
        #expect(fromWire(wire, local: r).removed == ["tom": 99])
    }

    /// Self-interop: what Swift pushes, Swift (and the TS `JSON.parse` on the
    /// other phones) can pull back unchanged — field names are byte-identical
    /// to the TS property names.
    @Test func wireRosterSurvivesAnEncodeDecodeRoundTrip() throws {
        let wire = toWire(roster([kid("lea", "Léa", earned: ["dad-phone": 10])]))
        let data = try JSONEncoder().encode(wire)
        let back = try JSONDecoder().decode(WireRoster.self, from: data)
        #expect(back == wire)
        let object = try #require(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        #expect(Set(object.keys) == ["children", "removed"])
        let child = try #require((object["children"] as? [[String: Any]])?.first)
        #expect(Set(child.keys) == ["id", "touchedAt", "profile"])
    }
}
