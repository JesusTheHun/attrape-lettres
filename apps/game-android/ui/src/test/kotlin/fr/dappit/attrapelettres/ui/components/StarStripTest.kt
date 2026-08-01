package fr.dappit.attrapelettres.ui.components

import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.platform.TimeSource
import fr.dappit.attrapelettres.core.rewards.MISS_COOLDOWN_MS
import fr.dappit.attrapelettres.ui.interaction.TouchDownCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The star strip — invariant 8's visible half, and ARCHITECTURE section 3's
// named proof for row 8.
//
// The strip classifier is asserted against `src/components/GameFrame.tsx`'s
// three-branch render; `miss` against the `missRound` callback every exercise
// declares; and the timing — the thing that actually carries the invariant —
// against the pointer-down path in `ui/interaction/TouchDown.kt`.
//
// Why the timing is the part that matters. Invariant 8 says spam has a VISIBLE
// cost in a game that deliberately has no fail state: the child sees the star go
// grey at the instant of the wrong tap, not at the recap, not on the lift, not
// after a round ends. Move the greying anywhere else and the strip stops
// teaching anything — a six-year-old cannot connect a consequence to a cause a
// second and a half later.

/**
 * The single-pick engine loop, reduced to what invariant 8 touches: the
 * celebration lock, the post-miss swallow window, and the star.
 *
 * The twin of iOS's `SinglePickHarness`. It is a test double for the ENGINE, not
 * for the strip — the strip is pure and needs no double.
 */
private class StripPickHarness(
    rounds: Int,
    private val target: String,
    private val time: TimeSource,
) {
    val stars: MutableList<Boolean> = MutableList(rounds) { true }
    var idx = 0
    var flash: String? = null
    var locked = false
    val audio = mutableListOf<String>()
    private var coolUntil = 0L

    /** TSX `disabled={flash != null}` — the celebration lock, never a verdict. */
    val tileDisabled: Boolean get() = flash != null

    fun pick(key: String): Verdict {
        if (locked) return Verdict.REJECT
        if (time.nowMillis < coolUntil) return Verdict.REJECT
        audio += "pop"
        if (key != target) {
            audio += "nudge"
            coolUntil = time.nowMillis + MISS_COOLDOWN_MS
            StarStrip.miss(idx, stars) // the star greys NOW
            return Verdict.REJECT
        }
        locked = true
        flash = key
        audio += "success"
        return Verdict.ACCEPT
    }
}

class StarStripCellsTest {

    @Test
    fun `star size is 20 dp, dropping to 16 dp only past 9 rounds`() {
        // TSX: const fontSize = total > 9 ? 16 : 20;
        assertEquals(20f, StarStrip.fontSize(5).value)
        assertEquals(20f, StarStrip.fontSize(9).value)
        assertEquals(16f, StarStrip.fontSize(10).value)
        assertEquals(16f, StarStrip.fontSize(12).value)
    }

    @Test
    fun `mid-run, played rounds keep their verdict and the live round pulses`() {
        // done = 2, one miss in round 1: i=0 earned, i=1 lost, i=2 live, then dots.
        val cells = StarStrip.cells(done = 2, total = 5, stars = listOf(true, false, true, true, true))
        assertEquals(
            listOf(StarCell.EARNED, StarCell.LOST, StarCell.LIVE, StarCell.PENDING, StarCell.PENDING),
            cells,
        )
    }

    @Test
    fun `the live round's star reads lost the instant its flag flips`() {
        // The `i == done && !stars[i]` branch: the round is still being played,
        // and the star already reads as gone. That branch IS invariant 8's
        // feedback — without it the child would see the loss only at the recap.
        val cells = StarStrip.cells(done = 2, total = 5, stars = listOf(true, false, false, true, true))
        assertEquals(
            listOf(StarCell.EARNED, StarCell.LOST, StarCell.LOST, StarCell.PENDING, StarCell.PENDING),
            cells,
        )
    }

    @Test
    fun `a fresh run pulses its first star and dots the rest`() {
        assertEquals(
            listOf(StarCell.LIVE, StarCell.PENDING, StarCell.PENDING),
            StarStrip.cells(done = 0, total = 3, stars = listOf(true, true, true)),
        )
    }

    @Test
    fun `a finished run is verdicts only — no live star, no dots`() {
        // Engines pass done = total once finished.
        assertEquals(
            listOf(StarCell.EARNED, StarCell.LOST, StarCell.EARNED),
            StarStrip.cells(done = 3, total = 3, stars = listOf(true, false, true)),
        )
    }

    @Test
    fun `a missing flag is falsy, as JS undefined — never read as earned`() {
        assertEquals(
            listOf(StarCell.LOST, StarCell.LOST, StarCell.PENDING),
            StarStrip.cells(done = 1, total = 3, stars = emptyList()),
        )
    }

    @Test
    fun `zero rounds render an empty strip`() {
        assertEquals(emptyList(), StarStrip.cells(done = 0, total = 0, stars = emptyList()))
        assertEquals(emptyList(), StarStrip.cells(done = 0, total = -3, stars = emptyList()))
    }

    @Test
    fun `kept is the count sessionReward is paid on`() {
        assertEquals(2, StarStrip.kept(listOf(true, false, true)))
        assertEquals(0, StarStrip.kept(listOf(false, false)))
        assertEquals(0, StarStrip.kept(emptyList()))
    }
}

class StarStripMissTest {

    @Test
    fun `the first wrong tap greys the round's star — a second changes nothing`() {
        val stars = mutableListOf(true, true, true)
        assertTrue(StarStrip.miss(1, stars))
        assertEquals(listOf(true, false, true), stars)
        // TSX: if (!starsRef.current[i]) return — spam cannot compound a
        // penalty that is already paid (invariants 3 and 8).
        assertFalse(StarStrip.miss(1, stars))
        assertEquals(listOf(true, false, true), stars)
    }

    @Test
    fun `out of range is a no-op — JS short-circuits on undefined`() {
        val stars = mutableListOf(true, true)
        assertFalse(StarStrip.miss(5, stars))
        assertFalse(StarStrip.miss(-1, stars))
        assertEquals(listOf(true, true), stars)
    }
}

class StarStripPointerDownTest {

    /**
     * INVARIANT 8, the timing half.
     *
     * The strip is sampled from INSIDE the pointer-down callback — the same
     * callback `Modifier.touchDown` invokes synchronously inside the pointer
     * dispatch, before measure, before draw, and above all before any
     * recomposition. If the greying were moved behind a state update, a
     * coroutine, a `LaunchedEffect` or the lift, the list captured here would
     * still read LIVE.
     */
    @Test
    fun `the star greys inside the pointer-down handler, before the finger lifts`() {
        val time = MutableTimeSource(1_000)
        val harness = StripPickHarness(rounds = 4, target = "ou", time = time)
        var cellsDuringDown: List<StarCell>? = null

        val core = TouchDownCore(
            onDown = {
                harness.pick("an") // wrong
                cellsDuringDown = StarStrip.cells(harness.idx, 4, harness.stars)
            },
            onUp = { },
        )

        // Round 0 is live and winnable before the tap…
        assertEquals(StarCell.LIVE, StarStrip.cells(harness.idx, 4, harness.stars).first())

        core.began()

        // …and read LOST while the finger was still down.
        assertEquals(
            listOf(StarCell.LOST, StarCell.PENDING, StarCell.PENDING, StarCell.PENDING),
            cellsDuringDown,
        )
        assertTrue(core.isTracking, "the greying must not have needed the lift")
        assertEquals(listOf("pop", "nudge"), harness.audio)
    }

    @Test
    fun `the lift changes nothing — the whole verdict landed at down`() {
        val time = MutableTimeSource(1_000)
        val harness = StripPickHarness(rounds = 3, target = "ou", time = time)
        val core = TouchDownCore(onDown = { harness.pick("an") }, onUp = { })

        core.began()
        val afterDown = harness.stars.toList()
        core.ended(inside = true)

        assertEquals(listOf(false, true, true), afterDown)
        assertEquals(afterDown, harness.stars.toList())
    }

    @Test
    fun `a second wrong tap inside the swallow window costs nothing more`() {
        // The three mechanisms interlocking: the cooldown swallows the pick, and
        // even once it expires the already-grey star cannot be greyed twice. A
        // child who machine-guns the grid pays exactly one star for the round.
        val time = MutableTimeSource(1_000)
        val harness = StripPickHarness(rounds = 4, target = "ou", time = time)

        TouchDownCore(onDown = { harness.pick("an") }, onUp = { }).began()
        val cells = StarStrip.cells(harness.idx, 4, harness.stars)

        // Inside the window: swallowed, silently.
        TouchDownCore(onDown = { harness.pick("in") }, onUp = { }).began()
        assertEquals(listOf("pop", "nudge"), harness.audio)
        assertEquals(cells, StarStrip.cells(harness.idx, 4, harness.stars))

        // Past it: heard again, and still no second star to lose.
        time.advance(MISS_COOLDOWN_MS + 1)
        TouchDownCore(onDown = { harness.pick("in") }, onUp = { }).began()
        assertEquals(listOf("pop", "nudge", "pop", "nudge"), harness.audio)
        assertEquals(cells, StarStrip.cells(harness.idx, 4, harness.stars))
        assertEquals(3, StarStrip.kept(harness.stars))
    }

    @Test
    fun `a miss locks nothing — invariant 3 holds through the strip`() {
        val time = MutableTimeSource(1_000)
        val harness = StripPickHarness(rounds = 3, target = "ou", time = time)

        TouchDownCore(onDown = { harness.pick("an") }, onUp = { }).began()
        assertFalse(harness.locked, "a wrong tap must not lock anything")
        assertFalse(harness.tileDisabled, "a wrong tap must not disable a tile")
        assertEquals(0, harness.idx, "a wrong tap must not advance the run")

        // …and the right one still lands, in the same round, once the window is out.
        time.advance(MISS_COOLDOWN_MS + 1)
        assertEquals(Verdict.ACCEPT, harness.pick("ou"))
    }
}
