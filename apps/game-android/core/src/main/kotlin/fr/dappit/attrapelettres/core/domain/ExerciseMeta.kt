package fr.dappit.attrapelettres.core.domain

// Port of `src/types.ts` — the hub catalog row and the app router's view union.

/**
 * One row of the hub catalog (`EXERCISES` in the web's levels.ts). The list's
 * ORDER is the hub order and is behaviour: the reward gradient is monotone with
 * it (invariant 8), so a row's position places it in the economy.
 */
data class ExerciseMeta(
    val id: ExerciseId,
    val name: String,
    val emoji: String,
    val levelCount: Int,
    /**
     * Reward weight (0 = training: the curve, never a bonus). Required: every new
     * exercise must place itself in the economy, same deal as its ExerciseIcon.
     */
    val difficulty: Difficulty,
    /** Extra hub chip when the name alone doesn't say what to do (parent-facing). */
    val hint: String? = null,
    /** Syllable exercises carry the seeding mode; first-letter leaves it undefined. */
    val mode: SyllableMode? = null,
    /** Syllable-grid drills carry which side of the grid they ask; others leave it undefined. */
    val grid: SyllableGridMode? = null,
    /** Fill-a-syllable siblings carry which letter mode they run; others leave it undefined. */
    val spell: SpellSyllableMode? = null,
    /**
     * Fill-a-syllable "écritures mêlées" twins: the word shows in ONE of three
     * writings (grande / petite / attachée) and the tray mixes forms, so the child
     * must pick each letter in the right case AND script. Plain siblings leave it off.
     */
    // NB: `mixed?: boolean` in TS, but the data only ever writes `mixed: true`
    // and every read is a truthy test — so a non-nullable `Boolean` defaulting to
    // false is exactly faithful, without a pointless `Boolean?`.
    val mixed: Boolean = false,
    /** Letter-form matching exercises carry which form they flip; others leave it undefined. */
    val match: LetterMatchKind? = null,
)

/**
 * The app router's one piece of state (TS `type View`). A discriminated union
 * there, a sealed hierarchy here — the exhaustive `when` is the point, so do
 * NOT flatten it into a class with optional fields. It lives in `:core` so the
 * routing logic is host-testable (ARCHITECTURE.md §2).
 */
sealed interface AppRoute {
    data object Hub : AppRoute

    data class Play(val exercise: ExerciseId, val level: Int) : AppRoute

    data object Dashboard : AppRoute

    data object Shop : AppRoute

    data object Pick : AppRoute

    /** Trial over. Reached only by tapping an exercise — never a startup wall. */
    data object Paywall : AppRoute
}
