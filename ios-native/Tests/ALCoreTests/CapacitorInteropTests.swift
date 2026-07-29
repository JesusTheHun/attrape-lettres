import Foundation
import Testing

@testable import ALCore

// D7 / persistence spec §8 — the whole point of the key-prefix decision: a
// family updating in place from the Capacitor build to the native one keeps
// their roster, their device id and their stars, because
// `@capacitor/preferences` stored everything in `UserDefaults.standard` under
// `CapacitorStorage.<key>` and the Swift loader reads exactly those keys.

/// A test-local UserDefaults adapter (the production one lives in ALPlatform,
/// W17). Host-testable via a throwaway suite.
private final class TestUserDefaultsStore: KVStore {
    let defaults: UserDefaults
    let suiteName: String

    /// NB: the suite name is made unique PER PROCESS. `UserDefaults` is host
    /// state, not process state, so two `swift test` runs on the same machine —
    /// two CI jobs, or two agents in one worktree — share a fixed suite name
    /// and `removePersistentDomain` in one wipes the other's seeded keys
    /// mid-test. Measured: two concurrent processes doing exactly the
    /// clear/seed/read below lose ~6% of their reads; one process loses none.
    /// That is a flake that reads as a persistence bug, so the domains are kept
    /// disjoint instead.
    init?(suiteName: String) {
        let unique = "\(suiteName).pid\(ProcessInfo.processInfo.processIdentifier)"
        guard let defaults = UserDefaults(suiteName: unique) else { return nil }
        self.defaults = defaults
        self.suiteName = unique
        defaults.removePersistentDomain(forName: unique)
    }

    func string(_ key: String) -> String? { defaults.string(forKey: key) }
    func set(_ value: String, for key: String) { defaults.set(value, forKey: key) }
    func remove(_ key: String) { defaults.removeObject(forKey: key) }

    func tearDown() { defaults.removePersistentDomain(forName: suiteName) }
}

/// A v4 roster blob exactly as the Capacitor build's `JSON.stringify` writes
/// it: all five (normalised) species slots, explicit `"activeId"`, `"removed"`.
private let capacitorRosterJSON = """
{"children":[{"id":"3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11","name":"Léa","nameRev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"},"touchedAt":1719400001000,"profile":{"chosen":true,"current":"unicorn","currentRev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"},"species":{"unicorn":{"config":{"species":"unicorn","stage":2,"colors":{"hornColor":"#F0A"},"styles":{},"accessories":["unicorn.crown"]},"owned":["unicorn.horn.rainbow","unicorn.crown"],"rev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"}},"cat":{"config":{"species":"cat","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"fox":{"config":{"species":"fox","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"rabbit":{"config":{"species":"rabbit","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"dragon":{"config":{"species":"dragon","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}}},"stars":{"earned":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":12},"spent":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":4}},"clears":{"read-image:1":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":2}}}}],"activeId":"3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11","removed":{}}
"""

private let capacitorDeviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

@Suite struct CapacitorInteropTests {
    @Test func thePrefixIsByteExact() {
        // `@capacitor/preferences` default group. Changing this loses a
        // family's roster on update — it is a contract, not a style choice.
        #expect(CapacitorInterop.keyPrefix == "CapacitorStorage.")
    }

    @Test func prefixedStoreMapsLogicalKeysToPhysicalOnes() {
        let base = InMemoryKVStore()
        let kv = PrefixedKVStore(base: base)
        kv.set("{}", for: "attrape-lettres:roster:v4")
        #expect(base.snapshot.keys.contains("CapacitorStorage.attrape-lettres:roster:v4"))
        #expect(base.string("attrape-lettres:roster:v4") == nil)
        #expect(kv.string("attrape-lettres:roster:v4") == "{}")
        kv.remove("attrape-lettres:roster:v4")
        #expect(base.snapshot.isEmpty)
    }

    // The D7 acceptance test: a real Capacitor-shaped UserDefaults domain
    // reads through the Swift loader — roster, device id and all.
    @Test func readsARealCapacitorShapedUserDefaultsDomain() throws {
        let store = try #require(TestUserDefaultsStore(suiteName: "al-capacitor-interop-v4"))
        defer { store.tearDown() }

        // Seed the PHYSICAL keys exactly as the Capacitor build left them.
        store.set(capacitorRosterJSON, for: "CapacitorStorage.attrape-lettres:roster:v4")
        store.set(capacitorDeviceId, for: "CapacitorStorage.attrape-lettres:device:v1")
        store.set(#"{"3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11":8}"#, for: "CapacitorStorage.attrape-lettres:shop-seen:v1")

        let kv = PrefixedKVStore(base: store)

        // The device id survives the update — counters keep accruing under the
        // same key instead of leaving a dangling one.
        #expect(DeviceIdentity(kv: kv).deviceId() == capacitorDeviceId)

        let roster = initialRoster(kv: kv, device: capacitorDeviceId, now: 1_719_500_000_000)
        #expect(roster.children.count == 1)
        let lea = roster.children[0]
        #expect(lea.name == "Léa")
        #expect(roster.activeId == lea.id)
        #expect(lea.profile.chosen)
        #expect(lea.profile.current == .unicorn)
        #expect(lea.profile.species[.unicorn].config.stage == 2)
        #expect(lea.profile.species[.unicorn].config.colors["hornColor"] == "#F0A")
        #expect(lea.profile.species[.unicorn].owned == ["unicorn.horn.rainbow", "unicorn.crown"])
        #expect(lea.profile.stars.earned == [capacitorDeviceId: 12])
        #expect(lea.profile.stars.spent == [capacitorDeviceId: 4])
        #expect(lea.profile.clears["read-image:1"] == [capacitorDeviceId: 2])

        #expect(ProfileStorage.loadShopSeen(kv) == ["3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11": 8])
    }

    // A v3-era Capacitor install migrates through the same prefix.
    @Test func aCapacitorV3BlobMigratesThroughThePrefix() throws {
        let store = try #require(TestUserDefaultsStore(suiteName: "al-capacitor-interop-v3"))
        defer { store.tearDown() }

        let v3 = """
        {"children":[{"id":"lea-v3","name":"Léa","profile":{"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":3,"colors":{"furColor":"#F80"},"styles":{},"accessories":[]},"owned":["fox.fur.orange"]}},"balance":17,"ledger":{"read-image:1":2}}}],"activeId":"lea-v3"}
        """
        store.set(v3, for: "CapacitorStorage.attrape-lettres:roster:v3")

        let kv = PrefixedKVStore(base: store)
        let device = DeviceIdentity(kv: kv).deviceId()
        let roster = initialRoster(kv: kv, device: device, now: 1_719_500_000_000)
        #expect(roster.children.count == 1)
        #expect(roster.children[0].profile.stars.earned == [device: 17])
        // Forward, additive: the v3 blob stays where a rolled-back Capacitor
        // build would look for it.
        #expect(store.string("CapacitorStorage.attrape-lettres:roster:v3") == v3)
    }

    // Self-interop: what Swift writes reads back through the same loader —
    // and lands under the physical key a Capacitor build would read on
    // rollback (the migration philosophy applied across the platform boundary).
    @Test func swiftWrittenRosterReadsBackThroughTheSamePrefix() throws {
        let store = try #require(TestUserDefaultsStore(suiteName: "al-capacitor-interop-self"))
        defer { store.tearDown() }
        let kv = PrefixedKVStore(base: store)

        var profile = defaultProfile
        profile.chosen = true
        profile.stars = StarCounters(earned: ["d1": 9], spent: [:])
        let roster = Roster(
            children: [ChildProfile(id: "c1", name: "Tom", nameRev: Rev(at: 1, by: "d1"), touchedAt: 2, profile: profile)],
            activeId: nil,
            removed: [:]
        )
        ProfileStorage.saveRoster(roster, to: kv)

        #expect(store.string("CapacitorStorage.attrape-lettres:roster:v4") != nil)
        let reloaded = initialRoster(kv: kv, device: "d1", now: 3)
        #expect(reloaded == roster)
    }
}
