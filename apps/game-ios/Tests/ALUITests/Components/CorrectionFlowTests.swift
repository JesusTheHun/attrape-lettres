import Foundation
import Testing

import ALCore

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The gate on « Suggérer une correction », as a rule rather than as a screen.  */
/*                                                                             */
/* Kids Category 1.3 does not care that a gate is drawn — it cares that a child */
/* cannot reach what is behind it. Here that means one thing: NO PATH THROUGH   */
/* THIS MODEL YIELDS A MAIL URL WITHOUT A PASSED GATE. The tests below try      */
/* every path there is, including the two that a refactor would plausibly       */
/* introduce (asking twice, asking after a cancel).                             */
/* -------------------------------------------------------------------------- */

private let report = CorrectionReport(exercise: .readImage, level: 3, appVersion: "1.4.0")

@Suite("The correction flow never opens mail without an adult")
@MainActor
struct CorrectionFlowTests {

    @Test("a fresh model shows the link and hands out nothing")
    func initialState() {
        let model = CorrectionFlowModel()
        #expect(model.step == .link)
        #expect(!model.passed)
        #expect(model.mailURL(for: report) == nil)
    }

    @Test("tapping the link raises the gate — it does not open mail")
    func openRaisesGate() {
        let model = CorrectionFlowModel()
        model.open()
        #expect(model.step == .gate)
        #expect(!model.passed)
        #expect(model.mailURL(for: report) == nil)
    }

    @Test("only a passed gate yields a URL")
    func gatePassedYieldsURL() {
        let model = CorrectionFlowModel()
        model.open()
        model.gatePassed()
        #expect(model.step == .link)
        #expect(model.passed)
        #expect(model.mailURL(for: report) != nil)
    }

    /// The authorisation is spent by reading it. A second read — a re-render, a
    /// double tap, a retry loop — must not re-open the client.
    @Test("the authorisation is single-use")
    func singleUse() {
        let model = CorrectionFlowModel()
        model.open()
        model.gatePassed()
        #expect(model.mailURL(for: report) != nil)
        #expect(model.mailURL(for: report) == nil)
        #expect(!model.passed)
    }

    @Test("cancelling spends the authorisation too")
    func cancelClearsPass() {
        let model = CorrectionFlowModel()
        model.open()
        model.gatePassed()
        model.cancel()
        #expect(model.step == .link)
        #expect(!model.passed)
        #expect(model.mailURL(for: report) == nil)
    }

    /// Opening the link a second time must re-ask. `ParentalGateView` re-rolls
    /// its sum on every mount, and this is the other half of that promise:
    /// passing once buys nothing later.
    @Test("re-opening asks again")
    func reopenAsksAgain() {
        let model = CorrectionFlowModel()
        model.open()
        model.gatePassed()
        _ = model.mailURL(for: report)
        model.open()
        #expect(model.step == .gate)
        #expect(!model.passed)
        #expect(model.mailURL(for: report) == nil)
    }

    @Test("no mail client is a note, not a dead end")
    func noMailApp() {
        let model = CorrectionFlowModel()
        model.open()
        model.gatePassed()
        _ = model.mailURL(for: report)
        model.mailAppMissing()
        #expect(model.step == .noMailApp)
        // The note is dismissible and the link comes back.
        model.cancel()
        #expect(model.step == .link)
    }

    @Test("the seam constructor is a seam, not a way in")
    func seededModelStillNeedsTheGate() {
        // A preview or a test may start at any step — but starting at `.link`
        // with no pass must still hand out nothing.
        let model = CorrectionFlowModel(step: .link)
        #expect(model.mailURL(for: report) == nil)
    }
}
