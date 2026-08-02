import ALCore
import SwiftUI

/* -------------------------------------------------------------------------- */
/* The view half of `src/App.tsx` — the ordered gates, the routed screens and   */
/* the exercise dispatch. Every RULE here is a plain value in                   */
/* `Screens/Router.swift` (`shellGate`, `engine(for:)`, `nextRoute`,            */
/* `openOutcome`, `remountKey`) and is host-tested there; this file only maps   */
/* values onto views.                                                          */
/*                                                                             */
/* Deliberately NOT a `NavigationStack`: the PWA replaces the whole screen —    */
/* no push animation, no system back button, no interactive pop. Each screen    */
/* brings its own back affordance, and « Suivant » REPLACES the exercise        */
/* (shell.md §4.1).                                                            */
/*                                                                             */
/* The route state is one `@State var route: AppRoute` (the TSX `useState`).    */
/* The gates read the stores directly, so a gate can override a stored route    */
/* at any time — `switchChild()` mid-game lands on « Qui joue ? » exactly as    */
/* the web does.                                                               */
/*                                                                             */
/* Dependencies: `ProfileStore`, `EntitlementModel` and `Telemetry` come from   */
/* the SwiftUI environment (ALCore `@Observable` models — ARCHITECTURE §5);     */
/* the audio engine, the KV store and the clock come through `init`, because    */
/* they are protocol values the screens thread by parameter (`EngineHost`,      */
/* `ShopView(store:audio:kv:)`). ALUI never imports ALPlatform — the App        */
/* target injects the live implementations (`AppEnvironment.swift`).            */
/* -------------------------------------------------------------------------- */

@MainActor
public struct RootView: View {

    @Environment(ProfileStore.self) private var profiles
    @Environment(EntitlementModel.self) private var entitlement
    @Environment(Telemetry.self) private var injectedTelemetry: Telemetry?

    private let audio: any AudioEngine
    private let kv: any KVStore
    private let time: any TimeSource
    /// Which build a parent is describing when they suggest a correction. Not
    /// read anywhere else here; the composition root passes the real one
    /// (`ALPlatform.SystemAppVersion`), and the default keeps every preview and
    /// test constructible without one.
    private let version: any AppVersionProvider
    /// The `#stages` / `#vo` stand-in. Resolved once at launch; nil in Release.
    private let dev: DevScreen?

    /// `useState<View>({ kind: "hub" })`.
    @State private var route: AppRoute = .hub
    /// `window.location.hash = ""` — the bench's close chip clears the hash.
    @State private var devDismissed = false

    public init(
        audio: any AudioEngine,
        kv: any KVStore,
        time: any TimeSource,
        version: any AppVersionProvider = FixedAppVersion("0.0.0"),
        dev: DevScreen? = DevScreen.fromProcess(),
        onPair: (() -> Void)? = nil
    ) {
        self.audio = audio
        self.kv = kv
        self.time = time
        self.version = version
        self.dev = dev
        self.onPair = onPair
    }

    /// Raises the pairing flow. Supplied only by the app target — the package
    /// has no way to mint a link, and a preview or a test renders the
    /// dashboard without the door exactly as before.
    private let onPair: (() -> Void)?

    /// The process-wide instance is the TS module-level `track`; an injected
    /// one wins so a test or a preview can watch it (Paywall's pattern).
    private var telemetry: Telemetry { injectedTelemetry ?? Telemetry.shared }

    public var body: some View {
        // `index.css` `#root`: page background, card top-centred
        // (`place-items: start center`), `max-width: 480px`, ≥ 16 pt gutters.
        //
        // D51 — the gutter is SAFE-AREA padding, not layout padding. Same insets
        // for the content either way; the difference is that a screen's wash can
        // ignore a safe area and cannot escape a `.padding`. See `stageWash`.
        ZStack(alignment: .top) {
            Palette.page.color.ignoresSafeArea()
            gatedScreen
                .frame(maxWidth: Shell.cardMaxWidth)
                .safeAreaPadding(Shell.minimumInset)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    // MARK: - The gates (ordered — Router.swift's `shellGate`)

    @ViewBuilder
    private var gatedScreen: some View {
        switch shellGate(
            dev: devDismissed ? nil : dev,
            onboarded: entitlement.onboarded,
            activeId: profiles.activeId,
            chosen: profiles.profile.chosen,
            route: route
        ) {
        case .devStages:
            devBench(.stages)
        case .devVo:
            devBench(.vo)
        case .onboarding:
            // Drives itself through `EntitlementModel`; `beginTrial()` flips
            // `onboarded` and this gate falls through on the next evaluation.
            OnboardingView()
        case .whoIsPlaying:
            // `selectChild`/`createChild` set `activeId` → falls through.
            WhoIsPlayingView()
        case .firstRunPicker:
            // `chooseSpecies()` flips `profile.chosen`; `onDone` pins the hub —
            // the child is NOT returned to whatever they tapped (shell.md §4.1).
            PickerView(variant: .firstRun, onDone: { route = .hub })
        case .screen(let screen):
            routedScreen(screen)
        }
    }

    // MARK: - The routed screens

    @ViewBuilder
    private func routedScreen(_ screen: AppRoute) -> some View {
        switch screen {
        case .hub:
            hub
        case let .play(exercise, level):
            playScreen(exercise, level)
        case .dashboard:
            DashboardView(
                onBack: { route = .hub },
                onShop: { route = .shop },
                onSwitch: { route = .pick },
                onShare: onPair
            )
        case .shop:
            // `.shop`'s back goes to `.dashboard`, not `.hub`.
            ShopView(
                store: profiles,
                audio: audio,
                kv: kv,
                onBack: { route = .dashboard }
            )
        case .pick:
            // `onDone` AND `onCancel` both land on the dashboard.
            PickerView(
                variant: .switchMascot,
                onDone: { route = .dashboard },
                onCancel: { route = .dashboard }
            )
        case .paywall:
            PaywallView(onBack: { route = .hub })
        }
    }

    private var hub: some View {
        HubView(
            audio: audio,
            onOpen: { open($0, $1) },
            onDashboard: { route = .dashboard },
            onPaywall: { route = .paywall }
        )
    }

    /// `App.tsx:147-154`. The only gate the child ever feels: an expired trial
    /// blocks STARTING a round, nothing else. `.unknown` plays (invariant 11).
    private func open(_ exercise: ExerciseId, _ level: Int) {
        switch openOutcome(exercise: exercise, level: level, entitlement: entitlement.entitlement) {
        case .paywall:
            telemetry.track(.trialExpired)
            route = .paywall
        case let .play(exercise, level):
            telemetry.track(.exerciseStarted, TelemetryProps(exercise: exercise, level: level))
            route = .play(exercise: exercise, level: level)
        }
    }

    // MARK: - The exercise dispatch (Router.swift's `engine(for:)`)

    @ViewBuilder
    private func playScreen(_ exercise: ExerciseId, _ level: Int) -> some View {
        if let meta = Levels.exercises.first(where: { $0.id == exercise }) {
            let host = EngineHost(
                audio: audio,
                time: time,
                // Engines never compute a point (invariant 8): every award goes
                // through `ProfileStore.award` → `Rewards.sessionReward`.
                award: { [profiles] in
                    profiles.award(
                        exercise: $0, level: $1, perfectRounds: $2, totalRounds: $3)
                }
            )
            let mascot = profiles.profile.config
            let back: () -> Void = { route = .hub }
            let next: () -> Void = {
                route = nextRoute(
                    after: .play(exercise: exercise, level: level),
                    in: Levels.exercises)
            }
            Group {
                switch engine(for: meta) {
                case .readImage:
                    ReadImageView(
                        level: level, host: host, mascot: mascot,
                        onBack: back, onNext: next)
                case .spellSound:
                    SpellSoundView(
                        level: level, mascot: mascot, host: host,
                        onBack: back, onNext: next)
                case .findSound:
                    FindSoundView(
                        level: level, host: host, mascot: mascot,
                        onBack: back, onNext: next)
                case .soundTwins:
                    SoundTwinsView(
                        level: level, host: host, mascot: mascot,
                        onBack: back, onNext: next)
                case let .syllableGrid(mode):
                    SyllableGridView(
                        exercise: exercise, mode: mode, level: level,
                        host: host, mascot: mascot,
                        onBack: back, onNext: next)
                case let .spellSyllable(mode, mixed):
                    SpellSyllableView(
                        exercise: exercise, mode: mode, level: level, mixed: mixed,
                        mascot: mascot, host: host,
                        onBack: back, onNext: next)
                case let .letterMatch(kind):
                    LetterMatchView(
                        exercise: exercise, kind: kind, level: level,
                        host: host, mascot: mascot,
                        onBack: back, onNext: next)
                case let .assemble(mode):
                    AssembleView(
                        exercise: exercise, mode: mode, level: level,
                        mascot: mascot, host: host,
                        onBack: back, onNext: next)
                case .firstLetter:
                    FirstLetterView(
                        level: level, host: host, mascot: mascot,
                        onBack: back, onNext: next)
                }
            }
            // React's `key={`${exercise}-${level}`}` — the REMOUNT. Every
            // level change tears the subtree down: fresh seeding, fresh audio,
            // fresh star array. Omit it and level 2 replays level 1's words.
            .id(AppRoute.play(exercise: exercise, level: level).remountKey)
            // The one place an exercise is mounted, so the one place the
            // correction link needs to be told what it is looking at. Nine
            // engines get the link without nine chances to forget it — and
            // outside this branch the environment stays nil, so no other
            // `GameFrame` grows an adult door by accident.
            .alCorrectionReport(
                CorrectionReport(
                    exercise: exercise, level: level, appVersion: version.marketing))
        } else {
            // `EXERCISES.findIndex` −1 would crash the TSX on `meta.levelCount`.
            // Unreachable (the catalog covers every ExerciseId — HubCatalogTests);
            // a hub bounce beats a trap in a six-year-old's app (shell.md §7.3).
            hub
        }
    }

    // MARK: - Dev benches

    /// The benches themselves (`src/dev/`) are not ported — see Router.swift's
    /// `DevScreen` doc. DEBUG-only placeholder so the route stays walkable.
    @ViewBuilder
    private func devBench(_ screen: DevScreen) -> some View {
        #if DEBUG
            VStack(spacing: 16) {
                Text(verbatim: screen == .stages ? "#stages" : "#vo")
                    .font(.system(.title, design: .monospaced).bold())
                Text(verbatim: "Banc de dev non porté — voir Screens/Router.swift.")
                    .font(.system(.body))
                Button(action: { devDismissed = true }) {
                    Text(verbatim: "Fermer")
                }
            }
            .frame(maxWidth: .infinity, minHeight: Shell.minimumScreenHeight)
        #else
            // Unreachable: `DevScreen.fromProcess()` is nil outside DEBUG.
            EmptyView()
        #endif
    }
}
