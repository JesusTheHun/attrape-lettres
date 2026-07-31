import Foundation
import Testing

@testable import ALCore

@Suite struct DeviceIdentityTests {
    @Test func mintsALowercaseUuidAndPersistsIt() {
        let kv = InMemoryKVStore()
        let identity = DeviceIdentity(kv: kv)
        let id = identity.deviceId()
        #expect(id.count == 36)
        #expect(id == id.lowercased())
        #expect(kv.string(DeviceIdentity.storageKey) == id)
    }

    @Test func theKeyIsByteExact() {
        #expect(DeviceIdentity.storageKey == "attrape-lettres:device:v1")
    }

    @Test func memoisesAcrossCalls() {
        let kv = InMemoryKVStore()
        let identity = DeviceIdentity(kv: kv)
        let first = identity.deviceId()
        // Even if the backing store is wiped behind its back, the memo holds
        // for this launch (TS module-variable behaviour).
        kv.remove(DeviceIdentity.storageKey)
        #expect(identity.deviceId() == first)
    }

    @Test func honoursAPresetId() {
        let kv = InMemoryKVStore([DeviceIdentity.storageKey: "dad-phone"])
        #expect(DeviceIdentity(kv: kv).deviceId() == "dad-phone")
    }

    @Test func aFreshInstanceReadsWhatThePreviousOneMinted() {
        let kv = InMemoryKVStore()
        let first = DeviceIdentity(kv: kv).deviceId()
        #expect(DeviceIdentity(kv: kv).deviceId() == first)
    }

    // TS `if (saved) return saved` — an empty stored string is falsy and
    // re-mints rather than acting as a (broken) empty device id.
    @Test func anEmptyStoredStringReMints() {
        let kv = InMemoryKVStore([DeviceIdentity.storageKey: ""])
        let id = DeviceIdentity(kv: kv).deviceId()
        #expect(!id.isEmpty)
        #expect(kv.string(DeviceIdentity.storageKey) == id)
    }

    // Port of `__resetDeviceId` — the merge and store tests act as different
    // devices through it.
    @Test func resetWithAnIdActsAsThatDevice() {
        let kv = InMemoryKVStore()
        let identity = DeviceIdentity(kv: kv)
        _ = identity.deviceId()
        #expect(identity._reset("mum-phone") == "mum-phone")
        #expect(identity.deviceId() == "mum-phone")
        #expect(kv.string(DeviceIdentity.storageKey) == "mum-phone")
    }

    @Test func resetWithoutAnIdMintsAFreshOne() {
        let kv = InMemoryKVStore()
        let identity = DeviceIdentity(kv: kv)
        let first = identity.deviceId()
        let second = identity._reset()
        #expect(second != first)
        #expect(identity.deviceId() == second)
    }
}
