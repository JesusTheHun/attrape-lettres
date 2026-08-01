package fr.dappit.attrapelettres.ui.engines

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.SoundTarget
import fr.dappit.attrapelettres.core.domain.SoundTile
import fr.dappit.attrapelettres.core.domain.SpellCell
import fr.dappit.attrapelettres.core.domain.SpellLetterTile
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.buildSoundRound
import fr.dappit.attrapelettres.core.levels.buildSpellSyllableRound
import fr.dappit.attrapelettres.core.levels.soundPrompt
import fr.dappit.attrapelettres.core.levels.soundSuccess
import fr.dappit.attrapelettres.core.levels.spellSyllablePool
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.HexColor
import fr.dappit.attrapelettres.ui.design.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The two spelling engines' VIEW rules — the part `AssemblyModel` deliberately
// does not own: what a slot / cell renders, what a tile is labelled, the five
// rows' fan-in, and the authored geometry of the row and the tray.
//
// Every number and string below was read out of the TypeScript
// (`src/exercises/SpellSoundExercise.tsx`, `src/exercises/SpellSyllableExercise.tsx`,
// `src/App.tsx`, `src/levels.ts`) and NOT out of the Kotlin. Nothing here needs
// a renderer: the rules are pure functions over `SoundRound` /
// `SpellSyllableRound`, which is the only reason they are testable at all (A11).
//
// The few behavioural tests drive the real model through the real :core
// builders, because "the screen shows the right thing" and "a tap does the right
// thing" are the same claim in a thin renderer.

// ===========================================================================
// SpellSound
// ===========================================================================

class SpellSoundViewTest {

    /**
     * `{target.emoji ?? "🎧"}` — the big picture above the listen button. A sound
     * with an anchor word shows the word's emoji; a bare sound (level 1 has
     * `word: undefined` rows) falls back to the headphones.
     */
    @Test
    fun `the prompt picture falls back to the headphones only when the target has none`() {
        val withEmoji = SoundTarget(
            sound = "lo",
            spelling = listOf("L", "O"),
            word = "loto",
            emoji = "🎰",
        )
        val bare = SoundTarget(sound = "o", spelling = listOf("O"))

        assertEquals("🎰", SpellSoundView.promptEmoji(withEmoji))
        assertEquals("🎧", SpellSoundView.promptEmoji(bare))
    }

    /**
     * ```
     * s != null ? <button aria-label={`Retirer ${s}`}>{s}</button>
     *           : <div style={dashed}>{""}</div>
     * ```
     */
    @Test
    fun `an empty slot is dashed and inert, a filled one is a Retirer button`() {
        assertEquals(SpellSoundView.SlotRender.Empty, SpellSoundView.render(null))
        assertEquals(SpellSlotFace.EMPTY, SpellSoundView.render(null).face)

        val filled = SpellSoundView.render("PH")
        assertEquals(SpellSoundView.SlotRender.Filled("PH", "Retirer PH"), filled)
        assertEquals(SpellSlotFace.FILLED, filled.face)
    }

    /**
     * `ariaLabel={`Lettre ${t.letter}`}` — the tray tile names the LETTER, and
     * SpellSound letters are always plain uppercase print (no `faceLabel` here;
     * that is SpellSyllable's job).
     */
    @Test
    fun `a tray tile is labelled Lettre X`() {
        assertEquals("Lettre O", SpellSoundView.trayLabel(SoundTile(id = 1, letter = "O")))
    }

    /**
     * `minWidth`/`height: "clamp(52px,15vw,76px)"`,
     * `fontSize: "clamp(24px,7vw,44px)"`, and the emoji's
     * `"clamp(56px,18vw,104px)"`. Checked at both clamped ends and in the
     * middle, because a clamp transcribed with min and max swapped resolves
     * identically at exactly one viewport.
     */
    @Test
    fun `the slot box and the big emoji resolve their authored clamps`() {
        // 320 dp: 15vw = 48 → the 52 floor wins.
        near(SpellSoundView.SLOT_SIDE.resolve(320f), 52f, "slot at 320")
        // 390 dp: 15vw = 58.5, inside the range.
        near(SpellSoundView.SLOT_SIDE.resolve(390f), 58.5f, "slot at 390")
        // 600 dp: 15vw = 90 → the 76 ceiling wins.
        near(SpellSoundView.SLOT_SIDE.resolve(600f), 76f, "slot at 600")

        near(SpellSoundView.SLOT_FONT_SIZE.resolve(390f), 27.3f, "slot glyph at 390")
        assertEquals(20.dp, SpellSoundView.SLOT_CORNER_RADIUS)
        assertEquals(8.dp, SpellSoundView.SLOT_HORIZONTAL_PADDING)

        near(SpellSoundView.EMOJI_SIZE.resolve(390f), 70.2f, "emoji at 390")
        near(SpellSoundView.EMOJI_SIZE.resolve(200f), 56f, "emoji at 200")
        near(SpellSoundView.EMOJI_SIZE.resolve(900f), 104f, "emoji at 900")
        assertEquals(1.1f, SpellSoundView.EMOJI_LINE_HEIGHT)
    }

    /**
     * A real round, built by the real builder: the row is one slot per letter of
     * the target's spelling, and every one of them starts empty
     * (`slots = r.slots` — all null).
     */
    @Test
    fun `a freshly built round renders one empty slot per letter of the spelling`() {
        val target = SoundTarget(
            sound = "fo",
            spelling = listOf("P", "H", "O"),
            word = "photo",
            emoji = "📷",
        )
        val round = buildSoundRound(target, 3, SeededGenerator(0xF00D))
        val renders = round.slots.map { SpellSoundView.render(it) }

        assertEquals(3, renders.size)
        assertTrue(renders.all { it == SpellSoundView.SlotRender.Empty })
        // The tray holds the answer's letters plus the intruders, so every slot
        // is fillable and the tray is at least as long as the answer.
        assertEquals(3 + 3, round.tray.size)
    }

    /**
     * Filling the row in order is what the model does; the VIEW's job is only to
     * name the letters back. Asserted over the real spelling so a wrong index
     * mapping would show up.
     */
    @Test
    fun `a filled row labels each slot with the letter it holds`() {
        val filled: List<String?> = listOf("P", "H", "O")
        assertEquals(
            listOf(
                SpellSoundView.SlotRender.Filled("P", "Retirer P"),
                SpellSoundView.SlotRender.Filled("H", "Retirer H"),
                SpellSoundView.SlotRender.Filled("O", "Retirer O"),
            ),
            filled.map { SpellSoundView.render(it) },
        )
    }

    /**
     * The chrome the screen reads straight off the model, verbatim from the TSX:
     * the consigne `<p>`, the 🔊 button's `aria-label`, and `Finished`'s title.
     */
    @Test
    fun `the screen's French comes from the model, verbatim`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSound(level = 1, deps = h.deps, rng = SeededGenerator(4))

        assertEquals("Écoute le son et écris-le avec les lettres", model.headline)
        assertEquals("Réécouter le son", model.listenAccessibilityLabel)
        assertEquals("Tu as tout réussi !", model.finishedTitle)
        assertEquals(ExerciseId.SPELL_SOUND, model.descriptor.exercise)
    }

    /**
     * The spoken lines, and the one thing a screen can get wrong on its own: the
     * 🔊 pill speaks `soundPrompt(target)` and nothing else, and the tray tile's
     * « Écouter » speaks the bare letter.
     */
    @Test
    fun `the listen pill replays the prompt and a tile previews its letter`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSound(level = 1, deps = h.deps, rng = SeededGenerator(11))
        model.activate()
        h.settle()
        assertEquals(listOf(soundPrompt(model.current)), h.audio.sayTexts)

        h.audio.clearEvents()
        model.replayPrompt()
        h.pump()
        assertEquals(listOf(soundPrompt(model.current)), h.audio.sayTexts)

        h.audio.clearEvents()
        val tile = model.round.tray.first()
        model.preview(tile.letter)
        h.pump()
        assertEquals(listOf(tile.letter), h.audio.sayTexts)
    }

    /**
     * A completed row: the success line is `soundSuccess(target)`, the star
     * survives, and the row advances only once the line has played.
     */
    @Test
    fun `a correct row speaks the success line and advances`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSound(level = 1, deps = h.deps, rng = SeededGenerator(21))
        val target = model.round.target
        val expected = soundSuccess(target)

        h.audio.holdSays()
        for (letter in target.spelling) {
            val tile = assertNotNull(
                model.round.tray.firstOrNull {
                    it.letter == letter && !model.isTrayTileUsed(it.id)
                },
            )
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.letter))
        }
        h.pump()

        assertEquals(listOf(expected), h.audio.pendingSayTexts)
        assertEquals(0, model.idx, "the round is still on screen while the line plays")
        h.settle()
        assertTrue(model.idx == 1 || model.done)
        assertTrue(model.stars[0])
        assertEquals(1, h.confetti.count)
    }

    /**
     * Invariant 3: a wrong row is not terminal. « Oh non », the star greys at the
     * same beat, the letters go back to the tray, and the child plays on — no
     * lock survives, nothing is lost, no route changes.
     */
    @Test
    fun `a wrong row greys the star, wipes the letters and locks nothing`() {
        val h = EngineHarness()
        // Level 2, not 1: `SOUND_LEVELS[0]` authors `distractors = 0`, so a
        // one-letter target there has a tray that cannot spell anything wrong.
        val model = AssemblyModel.spellSound(level = 2, deps = h.deps, rng = SeededGenerator(33))
        val spelling = model.round.target.spelling
        val wrongFirst = assertNotNull(
            model.round.tray.firstOrNull { it.letter != spelling[0] },
            "level 2 adds intruders, so a wrong first letter always exists",
        )

        assertEquals(Verdict.ACCEPT, model.pick(wrongFirst.id, wrongFirst.letter))
        var guard = 0
        while (model.slots.any { it == null } && guard < 20) {
            guard += 1
            val free = assertNotNull(model.round.tray.firstOrNull { !model.isTrayTileUsed(it.id) })
            model.pick(free.id, free.letter)
        }

        // The star greys SYNCHRONOUSLY, in the pick handler (invariant 8).
        assertFalse(model.stars[0])
        h.settle()
        assertEquals("Oh non ! On recommence.", h.audio.sayTexts.last())
        assertEquals(0, model.idx, "a miss never advances")
        assertFalse(model.done, "there is no fail state")
        assertTrue(model.slots.all { it == null }, "the row is wiped back to empty")
        assertTrue(model.used.isEmpty(), "every tray tile is tappable again")
        assertEquals(0, h.confetti.count)

        // …and the very next correct row still pays its own star.
        assertTrue(model.stars.drop(1).all { it })
    }
}

// ===========================================================================
// SpellSyllable
// ===========================================================================

class SpellSyllableViewTest {

    /**
     * `!c.fill` → « already written — a solid, non-interactive letter in the
     * round's writing ». The slot array is irrelevant to it.
     */
    @Test
    fun `a written cell renders its own glyph and ignores the slots`() {
        val cell = SpellCell(
            letter = "M",
            glyph = "m",
            script = LetterScript.CURSIVE,
            fill = false,
            slotIndex = -1,
            syllableStart = true,
        )
        val stray = LetterFace(base = "Z", glyph = "Z", script = LetterScript.PRINT)
        val expected = SpellSyllableView.CellRender.Written("m", LetterScript.CURSIVE)

        assertEquals(expected, SpellSyllableView.render(cell, listOf(stray)))
        assertEquals(expected, SpellSyllableView.render(cell, emptyList()))
    }

    /**
     * A gap cell reads `slots[c.slotIndex]`, and what it draws is the DROPPED
     * TILE's glyph in the DROPPED TILE's script (`{s.glyph}` +
     * `fontFamily: SCRIPT_FONT[s.script]`) — never the cell's own, which is the
     * answer. The distinction only shows in a mixed round, where a right letter
     * in the wrong writing must stay visible in the row it spoiled. The remove
     * label names `s.base`, the canonical uppercase letter.
     */
    @Test
    fun `a filled gap draws the dropped tile's writing and is labelled with its base letter`() {
        val cell = SpellCell(
            letter = "A",
            glyph = "A",
            script = LetterScript.PRINT,
            fill = true,
            slotIndex = 1,
            syllableStart = false,
        )
        val dropped = LetterFace(base = "A", glyph = "a", script = LetterScript.CURSIVE)

        assertEquals(
            SpellSyllableView.CellRender.Filled("a", LetterScript.CURSIVE, "Retirer A"),
            SpellSyllableView.render(cell, listOf(null, dropped)),
        )
    }

    @Test
    fun `an unfilled gap is dashed and inert, and an out-of-range slot degrades to empty`() {
        val cell = SpellCell(
            letter = "T",
            glyph = "T",
            script = LetterScript.PRINT,
            fill = true,
            slotIndex = 2,
            syllableStart = false,
        )
        assertEquals(
            SpellSyllableView.CellRender.Empty,
            SpellSyllableView.render(cell, listOf(null, null, null)),
        )
        // Defensive: JS would read `undefined` and render the dashed box.
        assertEquals(
            SpellSyllableView.CellRender.Empty,
            SpellSyllableView.render(cell, listOf(null)),
        )
    }

    /**
     * `marginLeft: c.syllableStart && i > 0 ? "clamp(8px,2.5vw,16px)" : 0`. The
     * word's first letter is a syllable start too and must NOT be pushed.
     */
    @Test
    fun `the syllable gap opens before every syllable except the first`() {
        val start = SpellCell(
            letter = "S",
            glyph = "S",
            script = LetterScript.PRINT,
            fill = false,
            slotIndex = -1,
            syllableStart = true,
        )
        val inner = start.copy(letter = "O", glyph = "O", syllableStart = false)

        assertFalse(SpellSyllableView.startsNewSyllable(start, 0))
        assertTrue(SpellSyllableView.startsNewSyllable(start, 3))
        assertFalse(SpellSyllableView.startsNewSyllable(inner, 3))

        near(SpellSyllableView.SYLLABLE_GAP.resolve(390f), 9.75f, "gap at 390")
        near(SpellSyllableView.SYLLABLE_GAP.resolve(200f), 8f, "gap at 200")
        near(SpellSyllableView.SYLLABLE_GAP.resolve(900f), 16f, "gap at 900")
    }

    /**
     * `const face: LetterFace = { base: t.letter, glyph: t.glyph, script: t.script }`
     * — the value the tile drops into the slot, and therefore what `sameFace`
     * judges. `base` stays the canonical uppercase letter even for a lowercase
     * cursive tile. The screen builds it with the spine's `spellTileFace`, so the
     * label, the pick and the judge cannot describe different tiles.
     */
    @Test
    fun `a tray tile drops its own writing, keeping the canonical base letter`() {
        val tile = SpellLetterTile(id = 9, letter = "E", glyph = "e", script = LetterScript.CURSIVE)
        val face = spellTileFace(tile)

        assertEquals("E", face.base)
        assertEquals("e", face.glyph)
        assertEquals(LetterScript.CURSIVE, face.script)
    }

    /**
     * `ariaLabel={faceLabel({ base: t.letter, glyph: t.glyph, script: t.script })}`
     * — the writing has to be spoken, because in a mixed round it IS the task.
     */
    @Test
    fun `a tray tile's label names the case and the cursive form`() {
        assertEquals(
            "Lettre A majuscule",
            SpellSyllableView.trayLabel(
                SpellLetterTile(id = 1, letter = "A", glyph = "A", script = LetterScript.PRINT),
            ),
        )
        assertEquals(
            "Lettre A minuscule attachée",
            SpellSyllableView.trayLabel(
                SpellLetterTile(id = 2, letter = "A", glyph = "a", script = LetterScript.CURSIVE),
            ),
        )
    }

    /**
     * `minWidth: clamp(40px,11vw,60px)`, `height: clamp(52px,14vw,72px)`,
     * `fontSize: clamp(24px,7vw,42px)`, `borderRadius: 16`, `padding: 0 6px`.
     */
    @Test
    fun `the cell box resolves its own clamps, distinct from SpellSound's slot`() {
        near(SpellSyllableView.CELL_MIN_WIDTH.resolve(390f), 42.9f, "cell width at 390")
        near(SpellSyllableView.CELL_MIN_WIDTH.resolve(200f), 40f, "cell width at 200")
        near(SpellSyllableView.CELL_MIN_WIDTH.resolve(900f), 60f, "cell width at 900")

        near(SpellSyllableView.CELL_HEIGHT.resolve(390f), 54.6f, "cell height at 390")
        near(SpellSyllableView.CELL_HEIGHT.resolve(900f), 72f, "cell height at 900")

        near(SpellSyllableView.CELL_FONT_SIZE.resolve(390f), 27.3f, "cell glyph at 390")
        assertEquals(16.dp, SpellSyllableView.CELL_CORNER_RADIUS)
        assertEquals(6.dp, SpellSyllableView.CELL_HORIZONTAL_PADDING)

        near(SpellSyllableView.ICON_SIZE.resolve(390f), 58.5f, "word icon at 390")
    }

    /**
     * A real `letters-exact` round: the cells, read in order, ARE the word —
     * written glyphs where the syllable is printed, one empty gap per answer
     * letter. If the port ever dropped or reordered a cell this is what breaks.
     */
    @Test
    fun `a real round's cells reconstruct the whole word, gaps included`() {
        val word = spellSyllablePool(1).first()
        val round = buildSpellSyllableRound(
            word,
            SpellSyllableMode.LETTERS_EXACT,
            2,
            false,
            SeededGenerator(0xBEEF),
        )
        val slots: List<LetterFace?> = round.answerFaces.map { null }

        val rebuilt = StringBuilder()
        var gaps = 0
        round.cells.forEachIndexed { index, cell ->
            when (val render = SpellSyllableView.render(cell, slots)) {
                is SpellSyllableView.CellRender.Written -> {
                    // Plain rounds are uppercase print.
                    assertEquals(LetterScript.PRINT, render.script)
                    rebuilt.append(render.glyph)
                }

                is SpellSyllableView.CellRender.Empty -> {
                    rebuilt.append(round.answer[gaps])
                    gaps += 1
                }

                is SpellSyllableView.CellRender.Filled ->
                    throw AssertionError("a fresh round cannot have a filled gap")
            }
            if (index == 0) assertFalse(SpellSyllableView.startsNewSyllable(cell, index))
        }

        assertEquals(word.syllables.joinToString(""), rebuilt.toString())
        assertEquals(round.answer.size, gaps)
        assertTrue(gaps > 0)
    }

    /**
     * The same round with every slot filled from `answerFaces`: each gap now
     * renders the answer's glyph and is tappable.
     */
    @Test
    fun `filling a real round's gaps renders the answer's own writing`() {
        val word = spellSyllablePool(2).first()
        val round = buildSpellSyllableRound(
            word,
            SpellSyllableMode.LETTERS_TWO,
            3,
            true,
            SeededGenerator(0x1234),
        )
        val slots: List<LetterFace?> = round.answerFaces

        var seen = 0
        for (cell in round.cells.filter { it.fill }) {
            val expected = round.answerFaces[cell.slotIndex]
            assertEquals(
                SpellSyllableView.CellRender.Filled(
                    expected.glyph,
                    expected.script,
                    "Retirer ${expected.base}",
                ),
                SpellSyllableView.render(cell, slots),
            )
            seen += 1
        }
        assertEquals(round.answer.size, seen)
        // `letters-two` blanks two syllables (every pool word has ≥ 3).
        assertTrue(word.syllables.size >= 3)
    }

    /**
     * THE FAN-IN. Five `EXERCISES` rows reach this one screen, and each is a
     * different game: `App.tsx` passes `mode={meta.spell}` and `mixed={meta.mixed}`
     * while `exercise` stays the row the child tapped, because it is the AWARD
     * KEY. A row wired to the wrong mode plays the wrong game; a row wired to the
     * wrong id moves its stars into another ledger.
     */
    @Test
    fun `the five catalog rows each build their own headline and keep their own award key`() {
        val rows = EXERCISES.filter { it.spell != null }
        assertEquals(5, rows.size)
        assertEquals(
            listOf(
                ExerciseId.SPELL_SYLLABLE,
                ExerciseId.SPELL_SYLLABLE_PLUS,
                ExerciseId.SPELL_TWO_SYLLABLES,
                ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
                ExerciseId.SPELL_TWO_SYLLABLES_MIXED,
            ),
            rows.map { it.id },
        )

        // HEADLINE / MIXED_HEADLINE in `SpellSyllableExercise.tsx`, verbatim.
        val expected = mapOf(
            ExerciseId.SPELL_SYLLABLE to "Complète le mot avec les lettres",
            ExerciseId.SPELL_SYLLABLE_PLUS to "Complète le mot — attention aux intrus",
            ExerciseId.SPELL_TWO_SYLLABLES to "Complète les deux syllabes",
            ExerciseId.SPELL_SYLLABLE_PLUS_MIXED to "La bonne lettre… et la bonne écriture",
            ExerciseId.SPELL_TWO_SYLLABLES_MIXED to "Deux syllabes — la bonne écriture",
        )

        for (row in rows) {
            val h = EngineHarness()
            val model = AssemblyModel.spellSyllable(
                exercise = row.id,
                mode = assertNotNull(row.spell),
                level = 1,
                mixed = row.mixed,
                deps = h.deps,
                rng = SeededGenerator(7),
            )
            assertEquals(expected[row.id], model.headline, "headline of ${row.id}")
            assertEquals(row.id, model.descriptor.exercise, "award key of ${row.id}")
            assertEquals("Réécouter le mot", model.listenAccessibilityLabel)
            assertEquals("Tu as tout réussi !", model.finishedTitle)
        }

        // The plain « Trouve la bonne écriture » headline has no catalog row —
        // there is no `letters-exact` mixed twin — but the mode is authored and
        // the copy exists, so the mapping is pinned here too.
        val h = EngineHarness()
        val orphan = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE,
            mode = SpellSyllableMode.LETTERS_EXACT,
            level = 1,
            mixed = true,
            deps = h.deps,
            rng = SeededGenerator(7),
        )
        assertEquals("Trouve la bonne écriture", orphan.headline)
    }

    /**
     * A mixed round played through the SCREEN's own values: the tile a cell
     * renders is the tile the pick handler dropped, so a right letter in the
     * WRONG writing fills the gap visibly and then fails the row.
     */
    @Test
    fun `a mixed round accepts the right writing and fails the wrong one`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
            mode = SpellSyllableMode.LETTERS_EXTRA,
            level = 1,
            mixed = true,
            deps = h.deps,
            rng = SeededGenerator(0x5EED),
        )
        val answer = model.round.answerFaces

        // The intruders of a mixed round are « the SAME gap letters in the two
        // OTHER writings » — find one, and the slot it is a trap FOR. (Which
        // answer letter gets a trap is up to the seed; the intruder budget is 2
        // and the answer can have more distinct letters than that.)
        val trap = assertNotNull(
            model.round.tray.firstOrNull { tile ->
                val face = spellTileFace(tile)
                answer.any { it.base == face.base && !sameFace(it, face) }
            },
            "a mixed round's intruders are same-letter, other-writing",
        )
        val trapFace = spellTileFace(trap)
        val slot = answer.indexOfFirst { it.base == trapFace.base && !sameFace(it, trapFace) }

        // Spell the row correctly up to that slot…
        fun placeCorrect(index: Int) {
            val tile = assertNotNull(
                model.round.tray.firstOrNull {
                    !model.isTrayTileUsed(it.id) && sameFace(spellTileFace(it), answer[index])
                },
                "no free tray tile for answer face $index",
            )
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, spellTileFace(tile)))
        }
        for (index in 0 until slot) placeCorrect(index)

        // …then the trap. It lands in the gap and is DRAWN in ITS OWN writing —
        // the child has to be able to see what they chose.
        model.pick(trap.id, trapFace)
        val gapCell = assertNotNull(
            model.round.cells.firstOrNull { it.fill && it.slotIndex == slot },
        )
        assertEquals(
            SpellSyllableView.CellRender.Filled(
                trap.glyph,
                trap.script,
                "Retirer ${trap.letter}",
            ),
            SpellSyllableView.render(gapCell, model.slots),
        )

        // Complete the row correctly from there; the whole row still fails.
        for (index in slot + 1 until answer.size) placeCorrect(index)
        assertFalse(model.stars[0], "the wrong writing greys the star at pointer-down")
        h.settle()
        assertEquals("Oh non ! On recommence.", h.audio.sayTexts.last())
        assertTrue(model.slots.all { it == null })
        assertNull(model.slots.getOrNull(0)?.glyph)
        assertEquals(0, model.idx)
        assertFalse(model.done)

        // And the same row, spelled in the round's own writing, passes.
        for (face in answer) {
            val tile = assertNotNull(
                model.round.tray.firstOrNull {
                    !model.isTrayTileUsed(it.id) && sameFace(spellTileFace(it), face)
                },
            )
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, spellTileFace(tile)))
        }
        h.settle()
        assertTrue(model.idx == 1 || model.done)
        assertEquals(1, h.confetti.count)
    }

    /**
     * The prompt is the WORD, spoken; a tray tile previews its canonical letter,
     * never its glyph (a cursive « a » is still « A »).
     */
    @Test
    fun `the prompt speaks the word and a tile previews its canonical letter`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE,
            mode = SpellSyllableMode.LETTERS_EXACT,
            level = 1,
            mixed = false,
            deps = h.deps,
            rng = SeededGenerator(19),
        )
        model.activate()
        h.settle()
        assertEquals(listOf(model.current.word), h.audio.sayTexts)
        assertEquals(listOf(EngineLines.ANNOUNCE_DELAY_MS), h.delays.requests)

        h.audio.clearEvents()
        val tile = model.round.tray.first()
        model.preview(tile.letter)
        h.pump()
        assertEquals(listOf(tile.letter), h.audio.sayTexts)
    }

    /**
     * `removeAt` is the undo the TSX gives a misplaced letter: tap the filled
     * gap and the tile goes home. Nothing about it is a miss (invariant 3) — no
     * star greys, no line plays.
     */
    @Test
    fun `tapping a filled gap sends its tile back to the tray, and costs nothing`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE,
            mode = SpellSyllableMode.LETTERS_EXACT,
            level = 1,
            mixed = false,
            deps = h.deps,
            rng = SeededGenerator(23),
        )
        val tile = model.round.tray.first()
        model.pick(tile.id, spellTileFace(tile))
        assertTrue(model.isTrayTileUsed(tile.id))
        assertTrue(model.isSlotRemovable(0))

        model.removeAt(0)
        assertFalse(model.isTrayTileUsed(tile.id))
        assertNull(model.slots[0])
        assertTrue(model.stars[0], "an undo is not a miss")
        assertTrue(h.audio.sayTexts.isEmpty())
    }
}

// ===========================================================================
// Shared chrome
// ===========================================================================

class SpellChromeTest {

    /**
     * `TRAY_COLORS[i % TRAY_COLORS.length]`, verbatim from BOTH TSX files — blue
     * first, and the rotation is by POSITION IN THE TRAY.
     */
    @Test
    fun `the tray palette is the five authored paints, cycling by position`() {
        val expected = listOf("#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65")
        expected.forEachIndexed { index, hex ->
            assertEquals(HexColor(hex), SpellTray.paint(index).bg, "tray paint $index")
        }
        // The inks, spot-checked at both ends of the ramp.
        assertEquals(HexColor("#062E3D"), SpellTray.paint(0).ink)
        assertEquals(HexColor("#4A2317"), SpellTray.paint(4).ink)
        // …and it wraps rather than running off the end.
        assertEquals(HexColor("#4FC3F7"), SpellTray.paint(5).bg)
        assertEquals(SpellTray.paint(3).bg, SpellTray.paint(13).bg)
        // It is the TRAY ramp, not the pick-tile one: same five paints, other
        // rotation, and collapsing them would recolour four engines.
        assertEquals(Palette.trayColors[0], SpellTray.paint(0))
        assertTrue(Palette.trayColors[0] != Palette.tileColors[0])
    }

    /**
     * `size="clamp(60px,17vw,92px)"` / `fontSize="clamp(26px,7vw,48px)"` —
     * authored identically in both files, and `gap-3` between tiles.
     */
    @Test
    fun `the tray tile is 60 to 92 dp with a 26 to 48 dp glyph`() {
        near(SpellTray.SIZE.resolve(320f), 60f, "tray tile at 320")
        near(SpellTray.SIZE.resolve(390f), 66.3f, "tray tile at 390")
        near(SpellTray.SIZE.resolve(600f), 92f, "tray tile at 600")
        near(SpellTray.FONT_SIZE.resolve(390f), 27.3f, "tray glyph at 390")
        near(SpellTray.FONT_SIZE.resolve(900f), 48f, "tray glyph at 900")
        assertEquals(12.dp, SpellTray.GAP)

        // Invariant 6's floor is 92 dp for a DEFAULT tile; the tray authors 60,
        // which is what the web ships. Recorded, not silently raised — and it
        // still clears the platform tap-target minimum at every viewport.
        assertTrue(SpellTray.SIZE.min < 92f)
        assertTrue(SpellTray.SIZE.resolve(320f) >= 44f)
    }

    /**
     * The slot faces, from the shared inline style:
     * `background: s ? "#FFFFFF" : "transparent"`,
     * `border: s ? "none" : "3px dashed #E4A15E"`,
     * and SpellSyllable's written cell on `#FFF3E0`.
     */
    @Test
    fun `only the empty face is dashed, and only the filled face casts a shadow`() {
        assertEquals(3.dp, SpellSlot.BORDER_WIDTH)
        assertEquals(listOf(9.dp, 9.dp), SpellSlot.DASH)
        assertEquals(HexColor("#E4A15E"), Palette.slotDashed)
        assertEquals(HexColor("#FFF3E0"), Palette.slotRevealed)

        // `0 6px 14px rgba(0,0,0,0.12)`. CSS defines the blur as twice the
        // Gaussian sigma, so the drawn radius is 7 — computed by `CssShadow`
        // rather than transcribed, unlike the iOS port.
        assertEquals(6.dp, SpellSlot.FILLED_SHADOW.y)
        assertEquals(14.dp, SpellSlot.FILLED_SHADOW.blur)
        assertEquals(0.12f, SpellSlot.FILLED_SHADOW.opacity)
        assertEquals(7.dp, SpellSlot.FILLED_SHADOW.blurRadius)

        // Three faces, and the third is SpellSyllable's alone.
        assertEquals(3, SpellSlotFace.entries.size)
    }

    /**
     * The two engines' rows are NOT interchangeable, and a port that shared one
     * set of numbers would look plausible: SpellSound spaces its slots `gap-2`
     * with the listen button at `mb-5`; SpellSyllable packs the whole word at
     * `gap-1.5` with `mb-4`.
     */
    @Test
    fun `the two spelling engines author different row gaps and listen margins`() {
        assertEquals(8.dp, SpellSoundView.ROW_SPACING) // gap-2
        assertEquals(6.dp, SpellSyllableView.ROW_SPACING) // gap-1.5
        assertEquals(20.dp, SpellSoundView.LISTEN_MARGIN_BOTTOM) // mb-5
        assertEquals(16.dp, SpellSyllableView.LISTEN_MARGIN_BOTTOM) // mb-4

        // `mb-6` under the row, `mb-1` under the headline, `px-4 pb-8 pt-2` on
        // the column — shared by both files.
        assertEquals(24.dp, SpellSoundView.ROW_MARGIN_BOTTOM)
        assertEquals(24.dp, SpellSyllableView.ROW_MARGIN_BOTTOM)
        assertEquals(4.dp, SpellSoundView.HEADLINE_MARGIN_BOTTOM)
        assertEquals(4.dp, SpellSyllableView.HEADLINE_MARGIN_BOTTOM)
        assertEquals(8.dp, SpellSoundView.PADDING_TOP)
        assertEquals(16.dp, SpellSoundView.PADDING_HORIZONTAL)
        assertEquals(32.dp, SpellSoundView.PADDING_BOTTOM)
        assertEquals(SpellSoundView.PADDING_TOP, SpellSyllableView.PADDING_TOP)
        assertEquals(SpellSoundView.PADDING_HORIZONTAL, SpellSyllableView.PADDING_HORIZONTAL)
        assertEquals(SpellSoundView.PADDING_BOTTOM, SpellSyllableView.PADDING_BOTTOM)

        // `relative z-[41]` on both content columns.
        assertEquals(41f, SpellSoundView.CONTENT_Z_INDEX)
        assertEquals(41f, SpellSyllableView.CONTENT_Z_INDEX)
    }
}
