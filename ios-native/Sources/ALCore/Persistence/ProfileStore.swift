import Foundation
import Observation

/* -------------------------------------------------------------------------- */
/* ProfileStore — single source of truth for players, points, mascots,          */
/* progress. Port of `src/hooks/useProfile.tsx` (the provider half; the pure    */
/* top half lives in Migrations.swift).                                         */
/*                                                                             */
/* Three ownership tiers:                                                       */
/*   • device : a Roster of named children (siblings share the tablet)          */
/*   • child  : stars (balance) + cleared-levels (ledger) are GLOBAL to a child */
/*   • species: each mascot keeps its own growth/look/items (switch is safe)    */
/* All mutations below act on the ACTIVE child; roster ops switch who that is.  */
/*                                                                             */
/* THE SINGLE CHOKE POINT for every profile mutation, exactly as `useProfile`   */
/* is today. ALUI reads `store.profile` (a ProfileView) and calls this API; it  */
/* never touches KVStore, Merge or ProfileStorage directly.                     */
/*                                                                             */
/* Every mutation method is a plain synchronous `@MainActor` function —         */
/* invariant 1 has nowhere to await. React's ref-mirror trick (`ref.current`)   */
/* is unnecessary here: a `@MainActor` stored property IS the single,           */
/* synchronously-readable, always-fresh value.                                  */
/* -------------------------------------------------------------------------- */

@MainActor
@Observable
public final class ProfileStore {
    /// Everyone on this device + who is at the wheel. Mutate only via the API.
    public private(set) var roster: Roster

    @ObservationIgnored private let kv: KVStore
    @ObservationIgnored private let device: () -> String
    @ObservationIgnored private let now: () -> Millis
    @ObservationIgnored private let sync: SyncClient?

    /**
     * - Parameters:
     *   - kv: the one key/value primitive (D7). Prefixed on device (§8).
     *   - device: this device's id; defaults to a `DeviceIdentity` over `kv`,
     *     lazy + memoised like the TS.
     *   - now: wall clock in epoch ms — injectable so tests can steer every
     *     `Rev` stamp and `touchedAt` (the same discipline as Merge).
     *   - sync: the household client, or nil when sync is not configured.
     *
     * Construction computes the roster (migrating v3/v2/v1 forward if needed)
     * but does NOT save it — the migrated result is only persisted when the
     * first mutation commits, exactly like `useState(initialRoster)` which
     * saves nothing until a write. The old-version blobs stay on disk.
     */
    public init(
        kv: KVStore,
        device: (() -> String)? = nil,
        now: @escaping () -> Millis = { Millis(Date().timeIntervalSince1970 * 1000) },
        sync: SyncClient? = nil
    ) {
        self.kv = kv
        let dev: () -> String
        if let device {
            dev = device
        } else {
            let identity = DeviceIdentity(kv: kv)
            dev = { identity.deviceId() }
        }
        self.device = dev
        self.now = now
        self.sync = sync
        self.roster = initialRoster(kv: kv, device: dev(), now: now())
    }

    /* -- what the UI reads --------------------------------------------------*/

    /// The ACTIVE child's flattened profile (a chosen=false default if none
    /// active). TS `expose()`: everything outside this store, ProfileStorage
    /// and Sync/Merge sees plain numbers.
    public var profile: ProfileView {
        let p = activeProfile
        return ProfileView(of: p, balance: balanceOf(p.stars), ledger: ledgerOf(p.clears))
    }

    /// Everyone on this device.
    public var children: [ChildProfile] { roster.children }

    /// The child currently playing, or nil while on the welcome screen.
    public var activeId: String? { roster.activeId }

    /* -- private helpers ----------------------------------------------------*/

    private var activeProfile: PersistedProfile {
        roster.children.first(where: { $0.id == roster.activeId })?.profile ?? defaultProfile
    }

    /// Set + persist. Every mutation persists immediately (TS commits on every
    /// write); `saveRoster` is synchronous and total.
    private func commit(_ next: Roster) {
        roster = next
        ProfileStorage.saveRoster(next, to: kv)
    }

    /// Rewrite the active child's profile; no-op if nobody is playing.
    /// Every write stamps `touchedAt` — the delete rule in Sync/Merge.swift
    /// reads it to refuse a tombstone that would erase play this device has
    /// since done.
    private func updateActive(_ fn: (PersistedProfile) -> PersistedProfile) {
        guard let activeId = roster.activeId else { return }
        let now = self.now()
        var next = roster
        next.children = next.children.map { c in
            guard c.id == activeId else { return c }
            var c = c
            c.touchedAt = now
            c.profile = fn(c.profile)
            return c
        }
        commit(next)
    }

    /* -- points (per active child) ------------------------------------------*/

    /**
     * Award for clearing (exercise, level). Returns points granted: the
     * decaying completion curve plus the first-try accuracy bonus (see
     * `Rewards.sessionReward` — the ONLY earner, invariant 8). Training
     * exercises (difficulty 0) grant 0 but still count in the ledger.
     *
     * NB oddity, ported as-is: with no active child the update no-ops but the
     * computed points are still returned (TS — `updateActive` guards, `award`
     * doesn't). And a 0-point award still bumps `earned` by +0, creating the
     * device's key.
     */
    @discardableResult
    public func award(exercise: ExerciseId, level: Int, perfectRounds: Int, totalRounds: Int) -> Int {
        let key = Rewards.ledgerKey(exercise: exercise, level: level)
        // Prior clears are read BEFORE the update, from the freshest state.
        let prior = ledgerOf(activeProfile.clears)[key] ?? 0
        let points = Rewards.sessionReward(
            difficulty: Levels.exerciseDifficulty(exercise),
            priorClears: prior,
            perfectRounds: perfectRounds,
            totalRounds: totalRounds
        )
        let device = self.device()
        updateActive { p in
            var p = p
            p.stars.earned = bump(p.stars.earned, device: device, by: points)
            p.clears[key] = bump(p.clears[key] ?? [:], device: device, by: 1)
            return p
        }
        return points
    }

    /// Points the NEXT clear of (exercise, level) guarantees — for "seen in
    /// advance" cues.
    public func preview(exercise: ExerciseId, level: Int) -> Int {
        Rewards.previewReward(
            ledger: ledgerOf(activeProfile.clears),
            exercise: exercise,
            level: level,
            difficulty: Levels.exerciseDifficulty(exercise)
        )
    }

    /// Spend points if affordable. Returns success. The affordability check
    /// reads the live store synchronously — this runs inside pointerdown-path
    /// code.
    @discardableResult
    public func spend(cost: Int) -> Bool {
        if balanceOf(activeProfile.stars) < cost { return false }
        let device = self.device()
        updateActive { p in
            var p = p
            p.stars.spent = bump(p.stars.spent, device: device, by: cost)
            return p
        }
        return true
    }

    /// Buy + own + equip/apply an option for the current species. No-op if
    /// unaffordable; re-equips if owned (a free success).
    @discardableResult
    public func buy(_ option: CustomizationOption) -> Bool {
        let p = activeProfile
        let cur = p.species[p.current]
        let owned = cur.owned.contains(option.id)
        if !owned && balanceOf(p.stars) < option.cost { return false }
        let device = self.device()
        let rev = newRev(device: device, now: now())
        updateActive { pp in
            var pp = pp
            let c = pp.species[pp.current]
            // Re-derived from the passed profile (TS does; keeps the closure
            // self-consistent).
            let already = c.owned.contains(option.id)
            if !already {
                pp.stars.spent = bump(pp.stars.spent, device: device, by: option.cost)
            }
            pp.species[pp.current] = SpeciesProgress(
                config: applyOption(c.config, option),
                owned: already ? c.owned : c.owned + [option.id],
                rev: rev
            )
            return pp
        }
        return true
    }

    /* -- mascot -------------------------------------------------------------*/

    /// Mutate the current species' mascot config (equip owned items, grow a
    /// stage, …). TS's value-or-function union becomes two overloads.
    public func setConfig(_ next: MascotConfig) {
        setConfigStamped { _ in next }
    }

    public func setConfig(_ transform: @escaping (MascotConfig) -> MascotConfig) {
        setConfigStamped(transform)
    }

    private func setConfigStamped(_ transform: (MascotConfig) -> MascotConfig) {
        // The rev is computed ONCE, outside the update closure (TS does — one
        // timestamp even if the closure re-ran; in Swift it runs once anyway).
        let rev = newRev(device: device(), now: now())
        updateActive { p in
            var p = p
            let cur = p.species[p.current]
            p.species[p.current] = SpeciesProgress(
                config: transform(cur.config),
                owned: cur.owned,
                rev: rev
            )
            return p
        }
    }

    /// Pick / switch the active mascot. Non-destructive: each species keeps its
    /// progress.
    public func chooseSpecies(_ species: Species) {
        let currentRev = newRev(device: device(), now: now())
        updateActive { p in
            var p = p
            p.chosen = true
            p.current = species
            p.currentRev = currentRev
            return p
        }
    }

    /* -- roster -------------------------------------------------------------*/

    /// Create a new child and make them active (their species picker follows).
    /// Name trimmed, "Joueur" fallback — and NO 14-char clamp (only
    /// `renameChild` clamps; TS asymmetry, ported as-is).
    public func createChild(name: String) {
        let c = ALCore.child(name: name, profile: defaultProfile, device: device(), now: now())
        var next = roster
        next.children.append(c)
        next.activeId = c.id
        commit(next)
    }

    /// Make an existing child the active player. (TS does not validate the id;
    /// ported as-is.)
    public func selectChild(id: String) {
        var next = roster
        next.activeId = id
        commit(next)
    }

    /// Rename a child. Ignored if the trimmed name is empty.
    public func renameChild(id: String, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return }
        let now = self.now()
        let nameRev = newRev(device: device(), now: now)
        var next = roster
        next.children = next.children.map { c in
            guard c.id == id else { return c }
            var c = c
            // NB: TS `slice(0, 14)` counts UTF-16 code units; `prefix(14)`
            // counts Characters. Identical for every name a parent types short
            // of splitting an emoji, where the Swift behaviour is the saner one.
            c.name = String(trimmed.prefix(14))
            c.nameRev = nameRev
            c.touchedAt = now
            return c
        }
        commit(next)
    }

    /// Delete a child and everything they own.
    public func deleteChild(id: String) {
        let r = roster
        commit(Roster(
            children: r.children.filter { $0.id != id },
            activeId: r.activeId == id ? nil : r.activeId,
            // Tombstone, not just a removal: without it the family's other
            // device still has the child and would hand them straight back on
            // next merge.
            removed: r.removed.merging([id: now()]) { _, new in new }
        ))
    }

    /// Return to the "Qui joue ?" welcome screen (no active player).
    public func switchChild() {
        var next = roster
        next.activeId = nil
        commit(next)
    }

    /* -- sync ---------------------------------------------------------------*/

    /**
     * Household sync: pull, merge, push. Fired by the App layer on launch and
     * on `scenePhase == .active` (the port of mount + `appStateChange.isActive`).
     * Safe to fire as often as we like; Merge.swift is idempotent. Deliberately
     * NOT on any write path: gameplay stays offline-first, and a child
     * mid-round must never wait on a network call. Any transport error is
     * swallowed — no signal, or the server is down: the device keeps playing
     * alone.
     *
     * NB: the mid-sync local-write race is ported AS-IS from the TS
     * (`syncOnce(ref.current)` reads the roster at call start; the commit
     * compares against the then-current value). If a local write lands
     * mid-sync, the merge of the older snapshot can overwrite it — the TS
     * tolerates this because the very next sync re-merges and counters are
     * lossless. Do not "fix" it with locking; that changes observable timing.
     */
    public func syncNow() {
        guard let sync, sync.enabled else { return }
        let snapshot = roster
        Task { [weak self] in
            guard let merged = try? await sync.syncOnce(local: snapshot) else { return }
            guard let self else { return }
            // Value equality replaces the TS reference check — same observable
            // result: an unchanged roster is not re-saved.
            if merged != self.roster {
                self.commit(merged)
            }
        }
    }
}
