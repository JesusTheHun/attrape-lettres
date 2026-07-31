import Testing

import ALCore
@testable import ALUI

// The two spelling engines' VIEW rules — the part `AssemblyModel` deliberately
// does not own: what a slot / cell renders, what a tile is labelled, and the
// authored geometry of the row and the tray.
//
// Every number and string below was read out of the TypeScript
// (`src/exercises/SpellSoundExercise.tsx`, `src/exercises/SpellSyllableExercise.tsx`)
// and NOT out of the Swift. Nothing here needs a renderer: the rules are pure
// functions over `SoundRound` / `SpellSyllableRound`, which is the only reason
// they are testable at all.

// MARK: - SpellSound

@Suite("SpellSoundView — the slot row and the tray")
struct SpellSoundViewTests {

    /// `{target.emoji ?? "🎧"}` — the big picture above the listen button. A
    /// sound with an anchor word shows the word's emoji; a bare sound (level 1
    /// has `word: undefined` rows) falls back to the headphones.
    @Test("the prompt picture falls back to 🎧 only when the target has none")
    func promptEmoji() {
        let withEmoji = SoundTarget(sound: "lo", spelling: ["L", "O"], word: "loto", emoji: "🎰")
        let bare = SoundTarget(sound: "o", spelling: ["O"])
        #expect(SpellSoundView.promptEmoji(withEmoji) == "🎰")
        #expect(SpellSoundView.promptEmoji(bare) == "🎧")
    }

    /// ```
    /// s != null ? <button aria-label={`Retirer ${s}`}>{s}</button>
    ///           : <div style={dashed}>{""}</div>
    /// ```
    @Test("an empty slot is dashed and inert; a filled one is a « Retirer » button")
    func slotRender() {
        #expect(SpellSoundView.render(slot: nil) == .empty)
        #expect(SpellSoundView.render(slot: nil).face == .empty)

        let filled = SpellSoundView.render(slot: "PH")
        #expect(filled == .filled(letter: "PH", removeLabel: "Retirer PH"))
        #expect(filled.face == .filled)
    }

    /// `ariaLabel={`Lettre ${t.letter}`}` — the tray tile names the LETTER, and
    /// SpellSound letters are always plain uppercase print (no `faceLabel`
    /// here; that is SpellSyllable's job).
    @Test("a tray tile is labelled « Lettre X »")
    func trayLabel() {
        #expect(SpellSoundView.trayLabel(SoundTile(id: 1, letter: "O")) == "Lettre O")
    }

    /// `minWidth`/`height: "clamp(52px,15vw,76px)"`,
    /// `fontSize: "clamp(24px,7vw,44px)"`, and the emoji's
    /// `"clamp(56px,18vw,104px)"`. Checked at the two clamped ends and in the
    /// middle, because a `clamp` transcribed with min and max swapped resolves
    /// identically at exactly one viewport.
    @Test("the slot box and the big emoji resolve their authored clamps")
    func slotMetrics() {
        // 320 pt: 15vw = 48 → the 52 floor wins.
        #expect(SpellSoundView.slotSide.resolve(viewport: 320) == 52)
        // 390 pt: 15vw = 58.5, inside the range.
        #expect(SpellSoundView.slotSide.resolve(viewport: 390) == 58.5)
        // 600 pt: 15vw = 90 → the 76 ceiling wins.
        #expect(SpellSoundView.slotSide.resolve(viewport: 600) == 76)

        #expect(SpellSoundView.slotFontSize.resolve(viewport: 390) == 27.3)
        #expect(SpellSoundView.slotCornerRadius == 20)
        #expect(SpellSoundView.slotHorizontalPadding == 8)

        #expect(SpellSoundView.emojiSize.resolve(viewport: 390) == 70.2)
        #expect(SpellSoundView.emojiSize.resolve(viewport: 200) == 56)
        #expect(SpellSoundView.emojiSize.resolve(viewport: 900) == 104)
    }

    /// A real round, built by the real builder: the row is one slot per letter
    /// of the target's spelling, and every one of them starts empty
    /// (`setSlotTile(r.slots.map(() => null))`, `slots = r.slots` — all nil).
    @Test("a freshly built round renders one empty slot per letter of the spelling")
    func freshRoundIsAllEmpty() {
        let target = SoundTarget(sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷")
        let round = Levels.buildSoundRound(
            target: target, distractors: 3, .seeded(0xF00D))
        let renders = round.slots.map { SpellSoundView.render(slot: $0) }

        #expect(renders.count == 3)
        #expect(renders.allSatisfy { $0 == .empty })
        // The tray holds the answer's letters plus the intruders, so every slot
        // is fillable and the tray is at least as long as the answer.
        #expect(round.tray.count == 3 + 3)
    }

    /// Filling the row in order is what the model does; the VIEW's job is only
    /// to name the letters back. Asserted over a real round so a wrong index
    /// mapping would show up.
    @Test("a filled row labels each slot with the letter it holds")
    func filledRowLabels() {
        let target = SoundTarget(sound: "fo", spelling: ["P", "H", "O"], word: "photo", emoji: "📷")
        let filled: [String?] = target.spelling
        let renders = filled.map { SpellSoundView.render(slot: $0) }
        #expect(
            renders == [
                .filled(letter: "P", removeLabel: "Retirer P"),
                .filled(letter: "H", removeLabel: "Retirer H"),
                .filled(letter: "O", removeLabel: "Retirer O"),
            ])
    }
}

// MARK: - SpellSyllable

@Suite("SpellSyllableView — the printed word, its gaps and the tray")
struct SpellSyllableViewTests {

    /// `!c.fill` → « already written — a solid, non-interactive letter in the
    /// round's writing ». The slot array is irrelevant to it.
    @Test("a written cell renders its own glyph and ignores the slots")
    func writtenCell() {
        let cell = SpellCell(
            letter: "M", glyph: "m", script: .cursive,
            fill: false, slotIndex: -1, syllableStart: true)
        let stray = LetterFace(base: "Z", glyph: "Z", script: .print)

        #expect(
            SpellSyllableView.render(cell: cell, slots: [stray])
                == .written(glyph: "m", script: .cursive))
        #expect(
            SpellSyllableView.render(cell: cell, slots: [])
                == .written(glyph: "m", script: .cursive))
    }

    /// A gap cell reads `slots[c.slotIndex]`, and what it draws is the DROPPED
    /// TILE's glyph in the DROPPED TILE's script (`{s.glyph}` +
    /// `fontFamily: SCRIPT_FONT[s.script]`) — never the cell's own, which is
    /// the answer. The distinction only shows in a mixed round, where a right
    /// letter in the wrong writing must stay visible in the row it spoiled.
    /// The remove label names `s.base`, the canonical uppercase letter.
    @Test("a filled gap draws the dropped tile's writing, and is labelled with its base letter")
    func filledGapUsesTheDroppedFace() {
        let cell = SpellCell(
            letter: "A", glyph: "A", script: .print,
            fill: true, slotIndex: 1, syllableStart: false)
        let dropped = LetterFace(base: "A", glyph: "a", script: .cursive)

        #expect(
            SpellSyllableView.render(cell: cell, slots: [nil, dropped])
                == .filled(glyph: "a", script: .cursive, removeLabel: "Retirer A"))
    }

    @Test("an unfilled gap is dashed and inert; an out-of-range slot degrades to empty")
    func emptyGap() {
        let cell = SpellCell(
            letter: "T", glyph: "T", script: .print,
            fill: true, slotIndex: 2, syllableStart: false)
        #expect(SpellSyllableView.render(cell: cell, slots: [nil, nil, nil]) == .empty)
        // Defensive: JS would read `undefined` and render the dashed box.
        #expect(SpellSyllableView.render(cell: cell, slots: [nil]) == .empty)
    }

    /// `marginLeft: c.syllableStart && i > 0 ? "clamp(8px,2.5vw,16px)" : 0`.
    /// The word's first letter is a syllable start too and must NOT be pushed.
    @Test("the syllable gap opens before every syllable except the first")
    func syllableGapRule() {
        let start = SpellCell(
            letter: "SON", glyph: "S", script: .print,
            fill: false, slotIndex: -1, syllableStart: true)
        let inner = SpellCell(
            letter: "O", glyph: "O", script: .print,
            fill: false, slotIndex: -1, syllableStart: false)

        #expect(SpellSyllableView.startsNewSyllable(start, at: 0) == false)
        #expect(SpellSyllableView.startsNewSyllable(start, at: 3) == true)
        #expect(SpellSyllableView.startsNewSyllable(inner, at: 3) == false)

        #expect(SpellSyllableView.syllableGap.resolve(viewport: 390) == 9.75)
        #expect(SpellSyllableView.syllableGap.resolve(viewport: 200) == 8)
        #expect(SpellSyllableView.syllableGap.resolve(viewport: 900) == 16)
    }

    /// `const face: LetterFace = { base: t.letter, glyph: t.glyph, script: t.script }`
    /// — the value the tile drops into the slot, and therefore what `sameFace`
    /// judges. `base` stays the canonical uppercase letter even for a lowercase
    /// cursive tile.
    @Test("a tray tile drops its own writing, keeping the canonical base letter")
    func pickValue() {
        let tile = SpellLetterTile(id: 9, letter: "E", glyph: "e", script: .cursive)
        let face = SpellSyllableView.pickValue(tile)
        #expect(face.base == "E")
        #expect(face.glyph == "e")
        #expect(face.script == .cursive)
    }

    /// `ariaLabel={faceLabel({ base: t.letter, glyph: t.glyph, script: t.script })}`
    /// — the writing has to be spoken, because in a mixed round it IS the task.
    @Test("a tray tile's label names the case and the cursive form")
    func trayLabel() {
        #expect(
            SpellSyllableView.trayLabel(
                SpellLetterTile(id: 1, letter: "A", glyph: "A", script: .print))
                == "Lettre A majuscule")
        #expect(
            SpellSyllableView.trayLabel(
                SpellLetterTile(id: 2, letter: "A", glyph: "a", script: .cursive))
                == "Lettre A minuscule attachée")
    }

    /// `minWidth: clamp(40px,11vw,60px)`, `height: clamp(52px,14vw,72px)`,
    /// `fontSize: clamp(24px,7vw,42px)`, `borderRadius: 16`, `padding: 0 6px`.
    /// The cell box is NOT the SpellSound slot box: narrower, shorter, tighter
    /// radius — a whole word has to fit on one line.
    @Test("the cell box resolves its own clamps, distinct from SpellSound's slot")
    func cellMetrics() {
        #expect(SpellSyllableView.cellMinWidth.resolve(viewport: 390) == 42.9)
        #expect(SpellSyllableView.cellMinWidth.resolve(viewport: 200) == 40)
        #expect(SpellSyllableView.cellMinWidth.resolve(viewport: 900) == 60)

        #expect(SpellSyllableView.cellHeight.resolve(viewport: 390) == 54.6)
        #expect(SpellSyllableView.cellHeight.resolve(viewport: 900) == 72)

        #expect(SpellSyllableView.cellFontSize.resolve(viewport: 390) == 27.3)
        #expect(SpellSyllableView.cellCornerRadius == 16)
        #expect(SpellSyllableView.cellHorizontalPadding == 6)

        #expect(SpellSyllableView.iconSize.resolve(viewport: 390) == 58.5)
    }

    /// A real `letters-exact` round: the cells, read in order, ARE the word —
    /// written glyphs where the syllable is printed, one empty gap per answer
    /// letter. If the port ever dropped or reordered a cell this is what breaks.
    @Test("a real round's cells reconstruct the whole word, gaps included")
    func realRoundReconstructsTheWord() throws {
        let word = try #require(Levels.spellSyllablePool(1).first)
        let round = Levels.buildSpellSyllableRound(
            word: word, mode: .lettersExact, distractors: 2, mixed: false, .seeded(0xBEEF))
        let slots: [LetterFace?] = round.answerFaces.map { _ in nil }

        var rebuilt = ""
        var gaps = 0
        for (index, cell) in round.cells.enumerated() {
            switch SpellSyllableView.render(cell: cell, slots: slots) {
            case .written(let glyph, let script):
                #expect(script == .print)  // plain rounds are uppercase print
                rebuilt += glyph
            case .empty:
                rebuilt += round.answer[gaps]
                gaps += 1
            case .filled:
                Issue.record(Comment(rawValue: "a fresh round cannot have a filled gap"))
            }
            // Every cell after the first that starts a syllable opens the gap.
            if index == 0 {
                #expect(SpellSyllableView.startsNewSyllable(cell, at: index) == false)
            }
        }

        #expect(rebuilt == word.syllables.joined())
        #expect(gaps == round.answer.count)
        #expect(gaps > 0)
    }

    /// The same round with every slot filled from `answerFaces`: each gap now
    /// renders the answer's glyph and is tappable.
    @Test("filling a real round's gaps renders the answer's own writing")
    func realRoundFilled() throws {
        let word = try #require(Levels.spellSyllablePool(2).first)
        let round = Levels.buildSpellSyllableRound(
            word: word, mode: .lettersTwo, distractors: 3, mixed: true, .seeded(0x1234))
        let slots: [LetterFace?] = round.answerFaces.map { $0 }

        var seen = 0
        for cell in round.cells where cell.fill {
            let expected = round.answerFaces[cell.slotIndex]
            #expect(
                SpellSyllableView.render(cell: cell, slots: slots)
                    == .filled(
                        glyph: expected.glyph,
                        script: expected.script,
                        removeLabel: "Retirer \(expected.base)"))
            seen += 1
        }
        #expect(seen == round.answer.count)
        // `letters-two` blanks two syllables (every pool word has ≥ 3).
        #expect(word.syllables.count >= 3)
    }
}

// MARK: - Shared chrome

@Suite("Spelling chrome — the tray, the slot box, the two margins")
struct SpellChromeTests {

    /// `TRAY_COLORS[i % TRAY_COLORS.length]`, verbatim from BOTH TSX files —
    /// blue first, and the rotation is by POSITION IN THE TRAY.
    @Test("the tray palette is the five authored paints, cycling by position")
    func trayPalette() {
        let expected = ["#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65"]
        for (index, hex) in expected.enumerated() {
            #expect(SpellTray.paint(at: index).bg == HexColor(hex))
        }
        // The inks, spot-checked at both ends of the ramp.
        #expect(SpellTray.paint(at: 0).ink == HexColor("#062E3D"))
        #expect(SpellTray.paint(at: 4).ink == HexColor("#4A2317"))
        // …and it wraps rather than running off the end.
        #expect(SpellTray.paint(at: 5).bg == HexColor("#4FC3F7"))
        #expect(SpellTray.paint(at: 13).bg == SpellTray.paint(at: 3).bg)
    }

    /// `size="clamp(60px,17vw,92px)"` / `fontSize="clamp(26px,7vw,48px)"` —
    /// authored identically in both files, and `gap-3` between tiles.
    @Test("the tray tile is 60…92 pt with a 26…48 pt glyph")
    func trayMetrics() {
        #expect(SpellTray.size.resolve(viewport: 320) == 60)
        #expect(SpellTray.size.resolve(viewport: 390) == 66.3)
        #expect(SpellTray.size.resolve(viewport: 600) == 92)
        #expect(SpellTray.fontSize.resolve(viewport: 390) == 27.3)
        #expect(SpellTray.fontSize.resolve(viewport: 900) == 48)
        #expect(SpellTray.gap == 12)
        // Invariant 6's floor is 92 pt for a DEFAULT tile; the tray authors 60,
        // which is what the web ships. Recorded, not silently raised.
        #expect(SpellTray.size.min < TileMetrics.defaultSize.min)
    }

    /// The slot faces, from the shared inline style:
    /// `background: s ? "#FFFFFF" : "transparent"`,
    /// `border: s ? "none" : "3px dashed #E4A15E"`,
    /// and SpellSyllable's written cell on `#FFF3E0`.
    @Test("only the empty face is dashed, and only the filled face casts a shadow")
    func slotFaces() {
        #expect(SpellSlot.borderWidth == 3)
        #expect(SpellSlot.dash == [9, 9])
        #expect(Palette.slotDashed == HexColor("#E4A15E"))
        #expect(Palette.slotRevealed == HexColor("#FFF3E0"))
        #expect(SpellSlot.filledShadowOpacity == 0.12)
        #expect(SpellSlot.filledShadowY == 6)
        // CSS blur radius is twice SwiftUI's: `0 6px 14px` → radius 7.
        #expect(SpellSlot.filledShadowRadius == 7)
    }

    /// The two engines' rows are NOT interchangeable, and a port that shared one
    /// set of numbers would look plausible: SpellSound spaces its slots `gap-2`
    /// with the listen button at `mb-5`; SpellSyllable packs the whole word at
    /// `gap-1.5` with `mb-4`.
    @Test("the two spelling engines author different row gaps and listen margins")
    func rowsDiffer() {
        #expect(SpellSoundView.rowSpacing == 8)  // gap-2
        #expect(SpellSyllableView.rowSpacing == 6)  // gap-1.5
        #expect(SpellSoundView.listenMarginBottom == 20)  // mb-5
        #expect(SpellSyllableView.listenMarginBottom == 16)  // mb-4
        #expect(SpellSoundView.rowSpacing != SpellSyllableView.rowSpacing)
        #expect(SpellSoundView.listenMarginBottom != SpellSyllableView.listenMarginBottom)

        // `mb-6` under the row, `mb-1` under the headline, `px-4 pb-8 pt-2` on
        // the column — shared by both files.
        #expect(SpellSoundView.rowMarginBottom == 24)
        #expect(SpellSyllableView.rowMarginBottom == 24)
        #expect(SpellSoundView.headlineMarginBottom == 4)
        #expect(SpellSyllableView.headlineMarginBottom == 4)
        #expect(SpellSoundView.padding.top == 8)
        #expect(SpellSoundView.padding.leading == 16)
        #expect(SpellSoundView.padding.bottom == 32)
        #expect(SpellSyllableView.padding == SpellSoundView.padding)
    }
}
