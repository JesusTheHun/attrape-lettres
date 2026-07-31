import Foundation
import Testing

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* « I earn no point at the end of an exercise » — reported from a device.      */
/*                                                                             */
/* Every tier below this one already passed: `RewardsTests` proves the maths,   */
/* `ProfileStoreTests` proves the store, and `SinglePickModelTests` proves that */
/* a model hands its `award` closure the right counts. What NOTHING covered is  */
/* the whole chain in one piece — catalog row → the engine the hub dispatches   */
/* to → a real session over the real content → `ProfileStore.award` → the       */
/* balance the child sees. An exercise that quietly failed to pay would have    */
/* passed every one of those suites.                                           */
/*                                                                             */
/* So this suite plays EVERY row of `Levels.exercises` to a full-perfect        */
/* finish, through `engine(for:)` — the same function `RootView` dispatches on  */
/* — with `award` wired to a real `ProfileStore`, and asserts the balance.      */
/* A perfect run pays `curve[0] + difficulty` (invariant 8), and difficulty 0   */
/* pays exactly nothing.                                                       */
/*                                                                             */
/* It is deliberately end-to-end and deliberately slow-ish: it is the only test */
/* that would fail if an engine stopped calling `award`, if a catalog row were  */
/* dispatched to the wrong engine, or if a session came back empty (which sets  */
/* `done` in the model's init and skips the finish transition entirely).        */
/* -------------------------------------------------------------------------- */

@MainActor
private func economyWorld() -> (store: ProfileStore, harness: EngineHarness, deps: EngineDeps) {
    let store = ProfileStore(
        kv: InMemoryKVStore(), device: { "this-phone" }, now: { 1_700_000_000_000 })
    store.createChild(name: "Léa")
    let harness = EngineHarness()
    var deps = harness.deps
    // The ONE line under test that no other suite runs: the app's award closure
    // (`RootView.playScreen`) reaching the real store.
    deps.award = { exercise, level, perfect, total in
        _ = harness.award.record(exercise, level, perfect, total)
        return store.award(
            exercise: exercise, level: level, perfectRounds: perfect, totalRounds: total)
    }
    return (store, harness, deps)
}

// MARK: - Playing a session perfectly, engine by engine

/// The five single-pick exercises: the descriptor knows the answer, so a
/// perfect run is « tap `targetKey` once per round ».
@MainActor
private func playPerfectly<Round>(_ model: SinglePickModel<Round>) async {
    var rounds = 0
    while !model.done && rounds < 200 {
        let before = model.idx
        _ = model.pick(model.descriptor.targetKey(model.current))
        await eventually { model.idx != before || model.done }
        rounds += 1
    }
}

/// Twins: tap every tile of the family, none of the intruders.
@MainActor
private func playPerfectly(_ model: TwinsModel) async {
    var rounds = 0
    while !model.done && rounds < 200 {
        let before = model.idx
        for tile in model.targets where !model.found.contains(tile.id) {
            _ = model.pick(tile)
        }
        await eventually { model.idx != before || model.done }
        rounds += 1
    }
}

/// The three assembly engines. `answers` is the round's target row and `tray`
/// its tiles; the model always fills the first empty slot, so the row is built
/// left to right with whichever unused tile matches.
@MainActor
private func playAssembly<Item, Round, Slot>(
    _ model: AssemblyModel<Item, Round, Slot>,
    answers: (Round) -> [Slot],
    tray: (Round) -> [(id: Int, value: Slot)],
    matches: (Slot, Slot) -> Bool
) async {
    var rounds = 0
    while !model.done && rounds < 200 {
        let before = model.idx
        let want = answers(model.round)
        let tiles = tray(model.round)
        var fills = 0
        while let slot = model.slots.firstIndex(where: { $0 == nil }), fills < 50 {
            guard slot < want.count,
                let tile = tiles.first(where: {
                    matches($0.value, want[slot]) && !model.isTrayTileUsed(id: $0.id)
                })
            else { break }
            _ = model.pick(tileID: tile.id, value: tile.value)
            fills += 1
        }
        await eventually { model.idx != before || model.done }
        rounds += 1
    }
}

/// `RootView.playScreen`'s dispatch, in test form: the same `engine(for:)`
/// switch, building the same model each view builds in its `.task`.
@MainActor
private func playCatalogRow(_ meta: ExerciseMeta, level: Int, deps: EngineDeps, seed: UInt64) async {
    let rng = RandomSource.seeded(seed)
    switch engine(for: meta) {
    case .firstLetter:
        await playPerfectly(.firstLetter(level: level, deps: deps, rng: rng))
    case .findSound:
        await playPerfectly(.findSound(level: level, deps: deps, rng: rng))
    case .readImage:
        await playPerfectly(.readImage(level: level, deps: deps, rng: rng))
    case let .syllableGrid(mode):
        await playPerfectly(
            .syllableGrid(exercise: meta.id, mode: mode, level: level, deps: deps, rng: rng))
    case let .letterMatch(kind):
        await playPerfectly(
            .letterMatch(exercise: meta.id, kind: kind, level: level, deps: deps, rng: rng))
    case .soundTwins:
        await playPerfectly(TwinsModel(level: level, deps: deps, rng: rng))
    case let .assemble(mode):
        await playAssembly(
            .assemble(exercise: meta.id, mode: mode, level: level, deps: deps, rng: rng),
            answers: { $0.word.syllables },
            tray: { $0.tray.map { (id: $0.id, value: $0.syllable) } },
            matches: ==)
    case .spellSound:
        await playAssembly(
            .spellSound(level: level, deps: deps, rng: rng),
            answers: { $0.target.spelling },
            tray: { $0.tray.map { (id: $0.id, value: $0.letter) } },
            matches: ==)
    case let .spellSyllable(mode, mixed):
        await playAssembly(
            .spellSyllable(
                exercise: meta.id, mode: mode, level: level, mixed: mixed, deps: deps, rng: rng),
            answers: { $0.answerFaces },
            tray: { $0.tray.map { (id: $0.id, value: SpellSyllableView.pickValue($0)) } },
            matches: sameFace)
    }
}

// MARK: - The suite

@Suite("the economy, end to end")
@MainActor
struct EconomyE2ETests {

    @Test("every exercise in the hub pays what its difficulty promises", arguments: Levels.exercises)
    func aPerfectRunPaysCurvePlusDifficulty(meta: ExerciseMeta) async {
        let world = economyWorld()
        await playCatalogRow(meta, level: 1, deps: world.deps, seed: 20_260_729)

        // A full-perfect run: `floor(perfect * difficulty / total) == difficulty`.
        let expected = meta.difficulty == .d0 ? 0 : Rewards.curve[0] + meta.difficulty.weight
        #expect(
            world.store.profile.balance == expected,
            Comment(
                rawValue: """
                    « \(meta.name) » (\(meta.id.rawValue), difficulty \(meta.difficulty.weight)) \
                    paid \(world.store.profile.balance), expected \(expected)
                    """))
        #expect(
            world.store.profile.ledger[Rewards.ledgerKey(exercise: meta.id, level: 1)] == 1,
            Comment(rawValue: "« \(meta.name) » never recorded the clear"))
    }

    @Test("the run this suite plays really is a full session, not an empty one")
    func theSessionsAreNotEmpty() async {
        // Guards the suite above against the way it could pass for free: a
        // builder returning [] sets `done` in the model's init, so `playPerfectly`
        // returns immediately — and a difficulty-0 row would still "pass".
        for meta in Levels.exercises {
            let world = economyWorld()
            await playCatalogRow(meta, level: 1, deps: world.deps, seed: 20_260_729)
            let calls = world.harness.award.calls
            #expect(
                calls.count == 1,
                Comment(rawValue: "« \(meta.name) » awarded \(calls.count) times"))
        }
    }
}
