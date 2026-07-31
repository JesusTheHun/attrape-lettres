import Testing

import ALCore
@testable import ALUI

// The assembly state machine (engines.md §4.2), asserted against the TSX:
// AssembleExercise.tsx is the reference for the loop; SpellSound and
// SpellSyllable differ only through the descriptor.

/// A fully scripted assembly: every round has the same `answer`; the item is
/// its own round. `seeded`/`lockedMask` default to an all-empty row.
@MainActor
private func makeAssembly(
    items: [String],
    answer: [String],
    seeded: [String?]? = nil,
    lockedMask: [Bool]? = nil,
    _ h: EngineHarness
) -> AssemblyModel<String, String, String> {
    AssemblyModel(
        descriptor: AssemblyDescriptor(
            exercise: .orderSyllables,
            level: 1,
            headline: "H",
            finishedTitle: "T",
            listenAccessibilityLabel: "L",
            buildSession: { _ in items },
            buildRound: { item, _ in item },
            promptLine: { "P-\($0)" },
            successLine: { "S-\($0)" },
            seededSlots: { _ in seeded ?? answer.map { _ in nil } },
            lockedSlots: { _ in lockedMask ?? answer.map { _ in false } },
            judge: { _, filled in filled == answer }
        ),
        deps: h.deps
    )
}

@Suite("AssemblyModel — fill, undo, judge")
@MainActor
struct AssemblyHandlerTests {

    @Test func valuesLandInTheFirstEmptySlotWithNoJudgement() {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r"], answer: ["BA", "TO"], h)
        #expect(model.pick(tileID: 1, value: "TO") == .accept)
        #expect(model.slots == ["TO", nil])
        #expect(model.slotTile == [1, nil])
        #expect(model.used == [1])
        #expect(model.isTrayTileUsed(id: 1))
        // A partial row: pop only — no judgement, no chime, no confetti.
        #expect(h.audio.events == [.unlock, .pop])
        #expect(h.confetti.count == 0)
        #expect(model.stars == [true])
    }

    @Test func removeAtSendsTheTileHome() {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r"], answer: ["BA", "TO"], h)
        _ = model.pick(tileID: 1, value: "TO")
        h.audio.clearEvents()
        #expect(model.isSlotRemovable(0))
        model.removeAt(0)
        #expect(h.audio.events == [.pop])
        #expect(model.slots == [nil, nil])
        #expect(model.slotTile == [nil, nil])
        #expect(model.used.isEmpty)
        // An empty slot ignores the tap — silently.
        h.audio.clearEvents()
        model.removeAt(1)
        #expect(h.audio.events.isEmpty)
    }

    @Test func removeAtRefusesPreRevealedSlots() {
        let h = EngineHarness()
        let model = makeAssembly(
            items: ["r"], answer: ["BA", "TO"],
            seeded: ["BA", nil], lockedMask: [true, false], h)
        #expect(model.slots == ["BA", nil], Comment(rawValue: "fill-blank seeds the revealed syllable"))
        #expect(!model.isSlotRemovable(0), Comment(rawValue: "pre-revealed slots are not interactive"))
        model.removeAt(0)
        #expect(model.slots == ["BA", nil])
        #expect(h.audio.events.isEmpty)
    }

    @Test func correctRowCelebratesThenAdvancesWithImmediateAnnounce() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA", "TO"], h)
        _ = model.pick(tileID: 1, value: "BA")
        h.audio.clearEvents()
        #expect(model.pick(tileID: 2, value: "TO") == .accept)
        // The completing tap: pop, then the row-level success + confetti.
        #expect(h.audio.events == [.unlock, .pop, .success])
        #expect(h.confetti.count == 1)
        #expect(model.mood == .happy)
        await eventually { h.audio.events.contains(.say("S-r1", 0.98)) }
        await eventually { model.idx == 1 }
        #expect(model.mood == .idle)
        #expect(model.slots == [nil, nil], Comment(rawValue: "fresh empty slots"))
        #expect(model.used.isEmpty)
        // The next round announces IMMEDIATELY — no 350 ms request was made.
        await eventually { h.audio.sayTexts.contains("P-r2") }
        #expect(h.delays.requests.isEmpty, Comment(rawValue: "only round 0 uses the delayed announce"))
        #expect(h.audio.events.contains(.say("P-r2", 0.94)))
    }

    @Test func wrongRowOopsesGreysAndResets() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA", "TO"], h)
        h.audio.holdSays()
        _ = model.pick(tileID: 1, value: "TO")
        h.audio.clearEvents()
        #expect(model.pick(tileID: 2, value: "BA") == .accept, Comment(rawValue: "the completing tile does NOT shake"))
        // oops — the two-note wah-wah — NOT nudge; the star greys NOW.
        #expect(h.audio.events == [.unlock, .pop, .oops])
        #expect(!h.audio.events.contains(.nudge))
        #expect(model.stars == [false, true])
        #expect(h.confetti.count == 0)
        #expect(model.mood == .idle, Comment(rawValue: "a wrong row never changes the mascot"))
        // « Oh non ! On recommence. » is playing; the row is locked.
        await eventually { h.audio.pendingSayCount == 1 }
        #expect(h.audio.pendingSayTexts == ["Oh non ! On recommence."])
        h.audio.clearEvents()
        #expect(model.pick(tileID: 3, value: "BA") == .reject)
        #expect(h.audio.events.isEmpty, Comment(rawValue: "taps during the line are silent rejects"))
        model.removeAt(0)
        #expect(model.slots == ["TO", "BA"], Comment(rawValue: "no undo while locked"))
        // The line ends → wipe back to the seeded row, unlocked, no cooldown.
        h.audio.resolveNextSay(true)
        await eventually { model.slots == [nil, nil] }
        #expect(model.used.isEmpty)
        #expect(model.slotTile == [nil, nil])
        #expect(model.idx == 0, Comment(rawValue: "the round replays — nothing lost, no fail state"))
        // Immediately pickable again — assembly has NO miss cooldown.
        #expect(model.pick(tileID: 1, value: "BA") == .accept)
    }

    @Test func wrongRowResetHappensEvenIfTheLineWasCutShort() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1"], answer: ["BA", "TO"], h)
        h.audio.queueSayResult(false)  // « Oh non » superseded — reset must STILL happen
        _ = model.pick(tileID: 1, value: "TO")
        _ = model.pick(tileID: 2, value: "BA")
        await eventually { model.slots == [nil, nil] }
        #expect(model.used.isEmpty)
        #expect(model.pick(tileID: 1, value: "BA") == .accept, Comment(rawValue: "unlocked after the cut line"))
    }

    @Test func wrongRowResetRestoresSeededFillBlankSlots() async {
        let h = EngineHarness()
        let model = makeAssembly(
            items: ["r1"], answer: ["BA", "TO"],
            seeded: ["BA", nil], lockedMask: [true, false], h)
        _ = model.pick(tileID: 9, value: "ZU")  // fills the one gap → wrong row
        await eventually { model.slots == ["BA", nil] }
        #expect(model.slots == ["BA", nil], Comment(rawValue: "pre-revealed syllables survive the wipe"))
        #expect(model.used.isEmpty)
    }

    @Test func advanceIsGatedOnTheSuccessLineButResetIsNot() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA"], h)
        h.audio.queueSayResult(false)
        _ = model.pick(tileID: 1, value: "BA")  // correct row, line cut short
        await eventually { h.audio.events.contains(.say("S-r1", 0.98)) }
        await Task.yield()
        await Task.yield()
        #expect(model.idx == 0, Comment(rawValue: "a cut success line never advances"))
        // Still locked (the TSX leaves locked=true on a cut celebrate).
        h.audio.clearEvents()
        #expect(model.pick(tileID: 2, value: "BA") == .reject)
        #expect(h.audio.events.isEmpty)
    }

    @Test func slotsFullPickIsRejectedAfterThePop() {
        let h = EngineHarness()
        let model = makeAssembly(
            items: ["r"], answer: ["BA", "TO"],
            seeded: ["BA", "TO"], lockedMask: [true, true], h)
        #expect(model.pick(tileID: 1, value: "X") == .reject)
        // Faithful ordering: unlock + pop fire BEFORE the no-empty-slot check.
        #expect(h.audio.events == [.unlock, .pop])
    }

    @Test func deactivatedModelNeverResetsNorAdvances() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1"], answer: ["BA", "TO"], h)
        h.audio.holdSays()
        _ = model.pick(tileID: 1, value: "TO")
        _ = model.pick(tileID: 2, value: "BA")  // wrong row → « Oh non » pending
        await eventually { h.audio.pendingSayCount == 1 }
        model.deactivate()
        h.audio.resolveNextSay(true)
        await Task.yield()
        await Task.yield()
        #expect(model.slots == ["TO", "BA"], Comment(rawValue: "a dead engine never mutates state"))
    }

    @Test func finishAwardsOnceAndSpeaksTheAssemblyBravo() async {
        let h = EngineHarness()
        h.award.result = 11
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA"], h)
        _ = model.pick(tileID: 1, value: "BA")
        await eventually { model.idx == 1 }
        _ = model.pick(tileID: 2, value: "BA")
        await eventually { model.done }
        #expect(model.mood == .cheer)
        #expect(model.earned == 11)
        #expect(h.award.calls.count == 1)
        #expect(h.award.calls[0].exercise == .orderSyllables)
        #expect(h.award.calls[0].perfect == 2)
        #expect(h.award.calls[0].total == 2)
        await eventually { h.audio.sayTexts.contains("Bravo ! Tu as tout réussi !") }
        #expect(h.audio.sayTexts.contains("Bravo ! Tu as tout réussi !"))
    }

    @Test func round0AnnouncesAfter350msOnActivateOnly() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA"], h)
        model.activate()
        #expect(h.audio.events.first == .unlock)
        await eventually { h.audio.sayTexts.contains("P-r1") }
        #expect(h.delays.requests == [350])
        // Re-activation does not re-announce (the TSX effect has [] deps).
        model.activate()
        await Task.yield()
        #expect(h.delays.requests == [350])
    }

    /// D45 — the fast-child stall, on the assembly engine.
    ///
    /// Only round 0 uses the delayed announce here (later rounds announce
    /// immediately), so this is the window: a child who completes the first row
    /// within 350 ms used to have the prompt cut their own success line short,
    /// and the advance is gated on that line finishing.
    ///
    /// `holdSays()` is load-bearing — it keeps `idx` at 0 while the timer fires,
    /// which is the only state where the announce's guard would pass.
    @Test func completingTheFirstRowCancelsThePendingAnnounce() async {
        let h = EngineHarness()
        h.delays.holdDelays()
        h.audio.holdSays()
        let model = makeAssembly(items: ["r1", "r2"], answer: ["BA", "TO"], h)
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        _ = model.pick(tileID: 1, value: "BA")
        #expect(model.pick(tileID: 2, value: "TO") == .accept)  // row complete, inside the window
        await eventually { h.audio.pendingSayCount == 1 }  // success line in flight
        h.delays.releaseNext()
        // A negative assertion is only as strong as the wait before it.
        for _ in 0..<50 { await Task.yield() }
        #expect(
            !h.audio.sayTexts.contains("P-r1"),
            Comment(rawValue: "a completed row must not announce its own prompt"))
    }

    @Test func pendingRound0AnnounceIsCancelledOnDeactivate() async {
        let h = EngineHarness()
        h.delays.holdDelays()
        let model = makeAssembly(items: ["r1"], answer: ["BA"], h)
        model.activate()
        await eventually { h.delays.pendingCount == 1 }
        model.deactivate()
        h.delays.releaseNext()
        await Task.yield()
        await Task.yield()
        #expect(!h.audio.sayTexts.contains("P-r1"))
    }

    @Test func replayAndPreviewAreLockedGuarded() async {
        let h = EngineHarness()
        let model = makeAssembly(items: ["r1"], answer: ["BA", "TO"], h)
        model.replayPrompt()
        await eventually { h.audio.events.contains(.say("P-r1", 0.94)) }
        model.preview(say: "BA")
        #expect(h.audio.events.contains(.unlock), Comment(rawValue: "preview unlocks"))
        await eventually { h.audio.events.contains(.say("BA", 0.94)) }
        // Wrong row → locked while « Oh non » plays: both go quiet.
        h.audio.holdSays()
        _ = model.pick(tileID: 1, value: "TO")
        _ = model.pick(tileID: 2, value: "BA")
        await eventually { h.audio.pendingSayCount == 1 }
        h.audio.clearEvents()
        model.replayPrompt()
        model.preview(say: "BA")
        await Task.yield()
        #expect(h.audio.events.isEmpty)
        h.audio.resolveNextSay(true)
        await eventually { model.slots == [nil, nil] }
    }
}

@Suite("AssemblyModel — real exercises")
@MainActor
struct AssemblyFactoryTests {

    @Test func assembleOrderSessionShapeAndCarefulRun() async {
        let h = EngineHarness()
        h.award.result = 9
        let model = AssemblyModel.assemble(
            exercise: .orderSyllables, mode: .order, level: 1, deps: h.deps, rng: .seeded(42))
        let tier = Levels.syllableTier(1)
        let pool = Levels.syllablePool(tier)
        let p = min(tier.pick, pool.count)
        #expect(model.totalRounds == p + min(tier.repeats, p))

        // A careful full run: place every round's syllables in order.
        var safety = 0
        while !model.done && safety < 200 {
            safety += 1
            let expected = model.idx
            let word = model.round.word
            #expect(model.round.tray.count == word.syllables.count, Comment(rawValue: "order mode has no intruder"))
            for syllable in word.syllables {
                let tile = model.round.tray.first {
                    $0.syllable == syllable && !model.isTrayTileUsed(id: $0.id)
                }
                guard let tile else {
                    Issue.record("no free tray tile for \(syllable)")
                    return
                }
                #expect(model.pick(tileID: tile.id, value: tile.syllable) == .accept)
            }
            await eventually { model.idx == expected + 1 || model.done }
        }
        #expect(model.done)
        #expect(model.earned == 9)
        #expect(h.award.calls.count == 1)
        #expect(h.award.calls[0].perfect == model.totalRounds, Comment(rawValue: "a careful run is all-perfect"))
        #expect(h.award.calls[0].total == model.totalRounds)
    }

    @Test func assembleFillBlankKeepsRevealedSlotsThroughAWrongRow() async throws {
        let h = EngineHarness()
        let model = AssemblyModel.assemble(
            exercise: .fillBlank, mode: .fillBlank, level: 1, deps: h.deps, rng: .seeded(5))
        let round = model.round
        let seeded = round.slots
        let gap = try #require(round.slots.firstIndex(of: nil))
        let missing = round.word.syllables[gap]
        #expect(round.locked.filter { !$0 }.count == 1, Comment(rawValue: "exactly one slot to fill"))
        #expect(round.tray.count == 2, Comment(rawValue: "the missing syllable + one distractor"))

        // Tap the distractor: the row completes wrong.
        let wrongTile = try #require(model.round.tray.first { $0.syllable != missing })
        _ = model.pick(tileID: wrongTile.id, value: wrongTile.syllable)
        #expect(model.stars[0] == false)
        await eventually { model.slots == seeded }
        #expect(model.slots == seeded, Comment(rawValue: "the wipe restores the SEEDED slots, revealed syllables intact"))

        // Now the right tile: the round advances.
        let rightTile = try #require(model.round.tray.first { $0.syllable == missing })
        _ = model.pick(tileID: rightTile.id, value: rightTile.syllable)
        await eventually { model.idx == 1 || model.done }
        #expect(model.idx == 1 || model.done)
    }

    @Test func spellSoundJudgesTheSpellingInOrder() async throws {
        let h = EngineHarness()
        let model = AssemblyModel.spellSound(level: 1, deps: h.deps, rng: .seeded(8))
        let cfg = Levels.soundLevel(1)
        #expect(model.totalRounds == Levels.soundPick + Levels.soundRepeats)
        let round = model.round
        #expect(round.slots.count == round.target.spelling.count)
        #expect(round.tray.count >= round.target.spelling.count)
        #expect(round.tray.count <= round.target.spelling.count + cfg.distractors)
        // Spell it correctly.
        for letter in round.target.spelling {
            let tile = try #require(
                model.round.tray.first { $0.letter == letter && !model.isTrayTileUsed(id: $0.id) })
            #expect(model.pick(tileID: tile.id, value: tile.letter) == .accept)
        }
        await eventually { model.idx == 1 || model.done }
        #expect(model.stars[0] == true)
    }

    @Test func spellSyllableMixedJudgesTheFaceNotJustTheLetter() {
        let h = EngineHarness()
        let mixed = AssemblyModel.spellSyllable(
            exercise: .spellSyllablePlusMixed, mode: .lettersExtra, level: 1, mixed: true,
            deps: h.deps, rng: .seeded(3))
        let judge = mixed.descriptor.judge
        let word = SyllableWord(word: "BATEAU", syllables: ["BA", "TEAU"], emoji: "⛵")
        let cursiveA = LetterFace(base: "A", glyph: "a", script: .cursive)
        let printA = LetterFace(base: "A", glyph: "A", script: .print)
        let round = SpellSyllableRound(
            word: word, cells: [], answer: ["A"], answerFaces: [cursiveA], tray: [])
        #expect(judge(round, [cursiveA]), Comment(rawValue: "the right face passes"))
        #expect(!judge(round, [printA]), Comment(rawValue: "right letter, wrong writing — the row fails"))

        // Plain rounds are all-uppercase print, so letter equality suffices.
        let plain = AssemblyModel.spellSyllable(
            exercise: .spellSyllable, mode: .lettersExact, level: 1, mixed: false,
            deps: h.deps, rng: .seeded(3))
        let plainRound = SpellSyllableRound(
            word: word, cells: [], answer: ["B"],
            answerFaces: [LetterFace(base: "B", glyph: "B", script: .print)], tray: [])
        #expect(plain.descriptor.judge(plainRound, [LetterFace(base: "B", glyph: "B", script: .print)]))

        // sameFace ignores `base` — two faces are the same tile iff they RENDER
        // identically (the TSX comparison, not LetterFace ==).
        #expect(sameFace(
            LetterFace(base: "X", glyph: "A", script: .print),
            LetterFace(base: "A", glyph: "A", script: .print)))
        #expect(!sameFace(cursiveA, printA))
    }

    @Test func spellSyllableRoundShapeMatchesTheBuilder() {
        let h = EngineHarness()
        let model = AssemblyModel.spellSyllable(
            exercise: .spellSyllable, mode: .lettersExact, level: 1, mixed: false,
            deps: h.deps, rng: .seeded(12))
        let cfg = Levels.spellSyllableLevel(1)
        let p = min(cfg.pick, Levels.spellSyllablePool(1).count)
        #expect(model.totalRounds == p + min(cfg.repeats, p))
        let round = model.round
        #expect(model.slots.count == round.answerFaces.count)
        #expect(model.slots.allSatisfy { $0 == nil }, Comment(rawValue: "SpellSyllable seeds all-empty slots"))
        #expect(round.tray.count == round.answer.count, Comment(rawValue: "letters-exact has no intruder"))
    }

    @Test func assembleSessionIsReproducibleFromTheSeed() {
        let h = EngineHarness()
        let a = AssemblyModel.assemble(
            exercise: .orderSyllables, mode: .order, level: 2, deps: h.deps, rng: .seeded(77))
        let b = AssemblyModel.assemble(
            exercise: .orderSyllables, mode: .order, level: 2, deps: h.deps, rng: .seeded(77))
        #expect(a.session == b.session)
        #expect(a.round.word == b.round.word)
        #expect(a.round.tray.map(\.syllable) == b.round.tray.map(\.syllable))
    }
}
