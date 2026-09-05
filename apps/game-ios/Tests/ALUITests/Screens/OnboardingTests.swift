import Foundation
import Testing

import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `src/components/Onboarding.tsx`, asserted against the TypeScript.            */
/*                                                                             */
/* `Onboarding.swift` shipped in the previous run untested. Every string and    */
/* every number below was copied out of the TSX (and out of                     */
/* `src/licensing/entitlement.ts` for `TRIAL_DAYS` / `UNLOCK_PRICE_EUR`), never */
/* out of `Copy.swift` or `Onboarding.swift` — an assertion sourced from the    */
/* code under test proves nothing.                                             */
/*                                                                             */
/* ```tsx                                                                       */
/* const PRICE = UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",");   // "11,99"    */
/* const scope = ios ? "pour toute la famille" : "sur vos appareils";           */
/* const [analytics, setAnalytics] = useState(false);                           */
/*                                                                              */
/* const start = () => {                                                        */
/*   setConsent(analytics);                                                     */
/*   track("trial_started", { daysLeft: TRIAL_DAYS });                          */
/*   beginTrial();                                                              */
/* };                                                                           */
/* ```                                                                          */
/* -------------------------------------------------------------------------- */

// The copy, retyped from the TSX with JSX's whitespace collapsed (source line
// breaks inside a text node become a single space).

private let tsTitle = "Nous aussi, on est parents."
private let tsScopeIOS = "pour toute la famille"
private let tsScopeAndroid = "sur vos appareils"

private func tsTrialParagraph(days: Int, price: String, scope: String) -> String {
    "Attrape-Lettres est gratuit pendant \(days) jours. Ensuite, un achat unique de "
        + "\(price) débloque tout \(scope), pour toujours. Pas d'abonnement, pas de "
        + "publicité, rien à acheter dans le jeu."
}

private func tsPauseParagraph(days: Int) -> String {
    "Après \(days) jours, les exercices se mettent en pause. Les progrès, les étoiles "
        + "et les mascottes sont gardés."
}

private let tsNoStoreParagraph =
    "Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de "
    + "compte, rien à acheter — et tout fonctionne sans connexion."

private let tsConsentTitle = "Nous aider à améliorer le jeu"
private let tsConsentBody =
    "On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le prénom "
    + "de votre enfant, jamais rien qui l'identifie. Vous pouvez changer d'avis à tout moment."

/// `TRIAL_DAYS = 7` and `UNLOCK_PRICE_EUR = 11.99` in `licensing/entitlement.ts`.
private let tsTrialDays = 7
private let tsFallbackPrice = "11,99 €"

// MARK: - emphasisRuns

@Suite("Onboarding — the <strong> splitter")
struct EmphasisRunsTests {

    private func flatten(_ runs: [EmphasisRun]) -> String {
        runs.map(\.text).joined()
    }

    @Test("the runs concatenate back to the sentence, unchanged")
    func lossless() {
        let full = tsTrialParagraph(days: tsTrialDays, price: tsFallbackPrice, scope: tsScopeIOS)
        let runs = emphasisRuns(full, bold: ["\(tsTrialDays) jours", tsFallbackPrice])
        #expect(flatten(runs) == full)
    }

    @Test("exactly the TSX's two spans are bold, and nothing else is")
    func boldSpans() {
        // `<strong>{TRIAL_DAYS} jours</strong>` and `<strong>{priceLabel ?? …}</strong>`.
        let full = tsTrialParagraph(days: tsTrialDays, price: tsFallbackPrice, scope: tsScopeIOS)
        let runs = emphasisRuns(full, bold: ["\(tsTrialDays) jours", tsFallbackPrice])
        #expect(runs.filter(\.bold).map(\.text) == ["\(tsTrialDays) jours", tsFallbackPrice])
    }

    @Test("runs come back in document order even when the needles are listed backwards")
    func documentOrder() {
        let full = tsTrialParagraph(days: tsTrialDays, price: tsFallbackPrice, scope: tsScopeIOS)
        let forwards = emphasisRuns(full, bold: ["\(tsTrialDays) jours", tsFallbackPrice])
        let backwards = emphasisRuns(full, bold: [tsFallbackPrice, "\(tsTrialDays) jours"])
        #expect(forwards == backwards)
    }

    @Test("each emphasised span occurs exactly once in the sentence it emphasises")
    func needlesAreUnambiguous() {
        // The splitter bolds every occurrence it finds. That is only equivalent
        // to the TSX because the TSX's sentence contains each span once — so
        // this is the property that makes the equivalence hold, asserted rather
        // than assumed. It also guards a future price label like "14,00 €".
        for days in [7, 14, 30] {
            for price in [tsFallbackPrice, "4,99 €", "£8.99"] {
                let full = tsTrialParagraph(days: days, price: price, scope: tsScopeIOS)
                for needle in ["\(days) jours", price] {
                    let hits = full.ranges(of: needle).count
                    #expect(
                        hits == 1,
                        Comment(rawValue: "« \(needle) » appears \(hits)× in the trial paragraph"))
                }
            }
        }
    }

    @Test("a sentence with no emphasis is one plain run")
    func noEmphasis() {
        let runs = emphasisRuns(tsNoStoreParagraph, bold: [])
        #expect(runs.count == 1)
        #expect(runs[0].bold == false)
        #expect(runs[0].text == tsNoStoreParagraph)
    }

    @Test("a needle that is not there changes nothing")
    func missingNeedle() {
        let runs = emphasisRuns(tsPauseParagraph(days: 14), bold: ["9,99 €"])
        #expect(runs == [EmphasisRun(text: tsPauseParagraph(days: 14), bold: false)])
    }

    @Test("emphasis at the very start emits no empty leading run")
    func leadingEmphasis() {
        let runs = emphasisRuns("14 jours de jeu", bold: ["14 jours"])
        #expect(runs == [
            EmphasisRun(text: "14 jours", bold: true),
            EmphasisRun(text: " de jeu", bold: false),
        ])
    }

    @Test("emphasis at the very end emits no empty trailing run")
    func trailingEmphasis() {
        let runs = emphasisRuns("gratuit pendant 14 jours", bold: ["14 jours"])
        #expect(runs == [
            EmphasisRun(text: "gratuit pendant ", bold: false),
            EmphasisRun(text: "14 jours", bold: true),
        ])
    }

    @Test("an empty needle is ignored rather than looping forever")
    func emptyNeedle() {
        let runs = emphasisRuns("Commencer", bold: ["", "menc"])
        #expect(flatten(runs) == "Commencer")
        #expect(runs.filter(\.bold).map(\.text) == ["menc"])
    }
}

// MARK: - OnboardingPlan

@Suite("Onboarding — what the screen says")
struct OnboardingPlanTests {

    private func text(_ plan: OnboardingPlan, _ index: Int) -> String {
        plan.paragraphs[index].map(\.text).joined()
    }

    @Test("with a store: two paragraphs, the trial terms then the pause note")
    func storeAvailable() {
        let plan = OnboardingPlan(storeAvailable: true, priceLabel: nil)
        #expect(plan.paragraphs.count == 2)
        #expect(text(plan, 0) == tsTrialParagraph(days: tsTrialDays, price: tsFallbackPrice, scope: tsScopeIOS))
        #expect(text(plan, 1) == tsPauseParagraph(days: tsTrialDays))
        #expect(plan.buttonTitle == "Commencer les \(tsTrialDays) jours")
    }

    @Test("the terms disclose duration, what stops, and the charge — App Review 3.1.1")
    func disclosesTheThreeThings() {
        // The three facts 3.1.1 requires BEFORE a trial starts, each in the copy
        // that renders above the button.
        let plan = OnboardingPlan(storeAvailable: true, priceLabel: nil)
        let all = plan.paragraphs.map { $0.map(\.text).joined() }.joined(separator: " ")
        #expect(all.contains("\(tsTrialDays) jours"))       // duration
        #expect(all.contains("se mettent en pause"))  // what stops working
        #expect(all.contains(tsFallbackPrice))        // the eventual charge
    }

    @Test("the store's own label wins over the computed fallback")
    func storeLabelWins() {
        // `{priceLabel ?? `${PRICE} €`}` — a localised label replaces the euro
        // string everywhere it appears, including inside the bold run.
        let plan = OnboardingPlan(storeAvailable: true, priceLabel: "£8.99")
        #expect(plan.price == "£8.99")
        #expect(text(plan, 0) == tsTrialParagraph(days: tsTrialDays, price: "£8.99", scope: tsScopeIOS))
        #expect(plan.paragraphs[0].filter(\.bold).map(\.text) == ["\(tsTrialDays) jours", "£8.99"])
        #expect(!text(plan, 0).contains(tsFallbackPrice))
    }

    @Test("the fallback price is UNLOCK_PRICE_EUR.toFixed(2) with a French comma")
    func fallbackPrice() {
        // 11.99 → "11.99" → "11,99", plus " €".
        #expect(OnboardingPlan(storeAvailable: true, priceLabel: nil).price == tsFallbackPrice)
        // The two-decimal fix matters: a round price must still show its cents.
        #expect(
            OnboardingPlan(storeAvailable: true, priceLabel: nil, priceEUR: 5).price == "5,00 €")
        #expect(
            OnboardingPlan(storeAvailable: true, priceLabel: nil, priceEUR: 12.5).price == "12,50 €")
    }

    @Test("without a store: one paragraph and a bare « Commencer »")
    func noStore() {
        let plan = OnboardingPlan(storeAvailable: false, priceLabel: nil)
        #expect(plan.paragraphs.count == 1)
        #expect(text(plan, 0) == tsNoStoreParagraph)
        #expect(plan.paragraphs[0].allSatisfy { !$0.bold })
        #expect(plan.buttonTitle == "Commencer")
        // No price is shown, but `PRICE` is still computed at module scope in
        // the TSX, so the resolved value is not conditional.
        #expect(plan.price == tsFallbackPrice)
    }

    @Test("the no-store branch promises nothing about money")
    func noStoreBranchMentionsNoPrice() {
        let plan = OnboardingPlan(storeAvailable: false, priceLabel: "£8.99")
        #expect(!text(plan, 0).contains("£8.99"))
        #expect(!text(plan, 0).contains("jours"))
    }

    @Test("the copy is built from TRIAL_DAYS, not from a hard-coded 14")
    func daysDriveTheCopy() {
        let plan = OnboardingPlan(storeAvailable: true, priceLabel: nil, days: 7)
        #expect(text(plan, 0) == tsTrialParagraph(days: 7, price: tsFallbackPrice, scope: tsScopeIOS))
        #expect(text(plan, 1) == tsPauseParagraph(days: 7))
        #expect(plan.buttonTitle == "Commencer les 7 jours")
        #expect(plan.paragraphs[0].filter(\.bold).map(\.text) == ["7 jours", tsFallbackPrice])
    }

    @Test("the default day count is TRIAL_DAYS = 14")
    func defaultDays() {
        #expect(
            OnboardingPlan(storeAvailable: true, priceLabel: nil).buttonTitle
                == "Commencer les \(tsTrialDays) jours")
    }

    @Test("scope: Family Sharing on iOS, per-device elsewhere")
    func scope() {
        // Apple's Family Sharing really does cover the household on the €11,99
        // non-consumable; Play's Family Library does not share IAPs, so the two
        // strings are different promises and must not be unified.
        let apple = OnboardingPlan(storeAvailable: true, priceLabel: nil, scope: tsScopeIOS)
        let other = OnboardingPlan(storeAvailable: true, priceLabel: nil, scope: tsScopeAndroid)
        #expect(text(apple, 0).contains("débloque tout \(tsScopeIOS), pour toujours"))
        #expect(text(other, 0).contains("débloque tout \(tsScopeAndroid), pour toujours"))
        #expect(text(apple, 0) != text(other, 0))
    }

    @Test("the title and the consent wording are the TSX's, character for character")
    func fixedCopy() {
        #expect(Copy.Onboarding.title == tsTitle)
        #expect(Copy.Onboarding.consentTitle == tsConsentTitle)
        #expect(Copy.Onboarding.consentBody == tsConsentBody)
        // The consent promise is the one this port must never make false.
        #expect(tsConsentBody.contains("Jamais le prénom de votre enfant"))
    }
}

// MARK: - start()

@Suite("Onboarding — start()")
@MainActor
struct StartOnboardingTests {

    private struct Rig {
        let kv: InMemoryKVStore
        let transport: RecordingTransport
        let telemetry: Telemetry
        let entitlement: EntitlementModel
        let time: MutableTimeSource
    }

    private static let t0: Int64 = 1_700_000_000_000

    private func rig(storeAvailable: Bool = true, reachable: Bool = true) -> Rig {
        let kv = InMemoryKVStore()
        let transport = RecordingTransport()
        let telemetry = Telemetry(
            endpoint: "https://t.test",
            transport: transport,
            kv: kv,
            appVersion: FixedAppVersion("1.2.3"))
        let time = MutableTimeSource(Self.t0)
        let entitlement = EntitlementModel(
            store: PreviewPurchaseStore(
                available: storeAvailable, paid: false, trialStartedAt: nil,
                reachable: reachable, price: nil),
            persist: LicenseStore(kv),
            time: time)
        return Rig(
            kv: kv, transport: transport, telemetry: telemetry, entitlement: entitlement,
            time: time)
    }

    @Test("the consent control starts OFF — CJEU Planet49, and useState(false)")
    func consentStartsOff() {
        // `useState(false)`, never `useState(hasConsent())`. Held as a named
        // constant so a change that seeded it from anything at all shows up here.
        #expect(OnboardingView.initialConsent == false)
    }

    @Test("setConsent runs BEFORE track, so the very first event respects the answer")
    func consentIsRecordedFirst() async {
        // The discriminator: consent is unanswered at entry, so if `track` ran
        // first it would see `hasConsent == false` and drop `trial_started` on
        // the floor. One event on the wire proves the order.
        let r = rig()
        #expect(r.telemetry.consentAnswered == false)

        startOnboarding(analytics: true, telemetry: r.telemetry, entitlement: r.entitlement)
        r.telemetry.flush()
        await r.telemetry.awaitPendingSends()

        #expect(r.telemetry.hasConsent == true)
        #expect(r.transport.sent.count == 1)
        let sent = r.transport.sent[0]
        #expect(sent.url.absoluteString == "https://t.test/events")
        #expect(sent.json.contains("\"event\":\"trial_started\""))
    }

    @Test("a refusal is recorded and the event never leaves the device")
    func refusalDropsTheEvent() async {
        let r = rig()
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        r.telemetry.flush()
        await r.telemetry.awaitPendingSends()

        #expect(r.telemetry.consentAnswered == true)  // refused ≠ unanswered
        #expect(r.telemetry.hasConsent == false)
        #expect(r.transport.sent.isEmpty)
    }

    @Test("declining costs the parent nothing — the trial starts either way")
    func refusalStillStartsTheTrial() {
        // "« Commencer » carries no hidden opt-in and declining costs the parent
        // nothing": `beginTrial()` is the last statement of `start()` and is not
        // conditional on the checkbox.
        for analytics in [true, false] {
            let r = rig()
            #expect(r.entitlement.onboarded == false)
            startOnboarding(
                analytics: analytics, telemetry: r.telemetry, entitlement: r.entitlement)
            #expect(r.entitlement.onboarded == true)
            #expect(r.entitlement.license.trialStartedAt == Self.t0)
        }
    }

    @Test("the onboarded flag is persisted under the PWA's own key (D7)")
    func onboardedIsPersisted() {
        let r = rig()
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        // `attrape-lettres:onboarded:v1` — a migration contract with the shipped
        // PWA, preserved byte for byte.
        #expect(r.kv.string("attrape-lettres:onboarded:v1") == "1")
    }

    @Test("invariant 11: an unreachable store still leaves the family playing")
    func unreachableStoreStillStartsTheTrial() {
        // Money never fails closed. With the store unreachable there is no
        // authoritative trial date, so the LOCAL stamp has to stand — otherwise
        // a flat network on first launch means a child who can never start.
        let r = rig(reachable: false)
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        #expect(r.entitlement.onboarded == true)
        #expect(r.entitlement.license.trialStartedAt == Self.t0)
        if case .trial = r.entitlement.entitlement {
            // good: playable
        } else {
            Issue.record("an unreachable store must resolve to a running trial, not a lock")
        }
    }

    @Test("invariant 11: no store at all still leaves the family playing")
    func noStoreStillStartsTheTrial() {
        let r = rig(storeAvailable: false)
        #expect(r.entitlement.storeAvailable == false)
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        #expect(r.entitlement.onboarded == true)
        #expect(r.entitlement.license.trialStartedAt == Self.t0)
    }

    @Test("invariant 10: the only property on the wire is daysLeft, and it is a number")
    func telemetryCarriesOneNumber() async {
        // `track("trial_started", { daysLeft: TRIAL_DAYS })`. `TelemetryProps`
        // has no String field at all (D12), so there is no parameter a name
        // could ride in — this asserts the byte-level consequence.
        let r = rig()
        startOnboarding(analytics: true, telemetry: r.telemetry, entitlement: r.entitlement)
        r.telemetry.flush()
        await r.telemetry.awaitPendingSends()

        let json = r.transport.sent[0].json
        #expect(json.contains("\"daysLeft\":\(tsTrialDays)"))
        for key in TelemetryProps.allowedKeys where key != "daysLeft" {
            #expect(
                !json.contains("\"\(key)\""),
                Comment(rawValue: "trial_started must carry daysLeft alone; found \(key)"))
        }
        // The app version rides the envelope; nothing else does.
        #expect(
            json
                == "{\"v\":\"1.2.3\",\"events\":[{\"event\":\"trial_started\",\"props\":{\"daysLeft\":\(tsTrialDays)}}]}"
        )
    }

    @Test("a second Commencer cannot re-start the clock")
    func trialStartIsNotOverwritten() {
        // `next.trialStartedAt = license.trialStartedAt ?? now` — an existing
        // stamp is never replaced, so a reinstall or a double tap buys nothing.
        let r = rig()
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        r.time.advance(days: 9)
        startOnboarding(analytics: false, telemetry: r.telemetry, entitlement: r.entitlement)
        #expect(r.entitlement.license.trialStartedAt == Self.t0)
    }
}
