package fr.dappit.attrapelettres.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import fr.dappit.attrapelettres.core.persistence.DeviceIdentity
import fr.dappit.attrapelettres.core.platform.KVStore

// ---------------------------------------------------------------------------
// The key/value primitive over SharedPreferences — the Android side of the
// web app's kv.ts (the iOS twin is UserDefaultsKVStore.swift).
//
// SYNCHRONOUS, because KVStore is synchronous by signature (A3, invariant 1):
// award and spend run inside a pointer-down handler and there is nowhere to
// await on that path. SharedPreferences reads from its in-memory map and its
// editor has a synchronous commit(), so the adapter is a direct fit — no
// DataStore, no suspend, no coroutine-hydrated cache. The whole reason the
// interface has no suspend is that an async adapter must not even compile.
//
// Unlike iOS there is NO legacy key prefix. The Capacitor shell shipped on
// iOS only, so no Android build has ever written a "CapacitorStorage." key;
// logical keys ("attrape-lettres:roster:v4", …) are the physical keys.
// ---------------------------------------------------------------------------

/**
 * One [KVStore] over one SharedPreferences file.
 *
 * Every access sits inside try/catch, the way kv.ts wraps localStorage. The
 * web original guarded private mode and quota; here the equivalents are a full
 * disk and, on a locked work/credential-encrypted profile, the
 * IllegalStateException Android throws for touching credential-protected
 * storage before first unlock. None of the callers has anywhere useful to
 * report a failure — a failed read is an absent value, a failed write is a
 * value this session still holds in memory. The game must degrade to "this
 * session will not persist", never crash. Total, never a throw.
 */
class SharedPreferencesKVStore(private val prefs: SharedPreferences) : KVStore {

    override fun string(key: String): String? =
        try {
            prefs.getString(key, null)
        } catch (_: Exception) {
            // Locked profile, corrupted file, or a non-String stored at this
            // key by some earlier bug (ClassCastException). All read as absent.
            null
        }

    // commit(), not apply(), on purpose (A3). apply() is synchronous only
    // against the in-memory map and races process death for the disk write;
    // commit() means the award the UI just celebrated is durable before the
    // handler returns. The writes here are a few hundred bytes of JSON at
    // human tap frequency — the "commit blocks the caller" advice targets a
    // different regime. Lint's ApplySharedPref suggestion is suppressed
    // because this is the one place the slower flush is the point.
    @SuppressLint("ApplySharedPref")
    override fun set(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).commit()
        } catch (_: Exception) {
            // Full disk / locked profile — this session simply does not
            // persist. Callers all tolerate that (see kv.ts and storage.ts).
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun remove(key: String) {
        try {
            prefs.edit().remove(key).commit()
        } catch (_: Exception) {
            // Ignore, as kv.ts does.
        }
    }

    companion object {
        /**
         * The main SharedPreferences file: roster, license, trial stamp,
         * household id, consent — everything Auto Backup must carry (A5: Play
         * has no price-0 IAP, so the 14-day trial stamp is a local value and a
         * reinstall must not re-roll it). backup_rules.xml includes the whole
         * sharedpref domain, so this file rides along under any name — but the
         * name is still frozen by a test, because renaming it in a later build
         * would orphan every family's data on an in-place update, which to a
         * six-year-old is indistinguishable from the app deleting them.
         */
        const val MAIN_FILE_NAME = "fr.dappit.attrapelettres.kv"

        /**
         * The device-id file. MUST be exactly this string: backup_rules.xml
         * and data_extraction_rules.xml exclude the sharedpref path
         * "fr.dappit.attrapelettres.device.xml", and that exclusion works at
         * FILE granularity — SharedPreferences names map to "name.xml" on
         * disk. Rename this and the exclusion silently stops matching: a
         * restored backup would then carry the old phone's device id, two live
         * phones would share one counter key, and max()-merged counters would
         * read two devices' independent progress as one stale one — a child
         * loses stars (A5, invariant 9). A test asserts the constant AND that
         * the two rules files exclude it.
         */
        const val DEVICE_FILE_NAME = "fr.dappit.attrapelettres.device"

        /**
         * The production store: the main file for everything, the excluded
         * file for the device id, composed behind one [KVStore] so no call
         * site can pick the wrong file.
         */
        fun from(context: Context): KVStore {
            val app = context.applicationContext
            return DeviceIsolatingKVStore(
                main = SharedPreferencesKVStore(
                    app.getSharedPreferences(MAIN_FILE_NAME, Context.MODE_PRIVATE)),
                device = SharedPreferencesKVStore(
                    app.getSharedPreferences(DEVICE_FILE_NAME, Context.MODE_PRIVATE)),
            )
        }
    }
}

/**
 * Routes [DeviceIdentity.STORAGE_KEY] to the excluded device file and every
 * other key to the backed-up main file.
 *
 * Pure Kotlin over two [KVStore]s, so the routing — which is the actual
 * carrier of A5 — is host-testable with [fr.dappit.attrapelettres.core.platform.InMemoryKVStore]
 * doubles and no Android runtime. The exclusion in backup_rules.xml only
 * protects what physically lives in the excluded file; this class is what
 * guarantees the device id does and nothing else ever will.
 */
class DeviceIsolatingKVStore(
    private val main: KVStore,
    private val device: KVStore,
) : KVStore {

    private fun storeFor(key: String): KVStore =
        if (key == DeviceIdentity.STORAGE_KEY) device else main

    override fun string(key: String): String? = storeFor(key).string(key)

    override fun set(key: String, value: String) = storeFor(key).set(key, value)

    override fun remove(key: String) = storeFor(key).remove(key)
}
