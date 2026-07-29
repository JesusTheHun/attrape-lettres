import Foundation

import ALCore
@testable import ALUI

// Shared fakes for the three engine models. Values asserted in the suites are
// derived FROM THE TYPESCRIPT (the nine `src/exercises/*.tsx` files and
// `engines.md` §4), never read back out of the Swift implementation.
//
// Everything is driven cooperatively: the models are @MainActor, the tests are
// @MainActor, and `eventually` yields the main actor until a condition holds.
// No test ever sleeps; time is `MutableTimeSource`, the announce timer is a
// recorded `DelayProbe`, and `say()` resolution is scripted.

// MARK: - FakeAudio

/// Records every `AudioEngine` call in order. `say` resolves `true`
/// immediately unless a result is queued (`queueSayResult`) or the fake is
/// holding (`holdSays` + `resolveNextSay`), which lets a test assert state
/// *while a line plays*.
///
/// `stop()` records the event and deliberately does NOT settle pending says —
/// the real engine's settle-on-stop is the audio target's contract; here the
/// test scripts each resolution explicitly (that is how the `isActive` guard
/// is testable separately from the `ok` guard).
final class FakeAudio: AudioEngine, @unchecked Sendable {
    enum Event: Equatable {
        case unlock, pop, success, nudge, oops, stop
        case say(String, Double)  // text, rate
    }

    private let lock = NSLock()
    private var _events: [Event] = []
    private var _queued: [Bool] = []
    private var _hold = false
    private var _pending: [(text: String, cont: CheckedContinuation<Bool, Never>)] = []

    var events: [Event] { lock.withLock { _events } }
    var sayTexts: [String] {
        events.compactMap {
            if case .say(let text, _) = $0 { return text }
            return nil
        }
    }
    var pendingSayCount: Int { lock.withLock { _pending.count } }
    var pendingSayTexts: [String] { lock.withLock { _pending.map(\.text) } }

    /// The next `say` returns this immediately (FIFO across calls).
    func queueSayResult(_ ok: Bool) { lock.withLock { _queued.append(ok) } }

    /// Every unqueued `say` suspends until `resolveNextSay`.
    func holdSays() { lock.withLock { _hold = true } }

    func resolveNextSay(_ ok: Bool) {
        let cont: CheckedContinuation<Bool, Never>? = lock.withLock {
            _pending.isEmpty ? nil : _pending.removeFirst().cont
        }
        cont?.resume(returning: ok)
    }

    func clearEvents() { lock.withLock { _events = [] } }

    func unlock() { lock.withLock { _events.append(.unlock) } }
    func pop() { lock.withLock { _events.append(.pop) } }
    func success() { lock.withLock { _events.append(.success) } }
    func nudge() { lock.withLock { _events.append(.nudge) } }
    func oops() { lock.withLock { _events.append(.oops) } }
    func stop() { lock.withLock { _events.append(.stop) } }

    @discardableResult
    func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
        enum Outcome { case result(Bool), hold }
        let outcome: Outcome = lock.withLock {
            _events.append(.say(text, rate))
            if !_queued.isEmpty { return .result(_queued.removeFirst()) }
            return _hold ? .hold : .result(true)
        }
        switch outcome {
        case .result(let ok):
            return ok
        case .hold:
            return await withCheckedContinuation { cont in
                lock.withLock { _pending.append((text, cont)) }
            }
        }
    }
}

// MARK: - DelayProbe (the announce timer)

/// Records every requested announce delay (the TSX's `setTimeout(…, 350)`).
/// `.immediate` by default; `holdDelays()` suspends each request until
/// `releaseNext()`, which is how announce/advance races are scripted.
final class DelayProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [Int] = []
    private var _hold = false
    private var _pending: [CheckedContinuation<Void, Never>] = []

    var requests: [Int] { lock.withLock { _requests } }
    var pendingCount: Int { lock.withLock { _pending.count } }

    func holdDelays() { lock.withLock { _hold = true } }

    func releaseNext() {
        let cont: CheckedContinuation<Void, Never>? = lock.withLock {
            _pending.isEmpty ? nil : _pending.removeFirst()
        }
        cont?.resume()
    }

    func releaseAll() {
        let conts: [CheckedContinuation<Void, Never>] = lock.withLock {
            let p = _pending
            _pending = []
            return p
        }
        for cont in conts { cont.resume() }
    }

    func callAsFunction(_ ms: Int) async {
        let hold = lock.withLock {
            _requests.append(ms)
            return _hold
        }
        if hold {
            await withCheckedContinuation { cont in
                lock.withLock { _pending.append(cont) }
            }
        }
    }
}

// MARK: - Award spy + confetti counter

/// Records `award` calls; returns a canned value the model must display as-is
/// (invariant 8 — the model never computes or adjusts a point).
final class AwardSpy {
    private(set) var calls: [(exercise: ExerciseId, level: Int, perfect: Int, total: Int)] = []
    var result = 7

    func record(_ exercise: ExerciseId, _ level: Int, _ perfect: Int, _ total: Int) -> Int {
        calls.append((exercise, level, perfect, total))
        return result
    }
}

final class Counter {
    private(set) var count = 0
    func bump() { count += 1 }
}

// MARK: - Harness

/// One bundle of fakes + the `EngineDeps` wired to them. Time starts at
/// 1 000 ms so a cooldown window is never confused with the epoch.
final class EngineHarness {
    let audio = FakeAudio()
    let time = MutableTimeSource(1_000)
    let award = AwardSpy()
    let delays = DelayProbe()
    let confetti = Counter()

    var deps: EngineDeps {
        EngineDeps(
            audio: audio,
            time: time,
            award: { [award] in award.record($0, $1, $2, $3) },
            fireConfetti: { [confetti] in confetti.bump() },
            delay: { [delays] in await delays($0) }
        )
    }

    /// What a view's CALLER passes — `EngineDeps` minus `fireConfetti`, which
    /// only the view can supply (see `EngineHost`).
    var host: EngineHost {
        EngineHost(
            audio: audio,
            time: time,
            award: { [award] in award.record($0, $1, $2, $3) },
            delay: { [delays] in await delays($0) }
        )
    }
}

// MARK: - Cooperative waiting

/// Resolves every pending (and late-arriving) held `say` so a test that used
/// `holdSays()` leaves no suspended continuation behind.
@MainActor
func drainSays(_ audio: FakeAudio) async {
    for _ in 0..<50 {
        while audio.pendingSayCount > 0 { audio.resolveNextSay(true) }
        await Task.yield()
    }
}

/// Yields the main actor until `condition` holds (or a large yield budget runs
/// out — the test then fails on its own assertion, never hangs).
@MainActor
@discardableResult
func eventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<10_000 {
        if condition() { return true }
        await Task.yield()
    }
    return condition()
}
