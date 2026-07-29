import ALCore
import Observation

/* ==========================================================================
   THE ENGINE API — read this before writing an exercise view.

   Three @MainActor @Observable models cover the nine exercises:

     SinglePickModel<Round>   FirstLetter, FindSound, SyllableGrid (hear/vowel),
                              LetterMatch (case/script), ReadImage
     AssemblyModel<I, R, S>   Assemble (fill-blank/order/order-distractor),
                              SpellSound, SpellSyllable (all modes, mixed twins)
     TwinsModel               SoundTwins

   Create one via the static factories (they wire the ALCore builders,
   prompts and copy — the 1:1 audit surface against the nine TSX files):

     SinglePickModel.firstLetter(level:deps:rng:)
     SinglePickModel.findSound(level:deps:rng:)
     SinglePickModel.syllableGrid(exercise:mode:level:deps:rng:)
     SinglePickModel.letterMatch(exercise:kind:level:deps:rng:)
     SinglePickModel.readImage(level:deps:rng:)
     AssemblyModel.assemble(exercise:mode:level:deps:rng:)
     AssemblyModel.spellSound(level:deps:rng:)
     AssemblyModel.spellSyllable(exercise:mode:level:mixed:deps:rng:)
     TwinsModel(level:deps:rng:)

   CREATION (D9 — this is load-bearing): the init seeds the whole session
   through the ALCore builders, so it must run EXACTLY ONCE per entry into an
   exercise. Never build a model in a view `init` or a `@State` default —
   SwiftUI evaluates `@State` defaults on every parent re-render. The pattern:

     @State private var model: SinglePickModel<FirstLetterRound>?
     var body: some View {
       content(model)                       // `if let model { … }` + placeholder
         .task {                            // runs once per view identity
           if model == nil { model = .firstLetter(level: level, deps: deps) }
           model?.activate()
         }
         .onDisappear { model?.deactivate() }
     }

   `activate()` = the TSX mount effects: audio.unlock() + the 350 ms delayed
   announce. `deactivate()` = unmount: cancels the pending announce, fades the
   voice (audio.stop()), and freezes the model (no advance, no award after it).

   THE VIEW IS A PROJECTION. It reads state and calls the handlers; it holds
   no rule. Shared surface (the `RoundRunner` protocol):

     totalRounds, idx, stars      → GameFrame(done: progressDone,
     progressDone                             total: totalRounds, stars: stars)
     mood                         → Mascot(config: profile.config, mood: mood)
     done, earned, finishedTitle  → Finished(stars: stars, earned: earned,
                                             title: finishedTitle)
     headline                     → the consigne line above the mascot (nil for
                                    FirstLetter and LetterMatch — no line)
     listenAccessibilityLabel     → the big 🔊 button's a11y label
     replayPrompt()               → the big 🔊 button's touchDown action
     activate() / deactivate()

   Per family:

     SinglePickModel:  current (Round), flash, tilesDisabled, listenText
                       pick(key) -> Verdict           key = the engine's flash
                         FirstLetter → letter, FindSound → graphy,
                         Grid → choice.text (BOTH modes — vowel tiles still
                         pick by the full syllable text), LetterMatch →
                         face.base, ReadImage → choice.word
                       preview(say:)                  pass what the TSX speaks:
                         letter / choice.sound / choice.sound / face.base /
                         choice.word
                       highlight a tile iff flash == its key; disable all
                       tiles iff tilesDisabled (flash != nil).

     AssemblyModel:    current (Item), round (Round), slots, used
                       pick(tileID:value:) -> Verdict  value: the syllable
                         String / letter String / LetterFace built from the
                         tray tile (base: t.letter, glyph: t.glyph,
                         script: t.script)
                       removeAt(_ slotIndex:)          filled-slot touchDown
                       isSlotRemovable(_:)             filled AND not
                                                       pre-revealed (Assemble)
                       isTrayTileUsed(id:)             disables a tray tile
                       preview(say:)                   t.syllable / t.letter

     TwinsModel:       round, found, complete
                       pick(_ tile: TwinTile) -> Verdict
                       tileDisabled(_:), tileHighlighted(_:)
                       foundTile(at:)                  the collection strip
                       preview(say: tile.sound)

   The verdict MUST be returned synchronously to `Tile`'s onPick (touchDown,
   invariant 1) — every pick handler here is synchronous; SFX and star greying
   happen inside it, before any await.

   WHAT THE VIEW STILL OWNS (data plumbing, not rules):
     - Tile a11y labels via Copy.Exercise (letterTile/syllableTile/soundTile/
       imageTile) or ALCore.faceLabel for letter-form tiles; preview labels via
       Copy.Exercise.listenTile.
     - Tile palettes/sizes (Palette + the per-engine FluidSpecs).
     - The ConfettiSystem: the view creates it, renders the overlay, and hands
       `system.fire` into EngineDeps.fireConfetti (reduce-motion gating lives
       inside ConfettiSystem).
     - EngineDeps.award should be `profile.award(exercise:level:perfectRounds:
       totalRounds:)` — the ONLY point source (invariant 8).

   PLACES AN ENGINE LEGITIMATELY DOES NOT FIT THE SHARED SHAPE:
     - FirstLetter's listen button shows the word only on levels 1–3
       (`level < count-1`); that rule is baked into the factory's listenText —
       the view just renders `model.listenText`.
     - SyllableGrid `vowel` mode's half-written syllable: the view renders
       `current.target.consonant` + a gap that shows `current.target.vowel`
       iff `flash != nil` (white card vs dashed box). Tiles show `choice.vowel`
       in vowel mode, `choice.text` in hear mode — but pick by `choice.text`.
     - ReadImage's printed word (never spoken) in a FitLine; its preview IS
       locked-guarded where the other four single-pick previews are not
       (ported asymmetry — see previewGuardedByLock).
     - Assembly engines have NO miss cooldown by design: pacing is the awaited
       « Oh non ! On recommence. » line. Do not add one.
     - Assembly announces round 0 after 350 ms; every later round announces
       immediately inside the advance (loadRound). Single-pick and twins
       announce 350 ms after EVERY round becomes current.
     - SpellSound shows `target.emoji ?? "🎧"`; SpellSyllable renders cells in
       their script font (`Typography` + LetterScript), shown cells a11y-hidden.
     - The 350 ms announce timer is NOT cancelled by a pick — a correct tap
       inside those 350 ms lets the prompt supersede the success line and the
       round does not advance. That is the web's exact behaviour, ported as-is.
   ========================================================================== */

/// (exercise, level, perfectRounds, totalRounds) → points earned. Wire it to
/// `ProfileStore.award` — engines never compute a point (invariant 8).
public typealias AwardFunction = (ExerciseId, Int, Int, Int) -> Int

/// What a CALLER can supply to an exercise view: the audio channel, the clock,
/// `award`, and the announce sleep. Every one of the nine views takes exactly
/// this, so the hub dispatches over all of them uniformly.
///
/// It exists because `EngineDeps` has a fifth member the caller cannot usefully
/// provide. `fireConfetti` must be *this run's* `ConfettiSystem.fire` — the TSX
/// calls `useConfetti()` once per exercise, and the view owns that system
/// because it also has to place the canvas. Four view agents solved that four
/// ways: three took `audio`/`time`/`award` loose, and six took a whole
/// `EngineDeps` and then silently threw its `fireConfetti` away. A parameter
/// that is accepted and ignored is worse than one that does not exist — it
/// reads, in the hub, as if passing it did something.
public struct EngineHost {
    public var audio: any AudioEngine
    public var time: any TimeSource
    public var award: AwardFunction
    /// The announce timer's sleep. Injected so tests drive it without sleeping.
    public var delay: (_ milliseconds: Int) async -> Void

    public init(
        audio: any AudioEngine,
        time: any TimeSource,
        award: @escaping AwardFunction,
        delay: @escaping (_ milliseconds: Int) async -> Void = EngineDeps.systemDelay
    ) {
        self.audio = audio
        self.time = time
        self.award = award
        self.delay = delay
    }

    /// Complete the set with the run's own confetti system. Called by each view
    /// inside `.task`, where the `ConfettiSystem` finally exists.
    public func deps(fireConfetti: @escaping () -> Void) -> EngineDeps {
        EngineDeps(
            audio: audio,
            time: time,
            award: award,
            fireConfetti: fireConfetti,
            delay: delay
        )
    }
}

/// Everything a round model needs from the outside world, injected so the
/// whole lifecycle runs under `swift test` on the host.
public struct EngineDeps {
    /// The SFX + single-flight voice channel (ALCore protocol; ALPlatform's
    /// engine in the app, a fake in tests).
    public var audio: any AudioEngine
    /// Drives the miss-cooldown swallow window (D6 — never `Date()`).
    public var time: any TimeSource
    /// The ONLY way points enter the model (invariant 8).
    public var award: AwardFunction
    /// `useConfetti().fire` — pass `ConfettiSystem.fire`; reduce-motion gating
    /// lives inside the system, not here (D29).
    public var fireConfetti: () -> Void
    /// The announce timer's sleep — `window.setTimeout(…, 350)`. Injected so
    /// tests drive it without sleeping. Default: a real Task.sleep.
    public var delay: (_ milliseconds: Int) async -> Void

    public init(
        audio: any AudioEngine,
        time: any TimeSource,
        award: @escaping AwardFunction,
        fireConfetti: @escaping () -> Void,
        delay: @escaping (_ milliseconds: Int) async -> Void = EngineDeps.systemDelay
    ) {
        self.audio = audio
        self.time = time
        self.award = award
        self.fireConfetti = fireConfetti
        self.delay = delay
    }

    /// The production announce sleep. Cancellation-aware: a cancelled announce
    /// task returns early (the caller re-checks `Task.isCancelled`).
    public static let systemDelay: (Int) async -> Void = { ms in
        try? await Task.sleep(nanoseconds: UInt64(max(0, ms)) * 1_000_000)
    }
}

/// The engine-owned constants and spoken lines. The spoken strings are VO clip
/// keys (D17) — byte-exact, never normalised. They are deliberately NOT in
/// `Copy.swift` (that file owns displayed text; these are spoken).
public enum EngineLines {
    /// `window.setTimeout(() => announce, 350)` — every TSX engine.
    public static let announceDelayMs = 350

    /// Success lines pass `{ rate: 0.98 }`; pitch stays the 1.1 default.
    public static let successRate = 0.98
    public static let defaultPitch = 1.1

    /// The assembly wrong-row line — spoken at the DEFAULT rate, and its
    /// completion is NOT gated on the returned Bool (the reset happens even if
    /// the line was cut short — engines.md §4.2, ported as-is).
    public static let ohNon = "Oh non ! On recommence."

    /// End-of-run bravo (fire-and-forget). Single-pick + twins:
    public static let bravoFound = "Bravo ! Tu as tout trouvé !"
    /// Assembly engines:
    public static let bravoSucceeded = "Bravo ! Tu as tout réussi !"
}

/// The uniform surface every exercise view wires into GameFrame / Mascot /
/// Finished. Conformance is what keeps the nine views identical in shape.
@MainActor
public protocol RoundRunner: AnyObject {
    /// Session length (`session.count`). GameFrame's `total`.
    var totalRounds: Int { get }
    /// The current round index. Frozen at the last round once `done`.
    var idx: Int { get }
    /// Per-round first-try flags. Greyed (false) on the round's FIRST wrong
    /// tap, synchronously at pointerdown (invariant 8).
    var stars: [Bool] { get }
    /// Mascot mood — `idle` / `happy` / `cheer`. A miss never changes it.
    var mood: Mood { get }
    /// The run is finished; render `Finished` instead of the round.
    var done: Bool { get }
    /// What `award` returned at finish. 0 before finish and for difficulty-0
    /// exercises (EarnBadge renders only when > 0).
    var earned: Int { get }
    /// The consigne line above the mascot; nil = no line (FirstLetter,
    /// LetterMatch).
    var headline: String? { get }
    /// `Finished`'s title, verbatim.
    var finishedTitle: String { get }
    /// The big 🔊 button's accessibility label.
    var listenAccessibilityLabel: String { get }
    /// GameFrame's `done` input: `done ? total : idx`.
    var progressDone: Int { get }
    /// Mount: unlock audio + schedule the announce. Call from `.task`.
    func activate()
    /// Unmount: cancel the announce, fade the voice, freeze the model.
    func deactivate()
    /// The big 🔊 button (touchDown). Locked-guarded: it never cuts the
    /// success line mid-celebration.
    func replayPrompt()
}

extension RoundRunner {
    public var progressDone: Int { done ? totalRounds : idx }
}
