package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.FIRST_LETTER_LEVELS
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.levels.findSoundLevel
import fr.dappit.attrapelettres.core.levels.findSoundPool
import fr.dappit.attrapelettres.core.levels.syllableGridLevel
import fr.dappit.attrapelettres.core.levels.syllableGridPool
import fr.dappit.attrapelettres.core.rewards.rewardFor
import fr.dappit.attrapelettres.core.rewards.sessionReward
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The single-pick state machine, asserted against the TSX: FirstLetterExercise
// is the reference for the loop; FindSound / Grid / LetterMatch / ReadImage
// differ only through the descriptor.

/** A fully scripted descriptor: `Round == String`, so a round IS its key. */
private fun makeModel(
    rounds: List<String>,
    h: EngineHarness,
    previewGuarded: Boolean = false,
): SinglePickModel<String> = SinglePickModel(
    descriptor = SinglePickDescriptor(
        exercise = ExerciseId.FIND_SOUND,
        level = 1,
        headline = null,
        finishedTitle = "T",
        listenAccessibilityLabel = "L",
        previewGuardedByLock = previewGuarded,
        buildSession = { rounds },
        targetKey = { it },
        promptLine = { "P-$it" },
        successLine = { "S-$it" },
        listenText = { "🔊" },
    ),
    deps = h.deps,
)

class SinglePickHandlerTest {

    @Test
    fun `a wrong pick nudges and greys the star, synchronously`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)

        assertEquals(Verdict.REJECT, model.pick("X"))
        // Exact audio order: the tap pops first, then the nudge marks the miss.
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Nudge),
            h.audio.events,
        )
        // No coroutine has run yet — everything above happened inside `pick`.
        assertEquals(listOf(false, true), model.stars.toList(), "the star greys NOW")
        assertEquals(Mood.IDLE, model.mood, "a miss never changes the mascot")
        assertNull(model.flash)
        assertFalse(model.done)
        assertEquals(0, model.idx)
        assertEquals(0, h.confetti.count)
    }

    /** Invariant 3, stated as bluntly as it deserves. */
    @Test
    fun `a miss locks nothing, loses nothing and changes no route`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)

        model.pick("X")
        h.pump()
        assertFalse(model.tilesDisabled, "nothing is disabled by a wrong answer")
        assertEquals(0, model.idx, "no round is failed and none is skipped")
        assertFalse(model.done, "there is no terminal state but finishing")
        assertEquals(Mood.IDLE, model.mood)

        // …and the round is still fully winnable once the shake is over.
        h.time.advance(800L)
        assertEquals(Verdict.ACCEPT, model.pick("A"))
        h.pump()
        assertEquals(1, model.idx)
        assertEquals(listOf(false, true), model.stars.toList())
    }

    @Test
    fun `the cooldown swallows silently for exactly 800 ms`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        model.pick("X")
        h.audio.clearEvents()

        // Inside the window: REJECT, zero audio, zero state change.
        assertEquals(Verdict.REJECT, model.pick("A"))
        assertTrue(h.audio.events.isEmpty(), "swallowed picks make no sound at all")
        assertEquals(listOf(false, true), model.stars.toList())

        // 799 ms later: still swallowed (strict `<` on MISS_COOLDOWN_MS = 800).
        h.time.advance(799L)
        assertEquals(Verdict.REJECT, model.pick("A"))
        assertTrue(h.audio.events.isEmpty())

        // At exactly +800 ms the window is over.
        h.time.advance(1L)
        assertEquals(Verdict.ACCEPT, model.pick("A"))
        assertEquals(AudioEvent.Unlock, h.audio.events.first())
    }

    @Test
    fun `a second miss in the same round greys nothing further but re-arms the cooldown`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        model.pick("X")
        h.time.advance(800L)

        assertEquals(Verdict.REJECT, model.pick("Y"))
        assertEquals(
            listOf(false, true),
            model.stars.toList(),
            "greying a round's star is idempotent — spam cannot compound a paid penalty",
        )
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick("A"))
        assertTrue(h.audio.events.isEmpty(), "a fresh cooldown was still armed")
    }

    @Test
    fun `a right pick celebrates at pointer-down`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        h.audio.holdSays()

        assertEquals(Verdict.ACCEPT, model.pick("A"))
        // The synchronous beat, asserted before a single coroutine has run.
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Success),
            h.audio.events,
        )
        assertEquals(1, h.confetti.count)
        assertEquals("A", model.flash)
        assertTrue(model.tilesDisabled, "disabled={flash != null}")
        assertEquals(Mood.HAPPY, model.mood)
        assertEquals(0, model.idx, "the advance waits for the line")

        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say("S-A", EngineLines.SUCCESS_RATE)))
        assertEquals(1, h.audio.pendingSayCount)
        h.audio.resolveNextSay(true)
        h.pump()
        assertEquals(1, model.idx)
    }

    @Test
    fun `the advance is gated on the success line's result`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        h.audio.queueSayResult(false) // the line was superseded / cut short
        model.pick("A")
        h.pump()

        assertTrue(h.audio.events.contains(AudioEvent.Say("S-A", EngineLines.SUCCESS_RATE)))
        assertEquals(0, model.idx, "a cut line never advances the round")
        assertEquals("A", model.flash, "flash is not cleared on a cut line")
        // Still locked: a further pick is silently rejected.
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick("B"))
        assertTrue(h.audio.events.isEmpty())
    }

    @Test
    fun `a completed line resets flash, lock and mood`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        model.pick("A")
        h.pump()

        assertEquals(1, model.idx)
        assertNull(model.flash)
        assertEquals(Mood.IDLE, model.mood)
        assertFalse(model.tilesDisabled)
        assertEquals(listOf(true, true), model.stars.toList())
        assertEquals(Verdict.ACCEPT, model.pick("B"))
    }

    @Test
    fun `a pick while the celebration plays is silently rejected`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)
        h.audio.holdSays()
        model.pick("A")
        h.pump()
        h.audio.clearEvents()

        assertEquals(Verdict.REJECT, model.pick("A"))
        assertTrue(h.audio.events.isEmpty())
        h.settle()
    }

    @Test
    fun `finishing awards once and speaks the bravo line`() {
        val h = EngineHarness()
        h.award.result = 13
        val model = makeModel(listOf("A", "B"), h)

        model.pick("X") // grey round 0's star
        h.time.advance(800L)
        model.pick("A")
        h.pump()
        model.pick("B")
        h.pump()

        assertTrue(model.done)
        assertEquals(Mood.CHEER, model.mood)
        assertEquals(13, model.earned, "earned is award's return, untouched")
        assertEquals(1, h.award.calls.size)
        assertEquals(ExerciseId.FIND_SOUND, h.award.calls[0].exercise)
        assertEquals(1, h.award.calls[0].level)
        assertEquals(1, h.award.calls[0].perfect, "only rounds with zero misses count")
        assertEquals(2, h.award.calls[0].total)
        assertTrue(h.audio.sayTexts.contains(EngineLines.BRAVO_FOUND))
        assertEquals(2, model.progressDone, "GameFrame's done input caps at total")
    }

    @Test
    fun `a deactivated model never advances and never awards`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A"), h)
        h.audio.holdSays()
        model.pick("A")
        h.pump()

        model.deactivate()
        h.audio.resolveNextSay(true) // the line completes AFTER the screen is gone
        h.pump()

        assertFalse(model.done)
        assertTrue(h.award.calls.isEmpty(), "a dead engine never awards")
        assertTrue(h.audio.events.contains(AudioEvent.Stop), "leaving fades the current line")
    }

    /**
     * Invariant 8 through the real economy: `award` wired to `sessionReward`
     * with the exercise's authored difficulty. A spam run pays the bare
     * completion curve; a careful run pays the curve plus the whole weight.
     */
    @Test
    fun `spam earns the bare curve and careful play earns the bonus`() {
        val difficulty = exerciseDifficulty(ExerciseId.FIND_SOUND)
        assertTrue(difficulty.weight > 0, "findSound must pay for this test to bite")

        val spam = runEconomySession(missEveryRound = true, difficulty = difficulty)
        val careful = runEconomySession(missEveryRound = false, difficulty = difficulty)

        assertEquals(rewardFor(0), spam, "spam pays the bare curve")
        assertEquals(
            rewardFor(0) + difficulty.weight,
            careful,
            "a full-perfect run adds exactly the difficulty weight",
        )
        assertTrue(careful > spam)
    }
}

/**
 * One full three-round run with the award closure wired to the REAL
 * `sessionReward`. Returns what the model displayed as earned.
 */
private fun runEconomySession(missEveryRound: Boolean, difficulty: Difficulty): Int {
    val h = EngineHarness()
    var earnedViaReward = 0
    val model = SinglePickModel(
        descriptor = SinglePickDescriptor(
            exercise = ExerciseId.FIND_SOUND,
            level = 1,
            headline = null,
            finishedTitle = "T",
            listenAccessibilityLabel = "L",
            previewGuardedByLock = false,
            buildSession = { listOf("A", "B", "C") },
            targetKey = { it },
            promptLine = { "P-$it" },
            successLine = { "S-$it" },
            listenText = { "🔊" },
        ),
        deps = h.deps.copy(
            award = { exercise, _, perfect, total ->
                assertEquals(ExerciseId.FIND_SOUND, exercise)
                earnedViaReward = sessionReward(
                    difficulty = difficulty,
                    priorClears = 0,
                    perfectRounds = perfect,
                    totalRounds = total,
                )
                earnedViaReward
            },
        ),
    )

    for (key in listOf("A", "B", "C")) {
        if (missEveryRound) {
            model.pick("WRONG")
            h.time.advance(800L)
        }
        model.pick(key)
        h.pump()
    }
    assertTrue(model.done)
    assertEquals(earnedViaReward, model.earned, "the model never adds to award's return")
    return model.earned
}

class SinglePickAudioTest {

    @Test
    fun `every round announces 350 ms after it becomes current`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)

        model.activate()
        assertEquals(AudioEvent.Unlock, h.audio.events.first(), "unlock on appear")
        h.pump()
        assertTrue(h.audio.sayTexts.contains("P-A"))
        assertEquals(listOf(EngineLines.ANNOUNCE_DELAY_MS), h.delays.requests)

        model.pick("A")
        h.pump()
        assertEquals(1, model.idx)
        assertTrue(h.audio.sayTexts.contains("P-B"))
        assertEquals(
            listOf(EngineLines.ANNOUNCE_DELAY_MS, EngineLines.ANNOUNCE_DELAY_MS),
            h.delays.requests,
            "every advance re-announces after 350 ms",
        )
    }

    /**
     * iOS D45 — the fast-child stall, and the one authorised deviation from the
     * TSX in this file.
     *
     * A correct tap inside the 350 ms announce window used to let the prompt
     * speak over the success line. The real `say` returns false when it is cut
     * short, the advance is gated on that, and the round stranded with `locked`
     * still true — no error, no feedback, and only for children quick enough to
     * answer in a third of a second.
     *
     * `holdSays()` is what makes this test mean anything: it keeps the success
     * line open, so `idx` has NOT advanced when the timer fires — the only
     * state in which the announce's own `idx == expected` guard would let it
     * through. Without it the guard masks the bug. Verified by mutation: remove
     * `announceJob?.cancel()` from `pick` and this fails.
     */
    @Test
    fun `a correct pick cancels the pending announce`() {
        val h = EngineHarness()
        h.delays.holdDelays()
        h.audio.holdSays()
        val model = makeModel(listOf("A", "B"), h)

        model.activate()
        h.pump()
        assertEquals(1, h.delays.pendingCount)

        model.pick("A") // answered inside the window
        h.pump()
        assertEquals(1, h.audio.pendingSayCount, "the success line is in flight")
        assertEquals(0, model.idx, "…so idx is pinned, and the announce guard cannot help")

        h.delays.releaseNext() // …and the timer fires right after
        h.pump()
        assertFalse(
            h.audio.sayTexts.contains("P-A"),
            "an answered round must not announce its own prompt",
        )
        h.settle()
    }

    @Test
    fun `a pending announce is cancelled on deactivate`() {
        val h = EngineHarness()
        h.delays.holdDelays()
        val model = makeModel(listOf("A", "B"), h)

        model.activate()
        h.pump()
        assertEquals(1, h.delays.pendingCount)
        model.deactivate()
        h.delays.releaseNext()
        h.pump()

        assertFalse(h.audio.sayTexts.contains("P-A"), "no stale prompt after unmount")
    }

    /**
     * Two guards stand between a stale timer and the child's ears — the cancel
     * on the correct pick, and the `idx == expected` check when it fires
     * anyway. This asserts the property both exist for: round 0's prompt is
     * never spoken over round 1.
     */
    @Test
    fun `a stale announce never speaks over the next round`() {
        val h = EngineHarness()
        h.delays.holdDelays()
        val model = makeModel(listOf("A", "B"), h)

        model.activate()
        h.pump()
        model.pick("A") // advance while round 0's announce is still pending
        h.pump()
        assertEquals(1, model.idx)

        h.delays.releaseAll() // both timers fire, round 0's included
        h.pump()
        assertFalse(h.audio.sayTexts.contains("P-A"))
        assertTrue(h.audio.sayTexts.contains("P-B"))
    }

    @Test
    fun `no announce is scheduled after the run finishes`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A"), h)

        model.activate()
        h.pump()
        assertTrue(h.audio.sayTexts.contains("P-A"))
        model.pick("A")
        h.pump()

        assertTrue(model.done)
        assertEquals(
            listOf(EngineLines.ANNOUNCE_DELAY_MS),
            h.delays.requests,
            "done schedules no further announce",
        )
    }

    @Test
    fun `replayPrompt is locked-guarded and speaks the prompt line`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A", "B"), h)

        model.replayPrompt()
        h.pump()
        // 0.94 — the default rate, not the success rate.
        assertTrue(h.audio.events.contains(AudioEvent.Say("P-A", 0.94)))

        h.audio.holdSays()
        model.pick("A")
        h.pump()
        h.audio.clearEvents()
        model.replayPrompt()
        h.pump()
        assertTrue(h.audio.events.isEmpty(), "never cut the success line mid-celebration")
        h.settle()
    }

    @Test
    fun `the preview lock asymmetry is ported exactly`() {
        // Four single-pick engines preview WITHOUT a locked guard…
        val unguardedHarness = EngineHarness()
        val unguarded = makeModel(listOf("A"), unguardedHarness, previewGuarded = false)
        unguardedHarness.audio.holdSays()
        unguarded.pick("A")
        unguardedHarness.pump()
        unguardedHarness.audio.clearEvents()
        unguarded.preview("a")
        assertEquals(
            listOf(AudioEvent.Unlock),
            unguardedHarness.audio.events,
            "an unguarded preview still unlocks while the celebration plays",
        )
        unguardedHarness.pump()
        assertTrue(unguardedHarness.audio.sayTexts.contains("a"))
        unguardedHarness.settle()

        // …ReadImage's IS guarded.
        val guardedHarness = EngineHarness()
        val guarded = makeModel(listOf("A"), guardedHarness, previewGuarded = true)
        guardedHarness.audio.holdSays()
        guarded.pick("A")
        guardedHarness.pump()
        guardedHarness.audio.clearEvents()
        guarded.preview("a")
        guardedHarness.pump()
        assertTrue(guardedHarness.audio.events.isEmpty())
        guardedHarness.settle()
    }

    @Test
    fun `a preview unlocks and speaks at the default rate`() {
        val h = EngineHarness()
        val model = makeModel(listOf("A"), h)

        model.preview("ou")
        assertEquals(AudioEvent.Unlock, h.audio.events.first())
        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say("ou", 0.94)))
    }
}

class SinglePickFactoryTest {

    @Test
    fun `firstLetter rounds have three choices including the target`() {
        val h = EngineHarness()
        val model = SinglePickModel.firstLetter(level = 1, deps = h.deps, rng = SeededGenerator(7))

        assertTrue(model.session.isNotEmpty())
        for (round in model.session) {
            assertEquals(3, round.choices.size, "target + exactly 2 distractors")
            assertTrue(round.choices.contains(round.target.letter))
            assertEquals(3, round.choices.toSet().size, "distractors differ from the target")
        }
        val cfg = FIRST_LETTER_LEVELS[0]
        assertEquals(cfg.pick + cfg.repeats, model.totalRounds)
    }

    @Test
    fun `a firstLetter session is reproducible from its seed`() {
        val h = EngineHarness()
        val a = SinglePickModel.firstLetter(level = 2, deps = h.deps, rng = SeededGenerator(99))
        val b = SinglePickModel.firstLetter(level = 2, deps = h.deps, rng = SeededGenerator(99))
        assertEquals(a.session, b.session)

        val c = SinglePickModel.firstLetter(level = 2, deps = h.deps, rng = SeededGenerator(100))
        assertNotEquals(a.session, c.session, "a different seed reshuffles")
    }

    @Test
    fun `firstLetter drops the written word on the last two levels`() {
        val h = EngineHarness()
        assertEquals(5, FIRST_LETTER_LEVELS.size)

        val early = SinglePickModel.firstLetter(level = 1, deps = h.deps, rng = SeededGenerator(1))
        assertEquals("🔊 ${early.current.target.word}", early.listenText)

        val fourth = SinglePickModel.firstLetter(level = 4, deps = h.deps, rng = SeededGenerator(1))
        assertEquals("🔊", fourth.listenText, "levels 4-5 are sound-only")

        val last = SinglePickModel.firstLetter(level = 5, deps = h.deps, rng = SeededGenerator(1))
        assertEquals("🔊", last.listenText)
    }

    @Test
    fun `findSound's run shape matches its level config`() {
        val h = EngineHarness()
        val model = SinglePickModel.findSound(level = 1, deps = h.deps, rng = SeededGenerator(3))
        val cfg = findSoundLevel(1)
        val pool = findSoundPool(1)
        val picks = minOf(cfg.pick, pool.size)

        assertEquals(picks + minOf(cfg.repeats, picks), model.totalRounds)
        for (round in model.session) {
            assertEquals(1 + cfg.distractors, round.choices.size)
            assertTrue(round.choices.contains(round.target))
        }
        for (i in 1 until model.session.size) {
            assertNotEquals(
                model.session[i - 1].target,
                model.session[i].target,
                "never the same target two rounds in a row",
            )
        }
    }

    @Test
    fun `a syllable-grid session never repeats back to back and replays from its seed`() {
        val h = EngineHarness()
        val model = SinglePickModel.syllableGrid(
            exercise = ExerciseId.HEAR_SYLLABLE,
            mode = SyllableGridMode.HEAR,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(11),
        )
        val cfg = syllableGridLevel(1)
        val picks = minOf(cfg.pick, syllableGridPool(1).size)
        assertEquals(picks + minOf(cfg.repeats, picks), model.totalRounds)
        for (i in 1 until model.session.size) {
            assertNotEquals(model.session[i - 1].target, model.session[i].target)
        }

        val again = SinglePickModel.syllableGrid(
            exercise = ExerciseId.HEAR_SYLLABLE,
            mode = SyllableGridMode.HEAR,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(11),
        )
        assertEquals(model.session, again.session)
    }

    @Test
    fun `the grid picks by the whole syllable text in BOTH modes`() {
        val h = EngineHarness()
        val model = SinglePickModel.syllableGrid(
            exercise = ExerciseId.PICK_VOWEL,
            mode = SyllableGridMode.VOWEL,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(5),
        )
        val target = model.current.target
        val wrong = model.current.choices.firstOrNull { it.text != target.text }
        if (wrong != null) {
            assertEquals(Verdict.REJECT, model.pick(wrong.text))
            h.time.advance(800L)
        }
        assertEquals(Verdict.ACCEPT, model.pick(target.text))
        assertEquals(target.text, model.flash, "flash keys on choice.text, vowel mode included")
    }

    @Test
    fun `letterMatch judges the base letter, not the form`() {
        val h = EngineHarness()
        val model = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_CASE,
            kind = LetterMatchKind.CASE,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(21),
        )
        val round = model.current
        val correct = assertNotNull(round.choices.firstOrNull { it.base == round.prompt.base })
        val wrong = round.choices.firstOrNull { it.base != round.prompt.base }
        if (wrong != null) {
            assertEquals(Verdict.REJECT, model.pick(wrong.base))
            h.time.advance(800L)
        }
        assertEquals(Verdict.ACCEPT, model.pick(correct.base))
        assertEquals(round.prompt.base, model.flash)
        h.pump()
        assertTrue(h.audio.sayTexts.contains("Oui ! ${round.prompt.base}."))
    }

    @Test
    fun `readImage judges by word and never speaks the word itself`() {
        val h = EngineHarness()
        val model = SinglePickModel.readImage(level = 1, deps = h.deps, rng = SeededGenerator(17))
        val round = model.current

        assertEquals(READ_IMAGE_PROMPT, model.promptText)
        val wrong = round.choices.firstOrNull { it.word != round.target.word }
        if (wrong != null) {
            assertEquals(Verdict.REJECT, model.pick(wrong.word))
            h.time.advance(800L)
        }
        assertEquals(Verdict.ACCEPT, model.pick(round.target.word))
        h.pump()
        assertTrue(h.audio.sayTexts.contains("Oui ! ${round.target.word}."))
        assertFalse(
            h.audio.sayTexts.contains(round.target.word),
            "the bare word is never spoken — reading it IS the task",
        )
    }
}
