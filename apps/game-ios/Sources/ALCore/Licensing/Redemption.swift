import Foundation

/* -------------------------------------------------------------------------- */
/* The redemption seam.                                                        */
/*                                                                             */
/* Same shape as `PurchaseStore`, for the same reason: the whole entitlement    */
/* machine has to run under `swift test` on a Mac with no network. The real     */
/* adapter is `ALPlatform.URLSessionRedemptionTransport`.                       */
/*                                                                             */
/* ── INVARIANT 11 LIVES IN THE RETURN TYPE. ──────────────────────────────────*/
/*                                                                             */
/* `redeem` does not throw, and there is no `Result`. Every network failure,    */
/* every timeout, every 5xx and every response nobody anticipated maps to       */
/* `.unreachable`, and `.unreachable` changes NOTHING on the device: not the    */
/* licence, not the entitlement, not the trial clock. A family that is playing  */
/* keeps playing; a family that redeemed last week stays unlocked.              */
/*                                                                             */
/* The four business answers are the only ones that touch anything, and only    */
/* one of them — `.granted` — is a grant. That asymmetry is the point: a code   */
/* can turn the game on and no answer from this endpoint can turn it off.       */
/* -------------------------------------------------------------------------- */

/// What the server said. Deliberately closed and deliberately small.
public enum RedemptionAnswer: Equatable, Sendable {
    /// Unlocked, as of this instant (epoch ms, the SERVER's clock).
    case granted(at: Int64)
    /// The family already held a grant. Same effect, and the code kept its uses.
    case already(at: Int64)
    /// No such code. A typo that happened to pass the checksum, or an old card.
    case unknown
    /// Every use of this code is spent.
    case exhausted
    /// The code has lapsed.
    case expired
    /// **Nothing was learnt.** Never render this as a refusal.
    case unreachable
}

extension RedemptionAnswer {
    /// The two answers that unlock. Written once so no screen has to remember
    /// that `already` is a success.
    public var isGrant: Bool {
        switch self {
        case .granted, .already: return true
        case .unknown, .exhausted, .expired, .unreachable: return false
        }
    }

    /// When the grant started, for the two cases that carry one.
    public var grantedAt: Int64? {
        switch self {
        case .granted(let at), .already(let at): return at
        case .unknown, .exhausted, .expired, .unreachable: return nil
        }
    }
}

/**
 * The transport. One method, non-throwing, total.
 *
 * `code` is already normalised and checksummed by `RedemptionCode.accept` —
 * the transport does not validate, it posts. `household` is the family's opaque
 * sync id, which is the only thing this request carries besides the code:
 * no account, no e-mail, no device id, no child.
 */
public protocol RedemptionTransport: Sendable {
    func redeem(code: String, household: String) async -> RedemptionAnswer
}

/// The build with no endpoint. Answers `.unreachable`, which changes nothing —
/// the same fail-open default `StubPurchaseStore` is.
public struct UnavailableRedemptionTransport: RedemptionTransport {
    public init() {}
    public func redeem(code: String, household: String) async -> RedemptionAnswer {
        .unreachable
    }
}
