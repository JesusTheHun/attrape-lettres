import ALCore
import Observation

// The multi-select twins engine — `SoundTwinsExercise.tsx` (engines.md §4.3).
//
// Hear one sound (« Trouve tous les ko ! »), tap EVERY tile that writes it.
// A correct partial tap locks its tile into the collection strip and speaks
// its anchor word FIRE-AND-FORGET (the next tap can land while the line
// plays); the COMPLETING tap locks the round and awaits its line before
// advancing. An intruder tap follows the single-pick miss rules: nudge +
// cooldown + the round's star greys at pointerdown. Partial finds never grey
// the star and never change the strip — only intruders do.

@MainActor
@Observable
public final class TwinsModel {
    @ObservationIgnored public let level: Int
    /// Seeded ONCE, in this init (D9 — never build the model in a view `init`
    /// or a `@State` default).
    public let session: [TwinRound]

    public private(set) var idx = 0
    /// This round's found tile ids, in tap order — fills the collection strip.
    public private(set) var found: [Int] = []
    public private(set) var mood: Mood = .idle
    public private(set) var done = false
    public private(set) var earned = 0
    public private(set) var stars: [Bool]

    @ObservationIgnored private var locked = false
    @ObservationIgnored private var isActive = true
    @ObservationIgnored private let cooldown: MissCooldown
    @ObservationIgnored private let deps: EngineDeps
    @ObservationIgnored private var announceTask: Task<Void, Never>?

    public init(
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) {
        self.level = level
        self.deps = deps
        self.cooldown = MissCooldown(time: deps.time)
        let session = Levels.buildTwinSession(level: level, rng)
        self.session = session
        self.stars = session.map { _ in true }
        self.done = session.isEmpty
    }

    // MARK: - Projection surface

    public var round: TwinRound { session[idx] }

    /// The twins to find — one collection-strip slot each.
    public var targets: [TwinTile] { round.tiles.filter(\.correct) }

    /// Every target found — the round is celebrating (or about to advance).
    public var complete: Bool {
        let found = self.found
        return targets.allSatisfy { found.contains($0.id) }
    }

    /// A found tile locks; a COMPLETE round locks the rest too, so nothing
    /// shakes while the celebration line plays out.
    public func tileDisabled(_ tile: TwinTile) -> Bool {
        found.contains(tile.id) || complete
    }

    /// Found tiles wear the green ring.
    public func tileHighlighted(_ tile: TwinTile) -> Bool {
        found.contains(tile.id)
    }

    /// Collection strip slot `i`: the i-th found tile, or nil (dashed box).
    public func foundTile(at i: Int) -> TwinTile? {
        guard found.indices.contains(i) else { return nil }
        let id = found[i]
        return round.tiles.first { $0.id == id }
    }

    // MARK: - Lifecycle

    public func activate() {
        isActive = true
        deps.audio.unlock()
        scheduleAnnounce()
    }

    public func deactivate() {
        announceTask?.cancel()
        announceTask = nil
        isActive = false
        deps.audio.stop()
    }

    // MARK: - The pick handler (synchronous — invariant 1)

    public func pick(_ tile: TwinTile) -> Verdict {
        if done { return .reject }  // unreachable from a correct view
        if locked { return .reject }
        if cooldown.isSwallowing { return .reject }  // silent while the shake plays
        deps.audio.unlock()
        deps.audio.pop()
        if !tile.correct {
            deps.audio.nudge()
            cooldown.registerMiss()
            StarStrip.miss(idx, in: &stars)  // the star greys NOW, same beat as the shake
            return .reject
        }
        found.append(tile.id)
        mood = .happy
        // The anchor word IS the feedback, per graphy.
        let line = Levels.twinSuccess(
            TwinGraphy(text: tile.text, word: tile.word, emoji: tile.emoji))
        if !complete {
            // A twin found, more to go — fire-and-forget; the next tap can
            // land while this line plays (say is latest-wins).
            let audio = deps.audio
            Task {
                _ = await audio.say(
                    line, rate: EngineLines.successRate, pitch: EngineLines.defaultPitch)
            }
            return .accept
        }
        locked = true
        deps.audio.success()
        deps.fireConfetti()
        // Advance only after the LAST success line played in full (`ok`).
        let next = idx + 1
        Task { [weak self] in
            guard let self else { return }
            let ok = await self.deps.audio.say(
                line, rate: EngineLines.successRate, pitch: EngineLines.defaultPitch)
            guard ok, self.isActive else { return }
            self.locked = false
            if next >= self.session.count {
                self.announceTask?.cancel()
                self.mood = .cheer
                self.earned = self.deps.award(
                    .soundTwins, self.level, self.stars.filter { $0 }.count, self.session.count)
                self.done = true
                let audio = self.deps.audio
                Task { _ = await audio.say(EngineLines.bravoFound) }
            } else {
                self.mood = .idle
                self.found = []
                self.idx = next
                self.scheduleAnnounce()
            }
        }
        return .accept
    }

    // MARK: - Audio affordances

    /// The big 🔊 button — locked-guarded, no unlock (TSX shape).
    public func replayPrompt() {
        guard !locked, !done, !session.isEmpty else { return }
        let line = Levels.twinPrompt(session[idx].family)
        let audio = deps.audio
        Task { _ = await audio.say(line) }
    }

    /// A tile's « Écouter » — speaks the tile's own family's sound
    /// (`tile.sound`); locked-guarded in the TSX.
    public func preview(say text: String) {
        guard !locked else { return }
        deps.audio.unlock()
        let audio = deps.audio
        Task { _ = await audio.say(text) }
    }

    // MARK: - Announce (350 ms after every round becomes current)

    private func scheduleAnnounce() {
        announceTask?.cancel()
        guard !done, !session.isEmpty else { return }
        let expected = idx
        let line = Levels.twinPrompt(session[expected].family)
        announceTask = Task { [weak self] in
            guard let self else { return }
            await self.deps.delay(EngineLines.announceDelayMs)
            guard !Task.isCancelled, self.isActive, !self.done, self.idx == expected
            else { return }
            _ = await self.deps.audio.say(line)
        }
    }
}

extension TwinsModel: RoundRunner {
    public var totalRounds: Int { session.count }
    /// U+0027 apostrophe + U+2014 em dash — never normalised (D17).
    public var headline: String? {
        "Un son peut s'écrire de plusieurs façons — trouve-les toutes !"
    }
    public var finishedTitle: String { Copy.Finished.allFound }
    public var listenAccessibilityLabel: String { Copy.Exercise.replaySound }
}
