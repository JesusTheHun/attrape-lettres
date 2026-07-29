/* -------------------------------------------------------------------------- */
/* What the family is allowed to play right now. PURE — no storage, no store,   */
/* no clock except the `now` passed in.                                         */
/*                                                                             */
/* The model Apple wrote a rule for (App Review 3.1.1): a non-subscription app  */
/* may run a free time-based trial before a full unlock, via a price-0          */
/* non-consumable named "14-day Trial", provided the duration, what stops       */
/* working, and the eventual charge are all disclosed BEFORE the trial starts.  */
/* Onboarding is that disclosure; this file is the clock.                       */
/* -------------------------------------------------------------------------- */
//
// Port of `src/licensing/entitlement.ts`. Owner of **invariant 11 — money never
// fails closed**. An unreachable store, a timed-out receipt check or a flat
// network may never lock a child out.
//
// Time is epoch MILLISECONDS as `Int64` throughout (money.md §2.2), so the
// arithmetic is byte-identical to the TypeScript's and the persisted blob stays
// interchangeable with the PWA's. There is no clock read anywhere in this
// directory — `now` is always a parameter, and `EntitlementModel` takes an
// injected `TimeSource` (D6). An unverifiable 14-day grace is an unverified one.

public let trialDays = 14
public let dayMs: Int64 = 24 * 60 * 60 * 1000
public let trialMs: Int64 = Int64(trialDays) * dayMs

/// Price in euros, TTC. Displayed copy lives in the screens; this is the truth.
public let unlockPriceEur = 9.99

public let productTrial = "fr.dappit.attrapelettres.trial14"
public let productUnlock = "fr.dappit.attrapelettres.unlock"

/**
 * How long a "paid" verdict survives without the store confirming it again.
 *
 * This is a fail-OPEN window and it is deliberate. A child on a plane, or in a
 * kitchen with no wifi, must never be told the game they own is locked. We
 * would rather give away a fortnight of play to someone who genuinely refunded
 * than show one paying six-year-old a paywall because StoreKit timed out.
 */
public let offlineGraceMs: Int64 = 14 * dayMs

// NB: money.md §2.5 freezes the screens' import list under the TypeScript
// spellings (`TRIAL_DAYS`, `UNLOCK_PRICE_EUR`). Both spellings are exported so
// the screens package cannot miss by a casing convention; there is exactly one
// value behind each pair.
public let TRIAL_DAYS = trialDays
public let UNLOCK_PRICE_EUR = unlockPriceEur

public struct LicenseState: Equatable, Codable, Sendable {
    /// The store says the unlock is owned by this Apple Account / Google account.
    public var paid: Bool

    /// When the store last CONFIRMED `paid`. Drives the offline grace window.
    /// `nil` is the TypeScript `null`, and it means "never confirmed" — which
    /// is treated as *paid forever*, not as *not paid*. See `entitlementOf` S1a.
    public var verifiedAt: Int64?

    /**
     * When the trial began.
     *
     * iOS: the price-0 IAP's StoreKit `purchaseDate` — signed by Apple, survives
     * a reinstall, and follows the Apple Account across devices. Never our own
     * timestamp, which a reinstall would reset.
     *
     * Android: Play has no price-0 IAP, so this is a local stamp persisted
     * through Auto Backup. Clearing app data resets it. Accepted: parents of
     * six-year-olds do not farm fortnightly trials, and the alternative is an
     * account system this app deliberately does not have.
     */
    public var trialStartedAt: Int64?

    /**
     * Highest `now` ever observed. Winding the device clock back is the one
     * trial-extension trick that costs nothing to try, and three lines to defeat.
     */
    public var clockHighWater: Int64

    public init(
        paid: Bool = false,
        verifiedAt: Int64? = nil,
        trialStartedAt: Int64? = nil,
        clockHighWater: Int64 = 0
    ) {
        self.paid = paid
        self.verifiedAt = verifiedAt
        self.trialStartedAt = trialStartedAt
        self.clockHighWater = clockHighWater
    }

    /// `BLANK_LICENSE`. NB: blank means *full trial*, i.e. the child plays.
    /// Every decode failure below degrades to this on purpose (R14 / invariant 11).
    public static let blank = LicenseState(
        paid: false, verifiedAt: nil, trialStartedAt: nil, clockHighWater: 0)

    // MARK: - Codable, hand-written on both sides

    private enum CodingKeys: String, CodingKey {
        case paid, verifiedAt, trialStartedAt, clockHighWater
    }

    /// Synthesised `Encodable` uses `encodeIfPresent` for optionals and DROPS the
    /// key; `JSON.stringify` emits `"verifiedAt":null`. Written by hand so the
    /// blob stays byte-interchangeable with the PWA's.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(paid, forKey: .paid)
        if let verifiedAt { try c.encode(verifiedAt, forKey: .verifiedAt) }
        else { try c.encodeNil(forKey: .verifiedAt) }
        if let trialStartedAt { try c.encode(trialStartedAt, forKey: .trialStartedAt) }
        else { try c.encodeNil(forKey: .trialStartedAt) }
        try c.encode(clockHighWater, forKey: .clockHighWater)
    }

    /// Total and lenient, mirroring `loadLicense`'s `parsed.x ?? default`. A
    /// wrong-typed field degrades to its default rather than failing the whole
    /// decode. Fail open all the way down.
    public init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: CodingKeys.self) else {
            self = .blank
            return
        }
        paid = ((try? c.decodeIfPresent(Bool.self, forKey: .paid)) ?? nil) ?? false
        verifiedAt = (try? c.decodeIfPresent(Int64.self, forKey: .verifiedAt)) ?? nil
        trialStartedAt = (try? c.decodeIfPresent(Int64.self, forKey: .trialStartedAt)) ?? nil
        clockHighWater =
            ((try? c.decodeIfPresent(Int64.self, forKey: .clockHighWater)) ?? nil) ?? 0
    }
}

public enum Entitlement: Equatable, Sendable {
    /// Nothing decided yet — the store has not answered. Never render a paywall
    /// on this.
    ///
    /// NB (money.md R8): `entitlementOf` NEVER returns this case. It exists only
    /// as a literal and only `canPlay` reads it. **Do not delete it as dead
    /// code.** It is the documented "the store has not answered" value; deleting
    /// it would let a future refactor make "no answer" mean "locked", which is
    /// exactly the failure invariant 11 exists to prevent.
    case unknown
    case trial(daysLeft: Int, endsAt: Int64)
    case expired
    case paid
}

/// Monotonic clock: a device clock that moved backwards is ignored.
public func effectiveNow(_ state: LicenseState, _ now: Int64) -> Int64 {
    max(now, state.clockHighWater)
}

/**
 * Fold the license into what the app should do.
 *
 * Order matters: paid beats everything, and an expired trial only bites once we
 * are sure the unlock is NOT owned. A merely unverified paid flag stays paid
 * until the grace window runs out.
 */
public func entitlementOf(_ state: LicenseState, _ now: Int64) -> Entitlement {
    let t = effectiveNow(state, now)

    if state.paid {
        let stale = state.verifiedAt.map { t - $0 > offlineGraceMs } ?? false
        if !stale { return .paid }
        // Grace exhausted: fall through and let the trial clock decide, rather than
        // hard-locking. Worst case the family sees the paywall and taps "Restaurer".
    }

    // Trial not started yet (pre-onboarding): treat as a full trial so nothing is
    // ever gated before the parent has even seen the terms.
    //
    // NB (money.md R13): `endsAt` is recomputed from `t` on every call, so it
    // SLIDES FORWARD — it is not a stable date. Nothing renders it today. Ported
    // as-is.
    guard let startedAt = state.trialStartedAt else {
        return .trial(daysLeft: trialDays, endsAt: t + trialMs)
    }

    let endsAt = startedAt + trialMs
    // `Math.ceil`, including for negatives: `.rounded(.up)` is toward +∞, NOT
    // `.toNearestOrAwayFromZero`. Epoch ms fit exactly in a Double (< 2^53), so
    // this is lossless.
    let daysLeft = Int((Double(endsAt - t) / Double(dayMs)).rounded(.up))
    return daysLeft > 0 ? .trial(daysLeft: daysLeft, endsAt: endsAt) : .expired
}

/// Can a new exercise round be started? The only gate the child ever feels.
///
/// NB: this is a BLACKLIST of one, and it must stay one. Never invert it into a
/// whitelist (`e == .paid || e == .trial`) — that is exactly the refactor that
/// breaks invariant 11, because a future fifth case would default to locked.
/// `.unknown` plays.
public func canPlay(_ e: Entitlement) -> Bool {
    e != .expired
}

/// Advance the high-water mark. Call whenever the license is touched.
public func withClock(_ state: LicenseState, _ now: Int64) -> LicenseState {
    guard now > state.clockHighWater else { return state }
    var next = state
    next.clockHighWater = now
    return next
}

/// French, parent-facing, for the trial countdown chip.
///
/// NB (money.md §1): this has ZERO call sites in the PWA, exactly like
/// `canPlay`. Ported anyway, copy byte for byte — em dash U+2014 with ordinary
/// ASCII spaces around it, ASCII apostrophe in « d'essai ».
public func trialNotice(_ e: Entitlement) -> String? {
    guard case .trial(let daysLeft, _) = e else { return nil }
    if daysLeft == 1 { return "Dernier jour d'essai" }
    return "Essai gratuit — \(daysLeft) jours restants"
}
