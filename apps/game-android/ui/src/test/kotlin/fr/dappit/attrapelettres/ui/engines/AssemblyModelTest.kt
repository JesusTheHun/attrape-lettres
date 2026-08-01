package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SpellSyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.SOUND_PICK
import fr.dappit.attrapelettres.core.levels.SOUND_REPEATS
import fr.dappit.attrapelettres.core.levels.soundLevel
import fr.dappit.attrapelettres.core.levels.spellSyllableLevel
import fr.dappit.attrapelettres.core.levels.spellSyllablePool
import fr.dappit.attrapelettres.core.levels.syllablePool
import fr.dappit.attrapelettres.core.levels.syllableTier
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The assembly state machine, asserted against the TSX: AssembleExercise is the
// reference for the loop; SpellSound and SpellSyllable differ only through the
// descriptor.

/**
 * A fully scripted assembly: every round has the same `answer`, and the item is
 * its own round. `seeded` / `lockedMask` default to an all-empty row.
 */
private fun makeAssembly(
    items: List<String>,
    answer: List<String>,
    h: EngineHarness,
    seeded: List<String?>? = null,
    lockedMask: List<Boolean>? = null,
): AssemblyModel<String, String, String> = AssemblyModel(
    descriptor = AssemblyDescriptor(
        exercise = ExerciseId.ORDER_SYLLABLES,
        level = 1,
        headline = "H",
        finishedTitle = "T",
        listenAccessibilityLabel = "L",
        buildSession = { items },
        buildRound = { item, _ -> item },
        promptLine = { "P-$it" },
        successLine = { "S-$it" },
        seededSlots = { seeded ?: List(answer.size) { null } },
        lockedSlots = { lockedMask ?: List(answer.size) { false } },
        judge = { _, filled -> filled == answer },
    ),
    deps = h.deps,
)

class AssemblyHandlerTest {

    @Test
    fun `a value lands in the first empty slot with no judgement`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r"), listOf("BA", "TO"), h)

        assertEquals(Verdict.ACCEPT, model.pick(1, "TO"))
        assertEquals(listOf("TO", null), model.slots)
        assertEquals(listOf(1, null), model.slotTile)
        assertEquals(setOf(1), model.used)
        assertTrue(model.isTrayTileUsed(1))
        // A partial row: pop only — no judgement, no chime, no confetti.
        assertEquals(listOf(AudioEvent.Unlock, AudioEvent.Pop), h.audio.events)
        assertEquals(0, h.confetti.count)
        assertEquals(listOf(true), model.stars.toList())
    }

    @Test
    fun `removeAt sends the tile home`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r"), listOf("BA", "TO"), h)
        model.pick(1, "TO")
        h.audio.clearEvents()

        assertTrue(model.isSlotRemovable(0))
        model.removeAt(0)
        assertEquals(listOf(AudioEvent.Pop), h.audio.events)
        assertEquals(listOf(null, null), model.slots)
        assertEquals(listOf(null, null), model.slotTile)
        assertTrue(model.used.isEmpty())

        // An empty slot ignores the tap — silently.
        h.audio.clearEvents()
        model.removeAt(1)
        assertTrue(h.audio.events.isEmpty())
    }

    @Test
    fun `removeAt refuses pre-revealed slots`() {
        val h = EngineHarness()
        val model = makeAssembly(
            items = listOf("r"),
            answer = listOf("BA", "TO"),
            h = h,
            seeded = listOf("BA", null),
            lockedMask = listOf(true, false),
        )

        assertEquals(listOf("BA", null), model.slots, "fill-blank seeds the revealed syllable")
        assertFalse(model.isSlotRemovable(0), "pre-revealed slots are not interactive")
        model.removeAt(0)
        assertEquals(listOf("BA", null), model.slots)
        assertTrue(h.audio.events.isEmpty())
    }

    @Test
    fun `a correct row celebrates, then advances with an IMMEDIATE announce`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA", "TO"), h)
        model.pick(1, "BA")
        h.audio.clearEvents()

        assertEquals(Verdict.ACCEPT, model.pick(2, "TO"))
        // The completing tap: pop, then the row-level success + confetti.
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Success),
            h.audio.events,
        )
        assertEquals(1, h.confetti.count)
        assertEquals(Mood.HAPPY, model.mood)

        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say("S-r1", EngineLines.SUCCESS_RATE)))
        assertEquals(1, model.idx)
        assertEquals(Mood.IDLE, model.mood)
        assertEquals(listOf(null, null), model.slots, "fresh empty slots")
        assertTrue(model.used.isEmpty())
        // The next round announces IMMEDIATELY — no 350 ms request was made.
        assertTrue(h.audio.events.contains(AudioEvent.Say("P-r2", 0.94)))
        assertTrue(h.delays.requests.isEmpty(), "only round 0 uses the delayed announce")
    }

    @Test
    fun `a wrong row oopses, greys the star and resets`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA", "TO"), h)
        h.audio.holdSays()
        model.pick(1, "TO")
        h.audio.clearEvents()

        assertEquals(
            Verdict.ACCEPT,
            model.pick(2, "BA"),
            "the tile that completes a wrong row does NOT shake",
        )
        // `oops` — the two-note wah-wah — never `nudge`; the star greys NOW.
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Oops),
            h.audio.events,
        )
        assertFalse(h.audio.events.contains(AudioEvent.Nudge))
        assertEquals(listOf(false, true), model.stars.toList())
        assertEquals(0, h.confetti.count)
        assertEquals(Mood.IDLE, model.mood, "a wrong row never changes the mascot")

        h.pump()
        assertEquals(listOf(EngineLines.OH_NON), h.audio.pendingSayTexts)

        // The row is locked while « Oh non » plays.
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick(3, "BA"))
        assertTrue(h.audio.events.isEmpty(), "taps during the line are silent rejects")
        model.removeAt(0)
        assertEquals(listOf("TO", "BA"), model.slots, "no undo while locked")

        // The line ends → wipe back to the seeded row, unlocked, no cooldown.
        h.audio.resolveNextSay(true)
        h.pump()
        assertEquals(listOf(null, null), model.slots)
        assertTrue(model.used.isEmpty())
        assertEquals(listOf(null, null), model.slotTile)
        assertEquals(0, model.idx, "the round replays — nothing lost, no fail state")
        assertEquals(
            Verdict.ACCEPT,
            model.pick(1, "BA"),
            "immediately pickable again — the assembly family has NO miss cooldown",
        )
    }

    @Test
    fun `the wrong-row reset happens even if the line was cut short`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1"), listOf("BA", "TO"), h)
        h.audio.queueSayResult(false) // « Oh non » superseded — the reset must STILL happen
        model.pick(1, "TO")
        model.pick(2, "BA")
        h.pump()

        assertEquals(listOf(null, null), model.slots)
        assertTrue(model.used.isEmpty())
        assertEquals(Verdict.ACCEPT, model.pick(1, "BA"), "unlocked after the cut line")
    }

    @Test
    fun `the wrong-row reset restores the seeded fill-blank slots`() {
        val h = EngineHarness()
        val model = makeAssembly(
            items = listOf("r1"),
            answer = listOf("BA", "TO"),
            h = h,
            seeded = listOf("BA", null),
            lockedMask = listOf(true, false),
        )

        model.pick(9, "ZU") // fills the one gap → wrong row
        h.pump()
        assertEquals(listOf("BA", null), model.slots, "pre-revealed syllables survive the wipe")
        assertTrue(model.used.isEmpty())
    }

    @Test
    fun `the advance is gated on the success line, but the reset is not`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA"), h)
        h.audio.queueSayResult(false)
        model.pick(1, "BA") // correct row, line cut short
        h.pump()

        assertTrue(h.audio.events.contains(AudioEvent.Say("S-r1", EngineLines.SUCCESS_RATE)))
        assertEquals(0, model.idx, "a cut success line never advances")
        // Still locked — the TSX leaves `locked` true on a cut celebration.
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick(2, "BA"))
        assertTrue(h.audio.events.isEmpty())
    }

    @Test
    fun `a pick on a full row is rejected AFTER the pop`() {
        val h = EngineHarness()
        val model = makeAssembly(
            items = listOf("r"),
            answer = listOf("BA", "TO"),
            h = h,
            seeded = listOf("BA", "TO"),
            lockedMask = listOf(true, true),
        )

        assertEquals(Verdict.REJECT, model.pick(1, "X"))
        // Faithful ordering: unlock + pop fire BEFORE the no-empty-slot check.
        assertEquals(listOf(AudioEvent.Unlock, AudioEvent.Pop), h.audio.events)
    }

    @Test
    fun `a deactivated model neither resets nor advances`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1"), listOf("BA", "TO"), h)
        h.audio.holdSays()
        model.pick(1, "TO")
        model.pick(2, "BA") // wrong row → « Oh non » pending
        h.pump()

        model.deactivate()
        h.audio.resolveNextSay(true)
        h.pump()
        assertEquals(listOf("TO", "BA"), model.slots, "a dead engine never mutates state")
    }

    @Test
    fun `finishing awards once and speaks the assembly bravo`() {
        val h = EngineHarness()
        h.award.result = 11
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA"), h)

        model.pick(1, "BA")
        h.pump()
        assertEquals(1, model.idx)
        model.pick(2, "BA")
        h.pump()

        assertTrue(model.done)
        assertEquals(Mood.CHEER, model.mood)
        assertEquals(11, model.earned)
        assertEquals(1, h.award.calls.size)
        assertEquals(ExerciseId.ORDER_SYLLABLES, h.award.calls[0].exercise)
        assertEquals(2, h.award.calls[0].perfect)
        assertEquals(2, h.award.calls[0].total)
        assertTrue(h.audio.sayTexts.contains(EngineLines.BRAVO_SUCCEEDED))
    }

    @Test
    fun `round 0 announces after 350 ms, on activate, once`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA"), h)

        model.activate()
        assertEquals(AudioEvent.Unlock, h.audio.events.first())
        h.pump()
        assertTrue(h.audio.sayTexts.contains("P-r1"))
        assertEquals(listOf(EngineLines.ANNOUNCE_DELAY_MS), h.delays.requests)

        // Re-activation does not re-announce (the TSX effect has [] deps).
        model.activate()
        h.pump()
        assertEquals(listOf(EngineLines.ANNOUNCE_DELAY_MS), h.delays.requests)
    }

    /**
     * iOS D45 — the fast-child stall, on the assembly engine.
     *
     * Only round 0 uses the delayed announce here (later rounds announce
     * immediately), so this is the whole window: a child who completes the first
     * row within 350 ms used to have the prompt cut their own success line
     * short, and the advance is gated on that line finishing.
     *
     * `holdSays()` is load-bearing — it keeps the model from advancing while the
     * timer fires, which is the only state where the announce would speak.
     */
    @Test
    fun `completing the first row cancels the pending announce`() {
        val h = EngineHarness()
        h.delays.holdDelays()
        h.audio.holdSays()
        val model = makeAssembly(listOf("r1", "r2"), listOf("BA", "TO"), h)

        model.activate()
        h.pump()
        assertEquals(1, h.delays.pendingCount)

        model.pick(1, "BA")
        assertEquals(Verdict.ACCEPT, model.pick(2, "TO")) // row complete, inside the window
        h.pump()
        assertEquals(1, h.audio.pendingSayCount, "the success line is in flight")

        h.delays.releaseNext()
        h.pump()
        assertFalse(
            h.audio.sayTexts.contains("P-r1"),
            "a completed row must not announce its own prompt",
        )
        h.settle()
    }

    @Test
    fun `round 0's pending announce is cancelled on deactivate`() {
        val h = EngineHarness()
        h.delays.holdDelays()
        val model = makeAssembly(listOf("r1"), listOf("BA"), h)

        model.activate()
        h.pump()
        model.deactivate()
        h.delays.releaseNext()
        h.pump()
        assertFalse(h.audio.sayTexts.contains("P-r1"))
    }

    @Test
    fun `replay and preview are both locked-guarded`() {
        val h = EngineHarness()
        val model = makeAssembly(listOf("r1"), listOf("BA", "TO"), h)

        model.replayPrompt()
        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say("P-r1", 0.94)))
        model.preview("BA")
        assertTrue(h.audio.events.contains(AudioEvent.Unlock), "a preview unlocks")
        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say("BA", 0.94)))

        // Wrong row → locked while « Oh non » plays: both go quiet.
        h.audio.holdSays()
        model.pick(1, "TO")
        model.pick(2, "BA")
        h.pump()
        h.audio.clearEvents()
        model.replayPrompt()
        model.preview("BA")
        h.pump()
        assertTrue(h.audio.events.isEmpty())
        h.settle()
    }
}

class AssemblyFactoryTest {

    @Test
    fun `an order-mode session has the tier's shape, and a careful run is all-perfect`() {
        val h = EngineHarness()
        h.award.result = 9
        val model = AssemblyModel.assemble(
            exercise = ExerciseId.ORDER_SYLLABLES,
            mode = SyllableMode.ORDER,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(42),
        )
        val tier = syllableTier(1)
        val picks = minOf(tier.pick, syllablePool(tier).size)
        assertEquals(picks + minOf(tier.repeats, picks), model.totalRounds)

        var safety = 0
        while (!model.done && safety < 200) {
            safety += 1
            val word = model.round.word
            assertEquals(
                word.syllables.size,
                model.round.tray.size,
                "order mode has no intruder",
            )
            for (syllable in word.syllables) {
                val tile = assertNotNull(
                    model.round.tray.firstOrNull {
                        it.syllable == syllable && !model.isTrayTileUsed(it.id)
                    },
                    "no free tray tile for $syllable",
                )
                assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.syllable))
            }
            h.pump()
        }

        assertTrue(model.done)
        assertEquals(9, model.earned)
        assertEquals(1, h.award.calls.size)
        assertEquals(model.totalRounds, h.award.calls[0].perfect, "a careful run is all-perfect")
        assertEquals(model.totalRounds, h.award.calls[0].total)
    }

    @Test
    fun `fill-blank keeps its revealed slots through a wrong row`() {
        val h = EngineHarness()
        val model = AssemblyModel.assemble(
            exercise = ExerciseId.FILL_BLANK,
            mode = SyllableMode.FILL_BLANK,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(5),
        )
        val round = model.round
        val seeded = round.slots
        val gap = round.slots.indexOfFirst { it == null }
        assertTrue(gap >= 0)
        val missing = round.word.syllables[gap]
        assertEquals(1, round.locked.count { !it }, "exactly one slot to fill")
        assertEquals(2, round.tray.size, "the missing syllable + one distractor")

        val wrongTile = assertNotNull(model.round.tray.firstOrNull { it.syllable != missing })
        model.pick(wrongTile.id, wrongTile.syllable)
        assertFalse(model.stars[0])
        h.pump()
        assertEquals(seeded, model.slots, "the wipe restores the SEEDED slots")

        val rightTile = assertNotNull(model.round.tray.firstOrNull { it.syllable == missing })
        model.pick(rightTile.id, rightTile.syllable)
        h.pump()
        assertTrue(model.idx == 1 || model.done)
    }

    @Test
    fun `spellSound judges the spelling in order`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSound(level = 1, deps = h.deps, rng = SeededGenerator(8))
        val cfg = soundLevel(1)

        assertEquals(SOUND_PICK + SOUND_REPEATS, model.totalRounds)
        val round = model.round
        assertEquals(round.target.spelling.size, round.slots.size)
        assertTrue(round.tray.size >= round.target.spelling.size)
        assertTrue(round.tray.size <= round.target.spelling.size + cfg.distractors)

        for (letter in round.target.spelling) {
            val tile = assertNotNull(
                model.round.tray.firstOrNull {
                    it.letter == letter && !model.isTrayTileUsed(it.id)
                },
            )
            assertEquals(Verdict.ACCEPT, model.pick(tile.id, tile.letter))
        }
        h.pump()
        assertTrue(model.idx == 1 || model.done)
        assertTrue(model.stars[0])
    }

    @Test
    fun `mixed spellSyllable judges the FACE, not just the letter`() {
        val h = EngineHarness()
        val mixed = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
            mode = SpellSyllableMode.LETTERS_EXTRA,
            level = 1,
            mixed = true,
            deps = h.deps,
            rng = SeededGenerator(3),
        )
        val word = SyllableWord(word = "BATEAU", syllables = listOf("BA", "TEAU"), emoji = "⛵")
        val cursiveA = LetterFace(base = "A", glyph = "a", script = LetterScript.CURSIVE)
        val printA = LetterFace(base = "A", glyph = "A", script = LetterScript.PRINT)
        val round = SpellSyllableRound(
            word = word,
            cells = emptyList(),
            answer = listOf("A"),
            answerFaces = listOf(cursiveA),
            tray = emptyList(),
        )

        assertTrue(mixed.descriptor.judge(round, listOf(cursiveA)), "the right face passes")
        assertFalse(
            mixed.descriptor.judge(round, listOf(printA)),
            "right letter, wrong writing — the row fails",
        )

        // Plain rounds are all-uppercase print, so face equality degrades to
        // letter equality on its own.
        val plain = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE,
            mode = SpellSyllableMode.LETTERS_EXACT,
            level = 1,
            mixed = false,
            deps = h.deps,
            rng = SeededGenerator(3),
        )
        val printB = LetterFace(base = "B", glyph = "B", script = LetterScript.PRINT)
        val plainRound = SpellSyllableRound(
            word = word,
            cells = emptyList(),
            answer = listOf("B"),
            answerFaces = listOf(printB),
            tray = emptyList(),
        )
        assertTrue(plain.descriptor.judge(plainRound, listOf(printB)))

        // sameFace ignores `base`: two faces are the same tile iff they RENDER
        // identically (the TSX comparison, not LetterFace's own ==).
        assertTrue(
            sameFace(
                LetterFace(base = "X", glyph = "A", script = LetterScript.PRINT),
                LetterFace(base = "A", glyph = "A", script = LetterScript.PRINT),
            ),
        )
        assertFalse(sameFace(cursiveA, printA))
    }

    @Test
    fun `a spellSyllable round matches the builder's shape`() {
        val h = EngineHarness()
        val model = AssemblyModel.spellSyllable(
            exercise = ExerciseId.SPELL_SYLLABLE,
            mode = SpellSyllableMode.LETTERS_EXACT,
            level = 1,
            mixed = false,
            deps = h.deps,
            rng = SeededGenerator(12),
        )
        val cfg = spellSyllableLevel(1)
        val picks = minOf(cfg.pick, spellSyllablePool(1).size)

        assertEquals(picks + minOf(cfg.repeats, picks), model.totalRounds)
        val round = model.round
        assertEquals(round.answerFaces.size, model.slots.size)
        assertTrue(model.slots.all { it == null }, "SpellSyllable seeds all-empty slots")
        assertEquals(round.answer.size, round.tray.size, "letters-exact has no intruder")

        // `spellTileFace` is the value a screen feeds `pick()`. Every answer
        // face must be reachable from some tray tile through it, or the row
        // could not be completed at all.
        val trayFaces = round.tray.map { spellTileFace(it) }
        assertEquals(round.tray.map { it.glyph }, trayFaces.map { it.glyph })
        assertTrue(round.answerFaces.all { answer -> trayFaces.any { sameFace(it, answer) } })
    }

    @Test
    fun `an assemble session is reproducible from its seed`() {
        val h = EngineHarness()
        val a = AssemblyModel.assemble(
            exercise = ExerciseId.ORDER_SYLLABLES,
            mode = SyllableMode.ORDER,
            level = 2,
            deps = h.deps,
            rng = SeededGenerator(77),
        )
        val b = AssemblyModel.assemble(
            exercise = ExerciseId.ORDER_SYLLABLES,
            mode = SyllableMode.ORDER,
            level = 2,
            deps = h.deps,
            rng = SeededGenerator(77),
        )
        assertEquals(a.session, b.session)
        assertEquals(a.round.word, b.round.word)
        assertEquals(a.round.tray.map { it.syllable }, b.round.tray.map { it.syllable })
    }
}
