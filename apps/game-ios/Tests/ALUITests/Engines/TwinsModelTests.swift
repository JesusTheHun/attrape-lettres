import Testing

import ALCore
@testable import ALUI

// The multi-select twins machine (engines.md §4.3), asserted against
// SoundTwinsExercise.tsx. Sessions come from the real builder under a seed.

@Suite("TwinsModel")
@MainActor
struct TwinsModelTests {

    private func makeModel(seed: UInt64 = 42, level: Int = 1) -> (EngineHarness, TwinsModel) {
        let h = EngineHarness()
        let model = TwinsModel(level: level, deps: h.deps, rng: .seeded(seed))
        return (h, model)
    }

    @Test func sessionShapeMatchesTheLevelConfigAndSeed() {
        let (_, model) = makeModel()
        let cfg = Levels.twinLevel(1)
        let pool = Levels.twinPool(1)
        let p = min(cfg.pick, pool.count)
        #expect(model.totalRounds == p + min(cfg.repeats, p))
        for round in model.session {
            let correct = round.tiles.filter(\.correct)
            #expect(!correct.isEmpty, Comment(rawValue: "every round has twins to find"))
            #expect(round.tiles.count > correct.count, Comment(rawValue: "and intruders to avoid"))
        }
        // Reproducible from the seed — modulo tile ids, which come from the
        // GLOBAL monotonic TileIDAllocator and never reset (engines.md §7.10).
        let (_, again) = makeModel()
        func shape(_ s: [TwinRound]) -> [[String]] {
            s.map { round in [round.family.sound] + round.tiles.map { "\($0.text):\($0.correct)" } }
        }
        #expect(shape(model.session) == shape(again.session))
    }

    @Test func partialCorrectTapLocksTileAndSpeaksItsAnchorUnawaited() async throws {
        let (h, model) = makeModel()
        let targets = model.targets
        try #require(targets.count >= 2, Comment(rawValue: "need a multi-twin round"))
        let first = targets[0]
        h.audio.holdSays()  // even with its line UNRESOLVED, the round keeps running
        #expect(model.pick(first) == .accept)
        #expect(h.audio.events.contains(.pop))
        #expect(!h.audio.events.contains(.success), Comment(rawValue: "no chime on a partial find"))
        #expect(h.confetti.count == 0)
        #expect(model.found == [first.id])
        #expect(model.mood == .happy)
        #expect(model.stars == model.session.map { _ in true }, Comment(rawValue: "partial finds never grey the star"))
        #expect(model.tileDisabled(first), Comment(rawValue: "a found tile locks"))
        #expect(model.foundTile(at: 0) == first)
        #expect(model.foundTile(at: 1) == nil)
        await eventually { h.audio.pendingSayCount == 1 }
        #expect(h.audio.pendingSayTexts == ["Oui ! \(first.word)."])
        // NOT locked: the next tap can land while the line plays.
        let second = targets[1]
        #expect(model.pick(second) == .accept)
        #expect(model.found == [first.id, second.id])
        await drainSays(h.audio)
    }

    @Test func intruderTapFollowsTheMissRules() {
        let (h, model) = makeModel()
        let intruder = model.round.tiles.first { !$0.correct }
        guard let intruder else {
            Issue.record("seeded round has no intruder")
            return
        }
        #expect(model.pick(intruder) == .reject)
        #expect(h.audio.events == [.unlock, .pop, .nudge])
        #expect(model.stars[0] == false, Comment(rawValue: "only intruder taps grey the star"))
        #expect(model.found.isEmpty)
        #expect(model.mood == .idle)
        // Cooldown: the next pick is silently swallowed, even a correct one.
        h.audio.clearEvents()
        #expect(model.pick(model.targets[0]) == .reject)
        #expect(h.audio.events.isEmpty)
        h.time.advance(millis: 800)
        #expect(model.pick(model.targets[0]) == .accept)
    }

    @Test func completingTapLocksCelebratesAndAdvancesAfterTheLine() async {
        let (h, model) = makeModel()
        let targets = model.targets
        for tile in targets.dropLast() {
            _ = model.pick(tile)
        }
        #expect(h.confetti.count == 0)
        // Let every partial line finish before holding, so the ONE held say
        // below is the completing line, deterministically.
        await eventually {
            h.audio.sayTexts.filter { $0.hasPrefix("Oui !") }.count == targets.count - 1
        }
        h.audio.clearEvents()
        h.audio.holdSays()
        let last = targets.last!
        #expect(model.pick(last) == .accept)
        #expect(h.audio.events == [.unlock, .pop, .success])
        #expect(h.confetti.count == 1)
        #expect(model.complete)
        for tile in model.round.tiles {
            #expect(model.tileDisabled(tile), Comment(rawValue: "a complete round locks every tile"))
        }
        // Locked while the line plays: a pick is silent.
        h.audio.clearEvents()
        #expect(model.pick(last) == .reject)
        #expect(h.audio.events.isEmpty)
        // The line completes → next round, strip cleared.
        await eventually { h.audio.pendingSayCount == 1 }
        h.audio.resolveNextSay(true)
        await eventually { model.idx == 1 }
        #expect(model.found.isEmpty)
        #expect(model.mood == .idle)
        await drainSays(h.audio)
    }

    @Test func completingLineCutShortNeverAdvances() async {
        let (h, model) = makeModel()
        h.audio.queueSayResult(false)
        for tile in model.targets {
            h.audio.queueSayResult(false)
            _ = model.pick(tile)
        }
        await Task.yield()
        await Task.yield()
        await Task.yield()
        #expect(model.idx == 0)
        #expect(model.done == false)
    }

    @Test func fullRunAwardsOnceAndSpeaksTheBravo() async {
        let (h, model) = makeModel()
        h.award.result = 21
        _ = model.pick(model.round.tiles.first { !$0.correct }!)  // one miss, round 0
        h.time.advance(millis: 800)
        var safety = 0
        while !model.done && safety < 100 {
            safety += 1
            let expected = model.idx
            for tile in model.targets {
                _ = model.pick(tile)
            }
            await eventually { model.idx == expected + 1 || model.done }
        }
        #expect(model.done)
        #expect(model.mood == .cheer)
        #expect(model.earned == 21)
        #expect(h.award.calls.count == 1)
        #expect(h.award.calls[0].exercise == .soundTwins)
        #expect(h.award.calls[0].level == 1)
        #expect(h.award.calls[0].perfect == model.totalRounds - 1, Comment(rawValue: "the one missed round is not perfect"))
        #expect(h.award.calls[0].total == model.totalRounds)
        await eventually { h.audio.sayTexts.contains("Bravo ! Tu as tout trouvé !") }
        #expect(h.audio.sayTexts.contains("Bravo ! Tu as tout trouvé !"))
    }

    /// D45 — the fast-child stall, on the twins engine.
    ///
    /// A child who finds every twin inside the 350 ms window used to have
    /// « Trouve tous les … » speak over the last success line, and the advance
    /// is gated on that line finishing.
    ///
    /// `holdSays()` is load-bearing: it keeps the round from completing while
    /// the timer fires, which is the only state in which the announce's own
    /// guards would let it through.
    @Test func completingTheRoundCancelsThePendingAnnounce() async {
        let (h, model) = makeModel()
        h.delays.holdDelays()
        h.audio.holdSays()
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        let prompt = Levels.twinPrompt(model.round.family)
        let spoken = model.targets.count
        for tile in model.targets { _ = model.pick(tile) }
        await eventually { h.audio.pendingSayCount == spoken }
        h.delays.releaseNext()  // the timer fires just after the last twin
        // A negative assertion is only as strong as the wait before it.
        for _ in 0..<50 { await Task.yield() }
        #expect(
            !h.audio.sayTexts.contains(prompt),
            Comment(rawValue: "a completed round must not announce its own prompt"))
        await drainSays(h.audio)
    }

    @Test func announceSpeaksTheTwinPromptAfter350ms() async {
        let (h, model) = makeModel()
        model.activate()
        let expected = Levels.twinPrompt(model.round.family)
        await eventually { h.audio.sayTexts.contains(expected) }
        #expect(h.delays.requests == [350])
        #expect(expected == "Trouve tous les \(model.round.family.sound) !")
    }

    @Test func replayAndPreviewAreLockedGuarded() async {
        let (h, model) = makeModel()
        model.replayPrompt()
        await eventually { h.audio.sayTexts.contains(Levels.twinPrompt(model.round.family)) }
        model.preview(say: model.round.tiles[0].sound)
        await eventually { h.audio.events.contains(.say(model.round.tiles[0].sound, 0.94)) }
        // Complete the round with the line held → locked → both go quiet.
        h.audio.holdSays()
        let spoken = model.targets.count  // every correct pick speaks one line
        for tile in model.targets {
            _ = model.pick(tile)
        }
        // Wait until EVERY pick's say task has arrived (and suspended), so no
        // straggler records an event after the clear below.
        await eventually { h.audio.pendingSayCount == spoken }
        h.audio.clearEvents()
        model.replayPrompt()
        model.preview(say: "x")
        await Task.yield()
        #expect(h.audio.events.isEmpty)
        await drainSays(h.audio)
    }
}
