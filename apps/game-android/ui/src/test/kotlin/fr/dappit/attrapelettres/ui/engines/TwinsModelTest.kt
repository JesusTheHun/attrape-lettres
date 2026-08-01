package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.TwinRound
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.twinLevel
import fr.dappit.attrapelettres.core.levels.twinPool
import fr.dappit.attrapelettres.core.levels.twinPrompt
import fr.dappit.attrapelettres.core.support.SeededGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The multi-select twins machine, asserted against SoundTwinsExercise.tsx.
// Sessions come from the real builder under a seed.

private fun makeTwins(seed: Long = 42, level: Int = 1): Pair<EngineHarness, TwinsModel> {
    val h = EngineHarness()
    return h to TwinsModel(level = level, deps = h.deps, rng = SeededGenerator(seed))
}

class TwinsModelTest {

    @Test
    fun `the session shape matches the level config, and replays from its seed`() {
        val (_, model) = makeTwins()
        val cfg = twinLevel(1)
        val picks = minOf(cfg.pick, twinPool(1).size)

        assertEquals(picks + minOf(cfg.repeats, picks), model.totalRounds)
        for (round in model.session) {
            val correct = round.tiles.filter { it.correct }
            assertTrue(correct.isNotEmpty(), "every round has twins to find")
            assertTrue(round.tiles.size > correct.size, "and intruders to avoid")
        }

        // Reproducible from the seed — modulo tile ids, which come from the
        // GLOBAL monotonic TileIdAllocator and never reset.
        fun shape(session: List<TwinRound>): List<List<String>> = session.map { round ->
            listOf(round.family.sound) + round.tiles.map { "${it.text}:${it.correct}" }
        }
        val (_, again) = makeTwins()
        assertEquals(shape(model.session), shape(again.session))
    }

    @Test
    fun `a partial correct tap locks its tile and speaks its anchor, unawaited`() {
        val (h, model) = makeTwins()
        val targets = model.targets
        assertTrue(targets.size >= 2, "this seed must give a multi-twin round")
        val first = targets[0]

        h.audio.holdSays() // even with its line UNRESOLVED the round keeps running
        assertEquals(Verdict.ACCEPT, model.pick(first))
        assertTrue(h.audio.events.contains(AudioEvent.Pop))
        assertFalse(h.audio.events.contains(AudioEvent.Success), "no chime on a partial find")
        assertEquals(0, h.confetti.count)
        assertEquals(listOf(first.id), model.found)
        assertEquals(Mood.HAPPY, model.mood)
        assertTrue(model.stars.all { it }, "partial finds never grey the star")
        assertTrue(model.tileDisabled(first), "a found tile locks")
        assertTrue(model.tileHighlighted(first))
        assertEquals(first, model.foundTile(0))
        assertNull(model.foundTile(1))

        h.pump()
        assertEquals(listOf("Oui ! ${first.word}."), h.audio.pendingSayTexts)

        // NOT locked: the next tap can land while the line plays.
        val second = targets[1]
        assertEquals(Verdict.ACCEPT, model.pick(second))
        assertEquals(listOf(first.id, second.id), model.found)
        h.settle()
    }

    @Test
    fun `an intruder tap follows the single-pick miss rules`() {
        val (h, model) = makeTwins()
        val intruder = assertNotNull(
            model.round.tiles.firstOrNull { !it.correct },
            "the seeded round has no intruder",
        )

        assertEquals(Verdict.REJECT, model.pick(intruder))
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Nudge),
            h.audio.events,
        )
        assertFalse(model.stars[0], "only intruder taps grey the star")
        assertTrue(model.found.isEmpty())
        assertEquals(Mood.IDLE, model.mood)

        // Cooldown: the next pick is silently swallowed, even a correct one.
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick(model.targets[0]))
        assertTrue(h.audio.events.isEmpty())
        h.time.advance(800L)
        assertEquals(Verdict.ACCEPT, model.pick(model.targets[0]))
    }

    @Test
    fun `the completing tap locks, celebrates and advances after its line`() {
        val (h, model) = makeTwins()
        val targets = model.targets
        for (tile in targets.dropLast(1)) {
            model.pick(tile)
        }
        h.pump()
        assertEquals(0, h.confetti.count)

        h.audio.clearEvents()
        h.audio.holdSays()
        val last = targets.last()
        assertEquals(Verdict.ACCEPT, model.pick(last))
        assertEquals(
            listOf(AudioEvent.Unlock, AudioEvent.Pop, AudioEvent.Success),
            h.audio.events,
        )
        assertEquals(1, h.confetti.count)
        assertTrue(model.complete)
        for (tile in model.round.tiles) {
            assertTrue(model.tileDisabled(tile), "a complete round locks every tile")
        }

        // Locked while the line plays: a pick is silent.
        h.audio.clearEvents()
        assertEquals(Verdict.REJECT, model.pick(last))
        assertTrue(h.audio.events.isEmpty())

        h.pump()
        assertEquals(1, h.audio.pendingSayCount)
        h.audio.resolveNextSay(true)
        h.pump()
        assertEquals(1, model.idx)
        assertTrue(model.found.isEmpty())
        assertEquals(Mood.IDLE, model.mood)
        h.settle()
    }

    @Test
    fun `a completing line cut short never advances`() {
        val (h, model) = makeTwins()
        val targets = model.targets
        repeat(targets.size) { h.audio.queueSayResult(false) }
        for (tile in targets) {
            model.pick(tile)
        }
        h.pump()

        assertEquals(0, model.idx)
        assertFalse(model.done)
    }

    @Test
    fun `a full run awards once and speaks the bravo`() {
        val (h, model) = makeTwins()
        h.award.result = 21
        val intruder = assertNotNull(model.round.tiles.firstOrNull { !it.correct })
        model.pick(intruder) // one miss, round 0
        h.time.advance(800L)

        var safety = 0
        while (!model.done && safety < 100) {
            safety += 1
            for (tile in model.targets) {
                model.pick(tile)
            }
            h.pump()
        }

        assertTrue(model.done)
        assertEquals(Mood.CHEER, model.mood)
        assertEquals(21, model.earned)
        assertEquals(1, h.award.calls.size)
        assertEquals(ExerciseId.SOUND_TWINS, h.award.calls[0].exercise)
        assertEquals(1, h.award.calls[0].level)
        assertEquals(
            model.totalRounds - 1,
            h.award.calls[0].perfect,
            "the one missed round is not perfect",
        )
        assertEquals(model.totalRounds, h.award.calls[0].total)
        assertTrue(h.audio.sayTexts.contains(EngineLines.BRAVO_FOUND))
    }

    /**
     * iOS D45 — the fast-child stall, on the twins engine. A child who finds
     * every twin inside the 350 ms window used to have « Trouve tous les … »
     * speak over the last success line, and the advance is gated on that line.
     */
    @Test
    fun `completing the round cancels the pending announce`() {
        val (h, model) = makeTwins()
        h.delays.holdDelays()
        h.audio.holdSays()
        model.activate()
        h.pump()
        assertEquals(1, h.delays.pendingCount)

        val prompt = twinPrompt(model.round.family)
        for (tile in model.targets) {
            model.pick(tile)
        }
        h.pump()

        h.delays.releaseNext() // the timer fires just after the last twin
        h.pump()
        assertFalse(
            h.audio.sayTexts.contains(prompt),
            "a completed round must not announce its own prompt",
        )
        h.settle()
    }

    @Test
    fun `the announce speaks the twin prompt after 350 ms`() {
        val (h, model) = makeTwins()
        model.activate()
        h.pump()

        val expected = twinPrompt(model.round.family)
        assertTrue(h.audio.sayTexts.contains(expected))
        assertEquals(listOf(EngineLines.ANNOUNCE_DELAY_MS), h.delays.requests)
        assertEquals("Trouve tous les ${model.round.family.sound} !", expected)
    }

    @Test
    fun `replay and preview are both locked-guarded`() {
        val (h, model) = makeTwins()
        model.replayPrompt()
        h.pump()
        assertTrue(h.audio.sayTexts.contains(twinPrompt(model.round.family)))

        model.preview(model.round.tiles[0].sound)
        h.pump()
        assertTrue(h.audio.events.contains(AudioEvent.Say(model.round.tiles[0].sound, 0.94)))

        // Complete the round with the line held → locked → both go quiet.
        h.audio.holdSays()
        for (tile in model.targets) {
            model.pick(tile)
        }
        h.pump()
        h.audio.clearEvents()
        model.replayPrompt()
        model.preview("x")
        h.pump()
        assertTrue(h.audio.events.isEmpty())
        h.settle()
    }
}
