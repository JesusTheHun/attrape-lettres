import Foundation
import Testing

@testable import ALCore

/// The persisted-model structure — the compile-time half of invariant 9 made
/// observable where it can be, plus the JSON details that keep blobs
/// interchangeable with the PWA's.
@Suite struct ProfileModelsTests {
    private func sampleProfile() -> PersistedProfile {
        var p = defaultProfile
        p.chosen = true
        p.current = .fox
        p.currentRev = Rev(at: 1_700_000_000_000, by: "dad-phone")
        p.species[.fox].config.stage = 3
        p.species[.fox].config.colors["furColor"] = "#F80"
        p.species[.fox].owned = ["fox.fur.orange"]
        p.species[.fox].rev = Rev(at: 1_700_000_000_000, by: "dad-phone")
        p.stars = StarCounters(earned: ["dad-phone": 17], spent: ["dad-phone": 4])
        p.clears = ["read-image:1": ["dad-phone": 2]]
        return p
    }

    // Invariant 9, byte-level: the persisted encoding of a profile carrying
    // stars and clears contains counters only — never a "balance" or "ledger"
    // key. (The type has no such property; this also guards the Codable
    // conformance against a future custom encoder regression.)
    @Test func persistedProfileEncodesCountersNeverTotals() throws {
        let data = try JSONEncoder().encode(sampleProfile())
        let json = String(decoding: data, as: UTF8.self)
        #expect(json.contains("\"earned\""))
        #expect(json.contains("\"spent\""))
        #expect(json.contains("\"clears\""))
        #expect(!json.contains("\"balance\""))
        #expect(!json.contains("\"ledger\""))
    }

    // Invariant 9, structural: the flattened view the UI reads is NOT Codable,
    // so persisting or wiring it is a compile error. The runtime check pins the
    // absence of the conformances.
    @Test func profileViewIsDeliberatelyNotCodable() {
        let view = ProfileView(of: sampleProfile(), balance: 13, ledger: ["read-image:1": 2])
        #expect(!((view as Any) is Encodable))
        #expect(!((view as Any) is Decodable))
    }

    @Test func profileViewFlattensTheCurrentSpecies() {
        let view = ProfileView(of: sampleProfile(), balance: 13, ledger: ["read-image:1": 2])
        #expect(view.config.species == .fox)
        #expect(view.config.stage == 3)
        #expect(view.config.colors["furColor"] == "#F80")
        #expect(view.owned == ["fox.fur.orange"])
        #expect(view.balance == 13)
        #expect(view.ledger["read-image:1"] == 2)
        // The persisted fields ride along, as in TS `Profile extends PersistedProfile`.
        #expect(view.chosen)
        #expect(view.stars.earned == ["dad-phone": 17])
    }

    @Test func speciesMapSubscriptRoundTripsAllFiveSlots() {
        var map = blankSpeciesMap()
        for s in Species.allCases {
            var p = blankProgress(s)
            p.config.stage = 7
            map[s] = p
            #expect(map[s].config.stage == 7)
            #expect(map[s].config.species == s)
        }
    }

    @Test func speciesMapDecodeFillsMissingSlotsAndIgnoresUnknownKeys() throws {
        // Only the fox present, plus a key no version of the app ever wrote.
        let json = """
        {"fox":{"config":{"species":"fox","stage":2,"colors":{},"styles":{},"accessories":[]},"owned":["fox.fur.orange"],"rev":{"at":5,"by":"d"}},
         "griffon":{"config":{"species":"griffon","stage":9,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":1,"by":"x"}}}
        """
        let map = try JSONDecoder().decode(SpeciesMap.self, from: Data(json.utf8))
        #expect(map[.fox].config.stage == 2)
        #expect(map[.fox].owned == ["fox.fur.orange"])
        #expect(map[.unicorn] == blankProgress(.unicorn))
        #expect(map[.dragon] == blankProgress(.dragon))
    }

    @Test func speciesMapEncodesAllFiveSlots() throws {
        let data = try JSONEncoder().encode(blankSpeciesMap())
        let json = String(decoding: data, as: UTF8.self)
        for s in Species.allCases {
            #expect(json.contains("\"\(s.rawValue)\""))
        }
    }

    @Test func rosterEncodesActiveIdNullExplicitly() throws {
        let roster = Roster(children: [], activeId: nil, removed: [:])
        let json = String(decoding: try JSONEncoder().encode(roster), as: UTF8.self)
        // JS JSON.stringify writes the null; we match it rather than omitting.
        #expect(json.contains("\"activeId\":null"))
    }

    @Test func rosterCodableRoundTrips() throws {
        let roster = Roster(
            children: [
                ChildProfile(
                    id: "lea-uuid",
                    name: "Léa",
                    nameRev: Rev(at: 42, by: "dad-phone"),
                    touchedAt: 99,
                    profile: sampleProfile()
                )
            ],
            activeId: "lea-uuid",
            removed: ["ghost": 123]
        )
        let data = try JSONEncoder().encode(roster)
        let back = try JSONDecoder().decode(Roster.self, from: data)
        #expect(back == roster)
    }

    @Test func zeroRevIsTheAlwaysLosingStamp() {
        #expect(Rev.zero == Rev(at: 0, by: ""))
    }

    @Test func defaultProfileIsBlankUnchosenUnicorn() {
        #expect(defaultProfile.chosen == false)
        #expect(defaultProfile.current == .unicorn)
        #expect(defaultProfile.currentRev == .zero)
        #expect(defaultProfile.stars == StarCounters(earned: [:], spent: [:]))
        #expect(defaultProfile.clears.isEmpty)
        for s in Species.allCases {
            #expect(defaultProfile.species[s] == blankProgress(s))
        }
    }
}

@Suite struct ApplyOptionTests {
    private let base = MascotConfig(species: .unicorn, stage: 0, colors: [:], styles: [:], accessories: [])

    @Test func colorSetsItsSlot() {
        let horn = CustomizationOption(
            id: "unicorn.horn.rainbow", species: .unicorn, category: .color,
            slot: "hornColor", value: "#F0A", name: "Corne arc-en-ciel", cost: 4
        )
        let next = applyOption(base, horn)
        #expect(next.colors["hornColor"] == "#F0A")
        #expect(next.accessories.isEmpty)
    }

    @Test func styleSetsItsSlot() {
        let tail = CustomizationOption(
            id: "unicorn.tail.long", species: .unicorn, category: .style,
            slot: "tailSize", value: "long", name: "Grande queue", cost: 3
        )
        #expect(applyOption(base, tail).styles["tailSize"] == "long")
    }

    @Test func accessoryAppendsOnceAndReapplyIsIdentity() {
        let crown = CustomizationOption(
            id: "unicorn.crown", species: .unicorn, category: .accessory,
            slot: "crown", value: "", name: "Couronne", cost: 6
        )
        let once = applyOption(base, crown)
        #expect(once.accessories == ["unicorn.crown"])
        let twice = applyOption(once, crown)
        #expect(twice == once)
    }
}
