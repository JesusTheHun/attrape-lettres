package fr.dappit.attrapelettres.core.licensing

import fr.dappit.attrapelettres.core.platform.KVStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Licence persistence. Port of `apps/game-web/src/licensing/persist.ts`.
 *
 * Separate from ProfileStorage on purpose: that file owns children's profiles
 * and a four-version migration contract; this owns one small blob about the
 * household's purchase. Losing a profile is a tragedy; losing this is one
 * "Restaurer" tap.
 *
 * Invariant 9: the licence accumulates nothing, so it is NOT a `Counter` and it
 * must **never** enter sync/Merge.kt. If household-level entitlement is ever
 * wanted, it belongs on the sync backend keyed by `familyId`, not inside the
 * merged profile blob.
 *
 * **A5, and this is the Android-only paragraph.** On iOS the trial date is a
 * signed StoreKit `purchaseDate` and this blob is a cache of it — throw the
 * blob away and the next `refresh()` reconstructs the truth. On Android there
 * is no signed receipt to reconstruct from, because Google Play has no price-0
 * in-app product: `trialStartedAt` written here IS the trial clock, and if it
 * did not survive a reinstall the fortnight would be infinitely re-rollable.
 * What carries it is Android Auto Backup — hence `allowBackup="true"` and
 * `shared_prefs` included in the backup rules.
 *
 * Note the deliberate asymmetry inside those same rules: the device id is
 * EXCLUDED from backup (both `backup_rules.xml` and
 * `data_extraction_rules.xml`, device-transfer section included), because
 * restoring it onto a second live phone would put two devices on one grow-only
 * counter key and `max()` would silently eat a child's stars (invariant 9).
 * The licence wants to travel; the device id must not.
 */
class LicenseStore(private val kv: KVStore) {

    companion object {
        /**
         * Key names are a persistence contract with the shipped PWA and the iOS
         * app: the `attrape-lettres:*` namespace is preserved byte for byte so a
         * family updating in place keeps their purchase. Renaming one hands a
         * paying household a paywall.
         */
        const val LICENSE_KEY = "attrape-lettres:license:v1"

        /** Has a parent seen the trial terms? Gates Onboarding, per App Review 3.1.1. */
        const val ONBOARDED_KEY = "attrape-lettres:onboarded:v1"

        private val json = Json
    }

    /**
     * Total and lenient, mirroring `loadLicense`'s `parsed.x ?? default`.
     *
     * Every failure mode — absent key, empty string, truncated JSON, a
     * wrong-typed field — degrades to a default rather than throwing, and the
     * defaults add up to [BLANK_LICENSE], which means *full trial*: the child
     * plays. Fail open all the way down.
     *
     * Field-by-field rather than through a generated deserializer for the
     * reason LooseDecoding.kt gives: an all-or-nothing decoder would throw away
     * `trialStartedAt` because `paid` came back as a string, and the fields have
     * no business failing together.
     */
    fun load(): LicenseState {
        val raw = kv.string(LICENSE_KEY)
        if (raw.isNullOrEmpty()) return BLANK_LICENSE
        val element = try {
            json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            return BLANK_LICENSE
        }
        val obj = element as? JsonObject ?: return BLANK_LICENSE
        return LicenseState(
            paid = obj["paid"].looseBoolean() ?: false,
            verifiedAt = obj["verifiedAt"].looseLong(),
            trialStartedAt = obj["trialStartedAt"].looseLong(),
            clockHighWater = obj["clockHighWater"].looseLong() ?: 0L,
        )
    }

    /**
     * All four keys, always, with `null` written out explicitly — that is what
     * `JSON.stringify` does, and the blob has to stay readable by the PWA and
     * the iOS app byte for byte.
     *
     * Nothing to guard against: building and encoding a `JsonObject` cannot
     * fail, and a store that cannot write simply does not (the web original
     * swallowed private-mode and quota throws; on Android that concern belongs
     * to the SharedPreferences adapter). A lost write costs one refresh, since
     * the store is re-queried every launch.
     */
    fun save(state: LicenseState) {
        val obj = buildJsonObject {
            put("paid", state.paid)
            put("verifiedAt", state.verifiedAt)
            put("trialStartedAt", state.trialStartedAt)
            put("clockHighWater", state.clockHighWater)
        }
        kv.set(LICENSE_KEY, json.encodeToString(JsonObject.serializer(), obj))
    }

    /** Exactly `"1"` counts. Anything else is "not onboarded", including `"true"`. */
    fun loadOnboarded(): Boolean = kv.string(ONBOARDED_KEY) == "1"

    /** One-way: there is no un-onboarding, because the terms cannot be un-shown. */
    fun saveOnboarded() {
        kv.set(ONBOARDED_KEY, "1")
    }
}

/* Loose field readers ---------------------------------------------------------*/
/* A wrong-typed value reads as null and takes its default, never a throw. Same  */
/* discipline as LooseDecoding.kt, kept local because licensing shares no shape  */
/* with the roster and should not import its private walkers.                    */

private fun JsonElement?.looseBoolean(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

/**
 * Read as `Double` and truncate, because that is what a JS number IS. Every
 * blob this app ever wrote holds integers (`Date.now()`), but a strict integer
 * read of a hypothetical `1.7e12` would throw a real trial date away.
 */
private fun JsonElement?.looseLong(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()
