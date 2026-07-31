package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** Port of the iOS `DeviceIdentityTests.swift`, itself a port of `device.test`. */
class DeviceIdentityTest {

    @Test
    fun `mints a lowercase uuid and persists it`() {
        val kv = InMemoryKVStore()
        val identity = DeviceIdentity(kv)
        val id = identity.deviceId()
        assertEquals(36, id.length)
        assertEquals(id.lowercase(), id)
        assertEquals(id, kv.string(DeviceIdentity.STORAGE_KEY))
    }

    @Test
    fun `the key is byte-exact`() {
        assertEquals("attrape-lettres:device:v1", DeviceIdentity.STORAGE_KEY)
    }

    @Test
    fun `memoises across calls`() {
        val kv = InMemoryKVStore()
        val identity = DeviceIdentity(kv)
        val first = identity.deviceId()
        // Even if the backing store is wiped behind its back, the memo holds
        // for this launch (TS module-variable behaviour).
        kv.remove(DeviceIdentity.STORAGE_KEY)
        assertEquals(first, identity.deviceId())
    }

    @Test
    fun `honours a preset id`() {
        val kv = InMemoryKVStore(mapOf(DeviceIdentity.STORAGE_KEY to "dad-phone"))
        assertEquals("dad-phone", DeviceIdentity(kv).deviceId())
    }

    @Test
    fun `a fresh instance reads what the previous one minted`() {
        val kv = InMemoryKVStore()
        val first = DeviceIdentity(kv).deviceId()
        assertEquals(first, DeviceIdentity(kv).deviceId())
    }

    @Test
    fun `an empty stored string re-mints`() {
        // TS `if (saved) return saved` — an empty stored string is falsy and
        // re-mints rather than acting as a (broken) empty device id.
        val kv = InMemoryKVStore(mapOf(DeviceIdentity.STORAGE_KEY to ""))
        val id = DeviceIdentity(kv).deviceId()
        assertFalse(id.isEmpty())
        assertEquals(id, kv.string(DeviceIdentity.STORAGE_KEY))
    }

    @Test
    fun `reset with an id acts as that device`() {
        // Port of `__resetDeviceId` — the merge and store tests act as
        // different devices through it.
        val kv = InMemoryKVStore()
        val identity = DeviceIdentity(kv)
        identity.deviceId()
        assertEquals("mum-phone", identity.reset("mum-phone"))
        assertEquals("mum-phone", identity.deviceId())
        assertEquals("mum-phone", kv.string(DeviceIdentity.STORAGE_KEY))
    }

    @Test
    fun `reset without an id mints a fresh one`() {
        val kv = InMemoryKVStore()
        val identity = DeviceIdentity(kv)
        val first = identity.deviceId()
        val second = identity.reset()
        assertNotEquals(first, second)
        assertEquals(second, identity.deviceId())
    }
}
