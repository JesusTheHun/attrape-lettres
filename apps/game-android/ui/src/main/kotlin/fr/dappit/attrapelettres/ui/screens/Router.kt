package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.domain.AppRoute
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.licensing.Entitlement
import fr.dappit.attrapelettres.core.licensing.canPlay

// ===========================================================================
// The routing half of `src/App.tsx`, as PLAIN VALUES.
//
// Every rule the view router applies lives here so `./gradlew :ui:test` can walk
// the whole table without a composition (A11: a host test cannot invoke a
// @Composable, so every decision a screen makes has to live in a plain function
// a test drives directly). `ui/RootView.kt` is the renderer and holds no rule.
//
// Four rules, all load-bearing and all ported verbatim from the TSX:
//
//  1. The GATES are an ordered if-chain, not a `when` on the route. A stored
//     route of `Play(...)` is silently overridden while a gate holds — that is
//     what lets `switchChild()` land on « Qui joue ? » from anywhere.
//  2. The exercise DISPATCH is four id checks, then four capability checks, in
//     that order (`App.tsx:89-121`). See [engineFor].
//  3. « Suivant » walks levels, then rolls into level 1 of the NEXT exercise,
//     and past the final exercise back to the hub (`App.tsx:82-87`).
//  4. Opening a level is the ONE place an expired trial ever says no
//     (`App.tsx:147-154`), and `canPlay` is a blacklist of one so an
//     unanswered store still plays (invariant 11).
//
// WHY THE DISPATCH IS HERE AND NOT INSIDE THE @Composable. It used to exist
// only as a hand-written copy inside `EconomyE2ETest.playCatalogRow`, which
// proved all 17 catalog rows reach the right engine and bank under the right
// `ledgerKey` — while nothing forced that copy and the shipping router to
// agree. A child's stars landing under the wrong key is silent, permanent and
// invisible to every other suite. The test now calls [engineFor]; do not
// re-inline this logic into a composable, because that instantly puts the proof
// back out of the host test's reach.
// ===========================================================================

// --- Dev benches (`#stages` / `#vo`) ----------------------------------------

/**
 * The two dev benches, reachable on the web at `window.location.hash ===
 * "#stages"` / `"#vo"` — before EVERY gate, including onboarding
 * (`App.tsx:54-58`).
 *
 * Android has no URL hash. The stand-in is an Intent extra
 * (`adb shell am start -e alDevScreen stages …`), resolved once at the root and
 * null unless the caller says the build is debuggable, which keeps both benches
 * out of any child's flow. `:ui` never touches `android.content.Intent`: the
 * activity reads the extra and hands over a plain `String?`, so [parse] stays
 * host-testable.
 *
 * NB the benches themselves (`src/dev/MascotGallery`, `src/dev/VoGallery`) are
 * not ported — no work package owns them, and `Copy.kt`'s header excludes
 * `src/dev/` deliberately. The ROUTE exists and is tested; the screen behind it
 * is a placeholder in `RootView` until someone ports the benches.
 */
enum class DevScreen(val wire: String) {
    STAGES("stages"),
    VO("vo"),
    ;

    companion object {

        /** The Intent extra key the activity reads. */
        const val EXTRA: String = "alDevScreen"

        /**
         * Unknown values, a missing extra and a dangling flag all resolve to
         * null — a typo must not strand the app on a blank screen.
         */
        fun parse(value: String?): DevScreen? = entries.firstOrNull { it.wire == value }

        /**
         * [parse], gated on the build. The activity passes `BuildConfig.DEBUG`;
         * a release build therefore has no dev screens at all, exactly as "out
         * of kid flow" intends.
         */
        fun parse(value: String?, debuggable: Boolean): DevScreen? =
            if (debuggable) parse(value) else null
    }
}

// --- The gates, in order -----------------------------------------------------

/**
 * What the root renders this frame. One value; `RootView`'s body is a `when`
 * over it and nothing else.
 */
sealed interface ShellGate {

    data object DevStages : ShellGate

    data object DevVo : ShellGate

    /**
     * `!onboarded` — the parent screen, once per device, before anything else.
     * App Review 3.1.1 wants the trial's terms shown BEFORE the trial starts,
     * and GDPR wants the analytics choice made by an adult; both happen there.
     */
    data object Onboarding : ShellGate

    /** `!activeId` — « Qui joue ? ». Siblings share the device. */
    data object WhoIsPlaying : ShellGate

    /**
     * `!profile.chosen` — until the active child picks a friend, the Picker is
     * the whole app.
     */
    data object FirstRunPicker : ShellGate

    /** Every gate passed: render the routed screen. */
    data class Screen(val route: AppRoute) : ShellGate
}

/**
 * `App.tsx`'s ordered early returns, lines 54-73. The order IS behaviour:
 *
 *  - the dev benches win over everything, including "no active player";
 *  - onboarding sits above the roster — a fresh device meets the parent first;
 *  - the roster and species gates sit ABOVE the play branch, so a [AppRoute.Play]
 *    route is silently overridden if the active child disappears or has not
 *    chosen a species. Do not reorder for tidiness.
 *
 * INVARIANT 5 lives in what is NOT here. There is no level argument, no ledger
 * read, no "finish level 2 first": the gates ask who is playing and whether a
 * grown-up has seen the terms, and nothing else. A gate that consulted progress
 * would be the lock this game refuses to have.
 *
 * INVARIANT 11 lives in what is NOT here either. No branch consults the
 * entitlement. An expired trial, an unreachable store and a flat network all
 * reach [ShellGate.Screen] and the child lands on the hub with every star
 * intact; the single place money is allowed to say no is [openOutcome].
 */
fun shellGate(
    dev: DevScreen?,
    onboarded: Boolean,
    activeId: String?,
    chosen: Boolean,
    route: AppRoute,
): ShellGate {
    if (dev == DevScreen.STAGES) return ShellGate.DevStages
    if (dev == DevScreen.VO) return ShellGate.DevVo
    if (!onboarded) return ShellGate.Onboarding
    if (activeId == null) return ShellGate.WhoIsPlaying
    if (!chosen) return ShellGate.FirstRunPicker
    return ShellGate.Screen(route)
}

// --- Opening an exercise -----------------------------------------------------

/**
 * What tapping a level does (`App.tsx:147-154`). The ONLY thing an expired
 * trial blocks is starting a new round — the hub, the mascot, the shop and
 * every star stay exactly where they were (invariant 3: nothing is ever taken
 * away from a child as a consequence).
 */
sealed interface OpenOutcome {

    /** `track("trial_expired")`, then the paywall. */
    data object Paywall : OpenOutcome

    /** `track("exercise_started", { exercise, level })`, then the run. */
    data class Play(val exercise: ExerciseId, val level: Int) : OpenOutcome
}

/**
 * INVARIANT 11, at its one decision point. `canPlay` is `:core`'s blacklist of
 * one — `Expired` is the only state that diverts, so `Unknown` PLAYS. A store
 * that has not answered, a receipt check that timed out and a device with no
 * network all land on [OpenOutcome.Play].
 *
 * There is no `level` validation here on purpose (invariant 5): every level of
 * every catalog row is one tap away, always, and this function's only question
 * is about money.
 */
fun openOutcome(exercise: ExerciseId, level: Int, entitlement: Entitlement): OpenOutcome =
    if (canPlay(entitlement)) OpenOutcome.Play(exercise, level) else OpenOutcome.Paywall

// --- The exercise dispatch ---------------------------------------------------

/**
 * The eight-branch dispatch of `App.tsx:89-121`, as data. `RootView` maps each
 * case onto its view; the nine views' uniform `host` / `mascot` / `reduceMotion`
 * / `onBack` / `onNext` surface is what makes that mapping a plain `when`.
 */
sealed interface ExerciseEngine {

    data object ReadImage : ExerciseEngine

    data object SpellSound : ExerciseEngine

    data object FindSound : ExerciseEngine

    data object SoundTwins : ExerciseEngine

    data class SyllableGrid(val mode: SyllableGridMode) : ExerciseEngine

    data class SpellSyllable(val mode: SpellSyllableMode, val mixed: Boolean) : ExerciseEngine

    data class LetterMatch(val kind: LetterMatchKind) : ExerciseEngine

    data class Assemble(val mode: SyllableMode) : ExerciseEngine

    data object FirstLetter : ExerciseEngine
}

/**
 * Four id checks FIRST, then four capability checks — the TSX order, ported
 * exactly (`App.tsx:89-121`).
 *
 * WHY THE ORDER IS PRESERVED THOUGH NOTHING TODAY DEPENDS ON IT. The four rows
 * the id branches catch — `read-image`, `spell-sound`, `find-sound`,
 * `sound-twins` — carry NONE of `grid` / `spell` / `match` / `mode` in
 * `EXERCISES`, so today the four id checks and the four capability checks
 * cannot both match one row and the order is unobservable. That is precisely
 * why it is written down: a future row that grew a capability field would
 * quietly change engine if somebody "simplified" this into capability-first, or
 * into a single `when (meta.id)`. The order is cheap; discovering the change is
 * not.
 *
 * The `else` on the id `when` is the faithful port of four `if`s over the id and
 * carries no invariant — the exhaustive-`when` rule is invariant 7's, in
 * `:art`'s `ExerciseIconCatalog`, not here.
 */
fun engineFor(meta: ExerciseMeta): ExerciseEngine {
    when (meta.id) {
        ExerciseId.READ_IMAGE -> return ExerciseEngine.ReadImage
        ExerciseId.SPELL_SOUND -> return ExerciseEngine.SpellSound
        ExerciseId.FIND_SOUND -> return ExerciseEngine.FindSound
        ExerciseId.SOUND_TWINS -> return ExerciseEngine.SoundTwins
        else -> Unit
    }
    val grid = meta.grid
    if (grid != null) return ExerciseEngine.SyllableGrid(grid)
    val spell = meta.spell
    if (spell != null) return ExerciseEngine.SpellSyllable(spell, meta.mixed)
    val match = meta.match
    if (match != null) return ExerciseEngine.LetterMatch(match)
    val mode = meta.mode
    if (mode != null) return ExerciseEngine.Assemble(mode)
    return ExerciseEngine.FirstLetter
}

// --- « Suivant » -------------------------------------------------------------

/**
 * `App.tsx:82-87` — the roll-over. Past an exercise's last level it advances to
 * level 1 of the NEXT catalog row, and past the final row back to the hub.
 *
 * [AppRoute.Hub] covers two branches the TSX cannot take: a non-play route (its
 * `next` closure only exists inside the play branch) and an exercise id missing
 * from the catalog, where `EXERCISES.findIndex` returns −1 and the TSX would
 * crash reading `meta.levelCount`. A hub bounce beats a crash in a six-year-
 * old's app — a recorded divergence in an unreachable path.
 */
fun nextRoute(after: AppRoute, catalog: List<ExerciseMeta> = EXERCISES): AppRoute {
    if (after !is AppRoute.Play) return AppRoute.Hub
    val index = catalog.indexOfFirst { it.id == after.exercise }
    if (index < 0) return AppRoute.Hub
    val meta = catalog[index]
    if (after.level < meta.levelCount) {
        return AppRoute.Play(after.exercise, after.level + 1)
    }
    if (index + 1 < catalog.size) {
        return AppRoute.Play(catalog[index + 1].id, 1)
    }
    return AppRoute.Hub
}

// --- The remount key ---------------------------------------------------------

/**
 * The port of React's ``key={`${exercise}-${level}`}`` (`App.tsx:88`).
 *
 * Applied as `key(route.remountKey) { … }` around the exercise subtree, it tears
 * the composition down and rebuilds it — fresh `remember`, fresh seeding, fresh
 * star array — on every level change, including the roll-over into the same
 * engine type. Omitting it is silent: level 2 replays level 1's words.
 *
 * Non-play routes share one key, exactly as the TSX only ever keys the play
 * branch.
 */
val AppRoute.remountKey: String
    get() = if (this is AppRoute.Play) "${exercise.wire}-$level" else "route"

// --- The species picker's two faces ------------------------------------------

/**
 * `Picker`'s `variant` prop (`shop/Picker.tsx`). It lives here rather than with
 * the screen because the ROUTER chooses it: [ShellGate.FirstRunPicker] is
 * `first-run` (the picker IS the app, no way back), [AppRoute.Pick] is `switch`
 * (reached from the dashboard, cancellable).
 */
enum class PickerVariant { FIRST_RUN, SWITCH }
