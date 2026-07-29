import Testing

@testable import ALCore

// Invariant 11 as properties: **no combination of store state and clock value
// may produce a lockout for a family that has paid.**
//
// The sweeps are deterministic (a seeded SplitMix64), so a failure reproduces.
// money.md §6.2.

/// SplitMix64 — deterministic, seedable, and local to this file so W9 does not
/// depend on W2's `RandomSource`.
private struct MoneySeededRNG {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func nextU64() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Uniform in `low...high`.
    mutating func int(_ low: Int64, _ high: Int64) -> Int64 {
        precondition(high >= low)
        let span = UInt64(bitPattern: high &- low) &+ 1
        if span == 0 { return Int64(bitPattern: nextU64()) }
        return low &+ Int64(bitPattern: nextU64() % span)
    }

    mutating func bool() -> Bool { nextU64() & 1 == 1 }
}

private let T0: Int64 = 1_700_000_000_000
private let iterations = 10_000

/// `rank(paid) = 3 > trial = 2 > unknown = 1 > expired = 0`.
///
/// Written as an exhaustive switch with **no `default:`** so a future fifth
/// `Entitlement` case fails to compile here rather than silently defaulting to
/// locked. That is property 5 of money.md §6.2, expressed structurally.
private func rank(_ e: Entitlement) -> Int {
    switch e {
    case .paid: return 3
    case .trial: return 2
    case .unknown: return 1
    case .expired: return 0
    }
}

@Suite("invariant 11 — money never fails closed")
struct EntitlementPropertyTests {

    /// P1. Unreachable never downgrades: applying an unreachable snapshot can
    /// only ever raise the clock, never the verdict.
    @Test func unreachableNeverDowngrades() {
        var rng = MoneySeededRNG(seed: 0xA11C_E511)
        for _ in 0..<iterations {
            let now = rng.int(0, T0 * 2)
            let state = LicenseState(
                paid: rng.bool(),
                verifiedAt: rng.bool() ? rng.int(0, T0 * 2) : nil,
                trialStartedAt: rng.bool() ? rng.int(0, T0 * 2) : nil,
                clockHighWater: rng.int(0, T0 * 2))
            let before = entitlementOf(state, now)
            let after = entitlementOf(applySnapshot(state, .unreachable, now), now)
            #expect(rank(after) >= rank(before))
            // And the stronger statement the invariant actually needs:
            if canPlay(before) { #expect(canPlay(after)) }
        }
    }

    /// P2. A confirmed paid family plays for a fortnight offline — every single
    /// millisecond of it, boundary included.
    @Test func confirmedPaidPlaysThroughTheWholeGrace() {
        var rng = MoneySeededRNG(seed: 0x0FF1_11E5)
        for _ in 0..<iterations {
            let verifiedAt = rng.int(0, T0 * 2)
            let elapsed = rng.int(0, offlineGraceMs)
            let now = verifiedAt + elapsed
            let state = LicenseState(
                paid: true,
                verifiedAt: verifiedAt,
                // Arbitrary, including an exhausted trial: paid beats it.
                trialStartedAt: rng.bool() ? rng.int(0, now) : nil,
                // The high-water mark can be anywhere at or below `now`; a
                // *higher* one is a clock that already ran past the grace, which
                // is a legitimate expiry and not part of this property.
                clockHighWater: rng.int(0, now))
            #expect(entitlementOf(state, now) == .paid)
        }
        // The boundary, stated exactly: `>` not `>=`.
        let s = LicenseState(paid: true, verifiedAt: T0, trialStartedAt: nil, clockHighWater: 0)
        #expect(entitlementOf(s, T0 + offlineGraceMs) == .paid)
        #expect(entitlementOf(s, T0 + offlineGraceMs + 1) != .paid)
    }

    /// P3. Never-verified paid is permanent — there is nothing to age it against,
    /// so we believe the flag. Ten years out, a century out.
    @Test func neverVerifiedPaidIsPermanent() {
        var rng = MoneySeededRNG(seed: 0xDEAD_BEEF)
        for _ in 0..<iterations {
            let now = rng.int(0, T0 * 4)
            let state = LicenseState(
                paid: true,
                verifiedAt: nil,
                trialStartedAt: rng.bool() ? rng.int(0, T0 * 4) : nil,
                clockHighWater: rng.int(0, T0 * 4))
            #expect(entitlementOf(state, now) == .paid)
        }
    }

    /// P4. Clock rewind is inert: below the high-water mark, the answer is the
    /// answer at the high-water mark. (This is the one deliberately fail-CLOSED
    /// rule in the module — winding the date back is the free trial extension.)
    @Test func clockRewindIsInert() {
        var rng = MoneySeededRNG(seed: 0xC10C_C10C)
        for _ in 0..<iterations {
            let highWater = rng.int(T0, T0 * 2)
            let state = LicenseState(
                paid: rng.bool(),
                verifiedAt: rng.bool() ? rng.int(0, T0 * 2) : nil,
                trialStartedAt: rng.bool() ? rng.int(0, T0 * 2) : nil,
                clockHighWater: highWater)
            let rewound = rng.int(0, highWater)
            #expect(entitlementOf(state, rewound) == entitlementOf(state, highWater))
        }
    }

    /// P5. `canPlay` is total, and it is a blacklist of one. `.unknown` plays —
    /// that is the whole reason the case still exists (money.md R8).
    @Test func canPlayIsTotalAndOnlyExpiredIsBlocked() {
        let cases: [Entitlement] = [
            .unknown, .trial(daysLeft: 14, endsAt: T0), .trial(daysLeft: 1, endsAt: T0),
            .expired, .paid,
        ]
        for e in cases {
            #expect(canPlay(e) == (rank(e) != 0))
            #expect(canPlay(e) == (e != .expired))
        }
        #expect(canPlay(.unknown))
        #expect(!canPlay(.expired))
    }

    /// P7. Monotone countdown: for a fixed started trial, `daysLeft` never rises
    /// as `t` advances, and `.expired` is reached exactly once and never left.
    @Test func countdownIsMonotone() {
        let started = T0
        var previous = Int.max
        var expiredAt: Int64?
        var t = started
        // Every quarter-day across three weeks.
        while t <= started + trialMs + 7 * dayMs {
            let e = entitlementOf(
                LicenseState(trialStartedAt: started), t)
            switch e {
            case .trial(let d, let endsAt):
                #expect(expiredAt == nil, "left .expired after entering it")
                #expect(d <= previous)
                #expect(endsAt == started + trialMs)
                previous = d
            case .expired:
                if expiredAt == nil { expiredAt = t }
            case .paid, .unknown:
                Issue.record("a started, unpaid trial produced \(e)")
            }
            t += dayMs / 4
        }
        #expect(expiredAt == started + trialMs)
    }

    /// The umbrella statement, swept: a family that has ever been confirmed paid
    /// is never locked out while the grace window is open, whatever the trial
    /// clock says and whatever the store does next.
    @Test func aPaidFamilyIsNeverLockedOutInsideTheGrace() {
        var rng = MoneySeededRNG(seed: 0x9A1D_0FF)
        for _ in 0..<iterations {
            let verifiedAt = rng.int(T0, T0 * 2)
            var state = LicenseState(
                paid: true,
                verifiedAt: verifiedAt,
                trialStartedAt: verifiedAt - rng.int(0, 400 * dayMs),
                clockHighWater: verifiedAt)
            // Any number of unreachable answers, at any clock value in the window.
            for _ in 0..<5 {
                let now = verifiedAt + rng.int(0, offlineGraceMs)
                state = applySnapshot(state, .unreachable, now)
                #expect(canPlay(entitlementOf(state, now)))
                #expect(entitlementOf(state, now) == .paid)
            }
        }
    }
}
