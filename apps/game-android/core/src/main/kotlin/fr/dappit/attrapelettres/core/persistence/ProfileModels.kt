package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import kotlinx.serialization.Serializable

// Port of the persisted-profile vocabulary from `src/types.ts` (the
// "Sync-safe counters (v4)" section onward) plus `applyOption` from
// `src/hooks/useProfile.tsx`.
//
// `Species`, `MascotConfig`, `CustomizationOption` live in `domain/Mascot.kt`
// and are imported, not redeclared. This file owns only the sync-safe storage
// types: Counter, Rev, StarCounters, ClearCounters, SpeciesProgress,
// PersistedProfile, ChildProfile, Roster, ProfileView.
//
// INVARIANT 9 — never persist a bare running total — is STRUCTURAL here:
//   • `PersistedProfile` has NO `balance` and NO `ledger` property. A stored
//     total cannot even be expressed in the persisted type; `StarCounters` /
//     `ClearCounters` are the only star/clear storage.
//   • `ProfileView` — the flattened shape the UI reads, which DOES carry
//     `balance` — is deliberately NOT `@Serializable`, so persisting or wiring
//     it is a missing-serializer compile error, not a code-review catch.
//
// The rule for any NEW persisted field (from CLAUDE.md, verbatim in spirit):
// decide its merge class FIRST — accumulates on two devices ⇒ `Counter`;
// cosmetic ⇒ a value plus a `Rev` stamp; a set ⇒ grow-only array. Then bump the
// storage key to `:vN` in ProfileStorage, add a `loadV(N-1)` reader, migrate
// forward in ProfileStore, and KEEP the old key and reader (a rolled-back
// launch must still read something it understands). Add the field to
// `mergeProfile`/`mergeChild` in the SAME change, with a test that two offline
// devices both writing it lose nothing. Cosmetics (which mascot, its colours, a
// name) are the only legitimate last-write-wins fields — losing one costs
// nothing, losing a star costs trust.

/** Epoch milliseconds — the unit of every stamp JS ever wrote (`Date.now()`). */
typealias Millis = Long

/* Sync-safe counters (v4) ----------------------------------------------------*/
/* One child plays on Dad's phone, Mum's phone and the iPad. Two devices can    */
/* earn stars for the SAME child while both are offline, so a plain `number`    */
/* cannot merge: last-write-wins silently eats one device's stars. Every value  */
/* that can change on two devices at once is therefore stored per device and    */
/* folded back into the flat number the UI reads (see ProfileView below).       */

/**
 * `deviceId` → a count that only ever grows on that device. Because a device
 * only ever increments its OWN key, merging two replicas is per-key `max` and
 * is lossless, commutative and idempotent — sync in any order, any number of
 * times, same result.
 *
 * `bump` (sync/Merge.kt) is the ONLY legal counter write, and it only ever
 * touches the caller's own device slot. Nothing in the API takes
 * "set balance to N".
 */
typealias Counter = Map<String, Int>

/**
 * Stars as a PN-counter: two grow-only halves, balance = Σearned − Σspent.
 * Never store a running total — a total cannot be merged.
 */
@Serializable
data class StarCounters(
    /** deviceId → stars ever earned on it. */
    val earned: Counter,
    /** deviceId → stars ever spent on it. */
    val spent: Counter,
)

/** ledgerKey() → per-device clear counts. Merged per device, then summed. */
typealias ClearCounters = Map<String, Counter>

/**
 * Times each (exerciseId, level) has been cleared. Key via ledgerKey().
 * DERIVED ONLY — a fold of `ClearCounters`. Never persisted.
 */
typealias CompletionLedger = Map<String, Int>

/**
 * Stamp for a genuinely last-write-wins field (cosmetics only — losing one is
 * harmless). `at` is Date.now(); `by` breaks ties deterministically so two
 * devices merging in opposite orders still agree.
 */
@Serializable
data class Rev(
    val at: Millis,
    val by: String,
) {
    companion object {
        /**
         * The stamp every never-written LWW field starts at — always loses a
         * merge. (TS: `ZERO_REV = { at: 0, by: "" }`.)
         */
        val ZERO = Rev(at = 0, by = "")
    }
}

/** One mascot's own progress. Kept forever — switching never discards it. */
@Serializable
data class SpeciesProgress(
    /** This mascot's current look (stage, colours, styles, accessories). */
    val config: MascotConfig,
    /**
     * Option ids bought FOR THIS SPECIES (unlocked, may or may not be
     * equipped). A grow-only ORDERED list, not a Set — merge preserves
     * local-side order and the order is observable (shop/inventory ordering).
     */
    val owned: List<String>,
    /** LWW stamp for `config`. `owned` needs none — it's a grow-only set. */
    val rev: Rev,
)

/**
 * The persisted child profile — what ProfileStorage reads/writes.
 *
 * Progress is split by ownership: growth/look/items live PER SPECIES (each
 * mascot remembers itself), while stars and cleared-levels belong to the CHILD
 * and survive every mascot switch.
 *
 * Every field here is mergeable across devices, by construction:
 *   chosen  grow-only boolean (OR)     stars   PN-counter
 *   current LWW via currentRev         clears  per-key counters
 *   species per-species merge (config LWW, owned grow-only set)
 * Nothing is a bare running total. See sync/Merge.kt.
 */
@Serializable
data class PersistedProfile(
    /** Has a species been picked yet (first-run gate). Never goes back to false. */
    val chosen: Boolean,
    /** The active mascot. */
    val current: Species,
    /** LWW stamp for `current` — which mascot you last picked is cosmetic. */
    val currentRev: Rev,
    /**
     * Per-species progress; the child can switch back anytime, nothing is lost.
     *
     * TS `Record<Species, SpeciesProgress>`. Unlike the iOS port (a fixed
     * five-slot struct), this stays a map because that is the shape the merge
     * unions and kotlinx writes enum keys through `@SerialName`, so the JSON
     * keys are the frozen wire strings either way. Totality is a NORMALISATION
     * guarantee, not a type one: every profile built by `normalizeProfile`
     * holds all five keys in declaration order, and `mergeProfile` unions both
     * sides so a slot can never be dropped. Readers stay total regardless —
     * see the ProfileView flatten.
     */
    val species: Map<Species, SpeciesProgress>,
    /** Stars — GLOBAL to the child, survives switches. Fold with balanceOf(). */
    val stars: StarCounters,
    /** Cleared (exercise, level) counts — GLOBAL to the child. Fold with ledgerOf(). */
    val clears: ClearCounters,
)

/**
 * One child on this device. Siblings share the tablet; each keeps their own
 * mascots, stars and progress.
 */
@Serializable
data class ChildProfile(
    /** Minted once and copied, never re-derived — no case normalisation. */
    val id: String,
    /**
     * The child's first name. DEVICE-LOCAL: the sync transport strips it, so
     * the server only ever holds opaque ids and integers. A device that joins
     * the household asks the parent « Qui est-ce ? » instead of receiving a
     * name. (Invariant 10 — `WireChild` in sync/Wire.kt has no such field.)
     */
    val name: String,
    /** LWW stamp for `name`, so a rename still merges between local replicas. */
    val nameRev: Rev,
    /**
     * Date.now() of the last write to this child, anywhere. Only the delete
     * rule reads it: a tombstone wins only if nothing happened to the child
     * after it, so tidying the roster on one phone can't erase a week of play
     * on another.
     */
    val touchedAt: Millis,
    val profile: PersistedProfile,
)

/**
 * Everyone who plays on this device + who's currently at the wheel.
 *
 * NB: the stored JSON spells a missing active child `"activeId": null`
 * explicitly, because JS `JSON.stringify` does and the blobs stay
 * byte-comparable across the three platforms. ProfileStorage's encoder keeps
 * kotlinx's default `explicitNulls = true` for exactly that reason; a test
 * pins the byte shape.
 */
@Serializable
data class Roster(
    val children: List<ChildProfile>,
    /**
     * The child now playing; null shows the "Qui joue ?" welcome screen.
     * DEVICE-LOCAL and never merged — who holds this tablet says nothing about
     * who holds the other one.
     */
    val activeId: String?,
    /**
     * childId → when it was deleted. Without tombstones a delete cannot win:
     * the other device still has the child and would resurrect them on next
     * merge.
     */
    val removed: Map<String, Millis>,
)

/**
 * Runtime profile the UI reads (TS `Profile extends PersistedProfile`): the
 * persisted shape plus flat mirrors — the CURRENT species' `config`/`owned`,
 * and the two counter folds. Callers keep reading `profile.balance` /
 * `profile.ledger` / `profile.config` / `profile.owned` as plain values; only
 * ProfileStore, ProfileStorage and sync/Merge ever see the counters underneath.
 *
 * DELIBERATELY NOT `@Serializable` (invariant 9): this is the flatten
 * (`expose()` in TS) and must never be persisted or sent. With no serializer,
 * wiring it into storage or transport fails at compile time, not in review.
 * Do not add the annotation.
 */
data class ProfileView(
    // The persisted fields.
    val chosen: Boolean,
    val current: Species,
    val currentRev: Rev,
    val species: Map<Species, SpeciesProgress>,
    val stars: StarCounters,
    val clears: ClearCounters,
    // The flat mirrors.
    /** = species[current].config */
    val config: MascotConfig,
    /** = species[current].owned */
    val owned: List<String>,
    /** = balanceOf(stars) — spendable stars, floored at 0. */
    val balance: Int,
    /** = ledgerOf(clears) — clears per (exercise, level), summed over devices. */
    val ledger: CompletionLedger,
) {
    /**
     * The flatten. The two folds are computed by the caller (ProfileStore's
     * `expose`) with `balanceOf`/`ledgerOf` from sync/Merge.kt — passed in here
     * so this file does not depend on the merge module. The current slot falls
     * back to its blank so the accessor stays total even on a profile that
     * skipped normalisation (normalised data always holds all five).
     */
    constructor(persisted: PersistedProfile, balance: Int, ledger: CompletionLedger) : this(
        chosen = persisted.chosen,
        current = persisted.current,
        currentRev = persisted.currentRev,
        species = persisted.species,
        stars = persisted.stars,
        clears = persisted.clears,
        config = (persisted.species[persisted.current] ?: blankProgress(persisted.current)).config,
        owned = (persisted.species[persisted.current] ?: blankProgress(persisted.current)).owned,
        balance = balance,
        ledger = ledger,
    )
}

/**
 * What `config` looks like with this option applied. Pure — the shop also uses
 * it to dress the live preview during a try-on, without touching the profile.
 * (Port of `applyOption` in `src/hooks/useProfile.tsx`.)
 */
fun applyOption(c: MascotConfig, o: CustomizationOption): MascotConfig =
    when (o.category) {
        CustomizationCategory.ACCESSORY ->
            if (o.id in c.accessories) c else c.copy(accessories = c.accessories + o.id)
        CustomizationCategory.COLOR -> c.copy(colors = c.colors + (o.slot to o.value))
        CustomizationCategory.STYLE -> c.copy(styles = c.styles + (o.slot to o.value))
    }
