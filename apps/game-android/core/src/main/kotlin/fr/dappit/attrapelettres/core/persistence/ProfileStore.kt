package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.platform.SystemTimeSource
import fr.dappit.attrapelettres.core.platform.TimeSource
import fr.dappit.attrapelettres.core.rewards.ledgerKey
import fr.dappit.attrapelettres.core.rewards.previewReward
import fr.dappit.attrapelettres.core.rewards.sessionReward
import fr.dappit.attrapelettres.core.sync.SyncClient
import fr.dappit.attrapelettres.core.sync.balanceOf
import fr.dappit.attrapelettres.core.sync.bump
import fr.dappit.attrapelettres.core.sync.ledgerOf
import fr.dappit.attrapelettres.core.sync.newRev
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------- */
/* ProfileStore — single source of truth for players, points, mascots,          */
/* progress. Port of `src/hooks/useProfile.tsx` (the provider half; the pure    */
/* top half lives in Migrations.kt), via ProfileStore.swift.                    */
/*                                                                             */
/* Three ownership tiers:                                                       */
/*   • device : a Roster of named children (siblings share the tablet)          */
/*   • child  : stars (balance) + cleared-levels (ledger) are GLOBAL to a child */
/*   • species: each mascot keeps its own growth/look/items (switch is safe)    */
/* All mutations below act on the ACTIVE child; roster ops switch who that is.  */
/*                                                                             */
/* THE SINGLE CHOKE POINT for every profile mutation, exactly as `useProfile`   */
/* is today. `:ui` reads `store.profile` (a ProfileView) and calls this API; it */
/* never touches KVStore, Merge or ProfileStorage directly.                    */
/*                                                                             */
/* This file is where invariants 8 and 9 stop being theory:                     */
/*                                                                             */
/*   • Points enter a profile through `sessionReward` and through nothing else. */
/*     There is no grant, no bonus and no debug earner on this class, and the   */
/*     math is not re-implemented here — `award` calls the one function in      */
/*     rewards/Rewards.kt and stores what it returns. (Invariant 8.)            */
/*                                                                             */
/*   • Every counter write goes through `bump`, which only ever touches THIS    */
/*     device's own slot. Nothing in this API can write another device's key,   */
/*     and nothing anywhere persists a bare total: `balance` and `ledger` are   */
/*     folds (`balanceOf` / `ledgerOf`) recomputed for the UI on every read.    */
/*     (Invariant 9.)                                                           */
/*                                                                             */
/* Every mutation method is a plain synchronous function — invariant 1 (A3) has */
/* nowhere to await, and the award/spend path runs inside a pointer-down        */
/* handler before the UI commits. `syncNow` is the single `suspend` member, and */
/* it is the one thing that deliberately never runs on a write path.            */
/*                                                                             */
/* React's ref-mirror trick (`ref.current`, so award/spend read the freshest    */
/* roster synchronously rather than a captured render's) has a Kotlin twin that */
/* costs nothing: `MutableStateFlow.value` IS the single, synchronously         */
/* readable, always-fresh value, and `rosterFlow` hands `:ui` the observation    */
/* channel `@Observable` gives the SwiftUI port. Reading `roster` inside a       */
/* mutation therefore always sees the previous mutation, even two in one frame. */
/* -------------------------------------------------------------------------- */

/**
 * @param kv the one key/value primitive (A3), synchronous by signature.
 * @param difficultyOf reward weight per exercise — `EXERCISES` is the single
 *   authority (`exerciseDifficulty` in the web's levels.ts). It is INJECTED
 *   rather than imported because `:core`'s build order puts this file (W8) on
 *   rewards + persistence + sync only, not on the ladders (W5); the composition
 *   root passes the levels catalog's lookup. It also keeps the choke point
 *   honest in a way a per-call parameter would not: a caller cannot hand
 *   `award` a difficulty of its choosing and inflate a training row.
 * @param time wall clock in epoch ms — injectable so a test can steer every
 *   `Rev` stamp and every `touchedAt` (the same discipline as sync/Merge.kt).
 * @param device this device's id; defaults to a [DeviceIdentity] over [kv],
 *   lazy and memoised like the TS. A function, not a String, because the
 *   identity is minted on first read, not at construction.
 * @param sync the household client, or null when sync is not configured.
 *
 * Construction computes the roster (migrating v3/v2/v1 forward if needed) but
 * does NOT save it — the migrated result is only persisted when the first
 * mutation commits, exactly like `useState(initialRoster)`, which saves nothing
 * until a write. The old-version blobs stay on disk, so a rolled-back launch
 * still finds data it understands.
 */
class ProfileStore(
    private val kv: KVStore,
    private val difficultyOf: (ExerciseId) -> Difficulty,
    private val time: TimeSource = SystemTimeSource(),
    private val device: () -> String = DeviceIdentity(kv)::deviceId,
    private val sync: SyncClient? = null,
) {

    private val _roster: MutableStateFlow<Roster> =
        MutableStateFlow(initialRoster(kv, device(), time.nowMillis))

    /**
     * Everyone on this device + who is at the wheel, as an observation channel
     * for `:ui`. Mutate only via the API below.
     */
    val rosterFlow: StateFlow<Roster> = _roster.asStateFlow()

    /** The same value, read synchronously. Always the freshest committed roster. */
    val roster: Roster
        get() = _roster.value

    /* -- what the UI reads --------------------------------------------------*/

    /**
     * The ACTIVE child's flattened profile (a `chosen = false` default if none
     * is active). TS `expose()`: everything outside this store, ProfileStorage
     * and sync/Merge sees plain numbers, and the two counter folds are
     * recomputed here on every read rather than stored anywhere (invariant 9).
     */
    val profile: ProfileView
        get() {
            val p = activeProfile
            return ProfileView(p, balanceOf(p.stars), ledgerOf(p.clears))
        }

    /** Everyone on this device. */
    val children: List<ChildProfile>
        get() = roster.children

    /** The child currently playing, or null while on the welcome screen. */
    val activeId: String?
        get() = roster.activeId

    /* -- private helpers ----------------------------------------------------*/

    private val activeProfile: PersistedProfile
        get() = roster.children.firstOrNull { it.id == roster.activeId }?.profile ?: DEFAULT_PROFILE

    /**
     * The current species' slot. Normalised data always holds all five, so the
     * fallback is unreachable in practice — it is there so the accessor is
     * total on a hand-corrupted blob rather than throwing on the tap path.
     */
    private fun currentProgress(p: PersistedProfile): SpeciesProgress =
        p.species[p.current] ?: blankProgress(p.current)

    /**
     * Set + persist. Every mutation persists immediately (the TS commits on
     * every write); `ProfileStorage.saveRoster` is synchronous and total.
     */
    private fun commit(next: Roster) {
        _roster.value = next
        ProfileStorage.saveRoster(next, kv)
    }

    /**
     * Rewrite the active child's profile; no-op if nobody is playing.
     *
     * Every write stamps `touchedAt` — the delete rule in sync/Merge.kt reads it
     * to refuse a tombstone that would erase play this device has since done.
     */
    private fun updateActive(fn: (PersistedProfile) -> PersistedProfile) {
        val current = roster
        val playing = current.activeId ?: return
        val now = time.nowMillis
        commit(
            current.copy(
                children = current.children.map { c ->
                    if (c.id == playing) c.copy(touchedAt = now, profile = fn(c.profile)) else c
                },
            ),
        )
    }

    /* -- points (per active child) ------------------------------------------*/

    /**
     * Award for clearing (exercise, level). Returns the points granted: the
     * decaying completion curve plus the first-try accuracy bonus — see
     * `sessionReward`, the ONLY earner in the app (invariant 8). Training
     * exercises (difficulty 0) pay the curve like any other row; what they never
     * pay is the bonus, so on those rows careful play earns exactly what spam
     * earns and there is nothing to grind for.
     *
     * Both writes go through `bump`, so this device increments only its own
     * counter slots and the result stays losslessly mergeable (invariant 9).
     *
     * NB oddity, ported as-is: with no active child the update no-ops but the
     * computed points are still returned (TS — `updateActive` guards, `award`
     * does not).
     */
    fun award(exercise: ExerciseId, level: Int, perfectRounds: Int, totalRounds: Int): Int {
        val key = ledgerKey(exercise, level)
        // Prior clears are read BEFORE the update, from the freshest state.
        val prior = ledgerOf(activeProfile.clears)[key] ?: 0
        val points = sessionReward(
            difficulty = difficultyOf(exercise),
            priorClears = prior,
            perfectRounds = perfectRounds,
            totalRounds = totalRounds,
        )
        val here = device()
        updateActive { p ->
            p.copy(
                stars = p.stars.copy(earned = bump(p.stars.earned, here, points)),
                clears = p.clears + (key to bump(p.clears[key] ?: emptyMap(), here, 1)),
            )
        }
        return points
    }

    /**
     * Points the NEXT clear of (exercise, level) guarantees — for the hub's
     * "seen in advance" pill. The guaranteed part only: the accuracy bonus is
     * earned, not promised.
     */
    fun preview(exercise: ExerciseId, level: Int): Int =
        previewReward(ledgerOf(activeProfile.clears), exercise, level)

    /**
     * Spend points if affordable. Returns success; an unaffordable spend writes
     * nothing and does not throw. The affordability check reads the live store
     * synchronously — this runs inside pointer-down-path code.
     *
     * Overdraw is not an error either: `balanceOf` floors at zero, so a child
     * whose two offline devices both bought the same 8-star item keeps BOTH
     * items and sees a balance of 0 rather than a negative number. We never claw
     * a purchase back from a six-year-old to satisfy arithmetic.
     */
    fun spend(cost: Int): Boolean {
        if (balanceOf(activeProfile.stars) < cost) return false
        val here = device()
        updateActive { p -> p.copy(stars = p.stars.copy(spent = bump(p.stars.spent, here, cost))) }
        return true
    }

    /**
     * Buy + own + equip/apply an option for the current species. No-op if
     * unaffordable; re-equips for free if already owned (a free success).
     */
    fun buy(option: CustomizationOption): Boolean {
        val p = activeProfile
        val owned = option.id in currentProgress(p).owned
        if (!owned && balanceOf(p.stars) < option.cost) return false
        val here = device()
        val rev = newRev(here, time.nowMillis)
        updateActive { pp ->
            val c = currentProgress(pp)
            // Re-derived from the profile the closure was handed (the TS does
            // the same); it keeps the closure self-consistent.
            val already = option.id in c.owned
            val bought = SpeciesProgress(
                config = applyOption(c.config, option),
                owned = if (already) c.owned else c.owned + option.id,
                rev = rev,
            )
            val stars =
                if (already) pp.stars
                else pp.stars.copy(spent = bump(pp.stars.spent, here, option.cost))
            pp.copy(stars = stars, species = pp.species + (pp.current to bought))
        }
        return true
    }

    /* -- mascot -------------------------------------------------------------*/

    /**
     * Replace the current species' mascot config outright. TS's
     * value-or-function union becomes two overloads.
     */
    fun setConfig(next: MascotConfig) {
        setConfigStamped { next }
    }

    /** Mutate the current species' config (equip owned items, grow a stage, …). */
    fun setConfig(transform: (MascotConfig) -> MascotConfig) {
        setConfigStamped(transform)
    }

    private fun setConfigStamped(transform: (MascotConfig) -> MascotConfig) {
        // The rev is computed ONCE, outside the update closure (the TS does the
        // same — one timestamp even if React re-ran the updater).
        val rev = newRev(device(), time.nowMillis)
        updateActive { p ->
            val cur = currentProgress(p)
            p.copy(species = p.species + (p.current to cur.copy(config = transform(cur.config), rev = rev)))
        }
    }

    /**
     * Pick / switch the active mascot. Non-destructive: each species keeps its
     * own growth, look and items, so switching back finds everything exactly
     * where it was. Stars and clears belong to the CHILD and never move.
     */
    fun chooseSpecies(species: Species) {
        val currentRev = newRev(device(), time.nowMillis)
        updateActive { p -> p.copy(chosen = true, current = species, currentRev = currentRev) }
    }

    /* -- roster -------------------------------------------------------------*/

    /**
     * Create a new child and make them active (their species picker follows).
     * Name trimmed, "Joueur" fallback — and NO 14-character clamp; only
     * [renameChild] clamps. A TS asymmetry, ported as-is.
     */
    fun createChild(name: String) {
        val c = child(name, DEFAULT_PROFILE, device(), time.nowMillis)
        val current = roster
        commit(current.copy(children = current.children + c, activeId = c.id))
    }

    /**
     * Make an existing child the active player. (The TS does not validate the
     * id; ported as-is. `normalizeRoster` drops a dangling `activeId` on the
     * next load anyway, so a bad id cannot survive a relaunch.)
     */
    fun selectChild(id: String) {
        commit(roster.copy(activeId = id))
    }

    /** Rename a child. Ignored if the trimmed name is empty. */
    fun renameChild(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val now = time.nowMillis
        val nameRev = newRev(device(), now)
        val current = roster
        commit(
            current.copy(
                children = current.children.map { c ->
                    // `take(14)` counts UTF-16 code units, exactly like the TS
                    // `slice(0, 14)`, so the two platforms clamp identically.
                    // (The Swift port counts Characters, which differs only
                    // where a grapheme would be split.)
                    if (c.id == id) c.copy(name = trimmed.take(14), nameRev = nameRev, touchedAt = now) else c
                },
            ),
        )
    }

    /** Delete a child and everything they own. */
    fun deleteChild(id: String) {
        val current = roster
        commit(
            Roster(
                children = current.children.filter { it.id != id },
                activeId = if (current.activeId == id) null else current.activeId,
                // Tombstone, not just a removal: without it the family's other
                // device still has the child and would hand them straight back
                // on the next merge.
                removed = current.removed + (id to time.nowMillis),
            ),
        )
    }

    /** Return to the "Qui joue ?" welcome screen (no active player). */
    fun switchChild() {
        commit(roster.copy(activeId = null))
    }

    /* -- sync ---------------------------------------------------------------*/

    /**
     * Household sync: pull, merge, push. Fired by the app layer on launch and on
     * resume (the port of mount + `visibilitychange`). Safe to fire as often as
     * we like; sync/Merge.kt is idempotent, so a double resume cannot double a
     * star.
     *
     * Deliberately NOT on any write path: gameplay stays offline-first, and a
     * child mid-round must never wait on a network call. That is also why this
     * is the only `suspend` member of the class — everything on the tap path is
     * synchronous by signature (A3), and the asynchrony is confined to the one
     * operation that never touches it.
     *
     * Any transport error is swallowed: no signal, or the server is down, and
     * the device keeps playing alone. Cancellation is re-thrown, because a
     * cancelled scope is not a failed sync and structured concurrency must not
     * be silently broken.
     *
     * NB: the mid-sync local-write race is ported AS-IS from the TS
     * (`syncOnce(ref.current)` reads the roster at call start; the commit
     * compares against the then-current value). If a local write lands mid-sync,
     * the merge of the older snapshot can overwrite it — the TS tolerates this
     * because the very next sync re-merges and counters are lossless. Do not
     * "fix" it with locking; that changes observable timing.
     */
    suspend fun syncNow() {
        val client = sync ?: return
        val snapshot = roster
        val merged = try {
            client.syncOnce(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        // Value equality replaces the TS reference check — same observable
        // result: an unchanged roster is not re-saved.
        if (merged != roster) commit(merged)
    }
}
