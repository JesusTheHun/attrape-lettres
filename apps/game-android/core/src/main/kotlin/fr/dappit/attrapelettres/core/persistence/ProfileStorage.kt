package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.platform.KVStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Persistence seam — the ONLY place in the app that reads or writes profiles.
 * Port of `src/storage.ts`.
 *
 * Every accessor is synchronous and total: a caller gets a value or a safe
 * default, never a throw. That contract is load-bearing — ProfileStore's
 * roster construction and the award/spend path inside a pointer-down handler
 * (invariant 1) have nowhere to await. The `KVStore` interface (A3) is what
 * keeps it true: synchronous by signature, `SharedPreferences` underneath on
 * device.
 *
 * Schema history (Migrations.kt owns the migration logic; storage just fetches
 * the raw blobs so defaults/domain shapes live in one place):
 *   v1  single mascot: { chosen, config, balance, ledger, owned }
 *   v2  per-species progress for ONE child (PersistedProfile)
 *   v3  a Roster of named children (siblings share the device)
 *   v4  sync-safe: balance/ledger become per-device counters, LWW fields get
 *       stamps, deletes get tombstones — so the same child's progress can be
 *       merged across the family's phones without losing stars (sync/Merge.kt)
 *       ← current
 *
 * A format change MUST be a forward, additive migration, never a rename/reshape
 * in place: bump the key to :vN, add a loadV(N-1) reader below, migrate old→new
 * in ProfileStore, and KEEP the old-version key + reader intact. Then even a
 * not-yet-updated (or rolled-back) launch reads a blob it still understands —
 * and the cross-platform household keeps the rule load-bearing: another device
 * in the same family may still be running v4-writing TypeScript.
 *
 * Difference from the TS, deliberately: `loadRoster` there returns the blob
 * cast to `Roster` and `normalizeRoster` runs later. Kotlin has no cast, so
 * `loadRoster` returns the loose shape and `Migrations.normalizeRoster` owns
 * the reshape. Same observable behaviour, one honest type.
 *
 * WHERE the bytes live is the :platform adapter's business, not this file's.
 * (The retired Capacitor Android build kept these same keys in the
 * "CapacitorStorage" SharedPreferences file — on Android the Capacitor "group"
 * was a prefs FILE name, not a key prefix, so the iOS `PrefixedKVStore` interop
 * has no :core twin here; continuity for an updating family is the adapter
 * opening that same file.)
 */
object ProfileStorage {
    // Key names byte-identical to storage.ts, forever: they are a persistence
    // contract shared with the web app, the iOS app and the retired Capacitor
    // build. Renaming one orphans a family's roster.
    const val ROSTER_KEY = "attrape-lettres:roster:v4"
    const val V3_KEY = "attrape-lettres:roster:v3"
    const val V2_KEY = "attrape-lettres:profile:v2"
    const val V1_KEY = "attrape-lettres:profile:v1"
    const val SHOP_SEEN_KEY = "attrape-lettres:shop-seen:v1"

    /**
     * The encoder. Deliberately keeps kotlinx's default `explicitNulls = true`:
     * JS `JSON.stringify` writes `"activeId": null` explicitly, `activeId` is
     * the only nullable field in the whole persisted tree, and matching it
     * keeps the three platforms' blobs byte-comparable. (A8 frames
     * `explicitNulls = false` / `ignoreUnknownKeys = true` as the DECODE
     * posture — decoding here never touches a generated deserializer at all;
     * LooseDecoding.kt walks the element tree instead, which tolerates unknown
     * keys and wrong-typed fields more finely than any flag could.)
     */
    private val json = Json

    /**
     * Parse-or-null, matching the TS try/catch around `JSON.parse`: an absent
     * key, unparseable JSON and a wrong top-level shape all read as null.
     */
    private fun parse(kv: KVStore, key: String): JsonElement? {
        val raw = kv.string(key) ?: return null
        return try {
            json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            null
        }
    }

    fun loadRoster(kv: KVStore): LooseRoster? = LooseRoster.of(parse(kv, ROSTER_KEY))

    fun saveRoster(roster: Roster, kv: KVStore) {
        // Nothing to guard: encoding these types cannot fail, and a store that
        // cannot write simply does not (the web original swallowed private-mode
        // and quota throws; on Android that concern belongs to the
        // SharedPreferences adapter).
        kv.set(ROSTER_KEY, json.encodeToString(Roster.serializer(), roster))
    }

    /**
     * Balance each child LAST SAW in the shop (childId → stars). Purely
     * cosmetic: the savings meters animate from this value on entry, so stars
     * earned since the previous visit read as visible growth. Losing it costs
     * nothing but the animation, hence its own key outside the roster blob (no
     * schema bump).
     */
    fun loadShopSeen(kv: KVStore): Map<String, Int> =
        parse(kv, SHOP_SEEN_KEY).looseDoubleMap()?.mapValues { it.value.looseCount } ?: emptyMap()

    fun saveShopSeen(seen: Map<String, Int>, kv: KVStore) {
        kv.set(SHOP_SEEN_KEY, json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), seen))
    }

    /**
     * The v3 roster, if this device still has one to migrate. Loose: its shape
     * is the OLD `Roster` (flat `balance`/`ledger`, no stamps), which the
     * current types no longer describe. Migrations.kt owns the reshape.
     */
    fun loadV3Roster(kv: KVStore): LegacyV3Roster? = LegacyV3Roster.of(parse(kv, V3_KEY))

    /**
     * The v2 single-child profile, if this device still has one to migrate.
     * Loose for the same reason as v3: v2 and the v3 *profile* share the old
     * flat `balance`/`ledger` shape.
     *
     * NB: TS returns any parseable JSON here and lets truthiness gate the
     * migration, so a corrupt non-object value (e.g. `"garbage-string"`) would
     * still mint a "Joueur 1" child from nothing. The walker rejects a
     * non-object and falls through to v1/empty instead. Reachable only from a
     * hand-corrupted blob; the honest-data behaviour is identical.
     */
    fun loadV2Profile(kv: KVStore): LegacyFlatProfile? = LegacyFlatProfile.of(parse(kv, V2_KEY))

    /** The raw v1 single-mascot blob, if present, for migration. */
    fun loadV1Profile(kv: KVStore): LegacyV1Profile? = LegacyV1Profile.of(parse(kv, V1_KEY))
}
