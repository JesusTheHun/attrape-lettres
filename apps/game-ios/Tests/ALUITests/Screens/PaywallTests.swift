import Foundation
import SwiftUI
import Testing

import ALCore

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `Screens/Paywall.swift` against `src/components/Paywall.tsx`, plus           */
/* `src/licensing/entitlement.ts` and `useEntitlement.tsx` for the money.       */
/*                                                                             */
/* Two things are being proved here, and the second is the one that matters:    */
/*                                                                             */
/*  1. the screen is the TSX — the three layers, the two handlers, the four     */
/*     events, the copy;                                                        */
/*  2. INVARIANT 11 — money never fails closed. No store state and no clock     */
/*     value reachable through this screen turns a paying or mid-trial family's */
/*     game off, and nothing the screen does can downgrade an entitlement.      */
/* -------------------------------------------------------------------------- */

// MARK: - Doubles

/// A `PurchaseStore` whose every answer is dictated by the test. The fail-open
/// mapping (`PurchaseStore`'s normative table) means a real adapter can only
/// ever produce values in this range: `.unreachable`, `nil`, `false`.
private struct FakeStore: PurchaseStore {
    var available = true
    var snapshot: StoreSnapshot = .unreachable
    var trialStart: Int64?
    var purchaseResult = false
    var restoreResult = false
    var price: String?

    func refresh() async -> StoreSnapshot { snapshot }
    func beginTrial() async -> Int64? { trialStart }
    func purchase() async -> Bool { purchaseResult }
    func restore() async -> Bool { restoreResult }
    func priceLabel() async -> String? { price }
}

/// A store that runs a hook *inside* the await, so a test can look at the model
/// while the purchase is in flight.
private final class HookedStore: PurchaseStore, @unchecked Sendable {
    var available = true
    var result = false
    var duringPurchase: (@Sendable () async -> Void)?

    func refresh() async -> StoreSnapshot { .unreachable }
    func beginTrial() async -> Int64? { nil }
    func purchase() async -> Bool {
        await duringPurchase?()
        return result
    }
    func restore() async -> Bool {
        await duringPurchase?()
        return result
    }
    func priceLabel() async -> String? { nil }
}

/// A `Telemetry` posting into a `RecordingTransport`, with consent granted so
/// `track` actually enqueues.
@MainActor
private final class TelemetrySpy {
    let transport = RecordingTransport()
    let telemetry: Telemetry

    init(consent: Bool = true) {
        telemetry = Telemetry(
            endpoint: "https://t.test",
            transport: transport,
            kv: InMemoryKVStore(),
            appVersion: FixedAppVersion("1.0.0"))
        telemetry.setConsent(consent)
    }

    /// Flush and read back the event names, in order.
    func drain() async -> [String] {
        telemetry.flush()
        await telemetry.awaitPendingSends()
        var names: [String] = []
        for sent in transport.sent {
            guard
                let object = try? JSONSerialization.jsonObject(with: sent.body) as? [String: Any],
                let events = object["events"] as? [[String: Any]]
            else { continue }
            names.append(contentsOf: events.compactMap { $0["event"] as? String })
        }
        transport.clear()
        return names
    }
}

private let t0: Int64 = 1_700_000_000_000

@MainActor
private func makeEntitlement(
    store: PurchaseStore = FakeStore(),
    license: LicenseState = .blank,
    time: MutableTimeSource
) -> EntitlementModel {
    let persist = LicenseStore(InMemoryKVStore())
    persist.save(license)
    return EntitlementModel(store: store, persist: persist, time: time)
}

// MARK: - The three layers

@MainActor
@Suite struct PaywallStepTests {

    /// `useState<Step>("child")` — a child is what the screen assumes it is
    /// looking at, always.
    @Test func startsOnTheChildLayer() {
        #expect(PaywallModel().step == .child)
    }

    /// « Je suis un adulte » only changes the step. Critically it does NOT emit
    /// `paywall_shown`: a child tapping around is not a paywall impression, and
    /// the price is still one gate away.
    @Test func askingForAnAdultOpensTheGateAndTracksNothing() async {
        let spy = TelemetrySpy()
        let model = PaywallModel()
        model.askForAnAdult()
        #expect(model.step == .gate)
        #expect(await spy.drain() == [])
    }

    /// `onPass={() => { setStep("parent"); track("paywall_shown"); }}`.
    @Test func passingTheGateShowsThePriceAndTracksTheImpression() async {
        let spy = TelemetrySpy()
        let model = PaywallModel()
        model.askForAnAdult()
        model.gatePassed(telemetry: spy.telemetry)
        #expect(model.step == .parent)
        #expect(await spy.drain() == ["paywall_shown"])
    }

    /// `onCancel={() => setStep("child")}` — the gate's « Annuler » returns to
    /// the calm screen, never out of the app, and never to a locked one.
    @Test func cancellingTheGateReturnsToTheChildLayer() async {
        let spy = TelemetrySpy()
        let model = PaywallModel()
        model.askForAnAdult()
        model.gateCancelled()
        #expect(model.step == .child)
        #expect(await spy.drain() == [])
    }

    /// Invariant 11's navigational half: the three layers form a closed loop
    /// with no dead end. The gate always gives the child screen back, and the
    /// child screen is always one tap from the mascot.
    @Test func theLayersFormAClosedLoop() async {
        let spy = TelemetrySpy()
        let model = PaywallModel()
        #expect(model.step == .child)
        model.askForAnAdult()
        #expect(model.step == .gate)
        model.gateCancelled()
        #expect(model.step == .child)
        model.askForAnAdult()
        model.gatePassed(telemetry: spy.telemetry)
        #expect(model.step == .parent)
        _ = await spy.drain()
    }

    /// Neither store button nor the consent toggle may move the step. A failed
    /// purchase must leave the parent exactly where they were — never pushed
    /// into a fourth, exit-less state, and never bounced back to the child layer
    /// mid-transaction.
    @Test func nothingElseMovesTheStep() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)

        for step in PaywallStep.allCases {
            let model = PaywallModel(step: step)
            await model.buy(entitlement: entitlement, telemetry: spy.telemetry)
            await model.redo(entitlement: entitlement, telemetry: spy.telemetry)
            model.setAnalytics(true, telemetry: spy.telemetry)
            model.setAnalytics(false, telemetry: spy.telemetry)
            #expect(model.step == step, Comment(rawValue: "started at \(step.rawValue)"))
            _ = await spy.drain()
        }
        #expect(PaywallStep.allCases.count == 3)
    }
}

// MARK: - Buying

@MainActor
@Suite struct PaywallPurchaseTests {

    @Test func aSuccessfulPurchaseTracksCompletionAndLeavesNoNote() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        var store = FakeStore()
        store.purchaseResult = true
        store.snapshot = StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
        let entitlement = makeEntitlement(store: store, time: time)
        let model = PaywallModel(step: .parent)

        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(model.busy == false)
        #expect(model.note == nil)
        #expect(await spy.drain() == ["purchase_completed"])
        #expect(entitlement.entitlement == .paid)
    }

    /// « L'achat n'a pas abouti. Rien n'a été débité. » — ASCII apostrophes, as
    /// authored. It says what did NOT happen; it never blames and never locks.
    @Test func aFailedPurchaseTracksFailureAndSaysNothingWasCharged() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)
        let model = PaywallModel(step: .parent)

        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(model.busy == false)
        #expect(model.note == "L\u{0027}achat n\u{0027}a pas abouti. Rien n\u{0027}a été débité.")
        #expect(await spy.drain() == ["purchase_failed"])
    }

    /// Cancel, StoreKit error, an unverified transaction and a **pending**
    /// Ask-to-Buy all collapse to `false` in `PurchaseStore`'s normative mapping.
    /// All four therefore land here — and none of them may move the entitlement.
    @Test func noFailureModeDowngradesTheEntitlement() async {
        let spy = TelemetrySpy()
        for license in [LicenseState.blank, LicenseState(paid: true, verifiedAt: t0)] {
            let time = MutableTimeSource(t0)
            let entitlement = makeEntitlement(store: FakeStore(), license: license, time: time)
            let before = entitlement.entitlement
            let model = PaywallModel(step: .parent)

            await model.buy(entitlement: entitlement, telemetry: spy.telemetry)

            #expect(entitlement.entitlement == before)
            #expect(canPlay(entitlement.entitlement))
            _ = await spy.drain()
        }
    }

    /// `setBusy(true)` happens BEFORE the await, so both store buttons are
    /// disabled for the whole round trip — a double tap cannot start two
    /// purchases.
    @Test func busyIsTrueForTheWholeRoundTrip() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let store = HookedStore()
        let entitlement = makeEntitlement(store: store, time: time)
        let model = PaywallModel(step: .parent)
        let seen = Box()
        store.duringPurchase = { @Sendable in
            await MainActor.run { seen.value = model.busy }
        }

        #expect(model.busy == false)
        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)
        #expect(seen.value == true)
        #expect(model.busy == false)
        _ = await spy.drain()
    }

    /// `setNote(null)` is the second statement of `buy`, so a retry clears the
    /// previous outcome before it starts.
    @Test func aRetryClearsThePreviousNote() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)
        let model = PaywallModel(step: .parent)

        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)
        #expect(model.note != nil)

        var winning = FakeStore()
        winning.purchaseResult = true
        winning.snapshot = StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
        let second = makeEntitlement(store: winning, time: time)
        await model.buy(entitlement: second, telemetry: spy.telemetry)
        #expect(model.note == nil)
        #expect(await spy.drain() == ["purchase_failed", "purchase_completed"])
    }
}

// MARK: - Restoring

@MainActor
@Suite struct PaywallRestoreTests {

    /// « Achat restauré. »
    @Test func aRestoreThatFindsSomethingSaysSo() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        var store = FakeStore()
        store.restoreResult = true
        store.snapshot = StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
        let entitlement = makeEntitlement(store: store, time: time)
        let model = PaywallModel(step: .parent)

        await model.redo(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(model.note == "Achat restauré.")
        #expect(entitlement.entitlement == .paid)
        #expect(await spy.drain() == ["purchase_restored"])
    }

    /// « Aucun achat trouvé sur ce compte. » — and `purchase_restored` fires
    /// ANYWAY. That asymmetry with `buy` (which branches its event) is in the
    /// TypeScript and is deliberate: the event records the attempt.
    @Test func aRestoreThatFindsNothingStillTracksTheAttempt() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)
        let model = PaywallModel(step: .parent)

        await model.redo(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(model.note == "Aucun achat trouvé sur ce compte.")
        #expect(await spy.drain() == ["purchase_restored"])
    }

    /// The restore path is the recovery route out of every fail-open verdict, so
    /// it must never itself take something away: an unreachable store answering
    /// « nothing restored » leaves a paid family paid.
    @Test func restoringAgainstAnUnreachableStoreKeepsAPaidFamilyPaid() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0 + 3 * dayMs)
        let entitlement = makeEntitlement(
            store: FakeStore(),
            license: LicenseState(paid: true, verifiedAt: t0),
            time: time)
        let model = PaywallModel(step: .parent)

        await model.redo(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(entitlement.entitlement == .paid)
        #expect(canPlay(entitlement.entitlement))
        // …and the unreachable answer did not re-stamp the confirmation, because
        // the grace window measures time since the last *confirmation*.
        #expect(entitlement.license.verifiedAt == t0)
        _ = await spy.drain()
    }
}

// MARK: - Consent

@MainActor
@Suite struct PaywallConsentTests {

    /// `useState(hasConsent)` — the paywall SHOWS the answer already given, so
    /// the parent can withdraw it (GDPR Art. 7(3)). Contrast `Onboarding`, whose
    /// box is a hard `false` because it is asking for the first time.
    @Test func theBoxStartsAtTheAnswerAlreadyStored() {
        let granted = PaywallModel()
        granted.seedAnalytics(true)
        #expect(granted.analytics == true)

        let refused = PaywallModel()
        refused.seedAnalytics(false)
        #expect(refused.analytics == false)

        // …and it is genuinely a mirror of storage, not a constant.
        #expect(OnboardingView.initialConsent == false)
    }

    /// The seed runs once. A second appear must not undo a toggle the parent has
    /// already moved.
    @Test func theSeedDoesNotClobberAToggleTheParentMoved() {
        let spy = TelemetrySpy(consent: false)
        let model = PaywallModel()
        model.seedAnalytics(spy.telemetry.hasConsent)
        #expect(model.analytics == false)

        model.setAnalytics(true, telemetry: spy.telemetry)
        model.seedAnalytics(false)
        #expect(model.analytics == true)
    }

    /// `setAnalytics(checked); setConsent(checked);` — local state then storage.
    @Test func togglingWritesConsentThrough() {
        let spy = TelemetrySpy(consent: false)
        let model = PaywallModel()

        model.setAnalytics(true, telemetry: spy.telemetry)
        #expect(model.analytics == true)
        #expect(spy.telemetry.hasConsent == true)

        model.setAnalytics(false, telemetry: spy.telemetry)
        #expect(model.analytics == false)
        #expect(spy.telemetry.hasConsent == false)
    }

    /// Withdrawal is retroactive: `setConsent(false)` drains anything queued but
    /// not yet sent, and nothing tracked afterwards is even enqueued.
    @Test func withdrawingConsentDropsWhatWasNotSentYet() async {
        let spy = TelemetrySpy()
        let model = PaywallModel()
        model.gatePassed(telemetry: spy.telemetry)  // queues paywall_shown

        model.setAnalytics(false, telemetry: spy.telemetry)
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)
        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(await spy.drain() == [])
    }
}

// MARK: - The price

@MainActor
@Suite struct PaywallPriceTests {

    /// `const PRICE = UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")`, rendered as
    /// `${PRICE} €`. The number comes from the entitlement module, never from a
    /// literal in the screen.
    @Test func theFallbackPriceIsBuiltFromTheEntitlementConstant() {
        #expect(unlockPriceEur == 9.99)
        #expect(paywallPrice(nil) == "9,99 €")
        // …and it is genuinely computed: a different price formats the same way.
        #expect(paywallPrice(nil, fallback: 4.5) == "4,50 €")
        #expect(paywallPrice(nil, fallback: 12) == "12,00 €")
    }

    /// `priceLabel ?? …` — the store's localised label wins the moment it lands.
    @Test func theStoreLabelWinsWhenItHasAnswered() {
        #expect(paywallPrice("£8.99") == "£8.99")
        #expect(paywallPrice("9,99 €") == "9,99 €")
    }

    /// The screen never invents a price: with no store answer it shows the
    /// fallback rather than an empty string or a spinner, so the parent always
    /// knows what they are agreeing to.
    @Test func theScreenAlwaysHasAPriceToShow() async {
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(), time: time)
        #expect(entitlement.priceLabel == nil)
        #expect(paywallPrice(entitlement.priceLabel).isEmpty == false)

        var priced = FakeStore()
        priced.price = "9,99 €"
        priced.snapshot = StoreSnapshot(paid: false, trialStartedAt: nil, reachable: true)
        let answered = makeEntitlement(store: priced, time: time)
        await answered.refresh()
        #expect(paywallPrice(answered.priceLabel) == "9,99 €")
    }

    /// The copy, byte for byte against the TSX.
    @Test func theCopyIsTheAuthoredFrench() {
        #expect(Copy.Paywall.Child.moon == "🌙")
        #expect(Copy.Paywall.Child.title == "Les jeux font une pause")
        // `Demande à un grand&nbsp;!` — U+00A0 before the "!".
        #expect(
            Copy.Paywall.Child.body
                == "Demande à un grand\u{00A0}! Tes étoiles et ta mascotte t\u{0027}attendent.")
        #expect(Copy.Paywall.Child.seeCompanion == "Voir ma mascotte")
        #expect(Copy.Paywall.Child.iAmAnAdult == "Je suis un adulte")

        #expect(Copy.Paywall.Parent.title == "Débloquer Attrape-Lettres")
        #expect(
            Copy.Paywall.Parent.body(price: "9,99 €")
                == "Un achat unique de 9,99 €. Pas d\u{0027}abonnement, pas de publicité, rien d\u{0027}autre à acheter. Les progrès de vos enfants sont déjà enregistrés."
        )
        // U+2014 em dash, ordinary spaces around it.
        #expect(Copy.Paywall.Parent.buy(price: "9,99 €") == "Débloquer \u{2014} 9,99 €")
        #expect(Copy.Paywall.Parent.busy == "\u{2026}")
        #expect(Copy.Paywall.Parent.restore == "Restaurer un achat")
        #expect(
            Copy.Paywall.Parent.noStore
                == "L\u{0027}achat se fait depuis l\u{0027}application installée sur le téléphone ou la tablette."
        )
        #expect(
            Copy.Paywall.Parent.consentBody
                == "Nous aider à améliorer le jeu \u{2014} exercice, niveau, réussi ou non. Jamais le prénom de votre enfant."
        )
        #expect(Copy.Paywall.Parent.backToGame == "Retour au jeu")

        // The gate's one line, passed in by this screen.
        #expect(
            Copy.ParentalGate.purchaseReason
                == "Cette page contient un achat. Elle est réservée aux adultes.")
    }

    /// `<strong>{price}</strong>` — the price is the only emphasised run, and the
    /// runs concatenate back to the authored sentence exactly.
    @Test func onlyThePriceIsEmphasisedInTheParentParagraph() {
        let price = paywallPrice(nil)
        let sentence = Copy.Paywall.Parent.body(price: price)
        let runs = emphasisRuns(sentence, bold: [price])
        #expect(runs.map(\.text).joined() == sentence)
        #expect(runs.filter(\.bold).map(\.text) == [price])
    }

    /// Tailwind and the inline styles, transcribed.
    @Test func metricsMatchTheAuthoredCSS() {
        #expect(PaywallMetrics.stagePadding == 24)          // p-6
        #expect(PaywallMetrics.childSpacing == 24)          // gap-6
        #expect(PaywallMetrics.parentSpacing == 20)         // gap-5
        #expect(PaywallMetrics.childMaxWidth == 320)        // max-w-xs
        #expect(PaywallMetrics.parentMaxWidth == 448)       // max-w-md
        #expect(PaywallMetrics.moonSize == FluidSpec(min: 56, vw: 17, max: 90))
        #expect(PaywallMetrics.childTitleSize == FluidSpec(min: 24, vw: 7, max: 34))
        #expect(PaywallMetrics.parentTitleSize == FluidSpec(min: 24, vw: 7, max: 32))
        #expect(PaywallMetrics.primaryPaddingX == 32)       // px-8
        #expect(PaywallMetrics.primaryPaddingY == 16)       // py-4
        #expect(PaywallMetrics.restorePaddingX == 24)       // px-6
        #expect(PaywallMetrics.restorePaddingY == 12)       // py-3
        #expect(PaywallMetrics.buttonLipDrop == 8)          // 0 8px 0 #43A047
        #expect(PaywallMetrics.buttonSoftShadow.y == 14)    // 0 14px 24px rgba(0,0,0,.2)
        #expect(PaywallMetrics.buttonSoftShadow.blur == 24)
        #expect(PaywallMetrics.buttonSoftShadow.opacity == 0.2)
        #expect(PaywallMetrics.activeScale == 0.95)         // active:scale-95
        #expect(PaywallMetrics.disabledOpacity == 0.5)      // disabled:opacity-50
        #expect(PaywallMetrics.consentRadius == 16)         // rounded-2xl
        #expect(PaywallMetrics.consentPadding == 16)        // p-4
        #expect(PaywallMetrics.consentOpacity == 0.7)       // rgba(255,255,255,0.7)
        #expect(PaywallMetrics.checkboxSide == 20)          // h-5 w-5
        #expect(PaywallMetrics.checkboxTopInset == 4)       // mt-1
        #expect(PaywallMetrics.checkboxGap == 12)           // gap-3
        // The colours the two layers name inline.
        #expect(Palette.green.hex == "#66BB6A")
        #expect(Palette.greenLip.hex == "#43A047")
        #expect(Palette.ink.hex == "#5A3A1E")
        #expect(Palette.inkProse.hex == "#6B4A2C")
        #expect(Palette.inkQuiet.hex == "#8A6A4A")
        #expect(Palette.adultSecondary.hex == "#F0E6D8")
    }
}

// MARK: - Invariant 11, as a matrix

@MainActor
@Suite struct PaywallNeverFailsClosedTests {

    /// Every `Entitlement` the machine can produce, against `canPlay`. The
    /// blacklist-of-one is the load-bearing shape: `.unknown` — "the store has
    /// not answered" — PLAYS.
    @Test func onlyAnExpiredTrialEverStopsARound() {
        #expect(canPlay(.unknown))
        #expect(canPlay(.paid))
        #expect(canPlay(.trial(daysLeft: 14, endsAt: t0 + trialMs)))
        #expect(canPlay(.trial(daysLeft: 1, endsAt: t0)))
        #expect(canPlay(.expired) == false)
    }

    /// The full cross product a family can actually be in when this screen is
    /// reachable: four store behaviours × five clock positions × three licenses.
    /// A paid or mid-trial family plays in every single cell.
    @Test func noStoreStateAndNoClockLocksOutAPayingOrMidTrialFamily() async {
        let stores: [(String, FakeStore)] = [
            ("unreachable", FakeStore()),
            (
                "reachable, owns it",
                FakeStore(snapshot: StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true))
            ),
            (
                "reachable, does not own it",
                FakeStore(
                    snapshot: StoreSnapshot(paid: false, trialStartedAt: nil, reachable: true))
            ),
            ("no purchase path at all", FakeStore(available: false)),
        ]
        // Every offset stays inside BOTH the 14-day offline grace and the 14-day
        // trial, which is the region the claim is about.
        let offsets: [Int64] = [0, dayMs, 7 * dayMs, 13 * dayMs, 14 * dayMs - 1]
        let licenses: [(String, LicenseState)] = [
            ("paid, just confirmed", LicenseState(paid: true, verifiedAt: t0)),
            ("paid, never confirmed", LicenseState(paid: true, verifiedAt: nil)),
            ("mid-trial", LicenseState(trialStartedAt: t0)),
            ("pre-onboarding", .blank),
        ]

        for (storeName, store) in stores {
            for (licenseName, license) in licenses {
                for offset in offsets {
                    let time = MutableTimeSource(t0)
                    let entitlement = makeEntitlement(store: store, license: license, time: time)
                    time.nowMillis = t0 + offset
                    // The screen's own two store calls, then the resume refresh.
                    let spy = TelemetrySpy()
                    let model = PaywallModel(step: .parent)
                    await model.buy(entitlement: entitlement, telemetry: spy.telemetry)
                    await model.redo(entitlement: entitlement, telemetry: spy.telemetry)
                    await entitlement.refresh()

                    #expect(
                        canPlay(entitlement.entitlement),
                        Comment(
                            rawValue:
                                "\(storeName) / \(licenseName) / +\(offset)ms ⇒ \(entitlement.entitlement)"
                        ))
                    _ = await spy.drain()
                }
            }
        }
    }

    /// S1a — a `paid` flag with nothing to age it against is believed forever.
    /// Ten years and a dead store later, the family still plays.
    @Test func aPaidFlagThatWasNeverConfirmedIsBelievedForever() async {
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(
            store: FakeStore(),
            license: LicenseState(paid: true, verifiedAt: nil, trialStartedAt: t0),
            time: time)
        let spy = TelemetrySpy()
        let model = PaywallModel(step: .parent)

        time.nowMillis = t0 + 3650 * dayMs
        await entitlement.refresh()
        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)

        #expect(entitlement.entitlement == .paid)
        #expect(canPlay(entitlement.entitlement))
        _ = await spy.drain()
    }

    /// The 14-day offline grace, walked. A paying family on a plane keeps
    /// playing for a fortnight of failed checks — and the boundary is `>`, so
    /// exactly 14 days is still fresh.
    @Test func aPayingFamilyOfflineKeepsPlayingForTheWholeGrace() async {
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(
            store: FakeStore(),
            license: LicenseState(paid: true, verifiedAt: t0),
            time: time)

        for day in 0...14 {
            time.nowMillis = t0 + Int64(day) * dayMs
            await entitlement.refresh()
            #expect(
                entitlement.entitlement == .paid,
                Comment(rawValue: "day \(day) offline"))
        }
        #expect(entitlement.license.verifiedAt == t0)
    }

    /// S1b — when even the grace runs out the machine FALLS THROUGH to the trial
    /// clock rather than hard-locking, and the paywall's own « Restaurer un
    /// achat » is the documented recovery. Pinned here because it is the one
    /// place a refactor could turn a stale confirmation into a locked door.
    @Test func anExhaustedGraceFallsThroughInsteadOfLocking() async {
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(
            store: FakeStore(),
            license: LicenseState(paid: true, verifiedAt: t0),
            time: time)

        time.nowMillis = t0 + 15 * dayMs
        await entitlement.refresh()
        // Not `.paid` any more — but not locked either: a family whose trial has
        // not started (or has not run out) is handed the trial clock.
        #expect(entitlement.entitlement != .paid)
        #expect(canPlay(entitlement.entitlement))

        // And the recovery route works: one « Restaurer » against a store that
        // can finally answer puts them straight back.
        var store = FakeStore()
        store.restoreResult = true
        store.snapshot = StoreSnapshot(paid: true, trialStartedAt: nil, reachable: true)
        let recovered = makeEntitlement(
            store: store,
            license: LicenseState(paid: true, verifiedAt: t0),
            time: time)
        let spy = TelemetrySpy()
        let model = PaywallModel(step: .parent)
        await model.redo(entitlement: recovered, telemetry: spy.telemetry)
        #expect(recovered.entitlement == .paid)
        #expect(model.note == "Achat restauré.")
        _ = await spy.drain()
    }

    /// The screen holds NO entitlement state, so it cannot hold a stale one. The
    /// only entitlement it can observe is `storeAvailable` and `priceLabel`, both
    /// of which are about what to RENDER, never about who may play.
    @Test func theScreenDecidesNothingAboutWhoMayPlay() async {
        let spy = TelemetrySpy()
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(
            store: FakeStore(available: false), license: .blank, time: time)
        let model = PaywallModel(step: .parent)

        // Walk the whole screen: every transition, both store buttons, the
        // consent toggle both ways.
        model.gateCancelled()
        model.askForAnAdult()
        model.gatePassed(telemetry: spy.telemetry)
        await model.buy(entitlement: entitlement, telemetry: spy.telemetry)
        await model.redo(entitlement: entitlement, telemetry: spy.telemetry)
        model.setAnalytics(false, telemetry: spy.telemetry)
        model.setAnalytics(true, telemetry: spy.telemetry)

        #expect(canPlay(entitlement.entitlement))
        // Invariant 9's neighbour: nothing here writes a license value either.
        #expect(entitlement.license.paid == false)
        #expect(entitlement.license.trialStartedAt == nil)
        _ = await spy.drain()
    }

    /// A build with no purchase path still shows a complete, exit-bearing screen:
    /// the price paragraph, the « achat depuis l'application » line, the consent
    /// toggle and « Retour au jeu ». What it must NOT show is a locked door.
    @Test func aBuildWithNoStoreStillPlays() async {
        let time = MutableTimeSource(t0)
        let entitlement = makeEntitlement(store: FakeStore(available: false), time: time)
        await entitlement.refresh()
        #expect(entitlement.storeAvailable == false)
        #expect(canPlay(entitlement.entitlement))
        #expect(paywallPrice(entitlement.priceLabel) == "9,99 €")
    }
}

// MARK: - It actually draws

#if canImport(AppKit) || canImport(UIKit)

@MainActor
@Suite struct PaywallRenderTests {

    /// `swift test` has no renderer for *behaviour*, but `ImageRenderer` works on
    /// macOS — and these three layers had never been drawn once. A blank frame
    /// would mean a layout that collapses to nothing, which no other assertion in
    /// this file can see.
    @Test(arguments: PaywallStep.allCases)
    func everyLayerPaintsSomething(step: PaywallStep) throws {
        let time = MutableTimeSource(t0)
        var store = FakeStore()
        store.price = "9,99 €"
        let entitlement = makeEntitlement(store: store, time: time)
        let view = PaywallView(onBack: {}, model: PaywallModel(step: step))
            .environment(entitlement)
            .frame(width: 390, height: 780)

        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        let image = try #require(renderer.cgImage)
        #expect(image.width == 390)
        #expect(image.height == 780)
    }
}

#endif

/// A `Sendable` box for the one value the in-flight hook reads back.
private final class Box: @unchecked Sendable {
    var value: Bool?
}
