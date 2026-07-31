import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/Onboarding.tsx` — first launch, and the only screen written  */
/* for the grown-up in the room.                                               */
/*                                                                             */
/* It does three jobs at once, on purpose. App Review 3.1.1 requires that a     */
/* time-based trial disclose its duration, what stops working, and the eventual */
/* charge BEFORE the trial starts. GDPR Art. 8 (France: age 15) means the       */
/* analytics answer has to come from the parent. A second modal for consent     */
/* would be one interruption too many, so both happen here.                     */
/*                                                                             */
/* THREE THINGS THIS SCREEN MUST KEEP, worst-to-lose first:                     */
/*                                                                             */
/*  1. The consent control starts OFF. `useState(false)` in the TSX, never      */
/*     `useState(hasConsent())`. Pre-ticked consent has been invalid since CJEU */
/*     *Planet49*, and an asymmetric button pair reads as a dark pattern to     */
/*     App Review and the CNIL alike — so « Commencer » carries no hidden       */
/*     opt-in and declining costs the parent nothing. `initialConsent` below is */
/*     a named constant so a test can hold it still.                           */
/*  2. The terms render ABOVE the button, and `beginTrial()` is only ever       */
/*     called from `start()`.                                                  */
/*  3. `start()`'s order is load-bearing: `setConsent` → `track` → `beginTrial`.*/
/*     Consent first, so the very first event already respects the answer (a    */
/*     refusal means `track` drops it on the floor).                            */
/*                                                                             */
/* ONCE PER DEVICE. This screen — not `ParentalGateView` — is the gate          */
/* `App.tsx:63` runs once (`if (!onboarded) return <Onboarding />`). The "once" */
/* is persisted by `EntitlementModel.beginTrial()` under                        */
/* `LicenseStore.onboardedKey` = `attrape-lettres:onboarded:v1` (D7: the        */
/* `attrape-lettres:*` names are a migration contract with the shipped PWA and  */
/* are preserved byte for byte). Invariant 11 is satisfied by the READ being    */
/* total: `loadOnboarded()` is `kv.string(key) == "1"`, so an unreadable or     */
/* absent flag answers `false` and the family sees this screen again — one tap  */
/* and they are playing. Nothing here can resolve to "locked".                  */
/*                                                                             */
/* Invariant 10: the only telemetry this screen emits is `trial_started` with a */
/* single numeric property. It never touches the roster, so a child's name has  */
/* no path from here to a transport.                                           */
/* -------------------------------------------------------------------------- */

// MARK: - `<strong>` runs, without re-typing the copy

/// One span of a paragraph, and whether the TSX wrapped it in `<strong>`.
public struct EmphasisRun: Equatable, Sendable {
    public let text: String
    public let bold: Bool

    public init(text: String, bold: Bool) {
        self.text = text
        self.bold = bold
    }
}

/// Splits an authored sentence around the substrings the TSX emphasises.
///
/// `Copy.Onboarding.trialParagraph` is deliberately ONE string (its `<strong>`
/// runs are emphasis, not separate copy), so the bold spans are recovered by
/// searching rather than by re-typing the sentence in two halves. Re-typing is
/// how the displayed copy and the tested copy drift apart.
///
/// Returns runs in document order whose `text` concatenates back to `full`
/// exactly — asserted in `Tests/ALUITests/Screens/OnboardingTests.swift`.
public func emphasisRuns(_ full: String, bold: [String]) -> [EmphasisRun] {
    var out: [EmphasisRun] = []
    var rest = Substring(full)
    while !rest.isEmpty {
        var hit: (range: Range<Substring.Index>, needle: String)?
        for needle in bold where !needle.isEmpty {
            guard let range = rest.range(of: needle) else { continue }
            if hit == nil || range.lowerBound < hit!.range.lowerBound {
                hit = (range, needle)
            }
        }
        guard let hit else {
            out.append(EmphasisRun(text: String(rest), bold: false))
            break
        }
        if hit.range.lowerBound > rest.startIndex {
            out.append(EmphasisRun(text: String(rest[rest.startIndex..<hit.range.lowerBound]), bold: false))
        }
        out.append(EmphasisRun(text: hit.needle, bold: true))
        rest = rest[hit.range.upperBound...]
    }
    return out
}

// MARK: - What the screen says, given what the store answered

/// The disclosure copy, resolved. Pure, so `swift test` can assert the wording
/// and the branch without a renderer.
public struct OnboardingPlan: Equatable, Sendable {
    /// `priceLabel ?? "9,99 €"` — the store's localised label wins once it has
    /// answered; the fallback is `UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")`.
    public let price: String

    /// The paragraphs in render order, each with its emphasis runs. Two when a
    /// store exists (trial terms + what pauses), one when it does not.
    public let paragraphs: [[EmphasisRun]]

    /// « Commencer les 14 jours » / « Commencer ».
    public let buttonTitle: String

    /// - Parameters:
    ///   - storeAvailable: `EntitlementModel.storeAvailable`.
    ///   - priceLabel: the store's label, or nil while it has not answered.
    ///   - days: `TRIAL_DAYS`. A parameter only so a test can prove the copy is
    ///     built from the constant rather than from a hard-coded "14".
    ///   - scope: `pour toute la famille` on iOS. See the note on
    ///     `Copy.Onboarding.scopeOther` — the Android promise is kept in the
    ///     design, not shipped, because Play's Family Library does not share
    ///     in-app purchases.
    public init(
        storeAvailable: Bool,
        priceLabel: String?,
        days: Int = trialDays,
        priceEUR: Double = unlockPriceEur,
        scope: String = Copy.Onboarding.scopeIOS
    ) {
        let resolved = priceLabel ?? Copy.fallbackPriceLabel(priceEUR)
        self.price = resolved
        if storeAvailable {
            let terms = Copy.Onboarding.trialParagraph(days: days, price: resolved, scope: scope)
            self.paragraphs = [
                emphasisRuns(terms, bold: ["\(days) jours", resolved]),
                emphasisRuns(Copy.Onboarding.pauseParagraph(days: days), bold: []),
            ]
            self.buttonTitle = Copy.Onboarding.startTrial(days: days)
        } else {
            self.paragraphs = [emphasisRuns(Copy.Onboarding.noStoreParagraph, bold: [])]
            self.buttonTitle = Copy.Onboarding.start
        }
    }
}

// MARK: - `start()`

/// The TSX's `start()`, extracted whole:
///
/// ```tsx
/// const start = () => {
///   setConsent(analytics);
///   track("trial_started", { daysLeft: TRIAL_DAYS });
///   beginTrial();
/// };
/// ```
///
/// The order is the behaviour and it is tested. `setConsent` first means a
/// refusal is already recorded when `track` runs, so `trial_started` is dropped
/// rather than sent-then-regretted; `beginTrial` last means the screen only
/// dismisses after the answer is stored.
@MainActor
public func startOnboarding(
    analytics: Bool,
    telemetry: Telemetry,
    entitlement: EntitlementModel,
    days: Int = trialDays
) {
    telemetry.setConsent(analytics)
    telemetry.track(.trialStarted, TelemetryProps(daysLeft: days))
    entitlement.beginTrial()
}

// MARK: - The screen

@MainActor
public struct OnboardingView: View {

    /// **The consent control's initial state, and it is `false`.**
    ///
    /// A named constant rather than a literal in the `@State` default: seeding
    /// it from `telemetry.hasConsent` would delete this line, which is what
    /// `OnboardingTests` is watching for. Do not make it a function of
    /// anything.
    public static let initialConsent = false

    /// `clamp(44px,13vw,64px)` — the 👋.
    public static let waveSize = FluidSpec(min: 44, vw: 13, max: 64)
    /// `clamp(26px,7vw,36px)` — the headline.
    public static let titleSize = FluidSpec(min: 26, vw: 7, max: 36)
    /// `max-w-md`.
    public static let maxWidth: CGFloat = 448
    /// `p-6` on the stage, `gap-5` between blocks, `gap-3` between paragraphs.
    public static let stagePadding: CGFloat = 24
    public static let blockGap: CGFloat = 20
    public static let paragraphGap: CGFloat = 12
    /// `px-8 py-4` on « Commencer », and its `0 8px 0 #43A047` lip +
    /// `0 14px 24px rgba(0,0,0,0.2)`.
    public static let buttonPaddingX: CGFloat = 32
    public static let buttonPaddingY: CGFloat = 16
    public static let buttonLipDrop: CGFloat = 8
    public static let buttonSoftShadow = CSSShadow(y: 14, blur: 24, opacity: 0.2)
    /// `rounded-2xl p-4` on the consent card, `bg rgba(255,255,255,0.7)`.
    public static let consentRadius: CGFloat = 16
    public static let consentPadding: CGFloat = 16
    public static let consentOpacity: Double = 0.7
    /// `h-5 w-5` on the checkbox, `mt-1` above it, `gap-3` beside it.
    public static let checkboxSide: CGFloat = 20
    public static let checkboxTopInset: CGFloat = 4
    public static let checkboxGap: CGFloat = 12

    @Environment(EntitlementModel.self) private var entitlement
    @Environment(Telemetry.self) private var injectedTelemetry: Telemetry?
    @Environment(\.alViewportWidth) private var viewport

    @State private var analytics = OnboardingView.initialConsent

    public init() {}

    /// The process-wide instance is the TS module-level `track`/`setConsent`;
    /// an injected one wins so a test or a preview can watch it.
    private var telemetry: Telemetry { injectedTelemetry ?? Telemetry.shared }

    private var plan: OnboardingPlan {
        OnboardingPlan(storeAvailable: entitlement.storeAvailable, priceLabel: entitlement.priceLabel)
    }

    public var body: some View {
        let plan = self.plan
        return VStack(alignment: .leading, spacing: Self.blockGap) {
            Text(verbatim: Copy.Onboarding.wave)
                .font(.system(size: Self.waveSize.resolve(viewport: viewport)))
                .accessibilityHidden(true)

            Text(verbatim: Copy.Onboarding.title)
                .font(Typography.rounded(Self.titleSize.resolve(viewport: viewport), Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .leading, spacing: Self.paragraphGap) {
                ForEach(Array(plan.paragraphs.enumerated()), id: \.offset) { _, runs in
                    paragraph(runs)
                        .font(Typography.rounded(Typography.Size.base))
                        .foregroundStyle(Palette.inkProse.color)
                        .lineSpacing(
                            Typography.lineSpacing(
                                size: Typography.Size.base, ratio: Typography.LineHeight.snug))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            startButton(plan)

            consentCard
        }
        .frame(maxWidth: Self.maxWidth)
        .padding(Self.stagePadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .stageWash(Palette.stageAdult)
        .fontDesign(.rounded)  // fontFamily: ui-rounded,'SF Pro Rounded',…
    }

    /// The `<strong>` runs, concatenated. `Text(verbatim:)` throughout: a bare
    /// `Text("…")` is a `LocalizedStringKey` lookup, and the adult copy contains
    /// characters (`—`, `:`) that a format-string reading could mangle.
    private func paragraph(_ runs: [EmphasisRun]) -> Text {
        runs.reduce(Text(verbatim: "")) { acc, run in
            acc + (run.bold ? Text(verbatim: run.text).bold() : Text(verbatim: run.text))
        }
    }

    /// Navigation, not gameplay: the TSX uses `onClick`, so this fires on
    /// touch-UP and is an ordinary `Button` (D5 reserves `touchDown` for the
    /// feedback path).
    private func startButton(_ plan: OnboardingPlan) -> some View {
        Button {
            startOnboarding(analytics: analytics, telemetry: telemetry, entitlement: entitlement)
        } label: {
            Text(verbatim: plan.buttonTitle)
                .font(Typography.rounded(Typography.Size.xl, Typography.Weight.extrabold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, Self.buttonPaddingX)
                .padding(.vertical, Self.buttonPaddingY)
                .background {
                    liftedCapsule(
                        fill: AnyShapeStyle(Palette.green.color),
                        lip: Palette.greenLip.color,
                        drop: Self.buttonLipDrop,
                        soft: Self.buttonSoftShadow
                    )
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var consentCard: some View {
        Toggle(isOn: $analytics) {
            (Text(verbatim: Copy.Onboarding.consentTitle)
                .bold()
                .foregroundStyle(Palette.ink.color)
                + Text(verbatim: "\n")
                + Text(verbatim: Copy.Onboarding.consentBody))
                .font(Typography.rounded(Typography.Size.sm))
                .foregroundStyle(Palette.inkProse.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Typography.Size.sm, ratio: Typography.LineHeight.snug))
        }
        .toggleStyle(
            ConsentCheckboxStyle(
                side: Self.checkboxSide,
                topInset: Self.checkboxTopInset,
                gap: Self.checkboxGap
            )
        )
        .padding(Self.consentPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color.white.opacity(Self.consentOpacity),
            in: RoundedRectangle(cornerRadius: Self.consentRadius)
        )
    }
}

/* -------------------------------------------------------------------------- */
/* `<label><input type="checkbox" …/><span>…</span></label>`.                  */
/*                                                                             */
/* The whole label is the hit target on the web, which is why this is a         */
/* `ToggleStyle` wrapping both the box and the text in one control rather than  */
/* a box with a caption beside it. `accessibilityRepresentation` hands          */
/* VoiceOver a real `Toggle`, so it announces "interrupteur, désactivé" and     */
/* not "bouton" — the native equivalent of the checkbox's own semantics.        */
/* -------------------------------------------------------------------------- */

struct ConsentCheckboxStyle: ToggleStyle {
    let side: CGFloat
    let topInset: CGFloat
    let gap: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(alignment: .top, spacing: gap) {
                box(configuration.isOn)
                    .padding(.top, topInset)
                configuration.label
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }
        }
    }

    private func box(_ on: Bool) -> some View {
        RoundedRectangle(cornerRadius: 4)
            .fill(on ? Palette.green.color : Color.white)
            .overlay {
                RoundedRectangle(cornerRadius: 4)
                    .strokeBorder(on ? Palette.green.color : Palette.inkFaint.color, lineWidth: 2)
            }
            .overlay {
                if on {
                    Image(systemName: "checkmark")
                        .font(.system(size: side * 0.62, weight: .black))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: side, height: side)
    }
}
