import Testing

@testable import ALCore

// 1:1 port of `src/licensing/entitlement.test.ts`. Same fixed T0, same cases,
// same assertions, same order.
//
// money.md §6.1: "If any of these need editing to pass, the port is wrong, not
// the test." This file is the executable half of invariant 11.

/// A fixed "now"; nothing here reads a real clock.
private let T0: Int64 = 1_700_000_000_000

private func license(
    paid: Bool = false,
    verifiedAt: Int64? = nil,
    trialStartedAt: Int64? = nil,
    clockHighWater: Int64 = 0
) -> LicenseState {
    LicenseState(
        paid: paid, verifiedAt: verifiedAt, trialStartedAt: trialStartedAt,
        clockHighWater: clockHighWater)
}

/// `expect(e).toMatchObject({ status: "trial", daysLeft: n })` — the TS matcher
/// ignores `endsAt`, so this does too.
private func daysLeft(_ e: Entitlement) -> Int? {
    guard case .trial(let d, _) = e else { return nil }
    return d
}

@Suite("trial clock")
struct TrialClockTests {
    @Test("gives a full fortnight before the parent has even accepted")
    func fullFortnightBeforeAccepting() {
        let e = entitlementOf(license(), T0)
        #expect(daysLeft(e) == trialDays)
    }

    @Test("counts down from the start date")
    func countsDown() {
        let s = license(trialStartedAt: T0)
        #expect(daysLeft(entitlementOf(s, T0)) == 14)
        #expect(daysLeft(entitlementOf(s, T0 + 3 * dayMs)) == 11)
        // 13.5 days: the ceil boundary that must round UP to a last day.
        #expect(daysLeft(entitlementOf(s, T0 + Int64(13.5 * Double(dayMs)))) == 1)
    }

    @Test("expires the instant the fortnight is up, not a moment before")
    func expiresExactlyOnTime() {
        let s = license(trialStartedAt: T0)
        #expect(entitlementOf(s, T0 + trialMs - 1) != .expired)
        #expect(daysLeft(entitlementOf(s, T0 + trialMs - 1)) == 1)
        #expect(entitlementOf(s, T0 + trialMs) == .expired)
    }

    @Test("blocks new rounds only once expired")
    func blocksOnlyOnceExpired() {
        #expect(canPlay(entitlementOf(license(trialStartedAt: T0), T0)))
        #expect(!canPlay(entitlementOf(license(trialStartedAt: T0), T0 + trialMs)))
        #expect(canPlay(entitlementOf(license(paid: true), T0)))
    }

    @Test("ignores a device clock wound backwards")
    func ignoresRewind() {
        // Day 10 of the trial, then someone sets the date back a year.
        let s = withClock(license(trialStartedAt: T0), T0 + 10 * dayMs)
        #expect(effectiveNow(s, T0 - 365 * dayMs) == T0 + 10 * dayMs)
        #expect(daysLeft(entitlementOf(s, T0 - 365 * dayMs)) == 4)
    }

    @Test("speaks French to the parent, and says the last day plainly")
    func speaksFrench() {
        #expect(
            trialNotice(entitlementOf(license(trialStartedAt: T0), T0 + 3 * dayMs))
                == "Essai gratuit — 11 jours restants")
        #expect(
            trialNotice(
                entitlementOf(license(trialStartedAt: T0), T0 + Int64(13.5 * Double(dayMs))))
                == "Dernier jour d'essai")
        #expect(trialNotice(.paid) == nil)
    }
}

@Suite("paid — and failing open")
struct PaidFailOpenTests {
    @Test("beats an exhausted trial")
    func paidBeatsExhaustedTrial() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * dayMs)
        #expect(entitlementOf(s, T0) == .paid)
    }

    @Test("stays paid through a long offline stretch")
    func staysPaidOffline() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * dayMs)
        // A fortnight in a cottage with no signal is not a reason to lock the app.
        #expect(entitlementOf(s, T0 + offlineGraceMs - dayMs) == .paid)
        // The boundary is `>`, so exactly 14 days is still fresh.
        #expect(entitlementOf(s, T0 + offlineGraceMs) == .paid)
    }

    @Test("falls back to the trial clock only once the grace window is spent")
    func fallsBackAfterGrace() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * dayMs)
        #expect(entitlementOf(s, T0 + offlineGraceMs + dayMs) == .expired)
    }

    @Test("never expires a paid family that has simply never been verified")
    func neverVerifiedIsPermanent() {
        // verifiedAt nil = we believe the flag and have nothing to age it against.
        let s = license(paid: true, verifiedAt: nil, trialStartedAt: T0 - 100 * dayMs)
        #expect(entitlementOf(s, T0 + 10 * 365 * dayMs) == .paid)
    }

    /// S1b: grace exhausted falls THROUGH to the trial clock rather than
    /// hard-locking, so a family whose trial never started still gets `.trial`.
    @Test("a spent grace window falls through to the trial clock, it does not lock")
    func spentGraceFallsThroughNotLocked() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: nil)
        let e = entitlementOf(s, T0 + offlineGraceMs + dayMs)
        #expect(daysLeft(e) == trialDays)
        #expect(canPlay(e))
    }
}

@Suite("applySnapshot — what the store is allowed to change")
struct ApplySnapshotTests {
    @Test("refuses to downgrade anything when the store is unreachable")
    func unreachableDowngradesNothing() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: T0)
        let after = applySnapshot(s, .unreachable, T0 + dayMs)
        #expect(after.paid)
        #expect(after.verifiedAt == T0)  // not re-stamped: it was never confirmed
        #expect(entitlementOf(after, T0 + dayMs) == .paid)
    }

    @Test("grants paid when the store confirms ownership")
    func grantsPaid() {
        let after = applySnapshot(
            license(trialStartedAt: T0 - 100 * dayMs),
            StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true),
            T0)
        #expect(entitlementOf(after, T0) == .paid)
    }

    @Test("honours a refund once the store says so out loud")
    func honoursRefund() {
        let s = license(paid: true, verifiedAt: T0, trialStartedAt: T0 - 100 * dayMs)
        let after = applySnapshot(
            s, StoreSnapshot(paid: false, trialStartedAt: nil, reachable: true), T0)
        #expect(entitlementOf(after, T0) == .expired)
    }

    @Test("takes the EARLIEST trial start, so reinstalling buys nothing")
    func earliestTrialStartWins() {
        // Local stamp says today; StoreKit's signed receipt says twelve days ago.
        let s = license(trialStartedAt: T0)
        let after = applySnapshot(
            s,
            StoreSnapshot(paid: false, trialStartedAt: T0 - 12 * dayMs, reachable: true),
            T0)
        #expect(after.trialStartedAt == T0 - 12 * dayMs)
        #expect(daysLeft(entitlementOf(after, T0)) == 2)
    }

    @Test("keeps the local stamp when the platform cannot prove one (Android)")
    func keepsLocalStampWhenUnprovable() {
        let s = license(trialStartedAt: T0 - 5 * dayMs)
        let after = applySnapshot(
            s, StoreSnapshot(paid: false, trialStartedAt: nil, reachable: true), T0)
        #expect(after.trialStartedAt == T0 - 5 * dayMs)
    }

    @Test("advances the clock high-water mark on every check")
    func advancesHighWater() {
        let after = applySnapshot(license(), .unreachable, T0)
        #expect(after.clockHighWater == T0)
    }
}
