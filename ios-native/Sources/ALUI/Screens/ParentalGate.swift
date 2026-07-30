import SwiftUI

import ALCore

/* -------------------------------------------------------------------------- */
/* `src/components/ParentalGate.tsx` — the adult door.                          */
/*                                                                             */
/* Kids Category guideline 1.3: an app in the Kids Category may not put a       */
/* purchase, an external link or any other "distraction" in front of a child    */
/* unless it sits behind one of these. App Review 3.1.1 wants the same before   */
/* anything that spends money.                                                 */
/*                                                                             */
/* A two-digit multiplication is the standard because it is the cheapest thing  */
/* a six-year-old genuinely cannot do and an adult does without thinking. The   */
/* operands are RE-ROLLED ON EVERY OPEN, so a child who watches once learns     */
/* nothing. Deliberately NOT kid-styled: the tone change is half the signal     */
/* that this screen is not for them.                                           */
/*                                                                             */
/* ── This gate persists NOTHING, and that is the TSX. ──────────────────────── */
/* W15a's brief said the gate "runs once per device (see App.tsx); the 'once'   */
/* is persisted". It is not this screen. `App.tsx` mounts `ParentalGate`        */
/* nowhere — the only caller is `Paywall.tsx`'s `step === "gate"` branch, and   */
/* the TSX holds the challenge in `useState(roll)`, i.e. fresh per mount, with  */
/* no storage read or write anywhere in the file. The once-per-device gate that */
/* IS persisted is **Onboarding**, under `LicenseStore.onboardedKey` =          */
/* `attrape-lettres:onboarded:v1` (see `Onboarding.swift`'s header). Adding a   */
/* "gate passed" flag here would be a behaviour change and a security          */
/* regression: the whole point of the re-roll is that passing once buys nothing.*/
/*                                                                             */
/* Invariant 11 is upheld by this screen holding no entitlement state at all.   */
/* It reads nothing, so it cannot fail to read something; a wrong answer costs  */
/* one retry and « Annuler » always returns the caller's own screen.            */
/*                                                                             */
/* Invariant 3's spirit: a wrong sum is not an error state. The field clears,   */
/* the border warms, and the adult tries again — nothing is locked or lost.     */
/* -------------------------------------------------------------------------- */

// MARK: - The challenge (pure)

/// Two single digits and the product an adult has to supply.
///
/// ```ts
/// function roll(): [number, number] {
///   const d = () => 3 + Math.floor(Math.random() * 7);   // 3..9
///   return [d(), d()];
/// }
/// ```
///
/// Operands 3…9, product 9…81 — never a trivial ×1 or ×2.
public struct GateChallenge: Equatable, Sendable {
    public let a: Int
    public let b: Int

    public init(a: Int, b: Int) {
        self.a = a
        self.b = b
    }

    /// The lowest and highest operand the roll can produce. Named so the test
    /// asserts the TypeScript's range rather than whatever the code happens to
    /// do.
    public static let lowestOperand = 3
    public static let operandSpread = 7

    public var answer: Int { a * b }

    /// « Combien font 7 × 4 ? » — U+00D7, not the letter x.
    public var question: String { Copy.ParentalGate.question(a, b) }

    /// D9: randomness is injected. The two draws happen in source order, so a
    /// seeded source reproduces the TS's `[d(), d()]` left-to-right evaluation.
    public static func roll(using random: RandomSource) -> GateChallenge {
        let first = lowestOperand + random.int(below: operandSpread)
        let second = lowestOperand + random.int(below: operandSpread)
        return GateChallenge(a: first, b: second)
    }

    /// A fresh, non-reproducible roll — one per gate mount.
    public static func roll() -> GateChallenge { roll(using: .system()) }
}

/// `e.target.value.replace(/\D/g, "").slice(0, 3)`.
///
/// Filtered per UNICODE SCALAR, not per `Character`, because that is what the
/// regex does. `\D` without the `u` flag matches one UTF-16 code unit at a time
/// and deletes everything outside U+0030…U+0039, so:
///
///   • non-ASCII digits go (Arabic-Indic ٤٢, full-width ４２, superscript ²) —
///     `Character.isNumber` would have KEPT them and let through a shape the web
///     field never accepted;
///   • a mark attached to a digit goes while the digit STAYS ("3" + U+0301 is
///     "3"; the keycap "1️⃣" is "1") — a `Character`-level filter drops the whole
///     grapheme cluster and answers "", which the web never does.
///
/// `.slice(0, 3)` counts code units too, and after the filter every survivor is
/// exactly one unit wide, so `prefix(3)` over the filtered scalars is the same
/// cut.
public func sanitizeGateInput(_ raw: String) -> String {
    let digits = raw.unicodeScalars.filter { $0.value >= 0x30 && $0.value <= 0x39 }
    return String(String.UnicodeScalarView(digits.prefix(3)))
}

// MARK: - The retry behaviour (pure, host-tested)

/// The gate's whole state machine. Extracted because `swift test` has no
/// renderer: the accept/reject rule and the clear-and-refocus on a wrong answer
/// are the parts worth holding still.
@MainActor
@Observable
public final class ParentalGateModel {
    public let challenge: GateChallenge

    /// The digits typed so far, already sanitised.
    public private(set) var value = ""

    /// Set by a failed `submit()`, cleared by the next keystroke — the TSX
    /// clears `wrong` in `onChange`, so the error line disappears the moment the
    /// adult starts over.
    public private(set) var wrong = false

    public init(challenge: GateChallenge) {
        self.challenge = challenge
    }

    /// `disabled={value.length === 0}` on « Continuer ».
    public var canSubmit: Bool { !value.isEmpty }

    /// `onChange` — sanitise, and clear the error.
    public func type(_ raw: String) {
        value = sanitizeGateInput(raw)
        wrong = false
    }

    /// `submit` — `true` means the adult passed and the caller should call
    /// `onPass()`. A failure clears the field (`setValue("")`) and raises the
    /// error line; the view refocuses.
    ///
    /// `Number(value) === answer`: an empty field parses to `0` in JS and to nil
    /// here, and both fail because the product is at least 9. Leading zeros
    /// parse the same in both.
    public func submit() -> Bool {
        if let typed = Int(value), typed == challenge.answer {
            return true
        }
        wrong = true
        value = ""
        return false
    }
}

// MARK: - The screen

@MainActor
public struct ParentalGateView: View {

    /// `max-w-sm` on the card, `p-6` inside it, `gap-4` between its rows.
    public static let cardMaxWidth: CGFloat = 384
    public static let cardPadding: CGFloat = 24
    public static let cardGap: CGFloat = 16
    /// `rounded-3xl` on the card, `rounded-2xl` on the field, `p-6` on the scrim.
    public static let cardRadius: CGFloat = 24
    public static let fieldRadius: CGFloat = 16
    public static let scrimPadding: CGFloat = 24
    /// `shadow-2xl` = `0 25px 50px -12px rgb(0 0 0 / 0.25)`.
    public static let cardShadow = CSSShadow(y: 25, blur: 50, opacity: 0.25)
    /// `px-4 py-3` on the field and on both buttons.
    public static let fieldPaddingX: CGFloat = 16
    public static let fieldPaddingY: CGFloat = 12
    public static let buttonPaddingX: CGFloat = 16
    public static let buttonPaddingY: CGFloat = 12
    /// `2px solid` on the field's border, `gap-3` between the two buttons.
    public static let fieldBorder: CGFloat = 2
    public static let buttonGap: CGFloat = 12
    /// `disabled:opacity-40`.
    public static let disabledOpacity: Double = 0.4

    /// One line telling the adult what they are unlocking. The Paywall passes
    /// `Copy.ParentalGate.purchaseReason`.
    public let reason: String
    public let onPass: () -> Void
    public let onCancel: () -> Void

    @State private var model: ParentalGateModel
    @FocusState private var focused: Bool

    /// - Parameter challenge: injected only by tests and previews. In the app it
    ///   is nil and a fresh roll happens here — `_model = State(initialValue:)`
    ///   rather than a `@State` default expression, per shell.md §4.12: the
    ///   default form re-evaluates (and re-draws) on every struct
    ///   re-initialisation even though the value is discarded.
    public init(
        reason: String,
        onPass: @escaping () -> Void,
        onCancel: @escaping () -> Void,
        challenge: GateChallenge? = nil
    ) {
        self.reason = reason
        self.onPass = onPass
        self.onCancel = onCancel
        _model = State(initialValue: ParentalGateModel(challenge: challenge ?? .roll()))
    }

    public var body: some View {
        ZStack {
            // `fixed inset-0 z-50` + `background: rgba(30,20,10,0.55)`.
            Palette.gateScrim
                .ignoresSafeArea()

            card
                .padding(Self.scrimPadding)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .fontDesign(.rounded)
        // `role="dialog" aria-modal="true" aria-label="Espace parents"`.
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityLabel(Text(verbatim: Copy.ParentalGate.title))
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: Self.cardGap) {
            Text(verbatim: Copy.ParentalGate.title)
                .font(Typography.rounded(Typography.Size.lg, Typography.Weight.bold))
                .foregroundStyle(Palette.ink.color)

            Text(verbatim: reason)
                .font(Typography.rounded(Typography.Size.sm))
                .foregroundStyle(Palette.inkGate.color)
                .lineSpacing(
                    Typography.lineSpacing(
                        size: Typography.Size.sm, ratio: Typography.LineHeight.snug))
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(verbatim: model.challenge.question)
                .font(Typography.rounded(Typography.Size.sm, Typography.Weight.semibold))
                .foregroundStyle(Palette.ink.color)

            answerField

            if model.wrong {
                // `role="alert"` — a live region. VoiceOver gets the same beat
                // from the announcement posted in `attempt()`.
                Text(verbatim: Copy.ParentalGate.wrong)
                    .font(Typography.rounded(Typography.Size.sm))
                    .foregroundStyle(Palette.gateError.color)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(spacing: Self.buttonGap) {
                cancelButton
                confirmButton
            }
        }
        .padding(Self.cardPadding)
        .frame(maxWidth: Self.cardMaxWidth)
        .background {
            RoundedRectangle(cornerRadius: Self.cardRadius)
                .fill(Palette.gateCard.color)
                .shadow(
                    color: .black.opacity(Self.cardShadow.opacity),
                    radius: Self.cardShadow.swiftUIRadius,
                    x: 0,
                    y: Self.cardShadow.y
                )
        }
    }

    private var answerField: some View {
        TextField(
            "",
            text: Binding(get: { model.value }, set: { model.type($0) })
        )
        .textFieldStyle(.plain)
        .multilineTextAlignment(.center)
        .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.bold))
        .foregroundStyle(Palette.ink.color)
        .numericKeypad()
        .focused($focused)
        .submitLabel(.done)
        .onSubmit { attempt() }
        .padding(.horizontal, Self.fieldPaddingX)
        .padding(.vertical, Self.fieldPaddingY)
        .background {
            RoundedRectangle(cornerRadius: Self.fieldRadius)
                .fill(Color.white)
                .overlay {
                    RoundedRectangle(cornerRadius: Self.fieldRadius)
                        .strokeBorder(
                            model.wrong ? Palette.gateFieldWrong.color : Palette.gateField.color,
                            lineWidth: Self.fieldBorder
                        )
                }
        }
        // `autoFocus`.
        .onAppear { focused = true }
        .accessibilityLabel(Text(verbatim: model.challenge.question))
    }

    private var cancelButton: some View {
        Button(action: onCancel) {
            Text(verbatim: Copy.ParentalGate.cancel)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.semibold))
                .foregroundStyle(Palette.ink.color)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, Self.buttonPaddingX)
                .padding(.vertical, Self.buttonPaddingY)
                .background(Palette.adultSecondary.color, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var confirmButton: some View {
        Button(action: attempt) {
            Text(verbatim: Copy.ParentalGate.confirm)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, Self.buttonPaddingX)
                .padding(.vertical, Self.buttonPaddingY)
                .background(Palette.green.color, in: Capsule())
                .contentShape(Capsule())
                .opacity(model.canSubmit ? 1 : Self.disabledOpacity)
        }
        .buttonStyle(.plain)
        .disabled(!model.canSubmit)
    }

    /// `submit(e)` — the model decides, the view moves focus and speaks.
    private func attempt() {
        if model.submit() {
            onPass()
            return
        }
        focused = true  // `inputRef.current?.focus()`
        announce(Copy.ParentalGate.wrong)
    }
}

/* -------------------------------------------------------------------------- */
/* Two platform seams, both with a real macOS path so ALUI still builds for the */
/* host suite (D1).                                                            */
/* -------------------------------------------------------------------------- */

extension View {
    /// `inputMode="numeric"` + `autoComplete="off"`. macOS has no software
    /// keyboard, so there is nothing to constrain there — `sanitizeGateInput`
    /// already rejects everything the keypad would have prevented.
    @ViewBuilder
    fileprivate func numericKeypad() -> some View {
        #if os(iOS)
        keyboardType(.numberPad)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
        #else
        autocorrectionDisabled(true)
        #endif
    }
}

/// `role="alert"`'s live region: VoiceOver speaks the error without the adult
/// having to hunt for it.
@MainActor
private func announce(_ message: String) {
    #if canImport(UIKit)
    AccessibilityNotification.Announcement(message).post()
    #endif
}
