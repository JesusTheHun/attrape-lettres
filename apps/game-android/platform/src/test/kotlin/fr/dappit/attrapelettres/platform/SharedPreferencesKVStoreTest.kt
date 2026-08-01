package fr.dappit.attrapelettres.platform

import android.content.SharedPreferences
import fr.dappit.attrapelettres.core.licensing.LicenseStore
import fr.dappit.attrapelettres.core.persistence.DeviceIdentity
import fr.dappit.attrapelettres.core.persistence.ProfileStorage
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.telemetry.TelemetryConsent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// ---------------------------------------------------------------------------
// The key/value adapter and the A5 contract it exists to honour.
//
// These run on a bare JVM: SharedPreferences and its Editor are INTERFACES in
// android.jar, so a map-backed fake is pure Kotlin and no Android runtime is
// touched. What cannot run here is Context.getSharedPreferences itself — the
// factory's file wiring is exercised by asserting the frozen file-name
// constants against the backup rules XML instead, which is the part that can
// actually go wrong silently.
// ---------------------------------------------------------------------------

/**
 * A map-backed SharedPreferences. Batching editor, commit and apply counted,
 * so a test can assert not just WHAT was written but HOW it was flushed —
 * commit() versus apply() is a durability decision, not an implementation
 * detail (A3).
 */
private class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var commits = 0
    var applies = 0

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        if (values.containsKey(key)) values[key] as? String else defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        if (!values.containsKey(key)) return defValues
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String>
    }

    override fun getInt(key: String?, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        // The adapter never listens; nothing to record.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        // See above.
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearFirst = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            pending[key!!] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            removals.add(key!!)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return this
        }

        override fun commit(): Boolean {
            commits += 1
            flush()
            return true
        }

        override fun apply() {
            applies += 1
            flush()
        }

        private fun flush() {
            if (clearFirst) values.clear()
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) -> values[key] = value }
        }
    }
}

/**
 * The locked-profile failure mode: touching credential-protected storage
 * before first unlock throws IllegalStateException from every entry point.
 * A full disk surfaces similarly (through the editor). The adapter must turn
 * all of it into "this session does not persist", never a crash.
 */
private class LockedSharedPreferences : SharedPreferences {
    private fun locked(): Nothing =
        throw IllegalStateException(
            "SharedPreferences in credential encrypted storage are not available " +
                "until after user is unlocked")

    override fun getAll(): MutableMap<String, *> = locked()
    override fun getString(key: String?, defValue: String?): String? = locked()
    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = locked()
    override fun getInt(key: String?, defValue: Int): Int = locked()
    override fun getLong(key: String?, defValue: Long): Long = locked()
    override fun getFloat(key: String?, defValue: Float): Float = locked()
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = locked()
    override fun contains(key: String?): Boolean = locked()
    override fun edit(): SharedPreferences.Editor = locked()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = locked()
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = locked()
}

class SharedPreferencesKVStoreTest {

    @Test
    fun `round-trips a value, and the write is visible to the very next synchronous read`() {
        val prefs = FakeSharedPreferences()
        val kv = SharedPreferencesKVStore(prefs)

        assertNull(kv.string(ProfileStorage.ROSTER_KEY))

        // Invariant 1 / A3: set then string with nothing awaited in between.
        kv.set(ProfileStorage.ROSTER_KEY, "{\"children\":[]}")
        assertEquals("{\"children\":[]}", kv.string(ProfileStorage.ROSTER_KEY))

        // Overwrite, not append.
        kv.set(ProfileStorage.ROSTER_KEY, "{\"children\":[1]}")
        assertEquals("{\"children\":[1]}", kv.string(ProfileStorage.ROSTER_KEY))

        kv.remove(ProfileStorage.ROSTER_KEY)
        assertNull(kv.string(ProfileStorage.ROSTER_KEY))
    }

    @Test
    fun `a second instance over the same preferences sees the same data`() {
        val prefs = FakeSharedPreferences()
        SharedPreferencesKVStore(prefs).set("attrape-lettres:household:v1", "h-42")
        // A fresh store is what the NEXT launch builds. No in-memory cache may
        // stand between it and the file.
        assertEquals(
            "h-42",
            SharedPreferencesKVStore(prefs).string("attrape-lettres:household:v1"))
    }

    @Test
    fun `writes flush with commit, never apply`() {
        // commit() is durable before the pointer-down handler returns; apply()
        // races process death for the disk write. The award the UI just
        // celebrated must not be on the losing side of that race (A3).
        val prefs = FakeSharedPreferences()
        val kv = SharedPreferencesKVStore(prefs)
        kv.set("k", "v")
        kv.remove("k")
        assertEquals(2, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test
    fun `a locked profile degrades to a session that does not persist, not a crash`() {
        val kv = SharedPreferencesKVStore(LockedSharedPreferences())
        // A failed read is an absent value…
        assertNull(kv.string(ProfileStorage.ROSTER_KEY))
        // …and a failed write or remove is silently absorbed, exactly as
        // kv.ts absorbs private mode and quota. No throw is the assertion.
        kv.set(ProfileStorage.ROSTER_KEY, "{}")
        kv.remove(ProfileStorage.ROSTER_KEY)
    }
}

class DeviceIsolatingKVStoreTest {

    @Test
    fun `the device id key lands in the device store and nowhere else`() {
        val main = InMemoryKVStore()
        val device = InMemoryKVStore()
        val kv: KVStore = DeviceIsolatingKVStore(main, device)

        kv.set(DeviceIdentity.STORAGE_KEY, "d-1")
        assertEquals("d-1", device.string(DeviceIdentity.STORAGE_KEY))
        assertNull(main.string(DeviceIdentity.STORAGE_KEY))
        assertEquals("d-1", kv.string(DeviceIdentity.STORAGE_KEY))

        kv.remove(DeviceIdentity.STORAGE_KEY)
        assertNull(device.string(DeviceIdentity.STORAGE_KEY))
    }

    @Test
    fun `every other persisted key lands in the backed-up main store`() {
        // The roster, the legacy rosters a rollback must still read, the trial
        // stamp (A5: without backup it would be infinitely re-rollable), the
        // onboarding flag and consent. If one of these leaked into the device
        // file it would silently stop surviving a reinstall.
        val backedUp = listOf(
            ProfileStorage.ROSTER_KEY,
            ProfileStorage.V3_KEY,
            ProfileStorage.V2_KEY,
            ProfileStorage.V1_KEY,
            ProfileStorage.SHOP_SEEN_KEY,
            LicenseStore.LICENSE_KEY,
            LicenseStore.ONBOARDED_KEY,
            TelemetryConsent.KEY,
        )

        val main = InMemoryKVStore()
        val device = InMemoryKVStore()
        val kv = DeviceIsolatingKVStore(main, device)

        for (key in backedUp) {
            kv.set(key, "v:$key")
            assertEquals("v:$key", main.string(key), "$key must live in the main file")
            assertNull(device.string(key), "$key leaked into the excluded device file")
            assertEquals("v:$key", kv.string(key))
        }
    }

    @Test
    fun `DeviceIdentity mints through the router into the excluded file only`() {
        val main = InMemoryKVStore()
        val device = InMemoryKVStore()
        val kv = DeviceIsolatingKVStore(main, device)

        val id = DeviceIdentity(kv).deviceId()
        assertEquals(id, device.string(DeviceIdentity.STORAGE_KEY))
        assertTrue(main.snapshot.isEmpty(), "minting the device id must not touch the main file")
    }

    @Test
    fun `a restore that wiped the device file mints a fresh id`() {
        // The A5 story end to end: the main file comes back from backup with
        // the family's data; the device file does not. The restored phone must
        // come up as a NEW device — same roster, fresh counter key — so that
        // the old phone, still live in the house, keeps sole ownership of its
        // counter key and max()-merge stays lossless.
        val restoredMain = InMemoryKVStore(
            mapOf(ProfileStorage.ROSTER_KEY to "{\"children\":[]}"))
        val kv = DeviceIsolatingKVStore(restoredMain, InMemoryKVStore())

        assertEquals("{\"children\":[]}", kv.string(ProfileStorage.ROSTER_KEY))
        assertNotEquals("", DeviceIdentity(kv).deviceId())
        assertNull(restoredMain.string(DeviceIdentity.STORAGE_KEY))
    }
}

class BackupRulesContractTest {

    @Test
    fun `the device file name is frozen, byte for byte`() {
        // This literal is load-bearing: backup_rules.xml and
        // data_extraction_rules.xml exclude "<this>.xml" by NAME, and the
        // exclusion works at file granularity. Change either side alone and
        // the device id silently starts riding Auto Backup — two live phones
        // on one counter key, and a child loses stars (A5, invariant 9).
        assertEquals("fr.dappit.attrapelettres.device", SharedPreferencesKVStore.DEVICE_FILE_NAME)
    }

    @Test
    fun `the main file name is frozen, byte for byte`() {
        // Not a backup-rules concern (the include covers the whole domain) but
        // a persistence contract all the same: renaming the file orphans every
        // family's roster on an in-place update.
        assertEquals("fr.dappit.attrapelettres.kv", SharedPreferencesKVStore.MAIN_FILE_NAME)
    }

    @Test
    fun `both backup rules files exclude exactly the device file`() {
        val exclusion = SharedPreferencesKVStore.DEVICE_FILE_NAME + ".xml"

        val backupRules = appXml("backup_rules.xml").readText()
        assertEquals(
            1,
            excludeLines(backupRules, exclusion),
            "backup_rules.xml must exclude sharedpref path $exclusion")

        // The API 31+ twin excludes it twice: once for cloud backup, once for
        // device-to-device transfer ("the old phone is retired" is the usual
        // case, not a guarantee).
        val extractionRules = appXml("data_extraction_rules.xml").readText()
        assertEquals(
            2,
            excludeLines(extractionRules, exclusion),
            "data_extraction_rules.xml must exclude $exclusion from both sections")

        // And the main file is excluded nowhere — it is the whole point of
        // Auto Backup that it survives (the trial stamp lives there).
        val mainAsFile = SharedPreferencesKVStore.MAIN_FILE_NAME + ".xml"
        assertEquals(0, excludeLines(backupRules, mainAsFile))
        assertEquals(0, excludeLines(extractionRules, mainAsFile))
    }

    /** Lines that are an exclude rule naming [path]. */
    private fun excludeLines(xml: String, path: String): Int =
        xml.lines().count { it.contains("<exclude") && it.contains(path) }

    /**
     * Locates a file under the app module's res/xml from wherever Gradle put
     * this JVM's working directory. Walks upward so the test survives being
     * run from the module, the Android root or the repo root; failing to find
     * the file FAILS the test — a contract that silently skips is no contract.
     */
    private fun appXml(name: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val bases = generateSequence(start) { it.parentFile }.take(8)
        for (base in bases) {
            val candidates = listOf(
                File(base, "app/src/main/res/xml/$name"),
                File(base, "apps/game-android/app/src/main/res/xml/$name"),
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
        }
        fail("could not locate $name walking up from $start — the A5 exclusion cannot be verified")
    }
}
