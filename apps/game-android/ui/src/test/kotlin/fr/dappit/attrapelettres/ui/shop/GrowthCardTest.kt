package fr.dappit.attrapelettres.ui.shop

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.GROWTH_STAGES
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.ui.design.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// `shop/GrowthCard.kt` against `src/shop/GrowthCard.tsx`.
//
//   const growthPrice = (stage) => 30 * (stage + 1);
//   const atMax      = config.stage >= GROWTH_STAGES - 1;
//   const affordable = balance >= price;
//   const disabled   = atMax || !affordable;
//   const nextStage  = Math.min(config.stage + 1, GROWTH_STAGES - 1);
//   const grow = () => { if (atMax) return;
//                        if (spend(price)) { setConfig(stage + 1); onGrew(price) } };
//
// The two invariants this file exists to hold are asserted against a REAL
// `ProfileStore`, not a stub: invariant 8 (nothing outside `sessionReward` mints
// a point — so growing may only ever REDUCE the balance) and invariant 3 (a
// wallet that is one star short is a quiet no-op, not an error).
// ---------------------------------------------------------------------------

private fun world(): ProfileStore {
    val store = ProfileStore(
        kv = InMemoryKVStore(),
        difficultyOf = ::exerciseDifficulty,
        time = MutableTimeSource(1_700_000_000_000),
        device = { "this-phone" },
    )
    store.createChild("Léa")
    return store
}

/** Stars, earned the only way there are any: by finishing a run perfectly. */
private fun ProfileStore.earn(times: Int) {
    repeat(times) { award(ExerciseId.FIRST_LETTER, 1, perfectRounds = 6, totalRounds = 6) }
}

class GrowthPriceTest {

    /** `30 * (stage + 1)` — the price rises with maturity. */
    @Test
    fun `the price curve is thirty per stage, one-based`() {
        assertEquals(30, growthPrice(0))
        assertEquals(60, growthPrice(1))
        assertEquals(300, growthPrice(GROWTH_STAGES - 1))
        for (stage in 0 until GROWTH_STAGES) {
            assertEquals(30 * (stage + 1), growthPrice(stage))
        }
    }

    /** Monotone: growing never gets cheaper, so it stays a meaningful goal. */
    @Test
    fun `the price never falls`() {
        for (stage in 1 until GROWTH_STAGES) {
            assertTrue(growthPrice(stage) > growthPrice(stage - 1))
        }
    }
}

class GrowthCardSurfaceTest {

    @Test
    fun `a broke baby sees the price, the meter and no way to fail`() {
        val s = growthCardSurface(stage = 0, balance = 0)
        assertEquals(30, s.price)
        assertFalse(s.atMax)
        assertFalse(s.affordable)
        assertTrue(s.disabled)
        assertTrue(s.showsMeter)
        assertEquals("1/10", s.counter)
        assertEquals(1, s.filledPips)
        assertEquals(1, s.nextStage)
        assertEquals("pas encore · ⭐ 30", s.buttonLabel)
        assertEquals(
            "Pas encore assez de points pour grandir, il en faut 30",
            s.accessibilityLabel,
        )
    }

    /** `affordable = balance >= price` — exactly enough IS enough. */
    @Test
    fun `exactly the price is affordable`() {
        assertTrue(growthCardSurface(stage = 0, balance = 30).affordable)
        assertFalse(growthCardSurface(stage = 0, balance = 29).affordable)
        val s = growthCardSurface(stage = 0, balance = 30)
        assertFalse(s.disabled)
        assertFalse(s.showsMeter)
        assertEquals("Grandir · ⭐ 30", s.buttonLabel)
        assertEquals("Faire grandir pour 30 points", s.accessibilityLabel)
    }

    /**
     * At the top the button is disabled, says « Niveau max ✨ », and — unlike the
     * unaffordable state — shows NO savings meter: there is nothing left to save
     * for, and a bar filling toward an unreachable price would be a fail state
     * in disguise (invariant 3).
     */
    @Test
    fun `the top of the ladder is an achievement, not a lock`() {
        val s = growthCardSurface(stage = GROWTH_STAGES - 1, balance = 100_000)
        assertTrue(s.atMax)
        assertTrue(s.disabled)
        assertFalse(s.showsMeter)
        assertEquals(GROWTH_STAGES - 1, s.nextStage)
        assertEquals("10/10", s.counter)
        assertEquals(GROWTH_STAGES, s.filledPips)
        assertEquals("Niveau max ✨", s.buttonLabel)
        assertEquals("Niveau maximum atteint", s.accessibilityLabel)
    }

    /** `i <= config.stage` fills — so a stage-0 baby already has ONE pip lit. */
    @Test
    fun `pips are one-based and never overflow the row`() {
        for (stage in 0 until GROWTH_STAGES) {
            val s = growthCardSurface(stage, balance = 0)
            assertEquals(stage + 1, s.filledPips)
            assertTrue(s.filledPips in 1..GROWTH_STAGES)
            assertEquals(Copy.Shop.Growth.meter(stage + 1, GROWTH_STAGES), s.meterLabel)
        }
    }

    /** `Math.min(stage + 1, GROWTH_STAGES - 1)` — the peek never runs off the end. */
    @Test
    fun `the peek clamps at the last stage`() {
        assertEquals(1, growthCardSurface(0, 0).nextStage)
        assertEquals(9, growthCardSurface(8, 0).nextStage)
        assertEquals(9, growthCardSurface(9, 0).nextStage)
    }
}

class PerformGrowTest {

    /**
     * INVARIANT 3. A wallet one star short changes NOTHING: no stage, no
     * balance, no error. `spend` refuses and the refusal is silent.
     */
    @Test
    fun `an unaffordable grow is a quiet no-op`() {
        val store = world()
        val before = store.profile
        assertNull(performGrow(store))
        assertEquals(before.config.stage, store.profile.config.stage)
        assertEquals(before.balance, store.profile.balance)
    }

    /**
     * INVARIANT 8. Growing is the headline SPEND: it may only ever reduce the
     * balance, by exactly the authored price, and it mints nothing.
     */
    @Test
    fun `growing spends exactly the price and mints nothing`() {
        val store = world()
        store.earn(40)
        val before = store.profile
        val stage = before.config.stage
        val price = growthPrice(stage)
        assertTrue(before.balance >= price, "the fixture must be able to afford one stage")

        assertEquals(price, performGrow(store))
        assertEquals(stage + 1, store.profile.config.stage)
        assertEquals(before.balance - price, store.profile.balance)
    }

    /**
     * At the top, `if (atMax) return` fires BEFORE `spend`, so a rich child at
     * stage 9 is never charged for a stage that does not exist.
     */
    @Test
    fun `a maxed mascot is never charged`() {
        val store = world()
        store.earn(400)
        // Through the shipping choke point, not by writing the field.
        store.setConfig { c -> c.copy(stage = GROWTH_STAGES - 1) }
        val atTop = store.profile
        assertEquals(GROWTH_STAGES - 1, atTop.config.stage)
        assertTrue(atTop.balance > growthPrice(GROWTH_STAGES - 1), "the wallet must be full")

        assertNull(performGrow(store))
        assertEquals(atTop.balance, store.profile.balance)
        assertEquals(GROWTH_STAGES - 1, store.profile.config.stage)
    }

    /**
     * INVARIANT 8, AT THE ONE PLACE A PRICE IS COMPUTED RATHER THAN AUTHORED.
     *
     * Every other price the shop can hand `ProfileStore.spend` is a `cost` field
     * on an authored `CATALOG` row, so it is positive by inspection of a table.
     * `growthPrice` is not: it is `30 * (stage + 1)` over `MascotConfig.stage`,
     * which the loose decoder reads off disk with no range check (`stage =
     * src.stage?.looseCount`) — the same latitude the web's
     * `JSON.parse(localStorage…)` has.
     *
     * `spend()` does not reject a negative cost (inherited verbatim from
     * `useProfile.tsx`), and `spend(-90)` is not a refusal — it bumps the
     * `spent` counter by −90 and the folded balance goes UP by 90. That is a
     * MINT, outside `sessionReward`, reachable from a value the app never wrote
     * but can read.
     *
     * The clamp in `growthCardSurface` is what closes it, so this drives the
     * whole path — a stage nothing in the app can produce, straight through the
     * shipping spend — and asserts the wallet only ever goes down.
     */
    @Test
    fun `a stage read off disk that the app never wrote cannot mint a star`() {
        for (rogue in listOf(-1, -4, -300, GROWTH_STAGES + 5)) {
            val store = world()
            store.earn(40)
            store.setConfig { c -> c.copy(stage = rogue) }
            val before = store.profile.balance

            val paid = performGrow(store)

            assertTrue(
                paid == null || paid > 0,
                "stage $rogue handed spend() a price of $paid",
            )
            assertTrue(
                store.profile.balance <= before,
                "stage $rogue MINTED ${store.profile.balance - before} stars outside sessionReward",
            )
            // Only a stage that was actually BOUGHT is guaranteed in range.
            // An over-max rogue reads as `atMax` and is declined before `spend`,
            // so nothing writes it back — and `stageScale`'s own
            // `coerceIn(0, 9)` is what keeps it renderable. What must never
            // happen is a PURCHASE leaving the stage out of range.
            if (paid != null) {
                assertTrue(
                    store.profile.config.stage in 0 until GROWTH_STAGES,
                    "a purchase left the stage at ${store.profile.config.stage}",
                )
            }
        }
    }

    /** The clamp is invisible to every legitimate stage: 0..9 are untouched. */
    @Test
    fun `the clamp changes nothing a real profile can hold`() {
        for (stage in 0 until GROWTH_STAGES) {
            val s = growthCardSurface(stage, balance = 100_000)
            assertEquals(stage, s.stage)
            assertEquals(growthPrice(stage), s.price)
        }
    }
}
