import Observation
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* « Suggérer une correction » — the adult door at the foot of an exercise.     */
/*                                                                             */
/* The value it produces is `ALCore.CorrectionReport`, and the mail it opens is */
/* built by `ALCore.CorrectionMail`; read that file first, it carries the       */
/* reason there is no endpoint behind this.                                    */
/*                                                                             */
/* ── Why the gate is not decoration. ───────────────────────────────────────── */
/*                                                                             */
/* This link leaves the app. Kids Category guideline 1.3 puts an external link  */
/* in the same bucket as a purchase: it may be VISIBLE to a child, it may not   */
/* be REACHABLE by tapping. So the door is `ParentalGateView`, the same         */
/* re-rolled multiplication the paywall and the pairing screen use, and passing */
/* it buys nothing beyond this one presentation (`ParentalGate.swift`'s         */
/* header).                                                                    */
/*                                                                             */
/* The rule is enforced by shape, not by review: `CorrectionFlowModel.mailURL`  */
/* is the ONLY place in the app that calls `CorrectionMail.url`, and it returns */
/* nil unless `gatePassed()` ran first. A view cannot get a URL out of this     */
/* module any other way, and `CorrectionFlowTests` walks every path that could  */
/* try.                                                                        */
/*                                                                             */
/* ── Why it sits inside the child's screen at all. ─────────────────────────── */
/*                                                                             */
/* Because that is where the mistake is. A parent notices a clip that says the  */
/* wrong thing WHILE the child is hearing it; a link on a settings screen is a  */
/* link nobody ever finds their way back to. The cost is one quiet line of text */
/* under the tiles, in adult register, with no glyph and no colour — small      */
/* enough that a six-year-old skips it, plain enough that the adult beside them */
/* does not.                                                                    */
/*                                                                             */
/* Invariant 3 holds throughout: a child who does tap it meets a sum they       */
/* cannot do, presses « Annuler », and is back in the round with the star, the  */
/* progress and the audio exactly as they were. Nothing is locked, nothing is   */
/* lost, and the exercise underneath is never torn down.                        */
/* -------------------------------------------------------------------------- */

// MARK: - The context, carried by the environment

/// What the exercise being played is, for the report.
///
/// Injected by `RootView` at the one place an exercise is mounted, rather than
/// threaded through nine engine views: the alternative is a parameter that nine
/// call sites can each forget, and the engines have no other use for it. The
/// default is nil, which renders no link at all — so a `GameFrame` outside the
/// play route (a preview, a test) is unchanged.
public struct CorrectionContextKey: EnvironmentKey {
    public static let defaultValue: CorrectionReport? = nil
}

extension EnvironmentValues {
    /// nil ⇒ no link. Set once, in `RootView.playScreen`.
    public var alCorrectionReport: CorrectionReport? {
        get { self[CorrectionContextKey.self] }
        set { self[CorrectionContextKey.self] = newValue }
    }
}

extension View {
    /// Announce which exercise the subtree is playing, so its `GameFrame` can
    /// offer the correction link.
    public func alCorrectionReport(_ report: CorrectionReport?) -> some View {
        environment(\.alCorrectionReport, report)
    }
}

// MARK: - The flow (pure enough to test without a renderer)

/// Where the flow is. Three states and no fourth: there is no "sending", because
/// nothing is sent from here.
public enum CorrectionStep: String, Equatable, Sendable, CaseIterable {
    /// The link is showing; nothing else is.
    case link
    /// The gate is up, over the exercise.
    case gate
    /// The system had no handler for `mailto:`. The address shows as text.
    case noMailApp
}

/// The whole state machine, and the only producer of a correction mail URL.
@MainActor
@Observable
public final class CorrectionFlowModel {

    public private(set) var step: CorrectionStep = .link

    /// Set by `gatePassed()`, cleared by everything else. The gate is
    /// per-presentation: opening the link a second time re-rolls the sum and
    /// this flag starts false again, exactly as `ParentalGate.swift` requires.
    public private(set) var passed = false

    public init() {}

    /// Test/preview seam.
    public init(step: CorrectionStep, passed: Bool = false) {
        self.step = step
        self.passed = passed
    }

    /// The link was tapped. Raises the gate — never the mail client.
    public func open() {
        passed = false
        step = .gate
    }

    /// « Annuler » on the gate, or the note being dismissed. Back to the round,
    /// with nothing spent and nothing remembered.
    public func cancel() {
        passed = false
        step = .link
    }

    /// The adult answered the sum. This does NOT open anything: it authorises
    /// `mailURL(for:)` to answer, and the view does the opening.
    public func gatePassed() {
        passed = true
        step = .link
    }

    /// The one call site of `CorrectionMail.url` in the whole app.
    ///
    /// Returns nil until `gatePassed()` has run, so no path — a mis-wired
    /// button, a future refactor, a preview — can reach a parent's mail client
    /// without an adult having answered a two-digit multiplication first.
    /// Single-use: the authorisation is spent by reading it.
    public func mailURL(for report: CorrectionReport) -> URL? {
        guard passed else { return nil }
        passed = false
        return CorrectionMail.url(report)
    }

    /// `openURL` reported that nothing accepted the URL — no Mail app, or every
    /// mail client removed. Show the address instead of failing silently.
    public func mailAppMissing() {
        step = .noMailApp
    }
}

// MARK: - Metrics

public enum CorrectionMetrics {
    /// The link's own padding — a 44 pt tap target without a visible box.
    public static let paddingX: CGFloat = 16
    public static let paddingY: CGFloat = 12
    /// Between the exercise column and the link.
    public static let topGap: CGFloat = 4
    /// Under it, above the frame's rounded edge.
    public static let bottomGap: CGFloat = 12
    /// Above the exercise, above the confetti, above everything (`GameFrame`'s
    /// z-order note: 40 confetti / 41 header / 42 children).
    public static let gateZIndex: Double = 60
    /// The « aucune application e-mail » line.
    public static let noteMaxWidth: CGFloat = 320
}

// MARK: - The link

/// The quiet line under the exercise. Text only, underlined, in the same muted
/// ink as the paywall's « Je suis un adulte » — the app's established "this is
/// for the grown-up" signal.
@MainActor
struct CorrectionLink: View {
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            Text(verbatim: Copy.Correction.link)
                .font(Typography.rounded(Typography.Size.sm, Typography.Weight.semibold))
                .underline()
                .foregroundStyle(Palette.inkQuiet.color)
                .padding(.horizontal, CorrectionMetrics.paddingX)
                .padding(.vertical, CorrectionMetrics.paddingY)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Navigation for an adult, so touch-UP — D5 reserves `touchDown` for the
        // child's feedback path, and a link that fires on touch-down is one a
        // scrolling finger can trip.
        .accessibilityLabel(Text(verbatim: Copy.Correction.link))
    }
}

/// The « no mail client » line. Not an error: it gives the parent the address.
@MainActor
struct CorrectionNote: View {
    let address: String
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onDismiss) {
            Text(verbatim: Copy.Correction.noMailApp(address))
                .font(Typography.rounded(Typography.Size.sm))
                .foregroundStyle(Palette.inkQuiet.color)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: CorrectionMetrics.noteMaxWidth)
                .padding(.horizontal, CorrectionMetrics.paddingX)
                .padding(.vertical, CorrectionMetrics.paddingY)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .textSelection(.enabled)
    }
}

// MARK: - The footer, as `GameFrame` mounts it

/// The flow half: the link, or the note that replaces it. `GameFrame` puts this
/// under the exercise column and `CorrectionGate` over the whole frame — two
/// views over one model, because the link is in the layout and the gate is not.
@MainActor
struct CorrectionFooter: View {
    let model: CorrectionFlowModel

    var body: some View {
        Group {
            switch model.step {
            case .link, .gate:
                CorrectionLink(onOpen: { model.open() })
            case .noMailApp:
                CorrectionNote(address: CorrectionMail.address, onDismiss: { model.cancel() })
            }
        }
        .padding(.top, CorrectionMetrics.topGap)
        .padding(.bottom, CorrectionMetrics.bottomGap)
        .frame(maxWidth: .infinity)
    }
}

/// The overlay half: the gate, and the one place a correction mail is opened.
///
/// The opening lives in a view rather than in the model because `openURL` is an
/// environment value — keeping it out of `CorrectionFlowModel` is what leaves
/// the model constructible, and therefore assertable, from a host test.
@MainActor
struct CorrectionGate: View {
    let report: CorrectionReport
    let model: CorrectionFlowModel

    @Environment(\.openURL) private var openURL

    @ViewBuilder
    var body: some View {
        if model.step == .gate {
            ParentalGateView(
                reason: Copy.Correction.gateReason,
                onPass: { passed() },
                onCancel: { model.cancel() }
            )
        }
    }

    private func passed() {
        model.gatePassed()
        // nil is unreachable here (`gatePassed()` just authorised it) unless the
        // address constant is emptied — in which case showing the note is the
        // right answer anyway.
        guard let url = model.mailURL(for: report) else {
            model.mailAppMissing()
            return
        }
        openURL(url) { accepted in
            // No mail client on the device. Not an error: the note carries the
            // address so the parent can write from anywhere.
            if !accepted { model.mailAppMissing() } else { model.cancel() }
        }
    }
}
