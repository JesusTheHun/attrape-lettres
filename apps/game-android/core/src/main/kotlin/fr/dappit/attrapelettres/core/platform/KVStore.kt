package fr.dappit.attrapelettres.core.platform

/* -------------------------------------------------------------------------- */
/* The key/value primitive — the Kotlin shape of `src/kv.ts`.                   */
/* -------------------------------------------------------------------------- */

/**
 * ONE interface, one SharedPreferences file, one namespace, for all call sites
 * (licensing, telemetry consent, device identity, profile storage, sync).
 *
 * SYNCHRONOUS BY SIGNATURE — A3. This is invariant 1 expressed in the type
 * system: an adapter that wants to suspend simply cannot implement it. It is
 * the same reason `kv.ts` keeps every read synchronous — award/spend run
 * inside a pointer-down handler, before the UI commits, and there is nowhere
 * to await on that path. `SharedPreferences` reads synchronously and
 * `commit()`s on write, so the Android adapter is a direct fit.
 *
 * The web original swallowed every throw (private mode, quota). Neither
 * SharedPreferences nor the in-memory double has an equivalent failure, so the
 * interface has no error channel; an adapter that cannot write simply does not.
 */
interface KVStore {
    fun string(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/**
 * The test and preview backing. Not thread-safe on purpose — everything that
 * touches storage in this app is main-thread bound.
 */
class InMemoryKVStore(initial: Map<String, String> = emptyMap()) : KVStore {
    private val storage: MutableMap<String, String> = initial.toMutableMap()

    override fun string(key: String): String? = storage[key]

    override fun set(key: String, value: String) {
        storage[key] = value
    }

    override fun remove(key: String) {
        storage.remove(key)
    }

    /** Everything currently stored. Tests assert over this; production never calls it. */
    val snapshot: Map<String, String>
        get() = storage.toMap()

    /** Wipes the store. Tests only. */
    fun removeAll() {
        storage.clear()
    }
}
