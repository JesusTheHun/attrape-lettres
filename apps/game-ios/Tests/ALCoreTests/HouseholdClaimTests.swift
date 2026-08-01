import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* The rule two devices apply without talking to each other. If it is not a     */
/* total order they disagree forever, each overwriting the other on every       */
/* launch, and a family watches their two phones argue.                         */
/* -------------------------------------------------------------------------- */

private func claim(_ id: String, at: Millis, by: String) -> HouseholdClaim {
    HouseholdClaim(id: id, rev: Rev(at: at, by: by))
}

private let alpha = "1111aaaa-2222-4bbb-8ccc-333344445555"
private let omega = "9999ffff-8888-4eee-8ddd-777766665555"

@Suite("HouseholdClaim — a deliberate join wins")
struct HouseholdClaimIntentTests {

    @Test("scanning stamps now, so the scanned household wins")
    func scanWins() {
        let existing = claim(alpha, at: 1_000, by: "this-phone")
        let scanned = claim(omega, at: 2_000, by: "this-phone")
        #expect(HouseholdClaim.winner(existing, scanned)?.id == omega)
        #expect(HouseholdClaim.winner(scanned, existing)?.id == omega)
    }

    @Test("a scan beats a lower id, which the no-intent rule would have picked")
    func intentBeatsTheDeterministicRule() {
        // The point of the stamp. `omega > alpha`, so on stamps alone the
        // iCloud tie-break would drag this device back to `alpha` and quietly
        // undo what the parent just did.
        let icloud = claim(alpha, at: 0, by: "")
        let scanned = claim(omega, at: 5, by: "this-phone")
        #expect(HouseholdClaim.winner(icloud, scanned)?.id == omega)
    }

    @Test("nothing to pick from stays nothing")
    func bothAbsent() {
        #expect(HouseholdClaim.winner(nil, nil) == nil)
    }

    @Test("a device with no household adopts the one that arrives")
    func adoptsWhenEmpty() {
        let arriving = claim(alpha, at: 7, by: "ipad")
        #expect(HouseholdClaim.winner(nil, arriving) == arriving)
        #expect(HouseholdClaim.winner(arriving, nil) == arriving)
    }
}

@Suite("HouseholdClaim — two devices must agree alone")
struct HouseholdClaimConvergenceTests {

    /// Every claim that could plausibly meet another, including the equal
    /// stamps that a fresh install and an unstamped legacy household produce.
    private static let population: [HouseholdClaim] = [
        claim(alpha, at: 0, by: ""),
        claim(omega, at: 0, by: ""),
        claim(alpha, at: 0, by: "phone-a"),
        claim(omega, at: 0, by: "phone-b"),
        claim(alpha, at: 100, by: "phone-a"),
        claim(omega, at: 100, by: "phone-a"),
        claim(alpha, at: 100, by: "phone-b"),
        claim(omega, at: 900, by: "ipad"),
    ]

    @Test("the answer does not depend on which device asks first")
    func commutative() {
        for a in Self.population {
            for b in Self.population {
                #expect(
                    HouseholdClaim.winner(a, b) == HouseholdClaim.winner(b, a),
                    "\(a) vs \(b) resolves differently depending on the order"
                )
            }
        }
    }

    @Test("three devices reach one household whatever order they meet in")
    func associative() {
        for a in Self.population {
            for b in Self.population {
                for c in Self.population {
                    let left = HouseholdClaim.winner(HouseholdClaim.winner(a, b), c)
                    let right = HouseholdClaim.winner(a, HouseholdClaim.winner(b, c))
                    #expect(left == right, "\(a), \(b), \(c) do not converge")
                }
            }
        }
    }

    @Test("comparing a claim with itself changes nothing")
    func idempotent() {
        for a in Self.population {
            #expect(HouseholdClaim.winner(a, a) == a)
        }
    }

    @Test("two unstamped households resolve to the lower id, on both devices")
    func deterministicWithoutStamps() {
        // The iCloud case: each device minted a household offline, neither
        // stamp means anything, and there is no round trip to break the tie.
        let a = claim(alpha, at: 0, by: "")
        let b = claim(omega, at: 0, by: "")
        #expect(HouseholdClaim.winner(a, b)?.id == alpha)
        #expect(HouseholdClaim.winner(b, a)?.id == alpha)
    }
}
