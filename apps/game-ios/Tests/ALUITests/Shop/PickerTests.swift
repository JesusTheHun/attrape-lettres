import Foundation
import QuartzCore
import Testing

import ALCore

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `Shop/Picker.swift` against `src/shop/Picker.tsx`.                          */
/*                                                                             */
/* ONE component, two variants. `App.tsx` renders `<Picker variant="first-run"  */
/* onDone={…} />` as the whole-app gate; `Dashboard`'s « Changer de copain »    */
/* renders `<Picker variant="switch" onDone onCancel />`. Both are exercised    */
/* here, because the landed file was never run.                                */
/*                                                                             */
/*   const CHOICES = [unicorn Licorne, cat Chat, fox Renard, rabbit Lapin,     */
/*                    dragon Dragon];                                          */
/*   const progress = profile.species[species];                                */
/*   const isCurrent = profile.chosen && profile.current === species;          */
/*   const grown = progress.config.stage > 0 || progress.owned.length > 0;     */
/*   aria-label={`Choisir ${name}`}                                            */
/*   {grown ? `Niveau ${stage + 1}/${GROWTH_STAGES}` : "Tout neuf"}            */
/* -------------------------------------------------------------------------- */

/// The TSX's `CHOICES`, transcribed. Order is render order.
private let tsChoices: [(species: Species, name: String)] = [
    (.unicorn, "Licorne"),
    (.cat, "Chat"),
    (.fox, "Renard"),
    (.rabbit, "Lapin"),
    (.dragon, "Dragon"),
]

/// `GROWTH_STAGES` in `src/types.ts`.
private let tsGrowthStages = 10

private func profile(
    chosen: Bool = true,
    current: Species = .unicorn,
    stages: [Species: Int] = [:],
    owned: [Species: [String]] = [:]
) -> ProfileView {
    var map = blankSpeciesMap()
    for species in Species.allCases {
        var progress = map[species]
        progress.config.stage = stages[species] ?? 0
        progress.owned = owned[species] ?? []
        map[species] = progress
    }
    let persisted = PersistedProfile(
        chosen: chosen,
        current: current,
        currentRev: .zero,
        species: map,
        stars: StarCounters(earned: [:], spent: [:]),
        clears: [:])
    return ProfileView(of: persisted, balance: 0, ledger: [:])
}

private func surface(
    _ variant: PickerVariant,
    canCancel: Bool = false,
    chosen: Bool = true,
    current: Species = .unicorn,
    stages: [Species: Int] = [:],
    owned: [Species: [String]] = [:]
) -> PickerSurface {
    pickerSurface(
        profile: profile(chosen: chosen, current: current, stages: stages, owned: owned),
        variant: variant,
        canCancel: canCancel)
}

@Suite struct PickerChoicesTests {

    /// Invariant 5, at its bluntest: five cards, always, in the authored order,
    /// with no branch that could shorten the list.
    @Test func allFiveFriendsAreOfferedInTheAuthoredOrder() {
        for variant in PickerVariant.allCases {
            let cards = surface(variant).cards
            #expect(cards.count == 5)
            #expect(cards.map(\.species) == tsChoices.map(\.species))
            #expect(cards.map(\.name) == tsChoices.map(\.name))
        }
    }

    /// No gating: a brand-new profile at stage 0 with nothing owned still gets
    /// every species, and none of them is marked unavailable in any way the
    /// surface can express.
    @Test func aBrandNewProfileCanPickAnySpecies() {
        let cards = surface(.firstRun, chosen: false).cards
        #expect(cards.count == 5)
        #expect(cards.allSatisfy { $0.caption == "Tout neuf" })
        #expect(cards.allSatisfy { $0.isCurrent == false })
    }

    /// `aria-label={`Choisir ${name}`}`.
    @Test func everyCardCarriesItsAccessibilityLabel() {
        let cards = surface(.switchMascot).cards
        #expect(
            cards.map(\.accessibilityLabel) == [
                "Choisir Licorne", "Choisir Chat", "Choisir Renard", "Choisir Lapin",
                "Choisir Dragon",
            ])
    }

    /// `grown = stage > 0 || owned.length > 0`, and the caption is
    /// `Niveau ${stage + 1}/${GROWTH_STAGES}` — ONE-BASED, so a stage-3 mascot
    /// reads « Niveau 4/10 ».
    @Test func captionIsOneBasedAgainstGrowthStages() {
        let cards = surface(.switchMascot, stages: [.fox: 3, .dragon: tsGrowthStages - 1]).cards
        #expect(cards[2].caption == "Niveau 4/10")
        #expect(cards[4].caption == "Niveau 10/10")
        #expect(cards[0].caption == "Tout neuf")
        #expect(tsGrowthStages == growthStages)
    }

    /// « Owning an item counts as played even at stage 0 » — the `||` half of
    /// `grown`, which a stage-only test would miss entirely.
    @Test func ownedItemsMakeAStageZeroMascotGrown() {
        let cards = surface(.switchMascot, owned: [.cat: ["cat.collar.red"]]).cards
        #expect(cards[1].caption == "Niveau 1/10")
        #expect(cards[0].caption == "Tout neuf")
    }

    /// `isCurrent = profile.chosen && profile.current === species` — TWO
    /// conditions. Before a species has ever been chosen, `current` still points
    /// at the default (`unicorn`) and no card may wear the badge.
    @Test func currentNeedsBothChosenAndAMatch() {
        let picked = surface(.switchMascot, chosen: true, current: .rabbit).cards
        #expect(picked.map(\.isCurrent) == [false, false, false, true, false])

        let unchosen = surface(.firstRun, chosen: false, current: .rabbit).cards
        #expect(unchosen.allSatisfy { $0.isCurrent == false })
    }

    /// « Each card shows that mascot at ITS real current look » — never the
    /// active species' config. The fox card must carry the fox's stage even
    /// while the unicorn is current.
    @Test func eachCardCarriesItsOwnSpeciesConfig() {
        let cards = surface(
            .switchMascot, current: .unicorn, stages: [.unicorn: 1, .fox: 7, .dragon: 4]
        ).cards
        #expect(cards.map(\.config.species) == tsChoices.map(\.species))
        #expect(cards.map(\.config.stage) == [1, 0, 7, 0, 4])
    }
}

@Suite struct PickerVariantTests {

    /// `switching ? "🔄" : "🥚"`, and the two titles/subtitles, byte for byte.
    @Test func firstRunIsTheEggAndTheGateCopy() {
        let s = surface(.firstRun)
        #expect(s.glyph == "🥚")
        #expect(s.title == "Choisis ton copain")
        #expect(s.subtitle == "Il grandira avec toi.")
    }

    @Test func switchIsTheCyclesGlyphAndTheReassuringCopy() {
        let s = surface(.switchMascot)
        #expect(s.glyph == "🔄")
        #expect(s.title == "Change de copain")
        // ASCII apostrophe U+0027 in « l'as », as authored.
        #expect(s.subtitle == "Tu retrouveras chacun comme tu l\u{0027}as laissé.")
    }

    /// `{switching && onCancel && …}` is TWO conditions. The first-run gate has
    /// nowhere to go back to and must never grow an exit — that is the one place
    /// this screen could turn into a locked door.
    @Test func onlyTheSwitchVariantWithACancelHandlerShowsTheWayBack() {
        #expect(surface(.switchMascot, canCancel: true).showsBack == true)
        #expect(surface(.switchMascot, canCancel: false).showsBack == false)
        #expect(surface(.firstRun, canCancel: true).showsBack == false)
        #expect(surface(.firstRun, canCancel: false).showsBack == false)
    }

    /// The variant changes four strings and one glyph — and nothing about what a
    /// card shows.
    @Test func theVariantDoesNotChangeAnyCard() {
        let stages: [Species: Int] = [.cat: 2]
        let owned: [Species: [String]] = [.dragon: ["dragon.hat"]]
        let first = surface(.firstRun, chosen: true, current: .cat, stages: stages, owned: owned)
        let second = surface(
            .switchMascot, canCancel: true, chosen: true, current: .cat, stages: stages,
            owned: owned)
        #expect(first.cards == second.cards)
    }

    /// « ← Retour » with a plain « Retour » as the label VoiceOver reads.
    @Test func backChipCopy() {
        #expect(Copy.Picker.backLabel == "← Retour")
        #expect(Copy.Picker.back == "Retour")
    }

    /// « Actuel ✓ » — U+2713, not an ASCII "v".
    @Test func currentBadgeCopy() {
        #expect(Copy.Picker.current == "Actuel \u{2713}")
    }
}

@MainActor
@Suite struct PickerMetricsTests {

    /// `min-h-[620px] … gap-5 rounded-3xl px-6 pb-10 pt-8`.
    @Test func rootMetricsAreTheTailwindClasses() {
        #expect(PickerMetrics.minHeight == 620)
        #expect(PickerMetrics.rootSpacing == 20)
        #expect(PickerMetrics.cornerRadius == 24)
        #expect(PickerMetrics.paddingX == 24)
        #expect(PickerMetrics.paddingTop == 32)
        #expect(PickerMetrics.paddingBottom == 40)
        #expect(PickerMetrics.subtitleBottomMargin == 4)
    }

    /// `clamp(44px,14vw,72px)` on the glyph and `clamp(26px,8vw,40px)` on the
    /// title — asserted through `fluid`, at a viewport where the middle term
    /// wins, so a swapped min/max cannot pass.
    @Test func fluidTypeMatchesTheClamps() {
        #expect(PickerMetrics.glyphSize == FluidSpec(min: 44, vw: 14, max: 72))
        #expect(PickerMetrics.titleSize == FluidSpec(min: 26, vw: 8, max: 40))
        // …and both clamps actually bite, so a swapped min/max cannot pass.
        #expect(PickerMetrics.glyphSize.resolve(viewport: 200) == 44)
        #expect(PickerMetrics.glyphSize.resolve(viewport: 900) == 72)
        #expect(PickerMetrics.titleSize.resolve(viewport: 200) == 26)
        #expect(PickerMetrics.titleSize.resolve(viewport: 900) == 40)
    }

    /// Invariant 6: the card is a whole-row target — 84 pt of mascot with 20 pt
    /// of padding either side, far past the 44 pt floor.
    @Test func cardIsAGenerousTapTarget() {
        #expect(PickerMetrics.mascotSize == 84)
        #expect(PickerMetrics.cardPadding == 20)
        #expect(PickerMetrics.mascotSize + 2 * PickerMetrics.cardPadding >= 44)
    }

    /// `gap-4` between cards, `gap-5` inside one, `3px` border,
    /// `0 8px 18px rgba(0,0,0,0.10)`, `active:scale-[0.98]`.
    @Test func cardMetricsAreTheTailwindClasses() {
        #expect(PickerMetrics.cardSpacing == 16)
        #expect(PickerMetrics.cardSpacingInner == 20)
        #expect(PickerMetrics.cardBorderWidth == 3)
        #expect(PickerMetrics.cardShadow.y == 8)
        #expect(PickerMetrics.cardShadow.blur == 18)
        #expect(PickerMetrics.cardShadow.opacity == 0.10)
        #expect(PickerMetrics.cardActiveScale == 0.98)
    }

    /// The back chip: `px-4 py-2`, Tailwind `shadow` = `0 1px 3px rgb(0 0 0/0.1)`,
    /// `active:scale-95`.
    @Test func backChipMetrics() {
        #expect(PickerMetrics.backPaddingX == 16)
        #expect(PickerMetrics.backPaddingY == 8)
        #expect(PickerMetrics.backShadow.y == 1)
        #expect(PickerMetrics.backShadow.blur == 3)
        #expect(PickerMetrics.backShadow.opacity == 0.1)
        #expect(PickerMetrics.backActiveScale == 0.95)
    }

    /// « Actuel ✓ »: `px-3 py-1`, `#E6F4E6` on `#2E7D32`; the current card's
    /// border is `3px solid #66BB6A`.
    @Test func badgeAndBorderColours() {
        #expect(PickerMetrics.badgePaddingX == 12)
        #expect(PickerMetrics.badgePaddingY == 4)
        #expect(Palette.currentBadge.hex == "#E6F4E6")
        #expect(Palette.currentBadgeInk.hex == "#2E7D32")
        #expect(Palette.green.hex == "#66BB6A")
    }

    /// `press` in `shop/anim.ts` is `scale(0.94)` over 130 ms — NOT `Tile`'s
    /// 0.9, and unlike `Tile` it IS reduced-motion gated, because `shop/anim.ts`
    /// checks `reducedMotion()` itself.
    @Test func pressIsTheShopsSquishNotTheTiles() throws {
        #expect(PickerMetrics.pressScale == 0.94)
        #expect(PickerMetrics.pressDuration == 0.13)

        let take = PickerAnim.pressAnimation()
        #expect(take.keyPath == "transform.scale")
        // `values` is `[Any]?`, so read the numbers back rather than casting the
        // array — the literals land as a mix of `Int` and `Double` boxes.
        #expect(take.values?.compactMap { ($0 as? NSNumber)?.doubleValue } == [1, 0.94, 1])
        #expect(take.keyTimes?.map(\.doubleValue) == [0, 0.5, 1])
        #expect(take.duration == 0.13)
        #expect(take.timingFunctions?.count == 2)
    }

    /// A nil layer (every macOS run — `LayerHost` is a no-op there) must not trap,
    /// with reduced motion on or off.
    @Test func aNilLayerIsANoOp() {
        PickerAnim.press(nil, reduceMotion: FixedReduceMotion(false))
        PickerAnim.press(nil, reduceMotion: FixedReduceMotion(true))
    }
}
