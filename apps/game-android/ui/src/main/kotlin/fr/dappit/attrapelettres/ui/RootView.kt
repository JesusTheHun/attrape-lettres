package fr.dappit.attrapelettres.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.AppRoute
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.licensing.EntitlementModel
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.core.platform.TimeSource
import fr.dappit.attrapelettres.core.telemetry.Telemetry
import fr.dappit.attrapelettres.core.telemetry.TelemetryEvent
import fr.dappit.attrapelettres.core.telemetry.TelemetryProps
import fr.dappit.attrapelettres.ui.design.LocalViewportWidth
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Shell
import fr.dappit.attrapelettres.ui.design.Typography
import fr.dappit.attrapelettres.ui.engines.AssembleView
import fr.dappit.attrapelettres.ui.engines.EngineHost
import fr.dappit.attrapelettres.ui.engines.FindSoundView
import fr.dappit.attrapelettres.ui.engines.FirstLetterView
import fr.dappit.attrapelettres.ui.engines.LetterMatchView
import fr.dappit.attrapelettres.ui.engines.ReadImageView
import fr.dappit.attrapelettres.ui.engines.SoundTwinsView
import fr.dappit.attrapelettres.ui.engines.SpellSoundScreen
import fr.dappit.attrapelettres.ui.engines.SpellSyllableScreen
import fr.dappit.attrapelettres.ui.engines.SyllableGridView
import fr.dappit.attrapelettres.ui.interaction.touchDown
import fr.dappit.attrapelettres.ui.screens.DashboardView
import fr.dappit.attrapelettres.ui.screens.DevScreen
import fr.dappit.attrapelettres.ui.screens.ExerciseEngine
import fr.dappit.attrapelettres.ui.screens.HubView
import fr.dappit.attrapelettres.ui.screens.OnboardingView
import fr.dappit.attrapelettres.ui.screens.OpenOutcome
import fr.dappit.attrapelettres.ui.screens.PaywallView
import fr.dappit.attrapelettres.ui.screens.PickerVariant
import fr.dappit.attrapelettres.ui.screens.ShellGate
import fr.dappit.attrapelettres.ui.screens.WhoIsPlayingView
import fr.dappit.attrapelettres.ui.screens.engineFor
import fr.dappit.attrapelettres.ui.screens.nextRoute
import fr.dappit.attrapelettres.ui.screens.openOutcome
import fr.dappit.attrapelettres.ui.screens.remountKey
import fr.dappit.attrapelettres.ui.screens.shellGate
import fr.dappit.attrapelettres.ui.shop.PickerView
import fr.dappit.attrapelettres.ui.shop.ShopView
import kotlinx.coroutines.CancellationException

// ===========================================================================
// The VIEW half of `src/App.tsx` — the ordered gates, the routed screens and the
// exercise dispatch. Every RULE here is a plain value in `screens/Router.kt`
// (`shellGate`, `engineFor`, `nextRoute`, `openOutcome`, `remountKey`) and is
// host-tested there; this file only maps values onto composables.
//
// Deliberately NOT a `NavHost`. The PWA replaces the whole screen: no push
// animation, no system back stack, no interactive pop. Each screen brings its
// own back affordance and « Suivant » REPLACES the exercise. A nav library would
// also add a back stack a six-year-old can fall down, and would put its own
// gesture detector above the exercise tiles, which is invariant 1's territory.
//
// The route state is one `mutableStateOf<AppRoute>` (the TSX `useState`). The
// gates read the stores directly, so a gate can override a stored route at any
// time — `switchChild()` mid-game lands on « Qui joue ? » exactly as the web
// does.
//
// ── DEPENDENCIES ──────────────────────────────────────────────────────────────
// Everything arrives as a PARAMETER, and every one of them is a `:core` type.
// `:ui` never depends on `:platform` (ARCHITECTURE §1), so this file is where
// the seams stop being abstract: `:app` builds the live adapters and hands them
// in. There is no service locator and no CompositionLocal for them — a screen
// that needs the roster is handed the roster.
//
// ── OBSERVABILITY, AND THE ONE WART ───────────────────────────────────────────
// `ProfileStore` publishes `rosterFlow`, so `collectAsState()` recomposes this
// root on every roster change and the two roster gates are live for free.
// `EntitlementModel` does NOT: its properties are plain Kotlin `var`s, invisible
// to the snapshot system (it is a `:core` class, and `:core` has no Compose on
// its classpath — A1). So the licence is re-read through a [ChangeTicker] that
// the two screens which can mutate it bump via `onLicenseChanged`, plus the
// resume hook. Reading a stale licence cannot lock anyone out (invariant 11:
// `canPlay` is a blacklist of one and the gates never consult it at all); the
// worst case is a trial countdown chip that redraws one interaction late.
//
// ── SYNC ──────────────────────────────────────────────────────────────────────
// Pull-merge-push runs on MOUNT and on RESUME and nowhere else. Never on a
// write: gameplay is offline-first and a child mid-round must never wait on a
// network call (`ProfileStore.syncNow`'s own contract). The activity signals a
// resume by ticking [ChangeTicker]; `:ui` does not observe the Android
// lifecycle itself, which keeps this file compilable and testable off-device.
//
// -- THE SCREEN CONTRACT ------------------------------------------------------
// Seven screens are called from below and each is written by somebody else.
// These signatures are the contract; a screen that widens them breaks the root.
// A trailing `modifier: Modifier = Modifier` is always welcome, and any screen
// may add further parameters provided they all have defaults.
//
//   package fr.dappit.attrapelettres.ui.screens
//
//     @Composable fun HubView(
//         profiles: ProfileStore, entitlement: Entitlement, audio: AudioEngine,
//         reduceMotion: ReduceMotionSource,
//         onOpen: (ExerciseId, Int) -> Unit,
//         onDashboard: () -> Unit, onPaywall: () -> Unit)
//
//     @Composable fun OnboardingView(
//         entitlement: EntitlementModel, telemetry: Telemetry,
//         reduceMotion: ReduceMotionSource, onDone: () -> Unit)
//
//     @Composable fun WhoIsPlayingView(
//         profiles: ProfileStore, reduceMotion: ReduceMotionSource)
//
//     @Composable fun DashboardView(
//         profiles: ProfileStore, audio: AudioEngine,
//         reduceMotion: ReduceMotionSource,
//         onBack: () -> Unit, onShop: () -> Unit, onSwitch: () -> Unit)
//
//     @Composable fun PaywallView(
//         entitlement: EntitlementModel, telemetry: Telemetry,
//         onBack: () -> Unit, onLicenseChanged: () -> Unit)
//
//   package fr.dappit.attrapelettres.ui.shop
//
//     @Composable fun ShopView(
//         profiles: ProfileStore, audio: AudioEngine, kv: KVStore,
//         reduceMotion: ReduceMotionSource, onBack: () -> Unit)
//
//     @Composable fun PickerView(
//         variant: PickerVariant, profiles: ProfileStore, audio: AudioEngine,
//         reduceMotion: ReduceMotionSource,
//         onDone: () -> Unit, onCancel: () -> Unit = {})
//
// Three notes on why the shapes differ:
//
//  - HubView takes the `Entitlement` VALUE, not the model. It only reads
//    `trialNotice(…)`; it must not be able to mutate a licence, and taking a
//    value makes that structural rather than a review note.
//  - OnboardingView and PaywallView take the MODEL, because they are the only
//    two screens that change it — `beginTrial`, `purchase`, `restore`. Both
//    MUST call `onDone` / `onLicenseChanged` after any such call, or the shell
//    keeps rendering the previous licence (see the observability note above).
//    Telemetry is theirs too: `trial_started`, `paywall_shown`,
//    `purchase_completed` / `_failed` / `_restored` are the only five events
//    raised outside this file.
//  - WhoIsPlayingView and the first-run PickerView need no completion callback
//    for the GATE: `selectChild` / `createChild` / `chooseSpecies` move the
//    roster, the roster flow recomposes the shell, and the gate falls through
//    by itself. `PickerView.onDone` exists for the second variant, where there
//    is a screen to go back to.
// ===========================================================================

/**
 * A recomposition signal for state the snapshot system cannot see.
 *
 * Two instances exist: one the activity ticks on `ON_RESUME`, and one this file
 * ticks when a screen reports that it changed the licence. A plain class with
 * one `Int` — host-testable, and `@Stable` so Compose can skip on it.
 */
@Stable
class ChangeTicker {

    var ticks: Int by mutableIntStateOf(0)
        private set

    fun tick() {
        ticks += 1
    }
}

/**
 * The resume work, as a plain suspend function so a host test can drive it.
 *
 * INVARIANT 11 — this is the path most likely to throw in the real world (no
 * network, a store that is not installed, a billing adapter that breaks its
 * non-throwing contract), and NOTHING it does may reach the child. Both halves
 * are individually guarded and the failure is swallowed; a device that cannot
 * reach anything keeps playing exactly as it did offline. `CancellationException`
 * is re-thrown, because a cancelled scope is not a failed refresh and structured
 * concurrency must not be silently broken.
 *
 * @return true when both halves completed. The app ignores it; the test does not.
 */
suspend fun resumeRefresh(profiles: ProfileStore, entitlement: EntitlementModel): Boolean {
    var clean = true
    try {
        // Idempotent by construction (sync/Merge.kt), so a double resume cannot
        // double a star. Already swallows transport errors; the catch here is
        // for anything a future transport throws that is not an IOException.
        profiles.syncNow()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        clean = false
    }
    try {
        // Re-check ownership: a purchase made in the store's own UI, a refund,
        // or simply the clock having moved the trial on. `EntitlementModel`
        // never commits paid state on a failed refresh, so a throw here cannot
        // downgrade a paying family — it just leaves the last answer standing.
        entitlement.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        clean = false
    }
    return clean
}

/**
 * `index.css`'s `#root { padding: max(16px, env(safe-area-inset-*)) }`, as one
 * plain function. A safe-area inset never SHRINKS the web's 16 dp gutter, and
 * the two are a max, not a sum — adding them is the mistake this exists to
 * prevent (a gesture-navigation phone would get a 40 dp gutter on a 480 dp card).
 */
fun gutter(safeArea: Dp, floor: Dp = Shell.minimumInset): Dp = maxOf(safeArea, floor)

/**
 * The app. One composable, wired once by `MainActivity` and never re-parented.
 *
 * @param profiles the roster and the ONLY mutation choke point for a profile.
 *   `profiles::award` is also the only thing that may reach an engine's award
 *   slot (invariant 8).
 * @param entitlement the licence state machine. Read here, mutated only by
 *   `OnboardingView` and `PaywallView`.
 * @param telemetry first-party analytics. Two events are this file's:
 *   `trial_expired` and `exercise_started` (`App.tsx:149,152`).
 * @param audio the SFX + voice channel. ONE instance for the app's lifetime,
 *   never one per exercise — invariant 1 forbids paying an engine start on the
 *   tap path.
 * @param kv the key/value primitive, threaded to the shop.
 * @param time the injected clock; drives the miss-cooldown window.
 * @param reduceMotion gates the mascot and the confetti, never press/shake.
 * @param resume ticked by the activity on `ON_RESUME`. Its initial value also
 *   fires the mount sync, so launch and resume share one code path.
 * @param dev the `#stages` / `#vo` stand-in. Resolve it with
 *   `DevScreen.parse(intent.getStringExtra(DevScreen.EXTRA), BuildConfig.DEBUG)`
 *   so it is null in a release build.
 */
@Composable
fun RootView(
    profiles: ProfileStore,
    entitlement: EntitlementModel,
    telemetry: Telemetry,
    audio: AudioEngine,
    kv: KVStore,
    time: TimeSource,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    resume: ChangeTicker = remember { ChangeTicker() },
    dev: DevScreen? = null,
) {
    // `useState<View>({ kind: "hub" })`.
    // The explicit type argument is load-bearing: `mutableStateOf(AppRoute.Hub)`
    // would infer `MutableState<AppRoute.Hub>` — the data OBJECT's own type —
    // and every other route would fail to assign.
    var route: AppRoute by remember { mutableStateOf<AppRoute>(AppRoute.Hub) }
    // `window.location.hash = ""` — the bench's close chip clears the hash.
    var devDismissed by remember { mutableStateOf(false) }
    val license = remember { ChangeTicker() }

    // Mount AND resume, one effect: `resume.ticks` starts at 0, so the first
    // composition runs it too. Never keyed on anything a write can change.
    LaunchedEffect(resume.ticks) {
        resumeRefresh(profiles, entitlement)
        license.tick()
    }

    // The roster is the observable half; reading it here is what subscribes the
    // whole shell to `createChild` / `selectChild` / `switchChild` / `award`.
    val roster by profiles.rosterFlow.collectAsState()
    val profile = remember(roster) { profiles.profile }

    // The licence is the unobservable half — see the file header.
    val tick = license.ticks
    val onboarded = remember(tick) { entitlement.onboarded }
    val licence = remember(tick) { entitlement.entitlement }

    val host = remember(audio, time, profiles) {
        // Engines never compute a point (invariant 8): every award goes through
        // `ProfileStore.award` → `core.rewards.sessionReward`.
        EngineHost(audio = audio, time = time, award = profiles::award)
    }

    val toHub: () -> Unit = { route = AppRoute.Hub }
    val toDashboard: () -> Unit = { route = AppRoute.Dashboard }

    // `App.tsx:147-154`. The only gate a child ever feels: an expired trial
    // blocks STARTING a round and nothing else. `Unknown` plays (invariant 11).
    val open: (ExerciseId, Int) -> Unit = { exercise, level ->
        when (val outcome = openOutcome(exercise, level, licence)) {
            OpenOutcome.Paywall -> {
                telemetry.track(TelemetryEvent.TRIAL_EXPIRED)
                route = AppRoute.Paywall
            }

            is OpenOutcome.Play -> {
                telemetry.track(
                    TelemetryEvent.EXERCISE_STARTED,
                    TelemetryProps(exercise = outcome.exercise, level = outcome.level),
                )
                route = AppRoute.Play(outcome.exercise, outcome.level)
            }
        }
    }

    ShellFrame(modifier) {
        when (
            val gate = shellGate(
                dev = if (devDismissed) null else dev,
                onboarded = onboarded,
                activeId = roster.activeId,
                chosen = profile.chosen,
                route = route,
            )
        ) {
            ShellGate.DevStages -> DevBench(DevScreen.STAGES) { devDismissed = true }

            ShellGate.DevVo -> DevBench(DevScreen.VO) { devDismissed = true }

            // Drives itself through `EntitlementModel`; `beginTrial()` flips
            // `onboarded` and this gate falls through on the next evaluation —
            // which is why `onDone` has to tick the licence.
            ShellGate.Onboarding -> OnboardingView(
                entitlement = entitlement,
                telemetry = telemetry,
                reduceMotion = reduceMotion,
                onDone = license::tick,
            )

            // `selectChild` / `createChild` set `activeId`, and the roster flow
            // recomposes this root, so the gate falls through by itself.
            ShellGate.WhoIsPlaying -> WhoIsPlayingView(
                profiles = profiles,
                reduceMotion = reduceMotion,
            )

            // `chooseSpecies()` flips `profile.chosen`; `onDone` pins the hub —
            // the child is NOT returned to whatever they tapped before.
            ShellGate.FirstRunPicker -> PickerView(
                variant = PickerVariant.FIRST_RUN,
                profiles = profiles,
                audio = audio,
                reduceMotion = reduceMotion,
                onDone = toHub,
            )

            is ShellGate.Screen -> when (val screen = gate.route) {
                AppRoute.Hub -> HubView(
                    profiles = profiles,
                    entitlement = licence,
                    audio = audio,
                    reduceMotion = reduceMotion,
                    onOpen = open,
                    onDashboard = toDashboard,
                    onPaywall = { route = AppRoute.Paywall },
                )

                is AppRoute.Play -> PlayScreen(
                    exercise = screen.exercise,
                    level = screen.level,
                    host = host,
                    profiles = profiles,
                    reduceMotion = reduceMotion,
                    onBack = toHub,
                    onNext = { route = nextRoute(screen) },
                    onMissing = toHub,
                )

                AppRoute.Dashboard -> DashboardView(
                    profiles = profiles,
                    audio = audio,
                    reduceMotion = reduceMotion,
                    onBack = toHub,
                    onShop = { route = AppRoute.Shop },
                    onSwitch = { route = AppRoute.Pick },
                )

                // The shop's back goes to the dashboard, not the hub.
                AppRoute.Shop -> ShopView(
                    profiles = profiles,
                    audio = audio,
                    kv = kv,
                    reduceMotion = reduceMotion,
                    onBack = toDashboard,
                )

                // `onDone` AND `onCancel` both land on the dashboard.
                AppRoute.Pick -> PickerView(
                    variant = PickerVariant.SWITCH,
                    profiles = profiles,
                    audio = audio,
                    reduceMotion = reduceMotion,
                    onDone = toDashboard,
                    onCancel = toDashboard,
                )

                AppRoute.Paywall -> PaywallView(
                    entitlement = entitlement,
                    telemetry = telemetry,
                    onBack = toHub,
                    onLicenseChanged = license::tick,
                )
            }
        }
    }
}

// --- The card shell ----------------------------------------------------------

/**
 * `index.css` `#root`: the page mat, the card top-centred, capped at 480 dp,
 * with a gutter that is the max of 16 dp and the safe area.
 *
 * The `BoxWithConstraints` is legitimate HERE and only here: `LocalViewportWidth`
 * is the `vw` basis for every `fluid()` call and must be the WINDOW width, which
 * is what this box measures because it sits above the 480 dp cap. `Fluid.kt`'s
 * warning is about wrapping the card, not the window. It is also not a scroller,
 * so it competes for no pointer event (invariant 1).
 */
@Composable
private fun ShellFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.page.color),
    ) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = gutter(insets.calculateStartPadding(direction)),
            top = gutter(insets.calculateTopPadding()),
            end = gutter(insets.calculateEndPadding(direction)),
            bottom = gutter(insets.calculateBottomPadding()),
        )
        CompositionLocalProvider(LocalViewportWidth provides maxWidth) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = Shell.cardMaxWidth)
                    .fillMaxWidth()
                    .padding(padding),
            ) {
                content()
            }
        }
    }
}

// --- The exercise dispatch (screens/Router.kt's `engineFor`) -----------------

/**
 * One catalog row, at one level, as one of nine engines.
 *
 * The `key(remountKey)` is React's ``key={`${exercise}-${level}`}`` — the
 * REMOUNT. Every level change tears the subtree down: fresh seeding, fresh
 * audio, fresh star array. Each engine also `remember`s on its own inputs, so
 * this is belt to their braces; omit it and a router bug becomes "level 2
 * replays level 1's words", which looks like a content bug and is not.
 *
 * INVARIANT 5. There is no precondition anywhere below — no ledger read, no
 * "previous level cleared" check, no clamp on [level]. Any level of any row is
 * one tap away, always.
 *
 * @param onMissing the catalog does not contain [exercise]. Unreachable
 *   (`HubCatalogTest` pins all 17 rows), and the TSX would crash here reading
 *   `meta.levelCount`; a hub bounce beats a crash in a six-year-old's app.
 */
@Composable
private fun PlayScreen(
    exercise: ExerciseId,
    level: Int,
    host: EngineHost,
    profiles: ProfileStore,
    reduceMotion: ReduceMotionSource,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onMissing: () -> Unit,
) {
    val meta = EXERCISES.firstOrNull { it.id == exercise }
    if (meta == null) {
        LaunchedEffect(exercise) { onMissing() }
        return
    }
    val mascot = profiles.profile.config

    key(AppRoute.Play(exercise, level).remountKey) {
        when (val engine = engineFor(meta)) {
            ExerciseEngine.ReadImage -> ReadImageView(
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            ExerciseEngine.SpellSound -> SpellSoundScreen(
                level = level,
                mascot = mascot,
                host = host,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            ExerciseEngine.FindSound -> FindSoundView(
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            ExerciseEngine.SoundTwins -> SoundTwinsView(
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            is ExerciseEngine.SyllableGrid -> SyllableGridView(
                exercise = exercise,
                mode = engine.mode,
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            is ExerciseEngine.SpellSyllable -> SpellSyllableScreen(
                exercise = exercise,
                mode = engine.mode,
                level = level,
                mixed = engine.mixed,
                mascot = mascot,
                host = host,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            is ExerciseEngine.LetterMatch -> LetterMatchView(
                exercise = exercise,
                kind = engine.kind,
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            is ExerciseEngine.Assemble -> AssembleView(
                exercise = exercise,
                mode = engine.mode,
                level = level,
                mascot = mascot,
                host = host,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )

            ExerciseEngine.FirstLetter -> FirstLetterView(
                level = level,
                host = host,
                mascot = mascot,
                reduceMotion = reduceMotion,
                onBack = onBack,
                onNext = onNext,
            )
        }
    }
}

// --- Dev benches -------------------------------------------------------------

/**
 * The benches themselves (`src/dev/`) are not ported — see `DevScreen`'s doc.
 * A placeholder so the route stays walkable, deliberately in English: it is
 * developer text, and `Copy.kt` owns every French string a child or a parent can
 * see. Unreachable in a release build, where `DevScreen.parse(…, false)` is null.
 */
@Composable
private fun DevBench(screen: DevScreen, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Shell.minimumScreenHeight)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(text = "#" + screen.wire, style = Typography.style(size = 24.dp))
        BasicText(
            text = "Dev bench not ported - see ui/screens/Router.kt",
            style = Typography.style(size = 14.dp),
        )
        // `touchDown`, not `clickable` — A12 makes that a module-wide rule, and
        // a debug placeholder is not a reason to introduce the one API the
        // source scan exists to keep out.
        Box(modifier = Modifier.touchDown { onClose() }) {
            BasicText(text = "Close", style = Typography.style(size = 18.dp))
        }
    }
}
