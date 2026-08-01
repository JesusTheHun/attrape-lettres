package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.rewards.REWARD_CURVE
import fr.dappit.attrapelettres.core.rewards.ledgerKey
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.screens.ExerciseEngine
import fr.dappit.attrapelettres.ui.screens.engineFor
import kotlin.test.Test
import kotlin.test.assertEquals

/* -------------------------------------------------------------------------- */
/* « I earn no point at the end of an exercise » — reported from a device.      */
/*                                                                             */
/* Every tier below this one already passes: `RewardsTest` proves the maths,    */
/* `ProfileStoreTest` proves the store, and the three model suites prove that a */
/* model hands its award callback the right counts. What NOTHING covers is the  */
/* whole chain in one piece — catalog row → the engine the hub dispatches to →  */
/* a real session over the real content → `ProfileStore.award` → the balance    */
/* the child sees. An exercise that quietly failed to pay would pass every one  */
/* of those suites.                                                            */
/*                                                                             */
/* So this suite plays EVERY row of `EXERCISES` to a full-perfect finish, with  */
/* `award` wired to a real `ProfileStore`, and asserts the balance. A perfect   */
/* run pays `curve[0] + difficulty` (invariant 8); difficulty 0 pays exactly    */
/* the curve and not a point more.                                             */
/*                                                                             */
/* It is deliberately end-to-end: it is the only test that would fail if an     */
/* engine stopped calling `award`, if a catalog row were dispatched to the      */
/* wrong engine, or if a session came back empty (which sets `done` in the      */
/* model's own construction and skips the finish transition entirely).         */
/*                                                                             */
/* AND IT DISPATCHES THROUGH THE ROUTER, not through a copy of it. See          */
/* `playCatalogRow` below — `engineFor` is the function `RootView` calls, so    */
/* "the wrong engine" above now means the app's wrong engine and not this       */
/* file's opinion of it.                                                       */
/* -------------------------------------------------------------------------- */

private class EconomyWorld {
    val harness = EngineHarness()
    val store = ProfileStore(
        kv = InMemoryKVStore(),
        difficultyOf = ::exerciseDifficulty,
        time = MutableTimeSource(1_700_000_000_000),
        device = { "this-phone" },
    )

    init {
        store.createChild("Léa")
    }

    /**
     * The ONE line under test that no other suite runs: the app's award
     * callback reaching the real store, and the model displaying what comes
     * back untouched.
     */
    val deps: EngineDeps = harness.deps.copy(
        award = { exercise, level, perfect, total ->
            harness.award.record(exercise, level, perfect, total)
            store.award(exercise, level, perfect, total)
        },
    )
}

// --- Playing a session perfectly, engine by engine ---------------------------

/**
 * The five single-pick exercises: the descriptor knows the answer, so a perfect
 * run is « tap `targetKey` once per round ».
 */
private fun <Round> playPerfectly(h: EngineHarness, model: SinglePickModel<Round>) {
    var rounds = 0
    while (!model.done && rounds < 200) {
        rounds += 1
        model.pick(model.descriptor.targetKey(model.current))
        h.pump()
    }
}

/** Twins: tap every tile of the family, none of the intruders. */
private fun playTwinsPerfectly(h: EngineHarness, model: TwinsModel) {
    var rounds = 0
    while (!model.done && rounds < 200) {
        rounds += 1
        for (tile in model.targets) {
            if (!model.found.contains(tile.id)) model.pick(tile)
        }
        h.pump()
    }
}

/**
 * The three assembly engines. `answers` is the round's target row and `tray`
 * its tiles; the model always fills the first empty slot, so the row is built
 * left to right with whichever unused tile matches.
 */
private fun <Item, Round, Slot : Any> playAssembly(
    h: EngineHarness,
    model: AssemblyModel<Item, Round, Slot>,
    answers: (Round) -> List<Slot>,
    tray: (Round) -> List<Pair<Int, Slot>>,
    matches: (Slot, Slot) -> Boolean,
) {
    var rounds = 0
    while (!model.done && rounds < 200) {
        rounds += 1
        val want = answers(model.round)
        val tiles = tray(model.round)
        var fills = 0
        while (fills < 50) {
            val slot = model.slots.indexOfFirst { it == null }
            if (slot < 0 || slot >= want.size) break
            val tile = tiles.firstOrNull {
                matches(it.second, want[slot]) && !model.isTrayTileUsed(it.first)
            } ?: break
            model.pick(tile.first, tile.second)
            fills += 1
        }
        h.pump()
    }
}

/**
 * Plays one catalog row through THE SHIPPING DISPATCH.
 *
 * This function used to hold its own hand-written copy of `App.tsx`'s if-chain,
 * because no router existed yet. That copy was the weak link in the whole
 * suite: it proved that every row banks under the right `ledgerKey` *given the
 * dispatch this file believed in*, and nothing forced the app to believe the
 * same thing. A row that quietly moved engine would have kept this suite green
 * while a child's stars landed under another key — silently, permanently, and
 * invisibly to every other test.
 *
 * So the copy is gone. `engineFor` is `ui/screens/Router.kt`'s function, the one
 * `RootView` calls to choose a composable, and the `when` below is only the
 * mapping from an engine to the model that engine renders. `RouterDispatchTest`
 * pins `engineFor` itself against the TypeScript, row by row; this suite now
 * inherits that instead of restating it.
 */
private fun playCatalogRow(world: EconomyWorld, meta: ExerciseMeta, level: Int, seed: Long) {
    val h = world.harness
    val deps = world.deps
    val rng: RandomSource = SeededGenerator(seed)
    when (val engine = engineFor(meta)) {
        ExerciseEngine.ReadImage ->
            playPerfectly(h, SinglePickModel.readImage(level, deps, rng))

        ExerciseEngine.SpellSound -> playAssembly(
            h = h,
            model = AssemblyModel.spellSound(level, deps, rng),
            answers = { it.target.spelling },
            tray = { round -> round.tray.map { it.id to it.letter } },
            matches = { a, b -> a == b },
        )

        ExerciseEngine.FindSound ->
            playPerfectly(h, SinglePickModel.findSound(level, deps, rng))

        ExerciseEngine.SoundTwins ->
            playTwinsPerfectly(h, TwinsModel(level, deps, rng))

        is ExerciseEngine.SyllableGrid ->
            playPerfectly(h, SinglePickModel.syllableGrid(meta.id, engine.mode, level, deps, rng))

        is ExerciseEngine.SpellSyllable -> playAssembly(
            h = h,
            model = AssemblyModel.spellSyllable(
                exercise = meta.id,
                mode = engine.mode,
                level = level,
                mixed = engine.mixed,
                deps = deps,
                rng = rng,
            ),
            answers = { it.answerFaces },
            tray = { round -> round.tray.map { it.id to spellTileFace(it) } },
            matches = ::sameFace,
        )

        is ExerciseEngine.LetterMatch ->
            playPerfectly(h, SinglePickModel.letterMatch(meta.id, engine.kind, level, deps, rng))

        is ExerciseEngine.Assemble -> playAssembly(
            h = h,
            model = AssemblyModel.assemble(meta.id, engine.mode, level, deps, rng),
            answers = { it.word.syllables },
            tray = { round -> round.tray.map { it.id to it.syllable } },
            matches = { a, b -> a == b },
        )

        ExerciseEngine.FirstLetter ->
            playPerfectly(h, SinglePickModel.firstLetter(level, deps, rng))
    }
}

private const val SEED = 20_260_729L

class EconomyE2ETest {

    @Test
    fun `every exercise in the hub pays what its difficulty promises`() {
        for (meta in EXERCISES) {
            val world = EconomyWorld()
            playCatalogRow(world, meta, level = 1, seed = SEED)

            // A full-perfect run: `perfect * difficulty / total == difficulty`,
            // on top of the curve EVERY row pays — training rows included,
            // whose weight is 0 and whose bonus is therefore nothing.
            val expected = REWARD_CURVE[0] + meta.difficulty.weight
            assertEquals(
                expected,
                world.store.profile.balance,
                "« ${meta.name} » (${meta.id.wire}, difficulty ${meta.difficulty.weight}) " +
                    "paid ${world.store.profile.balance}, expected $expected",
            )
            assertEquals(
                1,
                world.store.profile.ledger[ledgerKey(meta.id, 1)],
                "« ${meta.name} » never recorded the clear",
            )
        }
    }

    @Test
    fun `the runs this suite plays really are full sessions, not empty ones`() {
        // Guards the test above against the way it could pass for free: a
        // builder returning an empty list would leave `playPerfectly` returning
        // immediately, and a difficulty-0 row would still "pass" if the curve
        // were also skipped. Exactly one award per row is the proof.
        for (meta in EXERCISES) {
            val world = EconomyWorld()
            playCatalogRow(world, meta, level = 1, seed = SEED)
            assertEquals(
                1,
                world.harness.award.calls.size,
                "« ${meta.name} » awarded ${world.harness.award.calls.size} times",
            )
            assertEquals(
                world.harness.award.calls[0].total,
                world.harness.award.calls[0].perfect,
                "« ${meta.name} » was not played perfectly — the run is not a valid probe",
            )
        }
    }
}
