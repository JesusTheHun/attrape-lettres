package fr.dappit.attrapelettres.core.licensing

import fr.dappit.attrapelettres.core.platform.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/* -------------------------------------------------------------------------- */
/* Who may play, and the two buttons that change it.                            */
/* -------------------------------------------------------------------------- */
//
// Port of `apps/game-web/src/licensing/useEntitlement.tsx`, cross-checked
// against `apps/game-ios/Sources/ALCore/Licensing/EntitlementModel.swift`.
// `EntitlementProvider` + `useEntitlement()` collapse into one model, held at
// the root and handed to the screens.

/**
 * Fold a store answer into the saved licence. PURE.
 *
 * Unreachable ⇒ change nothing but the clock. This is the fail-open rule and it
 * is the single most important line in the file: a network blip must never turn
 * a paying family's game off.
 *
 * Note the asymmetry, which *is* invariant 11: a negative from a **reachable**
 * store is authoritative (that is how a refund lands); a negative from an
 * unreachable one is not, and must not even re-stamp `verifiedAt` — the grace
 * window measures time since the last *confirmation*, so re-stamping it on a
 * failed call would extend the window on evidence of nothing.
 */
fun applySnapshot(state: LicenseState, snapshot: StoreSnapshot, now: Long): LicenseState {
    if (!snapshot.reachable) return withClock(state, now)
    val authoritative = snapshot.trialStartedAt
    val trialStartedAt = if (authoritative != null) {
        // Earliest wins: a reinstall cannot buy a fresh fortnight.
        minOf(authoritative, state.trialStartedAt ?: authoritative)
    } else {
        // The platform cannot prove a start — which on Android is ALWAYS, since
        // there is no price-0 in-app product to date. Keep the local stamp.
        state.trialStartedAt
    }
    return withClock(
        state.copy(paid = snapshot.paid, verifiedAt = now, trialStartedAt = trialStartedAt),
        now,
    )
}

/**
 * The observable wrapper around the pure machine.
 *
 * **Observability is the caller's job, and that is a deliberate deviation.**
 * The React original is a context provider and the Swift port is `@Observable`;
 * the Kotlin analogue would be Compose snapshot state or a `StateFlow`, and
 * `:core` may have neither — A1 bans `androidx.*` outright, and exposing a
 * coroutines type in the public API of a module that depends on coroutines with
 * `implementation` would not survive the consumer's classpath either. So the
 * model exposes plain read-only properties and `:ui` re-reads them after each
 * call. "After each call" is a well-defined moment here because every mutator
 * is either synchronous or a `suspend` function that returns when it is done —
 * there is no fire-and-forget work whose completion a caller cannot observe.
 *
 * Not thread-safe, and does not need to be: like every other stateful object in
 * `:core` it is main-thread bound. [refresh] serialises against itself so two
 * resume triggers cannot interleave a read-modify-write, which is the one race
 * React got for free by being single-threaded.
 *
 * The clock is INJECTED (`TimeSource`). There is no system-clock read anywhere
 * in this package, and `LicensingSourceScanTest` enforces it: a 14-day offline
 * grace whose clock cannot be advanced in a test is one nobody has verified.
 */
class EntitlementModel(
    private val store: PurchaseStore,
    private val persist: LicenseStore,
    private val time: TimeSource,
) {
    /**
     * The persisted licence. Read-only; every mutation goes through [commit],
     * which also saves.
     */
    var license: LicenseState = persist.load()
        private set

    /** Has a parent seen and accepted the trial terms? */
    var onboarded: Boolean = persist.loadOnboarded()
        private set

    /** Localised store price, e.g. "9,99 €" — null until the store answers. */
    var priceLabel: String? = null
        private set

    /** Is there a purchase path on this build at all? */
    val storeAvailable: Boolean = store.available

    /**
     * The instant [entitlement] is evaluated against. Set only by [refresh].
     *
     * Storage is synchronous (A3), so unlike the web there is no hydration race
     * and nothing to await in the constructor.
     */
    var checkedAt: Long = time.nowMillis
        private set

    /**
     * What the app should do right now.
     *
     * NB: computed against [checkedAt], which is NOT a live clock — it moves
     * only inside [refresh]. "The trial can lapse mid-session. We recheck on
     * resume rather than ticking a timer: a child mid-exercise when the
     * fortnight runs out gets to finish." Do **not** replace this with a timer,
     * a ticking flow, or a `TimeSource` read in the getter. The staleness is the
     * feature.
     */
    val entitlement: Entitlement
        get() = entitlementOf(license, checkedAt)

    private val gate = Mutex()
    private var refreshing = false
    private var priceFetched = false

    private fun commit(next: LicenseState) {
        license = next
        persist.save(next)
    }

    /**
     * Re-check ownership. Called on boot and on resume — that is when a purchase
     * made in the store's own UI, a Family Sharing grant on iOS, or a refund
     * shows up. Never on a write: gameplay stays offline-first.
     *
     * **[DEVIATION] Serialised, and coalescing.** React's `ref.current`
     * read-modify-write was safe because JS is single-threaded and the two
     * triggers could not interleave a commit. Two overlapping coroutines could
     * each read `license`, suspend inside the store call, and write back —
     * losing one update. A second `refresh()` arriving while one is in flight
     * therefore waits for the in-flight one and takes its answer, instead of
     * starting a second store call. A `refresh()` arriving after the first has
     * finished is a real refresh; nothing is swallowed.
     */
    suspend fun refresh() {
        if (refreshing) {
            // Someone else's refresh is already covering this moment. Wait for
            // it to release the gate, then take its answer.
            gate.withLock { }
            return
        }
        gate.withLock {
            refreshing = true
            try {
                performRefresh()
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun performRefresh() {
        // Sampled BEFORE the suspension and reused after, exactly as the TS does:
        // `verifiedAt` must record when we asked, not when the answer landed.
        val now = time.nowMillis
        checkedAt = now
        if (!priceFetched) {
            // Fetched once and never retried — the price does not change between
            // resumes, and a store that failed to give one will fail again.
            priceFetched = true
            priceLabel = store.priceLabel()
        }
        commit(applySnapshot(license, store.refresh(), now))
    }

    /**
     * Accept the terms and start the clock. Called from Onboarding only.
     *
     * Synchronous and total: nothing here can fail, and nothing here awaits. The
     * order is load-bearing —
     *  1. persist `onboarded` FIRST, so the onboarding screen dismisses for good
     *     even if everything after it goes wrong;
     *  2. stamp `trialStartedAt` locally, so the fortnight starts with no
     *     network. `?: now` means an existing stamp is never overwritten.
     *
     * **On Android that is the whole mechanism**, not step one of two: Google
     * Play has no price-0 in-app product to date the trial from, so the local
     * stamp is the trial clock. [confirmTrialStart] exists for the platform that
     * can do better.
     */
    fun beginTrial() {
        val now = time.nowMillis
        persist.saveOnboarded()
        onboarded = true
        commit(withClock(license.copy(trialStartedAt = license.trialStartedAt ?: now), now))
    }

    /**
     * Ask the store for an authoritative — meaning EARLIER — trial start, and
     * adopt it if there is one. A no-op when the platform cannot mint one, which
     * on Android is always.
     *
     * **[DEVIATION] Split out of [beginTrial].** The web and iOS versions fire
     * this off as an unawaited task inside `beginTrial`, and iOS then has to
     * keep a handle on that task purely so a test can wait for it. Kotlin has no
     * ambient scope to launch into and `:core` will not take one (a
     * `CoroutineScope` in the constructor is a coroutines type in the public
     * API, and A1's plain-JVM module cannot assume the consumer has it on the
     * classpath), so the asynchronous half is a suspend function the caller
     * launches. It costs this port nothing: on Android the store's `beginTrial`
     * returns null forever, so the whole method is a no-op that exists to keep
     * the two ports one shape. Onboarding calls [beginTrial] on the tap and
     * launches this after.
     *
     * Re-reads `license` AFTER the store call and behind the same gate as
     * [refresh], so it composes with whatever a concurrent refresh wrote rather
     * than clobbering it.
     */
    suspend fun confirmTrialStart() {
        val authoritative = store.beginTrial() ?: return
        gate.withLock {
            val current = license
            commit(
                withClock(
                    current.copy(
                        trialStartedAt = minOf(
                            authoritative,
                            current.trialStartedAt ?: authoritative,
                        ),
                    ),
                    time.nowMillis,
                ),
            )
        }
    }

    /** Buy the unlock. Refreshes only on success. */
    suspend fun purchase(): Boolean {
        val ok = store.purchase()
        if (ok) refresh()
        return ok
    }

    /**
     * Re-apply prior purchases. Refreshes **unconditionally** — the asymmetry
     * with [purchase] is in the TypeScript and is correct: a restore can
     * materialise an entitlement even when the call itself reports that nothing
     * was restored.
     */
    suspend fun restore(): Boolean {
        val ok = store.restore()
        refresh()
        return ok
    }

    // NB: telemetry is deliberately NOT emitted here. The purchase and restore
    // events belong to the Paywall screen, after the call returns. Tidying them
    // into the model would double-fire them once the screens land.
}
