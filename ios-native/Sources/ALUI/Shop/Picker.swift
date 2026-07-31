import Observation
import QuartzCore
import SwiftUI

import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* `src/shop/Picker.tsx` — choose / switch mascot.                             */
/*                                                                             */
/*   variant "first-run": the whole-app gate — « Choisis ton copain ».          */
/*   variant "switch"   : reachable from the dashboard. Switching is            */
/*                        NON-DESTRUCTIVE — each species keeps its own growth,  */
/*                        look and items, so you can switch back anytime.       */
/* Each card shows that mascot at ITS real current look (grown, dressed).       */
/*                                                                             */
/* ```tsx                                                                      */
/* const CHOICES = [unicorn Licorne, cat Chat, fox Renard, rabbit Lapin,        */
/*                  dragon Dragon];                                            */
/*                                                                             */
/* function Choice({ species, name, onPick }) {                                 */
/*   const progress = profile.species[species];                                 */
/*   const isCurrent = profile.chosen && profile.current === species;            */
/*   const grown = progress.config.stage > 0 || progress.owned.length > 0;       */
/*   … <button aria-label={`Choisir ${name}`} onPointerDown={press}             */
/*              onClick={onPick} border={isCurrent ? 3px #66BB6A : transparent}> */
/*        <Mascot config={progress.config} mood="idle" size={84} />              */
/*        <span>{name}</span>                                                    */
/*        <span>{grown ? `Niveau ${stage + 1}/${GROWTH_STAGES}` : "Tout neuf"}</span>*/
/*        {isCurrent && <span>Actuel ✓</span>}                                   */
/* }                                                                            */
/* ```                                                                          */
/*                                                                             */
/* ONE component, two variants — the TSX is one component and stays one. The     */
/* variant changes four strings, one glyph and whether a « ← Retour » chip is    */
/* offered; it changes nothing about what a card shows or what picking does.     */
/*                                                                             */
/* INVARIANT 5 — no gating. Every species is pickable, always. There is no       */
/* "unlock the dragon at level 4" branch here and there must never be one.       */
/* INVARIANT 6 — each card carries the TSX's `aria-label` (« Choisir Licorne »)  */
/* and is a whole-row target: 84 pt of mascot plus 20 pt of padding on each      */
/* side.                                                                        */
/* -------------------------------------------------------------------------- */

// MARK: - Variant

public enum PickerVariant: String, Equatable, Sendable, CaseIterable {
    /// `variant="first-run"` — the whole-app gate, no way back.
    case firstRun
    /// `variant="switch"` — reached from the dashboard, cancellable.
    case switchMascot
}

// MARK: - The surface (pure, host-tested)

/// One row. Everything a card shows, decided without a renderer.
public struct PickerCard: Equatable, Sendable {
    public let species: Species
    /// `CHOICES[i].name` — view copy; `ALCore.Species` carries no display name.
    public let name: String
    /// `grown ? "Niveau {stage+1}/{GROWTH_STAGES}" : "Tout neuf"`.
    public let caption: String
    /// `profile.chosen && profile.current === species` — the green border and
    /// the « Actuel ✓ » badge.
    public let isCurrent: Bool
    /// `aria-label={`Choisir ${name}`}`.
    public let accessibilityLabel: String
    /// THIS species' own look — « Each card shows that mascot at ITS real
    /// current look ». Never the active species' config.
    public let config: MascotConfig
}

/// The whole screen, as data.
public struct PickerSurface: Equatable, Sendable {
    /// `switching ? "🔄" : "🥚"`.
    public let glyph: String
    public let title: String
    public let subtitle: String
    /// `switching && onCancel` — the « ← Retour » chip. The first-run gate has
    /// nowhere to go back to, so it offers no exit and must not grow one.
    public let showsBack: Bool
    /// The five friends, in the authored order.
    public let cards: [PickerCard]
}

/// Fold the active child's profile into the screen.
///
/// - Parameters:
///   - profile: the ACTIVE child's flattened profile. Read for `chosen`,
///     `current` and each species' own `config`/`owned` — never mutated.
///   - variant: which of the two screens this is.
///   - canCancel: did the caller pass an `onCancel`? (`switching && onCancel &&`
///     in the TSX is two conditions, not one.)
public func pickerSurface(
    profile: ProfileView,
    variant: PickerVariant,
    canCancel: Bool
) -> PickerSurface {
    let switching = variant == .switchMascot
    return PickerSurface(
        glyph: switching ? Copy.Picker.switchGlyph : Copy.Picker.eggGlyph,
        title: switching ? Copy.Picker.switchTitle : Copy.Picker.firstRunTitle,
        subtitle: switching ? Copy.Picker.switchSubtitle : Copy.Picker.firstRunSubtitle,
        showsBack: switching && canCancel,
        cards: Copy.Picker.speciesOrder.map { species in
            let progress = profile.species[species]
            let name = Copy.Picker.name(species)
            // « A mascot that's been played shows real growth; a fresh one is a
            // stage-0 baby. » Owning an item counts as played even at stage 0.
            let grown = progress.config.stage > 0 || !progress.owned.isEmpty
            return PickerCard(
                species: species,
                name: name,
                caption: grown
                    ? Copy.Picker.level(progress.config.stage + 1, of: growthStages)
                    : Copy.Picker.brandNew,
                isCurrent: profile.chosen && profile.current == species,
                accessibilityLabel: Copy.Picker.choose(name),
                config: progress.config)
        })
}

// MARK: - Metrics (the TSX's Tailwind classes and inline styles, verbatim)

public enum PickerMetrics {
    /// Root: `min-h-[620px] w-full flex-col items-center gap-5 rounded-3xl
    /// px-6 pb-10 pt-8`.
    public static let minHeight: CGFloat = Shell.minimumScreenHeight
    public static let rootSpacing: CGFloat = 20        // gap-5
    public static let cornerRadius: CGFloat = 24       // rounded-3xl
    public static let paddingX: CGFloat = 24           // px-6
    public static let paddingTop: CGFloat = 32         // pt-8
    public static let paddingBottom: CGFloat = 40      // pb-10

    /// `clamp(44px,14vw,72px)` — 🥚 / 🔄.
    public static let glyphSize = FluidSpec(min: 44, vw: 14, max: 72)
    /// `clamp(26px,8vw,40px)` — the title.
    public static let titleSize = FluidSpec(min: 26, vw: 8, max: 40)
    /// `mb-1` under the subtitle.
    public static let subtitleBottomMargin: CGFloat = 4

    /// Back chip: `rounded-full bg-white/80 px-4 py-2 text-base font-black
    /// shadow active:scale-95`.
    public static let backPaddingX: CGFloat = 16
    public static let backPaddingY: CGFloat = 8
    public static let backShadow = CSSShadow(y: 1, blur: 3, opacity: 0.1)
    public static let backActiveScale: CGFloat = 0.95

    /// The card column: `flex w-full flex-col gap-4`.
    public static let cardSpacing: CGFloat = 16

    /// A card: `items-center gap-5 rounded-3xl p-5 text-left
    /// active:scale-[0.98]`, `rgba(255,255,255,0.9)`,
    /// `0 8px 18px rgba(0,0,0,0.10)`, `border: 3px solid …`.
    public static let cardPadding: CGFloat = 20
    public static let cardSpacingInner: CGFloat = 20
    public static let cardShadow = CSSShadow(y: 8, blur: 18, opacity: 0.10)
    public static let cardBorderWidth: CGFloat = 3
    public static let cardActiveScale: CGFloat = 0.98

    /// `<Mascot … size={84} />`.
    public static let mascotSize: CGFloat = 84

    /// « Actuel ✓ »: `rounded-full px-3 py-1 text-sm font-black`.
    public static let badgePaddingX: CGFloat = 12
    public static let badgePaddingY: CGFloat = 4

    /// `press` in `shop/anim.ts`: `[scale(1), scale(0.94), scale(1)]`,
    /// 130 ms, ease-out. NOT `Anim.press` — the tile's squish is 0.9 and is
    /// deliberately ungated (D29); the shop's is 0.94 and IS gated, because
    /// `shop/anim.ts` checks `reducedMotion()` itself.
    public static let pressScale: Double = 0.94
    public static let pressDuration: Double = 0.13
}

// MARK: - The view

/// `<Picker onDone onCancel variant />`.
///
/// Reads the roster from the environment, matching `shell.md` §4.1's router
/// sketch (`PickerView(variant: .firstRun) { route = .hub }`).
public struct PickerView: View {
    private let variant: PickerVariant
    private let onDone: () -> Void
    private let onCancel: (() -> Void)?

    @Environment(ProfileStore.self) private var profiles
    @Environment(\.alViewportWidth) private var viewport
    @Environment(\.alReduceMotion) private var reduceMotion

    public init(
        variant: PickerVariant = .firstRun,
        onDone: @escaping () -> Void,
        onCancel: (() -> Void)? = nil
    ) {
        self.variant = variant
        self.onDone = onDone
        self.onCancel = onCancel
    }

    public var surface: PickerSurface {
        pickerSurface(profile: profiles.profile, variant: variant, canCancel: onCancel != nil)
    }

    public var body: some View {
        let surface = self.surface
        return VStack(spacing: PickerMetrics.rootSpacing) {
            if surface.showsBack, let onCancel {
                // `flex w-full` — the chip sits hard left in its own row.
                HStack(spacing: 0) {
                    backChip(onCancel)
                    Spacer(minLength: 0)
                }
            }

            Text(verbatim: surface.glyph)
                .font(.system(size: PickerMetrics.glyphSize.resolve(viewport: viewport)))
                .accessibilityHidden(true)

            Text(verbatim: surface.title)
                .font(
                    Typography.rounded(
                        PickerMetrics.titleSize.resolve(viewport: viewport),
                        Typography.Weight.black)
                )
                .foregroundStyle(Palette.ink.color)
                .multilineTextAlignment(.center)

            Text(verbatim: surface.subtitle)
                .font(Typography.rounded(Typography.Size.base))
                .foregroundStyle(Palette.inkSoft.color)
                .multilineTextAlignment(.center)
                .padding(.bottom, PickerMetrics.subtitleBottomMargin)

            VStack(spacing: PickerMetrics.cardSpacing) {
                ForEach(surface.cards, id: \.species) { card in
                    ChoiceRow(card: card, reduceMotion: reduceMotion) { pick(card.species) }
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: PickerMetrics.minHeight, alignment: .top)
        .padding(.horizontal, PickerMetrics.paddingX)
        .padding(.top, PickerMetrics.paddingTop)
        .padding(.bottom, PickerMetrics.paddingBottom)
        .background(Palette.stageAdult.gradient)
        .clipShape(RoundedRectangle(cornerRadius: PickerMetrics.cornerRadius))
        .fontDesign(.rounded)
        // D46. The stage is pinned to `minHeight` and the card list grows with
        // the roster: on a phone the FIFTH companion sat below the screen with
        // no way to reach it, so one animal could not be chosen at all. On the
        // web the document scrolls and the question never arises.
        .alPageScroll()
    }

    /// `const pick = (s) => { chooseSpecies(s); onDone(); }` — in that order, so
    /// the router's next screen already sees the chosen species.
    private func pick(_ species: Species) {
        profiles.chooseSpecies(species)
        onDone()
    }

    /// Navigation, not gameplay: the TSX uses `onClick`, so this is an ordinary
    /// touch-UP `Button` (the same call the GameFrame's « ← Menu » makes).
    private func backChip(_ onCancel: @escaping () -> Void) -> some View {
        Button(action: onCancel) {
            Text(verbatim: Copy.Picker.backLabel)
                .font(Typography.rounded(Typography.Size.base, Typography.Weight.black))
                .foregroundStyle(Palette.ink.color)
                .padding(.horizontal, PickerMetrics.backPaddingX)
                .padding(.vertical, PickerMetrics.backPaddingY)
                .background {
                    Capsule()
                        .fill(.white.opacity(Palette.White.o80))
                        .shadow(
                            color: .black.opacity(PickerMetrics.backShadow.opacity),
                            radius: PickerMetrics.backShadow.swiftUIRadius,
                            x: 0,
                            y: PickerMetrics.backShadow.y)
                }
        }
        .buttonStyle(PickerActiveScaleStyle(scale: PickerMetrics.backActiveScale))
        .accessibilityLabel(Text(verbatim: Copy.Picker.back))
    }
}

// MARK: - One card

/// A single friend's row. Split out so each card owns its own layer handle for
/// the press squish.
private struct ChoiceRow: View {
    let card: PickerCard
    let reduceMotion: any ReduceMotionSource
    let onPick: () -> Void

    @State private var handle = LayerHandle()
    /// `active:scale-[0.98]` — the CSS active state, which is a SECOND effect on
    /// top of the WAAPI squish; the TSX has both.
    @State private var active = false

    var body: some View {
        LayerHost(handle: handle) {
            HStack(spacing: PickerMetrics.cardSpacingInner) {
                Ollie(
                    config: card.config,
                    mood: .idle,
                    size: PickerMetrics.mascotSize,
                    reduceMotion: reduceMotion)

                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: card.name)
                        .font(Typography.rounded(Typography.Size.xxl, Typography.Weight.black))
                        .foregroundStyle(Palette.ink.color)
                    Text(verbatim: card.caption)
                        .font(Typography.rounded(Typography.Size.sm, Typography.Weight.bold))
                        .foregroundStyle(Palette.inkFaint.color)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if card.isCurrent {
                    Text(verbatim: Copy.Picker.current)
                        .font(Typography.rounded(Typography.Size.sm, Typography.Weight.black))
                        .foregroundStyle(Palette.currentBadgeInk.color)
                        .padding(.horizontal, PickerMetrics.badgePaddingX)
                        .padding(.vertical, PickerMetrics.badgePaddingY)
                        .background(Palette.currentBadge.color, in: Capsule())
                }
            }
            .padding(PickerMetrics.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                .white.opacity(Palette.White.o90),
                in: RoundedRectangle(cornerRadius: PickerMetrics.cornerRadius))
            // `border: 3px solid` — Tailwind's `box-sizing: border-box` puts it
            // INSIDE the box, which is what `strokeBorder` does.
            .overlay {
                RoundedRectangle(cornerRadius: PickerMetrics.cornerRadius)
                    .strokeBorder(
                        card.isCurrent ? Palette.green.color : Color.clear,
                        lineWidth: PickerMetrics.cardBorderWidth)
            }
            .shadow(
                color: .black.opacity(PickerMetrics.cardShadow.opacity),
                radius: PickerMetrics.cardShadow.swiftUIRadius,
                x: 0,
                y: PickerMetrics.cardShadow.y)
        }
        .scaleEffect(active ? PickerMetrics.cardActiveScale : 1)
        .contentShape(RoundedRectangle(cornerRadius: PickerMetrics.cornerRadius))
        .touchDown {
            // `onPointerDown={() => press(ref.current)}` — the squish only. The
            // pick itself is `onClick`, i.e. touch-UP inside, below.
            active = true
            PickerAnim.press(handle.layer, reduceMotion: reduceMotion)
        } onUp: { inside in
            active = false
            if inside { onPick() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: card.accessibilityLabel))
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Styles and animation

/// CSS `active:scale-N` on a touch-UP button.
struct PickerActiveScaleStyle: ButtonStyle {
    let scale: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? scale : 1)
    }
}

/// `press` from `src/shop/anim.ts`, on the hosted layer — off the render path
/// (invariant 2).
///
/// Deliberately NOT `Anim.press`: the shop's squish is `scale(0.94)` and it is
/// reduced-motion GATED (`shop/anim.ts` returns early on
/// `prefers-reduced-motion`), where `Tile`'s is `scale(0.9)` and ungated (D29).
/// Two different authored animations, and the difference is measured, not
/// assumed.
enum PickerAnim {

    @MainActor
    static func press(_ layer: CALayer?, reduceMotion: any ReduceMotionSource) {
        guard let layer, !reduceMotion.isReduced else { return }
        layer.add(pressAnimation(), forKey: "ALPickerPress")
    }

    static func pressAnimation() -> CAKeyframeAnimation {
        let animation = CAKeyframeAnimation(keyPath: "transform.scale")
        animation.values = [1, PickerMetrics.pressScale, 1]
        animation.keyTimes = [0, 0.5, 1].map { NSNumber(value: $0) }
        animation.timingFunctions = (0..<2).map { _ in
            CAMediaTimingFunction(controlPoints: 0, 0, 0.58, 1)  // CSS ease-out
        }
        animation.duration = PickerMetrics.pressDuration
        return animation
    }
}
