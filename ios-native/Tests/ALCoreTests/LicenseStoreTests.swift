import Foundation
import Testing

@testable import ALCore

// money.md §6.6.

private let T0: Int64 = 1_700_000_000_000

/// A store that accepts writes and drops them. iOS `UserDefaults` has no throwing
/// failure mode, so "a write failure" is modelled as a write that does not stick —
/// which is what a full disk or a locked container amounts to.
private final class WriteFailingKV: KVStore {
    private let inner = InMemoryKVStore()
    func string(_ key: String) -> String? { inner.string(key) }
    func set(_ value: String, for key: String) { /* swallowed */  }
    func remove(_ key: String) { inner.remove(key) }
}

@Suite("LicenseStore")
struct LicenseStoreTests {

    @Test("key names are a persistence contract with the shipped PWA")
    func keyNames() {
        #expect(LicenseStore.licenseKey == "attrape-lettres:license:v1")
        #expect(LicenseStore.onboardedKey == "attrape-lettres:onboarded:v1")
    }

    @Test("round-trips a populated state")
    func roundTrip() {
        let kv = InMemoryKVStore()
        let store = LicenseStore(kv)
        let s = LicenseState(
            paid: true, verifiedAt: T0, trialStartedAt: T0 - 3 * dayMs, clockHighWater: T0 + 1)
        store.save(s)
        #expect(store.load() == s)
    }

    @Test("an absent blob is a blank license, and a blank license PLAYS")
    func absentIsBlankAndPlays() {
        let store = LicenseStore(InMemoryKVStore())
        #expect(store.load() == .blank)
        #expect(canPlay(entitlementOf(.blank, T0)))
        #expect(canPlay(entitlementOf(.blank, 0)))
        #expect(canPlay(entitlementOf(.blank, T0 * 4)))
    }

    @Test("encodes an explicit null rather than dropping the key")
    func encodesExplicitNull() throws {
        let kv = InMemoryKVStore()
        LicenseStore(kv).save(.blank)
        let raw = try #require(kv.string(LicenseStore.licenseKey))
        // `JSON.stringify` emits `"verifiedAt":null`; synthesised Codable would
        // omit the key entirely and the blob would stop being interchangeable
        // with the PWA's.
        let object = try #require(
            try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
        #expect(object["verifiedAt"] is NSNull)
        #expect(object["trialStartedAt"] is NSNull)
        #expect(object.keys.sorted() == ["clockHighWater", "paid", "trialStartedAt", "verifiedAt"])
    }

    @Test("corrupt bytes degrade to blank rather than throwing")
    func corruptBytes() {
        let kv = InMemoryKVStore([LicenseStore.licenseKey: "{not json at all"])
        #expect(LicenseStore(kv).load() == .blank)
        kv.set("", for: LicenseStore.licenseKey)
        #expect(LicenseStore(kv).load() == .blank)
    }

    @Test("a wrong-typed field degrades to its default, the others survive")
    func wrongTypedFieldDegrades() {
        // `paid` is a string, `verifiedAt` is a string: both fall back, and the
        // fallbacks mean *full trial*, i.e. the child plays. Fail open all the
        // way down.
        let kv = InMemoryKVStore([
            LicenseStore.licenseKey:
                #"{"paid":"yes","verifiedAt":"soon","trialStartedAt":1700000000000,"clockHighWater":42}"#
        ])
        let loaded = LicenseStore(kv).load()
        #expect(loaded.paid == false)
        #expect(loaded.verifiedAt == nil)
        #expect(loaded.trialStartedAt == 1_700_000_000_000)
        #expect(loaded.clockHighWater == 42)
    }

    @Test("missing fields take their defaults")
    func missingFields() {
        let kv = InMemoryKVStore([LicenseStore.licenseKey: #"{"paid":true}"#])
        let loaded = LicenseStore(kv).load()
        #expect(loaded == LicenseState(paid: true))
    }

    @Test("a write that does not stick is swallowed and mutates nothing")
    func writeFailureIsSwallowed() {
        let store = LicenseStore(WriteFailingKV())
        let before = store.load()
        store.save(LicenseState(paid: true, verifiedAt: T0))
        #expect(store.load() == before)
        #expect(store.load() == .blank)
        // And the family still plays.
        #expect(canPlay(entitlementOf(store.load(), T0)))
    }

    @Test("onboarded is a separate, one-way flag")
    func onboarded() {
        let kv = InMemoryKVStore()
        let store = LicenseStore(kv)
        #expect(!store.loadOnboarded())
        store.saveOnboarded()
        #expect(store.loadOnboarded())
        #expect(kv.string(LicenseStore.onboardedKey) == "1")
        // Anything other than "1" is not onboarded.
        kv.set("true", for: LicenseStore.onboardedKey)
        #expect(!store.loadOnboarded())
    }
}
