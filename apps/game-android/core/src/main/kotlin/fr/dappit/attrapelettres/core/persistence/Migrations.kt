package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.platform.KVStore
import java.util.UUID

// The blanks, the loose-blob normalisation and the v1/v2/v3 → v4 migrations —
// port of the top half of `src/hooks/useProfile.tsx`.
//
// All pure functions, all host-testable. Inputs are loose shapes plus
// `device: String` and `now: Millis` PASSED IN — no clock reads and no
// device-id reads inside, the same discipline as sync/Merge.kt. The only
// non-determinism is `newChildId()`, which mints ids for brand-new children
// and for damaged blobs whose child has no id.
//
// The migration never deletes or rewrites the old key: the v3/v2/v1 blobs stay
// on disk untouched, and the migrated result is only persisted when the first
// mutation commits — `initialRoster` computes, it does not save. (TS: nothing
// writes until `commit`; the test "leaves the v3 blob in place" pins it.)

/* Blanks --------------------------------------------------------------------- */

fun blankConfig(species: Species): MascotConfig =
    MascotConfig(species = species, stage = 0, colors = emptyMap(), styles = emptyMap(), accessories = emptyList())

fun blankProgress(species: Species): SpeciesProgress =
    SpeciesProgress(config = blankConfig(species), owned = emptyList(), rev = Rev.ZERO)

/**
 * All five slots, blank, in declaration order — the totality that
 * `PersistedProfile.species` documents. `associateWith` builds a
 * LinkedHashMap, so the JSON writes the slots in the same order the web app
 * does.
 */
fun blankSpeciesMap(): Map<Species, SpeciesProgress> =
    Species.entries.associateWith { blankProgress(it) }

/** TS `DEFAULT_PROFILE` — what a child who has never played looks like. */
val DEFAULT_PROFILE: PersistedProfile = PersistedProfile(
    chosen = false,
    current = Species.UNICORN,
    currentRev = Rev.ZERO,
    species = blankSpeciesMap(),
    stars = StarCounters(earned = emptyMap(), spent = emptyMap()),
    clears = emptyMap(),
)

/**
 * TS `newId()` — `crypto.randomUUID()`. Java's `UUID.toString()` is lowercase
 * by spec, matching what JS emits, so mixed-fleet households produce
 * homogeneous-looking documents with no case fix (Foundation uppercases, which
 * is why the Swift port lowercases). The `c_<ts36>_<rand36>` fallback path
 * (crypto unavailable) has no JVM equivalent failure mode; `randomUUID()`
 * cannot fail.
 */
internal fun newChildId(): String = UUID.randomUUID().toString()

/* Normalisation --------------------------------------------------------------- */

fun normalizeRev(r: LooseRev?): Rev {
    // TS: `rev ?? ZERO_REV` — a *partial* rev object would be kept as-is
    // there; here the missing halves default field-wise. Identical for every
    // blob the app ever wrote (revs are always written whole).
    if (r == null) return Rev.ZERO
    return Rev(at = (r.at ?: 0.0).looseMillis, by = r.by ?: "")
}

/**
 * TS: `{ ...blankConfig(s), ...src.config, species: s }` — every present
 * field replaces the default, and `species` is FORCE-SET to the slot key
 * (a loose config claiming another species is overridden).
 */
internal fun normalizeConfig(s: Species, src: LooseMascotConfig?): MascotConfig {
    val blank = blankConfig(s)
    if (src == null) return blank
    return MascotConfig(
        species = s,
        stage = src.stage?.looseCount ?: blank.stage,
        colors = src.colors ?: blank.colors,
        styles = src.styles ?: blank.styles,
        accessories = src.accessories ?: blank.accessories,
    )
}

fun normalizeSpecies(partial: Map<String, LooseSpeciesProgress>?): Map<Species, SpeciesProgress> {
    val out = LinkedHashMap(blankSpeciesMap())
    // Iterating `Species.entries` drops unknown species keys, same as the TS
    // loop over ALL_SPECIES — and keeps the map total, all five slots present.
    for (s in Species.entries) {
        val src = partial?.get(s.wire) ?: continue
        out[s] = SpeciesProgress(
            config = normalizeConfig(s, src.config),
            owned = src.owned ?: emptyList(),
            rev = normalizeRev(src.rev),
        )
    }
    return out
}

fun normalizeProfile(p: LooseProfile?): PersistedProfile {
    // NB: TS types the parameter non-optional and would throw on a corrupted
    // v4 child with no profile object; this port stays total and reads it as a
    // blank profile.
    val src = p ?: LooseProfile()
    return PersistedProfile(
        chosen = src.chosen ?: false,
        // NB: documented deviation — JS would KEEP an unknown `current` string;
        // the enum cannot represent garbage, so unknown → UNICORN here. Only
        // reachable from a hand-corrupted blob; behaviourally invisible for all
        // data the app has ever written. Do not widen `current` to String over
        // this.
        current = src.current?.let(Species::fromWire) ?: Species.UNICORN,
        currentRev = normalizeRev(src.currentRev),
        species = normalizeSpecies(src.species),
        stars = StarCounters(
            earned = looseCounter(src.stars?.earned),
            spent = looseCounter(src.stars?.spent),
        ),
        clears = looseClears(src.clears),
    )
}

/* Migrations ------------------------------------------------------------------ */

/**
 * v2/v3 → v4. The flat totals become "everything earned on THIS device", which
 * is the only honest seeding: before v4 there was no sync, so whatever a device
 * holds is exactly what that device produced. Two phones that migrate their own
 * blobs and meet later therefore SUM — correct, they really were two separate
 * progressions. `balance` was already net of spending, so it seeds `earned`
 * with `spent` empty and the spendable total comes out unchanged.
 */
fun migrateFlatProfile(l: LegacyFlatProfile, device: String): PersistedProfile {
    val clears = LinkedHashMap<String, Map<String, Double>>()
    // Guard `n > 0`: a zero or negative legacy value produces NO key, not a
    // zero key (same below for balance).
    for ((key, n) in l.ledger ?: emptyMap()) {
        if (n > 0) clears[key] = mapOf(device to n)
    }
    val balance = l.balance ?: 0.0
    return normalizeProfile(
        LooseProfile(
            chosen = l.chosen,
            current = l.current,
            currentRev = null,
            species = l.species,
            stars = LooseStarCounters(
                earned = if (balance > 0) mapOf(device to balance) else emptyMap(),
                spent = emptyMap(),
            ),
            clears = clears,
        ),
    )
}

/** v1 (single mascot) → v4: that mascot fills its species slot, then as above. */
fun migrateV1Profile(l: LegacyV1Profile, device: String): PersistedProfile {
    // NB: same deviation as `normalizeProfile` — an unknown species string on
    // the legacy config reads as UNICORN.
    val current = l.config?.species?.let(Species::fromWire) ?: Species.UNICORN
    return migrateFlatProfile(
        LegacyFlatProfile(
            // v1 users had necessarily chosen (TS: `l.chosen ?? true`).
            chosen = l.chosen ?: true,
            current = current.wire,
            species = mapOf(
                current.wire to LooseSpeciesProgress(config = l.config, owned = l.owned ?: emptyList(), rev = null),
            ),
            balance = l.balance,
            ledger = l.ledger,
        ),
        device,
    )
}

/** TS `child()` — a brand-new roster entry. */
fun child(name: String, profile: PersistedProfile, device: String, now: Millis): ChildProfile {
    val trimmed = name.trim()
    return ChildProfile(
        id = newChildId(),
        // NB oddity, ported as-is: the trimmed-or-"Joueur" fallback does NOT
        // clamp to 14 characters — only `renameChild` clamps.
        name = trimmed.ifEmpty { "Joueur" },
        // TS `newRev(device, now)` — inlined; sync/Merge.kt owns the function.
        nameRev = Rev(at = now, by = device),
        touchedAt = now,
        profile = profile,
    )
}

fun normalizeRoster(r: LooseRoster): Roster {
    val children = (r.children ?: emptyList()).map { c ->
        ChildProfile(
            // TS uses `c.id || newId()` — an EMPTY string id is replaced too.
            id = c.id?.takeIf { it.isNotEmpty() } ?: newChildId(),
            name = c.name ?: "Joueur",
            nameRev = normalizeRev(c.nameRev),
            touchedAt = (c.touchedAt ?: 0.0).looseMillis,
            profile = normalizeProfile(c.profile),
        )
    }
    val activeId = r.activeId?.takeIf { id -> children.any { it.id == id } }
    return Roster(children = children, activeId = activeId, removed = looseTombstones(r.removed))
}

/**
 * Migrate the best available saved data into a roster (v4 → v3 → v2 → v1).
 * Port of `initialRoster()` — with `device` and `now` injected instead of read
 * (TS calls `deviceId()` and `Date.now()` inside).
 *
 * Computes only; never writes. The migrated result is persisted by the first
 * mutation's commit, exactly like `useState(initialRoster)` which saves
 * nothing until a write.
 */
fun initialRoster(kv: KVStore, device: String, now: Millis): Roster {
    val v4 = ProfileStorage.loadRoster(kv)
    if (v4 != null) return normalizeRoster(v4)

    // TS gate: `v3?.children?.length` — present AND non-empty.
    val v3 = ProfileStorage.loadV3Roster(kv)
    if (v3 != null) {
        val kids = v3.children
        if (!kids.isNullOrEmpty()) {
            val children = kids.map { c ->
                ChildProfile(
                    id = c.id?.takeIf { it.isNotEmpty() } ?: newChildId(),
                    name = c.name ?: "Joueur",
                    nameRev = Rev.ZERO,
                    // Fresh migration: nothing can have tombstoned these yet,
                    // and marking them touched keeps a future stale tombstone
                    // from erasing them.
                    touchedAt = now,
                    profile = migrateFlatProfile(c.profile ?: LegacyFlatProfile(), device),
                )
            }
            val activeId = v3.activeId?.takeIf { id -> children.any { it.id == id } }
            return Roster(children = children, activeId = activeId, removed = emptyMap())
        }
    }

    val v2 = ProfileStorage.loadV2Profile(kv)
    if (v2 != null) {
        val c = child("Joueur 1", migrateFlatProfile(v2, device), device, now)
        return Roster(children = listOf(c), activeId = c.id, removed = emptyMap())
    }

    val v1 = ProfileStorage.loadV1Profile(kv)
    if (v1 != null) {
        val c = child("Joueur 1", migrateV1Profile(v1, device), device, now)
        return Roster(children = listOf(c), activeId = c.id, removed = emptyMap())
    }

    return Roster(children = emptyList(), activeId = null, removed = emptyMap())
}
