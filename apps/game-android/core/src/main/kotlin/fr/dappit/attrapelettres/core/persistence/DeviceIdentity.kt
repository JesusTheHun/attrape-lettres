package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.platform.KVStore
import java.util.UUID

/* -------------------------------------------------------------------------- */
/* This device's identity — the key every counter in a profile is indexed by.   */
/* Port of `src/device.ts`.                                                     */
/* -------------------------------------------------------------------------- */

/**
 * It is NOT an identifier for a person. It is generated locally, never leaves
 * the household record, is not tied to the child, the parent, the OS or the
 * hardware, and a reinstall mints a fresh one. That is deliberate: it exists
 * so two replicas of the same child's progress can be merged without losing
 * stars (see sync/Merge.kt), and for nothing else. Never send it to an
 * analytics endpoint — that is what makes the telemetry payload genuinely
 * anonymous rather than merely pseudonymous. (It DOES appear inside Counter
 * keys uploaded to the household record — that is by design.)
 *
 * This is also why the key is EXCLUDED from Auto Backup (A5): a restored
 * backup that carried the id would put two live phones on one counter key, and
 * `max()`-merged counters would then read two devices' independent progress as
 * one stale one. A restored device mints a fresh id instead, and nothing is
 * lost by that — its historical counts travel inside the roster blob under the
 * OLD id and still sum correctly; only new earnings land on the new key.
 *
 * Lazy + memoised, as in TS — one storage read at whatever moment the roster
 * is first built. Reads are synchronous: the award/spend path runs inside a
 * pointer-down handler and cannot await (invariant 1, A3).
 */
class DeviceIdentity(private val kv: KVStore) {
    private var cached: String? = null

    /**
     * TS `mint()` — `crypto.randomUUID()`. Java's `UUID.toString()` is
     * lowercase by spec, matching what JS emits, so no case fix is needed
     * (Foundation uppercases, which is why the Swift port lowercases). The
     * `d_<ts36>_<rand36>` fallback path (crypto unavailable) has no JVM
     * equivalent failure mode; `randomUUID()` cannot fail.
     */
    private fun mint(): String = UUID.randomUUID().toString()

    private fun read(): String {
        // TS `if (saved) return saved` — an empty string is falsy and re-mints.
        val saved = kv.string(STORAGE_KEY)
        if (!saved.isNullOrEmpty()) return saved
        val fresh = mint()
        // If the write fails this is a per-launch id. That still merges
        // correctly — it just adds a counter key per launch. Stars stay exact,
        // which is the only thing that must not degrade. (SharedPreferences
        // writes do not fail; the comment travels anyway.)
        kv.set(STORAGE_KEY, fresh)
        return fresh
    }

    /** This device's stable id. */
    fun deviceId(): String {
        cached?.let { return it }
        val id = read()
        cached = id
        return id
    }

    /**
     * Tests only — forget the cached id so a spec can act as a different
     * device. Port of `__resetDeviceId`.
     */
    fun reset(id: String? = null): String {
        if (id != null) {
            kv.set(STORAGE_KEY, id)
        } else {
            kv.remove(STORAGE_KEY)
        }
        cached = null
        return deviceId()
    }

    companion object {
        const val STORAGE_KEY = "attrape-lettres:device:v1"
    }
}
