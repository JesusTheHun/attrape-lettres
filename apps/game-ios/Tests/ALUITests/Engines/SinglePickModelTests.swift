import Testing

import ALCore
@testable import ALUI

// The single-pick state machine (engines.md §4.1), asserted against the TSX:
// FirstLetterExercise.tsx is the reference for the loop; FindSound / Grid /
// LetterMatch / ReadImage differ only through the descriptor.

/// A fully scripted descriptor: `Round == String`, the round IS its key.
@MainActor
private func makeModel(
    rounds: [String],
    _ h: EngineHarness,
    previewGuarded: Bool = false
) -> SinglePickModel<String> {
    SinglePickModel(
        descriptor: SinglePickDescriptor(
            exercise: .findSound,
            level: 1,
            headline: nil,
            finishedTitle: "T",
            listenAccessibilityLabel: "L",
            previewGuardedByLock: previewGuarded,
            buildSession: { _ in rounds },
            targetKey: { $0 },
            promptLine: { "P-\($0)" },
            successLine: { "S-\($0)" },
            listenText: { _ in "🔊" }
        ),
        deps: h.deps
    )
}

@Suite("SinglePickModel — the pick handler")
@MainActor
struct SinglePickHandlerTests {

    @Test func wrongPickNudgesAndGreysStarSynchronously() {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        let verdict = model.pick("X")
        #expect(verdict == .reject)
        // Exact audio order: the tap pops first, then the nudge marks the miss.
        #expect(h.audio.events == [.unlock, .pop, .nudge])
        #expect(model.stars == [false, true], Comment(rawValue: "the star greys NOW, at pointerdown"))
        #expect(model.mood == .idle, Comment(rawValue: "a miss never changes the mascot"))
        #expect(model.flash == nil)
        #expect(model.done == false)
        #expect(model.idx == 0)
        #expect(h.confetti.count == 0)
    }

    @Test func cooldownSwallowsSilentlyForExactly800ms() {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        _ = model.pick("X")
        h.audio.clearEvents()

        // Inside the window: verdict .reject, ZERO audio, zero state change.
        #expect(model.pick("A") == .reject)
        #expect(h.audio.events.isEmpty, Comment(rawValue: "swallowed picks make no sound at all"))
        #expect(model.stars == [false, true])

        // 799 ms later: still swallowed (strict `<` on MISS_COOLDOWN_MS = 800).
        h.time.advance(millis: 799)
        #expect(model.pick("A") == .reject)
        #expect(h.audio.events.isEmpty)

        // At exactly +800 ms the window is over.
        h.time.advance(millis: 1)
        #expect(model.pick("A") == .accept)
        #expect(h.audio.events.first == .unlock)
    }

    @Test func secondMissInSameRoundGreysNothingFurtherButReArmsCooldown() {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        _ = model.pick("X")
        h.time.advance(millis: 800)
        #expect(model.pick("Y") == .reject)
        #expect(model.stars == [false, true], Comment(rawValue: "missRound is idempotent per round"))
        // A fresh cooldown was still set.
        h.audio.clearEvents()
        #expect(model.pick("A") == .reject)
        #expect(h.audio.events.isEmpty)
    }

    @Test func missLocksNothingAndChangesNoRoute() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        _ = model.pick("X")
        h.time.advance(millis: 800)
        // Invariant 3: after a miss the round is fully winnable.
        #expect(model.pick("A") == .accept)
        await eventually { model.idx == 1 }
        #expect(model.idx == 1)
        #expect(model.done == false)
        #expect(model.stars == [false, true])
    }

    @Test func rightPickCelebratesAtPointerdown() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        h.audio.holdSays()
        #expect(model.pick("A") == .accept)
        // Synchronous beat: unlock, pop, success chime + confetti + flash.
        #expect(h.audio.events == [.unlock, .pop, .success])
        #expect(h.confetti.count == 1)
        #expect(model.flash == "A")
        #expect(model.tilesDisabled, Comment(rawValue: "disabled={flash != null}"))
        #expect(model.mood == .happy)
        // The success line, at rate 0.98.
        await eventually { h.audio.pendingSayCount == 1 }
        #expect(h.audio.events.contains(.say("S-A", 0.98)))
        h.audio.resolveNextSay(true)
        await eventually { model.idx == 1 }
        await drainSays(h.audio)
    }

    @Test func advanceIsGatedOnTheSuccessLineResult() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        h.audio.queueSayResult(false)  // the line was superseded / cut short
        _ = model.pick("A")
        await eventually { h.audio.events.contains(.say("S-A", 0.98)) }
        await Task.yield()
        await Task.yield()
        #expect(model.idx == 0, Comment(rawValue: "a cut line never advances the round"))
        #expect(model.flash == "A", Comment(rawValue: "flash is not cleared on a cut line"))
        // Still locked: a further pick is silently rejected.
        h.audio.clearEvents()
        #expect(model.pick("B") == .reject)
        #expect(h.audio.events.isEmpty)
    }

    @Test func advanceOnSayTrueResetsFlashLockAndMood() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        _ = model.pick("A")
        await eventually { model.idx == 1 }
        #expect(model.flash == nil)
        #expect(model.mood == .idle)
        #expect(model.tilesDisabled == false)
        #expect(model.stars == [true, true])
        // Unlocked: the next round accepts picks.
        #expect(model.pick("B") == .accept)
    }

    @Test func pickWhileLockedIsSilentlyRejected() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        h.audio.holdSays()
        _ = model.pick("A")
        await eventually { h.audio.pendingSayCount == 1 }
        h.audio.clearEvents()
        #expect(model.pick("A") == .reject)
        #expect(h.audio.events.isEmpty)
        await drainSays(h.audio)
    }

    @Test func finishAwardsOnceAndSpeaksTheBravoLine() async {
        let h = EngineHarness()
        h.award.result = 13
        let model = makeModel(rounds: ["A", "B"], h)
        _ = model.pick("X")  // grey round 0's star
        h.time.advance(millis: 800)
        _ = model.pick("A")
        await eventually { model.idx == 1 }
        _ = model.pick("B")
        await eventually { model.done }
        #expect(model.done)
        #expect(model.mood == .cheer)
        #expect(model.earned == 13, Comment(rawValue: "earned is award's return, untouched"))
        #expect(h.award.calls.count == 1)
        #expect(h.award.calls[0].exercise == .findSound)
        #expect(h.award.calls[0].level == 1)
        #expect(h.award.calls[0].perfect == 1, Comment(rawValue: "rounds with zero misses"))
        #expect(h.award.calls[0].total == 2)
        await eventually { h.audio.sayTexts.contains("Bravo ! Tu as tout trouvé !") }
        #expect(h.audio.sayTexts.contains("Bravo ! Tu as tout trouvé !"))
        #expect(model.progressDone == 2, Comment(rawValue: "GameFrame's done input caps at total"))
    }

    @Test func deactivatedModelNeverAdvancesOrAwards() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A"], h)
        h.audio.holdSays()
        _ = model.pick("A")
        await eventually { h.audio.pendingSayCount == 1 }
        model.deactivate()
        h.audio.resolveNextSay(true)  // the line completes AFTER the exercise is gone
        await Task.yield()
        await Task.yield()
        await Task.yield()
        #expect(model.done == false)
        #expect(h.award.calls.isEmpty, Comment(rawValue: "a dead engine never awards"))
        #expect(h.audio.events.contains(.stop), Comment(rawValue: "leaving fades the current line"))
    }

    @Test func spamEarnsBareCurveCarefulEarnsBonus() async {
        // Invariant 8, end to end against the real economy: award wired to
        // Rewards.sessionReward with a real difficulty.
        let difficulty = Levels.exerciseDifficulty(.findSound)
        #expect(difficulty.weight > 0, Comment(rawValue: "findSound must pay for this test to bite"))

        let spam = await runEconomySession(missEveryRound: true, difficulty: difficulty)
        let careful = await runEconomySession(missEveryRound: false, difficulty: difficulty)
        #expect(spam == Rewards.rewardFor(priorClears: 0), Comment(rawValue: "spam pays the bare curve"))
        #expect(
            careful == Rewards.rewardFor(priorClears: 0) + difficulty.weight,
            Comment(rawValue: "a full-perfect run adds exactly the difficulty weight"))
        #expect(careful > spam)
    }
}

/// One full three-round run with the award closure wired to the REAL
/// `Rewards.sessionReward`. Returns what the model displayed as earned.
@MainActor
private func runEconomySession(missEveryRound: Bool, difficulty: Difficulty) async -> Int {
    let h = EngineHarness()
    var earnedViaReward = 0
    var deps = h.deps
    deps.award = { ex, _, perfect, total in
        #expect(ex == .findSound)
        earnedViaReward = Rewards.sessionReward(
            difficulty: difficulty, priorClears: 0,
            perfectRounds: perfect, totalRounds: total)
        return earnedViaReward
    }
    let model = SinglePickModel(
        descriptor: SinglePickDescriptor(
            exercise: .findSound, level: 1, headline: nil, finishedTitle: "T",
            listenAccessibilityLabel: "L", previewGuardedByLock: false,
            buildSession: { _ in ["A", "B", "C"] },
            targetKey: { $0 }, promptLine: { "P-\($0)" },
            successLine: { "S-\($0)" }, listenText: { _ in "🔊" }
        ),
        deps: deps
    )
    for (i, key) in ["A", "B", "C"].enumerated() {
        if missEveryRound {
            _ = model.pick("WRONG")
            h.time.advance(millis: 800)
        }
        _ = model.pick(key)
        await eventually { model.idx == i + 1 || model.done }
    }
    await eventually { model.done }
    #expect(model.earned == earnedViaReward, Comment(rawValue: "the model never adds to award's return"))
    return model.earned
}

@Suite("SinglePickModel — announce, replay, preview")
@MainActor
struct SinglePickAudioTests {

    @Test func announces350msAfterEveryRoundBecomesCurrent() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        model.activate()
        #expect(h.audio.events.first == .unlock, Comment(rawValue: "unlock on appear"))
        await eventually { h.audio.sayTexts.contains("P-A") }
        #expect(h.delays.requests == [350])
        _ = model.pick("A")
        await eventually { model.idx == 1 }
        await eventually { h.audio.sayTexts.contains("P-B") }
        #expect(h.delays.requests == [350, 350], Comment(rawValue: "every advance re-announces after 350 ms"))
    }

    /// D45 — the fast-child stall.
    ///
    /// A correct tap inside the 350 ms announce window used to let the prompt
    /// speak over the success line. The real `say` returns `false` when it is
    /// cut short, the advance is gated on that `false`, and the round stranded
    /// with `locked` still true — no error, no feedback, and only for children
    /// quick enough to answer in a third of a second.
    ///
    /// `holdSays()` is what makes this test mean anything. It keeps the success
    /// line open, so `idx` has NOT advanced when the timer fires — which is the
    /// only state in which the announce's own `idx == expected` guard would let
    /// it through. Without it the guard masks the bug and the test passes
    /// against the unfixed model.
    ///
    /// The waits are load-bearing too, and this test was VACUOUS before they
    /// were added: two bare `Task.yield()`s after `releaseNext()` were not
    /// enough for the resumed announce task to reach its `say`, so it passed
    /// against the unfixed model. It is verified by mutation now — remove the
    /// `announceTask?.cancel()` from `pick` and this fails.
    @Test func aCorrectPickCancelsThePendingAnnounce() async {
        let h = EngineHarness()
        h.delays.holdDelays()
        h.audio.holdSays()
        let model = makeModel(rounds: ["A", "B"], h)
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        _ = model.pick("A")  // answered inside the window
        // The success line is in flight and suspended: `idx` is pinned at 0.
        await eventually { h.audio.pendingSayCount == 1 }
        h.delays.releaseNext()  // …and the timer fires right after
        // Give the resumed announce task room to reach its `say` if it is going
        // to. A negative assertion is only as strong as the wait before it.
        for _ in 0..<50 { await Task.yield() }
        #expect(
            !h.audio.sayTexts.contains("P-A"),
            Comment(rawValue: "an answered round must not announce its own prompt"))
    }

    @Test func pendingAnnounceIsCancelledOnDeactivate() async {
        let h = EngineHarness()
        h.delays.holdDelays()
        let model = makeModel(rounds: ["A", "B"], h)
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        model.deactivate()
        h.delays.releaseNext()
        await Task.yield()
        await Task.yield()
        #expect(!h.audio.sayTexts.contains("P-A"), Comment(rawValue: "no stale prompt after unmount"))
    }

    @Test func staleAnnounceNeverSpeaksOverTheNextRound() async {
        let h = EngineHarness()
        h.delays.holdDelays()
        let model = makeModel(rounds: ["A", "B"], h)
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        _ = model.pick("A")  // advance while round 0's announce is still pending
        await eventually { model.idx == 1 }
        await eventually { h.delays.pendingCount == 2 }
        h.delays.releaseNext()  // round 0's timer fires late
        await Task.yield()
        await Task.yield()
        #expect(!h.audio.sayTexts.contains("P-A"), Comment(rawValue: "the idx guard drops the stale announce"))
        h.delays.releaseNext()  // round 1's timer
        await eventually { h.audio.sayTexts.contains("P-B") }
    }

    @Test func noAnnounceAfterFinish() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A"], h)
        model.activate()
        await eventually { h.audio.sayTexts.contains("P-A") }
        _ = model.pick("A")
        await eventually { model.done }
        await Task.yield()
        #expect(h.delays.requests == [350], Comment(rawValue: "done schedules no further announce"))
    }

    @Test func replayPromptIsLockedGuardedAndSpeaksTheLine() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A", "B"], h)
        model.replayPrompt()
        await eventually { h.audio.sayTexts.contains("P-A") }
        // 0.94 — the default rate, not the success rate.
        #expect(h.audio.events.contains(.say("P-A", 0.94)))
        // Locked (celebration playing): the button goes quiet.
        h.audio.holdSays()
        _ = model.pick("A")
        await eventually { h.audio.pendingSayCount == 1 }
        h.audio.clearEvents()
        model.replayPrompt()
        await Task.yield()
        #expect(h.audio.events.isEmpty, Comment(rawValue: "never cut the success line mid-celebration"))
        await drainSays(h.audio)
    }

    @Test func previewGuardAsymmetryIsPortedExactly() async {
        // Four single-pick engines preview WITHOUT a locked guard…
        let h1 = EngineHarness()
        let unguarded = makeModel(rounds: ["A"], h1, previewGuarded: false)
        h1.audio.holdSays()
        _ = unguarded.pick("A")
        await eventually { h1.audio.pendingSayCount == 1 }
        h1.audio.clearEvents()
        unguarded.preview(say: "a")
        #expect(h1.audio.events == [.unlock], Comment(rawValue: "unguarded preview still unlocks + speaks while locked"))
        await eventually { h1.audio.pendingSayCount == 2 }
        await drainSays(h1.audio)

        // …ReadImage's IS guarded.
        let h2 = EngineHarness()
        let guarded = makeModel(rounds: ["A"], h2, previewGuarded: true)
        h2.audio.holdSays()
        _ = guarded.pick("A")
        await eventually { h2.audio.pendingSayCount == 1 }
        h2.audio.clearEvents()
        guarded.preview(say: "a")
        await Task.yield()
        #expect(h2.audio.events.isEmpty)
        await drainSays(h2.audio)
    }

    @Test func previewSpeaksAtDefaultRateWithUnlock() async {
        let h = EngineHarness()
        let model = makeModel(rounds: ["A"], h)
        model.preview(say: "ou")
        #expect(h.audio.events.first == .unlock)
        await eventually { h.audio.events.contains(.say("ou", 0.94)) }
    }
}

@Suite("SinglePickModel — real exercises")
@MainActor
struct SinglePickFactoryTests {

    @Test func firstLetterRoundsHaveThreeChoicesIncludingTheTarget() {
        let h = EngineHarness()
        let model = SinglePickModel.firstLetter(level: 1, deps: h.deps, rng: .seeded(7))
        #expect(!model.session.isEmpty)
        for round in model.session {
            #expect(round.choices.count == 3, Comment(rawValue: "target + exactly 2 distractors"))
            #expect(round.choices.contains(round.target.letter))
            #expect(Set(round.choices).count == 3, Comment(rawValue: "distractors differ from the target"))
        }
        let cfg = Levels.firstLetterLevels[0]
        #expect(model.totalRounds == cfg.pick + cfg.repeats)
    }

    @Test func firstLetterSessionIsReproducibleFromTheSeed() {
        let h = EngineHarness()
        let a = SinglePickModel.firstLetter(level: 2, deps: h.deps, rng: .seeded(99))
        let b = SinglePickModel.firstLetter(level: 2, deps: h.deps, rng: .seeded(99))
        #expect(a.session == b.session)
        let c = SinglePickModel.firstLetter(level: 2, deps: h.deps, rng: .seeded(100))
        #expect(a.session != c.session, Comment(rawValue: "a different seed reshuffles"))
    }

    @Test func firstLetterListenTextDropsTheWordOnTheLastTwoLevels() {
        let h = EngineHarness()
        #expect(Levels.firstLetterLevels.count == 5)
        let early = SinglePickModel.firstLetter(level: 1, deps: h.deps, rng: .seeded(1))
        #expect(early.listenText == "🔊 \(early.current.target.word)")
        let fourth = SinglePickModel.firstLetter(level: 4, deps: h.deps, rng: .seeded(1))
        #expect(fourth.listenText == "🔊", Comment(rawValue: "levels 4-5 are sound-only"))
        let last = SinglePickModel.firstLetter(level: 5, deps: h.deps, rng: .seeded(1))
        #expect(last.listenText == "🔊")
    }

    @Test func findSoundRunShapeMatchesTheLevelConfig() {
        let h = EngineHarness()
        let model = SinglePickModel.findSound(level: 1, deps: h.deps, rng: .seeded(3))
        let cfg = Levels.findSoundLevel(1)
        let pool = Levels.findSoundPool(1)
        let p = min(cfg.pick, pool.count)
        #expect(model.totalRounds == p + min(cfg.repeats, p))
        for round in model.session {
            #expect(round.choices.count == 1 + cfg.distractors)
            #expect(round.choices.contains(round.target))
        }
        // Never the same target two rounds in a row.
        for i in 1..<model.session.count {
            #expect(model.session[i].target != model.session[i - 1].target)
        }
    }

    @Test func syllableGridSessionNeverRepeatsBackToBack() {
        let h = EngineHarness()
        let model = SinglePickModel.syllableGrid(
            exercise: .hearSyllable, mode: .hear, level: 1, deps: h.deps, rng: .seeded(11))
        let cfg = Levels.syllableGridLevel(1)
        let pool = Levels.syllableGridPool(1)
        let p = min(cfg.pick, pool.count)
        #expect(model.totalRounds == p + min(cfg.repeats, p))
        for i in 1..<model.session.count {
            #expect(model.session[i].target != model.session[i - 1].target)
        }
        // Reproducible from the seed.
        let again = SinglePickModel.syllableGrid(
            exercise: .hearSyllable, mode: .hear, level: 1, deps: h.deps, rng: .seeded(11))
        #expect(model.session == again.session)
    }

    @Test func gridPicksByTextInBothModes() async {
        let h = EngineHarness()
        let model = SinglePickModel.syllableGrid(
            exercise: .pickVowel, mode: .vowel, level: 1, deps: h.deps, rng: .seeded(5))
        let target = model.current.target
        // A wrong pick by the OTHER tile's text.
        if let wrong = model.current.choices.first(where: { $0.text != target.text }) {
            #expect(model.pick(wrong.text) == .reject)
            h.time.advance(millis: 800)
        }
        #expect(model.pick(target.text) == .accept)
        #expect(model.flash == target.text, Comment(rawValue: "flash keys on choice.text, vowel mode included"))
        await eventually { model.idx == 1 || model.done }
    }

    @Test func letterMatchJudgesTheBaseNotTheForm() async {
        let h = EngineHarness()
        let model = SinglePickModel.letterMatch(
            exercise: .matchCase, kind: .case, level: 1, deps: h.deps, rng: .seeded(21))
        let round = model.current
        // The correct tile is the counterpart FORM of the prompt: same base.
        let correct = round.choices.first { $0.base == round.prompt.base }
        let wrong = round.choices.first { $0.base != round.prompt.base }
        #expect(correct != nil)
        if let wrong {
            #expect(model.pick(wrong.base) == .reject)
            h.time.advance(millis: 800)
        }
        if let correct {
            #expect(model.pick(correct.base) == .accept)
            #expect(model.flash == round.prompt.base)
            await eventually { h.audio.sayTexts.contains("Oui ! \(round.prompt.base).") }
        }
    }

    @Test func readImageJudgesByWordAndPromptNeverNamesIt() async {
        let h = EngineHarness()
        let model = SinglePickModel.readImage(level: 1, deps: h.deps, rng: .seeded(17))
        let round = model.current
        #expect(model.promptText == "Trouve la bonne image.")
        if let wrong = round.choices.first(where: { $0.word != round.target.word }) {
            #expect(model.pick(wrong.word) == .reject)
            h.time.advance(millis: 800)
        }
        #expect(model.pick(round.target.word) == .accept)
        await eventually { h.audio.sayTexts.contains("Oui ! \(round.target.word).") }
        #expect(
            !h.audio.sayTexts.contains(round.target.word),
            Comment(rawValue: "the bare word is never spoken — reading it IS the task"))
    }
}
