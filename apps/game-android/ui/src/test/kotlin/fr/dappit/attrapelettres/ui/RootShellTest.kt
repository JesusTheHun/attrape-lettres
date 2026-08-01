package fr.dappit.attrapelettres.ui

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.AppRoute
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.licensing.Entitlement
import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.licensing.LicenseStore
import fr.dappit.attrapelettres.core.licensing.PurchaseStore
import fr.dappit.attrapelettres.core.licensing.StoreSnapshot
import fr.dappit.attrapelettres.core.licensing.canPlay
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.sync.SyncClient
import fr.dappit.attrapelettres.core.sync.SyncTransport
import fr.dappit.attrapelettres.core.sync.WireRoster
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.screens.OpenOutcome
import fr.dappit.attrapelettres.ui.screens.ShellGate
import fr.dappit.attrapelettres.ui.screens.openOutcome
import fr.dappit.attrapelettres.ui.screens.shellGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

// INVARIANT 11 AT THE COMPOSITION ROOT — money never fails closed.
//
// `EntitlementPropertyTest` in :core already proves the state machine: no store
// state and no clock value produces a lockout for a paid family. What it cannot
// prove is what the SHELL does with the answer, and the shell is where a
// six-year-old actually meets it. Two questions belong here and nowhere else:
//
//   1. Does any GATE consult the licence? It must not. An expired trial, a dead
//      store and a flat network all have to land the child on the hub, with the
//      mascot, the shop and every star exactly where they were.
//   2. Does the resume path — the one call that talks to a store and a network
//      on every foreground — have a failure mode that reaches the child? It
//      must not, including the failure `PurchaseStore`'s contract forbids and
//      an adapter could still commit: throwing.
//
// Nothing here composes (A11). Every value is read out of the same plain
// functions `RootView` calls.

// --- Stores that fail in every way a store can -------------------------------

/** Answers nothing: unreachable, refuses to sell, no price. */
private class DeadStore : PurchaseStore {
    override val available: Boolean = false
    override suspend fun refresh(): StoreSnapshot = StoreSnapshot.UNREACHABLE
    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = false
    override suspend fun restore(): Boolean = false
    override suspend fun priceLabel(): String? = null
}

/**
 * Breaks the interface's non-throwing contract. `PurchaseStore`'s KDoc calls
 * this out as the adapter's obligation rather than the compiler's, which means
 * it is exactly the thing that will one day be got wrong — a Play Billing
 * adapter that lets a `BillingClient` exception escape.
 */
private class ThrowingStore : PurchaseStore {
    override val available: Boolean = true
    override suspend fun refresh(): StoreSnapshot = error("billing exploded")
    override suspend fun beginTrial(): Long? = error("billing exploded")
    override suspend fun purchase(): Boolean = error("billing exploded")
    override suspend fun restore(): Boolean = error("billing exploded")
    override suspend fun priceLabel(): String? = error("billing exploded")
}

/** A family who paid, on a device that can still reach the store. */
private class PaidStore : PurchaseStore {
    override val available: Boolean = true
    override suspend fun refresh(): StoreSnapshot =
        StoreSnapshot(paid = true, trialStartedAt = null, reachable = true)

    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = true
    override suspend fun restore(): Boolean = true
    override suspend fun priceLabel(): String? = "9,99 €"
}

/** The store answered, out loud, that nothing is owned. The honest "no". */
private class UnownedStore : PurchaseStore {
    override val available: Boolean = true
    override suspend fun refresh(): StoreSnapshot =
        StoreSnapshot(paid = false, trialStartedAt = null, reachable = true)

    override suspend fun beginTrial(): Long? = null
    override suspend fun purchase(): Boolean = false
    override suspend fun restore(): Boolean = false
    override suspend fun priceLabel(): String? = null
}

private class ExplodingTransport : SyncTransport {
    override suspend fun exchange(payload: WireRoster): WireRoster = error("no network")
}

// --- The world ---------------------------------------------------------------

private class World(
    store: PurchaseStore,
    withSync: Boolean = false,
    onboard: Boolean = true,
) {
    val kv = InMemoryKVStore()
    val time = MutableTimeSource(1_700_000_000_000)
    val entitlement = EntitlementModel(store = store, persist = LicenseStore(kv), time = time)
    val profiles = ProfileStore(
        kv = kv,
        difficultyOf = ::exerciseDifficulty,
        time = time,
        device = { "test-device" },
        sync = if (withSync) SyncClient(ExplodingTransport()) else null,
    )

    init {
        // The gates a real family has already passed: the parent screen, the
        // roster AND the first-run picker. `createChild` alone leaves
        // `chosen = false`, which is `ShellGate.FirstRunPicker` — a real state,
        // but not the one these tests are about. The claim under test is that
        // MONEY never diverts a family who is past every gate, so the fixture
        // has to actually be past every gate.
        if (onboard) entitlement.beginTrial()
        profiles.createChild("Léa")
        profiles.chooseSpecies(Species.CAT)
    }

    /**
     * A SECOND model over the SAME persisted licence — the port of relaunching
     * the app, or of the store adapter changing its mind about being reachable.
     */
    fun relaunch(store: PurchaseStore): EntitlementModel =
        EntitlementModel(store = store, persist = LicenseStore(kv), time = time)

    /** The shell's answer for a stored route, with the live stores behind it. */
    fun gate(route: AppRoute = AppRoute.Hub, model: EntitlementModel = entitlement): ShellGate =
        shellGate(
            dev = null,
            onboarded = model.onboarded,
            activeId = profiles.activeId,
            chosen = profiles.profile.chosen,
            route = route,
        )
}

// --- The claim ---------------------------------------------------------------

class RootMoneyNeverFailsClosedTest {

    private val everyState: List<Entitlement> = listOf(
        Entitlement.Unknown,
        Entitlement.Trial(14, 0L),
        Entitlement.Trial(1, 0L),
        Entitlement.Trial(0, 0L),
        Entitlement.Paid,
        Entitlement.Expired,
    )

    @Test
    fun `no entitlement state gates the shell — every route reaches the screen`() {
        // The hub, the dashboard, the shop and the picker are reachable in EVERY
        // licence state, expired included. An expired trial takes nothing away
        // (invariant 3); it only refuses to start a NEW round.
        //
        // The structural half of the claim is `shellGate`'s SIGNATURE: it has no
        // entitlement parameter, so none of the six states below can even be
        // handed to it. Deleting that property is the regression this guards.
        for (route in listOf(AppRoute.Hub, AppRoute.Dashboard, AppRoute.Shop, AppRoute.Pick)) {
            assertEquals(
                ShellGate.Screen(route),
                shellGate(null, onboarded = true, activeId = "c1", chosen = true, route = route),
            )
        }
    }

    @Test
    fun `only EXPIRED ever diverts, over the whole catalog and every state`() {
        for (state in everyState) {
            val expectPaywall = state == Entitlement.Expired
            for (row in EXERCISES) {
                val outcome = openOutcome(row.id, 1, state)
                if (expectPaywall) {
                    assertEquals(OpenOutcome.Paywall, outcome, "${row.id.wire} under $state")
                } else {
                    assertEquals(
                        OpenOutcome.Play(row.id, 1),
                        outcome,
                        "${row.id.wire} was locked out under $state",
                    )
                }
            }
            assertEquals(!expectPaywall, canPlay(state))
        }
    }

    @Test
    fun `a dead store never locks anyone out, at boot or after a fortnight`() = runBlocking {
        val world = World(DeadStore())

        // Boot: the store says nothing, the local trial stamp carries the family.
        world.entitlement.refresh()
        assertTrue(canPlay(world.entitlement.entitlement))
        assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate())
        assertEquals(
            OpenOutcome.Play(ExerciseId.FIND_SOUND, 1),
            openOutcome(ExerciseId.FIND_SOUND, 1, world.entitlement.entitlement),
        )

        // Thirty days on the trial really is over — and the hub STILL renders.
        world.time.advance(30.0)
        world.entitlement.refresh()
        assertEquals(Entitlement.Expired, world.entitlement.entitlement)
        assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate())
        assertEquals(ShellGate.Screen(AppRoute.Dashboard), world.gate(AppRoute.Dashboard))
        assertEquals(
            OpenOutcome.Paywall,
            openOutcome(ExerciseId.FIND_SOUND, 1, world.entitlement.entitlement),
        )
    }

    @Test
    fun `a paid family stays paid through a store that stops answering`() = runBlocking {
        val world = World(PaidStore())
        world.entitlement.refresh()
        assertEquals(Entitlement.Paid, world.entitlement.entitlement)

        // The store goes dark. Every resume for the next fortnight — the offline
        // grace — must keep the family playing.
        val dark = world.relaunch(DeadStore())
        for (day in 1..14) {
            world.time.advance(1.0)
            dark.refresh()
            assertTrue(
                canPlay(dark.entitlement),
                "day $day of an unreachable store locked a paying family out",
            )
            assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate(model = dark))
        }
    }

    @Test
    fun `a store that THROWS on every call cannot lock a child out`() = runBlocking {
        val world = World(ThrowingStore(), withSync = true)

        // The resume path is where a throwing adapter would reach the app.
        val clean = resumeRefresh(world.profiles, world.entitlement)
        assertFalse(clean, "the probe did not actually exercise a failure")

        // …and the child is exactly where they were: on the hub, playable.
        assertTrue(canPlay(world.entitlement.entitlement))
        assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate())
        assertEquals(
            OpenOutcome.Play(ExerciseId.FIRST_LETTER, 1),
            openOutcome(ExerciseId.FIRST_LETTER, 1, world.entitlement.entitlement),
        )
    }

    @Test
    fun `a flat network on resume is a silent no-op, not a lockout`() = runBlocking {
        val world = World(DeadStore(), withSync = true)
        val before = world.profiles.profile.balance

        repeat(3) { resumeRefresh(world.profiles, world.entitlement) }

        assertEquals(before, world.profiles.profile.balance)
        assertTrue(canPlay(world.entitlement.entitlement))
        assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate())
    }

    @Test
    fun `an honest unowned answer still leaves the trial running`() = runBlocking {
        // The one case a naive adapter turns into a lockout: the store answered,
        // out loud, "nothing owned". That is not "expired" — the local trial
        // stamp is what decides, and it has 14 days on it.
        val world = World(UnownedStore())
        world.entitlement.refresh()
        assertTrue(canPlay(world.entitlement.entitlement))
        assertEquals(ShellGate.Screen(AppRoute.Hub), world.gate())
    }

    @Test
    fun `the paywall is never a startup wall — it is only ever routed to`() {
        // The gates cannot produce it: `shellGate` returns `Screen(route)` and
        // the only writers of `AppRoute.Paywall` are a blocked level tap and the
        // parent-facing trial chip. A fresh, un-onboarded device meets the
        // parent screen, whatever route was stored.
        val fresh = World(DeadStore(), onboard = false)
        assertEquals(ShellGate.Onboarding, fresh.gate())
        assertEquals(ShellGate.Onboarding, fresh.gate(AppRoute.Paywall))
    }
}

// --- The resume hook ---------------------------------------------------------

class RootResumeTest {

    @Test
    fun `resume re-checks the licence`() = runBlocking {
        val world = World(PaidStore())
        val at = world.entitlement.checkedAt
        world.time.advance(1.0)

        assertTrue(resumeRefresh(world.profiles, world.entitlement))
        assertTrue(world.entitlement.checkedAt > at, "the licence was not re-checked on resume")
        assertEquals(Entitlement.Paid, world.entitlement.entitlement)
    }

    @Test
    fun `resume is idempotent — three in a row cannot double a star`() = runBlocking {
        val world = World(DeadStore(), withSync = true)
        val earned = world.profiles.award(ExerciseId.FIRST_LETTER, 1, 4, 4)
        assertTrue(earned > 0, "the probe never earned anything")

        repeat(3) { resumeRefresh(world.profiles, world.entitlement) }
        assertEquals(earned, world.profiles.profile.balance)
    }

    @Test
    fun `the ticker is a plain counter, and it only goes up`() {
        val ticker = ChangeTicker()
        assertEquals(0, ticker.ticks)
        ticker.tick()
        ticker.tick()
        assertEquals(2, ticker.ticks)
    }
}

// --- The card gutter ---------------------------------------------------------

class RootShellFrameTest {

    @Test
    fun `the gutter is the MAX of the web's 16 dp and the safe area, never the sum`() {
        assertEquals(Shell.minimumInset, gutter(0.dp))
        assertEquals(Shell.minimumInset, gutter(8.dp))
        assertEquals(48.dp, gutter(48.dp))
        // The mistake this exists to prevent: 16 + 48 on a gesture-navigation
        // phone would eat a fifth of a 480 dp card.
        assertTrue(gutter(48.dp) < Shell.minimumInset + 48.dp)
    }
}
