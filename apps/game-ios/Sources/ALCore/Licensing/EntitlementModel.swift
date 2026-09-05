import Observation

/* -------------------------------------------------------------------------- */
/* Who may play, and the two buttons that change it.                            */
/* -------------------------------------------------------------------------- */
//
// Port of `src/licensing/useEntitlement.tsx`. `EntitlementProvider` +
// `useEntitlement()` collapse into one `@MainActor @Observable` model (D20),
// injected with `.environment(model)` and read with
// `@Environment(EntitlementModel.self)`.
//
// `@MainActor` and not an `actor`: every consumer is a view, and invariant 1
// forbids putting anything on the feedback path behind a hop. Single-actor is
// also what the React code was.
//
// D6: the clock is injected. There is no direct system-clock read in this
// directory — a 14-day offline grace whose clock cannot be advanced in a test is
// a 14-day offline grace nobody has ever verified.

/**
 * Fold a store answer into the saved license. PURE.
 *
 * Unreachable ⇒ change nothing but the clock. This is the fail-open rule and it
 * is the single most important line in the file: a network blip must never turn
 * a paying family's game off.
 *
 * Note the asymmetry, which *is* invariant 11: a negative from a **reachable**
 * store is authoritative (that is how a refund lands); a negative from an
 * unreachable one is not, and must not even re-stamp `verifiedAt` — the grace
 * window measures time since the last *confirmation*.
 */
public func applySnapshot(
    _ s: LicenseState, _ snap: StoreSnapshot, _ now: Int64
) -> LicenseState {
    if !snap.reachable { return withClock(s, now) }
    let trialStartedAt: Int64?
    if let authoritative = snap.trialStartedAt {
        // Earliest wins: a reinstall cannot buy a fresh fortnight.
        trialStartedAt = min(authoritative, s.trialStartedAt ?? authoritative)
    } else {
        // The platform cannot prove a start (Android): keep the local stamp.
        trialStartedAt = s.trialStartedAt
    }
    var next = s
    next.paid = snap.paid
    next.verifiedAt = now
    next.trialStartedAt = trialStartedAt
    return withClock(next, now)
}

@MainActor
@Observable
public final class EntitlementModel {
    /// What the app should do right now.
    ///
    /// NB: computed against `checkedAt`, which is NOT a live clock — it moves
    /// only inside `refresh()`. "The trial can lapse mid-session. We recheck on
    /// resume rather than ticking a timer: a child mid-exercise when the
    /// fortnight runs out gets to finish." Do **not** replace this with a
    /// `Timer`, a `TimelineView`, or a clock read in the getter. The staleness is
    /// the feature.
    public var entitlement: Entitlement { entitlementOf(license, checkedAt) }

    /// Has a parent seen and accepted the trial terms?
    public private(set) var onboarded: Bool

    /// Localised store price, e.g. "9,99 €" — nil until the store answers.
    public private(set) var priceLabel: String?

    /// Is there a purchase path on this build at all?
    public let storeAvailable: Bool

    /// The persisted license. Exposed read-only; every mutation goes through
    /// `commit`, which also saves.
    public private(set) var license: LicenseState

    /// The instant `entitlement` is evaluated against. Set only by `refresh()`.
    public private(set) var checkedAt: Int64

    @ObservationIgnored private let store: PurchaseStore
    @ObservationIgnored private let persist: LicenseStore
    @ObservationIgnored private let time: TimeSource
    @ObservationIgnored private let redemption: RedemptionTransport
    /// The family's opaque sync id, minted on demand. A closure rather than a
    /// `SyncClient` because licensing must not depend on sync: this file knows
    /// there is a string, not where it comes from.
    @ObservationIgnored private let household: () -> String?
    @ObservationIgnored private var inFlight: Task<Void, Never>?
    /// Which tier `priceLabel` was fetched for, or nil when it never has been.
    /// Not a `Bool`, because redeeming a discount code mid-session changes the
    /// tier and the screen must not keep showing the price of the other product.
    @ObservationIgnored private var pricedTier: UnlockTier?

    /// `beginTrial()`'s follow-up task, kept only so a test can await it.
    ///
    /// It used to be fire-and-forget, which made its completion unobservable and
    /// forced `EntitlementModelTests` to poll with a fixed number of
    /// `Task.yield()`s. That was flaky from the day it was written and began
    /// failing about one run in eight once the suite passed a thousand tests.
    /// A test cannot be made reliable against work it has no way to wait for, so
    /// the seam belongs here rather than in a cleverer poll.
    ///
    /// Deliberately NOT `inFlight`: the body awaits `inFlight?.value` to
    /// serialise behind a concurrent `refresh()`, so parking itself there would
    /// deadlock. Nothing in the app reads this.
    @ObservationIgnored private(set) var trialTask: Task<Void, Never>?

    /// - Parameters:
    ///   - redemption: the code endpoint. Defaults to the one that answers
    ///     `.unreachable`, so every existing caller — tests, previews, a build
    ///     with no backend — behaves exactly as it did.
    ///   - household: the family's sync id, or nil when there is none yet. The
    ///     app passes a closure that MINTS one on demand: a family redeeming a
    ///     code before they have ever paired still needs something to key the
    ///     grant on.
    public init(
        store: PurchaseStore,
        persist: LicenseStore,
        time: TimeSource,
        redemption: RedemptionTransport = UnavailableRedemptionTransport(),
        household: @escaping () -> String? = { nil }
    ) {
        self.store = store
        self.persist = persist
        self.time = time
        self.redemption = redemption
        self.household = household
        self.storeAvailable = store.available
        self.license = persist.load()
        self.onboarded = persist.loadOnboarded()
        self.priceLabel = nil
        // Storage is synchronous (D7), so unlike the web there is no hydration
        // race and nothing to await here.
        self.checkedAt = time.nowMillis
    }

    private func commit(_ next: LicenseState) {
        license = next
        persist.save(next)
    }

    /**
     * Re-check ownership. Called on boot and on `scenePhase == .active` — that is
     * when a purchase made in the store UI, a Family Sharing grant, or a refund
     * shows up. Never on `.inactive` (Control Centre, notification shade):
     * Capacitor's `isActive` was false there and the TS only acted on `true`.
     *
     * **[DEVIATION] Serialised.** React's `ref.current` read-modify-write was safe
     * because JS is single-threaded and the two triggers could not interleave a
     * commit. Two overlapping Swift `Task`s could each read `license`, await, and
     * write back — losing one update. A second `refresh()` while one is in flight
     * awaits the existing task instead of starting a new one. (money.md R1)
     */
    public func refresh() async {
        if let existing = inFlight {
            await existing.value
            return
        }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.performRefresh()
            // Cleared as the last statement of the body, so it happens before any
            // awaiter resumes and a later caller never coalesces onto a task that
            // has already finished.
            self.inFlight = nil
        }
        inFlight = task
        await task.value
    }

    private func performRefresh() async {
        // Sampled BEFORE the await and reused after, exactly as the TS does.
        let now = time.nowMillis
        checkedAt = now

        let tier = unlockTier
        if pricedTier == tier {
            let snap = await store.refresh()
            commit(applySnapshot(license, snap, now))
        } else {
            // Fetched in parallel with the refresh, once per tier: at boot, and
            // again the one time a discount code moves the family to `.early`.
            pricedTier = tier
            async let label = store.priceLabel(tier)
            async let snapshot = store.refresh()
            let (fetchedLabel, snap) = await (label, snapshot)
            priceLabel = fetchedLabel
            commit(applySnapshot(license, snap, now))
        }
    }

    /**
     * Accept the terms and start the clock. Called from Onboarding only.
     * Fire-and-forget: returns immediately.
     *
     * The order is load-bearing:
     *  1. persist `onboarded` synchronously, FIRST, so the onboarding screen
     *     dismisses even if everything after fails;
     *  2. stamp `trialStartedAt` locally, so the clock starts with no network.
     *     `?? now` means an existing stamp is never overwritten;
     *  3. only then ask the store for an authoritative (earlier) date. nil ⇒ the
     *     local stamp stands, silently.
     */
    public func beginTrial() {
        let now = time.nowMillis
        persist.saveOnboarded()
        onboarded = true

        var next = license
        next.trialStartedAt = license.trialStartedAt ?? now
        commit(withClock(next, now))

        trialTask = Task { @MainActor [weak self] in
            guard let self else { return }
            guard let authoritative = await self.store.beginTrial() else { return }
            // Routed through the same serialisation as `refresh()`, then re-read:
            // the TS reads `ref.current` after its await, so this must compose
            // with whatever a concurrent refresh wrote rather than clobber it.
            await self.inFlight?.value
            var s = self.license
            s.trialStartedAt = min(authoritative, self.license.trialStartedAt ?? authoritative)
            self.commit(withClock(s, self.time.nowMillis))
        }
    }

    /**
     * Which product this family is offered.
     *
     * `.early` only ever comes from a redeemed `discount` code. It is read from
     * the licence rather than held as its own state so that a reinstall, a
     * rollback or a second device reading the same blob all agree — and so that
     * there is exactly one thing to forge, which is a persisted timestamp the
     * store still refuses to sell against.
     */
    public var unlockTier: UnlockTier {
        license.discountGrantedAt != nil ? .early : .standard
    }

    /// Buy the unlock, at whichever tier this family is entitled to be offered.
    /// Refreshes only on success.
    public func purchase() async -> Bool {
        let ok = await store.purchase(unlockTier)
        if ok { await refresh() }
        return ok
    }

    /// Re-apply prior purchases. Refreshes **unconditionally** — the asymmetry
    /// with `purchase()` is in the TS and is correct: `AppStore.sync()` can
    /// materialise entitlements even when the call reports nothing restored.
    public func restore() async -> Bool {
        let ok = await store.restore()
        await refresh()
        return ok
    }

    /**
     * Spend a redemption code on this family.
     *
     * Three properties, and each of them is load-bearing:
     *
     *  1. **A malformed code never leaves the device.** `RedemptionCode.accept`
     *     normalises and checks the checksum first, so a typo is answered
     *     instantly and 31 of every 32 malformed guesses never reach the store.
     *  2. **Only a grant writes anything.** `.unknown`, `.exhausted`, `.expired`
     *     and `.unreachable` all leave the licence exactly as it was — a family
     *     mid-trial keeps their days, a family already unlocked stays unlocked.
     *  3. **The stamp is ours, not theirs.** The server's `grantedAt` is
     *     recorded, but `withClock` still runs, so a server clock skewed into
     *     the future cannot advance this device's high-water mark and shorten
     *     somebody's trial.
     *
     * Returns the answer verbatim so the screen can say what happened. It never
     * throws and it never fails closed (invariant 11).
     */
    public func redeem(_ raw: String) async -> RedemptionAnswer {
        guard let code = RedemptionCode.accept(raw) else { return .unknown }
        guard let household = household() else { return .unreachable }

        let answer = await redemption.redeem(code: code, household: household)
        guard let grantedAt = answer.grantedAt, let kind = answer.kind else { return answer }

        // Serialise behind any refresh in flight, exactly as `beginTrial` does:
        // both read-modify-write the licence across an await.
        await inFlight?.value
        var next = license
        switch kind {
        case .unlock:
            // `?? grantedAt` — a second redemption never moves an existing grant.
            next.codeGrantedAt = license.codeGrantedAt ?? grantedAt
        case .discount:
            // NOT a grant. This buys the right to see the early-adopter price
            // and nothing else: the family is still on their trial, and still
            // has to purchase. Writing `codeGrantedAt` here would hand the game
            // to everyone holding a €2.99 code, permanently and irreversibly.
            next.discountGrantedAt = license.discountGrantedAt ?? grantedAt
        }
        commit(withClock(next, time.nowMillis))
        // So the paywall disappears without waiting for the next resume — or, for
        // a discount, so it re-prices without one.
        checkedAt = time.nowMillis
        if kind == .discount { await refresh() }
        return answer
    }

    // NB: telemetry is deliberately NOT emitted here. `track("purchase_completed")`
    // and friends live in the Paywall screen, after the await. Tidying them into
    // the model would double-fire them once the screens are ported.
}
