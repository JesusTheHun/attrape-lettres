import ALCore
import Observation

// The assembly family — ONE loop for Assemble (fill-blank / order /
// order-distractor), SpellSound and SpellSyllable (engines.md §4.2).
//
// The loop: tiles drop into the first empty slot with NO per-tap judgement;
// only a COMPLETE row is judged. A right row celebrates and advances; a wrong
// row plays `oops` + « Oh non ! On recommence. » and wipes back to the seeded
// slots. A filled, non-pre-revealed slot can be tapped to send its tile home.
//
// Deliberate asymmetries, ported as-is (engines.md §7.5–7.6, §5.5):
//   - NO miss cooldown. Pacing on a wrong row is `locked = true` for the full
//     duration of the awaited « Oh non » line; taps during it are silent
//     rejects. Do not add a cooldown "for symmetry".
//   - The wrong-row reset IGNORES the say() result (resets even on a cut
//     line); every ADVANCE path requires `ok == true`.
//   - Round 0 announces 350 ms after mount; every later round announces
//     immediately inside loadRound.

/// Everything that distinguishes one assembly exercise from its siblings.
/// `Item` = the session element, `Round` = the built round, `Slot` = what a
/// slot holds (String for syllables/letters, LetterFace for SpellSyllable).
public struct AssemblyDescriptor<Item, Round, Slot> {
    public var exercise: ExerciseId
    public var level: Int
    public var headline: String
    public var finishedTitle: String
    public var listenAccessibilityLabel: String
    public var buildSession: (RandomSource) -> [Item]
    public var buildRound: (Item, RandomSource) -> Round
    /// Spoken 350 ms after mount (round 0) and immediately on every advance;
    /// also what replayPrompt speaks.
    public var promptLine: (Item) -> String
    /// Spoken at rate 0.98 when the row judges right.
    public var successLine: (Round) -> String
    /// The slots as the round seeds them — fill-blank's pre-revealed syllables
    /// stay filled; everything else is all-nil. Also the wrong-row reset state.
    public var seededSlots: (Round) -> [Slot?]
    /// Pre-revealed (non-interactive) slots — Assemble fill-blank only.
    public var lockedSlots: (Round) -> [Bool]
    /// Whole-row judgement over the filled values, in slot order.
    public var judge: (Round, [Slot]) -> Bool

    public init(
        exercise: ExerciseId,
        level: Int,
        headline: String,
        finishedTitle: String,
        listenAccessibilityLabel: String,
        buildSession: @escaping (RandomSource) -> [Item],
        buildRound: @escaping (Item, RandomSource) -> Round,
        promptLine: @escaping (Item) -> String,
        successLine: @escaping (Round) -> String,
        seededSlots: @escaping (Round) -> [Slot?],
        lockedSlots: @escaping (Round) -> [Bool],
        judge: @escaping (Round, [Slot]) -> Bool
    ) {
        self.exercise = exercise
        self.level = level
        self.headline = headline
        self.finishedTitle = finishedTitle
        self.listenAccessibilityLabel = listenAccessibilityLabel
        self.buildSession = buildSession
        self.buildRound = buildRound
        self.promptLine = promptLine
        self.successLine = successLine
        self.seededSlots = seededSlots
        self.lockedSlots = lockedSlots
        self.judge = judge
    }
}

/// `sameFace` from `SpellSyllableExercise.tsx`, verbatim: two faces are the
/// same tile iff they RENDER identically — glyph + script, `base` excluded.
/// Deliberately not `==` on `LetterFace` (which compares `base` too); ALCore
/// exposes no equivalent, so it lives with its only consumer.
public func sameFace(_ a: LetterFace, _ b: LetterFace) -> Bool {
    a.glyph == b.glyph && a.script == b.script
}

@MainActor
@Observable
public final class AssemblyModel<Item, Round, Slot> {
    @ObservationIgnored public let descriptor: AssemblyDescriptor<Item, Round, Slot>
    /// Seeded ONCE, in this init (D9 — never build the model in a view `init`
    /// or a `@State` default).
    public let session: [Item]

    public private(set) var idx = 0
    public private(set) var round: Round
    /// One entry per slot; nil = still empty. Fill-blank seeds some entries.
    public private(set) var slots: [Slot?]
    /// Which tray tile fills each slot (nil = empty or pre-revealed) — the
    /// undo path's memory.
    public private(set) var slotTile: [Int?]
    /// Dropped tray tile ids — those tiles grey out until removed.
    public private(set) var used: Set<Int> = []
    public private(set) var mood: Mood = .idle
    public private(set) var done = false
    public private(set) var earned = 0
    public private(set) var stars: [Bool]
    /// Pre-revealed slot mask for the current round.
    public private(set) var lockedMask: [Bool]

    /// TSX `locked` ref — true through the success line AND the « Oh non »
    /// line. Tray tiles stay enabled during those lines (only `used` ones grey
    /// out), which is why this guard carries the pacing (invariant 8).
    @ObservationIgnored private var locked = false
    @ObservationIgnored private var isActive = true
    @ObservationIgnored private let deps: EngineDeps
    @ObservationIgnored private let rng: RandomSource
    @ObservationIgnored private var announceTask: Task<Void, Never>?
    @ObservationIgnored private var didScheduleFirstAnnounce = false

    public init(
        descriptor: AssemblyDescriptor<Item, Round, Slot>,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) {
        self.descriptor = descriptor
        self.deps = deps
        self.rng = rng
        let session = descriptor.buildSession(rng)
        self.session = session
        self.stars = session.map { _ in true }
        self.done = session.isEmpty
        // Round 0, exactly like the TSX `useState` initialisers — built, not
        // announced (the announce is the 350 ms mount effect).
        if let first = session.first {
            let r = descriptor.buildRound(first, rng)
            self.round = r
            let seeded = descriptor.seededSlots(r)
            self.slots = seeded
            self.slotTile = seeded.map { _ in nil }
            self.lockedMask = descriptor.lockedSlots(r)
        } else {
            // Unreachable with the shipped pools; keeps the type sound.
            fatalError("AssemblyModel requires a non-empty session")
        }
    }

    // MARK: - Projection surface

    /// The current session item (the word / sound the round is built from).
    public var current: Item { session[idx] }

    /// Tray tile greyed ⟺ dropped in a slot (`disabled={used.has(t.id)}`).
    public func isTrayTileUsed(id: Int) -> Bool { used.contains(id) }

    /// A filled slot the child may tap back out — filled AND not pre-revealed.
    public func isSlotRemovable(_ i: Int) -> Bool {
        guard slots.indices.contains(i) else { return false }
        return slots[i] != nil && !lockedMask[i]
    }

    // MARK: - Lifecycle

    public func activate() {
        isActive = true
        deps.audio.unlock()
        // The TSX announce effect has `[]` deps: round 0 only, once per mount.
        guard !didScheduleFirstAnnounce, !done, let first = session.first else { return }
        didScheduleFirstAnnounce = true
        let line = descriptor.promptLine(first)
        announceTask = Task { [weak self] in
            guard let self else { return }
            await self.deps.delay(EngineLines.announceDelayMs)
            guard !Task.isCancelled, self.isActive else { return }
            _ = await self.deps.audio.say(line)
        }
    }

    public func deactivate() {
        announceTask?.cancel()
        announceTask = nil
        isActive = false
        deps.audio.stop()
    }

    // MARK: - The pick handler (synchronous — invariant 1)

    /// Drop a tray tile into the next open slot. No per-tap judgement — only a
    /// complete row is judged. The tile that completes a WRONG row still gets
    /// `.accept` (no shake): the row-level oops is the feedback.
    public func pick(tileID: Int, value: Slot) -> Verdict {
        if done { return .reject }  // unreachable from a correct view
        if locked { return .reject }
        deps.audio.unlock()
        deps.audio.pop()

        guard let nextEmpty = slots.firstIndex(where: { $0 == nil }) else { return .reject }
        slots[nextEmpty] = value
        slotTile[nextEmpty] = tileID
        used.insert(tileID)

        guard !slots.contains(where: { $0 == nil }) else { return .accept }

        // Row complete — judge the whole order at once.
        let filled = slots.compactMap { $0 }
        if descriptor.judge(round, filled) {
            locked = true
            // [DEVIATION, authorised] Stale prompt — see D45 and the identical
            // line in `SinglePickModel.pick`. The row is complete and correct;
            // announcing the task now would cut the success line short and
            // strand the round with `locked` still true.
            announceTask?.cancel()
            mood = .happy
            deps.audio.success()
            deps.fireConfetti()
            // Advance only after the success line played in full (`ok`).
            let nextIdx = idx + 1
            let line = descriptor.successLine(round)
            Task { [weak self] in
                guard let self else { return }
                let ok = await self.deps.audio.say(
                    line, rate: EngineLines.successRate, pitch: EngineLines.defaultPitch)
                guard ok, self.isActive else { return }
                if nextIdx >= self.session.count {
                    self.mood = .cheer
                    self.earned = self.deps.award(
                        self.descriptor.exercise, self.descriptor.level,
                        self.stars.filter { $0 }.count, self.session.count)
                    self.done = true
                    let audio = self.deps.audio
                    Task { _ = await audio.say(EngineLines.bravoSucceeded) }
                } else {
                    self.mood = .idle
                    self.idx = nextIdx
                    self.loadRound(self.session[nextIdx])
                }
            }
        } else {
            // Wrong row: « Oh non », then — once it has finished speaking —
            // wipe the tray tiles back out. The reset is NOT gated on the
            // line's result (ported as-is); pre-revealed slots stay put.
            locked = true
            deps.audio.oops()
            StarStrip.miss(idx, in: &stars)  // the star greys NOW, same beat as the "Oh non"
            Task { [weak self] in
                guard let self else { return }
                _ = await self.deps.audio.say(EngineLines.ohNon)
                guard self.isActive else { return }
                let seeded = self.descriptor.seededSlots(self.round)
                self.slots = seeded
                self.slotTile = seeded.map { _ in nil }
                self.used = []
                self.locked = false
            }
        }
        return .accept
    }

    /// Tap a filled slot to send its tile back to the tray. Pre-revealed
    /// (fill-blank) slots ignore; so does everything while a line plays.
    public func removeAt(_ i: Int) {
        guard !done, !locked else { return }
        guard lockedMask.indices.contains(i), !lockedMask[i] else { return }
        guard let tileID = slotTile[i] else { return }
        deps.audio.pop()
        slots[i] = nil
        slotTile[i] = nil
        used.remove(tileID)
    }

    // MARK: - Audio affordances

    /// The big 🔊 button — locked-guarded, no unlock (TSX shape).
    public func replayPrompt() {
        guard !locked, !done else { return }
        let line = descriptor.promptLine(session[idx])
        let audio = deps.audio
        Task { _ = await audio.say(line) }
    }

    /// A tray tile's « Écouter ». All three assembly previews are
    /// locked-guarded in the TSX.
    public func preview(say text: String) {
        guard !locked else { return }
        deps.audio.unlock()
        let audio = deps.audio
        Task { _ = await audio.say(text) }
    }

    // MARK: - Advance

    /// Fresh round + IMMEDIATE announce (`void audio.say(...)` in the TSX
    /// loadRound — no 350 ms delay after round 0).
    private func loadRound(_ item: Item) {
        let r = descriptor.buildRound(item, rng)
        round = r
        let seeded = descriptor.seededSlots(r)
        slots = seeded
        slotTile = seeded.map { _ in nil }
        lockedMask = descriptor.lockedSlots(r)
        used = []
        locked = false
        let line = descriptor.promptLine(item)
        let audio = deps.audio
        Task { _ = await audio.say(line) }
    }
}

extension AssemblyModel: RoundRunner {
    public var totalRounds: Int { session.count }
    public var headline: String? { descriptor.headline }
    public var finishedTitle: String { descriptor.finishedTitle }
    public var listenAccessibilityLabel: String { descriptor.listenAccessibilityLabel }
}

// MARK: - The three concrete engines (1:1 with the TSX files)

extension AssemblyModel where Item == SyllableWord, Round == SyllableRound, Slot == String {
    /// `AssembleExercise.tsx` — ONE engine for the three `SyllableMode`s. Mode
    /// reaches only `buildSyllableRound` and the headline; the assembly loop is
    /// mode-agnostic (CLAUDE.md).
    public static func assemble(
        exercise: ExerciseId,
        mode: SyllableMode,
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> AssemblyModel<SyllableWord, SyllableRound, String> {
        let tier = Levels.syllableTier(level)
        return AssemblyModel(
            descriptor: AssemblyDescriptor(
                exercise: exercise,
                level: level,
                headline: Levels.modeHint[mode] ?? "",
                finishedTitle: Copy.Finished.allSucceeded,
                listenAccessibilityLabel: Copy.Exercise.repeatWord,
                buildSession: {
                    repeatSession(
                        Levels.syllablePool(tier), pick: tier.pick, repeats: tier.repeats, $0)
                },
                buildRound: { Levels.buildSyllableRound(word: $0, mode: mode, $1) },
                promptLine: { $0.word },
                successLine: { "Oui ! \($0.word.word)." },
                seededSlots: { $0.slots },
                lockedSlots: { $0.locked },
                judge: { round, filled in
                    filled.count == round.word.syllables.count
                        && zip(filled, round.word.syllables).allSatisfy(==)
                }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension AssemblyModel where Item == SoundTarget, Round == SoundRound, Slot == String {
    /// `SpellSoundExercise.tsx` — hear a sound, spell it letter by letter.
    public static func spellSound(
        level: Int,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> AssemblyModel<SoundTarget, SoundRound, String> {
        let cfg = Levels.soundLevel(level)
        return AssemblyModel(
            descriptor: AssemblyDescriptor(
                exercise: .spellSound,
                level: level,
                headline: "Écoute le son et écris-le avec les lettres",
                finishedTitle: Copy.Finished.allSucceeded,
                listenAccessibilityLabel: Copy.Exercise.replaySound,
                buildSession: { Levels.buildSoundSession(level: level, $0) },
                buildRound: { Levels.buildSoundRound(target: $0, distractors: cfg.distractors, $1) },
                promptLine: { Levels.soundPrompt($0) },
                successLine: { Levels.soundSuccess($0.target) },
                seededSlots: { $0.slots },
                lockedSlots: { $0.slots.map { _ in false } },
                judge: { round, filled in
                    filled.count == round.target.spelling.count
                        && zip(filled, round.target.spelling).allSatisfy(==)
                }
            ),
            deps: deps,
            rng: rng
        )
    }
}

extension AssemblyModel
where Item == SyllableWord, Round == SpellSyllableRound, Slot == LetterFace {
    /// `SpellSyllableExercise.tsx` — part of the word is written; the gap is
    /// spelled into per-letter slots. Mixed twins judge the FACE (glyph +
    /// script via `sameFace`), so a right letter in the wrong writing fails
    /// the row; plain rounds are all-uppercase-print, degrading to letter
    /// equality.
    public static func spellSyllable(
        exercise: ExerciseId,
        mode: SpellSyllableMode,
        level: Int,
        mixed: Bool = false,
        deps: EngineDeps,
        rng: RandomSource = .system()
    ) -> AssemblyModel<SyllableWord, SpellSyllableRound, LetterFace> {
        let cfg = Levels.spellSyllableLevel(level)
        return AssemblyModel(
            descriptor: AssemblyDescriptor(
                exercise: exercise,
                level: level,
                headline: mixed
                    ? Copy.Exercise.spellHeadlineMixed(mode) : Copy.Exercise.spellHeadline(mode),
                finishedTitle: Copy.Finished.allSucceeded,
                listenAccessibilityLabel: Copy.Exercise.replayWord,
                buildSession: { Levels.buildSpellSyllableSession(level: level, $0) },
                buildRound: {
                    Levels.buildSpellSyllableRound(
                        word: $0, mode: mode, distractors: cfg.distractors, mixed: mixed, $1)
                },
                promptLine: { $0.word },
                successLine: { "Oui ! \($0.word.word)." },
                seededSlots: { $0.answerFaces.map { _ in nil } },
                lockedSlots: { $0.answerFaces.map { _ in false } },
                judge: { round, filled in
                    filled.count == round.answerFaces.count
                        && zip(filled, round.answerFaces).allSatisfy(sameFace)
                }
            ),
            deps: deps,
            rng: rng
        )
    }
}
