import Foundation
import Testing

@testable import ALCore
@testable import ALPlatform

/* -------------------------------------------------------------------------- */
/* The key/value adapter, and the migration contract it exists to honour.       */
/*                                                                             */
/* Every literal asserted here is derived from the TypeScript, not read back    */
/* out of the Swift: the key names come from `src/storage.ts`, `src/device.ts`, */
/* `src/sync/client.ts`, `src/licensing/persist.ts` and `src/telemetry.ts`, and */
/* the `CapacitorStorage.` prefix is what `@capacitor/preferences` writes into  */
/* `UserDefaults.standard` with no `group` configured (persistence.md §8).      */
/* Get one of these wrong and a family who updates in place from the shipped    */
/* Capacitor build loses their roster — to a six-year-old, indistinguishable    */
/* from the app deleting them.                                                 */
/* -------------------------------------------------------------------------- */

/// A `UserDefaults` nobody else can touch.
///
/// The suite name carries this PROCESS's pid: concurrent `swift test` runs share
/// the one real defaults database, and a previous phase of this port lost ~6% of
/// its reads to exactly that collision. Wiped on creation, so a crashed earlier
/// run cannot leak into this one either.
enum AdapterTestDefaults {
    static func make(_ label: String, function: String = #function) -> UserDefaults {
        let name =
            "fr.dappit.attrapelettres.ALPlatformTests"
            + ".\(ProcessInfo.processInfo.processIdentifier)"
            + ".\(label).\(function.filter { $0.isLetter || $0.isNumber })"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }
}

/// A v4 roster blob exactly as the shipped Capacitor build's `JSON.stringify`
/// writes it: all five normalised species slots, explicit `activeId`, `removed`,
/// counters keyed by the device id. Byte-for-byte the fixture ALCore's
/// `CapacitorInteropTests` uses, so the two ends assert against the same reality.
private let capacitorRosterJSON = """
{"children":[{"id":"3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11","name":"Léa","nameRev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"},"touchedAt":1719400001000,"profile":{"chosen":true,"current":"unicorn","currentRev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"},"species":{"unicorn":{"config":{"species":"unicorn","stage":2,"colors":{"hornColor":"#F0A"},"styles":{},"accessories":["unicorn.crown"]},"owned":["unicorn.horn.rainbow","unicorn.crown"],"rev":{"at":1719400000000,"by":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"}},"cat":{"config":{"species":"cat","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"fox":{"config":{"species":"fox","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"rabbit":{"config":{"species":"rabbit","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}},"dragon":{"config":{"species":"dragon","stage":0,"colors":{},"styles":{},"accessories":[]},"owned":[],"rev":{"at":0,"by":""}}},"stars":{"earned":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":12},"spent":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":4}},"clears":{"read-image:1":{"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d":2}}}}],"activeId":"3f2c9a10-77aa-4bfa-9c60-1de2f5a41c11","removed":{}}
"""

@Suite("UserDefaultsKVStore")
struct UserDefaultsKVStoreTests {

    @Test("round-trips a value through UserDefaults")
    func roundTrip() {
        let defaults = AdapterTestDefaults.make("roundtrip")
        let kv = UserDefaultsKVStore(defaults: defaults)

        #expect(kv.string("attrape-lettres:roster:v4") == nil)

        kv.set("{\"children\":[]}", for: "attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == "{\"children\":[]}")

        // Overwrite, not append.
        kv.set("{\"children\":[1]}", for: "attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == "{\"children\":[1]}")

        kv.remove("attrape-lettres:roster:v4")
        #expect(kv.string("attrape-lettres:roster:v4") == nil)
    }

    @Test("a second instance over the same defaults sees the same data")
    func sharedAcrossInstances() {
        let defaults = AdapterTestDefaults.make("shared")
        UserDefaultsKVStore(defaults: defaults).set("h-42", for: "attrape-lettres:household:v1")
        // A fresh store is what the NEXT launch builds. No in-memory cache may
        // stand between it and the disk.
        #expect(
            UserDefaultsKVStore(defaults: defaults).string("attrape-lettres:household:v1")
                == "h-42")
    }

    /* -- the migration contract --------------------------------------------*/

    @Test("every logical key resolves to its literal CapacitorStorage. key")
    func literalPrefixedKeys() {
        let defaults = AdapterTestDefaults.make("literals")
        let kv = UserDefaultsKVStore(defaults: defaults)

        // The prefix, spelled out. It is `@capacitor/preferences`' default group
        // name plus a dot; nothing derives it, so nothing may drift.
        #expect(UserDefaultsKVStore.keyPrefix == "CapacitorStorage.")

        // Every key the shipped build writes, spelled exactly as the TypeScript
        // spells it, mapped to exactly the UserDefaults key Capacitor used.
        let contract: [(logical: String, physical: String)] = [
            (ProfileStorage.rosterKey, "CapacitorStorage.attrape-lettres:roster:v4"),
            (ProfileStorage.v3Key, "CapacitorStorage.attrape-lettres:roster:v3"),
            (ProfileStorage.v2Key, "CapacitorStorage.attrape-lettres:profile:v2"),
            (ProfileStorage.v1Key, "CapacitorStorage.attrape-lettres:profile:v1"),
            (ProfileStorage.shopSeenKey, "CapacitorStorage.attrape-lettres:shop-seen:v1"),
            (DeviceIdentity.storageKey, "CapacitorStorage.attrape-lettres:device:v1"),
            (SyncClient.householdKey, "CapacitorStorage.attrape-lettres:household:v1"),
            (SyncClient.etagKey, "CapacitorStorage.attrape-lettres:household-etag:v1"),
            (LicenseStore.licenseKey, "CapacitorStorage.attrape-lettres:license:v1"),
            (LicenseStore.onboardedKey, "CapacitorStorage.attrape-lettres:onboarded:v1"),
            (TelemetryConsent.key, "CapacitorStorage.attrape-lettres:consent:v1"),
        ]

        for (logical, physical) in contract {
            #expect(
                kv.physicalKey(logical) == physical,
                Comment(rawValue: "\(logical) must resolve to \(physical)"))

            kv.set("v:\(logical)", for: logical)
            #expect(
                defaults.string(forKey: physical) == "v:\(logical)",
                Comment(rawValue: "nothing was written at \(physical)"))
            // And nothing was written at the bare key, which is where a port
            // that "tidied up" the prefix would have put it.
            #expect(
                defaults.string(forKey: logical) == nil,
                Comment(rawValue: "\(logical) was written unprefixed"))
        }
    }

    @Test("reads a roster the Capacitor build wrote, in place")
    func readsCapacitorWrittenRoster() throws {
        let defaults = AdapterTestDefaults.make("capacitor")
        // Exactly what the shipped TypeScript leaves behind: kv.ts hands the JSON
        // string to Preferences, Preferences writes it at the prefixed key.
        let rosterJSON = capacitorRosterJSON
        defaults.set(rosterJSON, forKey: "CapacitorStorage.attrape-lettres:roster:v4")
        defaults.set("device-from-capacitor", forKey: "CapacitorStorage.attrape-lettres:device:v1")

        let kv = UserDefaultsKVStore(defaults: defaults)

        let loaded = try #require(ProfileStorage.loadRoster(kv))
        #expect(loaded.children?.count == 1)
        #expect(loaded.children?.first?.name == "Léa")

        // The device id survives, so the counters this family already earned keep
        // accruing under the same key instead of forking a second slot.
        #expect(DeviceIdentity(kv: kv).deviceId() == "device-from-capacitor")
    }

    @Test("writes a roster back where a rolled-back Capacitor build would find it")
    func writesWhereCapacitorReads() {
        let defaults = AdapterTestDefaults.make("rollback")
        let kv = UserDefaultsKVStore(defaults: defaults)
        ProfileStorage.saveRoster(Roster(children: [], activeId: nil, removed: [:]), to: kv)

        let raw = defaults.string(forKey: "CapacitorStorage.attrape-lettres:roster:v4")
        #expect(raw != nil)
        // A JSON string, not a plist dictionary — Capacitor stores strings and
        // `JSON.parse` is what reads it on the other side.
        #expect(raw?.hasPrefix("{") == true)
    }

    @Test("an unprefixed base store is exactly the raw defaults")
    func backingStoreIsUnprefixed() {
        let defaults = AdapterTestDefaults.make("backing")
        let raw = UserDefaultsBackingStore(defaults)
        raw.set("plain", for: "some-key")
        #expect(defaults.string(forKey: "some-key") == "plain")
        #expect(defaults.string(forKey: "CapacitorStorage.some-key") == nil)
    }

    @Test("a nil or unopenable suite name falls back to standard defaults")
    func suiteFallback() {
        // Opening an empty suite silently would look exactly like losing the
        // roster, so the fallback is the database the data is actually in.
        let store = UserDefaultsKVStore(suiteName: nil)
        #expect(store.defaults == UserDefaults.standard)
    }
}
