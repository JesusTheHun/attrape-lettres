package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

// Shared fakes for the three engine models. Values asserted in the suites are
// derived FROM THE TYPESCRIPT (`apps/game-web/src/exercises/*.tsx`), with the
// Swift port as the worked example — never read back out of the Kotlin.
//
// NOTHING HERE SLEEPS, POLLS OR RACES, and that is the whole design.
//
// The iOS suites are `@MainActor` and wait cooperatively (`await eventually
// { … }`). A host JUnit run has no main actor and no frame clock, so the same
// determinism is bought differently: every coroutine the models launch is
// dispatched onto [QueueDispatcher], which just APPENDS the block to a list.
// Nothing runs until a test calls `pump()`, and then everything runs, in order,
// on the test thread. So a test can assert the state of the world at the exact
// moment a pick handler returned — which is what invariant 1 is about — and
// then advance the world one explicit step at a time.
//
// `kotlinx-coroutines-test` is deliberately NOT used: it is not on :ui's test
// classpath (A9 lists what is), and `runTest`'s virtual clock would give us
// nothing the queue does not, while making "did this happen synchronously?"
// harder to state rather than easier.

// --- The dispatcher ---------------------------------------------------------

/**
 * A dispatcher that queues every block and runs it only when asked. Single
 * threaded by construction, so none of the fakes below needs a lock.
 */
internal class QueueDispatcher : CoroutineDispatcher() {

    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    /** Runs queued blocks — and blocks they queue in turn — until none is left. */
    fun drain() {
        var guard = 0
        while (queue.isNotEmpty()) {
            guard += 1
            check(guard < 10_000) { "the engine queue never settled — a coroutine loop?" }
            queue.removeFirst().run()
        }
    }

    val pendingBlocks: Int get() = queue.size
}

// --- Audio ------------------------------------------------------------------

/** One recorded call on the audio seam. `say` carries its text and its rate. */
internal sealed interface AudioEvent {
    data object Unlock : AudioEvent
    data object Pop : AudioEvent
    data object Success : AudioEvent
    data object Nudge : AudioEvent
    data object Oops : AudioEvent
    data object Stop : AudioEvent
    data class Say(val text: String, val rate: Double) : AudioEvent
}

/**
 * Records every [AudioEngine] call in order.
 *
 * `say` resolves `true` immediately unless a result is queued
 * ([queueSayResult]) or the fake is holding ([holdSays] + [resolveNextSay]),
 * which is how a test asserts state *while a line plays* — the state the
 * engines spend most of their interesting time in.
 *
 * `stop()` records the event and deliberately does NOT settle pending says: the
 * real engine's settle-on-stop is the audio target's contract, and scripting
 * each resolution here is what makes the `mounted` guard testable separately
 * from the `ok` guard.
 */
internal class FakeAudio : AudioEngine {

    private val eventLog = mutableListOf<AudioEvent>()
    private val queued = ArrayDeque<Boolean>()
    private val pending = mutableListOf<Pair<String, CompletableDeferred<Boolean>>>()
    private var hold = false

    val events: List<AudioEvent> get() = eventLog.toList()

    val sayTexts: List<String>
        get() = eventLog.filterIsInstance<AudioEvent.Say>().map { it.text }

    val pendingSayCount: Int get() = pending.size
    val pendingSayTexts: List<String> get() = pending.map { it.first }

    /** The next `say` returns this immediately (FIFO across calls). */
    fun queueSayResult(ok: Boolean) {
        queued.addLast(ok)
    }

    /** Every unqueued `say` from now on suspends until [resolveNextSay]. */
    fun holdSays() {
        hold = true
    }

    fun resolveNextSay(ok: Boolean) {
        if (pending.isNotEmpty()) pending.removeAt(0).second.complete(ok)
    }

    /** Settles every held line, so a test leaves no suspended coroutine behind. */
    fun drainSays(ok: Boolean = true) {
        while (pending.isNotEmpty()) resolveNextSay(ok)
    }

    fun clearEvents() {
        eventLog.clear()
    }

    override fun unlock() {
        eventLog.add(AudioEvent.Unlock)
    }

    override fun pop() {
        eventLog.add(AudioEvent.Pop)
    }

    override fun success() {
        eventLog.add(AudioEvent.Success)
    }

    override fun nudge() {
        eventLog.add(AudioEvent.Nudge)
    }

    override fun oops() {
        eventLog.add(AudioEvent.Oops)
    }

    override fun stop() {
        eventLog.add(AudioEvent.Stop)
    }

    override suspend fun say(text: String, rate: Double, pitch: Double): Boolean {
        eventLog.add(AudioEvent.Say(text, rate))
        if (queued.isNotEmpty()) return queued.removeFirst()
        if (!hold) return true
        val settled = CompletableDeferred<Boolean>()
        pending.add(text to settled)
        return settled.await()
    }
}

// --- The announce timer -----------------------------------------------------

/**
 * Records every requested announce delay (the TSX's `setTimeout(…, 350)`).
 * Immediate by default; [holdDelays] suspends each request until
 * [releaseNext], which is how announce/advance races are scripted.
 */
internal class DelayProbe {

    private val requestLog = mutableListOf<Long>()
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    private var hold = false

    val requests: List<Long> get() = requestLog.toList()
    val pendingCount: Int get() = pending.size

    fun holdDelays() {
        hold = true
    }

    fun releaseNext() {
        if (pending.isNotEmpty()) pending.removeAt(0).complete(Unit)
    }

    fun releaseAll() {
        while (pending.isNotEmpty()) releaseNext()
    }

    suspend fun await(milliseconds: Long) {
        requestLog.add(milliseconds)
        if (!hold) return
        val settled = CompletableDeferred<Unit>()
        pending.add(settled)
        settled.await()
    }
}

// --- Award spy + confetti counter -------------------------------------------

/**
 * Records `award` calls and returns a canned value the model must display
 * as-is: invariant 8 says a model never computes or adjusts a point.
 */
internal class AwardSpy {

    data class Call(val exercise: ExerciseId, val level: Int, val perfect: Int, val total: Int)

    val calls = mutableListOf<Call>()
    var result = 7

    fun record(exercise: ExerciseId, level: Int, perfect: Int, total: Int): Int {
        calls.add(Call(exercise, level, perfect, total))
        return result
    }
}

internal class Counter {
    var count = 0
        private set

    fun bump() {
        count += 1
    }
}

// --- Harness ----------------------------------------------------------------

/**
 * One bundle of fakes plus the [EngineDeps] wired to them. Time starts at
 * 1 000 ms so a cooldown window is never confused with the epoch.
 */
internal class EngineHarness {

    val audio = FakeAudio()
    val time = MutableTimeSource(1_000)
    val award = AwardSpy()
    val delays = DelayProbe()
    val confetti = Counter()

    private val failures = mutableListOf<Throwable>()
    private val dispatcher = QueueDispatcher()

    /**
     * A supervisor scope, so one broken coroutine surfaces as a test failure in
     * [pump] instead of quietly cancelling every other pending line.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            dispatcher +
            CoroutineExceptionHandler { _, error -> failures.add(error) },
    )

    val deps = EngineDeps(
        audio = audio,
        time = time,
        award = { exercise, level, perfect, total ->
            award.record(exercise, level, perfect, total)
        },
        fireConfetti = { confetti.bump() },
        scope = scope,
        delay = { milliseconds -> delays.await(milliseconds) },
    )

    /**
     * What a screen's CALLER passes — [EngineDeps] minus the two members only
     * the screen can supply.
     */
    val host = EngineHost(
        audio = audio,
        time = time,
        award = { exercise, level, perfect, total ->
            award.record(exercise, level, perfect, total)
        },
        delay = { milliseconds -> delays.await(milliseconds) },
    )

    /** Run everything the models have queued, then surface any coroutine crash. */
    fun pump() {
        dispatcher.drain()
        failures.firstOrNull()?.let { throw it }
    }

    /**
     * Settle every held voice line and run what that unblocks, repeatedly —
     * the twin of iOS's `drainSays`. Used at the end of a test that held lines,
     * and to walk a full session in one call.
     */
    fun settle(ok: Boolean = true) {
        repeat(64) {
            audio.drainSays(ok)
            delays.releaseAll()
            pump()
            if (audio.pendingSayCount == 0 && delays.pendingCount == 0) return
        }
    }
}

// --- Shared assertions ------------------------------------------------------

/**
 * `kotlin.test`'s float overload wants a tolerance, and every view suite asserts
 * a `clamp()` triple, so the tolerance is spelled ONCE here rather than
 * file-privately in each. 1e-3 px: the authored numbers are CSS pixels, and no
 * clamp in the app is specified to a finer resolution than that.
 */
internal fun near(actual: Float, expected: Float, what: String) {
    kotlin.test.assertTrue(
        kotlin.math.abs(actual - expected) < 1e-3f,
        "$what: got $actual, expected $expected",
    )
}
