package fr.dappit.attrapelettres.core.persistence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The JSON tolerance layer — all-optional mirrors of every shape this app has
// ever written to disk: the current v4 roster plus the legacy v1/v2/v3 blobs
// (`LegacyV1Profile`, `LegacyFlatProfile`, `LegacyV3Roster` and the `Loose*`
// types from `src/hooks/useProfile.tsx`).
//
// Why it exists: in TS, `loadRoster` is `JSON.parse` + a cast, and
// `normalizeProfile`/`normalizeRoster` default every missing field. Kotlin has
// no cast, so the tolerance moves into decoding — and it cannot be kotlinx's
// GENERATED decoders, because those are all-or-nothing: one wrong-typed field
// (`"id": 42`) would fail the whole blob, the roster would read as "no roster",
// and a child would lose everything. So these mirrors decode by hand off
// kotlinx's `JsonElement` tree, field by field: a wrong-typed field degrades to
// "absent" (and takes its normalisation default), the same try?-per-field
// discipline as the Swift port. A partially-corrupt blob degrades; it does not
// erase a child. Unknown keys are ignored by construction — a walker only reads
// the keys it knows — which is A8's `ignoreUnknownKeys` posture delivered
// structurally.
//
// Numbers read as `Double?` because that is what a JS number IS — every blob
// this app ever wrote holds integers (`Date.now()`, integer stars), but a
// strict integer read of a hypothetical `17.0` would throw the whole roster
// away. Normalisation truncates to `Int`/`Millis`, the identity on all real
// data.
//
// These types are DECODE-ONLY — persisting a loose shape is not a thing (there
// is no encoder to hand one to). The constructors exist for the migrators and
// the tests.

/* Field readers --------------------------------------------------------------*/
/* A wrong-typed value reads as null, never a throw. Containers are atomic, the */
/* way Swift's `try? decode([String].self)` is: ONE wrong-typed element fails    */
/* the WHOLE container (→ absent → its default), it does not drop just that     */
/* element. Sub-objects that fail to be objects read as absent the same way.    */

private fun JsonElement?.looseString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.looseDouble(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

private fun JsonElement?.looseBoolean(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonElement?.looseStringList(): List<String>? {
    val arr = this as? JsonArray ?: return null
    return arr.map { it.looseString() ?: return null }
}

private fun JsonElement?.looseStringMap(): Map<String, String>? {
    val obj = this as? JsonObject ?: return null
    val out = LinkedHashMap<String, String>(obj.size)
    for ((k, v) in obj) out[k] = v.looseString() ?: return null
    return out
}

internal fun JsonElement?.looseDoubleMap(): Map<String, Double>? {
    val obj = this as? JsonObject ?: return null
    val out = LinkedHashMap<String, Double>(obj.size)
    for ((k, v) in obj) out[k] = v.looseDouble() ?: return null
    return out
}

private fun JsonElement?.looseClearsMap(): Map<String, Map<String, Double>>? {
    val obj = this as? JsonObject ?: return null
    val out = LinkedHashMap<String, Map<String, Double>>(obj.size)
    for ((k, v) in obj) out[k] = v.looseDoubleMap() ?: return null
    return out
}

private fun JsonElement?.looseSpeciesMap(): Map<String, LooseSpeciesProgress>? {
    val obj = this as? JsonObject ?: return null
    val out = LinkedHashMap<String, LooseSpeciesProgress>(obj.size)
    for ((k, v) in obj) out[k] = LooseSpeciesProgress.of(v) ?: return null
    return out
}

/* Loose v4 shapes ------------------------------------------------------------*/

/**
 * Loose `Rev`. TS keeps a partial rev object as-is; normalisation defaults the
 * missing halves to `0` / `""` — identical for every blob the app ever wrote
 * (revs are always written whole).
 */
data class LooseRev(
    val at: Double? = null,
    val by: String? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseRev? {
            val obj = element as? JsonObject ?: return null
            return LooseRev(at = obj["at"].looseDouble(), by = obj["by"].looseString())
        }
    }
}

/**
 * Loose `MascotConfig`. `species` stays a raw String here — unknown species
 * are dropped (map keys) or defaulted (`current`) during normalisation.
 */
data class LooseMascotConfig(
    val species: String? = null,
    val stage: Double? = null,
    val colors: Map<String, String>? = null,
    val styles: Map<String, String>? = null,
    val accessories: List<String>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseMascotConfig? {
            val obj = element as? JsonObject ?: return null
            return LooseMascotConfig(
                species = obj["species"].looseString(),
                stage = obj["stage"].looseDouble(),
                colors = obj["colors"].looseStringMap(),
                styles = obj["styles"].looseStringMap(),
                accessories = obj["accessories"].looseStringList(),
            )
        }
    }
}

/**
 * TS `LooseProgress` — anything shaped enough to be read as progress: a v4
 * species slot (with `rev`) or a legacy v2/v3 one (without).
 */
data class LooseSpeciesProgress(
    val config: LooseMascotConfig? = null,
    val owned: List<String>? = null,
    val rev: LooseRev? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseSpeciesProgress? {
            val obj = element as? JsonObject ?: return null
            return LooseSpeciesProgress(
                config = LooseMascotConfig.of(obj["config"]),
                owned = obj["owned"].looseStringList(),
                rev = LooseRev.of(obj["rev"]),
            )
        }
    }
}

data class LooseStarCounters(
    val earned: Map<String, Double>? = null,
    val spent: Map<String, Double>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseStarCounters? {
            val obj = element as? JsonObject ?: return null
            return LooseStarCounters(
                earned = obj["earned"].looseDoubleMap(),
                spent = obj["spent"].looseDoubleMap(),
            )
        }
    }
}

/** TS `LooseProfile` — a v4 profile blob, or anything shaped enough to pass. */
data class LooseProfile(
    val chosen: Boolean? = null,
    val current: String? = null,
    val currentRev: LooseRev? = null,
    val species: Map<String, LooseSpeciesProgress>? = null,
    val stars: LooseStarCounters? = null,
    val clears: Map<String, Map<String, Double>>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseProfile? {
            val obj = element as? JsonObject ?: return null
            return LooseProfile(
                chosen = obj["chosen"].looseBoolean(),
                current = obj["current"].looseString(),
                currentRev = LooseRev.of(obj["currentRev"]),
                species = obj["species"].looseSpeciesMap(),
                stars = LooseStarCounters.of(obj["stars"]),
                clears = obj["clears"].looseClearsMap(),
            )
        }
    }
}

data class LooseChild(
    val id: String? = null,
    val name: String? = null,
    val nameRev: LooseRev? = null,
    val touchedAt: Double? = null,
    val profile: LooseProfile? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseChild? {
            val obj = element as? JsonObject ?: return null
            return LooseChild(
                id = obj["id"].looseString(),
                name = obj["name"].looseString(),
                nameRev = LooseRev.of(obj["nameRev"]),
                touchedAt = obj["touchedAt"].looseDouble(),
                profile = LooseProfile.of(obj["profile"]),
            )
        }
    }
}

/**
 * The loose v4 roster — what `ProfileStorage.loadRoster` actually returns.
 * `Migrations.normalizeRoster` owns the reshape into a strict `Roster`.
 */
data class LooseRoster(
    val children: List<LooseChild>? = null,
    val activeId: String? = null,
    val removed: Map<String, Double>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LooseRoster? {
            val obj = element as? JsonObject ?: return null
            return LooseRoster(
                children = (obj["children"] as? JsonArray)?.let { arr ->
                    arr.map { LooseChild.of(it) ?: return@let null }
                },
                activeId = obj["activeId"].looseString(),
                removed = obj["removed"].looseDoubleMap(),
            )
        }
    }
}

/* Legacy shapes ------------------------------------------------------------- */

/** Old v1 shape (single mascot), kept only so we can migrate it forward. */
data class LegacyV1Profile(
    val chosen: Boolean? = null,
    val config: LooseMascotConfig? = null,
    val balance: Double? = null,
    val ledger: Map<String, Double>? = null,
    val owned: List<String>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LegacyV1Profile? {
            val obj = element as? JsonObject ?: return null
            return LegacyV1Profile(
                chosen = obj["chosen"].looseBoolean(),
                config = LooseMascotConfig.of(obj["config"]),
                balance = obj["balance"].looseDouble(),
                ledger = obj["ledger"].looseDoubleMap(),
                owned = obj["owned"].looseStringList(),
            )
        }
    }
}

/**
 * The v2/v3 profile shape — identical to each other, and to v4 except that
 * stars and clears were bare running totals. One migrator serves both.
 */
data class LegacyFlatProfile(
    val chosen: Boolean? = null,
    val current: String? = null,
    val species: Map<String, LooseSpeciesProgress>? = null,
    val balance: Double? = null,
    val ledger: Map<String, Double>? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LegacyFlatProfile? {
            val obj = element as? JsonObject ?: return null
            return LegacyFlatProfile(
                chosen = obj["chosen"].looseBoolean(),
                current = obj["current"].looseString(),
                species = obj["species"].looseSpeciesMap(),
                balance = obj["balance"].looseDouble(),
                ledger = obj["ledger"].looseDoubleMap(),
            )
        }
    }
}

data class LegacyV3Child(
    val id: String? = null,
    val name: String? = null,
    val profile: LegacyFlatProfile? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LegacyV3Child? {
            val obj = element as? JsonObject ?: return null
            return LegacyV3Child(
                id = obj["id"].looseString(),
                name = obj["name"].looseString(),
                profile = LegacyFlatProfile.of(obj["profile"]),
            )
        }
    }
}

data class LegacyV3Roster(
    val children: List<LegacyV3Child>? = null,
    val activeId: String? = null,
) {
    internal companion object {
        fun of(element: JsonElement?): LegacyV3Roster? {
            val obj = element as? JsonObject ?: return null
            return LegacyV3Roster(
                children = (obj["children"] as? JsonArray)?.let { arr ->
                    arr.map { LegacyV3Child.of(it) ?: return@let null }
                },
                activeId = obj["activeId"].looseString(),
            )
        }
    }
}

/* JS-number → integer conversion --------------------------------------------- */

/**
 * JS number → count. Kotlin's `toInt()` is already total in exactly the way
 * Swift had to hand-build: truncates toward zero (the identity on every value
 * the app ever wrote), NaN → 0, out-of-range clamps instead of trapping. The
 * named extension survives so call sites say what the conversion means.
 */
internal val Double.looseCount: Int get() = toInt()

/** JS number → epoch milliseconds, same totality rules as `looseCount`. */
internal val Double.looseMillis: Millis get() = toLong()

/** `[deviceId: Double]` → `Counter`, truncating each value. */
internal fun looseCounter(raw: Map<String, Double>?): Counter =
    (raw ?: emptyMap()).mapValues { it.value.looseCount }

/** Loose clears → `ClearCounters`, truncating each per-device value. */
internal fun looseClears(raw: Map<String, Map<String, Double>>?): ClearCounters =
    (raw ?: emptyMap()).mapValues { entry -> entry.value.mapValues { it.value.looseCount } }

/** Loose tombstones → `childId → Millis`. */
internal fun looseTombstones(raw: Map<String, Double>?): Map<String, Millis> =
    (raw ?: emptyMap()).mapValues { it.value.looseMillis }
