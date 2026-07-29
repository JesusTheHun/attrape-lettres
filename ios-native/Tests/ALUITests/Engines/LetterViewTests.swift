import Testing

import ALCore
@testable import ALUI

// The three letter-family views (`FirstLetterView`, `ReadImageView`,
// `LetterMatchView`). `swift test` has no renderer, so what is tested here is
// everything those views hold that is NOT a rule of the shared model: the tile
// palettes and their cycle length, the strings a tile carries, the printed
// word's `uppercase`, the authored `clamp()` triples, and — the one that would
// silently break the game — that the key a tile hands `pick` is the key the
// model judges.
//
// Every asserted value comes from the TypeScript:
//   src/exercises/FirstLetterExercise.tsx
//   src/exercises/ReadImageExercise.tsx
//   src/exercises/LetterMatchExercise.tsx
// (their `TILE_COLORS` arrays, `ariaLabel`/`previewLabel` template strings, the
// inline `clamp()` styles and the Tailwind classes on the exercise column).

// MARK: - FirstLetter

@MainActor
@Suite struct FirstLetterViewTests {

    /// ```tsx
    /// const TILE_COLORS = [
    ///   { bg: "#FF8A65", ink: "#4A2317" },
    ///   { bg: "#FFD54F", ink: "#4A3B00" },
    ///   { bg: "#4FC3F7", ink: "#062E3D" },
    /// ];
    /// ```
    /// THREE paints — so the cycle is `i % 3`. A row of four would wear
    /// `#FF8A65` again, never the green `#AED581` that only ReadImage reaches.
    @Test func paletteIsTheThreePaintPrefixAndCyclesOnThree() {
        #expect(FirstLetterStage.palette.count == 3)
        #expect(FirstLetterStage.palette.map(\.bg.hex) == ["#FF8A65", "#FFD54F", "#4FC3F7"])
        #expect(FirstLetterStage.palette.map(\.ink.hex) == ["#4A2317", "#4A3B00", "#062E3D"])
        #expect(FirstLetterStage.paint(at: 3) == FirstLetterStage.palette[0])
        #expect(FirstLetterStage.paint(at: 4).bg.hex == "#FFD54F")
    }

    /// `ariaLabel={`Lettre ${letter}`}`, `previewLabel={`Écouter ${letter}`}`,
    /// content `{letter}`, colour by POSITION in the row.
    @Test func tilesCarryTheLetterItsLabelAndItsPreviewLabel() {
        let round = FirstLetterRound(
            target: LetterWord(letter: "M", word: "Maison", emoji: "🏠"),
            choices: ["P", "M", "A"]
        )
        let tiles = FirstLetterStage.tiles(for: round)

        #expect(tiles.map(\.letter) == ["P", "M", "A"])
        #expect(tiles.map(\.label) == ["Lettre P", "Lettre M", "Lettre A"])
        #expect(tiles.map(\.previewLabel) == ["Écouter P", "Écouter M", "Écouter A"])
        #expect(tiles.map(\.paint.bg.hex) == ["#FF8A65", "#FFD54F", "#4FC3F7"])
    }

    /// `<WordIcon … size="clamp(80px,28vw,150px)" />`.
    @Test func pictureUsesTheAuthoredClamp() {
        #expect(FirstLetterStage.pictureSize == FluidSpec(min: 80, vw: 28, max: 150))
    }

    /// The row's key is the one the engine judges (`target.letter`): the tile
    /// whose letter IS the target is accepted, any other is a miss that greys
    /// the round's star at pointerdown (invariant 8).
    @Test func theTileKeyIsTheKeyTheModelJudges() throws {
        let seed: UInt64 = 11

        let accepting = EngineHarness()
        let winner = SinglePickModel.firstLetter(
            level: 1, deps: accepting.deps, rng: .seeded(seed))
        let target = winner.current.target.letter
        let tiles = FirstLetterStage.tiles(for: winner.current)
        let win = try #require(tiles.first { $0.letter == target })
        #expect(FirstLetterStage.pick(win, in: winner) == .accept)
        #expect(winner.flash == target)
        #expect(accepting.confetti.count == 1)
        // `highlight={flash === letter}` — the winner only.
        #expect(FirstLetterStage.isHighlighted(win, in: winner))
        #expect(tiles.filter { FirstLetterStage.isHighlighted($0, in: winner) }.count == 1)

        let missing = EngineHarness()
        let loser = SinglePickModel.firstLetter(
            level: 1, deps: missing.deps, rng: .seeded(seed))
        let miss = try #require(
            FirstLetterStage.tiles(for: loser.current).first { $0.letter != target })
        #expect(FirstLetterStage.pick(miss, in: loser) == .reject)
        #expect(loser.stars[0] == false)
        #expect(missing.confetti.count == 0)
    }

    /// `onPreview={() => { audio.unlock(); void audio.say(letter); }}` — the
    /// audition speaks the LETTER and commits nothing: no verdict, no star, no
    /// advance.
    @Test func previewSpeaksTheLetterAndCommitsNothing() async throws {
        let harness = EngineHarness()
        let model = SinglePickModel.firstLetter(level: 1, deps: harness.deps, rng: .seeded(5))
        let tile = try #require(FirstLetterStage.tiles(for: model.current).first)

        FirstLetterStage.preview(tile, in: model)

        #expect(harness.audio.events.contains(.unlock))
        await eventually { harness.audio.sayTexts.contains(tile.letter) }
        #expect(harness.audio.sayTexts.contains(tile.letter))
        #expect(model.stars.allSatisfy { $0 })
        #expect(model.idx == 0)
        #expect(model.flash == nil)
    }
}

// MARK: - ReadImage

@MainActor
@Suite struct ReadImageViewTests {

    /// ReadImage's `TILE_COLORS` is the FULL five-paint ramp (level 4 shows five
    /// pictures), cycled `i % 5`.
    @Test func paletteIsAllFivePaintsAndCyclesOnFive() {
        #expect(ReadImageStage.palette.count == 5)
        #expect(
            ReadImageStage.palette.map(\.bg.hex)
                == ["#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8"])
        #expect(ReadImageStage.palette.map(\.ink.hex).last == "#2C1846")
        #expect(ReadImageStage.paint(at: 5) == ReadImageStage.palette[0])
    }

    /// `className="… uppercase …"` on the printed word — while the tile's
    /// `aria-label` keeps the authored casing (`Image : ${choice.word}`), because
    /// that string is data, not typography.
    @Test func theWordIsPrintedUppercaseButLabelledAsAuthored() {
        let gateau = LetterWord(letter: "G", word: "Gâteau", emoji: "🍰")
        let round = ReadImageRound(target: gateau, choices: [gateau])

        #expect(ReadImageStage.displayWord(round) == "GÂTEAU")
        #expect(ReadImageStage.tiles(for: round).map(\.label) == ["Image : Gâteau"])
        #expect(ReadImageStage.tiles(for: round).map(\.previewLabel) == ["Écouter Gâteau"])
    }

    /// A tile carries the picture the way `WordIcon` wants it: the dedicated
    /// drawing when there is one, the emoji as the fallback that is never
    /// dropped.
    @Test func tilesCarryBothTheDrawingAndTheEmojiFallback() {
        let jupe = LetterWord(letter: "J", word: "Jupe", emoji: "👗", img: .jupe)
        let chat = LetterWord(letter: "C", word: "Chat", emoji: "🐱")
        let round = ReadImageRound(target: jupe, choices: [jupe, chat])
        let tiles = ReadImageStage.tiles(for: round)

        #expect(tiles[0].img == .jupe)
        #expect(tiles[0].emoji == "👗")
        #expect(tiles[1].img == nil)
        #expect(tiles[1].emoji == "🐱")
        #expect(tiles.map(\.word) == ["Jupe", "Chat"])
    }

    /// `fontSize: "clamp(38px,11vw,68px)"` on the word, `size="clamp(60px,19vw,104px)"`
    /// on a tile's picture, `my-1.5` around the FitLine, `mb-1` under the consigne.
    @Test func authoredSizesAndMargins() {
        #expect(ReadImageStage.wordSize == FluidSpec(min: 38, vw: 11, max: 68))
        #expect(ReadImageStage.pictureSize == FluidSpec(min: 60, vw: 19, max: 104))
        #expect(ReadImageStage.wordMargin == 6)
        #expect(ReadImageStage.headlineBottomMargin == 4)
    }

    /// The row's key is `choice.word` — what `buildReadImageSession`'s target is
    /// judged on.
    @Test func theTileKeyIsTheWordTheModelJudges() throws {
        let seed: UInt64 = 3

        let accepting = EngineHarness()
        let winner = SinglePickModel.readImage(level: 1, deps: accepting.deps, rng: .seeded(seed))
        let target = winner.current.target.word
        let win = try #require(
            ReadImageStage.tiles(for: winner.current).first { $0.word == target })
        #expect(ReadImageStage.pick(win, in: winner) == .accept)
        #expect(winner.flash == target)
        #expect(ReadImageStage.isHighlighted(win, in: winner))

        let missing = EngineHarness()
        let loser = SinglePickModel.readImage(level: 1, deps: missing.deps, rng: .seeded(seed))
        let miss = try #require(
            ReadImageStage.tiles(for: loser.current).first { $0.word != target })
        #expect(ReadImageStage.pick(miss, in: loser) == .reject)
        #expect(loser.stars[0] == false)
    }

    /// The audition speaks the picture's WORD — the only place the word is
    /// voiced before the success line, and only when the child asks for it.
    @Test func previewSpeaksTheTilesWord() async throws {
        let harness = EngineHarness()
        let model = SinglePickModel.readImage(level: 1, deps: harness.deps, rng: .seeded(9))
        let tile = try #require(ReadImageStage.tiles(for: model.current).first)

        ReadImageStage.preview(tile, in: model)

        await eventually { harness.audio.sayTexts.contains(tile.word) }
        #expect(harness.audio.sayTexts.contains(tile.word))
        // The consigne never names the target (that would give the answer away).
        #expect(model.promptText == Levels.readImagePrompt)
    }
}

// MARK: - LetterMatch

@MainActor
@Suite struct LetterMatchViewTests {

    /// LetterMatch's own `TILE_COLORS`: four paints, and the fourth is the
    /// STANDARD green `#AED581/#213606` — not the lighter `#A5D6A7/#123B18` the
    /// syllable grid substitutes at that index.
    @Test func paletteIsTheFourPaintPrefixWithTheStandardGreen() {
        #expect(LetterMatchStage.palette.count == 4)
        #expect(
            LetterMatchStage.palette.map(\.bg.hex)
                == ["#FF8A65", "#FFD54F", "#4FC3F7", "#AED581"])
        #expect(LetterMatchStage.paint(at: 3).ink.hex == "#213606")
        #expect(LetterMatchStage.paint(at: 3) != Palette.gridTileColors[3])
        #expect(LetterMatchStage.paint(at: 4) == LetterMatchStage.palette[0])
    }

    /// A tile is labelled by its FORM (`faceLabel`), auditioned by its NAME
    /// (`Écouter ${face.base}`) and picked by its IDENTITY (`face.base`) — three
    /// different strings off the same face, and the glyph is none of them.
    @Test func tilesAreLabelledByFormAndPickedByIdentity() {
        let round = LetterMatchRound(
            prompt: LetterFace(base: "A", glyph: "A", script: .print),
            choices: [
                LetterFace(base: "B", glyph: "b", script: .cursive),
                LetterFace(base: "A", glyph: "a", script: .cursive),
            ]
        )
        let tiles = LetterMatchStage.tiles(for: round)

        #expect(tiles.map(\.label) == ["Lettre B minuscule attachée", "Lettre A minuscule attachée"])
        #expect(tiles.map(\.previewLabel) == ["Écouter B", "Écouter A"])
        #expect(tiles.map(\.pickKey) == ["B", "A"])
        // The glyph is what gets DRAWN, in its own script — and it is not the key.
        #expect(tiles.map(\.face.glyph) == ["b", "a"])
        #expect(tiles.map(\.face.script) == [.cursive, .cursive])
    }

    /// `fontSize: "clamp(80px,28vw,150px)"` on the prompt glyph.
    @Test func promptGlyphUsesTheAuthoredClamp() {
        #expect(LetterMatchStage.promptSize == FluidSpec(min: 80, vw: 28, max: 150))
    }

    /// The row hands `pick` the letter's BASE. On a majuscule→minuscule round
    /// the winning tile's glyph is « a » and its base is « A »: keying the row
    /// on the glyph would make that round unwinnable, so this asserts both
    /// directions on the same seeded session.
    @Test func theRowIsKeyedOnTheBaseNotTheDrawnGlyph() throws {
        // Find a seed whose first round draws its tiles in lowercase, i.e. where
        // glyph and base actually differ (the direction is drawn per round).
        var found: (seed: UInt64, round: LetterMatchRound)?
        for seed in UInt64(1)..<60 {
            let probe = SinglePickModel.letterMatch(
                exercise: .matchCase, kind: .case, level: 1,
                deps: EngineHarness().deps, rng: .seeded(seed))
            if probe.current.choices.allSatisfy({ $0.glyph != $0.base }) {
                found = (seed, probe.current)
                break
            }
        }
        let (seed, round) = try #require(found)
        let base = round.prompt.base
        let winning = try #require(
            LetterMatchStage.tiles(for: round).first { $0.pickKey == base })
        #expect(winning.face.glyph == base.lowercased())

        let accepting = EngineHarness()
        let winner = SinglePickModel.letterMatch(
            exercise: .matchCase, kind: .case, level: 1,
            deps: accepting.deps, rng: .seeded(seed))
        #expect(LetterMatchStage.pick(winning, in: winner) == .accept)
        #expect(winner.flash == base)
        #expect(LetterMatchStage.isHighlighted(winning, in: winner))

        // The same tile, keyed on what it DRAWS: a miss.
        let missing = EngineHarness()
        let loser = SinglePickModel.letterMatch(
            exercise: .matchCase, kind: .case, level: 1,
            deps: missing.deps, rng: .seeded(seed))
        #expect(loser.pick(winning.face.glyph) == .reject)
        #expect(loser.stars[0] == false)
    }

    /// The audition speaks `face.base` — « A », never the drawn « a » (and
    /// never the cursive form, which has no separate name).
    @Test func previewSpeaksTheLettersNameNotItsGlyph() async throws {
        let harness = EngineHarness()
        let model = SinglePickModel.letterMatch(
            exercise: .matchScript, kind: .script, level: 1,
            deps: harness.deps, rng: .seeded(4))
        let tile = try #require(LetterMatchStage.tiles(for: model.current).first)

        LetterMatchStage.preview(tile, in: model)

        await eventually { harness.audio.sayTexts.contains(tile.face.base) }
        #expect(harness.audio.sayTexts == [tile.face.base])
        #expect(tile.face.base == tile.face.base.uppercased())
    }
}

// MARK: - The shared exercise column

@MainActor
@Suite struct LetterStageMetricsTests {

    /// `relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`
    /// and `gap-4` on the tile row — the Tailwind classes, resolved to points.
    @Test func theColumnMetricsAreTheTailwindClasses() {
        #expect(LetterStageMetrics.paddingX == 16)   // px-4
        #expect(LetterStageMetrics.paddingTop == 8)  // pt-2
        #expect(LetterStageMetrics.paddingBottom == 32)  // pb-8
        #expect(LetterStageMetrics.tileGap == 16)  // gap-4
        #expect(LetterStageMetrics.zIndex == 41)  // z-[41]
        #expect(LetterStageMetrics.promptMargin == 6)  // margin: "6px 0"
        #expect(LetterStageMetrics.promptLineHeight == 1.1)  // lineHeight: 1.1
    }

    /// `mb-6 rounded-full bg-white/70 px-5 py-2 text-lg font-bold …` on the 🔊
    /// pill, plus Tailwind's `shadow`
    /// (`0 1px 3px rgba(0,0,0,.1), 0 1px 2px -1px rgba(0,0,0,.1)`).
    @Test func theListenPillMetricsAreTheTailwindClasses() {
        #expect(LetterStageMetrics.listenBottomMargin == 24)  // mb-6
        #expect(LetterStageMetrics.listenPaddingX == 20)  // px-5
        #expect(LetterStageMetrics.listenPaddingY == 8)  // py-2
        #expect(LetterStageMetrics.listenShadow == CSSShadow(y: 1, blur: 3, opacity: 0.1))
        #expect(LetterStageMetrics.listenShadowTight == CSSShadow(y: 1, blur: 2, opacity: 0.1))
    }

    /// The three engines' tap targets are `Tile`'s default — the 92 pt
    /// accessibility floor (invariant 6). None of them narrows it.
    @Test func tilesKeepTheAccessibilityFloor() {
        #expect(TileMetrics.defaultSize.min == Copy.Tile.minimumSide)
        #expect(TileMetrics.defaultSize == FluidSpec(min: 92, vw: 27, max: 150))
    }
}
