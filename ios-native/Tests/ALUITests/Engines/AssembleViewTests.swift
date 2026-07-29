import Testing

import ALCore
@testable import ALUI

// What `AssembleView` OWNS, asserted against `src/exercises/AssembleExercise.tsx`.
//
// Every expected number and string below is read out of the TSX (or, for the
// round shapes, out of `buildSyllableRound` in `src/levels.ts`), never out of
// the Swift implementation. Nothing here re-asserts `AssemblyModel`'s rules —
// the pick loop, the « Oh non » pacing, the award and the star greying are
// covered by `AssemblyModelTests`.
//
// The through-line of the second and third suites is CLAUDE.md's rule that this
// is ONE engine for three modes: the projection is a function of
// `slots` / `locked` / `tray` alone, so the three `SyllableMode`s differ only in
// the data those arrays carry.

@Suite("AssembleView metrics")
struct AssembleViewMetricsTests {

    @Test("The round column's box: px-4 pb-8 pt-2, z-[41]")
    func columnBox() {
        #expect(AssembleMetrics.contentPaddingX == 16)
        #expect(AssembleMetrics.contentPaddingTop == 8)
        #expect(AssembleMetrics.contentPaddingBottom == 32)
        #expect(AssembleMetrics.zIndex == 41)
    }

    @Test("The consigne line: text-base, mb-1")
    func headline() {
        #expect(AssembleMetrics.headlineFontSize == 16)
        #expect(AssembleMetrics.headlineSpacing == 4)
    }

    @Test("The mascot renders at Mascot.tsx's default size")
    func mascot() {
        #expect(AssembleMetrics.mascotSize == 88)
    }

    @Test("The picture: margin 2px 0, clamp(64px,22vw,120px)")
    func wordIcon() {
        #expect(AssembleMetrics.wordIconMarginY == 2)
        #expect(AssembleMetrics.wordIconSize == FluidSpec(min: 64, vw: 22, max: 120))
        // 22vw of a 390 pt phone = 85.8, inside the clamp.
        #expect(AssembleMetrics.wordIconSize.resolve(viewport: 390) == 85.8)
    }

    @Test("The 🔊 button: mb-5 px-5 py-2 text-lg")
    func listenButton() {
        #expect(AssembleMetrics.listenSpacing == 20)
        #expect(AssembleMetrics.listenPaddingX == 20)
        #expect(AssembleMetrics.listenPaddingY == 8)
        #expect(AssembleMetrics.listenFontSize == 18)
    }

    @Test("Slot row: mb-6 wrapper, gap-2 inner row")
    func slotRow() {
        #expect(AssembleMetrics.slotRowSpacing == 24)
        #expect(AssembleMetrics.slotGap == 8)
    }

    @Test("A slot's own box, from the inline style object")
    func slotBox() {
        #expect(AssembleMetrics.slotSide == FluidSpec(min: 56, vw: 16, max: 84))
        #expect(AssembleMetrics.slotFontSize == FluidSpec(min: 22, vw: 6, max: 40))
        #expect(AssembleMetrics.slotPaddingX == 10)
        #expect(AssembleMetrics.slotCornerRadius == 20)
        #expect(AssembleMetrics.slotBorderWidth == 3)
        // `0 6px 14px rgba(0,0,0,0.12)` — CSS blur is twice the SwiftUI radius.
        #expect(AssembleMetrics.slotShadowOpacity == 0.12)
        #expect(AssembleMetrics.slotShadowRadius == 7)
        #expect(AssembleMetrics.slotShadowY == 6)
    }

    @Test("The tray: gap-3, clamp(64px,18vw,100px) tiles at clamp(20px,5.5vw,36px)")
    func tray() {
        #expect(AssembleMetrics.trayGap == 12)
        #expect(AssembleMetrics.tileSide == FluidSpec(min: 64, vw: 18, max: 100))
        #expect(AssembleMetrics.tileFontSize == FluidSpec(min: 20, vw: 5.5, max: 36))
    }

    @Test("The authored tile side is SMALLER than Tile's own 92 pt floor")
    func tileSideIsBelowTheDefaultFloor() {
        // Not a preference — a recorded fact about the frozen behaviour. The TSX
        // authors `clamp(64px,18vw,100px)`, which on a 390 pt phone resolves to
        // 70.2 pt, below `TileMetrics.defaultSize`'s 92 pt minimum. If the
        // authored spec is ever raised to meet that floor, this test fails and
        // the change is a deliberate behaviour change, not a silent one.
        #expect(AssembleMetrics.tileSide.resolve(viewport: 390) == 70.2)
        #expect(AssembleMetrics.tileSide.min < TileMetrics.defaultSize.min)
        // It does reach the floor on a wide tablet: 18vw of 512 = 92.16.
        #expect(AssembleMetrics.tileSide.resolve(viewport: 512) > 92)
    }
}

@Suite("AssembleView slots")
struct AssembleViewSlotTests {

    @Test("An empty, unfillable-by-the-round slot: no text, the loud dashed border")
    func emptySlot() {
        let row = AssembleSlot.row(filled: [nil], locked: [false])
        let slot = row[0]
        #expect(slot.text == "")
        #expect(slot.isFilled == false)
        #expect(slot.isPreRevealed == false)
        #expect(slot.isRemovable == false)
        #expect(slot.removeLabel == nil)
        #expect(slot.borderHex == "#E4A15E")
    }

    @Test("A slot the CHILD filled is a button labelled « Retirer … »")
    func childFilledSlot() {
        let row = AssembleSlot.row(filled: ["SON"], locked: [false])
        let slot = row[0]
        #expect(slot.text == "SON")
        #expect(slot.isFilled)
        #expect(slot.isRemovable)
        #expect(slot.removeLabel == "Retirer SON")
        // `border: "none"` and the drop shadow appear together.
        #expect(slot.borderHex == nil)
    }

    @Test("A pre-revealed (fill-blank) slot is filled but NOT removable")
    func preRevealedSlot() {
        let row = AssembleSlot.row(filled: ["MAI"], locked: [true])
        let slot = row[0]
        #expect(slot.text == "MAI")
        #expect(slot.isFilled)
        #expect(slot.isPreRevealed)
        #expect(slot.isRemovable == false)
        #expect(slot.removeLabel == nil)
        #expect(slot.borderHex == nil)
    }

    @Test("Empty AND pre-revealed takes the quiet dashed border (ported dead branch)")
    func emptyLockedSlot() {
        // `round.locked[i] ? "3px dashed #C9A87A" : …`. Unreachable in this
        // engine (a fill-blank round's locked slots always arrive filled), but
        // the TSX writes the branch, so the projection carries it.
        let row = AssembleSlot.row(filled: [nil], locked: [true])
        #expect(row[0].borderHex == "#C9A87A")
        #expect(row[0].isRemovable == false)
    }

    @Test("A short locked mask reads as not-locked, like JS undefined")
    func shortLockedMask() {
        // `round.locked[i]` is `undefined` past the end — falsy. So the slot is
        // an ordinary one: loud border when empty, removable when filled.
        let row = AssembleSlot.row(filled: [nil, "SON"], locked: [])
        #expect(row[0].borderHex == "#E4A15E")
        #expect(row[1].isRemovable)
        #expect(row[1].removeLabel == "Retirer SON")
    }

    @Test("fill-blank: exactly one gap, every other syllable pre-revealed and fixed")
    func fillBlankShape() throws {
        let word = SyllableWord(word: "MAISON", syllables: ["MAI", "SON"], emoji: "🏠")
        let round = Levels.buildSyllableRound(word: word, mode: .fillBlank, .seeded(7))
        let row = AssembleSlot.row(filled: round.slots, locked: round.locked)

        #expect(row.count == 2)
        #expect(row.filter { !$0.isFilled }.count == 1)
        let gap = try #require(row.first { !$0.isFilled })
        #expect(gap.isPreRevealed == false)
        #expect(gap.borderHex == "#E4A15E")
        // Nothing is removable before the child has dropped anything: the only
        // filled slots are the round's own.
        #expect(row.allSatisfy { !$0.isRemovable })
        for slot in row where slot.isPreRevealed {
            #expect(slot.isFilled)
            #expect(slot.text.isEmpty == false)
        }
    }

    @Test("fill-blank: the child's syllable becomes removable, the revealed ones never do")
    func fillBlankAfterTheDrop() throws {
        let word = SyllableWord(word: "MAISON", syllables: ["MAI", "SON"], emoji: "🏠")
        let round = Levels.buildSyllableRound(word: word, mode: .fillBlank, .seeded(7))
        let gapIndex = try #require(round.slots.firstIndex(where: { $0 == nil }))

        // What `pick` does to `slots`: the value lands in the first empty slot.
        var filled = round.slots
        filled[gapIndex] = "XX"
        let row = AssembleSlot.row(filled: filled, locked: round.locked)

        #expect(row[gapIndex].isRemovable)
        #expect(row[gapIndex].removeLabel == "Retirer XX")
        for (i, slot) in row.enumerated() where i != gapIndex {
            #expect(slot.isRemovable == false)
            #expect(slot.removeLabel == nil)
        }
    }

    @Test("order and order-distractor project IDENTICAL slot rows — the mode is data")
    func orderModesShareTheRow() {
        let word = SyllableWord(word: "CHOCOLAT", syllables: ["CHO", "CO", "LAT"], emoji: "🍫")
        let order = Levels.buildSyllableRound(word: word, mode: .order, .seeded(3))
        let distractor = Levels.buildSyllableRound(word: word, mode: .orderDistractor, .seeded(3))

        let a = AssembleSlot.row(filled: order.slots, locked: order.locked)
        let b = AssembleSlot.row(filled: distractor.slots, locked: distractor.locked)
        #expect(a == b)

        // Three empty, loud-bordered, non-removable slots: `syl.map(() => null)`
        // and `syl.map(() => false)`.
        #expect(a.count == 3)
        #expect(a.allSatisfy { $0.text.isEmpty })
        #expect(a.allSatisfy { $0.borderHex == "#E4A15E" })
        #expect(a.allSatisfy { !$0.isPreRevealed && !$0.isRemovable })
    }
}

@Suite("AssembleView tray")
struct AssembleViewTrayTests {

    /// Ids stride by 3, deliberately: `TileIDAllocator` hands out consecutive
    /// ids in production, and with consecutive ids `id % 5` and `position % 5`
    /// agree — a rotation keyed on the wrong one would pass unnoticed.
    private func tiles(_ syllables: [String]) -> [SyllableTile] {
        syllables.enumerated().map { SyllableTile(id: 100 + 3 * $0.offset, syllable: $0.element) }
    }

    @Test("TRAY_COLORS rotate by position and wrap at five")
    func paintRotation() {
        // `TRAY_COLORS` in AssembleExercise.tsx, in order.
        let bg = ["#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65"]
        let ink = ["#062E3D", "#213606", "#4A3B00", "#2C1846", "#4A2317"]

        let row = AssembleTrayTile.row(tiles(["A", "B", "C", "D", "E", "F"]), used: { _ in false })
        #expect(row.count == 6)
        for (i, tile) in row.enumerated() {
            #expect(tile.paint.bg.hex == bg[i % 5])
            #expect(tile.paint.ink.hex == ink[i % 5])
        }
        // `i % TRAY_COLORS.length` — the sixth tile is blue again, not a crash
        // and not a repeat of the fifth.
        #expect(row[5].paint.bg.hex == "#4FC3F7")
    }

    @Test("A tile carries both labels: « Syllabe CHA » and « Écouter CHA »")
    func labels() {
        let row = AssembleTrayTile.row(tiles(["CHA"]), used: { _ in false })
        #expect(row[0].label == "Syllabe CHA")
        #expect(row[0].previewLabel == "Écouter CHA")
        #expect(row[0].syllable == "CHA")
        #expect(row[0].id == 100)
    }

    @Test("A dropped tile greys out; the rest do not")
    func disabledFollowsUsed() {
        let used: Set<Int> = [103]
        let row = AssembleTrayTile.row(tiles(["A", "B", "C"]), used: { used.contains($0) })
        #expect(row.map(\.isDisabled) == [false, true, false])
    }

    @Test("order-distractor adds one tray tile; fill-blank offers exactly two")
    func trayCountsPerMode() {
        let word = SyllableWord(word: "CHOCOLAT", syllables: ["CHO", "CO", "LAT"], emoji: "🍫")

        let order = Levels.buildSyllableRound(word: word, mode: .order, .seeded(11))
        #expect(AssembleTrayTile.row(order.tray, used: { _ in false }).count == 3)

        let distractor = Levels.buildSyllableRound(word: word, mode: .orderDistractor, .seeded(11))
        let distractorRow = AssembleTrayTile.row(distractor.tray, used: { _ in false })
        #expect(distractorRow.count == 4)
        // The intruder is not one of the word's own syllables.
        let extras = distractorRow.map(\.syllable).filter { !word.syllables.contains($0) }
        #expect(extras.count == 1)

        let blank = Levels.buildSyllableRound(word: word, mode: .fillBlank, .seeded(11))
        #expect(AssembleTrayTile.row(blank.tray, used: { _ in false }).count == 2)
    }
}
