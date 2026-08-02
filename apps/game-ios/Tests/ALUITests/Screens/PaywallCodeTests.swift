import Foundation
import Testing

import ALCore

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The redemption layer of the paywall.                                        */
/*                                                                             */
/* Two claims, both structural rather than cosmetic:                           */
/*                                                                             */
/*  1. THE CODE FIELD IS BEHIND THE GATE. It is reachable only from `.parent`,  */
/*     which is only reachable through `ParentalGateView`. A child cannot type  */
/*     into it, and — more to the point — a child cannot reach a screen that    */
/*     looks like it wants something from them.                                 */
/*  2. NOTHING BUT A GRANT CHANGES ANYTHING. The screen sets a note on every    */
/*     answer, and the note is all it sets. The licence is written by           */
/*     `EntitlementModel.redeem` and by nothing here, which is why an           */
/*     unreachable server leaves the family exactly as playable as they were.   */
/* -------------------------------------------------------------------------- */

private let t0: Int64 = 1_700_000_000_000
private let goodCode = "0123456789A" + String(RedemptionCode.checkSymbol("0123456789A")!)

private struct SilentStore: PurchaseStore {
    var available = true
    func refresh() async -> StoreSnapshot { .unreachable }
    func beginTrial() async -> Int64? { nil }
    func purchase() async -> Bool { false }
    func restore() async -> Bool { false }
    func priceLabel() async -> String? { nil }
}

private struct FixedRedemption: RedemptionTransport {
    let answer: RedemptionAnswer
    func redeem(code: String, household: String) async -> RedemptionAnswer { answer }
}

@MainActor
private func entitlement(
    _ answer: RedemptionAnswer,
    license: LicenseState = LicenseState(trialStartedAt: t0 - 30 * dayMs)
) -> EntitlementModel {
    let persist = LicenseStore(InMemoryKVStore())
    persist.save(license)
    return EntitlementModel(
        store: SilentStore(),
        persist: persist,
        time: MutableTimeSource(t0),
        redemption: FixedRedemption(answer: answer),
        household: { "house-1" }
    )
}

@Suite("Getting to the code field")
@MainActor
struct PaywallCodeNavigationTests {

    @Test("the only way in is from the parent layer, which is behind the gate")
    func reachableOnlyFromParent() {
        let model = PaywallModel()
        #expect(model.step == .child)
        // A child can reach `.gate` and no further.
        model.askForAnAdult()
        #expect(model.step == .gate)
        // `askForCode` is not on the child's path; the parent layer is where the
        // link lives, and only `gatePassed` puts anybody there.
        model.gateCancelled()
        #expect(model.step == .child)
    }

    @Test("« Retour » goes back to the price, keeping nothing")
    func cancelClears() {
        let model = PaywallModel(step: .parent)
        model.askForCode()
        #expect(model.step == .code)
        model.type("7fq4m2xb9kdb")
        #expect(!model.code.isEmpty)
        model.cancelCode()
        #expect(model.step == .parent)
        #expect(model.code.isEmpty)
        #expect(model.note == nil)
    }

    @Test("the field holds only what a code can contain")
    func fieldSanitises() {
        let model = PaywallModel(step: .code)
        model.type("7fq4 m2xb-9kdb")
        #expect(model.code == "7FQ4-M2XB-9KDB")
        model.type("é🎉")
        #expect(model.code == "")
    }

    @Test("« Valider » wakes up at twelve symbols, checksum or not")
    func canSubmit() {
        let model = PaywallModel(step: .code)
        #expect(!model.canSubmit)
        model.type("7FQ4-M2XB-9KD")
        #expect(!model.canSubmit)
        model.type("7FQ4-M2XB-9KDB")
        // Deliberately live even on a wrong checksum: a dead button with no
        // explanation is worse than a refusal that names the problem.
        #expect(model.canSubmit)
    }
}

@Suite("What each answer says, and what it changes")
@MainActor
struct PaywallCodeOutcomeTests {

    @Test("a grant says so, clears the field and unlocks")
    func granted() async {
        let model = PaywallModel(step: .code)
        let money = entitlement(.granted(at: t0))
        model.type(goodCode)

        await model.submitCode(entitlement: money)

        #expect(model.note == Copy.Paywall.CodeNote.granted)
        #expect(model.code.isEmpty)
        #expect(!model.busy)
        #expect(money.entitlement == .paid)
        // The screen does not navigate: the router re-reads the entitlement and
        // the paywall is simply no longer rendered.
        #expect(model.step == .code)
    }

    @Test("« déjà débloqué » is a success, not a refusal")
    func already() async {
        let model = PaywallModel(step: .code)
        let money = entitlement(.already(at: t0 - dayMs))
        model.type(goodCode)

        await model.submitCode(entitlement: money)

        #expect(model.note == Copy.Paywall.CodeNote.already)
        #expect(money.entitlement == .paid)
    }

    @Test(
        "every refusal leaves the family exactly as playable as it was",
        arguments: [
            (RedemptionAnswer.unknown, Copy.Paywall.CodeNote.unknown),
            (.exhausted, Copy.Paywall.CodeNote.exhausted),
            (.expired, Copy.Paywall.CodeNote.expired),
            (.unreachable, Copy.Paywall.CodeNote.unreachable),
        ]
    )
    func refusals(_ answer: RedemptionAnswer, _ expected: String) async {
        // Mid-trial, so a regression that clobbers the licence is visible as a
        // change in what the child may do rather than as a field diff.
        let license = LicenseState(trialStartedAt: t0 - 3 * dayMs)
        let model = PaywallModel(step: .code)
        let money = entitlement(answer, license: license)
        let before = money.entitlement
        model.type(goodCode)

        await model.submitCode(entitlement: money)

        #expect(model.note == expected)
        #expect(money.entitlement == before)
        #expect(money.license == license)
        // The code stays in the field — a parent who mistyped one character
        // should not retype twelve.
        #expect(!model.code.isEmpty)
        #expect(!model.busy)
    }

    @Test("an unreachable server never reads as a failure")
    func unreachableIsNotAnError() async {
        let model = PaywallModel(step: .code)
        model.type(goodCode)
        await model.submitCode(entitlement: entitlement(.unreachable))

        let note = try! #require(model.note)
        // The wording is checked because this is the sentence a parent reads
        // when nothing worked, and « échec » / « erreur » is what invariant 11
        // exists to keep out of it.
        #expect(!note.lowercased().contains("échec"))
        #expect(!note.lowercased().contains("erreur"))
        #expect(note.contains("rien n'a changé"))
    }

    @Test("typing again clears the previous answer")
    func typingClearsNote() async {
        let model = PaywallModel(step: .code)
        model.type(goodCode)
        await model.submitCode(entitlement: entitlement(.exhausted))
        #expect(model.note != nil)
        model.type(goodCode + "X")
        #expect(model.note == nil)
    }

    @Test("a redemption emits no telemetry event")
    func noTelemetry() {
        // There is no `code_redeemed` in `TelemetryEvent`, and there must not be
        // one added by reflex: the closed list is the allowlist (D12), and a
        // redemption is not one of the eleven the PWA authored.
        #expect(!TelemetryEvent.allCases.contains { $0.rawValue.contains("code") })
        #expect(!TelemetryEvent.allCases.contains { $0.rawValue.contains("redeem") })
    }
}
