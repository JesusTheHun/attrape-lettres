import CoreGraphics
import Foundation
import Testing

import ALCore
@testable import ALUI

// The three sound-ladder VIEWS: `FindSoundView`, `SyllableGridView` (both modes)
// and `SoundTwinsView`. What is asserted here is only what the views own — the
// palettes, the authored clamps, the shown/judged/spoken split per tile, the
// accessibility labels and the `vowel`-mode gap. The loop, the cooldown, the
// star greying and the award belong to `SinglePickModel` / `TwinsModel` and are
// asserted in their own suites; nothing below re-states them.
//
// Every expected number and string was read out of
// `src/exercises/FindSoundExercise.tsx`, `SyllableGridExercise.tsx` and
// `SoundTwinsExercise.tsx` — never back out of the Swift implementation.

/// `clamp()` resolution compares floats.
private func near(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) < 0.001 }

// MARK: - Find the sound

@Suite("FindSoundView — the row's data rules")
struct FindSoundViewTests {

    /// ```tsx
    /// const TILE_COLORS = [
    ///   { bg: "#FF8A65", ink: "#4A2317" },
    ///   { bg: "#FFD54F", ink: "#4A3B00" },
    ///   { bg: "#4FC3F7", ink: "#062E3D" },
    /// ];
    /// ```
    /// Three paints, indexed `i % 3`.
    @Test func paletteIsTheAuthoredThreePaintsCycled() {
        #expect(FindSoundMetrics.tileColorCount == 3)
        #expect(FindSoundMetrics.paint(at: 0).bg.hex == "#FF8A65")
        #expect(FindSoundMetrics.paint(at: 0).ink.hex == "#4A2317")
        #expect(FindSoundMetrics.paint(at: 1).bg.hex == "#FFD54F")
        #expect(FindSoundMetrics.paint(at: 1).ink.hex == "#4A3B00")
        #expect(FindSoundMetrics.paint(at: 2).bg.hex == "#4FC3F7")
        #expect(FindSoundMetrics.paint(at: 2).ink.hex == "#062E3D")
        // The cycle is 3, not the shared ramp's 5: a fourth tile is coral again,
        // never the ramp's green.
        #expect(FindSoundMetrics.paint(at: 3).bg.hex == "#FF8A65")
        #expect(FindSoundMetrics.paint(at: 4).bg.hex == "#FFD54F")
        #expect(FindSoundMetrics.paint(at: 3).bg.hex != Palette.tileColors[3].bg.hex)
    }

    /// The tile SHOWS « OU », is JUDGED by « OU », and SPEAKS « ou ». The graphy
    /// and the sound are different strings with different clips (D17).
    @Test func tileShowsTheGraphyAndSpeaksTheSound() {
        let ou = BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉")
        #expect(FindSoundMetrics.tileFace(ou) == "OU")
        #expect(FindSoundMetrics.pickKey(ou) == "OU")
        #expect(FindSoundMetrics.previewText(ou) == "ou")
        #expect(FindSoundMetrics.previewText(ou) != FindSoundMetrics.tileFace(ou))
    }

    /// `ariaLabel={`Son ${choice.graphy}`}` / `previewLabel={`Écouter …`}` —
    /// « Son », where the two syllable drills say « Syllabe ».
    @Test func accessibilityLabelsUseTheSoundWording() {
        let ch = BasicSound(sound: "che", graphy: "CH", word: "chat", emoji: "🐱")
        #expect(FindSoundMetrics.tileLabel(ch) == "Son CH")
        #expect(FindSoundMetrics.previewLabel(ch) == "Écouter CH")
    }

    /// `<WordIcon size="clamp(80px,28vw,150px)" />`, `margin: "6px 0"`,
    /// `mb-6` on the button, `gap-4` between tiles.
    @Test func authoredMetrics() {
        #expect(FindSoundMetrics.wordIconSize == FluidSpec(min: 80, vw: 28, max: 150))
        #expect(near(FindSoundMetrics.wordIconSize.resolve(viewport: 390), 109.2))
        #expect(near(FindSoundMetrics.wordIconSize.resolve(viewport: 200), 80))
        #expect(near(FindSoundMetrics.wordIconSize.resolve(viewport: 1024), 150))
        #expect(FindSoundMetrics.wordIconMarginY == 6)
        #expect(FindSoundMetrics.listenBottom == 24)
        #expect(FindSoundMetrics.tileGap == 16)
    }

    /// The TSX passes no `size`, so find-sound tiles keep `Tile`'s default —
    /// the `clamp(92px,27vw,150px)` that IS invariant 6's floor.
    @Test func tilesKeepTheDefault92ptFloor() {
        #expect(TileMetrics.defaultSize.min == Copy.Tile.minimumSide)
        #expect(near(TileMetrics.defaultSize.resolve(viewport: 320), 92))
    }

    /// A real round wired the way the view wires it: one paint per choice, in
    /// row order, cycling at three.
    @Test func aRealRoundGetsPaintsInRowOrder() throws {
        let session = Levels.buildFindSoundSession(level: 2, .seeded(11))
        let round = try #require(session.first)
        let slots = SoundEngineChrome.slots(round.choices, id: \.graphy)
        #expect(slots.map(\.index) == Array(0..<round.choices.count))
        #expect(slots.map(\.id) == round.choices.map(\.graphy))
        for slot in slots {
            #expect(
                FindSoundMetrics.paint(at: slot.index).bg.hex
                    == Palette.tileColors[slot.index % 3].bg.hex)
        }
    }
}

// MARK: - The syllable grid (both drills)

@Suite("SyllableGridView — one engine, two drills")
struct SyllableGridViewTests {

    private let va = GridSyllable(text: "VA", sound: "va", consonant: "V", vowel: "A")
    private let vi = GridSyllable(text: "VI", sound: "vi", consonant: "V", vowel: "I")

    /// ```tsx
    /// const TILE_COLORS = [
    ///   { bg: "#FF8A65", ink: "#4A2317" },
    ///   { bg: "#FFD54F", ink: "#4A3B00" },
    ///   { bg: "#4FC3F7", ink: "#062E3D" },
    ///   { bg: "#A5D6A7", ink: "#123B18" },
    /// ];
    /// ```
    /// Four paints — and the fourth green is this file's own, not the shared
    /// ramp's `#AED581`/`#213606`.
    @Test func paletteIsFourPaintsWithTheAuthoredGreenException() {
        #expect(SyllableGridMetrics.tileColorCount == 4)
        #expect(SyllableGridMetrics.paint(at: 0).bg.hex == "#FF8A65")
        #expect(SyllableGridMetrics.paint(at: 1).bg.hex == "#FFD54F")
        #expect(SyllableGridMetrics.paint(at: 2).bg.hex == "#4FC3F7")
        #expect(SyllableGridMetrics.paint(at: 3).bg.hex == "#A5D6A7")
        #expect(SyllableGridMetrics.paint(at: 3).ink.hex == "#123B18")
        #expect(SyllableGridMetrics.paint(at: 3).bg.hex != Palette.tileColors[3].bg.hex)
        // Level 4+ shows five and six tiles; the fifth wraps to coral.
        #expect(SyllableGridMetrics.paint(at: 4).bg.hex == "#FF8A65")
        #expect(SyllableGridMetrics.paint(at: 5).bg.hex == "#FFD54F")
    }

    /// `hear`: the tile IS the syllable. `vowel`: the tile is the vowel — but
    /// the pick key, the flash key and the label stay the WHOLE syllable.
    @Test func modeChangesTheFaceAndNothingElse() {
        #expect(SyllableGridMetrics.tileFace(va, mode: .hear) == "VA")
        #expect(SyllableGridMetrics.tileFace(va, mode: .vowel) == "A")
        #expect(SyllableGridMetrics.pickKey(va) == "VA")
        #expect(SyllableGridMetrics.tileLabel(va) == "Syllabe VA")
        #expect(SyllableGridMetrics.previewLabel(va) == "Écouter VA")
        // The audition is the whole syllable in both modes — hearing VA next to
        // VI is the drill; a bare « a » would teach nothing.
        #expect(SyllableGridMetrics.previewText(va) == "va")
        #expect(SyllableGridMetrics.previewText(vi) == "vi")
    }

    /// The `vowel`-mode gap: dashed and empty while `flash == nil`, a white card
    /// showing the target's vowel once a pick has been accepted. The TSX tests
    /// the TRUTHINESS of `flash`, not equality with the target — ported as-is.
    @Test func theVowelGapFollowsFlashTruthiness() {
        #expect(SyllableGridMetrics.gap(target: va, flash: nil) == .init(text: "", filled: false))
        #expect(SyllableGridMetrics.gap(target: va, flash: "VA") == .init(text: "A", filled: true))
        #expect(
            SyllableGridMetrics.gap(target: va, flash: "VI") == .init(text: "A", filled: true),
            Comment(rawValue: "`flash ? target.vowel : \"\"` — any non-nil flash fills it"))
        // The gap shows the TARGET's vowel, never the flash key's own letters.
        #expect(SyllableGridMetrics.gap(target: vi, flash: "VI").text == "I")
    }

    /// The group carries the label; the box is `aria-hidden`.
    @Test func halfSyllableLabelNamesTheConsonant() {
        #expect(SyllableGridMetrics.halfSyllableLabel(va) == "Syllabe à compléter : V")
        let che = GridSyllable(text: "CHÉ", sound: "ché", consonant: "CH", vowel: "É")
        #expect(SyllableGridMetrics.halfSyllableLabel(che) == "Syllabe à compléter : CH")
    }

    /// `fontSize: "clamp(38px,11vw,64px)"`, the gap's em geometry,
    /// `mt-2`/`gap-2`, `mt-3 mb-6` on the button, `gap-3` between tiles,
    /// `size="clamp(62px,17vw,96px)"`, `fontSize="clamp(26px,7vw,46px)"`.
    @Test func authoredMetrics() {
        #expect(SyllableGridMetrics.syllableFontSize == FluidSpec(min: 38, vw: 11, max: 64))
        #expect(near(SyllableGridMetrics.syllableFontSize.resolve(viewport: 390), 42.9))
        #expect(SyllableGridMetrics.halfSyllableTop == 8)
        #expect(SyllableGridMetrics.halfSyllableGap == 8)
        #expect(SyllableGridMetrics.gapMinWidthEm == 0.9)
        #expect(SyllableGridMetrics.gapHeightEm == 1.1)
        #expect(SyllableGridMetrics.gapPaddingXEm == 0.1)
        #expect(SyllableGridMetrics.gapCornerRadius == 16)
        #expect(SyllableGridMetrics.gapBorderWidth == 4)
        #expect(SyllableGridMetrics.listenTop == 12)
        #expect(SyllableGridMetrics.listenBottom == 24)
        #expect(SyllableGridMetrics.tileGap == 12)
        #expect(SyllableGridMetrics.tileSize == FluidSpec(min: 62, vw: 17, max: 96))
        #expect(SyllableGridMetrics.tileFontSize == FluidSpec(min: 26, vw: 7, max: 46))
        #expect(near(SyllableGridMetrics.tileSize.resolve(viewport: 390), 66.3))
    }

    /// A `vowel`-mode round from the real builder: every tile shares the
    /// target's consonant, so the faces ARE the vowel column — and no two tiles
    /// read the same.
    @Test func aRealVowelRoundShowsTheVowelColumn() throws {
        let session = Levels.buildSyllableGridSession(level: 3, mode: .vowel, .seeded(7))
        let round = try #require(session.first)
        let faces = round.choices.map { SyllableGridMetrics.tileFace($0, mode: .vowel) }
        #expect(faces == round.choices.map(\.vowel))
        #expect(Set(faces).count == faces.count)
        #expect(round.choices.allSatisfy { $0.consonant == round.target.consonant })
        // …while the keys stay whole syllables — consonant + vowel, never the
        // bare face the tile displays.
        let keys = round.choices.map(SyllableGridMetrics.pickKey)
        #expect(keys == round.choices.map(\.text))
        #expect(keys.contains(round.target.text))
        let consonant = round.target.consonant
        #expect(keys.allSatisfy { $0.hasPrefix(consonant) && $0.count > consonant.count })
        #expect(!keys.contains(where: { $0 == round.target.vowel }))
    }
}

// MARK: - Sound twins

@Suite("SoundTwinsView — the collection strip and the tray ramp")
struct SoundTwinsViewTests {

    private func tile(_ text: String, _ sound: String, _ word: String, correct: Bool) -> TwinTile {
        TwinTile(id: 1, text: text, sound: sound, word: word, emoji: "🐓", correct: correct)
    }

    /// ```tsx
    /// const TILE_COLORS = [
    ///   { bg: "#4FC3F7", ink: "#062E3D" },
    ///   { bg: "#AED581", ink: "#213606" },
    ///   { bg: "#FFD54F", ink: "#4A3B00" },
    ///   { bg: "#BA9EE8", ink: "#2C1846" },
    ///   { bg: "#FF8A65", ink: "#4A2317" },
    /// ];
    /// ```
    /// The TRAY rotation — blue first. A twins tile at index 0 is therefore a
    /// different colour from a find-sound tile at index 0.
    @Test func paletteIsTheTrayRampBlueFirst() {
        #expect(SoundTwinsMetrics.tileColorCount == 5)
        #expect(SoundTwinsMetrics.paint(at: 0).bg.hex == "#4FC3F7")
        #expect(SoundTwinsMetrics.paint(at: 0).ink.hex == "#062E3D")
        #expect(SoundTwinsMetrics.paint(at: 1).bg.hex == "#AED581")
        #expect(SoundTwinsMetrics.paint(at: 2).bg.hex == "#FFD54F")
        #expect(SoundTwinsMetrics.paint(at: 3).bg.hex == "#BA9EE8")
        #expect(SoundTwinsMetrics.paint(at: 4).bg.hex == "#FF8A65")
        #expect(SoundTwinsMetrics.paint(at: 5).bg.hex == "#4FC3F7")
        #expect(SoundTwinsMetrics.paint(at: 0).bg.hex != FindSoundMetrics.paint(at: 0).bg.hex)
    }

    /// Each tile auditions ITS OWN family's sound — the intruder included. If a
    /// tile spoke the round's target instead, every tile would sound right and
    /// the exercise would be unplayable by ear.
    @Test func everyTileAuditionsItsOwnSound() {
        let co = tile("CO", "ko", "coq", correct: true)
        let so = tile("SO", "so", "sol", correct: false)
        #expect(SoundTwinsMetrics.previewText(co) == "ko")
        #expect(SoundTwinsMetrics.previewText(so) == "so")
        #expect(SoundTwinsMetrics.tileFace(so) == "SO")
        #expect(SoundTwinsMetrics.tileLabel(so) == "Syllabe SO")
        #expect(SoundTwinsMetrics.previewLabel(so) == "Écouter SO")
    }

    /// `mt-2 mb-4` on the button and a hard-coded « 🔊 Écouter » — `TwinsModel`
    /// exposes no `listenText` because only the single-pick family varies it.
    @Test func listenButtonTextAndSpacing() {
        #expect(SoundTwinsMetrics.listenText == "🔊 Écouter")
        #expect(SoundTwinsMetrics.listenTop == 8)
        #expect(SoundTwinsMetrics.listenBottom == 16)
    }

    /// The strip slot: `minWidth: clamp(56px,16vw,84px)`,
    /// `height: clamp(48px,13vw,64px)`, `fontSize: clamp(20px,5.5vw,32px)`,
    /// `padding: "0 10px"`, `borderRadius: 18`, `gap-1`, `3px dashed`, and the
    /// anchor emoji at `0.8em`.
    @Test func authoredStripMetrics() {
        #expect(SoundTwinsMetrics.slotMinWidth == FluidSpec(min: 56, vw: 16, max: 84))
        #expect(SoundTwinsMetrics.slotHeight == FluidSpec(min: 48, vw: 13, max: 64))
        #expect(SoundTwinsMetrics.slotFontSize == FluidSpec(min: 20, vw: 5.5, max: 32))
        #expect(near(SoundTwinsMetrics.slotMinWidth.resolve(viewport: 390), 62.4))
        #expect(near(SoundTwinsMetrics.slotHeight.resolve(viewport: 390), 50.7))
        #expect(near(SoundTwinsMetrics.slotFontSize.resolve(viewport: 390), 21.45))
        #expect(SoundTwinsMetrics.slotPaddingX == 10)
        #expect(SoundTwinsMetrics.slotCornerRadius == 18)
        #expect(SoundTwinsMetrics.slotInnerGap == 4)
        #expect(SoundTwinsMetrics.slotBorderWidth == 3)
        #expect(SoundTwinsMetrics.stripBottom == 24)
        #expect(SoundTwinsMetrics.stripGap == 8)
        #expect(SoundTwinsMetrics.slotEmojiEm == 0.8)
        #expect(near(SoundTwinsMetrics.slotEmojiFontSize(base: 32), 25.6))
        #expect(near(SoundTwinsMetrics.slotEmojiFontSize(base: 20), 16))
    }

    @Test func authoredTileMetrics() {
        #expect(SoundTwinsMetrics.tileGap == 12)
        #expect(SoundTwinsMetrics.tileSize == FluidSpec(min: 60, vw: 17, max: 92))
        #expect(SoundTwinsMetrics.tileFontSize == FluidSpec(min: 24, vw: 6.5, max: 44))
        #expect(near(SoundTwinsMetrics.tileSize.resolve(viewport: 390), 66.3))
        #expect(near(SoundTwinsMetrics.tileFontSize.resolve(viewport: 390), 25.35))
    }

    /// A real round: the strip has one slot per CORRECT tile, and the intruders
    /// get no slot however many there are.
    @Test func stripHasOneSlotPerTwinToFind() throws {
        let session = Levels.buildTwinSession(level: 4, .seeded(3))
        let round = try #require(session.first)
        let targets = round.tiles.filter(\.correct)
        #expect(targets.count == round.family.graphies.count)
        #expect(round.tiles.count > targets.count, Comment(rawValue: "level 4 adds 3 intruders"))
    }
}

// MARK: - The chrome the three engines share

@Suite("SoundEngineChrome — shared chrome and wiring")
struct SoundEngineChromeTests {

    /// `relative z-[41] … px-4 pb-8 pt-2` + `mb-1` on the consigne +
    /// `px-5 py-2` on the listen pill — identical in all three TSX files.
    @Test func authoredColumnAndPillMetrics() {
        #expect(SoundEngineChrome.columnPaddingX == 16)
        #expect(SoundEngineChrome.columnPaddingTop == 8)
        #expect(SoundEngineChrome.columnPaddingBottom == 32)
        #expect(SoundEngineChrome.consigneBottom == 4)
        #expect(SoundEngineChrome.listenPaddingX == 20)
        #expect(SoundEngineChrome.listenPaddingY == 8)
        // z-[41] — one above GameFrame's confetti canvas (zIndex 40).
        #expect(SoundEngineChrome.contentZIndex == 41)
    }

    /// The index feeds `TILE_COLORS[i % n]`; the id is the TSX `key`.
    @Test func slotsCarryRowOrderAndTheAuthoredKey() {
        let values = ["OU", "ON", "OI"]
        let slots = SoundEngineChrome.slots(values, id: { $0 })
        #expect(slots.map(\.index) == [0, 1, 2])
        #expect(slots.map(\.id) == values)
        #expect(slots.map(\.value) == values)
        #expect(SoundEngineChrome.slots([String](), id: { $0 }).isEmpty)
    }

    @Test func cssDashedIsProportionalToTheBorderWidth() {
        #expect(SoundEngineChrome.dash(width: 4) == [8, 8])
        #expect(SoundEngineChrome.dash(width: 3) == [6, 6])
    }

    /// Invariant: the run's OWN `ConfettiSystem` gets the burst, and everything
    /// the CALLER supplied — the audio channel, the clock, `award`, the announce
    /// delay — reaches the model untouched, because points may only ever come
    /// from the injected `award` (invariant 8).
    ///
    /// `EngineHost` has no `fireConfetti` member at all, so "the caller's
    /// fireConfetti must not win" is no longer something a test has to check —
    /// there is nothing for a caller to pass. What still needs checking is the
    /// other direction: that wiring the confetti in does not quietly drop or
    /// substitute one of the four things the caller DID pass.
    @MainActor
    @Test func wiringAddsConfettiAndPreservesEverythingElse() async {
        let harness = EngineHarness()
        let system = ConfettiSystem(random: .seeded(5), reduceMotion: FixedReduceMotion(false))
        system.report(size: CGSize(width: 320, height: 620), pixelScale: 2)

        let wired = SoundEngineChrome.wire(harness.host, to: system)
        wired.fireConfetti()

        #expect(system.particles.count == ConfettiSystem.burstCount)
        #expect(
            harness.confetti.count == 0,
            Comment(rawValue: "the burst went somewhere other than this run's system"))

        #expect(wired.audio === harness.audio)
        // `TimeSource` is not class-constrained, so identity is asserted through
        // behaviour: the wired clock still IS the harness's.
        harness.time.advance(millis: 1_234)
        #expect(wired.time.nowMillis == harness.time.nowMillis)
        #expect(wired.award(.findSound, 2, 3, 4) == harness.award.result)
        #expect(harness.award.calls.count == 1)
        #expect(harness.award.calls[0].exercise == .findSound)
        #expect(harness.award.calls[0].level == 2)
        #expect(harness.award.calls[0].perfect == 3)
        #expect(harness.award.calls[0].total == 4)
        await wired.delay(EngineLines.announceDelayMs)
        #expect(harness.delays.requests == [350])
    }

    /// Invariant 6's tap-target floor for the two engines that shrink their
    /// tiles below `Tile`'s default: even on the narrowest phone the authored
    /// `clamp` minimum keeps every tile well over 44 pt.
    @Test func everyAuthoredTileClampClearsTheTapTargetFloor() {
        for viewport in [CGFloat(320), 375, 390, 430, 1024] {
            #expect(SyllableGridMetrics.tileSize.resolve(viewport: viewport) >= 44)
            #expect(SoundTwinsMetrics.tileSize.resolve(viewport: viewport) >= 44)
            #expect(TileMetrics.previewHeight.resolve(viewport: viewport) >= 40)
        }
        // The authored minima themselves, so a future edit that lowers them
        // fails here and not in a pixel diff.
        #expect(SyllableGridMetrics.tileSize.min == 62)
        #expect(SoundTwinsMetrics.tileSize.min == 60)
    }
}
