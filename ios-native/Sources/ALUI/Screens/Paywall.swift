import Observation
import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/Paywall.tsx` — end of the trial.                            */
/*                                                                             */
/* Three layers, and the order is the whole design. A child who taps an         */
/* exercise sees a calm, kid-legible « demande à un grand » — never a price,    */
/* never a buy button (Kids Category 1.3 forbids putting a purchase in front of */
/* them, and invariant 3 forbids making a child feel they failed at something). */
/* The parental gate is the door. Only behind it does money appear.            */
/*                                                                             */
/* Nothing is confiscated: the hub, the mascot, the shop and every star stay    */
/* exactly where they were. Only starting a NEW round is paused.               */
/*                                                                             */
/* ── INVARIANT 11 — money never fails closed. ───────────────────────────────── */
/*                                                                             */
/* This screen SELLS; it never GATES. Read that as a structural claim, because  */
/* it is one: there is no `entitlement` read anywhere in this file. No          */
/* `if entitlement == .expired`, no `canPlay`, no clock, no store snapshot. The */
/* router decides who sees this screen (`canPlay(model.entitlement)` in         */
/* `App.tsx`, ported in the shell) and `ALCore` already guarantees that every   */
/* failure — unreachable App Store, timed-out receipt check, flat network,      */
/* StoreKit error, pending Ask-to-Buy — resolves to *playable*:                 */
/*                                                                             */
/*   • `StoreKitPurchaseStore` maps every throw and every timeout to            */
/*     `.unreachable` / `nil` / `false`, never to a locked verdict;             */
/*   • `applySnapshot` changes NOTHING but the clock when `reachable` is false; */
/*   • `entitlementOf` keeps a paid family paid for a 14-day offline grace, and */
/*     when even that runs out it FALLS THROUGH to the trial clock rather than  */
/*     hard-locking;                                                           */
/*   • `canPlay` is a blacklist of one, so `.unknown` plays.                    */
/*                                                                             */
/* The one way this screen could break that chain is by growing a branch of its */
/* own — "if the store did not answer, hide the way back", "if the purchase     */
/* failed, stay here". It has neither, and `PaywallTests` proves every step     */
/* offers an exit and that a failed purchase leaves the child's side untouched. */
/* Do not add such a branch.                                                    */
/*                                                                             */
/* Invariant 10: three of the four events this screen emits carry NO properties */
/* at all, and the fourth (`paywall_shown`) carries none either. The roster is  */
/* never read here, so a child's first name has no path from this file to a     */
/* transport.                                                                   */
/* -------------------------------------------------------------------------- */

// MARK: - The three layers

/// ```ts
/// type Step = "child" | "gate" | "parent";
/// ```
///
/// The order is the design: a child may only ever reach `child`, and the money
/// lives two deliberate taps away.
public enum PaywallStep: String, Equatable, Sendable, CaseIterable {
    /// « Les jeux font une pause » — no price, no purchase, no failure.
    case child
    /// `<ParentalGate reason=… />`.
    case gate
    /// Behind the gate: the price, the two store controls, the consent toggle.
    case parent
}

// MARK: - The state machine (pure enough to test without a renderer)

/// `useState` × 4, and the two `async` handlers, lifted out of the view.
///
/// The collaborators are passed per call rather than held: a SwiftUI `@State`
/// default cannot read `@Environment`, and threading them through is what keeps
/// this whole object constructible — and therefore assertable — from a test.
@MainActor
@Observable
public final class PaywallModel {

    /// `const [step, setStep] = useState<Step>("child")`.
    public private(set) var step: PaywallStep = .child

    /// `const [busy, setBusy] = useState(false)` — `disabled={busy}` on both
    /// store buttons, and the « … » label on the buy button.
    public private(set) var busy = false

    /// `const [note, setNote] = useState<string | null>(null)` — the one line
    /// under the buttons. Never an error state: it says what did NOT happen.
    public private(set) var note: String?

    /// `const [analytics, setAnalytics] = useState(hasConsent)`.
    ///
    /// NB the contrast with `OnboardingView.initialConsent`, which is a hard
    /// `false`: that screen ASKS for the first time (pre-ticked consent has been
    /// invalid since CJEU *Planet49*), this one shows the answer already given so
    /// the parent can withdraw it (GDPR Art. 7(3)). Both are deliberate and they
    /// must not be unified.
    public private(set) var analytics = false

    /// `useState(hasConsent)` is a *lazy* initialiser: it runs once, at mount.
    /// Swift cannot read the environment from a `@State` default expression, so
    /// the seed happens on first appear and this flag makes it happen once —
    /// a second appear must not clobber a toggle the parent has already moved.
    private var seeded = false

    public init() {}

    /// Test/preview seam: start already mounted at a given step.
    public init(step: PaywallStep, analytics: Bool = false) {
        self.step = step
        self.analytics = analytics
        self.seeded = true
    }

    /// The mount read of `hasConsent()`. Idempotent.
    public func seedAnalytics(_ consent: Bool) {
        guard !seeded else { return }
        seeded = true
        analytics = consent
    }

    // MARK: Navigation between the layers

    /// « Je suis un adulte » — the only door out of the child layer that is not
    /// « Voir ma mascotte ». Note what it does NOT do: no telemetry. A child
    /// tapping around does not count as a paywall impression.
    public func askForAnAdult() {
        step = .gate
    }

    /// ```tsx
    /// onPass={() => { setStep("parent"); track("paywall_shown"); }}
    /// ```
    /// In that order. `paywall_shown` means *an adult saw the price*, which is
    /// why it fires here and nowhere else.
    public func gatePassed(telemetry: Telemetry) {
        step = .parent
        telemetry.track(.paywallShown)
    }

    /// `onCancel={() => setStep("child")}` — back to the calm screen, not out of
    /// the app.
    public func gateCancelled() {
        step = .child
    }

    // MARK: The two store buttons

    /// ```tsx
    /// const buy = async () => {
    ///   setBusy(true);
    ///   setNote(null);
    ///   const ok = await purchase();
    ///   setBusy(false);
    ///   track(ok ? "purchase_completed" : "purchase_failed");
    ///   if (!ok) setNote("L'achat n'a pas abouti. Rien n'a été débité.");
    /// };
    /// ```
    ///
    /// Every non-success collapses to the same `false` — cancel, StoreKit error,
    /// an unverified transaction, and a **pending** Ask-to-Buy alike
    /// (`PurchaseStore`'s normative mapping). All four therefore land on a note
    /// that blames nobody and confiscates nothing, with the game exactly as
    /// playable as it was a second earlier.
    public func buy(entitlement: EntitlementModel, telemetry: Telemetry) async {
        busy = true
        note = nil
        let ok = await entitlement.purchase()
        busy = false
        telemetry.track(ok ? .purchaseCompleted : .purchaseFailed)
        if !ok { note = Copy.Paywall.Note.purchaseFailed }
    }

    /// ```tsx
    /// const redo = async () => {
    ///   setBusy(true);
    ///   setNote(null);
    ///   const ok = await restore();
    ///   setBusy(false);
    ///   track("purchase_restored");
    ///   setNote(ok ? "Achat restauré." : "Aucun achat trouvé sur ce compte.");
    /// };
    /// ```
    ///
    /// Two asymmetries with `buy`, both in the TypeScript and both correct:
    /// `purchase_restored` fires whatever the outcome, and a note is set either
    /// way. (`EntitlementModel.restore()` also refreshes unconditionally, because
    /// `AppStore.sync()` can materialise an entitlement even when the call itself
    /// reports nothing restored — so « Aucun achat trouvé » can be shown by a run
    /// that nonetheless just unlocked the app. Harmless: the router re-reads the
    /// entitlement and the paywall disappears.)
    public func redo(entitlement: EntitlementModel, telemetry: Telemetry) async {
        busy = true
        note = nil
        let ok = await entitlement.restore()
        busy = false
        telemetry.track(.purchaseRestored)
        note = ok ? Copy.Paywall.Note.restored : Copy.Paywall.Note.nothingToRestore
    }

    // MARK: The consent checkbox

    /// ```tsx
    /// onChange={(e) => { setAnalytics(e.target.checked); setConsent(e.target.checked); }}
    /// ```
    /// Local state first, storage second — and `setConsent(false)` drains the
    /// queue, so withdrawal is retroactive for anything not yet sent.
    public func setAnalytics(_ on: Bool, telemetry: Telemetry) {
        analytics = on
        telemetry.setConsent(on)
    }
}

/// `priceLabel ?? `${PRICE} €`` — the store's localised label wins; the fallback
/// is `UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",")` plus « €».
///
/// The literal is nowhere in this file: the number comes from
/// `ALCore.unlockPriceEur` and the formatting from `Copy.fallbackPriceLabel`.
public func paywallPrice(_ priceLabel: String?, fallback eur: Double = unlockPriceEur) -> String {
    priceLabel ?? Copy.fallbackPriceLabel(eur)
}

// MARK: - Metrics (the TSX's Tailwind classes and inline styles, verbatim)

public enum PaywallMetrics {
    /// Both layers: `min-h-[100dvh] w-full … p-6`.
    public static let stagePadding: CGFloat = 24

    /// Child: `flex-col items-center justify-center gap-6 text-center`.
    public static let childSpacing: CGFloat = 24
    /// `clamp(56px,17vw,90px)` — the 🌙.
    public static let moonSize = FluidSpec(min: 56, vw: 17, max: 90)
    /// `clamp(24px,7vw,34px)` — « Les jeux font une pause ».
    public static let childTitleSize = FluidSpec(min: 24, vw: 7, max: 34)
    /// `max-w-xs` on the child's paragraph and on its green button.
    public static let childMaxWidth: CGFloat = 320

    /// Parent: `w-full max-w-md flex-col gap-5`.
    public static let parentSpacing: CGFloat = 20
    public static let parentMaxWidth: CGFloat = 448
    /// `clamp(24px,7vw,32px)` — « Débloquer Attrape-Lettres ». One point shorter
    /// at the top than the child headline; both are in the TSX.
    public static let parentTitleSize = FluidSpec(min: 24, vw: 7, max: 32)

    /// The green button, both layers: `rounded-full px-8 py-4 text-xl
    /// font-extrabold text-white active:scale-95`, over
    /// `0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)`.
    public static let primaryPaddingX: CGFloat = 32
    public static let primaryPaddingY: CGFloat = 16
    public static let buttonLipDrop: CGFloat = 8
    public static let buttonSoftShadow = CSSShadow(y: 14, blur: 24, opacity: 0.2)
    public static let activeScale: CGFloat = 0.95

    /// « Restaurer un achat »: `rounded-full px-6 py-3 font-semibold` on
    /// `#F0E6D8`, with no lip — it is deliberately the quieter control.
    public static let restorePaddingX: CGFloat = 24
    public static let restorePaddingY: CGFloat = 12

    /// `disabled:opacity-50` on both store buttons while `busy`.
    public static let disabledOpacity: Double = 0.5

    /// The consent card: `rounded-2xl p-4` on `rgba(255,255,255,0.7)`, with an
    /// `h-5 w-5` box, `mt-1` above it and `gap-3` beside it.
    public static let consentRadius: CGFloat = 16
    public static let consentPadding: CGFloat = 16
    public static let consentOpacity: Double = 0.7
    public static let checkboxSide: CGFloat = 20
    public static let checkboxTopInset: CGFloat = 4
    public static let checkboxGap: CGFloat = 12
}

// MARK: - The screen

/// `<Paywall onBack={() => setRoute("dashboard")} />`.
@MainActor
public struct PaywallView: View {

    private let onBack: () -> Void

    @Environment(EntitlementModel.self) private var entitlement
    @Environment(Telemetry.self) private var injectedTelemetry: Telemetry?
    @Environment(\.alViewportWidth) private var viewport

    @State private var model: PaywallModel

    /// - Parameter model: injected only by tests and previews; the app lets the
    ///   screen mint its own, exactly as `useState` does.
    public init(onBack: @escaping () -> Void, model: PaywallModel? = nil) {
        self.onBack = onBack
        _model = State(initialValue: model ?? PaywallModel())
    }

    /// The process-wide instance is the TS module-level `track`/`setConsent`; an
    /// injected one wins so a test or a preview can watch it.
    private var telemetry: Telemetry { injectedTelemetry ?? Telemetry.shared }

    /// `priceLabel ?? `${PRICE} €``.
    private var price: String { paywallPrice(entitlement.priceLabel) }

    public var body: some View {
        content
            .onAppear { model.seedAnalytics(telemetry.hasConsent) }
    }

    @ViewBuilder
    private var content: some View {
        switch model.step {
        case .gate:
            ParentalGateView(
                reason: Copy.ParentalGate.purchaseReason,
                onPass: { model.gatePassed(telemetry: telemetry) },
                onCancel: { model.gateCancelled() })
        case .child:
            childLayer
        case .parent:
            parentLayer
        }
    }

    // MARK: The child layer

    private var childLayer: some View {
        VStack(spacing: PaywallMetrics.childSpacing) {
            Text(verbatim: Copy.Paywall.Child.moon)
                .font(.system(size: PaywallMetrics.moonSize.resolve(viewport: viewport)))
                .accessibilityHidden(true)

            Text(verbatim: Copy.Paywall.Child.title)
                .font(
                    Typography.rounded(
                        PaywallMetrics.childTitleSize.resolve(viewport: viewport),
                        Typography.Weight.black)
                )
                .foregroundStyle(Palette.ink.color)

            Text(verbatim: Copy.Paywall.Child.body)
                .font(Typography.rounded(Typography.Size.lg))
                .foregroundStyle(Palette.inkProse.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Typography.Size.lg, ratio: Typography.LineHeight.snug)
                )
                .frame(maxWidth: PaywallMetrics.childMaxWidth)

            // The way out, and it is always here. A child never sees a dead end.
            greenButton(Copy.Paywall.Child.seeCompanion, action: onBack)
                .frame(maxWidth: PaywallMetrics.childMaxWidth)

            underlinedLink(Copy.Paywall.Child.iAmAnAdult) { model.askForAnAdult() }
        }
        .multilineTextAlignment(.center)
        .padding(PaywallMetrics.stagePadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .stageWash(Palette.stageAdult)
        .fontDesign(.rounded)
    }

    // MARK: The parent layer

    private var parentLayer: some View {
        VStack(alignment: .leading, spacing: PaywallMetrics.parentSpacing) {
            Text(verbatim: Copy.Paywall.Parent.title)
                .font(
                    Typography.rounded(
                        PaywallMetrics.parentTitleSize.resolve(viewport: viewport),
                        Typography.Weight.black)
                )
                .foregroundStyle(Palette.ink.color)

            // `Un achat unique de <strong>{price}</strong>. …` — the emphasis is
            // recovered by search rather than by re-typing the sentence, the same
            // rule `OnboardingPlan` follows.
            emphasised(Copy.Paywall.Parent.body(price: price), bold: [price])
                .font(Typography.rounded(Typography.Size.base))
                .foregroundStyle(Palette.inkProse.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Typography.Size.base, ratio: Typography.LineHeight.snug))

            if entitlement.storeAvailable {
                greenButton(model.busy ? Copy.Paywall.Parent.busy : Copy.Paywall.Parent.buy(price: price)) {
                    Task { await model.buy(entitlement: entitlement, telemetry: telemetry) }
                }
                .disabled(model.busy)
                // `disabled:opacity-50` — as a GROUP. See WhoIsPlaying's note:
                // without this the lip shows through the face of the pill.
                .compositingGroup()
                .opacity(model.busy ? PaywallMetrics.disabledOpacity : 1)

                // Apple requires a restore control to exist for non-consumables.
                restoreButton
            } else {
                Text(verbatim: Copy.Paywall.Parent.noStore)
                    .font(Typography.rounded(Typography.Size.base))
                    .foregroundStyle(Palette.inkProse.color)
            }

            if let note = model.note {
                // `role="status"` — a polite live region.
                Text(verbatim: note)
                    .font(Typography.rounded(Typography.Size.sm))
                    .foregroundStyle(Palette.inkQuiet.color)
                    .accessibilityAddTraits(.isStaticText)
            }

            consentCard

            underlinedLink(Copy.Paywall.Parent.backToGame, action: onBack)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: PaywallMetrics.parentMaxWidth, alignment: .leading)
        .padding(PaywallMetrics.stagePadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .stageWash(Palette.stageAdult)
        .fontDesign(.rounded)
        // VoiceOver hears the outcome without hunting for it.
        .onChange(of: model.note) { _, next in
            if let next { announceStatus(next) }
        }
    }

    private var restoreButton: some View {
        Button {
            Task { await model.redo(entitlement: entitlement, telemetry: telemetry) }
        } label: {
            Text(verbatim: Copy.Paywall.Parent.restore)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.semibold))
                .foregroundStyle(Palette.ink.color)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, PaywallMetrics.restorePaddingX)
                .padding(.vertical, PaywallMetrics.restorePaddingY)
                .background(Palette.adultSecondary.color, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(model.busy)
        .compositingGroup()
        .opacity(model.busy ? PaywallMetrics.disabledOpacity : 1)
    }

    private var consentCard: some View {
        Toggle(
            isOn: Binding(
                get: { model.analytics },
                set: { model.setAnalytics($0, telemetry: telemetry) })
        ) {
            Text(verbatim: Copy.Paywall.Parent.consentBody)
                .font(Typography.rounded(Typography.Size.sm))
                .foregroundStyle(Palette.inkProse.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Typography.Size.sm, ratio: Typography.LineHeight.snug))
        }
        .toggleStyle(
            ConsentCheckboxStyle(
                side: PaywallMetrics.checkboxSide,
                topInset: PaywallMetrics.checkboxTopInset,
                gap: PaywallMetrics.checkboxGap)
        )
        .padding(PaywallMetrics.consentPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color.white.opacity(PaywallMetrics.consentOpacity),
            in: RoundedRectangle(cornerRadius: PaywallMetrics.consentRadius))
    }

    // MARK: Shared chrome

    /// The lifted green pill, both layers. Navigation and money are `onClick` in
    /// the TSX, so these are ordinary touch-UP `Button`s (D5 reserves `touchDown`
    /// for the feedback path).
    private func greenButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(Typography.rounded(Typography.Size.xl, Typography.Weight.extrabold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, PaywallMetrics.primaryPaddingX)
                .padding(.vertical, PaywallMetrics.primaryPaddingY)
                .background {
                    liftedCapsule(
                        fill: AnyShapeStyle(Palette.green.color),
                        lip: Palette.greenLip.color,
                        drop: PaywallMetrics.buttonLipDrop,
                        soft: PaywallMetrics.buttonSoftShadow)
                }
                .contentShape(Capsule())
        }
        .buttonStyle(PickerActiveScaleStyle(scale: PaywallMetrics.activeScale))
    }

    /// `text-base font-semibold underline` on `#8A6A4A` — the two quiet adult
    /// links. No background, no border; the underline is the affordance.
    private func underlinedLink(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.semibold))
                .underline()
                .foregroundStyle(Palette.inkQuiet.color)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func emphasised(_ full: String, bold: [String]) -> Text {
        emphasisRuns(full, bold: bold).reduce(Text(verbatim: "")) { acc, run in
            acc + (run.bold ? Text(verbatim: run.text).bold() : Text(verbatim: run.text))
        }
    }
}

/// `role="status"`'s live region. macOS has no equivalent post, and the note is
/// on screen either way — the announcement is an addition for VoiceOver, never
/// the only way to learn the outcome.
@MainActor
private func announceStatus(_ message: String) {
    #if canImport(UIKit)
    AccessibilityNotification.Announcement(message).post()
    #endif
}
