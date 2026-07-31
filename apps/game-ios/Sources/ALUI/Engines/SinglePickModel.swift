import ALCore
import Observation

// The single-pick family — ONE loop for FirstLetter, FindSound, SyllableGrid,
// LetterMatch and ReadImage (engines.md §4.1). The five TSX files are the same
// component with different data; every difference between them travels in the
// descriptor below, never in a fork of the loop.
//
// Invariants owned here:
//   1  pick(_:) is synchronous: SFX, verdict, flash and the star grey all
//      happen inside the touchDown call, before any await.
//   3  a wrong tap mutates ONLY the cooldown and the round's star. No lock,
//      no route change, no mood change, no terminal state but `done`.
//   8  cooldown swallow (MissCooldown, strict `<`, 800 ms) with press+shake
//      still playing; the star greys on the FIRST wrong tap at pointerdown;
//      points enter exclusively through deps.award at the single finish
//      transition.

/// Everything that distinguishes one single-pick exercise from its siblings.
/// The five factory extensions below are the audit surface against the TSX.
public struct SinglePickDescriptor<Round> {
    public var exercise: ExerciseId
    public var level: Int
    /// The consigne line above the mascot; nil = the engine shows none.
    public var headline: String?
    /// `Finished`'s title, verbatim.
    public var finishedTitle: String
    /// The big 🔊 button's aria-label.
    public var listenAccessibilityLabel: String
    /// ReadImage's preview checks `locked`; the other four do not (the TSX
    /// asymmetry, ported deliberately — engines.md §4.1).
    public var previewGuardedByLock: Bool
    public var buildSession: (RandomSource) -> [Round]
    /// The judge AND flash key: letter / graphy / text / face.base / word.
    public var targetKey: (Round) -> String
    /// Spoken 350 ms after the round becomes current, and by replayPrompt().
    public var promptLine: (Round) -> String
    /// Spoken at rate 0.98 on the correct pick; the round advances only after
    /// it played to completion.
    public var successLine: (Round) -> String
    /// The big 🔊 button's visible text (FirstLetter varies it per level).
    public var listenText: (Round) -> String

    public init(
        exercise: ExerciseId,
        level: Int,
        headline: String?,
        finishedTitle: String,
        listenAccessibilityLabel: String,
        previewGuardedByLock: Bool,
        buildSession: @escaping (RandomSource) -> [Round],
        targetKey: @escaping (Round) -> String,
        promptLine: @escaping (Round) -> String,
        successLine: @escaping (Round) -> String,
        listenText: @escaping (Round) -> String
    ) {
        self.exercise = exercise
        self.level = level
        self.headline = headline
        self.finishedTitle = finishedTitle
        self.listenAccessibilityLabel = listenAccessibilityLabel
        self.previewGuardedByLock = previewGuardedByLock
        self.buildSession = buildSession
        self.targetKey = targetKey
        self.promptLine = promptLine
        self.successLine = successLine
        self.listenText = listenText
    }
}

@MainActor
@Observable
public final class SinglePickModel<Round> {
    @ObservationIgnored public let descriptor: SinglePickDescriptor<Round>
    /// Seeded ONCE, in this init — which is why the model must never be built
    /// in a view `init` or a `@State` default (D9).
    public let session: [Round]

    public private(set) var idx = 0
    public private(set) var mood: Mood = .idle
    /// The highlighted correct answer's key during the celebration; also the
    /// family's all-tiles-disabled flag (`disabled={flash != null}`).
    public private(set) var flash: String?
    public private(set) var done = false
    public private(set) var earned = 0
    public private(set) var stars: [Bool]

    /// TSX `locked` ref — true from the correct pick until its success line
    /// finished. Deliberately not observed: no view reads it (the TSX kept it
    /// out of state for the same reason).
    @ObservationIgnored private var locked = false
    /// TSX `mountedRef` — async continuations bail once false.
    @ObservationIgnored private var isActive = true
    @ObservationIgnored private let cooldown: MissCooldown
    @ObservationIgnored private let deps: EngineDeps
    @ObservationIgnored private var announceTask: Task<Void, Never>?

    public init(
        descriptor: SinglePickDescriptor<Round>,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) {
        self.descriptor = descriptor
        self.deps = deps
        self.cooldown = MissCooldown(time: deps.time)
        let session = descriptor.buildSession(rng)
        self.session = session
        self.stars = session.map { _ in true }
        // Unreachable with the shipped pools (every builder yields ≥ 1 round);
        // guarded so a hypothetical empty session shows Finished, not a trap.
        self.done = session.isEmpty
    }

    // MARK: - Projection surface

    /// The current round. `idx` stays on the last round after finish, so this
    /// is valid for as long as the session is non-empty.
    public var current: Round { session[idx] }

    /// `disabled={flash != null}` — during the celebration line every tile
    /// (and its preview) is disabled in this family.
    public var tilesDisabled: Bool { flash != nil }

    /// The big 🔊 button's visible text for the current round.
    public var listenText: String { descriptor.listenText(current) }

    /// The prompt line for the current round (LetterMatch shows it as the
    /// listen button's text; it is also what replayPrompt speaks).
    public var promptText: String { descriptor.promptLine(current) }

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

    public func pick(_ key: String) -> Verdict {
        if done { return .reject }  // unreachable from a correct view (Finished is shown)
        if locked { return .reject }
        if cooldown.isSwallowing { return .reject }  // silent while the shake plays
        deps.audio.unlock()
        deps.audio.pop()
        let round = session[idx]
        if key != descriptor.targetKey(round) {
            deps.audio.nudge()
            cooldown.registerMiss()
            StarStrip.miss(idx, in: &stars)  // the star greys NOW, same beat as the shake
            return .reject
        }
        locked = true
        // [DEVIATION, authorised] The round has been answered, so the pending
        // "here is the task" prompt is stale. The TSX does NOT cancel it, and a
        // correct tap inside the 350 ms window therefore let the prompt speak
        // over the success line: `say` returned `ok == false`, the guard below
        // dropped the advance, and the game stalled with `locked` still true —
        // silently, on the fastest children. See D45.
        announceTask?.cancel()
        flash = key
        mood = .happy
        deps.audio.success()
        deps.fireConfetti()
        // Advance only after the success line has played in full. `ok` is false
        // if it was cut short (child left, watchdog) — then we don't advance.
        let next = idx + 1
        let line = descriptor.successLine(round)
        Task { [weak self] in
            guard let self else { return }
            let ok = await self.deps.audio.say(
                line, rate: EngineLines.successRate, pitch: EngineLines.defaultPitch)
            guard ok, self.isActive else { return }
            self.flash = nil
            self.locked = false
            if next >= self.session.count {
                self.announceTask?.cancel()
                self.mood = .cheer
                self.earned = self.deps.award(
                    self.descriptor.exercise, self.descriptor.level,
                    self.stars.filter { $0 }.count, self.session.count)
                self.done = true
                let audio = self.deps.audio
                Task { _ = await audio.say(EngineLines.bravoFound) }
            } else {
                self.mood = .idle
                self.idx = next
                self.scheduleAnnounce()
            }
        }
        return .accept
    }

    // MARK: - Audio affordances

    /// The big 🔊 button. Guarded: never cuts the success line mid-celebration.
    /// (No `unlock()` here — the TSX buttons call `say` alone.)
    public func replayPrompt() {
        guard !locked, !done, !session.isEmpty else { return }
        let line = descriptor.promptLine(session[idx])
        let audio = deps.audio
        Task { _ = await audio.say(line) }
    }

    /// A tile's « Écouter » button. The view passes what the TSX speaks
    /// (letter / choice.sound / face.base / choice.word). Only ReadImage's is
    /// locked-guarded (ported asymmetry).
    public func preview(say text: String) {
        if descriptor.previewGuardedByLock, locked { return }
        deps.audio.unlock()
        let audio = deps.audio
        Task { _ = await audio.say(text) }
    }

    // MARK: - Announce (350 ms after every round becomes current)

    /// The TSX `useEffect` keyed on `idx`: a 350 ms timer, cancelled when the
    /// round advances, the run finishes, or the exercise disappears. It is NOT
    /// cancelled by a pick — ported as-is (see RoundRunner header).
    private func scheduleAnnounce() {
        announceTask?.cancel()
        guard !done, !session.isEmpty else { return }
        let expected = idx
        let line = descriptor.promptLine(session[expected])
        announceTask = Task { [weak self] in
            guard let self else { return }
            await self.deps.delay(EngineLines.announceDelayMs)
            guard !Task.isCancelled, self.isActive, !self.done, self.idx == expected
            else { return }
            _ = await self.deps.audio.say(line)
        }
    }
}

extension SinglePickModel: RoundRunner {
    public var totalRounds: Int { session.count }
    public var headline: String? { descriptor.headline }
    public var finishedTitle: String { descriptor.finishedTitle }
    public var listenAccessibilityLabel: String { descriptor.listenAccessibilityLabel }
}

// MARK: - The five concrete engines (1:1 with the TSX files)

extension SinglePickModel where Round == FirstLetterRound {
    /// `FirstLetterExercise.tsx`. Session: `Levels.buildFirstLetterSession`
    /// (adjudication 5 — the one builder that lived in the TSX). Levels 4–5
    /// drop the written word from the listen button (`level < count - 1`).
    public static func firstLetter(
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> SinglePickModel<FirstLetterRound> {
        let showWord = level < Levels.firstLetterLevels.count - 1
        return SinglePickModel(
            descriptor: SinglePickDescriptor(
                exercise: .firstLetter,
                level: level,
                headline: nil,
                finishedTitle: Copy.Finished.allFound,
                listenAccessibilityLabel: Copy.Exercise.repeatWord,
                previewGuardedByLock: false,
                buildSession: { Levels.buildFirstLetterSession(level: level, $0) },
                targetKey: { $0.target.letter },
                promptLine: { "Trouve la première lettre de \($0.target.word)." },
                successLine: { "Oui ! \($0.target.letter). \($0.target.word)." },
                listenText: { showWord ? "🔊 \($0.target.word)" : "🔊" }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension SinglePickModel where Round == FindSoundRound {
    /// `FindSoundExercise.tsx`. Preview speaks `choice.sound`, tiles show the
    /// graphy; a11y label per tile is `Copy.Exercise.soundTile` (« Son X »).
    public static func findSound(
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> SinglePickModel<FindSoundRound> {
        SinglePickModel(
            descriptor: SinglePickDescriptor(
                exercise: .findSound,
                level: level,
                // U+0027 apostrophe — a VO-adjacent string, never normalised (D17).
                headline: "Écoute le son et trouve comment il s'écrit",
                finishedTitle: Copy.Finished.allFound,
                listenAccessibilityLabel: Copy.Exercise.replaySound,
                previewGuardedByLock: false,
                buildSession: { Levels.buildFindSoundSession(level: level, $0) },
                targetKey: { $0.target.graphy },
                promptLine: { Levels.findSoundPrompt($0.target) },
                successLine: { Levels.findSoundSuccess($0.target) },
                listenText: { _ in Copy.Exercise.listen }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension SinglePickModel where Round == GridRound {
    /// `SyllableGridExercise.tsx` — ONE engine, two modes. Mode reaches only
    /// the session builder, the headline, and what a tile SHOWS (`vowel` mode
    /// renders `choice.vowel`); the pick key is `choice.text` in both.
    public static func syllableGrid(
        exercise: ExerciseId,
        mode: SyllableGridMode,
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> SinglePickModel<GridRound> {
        SinglePickModel(
            descriptor: SinglePickDescriptor(
                exercise: exercise,
                level: level,
                headline: Levels.gridConsigne[mode],
                finishedTitle: Copy.Finished.allRead,
                listenAccessibilityLabel: Copy.Exercise.replaySyllable,
                previewGuardedByLock: false,
                buildSession: { Levels.buildSyllableGridSession(level: level, mode: mode, $0) },
                targetKey: { $0.target.text },
                promptLine: { Levels.gridPrompt($0.target) },
                successLine: { Levels.gridSuccess($0.target) },
                listenText: { _ in Copy.Exercise.listen }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension SinglePickModel where Round == LetterMatchRound {
    /// `LetterMatchExercise.tsx`. The prompt line never names the letter (the
    /// child reads the shape); the success line does. The listen button shows
    /// the line itself (`🔊 {line}`).
    public static func letterMatch(
        exercise: ExerciseId,
        kind: LetterMatchKind,
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> SinglePickModel<LetterMatchRound> {
        SinglePickModel(
            descriptor: SinglePickDescriptor(
                exercise: exercise,
                level: level,
                headline: nil,
                finishedTitle: Copy.Finished.allFound,
                listenAccessibilityLabel: Copy.Exercise.repeatInstruction,
                previewGuardedByLock: false,
                buildSession: { Levels.buildLetterMatchSession(kind: kind, level: level, $0) },
                targetKey: { $0.prompt.base },
                promptLine: { Levels.letterMatchPrompt($0.prompt, $0.choices[0]) },
                successLine: { Levels.letterMatchSuccess($0.prompt.base) },
                listenText: { "🔊 \(Levels.letterMatchPrompt($0.prompt, $0.choices[0]))" }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension SinglePickModel where Round == ReadImageRound {
    /// `ReadImageExercise.tsx`. The word is printed, never spoken; the prompt
    /// is the constant consigne. The ONLY single-pick engine whose preview is
    /// locked-guarded.
    public static func readImage(
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> SinglePickModel<ReadImageRound> {
        SinglePickModel(
            descriptor: SinglePickDescriptor(
                exercise: .readImage,
                level: level,
                headline: "Lis le mot et touche la bonne image",
                finishedTitle: Copy.Finished.allFound,
                listenAccessibilityLabel: Copy.Exercise.repeatInstruction,
                previewGuardedByLock: true,
                buildSession: { Levels.buildReadImageSession(level: level, $0) },
                targetKey: { $0.target.word },
                promptLine: { _ in Levels.readImagePrompt },
                successLine: { "Oui ! \($0.target.word)." },
                listenText: { _ in "🔊 \(Levels.readImagePrompt)" }
            ),
            deps: deps,
            rng: rng
        )
    }
}
