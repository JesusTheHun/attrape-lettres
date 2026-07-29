import Foundation
import Testing

@testable import ALCore

// storage.ts behaviour: synchronous and TOTAL — a caller gets a value or a
// safe default, never a throw. Corruption degrades, it never erases.

private let device = "this-phone"
private let now: Millis = 1_700_000_000_000

@Suite struct ProfileStorageTests {
    @Test func keysAreByteExact() {
        // D7: these strings are a persistence contract shared with the
        // Capacitor build. Changing one orphans a family's roster.
        #expect(ProfileStorage.rosterKey == "attrape-lettres:roster:v4")
        #expect(ProfileStorage.v3Key == "attrape-lettres:roster:v3")
        #expect(ProfileStorage.v2Key == "attrape-lettres:profile:v2")
        #expect(ProfileStorage.v1Key == "attrape-lettres:profile:v1")
        #expect(ProfileStorage.shopSeenKey == "attrape-lettres:shop-seen:v1")
    }

    @Test func loadRosterIsNilWhenAbsent() {
        #expect(ProfileStorage.loadRoster(InMemoryKVStore()) == nil)
    }

    @Test func loadRosterIsNilOnUnparseableJSON() {
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: "not json {"])
        #expect(ProfileStorage.loadRoster(kv) == nil)
    }

    @Test func loadRosterIsNilOnAWrongTopLevelShape() {
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: "[1,2,3]"])
        #expect(ProfileStorage.loadRoster(kv) == nil)
    }

    // The point of LooseDecoding: a partially-corrupt blob DEGRADES — wrong-
    // typed fields read as absent and take their defaults — instead of the
    // whole roster reading as nil and a child losing everything.
    @Test func partiallyCorruptBlobDegradesInsteadOfVanishing() {
        let json = """
        {"children":[{"id":42,"name":"Léa","nameRev":"bogus","touchedAt":"soon","profile":{"chosen":"yes","current":"fox","currentRev":{"at":1,"by":"d"},"species":{},"stars":{"earned":{"d":7},"spent":{}},"clears":{}}}],"activeId":null,"removed":{}}
        """
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        #expect(roster.children.count == 1)
        let c = roster.children[0]
        #expect(c.name == "Léa")             // the good fields survive
        #expect(!c.id.isEmpty)               // id: 42 → absent → freshly minted
        #expect(c.nameRev == .zero)          // "bogus" → absent → zero rev
        #expect(c.touchedAt == 0)            // "soon" → absent → 0
        #expect(c.profile.chosen == false)   // "yes" → absent → false
        #expect(c.profile.current == .fox)
        #expect(c.profile.stars.earned == ["d": 7]) // the stars survive
    }

    @Test func saveThenLoadRoundTripsThroughTheLooseLayer() {
        var profile = defaultProfile
        profile.chosen = true
        profile.current = .dragon
        profile.currentRev = Rev(at: now, by: device)
        profile.species[.dragon].config.stage = 5
        profile.species[.dragon].owned = ["dragon.wings.gold"]
        profile.species[.dragon].rev = Rev(at: now, by: device)
        profile.stars = StarCounters(earned: [device: 12, "mum-phone": 6], spent: [device: 4])
        profile.clears = ["read-image:1": [device: 2, "mum-phone": 1]]
        let roster = Roster(
            children: [ChildProfile(id: "c1", name: "Léa", nameRev: Rev(at: now, by: device), touchedAt: now, profile: profile)],
            activeId: "c1",
            removed: ["old-child": 123]
        )

        let kv = InMemoryKVStore()
        ProfileStorage.saveRoster(roster, to: kv)
        let reloaded = initialRoster(kv: kv, device: device, now: now + 1)
        #expect(reloaded == roster)
    }

    // Spec risk #6: a hypothetical JS writer emitting floats must not turn the
    // whole blob into "no roster". The loose layer truncates.
    @Test func floatNumbersAreToleratedAndTruncated() {
        let json = """
        {"children":[{"id":"c1","name":"Léa","nameRev":{"at":1.7e12,"by":"d"},"touchedAt":1.7e12,"profile":{"chosen":true,"current":"cat","currentRev":{"at":0,"by":""},"species":{},"stars":{"earned":{"d":17.0},"spent":{}},"clears":{"a:1":{"d":2.0}}}}],"activeId":"c1","removed":{"x":9.5}}
        """
        let kv = InMemoryKVStore([ProfileStorage.rosterKey: json])
        let roster = initialRoster(kv: kv, device: device, now: now)
        let c = roster.children[0]
        #expect(c.touchedAt == 1_700_000_000_000)
        #expect(c.profile.stars.earned == ["d": 17])
        #expect(c.profile.clears["a:1"] == ["d": 2])
        #expect(roster.removed == ["x": 9])
    }

    @Test func shopSeenRoundTripsAndDefaultsEmpty() {
        let kv = InMemoryKVStore()
        #expect(ProfileStorage.loadShopSeen(kv) == [:])
        ProfileStorage.saveShopSeen(["c1": 17, "c2": 0], to: kv)
        #expect(ProfileStorage.loadShopSeen(kv) == ["c1": 17, "c2": 0])
        // Corruption degrades to the empty default — the meter just won't
        // animate next visit.
        kv.set("###", for: ProfileStorage.shopSeenKey)
        #expect(ProfileStorage.loadShopSeen(kv) == [:])
    }

    @Test func legacyLoadersAreDecodeOrNil() {
        let kv = InMemoryKVStore([
            ProfileStorage.v3Key: "garbage",
            ProfileStorage.v2Key: "null",
            ProfileStorage.v1Key: "[]",
        ])
        #expect(ProfileStorage.loadV3Roster(kv) == nil)
        #expect(ProfileStorage.loadV2Profile(kv) == nil)
        #expect(ProfileStorage.loadV1Profile(kv) == nil)
        // And absent keys are nil too, so initialRoster lands on empty.
        let empty = InMemoryKVStore()
        #expect(ProfileStorage.loadV3Roster(empty) == nil)
        #expect(ProfileStorage.loadV2Profile(empty) == nil)
        #expect(ProfileStorage.loadV1Profile(empty) == nil)
    }

    // Invariant 9 at the storage boundary: what saveRoster writes carries
    // counters, never a folded total.
    @Test func savedRosterJSONNeverContainsATotal() throws {
        var profile = defaultProfile
        profile.stars = StarCounters(earned: [device: 10], spent: [device: 3])
        let roster = Roster(
            children: [ChildProfile(id: "c1", name: "Léa", nameRev: .zero, touchedAt: now, profile: profile)],
            activeId: "c1",
            removed: [:]
        )
        let kv = InMemoryKVStore()
        ProfileStorage.saveRoster(roster, to: kv)
        let json = try #require(kv.string(ProfileStorage.rosterKey))
        #expect(json.contains("\"earned\""))
        #expect(!json.contains("\"balance\""))
        #expect(!json.contains("\"ledger\""))
    }
}

// Cheap Equatable shims so `== nil` reads naturally above.
private func == (lhs: LooseRoster?, rhs: LooseRoster?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil): return true
    default: return false
    }
}
private func == (lhs: LegacyV3Roster?, rhs: LegacyV3Roster?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil): return true
    default: return false
    }
}
private func == (lhs: LegacyFlatProfile?, rhs: LegacyFlatProfile?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil): return true
    default: return false
    }
}
private func == (lhs: LegacyV1Profile?, rhs: LegacyV1Profile?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil): return true
    default: return false
    }
}
