package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableTile
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.buildSyllableRound
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.components.TileMetrics
import fr.dappit.attrapelettres.ui.design.FluidSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What `AssembleView` OWNS, asserted against `src/exercises/AssembleExercise.tsx`.
//
// Every expected number and string below is read out of the TSX (or, for the
// round shapes, out of `buildSyllableRound` in `src/levels.ts`), never out of the
// Kotlin. Nothing here re-asserts `AssemblyModel`'s loop — the pick path, the
// « Oh non » pacing, the award and the star greying are `AssemblyModelTest`'s.
// What is here instead is the projection, the authored metrics, and the
// Assemble-specific French the screen speaks and prints.
//
// The through-line of the slot and tray suites is the root CLAUDE.md's rule that
// this is ONE ENGINE FOR THREE MODES: the projection is a function of
// `slots` / `locked` / `tray` alone, so the three `SyllableMode`s differ only in
// the data those arrays carry. If a `when (mode)` ever appears in the view, the
// « order and order-distractor project identical rows » test is what fails.

// --- Metrics ------------------------------------------------------------------

class AssembleMetricsTest {

    @Test
    fun `the round column's box - px-4 pb-8 pt-2, z-41`() {
        assertEquals(16f, AssembleMetrics.CONTENT_PADDING_X.value)
        assertEquals(8f, AssembleMetrics.CONTENT_PADDING_TOP.value)
        assertEquals(32f, AssembleMetrics.CONTENT_PADDING_BOTTOM.value)
        // One above GameFrame's confetti canvas (`zIndex: 40`).
        assertEquals(41f, AssembleMetrics.Z_INDEX)
    }

    @Test
    fun `the consigne line - text-base, mb-1`() {
        assertEquals(16f, AssembleMetrics.HEADLINE_FONT_SIZE.value)
        assertEquals(4f, AssembleMetrics.HEADLINE_SPACING.value)
    }

    @Test
    fun `the mascot renders at Mascot tsx's default size`() {
        assertEquals(88f, AssembleMetrics.MASCOT_SIZE.value)
    }

    @Test
    fun `the picture - margin 2px 0, clamp(64px,22vw,120px)`() {
        assertEquals(2f, AssembleMetrics.WORD_ICON_MARGIN_Y.value)
        assertEquals(FluidSpec(64f, 22f, 120f), AssembleMetrics.WORD_ICON_SIZE)
        // 22vw of a 390 dp phone = 85.8, inside the clamp.
        assertEquals(85.8f, AssembleMetrics.WORD_ICON_SIZE.resolve(390f), 1e-3f)
    }

    @Test
    fun `the listen button - mb-5 px-5 py-2 text-lg`() {
        assertEquals(20f, AssembleMetrics.LISTEN_SPACING.value)
        assertEquals(20f, AssembleMetrics.LISTEN_PADDING_X.value)
        assertEquals(8f, AssembleMetrics.LISTEN_PADDING_Y.value)
        assertEquals(18f, AssembleMetrics.LISTEN_FONT_SIZE.value)
    }

    @Test
    fun `slot row - mb-6 wrapper, gap-2 inner row`() {
        assertEquals(24f, AssembleMetrics.SLOT_ROW_SPACING.value)
        assertEquals(8f, AssembleMetrics.SLOT_GAP.value)
    }

    @Test
    fun `a slot's own box, from the inline style object`() {
        assertEquals(FluidSpec(56f, 16f, 84f), AssembleMetrics.SLOT_SIDE)
        assertEquals(FluidSpec(22f, 6f, 40f), AssembleMetrics.SLOT_FONT_SIZE)
        assertEquals(10f, AssembleMetrics.SLOT_PADDING_X.value)
        assertEquals(20f, AssembleMetrics.SLOT_CORNER_RADIUS.value)
        assertEquals(3f, AssembleMetrics.SLOT_BORDER_WIDTH.value)
    }

    @Test
    fun `the filled slot's shadow is 0 6px 14px rgba(0,0,0,0-12)`() {
        assertEquals(6f, AssembleMetrics.SLOT_SHADOW.y.value)
        assertEquals(14f, AssembleMetrics.SLOT_SHADOW.blur.value)
        assertEquals(0.12f, AssembleMetrics.SLOT_SHADOW.opacity)
        assertEquals(0f, AssembleMetrics.SLOT_SHADOW.spread.value)
        // CSS blur is twice the Gaussian radius.
        assertEquals(7f, AssembleMetrics.SLOT_SHADOW.blurRadius.value)
    }

    @Test
    fun `the dashed border is 3 on, 3 off at the authored width`() {
        // Browser behaviour, not an authored value: Blink and WebKit draw
        // `3 * border-width` on and the same off.
        val width = AssembleMetrics.SLOT_BORDER_WIDTH.value
        assertEquals(3f * width, AssembleMetrics.SLOT_DASH_ON.value)
        assertEquals(AssembleMetrics.SLOT_DASH_ON, AssembleMetrics.SLOT_DASH_OFF)
    }

    @Test
    fun `the tray - gap-3, clamp(64px,18vw,100px) tiles at clamp(20px,5-5vw,36px)`() {
        assertEquals(12f, AssembleMetrics.TRAY_GAP.value)
        assertEquals(FluidSpec(64f, 18f, 100f), AssembleMetrics.TILE_SIDE)
        assertEquals(FluidSpec(20f, 5.5f, 36f), AssembleMetrics.TILE_FONT_SIZE)
    }

    @Test
    fun `the authored tile side is SMALLER than Tile's own 92 dp floor`() {
        // Not a preference — a recorded fact about frozen behaviour. The TSX
        // authors `clamp(64px,18vw,100px)`, 70.2 dp on a 390 dp phone, below
        // `TileMetrics.DEFAULT_SIZE`'s 92 dp minimum. If the authored spec is
        // ever raised to meet that floor, this test fails and the change is a
        // deliberate behaviour change rather than a silent one.
        assertEquals(70.2f, AssembleMetrics.TILE_SIDE.resolve(390f), 1e-3f)
        assertTrue(AssembleMetrics.TILE_SIDE.min < TileMetrics.DEFAULT_SIZE.min)
        // It does reach that floor on a wide tablet: 18vw of 512 = 92.16.
        assertTrue(AssembleMetrics.TILE_SIDE.resolve(512f) > 92f)
    }

    @Test
    fun `every authored tile size still clears the platform tap floor`() {
        // Invariant 6. The clamp's MINIMUM is what a narrow phone gets, so one
        // check covers every viewport.
        assertTrue(AssembleMetrics.TILE_SIDE.min >= TileMetrics.PLATFORM_MINIMUM_TAP_TARGET.value)
        for (width in listOf(320f, 360f, 390f, 430f, 600f, 1024f)) {
            assertTrue(
                AssembleMetrics.TILE_SIDE.resolve(width) >=
                    TileMetrics.PLATFORM_MINIMUM_TAP_TARGET.value,
                "tile at $width",
            )
        }
    }
}

// --- The slot row --------------------------------------------------------------

class AssembleSlotTest {

    @Test
    fun `an empty slot has no text and the loud dashed border`() {
        val slot = AssembleSlot.row(filled = listOf(null), locked = listOf(false))[0]
        assertEquals("", slot.text)
        assertFalse(slot.isFilled)
        assertFalse(slot.isPreRevealed)
        assertFalse(slot.isRemovable)
        assertNull(slot.removeLabel)
        assertEquals("#E4A15E", slot.borderHex)
    }

    @Test
    fun `a slot the CHILD filled is a button labelled Retirer`() {
        val slot = AssembleSlot.row(filled = listOf("SON"), locked = listOf(false))[0]
        assertEquals("SON", slot.text)
        assertTrue(slot.isFilled)
        assertTrue(slot.isRemovable)
        assertEquals("Retirer SON", slot.removeLabel)
        // `border: "none"` and the drop shadow appear together.
        assertNull(slot.borderHex)
    }

    @Test
    fun `a pre-revealed fill-blank slot is filled but NOT removable`() {
        val slot = AssembleSlot.row(filled = listOf("MAI"), locked = listOf(true))[0]
        assertEquals("MAI", slot.text)
        assertTrue(slot.isFilled)
        assertTrue(slot.isPreRevealed)
        assertFalse(slot.isRemovable)
        assertNull(slot.removeLabel)
        assertNull(slot.borderHex)
    }

    @Test
    fun `empty AND pre-revealed takes the quiet dashed border - a ported dead branch`() {
        // `round.locked[i] ? "3px dashed #C9A87A" : …`. Unreachable in this
        // engine (a fill-blank round's locked slots always arrive filled), but
        // the TSX writes the branch, so the projection carries it.
        val slot = AssembleSlot.row(filled = listOf(null), locked = listOf(true))[0]
        assertEquals("#C9A87A", slot.borderHex)
        assertFalse(slot.isRemovable)
    }

    @Test
    fun `a short locked mask reads as not-locked, like JS undefined`() {
        // `round.locked[i]` past the end is `undefined`, which is falsy. So the
        // slot is an ordinary one: loud border when empty, removable when filled.
        val row = AssembleSlot.row(filled = listOf(null, "SON"), locked = emptyList())
        assertEquals("#E4A15E", row[0].borderHex)
        assertTrue(row[1].isRemovable)
        assertEquals("Retirer SON", row[1].removeLabel)
    }

    @Test
    fun `fill-blank - exactly one gap, every other syllable pre-revealed and fixed`() {
        val word = SyllableWord(word = "MAISON", syllables = listOf("MAI", "SON"), emoji = "🏠")
        val round = buildSyllableRound(word, SyllableMode.FILL_BLANK, SeededGenerator(7))
        val row = AssembleSlot.row(filled = round.slots, locked = round.locked)

        assertEquals(2, row.size)
        assertEquals(1, row.count { !it.isFilled })
        val gap = assertNotNull(row.firstOrNull { !it.isFilled })
        assertFalse(gap.isPreRevealed)
        assertEquals("#E4A15E", gap.borderHex)
        // Nothing is removable before the child has dropped anything: the only
        // filled slots are the round's own.
        assertTrue(row.none { it.isRemovable })
        for (slot in row.filter { it.isPreRevealed }) {
            assertTrue(slot.isFilled)
            assertTrue(slot.text.isNotEmpty())
        }
    }

    @Test
    fun `fill-blank - the child's syllable becomes removable, the revealed ones never do`() {
        val word = SyllableWord(word = "MAISON", syllables = listOf("MAI", "SON"), emoji = "🏠")
        val round = buildSyllableRound(word, SyllableMode.FILL_BLANK, SeededGenerator(7))
        val gapIndex = round.slots.indexOfFirst { it == null }
        assertTrue(gapIndex >= 0)

        // What `pick` does to `slots`: the value lands in the first empty slot.
        val filled = round.slots.toMutableList().also { it[gapIndex] = "XX" }
        val row = AssembleSlot.row(filled = filled, locked = round.locked)

        assertTrue(row[gapIndex].isRemovable)
        assertEquals("Retirer XX", row[gapIndex].removeLabel)
        for ((i, slot) in row.withIndex()) {
            if (i == gapIndex) continue
            assertFalse(slot.isRemovable)
            assertNull(slot.removeLabel)
        }
    }

    @Test
    fun `order and order-distractor project IDENTICAL slot rows - the mode is data`() {
        val word = SyllableWord(
            word = "CHOCOLAT",
            syllables = listOf("CHO", "CO", "LAT"),
            emoji = "🍫",
        )
        val order = buildSyllableRound(word, SyllableMode.ORDER, SeededGenerator(3))
        val distractor =
            buildSyllableRound(word, SyllableMode.ORDER_DISTRACTOR, SeededGenerator(3))

        val a = AssembleSlot.row(filled = order.slots, locked = order.locked)
        val b = AssembleSlot.row(filled = distractor.slots, locked = distractor.locked)
        assertEquals(a, b)

        // Three empty, loud-bordered, non-removable slots: `syl.map(() => null)`
        // and `syl.map(() => false)`.
        assertEquals(3, a.size)
        assertTrue(a.all { it.text.isEmpty() })
        assertTrue(a.all { it.borderHex == "#E4A15E" })
        assertTrue(a.none { it.isPreRevealed || it.isRemovable })
    }
}

// --- The tray ------------------------------------------------------------------

class AssembleTrayTileTest {

    /**
     * Ids stride by 3, deliberately: `TileIdAllocator` hands out CONSECUTIVE ids
     * in production, and with consecutive ids `id % 5` and `position % 5` agree —
     * a rotation keyed on the wrong one would pass unnoticed.
     */
    private fun tiles(syllables: List<String>): List<SyllableTile> =
        syllables.mapIndexed { i, s -> SyllableTile(id = 100 + 3 * i, syllable = s) }

    @Test
    fun `TRAY_COLORS rotate by position and wrap at five`() {
        // `TRAY_COLORS` in AssembleExercise.tsx, in order.
        val bg = listOf("#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65")
        val ink = listOf("#062E3D", "#213606", "#4A3B00", "#2C1846", "#4A2317")

        val row = AssembleTrayTile.row(tiles(listOf("A", "B", "C", "D", "E", "F"))) { false }
        assertEquals(6, row.size)
        row.forEachIndexed { i, tile ->
            assertEquals(bg[i % 5], tile.paint.bg.hex, "bg at $i")
            assertEquals(ink[i % 5], tile.paint.ink.hex, "ink at $i")
        }
        // `i % TRAY_COLORS.length` — the sixth tile is blue again, not a crash
        // and not a repeat of the fifth.
        assertEquals("#4FC3F7", row[5].paint.bg.hex)
    }

    @Test
    fun `a tile carries both labels - Syllabe CHA and Ecouter CHA`() {
        val row = AssembleTrayTile.row(tiles(listOf("CHA"))) { false }
        assertEquals("Syllabe CHA", row[0].label)
        assertEquals("Écouter CHA", row[0].previewLabel)
        assertEquals("CHA", row[0].syllable)
        assertEquals(100, row[0].id)
    }

    @Test
    fun `a dropped tile greys out, the rest do not`() {
        val used = setOf(103)
        val row = AssembleTrayTile.row(tiles(listOf("A", "B", "C"))) { it in used }
        assertEquals(listOf(false, true, false), row.map { it.isDisabled })
    }

    @Test
    fun `order-distractor adds one tray tile, fill-blank offers exactly two`() {
        val word = SyllableWord(
            word = "CHOCOLAT",
            syllables = listOf("CHO", "CO", "LAT"),
            emoji = "🍫",
        )

        val order = buildSyllableRound(word, SyllableMode.ORDER, SeededGenerator(11))
        assertEquals(3, AssembleTrayTile.row(order.tray) { false }.size)

        val distractor =
            buildSyllableRound(word, SyllableMode.ORDER_DISTRACTOR, SeededGenerator(11))
        val distractorRow = AssembleTrayTile.row(distractor.tray) { false }
        assertEquals(4, distractorRow.size)
        // The intruder is not one of the word's own syllables.
        assertEquals(1, distractorRow.count { it.syllable !in word.syllables })

        val blank = buildSyllableRound(word, SyllableMode.FILL_BLANK, SeededGenerator(11))
        assertEquals(2, AssembleTrayTile.row(blank.tray) { false }.size)
    }
}

// --- The screen's copy and its loop, through the real factory -------------------

class AssembleEngineTest {

    private fun engine(mode: SyllableMode, exercise: ExerciseId, h: EngineHarness, seed: Long) =
        AssemblyModel.assemble(
            exercise = exercise,
            mode = mode,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(seed),
        )

    /** The first tray tile carrying [syllable] that is not already in a slot. */
    private fun freeTile(model: AssembleModel, syllable: String): SyllableTile? =
        model.round.tray.firstOrNull {
            it.syllable == syllable && !model.isTrayTileUsed(it.id)
        }

    /** The tray as the view projects it, with the model's own `used` rule. */
    private fun trayRow(model: AssembleModel): List<AssembleTrayTile> =
        AssembleTrayTile.row(model.round.tray) { model.isTrayTileUsed(it) }

    @Test
    fun `the consigne is MODE_HINT, verbatim - the only place the mode shows`() {
        // `levels.ts`' MODE_HINT, byte for byte, U+2019 apostrophes and U+2026
        // ellipsis included.
        val expected = mapOf(
            SyllableMode.FILL_BLANK to "Trouve la syllabe manquante",
            SyllableMode.ORDER to "Remets les syllabes dans l’ordre",
            SyllableMode.ORDER_DISTRACTOR to "Range le mot… et évite l’intrus !",
        )
        val ids = mapOf(
            SyllableMode.FILL_BLANK to ExerciseId.FILL_BLANK,
            SyllableMode.ORDER to ExerciseId.ORDER_SYLLABLES,
            SyllableMode.ORDER_DISTRACTOR to ExerciseId.FIND_INTRUDER,
        )
        for (mode in SyllableMode.entries) {
            val h = EngineHarness()
            val model = engine(mode, ids.getValue(mode), h, seed = 4)
            assertEquals(expected.getValue(mode), model.headline, mode.wire)
            // Same everywhere else: one engine, three modes.
            assertEquals("Répéter le mot", model.listenAccessibilityLabel, mode.wire)
            assertEquals("Tu as tout réussi !", model.finishedTitle, mode.wire)
        }
    }

    @Test
    fun `the prompt is the WORD, announced 350 ms after mount and replayed by the pill`() {
        val h = EngineHarness()
        val model = engine(SyllableMode.ORDER, ExerciseId.ORDER_SYLLABLES, h, seed = 21)
        val word = model.round.word.word

        model.activate()
        h.pump()
        assertEquals(listOf(350L), h.delays.requests)
        assertEquals(listOf(word), h.audio.sayTexts)

        // `aria-label="Répéter le mot"` — the pill speaks the same line.
        h.audio.clearEvents()
        model.replayPrompt()
        h.pump()
        assertEquals(listOf(word), h.audio.sayTexts)
    }

    @Test
    fun `a complete, correct row says Oui ! MOT- at rate 0-98, then advances`() {
        val h = EngineHarness()
        val model = engine(SyllableMode.ORDER, ExerciseId.ORDER_SYLLABLES, h, seed = 33)
        val word = model.round.word
        h.audio.clearEvents()

        for (syllable in word.syllables) {
            val tile = assertNotNull(freeTile(model, syllable))
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.syllable))
        }
        // Invariant 1: the chime and the confetti fired inside the last pick,
        // before anything was pumped.
        assertTrue(h.audio.events.contains(AudioEvent.Success))
        assertEquals(1, h.confetti.count)
        // The star survives a careful row.
        assertTrue(model.stars[0])

        h.pump()
        val success = h.audio.events.filterIsInstance<AudioEvent.Say>().first()
        assertEquals("Oui ! ${word.word}.", success.text)
        assertEquals(0.98, success.rate)
    }

    @Test
    fun `a wrong row says Oh non, greys ONE star and loses nothing`() {
        val h = EngineHarness()
        val model = engine(SyllableMode.ORDER, ExerciseId.ORDER_SYLLABLES, h, seed = 12)
        val word = model.round.word
        // What makes "backwards" a WRONG row at all. Tier 1 is 2-syllable words
        // and no authored split repeats a syllable, so this always holds; it is
        // asserted rather than assumed because a new word could break it and the
        // test would then silently assert the happy path.
        assertTrue(word.syllables != word.syllables.reversed())

        // Fill the row in the WRONG order: last syllable first.
        for (syllable in word.syllables.reversed()) {
            val tile = assertNotNull(freeTile(model, syllable))
            // Invariant 3: even the tile that completes a wrong row is ACCEPTed,
            // so it does not shake. The row-level « Oh non » is the feedback.
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.syllable))
        }
        assertTrue(h.audio.events.contains(AudioEvent.Oops))
        // The star greys NOW, at pointer-down, and exactly one does.
        assertFalse(model.stars[0])
        assertEquals(1, model.stars.count { !it })

        h.pump()
        assertEquals(
            "Oh non ! On recommence.",
            h.audio.events.filterIsInstance<AudioEvent.Say>().last().text,
        )
        // Nothing terminal: same round, tray back, model still playable.
        assertFalse(model.done)
        assertEquals(0, model.idx)
        assertTrue(model.used.isEmpty())
        assertEquals(0, h.award.calls.size)

        // And the very next, correct row still plays.
        for (syllable in word.syllables) {
            val tile = assertNotNull(freeTile(model, syllable))
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.syllable))
        }
        h.pump()
        assertTrue(model.idx == 1 || model.done)
    }

    @Test
    fun `a slot the child filled can be tapped back out mid-row`() {
        val h = EngineHarness()
        val model = engine(SyllableMode.ORDER, ExerciseId.ORDER_SYLLABLES, h, seed = 55)
        val tile = model.round.tray[0]
        model.pick(tile.id, tile.syllable)

        val row = AssembleSlot.row(filled = model.slots, locked = model.lockedMask)
        assertTrue(row[0].isRemovable)
        assertEquals("Retirer ${tile.syllable}", row[0].removeLabel)
        assertTrue(trayRow(model)[0].isDisabled)

        model.removeAt(0)
        assertNull(model.slots[0])
        assertFalse(trayRow(model)[0].isDisabled)
    }

    @Test
    fun `finishing speaks the assembly bravo and awards exactly once`() {
        val h = EngineHarness()
        h.award.result = 11
        val model = engine(SyllableMode.FILL_BLANK, ExerciseId.FILL_BLANK, h, seed = 77)

        var safety = 0
        while (!model.done && safety < 200) {
            safety += 1
            val gap = model.slots.indexOfFirst { it == null }
            val wanted = model.round.word.syllables[gap]
            val tile = assertNotNull(model.round.tray.firstOrNull { it.syllable == wanted })
            model.pick(tile.id, tile.syllable)
            h.pump()
        }

        assertTrue(model.done)
        assertEquals(11, model.earned, "earned is sessionReward's value, shown as-is")
        assertEquals(1, h.award.calls.size)
        assertEquals(ExerciseId.FILL_BLANK, h.award.calls[0].exercise)
        assertEquals(model.totalRounds, h.award.calls[0].perfect)
        assertTrue(h.audio.sayTexts.contains("Bravo ! Tu as tout réussi !"))
        // GameFrame's `done` input: `done ? session.length : idx`.
        assertEquals(model.totalRounds, model.progressDone)
    }
}
