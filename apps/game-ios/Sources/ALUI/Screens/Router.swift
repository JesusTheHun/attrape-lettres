import ALCore
import Foundation

/* -------------------------------------------------------------------------- */
/* The routing half of `src/App.tsx`, as PLAIN VALUES — every rule the view     */
/* router applies lives here so `swift test` can walk the whole table without   */
/* a renderer (ARCHITECTURE §6.4; the view half is `RootView.swift`).           */
/*                                                                             */
/* Three rules, all load-bearing and all ported verbatim from the TSX:          */
/*                                                                             */
/*  1. The GATES are an ordered if-chain, not a switch on the route. A stored   */
/*     route of `.play(…)` is silently overridden while a gate holds — that is  */
/*     what lets `switchChild()` land on « Qui joue ? » from anywhere.          */
/*  2. The exercise DISPATCH is four id checks, then four capability checks,    */
/*     in that order (`App.tsx:89-121`).                                       */
/*  3. « Suivant » walks levels, then rolls into level 1 of the NEXT exercise,  */
/*     and past the final exercise back to the hub (`App.tsx:82-87`).           */
/* -------------------------------------------------------------------------- */

// MARK: - Dev screens (`#stages` / `#vo`)

/**
 * The two dev benches, reachable on the web at `window.location.hash ===
 * "#stages"` / `"#vo"` — before EVERY gate, including onboarding
 * (`App.tsx:54-58`). iOS has no URL hash; the stand-in is a launch argument
 * (`-ALDevScreen stages`), resolved once at the root and nil in Release, which
 * keeps both benches out of any child's flow (shell.md §4.2).
 *
 * NB: the benches themselves (`src/dev/MascotGallery`, `src/dev/VoGallery`) are
 * not ported — no phase owns them, and `Copy.swift`'s header excludes `src/dev/`
 * deliberately. The ROUTE exists and is tested; the screen behind it is a
 * DEBUG-only placeholder in `RootView` until someone ports the benches.
 */
public enum DevScreen: String, Equatable, Sendable, CaseIterable {
    case stages
    case vo

    /// `-ALDevScreen <value>` from a launch-argument array. Unknown values and
    /// a dangling flag resolve to nil — a typo must not strand the app on a
    /// blank screen.
    public static func parse(arguments: [String]) -> DevScreen? {
        guard let flag = arguments.firstIndex(of: "-ALDevScreen"),
            arguments.indices.contains(flag + 1)
        else { return nil }
        return DevScreen(rawValue: arguments[flag + 1])
    }

    /// The app root's resolution. Always nil outside DEBUG — a Release build
    /// has no dev screens, exactly as "out of kid flow" intends.
    public static func fromProcess(_ info: ProcessInfo = .processInfo) -> DevScreen? {
        #if DEBUG
            return parse(arguments: info.arguments)
        #else
            return nil
        #endif
    }
}

// MARK: - The gates, in order

/// What the root renders this frame. One value; `RootView.body` is a switch
/// over it and nothing else.
public enum ShellGate: Equatable, Sendable {
    case devStages
    case devVo
    /// `!onboarded` — the parent screen, once per device, before anything else
    /// (App Review 3.1.1 wants the trial's terms BEFORE the trial starts).
    case onboarding
    /// `!activeId` — « Qui joue ? ». Siblings share the device.
    case whoIsPlaying
    /// `!profile.chosen` — until the active child picks a friend, the Picker is
    /// the whole app.
    case firstRunPicker
    /// Every gate passed: render the routed screen.
    case screen(AppRoute)
}

/**
 * `App.tsx`'s ordered early returns, lines 54-73. The order IS behaviour:
 *
 *  - the dev benches win over everything, including "no active player";
 *  - onboarding sits above the roster — a fresh device meets the parent first;
 *  - the roster and species gates sit ABOVE the play branch, so a `.play`
 *    route is silently overridden if the active child disappears or has not
 *    chosen a species. Do not reorder for tidiness (shell.md §4.1).
 */
public func shellGate(
    dev: DevScreen?,
    onboarded: Bool,
    activeId: String?,
    chosen: Bool,
    route: AppRoute
) -> ShellGate {
    if dev == .stages { return .devStages }
    if dev == .vo { return .devVo }
    if !onboarded { return .onboarding }
    if activeId == nil { return .whoIsPlaying }
    if !chosen { return .firstRunPicker }
    return .screen(route)
}

// MARK: - Opening an exercise

/// What tapping a level does (`App.tsx:147-154`). The ONLY thing an expired
/// trial blocks is starting a new round — the hub, the mascot, the shop and
/// every star stay exactly where they were (invariant 3: nothing is ever taken
/// away from a child as a consequence).
public enum OpenOutcome: Equatable, Sendable {
    /// `track("trial_expired")`, then the paywall.
    case paywall
    /// `track("exercise_started", { exercise, level })`, then the run.
    case play(ExerciseId, Int)
}

/// `canPlay` is ALCore's blacklist-of-one — `.unknown` PLAYS (invariant 11:
/// a store that has not answered never shows a paywall).
public func openOutcome(exercise: ExerciseId, level: Int, entitlement: Entitlement)
    -> OpenOutcome
{
    guard canPlay(entitlement) else { return .paywall }
    return .play(exercise, level)
}

// MARK: - The exercise dispatch

/// The eight-branch dispatch of `App.tsx:89-121`, as data. `RootView` maps each
/// case onto its view; the nine views' uniform `host:`/`mascot:`/`rng:`/
/// `onBack:`/`onNext:` surface (D35) is what makes that mapping a plain switch.
public enum ExerciseEngine: Equatable, Sendable {
    case readImage
    case spellSound
    case findSound
    case soundTwins
    case syllableGrid(SyllableGridMode)
    case spellSyllable(SpellSyllableMode, mixed: Bool)
    case letterMatch(LetterMatchKind)
    case assemble(SyllableMode)
    case firstLetter
}

/**
 * Four id checks FIRST, then four capability checks — the TSX order, ported
 * exactly. The `default: break` is the faithful port of four `if`s over the id
 * and carries no invariant (the exhaustive-switch rule is invariant 7's, in
 * `ExerciseIconCatalog`, not here — shell.md §4.3).
 */
public func engine(for meta: ExerciseMeta) -> ExerciseEngine {
    switch meta.id {
    case .readImage: return .readImage
    case .spellSound: return .spellSound
    case .findSound: return .findSound
    case .soundTwins: return .soundTwins
    default: break
    }
    if let grid = meta.grid { return .syllableGrid(grid) }
    if let spell = meta.spell { return .spellSyllable(spell, mixed: meta.mixed) }
    if let match = meta.match { return .letterMatch(match) }
    if let mode = meta.mode { return .assemble(mode) }
    return .firstLetter
}

// MARK: - « Suivant »

/**
 * `App.tsx:82-87` — the roll-over. Past an exercise's last level it advances to
 * level 1 of the NEXT catalog row, and past the final row back to the hub.
 *
 * The `guard`'s `.hub` covers two branches the TSX cannot take: a non-play
 * route (its `next` closure only exists inside the play branch) and an
 * exercise id missing from the catalog, where `EXERCISES.findIndex` would
 * return −1 and the TSX would crash reading `meta.levelCount`. A hub bounce
 * beats a `fatalError` in a six-year-old's app — recorded divergence in an
 * unreachable path (shell.md §7.3).
 */
public func nextRoute(after route: AppRoute, in catalog: [ExerciseMeta]) -> AppRoute {
    guard case let .play(exercise, level) = route,
        let index = catalog.firstIndex(where: { $0.id == exercise })
    else { return .hub }
    let meta = catalog[index]
    if level < meta.levelCount {
        return .play(exercise: exercise, level: level + 1)
    }
    if index + 1 < catalog.count {
        return .play(exercise: catalog[index + 1].id, level: 1)
    }
    return .hub
}

// MARK: - The remount key

extension AppRoute {
    /**
     * The port of React's `key={`${exercise}-${level}`}` (`App.tsx:88`).
     * Applied as `.id(route.remountKey)` on the exercise subtree, it tears the
     * view down and rebuilds it — fresh `@State`, fresh seeding, fresh star
     * array — on every level change, including the roll-over into the same
     * component type. Omitting it is silent: level 2 replays level 1's words
     * (shell.md §7.2).
     */
    public var remountKey: String {
        if case let .play(exercise, level) = self {
            return "\(exercise.rawValue)-\(level)"
        }
        return "route"
    }
}
