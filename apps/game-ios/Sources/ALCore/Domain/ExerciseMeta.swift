// Port of `src/types.ts` — the hub catalog row and the app router's view union.

/// One row of the hub catalog (`Levels.exercises`). The array's ORDER is the hub
/// order and is behaviour; see data-core.md §4.7.
public struct ExerciseMeta: Hashable, Sendable {
    public var id: ExerciseId
    public var name: String
    public var emoji: String
    public var levelCount: Int
    /// Reward weight (0 = training: the curve, never a bonus). Required: every new exercise
    /// must place itself in the economy, same deal as its ExerciseIcon.
    public var difficulty: Difficulty
    /// Extra hub chip when the name alone doesn't say what to do (parent-facing).
    public var hint: String?
    /// Syllable exercises carry the seeding mode; first-letter leaves it undefined.
    public var mode: SyllableMode?
    /// Syllable-grid drills carry which side of the grid they ask; others leave it undefined.
    public var grid: SyllableGridMode?
    /// Fill-a-syllable siblings carry which letter mode they run; others leave it undefined.
    public var spell: SpellSyllableMode?
    /**
     * Fill-a-syllable "écritures mêlées" twins: the word shows in ONE of three
     * writings (grande / petite / attachée) and the tray mixes forms, so the child
     * must pick each letter in the right case AND script. Plain siblings leave it off.
     */
    // NB: `mixed?: boolean` in TS, but the data only ever writes `mixed: true`
    // and every read is a truthy test — so a non-optional `Bool` defaulting to
    // false is exactly faithful, without a pointless `Bool?`.
    public var mixed: Bool
    /// Letter-form matching exercises carry which form they flip; others leave it undefined.
    public var match: LetterMatchKind?

    public init(
        id: ExerciseId,
        name: String,
        emoji: String,
        levelCount: Int,
        difficulty: Difficulty,
        hint: String? = nil,
        mode: SyllableMode? = nil,
        grid: SyllableGridMode? = nil,
        spell: SpellSyllableMode? = nil,
        mixed: Bool = false,
        match: LetterMatchKind? = nil
    ) {
        self.id = id
        self.name = name
        self.emoji = emoji
        self.levelCount = levelCount
        self.difficulty = difficulty
        self.hint = hint
        self.mode = mode
        self.grid = grid
        self.spell = spell
        self.mixed = mixed
        self.match = match
    }
}

/// The app router's one piece of state (TS `type View`). A discriminated union
/// there, an enum with associated values here — the exhaustive switch is the
/// point, so do NOT flatten it into a struct with optional fields.
public enum AppRoute: Hashable, Sendable {
    case hub
    case play(exercise: ExerciseId, level: Int)
    case dashboard
    case shop
    case pick
    /// Trial over. Reached only by tapping an exercise — never a startup wall.
    case paywall
}

// NB: `data-core.md` calls this type `AppView` (its TS name is `View`);
// ARCHITECTURE.md §2/§6.4 calls it `AppRoute` and ARCHITECTURE wins. The alias
// exists so a call site written against either spelling compiles.
public typealias AppView = AppRoute
